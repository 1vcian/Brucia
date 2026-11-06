#pragma once
// iBeacon proximity UUID check for `ble_ibeacon <secs> [uuid]` (pure, host-tested by
// tools/test_radio_guards.py).
//
// The UUID text went straight into NimBLEUUID/setProximityUUID: a malformed value ("xyz", a short or
// over-long string, extra words) did not build the 16-byte UUID the user typed, yet the broadcast
// started and the ACK said "ibeacon done". The handler now refuses it with ERR 400 before the BLE
// stack is touched. Accepted: the canonical 8-4-4-4-12 hexadecimal form (36 characters, either
// case), which is what the app sends.
#include <cstddef>

namespace brucia {
namespace companion {

inline bool ibeaconUuidValid(const char *s) {
    if (s == nullptr) return false;
    for (size_t i = 0; i < 36; ++i) {
        const char c = s[i];
        if (c == '\0') return false;
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (c != '-') return false;
        } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
            return false;
        }
    }
    return s[36] == '\0';
}

} // namespace companion
} // namespace brucia
