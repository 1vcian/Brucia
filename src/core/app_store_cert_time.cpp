#include "app_store_cert_time.h"

namespace app_store {

namespace {
bool leap(int y) { return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0; }

int daysInMonth(int y, int m) {
    static const int days[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    return m == 2 && leap(y) ? 29 : days[m - 1];
}

// Days since 1970-01-01 of a proleptic Gregorian date (H. Hinnant's days_from_civil).
int64_t daysFromCivil(int64_t y, int m, int d) {
    y -= m <= 2;
    const int64_t era = (y >= 0 ? y : y - 399) / 400;
    const int64_t yoe = y - era * 400;
    const int64_t doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
    const int64_t doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + doe - 719468;
}

bool digits(const char *p, int n, int &out) {
    out = 0;
    for (int i = 0; i < n; i++) {
        if (p[i] < '0' || p[i] > '9') return false;
        out = out * 10 + (p[i] - '0');
    }
    return true;
}

std::string where(size_t i) {
    return i == 0 ? std::string("server certificate") : "chain certificate " + std::to_string(i);
}
} // namespace

bool toUnix(const CertTime &t, int64_t &out) {
    // X.509 UTCTime/GeneralizedTime as parsed by mbedTLS: 4-digit year, seconds 0-59.
    if (t.year < 1950 || t.year > 9999 || t.mon < 1 || t.mon > 12) return false;
    if (t.day < 1 || t.day > daysInMonth(t.year, t.mon)) return false;
    if (t.hour < 0 || t.hour > 23 || t.min < 0 || t.min > 59 || t.sec < 0 || t.sec > 59) return false;
    out = daysFromCivil(t.year, t.mon, t.day) * 86400 + t.hour * 3600 + t.min * 60 + t.sec;
    return true;
}

bool parseIsoUtc(const char *iso, int64_t &out) {
    if (!iso) return false;
    for (int i = 0; i < 20; i++) {
        if (!iso[i]) return false;
    }
    if (iso[20] != '\0' || iso[4] != '-' || iso[7] != '-' || iso[10] != 'T' || iso[13] != ':' ||
        iso[16] != ':' || iso[19] != 'Z') {
        return false;
    }
    CertTime t;
    if (!digits(iso, 4, t.year) || !digits(iso + 5, 2, t.mon) || !digits(iso + 8, 2, t.day) ||
        !digits(iso + 11, 2, t.hour) || !digits(iso + 14, 2, t.min) || !digits(iso + 17, 2, t.sec)) {
        return false;
    }
    return toUnix(t, out);
}

TimePolicy timePolicy(const char *buildTimeIso, bool clockSynced, int64_t clockUtc) {
    TimePolicy p;
    int64_t built;
    if (parseIsoUtc(buildTimeIso, built) && built > p.floor) p.floor = built;
    p.clockValid = clockSynced && clockUtc >= p.floor && clockUtc - p.floor <= MAX_CLOCK_AHEAD_OF_FLOOR;
    p.now = p.clockValid ? clockUtc : p.floor;
    return p;
}

DateCheck checkCertificateDates(const CertValidity *chain, size_t n, const TimePolicy &policy) {
    DateCheck r;
    r.notBeforeChecked = policy.clockValid;
    if (!chain || n == 0) {
        r.error = "server sent no certificate";
        return r;
    }
    for (size_t i = 0; i < n; i++) {
        int64_t from, to;
        if (!toUnix(chain[i].notBefore, from) || !toUnix(chain[i].notAfter, to)) {
            r.error = where(i) + " has an unreadable validity date";
            return r;
        }
        if (to < policy.now) {
            r.error = where(i) + " has expired";
            return r;
        }
        if (policy.clockValid && from > policy.now) {
            r.error = where(i) + " is not valid yet";
            return r;
        }
    }
    return r;
}

} // namespace app_store
