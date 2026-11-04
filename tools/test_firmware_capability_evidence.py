#!/usr/bin/env python3
"""Compile the Arduino-free capability-evidence core (src/companion/capability_evidence_core.cpp)
on the host and run it: evidence-level resolution (compiled/configured/probed/initialized, probed
absent, busy) and static pin/bus conflict detection, including the stock T-Embed CC1101 pin map.
No ESP32 or PlatformIO installation required. This covers the pure logic only; the SPI/I2C probes
themselves and the pin values a real device loads from its saved config need hardware.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "src/companion"

DRIVER = r'''
#include "capability_evidence_core.h"
#include <cstdio>
#include <cstring>
#include <set>
#include <string>
#include <vector>

using namespace brucia::companion;

static int failures = 0, checks = 0;
static void check(bool ok, const char *what) {
    checks++;
    if (!ok) { failures++; std::printf("FAIL: %s\n", what); }
}

static EvidenceInputs in(bool c, bool cfg, ProbeOutcome p, bool init) {
    EvidenceInputs e; e.compiled = c; e.configured = cfg; e.probe = p; e.initialized = init; return e;
}

static std::set<std::string> conflictSet(const std::vector<PinUse> &u) {
    PinConflict out[64];
    size_t n = findPinConflicts(u.data(), u.size(), out, 64);
    std::set<std::string> s;
    for (size_t i = 0; i < n && i < 64; i++) {
        const PinUse &a = u[out[i].a], &b = u[out[i].b];
        s.insert(std::to_string(a.gpio) + ":" + a.module + "." + a.role + "=" + b.module + "." + b.role);
    }
    return s;
}

int main() {
    // Level resolution.
    check(resolveEvidenceLevel(in(false, true, ProbeOutcome::Present, true)) == EvidenceLevel::None,
          "not compiled -> none, whatever else is set");
    check(resolveEvidenceLevel(in(true, false, ProbeOutcome::NotRun, false)) == EvidenceLevel::Compiled,
          "compiled only");
    check(resolveEvidenceLevel(in(true, true, ProbeOutcome::NotRun, false)) == EvidenceLevel::Configured,
          "configured, no probe (e.g. iButton GPIO)");
    check(resolveEvidenceLevel(in(true, true, ProbeOutcome::Absent, false)) == EvidenceLevel::Configured,
          "probed absent stays configured");
    check(resolveEvidenceLevel(in(true, true, ProbeOutcome::Busy, false)) == EvidenceLevel::Configured,
          "busy stays configured");
    check(resolveEvidenceLevel(in(true, true, ProbeOutcome::Present, false)) == EvidenceLevel::Probed,
          "present -> probed");
    check(resolveEvidenceLevel(in(true, true, ProbeOutcome::Present, true)) == EvidenceLevel::Initialized,
          "initialized wins");
    check(resolveEvidenceLevel(in(true, false, ProbeOutcome::Present, false)) == EvidenceLevel::Probed,
          "a probe that saw the chip outranks a missing pin flag");
    check(std::strcmp(evidenceLevelName(EvidenceLevel::None), "none") == 0, "name none");
    check(std::strcmp(evidenceLevelName(EvidenceLevel::Compiled), "compiled") == 0, "name compiled");
    check(std::strcmp(evidenceLevelName(EvidenceLevel::Configured), "configured") == 0, "name configured");
    check(std::strcmp(evidenceLevelName(EvidenceLevel::Probed), "probed") == 0, "name probed");
    check(std::strcmp(evidenceLevelName(EvidenceLevel::Initialized), "initialized") == 0, "name initialized");
    check(probeOutcomeName(ProbeOutcome::NotRun) == nullptr, "not run -> omitted");
    check(std::strcmp(probeOutcomeName(ProbeOutcome::Present), "present") == 0, "probe present");
    check(std::strcmp(probeOutcomeName(ProbeOutcome::Absent), "absent") == 0, "probe absent");
    check(std::strcmp(probeOutcomeName(ProbeOutcome::Busy), "busy") == 0, "probe busy");

    const PinShare X = PinShare::Exclusive, S = PinShare::SpiBus, I = PinShare::I2cBus;

    // Stock T-Embed CC1101 board profile (boards/lilygo-t-embed-cc1101/pins_arduino.h), PN532 on I2C.
    std::vector<PinUse> tembed = {
        {"cc1101", "sck", 11, S}, {"cc1101", "miso", 10, S}, {"cc1101", "mosi", 9, S},
        {"cc1101", "cs", 12, X}, {"cc1101", "gdo0", 3, X}, {"cc1101", "gdo2", 38, X},
        {"nrf24", "sck", 11, S}, {"nrf24", "miso", 10, S}, {"nrf24", "mosi", 9, S},
        {"nrf24", "cs", 44, X}, {"nrf24", "ce", 43, X},
        {"eth", "sck", 11, S}, {"eth", "miso", 10, S}, {"eth", "mosi", 9, S},
        {"eth", "cs", 44, X}, {"eth", "int", 43, X},
        {"sd", "sck", 11, S}, {"sd", "miso", 10, S}, {"sd", "mosi", 9, S}, {"sd", "cs", 13, X},
        {"display", "sck", 11, S}, {"display", "miso", 10, S}, {"display", "mosi", 9, S},
        {"display", "cs", 41, X}, {"display", "dc", 16, X}, {"display", "rst", 40, X},
        {"nfc", "sda", 8, I}, {"nfc", "scl", 18, I}, {"nfc", "irq", 17, X}, {"nfc", "rst", 45, X},
        {"fm", "sda", 8, I}, {"fm", "scl", 18, I},
        {"ir", "tx", 2, X}, {"ir", "rx", 1, X},
        {"mic", "clk", 39, X}, {"mic", "data", 42, X},
        {"speaker", "bclk", 46, X}, {"speaker", "wclk", 40, X}, {"speaker", "dout", 7, X},
        {"speaker", "mclk", 39, X},
        {"ibutton", "data", -1, X},
    };
    std::set<std::string> got = conflictSet(tembed);
    std::set<std::string> want = {
        "44:nrf24.cs=eth.cs",
        "43:nrf24.ce=eth.int",
        "40:display.rst=speaker.wclk",
        "39:mic.clk=speaker.mclk",
    };
    check(got == want, "T-Embed: exactly the shared CS/CE, display RST/I2S WCLK and mic/MCLK conflicts");
    for (auto &s : got) std::printf("  t-embed conflict %s\n", s.c_str());

    // Shared SPI bus lines with the same role are not conflicts.
    std::vector<PinUse> bus = {{"a", "sck", 5, S}, {"b", "sck", 5, S}, {"a", "cs", 6, X}, {"b", "cs", 7, X}};
    check(conflictSet(bus).empty(), "shared SPI bus, distinct CS -> no conflict");

    // Same GPIO with a different bus role (A's SCK is B's MOSI) is a conflict.
    std::vector<PinUse> roles = {{"a", "sck", 5, S}, {"b", "mosi", 5, S}};
    check(conflictSet(roles).size() == 1, "SCK vs MOSI on one GPIO -> conflict");

    // An I2C line reused as SPI chip select (RC522 on SPI_SS_PIN 8 vs Grove SDA 8 on T-Embed).
    std::vector<PinUse> mixed = {{"nfc", "cs", 8, X}, {"fm", "sda", 8, I}};
    check(conflictSet(mixed).size() == 1, "I2C SDA vs SPI CS -> conflict");

    // SDA on I2C vs an SPI line with the same role name but a different bus kind.
    std::vector<PinUse> kinds = {{"a", "x", 4, S}, {"b", "x", 4, I}};
    check(conflictSet(kinds).size() == 1, "different bus kinds -> conflict");

    // Shared I2C bus.
    std::vector<PinUse> i2c = {{"nfc", "sda", 8, I}, {"fm", "sda", 8, I}, {"nfc", "scl", 18, I}, {"fm", "scl", 18, I}};
    check(conflictSet(i2c).empty(), "shared I2C bus -> no conflict");

    // Exclusive role equal on two modules is still a conflict (two chip selects on one GPIO).
    std::vector<PinUse> cs = {{"a", "cs", 9, X}, {"b", "cs", 9, X}};
    check(conflictSet(cs).size() == 1, "same CS on two modules -> conflict");

    // Self-misconfiguration inside one module.
    std::vector<PinUse> self = {{"nrf24", "cs", 4, X}, {"nrf24", "ce", 4, X}};
    check(conflictSet(self).size() == 1, "CS == CE inside one module -> conflict");

    // Exact duplicates and unconnected pins are ignored.
    std::vector<PinUse> dup = {{"a", "cs", 9, X}, {"a", "cs", 9, X}, {"a", "rst", -1, X}, {"b", "rst", -1, X}};
    check(conflictSet(dup).empty(), "duplicates and NC pins ignored");

    // Truncation: total is returned, only maxOut written.
    std::vector<PinUse> many = {{"a", "cs", 1, X}, {"b", "cs", 1, X}, {"c", "cs", 1, X}};
    PinConflict one[1];
    size_t total = findPinConflicts(many.data(), many.size(), one, 1);
    check(total == 3, "three-way share -> three pairwise conflicts counted");
    check(one[0].a == 0 && one[0].b == 1, "first conflict written in order");
    check(findPinConflicts(many.data(), many.size(), nullptr, 0) == 3, "count-only call");
    check(findPinConflicts(nullptr, 3, nullptr, 0) == 0, "null input");

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
        binary = tmp / "evidence_test"
        compile_cmd = [
            cxx, "-std=c++17", "-I", str(CORE), "-Wall", "-Wextra", "-Werror",
            str(src), str(CORE / "capability_evidence_core.cpp"), "-o", str(binary),
        ]
        print("compiling:", " ".join(compile_cmd))
        subprocess.run(compile_cmd, check=True)
        result = subprocess.run([str(binary)], capture_output=True, text=True)
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        if result.returncode != 0:
            print("Capability evidence host test FAILED")
            sys.exit(1)
    print("Capability evidence host test passed")


if __name__ == "__main__":
    main()
