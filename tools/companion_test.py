#!/usr/bin/env python3
"""Brucia companion protocol test client (host side).

Speaks the v0 wire protocol (see proto/PROTOCOL.md) over USB-CDC:
length-prefixed (uint32 LE) JSON envelopes {v, t, id, d}.

Usage:
    pip install pyserial
    # On the device: Connect -> Companion
    python3 tools/companion_test.py /dev/tty.usbmodemXXXX
    python3 tools/companion_test.py /dev/tty.usbmodemXXXX --cmd "wifi scan"

It sends HELLO, prints the CAPS reply, sends PING, and optionally a CMD.
"""
import argparse
import json
import struct
import sys
import time

try:
    import serial  # pyserial
except ImportError:
    sys.exit("pyserial not installed. Run: pip install pyserial")

# message kinds (keep in sync with proto/PROTOCOL.md)
HELLO, CAPS, CMD, ACK, ERR, EVENT, DATA, SUB, PING, PONG = range(1, 11)
PROTO_V = 0


def send(ser, kind, msg_id, body):
    payload = json.dumps({"v": PROTO_V, "t": kind, "id": msg_id, "d": body}).encode()
    ser.write(struct.pack("<I", len(payload)) + payload)
    ser.flush()


def recv(ser, timeout=3.0):
    ser.timeout = timeout
    head = ser.read(4)
    if len(head) < 4:
        return None
    (length,) = struct.unpack("<I", head)
    if length == 0 or length > 1 << 20:
        return None
    data = ser.read(length)
    if len(data) < length:
        return None
    return json.loads(data.decode(errors="replace"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("port", help="serial port, e.g. /dev/tty.usbmodem1101")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--cmd", help="run a CLI command via CMD and print the ACK")
    ap.add_argument(
        "--wardrive",
        action="store_true",
        help="subscribe to the wifi.ap stream and print access points until Ctrl-C",
    )
    args = ap.parse_args()

    with serial.Serial(args.port, args.baud, timeout=3) as ser:
        time.sleep(0.3)
        ser.reset_input_buffer()

        print("-> HELLO")
        send(ser, HELLO, 1, {"app": "companion_test.py", "appVersion": "0"})
        caps = recv(ser)
        print("<- CAPS:", json.dumps(caps, indent=2) if caps else "(no reply)")

        print("-> PING")
        send(ser, PING, 2, {})
        print("<- ", recv(ser))

        if args.cmd:
            print(f"-> CMD {args.cmd!r}")
            send(ser, CMD, 3, {"cmd": args.cmd})
            print("<- ", recv(ser))

        if args.wardrive:
            print("-> SUB wifi.ap on")
            send(ser, SUB, 4, {"stream": "wifi.ap", "on": True})
            print("<- ", recv(ser))
            print("Streaming access points (Ctrl-C to stop)...")
            seen = set()
            try:
                while True:
                    msg = recv(ser, timeout=10.0)
                    if not msg:
                        continue
                    if msg.get("t") == EVENT and msg.get("d", {}).get("ev") == "wifi.ap":
                        ap_ = msg["d"]["data"]
                        key = ap_.get("bssid")
                        tag = "NEW" if key not in seen else "   "
                        seen.add(key)
                        print(
                            f"[{tag}] {ap_.get('bssid'):17}  ch{ap_.get('ch'):>3}  "
                            f"{ap_.get('rssi'):>4}dBm  {ap_.get('enc'):<9}  "
                            f"{ap_.get('ssid')!r}   (unique={len(seen)})"
                        )
            except KeyboardInterrupt:
                print("\n-> SUB wifi.ap off")
                send(ser, SUB, 5, {"stream": "wifi.ap", "on": False})
                print("<- ", recv(ser))


if __name__ == "__main__":
    main()
