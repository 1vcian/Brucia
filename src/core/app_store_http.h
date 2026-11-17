#pragma once
#include "app_store_install.h"
#include "app_store_cert_time.h"
#include <HTTPClient.h>
#include <NetworkClientSecure.h>

namespace app_store {
/** Response body over the authenticated stream; idle-bounded so a stalled server cannot hang the UI. */
struct HttpBody : app_store::Body {
    HTTPClient &http;
    NetworkClient *stream;
    explicit HttpBody(HTTPClient &h) : http(h), stream(h.getStreamPtr()) {}
    long length() override { return http.getSize(); }
    long read(uint8_t *buf, size_t cap, uint32_t maxWaitMs) override {
        if (!stream) return -1;
        // 10 s of idle per read, never past the rest of the overall download budget.
        const uint32_t wait = maxWaitMs < 10000 ? maxWaitMs : 10000;
        const uint32_t start = millis();
        while (millis() - start < wait) {
            const int avail = stream->available();
            if (avail > 0) {
                const int n = stream->read(buf, cap < (size_t)avail ? cap : (size_t)avail);
                return n > 0 ? n : -1;
            }
            if (!stream->connected()) return 0;
            delay(5);
        }
        return -1;
    }
};

struct MillisClock : app_store::Clock {
    uint32_t nowMs() override { return millis(); }
};

DateCheck checkPeerCertificateDates(NetworkClientSecure &tls);
} // namespace app_store
