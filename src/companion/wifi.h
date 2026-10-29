#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
#include <WiFi.h>
namespace brucia {
namespace companion {
struct CommandRequest;
const char *encToStr(wifi_auth_mode_t enc);
void sendWifiApEvent(const String &bssid, const String &ssid, int ch, int rssi, const char *enc);
void staStart();
void staStop();
void staService();
void probeStart();
void probeStop();
void probeService();
void monStart(int channel, const char *bssidStr);
void monStop();
void monService();
void evilPortalStop(uint32_t id, bool ack);
void evilPortalService();

bool dispatchWifiCommand(const CommandRequest &request);

} // namespace companion
} // namespace brucia
