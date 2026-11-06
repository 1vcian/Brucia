#!/usr/bin/env python3
"""Radio-feature audit guards (docs/audit/radio.md): ownership, bounded waits, honest results.

Firmware is not built here (no PlatformIO in this test); each guard is pinned by
  - a host-native compile of the pure header it uses (src/companion/subghz_busy.h), with a mutation
    check that the classification is load-bearing, and
  - static source checks in the style of tools/test_companion_prompts.py / test_firmware_ble_adv.py
    (ordering of the guard before the radio call, no menu/unbounded wait in companion mode), and
  - protocol-level checks against tools/mock_device.py, which mirrors the firmware behavior.

Covered:
  RF   CC1101 single owner: one-shot radio commands refused with 409 while subghz.raw/spectrum run.
  LoRa lora_recv polls the app's `stop` (PING answered meanwhile); the app sends it on Stop.
  Link the 60 s app-silence exit is measured from the end of a handled frame (a ~60 s bounded
       command no longer drops the device out of companion mode right after its ACK).
  BLE  ble.scan refuses while a BLE HID session owns the stack (409) and reports init failure
       (503); ble_media refuses while the scan runs (its deinit freed the scanner).
  BadUSB `badusb run_from_*` refused in companion mode (USB port is the app link).
  IR   TX handlers refuse when the TX pin is not in IR_TX_PINS instead of opening the pin menu;
       `ir tx` rejects a protocol sendIRCommand ignores (was a false success).
  NFC  nfc_emv_read waits a bounded time and polls `stop`; a cancelled wait is never "parsed".
  ESP-NOW espnow_send keeps the Wi-Fi owner (no forced STA / disconnect).
Batch 18 (open items of docs/audit/radio.md):
  RF   the CC1101 classifier is case-insensitive and checks every newline/";;" segment (SimpleCLI
       matches names in any case and splits on them); subghz_tx_raw / subghz_replay ACK ok only
       when something was transmitted (sendRfCommand / rf_raw_emit report it).
  IR   TV-B-Gone and the IR jammer answer ok:false when 0 codes / packets were sent.
  BLE  ble_ibeacon refuses a malformed UUID (host-compiled ibeacon_uuid.h); ble_hid_begin checks
       the stack init and that the HID advertiser is running before answering "advertising".
  NFC  nfc_ndef_push contact refuses a vCard record over the PN532 126-byte NDEF limit before the
       reader is started (host-compiled ndef_contact.h, mirrored by the app check).

Run: python3 tools/test_radio_guards.py
"""
import os
import pty
import re
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from mock_device import ACK, ERR, PING, PONG, SUB, MockDevice, subghz_needs_radio  # noqa: E402

CXX = os.environ.get("CXX", "c++")
SRC = ROOT / "src"


def read(rel):
    return (ROOT / rel).read_text()


