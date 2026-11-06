#pragma once
// Brucia: Arduino-free iButton (1-Wire DS1990A / RW1990) core shared by the standalone menu,
// .ibtn file loading and the companion handlers. Plain C++ so it can be tested on the host.

#include <stddef.h>
#include <stdint.h>

enum IButtonResult {
    IBUTTON_SUCCESS = 0,
    IBUTTON_FAILURE,
    IBUTTON_NO_KEY,
    IBUTTON_CRC_ERROR,
    IBUTTON_CANCELLED,
};

// Parse an 8-byte ROM id. ':' and ' ' are ignored, surrounding whitespace is trimmed, and
// exactly 16 hex chars (either case) are required. On failure `out` is left untouched.
bool ibuttonParseId(const char *text, uint8_t out[8]);

// Uppercase hex with an optional separator: "AA:BB:..", "AA BB ..", or contiguous with "".
// Always NUL-terminates when outLen > 0 (truncates if the buffer is too small).
void ibuttonFormatId(const uint8_t id[8], char *out, size_t outLen, const char *sep);

// Dallas/Maxim CRC8 (poly 0x31 reflected), identical to OneWire::crc8.
uint8_t ibuttonCrc8(const uint8_t *data, size_t len);

// Usable iButton GPIO: 0 is the unset default (and GPIO0 is the ESP32-S3 boot strap pin),
// -1 means disabled from the pin menu, gpioMax and above do not exist.
bool ibuttonPinUsable(int pin, int gpioMax);

// Minimal 1-Wire bus used by ibuttonReadRom (OneWire adapter on device, fake in host tests).
struct IButtonBus {
    virtual ~IButtonBus() {}
    virtual bool reset() = 0; // true when a device answered with a presence pulse
    virtual void write(uint8_t v) = 0;
    virtual void readBytes(uint8_t *buf, size_t len) = 0;
    virtual void delayMs(uint32_t ms) = 0;
    virtual bool cancelled() { return false; }
    virtual void onRetry(int) {} // called with the 0-based attempt that failed CRC
};

// Read ROM (0x33) with retries. Per attempt: cancelled -> IBUTTON_CANCELLED; no presence ->
// IBUTTON_NO_KEY at once if stopOnNoPresence, else delay and retry; valid CRC -> SUCCESS;
// otherwise onRetry + delay. Exhausted: IBUTTON_CRC_ERROR if any presence answered (out holds
// the last bytes read), else IBUTTON_NO_KEY.
IButtonResult ibuttonReadRom(
    IButtonBus &bus, uint8_t out[8], int attempts, bool stopOnNoPresence, uint32_t retryDelayMs
);

// Headless write (companion ibutton_write), the steps the standalone menu takes plus a read-back:
// wait for a presence pulse (up to `attempts` resets, retryDelayMs apart; the menu waits without a
// bound), run `writeId` (the RW1990 sequence), then read the ROM back with the same attempts and
// compare all 8 bytes with `id` (even when its CRC byte is not valid: the bytes asked for are what
// must land). *written tells whether the sequence ran.
//   SUCCESS: the read-back equals id;
//   NO_KEY: nothing answered before the write (nothing written) or after it (unverified);
//   FAILURE: a key answered with other bytes (not a writable blank, or the write did not take);
//   CANCELLED: bus.cancelled() before the write.
IButtonResult ibuttonWriteVerified(
    IButtonBus &bus, const uint8_t id[8], void (*writeId)(void *ctx), void *ctx, int attempts,
    uint32_t retryDelayMs, bool *written
);
