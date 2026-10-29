#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
namespace brucia {
namespace companion {
struct CommandRequest;
void fmAudioService();
void micStart();
void micStop();
void micService();
void micPcmStart();
void micPcmStop();
void micPcmService();
void stopCompanionAudio();

bool dispatchAudioCommand(const CommandRequest &request);

} // namespace companion
} // namespace brucia
