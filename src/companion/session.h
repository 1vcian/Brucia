#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>

namespace brucia {
namespace companion {

struct CompanionSession {
    bool helloSeen;
    bool subWifiAp;
    bool subBleOn;
    bool subGhzOn;
    bool leaveForRemote;
};

void companionSetScreen(bool on);
void companionSetLed(bool on);
// Live lite-control state for CAPS (scrOn/ledOn): false while the app holds that output off.
bool companionScreenOn();
bool companionLedOn();
// scr_bright <n>: set the backlight without saving; a visible level ends a `scr 0` hold.
void companionSetScreenLevel(uint8_t level);
void drawCompanionScreen(bool connected, bool wifi, bool ble, bool subghz);
bool companionPollStop();

} // namespace companion
} // namespace brucia
