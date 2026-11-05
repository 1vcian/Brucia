#!/usr/bin/env python3
"""Brucia companion protocol test client (host side).

Speaks the v0 wire protocol (see proto/PROTOCOL.md) over USB-CDC:
length-prefixed (uint32 LE) JSON envelopes {v, t, id, d}.

Usage:
    pip install pyserial
    # On the device: tap Brucia on the home screen
    python3 tools/companion_test.py /dev/tty.usbmodemXXXX
    python3 tools/companion_test.py /dev/tty.usbmodemXXXX --cmd "wifi scan"
    # a long CMD, a second CMD (e.g. its stop) and a PING while it runs:
    python3 tools/companion_test.py /dev/tty.usbmodemXXXX --cmd "script_run delay(60000)" \
        --wait 25 --then script_stop --after 2 --ping-after 1

It sends HELLO, prints the CAPS reply, sends PING, and optionally a CMD. With --wait it keeps
reading (timestamps relative to the CMD) until the CMD's ACK/ERR arrives or the wait ends, sending
--then after --after seconds and a PING after --ping-after seconds.
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
    def _read_exact(k):
        b = b""
        while len(b) < k:
            c = ser.read(k - len(b))
            if not c:
                return None
            b += c
        return b
    head = _read_exact(4)
    if head is None:
        return None
    (length,) = struct.unpack("<I", head)
    if length == 0 or length > 1 << 20:
        return None
    data = _read_exact(length)
    if data is None:
        return None
    return json.loads(data.decode(errors="replace"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("port", help="serial port, e.g. /dev/tty.usbmodem1101")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--cmd", help="run a CLI command via CMD and print the ACK")
    ap.add_argument("--wait", type=float, default=0,
                    help="with --cmd: read until its ACK/ERR for up to N seconds, printing every frame")
    ap.add_argument("--then", help="with --wait: a second CMD to send while the first runs")
    ap.add_argument("--after", type=float, default=1.0, help="seconds after --cmd to send --then")
    ap.add_argument("--ping-after", type=float, default=-1,
                    help="with --wait: send a PING N seconds after --cmd (negative: none)")
    ap.add_argument(
        "--wardrive",
        action="store_true",
        help="subscribe to the wifi.ap stream and print access points",
    )
    ap.add_argument(
        "--seconds",
        type=float,
        default=0,
        help="stop wardriving after N seconds (0 = until Ctrl-C)",
    )
    args = ap.parse_args()

    with serial.Serial(args.port, args.baud, timeout=3) as ser:
        time.sleep(0.3)
        ser.reset_input_buffer()

        print("-> HELLO")
        send(ser, HELLO, 1, {"app": "companion_test.py", "appVersion": "0"})
        caps = recv(ser)
        print("<- CAPS:", json.dumps(caps, indent=2) if caps else "(no reply)")

        if not caps or caps.get("t") != CAPS or caps.get("id") != 1:
            sys.exit("FAIL: no valid CAPS; enter Brucia companion mode on the device and retry")

        print("-> PING")
        send(ser, PING, 2, {})
        pong = recv(ser)
        print("<- ", pong)
        if not pong or pong.get("t") != PONG or pong.get("id") != 2:
            sys.exit("FAIL: no matching PONG")

        if args.cmd and args.wait > 0:
            t0 = time.time()
            print(f"-> CMD {args.cmd!r}")
            send(ser, CMD, 3, {"cmd": args.cmd})
            then_sent = args.then is None
            ping_sent = args.ping_after < 0
            while time.time() - t0 < args.wait:
                now = time.time() - t0
                if not then_sent and now >= args.after:
                    print(f"-> (+{now:.2f}s) CMD {args.then!r}")
                    send(ser, CMD, 6, {"cmd": args.then})
                    then_sent = True
                if not ping_sent and now >= args.ping_after:
                    print(f"-> (+{now:.2f}s) PING")
                    send(ser, PING, 7, {})
                    ping_sent = True
                if not ser.in_waiting:
                    time.sleep(0.02)
                    continue
                msg = recv(ser)
                print(f"<- (+{time.time() - t0:.2f}s)", msg)
                if msg and msg.get("id") == 3 and msg.get("t") in (ACK, ERR):
                    break
            else:
                print(f"(no ACK/ERR for the CMD within {args.wait:.0f} s)")
        elif args.cmd:
            print(f"-> CMD {args.cmd!r}")
            send(ser, CMD, 3, {"cmd": args.cmd})
            print("<- ", recv(ser))

        if args.wardrive:
            print("-> SUB wifi.ap on")
            send(ser, SUB, 4, {"stream": "wifi.ap", "on": True})
            print("<- ", recv(ser))
            print("Streaming access points (Ctrl-C to stop)...")
            seen = set()
            end = time.time() + args.seconds if args.seconds > 0 else None
            try:
                while True:
                    if end is not None and time.time() >= end:
                        break
                    msg = recv(ser, timeout=2.0)
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
                pass
            print("\n-> SUB wifi.ap off")
            send(ser, SUB, 5, {"stream": "wifi.ap", "on": False})
            print("<- ", recv(ser))


if __name__ == "__main__":
    main()
