#!/usr/bin/env python3
"""Host checks for the recovery diagnostics core (src/companion/diag_core.h) behind `diag`.

Compiles the Arduino-free header with a C++ driver: saturating counters, ERR status buckets,
rejected-frame kinds, reset reason names, the single zero-initialized counter instance and the
`link` key list/order. The key list is compared with proto/PROTOCOL.md and with the mock device
(tools/mock_device.py). Source checks confirm where the firmware increments each counter, that
CAPS advertises features.diag and that the existing FM/mic drop counters are reused, not copied.
This does not validate esp_reset_reason(), heap figures or anything on real hardware.
Run: python3 tools/test_firmware_diag.py
"""
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "src/companion"
sys.path.insert(0, str(ROOT / "tools"))

DRIVER = r'''
#include "diag_core.h"
#include <cstdio>
#include <cstring>
using namespace brucia::companion;

static int failures = 0, checks = 0;
static void check(bool ok, const char *what) {
    checks++;
    if (!ok) { failures++; std::printf("FAIL: %s\n", what); }
}

struct PrintSink {
    void field(const char *key, uint32_t v) { std::printf("link %s %u\n", key, (unsigned)v); }
    void errField(const char *key, uint32_t v) { std::printf("err %s %u\n", key, (unsigned)v); }
};

int main() {
    {   // saturation: never wraps back to a small value
        uint32_t c = UINT32_MAX - 1;
        diagInc(c); check(c == UINT32_MAX, "increments to max");
        diagInc(c); check(c == UINT32_MAX, "saturates at max");
        uint32_t z = 0; diagInc(z); check(z == 1, "plain increment");
    }
    {   // ERR status buckets
        check(diagErrBucket(400) == DIAG_ERR_400 && diagErrBucket(404) == DIAG_ERR_404, "400/404");
        check(diagErrBucket(409) == DIAG_ERR_409 && diagErrBucket(413) == DIAG_ERR_413, "409/413");
        check(diagErrBucket(401) == DIAG_ERR_4XX && diagErrBucket(499) == DIAG_ERR_4XX, "other 4xx");
        check(diagErrBucket(500) == DIAG_ERR_5XX && diagErrBucket(501) == DIAG_ERR_5XX &&
              diagErrBucket(503) == DIAG_ERR_5XX && diagErrBucket(599) == DIAG_ERR_5XX, "5xx");
        check(diagErrBucket(0) == DIAG_ERR_OTHER && diagErrBucket(200) == DIAG_ERR_OTHER &&
              diagErrBucket(399) == DIAG_ERR_OTHER && diagErrBucket(600) == DIAG_ERR_OTHER &&
              diagErrBucket(UINT32_MAX) == DIAG_ERR_OTHER, "out of range codes");
        check(std::strcmp(diagErrBucketKey(DIAG_ERR_BUCKETS), "other") == 0 &&
              std::strcmp(diagErrBucketKey(255), "other") == 0, "bucket key bounds");
        check(std::strcmp(diagRxRejectKey(DIAG_RX_REJECT_KINDS), "rx_other") == 0, "reject key bounds");
    }
    {   // reset reason names (ESP-IDF 5.x esp_reset_reason_t numbering)
        const char *want[] = {"unknown", "poweron", "ext", "sw", "panic", "int_wdt", "task_wdt", "wdt",
                              "deepsleep", "brownout", "sdio", "usb", "jtag", "efuse", "pwr_glitch", "cpu_lockup"};
        bool all = true;
        for (int i = 0; i < 16; i++) all = all && std::strcmp(diagResetReasonName(i), want[i]) == 0;
        check(all, "reset names 0..15");
        check(std::strcmp(diagResetReasonName(-1), "other") == 0 && std::strcmp(diagResetReasonName(16), "other") == 0 &&
              std::strcmp(diagResetReasonName(1000), "other") == 0, "unknown reset codes map to other");
    }
    {   // one zero-initialized instance; note helpers bump exactly one counter
        DiagLinkCounters &a = diagLink();
        check(&a == &diagLink(), "single instance");
        bool zero = a.sessions == 0 && a.rxFrames == 0 && a.txFrames == 0 && a.txTorn == 0 && a.busy409 == 0;
        for (int k = 0; k < DIAG_RX_REJECT_KINDS; k++) zero = zero && a.rxRejected[k] == 0;
        for (int b = 0; b < DIAG_ERR_BUCKETS; b++) zero = zero && a.err[b] == 0;
        check(zero, "zero at boot");
        diagNoteSession(); diagNoteRxFrame(); diagNoteRxFrame(); diagNoteTxFrame(); diagNoteTxTorn(); diagNoteBusy();
        diagNoteRxReject(DIAG_RX_BAD_JSON); diagNoteRxReject(DIAG_RX_REJECT_KINDS); // invalid kind ignored
        diagNoteErr(409); diagNoteErr(409); diagNoteErr(503); diagNoteErr(7);
        check(a.sessions == 1 && a.rxFrames == 2 && a.txFrames == 1 && a.txTorn == 1 && a.busy409 == 1, "link counts");
        check(a.rxRejected[DIAG_RX_BAD_JSON] == 1 && a.rxRejected[DIAG_RX_OVERSIZE] == 0, "reject counts");
        check(a.err[DIAG_ERR_409] == 2 && a.err[DIAG_ERR_5XX] == 1 && a.err[DIAG_ERR_OTHER] == 1 &&
              a.err[DIAG_ERR_400] == 0, "err counts");
        a.err[DIAG_ERR_400] = UINT32_MAX;
        diagNoteErr(400);
        check(a.err[DIAG_ERR_400] == UINT32_MAX, "err bucket saturates");
        a.err[DIAG_ERR_400] = 0;
    }
    check(sizeof(DiagLinkCounters) == 4 * (5 + DIAG_RX_REJECT_KINDS + DIAG_ERR_BUCKETS), "fixed size, no padding");
    PrintSink sink;
    diagWriteLink(diagLink(), sink);
    std::printf("%d/%d checks passed\n", checks - failures, checks);
    return failures ? 1 : 0;
}
'''

