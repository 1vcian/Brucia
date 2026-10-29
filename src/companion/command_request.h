#pragma once
#include "session.h"
#include <Arduino.h>
#include <ArduinoJson.h>

namespace brucia {
namespace companion {
// A parsed protocol envelope plus an immutable snapshot of the current session.
// Services own their command matching; only the session loop owns serial/mode state.
struct CommandRequest {
    const String &command;
    uint32_t id;
    const JsonDocument &envelope;
    const CompanionSession &session;
};
} // namespace companion
} // namespace brucia
