#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
namespace brucia {
namespace companion {
struct CommandRequest;
// 0 = scanning, 409 = a BLE HID session owns the stack, 503 = BLE init failed.
int bleStart();
void bleStop();
void bleService();

bool dispatchBluetoothCommand(const CommandRequest &request);

void stopCompanionBluetoothControls();

} // namespace companion
} // namespace brucia
