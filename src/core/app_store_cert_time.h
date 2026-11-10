#pragma once
// Certificate validity dates for the App Store bootstrap download (F35). Portable (no Arduino or
// mbedTLS types) so tools/test_app_store_install.py runs the same code on the host.
//
// Why this exists: the prebuilt ESP-IDF SDK defines CONFIG_MBEDTLS_HAVE_TIME but not
// CONFIG_MBEDTLS_HAVE_TIME_DATE (sdkconfig.h of framework-arduinoespressif32-libs), so mbedTLS on
// the device verifies the chain to a pinned root but never compares notBefore/notAfter with a
// date. The installer adapter converts the dates of every certificate the server presented and
// calls checkCertificateDates() after the handshake, before any body byte is accepted.
//
// Time policy. The device clock is not trusted: at cold boot it holds a default date (which can
// even be ahead of real time), Bruce writes local time, not UTC, into it, and NVS/manual/companion
// settings are unauthenticated. The effective "now" is therefore
//     max(fresh UTC clock if synced and plausible, build-time floor)
// where the floor is the firmware's source time (BRUCIA_BUILD_TIME: SOURCE_DATE_EPOCH or commit
// time) and never less than SOURCE_TIME_FLOOR. Real time cannot be earlier than the source the
// firmware was built from, so a certificate that expired before that floor is always rejected,
// even with an unset clock. notAfter is checked against the effective now; notBefore only when a
// synced clock is available (otherwise the result says it was not checked). With no synced clock
// this is NOT full validity checking: a certificate that expired after the floor is accepted.
#include <cstddef>
#include <cstdint>
#include <string>

namespace app_store {

/** Lower bound for the floor when the build carries no usable time: 2026-11-10T00:00:00Z. */
constexpr int64_t SOURCE_TIME_FLOOR = 1794268800LL;
/** A synced clock further than this past the floor is treated as wrong (about 30 years). */
constexpr int64_t MAX_CLOCK_AHEAD_OF_FLOOR = 30LL * 366 * 24 * 3600;

/** A certificate time as stored in X.509 (UTC; month 1-12, day 1-31). */
struct CertTime {
    int year, mon, day, hour, min, sec;
};

struct CertValidity {
    CertTime notBefore, notAfter;
};

/** Seconds since 1970-01-01T00:00:00Z; false if a field is out of range. */
bool toUnix(const CertTime &t, int64_t &out);

/** Parses exactly "YYYY-MM-DDTHH:MM:SSZ"; false otherwise (e.g. "unknown"). */
bool parseIsoUtc(const char *iso, int64_t &out);

struct TimePolicy {
    int64_t floor = SOURCE_TIME_FLOOR; ///< source-time lower bound for real time
    bool clockValid = false;           ///< a synced, plausible UTC clock is available
    int64_t now = SOURCE_TIME_FLOOR;   ///< effective now: clock when valid, else the floor
};

/**
 * Builds the policy from the build time ([buildTimeIso], may be null or "unknown") and an optional
 * synced UTC clock reading. A synced reading earlier than the floor or more than
 * [MAX_CLOCK_AHEAD_OF_FLOOR] after it is wrong and ignored.
 */
TimePolicy timePolicy(const char *buildTimeIso, bool clockSynced, int64_t clockUtc);

struct DateCheck {
    std::string error;             ///< "" when every certificate passed
    bool notBeforeChecked = false; ///< false: start dates were not checked (no trusted clock)
};

/**
 * Checks the [n] certificates the server presented (leaf first). Rejects any whose notAfter is
 * before the effective now, and, only with a valid clock, any whose notBefore is after it. Both
 * bounds are inclusive (RFC 5280): now == notAfter and now == notBefore pass. An empty chain or
 * an unreadable date is rejected.
 */
DateCheck checkCertificateDates(const CertValidity *chain, size_t n, const TimePolicy &policy);

} // namespace app_store
