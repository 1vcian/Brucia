#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
namespace brucia {
namespace companion {
struct CommandRequest;
void bleStart();
void bleStop();
void bleService();

bool dispatchBluetoothCommand(const CommandRequest &request);

void stopCompanionBluetoothControls();

} // namespace companion
} // namespace brucia
