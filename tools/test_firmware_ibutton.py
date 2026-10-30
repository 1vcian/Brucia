#!/usr/bin/env python3
"""Compile the Arduino-free iButton core (src/modules/others/ibutton_core.cpp) on the host
with a C++ driver and run it: id parse/format, Dallas/Maxim CRC8, pin rule, ROM read retries
against a fake 1-Wire bus. No ESP32 or PlatformIO installation required.
"""
import os
from pathlib import Path
import random
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "src/modules/others"


def maxim_crc8(data):
    """Independent bitwise Dallas/Maxim CRC8 (x^8 + x^5 + x^4 + 1, reflected)."""
    crc = 0
    for byte in data:
        for _ in range(8):
            mix = (crc ^ byte) & 1
            crc >>= 1
            if mix:
                crc ^= 0x8C
            byte >>= 1
    return crc


# Maxim AN27 worked example: family 02, serial 00 00 00 01 B8 1C, CRC A2.
assert maxim_crc8(bytes.fromhex("021CB801000000")) == 0xA2


def crc_vectors():
    rng = random.Random(1990)
    vectors = [bytes.fromhex("021CB801000000"), bytes(7), bytes([0xFF] * 7), b"", b"\x01"]
    vectors += [bytes(rng.randrange(256) for _ in range(n)) for n in (7, 7, 7, 3, 16)]
    lines = []
    for v in vectors:
        arr = ",".join(f"0x{b:02X}" for b in v) or "0"
        lines.append(f"    {{const uint8_t d[] = {{{arr}}}; check(ibuttonCrc8(d, {len(v)}) == 0x{maxim_crc8(v):02X}, \"crc {v.hex()}\");}}")
    return "\n".join(lines)


def valid_rom(hex7):
    data = bytes.fromhex(hex7)
    return data + bytes([maxim_crc8(data)])


GOOD = valid_rom("01A1B2C3D4E5F6")
BAD = GOOD[:7] + bytes([GOOD[7] ^ 0x55])

