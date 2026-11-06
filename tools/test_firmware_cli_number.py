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
    report a failed encode/RMT transmit instead of returning success;
  - batch 18 (F03 remainder): `tone`/`beep` (sound_commands.cpp) and `screen color hex/rgb`
    (screen_commands.cpp) parse through clinum before any side effect (tone 0..20000 Hz,
    0..10000 ms; color 24-bit hex, rgb 0..255 per channel), and no std::sto* call is left in
    src/core/serial_commands/ or src/companion/.

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
SOUND_CMDS = ROOT / "src/core/serial_commands/sound_commands.cpp"
SCREEN_CMDS = ROOT / "src/core/serial_commands/screen_commands.cpp"
THROWING = r"std::sto(u?l{1,2}|i|f|d|l)\s*\("
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

    // tone/beep (sound_commands.cpp): frequency 0..20000 Hz, duration 0..10000 ms; the CLI
    // defaults are "500" (the old "500UL" only worked because stoul took the numeric prefix).
    v = 7; check(clinum::parseU32("500", 0, 20000, v) && v == 500u, "tone default 500");
    check(!clinum::parseU32("500UL", 0, 20000, v), "old '500UL' default would be rejected");
    check(legacyStoul("500UL") == 0, "legacy stoul read '500UL' as 500");
    check(!clinum::parseU32("abc", 0, 20000, v), "tone text rejected (stoul threw)");
    check(!clinum::parseU32("20001", 0, 20000, v), "tone above 20 kHz rejected");
    check(!clinum::parseU32("10001", 0, 10000, v), "tone duration above 10 s rejected");
    // screen color hex (screen_commands.cpp): 24-bit, "0x" optional.
    v = 7; check(clinum::parseHexU32("ff00ff", 0xFFFFFF, v) && v == 0xFF00FFu, "color ff00ff");
    v = 7; check(clinum::parseHexU32("0xFFFFFF", 0xFFFFFF, v) && v == 0xFFFFFFu, "color 0xFFFFFF");
    check(!clinum::parseHexU32("zz", 0xFFFFFF, v), "color zz rejected (stoul threw)");
    check(!clinum::parseHexU32("01000000", 0xFFFFFF, v), "color above 24 bits rejected");
    check(!clinum::parseHexU32("ffffffffffffffffffff", 0xFFFFFF, v), "20 hex digits rejected (stoul out_of_range)");
    // screen color rgb: each channel 0..255, whole token (atoi took "12abc" as 12).
    check(!clinum::parseU32("12abc", 0, 255, v), "rgb prefix rejected");
    check(!clinum::parseU32("256", 0, 255, v), "rgb 256 rejected");

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
        self.assertNotRegex(code, THROWING)

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


def callback_body(src, name):
    m = re.search(r"uint32_t\s+" + re.escape(name) + r"\s*\(cmd \*c\)\s*\{", src)
    if not m:
        raise AssertionError(f"{name} not found")
    depth, i = 1, m.end()
    while depth:
        depth += {"{": 1, "}": -1}.get(src[i], 0)
        i += 1
    return src[m.end():i - 1]


def code_only(text):
    return "\n".join(l for l in text.splitlines() if not l.strip().startswith("//"))


def sound_screen_problems(sound, screen):
    """Static F03 contract for tone/beep and screen color; returns the list of violations."""
    out = []
    for name, text in (("sound_commands.cpp", sound), ("screen_commands.cpp", screen)):
        if re.search(THROWING, code_only(text)):
            out.append(f"{name}: throwing std::sto* conversion left")
    tone = callback_body(sound, "toneCallback")
    parse = tone.find('toneArgU32(strFreq, "frequency", TONE_CLI_MAX_HZ')
    parse_d = tone.find('toneArgU32(strDur, "duration", TONE_CLI_MAX_MS')
    side = min(i for i in (tone.find("bruceConfig.soundEnabled = true"), tone.find("_tone(")) if i >= 0)
    if parse < 0 or parse_d < 0 or not (parse < side and parse_d < side):
        out.append("toneCallback: frequency/duration not parsed through clinum before touching sound")
    if "TONE_CLI_MAX_HZ = 20000;" not in sound or "TONE_CLI_MAX_MS = 10000;" not in sound:
        out.append("tone bounds changed")
    if 'toneCmd.addPosArg("frequency", "500");' not in sound or 'toneCmd.addPosArg("duration", "500");' not in sound:
        out.append("tone defaults are not '500' (the old '500UL' is rejected by the whole-token parser)")
    hexcb = callback_body(screen, "hexColorCallback")
    p = hexcb.find("clinum::parseHexU32(strValue.c_str(), 0xFFFFFF, value)")
    if p < 0 or p > hexcb.find("bruceConfig.priColor"):
        out.append("hexColorCallback: not parsed through clinum::parseHexU32 before applying")
    rgb = callback_body(screen, "rgbColorCallback")
    for ch in ("strRed", "strGreen", "strBlue"):
        if f"clinum::parseU32({ch}.c_str(), 0, 255," not in rgb:
            out.append(f"rgbColorCallback: {ch} not parsed whole-token 0..255")
    return out


class SoundScreenCliCallbacks(unittest.TestCase):
    """F03 remainder (batch 18): tone/beep and screen color used std::stoul."""

    def test_contract(self):
        found = sound_screen_problems(SOUND_CMDS.read_text(), SCREEN_CMDS.read_text())
        self.assertEqual([], found, "\n".join(found))

    def test_base_sources_fail(self):
        # The pre-batch-18 callbacks (std::stoul) are detected.
        base_sound = SOUND_CMDS.read_text().replace(
            'uint32_t frequency = 0, duration = 0;', 'unsigned long frequency = std::stoul(strFreq.c_str());')
        self.assertTrue(sound_screen_problems(base_sound, SCREEN_CMDS.read_text()))
        base_screen = SCREEN_CMDS.read_text().replace(
            "clinum::parseHexU32(strValue.c_str(), 0xFFFFFF, value)", "0; value = std::stoul(strValue.c_str(), nullptr, 16)")
        self.assertTrue(sound_screen_problems(SOUND_CMDS.read_text(), base_screen))

    def test_tone_parse_after_side_effect_is_detected(self):
        src = SOUND_CMDS.read_text()
        moved = src.replace("    bool soundEnabled = bruceConfig.soundEnabled;\n    bruceConfig.soundEnabled = true;\n", "")
        moved = moved.replace("    uint32_t frequency = 0, duration = 0;\n",
                              "    bruceConfig.soundEnabled = true;\n    uint32_t frequency = 0, duration = 0;\n")
        self.assertTrue(sound_screen_problems(moved, SCREEN_CMDS.read_text()))


class NoThrowingConversionInCliOrCompanion(unittest.TestCase):
    """No std::stoi/stoul/stoull/stof/stod left in the CLI callbacks or companion handlers (F03)."""

    def test_none_left(self):
        hits = []
        for folder in ("src/core/serial_commands", "src/companion"):
            for f in sorted((ROOT / folder).glob("*.[ch]*")):
                for n, line in enumerate(f.read_text().splitlines(), 1):
                    if not line.strip().startswith("//") and re.search(THROWING, line):
                        hits.append(f"{f.relative_to(ROOT)}:{n}: {line.strip()}")
        self.assertEqual([], hits, "\n".join(hits))


if __name__ == "__main__":
    unittest.main(verbosity=2)
