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

// Upper bound on how long the emission of one whole frame may wait on a stalled host before it
// gives up with the frame left partially written. Chosen above one HWCDC write timeout window
// (~20 * 100 ms, see below) so a host that resumes is still served, yet the companion loop can
// never be wedged: it is far under the 60 s app-silence idle-exit, and the task is blocked (not
// spinning) during the wait so the idle task still feeds the watchdog.
static const uint32_t COMPANION_TX_FRAME_MS = 2000;

// Write every byte of [buf, len) to Serial. Serial.write() is NOT guaranteed to accept all of
// `len`: the ESP32-S3 USB-Serial-JTAG path (HWCDC, the port Serial uses on the T-Embed) returns a
// SHORT count under sustained host backpressure, and so does the TinyUSB path (USBCDC); both honor
// the Arduino Stream::write contract that a short write is the caller's to finish. (The classic
// ESP32 UART path blocks and always writes all, so this loop is a no-op there.) The old code
// ignored the return value, so a short write truncated the length prefix or the payload and
// desynced the framed stream even with a single writer - stray non-frame bytes, an undecodable
// frame and a tx/rx counter mismatch, exactly what the 2026-11-06 soak saw (backlog F23). Loop on
// the return, writing only the remainder; bound the total wait by `deadline` with a 1 ms yield
// between tries so a vanished host cannot hang the loop. Returns true only when all len bytes were
// accepted; a false means the frame is torn (link effectively dead) and must not be counted.
static bool writeAll(const uint8_t *buf, size_t len, uint32_t deadline) {
    size_t off = 0;
    while (off < len) {
        off += Serial.write(buf + off, len - off);
        if (off >= len) break;
        if ((int32_t)(millis() - deadline) >= 0) return false;
        delay(1); // let the TX ring drain / the host catch up before retrying the remainder
    }
    return true;
}

static void writeFrame(const String &payload) {
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
    Serial.flush();
    if (locked) xSemaphoreGiveRecursive(g_frameLock);
    // Only count a frame that left intact; a torn write must not inflate tx_frames (that would be
    // the very tx/rx mismatch the soak reported).
    if (whole) diagNoteTxFrame();
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