LINK_KEYS = ["sessions", "rx_frames", "tx_frames", "tx_torn", "rx_zero_len", "rx_oversize", "rx_alloc_fail",
             "rx_stale", "rx_bad_json", "busy_409"]
ERR_KEYS = ["400", "404", "409", "413", "4xx", "5xx", "other"]


def run_core():
    with tempfile.TemporaryDirectory(prefix="brucia-diag-") as tmp:
        cpp = Path(tmp) / "diag.cpp"
        exe = Path(tmp) / "diag"
        cpp.write_text(DRIVER)
        subprocess.run([os.environ.get("CXX", "c++"), "-std=c++17", "-Wall", "-Wextra", "-Werror",
                        "-I", str(CORE), str(cpp), "-o", str(exe)], check=True)
        res = subprocess.run([str(exe)], capture_output=True, text=True)
        print(res.stdout.strip().splitlines()[-1] if res.stdout.strip() else "")
        if res.returncode:
            print(res.stdout)
            raise SystemExit("diag core checks failed")
        return res.stdout


def source_checks():
    failures = []

    def need(cond, what):
        if not cond:
            failures.append(what)

    core = (CORE / "diag_core.h").read_text()
    need(not re.search(r"\b(new|malloc|String|std::)\b|#include <(string|vector|map)", core),
         "diag_core.h must stay allocation-free and Arduino-free")
    proto = (CORE / "protocol.cpp").read_text()
    read = proto[proto.index("bool readFrame"):proto.index("static void writeFrame")]
    need("diagNoteRxFrame()" in read and "DIAG_RX_STALE" in read and "DIAG_RX_OVERSIZE" in read
         and "DIAG_RX_ZERO_LEN" in read and "DIAG_RX_ALLOC_FAIL" in read, "readFrame counts frames/rejects")
    write = proto[proto.index("static void writeFrame"):proto.index("void beginEnvelope")]
    need("diagNoteTxFrame()" in write, "writeFrame counts sent frames")
    env = proto[proto.index("void sendEnvelope"):proto.index("void sendAck")]
    need("diagNoteErr(" in env and env.index("diagNoteErr(") > env.index("response exceeds frame limit"),
         "sendEnvelope buckets every ERR after the 413 substitution")
    comp = (CORE / "companion.cpp").read_text()
    poll = comp[comp.index("bool companionPollStop"):comp.index("static void runCompanionUSB")]
    need("DIAG_RX_BAD_JSON" in poll and poll.index("diagNoteBusy()") < poll.index('sendErr(id, 409, "device busy")'),
         "companionPollStop counts bad JSON and busy 409")
    sub = poll.find("if (t == MSG_SUB) {")
    need(sub > 0 and "sendErr(id, 409, \"device busy\")" in poll[sub:sub + 400] and sub < poll.index("if (t != MSG_CMD)"),
         "companionPollStop refuses a SUB with 409 instead of dropping it")
    loop = comp[comp.index("static void runCompanionUSB"):]
    need("diagNoteSession()" in loop and loop.index("DIAG_RX_BAD_JSON") < loop.index('"bad json"'),
         "service loop counts sessions and bad JSON")
    need('d["features"]["diag"] = true' in (CORE / "capabilities.cpp").read_text(), "CAPS advertises features.diag")
    need('cmdStr == "diag"' in (CORE / "system.cpp").read_text(), "system dispatcher routes diag")
    diag = (CORE / "diag.cpp").read_text()
    need("const DiagLinkCounters link = diagLink();" in diag and "memset" not in diag and "= {}" not in diag,
         "diag reply snapshots counters and never resets them")
    audio = (CORE / "audio.cpp").read_text()
    acc = audio[audio.index("bool fmAudioLostSamples"):]
    need("lost = fmLostSamples;" in acc and "total - micPcmDropBase" in acc,
         "drop figures reuse the existing FM lost / mic dropped counters")
    need(audio.count("static uint32_t fmLostSamples") == 1, "no duplicated FM loss counter")
    return failures


