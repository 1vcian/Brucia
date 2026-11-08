#!/usr/bin/env python3
"""Cardputer ADV audio (ported from upstream Bruce 28f07c94, reworked for Brucia's I2S ownership).

Static checks of src/modules/others/audio.cpp: MCLK is not routed onto LRCLK on the Cardputer, and
the short-tone path opens its I2S channel per tone on any free port and deletes it on every exit
after creation (upstream kept a channel open for the whole session). No audio is played here.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AUDIO = (ROOT / "src/modules/others/audio.cpp").read_text()


def body(src, signature):
    start = src.index(signature)
    i = src.index("{", start) + 1
    depth = 1
    while depth:
        depth += {"{": 1, "}": -1}.get(src[i], 0)
        i += 1
    return src[start:i]


class CardputerTone(unittest.TestCase):
    def test_mclk_not_routed_on_cardputer(self):
        out = body(AUDIO, "static AudioOutputI2S *createConfiguredAudioOutput()")
        guard = out[out.index("#if defined(ARDUINO_M5STACK_CARDPUTER)"):out.index("#endif")]
        self.assertIn("audioout->SetPinout(BCLK, WCLK, DOUT);", guard)
        self.assertIn("audioout->SetPinout(BCLK, WCLK, DOUT, MCLK);", guard.split("#else", 1)[1])

    def test_tone_channel_is_per_call_and_always_deleted(self):
        tone = body(AUDIO, "static void cardputerTone(unsigned int frequency, unsigned long duration)")
        self.assertIn("I2S_NUM_AUTO", tone)
        self.assertNotRegex(AUDIO, r"static\s+i2s_chan_handle_t\s+cardputerToneTx")
        after = tone[tone.index("i2s_new_channel("):]
        # Every return after the channel exists deletes it first.
        failed_new = after[:after.index("i2s_channel_init_std_mode")]
        for m in re.finditer(r"\breturn\b", after):
            before = after[:m.start()]
            if m.start() < len(failed_new):
                continue  # i2s_new_channel failed: nothing to delete
            self.assertGreater(before.rfind("i2s_del_channel(tx)"), before.rfind("i2s_channel_init_std_mode"))
        self.assertTrue(tone.rstrip().endswith("_setup_codec_speaker(false);\n}"))
        self.assertLess(tone.rindex("i2s_channel_disable(tx)"), tone.rindex("i2s_del_channel(tx)"))
        # The DMA tail is replaced with silence before the channel stops.
        self.assertLess(tone.index("memset(samples, 0, sizeof(samples));"), tone.rindex("i2s_channel_disable(tx)"))

    def test_cardputer_tone_is_wired_into_tone(self):
        t = body(AUDIO, "void _tone(unsigned int frequency, unsigned long duration)")
        self.assertIn("#elif defined(ARDUINO_M5STACK_CARDPUTER)\n    cardputerTone(frequency, duration);", t)


if __name__ == "__main__":
    unittest.main()
