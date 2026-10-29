#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
namespace brucia {
namespace companion {
struct CommandRequest;

void stopCompanionNetwork();

bool dispatchNetworkCommand(const CommandRequest &request);

} // namespace companion
} // namespace brucia
