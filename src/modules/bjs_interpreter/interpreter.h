#ifndef __BJS_INTERPRETER_H__
#define __BJS_INTERPRETER_H__
#if !defined(LITE_VERSION) && !defined(DISABLE_INTERPRETER)
#include "core/display.h"

extern TaskHandle_t interpreterTaskHandler;

// Credits to https://github.com/justinknight93/Doolittle
// This functionality is dedicated to @justinknight93 for providing such a nice example! Consider yourself a
// part of the team!

void interpreterHandler(void *pvParameters);
void run_bjs_script();
bool run_bjs_script_headless(char *code);
bool run_bjs_script_headless(FS &fs, const String &filename);

// Brucia companion: headless output capture + execution bound. While a capture
// buffer is set, BruceJS console/print output is appended to it (bounded) instead
// of going to stdout, so a phone-supplied script can be run headless and its output
// returned over the companion protocol. g_bjsDeadlineMs (0 == no bound) is checked by
// the interpreter's interrupt handler to stop a runaway script.
void bjs_capture_begin(String *buf);
void bjs_capture_end();
bool bjs_capture_active();
void bjs_capture_write(const char *s, size_t n);
extern volatile uint32_t g_bjsDeadlineMs;

String getScriptsFolder(FS *&fs);
std::vector<Option> getScriptsOptionsList(
    const String &currentPath = "", bool saveStartupScript = false, int rememberedIndex = 0
);

#endif
#endif
