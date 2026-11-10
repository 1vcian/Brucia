#!/usr/bin/env python3
"""Host test of tools/hw_acceptance.py against tools/mock_device.py over a PTY. No hardware.

Runs the full runner (all checks, including the opt-in busy check) against a healthy mock and
expects PASS with JSON + Markdown reports; then against mocks with injected faults (diag counter
mismatch, heap leak, stream events after OFF) and expects the matching checks to FAIL; checks
the allowlist refuses every transmitting command before a byte is written, that Ctrl-C sends OFF
for an active SUB, that --dry-run opens nothing and that a missing feature yields SKIP.
Soak (--soak, seconds-long real-time runs): a healthy soak PASSes with a CSV time series while
the mock leaves companion mode after 1 s without a frame (keepalive proven, and a too-slow
keepalive fails); a simulated heap leak, reboot (uptime/counters reset) and device noise are
findings that FAIL the soak; Ctrl-C during a stream or an upload cleans up and still reports.
Radio coverage (F43, --require-events): the link verdict and the coverage are separate; with a
requirement, zero events (mock emission disabled, nothing made to emit), too few events, a Ctrl-C
before the planned minutes, a stream absent from CAPS or never started leave the radio gate NOT MET
(exit 3, or 1/130 when the link also FAILed/was interrupted); enough events MET (exit 0); no
requirement keeps a link PASS with coverage NOT REQUIRED. RadioCoverageTest (unittest) covers the
verdict function, the options and the zero-events run for `python3 -m unittest`.
Link tests: the reader resyncs after device text between/inside frames (counted, reported as a
warning, never fatal) and upload chunks follow CAPS rx_max (legacy 768 without it) so they fit a
simulated small RX queue that drops bytes, while oversized chunks reproduce the hardware loss.
The opt-in script_stop check PASSes on a mock that stops the run and FAILs on mocks whose firmware
ignores the stop or stays `interpreter busy` after it (SKIP on LITE). This proves the runner, not the device.

    python3 tools/test_hw_acceptance.py
    python3 -m unittest tools.test_hw_acceptance -v   # RadioCoverageTest only
"""
import contextlib
import io
import json
import os
import pty
import struct
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import hw_acceptance as hw  # noqa: E402
from mock_device import MockDevice  # noqa: E402

FAST = ["--pings", "40", "--ping-burst", "10", "--stream-secs", "1.5", "--quiet-secs", "1.0",
        "--diag-repeats", "8", "--timeout", "2", "--fs-timeout", "5", "--busy-secs", "6",
        "--script-stop-after", "0.5"]
ALL = ",".join(c for c in hw.CHECK_IDS if c != "soak")  # every check a plain run can select
# Short soak: intervals of 1 s, keepalive 0.3 s, short streams (later options override FAST).
SOAK = ["--soak", "0.07", "--soak-interval", "1.0", "--soak-keepalive", "0.3", "--soak-pings", "5",
        "--soak-burst", "3", "--stream-secs", "0.3", "--quiet-secs", "0.2"]

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" - {detail}" if detail and not ok else ""))


@contextlib.contextmanager
def mock(faults=(), **kw):
    master, slave = pty.openpty()
    dev = MockDevice(faults=faults, poll_interval=0.002, emit_interval=0.3, **kw)
    stop = threading.Event()
    th = threading.Thread(target=dev.serve, args=(master, stop), daemon=True)
    th.start()
    try:
        yield dev, os.ttyname(slave)
    finally:
        stop.set()
        th.join(timeout=10)
        os.close(master)
        os.close(slave)


def run_runner(path, extra, out):
    buf = io.StringIO()
    code = hw.run(["--port", path, "--out", out] + FAST + extra, log=lambda m: buf.write(m + "\n"))
    return code, hw.run.last_report, hw.run.last_paths, buf.getvalue()


def statuses(report):
    return {c["id"]: c["status"] for c in report["checks"]}


def all_sent_allowed(dev):
    for cmd in dev.received_cmds:
        hw.check_allowed(hw.CMD, {"cmd": cmd})
    for sub in dev.received_subs:
        hw.check_allowed(hw.SUB, sub)
    return True


def test_healthy(tmp):
    with mock() as (dev, path):
        code, rep, paths, log = run_runner(path, ["--only", ALL], tmp)
        st = statuses(rep)
        check("healthy mock: exit 0 and result PASS", code == 0 and rep["summary"]["result"] == "PASS",
              f"code={code} {st}\n{log}")
        expected = {c: "PASS" for c in hw.CHECK_IDS if c != "soak"}
        check("healthy mock: every check PASS (busy included)", st == expected, str(st))
        check("scratch upload removed from the mock FS",
              not any(p.startswith(hw.SCRATCH_PREFIX) for p in dev.files), str(list(dev.files)))
        check("no active SUB left on the mock", not dev.subs, str(dev.subs))
        check("every CMD/SUB the mock received is allowlisted", all_sent_allowed(dev))
        check("fs_read picked the small file and verified it",
              next(c for c in rep["checks"] if c["id"] == "fs_read")["evidence"].get("path") == "/brucia-mock-small.txt")
        streams = next(c for c in rep["checks"] if c["id"] == "streams")["evidence"]["wifi.probe"]
        check("wifi.probe events seen and none after OFF", streams["events"] > 0 and streams["after_off"] == 0,
              str(streams))
        counters = next(c for c in rep["checks"] if c["id"] == "diag_counters")["evidence"]
        check("diag deltas equal the runner's frame counts",
              counters["delta"]["rx_frames"] == counters["expected"]["rx_frames"] > 0
              and counters["delta"]["tx_frames"] == counters["expected"]["tx_frames"] > 0
              and counters["delta"]["busy_409"] == 2 and counters["delta"]["rx_bad_json"] == 1, str(counters["delta"]))
        jp, mp = paths
        data = json.loads(Path(jp).read_text())
        md = Path(mp).read_text()
        check("JSON report: provenance, timestamps, checks, commands",
              data["device"]["build"]["id"] == "mock" and data["started_utc"].endswith("Z")
              and data["finished_utc"].endswith("Z") and len(data["checks"]) == len(hw.CHECK_IDS) - 1
              and all(c["started_utc"] for c in data["checks"]) and data["commands_sent"])
        check("Markdown report: result, build and per-check table",
              "Result: **PASS**" in md and "Build: id `mock`" in md and "| `diag_counters`" in md)


def test_fault(tmp, fault, check_id, label):
    with mock(faults=(fault,)) as (dev, path):
        code, rep, _, log = run_runner(path, [], tmp)
        st = statuses(rep)
        check(f"{label}: {check_id} FAIL, exit 1", code == 1 and st.get(check_id) == "FAIL"
              and rep["summary"]["result"] == "FAIL", f"code={code} {st}")
        return rep


