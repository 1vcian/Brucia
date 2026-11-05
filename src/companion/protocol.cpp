#include "core/utils.h"
#include "diag_core.h"
#include "internal.h"
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

static void writeFrame(const String &payload) {
    uint32_t len = payload.length();
    uint8_t lenbuf[4] = {
        (uint8_t)(len & 0xFF),
        (uint8_t)((len >> 8) & 0xFF),
        (uint8_t)((len >> 16) & 0xFF),
        (uint8_t)((len >> 24) & 0xFF),
    };
    Serial.write(lenbuf, 4);
    Serial.write((const uint8_t *)payload.c_str(), len);
    Serial.flush();
    diagNoteTxFrame();
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
