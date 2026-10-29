#!/usr/bin/env python3
"""Mock Brucia device for hardware-free development and protocol testing.

Opens a pseudo-terminal (PTY) and speaks the companion protocol (see
proto/PROTOCOL.md): length-prefixed JSON envelopes {v,t,id,d}. Point the real
host client (tools/companion_test.py) or the Android app (over a serial bridge) at
the printed device path.

Responds to HELLO->CAPS, PING->PONG, CMD->ACK(+out), and SUB streams
(wifi.ap / ble.scan / subghz.raw) with synthetic events.

    python3 tools/mock_device.py            # prints the PTY path and serves
    python3 tools/mock_device.py --path-file /tmp/brucia_pty
"""
import argparse
import json
import os
import pty
import struct
import sys
import time

HELLO, CAPS, CMD, ACK, ERR, EVENT, DATA, SUB, PING, PONG = range(1, 11)

AP_SAMPLES = [
    {"bssid": "E4:4E:12:7E:CF:6F", "ssid": "FASTWEB-Q7U3EN", "ch": 2, "rssi": -51, "enc": "wpa2/wpa3"},
    {"bssid": "F8:0D:A9:1E:32:57", "ssid": "Lord of the pings", "ch": 11, "rssi": -55, "enc": "wpa2/wpa3"},
    {"bssid": "3C:EF:42:F6:92:12", "ssid": "WINDTRE-9212", "ch": 1, "rssi": -80, "enc": "wpa/wpa2"},
]
BLE_SAMPLES = [
    {"mac": "AC:23:3F:11:22:33", "name": "Mi Band", "rssi": -62},
    {"mac": "FF:EE:DD:CC:BB:AA", "name": "", "rssi": -88},
]
# PT2262-style timings (TE=350): short/long pairs + sync gap, 2 frames
SUBGHZ_TIMINGS = ([350, 1050, 1050, 350] * 12 + [350, 10850]) * 2


def send(fd, kind, msg_id, d):
    payload = json.dumps({"v": 0, "t": kind, "id": msg_id, "d": d}).encode()
    os.write(fd, struct.pack("<I", len(payload)) + payload)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--path-file", help="write the PTY device path here")
    args = ap.parse_args()

    master, slave = pty.openpty()
    dev = os.ttyname(slave)
    if args.path_file:
        with open(args.path_file, "w") as f:
            f.write(dev)
    print(f"mock device at: {dev}", flush=True)

    buf = b""
    subs = set()
    os.set_blocking(master, False)
    last_emit = 0.0
    while True:
        try:
            chunk = os.read(master, 4096)
            if chunk:
                buf += chunk
        except (BlockingIOError, OSError):
            pass

        while len(buf) >= 4:
            n = struct.unpack("<I", buf[:4])[0]
            if len(buf) < 4 + n:
                break
            msg = json.loads(buf[4:4 + n].decode(errors="replace"))
            buf = buf[4 + n:]
            t, mid, d = msg.get("t"), msg.get("id", 0), msg.get("d", {})
            if t == HELLO:
                send(master, CAPS, mid, {
                    "board": "Lilygo T-Embed CC1101 (mock)", "chip": "esp32-s3",
                    "fw": "mock", "proto": 0,
                    "modules": ["wifi", "ble", "rf", "ir"], "transports": ["usb"], "mtu": 8192,
                })
            elif t == PING:
                send(master, PONG, mid, {})
            elif t == CMD:
                cmd = d.get("cmd", "")
                if cmd.startswith("fs_read"):
                    content = b"BRUCIA-MOCK-FILE-" + (b"x" * 2000)
                    import base64 as _b
                    for i in range(0, len(content), 768):
                        send(master, DATA, 0, {"stream": "file", "seq": i // 768, "chunk": _b.b64encode(content[i:i+768]).decode()})
                    send(master, ACK, mid, {"ok": True, "size": len(content)})
                else:
                    send(master, ACK, mid, {"ok": True, "out": f"mock output for: {cmd}"})
            elif t == SUB:
                stream, on = d.get("stream"), d.get("on")
                if on:
                    subs.add(stream)
                    send(master, ACK, mid, {"ok": True})
                else:
                    subs.discard(stream)
                    send(master, ACK, mid, {"ok": True})

        now = time.time()
        if now - last_emit > 1.0:
            last_emit = now
            if "wifi.ap" in subs:
                for ap_ in AP_SAMPLES:
                    send(master, EVENT, 0, {"ev": "wifi.ap", "data": ap_})
            if "ble.scan" in subs:
                for b in BLE_SAMPLES:
                    send(master, EVENT, 0, {"ev": "ble.dev", "data": b})
            if "subghz.raw" in subs:
                send(master, EVENT, 0, {"ev": "subghz.raw", "data": {"freq": 433920000, "timings": SUBGHZ_TIMINGS}})
        time.sleep(0.02)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
