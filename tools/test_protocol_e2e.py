#!/usr/bin/env python3
"""Automated end-to-end test of the companion protocol, no hardware needed.

Runs the mock device (on the PTY master, in a thread) and a host client (on the
slave, via pyserial) and asserts the full round-trip: HELLO->CAPS, PING->PONG,
CMD->ACK(+out), and a deduplicated wifi.ap stream. Exit code 0 = PASS.

    python3 tools/test_protocol_e2e.py
"""
import json
import os
import pty
import struct
import sys
import threading
import time

HELLO, CAPS, CMD, ACK, ERR, EVENT, DATA, SUB, PING, PONG = range(1, 11)


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
            m = json.loads(buf[4:4 + n]); buf = buf[4 + n:]
            t, mid, d = m.get("t"), m.get("id", 0), m.get("d", {})
            if t == HELLO:
                _wwrite(master, frame({"v": 0, "t": CAPS, "id": mid, "d": {"board": "mock", "chip": "esp32-s3", "proto": 0, "modules": ["wifi"]}}))
            elif t == PING:
                _wwrite(master, frame({"v": 0, "t": PONG, "id": mid, "d": {}}))
            elif t == CMD:
                cmd = d.get("cmd", "")
                if cmd.startswith("fs_read"):
                    import base64 as _b
                    content = b"BRUCIA-E2E-FILE-" + (b"z" * 1500)
                    for i in range(0, len(content), 768):
                        _wwrite(master, frame({"v": 0, "t": DATA, "id": 0, "d": {"stream": "file", "seq": i // 768, "chunk": _b.b64encode(content[i:i+768]).decode()}}))
                    _wwrite(master, frame({"v": 0, "t": ACK, "id": mid, "d": {"ok": True, "size": len(content)}}))
                else:
                    _wwrite(master, frame({"v": 0, "t": ACK, "id": mid, "d": {"ok": True, "out": "ok:" + cmd}}))
            elif t == SUB:
                if d.get("on"):
                    subs.add(d.get("stream"))
                _wwrite(master, frame({"v": 0, "t": ACK, "id": mid, "d": {"ok": True}}))
        if time.time() - last > 0.2 and "wifi.ap" in subs:
            last = time.time()
            for ap in [{"bssid": "AA:BB:CC:00:11:22", "ssid": "N1", "ch": 6, "rssi": -50, "enc": "wpa2"},
                       {"bssid": "AA:BB:CC:00:11:22", "ssid": "N1", "ch": 6, "rssi": -52, "enc": "wpa2"}]:
                _wwrite(master, frame({"v": 0, "t": EVENT, "id": 0, "d": {"ev": "wifi.ap", "data": ap}}))
        time.sleep(0.01)


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
    stop.set()
    ser.close()

    ok = all(v for _, v in checks)
    for name, v in checks:
        print(f"  [{'PASS' if v else 'FAIL'}] {name}")
    print("E2E PASS" if ok else "E2E FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
