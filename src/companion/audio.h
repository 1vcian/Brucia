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
// One owner for the shared I2S pins/mic (F21): FM audio TX drives the speaker's BCLK/WCLK/DOUT and
// its mic source reads the same mic as mic.spectrum/mic.pcm/mic_record. The newest user wins, as
// between speaker and mic: each start releases the other owner first (also from Console/Macros).
void speakerOutputRelease(const char *reason); // speaker_play file + spkr_pcm session
void fmAudioRelease(bool onlyMicSource);       // end FM audio TX (only a mic-sourced one if true)
void stopCompanionAudio();
// Recovery diagnostics: existing drop counters; false when not compiled in or not readable now.
bool fmAudioLostSamples(uint32_t &lost, bool &active);
bool micPcmDroppedSamples(uint32_t &dropped);

bool dispatchAudioCommand(const CommandRequest &request);

} // namespace companion
} // namespace brucia
