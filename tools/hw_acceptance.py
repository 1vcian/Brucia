#!/usr/bin/env python3
"""Passive hardware acceptance runner for a Brucia device in companion mode (S05 helper).

Talks the companion protocol (proto/PROTOCOL.md) over USB serial and runs only passive,
non-transmitting, non-destructive checks, then writes a JSON and a Markdown report with the
firmware build/provenance, timestamps, a PASS/FAIL/SKIP per check and the raw evidence.

    python3 tools/hw_acceptance.py --port /dev/cu.usbmodem1101
    python3 tools/hw_acceptance.py --port /dev/cu.usbmodem1101 --only caps,ping,diag_counters
    python3 tools/hw_acceptance.py --port /dev/cu.usbmodem1101 --dry-run
    python3 tools/hw_acceptance.py --port /dev/cu.usbmodem1101 --only caps,script_stop
    python3 tools/hw_acceptance.py --port /dev/cu.usbmodem1101 --soak 20 --streams wifi.probe,wifi.sta,subghz.raw

Soak (`--soak <minutes>`, opt-in): repeats intervals of the same passive operations (PINGs,
`storage list /`, a CRC-checked `fs_read`, the scratch upload cycle, one receive-only stream
ON/OFF, `diag`) for the given time, keeps the link alive with PINGs (the device leaves companion
mode after 60 s without a host frame), and records heap, link counters, latency, reset/uptime
and the timing of every operation per interval; the report adds a soak summary and a CSV time
series. See Runner.check_soak() and docs/HARDWARE_VERIFICATION.md.

Hard safety: every frame goes through `check_allowed()` (an explicit allowlist). No jam,
deauth, beacon, BLE advertising/spam, IR/RF/Sub-GHz TX, replay, speaker or other emitting
command can be sent: anything outside the allowlist raises AllowlistViolation before a byte is
written. The only storage mutation is a uniquely named scratch file this run creates at the
root (/brucia-acceptance-<UTC>-<hex>.txt) and removes again. `wifi.ap` (active scan: probe
requests) and `ble.scan` (active scan requests) are not receive-only and are not allowed;
the stream check uses promiscuous `wifi.probe` / `wifi.sta` or CC1101 `subghz.raw` receive.
The `busy` check (receive-only `jam_detect`) is opt-in because its Wi-Fi setup calls
esp_wifi_disconnect(), which sends a disassociation if the device is joined to an AP.
The `script_stop` check (opt-in) runs exactly two fixed scripts (SCRIPT_LONG: a loop of delay()s;
SCRIPT_SHORT: one console.log) and `script_stop`: no radio, no file access; the device shows the script
screen while it runs.

Link handling: frames sent never exceed CAPS `rx_max` (768 bytes for firmware without it) and
the reader resyncs after device text in the framed stream (a 4-byte length in range followed
by '{"v":'), recording the skipped bytes as warnings instead of failing (proto/PROTOCOL.md).

Simulated runs (tools/test_hw_acceptance.py against tools/mock_device.py) prove the runner,
not the device. Exit code: 0 no FAIL, 1 at least one FAIL, 2 link/usage error, 130 Ctrl-C.
"""
import argparse
import base64
import csv
import json
import platform
import random
import re
import statistics
import struct
import sys
import time
import zlib
from datetime import datetime, timezone
from pathlib import Path

RUNNER_VERSION = 1
HELLO, CAPS, CMD, ACK, ERR, EVENT, DATA, SUB, PING, PONG = range(1, 11)
KIND_NAMES = {1: "HELLO", 2: "CAPS", 3: "CMD", 4: "ACK", 5: "ERR", 6: "EVENT", 7: "DATA", 8: "SUB",
              9: "PING", 10: "PONG"}
MAX_FRAME = 8192
MALFORMED_PAYLOAD = b"{not json"  # the one deliberately bad frame (ERR 400 path, harmless)
# Every device frame payload starts with this (beginEnvelope() writes "v" first). A 4-byte LE
# length in 5..MAX_FRAME followed by it marks a frame start; the reader resyncs on it after
# stray text (proto/PROTOCOL.md "Resynchronisation").
ENVELOPE_PREFIX = b'{"v":'
NOISE_SAMPLE_BYTES = 120   # bytes of each skipped noise run kept for the report
NOISE_SAMPLES_KEPT = 10
# Inbound frame capacity (proto/PROTOCOL.md "Inbound frame capacity"). CAPS rx_max is the
# largest frame payload the device's serial RX buffer holds whole; firmware without it is
# assumed to have the 1024-byte PSRAM-board queue measured on hardware: 1024 - 256 headroom.
LEGACY_RX_MAX = 768
UPLOAD_MAX_CHUNK = 3072    # firmware upload::MAX_CHUNK (src/companion/storage_upload.h)
UPLOAD_MAX_SIZE = 8192     # the scratch file never exceeds this

# --------------------------------------------------------------------------------------------
# Allowlist (hard safety). Anything not matched here is a programming error.
# --------------------------------------------------------------------------------------------


class AllowlistViolation(AssertionError):
    """A frame outside the passive allowlist was about to be sent. Never caught by the runner."""


# Receive-only streams and the EVENT name each produces. wifi.probe / wifi.sta are promiscuous
# sniffers (no scan, no association); subghz.raw keeps the CC1101 in RX.
RECEIVE_ONLY_STREAMS = {"wifi.probe": "wifi.probe", "wifi.sta": "wifi.sta", "subghz.raw": "subghz.raw"}
SCRATCH_PREFIX = "/brucia-acceptance-"
SCRATCH_RE = re.compile(r"^/brucia-acceptance-\d{8}T\d{6}Z-[0-9a-f]{6}\.txt$")
_TOKEN = r"[0-9a-f]{32}"
_B64 = r"[A-Za-z0-9+/]+={0,2}"


def _safe_read_path(path):
    """Absolute path for list/read: no control chars, no '..' segments, at most 192 bytes."""
    return (path.startswith("/") and len(path.encode()) <= 192 and ".." not in path.split("/")
            and not any(ord(c) < 0x20 or c in '\\:*?"<>|' for c in path))


# The only two scripts the opt-in script_stop check runs (the allowlist takes no other source): a
# loop of delay()s that outlasts the 15 s execution bound (no I/O, no radio), and one console.log.
# BruceJS has no global print(); console.log() is what script_run captures into ACK out.
SCRIPT_LONG = "for (var i = 0; i < 300; i++) { delay(100); }\nconsole.log('brucia-loop-ended');"
SCRIPT_SHORT = "console.log('brucia-after-stop');"
SCRIPT_SHORT_OUT = "brucia-after-stop"
SCRIPT_SOURCES = (SCRIPT_LONG, SCRIPT_SHORT)

# (name, regex, extra validator on the match) for CMD bodies.
COMMAND_RULES = (
    ("diag", re.compile(r"^diag$"), None),
    ("storage list", re.compile(r"^storage list (/.*)$"), lambda m: _safe_read_path(m.group(1))),
    ("fs_read", re.compile(r"^fs_read (/.*)$"), lambda m: _safe_read_path(m.group(1))),
    ("fs_upload_begin", re.compile(rf"^fs_upload_begin {_TOKEN} (\d{{1,5}}) (\d{{1,10}}) (\S+)$"),
     lambda m: int(m.group(1)) <= UPLOAD_MAX_SIZE and int(m.group(2)) <= 0xFFFFFFFF and SCRATCH_RE.match(m.group(3))),
    ("fs_upload_chunk", re.compile(rf"^fs_upload_chunk {_TOKEN} (\d{{1,5}}) ({_B64})$"),
     lambda m: len(m.group(2)) <= 4096),
    ("fs_upload_commit", re.compile(rf"^fs_upload_commit {_TOKEN}$"), None),
    ("fs_upload_abort", re.compile(rf"^fs_upload_abort {_TOKEN}$"), None),
    ("storage remove", re.compile(r"^storage remove (\S+)$"), lambda m: SCRATCH_RE.match(m.group(1))),
    ("jam_detect", re.compile(r"^jam_detect ([1-9]|[12]\d|30)$"), None),
    ("stop", re.compile(r"^stop$"), None),
    # Idle-stop guards: on an idle device they only answer "not running" (never start anything).
    ("idle stop", re.compile(r"^(nrf24_stop|jam_detect_stop|ir_power_spam_stop)$"), None),
    ("script_run", re.compile(r"^script_run (.*)$", re.S), lambda m: m.group(1) in SCRIPT_SOURCES),
    ("script_stop", re.compile(r"^script_stop$"), None),
)


def check_allowed(kind, d):
    """Raise AllowlistViolation unless (kind, d) is a passive frame this runner may send."""
    if kind == HELLO:
        if not (isinstance(d, dict) and set(d) <= {"app", "appVersion"}):
            raise AllowlistViolation(f"HELLO body not allowed: {d!r}")
        return "HELLO"
    if kind == PING:
        if d != {}:
            raise AllowlistViolation(f"PING body not allowed: {d!r}")
        return "PING"
    if kind == SUB:
        if not (isinstance(d, dict) and set(d) == {"stream", "on"} and isinstance(d["on"], bool)
                and d["stream"] in RECEIVE_ONLY_STREAMS):
            raise AllowlistViolation(f"SUB not allowed (receive-only streams only): {d!r}")
        return f"SUB {d['stream']} {'on' if d['on'] else 'off'}"
    if kind == CMD:
        if not (isinstance(d, dict) and set(d) == {"cmd"} and isinstance(d["cmd"], str)):
            raise AllowlistViolation(f"CMD body not allowed: {d!r}")
        cmd = d["cmd"]
        for name, rx, extra in COMMAND_RULES:
            m = rx.match(cmd)
            if m and (extra is None or extra(m)):
                return name
        raise AllowlistViolation(f"CMD not in the passive allowlist: {cmd[:80]!r}")
    raise AllowlistViolation(f"message kind {kind!r} is never sent by this runner")


# --------------------------------------------------------------------------------------------
# Link: framed transport with accounting
# --------------------------------------------------------------------------------------------


class LinkError(Exception):
    """Framing lost or port failure: the remaining checks cannot run."""


class Link:
    """Length-prefixed JSON frames over a pyserial-like object (read/write/in_waiting)."""

    def __init__(self, port, echo=None):
        self.port = port
        self.echo = echo
        self.buf = b""
        self.next_id = 100
        self.sent = 0            # complete frames written (incl. the malformed one)
        self.received = 0        # complete frames read (incl. undecodable payloads)
        self.sent_by_kind = {}
        self.recv_by_kind = {}
        self.errors = []         # (rx index, id, code, msg) of every ERR received
        self.bad_rx_json = 0
        self.commands = []       # audit trail of what was sent: {t, kind, summary}
        self.malformed_sent = 0
        self.rx_max = MAX_FRAME  # outbound payload limit; set from CAPS rx_max (or LEGACY_RX_MAX)
        self.noise_bytes = 0     # bytes skipped to resynchronise on a frame start
        self.resyncs = 0         # separate noise runs
        self.noise_samples = []  # first NOISE_SAMPLES_KEPT runs: {utc, rx_index, bytes, sample}
        self._resyncing = False

    def _write(self, data):
        try:
            self.port.write(data)
            flush = getattr(self.port, "flush", None)
            if flush:
                flush()
        except OSError as e:  # pyserial's SerialException is an OSError
            raise LinkError(f"write failed: {e}") from e

    def send(self, kind, d, mid=None):
        summary = check_allowed(kind, d)  # raises before anything is written
        if mid is None:
            mid = self.next_id
            self.next_id += 1
        payload = json.dumps({"v": 0, "t": kind, "id": mid, "d": d}, separators=(",", ":")).encode()
        if len(payload) > MAX_FRAME:
            raise AllowlistViolation("frame exceeds COMPANION_MAX_FRAME")
        if len(payload) > self.rx_max:
            raise AllowlistViolation(f"frame of {len(payload)} bytes exceeds the device rx_max {self.rx_max}")
        self._write(struct.pack("<I", len(payload)) + payload)
        self.sent += 1
        self.sent_by_kind[KIND_NAMES[kind]] = self.sent_by_kind.get(KIND_NAMES[kind], 0) + 1
        if kind == CMD and d["cmd"].startswith("fs_upload_chunk"):
            summary = " ".join(d["cmd"].split(" ")[:3]) + " <base64>"
        elif kind == CMD:
            summary = d["cmd"]
        self.commands.append({"t": round(time.time(), 3), "kind": KIND_NAMES[kind], "id": mid,
                              "summary": summary, "bytes": len(payload)})
        if self.echo:
            self.echo(f"> {KIND_NAMES[kind]} id={mid} {summary}")
        return mid

    def send_malformed(self):
        self._write(struct.pack("<I", len(MALFORMED_PAYLOAD)) + MALFORMED_PAYLOAD)
        self.sent += 1
        self.malformed_sent += 1
        self.sent_by_kind["MALFORMED"] = self.sent_by_kind.get("MALFORMED", 0) + 1
        self.commands.append({"t": round(time.time(), 3), "kind": "MALFORMED", "id": 0,
                              "summary": MALFORMED_PAYLOAD.decode()})

    def drain(self, secs=0.3):
        """Discard stale bytes before HELLO; returns how many were dropped."""
        dropped, end = 0, time.time() + secs
        while time.time() < end:
            n = self.port.in_waiting
            if n:
                dropped += len(self.port.read(n))
            else:
                time.sleep(0.02)
        self.buf = b""
        return dropped

    def _skip_noise(self, count):
        """Drop `count` leading bytes that are not a frame start; account them as noise."""
        noise, self.buf = self.buf[:count], self.buf[count:]
        if not self._resyncing:
            self._resyncing = True
            self.resyncs += 1
            if len(self.noise_samples) < NOISE_SAMPLES_KEPT:
                self.noise_samples.append({"utc": utc_now(), "after_rx_frames": self.received,
                                           "bytes": 0, "sample": ""})
        if self.noise_samples and self.resyncs <= NOISE_SAMPLES_KEPT:
            ev = self.noise_samples[-1]
            ev["bytes"] += len(noise)
            if len(ev["sample"]) < NOISE_SAMPLE_BYTES:
                ev["sample"] += noise[:NOISE_SAMPLE_BYTES - len(ev["sample"])].decode("latin-1")
        self.noise_bytes += len(noise)

    def _sync(self):
        """Align self.buf on a frame start. True when buf starts with a plausible header and the
        envelope prefix; False when more bytes are needed. Skips stray bytes (device text output)
        up to the next position where a 4-byte LE length in range is followed by '{"v":'."""
        need = 4 + len(ENVELOPE_PREFIX)
        while len(self.buf) >= 4:
            n = struct.unpack("<I", self.buf[:4])[0]
            plausible = len(ENVELOPE_PREFIX) <= n <= MAX_FRAME
            if plausible and len(self.buf) < need:
                return False
            if plausible and self.buf[4:need] == ENVELOPE_PREFIX:
                return True
            if not self._resyncing and self.buf.startswith(b"COMMAND:"):
                # Bruce's serial CLI echoed our frame: the board is in its normal menu
                # (companion mode also auto-exits after 60 s without host frames).
                raise LinkError("the board answered with Bruce's serial CLI, not companion frames: "
                                "open Brucia on the device and run again right away")
            at = self.buf.find(ENVELOPE_PREFIX, 5)
            while at != -1 and not (len(ENVELOPE_PREFIX) <= struct.unpack("<I", self.buf[at - 4:at])[0] <= MAX_FRAME):
                at = self.buf.find(ENVELOPE_PREFIX, at + 1)
            if at != -1:
                self._skip_noise(at - 4)
                continue
            keep = need - 1  # a frame start may straddle the end of what has arrived so far
            if len(self.buf) <= keep:
                return False
            self._skip_noise(len(self.buf) - keep)
        return False

    def recv(self, deadline):
        """One frame (dict, or {'_bad': raw}) or None at the deadline."""
        while True:
            if self._sync():
                n = struct.unpack("<I", self.buf[:4])[0]
                if len(self.buf) >= 4 + n:
                    raw, self.buf = self.buf[4:4 + n], self.buf[4 + n:]
                    self._resyncing = False
                    self.received += 1
                    try:
                        msg = json.loads(raw.decode("utf-8", errors="replace"))
                        if not isinstance(msg, dict):
                            raise ValueError
                    except ValueError:
                        self.bad_rx_json += 1
                        return {"_bad": raw[:200].decode("latin-1")}
                    name = KIND_NAMES.get(msg.get("t"), str(msg.get("t")))
                    self.recv_by_kind[name] = self.recv_by_kind.get(name, 0) + 1
                    if msg.get("t") == ERR:
                        d = msg.get("d") or {}
                        self.errors.append((self.received, msg.get("id"), d.get("code"), d.get("msg")))
                    return msg
            if time.time() >= deadline:
                return None
            try:
                n = self.port.in_waiting
                chunk = self.port.read(n if n else 1)
            except OSError as e:
                raise LinkError(f"read failed: {e}") from e
            if chunk:
                self.buf += chunk

    def wait(self, match, timeout, on_other=None):
        """Read until match(frame) is true; other frames go to on_other. None on timeout."""
        deadline = time.time() + timeout
        while True:
            f = self.recv(deadline)
            if f is None:
                return None
            if match(f):
                return f
            if on_other:
                on_other(f)

    def reply(self, mid, timeout, on_other=None):
        """ACK/ERR/CAPS/PONG carrying this id."""
        return self.wait(lambda f: f.get("id") == mid and f.get("t") in (ACK, ERR, CAPS, PONG), timeout, on_other)


