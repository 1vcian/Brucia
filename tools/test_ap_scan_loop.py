"""Exercise the firmware polling loop with the Arduino scan driver's result contract."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PROGRAM = r"""
#include "src/companion/ap_scan_loop.h"
#include <cassert>
struct Driver {
    int starts = 0, polls = 0, clears = 0, startResult = -2, pollResult = -1;
    int scanNetworks(bool async, bool hidden) {
        assert(async && hidden); ++starts; return startResult;
    }
    int scanComplete() { ++polls; return pollResult; }
    void scanDelete() { ++clears; }
};
int main() {
    brucia::companion::ApScanLoop loop;
    Driver driver;
    int failures = 0, batches = 0, aps = 0;
    auto tick = [&](uint32_t now) {
        loop.poll(now, driver, -1, [&](int n) { ++batches; aps += n; }, [&] { ++failures; });
    };
    const uint32_t start = UINT32_MAX - 99;
    tick(start); // failed start
    tick(start + 999);
    assert(driver.starts == 1 && !driver.polls && failures == 1);
    driver.startResult = -1;
    tick(start + 1000); tick(start + 1001); // running
    driver.pollResult = -2;
    tick(start + 1002); // failed completion
    tick(start + 2001);
    assert(driver.starts == 2 && driver.polls == 2 && failures == 2 && !batches);
    tick(start + 2002);
    driver.pollResult = 3;
    tick(start + 2003);
    assert(driver.starts == 3 && aps == 3 && batches == 1 && driver.clears == 3);
    driver.startResult = -2;
    tick(start + 2004);
    loop.reset(); // explicit re-subscription clears previous backoff
    driver.startResult = 0;
    tick(start + 2005);
    assert(failures == 3 && batches == 2 && aps == 3 && driver.starts == 5);
}
"""


class ApScanLoopTest(unittest.TestCase):
    def test_driver_failures_recover_across_tick_wrap(self):
        with tempfile.TemporaryDirectory() as tmp:
            binary = Path(tmp) / "test"
            subprocess.run(["c++", "-std=c++17", "-I", str(ROOT), "-x", "c++", "-", "-o", str(binary)],
                           input=PROGRAM, text=True, check=True)
            subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    unittest.main()
