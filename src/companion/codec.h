#pragma once
#include <Arduino.h>
namespace brucia {
namespace companion {
String b64encode(const uint8_t *d, size_t n);
uint8_t *companionB64decode(const String &in, size_t *outLen);

} // namespace companion
} // namespace brucia
