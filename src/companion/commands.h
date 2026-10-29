#pragma once
#include "session.h"
#include <Arduino.h>
#include <ArduinoJson.h>
namespace brucia {
namespace companion {
void handleCompanionCommand(JsonDocument &in, uint32_t id, CompanionSession &session);

}
} // namespace brucia