def strip_comments(text):
    """Drop // and /* */ comments, leaving string and char literals intact."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c in "\"'":
            j = i + 1
            while j < n and text[j] != c:
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        elif text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def body_of(src, signature_regex):
    """Brace-matched body of the first function whose signature matches signature_regex."""
    m = re.search(signature_regex + r"[^{;]*\{", src)
    if not m:
        raise AssertionError(f"function not found: {signature_regex}")
    depth, i = 1, m.end()
    while depth:
        depth += {"{": 1, "}": -1}.get(src[i], 0)
        i += 1
    return src[m.end():i - 1]


# --- host-native: the CC1101 command classifier ---------------------------------------------------
BUSY_DRIVER = r'''
#include "subghz_busy.h"
#include <cstdio>
using brucia::companion::subghzCommandNeedsRadio;
static int failures = 0;
static void expect(bool want, const char *cmd) {
    if (subghzCommandNeedsRadio(cmd) != want) { failures++; std::printf("FAIL: %s -> %d\n", cmd, !want); }
}
int main() {
    const char *radio[] = {"subghz_replay 433920 5", "subghz_tx_raw 433920 350 1,2", "subghz_jammer 433920 8",
        "subghz_config 433920", "subghz_bruteforce 0 433920000 12 5", "rf rx 433920000", "subghz rx",
        "rf tx 445533 433920000 350 10", "subghz txp Princeton 433920000 24 1 350 10", "rf scan 433 434",
        "subghz tx_from_file /a.sub", "rf tx_from_buffer", "subghz keeloqtx CAME 433920000",
        "RfSend {\"Data\":\"0x1\"}", "  rf  rx 433920000",
        // batch 18: SimpleCLI matches names in any case and runs every newline / ";;" segment.
        "RF RX 433920000", "SubGhz Tx 445533 433920000 350 10", "rfsend {}", "RFSEND {}", "SUBGHZ_REPLAY 1 2",
        "subghz mfcodes list;;subghz rx", "diag\nrf rx 433920000", "diag\r\nRF TXP X 1 24 1",
        "\"rf\" \"rx\" 433920000", "rf mfcodes list;;rx 433920000", "subghz \"mfcodes list;;scan 1 2\""};
    const char *free_[] = {"subghz mfcodes list", "rf mfcodes add a;b;c", "rfid read", "rfx rx",
        "subghzz rx", "ir tx NEC 00 00", "diag", "", "rf", "subghz", "rf txpx", "RfSendX",
        "RF MFCODES LIST", "diag;;rfid read", "rx 433920000", "ir rx;;tx 1"};
    for (const char *c : radio) expect(true, c);
    for (const char *c : free_) expect(false, c);
    expect(false, nullptr);
    std::printf("failures=%d\n", failures);
    return failures ? 1 : 0;
}
'''


def compile_run(header_dir):
    with tempfile.TemporaryDirectory(prefix="brucia-busy-") as tmp:
        tmp = Path(tmp)
        (tmp / "d.cpp").write_text(BUSY_DRIVER)
        exe = tmp / "busy"
        comp = subprocess.run([CXX, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-I", str(header_dir),
                               str(tmp / "d.cpp"), "-o", str(exe)], capture_output=True, text=True)
        if comp.returncode:
            return comp.returncode, comp.stderr
        run = subprocess.run([str(exe)], capture_output=True, text=True, timeout=30)
        return run.returncode, run.stdout


class SubghzBusyClassifier(unittest.TestCase):
    HEADER = SRC / "companion/subghz_busy.h"

    def test_classifier(self):
        rc, out = compile_run(self.HEADER.parent)
        self.assertEqual(0, rc, out)

    def _mutant(self, old, new):
        src = self.HEADER.read_text()
        self.assertIn(old, src)
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "subghz_busy.h").write_text(src.replace(old, new))
            return compile_run(Path(tmp))

    def test_companion_prefix_rule_is_load_bearing(self):
        rc, out = self._mutant('strncmp(first, "subghz_", 7) == 0 || ', "")
        self.assertNotEqual(0, rc, "mutation survived: subghz_* commands not classified")

    def test_case_folding_is_load_bearing(self):
        # Without lowercasing, "RF RX ..." (SimpleCLI is case-insensitive) bypasses the 409.
        rc, out = self._mutant("return (c >= 'A' && c <= 'Z') ? (char)(c - 'A' + 'a') : c;", "return c;")
        self.assertNotEqual(0, rc, "mutation survived: upper-case radio verbs not classified")
        self.assertIn("RF RX 433920000", out)

    def test_segment_split_is_load_bearing(self):
        rc, out = self._mutant("*p == '\\0' || *p == '\\n' || *p == '\\r' || (p[0] == ';' && p[1] == ';')",
                               "*p == '\\0'")
        self.assertNotEqual(0, rc, "mutation survived: a newline/;; segment bypasses the classifier")

    def test_mock_mirrors_classifier(self):
        for c in ("subghz_replay 1 2", "rf rx 433920000", "subghz txp X 1 24 1", "RfSend {}",
                  "RF RX 433920000", "rfsend {}", "diag\nrf rx 1", "subghz mfcodes list;;subghz rx",
                  "rf mfcodes list;;rx 433920000", '"rf" "rx" 1'):
            self.assertTrue(subghz_needs_radio(c), c)
        for c in ("subghz mfcodes list", "rfid read", "diag", "RF MFCODES LIST", "rx 433920000",
                  "rf mfcodes add a;b;c"):
            self.assertFalse(subghz_needs_radio(c), c)


# --- static firmware guards ----------------------------------------------------------------------
class FirmwareGuards(unittest.TestCase):
    def test_subghz_dispatch_refuses_radio_commands_while_streaming(self):
        src = strip_comments(read("src/companion/subghz.cpp"))
        body = body_of(src, r"bool dispatchSubghzCommand\(const CommandRequest &request\)")
        guard = body.find("subghzReceiverBusy() && subghzCommandNeedsRadio(")
        self.assertGreaterEqual(guard, 0, "dispatchSubghzCommand has no CC1101 busy guard")
        self.assertIn("sendErr(id, 409,", body[guard:guard + 200])
        self.assertLess(guard, body.find('startsWith("subghz_replay ")'), "guard after the handlers")
        busy = body_of(src, r"static bool subghzReceiverBusy\(\)")
        self.assertIn("subRf || subSpec", busy)
        # dispatchSubghzCommand runs before routeCommand, so routed rf/subghz CLI verbs are covered.
        cmds = strip_comments(read("src/companion/commands.cpp"))
        self.assertLess(cmds.find("dispatchSubghzCommand,"), cmds.find("routeCommand(cmdStr, out)"))

    def test_subghz_config_reports_init_failure(self):
        src = strip_comments(read("src/companion/subghz.cpp"))
        body = body_of(src, r"static void runSubghzConfig\(")
        self.assertIn('const bool applied = initRfModule("rx"', body)
        self.assertIn("companionAckMsg(id, applied, out);", body)

    def test_lora_recv_polls_stop(self):
        body = body_of(strip_comments(read("src/companion/network.cpp")), r"static void runLoraRecv\(")
        loop = body[body.find("while ((int32_t)(millis() - end) < 0)"):]
        self.assertIn("companionPollStop()", loop, "lora_recv ignores the app's stop (up to 120 s)")

    def test_ir_power_spam_polls_stop_and_covers_all_tables(self):
        """New IR power spam: both regions + universal, repeating, abortable by the app stop."""
        tv = strip_comments(read("src/modules/ir/TV-B-Gone.cpp"))
        body = body_of(tv, r"uint16_t runIrPowerSpamHeadless\(")
        for tbl in ("NApowerCodes", "EUpowerCodes", "UniversalParsedCodes", "UniversalRawCodes"):
            self.assertIn(tbl, body, f"ir power spam misses {tbl}")
        self.assertIn("while (!done())", body, "ir power spam does not repeat the set")
        self.assertIn("shouldAbort && shouldAbort()", body)
        ir = strip_comments(read("src/companion/infrared.cpp"))
        disp = body_of(ir, r"bool dispatchInfraredCommand\(")
        self.assertLess(disp.find('cmdStr == "ir_power_spam_stop"'), disp.find('cmdStr == "ir_power_spam"'),
                        "idle ir_power_spam_stop guard must precede the start handler")
        spam = body_of(ir, r"static void runIrPowerSpam\(")
        self.assertIn("companionPollStop()", spam, "ir_power_spam does not poll the app stop")
        self.assertIn("(stopped)", spam)
        # F33: a malformed explicit duration is refused before the pin check / driver work.
        reject = spam.find('sendErr(id, 400, "usage: ir_power_spam')
        self.assertGreater(reject, 0, "ir_power_spam does not reject a malformed duration")
        self.assertLess(reject, spam.find("irTxPinReady()"), "duration parsed after the pin check")
        self.assertLess(reject, spam.find("runIrPowerSpamHeadless("), "duration parsed after driver work")
        self.assertIn("v < 1 || v > 120", spam, "ir_power_spam range check missing")
        self.assertIn("numeric = false", spam, "ir_power_spam does not reject nonnumeric input")

    def test_nrf24_windows_poll_stop(self):
        """Backlog A2: every nrf24_* window ends on the app's stop, not only at its end."""
        src = strip_comments(read("src/companion/nrf24.cpp"))
        for fn in ("runNrf24Scan", "runNrf24Sniff"):
            body = body_of(src, r"static void %s\(" % fn)
            self.assertIn("companionPollStop()", body[body.find("while ("):], f"{fn} ignores the app's stop")
        self.assertIn("nrf_jammer_headless(secs, [&stopped]() { return stopped = stopped || companionPollStop(); })", src)
        self.assertIn("[&stopped]() { return stopped = stopped || companionPollStop(); }",
                      body_of(src, r"static void runNrf24Mousejack\("))
        jam = body_of(strip_comments(read("src/modules/NRF24/nrf_jammer.cpp")), r"int nrf_jammer_headless\(")
        self.assertIn("shouldAbort && shouldAbort()", jam)
        mj = body_of(strip_comments(read("src/modules/NRF24/nrf_mousejack.cpp")), r"int nrf_mousejack_headless\(")
        self.assertIn("shouldAbort && shouldAbort()", mj)
        self.assertIn("if (!aborted && injectText", mj, "an aborted MouseJack scan must not inject")

    def test_fm_i2s_single_owner(self):
        """Backlog A3: FM audio TX, speaker and mic release each other at the firmware boundary."""
        audio = strip_comments(read("src/companion/audio.cpp"))
        begin = body_of(audio, r"static void runFmAudioBegin\(")
        release = begin.find('speakerOutputRelease("superseded");')
        self.assertGreater(release, 0, "fm_audio_begin keeps a speaker output on the shared pins")
        self.assertLess(release, begin.find("fm_tx_digital_begin("))
        self.assertIn("micPcmStop();", begin[:begin.find("fm_tx_digital_begin(")], "FM mic source vs mic.pcm")
        for fn in (r"static void runSpeakerTone\(", r"static void runSpeakerPlay\(", r"static void runSpkrPcmBegin\("):
            self.assertIn("fmAudioRelease(false);", body_of(audio, fn), f"{fn} leaves FM audio on the speaker pins")
        rel = body_of(audio, r"void fmAudioRelease\(bool onlyMicSource\)")
        self.assertIn("fmAudioActive && (!onlyMicSource || fmAudioMic)", rel)
        rec = audio.find('if (cmdStr == "mic_record" || cmdStr.startsWith("mic_record "))')
        self.assertGreater(rec, 0)
        self.assertLess(audio.find("fmAudioRelease(true);", rec), audio.find("runMicRecord(", rec),
                        "mic_record vs FM mic source")
        comp = strip_comments(read("src/companion/companion.cpp"))
        self.assertEqual(2, comp.count("if (on) fmAudioRelease(true);"), "mic.spectrum / mic.pcm ON vs FM mic source")
        # A3: every speaker entry (including async song/rtttl/dtmf) releases FM first.
        disp = body_of(audio, r"bool dispatchAudioCommand\(")
        # Each handler releases FM before it acquires the speaker; check up to that acquire call
        # (not the first break, which for dtmf is an early error-branch return).
        for verb, acquire in (("speaker_rtttl ", "playAudioRTTTLString("),
                              ("speaker_song ", "playAudioRTTTLString("),
                              ("speaker_dtmf ", "playDtmf(")):
            start = disp.find('startsWith("%s")' % verb)
            self.assertGreater(start, 0, f"{verb.strip()} handler not found")
            blk = disp[start:disp.find(acquire, start)]
            self.assertIn("fmAudioRelease(false);", blk, f"{verb.strip()} does not release FM audio before acquiring")
        # A3: speakerOutputRelease stops an async song (not tracked by spkFileActive).
        rel = body_of(audio, r"void speakerOutputRelease\(const char \*reason\)")
        self.assertIn("isAudioPlaybackTaskActive()", rel, "speakerOutputRelease ignores an async song")
        self.assertIn("stopAudioPlayback()", rel)

    def test_companion_idle_exit_measured_after_handling(self):
        src = strip_comments(read("src/companion/companion.cpp"))
        loop = body_of(src, r"static void runCompanionUSB\(bool headless\)")
        switch_end = loop.find('default: sendErr(id, 404, "unknown message kind"); break;')
        self.assertGreater(switch_end, 0)
        self.assertIn("lastFrame = millis();", loop[switch_end:switch_end + 400],
                      "lastFrame not refreshed after a handled frame: a ~60 s command trips the idle exit")

    def test_ble_scan_start_owner_checks(self):
        src = strip_comments(read("src/companion/bluetooth.cpp"))
        start = body_of(src, r"int bleStart\(\)")
        self.assertIn("if (g_companionHid) return 409;", start)
        self.assertIn("return 503;", start)
        self.assertLess(start.find("return 409;"), start.find("NimBLEDevice::init("))
        comp = strip_comments(read("src/companion/companion.cpp"))
        sub = comp[comp.find('strcmp(stream, "ble.scan") == 0'):comp.find('strcmp(stream, "subghz.raw") == 0')]
        self.assertIn("subBleOn = on && code == 0;", sub)
        self.assertIn("sendErr(id, 409,", sub)
        self.assertIn("sendErr(id, 503,", sub)
        self.assertNotIn("sendAck(id, true);\n", sub.replace("if (code == 0) sendAck(id, true);", ""))

    def test_ble_media_refuses_while_scanning(self):
        src = strip_comments(read("src/companion/bluetooth.cpp"))
        media = src[src.find('cmdStr.startsWith("ble_media ")'):]
        media = media[:media.find("runMediaCommandHijackHeadless(")]
        self.assertIn("} else if (subBle) {", media)
        self.assertIn('sendErr(id, 409, "ble scan active (stop the scan first)");', media)

    def test_badusb_refused_in_companion_mode(self):
        src = strip_comments(read("src/core/serial_commands/badusb_commands.cpp"))
        for name in ("badusbFileCallback", "badusbBufferCallback"):
            body = body_of(src, r"uint32_t " + name + r"\(cmd \*c\)")
            guard = body.find("badusbRefusedInCompanion()")
            self.assertGreaterEqual(guard, 0, f"{name} not refused in companion mode")
            self.assertLess(guard, body.find("ducky_startKb("), f"{name}: guard after USB HID setup")
        helper = body_of(src, r"static bool badusbRefusedInCompanion\(\)")
        self.assertIn("if (!g_companionActive) return false;", helper)

    def test_ir_tx_pin_checked_before_transmit(self):
        ir = strip_comments(read("src/companion/infrared.cpp"))
        for fn, call in ((r"static void runIrTvbgone\(", "runTvBGoneHeadless("),
                         (r"static void runIrTxRaw\(", "sendRawCommand(")):
            body = body_of(ir, fn)
            self.assertGreaterEqual(body.find("if (!irTxPinReady())"), 0, fn)
            self.assertLess(body.find("if (!irTxPinReady())"), body.find(call), fn)
        jam = ir[ir.find('cmdStr == "ir_jammer" || cmdStr.startsWith("ir_jammer ")'):]
        self.assertGreater(len(jam), 0)
        self.assertLess(jam.find("if (!irTxPinReady())"), jam.find("ir_jammer_headless("))
        tvb = strip_comments(read("src/modules/ir/TV-B-Gone.cpp"))
        chk = body_of(tvb, r"void checkIrTxPin\(\)")
        self.assertLess(chk.find("if (g_companionActive) return;"), chk.find("gsetIrTxPin(true)"),
                        "checkIrTxPin can open the pin menu in companion mode")
        cli = strip_comments(read("src/core/serial_commands/ir_commands.cpp"))
        for name, call in (("irTxCallback", "sendIRCommand("), ("irTxRawCallback", "sendIRCommand("),
                           ("irTxFileCallback", "txIrFile("), ("irSendCallback", "sendDecodedCommand(")):
            body = body_of(cli, r"uint32_t " + name + r"\(cmd \*c\)")
            self.assertLess(body.find("irTxRefusedHeadless()"), body.rfind(call), name)
            self.assertGreaterEqual(body.find("irTxRefusedHeadless()"), 0, name)

    def test_ir_tx_rejects_unsupported_protocol(self):
        cli = strip_comments(read("src/core/serial_commands/ir_commands.cpp"))
        body = body_of(cli, r"uint32_t irTxCallback\(cmd \*c\)")
        check = body.find("if (!irParsedProtocolSupported(protocol))")
        self.assertGreaterEqual(check, 0)
        self.assertIn("return false;", body[check:check + 160])
        # The supported list matches what sendIRCommand() actually dispatches.
        custom = strip_comments(read("src/modules/ir/custom_ir.cpp"))
        send = body_of(custom, r"void sendIRCommand\(IRCode \*code, bool hideDefaultUI\)")
        dispatched = set(re.findall(r'protocol\.equalsIgnoreCase\("([^"]+)"\)', send))
        listed = set(re.findall(r'"([A-Za-z0-9]+)"', body_of(custom, r"bool irParsedProtocolSupported\(")))
        self.assertEqual(dispatched, listed)

    def test_ir_ac_state_bounded_before_stack_buffer(self):
        custom = strip_comments(read("src/modules/ir/custom_ir.cpp"))
        body = body_of(custom, r"bool sendDecodedCommand\(")
        guard = body.find("if (stateLen == 0 || (value.length() + 2) / 3 > stateLen) return false;")
        self.assertGreaterEqual(guard, 0, "AC state parse can overflow uint8_t state[bits / 8]")
        self.assertLess(guard, body.find("uint8_t state[bits / 8]"))

    def test_emv_wait_bounded_and_cancel_not_parsed(self):
        nfc = strip_comments(read("src/companion/nfc.cpp"))
        body = body_of(nfc, r"static void runNfcEmvRead\(uint32_t id\)")
        self.assertLess(body.find("emv.setHeadlessWait(NFC_EMV_WAIT_MS, companionPollStop);"),
                        body.find("emv.readMaskedSummary(out)"))
        emv = strip_comments(read("src/modules/rfid/emv_reader.cpp"))
        aid = body_of(emv, r"std::vector<uint8_t> EMVReader::emv_ask_for_aid\(\)")
        self.assertIn("_waitMs && millis() - waitStart >= _waitMs", aid)
        self.assertIn("_pollStop && _pollStop()", aid)
        card = body_of(emv, r"EMVCard EMVReader::read_emv_card\(\)")
        self.assertEqual(2, card.count("res.parsed = false;\n        return res;"))

    def test_espnow_keeps_wifi_owner(self):
        body = body_of(strip_comments(read("src/companion/system.cpp")), r"static void runEspnowSend\(")
        self.assertNotIn("WiFi.disconnect(", body)
        self.assertNotRegex(body, r"\n\s*WiFi\.mode\(WIFI_STA\);")  # only under the WIFI_MODE_NULL check
        self.assertIn("if (mode == WIFI_MODE_NULL) WiFi.mode(WIFI_STA);", body)
        self.assertIn("else if (mode == WIFI_MODE_AP) WiFi.enableSTA(true);", body)


