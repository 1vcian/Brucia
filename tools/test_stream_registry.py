"""Preprocess the actual lifecycle registry for the eight board-guard combinations."""
from itertools import product
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
STREAMS = "wifi.ap subghz.raw subghz.spectrum ble.scan wifi.sta wifi.probe wifi.monitor mic.spectrum mic.pcm".split()


class StreamRegistryTest(unittest.TestCase):
    def test_variants(self):
        for lite, mic, cc in product([False, True], repeat=3):
            flags = ["-D" + flag for enabled, flag in [(lite, "LITE_VERSION"), (mic, "COMPANION_HAS_MIC"),
                     (cc, "COMPANION_HAS_CC1101")] if enabled]
            source = ('#define BRUCIA_STREAM(id, name, poll, close) name poll close\n'
                      '#define BRUCIA_SERVICE(poll, close) poll close\n#include "src/companion/stream_features.def"\n')
            rows = subprocess.check_output(["c++", "-E", "-P", "-x", "c++", *flags, "-"], input=source,
                                           cwd=ROOT, text=True)
            with self.subTest(lite=lite, mic=mic, cc=cc):
                self.assertEqual(re.findall(r'"([^"]+)"', rows), STREAMS)
                self.assertEqual("micPcmService() micPcmStop()" in rows, mic)
                self.assertEqual("subghzSpecService() subghzSpecStop()" in rows, cc)
                self.assertEqual("evilPortalService() evilPortalStop(0, false)" in rows, not lite)


if __name__ == "__main__":
    unittest.main()
