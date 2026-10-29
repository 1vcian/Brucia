#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
namespace brucia {
namespace companion {
struct CommandRequest;
void subghzStart();
void subghzStop();
void subghzService();
void subghzSpecStart(float fStart, float fEnd, float fStep);
void subghzSpecStop();
void subghzSpecService();

bool dispatchSubghzCommand(const CommandRequest &request);

} // namespace companion
} // namespace brucia