# --- batch 18: host-compiled iBeacon UUID and NDEF contact checks ---------------------------------
UUID_DRIVER = r"""
#include "ibeacon_uuid.h"
#include <cstdio>
using brucia::companion::ibeaconUuidValid;
static int failures = 0;
static void expect(bool want, const char *s) {
    if (ibeaconUuidValid(s) != want) { failures++; std::printf("FAIL: %s -> %d\n", s ? s : "(null)", !want); }
}
int main() {
    expect(true, "8ec76ea3-6668-48da-9866-75be8bc86f4d");
    expect(true, "8EC76EA3-6668-48DA-9866-75BE8BC86F4D");
    expect(true, "00000000-0000-0000-0000-000000000000");
    expect(false, nullptr);
    expect(false, "");
    expect(false, "xyz");
    expect(false, "8ec76ea3666848da986675be8bc86f4d");        // no dashes
    expect(false, "8ec76ea3-6668-48da-9866-75be8bc86f4");     // 35 chars
    expect(false, "8ec76ea3-6668-48da-9866-75be8bc86f4d0");   // 37 chars
    expect(false, "8ec76ea3-6668-48da-9866-75be8bc86f4g");    // non-hex
    expect(false, "8ec76ea3-6668-48da-9866_75be8bc86f4d");    // wrong separator
    expect(false, "8ec76ea3-6668-48da-9866-75be8bc86f4d x");  // extra word
    expect(false, "-8ec76ea-6668-48da-9866-75be8bc86f4d");    // dash moved
    std::printf("failures=%d\n", failures);
    return failures ? 1 : 0;
}
"""

