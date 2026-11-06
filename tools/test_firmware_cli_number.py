#!/usr/bin/env python3
"""Host tests for the non-throwing CLI number parser and the RF CLI callbacks (backlog F03).

The RF CLI callbacks (`subghz tx`, `subghz txp`, `subghz keeloqtx`; src/core/serial_commands/
rf_commands.cpp) converted frequency/te/count/bits/button/serial/counter/repeat with std::stoul.
That translation unit keeps C++ exceptions and the companion routing path (routeCommand ->
serialCli.parse) has no try/catch, so `subghz tx 445533 abc` (std::invalid_argument) or a 20-digit
frequency (std::out_of_range) reached std::terminate and aborted the device.

This file:
  - compiles src/core/serial_commands/cli_number.h with -fexceptions and checks that every
    malformed/out-of-range token is rejected (false, output untouched) and valid ones parse; the
    driver also shows the old std::stoul behavior on the same inputs (throws / accepts a prefix);
  - mutates the overflow guard and the trailing-garbage check to prove they are load-bearing;
  - statically checks rf_commands.cpp: no std::sto* call is left, every numeric argument of the
    three transmit callbacks is parsed through clinum before initRfModule(), and txp/keeloqtx
    report a failed encode/RMT transmit instead of returning success.

Requires a C++ compiler. Run: python3 tools/test_firmware_cli_number.py
"""
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HEADER = ROOT / "src/core/serial_commands/cli_number.h"
RF_CMDS = ROOT / "src/core/serial_commands/rf_commands.cpp"
CXX = os.environ.get("CXX", "c++")

DRIVER = r'''
#include "cli_number.h"
#include <cstdint>
#include <cstdio>
#include <stdexcept>
#include <string>

static int failures = 0, checks = 0;
static void check(bool ok, const char *what) {
    checks++;
    if (!ok) { failures++; std::printf("FAIL: %s\n", what); }
}

// What the old code did with the same token: 0 = parsed, 1 = threw.
static int legacyStoul(const char *s) {
    try { (void)std::stoul(s); return 0; } catch (const std::exception &) { return 1; }
}

int main() {
    uint32_t v = 0;

    // Valid decimal values parse.
    v = 7; check(clinum::parseU32("433920000", 0, UINT32_MAX, v) && v == 433920000u, "frequency");
    v = 7; check(clinum::parseU32("0", 0, UINT32_MAX, v) && v == 0u, "zero");
    v = 7; check(clinum::parseU32("4294967295", 0, UINT32_MAX, v) && v == 4294967295u, "uint32 max");
    v = 7; check(clinum::parseU32(" +10 ", 0, 1000, v) && v == 10u, "blanks and plus sign");
    v = 7; check(clinum::parseU32("64", 1, 64, v) && v == 64u, "upper bound inclusive");
    v = 7; check(clinum::parseU32("1", 1, 64, v) && v == 1u, "lower bound inclusive");

    // Rejected: false, no throw, out unchanged.
    v = 1234; check(!clinum::parseU32("abc", 0, UINT32_MAX, v) && v == 1234u, "letters rejected, out unchanged");
    check(legacyStoul("abc") == 1, "legacy stoul threw on 'abc' (the F03 abort)");
    check(!clinum::parseU32("", 0, UINT32_MAX, v), "empty rejected");
    check(legacyStoul("") == 1, "legacy stoul threw on empty");
    check(!clinum::parseU32(nullptr, 0, UINT32_MAX, v), "null rejected");
    check(!clinum::parseU32("   ", 0, UINT32_MAX, v), "blanks only rejected");
    check(!clinum::parseU32("+", 0, UINT32_MAX, v), "sign only rejected");
    check(!clinum::parseU32("4294967296", 0, UINT32_MAX, v), "uint32 max + 1 rejected");
    check(!clinum::parseU32("99999999999999999999999", 0, UINT32_MAX, v), "23 digits rejected");
    check(legacyStoul("99999999999999999999999") == 1, "legacy stoul threw out_of_range");
    check(!clinum::parseU32("433abc", 0, UINT32_MAX, v), "numeric prefix rejected (stoul took 433)");
    check(legacyStoul("433abc") == 0, "legacy stoul silently accepted a prefix");
    check(!clinum::parseU32("-1", 0, UINT32_MAX, v), "negative rejected (stoul wrapped it)");
    check(!clinum::parseU32("4.5", 0, UINT32_MAX, v), "decimal point rejected");
    check(!clinum::parseU32("1 2", 0, UINT32_MAX, v), "two numbers rejected");
    check(!clinum::parseU32("65", 1, 64, v), "above max rejected");
    check(!clinum::parseU32("0", 1, 64, v), "below min rejected");
    check(!clinum::parseU32("0x10", 0, UINT32_MAX, v), "hex prefix rejected for decimal");

    // Hex (KeeLoq serial, 28 bits).
    v = 7; check(clinum::parseHexU32("0FFFFFFF", 0x0FFFFFFF, v) && v == 0x0FFFFFFFu, "28-bit serial");
    v = 7; check(clinum::parseHexU32("0xab", 0x0FFFFFFF, v) && v == 0xABu, "0x prefix");
    check(!clinum::parseHexU32("10000000", 0x0FFFFFFF, v), "29-bit serial rejected");
    check(!clinum::parseHexU32("xyz", 0x0FFFFFFF, v), "non-hex rejected");
    check(!clinum::parseHexU32("0x", 0x0FFFFFFF, v), "bare 0x rejected");

    // Small maximum (d > max must not underflow the guard).
    check(!clinum::parseU32("5", 0, 0, v), "max 0 rejects 5");
    v = 9; check(clinum::parseU32("0", 0, 0, v) && v == 0u, "max 0 accepts 0");

    std::printf("checks=%d failures=%d\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
'''


