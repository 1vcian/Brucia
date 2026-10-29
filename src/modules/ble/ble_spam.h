#pragma once

#include <Arduino.h>
#include <NimBLEBeacon.h>
#include <NimBLEDevice.h>
#include <NimBLEServer.h>
#include <NimBLEUtils.h>
void spamMenu();

// Brucia: headless, time-bounded Apple ProximityPair spam driven by the companion app.
// copies = how many distinct random advertisements to blast per cycle (burst), clamped 1..100.
void runBleSpamBounded(uint32_t durationMs, uint8_t copies = 1);

// Brucia: headless, time-bounded Google Fast Pair (Android) spam driven by the companion app.
// copies = how many distinct random advertisements to blast per cycle (burst), clamped 1..100.
void runBleFastPairSpamBounded(uint32_t durationMs, uint8_t copies = 1);

// Brucia: headless, time-bounded Microsoft/Windows Swift Pair spam driven by the companion app.
// copies = how many distinct random advertisements to blast per cycle (burst), clamped 1..100.
void runBleSwiftPairSpamBounded(uint32_t durationMs, uint8_t copies = 1);

// Brucia: headless, time-bounded Samsung (Galaxy Buds/Watch) spam driven by the companion app.
// copies = how many distinct random advertisements to blast per cycle (burst), clamped 1..100.
void runBleSamsungSpamBounded(uint32_t durationMs, uint8_t copies = 1);

void ibeacon(
    const char *DeviceName = "Bruce iBeacon", const char *BEACON_UUID = "8ec76ea3-6668-48da-9866-75be8bc86f4d",
    int ManufacturerId = 0x4C00
);

// Brucia: headless, time-bounded iBeacon advertiser (companion). Advertises an iBeacon frame with
// the given name/UUID/major/minor for durationMs, then stops.
void runIbeaconBounded(
    uint32_t durationMs, const char *name = "Bruce iBeacon",
    const char *uuid = "8ec76ea3-6668-48da-9866-75be8bc86f4d", uint16_t major = 5, uint16_t minor = 88
);