NDEF_DRIVER = r"""
#include "ndef_contact.h"
#include <cstdio>
#include <string>
using namespace brucia::companion;
static int failures = 0;
static void check(bool ok, const char *what) { if (!ok) { failures++; std::printf("FAIL: %s\n", what); } }
int main() {
    // Same text the firmware built before (BEGIN/VERSION/FN[/TEL]/END, CRLF).
    check(ndefContactVcard("Ann") == "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ann\r\nEND:VCARD\r\n", "name only");
    check(ndefContactVcard("Ann ; +391234") ==
          "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ann\r\nTEL:+391234\r\nEND:VCARD\r\n", "name;tel trimmed");
    check(ndefContactVcard("Ann;") == "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ann\r\nEND:VCARD\r\n", "empty tel");
    // Record = 3 header bytes + "text/vcard" (10) + vCard.
    check(ndefContactRecordSize("Ann") == 13 + 45, "record size name only");
    // Limit: 126 bytes. Name only: 13 + 42 + name <= 126 -> name <= 71.
    check(ndefContactRecordSize(std::string(71, 'a')) == 126, "71-char name fits exactly");
    check(ndefContactRecordSize(std::string(72, 'a')) == 127, "72-char name is one over");
    // With a phone: 13 + 42 + 6 + name + tel <= 126 -> name + tel <= 65.
    check(ndefContactRecordSize(std::string(50, 'a') + ";" + std::string(15, '1')) == 126, "name+tel fits");
    check(ndefContactRecordSize(std::string(50, 'a') + ";" + std::string(16, '1')) == 127, "name+tel over");
    check(NDEF_PUSH_MAX_RECORD == 126, "PN532 limit");
    std::printf("failures=%d\n", failures);
    return failures ? 1 : 0;
}
"""