DRIVER = r'''
#include "ibutton_core.h"
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <vector>

static int failures = 0, checks = 0;
static void check(bool ok, const char *what) {
    checks++;
    if (!ok) { failures++; std::printf("FAIL: %s\n", what); }
}

struct FakeBus : IButtonBus {
    std::vector<bool> presence;              // per reset() call; past the end = last value
    std::vector<std::vector<uint8_t>> roms;  // per readBytes() call; past the end = last value
    int resets = 0, reads = 0, retries = 0, delays = 0, cancelAt = -1, cancelCalls = 0;
    std::vector<uint8_t> writes;
    bool reset() override {
        bool p = presence.empty() ? false : presence[std::min<size_t>(resets, presence.size() - 1)];
        resets++;
        return p;
    }
    void write(uint8_t v) override { writes.push_back(v); }
    void readBytes(uint8_t *buf, size_t len) override {
        const std::vector<uint8_t> &r = roms[std::min<size_t>(reads, roms.size() - 1)];
        reads++;
        for (size_t i = 0; i < len; i++) buf[i] = r[i];
    }
    void delayMs(uint32_t) override { delays++; }
    bool cancelled() override { return cancelCalls++ == cancelAt; }
    void onRetry(int attempt) override { check(attempt == retries, "onRetry attempt index"); retries++; }
};

static const std::vector<uint8_t> GOOD = {@GOOD@}, BAD = {@BAD@};

int main() {
    uint8_t id[8], keep[8] = {9, 9, 9, 9, 9, 9, 9, 9};
    const uint8_t expect[8] = {0xAA, 0xBB, 0xCC, 0xDD, 0x01, 0x23, 0x45, 0x6F};
    check(ibuttonParseId("AA:BB:CC:DD:01:23:45:6F", id) && !memcmp(id, expect, 8), "parse colons");
    check(ibuttonParseId("AA BB CC DD 01 23 45 6F", id) && !memcmp(id, expect, 8), "parse spaces");
    check(ibuttonParseId("  aaBbcCDd0123456f\r\n", id) && !memcmp(id, expect, 8), "parse mixed case + trim");
    check(ibuttonParseId("\tAA:BB CC:DD 01:23 45:6F ", id) && !memcmp(id, expect, 8), "parse mixed separators");
    const char *bad[] = {"AABBCCDD0123456", "AABBCCDD0123456F0", "AABBCCDD0123456G", "AABBCCDD-0123456F",
                         "AABBCCDD\t0123456F", "", "   ", "0xAABBCCDD012345"};
    for (const char *b : bad) {
        memset(id, 9, 8);
        check(!ibuttonParseId(b, id) && !memcmp(id, keep, 8), b);
    }
    check(!ibuttonParseId(nullptr, id) && !memcmp(id, keep, 8), "parse null");

    char out[32];
    ibuttonFormatId(expect, out, sizeof(out), ":");
    check(!strcmp(out, "AA:BB:CC:DD:01:23:45:6F"), "format colon");
    ibuttonFormatId(expect, out, sizeof(out), " ");
    check(!strcmp(out, "AA BB CC DD 01 23 45 6F"), "format space");
    ibuttonFormatId(expect, out, sizeof(out), "");
    check(!strcmp(out, "AABBCCDD0123456F"), "format contiguous");
    ibuttonFormatId(expect, out, sizeof(out), nullptr);
    check(!strcmp(out, "AABBCCDD0123456F"), "format null sep");
    ibuttonFormatId(expect, out, 24, ":");
    check(!strcmp(out, "AA:BB:CC:DD:01:23:45:6F"), "format exact fit");
    ibuttonFormatId(expect, out, 6, ":");
    check(!strcmp(out, "AA:BB"), "format truncates");

@CRC@

    check(!ibuttonPinUsable(-1, 49), "pin -1");
    check(!ibuttonPinUsable(0, 49), "pin 0");
    check(ibuttonPinUsable(1, 49), "pin 1");
    check(ibuttonPinUsable(48, 49), "pin max-1");
    check(!ibuttonPinUsable(49, 49), "pin max");

    {   // success first try
        FakeBus b; b.presence = {true}; b.roms = {GOOD};
        check(ibuttonReadRom(b, id, 20, true, 100) == IBUTTON_SUCCESS, "read success");
        check(!memcmp(id, GOOD.data(), 8) && b.writes == std::vector<uint8_t>{0x33}, "read rom cmd + bytes");
        check(b.retries == 0 && b.delays == 0, "read success no retry");
    }
    {   // no presence, stop at once (standalone menu)
        FakeBus b; b.presence = {false}; b.roms = {GOOD};
        check(ibuttonReadRom(b, id, 20, true, 100) == IBUTTON_NO_KEY && b.resets == 1 && b.delays == 0, "no presence stop");
    }
    {   // no presence, keep trying (headless), key arrives on the 3rd attempt
        FakeBus b; b.presence = {false, false, true}; b.roms = {GOOD};
        check(ibuttonReadRom(b, id, 10, false, 30) == IBUTTON_SUCCESS && b.resets == 3 && b.delays == 2, "no presence retry");
    }
    {   // CRC failure then success
        FakeBus b; b.presence = {true}; b.roms = {BAD, BAD, GOOD};
        check(ibuttonReadRom(b, id, 20, true, 100) == IBUTTON_SUCCESS && b.retries == 2 && b.delays == 2, "crc retry");
        check(!memcmp(id, GOOD.data(), 8), "crc retry bytes");
    }
    {   // exhaustion -> CRC_ERROR with the last bytes
        FakeBus b; b.presence = {true}; b.roms = {GOOD, BAD};
        b.roms[0][0] ^= 1;
        check(ibuttonReadRom(b, id, 20, true, 100) == IBUTTON_CRC_ERROR && b.retries == 20 && b.reads == 20, "exhausted");
        check(!memcmp(id, BAD.data(), 8), "exhausted keeps last bytes");
    }
    {   // never present (headless) -> NO_KEY after all attempts
        FakeBus b; b.presence = {false}; b.roms = {GOOD};
        check(ibuttonReadRom(b, id, 10, false, 30) == IBUTTON_NO_KEY && b.resets == 10 && b.delays == 10 && b.reads == 0, "never present");
    }
    {   // presence with bad CRC then gone (headless) -> CRC_ERROR
        FakeBus b; b.presence = {true, false}; b.roms = {BAD};
        check(ibuttonReadRom(b, id, 10, false, 30) == IBUTTON_CRC_ERROR, "bad crc then gone");
    }
    {   // cancel before first attempt
        FakeBus b; b.presence = {true}; b.roms = {GOOD}; b.cancelAt = 0;
        memcpy(id, keep, 8);
        check(ibuttonReadRom(b, id, 20, true, 100) == IBUTTON_CANCELLED && b.resets == 0 && !memcmp(id, keep, 8), "cancel first");
    }
    {   // cancel after one CRC retry
        FakeBus b; b.presence = {true}; b.roms = {BAD}; b.cancelAt = 1;
        check(ibuttonReadRom(b, id, 20, true, 100) == IBUTTON_CANCELLED && b.retries == 1, "cancel mid retry");
    }

    if (failures) { std::printf("Firmware iButton FAIL (%d/%d checks failed)\n", failures, checks); return 1; }
    std::printf("Firmware iButton PASS (%d checks: parse/format, CRC8, pin rule, ROM read retries)\n", checks);
    return 0;
}
'''


