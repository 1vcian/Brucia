#!/usr/bin/env python3
"""The companion frame writer emits each frame atomically AND completely (backlog F23).

A companion frame is a 4-byte little-endian length prefix followed by exactly that many payload
bytes. `writeFrame` in src/companion/protocol.cpp must put those bytes on the single USB serial as
one uninterrupted, complete run. Two independent defects could desync the length-prefixed stream
(both seen in the 2026-11-06 hardware soak - stray bytes, an undecodable frame, a tx/rx counter
mismatch):

  1. Interleaving: without a lock, a stream EVENT emitted from another task could interleave its
     Serial.write calls with a response. Fixed by a recursive mutex around the whole emission.
  2. Partial writes: `Serial.write(buf, len)` is NOT guaranteed to accept all `len` bytes. The
     ESP32-S3 USB-Serial-JTAG path (HWCDC) and the TinyUSB path (USBCDC) return a SHORT count under
     host backpressure (Arduino Stream::write contract). Ignoring that return truncates the length
     prefix or the payload - a desync even with a single writer. Fixed by `writeAll`, which loops on
     the return value writing the remainder (bounded by a deadline so a vanished host cannot hang).

This test compiles the ACTUAL source of `writeFrame`/`writeAll` (so a regression in the real code is
caught) against host shims, and proves each fix with a mutation:

  - concurrency: two threads, 0 interleaved frames with the lock, interleave once the lock is
    stripped;
  - partial write: a Serial sink that accepts only a few bytes per write still yields the complete,
    correctly-framed stream with `writeAll`, but a truncated stream once `writeAll` is reduced to the
    pre-fix single write.

Plus static checks that the lock wraps the writes and flush, and that no frame emitter in
src/companion writes Serial outside writeFrame/writeAll.

    python3 tools/test_firmware_frame_lock.py
"""
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROTO = ROOT / "src/companion/protocol.cpp"
SOURCE = PROTO.read_text()


