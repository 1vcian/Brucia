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
void speakerPlayService();
void speakerPlayRelease(const char *reason);
void stopCompanionAudio();
// Recovery diagnostics: existing drop counters; false when not compiled in or not readable now.
bool fmAudioLostSamples(uint32_t &lost, bool &active);
bool micPcmDroppedSamples(uint32_t &dropped);

bool dispatchAudioCommand(const CommandRequest &request);

} // namespace companion
} // namespace brucia
