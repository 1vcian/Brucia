#!/usr/bin/env python3
"""Mock Brucia device for hardware-free development and protocol testing.

Opens a pseudo-terminal (PTY) and speaks the companion protocol (see
proto/PROTOCOL.md): length-prefixed JSON envelopes {v,t,id,d}. Point the real
host client (tools/companion_test.py) or the Android app (over a serial bridge) at
the printed device path.

Responds to HELLO->CAPS, PING->PONG, CMD->ACK(+out), `diag` (recovery diagnostics
with the firmware's key set and real link counters of this mock), and SUB streams
(wifi.ap / ble.scan / subghz.raw) with synthetic events. Bad JSON gets ERR 400 and an
invalid length prefix is counted and drops the buffered bytes, as on the device.

    python3 tools/mock_device.py            # prints the PTY path and serves
    python3 tools/mock_device.py --path-file /tmp/brucia_pty
"""
import argparse
import base64
import json
import os
import pty
import struct
import sys
import time
import zlib

HELLO, CAPS, CMD, ACK, ERR, EVENT, DATA, SUB, PING, PONG = range(1, 11)

# Files the mock "captures" this session, keyed by the descriptor path it reports. fs_read returns
# these so the descriptor's size/crc32 match the bytes the host pulls (proto/PROTOCOL.md capture
# descriptor). A tiny valid-looking pcap global header + payload is enough for the transport test.
CAPTURES = {}


def _hs_path(bssid, ssid, pmkid):
    mac = bssid.replace(":", "").replace("-", "").upper()
    if pmkid:
        return f"/BrucePCAP/handshakes/PMKID_{mac}.pcap"
    safe = "".join(c if (c.isalnum() or c in "-_.") else "_" for c in ssid)
    return f"/BrucePCAP/handshakes/HS_{mac}" + (f"_{safe}" if safe else "") + ".pcap"


def _capture_descriptor(cmd, pmkid):
    # cmd: "wifi_handshake <bssid> <ch> <secs> [ssid]" / "wifi_pmkid <bssid> <ch> <secs>"
    parts = cmd.split()
    bssid = parts[1] if len(parts) > 1 else "AA:BB:CC:DD:EE:FF"
    ssid = parts[4] if (not pmkid and len(parts) > 4) else ""
    path = _hs_path(bssid, ssid, pmkid)
    prior = b"" if pmkid else CAPTURES.get(path, b"")  # pmkid truncates; handshake accumulates (append)
    new_records = b"BRUCIA-MOCK-CAPTURE-" + bssid.encode() + (b"P" if pmkid else b"H")
    body = (prior or b"\xd4\xc3\xb2\xa1") + new_records  # fresh file gets a pcap-style global header
    CAPTURES[path] = body
    desc = {
        "v": 1, "path": path, "size": len(body), "crc32": zlib.crc32(body) & 0xFFFFFFFF,
        "frames": 3 if pmkid else 4, "complete": True, "stop": "complete",
        "time_src": "device_uptime_ms", "uptime_ms": int(time.monotonic() * 1000) & 0xFFFFFFFF,
    }
    if not pmkid:
        desc["handshake"] = True
    if prior:  # the file pre-existed and this run appended to it
        desc["appended"] = True
        desc["prior_size"] = len(prior)
    return desc

# CAPS d.evidence sample (proto/PROTOCOL.md). A mock has no hardware: nothing is reported as
# probed present; the entries exercise the app's evidence list and conflict rendering.
MOCK_EVIDENCE = {
    "wifi": {"level": "compiled", "detail": "mock: no radio"},
    "cc1101": {"level": "configured", "probe": "absent", "detail": "mock: no chip on SPI"},
    "nrf24": {"level": "configured", "probe": "absent", "detail": "mock: no chip on SPI",
              "conflict": [{"gpio": 44, "role": "cs", "with": "eth", "with_role": "cs"}]},
    "eth": {"level": "configured", "probe": "busy", "detail": "mock: not probed",
            "conflict": [{"gpio": 44, "role": "cs", "with": "nrf24", "with_role": "cs"}]},
    "lora": {"level": "none"},
}

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


MAX_FRAME = 8192
U32_MAX = 0xFFFFFFFF


