#!/usr/bin/env python3
"""Mock Brucia device for hardware-free development and protocol testing.

Opens a pseudo-terminal (PTY) and speaks the companion protocol (see
proto/PROTOCOL.md): length-prefixed JSON envelopes {v,t,id,d}. Point the real
host client (tools/companion_test.py) or the Android app (over a serial bridge) at
the printed device path.

Responds to HELLO->CAPS, PING->PONG, CMD->ACK(+out), `diag` (recovery diagnostics
with the firmware's key set and real link counters of this mock), and SUB streams
(wifi.ap / ble.scan / subghz.raw / wifi.probe / wifi.sta) with synthetic events. Bad JSON
gets ERR 400 and an invalid length prefix is counted and drops the buffered bytes, as on
the device. A small in-memory file system backs `storage list|remove`, `fs_read` and the
transactional `fs_upload_*` commands; `jam_detect <secs>` behaves as a blocking operation
(PING answered, `stop` ends it, other CMDs get ERR 409 "device busy"). `MockDevice` can be
served from a thread (tools/test_hw_acceptance.py) and accepts optional injected faults.

Link simulation (host reader/sizing tests): `--noise between` writes Bruce-style debug text
("cc1101 Connection OK") between frames, `--noise inside` also splices it into EVENT frames
(those frames are destroyed, as on hardware); `--rx-queue N` models the firmware's serial RX
queue: bytes beyond N that arrive while the mock is not reading are dropped, and a partial
frame is abandoned after 2 s (rx_stale), as the device reader does. CAPS reports
`rx_max`/`rx_buf` for that queue unless `--legacy-caps` (firmware before rx_max).

Lite controls: `scr 0|1`, `led 0|1` and `scr_bright <n>` change the mock's screen/LED state, which
CAPS reports as `scrOn`/`ledOn`/`scrBright` (omitted with `--legacy-outputs`, firmware before
them); after 15 s without a frame both outputs turn back on, as on the device.

    python3 tools/mock_device.py            # prints the PTY path and serves
    python3 tools/mock_device.py --path-file /tmp/brucia_pty
    python3 tools/mock_device.py --profile lite   # CAPS/ERR 501 of a LITE (launcher) build
    python3 tools/mock_device.py --rx-queue 1024 --legacy-caps --noise between
    python3 tools/mock_device.py --legacy-outputs  # CAPS without scrOn/ledOn/scrBright
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

# LITE profile (--profile lite): the companion endpoint groups a LITE firmware build compiles out
# (src/companion/config.h) and the exact ERR 501 text each dispatcher answers with. CAPS then
# reports each group as a literal false and drops features.diag / the mifare_keycheck flags /
# capture_descriptor; caps.nrf24 resolves to evidence level "none". tools/test_lite_companion.py
# keeps this table in step with the firmware sources.
LITE_OMITTED_FEATURES = {"mifare_keycheck": False, "ir_cmds": False, "ble_gatt": False, "diag": False}
LITE_501 = (
    (lambda c: c.startswith("mifare_keycheck "), "mifare_keycheck not available in this (lite) build"),
    (lambda c: c == "ir_rx" or c == "ir_tvbgone" or c.startswith("ir_tvbgone ") or c.startswith("ir_tx_raw ")
     or c.startswith("ir_jammer"), "ir companion commands not available in this (lite) build"),
    (lambda c: c.startswith("ble_gatt_connect ") or c.startswith("ble_gatt_read "),
     "ble_gatt not available in this (lite) build"),
    (lambda c: c == "diag", "diag not available in this (lite) build"),
    (lambda c: any(c == v or c.startswith(v + " ") for v in ("nrf24_scan", "nrf24_sniff", "nrf24_jammer",
                                                             "nrf24_mousejack")),
     "nrf24 not available in this (lite) build"),
)
PROFILES = ("full", "lite")


def lite_501(cmd):
    """The firmware's ERR 501 message for a command a LITE build compiled out, else None."""
    return next((msg for match, msg in LITE_501 if match(cmd)), None)

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
DEFAULT_RX_BUF = 8192 + 256  # firmware COMPANION_SERIAL_RX_BUFFER on PSRAM boards
NOISE_TEXT = b"cc1101 Connection OK\r\ncc1101 setMHZ(frequency);\r\n"
NOISE_MODES = ("between", "inside")
STALE_MS = 2000  # firmware readFrame(): an abandoned partial frame is dropped after 2 s


