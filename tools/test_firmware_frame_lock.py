#!/usr/bin/env python3
"""The companion frame writer emits each frame atomically AND completely, and recovers when it can't
(backlog F23 / point 2).

A companion frame is a 4-byte little-endian length prefix followed by exactly that many payload
bytes. `writeFrame` in src/companion/protocol.cpp must put those bytes on the single USB serial as
one uninterrupted, complete run. Three defects could desync the length-prefixed stream (the first two
were seen in the 2026-11-06 hardware soak - stray bytes, an undecodable frame, a tx/rx counter
mismatch):

  1. Interleaving: without a lock, a stream EVENT emitted from another task could interleave its
     Serial.write calls with a response. Fixed by a recursive mutex around the whole emission.
  2. Partial writes: `Serial.write(buf, len)` is NOT guaranteed to accept all `len` bytes. The
     ESP32-S3 USB-Serial-JTAG path (HWCDC, the port Serial uses on the T-Embed, which builds with
     ARDUINO_USB_MODE=1) and the TinyUSB path (USBCDC) return a SHORT count under host backpressure
     (Arduino Stream::write contract). Ignoring that return truncates the length prefix or the
     payload - a desync even with a single writer. Fixed by `writeAll`, which loops on the return
     value writing the remainder.
  3. No recovery after a tear: `writeAll` can still fail to place a whole frame (host gone). The old
     code then let writeFrame emit the NEXT frame onto the already-torn stream, so it stayed desynced.
     Fixed by: writeAll distinguishes a host making progress (idle timer reset on every accepted byte,
     a delayed partial write completes) from one accepting nothing (bounded by the idle timeout) or a
     trickle (bounded by the absolute per-frame ceiling); a torn frame sets g_txLinkBroken, which
     suppresses all further emission and is observed by the companion loop, which drops the link so
     the app reconnects onto a clean session; and a torn frame is counted as tx_torn, not tx_frames.

This test compiles the ACTUAL source of `writeFrame`/`writeAll` (so a regression in the real code is
caught) against host shims, and proves each fix with a mutation where applicable:

  - concurrency: two threads, 0 interleaved frames with the lock, interleave once the lock is stripped;
  - partial write: a sink that accepts only a few bytes per write still yields the complete,
    correctly-framed stream with `writeAll`, but a truncated stream once `writeAll` is reduced to the
    pre-fix write that assumes the full count;
  - delayed resume: a sink that stalls (accepts 0) for a while UNDER the idle bound, then resumes,
    still completes the frame - a slow host is not torn;
  - host gone / blocked: a sink that accepts nothing tears the frame in bounded time, sets the
    link-broken flag, is counted as tx_torn not tx_frames, and the NEXT writeFrame emits nothing;
    companionClearTxLink() restores emission (a fresh session);
  - bounded wait against the REAL HWCDC loop: a faithful HWCDC::write model (chunked by the 256-byte
    ring, with the consec-timeout counter that RESETS on every accepted chunk, HWCDC.cpp:606) shows a
    single uncapped write can run for seconds; the chunk cap in writeAll keeps each Serial.write to one
    chunk (<= one 20-timeout window) so the frame tears at the ceiling with a bounded total, and the
    mutation that removes the cap lets one write exceed the whole ceiling (the 6 s "counted as
    succeeded" the review measured).

The ACTUAL app reconnection after a torn frame (a command still pending, the link dropped, a fresh
session serving a new command) is proven in CommandSessionTest - a flag reset here does not show it.

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

WRITE_ALL_SIG = "static bool writeAll(const uint8_t *buf, size_t len, uint32_t frameDeadline) {"
WRITE_FRAME_SIG = "static void writeFrame(const String &payload) {"


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
IDLE_CONST = line_of(SOURCE, r"^static const uint32_t COMPANION_TX_IDLE_MS\b.*$")
FRAME_CONST = line_of(SOURCE, r"^static const uint32_t COMPANION_TX_FRAME_MS\b.*$")
CHUNK_CONST = line_of(SOURCE, r"^static const size_t COMPANION_TX_CHUNK\b.*$")
BROKEN_DECL = line_of(SOURCE, r"^static volatile bool g_txLinkBroken\b.*$")
BROKEN_GET = line_of(SOURCE, r"^bool companionTxLinkBroken\(\).*$")
BROKEN_CLEAR = line_of(SOURCE, r"^void companionClearTxLink\(\).*$")
WRITE_ALL = extract_fn(SOURCE, WRITE_ALL_SIG)
WRITE_FRAME = extract_fn(SOURCE, WRITE_FRAME_SIG)

# Everything writeFrame needs, in source order, compiled verbatim.
REAL_BLOCK = "\n".join(
    [LOCK_DECL, IDLE_CONST, FRAME_CONST, CHUNK_CONST, BROKEN_DECL, BROKEN_GET, BROKEN_CLEAR, WRITE_ALL, WRITE_FRAME]
)

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
static int g_txFrames = 0, g_txTorn = 0;
static void diagNoteTxFrame() { g_txFrames++; }
static void diagNoteTxTorn() { g_txTorn++; }
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
# Virtual time: delay(ms) advances g_ms; millis() reads it. Every write accepts CAP>0 bytes, so the
# idle timer always resets and the frame completes.
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
    printf("produced=%zu expected=%zu match=%d torn=%d broken=%d\n",
           Serial.buf.size(), expected.size(), match ? 1 : 0, g_txTorn, companionTxLinkBroken() ? 1 : 0);
    return (match && g_txTorn == 0 && !companionTxLinkBroken()) ? 0 : 1;
}
"""

