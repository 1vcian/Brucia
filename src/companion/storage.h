#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
namespace brucia {
namespace companion {
struct CommandRequest;

bool dispatchStorageCommand(const CommandRequest &request);
void startCompanionStorage();
void serviceCompanionStorage();
void stopCompanionStorage();

} // namespace companion
} // namespace brucia
