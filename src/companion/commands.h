#pragma once
#include "session.h"
#include <Arduino.h>
#include <ArduinoJson.h>
namespace brucia {
namespace companion {
void handleCompanionCommand(JsonDocument &in, uint32_t id, CompanionSession &session);
// Companion session start/end: swap Bruce's global `serialDevice` (the CLI's console) for a device
// with no input and no output, so nothing outside the companion frame reader reads or writes the
// framed port through it. Restores the previous device on release. Bruce outside companion mode is
// unaffected.
void companionClaimSerialDevice(bool claim);

}
} // namespace brucia