def compile_driver(driver, header_dir):
    with tempfile.TemporaryDirectory(prefix="brucia-b18-") as tmp:
        tmp = Path(tmp)
        (tmp / "d.cpp").write_text(driver)
        exe = tmp / "d"
        comp = subprocess.run([CXX, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-fno-exceptions", "-I",
                               str(header_dir), str(tmp / "d.cpp"), "-o", str(exe)], capture_output=True, text=True)
        if comp.returncode:
            return comp.returncode, comp.stderr
        run = subprocess.run([str(exe)], capture_output=True, text=True, timeout=30)
        return run.returncode, run.stdout


def mutant_driver(driver, header, old, new):
    src = (SRC / "companion" / header).read_text()
    assert old in src, f"mutation anchor missing: {old!r}"
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / header).write_text(src.replace(old, new))
        return compile_driver(driver, Path(tmp))


class Batch18Headers(unittest.TestCase):
    def test_ibeacon_uuid(self):
        rc, out = compile_driver(UUID_DRIVER, SRC / "companion")
        self.assertEqual(0, rc, out)

    def test_ibeacon_uuid_mutations(self):
        rc, out = mutant_driver(UUID_DRIVER, "ibeacon_uuid.h", "return s[36] == '\\0';", "return true;")
        self.assertNotEqual(0, rc, "length check mutation survived")
        rc, out = mutant_driver(UUID_DRIVER, "ibeacon_uuid.h", "if (c != '-') return false;", "")
        self.assertNotEqual(0, rc, "separator check mutation survived")

    def test_ndef_contact(self):
        rc, out = compile_driver(NDEF_DRIVER, SRC / "companion")
        self.assertEqual(0, rc, out)

    def test_ndef_contact_mutation(self):
        rc, out = mutant_driver(NDEF_DRIVER, "ndef_contact.h", "= 3 + 10;", "= 3;")
        self.assertNotEqual(0, rc, "record overhead mutation survived")

    def test_app_mirrors_the_ndef_limit(self):
        kt = read("android/app/src/main/java/app/brucia/nfc/NfcToolsService.kt")
        self.assertIn("NDEF_MAX_CONTACT_RECORD_BYTES = 126", kt)
        self.assertIn("NDEF_CONTACT_RECORD_OVERHEAD = 13", kt)


