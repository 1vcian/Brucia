#!/usr/bin/env python3
"""F35: App Store installer trust and complete-file install.

Compiles the real src/core/app_store_install.cpp against fake download/storage faults
(interrupted, empty, oversized, unsized, HTML, full storage, failed flush/rename, bytes lost after a
"successful" write, rollback refused), runs the real POSIX storage adapter on a temp directory with
fsync/fclose failing, drives the overall download budget with a fake clock (trickling and stalled
servers), compiles the real src/core/app_store_cert_time.cpp against a certificate date matrix
(build-time floor, synced/unset clock, inclusive boundary seconds, expired intermediate), checks the
device adapter source (pinned CA, never setInsecure, dates checked before the body, no Arduino File
whose flush/close drop errors) and the pinned roots themselves.

Limits: the TLS checks use Python's ssl with the pinned roots, not mbedtls on the device, and the
date tests exercise the portable policy, not mbedTLS (which in this SDK checks no dates at all);
the live check against the real store host runs only with BRUCIA_NET_TESTS=1.
"""
import hashlib
import os
import re
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "src/core"
ROOTS_H = CORE / "app_store_roots.h"
SETTINGS = CORE / "settings.cpp"

HARNESS = r'''
#include "app_store_install.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <map>
#include <set>
#include <string>
using namespace app_store;

// Monotonic fake millis(); starts near the uint32 wrap so elapsed-time arithmetic must be unsigned.
struct FakeClock : Clock {
    uint32_t t = 0xFFFFFFFFu - 20000u;
    uint32_t nowMs() override { return t; }
};
static FakeClock gClock;

struct FakeBody : Body {
    long len; std::string data; long cutAt; long errAt;  // cutAt: end of stream early; errAt: timeout
    size_t pos = 0;
    size_t chunk = (size_t)-1;   // most bytes handed out per read (1: a trickling server)
    uint32_t perReadMs = 0;      // time each successful read takes
    uint32_t lastMaxWait = 0; bool waitWithinBudget = true; uint32_t budget = DOWNLOAD_BUDGET_MS; uint32_t start = 0; bool started = false;
    int reads = 0;
    FakeBody(std::string d, long l, long cut = -1, long err = -1) : len(l), data(d), cutAt(cut), errAt(err) {}
    long length() override { return len; }
    long read(uint8_t *b, size_t cap, uint32_t maxWait) override {
        if (!started) { started = true; start = gClock.t; }
        reads++; lastMaxWait = maxWait;
        if (maxWait == 0 || maxWait > budget - (gClock.t - start)) waitWithinBudget = false;
        if (errAt >= 0 && (long)pos >= errAt) {   // stalled: wait like the device adapter (10 s idle cap), then time out
            gClock.t += maxWait < 10000 ? maxWait : 10000; return -1;
        }
        if (cutAt >= 0 && (long)pos >= cutAt) return 0;
        size_t n = std::min(std::min(cap, chunk), data.size() - pos);
        if (cutAt >= 0) n = std::min(n, (size_t)(cutAt - pos));
        if (errAt >= 0) n = std::min(n, (size_t)(errAt - pos));
        if (n == 0) return 0;
        memcpy(b, data.data() + pos, n); pos += n; gClock.t += perReadMs; return (long)n;
    }
};

// The existing cases run with the fake clock; the deadline cases drive it explicitly.
static std::string install(Body &b, Files &fs, const std::string &dest) { return app_store::install(b, fs, dest, gClock); }

struct FakeFs : Files {
    std::map<std::string, std::string> files;
    std::string open; bool isOpen = false;
    long capacity = 1 << 30;         // bytes the storage accepts in total
    bool flushFails = false, sizeLies = false, lostOnFlush = false, readFails = false;
    std::string reading; size_t rpos = 0; bool isReading = false;
    std::set<std::pair<std::string, std::string>> failRename;
    long used() const { long n = 0; for (auto &f : files) n += (long)f.second.size(); return n; }
    bool exists(const std::string &p) override { return files.count(p) > 0; }
    bool remove(const std::string &p) override { return files.erase(p) > 0; }
    bool rename(const std::string &a, const std::string &b) override {
        if (failRename.count({a, b}) || !files.count(a) || files.count(b)) return false;  // FAT: no overwrite
        files[b] = files[a]; files.erase(a); return true;
    }
    bool beginWrite(const std::string &p) override { assert(!isOpen); files[p] = ""; open = p; isOpen = true; return true; }
    size_t write(const uint8_t *d, size_t n) override {
        assert(isOpen);
        long room = capacity - used(); if (room < 0) room = 0;
        size_t w = std::min(n, (size_t)room);
        files[open].append((const char *)d, w); return w;
    }
    bool endWrite() override {
        assert(isOpen); isOpen = false;
        // A sync error the driver swallowed: same size, but the last bytes never reached the medium.
        if (lostOnFlush && !files[open].empty()) files[open].back() ^= 0x20;
        return !flushFails;
    }
    long size(const std::string &p) override { if (!files.count(p)) return -1; return (long)files[p].size() + (sizeLies ? 1 : 0); }
    bool beginRead(const std::string &p) override {
        assert(!isOpen && !isReading); if (!files.count(p)) return false;
        reading = p; rpos = 0; isReading = true; return true;
    }
    long read(uint8_t *b, size_t cap) override {
        assert(isReading); if (readFails) return -1;
        const std::string &d = files[reading]; size_t n = std::min(cap, d.size() - rpos);
        memcpy(b, d.data() + rpos, n); rpos += n; return (long)n;
    }
    void endRead() override { isReading = false; }
};

// The real POSIX adapter, with storage that accepts every write and then fails to sync or close.
struct FailingPosix : PosixFiles {
    bool syncFails = false, closeFails = false;
    using PosixFiles::PosixFiles;
    int syncFd(int fd) override { return syncFails ? -1 : PosixFiles::syncFd(fd); }
    int closeFile(std::FILE *f) override { int r = PosixFiles::closeFile(f); return closeFails ? -1 : r; }
};
static std::string slurp(const std::string &path) {
    std::FILE *f = std::fopen(path.c_str(), "rb"); if (!f) return "<missing>";
    std::string d; char b[512]; size_t n; while ((n = std::fread(b, 1, sizeof b, f)) > 0) d.append(b, n);
    std::fclose(f); return d;
}
static void put(const std::string &path, const std::string &d) {
    std::FILE *f = std::fopen(path.c_str(), "wb"); std::fwrite(d.data(), 1, d.size(), f); std::fclose(f);
}
static bool there(const std::string &path) { std::FILE *f = std::fopen(path.c_str(), "rb"); if (f) std::fclose(f); return f != nullptr; }

static const std::string DEST = "/BruceJS/Tools/App Store.js";
static const std::string PART = DEST + ".part", BAK = DEST + ".bak";
static std::string script(size_t n) { std::string s = "function e(){}"; while (s.size() < n) s += "x"; s.resize(n); return s; }

static bool recoveryRequired(const std::string &e) { return e.rfind(RECOVERY_REQUIRED, 0) == 0 && e.find(".bak") != std::string::npos; }

#define CHECK(c) do { if (!(c)) { printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)
#define CASE(name) printf("case %s\n", name)

// After any failure: no staging file, previous install (if any) untouched, never left open.
static bool clean(FakeFs &fs, const char *old) {
    if (fs.exists(PART) || fs.isOpen) return false;
    if (old) return fs.exists(DEST) && fs.files[DEST] == old && !fs.exists(BAK);
    return !fs.exists(DEST);
}

int main(int argc, char **argv) {
    const std::string s = script(10821);
    { CASE("fresh install");
      FakeFs fs; FakeBody b(s, (long)s.size());
      CHECK(install(b, fs, DEST) == ""); CHECK(fs.files[DEST] == s); CHECK(!fs.exists(PART) && !fs.exists(BAK)); }
    { CASE("update replaces previous, backup removed");
      FakeFs fs; fs.files[DEST] = "old"; FakeBody b(s, (long)s.size());
      CHECK(install(b, fs, DEST) == ""); CHECK(fs.files[DEST] == s); CHECK(!fs.exists(BAK)); }
    for (const char *old : {(const char *)nullptr, "old"}) {
      { CASE("interrupted: stream ends early"); FakeFs fs; if (old) fs.files[DEST] = old; FakeBody b(s, (long)s.size(), 4000);
        CHECK(install(b, fs, DEST) == "download interrupted"); CHECK(clean(fs, old)); }
      { CASE("interrupted: read timeout"); FakeFs fs; if (old) fs.files[DEST] = old; FakeBody b(s, (long)s.size(), -1, 512);
        CHECK(install(b, fs, DEST) == "download interrupted"); CHECK(clean(fs, old)); }
      { CASE("no content-length"); FakeFs fs; if (old) fs.files[DEST] = old; FakeBody b(s, -1);
        CHECK(install(b, fs, DEST) != ""); CHECK(clean(fs, old)); }
      { CASE("empty"); FakeFs fs; if (old) fs.files[DEST] = old; FakeBody b("", 0);
        CHECK(install(b, fs, DEST) == "empty download"); CHECK(clean(fs, old)); }
      { CASE("blank body"); FakeFs fs; if (old) fs.files[DEST] = old; FakeBody b("   \n", 4);
        CHECK(install(b, fs, DEST) == "download is blank"); CHECK(clean(fs, old)); }
      { CASE("oversized refused before writing"); FakeFs fs; if (old) fs.files[DEST] = old;
        std::string big = script(MAX_SCRIPT_BYTES + 1); FakeBody b(big, (long)big.size());
        CHECK(install(b, fs, DEST) == "download too large"); CHECK(clean(fs, old)); CHECK(b.pos == 0); }
      { CASE("html error page"); FakeFs fs; if (old) fs.files[DEST] = old; std::string h = "\n <html>nope</html>"; FakeBody b(h, (long)h.size());
        CHECK(install(b, fs, DEST) == "server sent a web page, not a script"); CHECK(clean(fs, old)); }
      { CASE("storage full mid-write"); FakeFs fs; if (old) fs.files[DEST] = old; fs.capacity = fs.used() + 3000; FakeBody b(s, (long)s.size());
        CHECK(install(b, fs, DEST) == "storage write failed (full?)"); CHECK(clean(fs, old)); }
      { CASE("storage full: zero bytes accepted"); FakeFs fs; if (old) fs.files[DEST] = old; fs.capacity = fs.used(); FakeBody b(s, (long)s.size());
        CHECK(install(b, fs, DEST) == "storage write failed (full?)"); CHECK(clean(fs, old)); }
      { CASE("flush fails"); FakeFs fs; if (old) fs.files[DEST] = old; fs.flushFails = true; FakeBody b(s, (long)s.size());
        CHECK(install(b, fs, DEST) != ""); CHECK(clean(fs, old)); }
      { CASE("size re-read mismatch"); FakeFs fs; if (old) fs.files[DEST] = old; fs.sizeLies = true; FakeBody b(s, (long)s.size());
        CHECK(install(b, fs, DEST) == "written size does not match download"); CHECK(clean(fs, old)); }
      { CASE("bytes lost although every write and the flush reported success"); FakeFs fs; if (old) fs.files[DEST] = old; fs.lostOnFlush = true; FakeBody b(s, (long)s.size());
        CHECK(install(b, fs, DEST) == "stored script differs from download"); CHECK(clean(fs, old)); CHECK(!fs.isReading); }
      { CASE("read back fails"); FakeFs fs; if (old) fs.files[DEST] = old; fs.readFails = true; FakeBody b(s, (long)s.size());
        CHECK(install(b, fs, DEST) == "stored script differs from download"); CHECK(clean(fs, old)); CHECK(!fs.isReading); }
      { CASE("final rename fails"); FakeFs fs; if (old) fs.files[DEST] = old; fs.failRename.insert({PART, DEST}); FakeBody b(s, (long)s.size());
        CHECK(install(b, fs, DEST) == "cannot install downloaded script"); CHECK(clean(fs, old)); }
    }
    { CASE("previous cannot be moved aside"); FakeFs fs; fs.files[DEST] = "old"; fs.failRename.insert({DEST, BAK}); FakeBody b(s, (long)s.size());
      CHECK(install(b, fs, DEST) == "cannot move previous install aside"); CHECK(clean(fs, "old")); }
    { CASE("stale staging file is replaced"); FakeFs fs; fs.files[PART] = "junk"; FakeBody b(s, (long)s.size());
      CHECK(install(b, fs, DEST) == ""); CHECK(fs.files[DEST] == s && !fs.exists(PART)); }
    { CASE("crash between renames: backup restored, then a failed retry keeps it");
      FakeFs fs; fs.files[BAK] = "old"; FakeBody b(s, (long)s.size(), 100);
      CHECK(install(b, fs, DEST) == "download interrupted"); CHECK(clean(fs, "old")); }
    { CASE("final rename and rollback both fail: recovery required, old bytes kept in .bak");
      FakeFs fs; fs.files[DEST] = "old"; fs.failRename.insert({PART, DEST}); fs.failRename.insert({BAK, DEST});
      FakeBody b(s, (long)s.size());
      const std::string e = install(b, fs, DEST);
      CHECK(recoveryRequired(e)); CHECK(!fs.exists(DEST)); CHECK(fs.files[BAK] == "old"); CHECK(!fs.exists(PART));
      // The next attempt puts the old script back before anything else, even when it fails again.
      fs.failRename.clear(); FakeBody again(s, (long)s.size(), 100);
      CHECK(install(again, fs, DEST) == "download interrupted"); CHECK(clean(fs, "old")); }
    { CASE("leftover backup that cannot be restored: recovery required, nothing downloaded");
      FakeFs fs; fs.files[BAK] = "old"; fs.failRename.insert({BAK, DEST}); FakeBody b(s, (long)s.size());
      CHECK(recoveryRequired(install(b, fs, DEST))); CHECK(b.pos == 0); CHECK(fs.files[BAK] == "old"); CHECK(!fs.exists(DEST)); }

    // Overall download budget (DOWNLOAD_BUDGET_MS): a trickling server cannot block the UI for long.
    for (const char *old : {(const char *)nullptr, "old"}) {
      { CASE("deadline: trickling server, one byte per second"); FakeFs fs; if (old) fs.files[DEST] = old;
        FakeBody b(s, (long)s.size()); b.chunk = 1; b.perReadMs = 1000;
        CHECK(install(b, fs, DEST) == TOO_SLOW); CHECK(clean(fs, old));
        CHECK(b.reads == 60); CHECK(b.waitWithinBudget); }
      { CASE("deadline: stall after trickling, the read wait is cut to the rest of the budget"); FakeFs fs; if (old) fs.files[DEST] = old;
        FakeBody b(s, (long)s.size(), -1, 55); b.chunk = 1; b.perReadMs = 1000;
        CHECK(install(b, fs, DEST) == TOO_SLOW); CHECK(clean(fs, old));
        CHECK(b.lastMaxWait == 5000); CHECK(b.waitWithinBudget); }
    }
    { CASE("deadline: normal body finishes well inside the budget"); FakeFs fs; fs.files[DEST] = "old";
      FakeBody b(s, (long)s.size()); b.perReadMs = 20;
      CHECK(install(b, fs, DEST) == ""); CHECK(fs.files[DEST] == s); CHECK(b.waitWithinBudget); }
    { CASE("deadline: slow but complete just inside the budget (last read at 59 s)"); FakeFs fs;
      std::string s60 = script(60); FakeBody b(s60, 60); b.chunk = 1; b.perReadMs = 1000;
      CHECK(install(b, fs, DEST) == ""); CHECK(fs.files[DEST] == s60); }
    { CASE("deadline: one more byte needs a read at exactly 60 s"); FakeFs fs;
      std::string s61 = script(61); FakeBody b(s61, 61); b.chunk = 1; b.perReadMs = 1000;
      CHECK(install(b, fs, DEST) == TOO_SLOW); CHECK(clean(fs, nullptr)); CHECK(b.reads == 60); }
    { CASE("deadline: short stall stays an interruption, not a timeout"); FakeFs fs;
      FakeBody b(s, (long)s.size(), -1, 512);
      CHECK(install(b, fs, DEST) == "download interrupted"); CHECK(b.lastMaxWait == DOWNLOAD_BUDGET_MS); }
    { CASE("deadline: custom budget is honoured"); FakeFs fs;
      FakeBody b(s, (long)s.size()); b.chunk = 100; b.perReadMs = 1000; b.budget = 5000;
      CHECK(app_store::install(b, fs, DEST, gClock, 5000) == TOO_SLOW); CHECK(b.reads == 5); CHECK(b.waitWithinBudget); }

    // Real POSIX adapter on a temp directory (argv[1]); DEST's directory is created by the caller.
    const std::string root = argc > 1 ? argv[1] : "";
    if (!root.empty()) {
        { CASE("posix: fresh install, then update");
          FailingPosix fs(root); FakeBody b(s, (long)s.size());
          CHECK(install(b, fs, DEST) == ""); CHECK(slurp(root + DEST) == s);
          std::string s2 = script(5000); s2[0] = 'v'; FakeBody b2(s2, (long)s2.size());
          CHECK(install(b2, fs, DEST) == ""); CHECK(slurp(root + DEST) == s2);
          CHECK(!there(root + PART) && !there(root + BAK)); }
        for (int mode = 0; mode < 2; mode++) {
          CASE(mode == 0 ? "posix: fsync fails after every write succeeded" : "posix: fclose fails after every write succeeded");
          put(root + DEST, "old");
          FailingPosix fs(root); (mode == 0 ? fs.syncFails : fs.closeFails) = true; FakeBody b(s, (long)s.size());
          CHECK(install(b, fs, DEST) == "storage flush failed (full?)");
          CHECK(slurp(root + DEST) == "old"); CHECK(!there(root + PART) && !there(root + BAK));
        }
        { CASE("posix: missing directory"); FailingPosix fs(root + "/nope"); FakeBody b(s, (long)s.size());
          CHECK(install(b, fs, DEST) == "cannot create staging file"); }
    }
    puts("ALL OK");
    return 0;
}
'''