def contract_checks(output):
    failures = []
    link = [l.split()[1] for l in output.splitlines() if l.startswith("link ")]
    err = [l.split()[1] for l in output.splitlines() if l.startswith("err ")]
    if link != LINK_KEYS:
        failures.append(f"core link keys {link} != {LINK_KEYS}")
    if err != ERR_KEYS:
        failures.append(f"core err keys {err} != {ERR_KEYS}")
    from mock_device import LinkCounters, diag_reply
    mock = diag_reply(LinkCounters(), 0)
    if list(mock["link"]) != ["err"] + LINK_KEYS or list(mock["link"]["err"]) != ERR_KEYS:
        failures.append("mock_device diag link keys differ from the firmware core")
    doc = (ROOT / "proto/PROTOCOL.md").read_text()
    section = doc[doc.index("### Recovery diagnostics"):]
    section = section[:section.index("\n## ")] if "\n## " in section else section
    for key in LINK_KEYS + ["reset", "reset_code", "uptime_s", "heap", "psram", "drops", "fm_pcm_lost",
                            "mic_pcm_dropped"]:
        if f"`{key}`" not in section:
            failures.append(f"PROTOCOL.md diag section does not document `{key}`")
    if "`features.diag: true`" not in section:
        failures.append("PROTOCOL.md diag section does not name features.diag")
    for key in ERR_KEYS:
        if f'"{key}"' not in section:
            failures.append(f'PROTOCOL.md diag section does not list ERR bucket "{key}"')
    return failures


def main():
    output = run_core()
    failures = source_checks() + contract_checks(output)
    for f in failures:
        print("FAIL:", f)
    if failures:
        raise SystemExit(1)
    print("Firmware diag PASS")


if __name__ == "__main__":
    main()