# ---- delayed-resume harness: the sink stalls (accepts 0) UNDER the idle bound, then resumes --------
# A slow / briefly-disconnected host that comes back must NOT be torn: progress resumes before the
# idle timeout, so the frame completes whole.
RESUME_SHIM = r"""
static uint32_t g_ms = 0;
static uint32_t millis() { return g_ms; }
static void delay(uint32_t ms) { g_ms += ms ? ms : 1; }
static const size_t CAP = 6;                      // bytes accepted per non-stalled write
static const uint32_t GAP_MS = 700;              // stall between chunks: < COMPANION_TX_IDLE_MS each,
                                                 // but several in a row sum PAST it. Only resetting the
                                                 // idle timer on progress keeps the frame alive.
struct SerialShim {
    std::vector<uint8_t> buf;
    uint32_t lastAccept = 0;
    bool started = false;
    size_t write(const uint8_t *p, size_t n) {
        if (started && g_ms - lastAccept < GAP_MS) return 0;  // within a stall: accept nothing yet
        size_t take = n < CAP ? n : CAP;
        for (size_t i = 0; i < take; i++) buf.push_back(p[i]);
        lastAccept = g_ms; started = true;
        return take;
    }
    void flush() {}
} Serial;
"""

RESUME_MAIN = r"""
static const int K = 20;
int main() {
    String payload(K, 'Z');
    writeFrame(payload);
    bool ok = Serial.buf.size() == (size_t)(4 + K) && g_txTorn == 0 && !companionTxLinkBroken() && g_txFrames == 1;
    printf("produced=%zu torn=%d broken=%d frames=%d ms=%u\n",
           Serial.buf.size(), g_txTorn, companionTxLinkBroken() ? 1 : 0, g_txFrames, g_ms);
    return ok ? 0 : 1;
}
"""

# ---- faithful HWCDC::write model: chunked, with a consec-timeout counter that RESETS on progress ----
# This reproduces the real driver (HWCDC.cpp): write(buf,n) splits into RING-byte chunks; each chunk
# waits up to TX ms per attempt, giving up after MAX_CONSEC *consecutive* timeouts - but that counter
# resets whenever a chunk is accepted. A host that drains one chunk every `drain_every` attempts keeps a
# SINGLE large write() alive far past MAX_CONSEC*TX. The only real bound is to cap each write to <= one
# chunk, which writeAll does (COMPANION_TX_CHUNK). g_max_write_ms records the longest single write() so a
# test can show the cap bounds it and the uncapped mutation does not.
HWCDC_SHIM = r"""
static const size_t RING = 256;                   // HWCDC TX ring (setTxBufferSize(256))
static const uint32_t TX = 10;                    // per-attempt tx timeout == COMPANION_TX_WRITE_MS
static const int MAX_CONSEC = 20;                 // HWCDC.cpp max_consec_timeouts
static uint32_t g_ms = 0;
static uint32_t g_max_write_ms = 0;               // longest single Serial.write() seen
static uint32_t millis() { return g_ms; }
static void delay(uint32_t ms) { g_ms += ms ? ms : 1; }
struct SerialShim {
    std::vector<uint8_t> buf;
    int drain_every = -1;                         // accept one chunk after this many timeouts; -1 = never
    int attempts = 0;                             // timeouts since the last accepted chunk (host drain model)
    size_t write(const uint8_t *p, size_t n) {
        uint32_t start = g_ms;
        size_t so_far = 0; int consec = 0;
        while (so_far < n) {
            size_t chunk = (n - so_far) < RING ? (n - so_far) : RING;
            if (drain_every >= 0 && attempts >= drain_every) {   // ring has room now: accept a chunk
                for (size_t i = 0; i < chunk; i++) buf.push_back(p[so_far + i]);
                so_far += chunk; consec = 0; attempts = 0;       // <-- the reset that defeats a naive bound
            } else {
                g_ms += TX; attempts++; consec++;                // one timed-out ring-send attempt
                if (consec >= MAX_CONSEC) break;                 // give up this call -> short write
            }
        }
        uint32_t dur = g_ms - start;
        if (dur > g_max_write_ms) g_max_write_ms = dur;
        return so_far;
    }
    void flush() {}
} Serial;
"""