def compiler():
    return shutil.which("c++") or shutil.which("clang++") or shutil.which("g++")


CERT_HARNESS = r"""
#include "app_store_cert_time.h"
#include <cstdio>
#include <string>
using namespace app_store;

#define CHECK(c) do { if (!(c)) { printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)
#define CASE(name) printf("case %s\n", name)

static CertTime at(int64_t u) {  // inverse of toUnix for test data (civil_from_days)
    int64_t z = (u >= 0 ? u : u - 86399) / 86400, s = u - z * 86400;
    z += 719468; int64_t era = (z >= 0 ? z : z - 146096) / 146097; int64_t doe = z - era * 146097;
    int64_t yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365; int64_t y = yoe + era * 400;
    int64_t doy = doe - (365 * yoe + yoe / 4 - yoe / 100); int64_t mp = (5 * doy + 2) / 153;
    int d = (int)(doy - (153 * mp + 2) / 5 + 1), m = (int)(mp < 10 ? mp + 3 : mp - 9);
    return {(int)(y + (m <= 2)), m, d, (int)(s / 3600), (int)(s / 60 % 60), (int)(s % 60)};
}
static CertValidity cert(int64_t from, int64_t to) { return {at(from), at(to)}; }
static DateCheck one(int64_t from, int64_t to, const TimePolicy &p) { CertValidity c = cert(from, to); return checkCertificateDates(&c, 1, p); }

struct Known { CertTime t; long long unix; };
static const Known KNOWN[] = { @KNOWN@ };

int main() {
    const int64_t DAY = 86400;
    { CASE("toUnix matches calendar.timegm");
      for (const Known &k : KNOWN) { int64_t u; CHECK(toUnix(k.t, u)); CHECK(u == k.unix); CertTime r = at(u);
        CHECK(r.year == k.t.year && r.mon == k.t.mon && r.day == k.t.day && r.hour == k.t.hour && r.min == k.t.min && r.sec == k.t.sec); } }
    { CASE("toUnix rejects impossible dates"); int64_t u;
      CHECK(!toUnix({2100, 2, 29, 0, 0, 0}, u)); CHECK(!toUnix({2026, 13, 1, 0, 0, 0}, u)); CHECK(!toUnix({2026, 4, 31, 0, 0, 0}, u));
      CHECK(!toUnix({2026, 1, 1, 24, 0, 0}, u)); CHECK(!toUnix({2026, 1, 1, 0, 60, 0}, u)); CHECK(!toUnix({2026, 1, 1, 0, 0, 60}, u));
      CHECK(!toUnix({0, 0, 0, 0, 0, 0}, u)); CHECK(toUnix({2000, 2, 29, 0, 0, 0}, u)); }
    { CASE("build time parsing"); int64_t u;
      CHECK(parseIsoUtc("2026-11-10T21:23:19Z", u) && u == 1794345799LL);
      CHECK(!parseIsoUtc("unknown", u)); CHECK(!parseIsoUtc(nullptr, u)); CHECK(!parseIsoUtc("2026-11-10T21:23:19", u));
      CHECK(!parseIsoUtc("2026-11-10T21:23:19Z ", u)); CHECK(!parseIsoUtc("2026-13-10T21:23:19Z", u)); CHECK(!parseIsoUtc("2026-11-10 21:23:19Z", u)); }

    const char *BUILD = "2026-11-10T21:23:19Z"; const int64_t F = 1794345799LL;
    { CASE("policy: floor from build time, never below the source floor, clock only when plausible");
      TimePolicy p = timePolicy(BUILD, false, 0); CHECK(p.floor == F && p.now == F && !p.clockValid);
      p = timePolicy("unknown", false, 0); CHECK(p.floor == SOURCE_TIME_FLOOR && p.now == SOURCE_TIME_FLOOR);
      p = timePolicy("2001-01-01T00:00:00Z", false, 0); CHECK(p.floor == SOURCE_TIME_FLOOR);
      p = timePolicy(BUILD, true, F + 100 * DAY); CHECK(p.clockValid && p.now == F + 100 * DAY);
      p = timePolicy(BUILD, true, F - 1); CHECK(!p.clockValid && p.now == F);           // clock behind the source: wrong
      p = timePolicy(BUILD, true, F); CHECK(p.clockValid && p.now == F);
      p = timePolicy(BUILD, true, F + MAX_CLOCK_AHEAD_OF_FLOOR + 1); CHECK(!p.clockValid && p.now == F);
      p = timePolicy(BUILD, false, F + 100 * DAY); CHECK(!p.clockValid && p.now == F); }  // not synced: ignored

    const TimePolicy unset = timePolicy(BUILD, false, 0);
    const int64_t C = F + 40 * DAY; const TimePolicy synced = timePolicy(BUILD, true, C);
    { CASE("cold boot, clock unset: certificate expired before the firmware's source time is rejected");
      DateCheck r = one(F - 400 * DAY, F - 10 * DAY, unset); CHECK(r.error == "server certificate has expired"); CHECK(!r.notBeforeChecked); }
    { CASE("valid certificate, clock unset: accepted, start date flagged as not checked");
      DateCheck r = one(F - 30 * DAY, F + 60 * DAY, unset); CHECK(r.error.empty()); CHECK(!r.notBeforeChecked); }
    { CASE("valid certificate, synced clock: accepted, start date checked");
      DateCheck r = one(C - 30 * DAY, C + 60 * DAY, synced); CHECK(r.error.empty()); CHECK(r.notBeforeChecked); }
    { CASE("not yet valid, synced clock: rejected");
      DateCheck r = one(C + 3600, C + 90 * DAY, synced); CHECK(r.error == "server certificate is not valid yet"); }
    { CASE("not yet valid, clock unset: accepted but flagged");
      DateCheck r = one(F + 3600, F + 90 * DAY, unset); CHECK(r.error.empty()); CHECK(!r.notBeforeChecked); }
    { CASE("expired after the source time: rejected with a synced clock, accepted (limit) with it unset");
      CHECK(one(F - 60 * DAY, F + 10 * DAY, synced).error == "server certificate has expired");
      CHECK(one(F - 60 * DAY, F + 10 * DAY, unset).error.empty()); }
    { CASE("boundary seconds: notAfter inclusive (floor and clock)");
      CHECK(one(F - DAY, F, unset).error.empty()); CHECK(one(F - DAY, F - 1, unset).error == "server certificate has expired");
      CHECK(one(C - DAY, C, synced).error.empty()); CHECK(one(C - DAY, C - 1, synced).error == "server certificate has expired"); }
    { CASE("boundary seconds: notBefore inclusive with a synced clock");
      CHECK(one(C, C + DAY, synced).error.empty()); CHECK(one(C + 1, C + DAY, synced).error == "server certificate is not valid yet"); }
    { CASE("expired intermediate in the presented chain: rejected");
      CertValidity chain[] = {cert(F - 10 * DAY, F + 80 * DAY), cert(F - 900 * DAY, F - DAY), cert(F - 3000 * DAY, F + 3000 * DAY)};
      CHECK(checkCertificateDates(chain, 3, unset).error == "chain certificate 1 has expired");
      CHECK(checkCertificateDates(chain, 3, synced).error == "chain certificate 1 has expired");
      chain[1] = cert(F - 900 * DAY, F + 900 * DAY); CHECK(checkCertificateDates(chain, 3, synced).error.empty()); }
    { CASE("not-yet-valid intermediate with a synced clock: rejected");
      CertValidity chain[] = {cert(C - DAY, C + DAY), cert(C + 60, C + 900 * DAY)};
      CHECK(checkCertificateDates(chain, 2, synced).error == "chain certificate 1 is not valid yet"); }
    { CASE("no certificate or an unreadable date: rejected");
      CHECK(checkCertificateDates(nullptr, 0, synced).error == "server sent no certificate");
      CertValidity bad = {{2026, 2, 30, 0, 0, 0}, at(F + DAY)};
      CHECK(checkCertificateDates(&bad, 1, synced).error == "server certificate has an unreadable validity date"); }
    puts("ALL OK");
    return 0;
}
"""