def test_counter_mismatch(tmp):
    rep = test_fault(tmp, "rx_undercount", "diag_counters", "counter mismatch")
    reason = next(c for c in rep["checks"] if c["id"] == "diag_counters")["reason"]
    check("counter mismatch reason names rx_frames", "rx_frames" in reason, reason)
    others = {k: v for k, v in statuses(rep).items() if k not in ("diag_counters",)}
    check("counter mismatch: unrelated checks still PASS",
          all(v == "PASS" for k, v in others.items() if k != "malformed"), str(others))


def test_allowlist():
    class Recorder:
        def __init__(self):
            self.written = b""

        def write(self, data):
            self.written += data

        in_waiting = 0

    rec = Recorder()
    link = hw.Link(rec)
    forbidden = [
        (hw.CMD, {"cmd": "wifi_deauth AA:BB:CC:DD:EE:FF 6 10"}),
        (hw.CMD, {"cmd": "ble_spam 5"}),
        (hw.CMD, {"cmd": "wifi_handshake AA:BB:CC:DD:EE:FF 6 20"}),
        (hw.CMD, {"cmd": "wifi_pmkid AA:BB:CC:DD:EE:FF 6 20"}),
        (hw.CMD, {"cmd": "ir tx NEC 0x04 0x08"}),
        (hw.CMD, {"cmd": "ir_tx_raw 38000 9000,4500"}),
        (hw.CMD, {"cmd": "rf tx 1234 24 433920000 350"}),
        (hw.CMD, {"cmd": "subghz tx_from_file /a.sub"}),
        (hw.CMD, {"cmd": "speaker_play /song.wav"}),
        (hw.CMD, {"cmd": "tone 1000 500"}),
        (hw.CMD, {"cmd": "karma 30"}),
        (hw.CMD, {"cmd": "responder 30"}),
        (hw.CMD, {"cmd": "brucegotchi 30"}),
        (hw.CMD, {"cmd": "mifare_keycheck 0 FFFFFFFFFFFF"}),
        (hw.CMD, {"cmd": "storage remove /bruce.conf"}),
        (hw.CMD, {"cmd": "storage remove /brucia-acceptance-x.txt"}),
        (hw.CMD, {"cmd": "fs_write /a.txt QUJD"}),
        (hw.CMD, {"cmd": "fs_upload_begin " + "0" * 32 + " 3 0 /bruce.conf"}),
        (hw.CMD, {"cmd": "fs_read /../etc"}),
        (hw.CMD, {"cmd": "diag; ble_spam 5"}),
        (hw.CMD, {"cmd": "diag", "extra": 1}),
        (hw.CMD, {"cmd": "jam_detect 120"}),
        (hw.CMD, {"cmd": "script_run print('x');"}),
        (hw.CMD, {"cmd": "script_run " + hw.SCRIPT_SHORT + " ble.advertise()"}),
        (hw.CMD, {"cmd": "script_run  " + hw.SCRIPT_SHORT}),
        (hw.CMD, {"cmd": "script_stop now"}),
        (hw.SUB, {"stream": "wifi.ap", "on": True}),
        (hw.SUB, {"stream": "ble.scan", "on": True}),
        (hw.SUB, {"stream": "subghz.spectrum", "on": True}),
        (hw.SUB, {"stream": "mic.pcm", "on": True}),
        (hw.SUB, {"stream": "wifi.probe", "on": True, "channel": 6}),
        (hw.ACK, {"ok": True}),
        (11, {}),
    ]
    refused = 0
    for kind, d in forbidden:
        try:
            link.send(kind, d)
        except hw.AllowlistViolation:
            refused += 1
    check(f"allowlist refuses {len(forbidden)} TX/destructive/unknown frames", refused == len(forbidden),
          f"{refused}/{len(forbidden)}")
    check("nothing was written for refused frames", rec.written == b"" and link.sent == 0)
    check("AllowlistViolation is an AssertionError (programming error)", issubclass(hw.AllowlistViolation, AssertionError))
    allowed = [(hw.CMD, {"cmd": "diag"}), (hw.CMD, {"cmd": "storage list /"}), (hw.PING, {}),
               (hw.SUB, {"stream": "wifi.probe", "on": False}),
               (hw.CMD, {"cmd": "storage remove /brucia-acceptance-20261105T101010Z-0a1b2c.txt"}),
               (hw.CMD, {"cmd": "script_run " + hw.SCRIPT_LONG}), (hw.CMD, {"cmd": "script_run " + hw.SCRIPT_SHORT}),
               (hw.CMD, {"cmd": "script_stop"})]
    for kind, d in allowed:
        link.send(kind, d)
    check("allowlisted frames are written", link.sent == len(allowed) and len(rec.written) > 0)


class BytePort:
    """pyserial-like port that serves pre-cut chunks (one per read) for reader unit tests."""

    def __init__(self, chunks):
        self.chunks = list(chunks)

    @property
    def in_waiting(self):
        return len(self.chunks[0]) if self.chunks else 0

    def read(self, n):
        return self.chunks.pop(0) if self.chunks else b""

    def write(self, data):
        pass


def frame(obj):
    payload = json.dumps(obj).encode()
    return struct.pack("<I", len(payload)) + payload


def read_all(link, limit=20):
    out = []
    for _ in range(limit):
        f = link.recv(time.time() + 0.05)
        if f is None:
            break
        out.append(f)
    return out


def test_resync_reader():
    a, b, c = (frame({"v": 0, "t": hw.PONG, "id": i, "d": {}}) for i in (1, 2, 3))
    text = b"cc1101 Connection OK\r\n"
    # Text between frames, as the first hardware run saw ("cc11" read as a length).
    link = hw.Link(BytePort([a + text + b + text, c]))
    ids = [f.get("id") for f in read_all(link)]
    check("resync: text between frames skipped, all frames kept", ids == [1, 2, 3], str(ids))
    check("resync: noise bytes and runs counted", link.noise_bytes == 2 * len(text) and link.resyncs == 2,
          f"{link.noise_bytes} {link.resyncs}")
    check("resync: noise sample recorded", link.noise_samples and link.noise_samples[0]["sample"].startswith("cc1101"),
          str(link.noise_samples))
    # Text before the first frame, split across reads one byte at a time.
    link = hw.Link(BytePort([bytes([x]) for x in text + a + b]))
    ids = [f.get("id") for f in read_all(link, 200)]
    check("resync: byte-by-byte arrival", ids == [1, 2] and link.noise_bytes == len(text), f"{ids} {link.noise_bytes}")
    # Text between a length prefix and its payload: that frame is lost, the next one is found.
    link = hw.Link(BytePort([b[:4] + text + b[4:] + c]))
    ids = [f.get("id") for f in read_all(link)]
    check("resync: text after a length prefix loses only that frame", ids == [3] and link.noise_bytes == len(b) + len(text),
          f"{ids} {link.noise_bytes}")
    # Text inside a payload: the frame is undecodable (counted), the tail is skipped, framing survives.
    link = hw.Link(BytePort([b[:20] + text + b[20:] + c]))
    got = read_all(link)
    check("resync: text inside a payload -> one bad frame, next frame intact",
          len(got) == 2 and "_bad" in got[0] and got[1].get("id") == 3 and link.bad_rx_json == 1, str(got))
    # A plausible length whose payload is not an envelope (binary noise) is skipped too.
    link = hw.Link(BytePort([struct.pack("<I", 40) + b"x" * 40 + c]))
    ids = [f.get("id") for f in read_all(link)]
    check("resync: plausible length without the envelope prefix is noise", ids == [3] and link.noise_bytes == 44,
          f"{ids} {link.noise_bytes}")
    # Zero and oversized lengths no longer drop the link.
    link = hw.Link(BytePort([b"\0\0\0\0" + struct.pack("<I", 9000) + a]))
    ids = [f.get("id") for f in read_all(link)]
    check("resync: zero/oversized length skipped", ids == [1] and link.noise_bytes == 8, f"{ids} {link.noise_bytes}")
    # Noise with no frame after it: everything but a possible frame-start tail is counted now.
    link = hw.Link(BytePort([text * 3]))
    check("resync: trailing noise counted, link waits", read_all(link) == [] and link.noise_bytes == len(text) * 3 - 8,
          str(link.noise_bytes))
    # Bruce's CLI echo is still a clear link error (not companion mode).
    link = hw.Link(BytePort([b"COMMAND: " + a]))
    try:
        read_all(link)
        ok = False
    except hw.LinkError as e:
        ok = "serial CLI" in str(e)
    check("resync: Bruce CLI echo still reported as a link error", ok)