def extract_fn(src, signature):
    """Return the verbatim text of a function given its exact signature line (brace-matched)."""
    start = src.index(signature)
    i = src.index("{", start)
    depth = 0
    for j in range(i, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[start : j + 1]
    raise AssertionError(f"function not terminated: {signature!r}")


def line_of(src, needle):
    m = re.search(needle, src, re.M)
    if not m:
        raise AssertionError(f"not found: {needle!r}")
    return m.group(0)


LOCK_DECL = line_of(SOURCE, r"^static SemaphoreHandle_t g_frameLock\b.*$")
CONST_DECL = line_of(SOURCE, r"^static const uint32_t COMPANION_TX_FRAME_MS\b.*$")
WRITE_ALL = extract_fn(SOURCE, "static bool writeAll(const uint8_t *buf, size_t len, uint32_t deadline) {")
WRITE_FRAME = extract_fn(SOURCE, "static void writeFrame(const String &payload) {")

# Everything writeFrame needs, in source order, compiled verbatim.
REAL_BLOCK = "\n".join([LOCK_DECL, CONST_DECL, WRITE_ALL, WRITE_FRAME])

# ---- shared C++ shims -------------------------------------------------------
COMMON = r"""
#include <cstdint>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <thread>
#include <mutex>
#include <chrono>

using std::size_t;

class String : public std::string {
public:
    using std::string::string;
    size_t length() const { return size(); }
};

typedef int BaseType_t;
static const BaseType_t pdTRUE = 1;
static const uint32_t portMAX_DELAY = 0xffffffffu;
typedef std::recursive_mutex *SemaphoreHandle_t;
static SemaphoreHandle_t xSemaphoreCreateRecursiveMutex() { return new std::recursive_mutex(); }
static BaseType_t xSemaphoreTakeRecursive(SemaphoreHandle_t h, uint32_t) { h->lock(); return pdTRUE; }
static BaseType_t xSemaphoreGiveRecursive(SemaphoreHandle_t h) { h->unlock(); return pdTRUE; }
static void diagNoteTxFrame() {}
"""

# ---- concurrency harness: full writes, two threads --------------------------
# Serial.write accepts all bytes (returns the full count) but sleeps after a 4-byte length prefix so,
# WITHOUT the frame lock, the other thread reliably interposes between the length and the payload.
CONC_SHIM = r"""
static uint32_t millis() { return 0; }          // writeAll never loops here (writes are full)
static void delay(uint32_t) {}
struct SerialShim {
    std::mutex txm;                              // per-write byte atomicity only, never frame atomicity
    std::vector<uint8_t> buf;
    size_t write(const uint8_t *p, size_t n) {
        {
            std::lock_guard<std::mutex> g(txm);
            for (size_t i = 0; i < n; i++) buf.push_back(p[i]);
        }
        if (n == 4) std::this_thread::sleep_for(std::chrono::microseconds(80));
        else std::this_thread::yield();
        return n;
    }
    void flush() { std::this_thread::yield(); }
} Serial;
"""

CONC_MAIN = r"""
static const int K = 64;
static const int ITER = 1500;
static void writer(char c) { String payload(K, c); for (int i = 0; i < ITER; i++) writeFrame(payload); }

// A clean stream is a run of [len=K][K identical bytes]. A payload region that is not all-'A'/all-'B'
// or a length != K means two frames interleaved. Returns the count of interleaved frames.
static int countInterleaved() {
    const std::vector<uint8_t> &b = Serial.buf;
    size_t pos = 0; int bad = 0;
    while (pos + 4 <= b.size()) {
        uint32_t len = (uint32_t)b[pos] | ((uint32_t)b[pos+1]<<8) | ((uint32_t)b[pos+2]<<16) | ((uint32_t)b[pos+3]<<24);
        pos += 4;
        if (len != (uint32_t)K) { bad++; continue; }
        if (pos + len > b.size()) { bad++; break; }
        uint8_t first = b[pos]; bool uniform = (first=='A'||first=='B');
        for (uint32_t i = 0; i < len && uniform; i++) if (b[pos+i] != first) uniform = false;
        if (!uniform) bad++;
        pos += len;
    }
    return bad;
}
int main() {
    std::thread a(writer, 'A'), b(writer, 'B');
    a.join(); b.join();
    int bad = countInterleaved();
    printf("%d\n", bad);
    return bad == 0 ? 0 : 1;
}
"""

# ---- partial-write harness: a sink that accepts only CAP bytes per write ----
PARTIAL_SHIM = r"""
static const size_t CAP = 3;                     // the sink accepts at most 3 bytes per write()
static uint32_t g_ms = 0;
static uint32_t millis() { return g_ms; }
static void delay(uint32_t ms) { g_ms += ms ? ms : 1; }
struct SerialShim {
    std::vector<uint8_t> buf;
    size_t write(const uint8_t *p, size_t n) {   // short write: honors at most CAP bytes
        size_t take = n < CAP ? n : CAP;
        for (size_t i = 0; i < take; i++) buf.push_back(p[i]);
        return take;
    }
    void flush() {}
} Serial;
"""

PARTIAL_MAIN = r"""
static const int NFRAMES = 40;
static const int K = 10;                          // payload length per frame
int main() {
    std::vector<uint8_t> expected;
    for (int i = 0; i < NFRAMES; i++) {
        char c = (char)('A' + (i % 26));
        String payload(K, c);
        writeFrame(payload);
        uint32_t len = K;
        for (int b = 0; b < 4; b++) expected.push_back((uint8_t)(len >> (8*b)));
        for (int b = 0; b < K; b++) expected.push_back((uint8_t)c);
    }
    bool match = Serial.buf == expected;
    printf("produced=%zu expected=%zu match=%d\n", Serial.buf.size(), expected.size(), match ? 1 : 0);
    return match ? 0 : 1;
}
"""


def build_and_run(block, shim, main, tmpdir):
    code = COMMON + "\n" + shim + "\n" + block + "\n" + main
    src = Path(tmpdir) / "harness.cpp"
    src.write_text(code)
    exe = Path(tmpdir) / "harness"
    cxx = os.environ.get("CXX", "c++")
    subprocess.run([cxx, "-std=c++17", "-O1", "-pthread", str(src), "-o", str(exe)],
                   check=True, capture_output=True)
    proc = subprocess.run([str(exe)], capture_output=True, text=True, timeout=120)
    return proc.returncode, proc.stdout.strip()


def strip_lock(block):
    """Mutation: force `locked` false so the mutex take/give never run."""
    out = re.sub(r"g_frameLock != nullptr && xSemaphoreTakeRecursive\([^;]*\) == pdTRUE", "false", block)
    assert out != block, "lock mutation did not change the source"
    return out


def one_shot_writeall(block):
    """Mutation: reduce writeAll to the pre-fix single write that ignores the short-write return."""
    needle = "off += Serial.write(buf + off, len - off);"
    assert block.count(needle) == 1, "writeAll inner write not found uniquely"
    return block.replace(needle, needle + "\n        return true; // MUTATION: pre-fix one-shot write")


class Concurrency(unittest.TestCase):
    def test_locked_never_interleaves(self):
        with tempfile.TemporaryDirectory() as d:
            rc, out = build_and_run(REAL_BLOCK, CONC_SHIM, CONC_MAIN, d)
        self.assertEqual((rc, out), (0, "0"), f"locked writeFrame interleaved: {out}")

    def test_unlocked_interleaves(self):
        with tempfile.TemporaryDirectory() as d:
            rc, out = build_and_run(strip_lock(REAL_BLOCK), CONC_SHIM, CONC_MAIN, d)
        self.assertEqual(rc, 1, "lock-stripped writeFrame did not interleave; harness not exercising concurrency")
        self.assertGreater(int(out), 0)


class PartialWrite(unittest.TestCase):
    def test_writeall_completes_under_short_writes(self):
        with tempfile.TemporaryDirectory() as d:
            rc, out = build_and_run(REAL_BLOCK, PARTIAL_SHIM, PARTIAL_MAIN, d)
        self.assertEqual(rc, 0, f"writeAll did not produce the complete framed stream: {out}")
        self.assertIn("match=1", out)

    def test_pre_fix_single_write_truncates(self):
        with tempfile.TemporaryDirectory() as d:
            rc, out = build_and_run(one_shot_writeall(REAL_BLOCK), PARTIAL_SHIM, PARTIAL_MAIN, d)
        self.assertEqual(rc, 1, "pre-fix single write did not truncate under a short-write sink")
        self.assertIn("match=0", out)


class Static(unittest.TestCase):
    def test_lock_is_recursive_mutex(self):
        self.assertIn("xSemaphoreCreateRecursiveMutex()", LOCK_DECL)
        self.assertRegex(LOCK_DECL, r"SemaphoreHandle_t\s+g_frameLock")

    def test_lock_wraps_writes_and_flush(self):
        wf = WRITE_FRAME
        take = wf.index("xSemaphoreTakeRecursive")
        give = wf.index("xSemaphoreGiveRecursive")
        writes = wf.index("writeAll(lenbuf, 4")
        flush = wf.index("Serial.flush()")
        self.assertLess(take, writes, "lock taken before the writes")
        self.assertLess(writes, flush)
        self.assertLess(flush, give, "lock released only after the flush")

    def test_writeall_loops_on_return_and_is_bounded(self):
        self.assertIn("off += Serial.write(", WRITE_ALL)       # honors the short-write return
        self.assertIn("millis() - deadline", WRITE_ALL)        # bounded wait
        self.assertRegex(WRITE_ALL, r"while \(off < len\)")

    def test_torn_frame_not_counted(self):
        # diagNoteTxFrame (tx_frames) is called only when the whole frame was written.
        self.assertRegex(WRITE_FRAME, r"if \(whole\) diagNoteTxFrame")

    def test_fallback_when_lock_missing(self):
        self.assertIn("g_frameLock != nullptr", WRITE_FRAME)


class NoSerialWriteOutsideEmitter(unittest.TestCase):
    """Every companion frame emitter goes through writeFrame/writeAll; nothing else writes Serial."""

    WRITE = re.compile(r"\bSerial\s*\.\s*(write|print|println|printf|flush)\s*\(")

    def test_only_emitter_writes_serial(self):
        comp = ROOT / "src/companion"
        offenders = []
        for path in sorted(comp.rglob("*")):
            if path.suffix not in (".c", ".cpp", ".h", ".hpp"):
                continue
            text = path.read_text()
            text = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), text, flags=re.S)
            text = re.sub(r"//[^\n]*", lambda m: " " * len(m.group(0)), text)
            spans = []
            if path.name == "protocol.cpp":
                for sig in ("static bool writeAll(const uint8_t *buf, size_t len, uint32_t deadline) {",
                            "static void writeFrame(const String &payload) {"):
                    s = text.index(sig)
                    spans.append((s, s + len(extract_fn(text, sig))))
            for m in self.WRITE.finditer(text):
                if any(a <= m.start() < b for a, b in spans):
                    continue
                line = text.count("\n", 0, m.start()) + 1
                offenders.append(f"{path.relative_to(ROOT)}:{line}: {m.group(0)}")
        self.assertEqual(offenders, [], "Serial write(s) outside writeFrame/writeAll:\n" + "\n".join(offenders))


if __name__ == "__main__":
    unittest.main()
