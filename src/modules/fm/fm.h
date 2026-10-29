#ifndef __FM_H__
#define __FM_H__
#ifndef LITE_VERSION
#include "core/display.h"
#include "core/mykeyboard.h"
#include <Adafruit_Si4713.h>
#include <globals.h>

void fm_live_run(bool reserved = true);

void fm_ta_run();

bool fm_begin();

bool fm_setup(bool traffic_alert = false, bool silent = false);

void fm_stop();

void fm_spectrum();

// Brucia companion: start a silent FM broadcast at freq10k (in 10 kHz units,
// e.g. 10230 = 102.30 MHz) with optional RDS station text. Returns false if the
// Si4713 is not found. TX power is kept at the low end of the library range.
bool fm_tx_companion(uint16_t freq10k, const String &rdsText);

// Brucia companion: configure the Si4713 for DIGITAL (I2S) audio input and start a
// silent TX carrier at freq10k (10 kHz units). The PCM audio itself is clocked into
// the Si4713 over its I2S slave pins (DCLK/DFS/DIN) - which must be physically wired
// to the ESP's I2S bus; that routing exists only on hardware variants that wire the
// Si4713 for digital audio. sampleRateHz is the PCM rate presented on the I2S bus
// (the Si4713 digital range is 32-48 kHz per AN332). Returns false if the Si4713 is
// not found. TX power is kept at the low end of the library range.
bool fm_tx_digital_begin(uint16_t freq10k, uint32_t sampleRateHz);

#endif
#endif
