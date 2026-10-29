#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
namespace brucia {
namespace companion {
struct CommandRequest;

bool dispatchSystemCommand(const CommandRequest &request);

} // namespace companion
} // namespace brucia
