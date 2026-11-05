#ifndef __BJS_ABORT_CORE_H__
#define __BJS_ABORT_CORE_H__
// Brucia companion: stopping a headless script (script_run) that sits in a native wait.
//
// Arduino-free so the host test (tools/test_firmware_script_abort.py) compiles it as is. The
// firmware side (interpreter.cpp, globals_js.cpp, companion/system.cpp) supplies the clock and
// the flags; nothing here touches hardware.
//
// The QuickJS interrupt handler only runs between bytecodes, so a native call (delay(), a radio
// read with a long timeout, a timer wait) used to keep the interpreter task, and every later
// script_run ("interpreter busy"), until it returned on its own; and once script_run gave up it
// cleared the deadline, so the script then ran on unbounded. Now an abort, once requested (the
// execution bound passed, the wall clock ran out or a stop arrived), stays requested until the
// interpreter task ends, and the waits below end within one slice of it.
#include <stdint.h>

// Wrap-safe "now is at or past deadline"; 0 means no deadline (interactive use, never passes).
inline bool bjsDeadlinePassed(uint32_t deadline, uint32_t now) {
    return deadline != 0 && (int32_t)(now - deadline) >= 0;
}

inline bool bjsAbortDue(bool abortRequested, uint32_t deadline, uint32_t now) {
    return abortRequested || bjsDeadlinePassed(deadline, now);
}

static const uint32_t BJS_SLEEP_SLICE_MS = 10;

// Sleep `ms` in slices of at most BJS_SLEEP_SLICE_MS, returning false as soon as the clock reports
// an abort (checked before every slice, so an abort already requested sleeps not at all).
// Clock: uint32_t now(); void sleep(uint32_t ms); bool aborted().
template <class Clock> bool bjsSleepSliced(uint32_t ms, Clock &clock) {
    const uint32_t start = clock.now();
    for (;;) {
        if (clock.aborted()) return false;
        const uint32_t elapsed = clock.now() - start;
        if (elapsed >= ms) return true;
        const uint32_t left = ms - elapsed;
        clock.sleep(left < BJS_SLEEP_SLICE_MS ? left : BJS_SLEEP_SLICE_MS);
    }
}

// ESC / any-key as a stopped script sees them (check() in globals.h asks on the interpreter task
// while the abort is set). Like a person pressing the key again and again: every other read of a
// key is "pressed", so a loop that stops on the key ends within two reads, and a loop that waits
// for the key to be released (`while (check(EscPress)) delay(10);`) ends too instead of spinning.
struct BjsStopKeys {
    bool escPressed = false;
    bool anyPressed = false;
    bool read(bool isEsc, bool isAny) {
        if (isEsc) return escPressed = !escPressed;
        if (isAny) return anyPressed = !anyPressed;
        return false;
    }
};

// script_run's wait for the interpreter task, one poll at a time (companion/system.cpp).
//   execDeadline: the execution bound (15 s); passing it requests the abort.
//   wallDeadline: the wall clock (20 s); passing it requests the abort and gives up waiting.
//   a stop (`stop` / `*_stop` CMD consumed by companionPollStop) requests the abort at once.
// The caller copies abortRequested into the interpreter's flag after every step and never clears
// it while the task still runs; the task clears it when it ends, and the caller drops the copy the
// last step made once the task is gone (so no abort outlives the run).
struct BjsRunWatch {
    enum Step { WAIT, DONE, GAVE_UP };
    uint32_t execDeadline;
    uint32_t wallDeadline;
    bool stopRequested;
    bool abortRequested;

    BjsRunWatch(uint32_t exec, uint32_t wall)
        : execDeadline(exec), wallDeadline(wall), stopRequested(false), abortRequested(false) {}

    Step step(uint32_t now, bool taskRunning, bool stopPolled) {
        if (!taskRunning) return DONE; // a stop that arrives after the end changes nothing
        if (stopPolled) stopRequested = true;
        if (stopRequested || bjsDeadlinePassed(execDeadline, now)) abortRequested = true;
        if (bjsDeadlinePassed(wallDeadline, now)) {
            abortRequested = true;
            return GAVE_UP;
        }
        return WAIT;
    }
};

// The script_run reply once the wait ended: nullptr = ok (ACK out = the captured output), otherwise
// the ok:false message. A stop wins over the output: the script did not run to its end.
inline const char *bjsRunFailure(bool finished, bool stopRequested) {
    if (!finished) return "script timeout";
    if (stopRequested) return "script stopped";
    return nullptr;
}

#endif
