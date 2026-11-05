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

// Brucia companion: stop a headless script that sits in a native wait (see bjs_abort_core.h).
// g_bjsAbort is set by script_run (execution bound passed, wall clock out, or a stop CMD) and
// cleared only by the interpreter task when it ends, together with g_bjsDeadlineMs.
//   bjs_abort_requested(): g_bjsAbort, or the deadline passed (the interrupt handler's test)
//   bjs_sleep_ms(ms): delay() for natives; while a deadline is set it sleeps in 10 ms slices and
//     returns false once an abort is requested; with no deadline (interactive use) it is a plain
//     vTaskDelay as before
//   bjsStopKeyPressed(btn): check() in globals.h asks it while g_bjsAbort is set; EscPress /
//     AnyKeyPress read as pressed every other read (BjsStopKeys), on the interpreter task only, so
//     Bruce loops a native is in end the way ESC at the device ends them, and wait-for-release
//     loops still end. Other tasks (the companion loop) never see it.
extern volatile bool g_bjsAbort;
bool bjs_abort_requested();
bool bjs_sleep_ms(int32_t ms);

String getScriptsFolder(FS *&fs);
std::vector<Option> getScriptsOptionsList(
    const String &currentPath = "", bool saveStartupScript = false, int rememberedIndex = 0
);

#endif
#endif
