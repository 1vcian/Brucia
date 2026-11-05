#pragma once
// Safe hex-key parsing for the single-code RF transmit commands (`subghz tx`, `subghz txp`).
//
// The CLI used std::stoull(key, nullptr, 16) directly. A key longer than 16 hex digits (e.g. a
// phone-computed De Bruijn sweep: order 12 is 1024 hex characters) makes std::stoull throw
// std::out_of_range. That code is Bruce CLI, compiled WITH exceptions, and nothing on the
// companion routing path (serialCli.parse) catches it, so the throw reaches std::terminate and
// aborts/reboots the device. This parser rejects such input instead: no exception, a clear error,
// the device state preserved, the next command accepted. It does NOT extend transmit capability -
// a `subghz tx` key is still one fixed code of at most 64 bits (16 hex digits).
//
// Pure C++ (no Arduino/ESP headers) so it is unit-tested on the host (tools/test_firmware_debruijn.py).
#include <cstddef>
#include <cstdint>

namespace rfhex {

// Maximum hex digits that fit a 64-bit single-code key.
static const int MAX_HEX_DIGITS = 16;

// Parse a hex string into a 64-bit key. Returns true on success, false (out left unchanged) for:
// a null/empty string, any non-hex character, or more than MAX_HEX_DIGITS hex digits (too long to
// be a single 64-bit code). An optional "0x"/"0X" prefix and leading spaces/tabs are accepted.
inline bool parseKey64(const char *s, uint64_t &out) {
    if (s == nullptr) return false;
    while (*s == ' ' || *s == '\t') ++s;
    if (s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) s += 2;
    if (*s == '\0') return false;
    uint64_t v = 0;
    int digits = 0;
    for (const char *p = s; *p != '\0'; ++p) {
        char c = *p;
        int d;
        if (c >= '0' && c <= '9') d = c - '0';
        else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
        else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
        else return false; // non-hex character
        if (++digits > MAX_HEX_DIGITS) return false; // too long for a 64-bit single code
        v = (v << 4) | (uint64_t)d;
    }
    out = v;
    return true;
}

} // namespace rfhex
