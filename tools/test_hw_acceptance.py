#!/usr/bin/env python3
"""Host test of tools/hw_acceptance.py against tools/mock_device.py over a PTY. No hardware.

Runs the full runner (all checks, including the opt-in busy check) against a healthy mock and
expects PASS with JSON + Markdown reports; then against mocks with injected faults (diag counter
mismatch, heap leak, stream events after OFF) and expects the matching checks to FAIL; checks
the allowlist refuses every transmitting command before a byte is written, that Ctrl-C sends OFF
for an active SUB, that --dry-run opens nothing and that a missing feature yields SKIP.
Link tests: the reader resyncs after device text between/inside frames (counted, reported as a
warning, never fatal) and upload chunks follow CAPS rx_max (legacy 768 without it) so they fit a
simulated small RX queue that drops bytes, while oversized chunks reproduce the hardware loss.
This proves the runner, not the device.

    python3 tools/test_hw_acceptance.py
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
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import hw_acceptance as hw  # noqa: E402
from mock_device import MockDevice  # noqa: E402

FAST = ["--pings", "40", "--ping-burst", "10", "--stream-secs", "1.5", "--quiet-secs", "1.0",
        "--diag-repeats", "8", "--timeout", "2", "--fs-timeout", "5", "--busy-secs", "6"]
ALL = ",".join(hw.CHECK_IDS)

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
        expected = {c: "PASS" for c in hw.CHECK_IDS}
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
              and counters["delta"]["busy_409"] == 1 and counters["delta"]["rx_bad_json"] == 1, str(counters["delta"]))
        jp, mp = paths
        data = json.loads(Path(jp).read_text())
        md = Path(mp).read_text()
        check("JSON report: provenance, timestamps, checks, commands",
              data["device"]["build"]["id"] == "mock" and data["started_utc"].endswith("Z")
              and data["finished_utc"].endswith("Z") and len(data["checks"]) == len(hw.CHECK_IDS)
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
               (hw.CMD, {"cmd": "storage remove /brucia-acceptance-20261105T101010Z-0a1b2c.txt"})]
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
          and "jam_detect" in text and "SUB" in text and hw.run.last_report is None)


def test_bad_args():
    for argv in (["--streams", "wifi.ap"], ["--streams", "ble.scan"], ["--only", "tx"]):
        try:
            with contextlib.redirect_stderr(io.StringIO()):
                hw.parse_args(["--port", "x"] + argv)
            ok = False
        except SystemExit:
            ok = True
        check(f"argument refused: {' '.join(argv)}", ok)


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
        test_noise_between(tmp)
        test_noise_inside(tmp)
        test_rx_queue(tmp)
    ok = all(v for _, v in results)
    print(f"{sum(v for _, v in results)}/{len(results)} checks passed")
    print("HW ACCEPTANCE TEST PASS" if ok else "HW ACCEPTANCE TEST FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