class Batch18Firmware(unittest.TestCase):
    def test_ibeacon_uuid_checked_before_the_stack(self):
        bt = strip_comments(read("src/companion/bluetooth.cpp"))
        h = bt[bt.find('if (cmdStr == "ble_ibeacon" || cmdStr.startsWith("ble_ibeacon ")) {'):]
        check = h.find("if (uuid.length() && !ibeaconUuidValid(uuid.c_str())) {")
        self.assertGreater(check, 0, "ble_ibeacon does not validate the UUID")
        self.assertIn("sendErr(id, 400,", h[check:check + 200])
        self.assertLess(check, h.find("bleAdvertiserConflict()"))
        self.assertLess(check, h.find("runIbeaconBounded("))

    def test_ble_hid_begin_checks_init_and_advertising(self):
        bt = strip_comments(read("src/companion/bluetooth.cpp"))
        h = bt[bt.find('if (cmdStr == "ble_hid_begin") {'):bt.find('} else if (cmdStr == "ble_hid_status") {')]
        init = h.find('if (!NimBLEDevice::isInitialized() && !NimBLEDevice::init("Brucia")) {')
        self.assertGreater(init, 0, "ble_hid_begin ignores the NimBLE init result")
        self.assertIn("sendErr(id, 503,", h[init:init + 120])
        adv = h.find("if (hidAdv == nullptr || !hidAdv->isAdvertising()) {")
        self.assertGreater(adv, h.find("g_companionHid->begin();"))
        self.assertIn("sendErr(id, 500,", h[adv:adv + 300])
        self.assertIn("g_companionHid = nullptr;", h[adv:adv + 300])
        self.assertLess(adv, h.find('companionAckMsg(id, true, "advertising'))

    def test_ir_zero_codes_is_not_ok(self):
        ir = strip_comments(read("src/companion/infrared.cpp"))
        tvb = body_of(ir, r"static void runIrTvbgone\(")
        self.assertIn("companionAckMsg(id, sent > 0, out.c_str());", tvb)
        self.assertNotIn("companionAckMsg(id, true, out.c_str());", tvb)
        jam = ir[ir.find('cmdStr == "ir_jammer" || cmdStr.startsWith("ir_jammer ")'):]
        jam = jam[:jam.find("break;\n        }\n        return false;")]
        self.assertIn("companionAckMsg(id, n > 0, buf);", jam)

    def test_subghz_tx_raw_reports_the_transmit(self):
        sg = strip_comments(read("src/companion/subghz.cpp"))
        body = body_of(sg, r"static void runSubghzTxRaw\(")
        self.assertIn("const bool sent = sendRfCommand(code, true);", body)
        self.assertIn("companionAckMsg(id, false, out);", body[body.find("if (!sent)"):])
        rs = strip_comments(read("src/modules/rf/rf_send.cpp"))
        send = body_of(rs, r"bool sendRfCommand\(struct RfCodes rfcode, bool hideDefaultUI\)")
        self.assertIn('if (!initRfModule("", frequency / 1000000.0)) return false;', send)
        for call in ("sent = rfTransmitRawTimings(", "sent = rfTransmitRawBits(", "sent = rf_tx_keeloq(",
                     "sent = rf_tx_protocol(", "sent = rfTransmitCode("):
            self.assertIn(call, send)
        self.assertTrue(send.rstrip().endswith("return sent;"))
        self.assertIn("if (transmittimings == nullptr) {", send)

    def test_subghz_replay_reports_repeats_sent(self):
        sg = strip_comments(read("src/companion/subghz.cpp"))
        body = body_of(sg, r"static void runSubghzReplay\(")
        self.assertIn("if (!rf_raw_emit(rec, rtm)) break;", body)
        zero = body.find("if (done == 0) {")
        self.assertGreater(zero, 0)
        self.assertIn("companionAckMsg(id, false, out);", body[zero:zero + 250])
        emit = strip_comments(read("src/modules/rf/emit.cpp"))
        eb = body_of(emit, r"bool rf_raw_emit\(RawRecording &recorded, bool &returnToMenu\)")
        self.assertIn('const bool radioReady = initRfModule("tx", recorded.frequency);', eb)
        self.assertTrue(eb.rstrip().endswith("return radioReady;"))

    def test_ndef_contact_checked_before_the_reader(self):
        nfc = strip_comments(read("src/companion/nfc.cpp"))
        body = body_of(nfc, r"static void runNfcNdefPush\(")
        check = body.find('if (type == "contact" && ndefContactRecordSize(contactPayload) > NDEF_PUSH_MAX_RECORD) {')
        self.assertGreater(check, 0)
        self.assertIn("sendErr(id, 400, why);", body[check:check + 400])
        self.assertLess(check, body.find("createCompanionRfidModule()"))
        self.assertIn("ndefContactVcard(contactPayload)", body)