# Host gone: the ring never drains (drain_every = -1). With the chunk cap each Serial.write covers one
# <=64-byte chunk -> at most MAX_CONSEC timeouts -> returns 0; the idle bound then tears the frame, and
# the link recovers. Verifies the gone case AND that one write is bounded to MAX_CONSEC*TX.
GONE_MAIN = r"""
static const int K = 30;
int main() {
    String payload(K, 'Q');
    uint32_t before = g_ms;
    writeFrame(payload);
    uint32_t waited = g_ms - before;
    bool tore = companionTxLinkBroken() && g_txTorn == 1 && g_txFrames == 0;
    bool one_write_bounded = g_max_write_ms <= (uint32_t)(MAX_CONSEC * TX);      // a single write <= one window
    bool total_bounded = waited <= COMPANION_TX_IDLE_MS + (uint32_t)(MAX_CONSEC * TX);
    size_t afterTear = Serial.buf.size();

    // While broken, further frames emit NOTHING even once the host would accept again.
    Serial.drain_every = 0;
    writeFrame(payload);
    bool suppressed = Serial.buf.size() == afterTear && g_txFrames == 0;

    // Device-side only: a fresh session clears the flag and emits again. The ACTUAL app reconnection
    // (pending command fails, then recovery with transport.isOpen==true) is proven in CommandSessionTest.
    companionClearTxLink();
    writeFrame(payload);
    bool recovered = !companionTxLinkBroken() && g_txFrames == 1 && Serial.buf.size() == afterTear + 4 + K;

    printf("tore=%d one_write_bounded=%d total_bounded=%d waited=%u max_write=%u suppressed=%d recovered=%d\n",
           tore, one_write_bounded, total_bounded, waited, g_max_write_ms, suppressed, recovered);
    return (tore && one_write_bounded && total_bounded && suppressed && recovered) ? 0 : 1;
}
"""

# Amplifying host: drains one chunk every 19 timeouts (< MAX_CONSEC, so a chunk always eventually goes
# and consec RESETS). A big frame (2000 B = ~32 chunks). With the real cap, each Serial.write is one
# chunk, so the longest single write is <= MAX_CONSEC*TX and writeAll re-checks the deadline between
# writes -> the frame is torn at the ceiling with a bounded total. The uncapped mutation runs the whole
# amplified drain inside ONE Serial.write, so one write alone exceeds the entire ceiling and the frame is
# (wrongly) reported whole - exactly the 6 s "counted as succeeded" the review measured.
AMPLIFY_MAIN = r"""
static const int K = 2000;
int main() {
    Serial.drain_every = 19;                      // < MAX_CONSEC: every chunk eventually accepted (reset)
    String payload(K, 'T');
    uint32_t before = g_ms;
    writeFrame(payload);
    uint32_t waited = g_ms - before;
    bool one_write_bounded = g_max_write_ms <= (uint32_t)(MAX_CONSEC * TX);   // THE point: one write <= one window
    bool total_bounded = waited <= COMPANION_TX_FRAME_MS + (uint32_t)(MAX_CONSEC * TX);
    bool torn = companionTxLinkBroken() && g_txTorn == 1 && g_txFrames == 0;  // too big to finish by the ceiling
    printf("torn=%d one_write_bounded=%d total_bounded=%d waited=%u max_write=%u ceiling=%u frames=%d\n",
           torn, one_write_bounded, total_bounded, waited, g_max_write_ms, COMPANION_TX_FRAME_MS, g_txFrames);
    return (torn && one_write_bounded && total_bounded) ? 0 : 1;
}
"""

