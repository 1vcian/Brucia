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
void drawCompanionScreen(bool connected, bool wifi, bool ble, bool subghz);
bool companionPollStop();

} // namespace companion
} // namespace brucia
