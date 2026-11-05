#!/usr/bin/env python3
"""Automated end-to-end test of the companion protocol, no hardware needed.

Runs the mock device (on the PTY master, in a thread) and a host client (on the
slave, via pyserial) and asserts the full round-trip: HELLO->CAPS, PING->PONG,
CMD->ACK(+out), and a deduplicated wifi.ap stream. Exit code 0 = PASS.

The lite-control state (CAPS scrOn/ledOn/scrBright after scr/led/scr_bright, a reconnect seeing it,
the 15 s restore, and an older firmware without the keys) runs against tools/mock_device.py.

    python3 tools/test_protocol_e2e.py
"""
import json
import os
import pty
import struct
import sys
import threading
import time
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mock_device import LinkCounters, MockDevice, diag_reply  # noqa: E402  (shared diag shape + counters)

ROOT = Path(__file__).resolve().parents[1]
OUTPUT_KEYS = ("scrOn", "ledOn", "scrBright")

HELLO, CAPS, CMD, ACK, ERR, EVENT, DATA, SUB, PING, PONG = range(1, 11)

# Capture files the mock reports via the descriptor and serves back on fs_read, so the descriptor's
# size/crc32 match the transferred bytes (the identity the app relies on, proto/PROTOCOL.md).
E2E_CAPTURES = {}


def e2e_capture_descriptor(cmd):
    parts = cmd.split()
    bssid = parts[1] if len(parts) > 1 else "AA:BB:CC:DD:EE:FF"
    mac = bssid.replace(":", "").upper()
    ssid = parts[4] if len(parts) > 4 else ""
    path = f"/BrucePCAP/handshakes/HS_{mac}" + (f"_{ssid}" if ssid else "") + ".pcap"
    prior = E2E_CAPTURES.get(path, b"")                 # handshake files accumulate across runs (append)
    body = (prior or b"\xd4\xc3\xb2\xa1") + b"BRUCIA-E2E-HS-" + mac.encode()
    E2E_CAPTURES[path] = body
    desc = {"v": 1, "path": path, "size": len(body), "crc32": zlib.crc32(body) & 0xFFFFFFFF,
            "frames": 4, "handshake": True, "complete": True, "stop": "complete",
            "time_src": "device_uptime_ms", "uptime_ms": 12345}
    if prior:
        desc["appended"] = True
        desc["prior_size"] = len(prior)
    return desc

# CAPS d.evidence contract (proto/PROTOCOL.md "Capability evidence").
EVIDENCE_LEVELS = {"none", "compiled", "configured", "probed", "initialized"}
EVIDENCE_PROBES = {"present", "absent", "busy"}
E2E_EVIDENCE = {
    "cc1101": {"level": "probed", "probe": "present", "detail": "SPI PARTNUM 0x00 VERSION 0x14"},
    "nrf24": {"level": "configured", "probe": "absent", "detail": "SPI SETUP_AW readback invalid",
              "conflict": [{"gpio": 44, "role": "cs", "with": "eth", "with_role": "cs"}]},
    "lora": {"level": "initialized", "probe": "present", "detail": "RadioLib SX1276 begin ok; left asleep"},
    "ibutton": {"level": "compiled"},
}


def evidence_valid(ev):
    """Shape check for d.evidence; absent (older firmware) is valid."""
    if ev is None:
        return True
    if not isinstance(ev, dict):
        return False
    for name, m in ev.items():
        if not isinstance(m, dict) or m.get("level") not in EVIDENCE_LEVELS:
            return False
        if "probe" in m and m["probe"] not in EVIDENCE_PROBES:
            return False
        # "probed"/"initialized" need a positive probe; "present" never sits below "probed".
        if m["level"] in ("probed", "initialized") and m.get("probe") != "present":
            return False
        if m.get("probe") == "present" and m["level"] not in ("probed", "initialized"):
            return False
        if "detail" in m and not isinstance(m["detail"], str):
            return False
        for c in m.get("conflict", []):
            if not (isinstance(c.get("gpio"), int) and all(isinstance(c.get(k), str) for k in ("role", "with", "with_role"))):
                return False
    return True


DIAG_U32 = 0xFFFFFFFF
DIAG_RESET_NAMES = {"unknown", "poweron", "ext", "sw", "panic", "int_wdt", "task_wdt", "wdt", "deepsleep",
                    "brownout", "sdio", "usb", "jtag", "efuse", "pwr_glitch", "cpu_lockup", "other"}