def test_upload_sizing():
    tok = "0" * 32
    for rx_max in (768, 1792, 4096, 8192):
        n = hw.upload_chunk_size(rx_max, tok, 6144)
        body = json.dumps({"v": 0, "t": hw.CMD, "id": hw.U32, "d": {
            "cmd": f"fs_upload_chunk {tok} 6143 " + "A" * (4 * ((n + 2) // 3))}}, separators=(",", ":"))
        check(f"upload chunk for rx_max {rx_max}: {n} raw bytes, frame fits",
              0 < n <= hw.UPLOAD_MAX_CHUNK and len(body) <= rx_max, f"{n} {len(body)}")
    check("rx_max from CAPS, legacy default without it",
          hw.device_rx_max({"rx_max": 1792}) == (1792, "caps") and hw.device_rx_max({})[0] == hw.LEGACY_RX_MAX
          and hw.device_rx_max({"rx_max": 0})[0] == hw.LEGACY_RX_MAX
          and hw.device_rx_max({"rx_max": 9000})[0] == hw.LEGACY_RX_MAX)
    check("CAPS validator: bad rx_max flagged", any("rx_max" in p for p in hw.caps_problems(
        {"board": "b", "chip": "c", "fw": "f", "proto": 0, "modules": [], "features": {}, "rx_max": 0})))
    base = {"board": "b", "chip": "c", "fw": "f", "proto": 0, "modules": [], "features": {}}
    check("CAPS validator: screen/LED state accepted, absent or valid",
          hw.caps_problems(base) == [] and hw.caps_problems({**base, "scrOn": False, "ledOn": True, "scrBright": 0}) == [])
    check("CAPS validator: malformed screen/LED state flagged",
          any("scrOn" in p for p in hw.caps_problems({**base, "scrOn": 0}))
          and any("ledOn" in p for p in hw.caps_problems({**base, "ledOn": "off"}))
          and any("scrBright" in p for p in hw.caps_problems({**base, "scrBright": 101}))
          and any("scrBright" in p for p in hw.caps_problems({**base, "scrBright": True})))
    link = hw.Link(BytePort([]))
    link.rx_max = 64
    try:
        link.send(hw.CMD, {"cmd": "storage list /" + "a" * 80})
        ok = False
    except hw.AllowlistViolation:
        ok = True
    check("a frame above the device rx_max is refused before writing", ok and link.sent == 0)


def test_noise_between(tmp):
    with mock(noise=("between",)) as (dev, path):
        code, rep, paths, log = run_runner(path, [], tmp)
        st = statuses(rep)
        check("noise between frames: every default check PASS", code == 0 and all(v == "PASS" for v in st.values()),
              f"code={code} {st}\n{log[-2000:]}")
        check("noise between frames: skipped bytes equal the injected text",
              rep["link"]["noise_bytes"] == dev.noise_written > 0, f"{rep['link']['noise_bytes']} {dev.noise_written}")
        warned = [w for c in rep["checks"] for w in c["warnings"] if "non-frame byte" in w]
        check("noise between frames: reported as check warnings with a sample",
              warned and "cc1101" in warned[0], str(warned[:2]))
        md = Path(paths[1]).read_text()
        check("noise between frames: Markdown names the noise", "device noise" in md and "resync" in md)


def test_noise_inside(tmp):
    with mock(noise=("between", "inside")) as (dev, path):
        code, rep, _, log = run_runner(path, ["--only", "caps,streams"], tmp)
        st = statuses(rep)
        check("noise inside EVENT frames: no fatal link error, checks PASS",
              code == 0 and rep["fatal"] is None and st == {"caps": "PASS", "streams": "PASS"}, f"code={code} {st}")
        check("noise inside EVENT frames: noise counted", rep["link"]["noise_bytes"] > 0 and rep["link"]["resyncs"] > 0,
              str(rep["link"]))


def upload_check(rep):
    return next(c for c in rep["checks"] if c["id"] == "fs_upload")


def test_rx_queue(tmp):
    only = ["--only", "caps,fs_upload"]
    with mock(rx_queue=1024, caps_rx_max=None) as (dev, path):
        code, rep, _, _ = run_runner(path, only, tmp)
        up = upload_check(rep)
        check("legacy CAPS + 1024-byte RX queue: upload PASS with 768-byte frames, nothing dropped",
              up["status"] == "PASS" and up["evidence"]["rx_max"] == hw.LEGACY_RX_MAX
              and max(up["evidence"]["frame_payloads"]) <= hw.LEGACY_RX_MAX and dev.rx_dropped == 0,
              f"{up['status']} {up['reason']} {up['evidence'].get('frame_payloads')} dropped={dev.rx_dropped}")
        check("legacy CAPS: caps check warns about the missing rx_max",
              any("rx_max" in w for c in rep["checks"] if c["id"] == "caps" for w in c["warnings"]))
    with mock(rx_queue=1024, caps_rx_max=8192) as (dev, path):
        code, rep, _, _ = run_runner(path, only, tmp)
        up = upload_check(rep)
        check("oversized chunks vs a 1024-byte RX queue reproduce the hardware loss (upload FAIL)",
              up["status"] == "FAIL" and dev.rx_dropped > 0, f"{up['status']} dropped={dev.rx_dropped}")
    with mock(rx_queue=2048) as (dev, path):
        code, rep, _, _ = run_runner(path, only, tmp)
        up = upload_check(rep)
        check("non-PSRAM class (2048-byte queue, rx_max 1792): upload PASS, frames fit",
              up["status"] == "PASS" and up["evidence"]["rx_max"] == 1792
              and 1500 < max(up["evidence"]["frame_payloads"]) <= 1792 and dev.rx_dropped == 0,
              f"{up['status']} {up['evidence'].get('frame_payloads')} dropped={dev.rx_dropped}")
    with mock(rx_queue=8448) as (dev, path):
        code, rep, _, _ = run_runner(path, only, tmp)
        up = upload_check(rep)
        check("PSRAM class (8448-byte queue, rx_max 8192): 3072-byte chunks PASS",
              up["status"] == "PASS" and up["evidence"]["chunk_raw"] == hw.UPLOAD_MAX_CHUNK
              and max(up["evidence"]["frame_payloads"]) > 4000 and dev.rx_dropped == 0,
              f"{up['status']} {up['evidence'].get('frame_payloads')}")


def test_interrupt(tmp):
    class Interrupting(hw.Runner):
        def stream_listen(self, stream, collect):
            raise KeyboardInterrupt

    original = hw.Runner
    hw.Runner = Interrupting
    try:
        with mock() as (dev, path):
            code, rep, _, _ = run_runner(path, ["--only", "caps,streams"], tmp)
            check("Ctrl-C: exit 130, report marked interrupted", code == 130 and rep["interrupted"] is True)
            check("Ctrl-C: OFF sent for the active SUB",
                  not dev.subs and dev.received_subs[-1] == {"stream": "wifi.probe", "on": False},
                  f"subs={dev.subs} received={dev.received_subs}")
            check("Ctrl-C: cleanup recorded", any("SUB wifi.probe off" in a[0] for a in rep["cleanup"]),
                  str(rep["cleanup"]))
    finally:
        hw.Runner = original


def script_stop_of(rep):
    return next(c for c in rep["checks"] if c["id"] == "script_stop")


def test_script_stop(tmp):
    with mock() as (dev, path):
        code, rep, _, log = run_runner(path, ["--only", "caps,script_stop,diag_counters"], tmp)
        c = script_stop_of(rep)
        ev = c["evidence"]
        check("script_stop: PASS on a mock that stops the run", code == 0 and c["status"] == "PASS",
              f"code={code} {c}\n{log}")
        check("script_stop: stopped reply, PONG while running, next run printed, stop not answered",
              ev.get("run_reply") == {"ok": False, "out": "script stopped"} and ev.get("ping_while_running")
              and ev.get("next_run_reply") == {"ok": True, "out": hw.SCRIPT_SHORT_OUT + "\n"}
              and ev.get("stop_replies") == [] and ev.get("stop_to_reply_s", 9) <= 2.0, str(ev))
        check("script_stop: exactly the frames of the check, in order",
              dev.received_cmds[-4:] == ["diag", "script_run " + hw.SCRIPT_LONG, "script_stop",
                                         "script_run " + hw.SCRIPT_SHORT] or
              [x for x in dev.received_cmds if x.startswith("script_")] ==
              ["script_run " + hw.SCRIPT_LONG, "script_stop", "script_run " + hw.SCRIPT_SHORT], str(dev.received_cmds))
        check("script_stop: diag counters still match", statuses(rep).get("diag_counters") == "PASS", str(statuses(rep)))
    # Firmware that does not read the link during script_run: over a PTY the PING write itself waits
    # until the run ends at its bound, so the run is seen ending without a stop.
    with mock(faults=("script_stop_ignored",), script_bound=3.0) as (dev, path):
        code, rep, _, _ = run_runner(path, ["--only", "script_stop"], tmp)
        c = script_stop_of(rep)
        check("script_stop: firmware that does not read the stop FAILs",
              code == 1 and c["status"] == "FAIL" and "ended before the stop" in c["reason"]
              and c["evidence"].get("run_reply", {}).get("ok") is True, c["reason"])
    with mock(faults=("script_busy_after_stop",)) as (dev, path):
        code, rep, _, _ = run_runner(path, ["--only", "script_stop"], tmp)
        c = script_stop_of(rep)
        check("script_stop: interpreter busy after the stop FAILs", code == 1 and c["status"] == "FAIL"
              and "interpreter busy" in c["reason"], c["reason"])
    # The device has no global print(): a check using it must fail on the mock too (it did on hardware).
    saved = (hw.SCRIPT_SHORT, hw.SCRIPT_SOURCES)
    hw.SCRIPT_SHORT = "print('brucia-after-stop');"
    hw.SCRIPT_SOURCES = (hw.SCRIPT_LONG, hw.SCRIPT_SHORT)
    try:
        with mock() as (dev, path):
            code, rep, _, _ = run_runner(path, ["--only", "script_stop"], tmp)
            c = script_stop_of(rep)
            check("script_stop: a follow-up script calling print() FAILs (ReferenceError, ok:false)",
                  code == 1 and c["status"] == "FAIL" and "ReferenceError" in c["reason"]
                  and c["evidence"]["next_run_reply"]["ok"] is False, c["reason"])
    finally:
        hw.SCRIPT_SHORT, hw.SCRIPT_SOURCES = saved
    check("script_stop scripts log with console.log, never print()",
          all("console.log(" in x and "print(" not in x for x in hw.SCRIPT_SOURCES))
    with mock(profile="lite") as (dev, path):
        code, rep, _, _ = run_runner(path, ["--only", "script_stop"], tmp)
        c = script_stop_of(rep)
        check("script_stop: SKIP on a LITE build (ERR 501), nothing else sent",
              code == 0 and c["status"] == "SKIP" and dev.received_cmds == ["script_run " + hw.SCRIPT_LONG], str(c))


def test_skip_without_feature(tmp):
    with mock(faults=("no_transactional_uploads",)) as (dev, path):
        code, rep, _, _ = run_runner(path, ["--only", "caps,fs_list,fs_read,fs_upload"], tmp)
        st = statuses(rep)
        check("no transactional_uploads: fs_upload SKIP, rest PASS",
              code == 0 and st == {"caps": "PASS", "fs_list": "PASS", "fs_read": "PASS", "fs_upload": "SKIP"},
              str(st))
        check("no upload command sent", not any(c.startswith("fs_upload") for c in dev.received_cmds))


def test_dry_run():
    out = []
    code = hw.run(["--port", "/dev/brucia-no-such-port", "--dry-run", "--only", ALL], log=out.append)
    text = "\n".join(out)
    check("dry run opens nothing and lists the plan", code == 0 and "fs_upload_begin" in text
          and "jam_detect" in text and "SUB" in text and "script_stop" in text and hw.run.last_report is None)


def test_bad_args():
    for argv in (["--streams", "wifi.ap"], ["--streams", "ble.scan"], ["--only", "tx"], ["--only", "soak"],
                 ["--soak", "0"], ["--soak", "5", "--soak-keepalive", "60"], ["--soak", "5", "--soak-keepalive", "0"],
                 ["--soak", "5", "--streams", "wifi.ap"], ["--soak", "5", "--stream-secs", "45"],
                 ["--script-stop-after", "0"], ["--script-stop-bound", "30"],
                 ["--require-events", "wifi.probe"], ["--soak", "5", "--require-events", "wifi.ap"],
                 ["--soak", "5", "--require-events", "wifi.probe:0"],
                 ["--soak", "5", "--require-events", "wifi.probe:many"]):
        try:
            with contextlib.redirect_stderr(io.StringIO()):
                hw.parse_args(["--port", "x"] + argv)
            ok = False
        except SystemExit:
            ok = True
        check(f"argument refused: {' '.join(argv)}", ok)


# ---------------------------------------------------------------------------------------------
# Soak
# ---------------------------------------------------------------------------------------------


def soak_of(rep):
    return next(c for c in rep["checks"] if c["id"] == "soak")


def test_heap_trend():
    tol, limit = 4096, hw.SOAK_MAX_LEAK_BPH
    noise = [0, 900, -700, 300, -1200, 500, 1100, -400, 0, -800, 600, -300]
    flat = [(i * 300, 100000 + noise[i % len(noise)]) for i in range(24)]  # 2 h, +-1.2 KB
    t = hw.heap_trend(flat, limit, tol)
    check("heap trend: flat but noisy heap over 2 h is not a leak", t["assessed"] and not t["leak"], str(t))
    leak = [(i * 300, 100000 - 8000 * i * 300 / 3600 + noise[i % len(noise)]) for i in range(24)]
    t = hw.heap_trend(leak, limit, tol)
    check("heap trend: 8 KB/h decline over 2 h is a leak with the slope reported",
          t["leak"] and -9000 < t["slope_bph"] < -7000, str(t))
    slow = [(i * 300, 100000 - 1000 * i * 300 / 3600) for i in range(24)]
    check("heap trend: 1 KB/h over 2 h stays under the 2 KB/h threshold", not hw.heap_trend(slow, limit, tol)["leak"])
    short = [(i * 30, 100000 - 3000 * i * 30 / 3600) for i in range(40)]  # 20 min at 3 KB/h: 1 KB total
    t = hw.heap_trend(short, limit, tol)
    check("heap trend: a 20-minute run cannot resolve 3 KB/h (detection limit = tolerance / window)",
          not t["leak"] and t["detection_limit_bph"] > 12000, str(t))
    spike = list(flat)
    spike[10] = (spike[10][0], 70000)
    check("heap trend: one low outlier is not a trend (Theil-Sen)", not hw.heap_trend(spike, limit, tol)["leak"])
    check("heap trend: fewer than 3 samples are not assessed",
          not hw.heap_trend(flat[:2], limit, tol)["assessed"] and not hw.heap_trend([], limit, tol)["leak"])


def test_soak_args():
    o = hw.parse_args(["--port", "x", "--soak", "20"])
    check("--soak alone selects the identity checks, the soak and diag_counters",
          o.only == hw.SOAK_DEFAULT_CHECKS and o.soak == 20.0, str(o.only))
    o = hw.parse_args(["--port", "x", "--soak", "1", "--only", "caps,ping"])
    check("--soak with --only appends the soak check", o.only == ["caps", "ping", "soak"], str(o.only))
    check("without --soak the default checks are unchanged",
          hw.parse_args(["--port", "x"]).only == hw.DEFAULT_CHECKS and "soak" not in hw.DEFAULT_CHECKS)
    out = []
    code = hw.run(["--port", "/dev/brucia-no-such-port", "--dry-run", "--soak", "20",
                   "--streams", "wifi.probe,wifi.sta,subghz.raw"], log=out.append)
    text = "\n".join(out)
    check("soak dry run: opens nothing, lists the interval frames (all allowlisted by the plan)",
          code == 0 and hw.run.last_report is None and "[soak] SUB" in text and "subghz.raw" in text
          and "[soak] CMD {\"cmd\": \"fs_upload_commit" in text and "keepalive" in text, text[-600:])


def soak_files(dev):
    return [p for p in dev.files if p.startswith(hw.SCRATCH_PREFIX)]


def test_soak_healthy(tmp):
    # The mock leaves companion mode after 1 s without a host frame; intervals of 2 s leave
    # about 1.4 s idle each, so only the keepalive PINGs keep the session alive.
    extra = SOAK + ["--soak", "0.11", "--soak-interval", "2.0"]
    with mock(idle_exit=1.0) as (dev, path):
        code, rep, paths, log = run_runner(path, extra, tmp)
        st = statuses(rep)
        soak = rep["soak"]
        sm = soak["summary"]
        check("soak healthy: exit 0, every selected check PASS",
              code == 0 and st == {c: "PASS" for c in hw.SOAK_DEFAULT_CHECKS}, f"code={code} {st}\n{log[-1500:]}")
        check("soak healthy: at least 3 intervals, no findings, not stopped",
              sm["intervals"] >= 3 and sm["findings_total"] == 0 and sm["stopped"] is None, str(sm)[:400])
        check("soak keepalive: the mock never left companion mode (1 s idle exit) and keepalives were sent",
              not dev.exited and all(r["keepalives"] >= 1 for r in soak["series"][1:-1]),  # last may end early
              f"exited={dev.exited} {[r['keepalives'] for r in soak['series']]}")
        check("soak healthy: heap trend assessed, flat, no leak",
              sm["heap_free_trend"]["assessed"] and sm["heap_free_trend"]["slope_bph"] == 0
              and not sm["heap_free_trend"]["leak"], str(sm["heap_free_trend"]))
        series = soak["series"][1:]
        check("soak healthy: per-interval counters equal the runner's frames",
              all(r["dev_rx_delta"] == r["host_sent_delta"] > 0 and r["dev_tx_delta"] == r["host_recv_delta"] > 0
                  and r["noise_bytes_delta"] == 0 and r["rejects_delta"] == 0 for r in series), str(series[:2]))
        ops = sm["ops"]
        check("soak healthy: every interval ran pings, list, read, upload, stream and diag (timed)",
              all(ops[o]["n"] == sm["intervals"] and ops[o]["failed"] == 0
                  for o in ("ping_round", "fs_list", "fs_read", "fs_upload", "stream"))
              and ops["diag"]["n"] == sm["intervals"] + 1 and all("ms" in e for e in soak["ops"]), str(ops))
        check("soak healthy: latency percentiles per interval and overall",
              all(r["p50_ms"] is not None and r["p99_ms"] >= r["p50_ms"] for r in series)
              and sm["latency"]["n"] >= sm["intervals"] * 5, str(sm["latency"]))
        check("soak healthy: uptime rises, reset reason unchanged",
              [r["uptime_s"] for r in soak["series"]] == sorted(r["uptime_s"] for r in soak["series"])
              and {r["reset"] for r in soak["series"]} == {"poweron"})
        check("soak healthy: scratch files removed, no SUB left, everything sent allowlisted",
              not soak_files(dev) and not dev.subs and all_sent_allowed(dev) and sm["uploads"] == sm["intervals"],
              f"{soak_files(dev)} {dev.subs}")
        check("soak healthy: no transmitting or active-scan frame reached the mock",
              {d["stream"] for d in dev.received_subs} == {"wifi.probe"}
              and not any(c.split(" ")[0] in ("jam_detect", "stop") for c in dev.received_cmds))
        check("soak healthy: JSON, Markdown and CSV reports", len(paths) == 3 and all(Path(p).exists() for p in paths))
        lines = Path(paths[2]).read_text().splitlines()
        check("soak CSV: header and one row per diag sample",
              lines[0].split(",") == list(hw.SOAK_CSV_COLUMNS) and len(lines) == 1 + len(soak["series"]), lines[:2])
        md = Path(paths[1]).read_text()
        check("soak Markdown: summary, operations and time series sections",
              "## Soak" in md and "### Soak operations" in md and "### Soak time series" in md
              and Path(paths[2]).name in md)


def test_soak_keepalive_too_slow(tmp):
    extra = SOAK + ["--soak", "0.05", "--soak-interval", "2.5", "--soak-keepalive", "1.6"]
    with mock(idle_exit=1.0) as (dev, path):
        code, rep, _, _ = run_runner(path, extra, tmp)
        sm = rep["soak"]["summary"]
        check("soak negative control: a keepalive slower than the idle exit loses the session (FAIL)",
              dev.exited and code == 1 and soak_of(rep)["status"] == "FAIL" and sm["stopped"] == "diag did not answer",
              f"exited={dev.exited} code={code} {sm['findings_by_kind']} {sm['stopped']}")


def test_soak_heap_leak(tmp):
    with mock(faults=("heap_leak",)) as (dev, path):
        code, rep, _, _ = run_runner(path, SOAK, tmp)
        sm = rep["soak"]["summary"]
        trend = sm["heap_free_trend"]
        check("soak heap leak: FAIL with only a heap finding, slope reported per hour",
              code == 1 and soak_of(rep)["status"] == "FAIL" and sm["findings_by_kind"] == {"heap": 1}
              and trend["leak"] and trend["slope_bph"] < -hw.SOAK_MAX_LEAK_BPH, f"{sm['findings_by_kind']} {trend}")
        check("soak heap leak: other checks still PASS", statuses(rep)["diag_counters"] == "PASS")


def test_soak_reboot(tmp):
    with mock(faults=("reboot",)) as (dev, path):
        code, rep, paths, _ = run_runner(path, SOAK, tmp)
        sm = rep["soak"]["summary"]
        f = next((x for x in rep["soak"]["findings"] if x["kind"] == "reboot"), None)
        check("soak reboot: uptime/counter reset is a reboot finding, soak stops, exit 1",
              code == 1 and soak_of(rep)["status"] == "FAIL" and f is not None and "uptime went back" in f["detail"]
              and sm["stopped"] == "unexpected reboot" and sm["intervals"] == hw_mock_reboot_interval(),
              f"code={code} {sm['findings_by_kind']} {sm['stopped']} intervals={sm['intervals']}")
        check("soak reboot: the CSV still has the rows up to the reboot", len(Path(paths[2]).read_text().splitlines())
              == 1 + len(rep["soak"]["series"]) >= 4)


def hw_mock_reboot_interval():
    import mock_device
    # diag calls: run baseline, soak interval 0, then one per interval; the reboot is seen at
    # call index REBOOT_AT_DIAG.
    return mock_device.REBOOT_AT_DIAG - 1


def test_soak_noise(tmp):
    with mock(noise=("between",)) as (dev, path):
        code, rep, paths, _ = run_runner(path, SOAK + ["--only", "caps"], tmp)
        sm = rep["soak"]["summary"]
        kinds = sm["findings_by_kind"]
        series = rep["soak"]["series"][1:]
        check("soak noise: device text between frames is a noise finding (FAIL), counters still exact",
              code == 1 and soak_of(rep)["status"] == "FAIL" and set(kinds) == {"noise"}
              and all(r["dev_rx_delta"] == r["host_sent_delta"] and r["dev_tx_delta"] == r["host_recv_delta"]
                      for r in series), f"{kinds} {series[:1]}")
        check("soak noise: per-interval noise bytes and resyncs in the series",
              sum(r["noise_bytes_delta"] for r in series) > 0 and sum(r["resyncs_delta"] for r in series) > 0)
        md = Path(paths[1]).read_text()
        check("soak noise: findings listed with a sample in the Markdown",
              "### Soak findings" in md and "non-frame byte" in md and "cc1101" in md)


def run_interrupted(tmp, runner_cls, extra):
    original = hw.Runner
    hw.Runner = runner_cls
    try:
        with mock() as (dev, path):
            code, rep, paths, _ = run_runner(path, SOAK + extra, tmp)
            return dev, code, rep, paths
    finally:
        hw.Runner = original


def test_soak_interrupt_stream(tmp):
    class Interrupting(hw.Runner):
        calls = 0

        def stream_listen(self, stream, collect):
            Interrupting.calls += 1
            if Interrupting.calls == 2:
                raise KeyboardInterrupt
            super().stream_listen(stream, collect)

    dev, code, rep, paths = run_interrupted(tmp, Interrupting, ["--soak", "1"])
    sm = (rep.get("soak") or {}).get("summary") or {"interrupted": False}
    check("soak Ctrl-C in a stream: exit 130, report and soak summary marked interrupted",
          code == 130 and rep["interrupted"] and sm["interrupted"] and "interrupted after" in soak_of(rep)["reason"],
          f"code={code} {soak_of(rep)['reason']}")
    check("soak Ctrl-C in a stream: OFF sent, no SUB left, no scratch file",
          not dev.subs and dev.received_subs[-1] == {"stream": "wifi.probe", "on": False} and not soak_files(dev)
          and any("SUB wifi.probe off" in a[0] for a in rep["cleanup"]), f"{dev.subs} {rep['cleanup']}")
    series = (rep.get("soak") or {}).get("series") or []
    check("soak Ctrl-C in a stream: CSV written with the completed and the partial interval",
          len(paths) == 3 and [r["interval"] for r in series] == [0, 1, 2], str(series)[:300])


def test_soak_interrupt_upload(tmp):
    class Interrupting(hw.Runner):
        def fs_read(self, path, timeout):
            if path.startswith(hw.SCRATCH_PREFIX):
                raise KeyboardInterrupt  # after commit, before the remove
            return super().fs_read(path, timeout)

    dev, code, rep, paths = run_interrupted(tmp, Interrupting, ["--soak", "1"])
    removes = [a for a in rep["cleanup"] if a[0].startswith("storage remove " + hw.SCRATCH_PREFIX)]
    check("soak Ctrl-C after an upload commit: cleanup removes the scratch file, report written",
          code == 130 and removes and removes[0][1] is True and not soak_files(dev)
          and any(c.startswith("storage remove " + hw.SCRATCH_PREFIX) for c in dev.received_cmds)
          and ((rep.get("soak") or {}).get("summary") or {}).get("interrupted") and Path(paths[0]).exists(),
          f"code={code} {rep['cleanup']} {soak_files(dev)}")


# ---------------------------------------------------------------------------------------------
# Radio coverage (F43): link verdict and radio coverage are separate verdicts
# ---------------------------------------------------------------------------------------------


def coverage_run(tmp, extra, emit=True, runner_cls=None):
    """A short soak with only the soak check; emit=False disables the mock's synthetic events
    (the radio is quiet: nothing is made to emit)."""
    original = hw.Runner
    if runner_cls is not None:
        hw.Runner = runner_cls
    try:
        with mock() as (dev, path):
            if not emit:
                dev.emit_interval = 1e20
            code, rep, paths, log = run_runner(path, SOAK + ["--only", "soak"] + extra, tmp)
            return dev, code, rep, paths, log
    finally:
        hw.Runner = original


def test_coverage_zero_events(tmp):
    dev, code, rep, paths, log = coverage_run(tmp, ["--require-events", "wifi.probe"], emit=False)
    cov, s = rep["radio_coverage"], rep["summary"]
    check("coverage zero events: link PASS but coverage INSUFFICIENT, gate NOT MET, exit 3",
          code == hw.EXIT_COVERAGE == 3 and s["result"] == "PASS" and s["coverage"] == "INSUFFICIENT"
          and s["gate"] == "NOT MET" and cov["streams"]["wifi.probe"]["events"] == 0
          and cov["streams"]["wifi.probe"]["verdict"] == "INSUFFICIENT", f"code={code} {s} {cov}")
    check("coverage zero events: nothing transmitting sent, only wifi.probe SUBs",
          all_sent_allowed(dev) and {d["stream"] for d in dev.received_subs} == {"wifi.probe"})
    md = Path(paths[1]).read_text()
    check("coverage zero events: Markdown states INSUFFICIENT and NOT MET with the per-stream table",
          "## Radio coverage" in md and "| wifi.probe | INSUFFICIENT | 0 | 1 |" in md and "**NOT MET**" in md
          and "radio gate NOT MET" in log, md[:600])


def test_coverage_few_events(tmp):
    _, code, rep, _, _ = coverage_run(tmp, ["--require-events", "wifi.probe:1000"])
    e = rep["radio_coverage"]["streams"]["wifi.probe"]
    check("coverage few events: some events but below the minimum -> INSUFFICIENT, exit 3",
          code == 3 and rep["summary"]["result"] == "PASS" and 0 < e["events"] < 1000
          and e["verdict"] == "INSUFFICIENT" and "< required 1000" in e["reason"], f"code={code} {e}")


def test_coverage_sufficient(tmp):
    _, code, rep, paths, _ = coverage_run(tmp, ["--require-events", "wifi.probe:2", "--stream-secs", "0.7"])
    cov = rep["radio_coverage"]
    check("coverage sufficient: enough events over the full duration -> SUFFICIENT, gate MET, exit 0",
          code == 0 and rep["summary"]["result"] == "PASS" and cov["overall"] == "SUFFICIENT"
          and rep["summary"]["gate"] == "MET" and cov["streams"]["wifi.probe"]["events"] >= 2
          and cov["duration"]["verdict"] == "SUFFICIENT", f"code={code} {cov}")
    check("coverage sufficient: soak summary has effective minutes >= planned and started cycles",
          rep["soak"]["summary"]["effective_min"] >= rep["soak"]["summary"]["planned_min"]
          and rep["soak"]["summary"]["stream_started"]["wifi.probe"] >= 1, str(rep["soak"]["summary"])[:300])


def test_coverage_interrupted(tmp):
    class Interrupting(hw.Runner):
        calls = 0

        def stream_listen(self, stream, collect):
            Interrupting.calls += 1
            if Interrupting.calls == 3:
                raise KeyboardInterrupt
            super().stream_listen(stream, collect)

    _, code, rep, _, _ = coverage_run(tmp, ["--soak", "1", "--require-events", "wifi.probe:1"],
                                      runner_cls=Interrupting)
    cov = rep["radio_coverage"]
    check("coverage Ctrl-C: events seen but the run ended early -> duration and coverage INSUFFICIENT",
          code == 130 and cov["overall"] == "INSUFFICIENT" and cov["duration"]["verdict"] == "INSUFFICIENT"
          and cov["duration"]["effective_min"] < 1 and rep["summary"]["gate"] == "NOT MET", f"code={code} {cov}")


def test_coverage_stream_absent(tmp):
    # The mock's CAPS has no cc1101: subghz.raw is required but cannot run.
    _, code, rep, _, _ = coverage_run(tmp, ["--require-events", "subghz.raw"])
    cov = rep["radio_coverage"]
    e = cov["streams"]["subghz.raw"]
    check("coverage stream absent from CAPS: NOT RUN, gate NOT MET, exit 3",
          code == 3 and rep["summary"]["result"] == "PASS" and e["verdict"] == "NOT RUN"
          and "CAPS" in e["reason"] and cov["overall"] == "NOT RUN"
          and "subghz.raw" in rep["soak"]["config"]["streams_requested"], f"code={code} {cov}")


def test_coverage_never_started(tmp):
    class Refusing(hw.Runner):
        """SUB ON answered with an ERR (as a busy radio would): the stream never starts."""

        def stream_cycle(self, stream, info):
            real, seen = self.link.reply, []

            def reply(mid, timeout, on_other=None):
                f = real(mid, timeout, on_other)
                seen.append(f)
                return {"t": hw.ERR, "d": {"code": 409, "msg": "busy (simulated)"}} if len(seen) == 1 else f
            self.link.reply = reply
            try:
                return super().stream_cycle(stream, info)
            finally:
                self.link.reply = real

    _, code, rep, _, _ = coverage_run(tmp, ["--require-events", "wifi.probe"], runner_cls=Refusing)
    e = rep["radio_coverage"]["streams"]["wifi.probe"]
    check("coverage stream never started: NOT RUN, link FAIL (SUB not ACKed), gate NOT MET",
          code == 1 and e["verdict"] == "NOT RUN" and e["started"] == 0 and e["cycles"] >= 1
          and "never started" in e["reason"] and rep["summary"]["gate"] == "NOT MET", f"code={code} {e}")


def test_coverage_not_required(tmp):
    _, code, rep, paths, _ = coverage_run(tmp, [], emit=False)
    md = Path(paths[1]).read_text()
    check("no requirement: zero events, link PASS, exit 0, coverage NOT REQUIRED stated in JSON and Markdown",
          code == 0 and rep["summary"]["result"] == "PASS" and rep["summary"]["coverage"] == "NOT REQUIRED"
          and rep["summary"]["gate"] == "NOT REQUIRED" and "radio coverage NOT REQUIRED" in soak_of(rep)["reason"]
          and "Radio coverage: **NOT REQUIRED**" in md and "not evidence under radio traffic" in md,
          f"code={code} {rep['summary']}")


def test_coverage_args():
    o = hw.parse_args(["--port", "x", "--soak", "5", "--streams", "wifi.probe",
                       "--require-events", "wifi.sta:7,subghz.raw", "--require-events", "wifi.probe:3"])
    check("--require-events: parsed with defaults, required streams added to --streams",
          o.require_events == {"wifi.sta": 7, "subghz.raw": 1, "wifi.probe": 3}
          and o.streams == ["wifi.probe", "wifi.sta", "subghz.raw"], f"{o.require_events} {o.streams}")
    out = []
    hw.run(["--port", "/dev/brucia-no-such-port", "--dry-run", "--soak", "5", "--require-events", "wifi.sta:4"],
           log=out.append)
    check("--require-events dry run: states the requirement", "radio coverage required: wifi.sta >= 4" in "\n".join(out))


class RadioCoverageTest(unittest.TestCase):
    """radio_coverage() verdicts (pure) and the zero-events soak against the mock (F43)."""

    @staticmethod
    def summary(**kw):
        sm = {"stream_events": {"wifi.probe": 0}, "stream_cycles": {"wifi.probe": 3},
              "stream_started": {"wifi.probe": 3}, "streams_unsupported": [], "interrupted": False,
              "stopped": None, "effective_min": 1.02}
        sm.update(kw)
        return sm

    def test_not_required(self):
        cov = hw.radio_coverage({}, 1, self.summary())
        self.assertEqual(cov["overall"], "NOT REQUIRED")
        self.assertIn("NOT RUN", cov["reasons"][0])

    def test_zero_events_insufficient(self):
        cov = hw.radio_coverage({"wifi.probe": 1}, 1, self.summary())
        self.assertEqual((cov["overall"], cov["streams"]["wifi.probe"]["verdict"]), ("INSUFFICIENT", "INSUFFICIENT"))

    def test_few_events_insufficient(self):
        cov = hw.radio_coverage({"wifi.probe": 10}, 1, self.summary(stream_events={"wifi.probe": 9}))
        self.assertEqual(cov["overall"], "INSUFFICIENT")

    def test_sufficient(self):
        cov = hw.radio_coverage({"wifi.probe": 10}, 1, self.summary(stream_events={"wifi.probe": 10}))
        self.assertEqual((cov["overall"], cov["duration"]["verdict"]), ("SUFFICIENT", "SUFFICIENT"))
        self.assertEqual(cov["reasons"], [])

    def test_short_or_interrupted_or_stopped(self):
        ev = {"wifi.probe": 50}
        for kw in ({"effective_min": 0.5}, {"interrupted": True}, {"stopped": "unexpected reboot"}):
            cov = hw.radio_coverage({"wifi.probe": 1}, 1, self.summary(stream_events=ev, **kw))
            self.assertEqual((cov["overall"], cov["duration"]["verdict"]), ("INSUFFICIENT", "INSUFFICIENT"), kw)

    def test_absent_and_never_started(self):
        cov = hw.radio_coverage({"subghz.raw": 1}, 1, self.summary(streams_unsupported=["subghz.raw"]))
        self.assertEqual((cov["overall"], cov["streams"]["subghz.raw"]["verdict"]), ("NOT RUN", "NOT RUN"))
        cov = hw.radio_coverage({"wifi.probe": 1, "subghz.raw": 1}, 1,
                                self.summary(stream_events={"wifi.probe": 5}, streams_unsupported=["subghz.raw"]))
        self.assertEqual(cov["overall"], "INSUFFICIENT")  # one stream sufficient does not cover the other
        cov = hw.radio_coverage({"wifi.probe": 1}, 1, self.summary(stream_started={"wifi.probe": 0}))
        self.assertEqual(cov["streams"]["wifi.probe"]["verdict"], "NOT RUN")
        self.assertIn("never started", cov["streams"]["wifi.probe"]["reason"])

    def test_soak_never_ran(self):
        cov = hw.radio_coverage({"wifi.probe": 1}, 1, None)
        self.assertEqual((cov["overall"], cov["duration"]["verdict"]), ("NOT RUN", "NOT RUN"))

    def test_args(self):
        with contextlib.redirect_stderr(io.StringIO()):
            for argv in (["--require-events", "wifi.probe"], ["--soak", "1", "--require-events", "wifi.ap"],
                         ["--soak", "1", "--require-events", "wifi.probe:0"]):
                with self.assertRaises(SystemExit, msg=argv):
                    hw.parse_args(["--port", "x"] + argv)
        o = hw.parse_args(["--port", "x", "--soak", "1", "--require-events", "subghz.raw:2"])
        self.assertEqual((o.require_events, o.streams), ({"subghz.raw": 2}, ["wifi.probe", "subghz.raw"]))

    def test_zero_events_soak_does_not_pass(self):
        """The F43 repro: a soak with emission disabled and a requirement must not pass."""
        with tempfile.TemporaryDirectory() as tmp:
            dev, code, rep, _, _ = coverage_run(tmp, ["--require-events", "wifi.probe"], emit=False)
        self.assertEqual(rep["soak"]["summary"]["stream_events"], {"wifi.probe": 0})
        self.assertEqual(rep["summary"]["result"], "PASS")  # the link itself is healthy
        self.assertEqual((rep["summary"]["coverage"], rep["summary"]["gate"]), ("INSUFFICIENT", "NOT MET"))
        self.assertEqual(code, hw.EXIT_COVERAGE)


def main():
    with tempfile.TemporaryDirectory() as tmp:
        test_allowlist()
        test_resync_reader()
        test_upload_sizing()
        test_bad_args()
        test_dry_run()
        test_healthy(tmp)
        test_counter_mismatch(tmp)
        test_fault(tmp, "heap_leak", "heap", "heap leak")
        test_fault(tmp, "event_after_off", "streams", "event after OFF")
        test_interrupt(tmp)
        test_skip_without_feature(tmp)
        test_script_stop(tmp)
        test_noise_between(tmp)
        test_noise_inside(tmp)
        test_rx_queue(tmp)
        test_heap_trend()
        test_soak_args()
        test_soak_healthy(tmp)
        test_soak_keepalive_too_slow(tmp)
        test_soak_heap_leak(tmp)
        test_soak_reboot(tmp)
        test_soak_noise(tmp)
        test_soak_interrupt_stream(tmp)
        test_soak_interrupt_upload(tmp)
        test_coverage_args()
        test_coverage_zero_events(tmp)
        test_coverage_few_events(tmp)
        test_coverage_sufficient(tmp)
        test_coverage_interrupted(tmp)
        test_coverage_stream_absent(tmp)
        test_coverage_never_started(tmp)
        test_coverage_not_required(tmp)
    ok = all(v for _, v in results)
    print(f"{sum(v for _, v in results)}/{len(results)} checks passed")
    print("HW ACCEPTANCE TEST PASS" if ok else "HW ACCEPTANCE TEST FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
