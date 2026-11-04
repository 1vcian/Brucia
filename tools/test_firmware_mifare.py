#!/usr/bin/env python3
"""Compile the Arduino-free MIFARE key-check core (src/modules/rfid/mifare_keycheck_core.cpp)
on the host with a C++ driver and run it: sector-count/trailer-block maps, a complete scan,
a no-tag result, and a partial scan when the tag is lost mid-batch (reselect fails). No ESP32
or PlatformIO installation required. This covers the completeness accounting the companion
turns into the v1 complete/partial ACK; the PN532 auth primitive itself needs hardware.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "src/modules/rfid"

DRIVER = r'''
#include "mifare_keycheck_core.h"
#include <cstdio>
#include <cstring>
#include <vector>

static int failures = 0, checks = 0;
static void check(bool ok, const char *what) {
    checks++;
    if (!ok) { failures++; std::printf("FAIL: %s\n", what); }
}

// Fake reader. `matches` lists (trailerBlock, keyType, key) tuples that authenticate accepts.
// reselectFailAt = the 1-based reselect() call that returns false (0 = never).
struct FakeBus : MifareAuthBus {
    bool present = true;
    uint8_t uid[10] = {0x04, 0xAA, 0xBB, 0xCC};
    uint8_t uidLen = 4;
    uint8_t sak = MIFARE_SAK_1K;
    int reselectFailAt = 0;
    int reselects = 0, attempts = 0;
    int stopAtAttempt = 0; // when >0, shouldStop() returns true once `attempts` reaches this count
    int stopPolls = 0;
    struct Match { uint8_t trailer; uint8_t kt; uint8_t key[6]; };
    std::vector<Match> matches;

    bool selectTag(uint8_t u[10], uint8_t &ul, uint8_t &s) override {
        if (!present) return false;
        ul = uidLen;
        for (int i = 0; i < uidLen; i++) u[i] = uid[i];
        s = sak;
        return true;
    }
    bool authenticate(uint8_t trailer, uint8_t kt, const uint8_t key[6]) override {
        attempts++;
        for (auto &m : matches)
            if (m.trailer == trailer && m.kt == kt && memcmp(m.key, key, 6) == 0) return true;
        return false;
    }
    bool reselect() override {
        reselects++;
        return reselectFailAt == 0 || reselects < reselectFailAt;
    }
    bool shouldStop() override {
        stopPolls++;
        return stopAtAttempt > 0 && attempts >= stopAtAttempt;
    }
};

int main() {
    // Sector-count map (1K=16, 4K=40, Mini=5, default 16).
    check(mifareSectorCountFromSak(MIFARE_SAK_1K) == 16, "sak 1K -> 16");
    check(mifareSectorCountFromSak(MIFARE_SAK_4K) == 40, "sak 4K -> 40");
    check(mifareSectorCountFromSak(MIFARE_SAK_MINI) == 5, "sak Mini -> 5");
    check(mifareSectorCountFromSak(0x20) == 16, "sak other -> 16");

    // Sector trailer blocks.
    check(mifareSectorTrailerBlock(0) == 3, "trailer sector 0");
    check(mifareSectorTrailerBlock(1) == 7, "trailer sector 1");
    check(mifareSectorTrailerBlock(31) == 127, "trailer sector 31");
    check(mifareSectorTrailerBlock(32) == 143, "trailer sector 32");
    check(mifareSectorTrailerBlock(39) == 255, "trailer sector 39");

    uint8_t keyFF[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    uint8_t keyAA[6] = {0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA};
    uint8_t keyList[2][6];
    memcpy(keyList[0], keyFF, 6);
    memcpy(keyList[1], keyAA, 6);

    // Complete 1K scan with one match (sector 0, key A, FFFFFFFFFFFF).
    {
        FakeBus bus;
        FakeBus::Match m; m.trailer = 3; m.kt = 0; memcpy(m.key, keyFF, 6);
        bus.matches.push_back(m);
        MifareKeyCheckResult r = mifareKeyCheckCore(bus, keyList, 2);
        check(r.tagPresent, "complete: tag present");
        check(r.stop == MIFARE_KC_COMPLETE, "complete: stop COMPLETE");
        check(r.sectors == 16, "complete: 16 sectors");
        check(r.sectorsCompleted == 16, "complete: all sectors completed");
        check(r.uidLen == 4 && r.uid[0] == 0x04 && r.uid[3] == 0xCC, "complete: uid captured");
        check(r.sak == MIFARE_SAK_1K, "complete: sak captured");
        check(r.keysInBatch == 2, "complete: keys in batch");
        check(r.hits.size() == 1, "complete: one hit");
        check(r.hits[0].sector == 0 && !r.hits[0].keyB &&
              memcmp(r.hits[0].key, keyFF, 6) == 0, "complete: hit is sector0 keyA FF");
    }

    // No tag present.
    {
        FakeBus bus; bus.present = false;
        MifareKeyCheckResult r = mifareKeyCheckCore(bus, keyList, 2);
        check(!r.tagPresent, "no tag: not present");
        check(r.stop == MIFARE_KC_NO_TAG, "no tag: stop NO_TAG");
        check(r.sectors == 0 && r.sectorsCompleted == 0, "no tag: no sectors");
        check(r.hits.empty(), "no tag: no hits");
    }

    // Partial scan: no matching keys, reselect fails on the 3rd call -> tag lost mid-scan.
    {
        FakeBus bus; bus.reselectFailAt = 3;
        MifareKeyCheckResult r = mifareKeyCheckCore(bus, keyList, 1);
        check(r.tagPresent, "partial: tag present at start");
        check(r.stop == MIFARE_KC_TAG_LOST, "partial: stop TAG_LOST");
        // sector0 keyA fail -> reselect#1; sector0 keyB fail -> reselect#2 (sector0 done);
        // sector1 keyA fail -> reselect#3 fails -> stop. 3 auth attempts, 1 sector completed.
        check(r.sectorsCompleted == 1, "partial: one sector completed");
        check(bus.attempts == 3, "partial: three auth attempts recorded on bus");
        check(r.authAttempts == 3, "partial: auth attempts accounted");
        check(r.hits.empty(), "partial: no hits");
    }

    // Cooperative cancellation: the phone asks to stop mid-scan. shouldStop() is polled once per
    // candidate-key attempt; after 2 auths it returns true, so sector 0 (key A + key B) is tested,
    // sector 1 is not. The hit found before the stop is kept; the result is partial (CANCELLED).
    {
        FakeBus bus; bus.stopAtAttempt = 2;
        FakeBus::Match m; m.trailer = 3; m.kt = 0; memcpy(m.key, keyFF, 6); // sector0 keyA = FF
        bus.matches.push_back(m);
        MifareKeyCheckResult r = mifareKeyCheckCore(bus, keyList, 1);
        check(r.tagPresent, "cancel: tag present at start");
        check(r.stop == MIFARE_KC_CANCELLED, "cancel: stop CANCELLED");
        check(r.sectorsCompleted == 1, "cancel: one sector completed before stop");
        check(r.authAttempts == 2, "cancel: two auth attempts before stop");
        check(r.hits.size() == 1 && r.hits[0].sector == 0 && !r.hits[0].keyB,
              "cancel: hit found before stop is kept");
        check(bus.stopPolls >= 1, "cancel: shouldStop polled during scan");
    }

    // shouldStop() that always returns false must not change a normal complete scan.
    {
        FakeBus bus; bus.stopAtAttempt = 0; // never stop
        MifareKeyCheckResult r = mifareKeyCheckCore(bus, keyList, 1);
        check(r.stop == MIFARE_KC_COMPLETE, "no-cancel: stop COMPLETE when shouldStop stays false");
        check(r.sectorsCompleted == 16, "no-cancel: all sectors completed");
    }

    std::printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
'''


def main():
    cxx = os.environ.get("CXX", "c++")
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        src = tmp / "driver.cpp"
        src.write_text(DRIVER)
        binary = tmp / "mifare_test"
        compile_cmd = [
            cxx, "-std=c++17", "-I", str(CORE), "-Wall", "-Wextra",
            str(src), str(CORE / "mifare_keycheck_core.cpp"), "-o", str(binary),
        ]
        print("compiling:", " ".join(compile_cmd))
        subprocess.run(compile_cmd, check=True)
        result = subprocess.run([str(binary)], capture_output=True, text=True)
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        if result.returncode != 0:
            print("MIFARE core host test FAILED")
            sys.exit(1)
    print("MIFARE core host test passed")


if __name__ == "__main__":
    main()
