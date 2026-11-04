#pragma once
#include <Arduino.h>
namespace brucia {
namespace companion {
// `diag` CMD: reset reason, uptime, heap/PSRAM and the bounded link/drop counters since boot.
// Sent only when the app asks; see proto/PROTOCOL.md "Recovery diagnostics".
void runDiagCommand(uint32_t id);

} // namespace companion
} // namespace brucia
