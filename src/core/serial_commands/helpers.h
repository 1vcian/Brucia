#ifndef __SERIAL_HELPERS_H__
#define __SERIAL_HELPERS_H__
#ifndef LITE_VERSION
#include <precompiler_flags.h>

#include <Arduino.h>
#include <PSRamFS.h>

bool _setupPsramFs();
char *_readFileFromSerial(size_t fileSizeChar = SAFE_STACK_BUFFER_SIZE);
// Brucia: true while a script runs in companion mode and reaches this CLI command through
// serial.cmd() or a CLI-backed JS helper (the command runs on the interpreter task). Modes that run
// until a key is pressed at the device are refused there; the app's own routed commands keep them.
bool cliFromCompanionScript();

#endif
#endif