DIAG_LINK_KEYS = ["sessions", "rx_frames", "tx_frames", "rx_zero_len", "rx_oversize", "rx_alloc_fail",
                  "rx_stale", "rx_bad_json", "busy_409", "err"]
DIAG_ERR_KEYS = ["400", "404", "409", "413", "4xx", "5xx", "other"]


def _u32(v):
    return isinstance(v, int) and not isinstance(v, bool) and 0 <= v <= DIAG_U32


def diag_valid(d):
    """Shape check for the `diag` ACK body (proto/PROTOCOL.md "Recovery diagnostics")."""
    if not isinstance(d, dict) or d.get("ok") is not True or d.get("v") != 1:
        return False
    if d.get("reset") not in DIAG_RESET_NAMES or not _u32(d.get("reset_code")) or not _u32(d.get("uptime_s")):
        return False
    heap = d.get("heap")
    if not isinstance(heap, dict) or not all(_u32(heap.get(k)) for k in ("free", "min_free", "largest")):
        return False
    if "psram" in d and not all(_u32(d["psram"].get(k)) for k in ("size", "free", "min_free", "largest")):
        return False
    link = d.get("link")
    if not isinstance(link, dict) or set(link) != set(DIAG_LINK_KEYS) or set(link["err"]) != set(DIAG_ERR_KEYS):
        return False
    if not all(_u32(link[k]) for k in DIAG_LINK_KEYS[:-1]) or not all(_u32(v) for v in link["err"].values()):
        return False
    drops = d.get("drops")
    if not isinstance(drops, dict):
        return False
    return all(_u32(drops[k]) for k in ("fm_pcm_lost", "mic_pcm_dropped") if k in drops) and \
        isinstance(drops.get("fm_pcm_active", False), bool)


def frame(obj):
    p = json.dumps(obj).encode()
    return struct.pack("<I", len(p)) + p


def _wwrite(master, data):
    import time as _t
    off = 0
    while off < len(data):
        try:
            off += os.write(master, data[off:])
        except BlockingIOError:
            _t.sleep(0.005)