def rx_max_for_buffer(rx_buf):
    """Mirror of src/companion/serial_rx.h rxMaxForBuffer()."""
    headroom = min(256, rx_buf // 4)
    return min(rx_buf - headroom, MAX_FRAME)


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
        # Same key order as the firmware (diag.cpp creates link.err before the counters).
        out = {"err": dict(self.err)}
        out.update(self.values)
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

# Files the mock's storage CLI (`storage list|remove`), fs_read and the transactional upload see.
# A small text file at the root gives the host something to read back and verify (size/CRC).
MOCK_FILES = {
    "/brucia-mock-small.txt": b"BRUCIA mock small file\n" * 12,
    "/BrucePCAP/readme.txt": b"mock capture folder\n",
}
PROBE_SAMPLES = [
    {"mac": "DA:A1:19:00:11:22", "ssid": "HomeNet", "rssi": -61, "ch": 6},
    {"mac": "DA:A1:19:00:11:23", "ssid": "", "rssi": -77, "ch": 1},
]
STA_SAMPLES = [
    {"mac": "3C:22:FB:00:00:01", "bssid": "E4:4E:12:7E:CF:6F", "rssi": -58, "ch": 2},
]
# Fault injection for host tests (tools/test_hw_acceptance.py); none is active by default.
#   rx_undercount            PING frames are not counted in diag rx_frames (counter mismatch)
#   heap_leak                diag heap.free drops 4096 bytes per call
#   event_after_off          one more event of a stream arrives after its OFF ACK
#   no_transactional_uploads CAPS does not advertise features.transactional_uploads
FAULTS = ("rx_undercount", "heap_leak", "event_after_off", "no_transactional_uploads")
UPLOAD_MAX_CHUNK = 3072


def _write_all(fd, data):
    off = 0
    while off < len(data):
        try:
            off += os.write(fd, data[off:])
        except BlockingIOError:
            time.sleep(0.002)


def send(fd, kind, msg_id, d, counters=None):
    payload = json.dumps({"v": 0, "t": kind, "id": msg_id, "d": d}).encode()
    _write_all(fd, struct.pack("<I", len(payload)) + payload)
    (counters or COUNTERS).note_sent(kind, d)


def _dir_of(path):
    return path.rsplit("/", 1)[0] or "/"


class MockDevice:
    """One mock device serving the companion protocol on a PTY master fd.

    main() runs one on a fresh PTY; tests run one in a thread with a stop event."""

    def __init__(self, faults=(), counters=None, poll_interval=0.02, emit_interval=1.0, profile="full",
                 noise=(), rx_queue=None, caps_rx_max="auto", legacy_outputs=False):
        unknown = set(faults) - set(FAULTS)
        if unknown:
            raise ValueError(f"unknown mock faults: {sorted(unknown)}")
        if profile not in PROFILES:
            raise ValueError(f"unknown mock profile: {profile}")
        if set(noise) - set(NOISE_MODES):
            raise ValueError(f"unknown noise modes: {sorted(set(noise) - set(NOISE_MODES))}")
        self.noise = set(noise)
        self.rx_queue = rx_queue      # None: unlimited (no drops)
        # "auto": rx_max/rx_buf for the simulated queue; None: omitted (legacy firmware); int: forced
        self.caps_rx_max = caps_rx_max
        self.rx_dropped = 0           # bytes the simulated RX queue lost
        self.noise_written = 0        # bytes of debug text injected into the device->host stream
        self._sent_frames = 0
        self._rx_last = 0.0
        self._wire_hdr, self._wire_left, self._wire_pos = b"", 0, 0  # host framing, for rx_queue
        self.faults = set(faults)
        self.lite = profile == "lite"
        # Lite controls (scr/led/scr_bright): live state reported in CAPS unless legacy_outputs.
        self.legacy_outputs = legacy_outputs
        self.config_bright = 100
        self.screen_on, self.led_on, self.scr_bright = True, True, self.config_bright
        self._last_frame = time.time()
        self.counters = counters or LinkCounters()
        self.poll_interval = poll_interval
        self.emit_interval = emit_interval
        self.files = dict(MOCK_FILES)
        self.subs = set()
        self.upload = None  # {token, size, crc, path, data}
        self.started = time.time()
        self.diag_calls = 0
        self.received_cmds = []   # every CMD string, in order (tests audit what the host sent)
        self.received_subs = []   # every SUB body, in order
        self._buf = b""
        self._after_off = []      # (due time, stream) for the event_after_off fault

    def _send(self, fd, kind, msg_id, d):
        if not self.noise:
            return send(fd, kind, msg_id, d, self.counters)
        self._sent_frames += 1
        payload = json.dumps({"v": 0, "t": kind, "id": msg_id, "d": d}).encode()
        frame = struct.pack("<I", len(payload)) + payload
        if "inside" in self.noise and kind == EVENT and self._sent_frames % 2 == 0:
            cut = 4 + len(payload) // 2  # unframed text from another task mid-frame
            frame = frame[:cut] + NOISE_TEXT + frame[cut:]
            self.noise_written += len(NOISE_TEXT)
        if "between" in self.noise and self._sent_frames % 3 == 0:
            frame = NOISE_TEXT + frame  # an unguarded Serial.println before the reply
            self.noise_written += len(NOISE_TEXT)
        _write_all(fd, frame)
        self.counters.note_sent(kind, d)

    def _next_frame(self):
        """Pop one complete frame from the buffer: payload bytes, or None if incomplete."""
        if self._buf and self.rx_queue is not None and (time.time() - self._rx_last) * 1000 > STALE_MS:
            self.counters.note("rx_stale")  # firmware readFrame(): abandoned partial frame
            self._buf = b""
        while len(self._buf) >= 4:
            n = struct.unpack("<I", self._buf[:4])[0]
            if n == 0 or n > MAX_FRAME:
                self.counters.note("rx_zero_len" if n == 0 else "rx_oversize")
                self._buf = b""  # frame boundary unknown
                return None
            if len(self._buf) < 4 + n:
                return None
            raw = self._buf[4:4 + n]
            self._buf = self._buf[4 + n:]
            return raw
        return None

    def _read(self, fd):
        if self.rx_queue is not None:
            return self._read_queue(fd)
        try:
            chunk = os.read(fd, 4096)
            if chunk:
                self._buf += chunk
        except (BlockingIOError, OSError):
            pass

    def _read_queue(self, fd):
        """Worst case of the firmware's serial RX queue: the reader is busy (display redraw,
        storage, a handler) for the whole time one inbound frame arrives, so only the first
        rx_queue bytes of each frame (length prefix included) fit; the rest is dropped, as the
        HWCDC ISR does when xQueueSendFromISR fails. Frame boundaries are taken from the host's
        own length prefixes, so the model does not depend on how the PTY splits the bytes."""
        got = b""
        while True:
            try:
                chunk = os.read(fd, 65536)
            except (BlockingIOError, OSError):
                break
            if not chunk:
                break
            got += chunk
        if not got:
            return
        self._rx_last = time.time()
        i = 0
        while i < len(got):
            if len(self._wire_hdr) < 4:  # a host frame starts: learn its length
                take = min(4 - len(self._wire_hdr), len(got) - i)
                self._wire_hdr += got[i:i + take]
                if len(self._wire_hdr) == 4:
                    self._wire_left = struct.unpack("<I", self._wire_hdr)[0]
            else:
                take = min(self._wire_left, len(got) - i)
                self._wire_left -= take
            part = got[i:i + take]
            room = max(0, self.rx_queue - self._wire_pos)
            self._buf += part[:room]
            self.rx_dropped += max(0, len(part) - room)
            self._wire_pos += take
            i += take
            if len(self._wire_hdr) == 4 and self._wire_left == 0:
                self._wire_hdr, self._wire_pos = b"", 0

    @staticmethod
    def _parse(raw):
        msg = json.loads(raw.decode(errors="replace"))
        if not isinstance(msg, dict):
            raise ValueError("not an object")
        return msg

    def _count_rx(self, msg):
        if "rx_undercount" in self.faults and isinstance(msg, dict) and msg.get("t") == PING:
            return
        self.counters.note("rx_frames")

    def _caps(self):
        features = {"verified_files": True, "transactional_uploads": True,
                    "mifare_keycheck_report": True, "mifare_keycheck_stop": True,
                    "capture_descriptor": True, "diag": True}
        if "no_transactional_uploads" in self.faults:
            del features["transactional_uploads"]
        evidence = MOCK_EVIDENCE
        if self.lite:
            for key in ("mifare_keycheck_report", "mifare_keycheck_stop", "capture_descriptor", "diag"):
                features.pop(key, None)
            features.update(LITE_OMITTED_FEATURES)
            evidence = dict(MOCK_EVIDENCE, nrf24={
                "level": "none", "detail": "NRF24 companion commands not built into this LITE firmware"})
        rx = {}
        if self.caps_rx_max == "auto":
            rx_buf = self.rx_queue if self.rx_queue is not None else DEFAULT_RX_BUF
            rx = {"rx_max": rx_max_for_buffer(rx_buf), "rx_buf": rx_buf}
        elif self.caps_rx_max is not None:
            rx = {"rx_max": int(self.caps_rx_max), "rx_buf": self.rx_queue or DEFAULT_RX_BUF}
        return {
            **rx,
            "board": "Lilygo T-Embed CC1101 (mock)", "chip": "esp32-s3",
            "fw": "mock", "proto": 0,
            "modules": ["wifi", "ble", "rf", "ir"], "transports": ["usb"], "mtu": 8192,
            "features": features,
            # Release provenance as a build without Git metadata reports it (no dirty flag).
            "build": {"commit": "unknown", "id": "mock", "time": "unknown",
                      "time_src": "unknown", "env": "mock"},
            "evidence": evidence,
            "bright": self.config_bright,
            **({} if self.legacy_outputs else
               {"scrOn": self.screen_on, "ledOn": self.led_on, "scrBright": self.scr_bright}),
        }

    def _outputs(self, cmd):
        """scr/led/scr_bright as the firmware applies them; returns the ACK body, None if not one."""
        verb, _, arg = cmd.partition(" ")
        if verb not in ("scr", "led", "scr_bright") or not arg.strip():
            return None
        try:
            v = int(arg.strip())
        except ValueError:
            v = 0  # String::toInt()
        if verb == "scr":
            self.screen_on = v != 0
            self.scr_bright = self.config_bright if self.screen_on else 0
            return {"ok": True}
        if verb == "led":
            self.led_on = v != 0
            return {"ok": True}
        v = min(max(v, 0), 100)
        self.scr_bright = v
        if v > 0:
            self.screen_on = True  # a visible level ends a `scr 0` hold
        return {"ok": True, "out": f"screen {v}%"}

    def _restore_outputs_if_silent(self):
        if (not self.screen_on or not self.led_on) and time.time() - self._last_frame > 15:
            self._outputs("scr 1")
            self._outputs("led 1")

    def _diag(self):
        reply = diag_reply(self.counters, time.time() - self.started)
        if "heap_leak" in self.faults:
            reply["heap"]["free"] -= 4096 * self.diag_calls
        self.diag_calls += 1
        return reply

    def _storage(self, fd, mid, cmd):
        parts = cmd.split(" ", 2)
        verb = parts[1] if len(parts) > 1 else ""
        path = (parts[2].strip() if len(parts) > 2 else "") or "/"
        if not path.startswith("/"):
            path = "/" + path
        if verb == "list":
            base = path.rstrip("/") or "/"
            names = {}
            for f, body in self.files.items():
                if _dir_of(f) == base:
                    names[f.rsplit("/", 1)[1]] = str(len(body))
                elif f.startswith(base.rstrip("/") + "/"):
                    names[f[len(base.rstrip("/")) + 1:].split("/", 1)[0]] = "<DIR>"
            ok = bool(names) or base == "/"
            out = "".join(f"{k}\t{v}\n" for k, v in sorted(names.items()))
            self._send(fd, ACK, mid, {"ok": ok, "out": out})
        elif verb == "remove":
            if path in self.files:
                del self.files[path]
                self._send(fd, ACK, mid, {"ok": True, "out": "File removed\n"})
            else:
                self._send(fd, ACK, mid, {"ok": False, "out": "File does not exist\n"})
        else:
            self._send(fd, ACK, mid, {"ok": True, "out": f"mock output for: {cmd}"})

    def _upload(self, fd, mid, cmd):
        parts = cmd.split(" ")
        verb = parts[0]
        up = self.upload
        if verb == "fs_upload_begin" and len(parts) >= 5:
            token, path = parts[1], " ".join(parts[4:])
            if up is not None and up["token"] != token:
                return self._send(fd, ERR, mid, {"code": 409, "msg": "upload busy"})
            if not (len(token) == 32 and all(c in "0123456789abcdef" for c in token)) \
                    or not path.startswith("/") or path.lower().startswith("/.brucia-upload."):
                return self._send(fd, ERR, mid, {"code": 400, "msg": "invalid upload metadata"})
            self.upload = {"token": token, "size": int(parts[2]), "crc": int(parts[3]), "path": path,
                           "data": b""}
            return self._send(fd, ACK, mid, {"ok": True, "token": token, "size": 0})
        if verb == "fs_upload_chunk" and len(parts) == 4:
            if up is None or up["token"] != parts[1] or int(parts[2]) != len(up["data"]):
                return self._send(fd, ERR, mid, {"code": 409, "msg": "upload token/offset mismatch"})
            try:
                chunk = base64.b64decode(parts[3], validate=True)
            except ValueError:
                chunk = b""
            if not chunk or len(chunk) > UPLOAD_MAX_CHUNK or len(up["data"]) + len(chunk) > up["size"]:
                return self._send(fd, ERR, mid, {"code": 400, "msg": "invalid upload chunk encoding/size"})
            up["data"] += chunk
            return self._send(fd, ACK, mid, {"ok": True, "token": up["token"], "size": len(up["data"])})
        if verb in ("fs_upload_commit", "fs_upload_abort") and len(parts) == 2:
            if up is None or up["token"] != parts[1]:
                return self._send(fd, ERR, mid, {"code": 409, "msg": "no active upload"})
            self.upload = None
            if verb == "fs_upload_abort":
                return self._send(fd, ACK, mid, {"ok": True, "token": up["token"], "size": len(up["data"])})
            if len(up["data"]) != up["size"] or (zlib.crc32(up["data"]) & 0xFFFFFFFF) != up["crc"]:
                return self._send(fd, ERR, mid, {"code": 409, "msg": "staging size/crc mismatch"})
            self.files[up["path"]] = up["data"]
            return self._send(fd, ACK, mid, {"ok": True, "token": up["token"], "size": up["size"],
                                             "crc32": up["crc"], "committed": True, "cleanup_pending": False})
        return self._send(fd, ERR, mid, {"code": 400, "msg": "invalid upload command"})

    def _blocking_op(self, fd, mid, secs, stop):
        """A receive-only blocking command (`jam_detect`): drains one frame per poll as
        companionPollStop does - PING answered, stop/*_stop ends it, other CMDs get ERR 409."""
        deadline = time.time() + secs
        while time.time() < deadline and not (stop and stop.is_set()):
            self._read(fd)
            raw = self._next_frame()
            if raw is None:
                time.sleep(self.poll_interval)
                continue
            try:
                msg = self._parse(raw)
            except ValueError:
                self.counters.note("rx_frames")
                self.counters.note("rx_bad_json")  # dropped without reply while busy
                continue
            self._count_rx(msg)
            t, cid, d = msg.get("t"), msg.get("id", 0), msg.get("d") or {}
            if t == PING:
                self._send(fd, PONG, cid, {})
            elif t == CMD:
                cs = d.get("cmd", "") if isinstance(d, dict) else ""
                self.received_cmds.append(cs)
                if cs == "stop" or cs.endswith("_stop"):
                    break
                self.counters.note("busy_409")
                self._send(fd, ERR, cid, {"code": 409, "msg": "device busy"})
        self._send(fd, ACK, mid, {"ok": True, "out": "deauth=0 total=0"})

    def _handle(self, fd, msg, stop):
        t, mid, d = msg.get("t"), msg.get("id", 0), msg.get("d", {})
        self._last_frame = time.time()
        if t == HELLO:
            self._send(fd, CAPS, mid, self._caps())
        elif t == PING:
            self._send(fd, PONG, mid, {})
        elif t == CMD:
            cmd = d.get("cmd", "")
            self.received_cmds.append(cmd)
            unsupported = lite_501(cmd) if self.lite else None
            if unsupported:
                self._send(fd, ERR, mid, {"code": 501, "msg": unsupported})
            elif cmd == "diag":
                self._send(fd, ACK, mid, self._diag())
            elif (reply := self._outputs(cmd)) is not None:
                self._send(fd, ACK, mid, reply)
            elif cmd.startswith("fs_read"):
                path = cmd[len("fs_read"):].strip()
                content = self.files.get(path, CAPTURES.get(path, b"BRUCIA-MOCK-FILE-" + (b"x" * 2000)))
                # DATA frames echo the CMD id (proto/PROTOCOL.md fs_read, features.verified_files).
                for i in range(0, len(content), 768):
                    self._send(fd, DATA, mid, {"stream": "file", "seq": i // 768,
                                               "chunk": base64.b64encode(content[i:i+768]).decode()})
                self._send(fd, ACK, mid, {"ok": True, "size": len(content),
                                          "crc32": zlib.crc32(content) & 0xFFFFFFFF})
            elif cmd.startswith("fs_upload_"):
                self._upload(fd, mid, cmd)
            elif cmd.startswith("storage "):
                self._storage(fd, mid, cmd)
            elif cmd.startswith("jam_detect"):
                secs = cmd[len("jam_detect"):].strip()
                self._blocking_op(fd, mid, min(max(int(secs) if secs.isdigit() else 5, 1), 120), stop)
            elif cmd.startswith("wifi_handshake") or cmd.startswith("wifi_pmkid"):
                pmkid = cmd.startswith("wifi_pmkid")
                desc = _capture_descriptor(cmd, pmkid)
                self._send(fd, ACK, mid, {"ok": True, "out": "mock capture written", "desc": desc})
            else:
                self._send(fd, ACK, mid, {"ok": True, "out": f"mock output for: {cmd}"})
        elif t == SUB:
            stream, on = d.get("stream"), d.get("on")
            self.received_subs.append(dict(d))
            if on:
                self.subs.add(stream)
            else:
                if stream in self.subs and "event_after_off" in self.faults:
                    self._after_off.append((time.time() + 0.2, stream))
                self.subs.discard(stream)
            self._send(fd, ACK, mid, {"ok": True})

    def _emit(self, fd, stream):
        if stream == "wifi.ap":
            for ap_ in AP_SAMPLES:
                self._send(fd, EVENT, 0, {"ev": "wifi.ap", "data": ap_})
        elif stream == "ble.scan":
            for b in BLE_SAMPLES:
                self._send(fd, EVENT, 0, {"ev": "ble.dev", "data": b})
        elif stream == "subghz.raw":
            self._send(fd, EVENT, 0, {"ev": "subghz.raw", "data": {"freq": 433920000, "timings": SUBGHZ_TIMINGS}})
        elif stream == "wifi.probe":
            for p in PROBE_SAMPLES:
                self._send(fd, EVENT, 0, {"ev": "wifi.probe", "data": p})
        elif stream == "wifi.sta":
            for s in STA_SAMPLES:
                self._send(fd, EVENT, 0, {"ev": "wifi.sta", "data": s})

    def serve(self, fd, stop=None):
        os.set_blocking(fd, False)
        last_emit = 0.0
        while not (stop and stop.is_set()):
            self._read(fd)
            while True:
                raw = self._next_frame()
                if raw is None:
                    break
                try:
                    msg = self._parse(raw)
                except ValueError:
                    self.counters.note("rx_frames")
                    self.counters.note("rx_bad_json")
                    self._send(fd, ERR, 0, {"code": 400, "msg": "bad json"})
                    continue
                self._count_rx(msg)
                self._handle(fd, msg, stop)

            self._restore_outputs_if_silent()
            now = time.time()
            for due, stream in list(self._after_off):
                if now >= due:
                    self._after_off.remove((due, stream))
                    self._emit(fd, stream)
            if now - last_emit > self.emit_interval:
                last_emit = now
                for stream in ("wifi.ap", "ble.scan", "subghz.raw", "wifi.probe", "wifi.sta"):
                    if stream in self.subs:
                        self._emit(fd, stream)
            time.sleep(self.poll_interval)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--path-file", help="write the PTY device path here")
    ap.add_argument("--fault", action="append", default=[], choices=FAULTS,
                    help="inject a fault (host-tool testing); repeatable")
    ap.add_argument("--profile", default="full", choices=PROFILES,
                    help="lite: CAPS and ERR 501 replies of a LITE firmware build")
    ap.add_argument("--noise", action="append", default=[], choices=NOISE_MODES,
                    help="inject debug text between frames / inside EVENT frames; repeatable")
    ap.add_argument("--rx-queue", type=int, help="simulate a device RX queue of N bytes (drops beyond it)")
    ap.add_argument("--legacy-caps", action="store_true", help="omit CAPS rx_max/rx_buf (older firmware)")
    ap.add_argument("--legacy-outputs", action="store_true",
                    help="omit CAPS scrOn/ledOn/scrBright (firmware before the lite-control state)")
    args = ap.parse_args()

    master, slave = pty.openpty()
    dev = os.ttyname(slave)
    if args.path_file:
        with open(args.path_file, "w") as f:
            f.write(dev)
    print(f"mock device at: {dev}", flush=True)
    MockDevice(faults=args.fault, counters=COUNTERS, profile=args.profile, noise=args.noise,
               rx_queue=args.rx_queue, caps_rx_max=None if args.legacy_caps else "auto",
               legacy_outputs=args.legacy_outputs).serve(master)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