SANITIZERS = (["-fsanitize=address,undefined", "-fno-sanitize-recover=undefined"],
              ["-fsanitize=undefined", "-fno-sanitize-recover=undefined"], [])


def pick_flags(cxx, tmp):
    """First sanitizer set that builds and runs (ASan can hang in some sandboxed macOS shells)."""
    (tmp / "probe.cpp").write_text("int main() { return 0; }\n")
    for flags in SANITIZERS:
        exe = tmp / "probe"
        if subprocess.run([cxx, *flags, str(tmp / "probe.cpp"), "-o", str(exe)],
                          stderr=subprocess.DEVNULL).returncode != 0:
            continue
        try:
            if subprocess.run([str(exe)], timeout=10).returncode == 0:
                return flags
        except subprocess.TimeoutExpired:
            pass
    return []


def main():
    def arr(b):
        return ",".join(f"0x{x:02X}" for x in b)

    source = DRIVER.replace("@GOOD@", arr(GOOD)).replace("@BAD@", arr(BAD)).replace("@CRC@", crc_vectors())
    cxx = os.environ.get("CXX", "c++")
    with tempfile.TemporaryDirectory(prefix="brucia-ibutton-") as tmp:
        tmp = Path(tmp)
        (tmp / "driver.cpp").write_text(source)
        flags = pick_flags(cxx, tmp)
        if flags != SANITIZERS[0]:
            print(f"note: building with {' '.join(flags) or 'no sanitizers'} (ASan unavailable here)", flush=True)
        exe = tmp / "ibutton_test"
        cmd = [cxx, "-std=c++17", "-Wall", "-Wextra", "-Werror", *flags, "-I", str(CORE),
               str(tmp / "driver.cpp"), str(CORE / "ibutton_core.cpp"), "-o", str(exe)]
        if subprocess.run(cmd).returncode != 0:
            print("Firmware iButton FAIL (compile error)")
            return 1
        try:
            return subprocess.run([str(exe)], timeout=60).returncode
        except subprocess.TimeoutExpired:
            print("Firmware iButton FAIL (timeout)")
            return 1


if __name__ == "__main__":
    sys.exit(main())
