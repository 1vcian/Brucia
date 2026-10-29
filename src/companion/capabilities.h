#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
namespace brucia {
namespace companion {
bool companionNrf24Present();
void companionWarmCaps();
void sendCaps(uint32_t id);

} // namespace companion
} // namespace brucia
