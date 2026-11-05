#pragma once
// Arduino-free recovery diagnostics core (see proto/PROTOCOL.md "Recovery diagnostics").
// Fixed-size, in-RAM companion link counters since boot plus the pure mapping helpers used by
// the `diag` command. Nothing here allocates, logs or transmits: the counters are read only when
// the app sends `diag`. Host-tested by tools/test_firmware_diag.py.
#include <stddef.h>
#include <stdint.h>

namespace brucia {
namespace companion {

static const uint32_t DIAG_REPLY_VERSION = 1;

// Every counter saturates at UINT32_MAX (it never wraps back to a small value). A value equal to
// UINT32_MAX therefore means "at least this many".
inline void diagInc(uint32_t &counter) {
    if (counter != UINT32_MAX) ++counter;
}

// ERR replies sent, grouped by status code.
enum DiagErrBucket : uint8_t {
    DIAG_ERR_400 = 0,
    DIAG_ERR_404,
    DIAG_ERR_409,
    DIAG_ERR_413,
    DIAG_ERR_4XX, // any other 4xx
    DIAG_ERR_5XX,
    DIAG_ERR_OTHER, // < 400 or >= 600
    DIAG_ERR_BUCKETS
};

inline DiagErrBucket diagErrBucket(uint32_t code) {
    switch (code) {
        case 400: return DIAG_ERR_400;
        case 404: return DIAG_ERR_404;
        case 409: return DIAG_ERR_409;
        case 413: return DIAG_ERR_413;
        default: break;
    }
    if (code >= 400 && code < 500) return DIAG_ERR_4XX;
    if (code >= 500 && code < 600) return DIAG_ERR_5XX;
    return DIAG_ERR_OTHER;
}

inline const char *diagErrBucketKey(uint8_t bucket) {
    static const char *const keys[DIAG_ERR_BUCKETS] = {"400", "404", "409", "413", "4xx", "5xx", "other"};
    return bucket < DIAG_ERR_BUCKETS ? keys[bucket] : "other";
}

// Incoming frames the companion decoder rejected. None of these reach a command handler.
enum DiagRxReject : uint8_t {
    DIAG_RX_ZERO_LEN = 0, // length prefix 0
    DIAG_RX_OVERSIZE,     // length prefix > COMPANION_MAX_FRAME
    DIAG_RX_ALLOC_FAIL,   // valid length, payload buffer could not be reserved
    DIAG_RX_STALE,        // partial frame abandoned after 2 s without bytes
    DIAG_RX_BAD_JSON,     // complete frame whose payload is not valid JSON
    DIAG_RX_REJECT_KINDS
};

inline const char *diagRxRejectKey(uint8_t kind) {
    static const char *const keys[DIAG_RX_REJECT_KINDS] = {
        "rx_zero_len", "rx_oversize", "rx_alloc_fail", "rx_stale", "rx_bad_json"};
    return kind < DIAG_RX_REJECT_KINDS ? keys[kind] : "rx_other";
}

struct DiagLinkCounters {
    uint32_t sessions;  // companion mode entries
    uint32_t rxFrames;  // complete frames read by the decoder (valid length, before JSON parsing)
    uint32_t txFrames;  // frames written (every kind)
    uint32_t busy409;   // ERR 409 "device busy" replies from companionPollStop (also in err["409"])
    uint32_t rxRejected[DIAG_RX_REJECT_KINDS];
    uint32_t err[DIAG_ERR_BUCKETS];
};

// The single process-wide instance (C++17 inline function: one object across translation units).
// Zero-initialized static storage; no constructor runs and nothing is allocated.
inline DiagLinkCounters &diagLink() {
    static DiagLinkCounters counters;
    return counters;
}

#if defined(LITE_VERSION)
// LITE builds compile `diag` out for app-slot headroom (src/companion/config.h COMPANION_HAS_DIAG):
// the hooks below are no-ops, nothing reads the counters and CAPS does not advertise features.diag.
inline void diagNoteSession() {}
inline void diagNoteRxFrame() {}
inline void diagNoteTxFrame() {}
inline void diagNoteBusy() {}
inline void diagNoteRxReject(DiagRxReject) {}
inline void diagNoteErr(uint32_t) {}
#else
inline void diagNoteSession() { diagInc(diagLink().sessions); }
inline void diagNoteRxFrame() { diagInc(diagLink().rxFrames); }
inline void diagNoteTxFrame() { diagInc(diagLink().txFrames); }
inline void diagNoteBusy() { diagInc(diagLink().busy409); }
inline void diagNoteRxReject(DiagRxReject kind) {
    if (kind < DIAG_RX_REJECT_KINDS) diagInc(diagLink().rxRejected[kind]);
}
inline void diagNoteErr(uint32_t code) { diagInc(diagLink().err[diagErrBucket(code)]); }
#endif

// esp_reset_reason_t (ESP-IDF 5.x numbering) as a stable lowercase name. The firmware adapter
// static_asserts that the enum still matches this table; unknown future values map to "other".
inline const char *diagResetReasonName(int code) {
    static const char *const names[] = {
        "unknown", "poweron", "ext", "sw", "panic", "int_wdt", "task_wdt", "wdt",
        "deepsleep", "brownout", "sdio", "usb", "jtag", "efuse", "pwr_glitch", "cpu_lockup"};
    if (code < 0 || code >= (int)(sizeof(names) / sizeof(names[0]))) return "other";
    return names[code];
}

// Emits the `link` object of the diag reply through a sink with
//   void field(const char *key, uint32_t value);     // link.<key>
//   void errField(const char *key, uint32_t value);  // link.err.<key>
// so the firmware (ArduinoJson) and the host test share one key list and order.
template <class Sink> void diagWriteLink(const DiagLinkCounters &c, Sink &sink) {
    sink.field("sessions", c.sessions);
    sink.field("rx_frames", c.rxFrames);
    sink.field("tx_frames", c.txFrames);
    for (uint8_t k = 0; k < DIAG_RX_REJECT_KINDS; k++) sink.field(diagRxRejectKey(k), c.rxRejected[k]);
    sink.field("busy_409", c.busy409);
    for (uint8_t b = 0; b < DIAG_ERR_BUCKETS; b++) sink.errField(diagErrBucketKey(b), c.err[b]);
}

} // namespace companion
} // namespace brucia
