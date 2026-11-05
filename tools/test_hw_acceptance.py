#!/usr/bin/env python3
"""Host test of tools/hw_acceptance.py against tools/mock_device.py over a PTY. No hardware.

Runs the full runner (all checks, including the opt-in busy check) against a healthy mock and
expects PASS with JSON + Markdown reports; then against mocks with injected faults (diag counter
mismatch, heap leak, stream events after OFF) and expects the matching checks to FAIL; checks
the allowlist refuses every transmitting command before a byte is written, that Ctrl-C sends OFF
for an active SUB, that --dry-run opens nothing and that a missing feature yields SKIP.
This proves the runner, not the device.

    python3 tools/test_hw_acceptance.py
"""
import contextlib
import io
import json
import os
import pty
import sys
import tempfile
import threading
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
def mock(faults=()):
    master, slave = pty.openpty()
    dev = MockDevice(faults=faults, poll_interval=0.002, emit_interval=0.3)
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
        test_bad_args()
        test_dry_run()
        test_healthy(tmp)
        test_counter_mismatch(tmp)
        test_fault(tmp, "heap_leak", "heap", "heap leak")
        test_fault(tmp, "event_after_off", "streams", "event after OFF")
        test_interrupt(tmp)
        test_skip_without_feature(tmp)
    ok = all(v for _, v in results)
    print(f"{sum(v for _, v in results)}/{len(results)} checks passed")
    print("HW ACCEPTANCE TEST PASS" if ok else "HW ACCEPTANCE TEST FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