# --------------------------------------------------------------------------------------------
# Validators (proto/PROTOCOL.md)
# --------------------------------------------------------------------------------------------

U32 = 0xFFFFFFFF
EVIDENCE_LEVELS = ("none", "compiled", "configured", "probed", "initialized")
EVIDENCE_PROBES = ("present", "absent", "busy")
BUILD_VALUE_RE = re.compile(r"^[A-Za-z0-9._+:-]{1,64}$")
DIAG_RESET_NAMES = {"unknown", "poweron", "ext", "sw", "panic", "int_wdt", "task_wdt", "wdt", "deepsleep",
                    "brownout", "sdio", "usb", "jtag", "efuse", "pwr_glitch", "cpu_lockup", "other"}
DIAG_LINK_KEYS = ["sessions", "rx_frames", "tx_frames", "tx_torn", "rx_zero_len", "rx_oversize", "rx_alloc_fail",
                  "rx_stale", "rx_bad_json", "busy_409", "err"]
DIAG_ERR_KEYS = ["400", "404", "409", "413", "4xx", "5xx", "other"]
REJECT_KEYS = ("rx_zero_len", "rx_oversize", "rx_alloc_fail", "rx_stale")
STREAM_EVENT_KEYS = {"wifi.probe": ("mac", "ssid", "rssi", "ch"), "wifi.sta": ("mac", "bssid", "rssi", "ch"),
                     "subghz.raw": ("freq", "timings")}


def stream_module(stream):
    """CAPS caps/modules flag a receive-only stream needs."""
    return "cc1101" if stream == "subghz.raw" else "wifi"


def _u32(v):
    return isinstance(v, int) and not isinstance(v, bool) and 0 <= v <= U32


def caps_problems(d):
    p = []
    if not isinstance(d, dict):
        return ["CAPS body is not an object"]
    for k in ("board", "chip", "fw"):
        if not isinstance(d.get(k), str) or not d.get(k):
            p.append(f"{k} missing or not a string")
    if d.get("proto") != 0:
        p.append(f"proto is {d.get('proto')!r}, expected 0")
    if not (isinstance(d.get("modules"), list) and all(isinstance(m, str) for m in d["modules"])):
        p.append("modules missing or not a list of strings")
    if "transports" in d and not (isinstance(d["transports"], list) and "usb" in d["transports"]):
        p.append("transports does not list usb")
    if "mtu" in d and d["mtu"] != MAX_FRAME:
        p.append(f"mtu {d['mtu']!r} != {MAX_FRAME}")
    if not isinstance(d.get("features"), dict):
        p.append("features missing or not an object")
    if "caps" in d and not (isinstance(d["caps"], dict) and all(isinstance(v, bool) for v in d["caps"].values())):
        p.append("caps present but not a map of booleans")
    if "battery" in d and not isinstance(d["battery"], int):
        p.append("battery present but not an integer")
    if "rx_max" in d and not (_u32(d["rx_max"]) and 1 <= d["rx_max"] <= MAX_FRAME):
        p.append(f"rx_max {d['rx_max']!r} is not an integer in 1..{MAX_FRAME}")
    if "rx_buf" in d and not (_u32(d["rx_buf"]) and d["rx_buf"] > 0):
        p.append(f"rx_buf {d['rx_buf']!r} is not a positive integer")
    # Lite-control state (additive; absent on older firmware).
    for k in ("scrOn", "ledOn"):
        if k in d and not isinstance(d[k], bool):
            p.append(f"{k} {d[k]!r} is not a boolean")
    if "scrBright" in d and not (_u32(d["scrBright"]) and d["scrBright"] <= 100):
        p.append(f"scrBright {d['scrBright']!r} is not an integer in 0..100")
    return p


def device_rx_max(caps):
    """(rx_max, source): CAPS rx_max when valid, else LEGACY_RX_MAX for older firmware."""
    v = (caps or {}).get("rx_max")
    if _u32(v) and 1 <= v <= MAX_FRAME:
        return v, "caps"
    return LEGACY_RX_MAX, "legacy default (CAPS has no rx_max)"