def known_dates():
    import calendar
    import random
    rnd = random.Random(35)
    dates = [(1970, 1, 1, 0, 0, 0), (1999, 12, 31, 23, 59, 59), (2000, 2, 29, 12, 0, 0), (2024, 2, 29, 0, 0, 0),
             (2026, 11, 10, 0, 0, 0), (2036, 2, 7, 6, 28, 16), (2038, 1, 19, 3, 14, 8), (2050, 1, 1, 0, 0, 0),
             (2099, 12, 31, 23, 59, 59), (1950, 1, 1, 0, 0, 0)]
    for _ in range(200):
        y = rnd.randint(1950, 2200)
        m = rnd.randint(1, 12)
        d = rnd.randint(1, calendar.monthrange(y, m)[1])
        dates.append((y, m, d, rnd.randint(0, 23), rnd.randint(0, 59), rnd.randint(0, 59)))
    return ", ".join("{{%d, %d, %d, %d, %d, %d}, %dLL}" % (*t, calendar.timegm(t)) for t in dates)


class CertificateDateTest(unittest.TestCase):
    """Portable date policy only. It is not evidence about mbedTLS (which here checks no dates at all)."""

    @unittest.skipUnless(compiler(), "no host C++ compiler")
    def test_certificate_date_policy(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "cert.cpp"
            exe = Path(tmp) / "cert"
            src.write_text(CERT_HARNESS.replace("@KNOWN@", known_dates()))
            build = subprocess.run([compiler(), "-std=c++17", "-Wall", "-Werror", "-I", str(CORE), str(src),
                                    str(CORE / "app_store_cert_time.cpp"), "-o", str(exe)], capture_output=True, text=True)
            self.assertEqual(0, build.returncode, build.stderr)
            run = subprocess.run([str(exe)], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, run.returncode, run.stdout + run.stderr)
            self.assertIn("ALL OK", run.stdout)
            self.assertIn("case expired intermediate", run.stdout)

    def test_sdk_does_not_check_certificate_dates(self):
        # Why the installer checks dates itself: without CONFIG_MBEDTLS_HAVE_TIME_DATE, mbedTLS
        # compares no validity dates. Skipped when the PlatformIO packages are not installed.
        libs = Path.home() / ".platformio/packages/framework-arduinoespressif32-libs/esp32s3"
        configs = sorted(libs.glob("*/include/sdkconfig.h"))
        if not configs:
            self.skipTest("ESP32-S3 SDK libs not installed")
        for cfg in configs:
            text = cfg.read_text()
            self.assertIn("#define CONFIG_MBEDTLS_HAVE_TIME 1", text, cfg)
            self.assertNotIn("CONFIG_MBEDTLS_HAVE_TIME_DATE", text, cfg)


class InstallLogicTest(unittest.TestCase):
    @unittest.skipUnless(compiler(), "no host C++ compiler")
    def test_faults_never_report_success_or_damage_previous_install(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "harness.cpp"
            exe = Path(tmp) / "harness"
            src.write_text(HARNESS)
            build = subprocess.run([compiler(), "-std=c++17", "-Wall", "-Werror", "-I", str(CORE), str(src),
                                    str(CORE / "app_store_install.cpp"), "-o", str(exe)], capture_output=True, text=True)
            self.assertEqual(0, build.returncode, build.stderr)
            storage = Path(tmp) / "storage"
            (storage / "BruceJS/Tools").mkdir(parents=True)
            run = subprocess.run([str(exe), str(storage)], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, run.returncode, run.stdout + run.stderr)
            self.assertIn("ALL OK", run.stdout)
            self.assertIn("case posix: fsync fails", run.stdout)
        self.assertIn("case deadline: trickling server", run.stdout)


class AdapterSourceTest(unittest.TestCase):
    def setUp(self):
        text = SETTINGS.read_text()
        self.fn = text[text.index("void installAppStoreJS() {"):]
        self.fn = self.fn[:self.fn.index("\n}\n")]
        self.file = text

    def test_download_is_authenticated_with_the_pinned_roots(self):
        self.assertIn("tls.setCACert(BRUCIA_APP_STORE_ROOTS)", self.fn)
        self.assertIn("http.begin(tls, BRUCIA_UPSTREAM_APP_STORE_URL)", self.fn)
        self.assertIsNone(re.search(r"\.setInsecure\s*\(", self.file))
        # One-argument begin(url) is the path that ends in setInsecure() in the Arduino core.
        self.assertIsNone(re.search(r"http\.begin\(\s*BRUCIA_UPSTREAM_APP_STORE_URL\s*\)", self.fn))

    def test_install_goes_through_the_checked_path(self):
        self.assertIn("http.useHTTP10(true)", self.fn)
        self.assertNotIn("getString()", self.fn)
        self.assertNotIn("file.print", self.fn)
        ok = self.fn.index("displaySuccess(\"Bruce App Store installed\"")
        self.assertLess(self.fn.index("if (!err.empty())"), ok)

    def test_storage_adapter_reports_sync_errors(self):
        # Arduino File.flush()/close() discard fflush/fsync/fclose results (vfs_api.cpp), so a
        # File-based adapter cannot see a failed sync: the installer must use the POSIX adapter.
        self.assertIn("app_store::PosixFiles files(fs->mountpoint());", self.fn)
        self.assertNotIn("getWriteError", self.file)
        self.assertNotIn("struct FsFiles", self.file)
        impl = (CORE / "app_store_install.cpp").read_text()
        end = impl[impl.index("bool PosixFiles::endWrite()"):]
        end = end[:end.index("\n}\n")]
        for call in ("std::fflush(out_) != 0", "syncFd(fileno(out_)) != 0", "closeFile(out_) != 0"):
            self.assertIn(call, end)

    def test_certificate_dates_checked_before_any_body_byte(self):
        check = self.fn.index("checkPeerCertificateDates(tls)")
        self.assertLess(self.fn.index("http.GET()"), check)
        self.assertLess(check, self.fn.index("HttpBody body(http)"))
        self.assertLess(check, self.fn.index("app_store::install("))
        guard = self.fn[check:self.fn.index("HttpBody body(http)")]
        self.assertIn("if (!dates.error.empty())", guard)
        self.assertIn("return;", guard)
        # Every presented certificate, both dates, floor from the build provenance header.
        helper = self.file[self.file.index("app_store::DateCheck checkPeerCertificateDates("):]
        helper = helper[:helper.index("\n}\n")]
        for piece in ("tls.getPeerCertificate()", "crt = crt->next", "crt->valid_from", "crt->valid_to",
                      "app_store::timePolicy(BRUCIA_BUILD_TIME, synced, utc)", "app_store::checkCertificateDates("):
            self.assertIn(piece, helper)
        self.assertIn('#include "brucia_build_info.h"', self.file)
        # Start date not checked must be said, never implied as full validity checking.
        self.assertIn("if (!dates.notBeforeChecked)", self.fn)

    def test_download_has_an_overall_budget(self):
        self.assertIn("app_store::install(body, files, \"/BruceJS/Tools/App Store.js\", clock)", self.fn)
        body = self.file[self.file.index("struct HttpBody : app_store::Body {"):]
        body = body[:body.index("\n};\n")]
        self.assertIn("long read(uint8_t *buf, size_t cap, uint32_t maxWaitMs) override", body)
        self.assertIn("maxWaitMs < 10000 ? maxWaitMs : 10000", body)
        self.assertIn("millis() - start < wait", body)
        self.assertIn("tls.setHandshakeTimeout(", self.fn)

    def test_recovery_required_is_shown_as_an_error(self):
        rec = self.fn.index("err.rfind(app_store::RECOVERY_REQUIRED, 0) == 0")
        self.assertLess(rec, self.fn.index("if (!err.empty())"))
        self.assertIn("displayError(", self.fn[rec:self.fn.index("if (!err.empty())")])


def pinned_pems():
    text = ROOTS_H.read_text()
    body = text[text.index('R"PEM(') + 6:text.index(')PEM"')]
    return re.findall(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", body, re.S), body


class PinnedRootsTest(unittest.TestCase):
    def test_every_pinned_root_matches_its_documented_fingerprint(self):
        pems, _ = pinned_pems()
        documented = re.findall(r"^// (.+): ([0-9a-f]{64})$", ROOTS_H.read_text(), re.M)
        self.assertEqual(9, len(pems))
        self.assertEqual(len(documented), len(pems))
        for pem, (name, fp) in zip(pems, documented):
            self.assertEqual(fp, hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest(), name)

    @unittest.skipUnless(shutil.which("openssl"), "openssl not installed")
    def test_server_with_untrusted_certificate_is_rejected(self):
        _, bundle = pinned_pems()
        with tempfile.TemporaryDirectory() as tmp:
            key, crt, ca = (Path(tmp) / n for n in ("k.pem", "c.pem", "ca.pem"))
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                            "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
                            "-keyout", str(key), "-out", str(crt)], check=True, capture_output=True)
            ca.write_text(bundle)
            server_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            server_ctx.load_cert_chain(str(crt), str(key))
            listener = socket.socket()
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            port = listener.getsockname()[1]

            def serve():
                try:
                    conn, _ = listener.accept()
                    with server_ctx.wrap_socket(conn, server_side=True):
                        pass
                except (ssl.SSLError, OSError):
                    pass
            t = threading.Thread(target=serve, daemon=True)
            t.start()
            client_ctx = ssl.create_default_context(cafile=str(ca))
            with self.assertRaises(ssl.SSLCertVerificationError):
                with socket.create_connection(("127.0.0.1", port), timeout=5) as sock:
                    with client_ctx.wrap_socket(sock, server_hostname="localhost"):
                        pass
            listener.close()
            t.join(5)

    @unittest.skipUnless(os.environ.get("BRUCIA_NET_TESTS") == "1", "set BRUCIA_NET_TESTS=1 for live TLS checks")
    def test_live_store_host_validates_only_against_the_pinned_roots(self):
        _, bundle = pinned_pems()
        ctx = ssl.create_default_context(cadata=bundle)
        host = "ghp.iceis.co.uk"
        with socket.create_connection((host, 443), timeout=15) as sock:
            with ctx.wrap_socket(sock, server_hostname=host) as tls:
                self.assertTrue(tls.getpeercert())
        for bad in ("self-signed.badssl.com", "expired.badssl.com", "wrong.host.badssl.com", "untrusted-root.badssl.com"):
            with self.assertRaises(ssl.SSLError, msg=bad):
                with socket.create_connection((bad, 443), timeout=15) as sock:
                    with ctx.wrap_socket(sock, server_hostname=bad):
                        pass


if __name__ == "__main__":
    unittest.main()
