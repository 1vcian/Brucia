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

// Bruce's CLI (SimpleCLI) matches command names case-insensitively and splits one CMD string into
// several commands at a newline or ";;". The classifier therefore compares case-insensitively and
// classifies every such segment, so `RF RX 433920000`, `RfSend`-as-`rfsend` or
// `subghz mfcodes list;;subghz rx` cannot slip past the 409 while a stream owns the radio. Quote
// and backslash characters are ignored inside words (SimpleCLI uses them for quoting/escaping).
// Over-matching is safe here: it only refuses a radio command while Listen/Spectrum runs.

inline char subghzLower(char c) { return (c >= 'A' && c <= 'Z') ? (char)(c - 'A' + 'a') : c; }

inline bool subghzSkipChar(char c) { return c == '"' || c == '\\'; }

// End of the current segment: a newline/CR, ";;", or the end of the string.
inline bool subghzSegmentEnd(const char *p) { return *p == '\0' || *p == '\n' || *p == '\r' || (p[0] == ';' && p[1] == ';'); }

// Next word of the segment starting at *p, lowercased into `out` (quotes/backslashes dropped,
// truncated to `cap - 1` chars). Advances *p past the word. Returns false at the segment end.
inline bool subghzNextWord(const char *&p, char *out, size_t cap) {
    while (*p == ' ' || subghzSkipChar(*p)) ++p;
    if (subghzSegmentEnd(p)) return false;
    size_t n = 0;
    while (!subghzSegmentEnd(p) && *p != ' ') {
        if (!subghzSkipChar(*p) && n + 1 < cap) out[n++] = subghzLower(*p);
        ++p;
    }
    out[n] = '\0';
    return true;
}

inline bool subghzRadioVerb(const char *verb) {
    static const char *const radioVerbs[] = {"rx",           "tx",             "txp",     "scan",
                                             "tx_from_file", "tx_from_buffer", "keeloqtx"};
    for (const char *w : radioVerbs) {
        if (strcmp(verb, w) == 0) return true;
    }
    return false;
}

// True for a command that initializes/uses the CC1101 itself: every `subghz_*` companion command,
// `RfSend`, and the Bruce CLI `rf`/`subghz` radio verbs (rx, tx, txp, scan, tx_from_file,
// tx_from_buffer, keeloqtx), in any letter case and in any newline/";;" segment of the CMD.
// `rf/subghz mfcodes ...` only edits a file and stays allowed. A segment that starts with a radio
// verb counts too once any segment names rf/subghz (a composite's sub-command can be split off by
// ";;" inside quotes).
inline bool subghzCommandNeedsRadio(const char *cmd) {
    if (cmd == nullptr) return false;
    bool rfComposite = false, bareRadioVerb = false;
    const char *p = cmd;
    while (true) {
        char first[24], second[24];
        if (subghzNextWord(p, first, sizeof(first))) {
            if (strncmp(first, "subghz_", 7) == 0 || strcmp(first, "rfsend") == 0) return true;
            if (strcmp(first, "rf") == 0 || strcmp(first, "subghz") == 0) {
                rfComposite = true;
                if (subghzNextWord(p, second, sizeof(second)) && subghzRadioVerb(second)) return true;
            } else if (subghzRadioVerb(first)) {
                bareRadioVerb = true;
            }
        }
        while (!subghzSegmentEnd(p)) ++p; // rest of this segment
        if (*p == '\0') break;
        p += (p[0] == ';') ? 2 : 1;
    }
    return rfComposite && bareRadioVerb;
}

} // namespace companion
} // namespace brucia
