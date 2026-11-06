#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
namespace brucia {
namespace companion {
// ---------------------------------------------------------------------------
// Brucia companion protocol v0 (see proto/PROTOCOL.md).
// Framing: uint32 little-endian payload length, then <length> bytes of payload.
// Payload: a JSON object envelope {v, t, id, d}.
// ---------------------------------------------------------------------------

static const uint32_t BRUCIA_PROTO_VERSION = 0;
static const uint32_t COMPANION_MAX_FRAME = 8192;

enum CompanionKind : uint8_t {
    MSG_HELLO = 1,
    MSG_CAPS = 2,
    MSG_CMD = 3,
    MSG_ACK = 4,
    MSG_ERR = 5,
    MSG_EVENT = 6,
    MSG_DATA = 7,
    MSG_SUB = 8,
    MSG_PING = 9,
    MSG_PONG = 10,
};

void resetFrameReader();
bool readFrame(String &payload, uint32_t timeoutMs);
// True once a frame could not be written in full: the TX stream is desynced and the link must be
// dropped so the app reconnects onto a clean session. Cleared by companionClearTxLink().
bool companionTxLinkBroken();
void companionClearTxLink();
// Bound (true) or restore (false) the CDC driver's per-write tx timeout so one blocking Serial.write
// cannot add ~2 s to the per-frame wait. No-op on the classic-ESP32 UART path. Call true on entering
// companion mode, false on leaving.
void companionApplyTxTimeout(bool bounded);
void beginEnvelope(JsonDocument &out, uint8_t kind, uint32_t id);
void sendEnvelope(JsonDocument &out);
void sendAck(uint32_t id, bool ok);
void companionAckMsg(uint32_t id, bool ok, const char *out);
void sendErr(uint32_t id, uint32_t code, const char *msg);
void sendPong(uint32_t id);

} // namespace companion
} // namespace brucia
