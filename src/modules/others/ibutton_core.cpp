#include "ibutton_core.h"
#include <string.h>

static int hexNibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static bool isTrimSpace(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n'; }

bool ibuttonParseId(const char *text, uint8_t out[8]) {
    if (!text) return false;
    size_t start = 0, end = strlen(text);
    while (start < end && isTrimSpace(text[start])) start++;
    while (end > start && isTrimSpace(text[end - 1])) end--;

    uint8_t tmp[8] = {0};
    int count = 0;
    for (size_t i = start; i < end; i++) {
        char c = text[i];
        if (c == ':' || c == ' ') continue;
        int v = hexNibble(c);
        if (v < 0 || count >= 16) return false;
        tmp[count / 2] = (uint8_t)((tmp[count / 2] << 4) | v);
        count++;
    }
    if (count != 16) return false;
    memcpy(out, tmp, 8);
    return true;
}

void ibuttonFormatId(const uint8_t id[8], char *out, size_t outLen, const char *sep) {
    static const char digits[] = "0123456789ABCDEF";
    if (!out || outLen == 0) return;
    if (!sep) sep = "";
    size_t pos = 0;
    auto put = [&](char c) {
        if (pos + 1 < outLen) out[pos++] = c;
    };
    for (int i = 0; i < 8; i++) {
        put(digits[id[i] >> 4]);
        put(digits[id[i] & 0x0F]);
        for (const char *s = sep; i < 7 && *s; s++) put(*s);
    }
    out[pos] = '\0';
}

uint8_t ibuttonCrc8(const uint8_t *data, size_t len) {
    uint8_t crc = 0;
    while (len--) {
        uint8_t in = *data++;
        for (int i = 0; i < 8; i++) {
            uint8_t mix = (crc ^ in) & 0x01;
            crc >>= 1;
            if (mix) crc ^= 0x8C;
            in >>= 1;
        }
    }
    return crc;
}

bool ibuttonPinUsable(int pin, int gpioMax) { return pin > 0 && pin < gpioMax; }

IButtonResult ibuttonReadRom(
    IButtonBus &bus, uint8_t out[8], int attempts, bool stopOnNoPresence, uint32_t retryDelayMs
) {
    bool anyPresence = false;
    for (int attempt = 0; attempt < attempts; attempt++) {
        if (bus.cancelled()) return IBUTTON_CANCELLED;
        if (!bus.reset()) {
            if (stopOnNoPresence) return IBUTTON_NO_KEY;
            bus.delayMs(retryDelayMs);
            continue;
        }
        anyPresence = true;
        bus.write(0x33); // Read ROM
        bus.readBytes(out, 8);
        if (ibuttonCrc8(out, 7) == out[7]) return IBUTTON_SUCCESS;
        bus.onRetry(attempt);
        bus.delayMs(retryDelayMs);
    }
    return anyPresence ? IBUTTON_CRC_ERROR : IBUTTON_NO_KEY;
}

IButtonResult ibuttonWriteVerified(
    IButtonBus &bus, const uint8_t id[8], void (*writeId)(void *ctx), void *ctx, int attempts,
    uint32_t retryDelayMs, bool *written
) {
    if (written) *written = false;
    bool present = false;
    for (int attempt = 0; attempt < attempts && !present; attempt++) {
        if (bus.cancelled()) return IBUTTON_CANCELLED;
        present = bus.reset();
        if (!present) bus.delayMs(retryDelayMs);
    }
    if (!present) return IBUTTON_NO_KEY;
    writeId(ctx);
    if (written) *written = true;
    uint8_t back[8];
    IButtonResult r = ibuttonReadRom(bus, back, attempts, false, retryDelayMs);
    if (r == IBUTTON_NO_KEY || r == IBUTTON_CANCELLED) return IBUTTON_NO_KEY;
    for (int i = 0; i < 8; i++)
        if (back[i] != id[i]) return IBUTTON_FAILURE;
    return IBUTTON_SUCCESS;
}
