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


if __name__ == "__main__":
    main()