class LinkCounters:
    """Mirror of the firmware `diag` link counters (src/companion/diag_core.h).

    Same keys and order as diagWriteLink(); every counter saturates at 2**32-1."""
    REJECT_KEYS = ("rx_zero_len", "rx_oversize", "rx_alloc_fail", "rx_stale", "rx_bad_json")
    ERR_KEYS = ("400", "404", "409", "413", "4xx", "5xx", "other")

    def __init__(self):
        self.values = {"sessions": 1, "rx_frames": 0, "tx_frames": 0}
        self.values.update({k: 0 for k in self.REJECT_KEYS})
        self.values["busy_409"] = 0
        self.err = {k: 0 for k in self.ERR_KEYS}

    @staticmethod
    def _inc(table, key):
        table[key] = min(table[key] + 1, U32_MAX)

    @staticmethod
    def bucket(code):
        if code in (400, 404, 409, 413):
            return str(code)
        if 400 <= code < 500:
            return "4xx"
        if 500 <= code < 600:
            return "5xx"
        return "other"

    def note(self, key):
        self._inc(self.values, key)

    def note_sent(self, kind, d):
        self.note("tx_frames")
        if kind == ERR:
            code = d.get("code", 0) if isinstance(d, dict) else 0
            self._inc(self.err, self.bucket(code if isinstance(code, int) else 0))

    def link(self):
        out = dict(self.values)
        out["err"] = dict(self.err)
        return out


def diag_reply(counters, uptime_s):
    """ACK body of `diag` (proto/PROTOCOL.md "Recovery diagnostics"). A mock has no heap or
    reset history: the values are fixed placeholders; the link counters are real for this mock."""
    return {
        "ok": True, "v": 1, "reset": "poweron", "reset_code": 1, "uptime_s": int(uptime_s),
        "heap": {"free": 180000, "min_free": 150000, "largest": 110000},
        "link": counters.link(),
        "drops": {},
    }


COUNTERS = LinkCounters()


def send(fd, kind, msg_id, d):
    payload = json.dumps({"v": 0, "t": kind, "id": msg_id, "d": d}).encode()
    os.write(fd, struct.pack("<I", len(payload)) + payload)
    COUNTERS.note_sent(kind, d)


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
    started = time.time()
    while True:
        try:
            chunk = os.read(master, 4096)
            if chunk:
                buf += chunk
        except (BlockingIOError, OSError):
            pass

        while len(buf) >= 4:
            n = struct.unpack("<I", buf[:4])[0]
            if n == 0 or n > MAX_FRAME:
                COUNTERS.note("rx_zero_len" if n == 0 else "rx_oversize")
                buf = b""  # frame boundary unknown
                break
            if len(buf) < 4 + n:
                break
            raw = buf[4:4 + n]
            buf = buf[4 + n:]
            COUNTERS.note("rx_frames")
            try:
                msg = json.loads(raw.decode(errors="replace"))
                if not isinstance(msg, dict):
                    raise ValueError("not an object")
            except ValueError:
                COUNTERS.note("rx_bad_json")
                send(master, ERR, 0, {"code": 400, "msg": "bad json"})
                continue
            t, mid, d = msg.get("t"), msg.get("id", 0), msg.get("d", {})
            if t == HELLO:
                send(master, CAPS, mid, {
                    "board": "Lilygo T-Embed CC1101 (mock)", "chip": "esp32-s3",
                    "fw": "mock", "proto": 0,
                    "modules": ["wifi", "ble", "rf", "ir"], "transports": ["usb"], "mtu": 8192,
                    "features": {"verified_files": True, "transactional_uploads": True,
                                 "mifare_keycheck_report": True, "mifare_keycheck_stop": True,
                                 "capture_descriptor": True, "diag": True},
                    # Release provenance as a build without Git metadata reports it (no dirty flag).
                    "build": {"commit": "unknown", "id": "mock", "time": "unknown",
                              "time_src": "unknown", "env": "mock"},
                    "evidence": MOCK_EVIDENCE,
                })
            elif t == PING:
                send(master, PONG, mid, {})
            elif t == CMD:
                cmd = d.get("cmd", "")
                if cmd == "diag":
                    send(master, ACK, mid, diag_reply(COUNTERS, time.time() - started))
                elif cmd.startswith("fs_read"):
                    path = cmd[len("fs_read"):].strip()
                    content = CAPTURES.get(path, b"BRUCIA-MOCK-FILE-" + (b"x" * 2000))
                    for i in range(0, len(content), 768):
                        send(master, DATA, 0, {"stream": "file", "seq": i // 768, "chunk": base64.b64encode(content[i:i+768]).decode()})
                    send(master, ACK, mid, {"ok": True, "size": len(content), "crc32": zlib.crc32(content) & 0xFFFFFFFF})
                elif cmd.startswith("wifi_handshake") or cmd.startswith("wifi_pmkid"):
                    pmkid = cmd.startswith("wifi_pmkid")
                    desc = _capture_descriptor(cmd, pmkid)
                    send(master, ACK, mid, {"ok": True, "out": "mock capture written", "desc": desc})
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
