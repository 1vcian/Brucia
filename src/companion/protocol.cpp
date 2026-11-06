#include "core/utils.h"
#include "diag_core.h"
#include "internal.h"
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <globals.h>

namespace brucia {
namespace companion {
// --- framing over USB-CDC (Serial) ---

// Retain partial headers/payloads across short polls. USB packets may arrive more
// than one service-loop iteration apart; discarding a partial read loses framing.
static uint8_t rxHeader[4];
static size_t rxHeaderUsed = 0;
static uint32_t rxExpected = 0, rxLastByte = 0;
static String rxPayload;

void resetFrameReader() {
    rxHeaderUsed = 0;
    rxExpected = 0;
    rxPayload = "";
}

bool readFrame(String &payload, uint32_t timeoutMs) {
    // A genuinely abandoned frame must not retain a prefix across a new session.
    if ((rxHeaderUsed || rxExpected) && millis() - rxLastByte > 2000) {
        diagNoteRxReject(DIAG_RX_STALE);
        resetFrameReader();
    }
    const uint32_t started = millis();
    while (Serial.available() && millis() - started < timeoutMs) {
        const int c = Serial.read();
        if (c < 0) break;
        rxLastByte = millis();
        if (rxHeaderUsed < 4) {
            rxHeader[rxHeaderUsed++] = static_cast<uint8_t>(c);
            if (rxHeaderUsed < 4) continue;
            rxExpected = static_cast<uint32_t>(rxHeader[0]) | (static_cast<uint32_t>(rxHeader[1]) << 8) |
                         (static_cast<uint32_t>(rxHeader[2]) << 16) |
                         (static_cast<uint32_t>(rxHeader[3]) << 24);
            if (rxExpected == 0 || rxExpected > COMPANION_MAX_FRAME || !rxPayload.reserve(rxExpected + 1)) {
                diagNoteRxReject(
                    rxExpected == 0 ? DIAG_RX_ZERO_LEN
                                    : (rxExpected > COMPANION_MAX_FRAME ? DIAG_RX_OVERSIZE : DIAG_RX_ALLOC_FAIL)
                );
                resetFrameReader();
                return false;
            }
        } else {
            rxPayload += static_cast<char>(c);
            if (rxPayload.length() == rxExpected) {
                payload = rxPayload;
                resetFrameReader();
                diagNoteRxFrame();
                return true;
            }
        }
    }
    return false;
}

// Serializes the whole frame emission across tasks. A frame is a 4-byte length prefix
// followed by exactly that many payload bytes; if two tasks interleave their Serial.write
// calls the length no longer matches its payload and the stream desynchronizes (hardware
// soak 2026-11-06 / backlog F23). Companion responses (PONG/ACK/ERR/DATA) are emitted from
// the companion loop task; stream EVENTs (wifi.sta/wifi.probe/subghz.raw, ble.dev, mic/fm
// PCM, ...) are emitted from the same loop today, but the lock makes the framing correct for
// any future emitter on another task too. A recursive mutex so a nested emission on the same
// task (e.g. an oversized-frame ERR rebuilt inside sendEnvelope) can never self-deadlock.
//
// No emitter runs in ISR context: the only ISR is the RMT on_recv_done callback
// (subghz.cpp), which merely xQueueSendFromISR()s; the actual subghz.raw frame is emitted
// later from subghzService() on the loop task. The esp_wifi promiscuous RX callbacks run on
// the Wi-Fi task but only fill ring buffers; their frames are emitted from *Service() on the
// loop task. So taking a blocking mutex here is always safe.
//
// Created at static init (the FreeRTOS heap is already up when C++ constructors run on the
// ESP32). If that allocation ever failed, g_frameLock stays null and writeFrame falls back
// to the unlocked write - correct during very early boot, before any stream can be active.
static SemaphoreHandle_t g_frameLock = xSemaphoreCreateRecursiveMutex();

// Two bounds on how long emitting one frame may wait on a host that is not draining the port. Just
// raising a single timeout is not enough (backlog F23 / point 2): the HWCDC driver can itself block
// for most of a window and then return a SHORT count, so a single wall-clock deadline tears a host
// that was merely slow-but-progressing, while too long a deadline wedges the loop on a host that is
// truly gone. So we separate the two:
//  - COMPANION_TX_IDLE_MS: give up if NO byte is accepted for this long. That is the real "host gone
//    / blocked" signal. It is reset on every byte of progress, so a host that keeps draining - even
//    in small, delayed chunks - is never torn.
//  - COMPANION_TX_FRAME_MS: an absolute per-frame ceiling so a host that accepts a trickle forever
//    (1 byte, then stalls just under the idle bound, repeatedly) still cannot hold the loop open.
// Both are far under the 60 s app-silence idle-exit, and the task is blocked (not spinning) during
// the wait, so the idle task keeps feeding the watchdog.
static const uint32_t COMPANION_TX_IDLE_MS = 1500;
static const uint32_t COMPANION_TX_FRAME_MS = 4000;

// The two bounds above are measured with millis() around our own loop, but a single Serial.write() can
// itself BLOCK inside the driver before it returns, and bounding that block is subtle. On the CDC path
// (HWCDC on the T-Embed, USBCDC on TinyUSB boards) HWCDC::write(buf,len) splits the buffer into
// ring-sized chunks (the TX ring is 256 bytes) and waits up to tx_timeout_ms per chunk with a cap of 20
// CONSECUTIVE timeouts - but that counter RESETS every time a chunk is accepted (HWCDC.cpp:606). So a
// host that drains just enough to accept one chunk now and then keeps a SINGLE large write() alive far
// longer than 20 * tx_timeout_ms (measured at ~6 s for a full frame). Shrinking tx_timeout alone does
// not fix this. Two things give a real bound:
//  - companionApplyTxTimeout(true) shrinks the per-attempt tx_timeout to COMPANION_TX_WRITE_MS;
//  - writeAll issues at most COMPANION_TX_CHUNK (<= one ring chunk) bytes per Serial.write, so each call
//    covers a SINGLE chunk whose consec-timeout counter cannot be reset mid-call -> one call blocks at
//    most 20 * COMPANION_TX_WRITE_MS, and writeAll re-checks the deadline before the next call.
// Net worst-case wait for a torn frame is then about COMPANION_TX_FRAME_MS + 20 * COMPANION_TX_WRITE_MS
// (~4.2 s) - far under the 60 s app-silence idle-exit, and the task blocks (not spins) inside the driver
// so the idle task still feeds the watchdog. The classic-ESP32 UART path has no such timeout API
// (uart_write_bytes blocks until the bytes are queued, never returning short), so it is left untouched;
// chunking is harmless there.
static const uint32_t COMPANION_TX_WRITE_MS = 10;        // per-attempt driver tx timeout in companion mode
static const uint32_t COMPANION_TX_WRITE_MS_DEFAULT = 100; // the arduino-esp32 core default, restored on exit
static const size_t COMPANION_TX_CHUNK = 64;             // bytes per Serial.write: <= one 256-byte ring chunk

void companionApplyTxTimeout(bool bounded) {
#if ARDUINO_USB_CDC_ON_BOOT
    Serial.setTxTimeoutMs(bounded ? COMPANION_TX_WRITE_MS : COMPANION_TX_WRITE_MS_DEFAULT);
#else
    (void)bounded; // classic-ESP32 UART: no per-write timeout to bound
#endif
}

// Set true when a frame could not be written in full (writeAll returned false): the length prefix or
// payload was left torn on the wire, so the length-prefixed stream is now desynced and CANNOT be
// resynced in place (the format has no sync marker). writeFrame stops emitting once it is set so no
// further bytes pile onto the corrupt stream, and the companion loop observes it and drops the link
// (companionTxLinkBroken) so the app reconnects onto a fresh, frame-aligned session. Cleared at the
// start of each companion session (companionClearTxLink).
static volatile bool g_txLinkBroken = false;

bool companionTxLinkBroken() { return g_txLinkBroken; }
void companionClearTxLink() { g_txLinkBroken = false; }

// Write every byte of [buf, len) to Serial. Serial.write() is NOT guaranteed to accept all of
// `len`: the ESP32-S3 USB-Serial-JTAG path (HWCDC, the port Serial uses on the T-Embed - this board
// builds with ARDUINO_USB_MODE=1) returns a SHORT count under sustained host backpressure, and so
// does the TinyUSB path (USBCDC); both honor the Arduino Stream::write contract that a short write
// is the caller's to finish. (The classic ESP32 UART path blocks and always writes all, so this
// loop is a no-op there.) The old code ignored the return value, so a short write truncated the
// length prefix or the payload and desynced the framed stream even with a single writer - stray
// non-frame bytes, an undecodable frame and a tx/rx counter mismatch, exactly what the 2026-11-06
// soak saw (backlog F23). Loop on the return, writing only the remainder; a delayed partial write
// that resumes keeps making progress and completes, while a host that accepts nothing is bounded by
// the idle timeout and a trickling host by the absolute `frameDeadline`. Returns true only when all
// len bytes were accepted; false means the frame is torn (the host stopped accepting) and the link
// must be dropped.
static bool writeAll(const uint8_t *buf, size_t len, uint32_t frameDeadline) {
    size_t off = 0;
    uint32_t lastProgress = millis();
    while (off < len) {
        // Check the bounds BEFORE each (possibly blocking) write so we never START a write once the
        // frame deadline has passed or the host has accepted nothing for the idle window. This is what
        // keeps the total wait bounded even though Serial.write itself blocks inside the driver: the
        // last write can only begin before frameDeadline, so the worst case is frameDeadline plus one
        // bounded driver window (COMPANION_TX_WRITE_MS; see companionApplyTxTimeout).
        if ((int32_t)(millis() - frameDeadline) >= 0) return false;       // absolute per-frame ceiling
        if (millis() - lastProgress >= COMPANION_TX_IDLE_MS) return false; // host accepted nothing
        // Cap each write to one ring chunk so a single Serial.write cannot be reset-amplified past one
        // 20-timeout window (see the COMPANION_TX_CHUNK note above); the deadline is re-checked between.
        size_t want = len - off;
        if (want > COMPANION_TX_CHUNK) want = COMPANION_TX_CHUNK;
        size_t n = Serial.write(buf + off, want);
        if (n > 0) {
            off += n;
            lastProgress = millis(); // a byte moved: the host is draining, reset the idle timer
        } else {
            delay(1); // no progress: let the TX ring drain / the host catch up before retrying
        }
    }
    return true;
}

static void writeFrame(const String &payload) {
    // The stream is already torn: emitting more only corrupts it further. Drop everything until the
    // loop tears the link down and the app reconnects onto a clean session.
    if (g_txLinkBroken) return;
    uint32_t len = payload.length();
    uint8_t lenbuf[4] = {
        (uint8_t)(len & 0xFF),
        (uint8_t)((len >> 8) & 0xFF),
        (uint8_t)((len >> 16) & 0xFF),
        (uint8_t)((len >> 24) & 0xFF),
    };
    // Hold the lock around the length write, the payload write and the flush - and nothing else -
    // so the four length bytes and their payload reach Serial as one uninterrupted run and no other
    // task can interpose a frame between them. One deadline for the whole frame bounds the hold.
    const bool locked = g_frameLock != nullptr && xSemaphoreTakeRecursive(g_frameLock, portMAX_DELAY) == pdTRUE;
    const uint32_t deadline = millis() + COMPANION_TX_FRAME_MS;
    const bool whole = writeAll(lenbuf, 4, deadline) &&
                       writeAll((const uint8_t *)payload.c_str(), len, deadline);
    // Flush only a whole frame: on a torn frame the host is gone, and flush would just block up to
    // its own driver timeout for bytes that will never drain.
    if (whole) Serial.flush();
    else g_txLinkBroken = true; // mark before releasing the lock so no other task emits onto the tear
    if (locked) xSemaphoreGiveRecursive(g_frameLock);
    // Only count a frame that left intact; a torn write is counted separately so a diag taken on the
    // NEXT session shows it (the counters persist across sessions; this one cannot be delivered now).
    if (whole) diagNoteTxFrame();
    else diagNoteTxTorn();
}

void beginEnvelope(JsonDocument &out, uint8_t kind, uint32_t id) {
    out["v"] = BRUCIA_PROTO_VERSION;
    out["t"] = kind;
    out["id"] = id;
}

void sendEnvelope(JsonDocument &out) {
    if (measureJson(out) > COMPANION_MAX_FRAME) {
        const uint32_t id = out["id"] | 0;
        out.clear();
        beginEnvelope(out, MSG_ERR, id);
        out["d"]["code"] = 413;
        out["d"]["msg"] = "response exceeds frame limit";
    }
    // Recovery diagnostics: every ERR, whichever handler built it, lands in one status bucket.
#if defined(COMPANION_HAS_DIAG)
    if ((out["t"] | 0) == MSG_ERR) diagNoteErr(out["d"]["code"] | 0u);
#endif
    String s;
    serializeJson(out, s);
    writeFrame(s);
}

void sendAck(uint32_t id, bool ok) {
    JsonDocument out;
    beginEnvelope(out, MSG_ACK, id);
    out["d"]["ok"] = ok;
    sendEnvelope(out);
}

// ACK carrying a human-readable `out` string (used by the bounded command wrappers).
void companionAckMsg(uint32_t id, bool ok, const char *out) {
    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = ok;
    d["out"] = out;
    sendEnvelope(o);
}

void sendErr(uint32_t id, uint32_t code, const char *msg) {
    JsonDocument out;
    beginEnvelope(out, MSG_ERR, id);
    out["d"]["code"] = code;
    out["d"]["msg"] = msg;
    sendEnvelope(out);
}

void sendPong(uint32_t id) {
    JsonDocument out;
    beginEnvelope(out, MSG_PONG, id);
    JsonObject d = out["d"].to<JsonObject>();
    d["battery"] = getBattery(); // the app pings periodically, so battery stays fresh
    sendEnvelope(out);
}

} // namespace companion
} // namespace brucia
