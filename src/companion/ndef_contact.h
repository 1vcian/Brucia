#pragma once
// vCard contact record for `nfc_ndef_push contact <Name[;+phone]>` (pure, host-tested by
// tools/test_radio_guards.py).
//
// The contact payload becomes a "text/vcard" MIME record (Ndef::mimeRecord: 3 header bytes + the
// 10-byte type + the vCard). PN532 target mode serves at most 126 bytes of NDEF message
// (kNdefMaxLen 128 minus the 2-byte length); a longer record made emulate() fail only after the
// module was started. runNfcNdefPush refuses it up front with ERR 400 naming the limit.
#include <cstddef>
#include <string>

namespace brucia {
namespace companion {

static const size_t NDEF_PUSH_MAX_RECORD = 126; // PN532 emulate(): kNdefMaxLen (128) - 2
static const size_t NDEF_MIME_RECORD_OVERHEAD = 3 + 10; // header, type length, payload length + "text/vcard"

inline std::string ndefTrim(const std::string &s) {
    size_t a = 0, b = s.size();
    while (a < b && (s[a] == ' ' || s[a] == '\t' || s[a] == '\r' || s[a] == '\n')) ++a;
    while (b > a && (s[b - 1] == ' ' || s[b - 1] == '\t' || s[b - 1] == '\r' || s[b - 1] == '\n')) --b;
    return s.substr(a, b - a);
}

// "Full Name" or "Full Name;+123456789" -> the vCard text the firmware wraps in the record.
inline std::string ndefContactVcard(const std::string &payload) {
    std::string name = payload, tel;
    const size_t semi = payload.find(';');
    if (semi != std::string::npos) {
        name = ndefTrim(payload.substr(0, semi));
        tel = ndefTrim(payload.substr(semi + 1));
    }
    std::string vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:" + name + "\r\n";
    if (!tel.empty()) vcard += "TEL:" + tel + "\r\n";
    vcard += "END:VCARD\r\n";
    return vcard;
}

inline size_t ndefContactRecordSize(const std::string &payload) {
    return NDEF_MIME_RECORD_OVERHEAD + ndefContactVcard(payload).size();
}

} // namespace companion
} // namespace brucia