def compile_run(header_path):
    with tempfile.TemporaryDirectory(prefix="brucia-clinum-") as tmp:
        tmp = Path(tmp)
        (tmp / "driver.cpp").write_text(DRIVER)
        exe = tmp / "clinum_test"
        cmd = [CXX, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-fexceptions",
               "-I", str(header_path.parent), str(tmp / "driver.cpp"), "-o", str(exe)]
        comp = subprocess.run(cmd, capture_output=True, text=True)
        if comp.returncode != 0:
            return comp.returncode, comp.stderr
        run = subprocess.run([str(exe)], capture_output=True, text=True, timeout=60)
        return run.returncode, run.stdout + run.stderr


def mutant_run(old, new):
    src = HEADER.read_text()
    if old not in src:
        raise AssertionError(f"mutation anchor not found: {old!r}")
    with tempfile.TemporaryDirectory(prefix="brucia-clinum-mut-") as tmp:
        mutant = Path(tmp) / "cli_number.h"
        mutant.write_text(src.replace(old, new))
        return compile_run(mutant)


def function_body(src, name):
    """Text of `uint32_t name(cmd *c) { ... }` (brace matched)."""
    m = re.search(r"uint32_t\s+" + re.escape(name) + r"\s*\(cmd \*c\)\s*\{", src)
    if not m:
        raise AssertionError(f"{name} not found in rf_commands.cpp")
    depth, i = 1, m.end()
    while depth:
        depth += {"{": 1, "}": -1}.get(src[i], 0)
        i += 1
    return src[m.end():i - 1]


