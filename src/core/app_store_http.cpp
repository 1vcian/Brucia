#if !defined(LITE_VERSION) && !defined(DISABLE_INTERPRETER)
#include "app_store_http.h"
#include <NTPClient.h>
#include <WiFiUdp.h>
#if __has_include("brucia_build_info.h")
#include "brucia_build_info.h"
#endif
#ifndef BRUCIA_BUILD_TIME
#define BRUCIA_BUILD_TIME "unknown"
#endif
extern const char *ntpServer;
namespace app_store {
/**
 * One UTC reading from the NTP server Bruce already uses (own UDP socket and zero offset, so the
 * shared timeClient and its time-zone offset are untouched). The system clock is not used: at cold
 * boot it holds a default date and Bruce sets it to local time. NTP is unauthenticated, so the
 * core only lets this reading raise "now" above the build floor and enable the notBefore check.
 */
bool utcFromNtp(int64_t &utc) {
    WiFiUDP udp;
    NTPClient ntp(udp, ntpServer, 0, 0);
    ntp.begin(49152 + (esp_random() % 16384)); // not the shared client's port
    bool ok = ntp.forceUpdate(); // one request, at most about 1 s; the library checks no reply field
    if (ok) {
        const unsigned long epoch = ntp.getEpochTime();
        // A zero transmit timestamp (Kiss-o'-Death or garbage) comes out as 2036-02-07T06:28:16Z
        // (+ the seconds since the reply), which would make every certificate look expired.
        ok = epoch - 2085978496UL > 60UL;
        utc = (int64_t)epoch;
    }
    ntp.end();
    return ok;
}

/**
 * Validity dates of every certificate the server presented (mbedTLS here does not check them,
 * see app_store_cert_time.h). Empty error: accepted; [notBeforeChecked] says whether start dates
 * were compared with a synced clock.
 */
app_store::DateCheck checkPeerCertificateDates(NetworkClientSecure &tls) {
    constexpr size_t MAX_CHAIN = 8;
    app_store::CertValidity chain[MAX_CHAIN];
    size_t n = 0;
    const mbedtls_x509_crt *crt = tls.getPeerCertificate();
    for (; crt && n < MAX_CHAIN; crt = crt->next, n++) {
        const mbedtls_x509_time &f = crt->valid_from, &t = crt->valid_to;
        chain[n] = {
            {f.year, f.mon, f.day, f.hour, f.min, f.sec},
            {t.year, t.mon, t.day, t.hour, t.min, t.sec}
        };
    }
    if (crt) {
        app_store::DateCheck tooLong;
        tooLong.error = "certificate chain too long to check";
        return tooLong;
    }
    int64_t utc = 0;
    const bool synced = utcFromNtp(utc);
    return app_store::checkCertificateDates(
        chain, n, app_store::timePolicy(BRUCIA_BUILD_TIME, synced, utc)
    );
}

} // namespace app_store
#endif
