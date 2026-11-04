#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
#include "subghz_spectrum_plan.h"
namespace brucia {
namespace companion {
struct CommandRequest;
bool subghzStart();
void subghzStop();
void subghzService();
static const int SPEC_MAX_POINTS = 128; // max frequency points per sweep (one frame holds the array)
bool subghzSpecStart(const SpecPlan &plan);
void subghzSpecStop();
void subghzSpecService();

bool dispatchSubghzCommand(const CommandRequest &request);

} // namespace companion
} // namespace brucia
