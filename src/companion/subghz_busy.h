#pragma once
// Which companion commands drive the CC1101 on their own (pure, host-testable via
// tools/test_radio_guards.py).
//
// The subghz.raw and subghz.spectrum streams own the CC1101 while they run. A one-shot command
// that re-initializes the radio (a receive window, a transmit, the replay/config/jammer/brute-force
// helpers) ends with deinitRfModule() and, for the receive paths, a second RMT RX channel on the
// same GDO0 pin: the running stream then stays "on" in the app while the radio under it is idle or
// re-tuned. dispatchSubghzCommand() refuses these with ERR 409 while a stream owns the radio, like
// the BLE advertisers refuse while the scan owns the NimBLE stack (batch 16).
#include <cstring>

namespace brucia {
namespace companion {

inline bool subghzWordIs(const char *s, size_t len, const char *word) {
    return strlen(word) == len && strncmp(s, word, len) == 0;
}

// True for a command that initializes/uses the CC1101 itself: every `subghz_*` companion command,
// `RfSend`, and the Bruce CLI `rf`/`subghz` radio verbs (rx, tx, txp, scan, tx_from_file,
// tx_from_buffer, keeloqtx). `rf/subghz mfcodes ...` only edits a file and stays allowed.
inline bool subghzCommandNeedsRadio(const char *cmd) {
    if (cmd == nullptr) return false;
    while (*cmd == ' ') ++cmd;
    if (strncmp(cmd, "subghz_", 7) == 0) return true;
    const char *end = cmd;
    while (*end && *end != ' ') ++end;
    const size_t len = (size_t)(end - cmd);
    if (subghzWordIs(cmd, len, "RfSend")) return true;
    if (!subghzWordIs(cmd, len, "rf") && !subghzWordIs(cmd, len, "subghz")) return false;
    const char *verb = end;
    while (*verb == ' ') ++verb;
    const char *vend = verb;
    while (*vend && *vend != ' ') ++vend;
    const size_t vlen = (size_t)(vend - verb);
    static const char *const radioVerbs[] = {"rx",           "tx",             "txp",     "scan",
                                             "tx_from_file", "tx_from_buffer", "keeloqtx"};
    for (const char *w : radioVerbs) {
        if (subghzWordIs(verb, vlen, w)) return true;
    }
    return false;
}

} // namespace companion
} // namespace brucia
