#!/usr/bin/env python3
"""Host-native tests for the RF single-code hex-key parser (src/modules/rf/rf_hexkey.h).

Backlog F01-F02 (De Bruijn): the phone's De Bruijn generator produces a hex key far longer than
one 64-bit code (order 12 = 1024 hex characters). The CLI parsed it with std::stoull, which throws
std::out_of_range on such input; that code is Bruce CLI (exceptions enabled) and nothing on the
companion routing path catches it, so the throw aborts/reboots the device. rfhex::parseKey64
rejects it instead: no exception, a clear error, the device state preserved.

This compiles the pure header with a C++ driver and runs it (no ESP32/PlatformIO needed):
  - valid short/max keys parse (24-bit code, full 16-digit 64-bit key, 0x prefix);
  - empty, non-hex and over-16-digit keys are rejected WITHOUT throwing;
  - the exact De Bruijn hex strings the app generates for every UI-offered order are rejected
    (reproducing the crash input and proving it no longer throws/aborts);
  - a mutation of the length guard (> 16 -> > 17) makes the over-long cases slip through, so the
    test then fails - showing the guard is load-bearing.

Requires a C++ compiler. Run: python3 tools/test_firmware_debruijn.py
"""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RF_DIR = ROOT / "src/modules/rf"
HEADER = RF_DIR / "rf_hexkey.h"
CXX = os.environ.get("CXX", "c++")


# --- independent De Bruijn generator (mirrors android/.../subghz/DeBruijn.kt) -------------------
def db_sequence(n):
    """Lexicographically-smallest de Bruijn B(2,n) sequence (length 2^n), same algorithm as the app."""
    k = 2
    a = [0] * (k * n)
    out = []

    def db(t, p):
        if t > n:
            if n % p == 0:
                out.extend(a[1:p + 1])
        else:
            a[t] = a[t - p]
            db(t + 1, p)
            for j in range(a[t - p] + 1, k):
                a[t] = j
                db(t + 1, t)

    db(1, 1)
    return "".join(str(b) for b in out)


def db_hexkey(n):
    """The app's hexKey(n): bits padded up to whole nibbles, encoded as uppercase hex."""
    bits = db_sequence(n)
    pad = (4 - len(bits) % 4) % 4
    padded = bits + "0" * pad
    return "".join(format(int(padded[i:i + 4], 2), "X") for i in range(0, len(padded), 4))


OFFERED_ORDERS = [8, 10, 12, 16, 20, 22]  # must match DeBruijn.OFFERED_ORDERS

DRIVER = r'''
#include "rf_hexkey.h"
#include <cstdint>
#include <cstdio>
#include <cstring>

static int failures = 0, checks = 0;
static void check(bool ok, const char *what) {
    checks++;
    if (!ok) { failures++; std::printf("FAIL: %s\n", what); }
}

int main() {
    uint64_t v = 0xDEADBEEF;

    // Valid keys parse to the right value.
    v = 0; check(rfhex::parseKey64("445533", v) && v == 0x445533ULL, "24-bit code 445533");
    v = 0; check(rfhex::parseKey64("0", v) && v == 0ULL, "zero key");
    v = 0; check(rfhex::parseKey64("FFFFFFFFFFFFFFFF", v) && v == 0xFFFFFFFFFFFFFFFFULL, "full 16-digit 64-bit key");
    v = 0; check(rfhex::parseKey64("0x1A2b", v) && v == 0x1A2BULL, "0x prefix, mixed case");
    v = 0; check(rfhex::parseKey64("  abcdef", v) && v == 0xABCDEFULL, "leading whitespace");

    // Rejected inputs: the call returns false and does NOT throw/abort. `out` is left unchanged.
    v = 0x1234; check(!rfhex::parseKey64("", v) && v == 0x1234ULL, "empty rejected, out unchanged");
    v = 0x1234; check(!rfhex::parseKey64(nullptr, v), "null rejected");
    check(!rfhex::parseKey64("12g4", v), "non-hex rejected");
    check(!rfhex::parseKey64("12 34", v), "embedded space rejected");
    check(!rfhex::parseKey64("00000000000000000", v), "17 zero digits rejected (too long)");
    check(!rfhex::parseKey64("FFFFFFFFFFFFFFFFF", v), "17 F digits rejected (overflow range)");

    // The exact De Bruijn hex the app generates for each offered order: must be rejected, no throw.
@DEBRUIJN@

    std::printf("checks=%d failures=%d\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
'''


def build_driver():
    lines = []
    for n in OFFERED_ORDERS:
        hx = db_hexkey(n)
        lines.append(
            f'    check(!rfhex::parseKey64("{hx}", v), "De Bruijn order {n} ({len(hx)} hex chars) rejected");'
        )
    return DRIVER.replace("@DEBRUIJN@", "\n".join(lines))


def compile_run(header_path, driver_src):
    """Compile the driver against header_path's directory; return (returncode, stdout+stderr)."""
    with tempfile.TemporaryDirectory(prefix="brucia-debruijn-") as tmp:
        tmp = Path(tmp)
        (tmp / "driver.cpp").write_text(driver_src)
        exe = tmp / "debruijn_test"
        cmd = [CXX, "-std=c++17", "-Wall", "-Wextra", "-Werror",
               "-fexceptions",  # the real CLI TU keeps exceptions; prove we never throw anyway
               "-I", str(header_path.parent), str(tmp / "driver.cpp"), "-o", str(exe)]
        comp = subprocess.run(cmd, capture_output=True, text=True)
        if comp.returncode != 0:
            return comp.returncode, comp.stderr
        run = subprocess.run([str(exe)], capture_output=True, text=True, timeout=60)
        return run.returncode, run.stdout + run.stderr


class DeBruijnKeyParse(unittest.TestCase):
    def test_de_bruijn_order_12_is_1024_hex_chars(self):
        # The exact input the owner's reboot came from.
        self.assertEqual(1024, len(db_hexkey(12)))
        self.assertEqual(256, len(db_hexkey(10)))
        self.assertEqual(64, len(db_hexkey(8)))

    def test_parser_accepts_valid_and_rejects_bad_and_debruijn(self):
        rc, out = compile_run(HEADER, build_driver())
        self.assertEqual(0, rc, f"host test failed:\n{out}")
        self.assertIn("failures=0", out)

    def test_length_guard_is_load_bearing_mutation(self):
        # Flip the length cap (> 16 -> > 17). Over-16-digit keys then slip through, so the test
        # that expects them rejected must now FAIL. If it still passes, the guard does nothing.
        src = HEADER.read_text()
        self.assertIn("if (++digits > MAX_HEX_DIGITS) return false;", src,
                      "guard line not found - update the mutation test")
        mutated = src.replace("if (++digits > MAX_HEX_DIGITS) return false;",
                              "if (++digits > MAX_HEX_DIGITS + 1) return false;")
        self.assertNotEqual(src, mutated)
        with tempfile.TemporaryDirectory(prefix="brucia-debruijn-mut-") as tmp:
            mutant = Path(tmp) / "rf_hexkey.h"
            mutant.write_text(mutated)
            rc, out = compile_run(mutant, build_driver())
        self.assertNotEqual(0, rc, f"mutation survived - the guard is not load-bearing:\n{out}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