class CliNumberParser(unittest.TestCase):
    def test_parser_contract(self):
        rc, out = compile_run(HEADER)
        self.assertEqual(0, rc, f"host test failed:\n{out}")
        self.assertIn("failures=0", out)

    def test_overflow_guard_is_load_bearing(self):
        rc, out = mutant_run("if ((uint64_t)d > max || v > (max - (uint64_t)d) / (uint64_t)base) return false;",
                             "")
        self.assertNotEqual(0, rc, f"overflow guard mutation survived:\n{out}")

    def test_trailing_garbage_check_is_load_bearing(self):
        rc, out = mutant_run("if (*s != '\\0' || digits == 0) return false;",
                             "if (digits == 0) return false;")
        self.assertNotEqual(0, rc, f"full-token mutation survived:\n{out}")


class RfCliCallbacks(unittest.TestCase):
    SRC = RF_CMDS.read_text()

    def test_no_throwing_conversion_left(self):
        code = "\n".join(l for l in self.SRC.splitlines() if not l.strip().startswith("//"))
        self.assertNotRegex(code, r"std::sto(u?l{1,2}|i|f|d)\s*\(")

    def _assert_parsed_before_init(self, name, args):
        body = function_body(self.SRC, name)
        init = body.find("initRfModule(")
        self.assertGreater(init, 0, f"{name}: initRfModule not found")
        for arg in args:
            pos = body.find(arg)
            self.assertGreaterEqual(pos, 0, f"{name}: {arg} is not parsed through clinum")
            self.assertLess(pos, init, f"{name}: {arg} parsed after the radio is initialized")

    def test_tx_args_validated_before_radio(self):
        self._assert_parsed_before_init("rfTxCallback", [
            'rfArgU32(strFrequency, "frequency"', 'rfArgU32(strTe, "te"', 'rfArgU32(strCount, "count"',
            "rfhex::parseKey64("])

    def test_txp_args_validated_before_radio(self):
        self._assert_parsed_before_init("rfTxByNameCallback", [
            'rfArgU32(strFrequency, "frequency"', 'rfArgU32(strBits, "bits", 1, RF_CLI_MAX_BITS',
            'rfArgU32(strTe, "te"', 'rfArgU32(strRepeat, "repeat"', "rfhex::parseKey64("])

    def test_keeloqtx_args_validated_before_radio(self):
        self._assert_parsed_before_init("rfKeeloqTxCallback", [
            'rfArgU32(strBtn, "button", 0, 15', 'rfArgU32(strCnt, "counter", 0, 0xFFFF',
            'rfArgU32(strFreq, "frequency"', 'rfArgU32(strRepeat, "repeat"',
            "clinum::parseHexU32(strSerial.c_str(), 0x0FFFFFFF"])

    def test_bits_bounded_to_key_width(self):
        self.assertIn("RF_CLI_MAX_BITS = 64;", self.SRC)

    def test_rfsend_json_bounds(self):
        body = function_body(self.SRC, "rfSendCallback")
        guard = body.find("bits > RF_CLI_MAX_BITS")
        self.assertGreater(guard, 0, "RfSend does not bound Bits/Repeat")
        self.assertIn("repeat > (int)RF_CLI_MAX_REPEAT", body)
        self.assertLess(guard, body.find("initRfModule("), "RfSend bounds checked after radio init")

    def test_encoder_refuses_more_than_64_bits(self):
        # Covers every caller (txp, RfSend, .sub files via txSubFile/tx_from_file): a file "Bit:"
        # value sized the encoder's reserve and shifted the 64-bit key by >= 64.
        enc = (ROOT / "src/modules/rf/protocols/rf_encoder.cpp").read_text()
        self.assertIn("if (def == nullptr || bits == 0 || bits > 64) return false;", enc)

    def test_txp_and_keeloqtx_report_transmit_failure(self):
        for name, call in (("rfTxByNameCallback", "rf_tx_protocol("), ("rfKeeloqTxCallback", "rf_tx_keeloq(")):
            body = function_body(self.SRC, name)
            self.assertRegex(body, r"bool sent = " + re.escape(call), f"{name} ignores the TX result")
            self.assertIn("return sent;", body, f"{name} reports success regardless of the TX result")


if __name__ == "__main__":
    unittest.main(verbosity=2)