# --- protocol level against the mock ---------------------------------------------------------------
class MockProtocol(unittest.TestCase):
    def setUp(self):
        from test_protocol_e2e import FrameClient
        self.master, slave = pty.openpty()
        self.dev = MockDevice(poll_interval=0.002)
        self.stop = threading.Event()
        self.th = threading.Thread(target=self.dev.serve, args=(self.master, self.stop), daemon=True)
        self.th.start()
        self.cli = FrameClient(os.ttyname(slave))
        self.assertIsNotNone(self.cli.caps(1))

    def tearDown(self):
        self.cli.close()
        self.stop.set()
        self.th.join(timeout=5)

    def test_cc1101_busy_while_receive_stream_runs(self):
        self.cli.send(SUB, 2, {"stream": "subghz.raw", "on": True})
        self.assertIsNotNone(self.cli.reply(2, ACK))
        self.cli.send(3, 3, {"cmd": "rf rx 433920000"})  # 3 = CMD
        busy = self.cli.reply(3, ERR)
        self.assertIsNotNone(busy)
        self.assertEqual(409, busy["d"]["code"])
        self.assertIn("sub-ghz receiver busy", busy["d"]["msg"])
        self.assertTrue(self.cli.cmd(4, "subghz mfcodes list")["ok"])  # file only: allowed
        self.cli.send(SUB, 5, {"stream": "subghz.raw", "on": False})
        self.assertIsNotNone(self.cli.reply(5, ACK))
        self.assertIsNotNone(self.cli.cmd(6, "subghz_config 433920"))  # radio free again: ACK

    def test_idle_stops_never_start_a_run(self):
        for i, stop in enumerate(("jam_detect_stop", "ir_jammer_stop", "ble_ibeacon_stop", "karma_stop")):
            r = self.cli.cmd(20 + i, stop)
            self.assertTrue(r["ok"], stop)
            self.assertEqual("not running", r["out"], stop)

    def test_upper_case_radio_verb_refused_while_streaming(self):
        self.cli.send(SUB, 2, {"stream": "subghz.raw", "on": True})
        self.assertIsNotNone(self.cli.reply(2, ACK))
        for i, c in enumerate(("RF RX 433920000", "subghz mfcodes list;;subghz rx")):
            self.cli.send(3, 30 + i, {"cmd": c})
            busy = self.cli.reply(30 + i, ERR)
            self.assertIsNotNone(busy, c)
            self.assertEqual(409, busy["d"]["code"])

    def test_sub_during_blocking_op_is_refused_not_dropped(self):
        self.cli.send(3, 40, {"cmd": "jam_detect 30"})
        time.sleep(0.1)
        self.cli.send(SUB, 41, {"stream": "wifi.ap", "on": True})
        busy = self.cli.reply(41, ERR, timeout=3)
        self.assertIsNotNone(busy, "SUB dropped unanswered during a blocking op")
        self.assertEqual(409, busy["d"]["code"])
        self.cli.send(3, 42, {"cmd": "jam_detect_stop"})
        self.assertIsNotNone(self.cli.reply(40, ACK, timeout=5))
        self.cli.send(SUB, 43, {"stream": "wifi.ap", "on": True})  # idle again: served
        self.assertIsNotNone(self.cli.reply(43, ACK, timeout=3))

    def test_nrf24_stop_idle_answers_not_running(self):
        r = self.cli.cmd(50, "nrf24_stop")
        self.assertTrue(r["ok"])
        self.assertEqual("not running", r["out"])

    def test_lora_recv_answers_ping_and_ends_on_stop(self):
        t0 = time.time()
        self.cli.send(3, 10, {"cmd": "lora_recv 60"})
        time.sleep(0.1)
        self.cli.send(PING, 11, {})
        self.assertIsNotNone(self.cli.reply(11, PONG), "PING unanswered during lora_recv")
        self.cli.send(3, 12, {"cmd": "stop"})
        done = self.cli.reply(10, ACK, timeout=5)
        self.assertIsNotNone(done)
        self.assertIn("(stopped)", done["d"]["out"])
        self.assertLess(time.time() - t0, 10)


if __name__ == "__main__":
    unittest.main(verbosity=2)
