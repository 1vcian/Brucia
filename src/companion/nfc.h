#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
class RFIDInterface;
namespace brucia {
namespace companion {
struct CommandRequest;
RFIDInterface *createCompanionRfidModule();

bool dispatchNfcCommand(const CommandRequest &request);

} // namespace companion
} // namespace brucia