def mock_serve(master, stop):
    buf = b""
    subs = set()
    os.set_blocking(master, False)
    last = 0.0
    counters = LinkCounters()
    started = time.time()

    def out(obj):
        _wwrite(master, frame(obj))
        counters.note_sent(obj["t"], obj.get("d"))

    while not stop.is_set():
        try:
            c = os.read(master, 4096)
            if c:
                buf += c
        except (BlockingIOError, OSError):
            pass
        while len(buf) >= 4:
            n = struct.unpack("<I", buf[:4])[0]
            if len(buf) < 4 + n:
                break
            raw = buf[4:4 + n]; buf = buf[4 + n:]
            counters.note("rx_frames")
            try:
                m = json.loads(raw)
            except ValueError:
                counters.note("rx_bad_json")
                out({"v": 0, "t": ERR, "id": 0, "d": {"code": 400, "msg": "bad json"}})
                continue
            t, mid, d = m.get("t"), m.get("id", 0), m.get("d", {})
            if t == HELLO:
                out({"v": 0, "t": CAPS, "id": mid, "d": {"board": "mock", "chip": "esp32-s3", "proto": 0, "modules": ["wifi"], "features": {"diag": True, "verified_files": True, "capture_descriptor": True}, "evidence": E2E_EVIDENCE}})
            elif t == PING:
                out({"v": 0, "t": PONG, "id": mid, "d": {}})
            elif t == CMD:
                cmd = d.get("cmd", "")
                if cmd == "diag":
                    out({"v": 0, "t": ACK, "id": mid, "d": diag_reply(counters, time.time() - started)})
                elif cmd.startswith("fs_read"):
                    import base64 as _b
                    path = cmd[len("fs_read"):].strip()
                    content = E2E_CAPTURES.get(path, b"BRUCIA-E2E-FILE-" + (b"z" * 1500))
                    for i in range(0, len(content), 768):
                        out({"v": 0, "t": DATA, "id": 0, "d": {"stream": "file", "seq": i // 768, "chunk": _b.b64encode(content[i:i+768]).decode()}})
                    out({"v": 0, "t": ACK, "id": mid, "d": {"ok": True, "size": len(content), "crc32": zlib.crc32(content) & 0xFFFFFFFF}})
                elif cmd.startswith("wifi_handshake") or cmd.startswith("wifi_pmkid"):
                    out({"v": 0, "t": ACK, "id": mid, "d": {"ok": True, "out": "mock capture", "desc": e2e_capture_descriptor(cmd)}})
                else:
                    out({"v": 0, "t": ACK, "id": mid, "d": {"ok": True, "out": "ok:" + cmd}})
            elif t == SUB:
                if d.get("on"):
                    subs.add(d.get("stream"))
                out({"v": 0, "t": ACK, "id": mid, "d": {"ok": True}})
        if time.time() - last > 0.2 and "wifi.ap" in subs:
            last = time.time()
            for ap in [{"bssid": "AA:BB:CC:00:11:22", "ssid": "N1", "ch": 6, "rssi": -50, "enc": "wpa2"},
                       {"bssid": "AA:BB:CC:00:11:22", "ssid": "N1", "ch": 6, "rssi": -52, "enc": "wpa2"}]:
                out({"v": 0, "t": EVENT, "id": 0, "d": {"ev": "wifi.ap", "data": ap}})
        time.sleep(0.01)


class FrameClient:
    """Length-prefixed JSON client on a PTY slave (pyserial)."""

    def __init__(self, dev_path):
        import serial  # pyserial
        self.ser = serial.Serial(dev_path, 115200, timeout=2)

    def send(self, kind, mid, d):
        p = json.dumps({"v": 0, "t": kind, "id": mid, "d": d}).encode()
        self.ser.write(struct.pack("<I", len(p)) + p)

    def _exact(self, k):
        b = b""
        while len(b) < k:
            c = self.ser.read(k - len(b))
            if not c:
                return None
            b += c
        return b

    def reply(self, mid, kind, timeout=3):
        deadline = time.time() + timeout
        while time.time() < deadline:
            head = self._exact(4)
            if head is None:
                continue
            body = self._exact(struct.unpack("<I", head)[0])
            m = json.loads(body) if body else None
            if m and m.get("id") == mid and m.get("t") == kind:
                return m
        return None

    def caps(self, mid):
        self.send(HELLO, mid, {"app": "e2e"})
        m = self.reply(mid, CAPS)
        return m["d"] if m else None

    def cmd(self, mid, text):
        self.send(CMD, mid, {"cmd": text})
        m = self.reply(mid, ACK)
        return m["d"] if m else None

    def close(self):
        self.ser.close()


def output_state(caps):
    """The app's reading of CAPS: (screenOn, ledOn, scrBright), None for each key an older firmware omits."""
    def b(k):
        v = (caps or {}).get(k)
        return v if isinstance(v, bool) else None
    lvl = (caps or {}).get("scrBright")
    return b("scrOn"), b("ledOn"), lvl if isinstance(lvl, int) and not isinstance(lvl, bool) and 0 <= lvl <= 100 else None


def outputs_checks(checks):
    """Screen/LED state in CAPS (proto/PROTOCOL.md "Lite controls"): firmware and mock agree on the
    keys, a reconnect (fresh HELLO) sees what the previous session set, and older firmware omits them."""
    caps_src = (ROOT / "src/companion/capabilities.cpp").read_text()
    checks.append(("firmware CAPS reports scrOn/ledOn/scrBright",
                   all(f'd["{k}"]' in caps_src for k in OUTPUT_KEYS)))
    for legacy in (False, True):
        master, slave = pty.openpty()
        dev = MockDevice(poll_interval=0.002, legacy_outputs=legacy)
        stop = threading.Event()
        th = threading.Thread(target=dev.serve, args=(master, stop), daemon=True)
        th.start()
        cli = FrameClient(os.ttyname(slave))
        try:
            first = cli.caps(1)
            acks = [cli.cmd(2, "scr 0"), cli.cmd(3, "led 0")]
            cli.close()  # app gone: a new session starts with HELLO
            cli = FrameClient(os.ttyname(slave))
            again = cli.caps(4)
            if legacy:
                checks.append(("older firmware: CAPS without screen/LED state",
                               first is not None and again is not None
                               and not any(k in d for d in (first, again) for k in OUTPUT_KEYS)
                               and output_state(again) == (None, None, None)))
                continue
            checks.append(("CAPS: screen/LED on at session start", output_state(first) == (True, True, 100)))
            checks.append(("scr 0 / led 0 ACK ok", all(a and a.get("ok") is True for a in acks)))
            checks.append(("reconnect: CAPS reports screen/LED off", output_state(again) == (False, False, 0)))
            lvl = cli.cmd(5, "scr_bright 40")
            checks.append(("scr_bright: visible level ends the scr 0 hold",
                           lvl is not None and lvl.get("out") == "screen 40%"
                           and output_state(cli.caps(6)) == (True, False, 40)))
            dev._last_frame -= 16  # 15 s without a frame: the device turns both back on
            time.sleep(0.05)
            checks.append(("15 s silence restores screen/LED", output_state(cli.caps(7)) == (True, True, 100)))
        finally:
            cli.close()
            stop.set()
            th.join(timeout=5)
            os.close(master)
            os.close(slave)


def main():
    import serial  # pyserial
    master, slave = pty.openpty()
    stop = threading.Event()
    th = threading.Thread(target=mock_serve, args=(master, stop), daemon=True)
    th.start()
    ser = serial.Serial(os.ttyname(slave), 115200, timeout=2)

    def send(kind, mid, d):
        p = json.dumps({"v": 0, "t": kind, "id": mid, "d": d}).encode()
        ser.write(struct.pack("<I", len(p)) + p)

    def read_exact(k):
        b = b""
        while len(b) < k:
            c = ser.read(k - len(b))
            if not c:
                return None
            b += c
        return b

    def recv():
        head = read_exact(4)
        if head is None:
            return None
        n = struct.unpack("<I", head)[0]
        data = read_exact(n)
        if data is None:
            return None
        return json.loads(data)

    checks = []
    send(HELLO, 1, {"app": "e2e"})
    caps = recv()
    checks.append(("HELLO->CAPS", caps and caps["t"] == CAPS and caps["d"]["chip"] == "esp32-s3"))
    checks.append(("CAPS evidence shape", caps and evidence_valid(caps["d"].get("evidence"))
                   and not evidence_valid({"x": {"level": "tested"}})
                   and not evidence_valid({"x": {"level": "configured", "probe": "present"}})
                   and evidence_valid(None)))

    send(PING, 2, {})
    pong = recv()
    checks.append(("PING->PONG", pong and pong["t"] == PONG))

    send(CMD, 3, {"cmd": "wifi scan"})
    ack = recv()
    checks.append(("CMD->ACK+out", ack and ack["t"] == ACK and ack["d"]["out"] == "ok:wifi scan"))

    send(SUB, 4, {"stream": "wifi.ap", "on": True})
    seen = {}
    deadline = time.time() + 3
    while time.time() < deadline and len(seen) < 1:
        m = recv()
        if m and m.get("t") == EVENT and m["d"].get("ev") == "wifi.ap":
            seen[m["d"]["data"]["bssid"]] = m["d"]["data"]
    checks.append(("wifi.ap stream + dedup", len(seen) == 1))  # two events, same bssid -> 1 unique

    # file pull (fs_read)
    import base64 as _b
    send(CMD, 6, {"cmd": "fs_read /BrucePCAP/test.bin"})
    buf = bytearray()
    file_ok = False
    deadline = time.time() + 3
    while time.time() < deadline:
        m = recv()
        if m is None:
            continue
        if m.get("t") == DATA and m["d"].get("stream") == "file":
            buf += _b.b64decode(m["d"]["chunk"])
        elif m.get("t") == ACK and "size" in m.get("d", {}):
            file_ok = len(buf) == m["d"]["size"] and buf.startswith(b"BRUCIA-E2E-FILE-")
            break

    checks.append(("fs_read pull", file_ok))

    # capture descriptor: wifi_handshake ACK carries d.desc for the file written this run, and the
    # descriptor's size/crc32 match the bytes a following fs_read returns (what the app verifies).
    caps_feats = (caps["d"].get("features") or {}) if caps else {}
    checks.append(("CAPS advertises capture_descriptor", caps_feats.get("capture_descriptor") is True))
    send(CMD, 7, {"cmd": "wifi_handshake AA:BB:CC:00:11:22 6 20 NET1"})
    hs = recv()
    desc = hs["d"].get("desc") if (hs and hs.get("t") == ACK) else None
    desc_shape = bool(desc) and desc.get("v") == 1 and isinstance(desc.get("path"), str) \
        and desc["path"].startswith("/BrucePCAP/handshakes/") and desc.get("complete") is True \
        and desc.get("stop") == "complete" and desc.get("time_src") == "device_uptime_ms" \
        and isinstance(desc.get("size"), int) and isinstance(desc.get("crc32"), int)
    checks.append(("capture descriptor shape", desc_shape))
    # Never a wall-clock capture timestamp the device cannot know.
    checks.append(("descriptor claims no wall-clock time", desc_shape and "ts" not in desc and "time" not in desc))

    desc_verified = False
    if desc_shape:
        send(CMD, 8, {"cmd": "fs_read " + desc["path"]})
        buf = bytearray()
        deadline = time.time() + 3
        while time.time() < deadline:
            m = recv()
            if m is None:
                continue
            if m.get("t") == DATA and m["d"].get("stream") == "file":
                buf += _b.b64decode(m["d"]["chunk"])
            elif m.get("t") == ACK and "size" in m.get("d", {}):
                desc_verified = (len(buf) == desc["size"] and (zlib.crc32(bytes(buf)) & 0xFFFFFFFF) == desc["crc32"])
                break
    checks.append(("descriptor size/crc32 match the transferred file", desc_verified))

    # a second run on the same AP: the handshake file accumulates, so the descriptor reports appended
    # with prior_size, and size/crc32 still cover the WHOLE file the host pulls.
    send(CMD, 9, {"cmd": "wifi_handshake AA:BB:CC:00:11:22 6 20 NET1"})
    hs2 = recv()
    desc2 = hs2["d"].get("desc") if (hs2 and hs2.get("t") == ACK) else None
    appended_ok = bool(desc2) and desc2.get("appended") is True \
        and desc2.get("prior_size") == desc["size"] and desc2["size"] > desc["size"]
    checks.append(("second run reports appended + prior_size", appended_ok))
    whole_ok = False
    if appended_ok:
        send(CMD, 10, {"cmd": "fs_read " + desc2["path"]})
        buf = bytearray()
        deadline = time.time() + 3
        while time.time() < deadline:
            m = recv()
            if m is None:
                continue
            if m.get("t") == DATA and m["d"].get("stream") == "file":
                buf += _b.b64decode(m["d"]["chunk"])
            elif m.get("t") == ACK and "size" in m.get("d", {}):
                whole_ok = (len(buf) == desc2["size"] and (zlib.crc32(bytes(buf)) & 0xFFFFFFFF) == desc2["crc32"])
                break
    checks.append(("appended descriptor verifies the whole accumulated file", whole_ok))

    # Recovery diagnostics: advertised in CAPS, shape valid, malformed frame counted (not silent).
    def await_reply(mid, kind=None, timeout=3):
        deadline = time.time() + timeout
        while time.time() < deadline:
            m = recv()
            if m and m.get("id") == mid and (kind is None or m.get("t") == kind):
                return m
        return None

    send(CMD, 11, {"cmd": "diag"})
    first = await_reply(11, ACK)
    checks.append(("CAPS features.diag", caps and caps["d"].get("features", {}).get("diag") is True))
    checks.append(("diag shape", first is not None and diag_valid(first["d"])
                   and not diag_valid({**first["d"], "reset": "exploded"})
                   and not diag_valid({**first["d"], "link": {**first["d"]["link"], "rx_frames": -1}})
                   and not diag_valid({**first["d"], "heap": {"free": 1}})
                   and not diag_valid(None)))
    payload = b"{not json"
    ser.write(struct.pack("<I", len(payload)) + payload)
    bad = await_reply(0, ERR)
    send(CMD, 12, {"cmd": "diag"})
    second = await_reply(12, ACK)
    before = first["d"]["link"] if first else {}
    after = second["d"]["link"] if second else {}
    checks.append(("bad json -> ERR 400", bad is not None and bad["d"].get("code") == 400))
    checks.append(("diag counts malformed frame", bool(before) and bool(after)
                   and after["rx_bad_json"] == before["rx_bad_json"] + 1
                   and after["err"]["400"] == before["err"]["400"] + 1
                   and after["rx_frames"] == before["rx_frames"] + 2
                   and after["tx_frames"] >= before["tx_frames"] + 2))
    stop.set()
    ser.close()

    outputs_checks(checks)

    ok = all(v for _, v in checks)
    for name, v in checks:
        print(f"  [{'PASS' if v else 'FAIL'}] {name}")
    print("E2E PASS" if ok else "E2E FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
