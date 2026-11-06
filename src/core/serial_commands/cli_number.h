#pragma once
// Non-throwing numeric argument parsing for Bruce CLI callbacks (backlog F03).
//
// The CLI callbacks converted arguments with std::stoul. That code is compiled WITH exceptions and
// nothing on the companion routing path (routeCommand -> serialCli.parse) catches, so malformed text
// ("abc", "") throws std::invalid_argument and an over-wide number throws std::out_of_range: the
// throw reaches std::terminate and aborts/reboots the device. std::stoul also silently accepted a
// numeric prefix ("433abc" -> 433) and wrapped a leading '-' to a huge value.
//
// parseUnsigned() never throws. It accepts the whole token only: optional leading/trailing blanks,
// an optional '+', digits in the given base (base 16 also takes a "0x"/"0X" prefix), and a value
// within [0, max]. Anything else returns false and leaves `out` unchanged, so the caller can print
// an error and return without touching the radio.
//
// Pure C++ (no Arduino/ESP headers) so it is unit-tested on the host (tools/test_firmware_cli_number.py).
#include <cstdint>

namespace clinum {

inline bool isBlank(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n'; }

inline bool parseUnsigned(const char *s, int base, uint64_t max, uint64_t &out) {
    if (s == nullptr || (base != 10 && base != 16)) return false;
    while (isBlank(*s)) ++s;
    if (*s == '+') ++s;
    if (base == 16 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) s += 2;
    uint64_t v = 0;
    int digits = 0;
    for (; *s != '\0' && !isBlank(*s); ++s) {
        char c = *s;
        int d;
        if (c >= '0' && c <= '9') d = c - '0';
        else if (base == 16 && c >= 'a' && c <= 'f') d = c - 'a' + 10;
        else if (base == 16 && c >= 'A' && c <= 'F') d = c - 'A' + 10;
        else return false; // not a digit of this base (includes '-', '.', letters)
        if ((uint64_t)d > max || v > (max - (uint64_t)d) / (uint64_t)base) return false; // v*base+d > max
        v = v * (uint64_t)base + (uint64_t)d;
        ++digits;
    }
    while (isBlank(*s)) ++s;
    if (*s != '\0' || digits == 0) return false; // trailing garbage or no digits at all
    out = v;
    return true;
}

// Decimal value in [min, max] (32-bit). False for malformed text or a value out of range.
inline bool parseU32(const char *s, uint32_t min, uint32_t max, uint32_t &out) {
    uint64_t v = 0;
    if (!parseUnsigned(s, 10, max, v) || v < min) return false;
    out = (uint32_t)v;
    return true;
}

// Hexadecimal value in [0, max] (32-bit), "0x" prefix optional.
inline bool parseHexU32(const char *s, uint32_t max, uint32_t &out) {
    uint64_t v = 0;
    if (!parseUnsigned(s, 16, max, v)) return false;
    out = (uint32_t)v;
    return true;
}

} // namespace clinum
