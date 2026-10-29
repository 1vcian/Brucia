
// #ifndef __LORA_MENU_H__
// #define __LORA_MENU_H__
#ifndef __LORA_RF_H__
#define __LORA_RF_H__
#if !defined(LITE_VERSION)
#include "HWCDC.h"
#include <WString.h>
void lorachat();
void loraconf();

// --- Brucia companion: headless LoRa access ---------------------------------------
// Reuses this module's RadioLib driver (same pin helpers, Module/SX127x/SX126x objects
// and RX interrupt) so the phone can drive the radio without the on-device chat UI. The
// radio variant is auto-selected from the board wiring (SX1262 when a BUSY pin exists,
// else SX1276). All functions are silent (no TFT/Serial popups) so they are safe to call
// from inside the framed companion protocol.

// Bring up the radio at freqMHz with the given LoRa parameters. Pass <= 0 for bwKHz / sf /
// cr to keep the module defaults (SF9, 31.25 kHz, 4/8). Returns false if the LoRa pins are
// unconfigured or the radio fails to initialize.
bool lora_companion_begin(float freqMHz, float bwKHz, int sf, int cr);

// Transmit text on the running radio. Returns false if the radio was not started or TX failed.
bool lora_companion_send(const String &text);

// Non-blocking: pull one received packet if the RX interrupt flagged one. Returns 1 and fills
// data/rssi/snr on a packet, 0 otherwise. lora_companion_begin() must have run first.
int lora_companion_poll(String &data, float &rssi, float &snr);

// True once lora_companion_begin() has succeeded (radio is up and receiving).
bool lora_companion_ready();

// Tear the radio down (releases the RadioLib objects).
void lora_companion_end();
#endif
#endif