def upload_chunk_size(rx_max, token, total):
    """Largest raw fs_upload_chunk whose frame payload (compact JSON, 10-digit id) fits rx_max,
    capped at the firmware's UPLOAD_MAX_CHUNK. Base64 has no characters json.dumps escapes."""
    head = json.dumps({"v": 0, "t": CMD, "id": U32,
                       "d": {"cmd": f"fs_upload_chunk {token} {'9' * len(str(total))} "}},
                      separators=(",", ":"))
    room = rx_max - len(head.encode())
    return max(0, min(UPLOAD_MAX_CHUNK, (room // 4) * 3))


def build_problems(b):
    p = []
    if not isinstance(b, dict):
        return ["build is not an object"]
    for k in ("commit", "id", "time", "time_src", "env"):
        v = b.get(k)
        if not isinstance(v, str) or not BUILD_VALUE_RE.match(v):
            p.append(f"build.{k} missing or outside [A-Za-z0-9._+:-]{{1,64}}: {v!r}")
    c = b.get("commit")
    if isinstance(c, str) and c != "unknown" and not re.fullmatch(r"[0-9a-f]{12}", c):
        p.append(f"build.commit {c!r} is neither a 12-char SHA nor 'unknown'")
    if b.get("time_src") not in ("source_date_epoch", "commit", "unknown"):
        p.append(f"build.time_src {b.get('time_src')!r} unknown")
    if "dirty" in b and not isinstance(b["dirty"], bool):
        p.append("build.dirty present but not a boolean")
    return p


def evidence_problems(ev):
    p = []
    if not isinstance(ev, dict):
        return ["evidence is not an object"]
    for name, m in ev.items():
        if not isinstance(m, dict) or m.get("level") not in EVIDENCE_LEVELS:
            p.append(f"{name}: level missing/unknown")
            continue
        probe = m.get("probe")
        if probe is not None and probe not in EVIDENCE_PROBES:
            p.append(f"{name}: probe {probe!r} unknown")
        if m["level"] in ("probed", "initialized") and probe != "present":
            p.append(f"{name}: level {m['level']} without probe present")
        if probe == "present" and m["level"] not in ("probed", "initialized"):
            p.append(f"{name}: probe present below level probed")
        if "detail" in m and not isinstance(m["detail"], str):
            p.append(f"{name}: detail not a string")
        for c in m.get("conflict", []) if isinstance(m.get("conflict", []), list) else [None]:
            if not (isinstance(c, dict) and isinstance(c.get("gpio"), int)
                    and all(isinstance(c.get(k), str) for k in ("role", "with", "with_role"))):
                p.append(f"{name}: malformed conflict entry {c!r}")
    return p


def diag_problems(d):
    if not isinstance(d, dict) or d.get("ok") is not True or d.get("v") != 1:
        return ["diag ACK is not {ok:true, v:1, ...}"]
    p = []
    if d.get("reset") not in DIAG_RESET_NAMES:
        p.append(f"reset {d.get('reset')!r} unknown")
    for k in ("reset_code", "uptime_s"):
        if not _u32(d.get(k)):
            p.append(f"{k} not a uint32")
    heap = d.get("heap")
    if not isinstance(heap, dict) or not all(_u32(heap.get(k)) for k in ("free", "min_free", "largest")):
        p.append("heap free/min_free/largest missing")
    if "psram" in d and not (isinstance(d["psram"], dict)
                             and all(_u32(d["psram"].get(k)) for k in ("size", "free", "min_free", "largest"))):
        p.append("psram malformed")
    link = d.get("link")
    # JSON object order is not part of the contract (the firmware emits "err" first, the app reads
    # by name), so compare key sets.
    if not isinstance(link, dict) or set(link) != set(DIAG_LINK_KEYS) or not isinstance(link.get("err"), dict) \
            or set(link["err"]) != set(DIAG_ERR_KEYS):
        p.append("link keys differ from the contract")
    elif not all(_u32(link[k]) for k in DIAG_LINK_KEYS[:-1]) or not all(_u32(v) for v in link["err"].values()):
        p.append("link counter not a uint32")
    if not isinstance(d.get("drops"), dict):
        p.append("drops missing")
    return p


def err_bucket(code):
    if code in (400, 404, 409, 413):
        return str(code)
    if isinstance(code, int) and 400 <= code < 500:
        return "4xx"
    if isinstance(code, int) and 500 <= code < 600:
        return "5xx"
    return "other"


def parse_listing(out):
    """`storage list` output: `name\\t<DIR>` or `name\\t<size>` per line (as app FileList)."""
    entries = []
    for line in (out or "").split("\n"):
        line = line.rstrip("\r")
        name, sep, rest = line.partition("\t")
        if not sep or not name.strip() or name in (".", ".."):
            continue
        rest = rest.strip()
        if rest == "<DIR>":
            entries.append({"name": name, "dir": True, "size": 0})
        elif rest.isdigit():
            entries.append({"name": name, "dir": False, "size": int(rest)})
    return entries


def pct(values, q):
    if not values:
        return None
    s = sorted(values)
    k = min(len(s) - 1, max(0, int(round(q / 100.0 * (len(s) - 1)))))
    return s[k]


def utc_now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")[:-4] + "Z"


# --------------------------------------------------------------------------------------------
# Soak analysis (pure functions)
# --------------------------------------------------------------------------------------------

# Default --soak-max-leak: heap.free bytes lost per hour that fail a soak. The T-Embed reported
# about 100-115 KB free in companion mode (TESTS.md, batch 8 hardware run). A steady 2 KB/h loss
# would take about half of that in 24 hours, the longest session expected (a day of wardriving),
# and leave the Wi-Fi/BLE stacks short of memory well before the end. Short runs cannot resolve
# a slope that small against allocator noise: heap_trend() also requires the fitted decline to
# exceed --heap-tolerance, so a run's real detection limit is max(this, tolerance / hours).
SOAK_MAX_LEAK_BPH = 2048
SOAK_MIN_TREND_SAMPLES = 3   # post-warm-up diag samples needed to assess the heap trend
SOAK_FINDINGS_KEPT = 500     # findings stored in the report (all are counted)
SOAK_CSV_COLUMNS = (
    "interval", "utc", "elapsed_s", "uptime_s", "reset", "heap_free", "heap_min_free", "heap_largest",
    "dev_rx_delta", "host_sent_delta", "dev_tx_delta", "host_recv_delta", "rejects_delta", "err_delta",
    "noise_bytes_delta", "resyncs_delta", "bad_rx_json_delta", "pings", "pings_lost", "p50_ms", "p95_ms",
    "p99_ms", "max_ms", "stream", "stream_events", "ping_round_ms", "fs_list_ms", "fs_read_ms",
    "fs_upload_ms", "stream_ms", "diag_ms", "keepalives", "findings")


def theil_sen(points):
    """Median of pairwise slopes of (x, y) points: robust against single outliers."""
    slopes = [(y2 - y1) / (x2 - x1) for i, (x1, y1) in enumerate(points) for x2, y2 in points[i + 1:] if x2 != x1]
    return statistics.median(slopes) if slopes else None


def ls_slope(points):
    """Least-squares slope of (x, y) points (reported next to the robust one)."""
    if len(points) < 2:
        return None
    mx = sum(x for x, _ in points) / len(points)
    my = sum(y for _, y in points) / len(points)
    sxx = sum((x - mx) ** 2 for x, _ in points)
    return sum((x - mx) * (y - my) for x, y in points) / sxx if sxx else None


def heap_trend(points, max_leak_bph, tolerance):
    """Trend of (elapsed_s, bytes) samples taken after the warm-up.

    `leak` is true when the Theil-Sen slope falls faster than the run's detection limit,
    max(max_leak_bph, tolerance / window_hours): steeper than the per-hour threshold AND a fitted
    decline over the observed window larger than the allocator noise floor (`tolerance`, the
    heap check's --heap-tolerance). One-time allocations before the first sample (Wi-Fi/CC1101
    driver set-up on the first ON of each stream) are excluded by the caller's warm-up."""
    out = {"assessed": False, "samples": len(points), "max_leak_bph": max_leak_bph, "tolerance_bytes": tolerance,
           "leak": False}
    if len(points) < SOAK_MIN_TREND_SAMPLES or points[-1][0] <= points[0][0]:
        out["reason"] = f"fewer than {SOAK_MIN_TREND_SAMPLES} samples after the warm-up"
        return out
    hours = [(x / 3600.0, y) for x, y in points]
    window_h = hours[-1][0] - hours[0][0]
    ts, ls = theil_sen(hours), ls_slope(hours)
    ys = [y for _, y in points]
    diffs = [b - a for a, b in zip(ys, ys[1:])]
    limit = max(max_leak_bph, tolerance / window_h)
    out.update({
        "assessed": True, "window_h": round(window_h, 4), "slope_bph": round(ts, 1), "ls_slope_bph": round(ls, 1),
        "fitted_decline_bytes": round(-ts * window_h), "detection_limit_bph": round(limit),
        "falling_steps": sum(1 for d in diffs if d < 0), "rising_steps": sum(1 for d in diffs if d > 0),
        "first": ys[0], "last": ys[-1], "min": min(ys), "max": max(ys), "leak": -ts > limit})
    return out


def latency_stats(values):
    if not values:
        return {"n": 0}
    return {"n": len(values), "p50_ms": round(pct(values, 50), 2), "p95_ms": round(pct(values, 95), 2),
            "p99_ms": round(pct(values, 99), 2), "max_ms": round(max(values), 2)}


# --------------------------------------------------------------------------------------------
# Checks
# --------------------------------------------------------------------------------------------

# (id, title, default). `busy` is opt-in (see module docstring).
CHECKS = (
    ("caps", "HELLO -> CAPS shape, features and modules", True),
    ("provenance", "CAPS build provenance", True),
    ("evidence", "CAPS capability evidence map", True),
    ("diag_shape", "diag reply shape (run baseline)", True),
    ("ping", "PING round trip: latency, loss, order", True),
    ("malformed", "One bad-JSON frame -> ERR 400 and rx_bad_json +1", True),
    ("fs_list", "storage list /", True),
    ("fs_read", "Verified read (size/CRC) of a small existing file", True),
    ("fs_upload", "Transactional upload of a scratch file, read-back, remove", True),
    ("streams", "Receive-only stream on/off, no events after OFF", True),
    ("busy", "Busy handling during receive-only jam_detect (opt-in)", False),
    ("script_stop", "script_stop ends a running script_run; the next script_run runs (opt-in)", False),
    ("idle_stops", "Idle nrf24_stop / jam_detect_stop / ir_power_spam_stop answer \"not running\" (opt-in)", False),
    ("soak", "Long-session soak: heap, link counters, latency, reboots (--soak)", False),
    ("heap", "Heap not trending down across repeated diag", True),
    ("diag_counters", "diag link counters match the runner's traffic", True),
)
CHECK_IDS = [c[0] for c in CHECKS]
DEFAULT_CHECKS = [c[0] for c in CHECKS if c[2]]
# --soak without --only: the identity checks, the soak, then the whole-run counter cross-check.
SOAK_DEFAULT_CHECKS = ["caps", "provenance", "diag_shape", "soak", "diag_counters"]
DIAG_USERS = {"diag_shape", "malformed", "heap", "diag_counters", "busy", "soak"}

# TESTS.md items this runner helps with; everything else in "Batches 1-4" stays manual.
COVERAGE_NOTE = (
    "Automates (passive part only): `diag` card counters/reset/heap (link counters rising with "
    "traffic, busy 409 with --only busy); script Stop on the device (script_run ended by script_stop, "
    "next script_run accepted) with --only script_stop; Hardware evidence card (CAPS evidence levels and pin "
    "conflicts are recorded; plausibility vs the real board is a human judgement); Files "
    "list/read/upload transport. Manual: Wi-Fi capture descriptor, Mifare, speaker, Sub-GHz "
    "Listen/Spectrum UI, Stop all/unplug in the app, USB permission/flash/wake lock, in-app "
    "update, FM PCM, and anything that transmits.")


class Result:
    def __init__(self, cid, title):
        self.id, self.title = cid, title
        self.status, self.reason = "SKIP", "not run"
        self.warnings, self.evidence = [], {}
        self.started, self.duration_ms = None, 0

    def as_dict(self):
        return {"id": self.id, "title": self.title, "status": self.status, "reason": self.reason,
                "warnings": self.warnings, "started_utc": self.started, "duration_ms": self.duration_ms,
                "evidence": self.evidence}


class Runner:
    def __init__(self, link, opts, log=print):
        self.link, self.o, self.log = link, opts, log
        self.selected = list(opts.only)
        self.results = {cid: Result(cid, title) for cid, title, _ in CHECKS if cid in self.selected}
        self.caps = None
        self.features = {}
        self.diag_ok = False
        self.baseline = None      # {"reply", "sent", "received"}
        self.final = None
        self.diag_samples = []    # (utc, heap dict, uptime)
        self.active_subs = set()
        self.upload = None        # {"token", "path", "committed", "removed"}
        self.busy_mid = None
        self.script_mid = None    # a script_run still waiting for its reply (cleanup stops it)
        self.soak = None          # soak working state (check_soak)
        self.soak_report = None   # soak summary, series, ops and findings for the report
        self._ping_outstanding = set()
        self.interrupted = False
        self.link_error = None
        self.hello_evidence = {}
        self.rx_max, self.rx_max_source = LEGACY_RX_MAX, "not read yet"

    # -- helpers ---------------------------------------------------------------------------
    def cmd(self, text, timeout, on_other=None):
        mid = self.link.send(CMD, {"cmd": text})
        return self.link.reply(mid, timeout, on_other)

    def diag(self, timeout=None):
        r = self.cmd("diag", timeout or self.o.timeout)
        if r is None or r.get("t") != ACK:
            return None
        self.diag_samples.append({"utc": utc_now(), "heap": r["d"].get("heap"), "uptime_s": r["d"].get("uptime_s")})
        return r["d"]

    def caps_flag(self, name):
        caps = (self.caps or {}).get("caps")
        if isinstance(caps, dict):
            return caps.get(name) is True
        return name in ((self.caps or {}).get("modules") or [])

    def _run(self, cid, fn):
        if cid not in self.results:
            return
        r = self.results[cid]
        if self.link_error:
            r.status, r.reason = "SKIP", f"link lost earlier: {self.link_error}"
            return
        r.started = utc_now()
        t0 = time.time()
        noise0, resync0 = self.link.noise_bytes, self.link.resyncs
        self.log(f"[{cid}] {r.title} ...")
        try:
            fn(r)
        except LinkError as e:
            self.link_error = str(e)
            r.status, r.reason = "FAIL", f"link error: {e}"
        except AllowlistViolation:
            raise  # a programming error, never reported as a device result
        except Exception as e:  # noqa: BLE001 - a malformed reply must not lose the report
            r.status, r.reason = "FAIL", f"unexpected reply or runner error: {type(e).__name__}: {e}"
        finally:
            r.duration_ms = int((time.time() - t0) * 1000)
            self.note_noise(r.warnings, noise0, resync0)
        self.log(f"[{cid}] {r.status}: {r.reason}")

    def note_noise(self, warnings, noise0, resync0):
        """Stray device bytes are not fatal: the reader resynced. Record them as a warning."""
        skipped = self.link.noise_bytes - noise0
        if skipped:
            runs = self.link.resyncs - resync0
            sample = next((e["sample"] for e in reversed(self.link.noise_samples) if e["sample"]), "")
            warnings.append(f"skipped {skipped} non-frame byte(s) from the device in {runs} run(s) and resynced "
                            f"(unframed serial output in companion mode); sample: {sample[:80]!r}")

    @staticmethod
    def verdict(r, problems, ok_reason):
        r.status = "FAIL" if problems else "PASS"
        r.reason = "; ".join(problems) if problems else ok_reason

    # -- run -------------------------------------------------------------------------------
    def _enter_companion(self):
        """Get a CAPS reply, entering companion mode if the board is sitting at its normal CLI.

        Opening the ESP32-S3 USB-Serial-JTAG port can reset the chip (RTS), which reboots it OUT of
        companion to the home UI. Mirror the app's auto-nudge: try a framed HELLO; if no CAPS, send the
        raw `companion` CLI line (not a framed command) and retry, draining the boot/menu text between
        tries. Returns the CAPS frame or None."""
        self.link.drain(2.5)  # absorb the boot log if the port-open reset the board
        for _ in range(8):
            try:
                self.link.send(HELLO, {"app": "hw_acceptance", "appVersion": str(RUNNER_VERSION)})
                caps = self.link.wait(lambda f: f.get("t") == CAPS, 1.5)
            except LinkError:
                # At Bruce's CLI the board echoes our frame as "COMMAND:" (sync() raises). That just
                # means it is not in companion yet: nudge it and retry rather than failing the run.
                caps = None
            if caps is not None:
                return caps
            try:  # raw mode-entry nudge, exactly as the app's read loop sends it
                self.link.port.write(b"companion\r\n")
                flush = getattr(self.link.port, "flush", None)
                if flush:
                    flush()
            except Exception:  # noqa: BLE001
                pass
            time.sleep(1.2)
            self.link.drain(0.6)
        return None

    def run(self):
        dropped = self.link.drain()
        caps = self._enter_companion()
        self.hello_evidence = {"stale_bytes_dropped": dropped,
                               "caps_id": caps.get("id") if caps else None,
                               "noise_bytes_before_caps": self.link.noise_bytes}
        if caps is None:
            raise LinkError(f"no CAPS after a companion nudge; is the board connected and running Brucia?")
        self.caps = caps.get("d") if isinstance(caps.get("d"), dict) else {}
        self.rx_max, self.rx_max_source = device_rx_max(self.caps)
        self.link.rx_max = self.rx_max
        self.hello_evidence["rx_max"] = self.rx_max
        self.hello_evidence["rx_max_source"] = self.rx_max_source
        self.features = self.caps.get("features") if isinstance(self.caps.get("features"), dict) else {}
        self.diag_ok = self.features.get("diag") is True

        self._run("caps", self.check_caps)
        self._run("provenance", self.check_provenance)
        self._run("evidence", self.check_evidence)
        if self.diag_ok and DIAG_USERS & set(self.selected):
            self.take_baseline()
        self._run("diag_shape", self.check_diag_shape)
        self._run("ping", self.check_ping)
        self._run("malformed", self.check_malformed)
        listing = {}
        self._run("fs_list", lambda r: self.check_fs_list(r, listing))
        self._run("fs_read", lambda r: self.check_fs_read(r, listing))
        self._run("fs_upload", self.check_fs_upload)
        self._run("streams", self.check_streams)
        self._run("busy", self.check_busy)
        self._run("idle_stops", self.check_idle_stops)
        self._run("script_stop", self.check_script_stop)
        self._run("soak", self.check_soak)
        self._run("heap", self.check_heap)
        self._run("diag_counters", self.check_diag_counters)

    def take_baseline(self):
        d = self.diag()
        if d is not None:
            # The baseline request is counted by the device; its reply is the first tx we count.
            self.baseline = {"reply": d, "sent": self.link.sent, "received": self.link.received - 1,
                             "malformed": self.link.malformed_sent}

    def cleanup(self):
        """Best effort: OFF for active SUBs, stop a busy op, abort/remove the scratch upload."""
        actions = []
        t = min(self.o.timeout, 3.0)
        try:
            if self.busy_mid is not None:
                self.link.send(CMD, {"cmd": "stop"})
                actions.append(("stop busy op", self.link.reply(self.busy_mid, t) is not None))
                self.busy_mid = None
            if self.script_mid is not None:
                self.link.send(CMD, {"cmd": "script_stop"})
                actions.append(("stop script_run", self.link.reply(self.script_mid, t) is not None))
                self.script_mid = None
            for stream in sorted(self.active_subs):
                mid = self.link.send(SUB, {"stream": stream, "on": False})
                actions.append((f"SUB {stream} off", self.link.reply(mid, t) is not None))
                self.active_subs.discard(stream)
            if self.upload and not self.upload.get("committed") and not self.upload.get("aborted"):
                r = self.cmd(f"fs_upload_abort {self.upload['token']}", t)
                actions.append(("fs_upload_abort", r is not None))
                self.upload["aborted"] = True
            if self.upload and self.upload.get("committed") and not self.upload.get("removed"):
                r = self.cmd(f"storage remove {self.upload['path']}", t)
                actions.append((f"storage remove {self.upload['path']}", bool(r and r["d"].get("ok"))))
                self.upload["removed"] = bool(r and r["d"].get("ok"))
        except (LinkError, OSError) as e:
            actions.append(("cleanup aborted", str(e)))
        return actions

    # -- checks ----------------------------------------------------------------------------
    def check_caps(self, r):
        c = self.caps
        r.evidence = {"hello": self.hello_evidence, "caps": c}
        problems = caps_problems(c)
        feats = sorted(k for k, v in self.features.items() if v is True)
        r.evidence["features_true"] = feats
        if "caps" not in c:
            r.warnings.append("no per-module caps map (older firmware): app tools stay disabled")
        if "rx_max" not in c:
            r.warnings.append(f"no rx_max in CAPS (older firmware): large frames sized for {LEGACY_RX_MAX} bytes")
        r.evidence["rx_max"] = {"value": self.rx_max, "source": self.rx_max_source, "rx_buf": c.get("rx_buf")}
        self.verdict(r, problems, f"{c.get('board')} / {c.get('chip')} fw {c.get('fw')}; features: "
                                  f"{', '.join(feats) or 'none'}")

    def check_provenance(self, r):
        b = self.caps.get("build")
        r.evidence = {"build": b, "fw": self.caps.get("fw")}
        if b is None:
            r.status, r.reason = "FAIL", "firmware reports no build provenance (CAPS d.build); record the build by hand"
            return
        if b.get("dirty") is True:
            r.warnings.append("dirty build: uncommitted changes at build time, not a release binary")
        if b.get("commit") == "unknown":
            r.warnings.append("commit unknown: built without Git metadata")
        self.verdict(r, build_problems(b), f"id {b.get('id')} commit {b.get('commit')} "
                                           f"time {b.get('time')} ({b.get('time_src')}) env {b.get('env')}")

    def check_evidence(self, r):
        ev = self.caps.get("evidence")
        if ev is None:
            r.status, r.reason = "SKIP", "no evidence map in CAPS (older firmware)"
            return
        table = {}
        if isinstance(ev, dict):
            for name, m in ev.items():
                if isinstance(m, dict):
                    table[name] = {"level": m.get("level"), "probe": m.get("probe"), "detail": m.get("detail"),
                                   "conflicts": [f"gpio {c.get('gpio')} {c.get('role')} with {c.get('with')} "
                                                 f"{c.get('with_role')}" for c in m.get("conflict", [])
                                                 if isinstance(c, dict)]}
                    caps_map = self.caps.get("caps") if isinstance(self.caps.get("caps"), dict) else {}
                    if m.get("probe") == "absent" and caps_map.get(name) is True:
                        r.warnings.append(f"{name}: caps true but probe absent (app treats it as unavailable)")
        r.evidence = {"modules": table, "raw": ev}
        conflicts = sum(len(v["conflicts"]) for v in table.values())
        self.verdict(r, evidence_problems(ev), f"{len(table)} modules, {conflicts} pin conflict entries; "
                                               "plausibility vs the board is a human check")

    def check_diag_shape(self, r):
        if not self.diag_ok:
            r.status, r.reason = "SKIP", "features.diag not advertised"
            return
        if self.baseline is None:
            r.status, r.reason = "FAIL", f"no diag ACK within {self.o.timeout}s"
            return
        d = self.baseline["reply"]
        r.evidence = {"diag": d}
        self.verdict(r, diag_problems(d), f"reset {d.get('reset')} uptime {d.get('uptime_s')} s heap free "
                                          f"{(d.get('heap') or {}).get('free')}")

    def ping_round(self, count, burst):
        """`count` sequential PINGs (latency, loss) then a pipelined burst of `burst` PINGs whose
        PONGs must come back in send order. A PONG for an earlier lost PING counts as late."""
        lat, lost, late, wrong = [], 0, 0, 0
        outstanding = self._ping_outstanding

        def other(f):
            nonlocal late, wrong
            if f.get("t") == PONG:
                if f.get("id") in outstanding:
                    late += 1
                    outstanding.discard(f.get("id"))
                else:
                    wrong += 1

        for _ in range(count):
            t0 = time.perf_counter()
            mid = self.link.send(PING, {})
            f = self.link.wait(lambda x, m=mid: x.get("t") == PONG and x.get("id") == m, self.o.ping_timeout, other)
            if f is None:
                lost += 1
                outstanding.add(mid)
            else:
                lat.append((time.perf_counter() - t0) * 1000.0)
        ids = [self.link.send(PING, {}) for _ in range(burst)]
        got = []
        deadline = time.time() + self.o.ping_timeout + 0.05 * len(ids)
        while len(got) < len(ids):
            f = self.link.recv(deadline)
            if f is None:
                break
            if f.get("t") == PONG and f.get("id") in ids:
                got.append(f["id"])
            else:
                other(f)
        outstanding.update(set(ids) - set(got))
        return {"lat": lat, "lost": lost, "late": late, "wrong": wrong, "burst_sent": len(ids),
                "burst_got": got, "burst_lost": len(ids) - len(got),
                "out_of_order": sum(1 for a, b in zip(got, got[1:]) if b < a)}

    def check_ping(self, r):
        p = self.ping_round(self.o.pings, self.o.ping_burst)
        lat, lost, late, wrong, got = p["lat"], p["lost"], p["late"], p["wrong"], p["burst_got"]
        burst_n, burst_lost, out_of_order = p["burst_sent"], p["burst_lost"], p["out_of_order"]
        stats = {}
        if lat:
            stats = {"n": len(lat), "min_ms": round(min(lat), 2), "median_ms": round(statistics.median(lat), 2),
                     "mean_ms": round(statistics.mean(lat), 2), "p95_ms": round(pct(lat, 95), 2),
                     "p99_ms": round(pct(lat, 99), 2), "max_ms": round(max(lat), 2)}
        r.evidence = {"sequential": {"sent": self.o.pings, "lost": lost, "late": late, "stats": stats},
                      "burst": {"sent": burst_n, "received": len(got), "lost": burst_lost,
                                "out_of_order": out_of_order},
                      "unexpected_pong_ids": wrong, "max_p99_ms": self.o.max_p99_ms}
        problems = []
        if lost:
            problems.append(f"{lost}/{self.o.pings} pings lost (timeout {self.o.ping_timeout}s)")
        if late:
            problems.append(f"{late} late PONGs")
        if burst_lost:
            problems.append(f"{burst_lost}/{burst_n} burst pings lost")
        if out_of_order:
            problems.append(f"{out_of_order} burst PONGs out of order")
        if wrong:
            problems.append(f"{wrong} PONGs with an id never sent")
        if stats and stats["p99_ms"] > self.o.max_p99_ms:
            problems.append(f"p99 {stats['p99_ms']} ms > {self.o.max_p99_ms} ms")
        self.verdict(r, problems, f"{len(lat)} ok, median {stats.get('median_ms')} ms, p99 {stats.get('p99_ms')} ms, "
                                  f"burst {len(got)}/{burst_n} in order")

    def check_malformed(self, r):
        before = self.diag() if self.diag_ok else None
        self.link.send_malformed()
        errf = self.link.wait(lambda f: f.get("t") == ERR and f.get("id") == 0, self.o.timeout)
        after = self.diag() if self.diag_ok else None
        r.evidence = {"err": errf.get("d") if errf else None}
        problems = []
        if errf is None or (errf.get("d") or {}).get("code") != 400:
            problems.append("no ERR 400 (id 0) for the bad-JSON frame")
        if self.diag_ok:
            if before is None or after is None:
                problems.append("diag before/after missing")
            else:
                b, a = before["link"], after["link"]
                delta = {"rx_bad_json": a["rx_bad_json"] - b["rx_bad_json"],
                         "err_400": a["err"]["400"] - b["err"]["400"],
                         "rx_frames": a["rx_frames"] - b["rx_frames"]}
                r.evidence["delta"] = delta
                if delta["rx_bad_json"] != 1:
                    problems.append(f"rx_bad_json grew by {delta['rx_bad_json']}, expected 1")
                if delta["err_400"] != 1:
                    problems.append(f"err.400 grew by {delta['err_400']}, expected 1")
                if delta["rx_frames"] != 2:
                    problems.append(f"rx_frames grew by {delta['rx_frames']}, expected 2 (bad frame + diag)")
        else:
            r.warnings.append("features.diag not advertised: counters not checked")
        self.verdict(r, problems, "ERR 400 returned" + (", rx_bad_json +1, err.400 +1" if self.diag_ok else ""))

    def check_fs_list(self, r, listing):
        resp = self.cmd("storage list /", self.o.timeout)
        r.evidence = {"reply": resp.get("d") if resp else None}
        if resp is None or resp.get("t") != ACK:
            r.status, r.reason = "FAIL", "no ACK for storage list /"
            return
        d = resp.get("d") or {}
        entries = parse_listing(d.get("out"))
        listing["/"] = entries
        r.evidence = {"ok": d.get("ok"), "entries": entries[:100], "count": len(entries)}
        problems = [] if d.get("ok") is True else [f"storage list / returned ok={d.get('ok')!r}: {d.get('out')!r}"]
        self.verdict(r, problems, f"{len(entries)} entries at /")

    def fs_read(self, path, timeout):
        """fs_read with id/seq checks. Returns (bytes or None, evidence dict)."""
        chunks, problems, expected_seq = [], [], 0
        t0 = time.time()
        mid = self.link.send(CMD, {"cmd": f"fs_read {path}"})

        def other(f):
            nonlocal expected_seq
            d = f.get("d") or {}
            if f.get("t") == DATA and d.get("stream") == "file":
                if f.get("id") != mid:
                    problems.append(f"DATA id {f.get('id')} != request id {mid}")
                    return
                if d.get("seq") != expected_seq:
                    problems.append(f"DATA seq {d.get('seq')} != expected {expected_seq}")
                expected_seq += 1
                try:
                    chunks.append(base64.b64decode(d.get("chunk", ""), validate=True))
                except ValueError:
                    problems.append(f"bad base64 in chunk {d.get('seq')}")

        end = self.link.reply(mid, timeout, other)
        data = b"".join(chunks)
        crc = zlib.crc32(data) & U32
        ev = {"path": path, "request_id": mid, "chunks": expected_seq, "bytes": len(data), "crc32": crc,
              "seconds": round(time.time() - t0, 3)}
        if end is None:
            problems.append(f"no terminal ACK within {timeout}s")
        elif end.get("t") == ERR:
            problems.append(f"ERR {end['d'].get('code')}: {end['d'].get('msg')}")
        else:
            d = end.get("d") or {}
            ev["ack"] = d
            if d.get("ok") is not True:
                problems.append("terminal ACK ok is not true")
            if d.get("size") != len(data):
                problems.append(f"ACK size {d.get('size')} != received {len(data)}")
            if d.get("crc32") != crc:
                problems.append(f"ACK crc32 {d.get('crc32')} != computed {crc}")
        ev["problems"] = problems
        return (None if problems else data), ev

    def pick_read_file(self, entries):
        """(path, listed size) of the smallest regular file at / up to --read-max, never a
        staging or scratch file; (None, None) if there is none."""
        files = [e for e in entries if not e["dir"] and 0 < e["size"] <= self.o.read_max
                 and not e["name"].lower().startswith(".brucia-upload.")
                 and not e["name"].startswith(SCRATCH_PREFIX[1:])]
        if not files:
            return None, None
        pick = min(files, key=lambda e: (e["size"], e["name"]))
        return "/" + pick["name"], pick["size"]

    def check_fs_read(self, r, listing):
        if self.features.get("verified_files") is not True:
            r.status, r.reason = "SKIP", "features.verified_files not advertised"
            return
        path, listed = self.o.read_path, None
        if path is None:
            if "/" not in listing:
                r.status, r.reason = "SKIP", "no root listing (run fs_list or pass --read-path)"
                return
            path, listed = self.pick_read_file(listing["/"])
            if path is None:
                r.status, r.reason = "SKIP", f"no file of 1..{self.o.read_max} bytes at / (pass --read-path)"
                return
        data, ev = self.fs_read(path, self.o.fs_timeout)
        ev["listed_size"] = listed
        r.evidence = ev
        problems = list(ev["problems"])
        if listed is not None and data is not None and len(data) != listed:
            problems.append(f"read {len(data)} bytes but the listing says {listed}")
        self.verdict(r, problems, f"{path}: {ev['bytes']} bytes, crc32 {ev['crc32']:08x} verified")

    def check_fs_upload(self, r):
        if self.features.get("verified_files") is not True or self.features.get("transactional_uploads") is not True:
            r.status, r.reason = "SKIP", "features.verified_files/transactional_uploads not advertised"
            return
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        path = f"{SCRATCH_PREFIX}{stamp}-{random.getrandbits(24):06x}.txt"
        token = "%032x" % random.getrandbits(128)
        # The first chunk is the largest frame the device advertises it can take (rx_max), the
        # second half that: the size class that was lost on hardware with a too-small RX queue.
        chunk = upload_chunk_size(self.rx_max, token, UPLOAD_MAX_CHUNK * 2)
        if chunk < 3:
            r.status, r.reason = "FAIL", f"rx_max {self.rx_max} leaves no room for an upload chunk"
            return
        size = min(chunk + chunk // 2, UPLOAD_MAX_SIZE)
        line = f"Brucia hw_acceptance scratch file {stamp}; safe to delete.\n".encode()
        body = (line * (1 + size // len(line)))[:size]
        crc = zlib.crc32(body) & U32
        steps, problems = [], []
        r.evidence = {"path": path, "size": len(body), "crc32": crc, "steps": steps, "rx_max": self.rx_max,
                      "rx_max_source": self.rx_max_source, "chunk_raw": chunk, "frame_payloads": []}

        def step(name, resp, ok):
            steps.append({"step": name, "reply": resp.get("d") if resp else None, "ok": bool(ok)})
            if not ok:
                problems.append(f"{name}: " + ("timeout" if resp is None else json.dumps(resp.get("d"))[:160]))
            return ok

        pre = self.cmd("storage list /", self.o.timeout)
        names = {e["name"] for e in parse_listing((pre or {}).get("d", {}).get("out"))}
        if not step("pre-list", pre, pre is not None and pre.get("t") == ACK and path[1:] not in names):
            self.verdict(r, problems, "")
            return
        self.upload = {"token": token, "path": path, "committed": False, "removed": False}
        resp = self.cmd(f"fs_upload_begin {token} {len(body)} {crc} {path}", self.o.fs_timeout)
        ok = step("begin", resp, resp and resp.get("t") == ACK and resp["d"].get("token") == token
                  and resp["d"].get("size") == 0)
        off = chunks = 0
        while ok and off < len(body):
            part = body[off:off + chunk]
            sent_before = len(self.link.commands)
            resp = self.cmd(f"fs_upload_chunk {token} {off} {base64.b64encode(part).decode()}", self.o.timeout)
            if len(self.link.commands) > sent_before:
                r.evidence["frame_payloads"].append(self.link.commands[sent_before].get("bytes"))
            off += len(part)
            chunks += 1
            ok = step(f"chunk@{off - len(part)}", resp, resp and resp.get("t") == ACK
                      and resp["d"].get("token") == token and resp["d"].get("size") == off)
        acked_commit = False
        if ok:
            resp = self.cmd(f"fs_upload_commit {token}", self.o.fs_timeout)
            ok = step("commit", resp, resp and resp.get("t") == ACK and resp["d"].get("committed") is True
                      and resp["d"].get("crc32") == crc and resp["d"].get("size") == len(body))
            acked_commit = bool(resp and resp.get("t") == ACK and resp["d"].get("committed") is True)
            if resp is None or acked_commit:
                # Committed, or unknown after a lost ACK: never abort; verify/remove the destination.
                self.upload["committed"] = True
                if ok and resp["d"].get("cleanup_pending"):
                    r.warnings.append("commit reported cleanup_pending:true (FAT backup cleanup failed)")
            else:  # rejected commit: nothing promoted; release the staging (best effort)
                self.cmd(f"fs_upload_abort {token}", self.o.timeout)
                self.upload["aborted"] = True
        else:
            a = self.cmd(f"fs_upload_abort {token}", self.o.timeout)
            step("abort", a, a is not None and a.get("t") == ACK)
            self.upload["aborted"] = True
        if ok:
            data, ev = self.fs_read(path, self.o.fs_timeout)
            steps.append({"step": "read-back", "evidence": ev, "ok": data == body})
            if data != body:
                problems.append("read-back differs from the uploaded bytes" + (f": {ev['problems']}" if ev["problems"] else ""))
        if self.upload.get("committed"):
            resp = self.cmd(f"storage remove {path}", self.o.timeout)
            removed = bool(resp and resp.get("t") == ACK and resp["d"].get("ok") is True)
            if acked_commit:
                step("remove", resp, removed)
            else:  # commit outcome unknown: the file may legitimately be absent
                steps.append({"step": "remove (commit outcome unknown)", "reply": resp.get("d") if resp else None,
                              "ok": removed})
            post = self.cmd("storage list /", self.o.timeout)
            names = {e["name"] for e in parse_listing((post or {}).get("d", {}).get("out"))}
            gone = step("post-list (scratch gone)", post, post is not None and post.get("t") == ACK
                        and path[1:] not in names)
            self.upload["removed"] = removed or gone
        else:
            self.upload["removed"] = True  # nothing was promoted
        self.verdict(r, problems, f"{path}: begin, {chunks} chunks (largest {chunk} raw bytes, rx_max "
                                  f"{self.rx_max}), commit, read-back and remove verified")

    def check_streams(self, r):
        r.evidence = {}
        problems, ran = [], 0
        for stream in self.o.streams:
            need = stream_module(stream)
            if not self.caps_flag(need):
                r.evidence[stream] = {"skipped": f"caps.{need} not true"}
                continue
            ran += 1
            info = {"events": 0, "bad_events": [], "after_off": 0, "sample": []}
            r.evidence[stream] = info
            problems += self.stream_cycle(stream, info)
            if info["events"] == 0:
                r.warnings.append(f"{stream}: no events in {self.o.stream_secs}s (nothing nearby, or not receiving)")
        if not ran:
            r.status, r.reason = "SKIP", "no requested stream is supported by CAPS"
            return
        summary = ", ".join(f"{s}: {v.get('events', 0)} events" for s, v in r.evidence.items())
        self.verdict(r, problems, f"{summary}; ON/OFF ACKed, silent after OFF")

    def stream_cycle(self, stream, info):
        """SUB ON, listen --stream-secs, SUB OFF, then --quiet-secs that must stay silent.
        Fills `info` (events, bad_events, after_off, sample, replies); returns the problems."""
        ev_name = RECEIVE_ONLY_STREAMS[stream]
        problems = []

        def collect(f):
            d = f.get("d") or {}
            if f.get("t") == EVENT and d.get("ev") == ev_name:
                data = d.get("data")
                info["events"] += 1
                if len(info["sample"]) < 5:
                    info["sample"].append(data)
                if not (isinstance(data, dict) and all(k in data for k in STREAM_EVENT_KEYS[stream])):
                    info["bad_events"].append(data)

        mid = self.link.send(SUB, {"stream": stream, "on": True})
        self.active_subs.add(stream)
        on = self.link.reply(mid, self.o.sub_timeout, collect)
        info["on_reply"] = on.get("d") if on else None
        if on is None or on.get("t") != ACK or (on.get("d") or {}).get("ok") is not True:
            problems.append(f"{stream}: SUB on not ACKed ok ({info['on_reply']})")
        else:
            self.stream_listen(stream, collect)
        mid = self.link.send(SUB, {"stream": stream, "on": False})
        off = self.link.reply(mid, self.o.sub_timeout, collect)
        info["off_reply"] = off.get("d") if off else None
        if off is None or off.get("t") != ACK or (off.get("d") or {}).get("ok") is not True:
            problems.append(f"{stream}: SUB off not ACKed ok ({info['off_reply']})")
        else:
            self.active_subs.discard(stream)
        before_quiet = info["events"]
        self.link.wait(lambda f: False, self.o.quiet_secs, collect)
        info["after_off"] = info["events"] - before_quiet
        if info["after_off"]:
            problems.append(f"{stream}: {info['after_off']} events after the OFF ACK")
        if info["bad_events"]:
            problems.append(f"{stream}: {len(info['bad_events'])} events missing {STREAM_EVENT_KEYS[stream]}")
        return problems

    def stream_listen(self, stream, collect):
        self.link.wait(lambda f: False, self.o.stream_secs, collect)

    def check_busy(self, r):
        if not self.caps_flag("wifi"):
            r.status, r.reason = "SKIP", "caps.wifi not true"
            return
        secs = self.o.busy_secs
        t0 = time.time()
        self.busy_mid = self.link.send(CMD, {"cmd": f"jam_detect {secs}"})
        early = self.link.reply(self.busy_mid, 1.0)
        if early is not None:
            self.busy_mid = None
            d = early.get("d") or {}
            r.evidence = {"jam_detect_reply": d}
            if early.get("t") == ERR and d.get("code") == 501:
                r.status, r.reason = "SKIP", "jam_detect not in this build (ERR 501)"
            else:
                r.status, r.reason = "FAIL", f"jam_detect answered at once: {d}"
            return
        steps, problems = {}, []
        pm = self.link.send(PING, {})
        pong = self.link.reply(pm, self.o.timeout)
        steps["ping_while_busy"] = pong is not None and pong.get("t") == PONG
        if not steps["ping_while_busy"]:
            problems.append("no PONG while busy")
        dm = self.link.send(CMD, {"cmd": "diag"})
        busy = self.link.reply(dm, self.o.timeout)
        steps["diag_while_busy"] = busy.get("d") if busy else None
        if not (busy and busy.get("t") == ERR and (busy.get("d") or {}).get("code") == 409):
            problems.append(f"CMD while busy was not ERR 409: {steps['diag_while_busy']}")
        # A SUB awaits an ACK too: firmware before 2026-11-06 dropped it unanswered.
        sm = self.link.send(SUB, {"stream": "wifi.probe", "on": True})
        sub = self.link.reply(sm, self.o.timeout)
        steps["sub_while_busy"] = sub.get("d") if sub else None
        if not (sub and sub.get("t") == ERR and (sub.get("d") or {}).get("code") == 409):
            problems.append(f"SUB while busy was not ERR 409: {steps['sub_while_busy']}")
            if sub and sub.get("t") == ACK:  # accepted anyway: do not leave the stream on
                self.link.send(SUB, {"stream": "wifi.probe", "on": False})
        ts = time.time()
        self.link.send(CMD, {"cmd": "stop"})
        end = self.link.reply(self.busy_mid, self.o.timeout + 3)
        steps["stop_to_ack_s"] = round(time.time() - ts, 3)
        steps["total_s"] = round(time.time() - t0, 3)
        steps["final"] = end.get("d") if end else None
        if end is None:
            problems.append("no terminal ACK after stop")
        else:
            self.busy_mid = None
            if steps["stop_to_ack_s"] >= secs:
                problems.append("stop did not end the operation early")
        r.evidence = steps
        self.verdict(r, problems, f"PONG, CMD and SUB ERR 409 while busy; stop ended it in {steps['stop_to_ack_s']} s")

    def check_idle_stops(self, r):
        problems, steps = [], {}
        for cmd in ("nrf24_stop", "jam_detect_stop", "ir_power_spam_stop"):
            mid = self.link.send(CMD, {"cmd": cmd})
            rep = self.link.reply(mid, self.o.timeout)
            d = (rep or {}).get("d") or {}
            steps[cmd] = d if rep else None
            if not (rep and rep.get("t") == ACK and d.get("ok") is True and d.get("out") == "not running"):
                problems.append(f"{cmd}: {steps[cmd]}")
        r.evidence = steps
        self.verdict(r, problems, "all idle stops answered ok \"not running\"")

    def check_script_stop(self, r):
        """script_run of SCRIPT_LONG, PING while it runs, script_stop after --script-stop-after s: the
        run must end with ok:false `script stopped` within --script-stop-bound s, the stop itself
        must get no reply, and an immediate script_run of SCRIPT_SHORT must print its text (not
        `interpreter busy`)."""
        steps, problems, stop_ids, stop_replies, run_end = {}, [], set(), [], []

        def other(f):
            if f.get("t") not in (ACK, ERR):
                return
            if f.get("id") in stop_ids:
                stop_replies.append(f.get("d"))
            elif self.script_mid is not None and f.get("id") == self.script_mid:
                run_end.append(f)  # the run ended while we waited for something else (e.g. the PONG)

        t0 = time.time()
        self.script_mid = self.link.send(CMD, {"cmd": f"script_run {SCRIPT_LONG}"})
        early = self.link.reply(self.script_mid, self.o.script_stop_after, other)
        if early is not None:
            self.script_mid = None
            d = early.get("d") or {}
            r.evidence = {"script_run_reply": d, "after_s": round(time.time() - t0, 3)}
            if early.get("t") == ERR and d.get("code") == 501:
                r.status, r.reason = "SKIP", "script_run not in this build (ERR 501)"
            else:
                r.status, r.reason = "FAIL", f"the looping script ended before the stop: {d}"
            return
        pm = self.link.send(PING, {})
        pong = self.link.reply(pm, self.o.timeout, other)
        steps["ping_while_running"] = pong is not None and pong.get("t") == PONG
        if not steps["ping_while_running"]:
            problems.append("no PONG while the script ran")
        ts = time.time()
        if run_end:
            self.script_mid = None
            r.evidence = {**steps, "run_reply": run_end[0].get("d")}
            r.status, r.reason = "FAIL", f"the looping script ended before the stop: {run_end[0].get('d')}"
            return
        stop_ids.add(self.link.send(CMD, {"cmd": "script_stop"}))
        # Wait past the bound (up to the 20 s wall clock) so a slow or missing stop shows its real outcome.
        end = self.link.reply(self.script_mid, 20 + self.o.timeout, other)
        end = end or (run_end[0] if run_end else None)
        steps["stop_to_reply_s"] = round(time.time() - ts, 3)
        steps["run_reply"] = end.get("d") if end else None
        if end is None:
            problems.append("the script_run never answered after script_stop")
            r.evidence = steps
            return self.verdict(r, problems, "")
        self.script_mid = None
        d = end.get("d") or {}
        if not (end.get("t") == ACK and d.get("ok") is False and d.get("out") == "script stopped"):
            problems.append(f"script_run did not end with ok:false 'script stopped': {d}")
        if steps["stop_to_reply_s"] > self.o.script_stop_bound:
            problems.append(f"the run ended {steps['stop_to_reply_s']} s after script_stop "
                            f"(bound {self.o.script_stop_bound} s)")
        tn = time.time()
        nm = self.link.send(CMD, {"cmd": f"script_run {SCRIPT_SHORT}"})
        nxt = self.link.reply(nm, self.o.timeout + 5, other)
        steps["next_run_s"] = round(time.time() - tn, 3)
        steps["next_run_reply"] = nxt.get("d") if nxt else None
        nd = (nxt or {}).get("d") or {}
        if nxt is None:
            problems.append("the next script_run did not answer")
        elif nd.get("out") == "interpreter busy":
            problems.append("the next script_run was refused: interpreter busy")
        elif not (nxt.get("t") == ACK and nd.get("ok") is True and SCRIPT_SHORT_OUT in str(nd.get("out", ""))):
            problems.append(f"the next script_run did not print {SCRIPT_SHORT_OUT!r}: {nd}")
        self.link.wait(lambda f: False, 0.5, other)  # a reply to the stop would arrive by now
        steps["stop_replies"] = stop_replies
        if stop_replies:
            problems.append(f"script_stop was answered instead of consumed by the run: {stop_replies}")
        steps["total_s"] = round(time.time() - t0, 3)
        r.evidence = steps
        self.verdict(r, problems, f"script stopped {steps['stop_to_reply_s']} s after script_stop; "
                                  f"the next script_run printed {SCRIPT_SHORT_OUT!r}")

    # -- soak ------------------------------------------------------------------------------
    def check_soak(self, r):
        """--soak: repeat passive intervals for the given minutes. Each interval sends, in order:
        --soak-pings sequential PINGs and a --soak-burst pipelined burst; `storage list /`;
        `fs_read` of one small existing file (CRC must not change); one scratch upload cycle
        (check_fs_upload); one receive-only stream ON/OFF, rotating through --streams; keepalive
        PINGs until the interval ends; `diag`. Findings (each FAILs the check): a failed or lost
        operation, link counters that differ from the frames sent/received, any reject, ERR,
        noise byte or resync, an unexpected reboot (stops the soak), a heap.free decline above
        the detection limit (heap_trend), an overall PING p99 above --max-p99-ms."""
        if not self.diag_ok:
            r.status, r.reason = "SKIP", "features.diag not advertised: the soak needs diag (heap, counters, reboots)"
            return
        o = self.o
        streams = [st for st in o.streams if self.caps_flag(stream_module(st))]
        s = {
            "t0": time.time(), "started_utc": utc_now(), "k": 0, "row": None, "rows": [], "ops": [],
            "findings": [], "findings_total": 0, "warnings": [], "lat": [], "first": None, "prev": None,
            "stop": None, "interrupted": False, "streams": streams, "warmup_intervals": max(1, len(streams)),
            "read": {"chosen": False, "path": None, "listed": None, "crc32": None, "size": None},
            "uploads_on": True, "uploads": 0, "scratch_paths": [],
            "stream_cycles": {st: 0 for st in streams}, "stream_events": {st: 0 for st in streams},
            "config": {"minutes": o.soak, "interval_s": o.soak_interval, "keepalive_s": o.soak_keepalive,
                       "pings_per_interval": o.soak_pings, "burst_per_interval": o.soak_burst,
                       "streams_requested": o.streams, "streams": streams, "stream_secs": o.stream_secs,
                       "quiet_secs": o.quiet_secs, "max_leak_bph": o.soak_max_leak,
                       "heap_tolerance_bytes": o.heap_tolerance, "max_p99_ms": o.max_p99_ms,
                       "warmup_intervals": max(1, len(streams))},
        }
        self.soak = s
        try:
            self._soak_loop(s)
        except KeyboardInterrupt:
            s["interrupted"] = True
            raise
        except LinkError as e:
            s["stop"] = f"link error: {e}"
            self._soak_finding(s, "link", str(e))
            raise
        finally:
            self._soak_finish(r, s)

    def _soak_loop(self, s):
        o = self.o
        end = s["t0"] + o.soak * 60
        files = self.features.get("verified_files") is True
        uploads = files and self.features.get("transactional_uploads") is True
        if not files:
            s["warnings"].append("features.verified_files not advertised: no fs_read/upload in the soak")
        elif not uploads:
            s["warnings"].append("features.transactional_uploads not advertised: no upload cycle in the soak")
        if not s["streams"]:
            s["warnings"].append(f"no requested stream ({', '.join(o.streams)}) is supported by CAPS: no stream cycles")
        s["row"] = self._soak_row(0)
        self._soak_op(s, "diag", lambda: self._soak_sample(s))  # interval 0: the soak baseline
        self._soak_close_row(s)
        self.log(f"[soak] {o.soak} min, intervals of {o.soak_interval} s, keepalive {o.soak_keepalive} s, "
                 f"streams {', '.join(s['streams']) or 'none'}")
        while not s["stop"] and time.time() < end:
            s["k"] += 1
            k, iv0 = s["k"], time.time()
            s["row"] = self._soak_row(k)
            self._soak_op(s, "ping_round", lambda: self._soak_pings(s, o.soak_pings, o.soak_burst))
            if files:
                self._soak_op(s, "fs_list", lambda: self._soak_fs_list(s))
                if s["read"]["path"]:
                    self._soak_op(s, "fs_read", lambda: self._soak_fs_read(s))
            if uploads and s["uploads_on"]:
                self._soak_op(s, "fs_upload", lambda: self._soak_upload(s))
            if s["streams"]:
                stream = s["streams"][(k - 1) % len(s["streams"])]
                self._soak_op(s, "stream", lambda: self._soak_stream(s, stream))
            # Rest of the interval: PING whenever --soak-keepalive passed since the last host frame
            # (the device leaves companion mode after 60 s without one); frames are still read.
            until = min(iv0 + o.soak_interval, end)
            while True:
                now = time.time()
                if now >= until:
                    break
                due = (self.link.commands[-1]["t"] if self.link.commands else 0) + o.soak_keepalive
                if now >= due:
                    s["row"]["keepalives"] += 1
                    self._soak_op(s, "keepalive", lambda: self._soak_pings(s, 1, 0))
                else:
                    self.link.wait(lambda f: False, min(due, until) - now)
            self._soak_op(s, "diag", lambda: self._soak_sample(s))
            self._soak_close_row(s)
            done = time.time() - s["t0"]
            self.log(f"[soak] interval {k}: {done / 60:.2f}/{o.soak} min, heap free {s['row'].get('heap_free')}, "
                     f"findings {s['findings_total']}")

    @staticmethod
    def _soak_row(k):
        return {"interval": k, "pings": 0, "pings_lost": 0, "keepalives": 0, "findings": 0, "_lat": []}

    def _soak_close_row(self, s):
        row = s["row"]
        if row is None or row.get("_closed"):
            return
        lat = latency_stats(row.pop("_lat", []))
        row.update({k: lat.get(k) for k in ("p50_ms", "p95_ms", "p99_ms", "max_ms")})
        row["_closed"] = True
        if row["interval"] and lat["n"] and lat["p99_ms"] > self.o.max_p99_ms:
            s["warnings"].append(f"interval {row['interval']}: PING p99 {lat['p99_ms']} ms > {self.o.max_p99_ms} ms")
        s["rows"].append(row)

    def _soak_finding(self, s, kind, detail):
        s["findings_total"] += 1
        if s["row"] is not None:
            s["row"]["findings"] = s["row"].get("findings", 0) + 1
        if len(s["findings"]) < SOAK_FINDINGS_KEPT:
            s["findings"].append({"interval": s["k"], "elapsed_s": round(time.time() - s["t0"], 2), "utc": utc_now(),
                                  "kind": kind, "detail": detail})
        self.log(f"[soak] finding ({kind}) in interval {s['k']}: {detail}")

    def _soak_op(self, s, name, fn):
        """Run one operation, record its timing; a failed operation is a finding."""
        t = time.time()
        ok, detail = False, "did not complete"
        try:
            ok, detail = fn()
        finally:
            ms = round((time.time() - t) * 1000, 1)
            entry = {"interval": s["k"], "op": name, "t_s": round(t - s["t0"], 3), "ms": ms, "ok": bool(ok)}
            if not ok:
                entry["detail"] = detail
            s["ops"].append(entry)
            if f"{name}_ms" in SOAK_CSV_COLUMNS:
                s["row"][f"{name}_ms"] = ms
        if not ok:
            self._soak_finding(s, "op", f"{name}: {detail}")
        return ok

    def _soak_pings(self, s, count, burst):
        p = self.ping_round(count, burst)
        s["lat"] += p["lat"]
        s["row"]["_lat"] += p["lat"]
        s["row"]["pings"] += count + burst
        s["row"]["pings_lost"] += p["lost"] + p["burst_lost"]
        problems = []
        if p["lost"]:
            problems.append(f"{p['lost']}/{count} PINGs lost (timeout {self.o.ping_timeout}s)")
        if p["late"]:
            problems.append(f"{p['late']} late PONGs")
        if p["burst_lost"]:
            problems.append(f"{p['burst_lost']}/{burst} burst PINGs lost")
        if p["out_of_order"]:
            problems.append(f"{p['out_of_order']} burst PONGs out of order")
        if p["wrong"]:
            problems.append(f"{p['wrong']} PONGs with an id never sent")
        return not problems, "; ".join(problems)

    def _soak_fs_list(self, s):
        resp = self.cmd("storage list /", self.o.timeout)
        if resp is None or resp.get("t") != ACK:
            return False, "no ACK for storage list /"
        d = resp.get("d") or {}
        if d.get("ok") is not True:
            return False, f"storage list / returned ok={d.get('ok')!r}: {str(d.get('out'))[:120]!r}"
        entries = parse_listing(d.get("out"))
        rd = s["read"]
        if not rd["chosen"]:
            rd["chosen"] = True
            rd["path"], rd["listed"] = (self.o.read_path, None) if self.o.read_path else self.pick_read_file(entries)
            if rd["path"] is None:
                s["warnings"].append(f"no file of 1..{self.o.read_max} bytes at / (pass --read-path): no fs_read")
        left = [p for p in s["scratch_paths"] if p[1:] in {e["name"] for e in entries}]
        if left:
            return False, f"scratch file(s) of this soak still listed: {left}"
        return True, None

    def _soak_fs_read(self, s):
        rd = s["read"]
        data, ev = self.fs_read(rd["path"], self.o.fs_timeout)
        problems = list(ev["problems"])
        if data is not None:
            if rd["listed"] is not None and len(data) != rd["listed"]:
                problems.append(f"read {len(data)} bytes but the listing says {rd['listed']}")
            if rd["crc32"] is None:
                rd["crc32"], rd["size"] = ev["crc32"], ev["bytes"]
            elif (ev["crc32"], ev["bytes"]) != (rd["crc32"], rd["size"]):
                problems.append(f"{rd['path']} changed: {ev['bytes']} bytes crc32 {ev['crc32']:08x}, first read "
                                f"{rd['size']} bytes crc32 {rd['crc32']:08x}")
        return not problems, "; ".join(problems)

    def _soak_upload(self, s):
        """One scratch upload cycle: the fs_upload check itself (same allowlist, unique name,
        commit, read-back, remove, listing confirms it is gone)."""
        tr = Result("fs_upload", "soak upload cycle")
        self.check_fs_upload(tr)
        path = tr.evidence.get("path") if isinstance(tr.evidence, dict) else None
        if path:
            s["scratch_paths"].append(path)
            s["uploads"] += 1
        for w in tr.warnings:
            if w not in s["warnings"]:
                s["warnings"].append(w)
        if self.upload and self.upload.get("committed") and not self.upload.get("removed"):
            s["uploads_on"] = False  # keep self.upload: cleanup() retries the remove at exit
            return False, (f"{tr.reason}; scratch {self.upload['path']} not removed: no more uploads in this soak "
                           "(cleanup retries at exit)")
        return tr.status == "PASS", tr.reason

    def _soak_stream(self, s, stream):
        info = {"events": 0, "bad_events": [], "after_off": 0, "sample": []}
        problems = self.stream_cycle(stream, info)
        s["row"]["stream"], s["row"]["stream_events"] = stream, info["events"]
        s["stream_cycles"][stream] += 1
        s["stream_events"][stream] += info["events"]
        return not problems, "; ".join(problems)

    def _soak_sample(self, s):
        """diag at the end of an interval: heap/uptime/reset into the row, link counters
        compared with the previous sample (exact frame accounting, as diag_counters)."""
        d = self.diag()
        if d is None:
            s["stop"] = "diag did not answer"
            return False, f"no diag ACK within {self.o.timeout}s"
        shape = diag_problems(d)
        if shape:
            s["stop"] = "malformed diag reply"
            return False, "; ".join(shape)
        now = time.time()
        smp = {"reply": d, "sent": self.link.sent, "recv": self.link.received - 1, "noise": self.link.noise_bytes,
               "resyncs": self.link.resyncs, "bad_rx": self.link.bad_rx_json, "errors": len(self.link.errors)}
        heap = d["heap"]
        s["row"].update({"utc": utc_now(), "elapsed_s": round(now - s["t0"], 2), "uptime_s": d["uptime_s"],
                         "reset": d["reset"], "heap_free": heap["free"], "heap_min_free": heap["min_free"],
                         "heap_largest": heap["largest"]})
        if s["prev"] is None:
            s["first"] = smp
        else:
            self._soak_compare(s, s["prev"], smp)
        s["prev"] = smp
        return True, None

    def _soak_compare(self, s, prev, smp):
        pd, d, first = prev["reply"], smp["reply"], s["first"]["reply"]
        pl, al = pd["link"], d["link"]
        row = s["row"]
        drx, dtx = al["rx_frames"] - pl["rx_frames"], al["tx_frames"] - pl["tx_frames"]
        exp_rx, exp_tx = smp["sent"] - prev["sent"], smp["recv"] - prev["recv"]
        row.update({"dev_rx_delta": drx, "host_sent_delta": exp_rx, "dev_tx_delta": dtx, "host_recv_delta": exp_tx})
        reboot = []
        if (d["reset"], d["reset_code"]) != (first["reset"], first["reset_code"]):
            reboot.append(f"reset reason {first['reset']}/{first['reset_code']} -> {d['reset']}/{d['reset_code']}")
        if d["uptime_s"] < pd["uptime_s"]:
            reboot.append(f"uptime went back {pd['uptime_s']} -> {d['uptime_s']} s")
        if drx < 0 or dtx < 0 or al["sessions"] < pl["sessions"]:
            reboot.append("link counters went back")
        if reboot:
            self._soak_finding(s, "reboot", "unexpected reboot: " + "; ".join(reboot))
            s["stop"] = "unexpected reboot"
            return
        if drx != exp_rx:
            self._soak_finding(s, "counters", f"rx_frames +{drx}, runner sent {exp_rx} frames")
        if dtx != exp_tx:
            self._soak_finding(s, "counters", f"tx_frames +{dtx}, runner received {exp_tx} frames")
        if al["tx_torn"] != pl["tx_torn"]:
            self._soak_finding(s, "tx_torn",
                               f"tx_torn +{al['tx_torn'] - pl['tx_torn']}: a frame was abandoned mid-write "
                               f"(TX stream desync; the firmware dropped the link to resync)")
        rejects = {k: al[k] - pl[k] for k in REJECT_KEYS + ("rx_bad_json",) if al[k] != pl[k]}
        row["rejects_delta"] = sum(rejects.values())
        if rejects:
            self._soak_finding(s, "reject", f"device rejected inbound frames: {rejects}")
        if al["busy_409"] != pl["busy_409"]:
            self._soak_finding(s, "counters", f"busy_409 +{al['busy_409'] - pl['busy_409']}")
        if al["sessions"] != pl["sessions"]:
            self._soak_finding(s, "session", f"sessions +{al['sessions'] - pl['sessions']}: companion mode re-entered")
        err_dev = sum(al["err"].values()) - sum(pl["err"].values())
        errs = self.link.errors[prev["errors"]:smp["errors"]]
        row["err_delta"] = err_dev
        if err_dev or errs:
            self._soak_finding(s, "err", f"device err +{err_dev}, ERR replies received: "
                                         f"{[(code, msg) for _, _, code, msg in errs][:5]}")
        noise, resyncs, bad = smp["noise"] - prev["noise"], smp["resyncs"] - prev["resyncs"], smp["bad_rx"] - prev["bad_rx"]
        row.update({"noise_bytes_delta": noise, "resyncs_delta": resyncs, "bad_rx_json_delta": bad})
        if noise or resyncs:
            sample = next((e["sample"] for e in reversed(self.link.noise_samples) if e["sample"]), "")
            self._soak_finding(s, "noise", f"{noise} non-frame byte(s) from the device in {resyncs} resync(s); "
                                           f"sample: {sample[:80]!r}")
        if bad:
            self._soak_finding(s, "noise", f"{bad} undecodable frame(s) from the device")

    def _soak_finish(self, r, s):
        """Summary, heap trend and verdict; also runs on Ctrl-C and link loss."""
        o = self.o
        if s["k"]:
            self._soak_close_row(s)
        elapsed = time.time() - s["t0"]
        rows = [x for x in s["rows"] if x.get("heap_free") is not None]
        warm = s["warmup_intervals"]
        after = [x for x in rows if x["interval"] >= warm]
        heap = heap_trend([(x["elapsed_s"], x["heap_free"]) for x in after], o.soak_max_leak, o.heap_tolerance)
        largest = heap_trend([(x["elapsed_s"], x["heap_largest"]) for x in after], o.soak_max_leak, o.heap_tolerance)
        if heap["leak"]:
            self._soak_finding(s, "heap", f"heap.free falls {-heap['slope_bph']:.0f} B/h over {heap['window_h']} h "
                                          f"(detection limit {heap['detection_limit_bph']} B/h, fitted decline "
                                          f"{heap['fitted_decline_bytes']} B, {heap['first']} -> {heap['last']})")
        elif not heap["assessed"]:
            s["warnings"].append(f"heap trend not assessed: {heap['reason']} (warm-up {warm} interval(s); "
                                 "run longer)")
        if largest["leak"]:
            s["warnings"].append(f"largest free block falls {-largest['slope_bph']:.0f} B/h (fragmentation; reported, "
                                 "not a FAIL by itself)")
        lat = latency_stats(s["lat"])
        if lat["n"] and lat["p99_ms"] > o.max_p99_ms:
            self._soak_finding(s, "latency", f"PING p99 {lat['p99_ms']} ms over the soak > {o.max_p99_ms} ms")
        ops = {}
        for e in s["ops"]:
            ops.setdefault(e["op"], []).append(e)
        op_stats = {name: {"n": len(es), "failed": sum(1 for e in es if not e["ok"]),
                           **{k: v for k, v in latency_stats([e["ms"] for e in es]).items() if k != "n"}}
                    for name, es in ops.items()}
        first, last = s["first"], s["prev"]
        totals = None
        if first and last and last is not first:
            fl, ll = first["reply"]["link"], last["reply"]["link"]
            totals = {"dev_rx": ll["rx_frames"] - fl["rx_frames"], "host_sent": last["sent"] - first["sent"],
                      "dev_tx": ll["tx_frames"] - fl["tx_frames"], "host_recv": last["recv"] - first["recv"],
                      "noise_bytes": last["noise"] - first["noise"], "resyncs": last["resyncs"] - first["resyncs"],
                      "uptime_s": [first["reply"]["uptime_s"], last["reply"]["uptime_s"]],
                      "reset": last["reply"]["reset"],
                      "heap_min_free": [first["reply"]["heap"]["min_free"], last["reply"]["heap"]["min_free"]]}
        kinds = {}
        for f in s["findings"]:
            kinds[f["kind"]] = kinds.get(f["kind"], 0) + 1
        summary = {
            "started_utc": s["started_utc"], "elapsed_min": round(elapsed / 60, 2), "planned_min": o.soak,
            "intervals": s["k"], "samples": len(rows), "interrupted": s["interrupted"], "stopped": s["stop"],
            "findings_total": s["findings_total"], "findings_by_kind": kinds, "heap_free_trend": heap,
            "heap_largest_trend": largest, "latency": lat, "ops": op_stats, "counters_total": totals,
            "uploads": s["uploads"], "fs_read": {k: v for k, v in s["read"].items() if k != "chosen"},
            "stream_cycles": s["stream_cycles"], "stream_events": s["stream_events"],
        }
        series = [{k: v for k, v in x.items() if not k.startswith("_")} for x in s["rows"]]
        self.soak_report = {"config": s["config"], "summary": summary, "warnings": s["warnings"],
                            "findings": s["findings"], "series": series, "ops": s["ops"]}
        r.evidence = {"config": s["config"], "summary": summary, "findings": s["findings"][:50]}
        r.warnings += s["warnings"]
        head = (f"interrupted after {elapsed / 60:.1f} of {o.soak} min; " if s["interrupted"] else "") + \
               (f"stopped: {s['stop']}; " if s["stop"] else "")
        if s["findings_total"]:
            r.status = "FAIL"
            r.reason = head + f"{s['findings_total']} finding(s) {kinds}; first: {s['findings'][0]['detail']}"
        else:
            r.status = "PASS"
            slope = (f"heap.free slope {heap['slope_bph']} B/h (limit {heap['detection_limit_bph']} B/h)"
                     if heap["assessed"] else "heap trend not assessed")
            r.reason = head + (f"{elapsed / 60:.1f} min, {s['k']} intervals: {slope}; link counters exact, no "
                               f"rejects, no noise; PING p50 {lat.get('p50_ms')} ms p99 {lat.get('p99_ms')} ms; "
                               f"no reboot (uptime {totals['uptime_s'] if totals else None})")

    def check_heap(self, r):
        if not self.diag_ok:
            r.status, r.reason = "SKIP", "features.diag not advertised"
            return
        frees = []
        for _ in range(self.o.diag_repeats):
            d = self.diag()
            if d is None:
                r.status, r.reason = "FAIL", "diag did not answer during the repeat loop"
                r.evidence = {"free": frees}
                return
            frees.append(d["heap"]["free"])
        k = max(1, min(5, len(frees) // 3))
        first, last = statistics.median(frees[:k]), statistics.median(frees[-k:])
        run_start = (self.baseline or {}).get("reply", {}).get("heap", {}).get("free")
        r.evidence = {"repeat_free": frees, "first_median": first, "last_median": last,
                      "run_start_free": run_start, "run_end_free": frees[-1],
                      "min_free": d["heap"].get("min_free"), "largest": d["heap"].get("largest"),
                      "tolerance": self.o.heap_tolerance, "run_tolerance": self.o.heap_run_tolerance,
                      "samples": self.diag_samples[-(len(frees) + 8):]}
        problems = []
        if last < first - self.o.heap_tolerance:
            problems.append(f"heap.free fell {first - last:.0f} bytes over {len(frees)} diag calls")
        if run_start is not None and frees[-1] < run_start - self.o.heap_run_tolerance:
            problems.append(f"heap.free fell {run_start - frees[-1]} bytes over the run")
        self.verdict(r, problems, f"free {first:.0f} -> {last:.0f} over {len(frees)} diag calls "
                                  f"(run start {run_start})")

    def check_diag_counters(self, r):
        if not self.diag_ok:
            r.status, r.reason = "SKIP", "features.diag not advertised"
            return
        if self.baseline is None:
            r.status, r.reason = "FAIL", "no baseline diag"
            return
        d = self.diag()
        if d is None:
            r.status, r.reason = "FAIL", "final diag did not answer"
            return
        b, a = self.baseline["reply"], d
        bl, al = b["link"], a["link"]
        exp_rx = self.link.sent - self.baseline["sent"]
        exp_tx = (self.link.received - 1) - self.baseline["received"]
        base_rx_index = self.baseline["received"] + 1
        errs = [e for e in self.link.errors if e[0] >= base_rx_index]
        exp_err = {k: 0 for k in DIAG_ERR_KEYS}
        for _, _, code, _ in errs:
            exp_err[err_bucket(code)] += 1
        exp_busy = sum(1 for _, _, code, msg in errs if code == 409 and msg == "device busy")
        delta = {k: al[k] - bl[k] for k in DIAG_LINK_KEYS[:-1]}
        delta["err"] = {k: al["err"][k] - bl["err"][k] for k in DIAG_ERR_KEYS}
        expected = {"rx_frames": exp_rx, "tx_frames": exp_tx,
                    "rx_bad_json": self.link.malformed_sent - self.baseline["malformed"],
                    "busy_409": exp_busy, "err": exp_err, "sessions": 0}
        problems = []
        if delta["rx_frames"] != exp_rx:
            problems.append(f"rx_frames +{delta['rx_frames']}, runner sent {exp_rx} frames")
        if delta["tx_frames"] != exp_tx:
            problems.append(f"tx_frames +{delta['tx_frames']}, runner received {exp_tx} frames")
        for k in REJECT_KEYS:
            if delta[k]:
                problems.append(f"{k} +{delta[k]} (unexpected reject)")
        if delta["rx_bad_json"] != expected["rx_bad_json"]:
            problems.append(f"rx_bad_json +{delta['rx_bad_json']}, runner sent {expected['rx_bad_json']} bad frames")
        if delta["busy_409"] != exp_busy:
            problems.append(f"busy_409 +{delta['busy_409']}, runner saw {exp_busy}")
        if delta["err"] != exp_err:
            problems.append(f"err buckets {delta['err']} != ERRs received {exp_err}")
        if delta["sessions"]:
            problems.append(f"sessions +{delta['sessions']}: companion mode was re-entered during the run")
        if a.get("reset") != b.get("reset") or a.get("reset_code") != b.get("reset_code") \
                or not _u32(a.get("uptime_s")) or a["uptime_s"] < b["uptime_s"]:
            problems.append("reset reason changed or uptime went backwards: the device rebooted")
        if self.link.bad_rx_json:
            problems.append(f"{self.link.bad_rx_json} undecodable frames received from the device")
        r.evidence = {"baseline": b, "final": a, "delta": delta, "expected": expected,
                      "host_sent_by_kind": self.link.sent_by_kind, "host_recv_by_kind": self.link.recv_by_kind}
        self.verdict(r, problems, f"rx +{delta['rx_frames']} = sent, tx +{delta['tx_frames']} = received, "
                                  f"no rejects, reset {a.get('reset')} uptime {b['uptime_s']} -> {a['uptime_s']} s")


# --------------------------------------------------------------------------------------------
# Dry run and reports
# --------------------------------------------------------------------------------------------


def dry_run_plan(opts):
    """Frames each selected check would send (placeholders for values read from the device)."""
    tok = "0" * 32
    scratch = f"{SCRATCH_PREFIX}20260101T000000Z-000000.txt"
    plan = [("setup", HELLO, {"app": "hw_acceptance", "appVersion": str(RUNNER_VERSION)})]
    sel = set(opts.only)
    if DIAG_USERS & sel:
        plan.append(("baseline", CMD, {"cmd": "diag"}))
    if "ping" in sel:
        plan += [("ping", PING, {})] * 3 + [("ping", None, f"... {opts.pings} sequential + {opts.ping_burst} burst PINGs")]
    if "malformed" in sel:
        plan += [("malformed", CMD, {"cmd": "diag"}), ("malformed", "MALFORMED", MALFORMED_PAYLOAD.decode()),
                 ("malformed", CMD, {"cmd": "diag"})]
    if "fs_list" in sel:
        plan.append(("fs_list", CMD, {"cmd": "storage list /"}))
    if "fs_read" in sel:
        plan.append(("fs_read", CMD, {"cmd": f"fs_read {opts.read_path or '/{smallest-file-at-root}'}"}))

    def upload(check):
        return [(check, CMD, {"cmd": "storage list /"}),
                (check, None, "... chunk size from CAPS rx_max (largest frame first), "
                              f"{LEGACY_RX_MAX} bytes when CAPS has none"),
                (check, CMD, {"cmd": f"fs_upload_begin {tok} 4608 0 {scratch}"}),
                (check, CMD, {"cmd": f"fs_upload_chunk {tok} 0 QUJD"}),
                (check, CMD, {"cmd": f"fs_upload_chunk {tok} 3072 QUJD"}),
                (check, CMD, {"cmd": f"fs_upload_commit {tok}"}),
                (check, CMD, {"cmd": f"fs_read {scratch}"}),
                (check, CMD, {"cmd": f"storage remove {scratch}"}),
                (check, CMD, {"cmd": "storage list /"})]
    if "fs_upload" in sel:
        plan += upload("fs_upload")
    if "streams" in sel:
        for s in opts.streams:
            plan += [("streams", SUB, {"stream": s, "on": True}), ("streams", SUB, {"stream": s, "on": False})]
    if "busy" in sel:
        plan += [("busy", CMD, {"cmd": f"jam_detect {opts.busy_secs}"}), ("busy", PING, {}),
                 ("busy", CMD, {"cmd": "diag"}), ("busy", SUB, {"stream": "wifi.probe", "on": True}),
                 ("busy", CMD, {"cmd": "stop"})]
    if "idle_stops" in sel:
        plan += [("idle_stops", CMD, {"cmd": "nrf24_stop"}), ("idle_stops", CMD, {"cmd": "jam_detect_stop"}),
                 ("idle_stops", CMD, {"cmd": "ir_power_spam_stop"})]
    if "script_stop" in sel:
        plan += [("script_stop", CMD, {"cmd": f"script_run {SCRIPT_LONG}"}),
                 ("script_stop", None, f"... {opts.script_stop_after} s after script_run:"),
                 ("script_stop", PING, {}), ("script_stop", CMD, {"cmd": "script_stop"}),
                 ("script_stop", None, "... once the run answered `script stopped`:"),
                 ("script_stop", CMD, {"cmd": f"script_run {SCRIPT_SHORT}"})]
    if "soak" in sel:
        plan += [("soak", CMD, {"cmd": "diag"}),
                 ("soak", None, f"... every {opts.soak_interval} s for {opts.soak} min:"),
                 ("soak", PING, {}), ("soak", None, f"... {opts.soak_pings} sequential + {opts.soak_burst} burst PINGs"),
                 ("soak", CMD, {"cmd": "storage list /"}),
                 ("soak", CMD, {"cmd": f"fs_read {opts.read_path or '/{smallest-file-at-root}'}"})]
        plan += upload("soak") + [("soak", None, "... upload cycle: new token and scratch name each interval")]
        for st in opts.streams:
            plan += [("soak", SUB, {"stream": st, "on": True}), ("soak", SUB, {"stream": st, "on": False})]
        plan += [("soak", None, f"... one stream per interval, in turn: {', '.join(opts.streams)}"),
                 ("soak", PING, {}), ("soak", None, f"... keepalive PING when {opts.soak_keepalive} s passed "
                                                     "since the last frame"),
                 ("soak", CMD, {"cmd": "diag"})]
    if "heap" in sel:
        plan += [("heap", CMD, {"cmd": "diag"}), ("heap", None, f"... {opts.diag_repeats} diag calls in total")]
    if "diag_counters" in sel:
        plan.append(("diag_counters", CMD, {"cmd": "diag"}))
    lines = []
    for check, kind, d in plan:
        if kind is None:
            lines.append(f"  [{check}] {d}")
        elif kind == "MALFORMED":
            lines.append(f"  [{check}] raw frame {d!r} (bad JSON, expects ERR 400)")
        else:
            check_allowed(kind, d)  # the plan itself must pass the allowlist
            lines.append(f"  [{check}] {KIND_NAMES[kind]} {json.dumps(d)}")
    return lines


def _md_escape(text):
    return str(text).replace("|", "\\|").replace("\n", " ")


def write_reports(report, out_dir):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    stem = "hw-acceptance-" + report["started_utc"].replace(":", "").replace("-", "").replace(".", "")
    jp, mp, cp = out / f"{stem}.json", out / f"{stem}.md", out / f"{stem}-soak.csv"
    jp.write_text(json.dumps(report, indent=2, sort_keys=False) + "\n")
    if report.get("soak"):
        with cp.open("w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=SOAK_CSV_COLUMNS, extrasaction="ignore")
            w.writeheader()
            w.writerows(report["soak"]["series"])
    dev = report.get("device") or {}
    b = dev.get("build") or {}
    s = report["summary"]
    lines = [
        "# Brucia hardware acceptance report", "",
        f"- Result: **{s['result']}** ({s['PASS']} pass, {s['FAIL']} fail, {s['SKIP']} skip)"
        + (" - INTERRUPTED" if report.get("interrupted") else ""),
        f"- Started {report['started_utc']}, finished {report['finished_utc']} ({report['duration_s']} s)",
        f"- Port `{report['port']}`, runner v{report['runner_version']}, host {report['host']['platform']}, "
        f"Python {report['host']['python']}, pyserial {report['host']['pyserial']}",
        f"- Device: {dev.get('board')} / {dev.get('chip')}, fw `{dev.get('fw')}`, proto {dev.get('proto')}",
        f"- Build: id `{b.get('id')}`, commit `{b.get('commit')}`, dirty `{b.get('dirty', 'not reported')}`, "
        f"time `{b.get('time')}` ({b.get('time_src')}), env `{b.get('env')}`" if b else "- Build: not reported",
        f"- Frames: sent {report['frames']['sent']}, received {report['frames']['received']}",
        f"- Link: rx_max {report['link']['rx_max']} ({report['link']['rx_max_source']}); "
        f"{report['link']['noise_bytes']} non-frame byte(s) skipped in {report['link']['resyncs']} resync(s)",
    ]
    if report.get("fatal"):
        lines.append(f"- Fatal: {report['fatal']}")
    for ev in report["link"]["noise_samples"]:
        lines.append(f"- warning: device noise at {ev['utc']} after frame {ev['after_rx_frames']}: "
                     f"{ev['bytes']} byte(s), sample `{_md_escape(repr(ev['sample'][:80]))}`")
    lines += ["", "Passive checks only; nothing here proves radio TX/RX quality. "
              "Simulated runs (mock device) are not hardware evidence.", "",
              "| Check | Status | Summary |", "|---|---|---|"]
    for c in report["checks"]:
        lines.append(f"| `{c['id']}` {_md_escape(c['title'])} | {c['status']} | {_md_escape(c['reason'])} |")
    lines += ["", "## Details", ""]
    for c in report["checks"]:
        lines.append(f"### `{c['id']}` - {c['status']}")
        lines.append("")
        lines.append(f"{c['reason']}  ")
        lines.append(f"Started {c['started_utc']}, {c['duration_ms']} ms.")
        for w in c["warnings"]:
            lines.append(f"- warning: {w}")
        ev = json.dumps(c["evidence"], indent=1)
        if len(ev) > 4000:
            ev = ev[:4000] + "\n... (truncated; full evidence in the JSON report)"
        lines += ["", "```json", ev, "```", ""]
    if report.get("soak"):
        lines += soak_markdown(report["soak"], cp.name)
    if report.get("cleanup"):
        lines += ["## Cleanup", ""] + [f"- {a}: {ok}" for a, ok in report["cleanup"]] + [""]
    lines += ["## Coverage", "", report["coverage"], ""]
    lines += ["## Commands sent", "", "```"] + [f"{c['kind']} id={c['id']} {c['summary']}"
                                               for c in report["commands_sent"]][:400] + ["```", ""]
    mp.write_text("\n".join(lines))
    return (jp, mp, cp) if report.get("soak") else (jp, mp)


def soak_markdown(soak, csv_name):
    """The Markdown soak section: summary, findings, per-interval table, operation timing."""
    sm, cfg = soak["summary"], soak["config"]
    heap, lat, tot = sm["heap_free_trend"], sm["latency"], sm["counters_total"] or {}
    lines = ["## Soak", "",
             f"- {sm['elapsed_min']} of {sm['planned_min']} min, {sm['intervals']} interval(s) of "
             f"{cfg['interval_s']} s, keepalive {cfg['keepalive_s']} s, streams "
             f"{', '.join(cfg['streams']) or 'none'} (warm-up {cfg['warmup_intervals']} interval(s))"
             + (" - INTERRUPTED" if sm["interrupted"] else "") + (f" - stopped: {sm['stopped']}" if sm["stopped"] else ""),
             f"- Findings: {sm['findings_total']}" + (f" {sm['findings_by_kind']}" if sm["findings_by_kind"] else ""),
             (f"- Heap free: {heap['first']} -> {heap['last']} B (min {heap['min']}), slope {heap['slope_bph']} B/h "
              f"(least squares {heap['ls_slope_bph']}), detection limit {heap['detection_limit_bph']} B/h over "
              f"{heap['window_h']} h, {heap['falling_steps']} falling / {heap['rising_steps']} rising steps"
              if heap["assessed"] else f"- Heap free trend not assessed: {heap.get('reason')}"),
             f"- Heap min_free {tot.get('heap_min_free')}; largest block slope "
             f"{sm['heap_largest_trend'].get('slope_bph', 'n/a')} B/h",
             f"- Link: device rx +{tot.get('dev_rx')} / runner sent {tot.get('host_sent')}, device tx "
             f"+{tot.get('dev_tx')} / runner received {tot.get('host_recv')}, noise {tot.get('noise_bytes')} B, "
             f"resyncs {tot.get('resyncs')}",
             f"- Reset {tot.get('reset')}, uptime {tot.get('uptime_s')} s",
             f"- PING latency: n {lat['n']}, p50 {lat.get('p50_ms')} ms, p95 {lat.get('p95_ms')} ms, p99 "
             f"{lat.get('p99_ms')} ms, max {lat.get('max_ms')} ms",
             f"- Uploads {sm['uploads']}; fs_read {sm['fs_read'].get('path')}; stream cycles {sm['stream_cycles']}, "
             f"events {sm['stream_events']}",
             f"- Time series: `{csv_name}` (one row per diag sample)", ""]
    for w in soak["warnings"]:
        lines.append(f"- warning: {_md_escape(w)}")
    if soak["findings"]:
        lines += ["", "### Soak findings", ""] + [
            f"- interval {f['interval']} (+{f['elapsed_s']} s, {f['utc']}) {f['kind']}: {_md_escape(f['detail'])}"
            for f in soak["findings"][:100]]
    lines += ["", "### Soak operations (ms)", "", "| Operation | n | failed | p50 | p95 | p99 | max |",
              "|---|---|---|---|---|---|---|"]
    for name, st in sm["ops"].items():
        lines.append(f"| {name} | {st['n']} | {st['failed']} | {st.get('p50_ms')} | {st.get('p95_ms')} | "
                     f"{st.get('p99_ms')} | {st.get('max_ms')} |")
    cols = ("interval", "elapsed_s", "uptime_s", "heap_free", "heap_min_free", "heap_largest", "dev_rx_delta",
            "host_sent_delta", "dev_tx_delta", "host_recv_delta", "noise_bytes_delta", "pings_lost", "p50_ms",
            "p99_ms", "stream", "stream_events", "findings")
    rows = soak["series"]
    step = max(1, -(-len(rows) // 120))  # at most ~120 rows here; every row is in the CSV
    lines += ["", "### Soak time series" + (f" (every {step}th row; all in the CSV)" if step > 1 else ""), "",
              "| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
    for row in rows[::step]:
        lines.append("| " + " | ".join("" if row.get(c) is None else _md_escape(row[c]) for c in cols) + " |")
    return lines + [""]


def build_report(runner, opts, started, fatal=None, cleanup=None):
    checks = [runner.results[c].as_dict() for c in CHECK_IDS if c in runner.results]
    counts = {k: sum(1 for c in checks if c["status"] == k) for k in ("PASS", "FAIL", "SKIP")}
    result = "FAIL" if counts["FAIL"] or fatal else ("INTERRUPTED" if runner.interrupted else "PASS")
    try:
        import serial
        pyserial = getattr(serial, "__version__", "?")
    except ImportError:
        pyserial = "missing"
    caps = runner.caps or {}
    return {
        "tool": "tools/hw_acceptance.py", "runner_version": RUNNER_VERSION,
        "started_utc": started, "finished_utc": utc_now(),
        "duration_s": round(time.time() - opts._t0, 2),
        "port": opts.port, "interrupted": runner.interrupted, "fatal": fatal,
        "host": {"python": platform.python_version(), "platform": platform.platform(), "pyserial": pyserial},
        "options": {k: v for k, v in vars(opts).items() if not k.startswith("_")},
        "device": {"board": caps.get("board"), "chip": caps.get("chip"), "fw": caps.get("fw"),
                   "proto": caps.get("proto"), "build": caps.get("build"), "caps": caps},
        "summary": {**counts, "result": result},
        "checks": checks,
        "frames": {"sent": runner.link.sent, "received": runner.link.received,
                   "sent_by_kind": runner.link.sent_by_kind, "recv_by_kind": runner.link.recv_by_kind},
        "link": {"rx_max": runner.rx_max, "rx_max_source": runner.rx_max_source,
                 "noise_bytes": runner.link.noise_bytes, "resyncs": runner.link.resyncs,
                 "noise_samples": runner.link.noise_samples},
        "commands_sent": runner.link.commands,
        "soak": runner.soak_report,
        "cleanup": cleanup or [],
        "coverage": COVERAGE_NOTE,
    }


def parse_args(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--port", required=True, help="serial device of the board in companion mode")
    ap.add_argument("--out", default="hw-acceptance-reports", help="report directory")
    ap.add_argument("--only", default=None,
                    help=f"comma list of checks (default: all but busy and soak; with --soak: "
                         f"{','.join(SOAK_DEFAULT_CHECKS)}). Known: {','.join(CHECK_IDS)}")
    ap.add_argument("--dry-run", action="store_true", help="print the frames that would be sent; open nothing")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--timeout", type=float, default=3.0, help="reply timeout for ordinary commands (s)")
    ap.add_argument("--fs-timeout", type=float, default=30.0, help="fs_read / upload begin/commit timeout (s)")
    ap.add_argument("--sub-timeout", type=float, default=8.0, help="SUB ACK timeout (s), as the app")
    ap.add_argument("--pings", type=int, default=200)
    ap.add_argument("--ping-burst", type=int, default=20)
    ap.add_argument("--ping-timeout", type=float, default=1.0)
    ap.add_argument("--max-p99-ms", type=float, default=250.0)
    ap.add_argument("--streams", default="wifi.probe",
                    help=f"receive-only streams to toggle: {','.join(RECEIVE_ONLY_STREAMS)}")
    ap.add_argument("--stream-secs", type=float, default=5.0)
    ap.add_argument("--quiet-secs", type=float, default=2.0, help="window after OFF that must be silent")
    ap.add_argument("--read-path", help="file for fs_read (default: smallest file at / up to --read-max)")
    ap.add_argument("--read-max", type=int, default=65536)
    ap.add_argument("--busy-secs", type=int, default=8, help="jam_detect duration for --only busy (1..30)")
    ap.add_argument("--script-stop-after", type=float, default=2.0,
                    help="--only script_stop: seconds the looping script runs before script_stop (0.2..10)")
    ap.add_argument("--script-stop-bound", type=float, default=2.0,
                    help="--only script_stop: seconds within which the run must answer `script stopped` (0.1..15)")
    ap.add_argument("--diag-repeats", type=int, default=20)
    ap.add_argument("--heap-tolerance", type=int, default=4096)
    ap.add_argument("--heap-run-tolerance", type=int, default=32768)
    soak = ap.add_argument_group("soak (--soak MINUTES; passive, same allowlist)")
    soak.add_argument("--soak", type=float, metavar="MINUTES",
                      help="repeat passive intervals for this many minutes (adds the soak check)")
    soak.add_argument("--soak-interval", type=float, default=30.0, help="seconds per interval (diag sample each)")
    soak.add_argument("--soak-keepalive", type=float, default=5.0,
                      help="PING when this many seconds passed since the last frame (device leaves "
                           "companion mode after 60 s without one); 0 < s <= 30")
    soak.add_argument("--soak-pings", type=int, default=20, help="sequential PINGs per interval")
    soak.add_argument("--soak-burst", type=int, default=10, help="pipelined burst PINGs per interval")
    soak.add_argument("--soak-max-leak", type=float, default=SOAK_MAX_LEAK_BPH,
                      help="heap.free loss in bytes per hour that fails the soak (also bounded by "
                           "--heap-tolerance over the run, see heap_trend)")
    o = ap.parse_args(argv)
    soak_on = o.soak is not None
    if o.only is None:
        o.only = ",".join(SOAK_DEFAULT_CHECKS if soak_on else DEFAULT_CHECKS)
    o.only = [c.strip() for c in o.only.split(",") if c.strip()]
    if soak_on and "soak" not in o.only:
        o.only.append("soak")
    if "soak" in o.only and not soak_on:
        ap.error("the soak check needs --soak MINUTES")
    if soak_on:
        if not o.soak > 0:
            ap.error("--soak MINUTES must be > 0")
        if not (0 < o.soak_keepalive <= 30):
            ap.error("--soak-keepalive must be in (0, 30] s: the device leaves companion mode after 60 s")
        if not o.soak_interval > 0 or o.soak_pings < 1 or o.soak_burst < 0:
            ap.error("--soak-interval > 0, --soak-pings >= 1, --soak-burst >= 0")
        if o.stream_secs > 30 or o.quiet_secs > 30:
            ap.error("--stream-secs and --quiet-secs must be <= 30 s with --soak (no host frame meanwhile)")
        if o.soak_max_leak <= 0:
            ap.error("--soak-max-leak must be > 0")
    unknown = [c for c in o.only if c not in CHECK_IDS]
    if unknown:
        ap.error(f"unknown checks: {unknown}")
    o.streams = [s.strip() for s in o.streams.split(",") if s.strip()]
    bad = [s for s in o.streams if s not in RECEIVE_ONLY_STREAMS]
    if bad:
        ap.error(f"not a receive-only stream: {bad} (allowed: {', '.join(RECEIVE_ONLY_STREAMS)})")
    if not 1 <= o.busy_secs <= 30:
        ap.error("--busy-secs must be 1..30")
    if not (0.2 <= o.script_stop_after <= 10 and 0.1 <= o.script_stop_bound <= 15):
        ap.error("--script-stop-after must be 0.2..10 s and --script-stop-bound 0.1..15 s")
    if o.pings < 1 or o.diag_repeats < 3:
        ap.error("--pings >= 1 and --diag-repeats >= 3")
    return o


def run(argv, port_factory=None, log=print):
    """Entry point (also used by the tests). Returns the exit code; the report paths are in
    run.last_paths and the report dict in run.last_report."""
    opts = parse_args(argv)
    opts._t0 = time.time()
    run.last_report, run.last_paths = None, None
    if opts.dry_run:
        log(f"dry run: frames that would be sent to {opts.port} (nothing is opened)")
        for line in dry_run_plan(opts):
            log(line)
        return 0
    if port_factory is None:
        import serial  # pyserial
        port_factory = lambda: serial.Serial(opts.port, opts.baud, timeout=0.02, write_timeout=2)  # noqa: E731
    started = utc_now()
    port = port_factory()
    try:  # do not hold the S3 in reset via the control lines after open
        port.rts = False
        port.dtr = False
    except Exception:  # noqa: BLE001 - not all port objects expose these
        pass
    link = Link(port)
    runner = Runner(link, opts, log)
    fatal, cleanup, code = None, None, 0
    try:
        runner.run()
    except KeyboardInterrupt:
        runner.interrupted = True
        log("interrupted: sending OFF for active streams and cleaning up")
    except LinkError as e:
        fatal = str(e)
    finally:
        cleanup = runner.cleanup()
        try:
            port.close()
        except Exception:  # noqa: BLE001 - closing is best effort
            pass
    report = build_report(runner, opts, started, fatal, cleanup)
    paths = write_reports(report, opts.out)
    run.last_report, run.last_paths = report, paths
    s = report["summary"]
    log(f"result {s['result']}: {s['PASS']} pass, {s['FAIL']} fail, {s['SKIP']} skip")
    log("reports: " + " ".join(str(p) for p in paths))
    if runner.interrupted:
        code = 130
    elif fatal:
        code = 2
    elif s["FAIL"]:
        code = 1
    return code


if __name__ == "__main__":
    sys.exit(run(sys.argv[1:]))
