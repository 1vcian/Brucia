#!/usr/bin/env python3
"""Compile the real subghz.spectrum sweep validation (src/companion/subghz_spectrum_plan.h)
with UBSan and check its accept/reject rules and kHz grid quantization."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
TEST = r'''
#include "subghz_spectrum_plan.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <limits>
using namespace brucia::companion;
static bool ok(double s, double e, double st, uint32_t sk, uint32_t stk, int n) {
    SpecPlan p = {};
    const char *err = planSpectrumSweep(s, e, st, 128, p);
    if (err) { std::printf("unexpected reject %g %g %g: %s\n", s, e, st, err); return false; }
    if (p.startKHz != sk || p.stepKHz != stk || p.count != n) {
        std::printf("plan %g %g %g -> %u %u %d\n", s, e, st, p.startKHz, p.stepKHz, p.count);
        return false;
    }
    return true;
}
static bool bad(double s, double e, double st) {
    SpecPlan p = {9, 9, 9};
    const char *err = planSpectrumSweep(s, e, st, 128, p);
    if (!err) { std::printf("unexpected accept %g %g %g\n", s, e, st); return false; }
    return p.startKHz == 9 && p.stepKHz == 9 && p.count == 9; // untouched on reject
}
int main() {
    const double nan = std::numeric_limits<double>::quiet_NaN();
    const double inf = std::numeric_limits<double>::infinity();
    assert(ok(300, 348, 1, 300000, 1000, 49));            // app default window
    assert(ok(433.92, 433.92, 1, 433920, 1000, 1));       // single point
    assert(ok(280, 928, 6, 280000, 6000, 109));           // full band edges
    assert(ok(433.0, 434.0, 0.05, 433000, 50, 21));       // 0.05 MHz is exactly 50 kHz, not 49
    assert(ok(868.35, 868.36, 0.001, 868350, 1, 11));     // 1 kHz minimum step
    assert(ok(300, 300.0049, 0.001, 300000, 1, 6));       // end rounds to the nearest kHz
    assert(ok(300, 301.5, 1, 300000, 1000, 2));           // last point never exceeds f_end
    assert(ok(300, 427, 1, 300000, 1000, 128));           // exactly the point limit
    assert(bad(nan, 348, 1) && bad(300, nan, 1) && bad(300, 348, nan));
    assert(bad(inf, 348, 1) && bad(300, inf, 1) && bad(300, 348, inf) && bad(-inf, 348, 1));
    assert(bad(279.9, 300, 1) && bad(300, 928.1, 1) && bad(0, 348, 1) && bad(-300, 348, 1));
    assert(bad(1e300, 348, 1) && bad(300, 1e300, 1));
    assert(bad(348, 300, 1));                             // reversed range is not swapped
    assert(bad(300, 348, 0) && bad(300, 348, -1) && bad(300, 348, 0.0004) && bad(300, 348, 1e300));
    assert(bad(300, 428, 1));                             // 129 points: rejected, step not widened
    assert(bad(280, 928, 0.01));
    SpecPlan p = {};
    assert(planSpectrumSweep(300, 300, 1, 0, p) != nullptr);
    std::printf("Firmware spectrum plan PASS (UBSan)\n");
    return 0;
}
'''


def run():
    with tempfile.TemporaryDirectory(prefix="brucia-spectrum-") as tmp:
        tmp = Path(tmp)
        (tmp / "test.cpp").write_text(TEST)
        exe = tmp / "spectrum"
        subprocess.run([os.environ.get("CXX", "c++"), "-std=c++17", "-fsanitize=undefined",
                        "-fno-sanitize-recover=undefined", "-I", str(ROOT / "src/companion"),
                        str(tmp / "test.cpp"), "-o", str(exe)], check=True)
        return subprocess.run([str(exe)], check=True, capture_output=True, text=True).stdout


class FirmwareSpectrumPlanTest(unittest.TestCase):
    def test_validation_and_khz_grid(self):
        self.assertIn("PASS", run())


if __name__ == "__main__":
    print(run(), end="")