# Without the chunk cap the same amplifying host lets ONE Serial.write run the whole drain: the longest
# single write exceeds the entire per-frame ceiling (the deadline is never re-checked mid-write), and the
# frame is reported whole. The mutation main asserts that pathology so the cap's necessity is proven.
AMPLIFY_UNCAPPED_MAIN = r"""
static const int K = 7000;                        // big enough that one uncapped write alone > the ceiling
int main() {
    Serial.drain_every = 19;
    String payload(K, 'T');
    writeFrame(payload);
    // One write ran longer than the whole ceiling, and the frame was (wrongly) counted as sent.
    bool one_write_over_ceiling = g_max_write_ms > COMPANION_TX_FRAME_MS;
    printf("one_write_over_ceiling=%d max_write=%u ceiling=%u frames=%d torn=%d\n",
           one_write_over_ceiling, g_max_write_ms, COMPANION_TX_FRAME_MS, g_txFrames, g_txTorn);
    return one_write_over_ceiling ? 0 : 1;
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
    """Mutation: pretend the full count was accepted, ignoring the real short-write return."""
    needle = "size_t n = Serial.write(buf + off, want);"
    assert block.count(needle) == 1, "writeAll inner write not found uniquely"
    return block.replace(
        needle, "Serial.write(buf + off, want); size_t n = want; // MUTATION: assume full write"
    )


def remove_chunk_cap(block):
    """Mutation: drop the per-write chunk cap so a single Serial.write takes the whole remainder."""
    needle = "        if (want > COMPANION_TX_CHUNK) want = COMPANION_TX_CHUNK;\n"
    assert block.count(needle) == 1, "chunk-cap line not found uniquely"
    return block.replace(needle, "        // MUTATION: no chunk cap\n")


def no_progress_reset(block):
    """Mutation: never reset the idle timer on progress, so even a draining host trips the idle bound."""
    needle = "lastProgress = millis(); // a byte moved: the host is draining, reset the idle timer"
    assert block.count(needle) == 1, "idle-reset line not found uniquely"
    return block.replace(needle, "; // MUTATION: idle timer never reset")


def keep_emitting_when_broken(block):
    """Mutation: drop the early-return guard so writeFrame keeps emitting after a tear."""
    needle = "    if (g_txLinkBroken) return;"
    assert block.count(needle) == 1, "broken-guard line not found uniquely"
    return block.replace(needle, "    // MUTATION: no broken guard")


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


class DelayedResume(unittest.TestCase):
    def test_slow_host_that_resumes_is_not_torn(self):
        with tempfile.TemporaryDirectory() as d:
            rc, out = build_and_run(REAL_BLOCK, RESUME_SHIM, RESUME_MAIN, d)
        self.assertEqual(rc, 0, f"a recoverable stall was torn: {out}")
        self.assertIn("torn=0", out)
        self.assertIn("broken=0", out)

    def test_idle_reset_is_what_saves_the_slow_host(self):
        # Without resetting the idle timer on progress, the same recoverable stall tears the frame.
        with tempfile.TemporaryDirectory() as d:
            rc, out = build_and_run(no_progress_reset(REAL_BLOCK), RESUME_SHIM, RESUME_MAIN, d)
        self.assertEqual(rc, 1, f"idle-reset mutation still completed the frame: {out}")
        self.assertIn("torn=1", out)


class HostGoneRecovery(unittest.TestCase):
    def test_tear_is_bounded_counted_suppressed_and_recovers(self):
        with tempfile.TemporaryDirectory() as d:
            rc, out = build_and_run(REAL_BLOCK, HWCDC_SHIM, GONE_MAIN, d)
        self.assertEqual(rc, 0, f"host-gone recovery failed: {out}")
        for token in ("tore=1", "one_write_bounded=1", "total_bounded=1", "suppressed=1", "recovered=1"):
            self.assertIn(token, out)

    def test_without_broken_guard_more_bytes_pile_on(self):
        # With the guard removed, the second writeFrame (host now accepting) emits onto the torn
        # stream instead of staying silent -> suppressed=0.
        with tempfile.TemporaryDirectory() as d:
            rc, out = build_and_run(keep_emitting_when_broken(REAL_BLOCK), HWCDC_SHIM, GONE_MAIN, d)
        self.assertEqual(rc, 1, f"broken-guard mutation still suppressed emission: {out}")
        self.assertIn("suppressed=0", out)


class BoundedWaitIncludesDriverBlock(unittest.TestCase):
    """The per-frame wait is bounded even against the REAL HWCDC loop (chunked, consec-reset)."""

    def test_amplifying_host_single_write_and_frame_are_bounded(self):
        # The host drains one chunk every 19 timeouts (< MAX_CONSEC), so the driver's consec counter
        # resets and a single uncapped write would run for seconds. With the chunk cap each Serial.write
        # is one chunk (<= MAX_CONSEC*TX), and the frame is torn at the ceiling with a bounded total.
        with tempfile.TemporaryDirectory() as d:
            rc, out = build_and_run(REAL_BLOCK, HWCDC_SHIM, AMPLIFY_MAIN, d)
        self.assertEqual(rc, 0, f"amplifying host not bounded: {out}")
        for token in ("torn=1", "one_write_bounded=1", "total_bounded=1"):
            self.assertIn(token, out)

    def test_without_chunk_cap_one_write_exceeds_the_whole_ceiling(self):
        # Removing the cap lets ONE Serial.write run the entire amplified drain: the longest single
        # write exceeds the per-frame ceiling (the deadline is never re-checked mid-write) and the frame
        # is wrongly reported whole - the exact ~6 s "counted as succeeded" the review measured.
        with tempfile.TemporaryDirectory() as d:
            rc, out = build_and_run(remove_chunk_cap(REAL_BLOCK), HWCDC_SHIM, AMPLIFY_UNCAPPED_MAIN, d)
        self.assertEqual(rc, 0, f"uncapped write did not blow past the ceiling: {out}")
        self.assertIn("one_write_over_ceiling=1", out)


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
        self.assertIn("off += n;", WRITE_ALL)                       # honors the short-write return
        self.assertIn("Serial.write(", WRITE_ALL)
        self.assertIn("millis() - frameDeadline", WRITE_ALL)       # absolute ceiling
        self.assertIn("COMPANION_TX_IDLE_MS", WRITE_ALL)           # idle bound (host accepts nothing)
        self.assertRegex(WRITE_ALL, r"while \(off < len\)")

    def test_writeall_caps_each_write_to_one_chunk(self):
        # The per-write size is capped so a single Serial.write cannot be reset-amplified past one
        # 20-timeout window (the real HWCDC consec-timeout reset, HWCDC.cpp:606).
        self.assertIn("COMPANION_TX_CHUNK", WRITE_ALL)
        self.assertRegex(WRITE_ALL, r"want > COMPANION_TX_CHUNK")
        self.assertIn("Serial.write(buf + off, want)", WRITE_ALL)

    def test_idle_timer_reset_on_progress(self):
        # The distinction between a slow-but-progressing host and a gone one: the idle timer must be
        # reset whenever a byte is accepted.
        self.assertRegex(WRITE_ALL, r"if \(n > 0\)")
        self.assertRegex(WRITE_ALL, r"lastProgress = millis\(\);")

    def test_torn_frame_counted_separately_not_as_tx_frame(self):
        self.assertRegex(WRITE_FRAME, r"if \(whole\) diagNoteTxFrame")
        self.assertRegex(WRITE_FRAME, r"else diagNoteTxTorn")

    def test_torn_frame_marks_link_broken_and_guards_emission(self):
        self.assertIn("if (g_txLinkBroken) return;", WRITE_FRAME)   # no emission onto a torn stream
        self.assertIn("g_txLinkBroken = true", WRITE_FRAME)         # a tear marks the link
        self.assertIn("return g_txLinkBroken", BROKEN_GET)
        self.assertIn("g_txLinkBroken = false", BROKEN_CLEAR)

    def test_flush_only_on_whole_frame(self):
        # A torn frame must not block on flush for bytes that will never drain.
        self.assertRegex(WRITE_FRAME, r"if \(whole\) Serial\.flush\(\)")

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
                for sig in (WRITE_ALL_SIG, WRITE_FRAME_SIG):
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
