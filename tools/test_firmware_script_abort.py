#!/usr/bin/env python3
"""Host checks for stopping a companion script_run that sits in a native wait.

Compiles src/modules/bjs_interpreter/bjs_abort_core.h (Arduino-free) with a C++ driver on a fake
clock and checks:
  - bjsDeadlinePassed / bjsAbortDue: 0 is "no deadline", millis() wrap-around, abort flag wins;
  - BjsStopKeys: ESC / any-key read as pressed every other read (per key), so a loop that stops
    on the key ends within two reads and a wait-for-release loop ends too;
  - bjsSleepSliced: sleeps the full time in <= 10 ms slices when nothing aborts, ends within one
    slice of an abort requested mid-way, sleeps not at all when the abort is already requested,
    and survives a clock that wraps during the sleep;
  - BjsRunWatch (script_run's wait): the execution bound requests the abort but keeps waiting,
    the wall clock requests it and gives up, a stop requests it at once, a task that already
    ended is DONE (a late stop changes nothing), and the abort is never withdrawn;
  - bjsRunFailure: the ok/ok:false reply for finished/stopped/timed out runs;
  - an end-to-end model: a 60 s native delay() in a script_run ends at the 15 s bound (one slice
    late at most), so the next script_run finds the interpreter free; with the old wiring (no
    sliced sleep, deadline cleared when script_run gives up) the same run held it for 60 s.
Source checks (static) pin the firmware wiring: the interrupt handler, delay(), the timer loop,
wifi.connect / nrf24.receive / notify, the check() hook in globals.h (interpreter task, ESC and
any-key only), the task clearing the abort/deadline/handle on every exit, script_run polling
companionPollStop and never clearing the abort itself, and the removed wait for interpreter_state
to leave 1 (nothing changes it; every run used to sit out the 20 s wall clock first).
Not covered: real FreeRTOS scheduling, QuickJS's interrupt cadence, the Bruce module loops
themselves, hardware.
Run: python3 tools/test_firmware_script_abort.py
"""
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JS = ROOT / "src/modules/bjs_interpreter"
sys.path.insert(0, str(ROOT / "tools"))
from test_companion_prompts import body_of, strip_code  # noqa: E402

DRIVER = r'''
#include "bjs_abort_core.h"
#include <cstdio>
#include <string>
#include <vector>

static int failures = 0, checks = 0;
static void check(bool ok, const char *what) {
    checks++;
    if (!ok) { failures++; std::printf("FAIL: %s\n", what); }
}

// Fake clock: sleep() advances time; an optional abort time flips aborted().
struct FakeClock {
    uint32_t t;
    uint32_t abortAt;      // 0 = never
    bool abortNow = false;
    std::vector<uint32_t> slices;
    explicit FakeClock(uint32_t start, uint32_t abortAt_ = 0) : t(start), abortAt(abortAt_) {}
    uint32_t now() { return t; }
    void sleep(uint32_t ms) { slices.push_back(ms); t += ms; }
    bool aborted() { return abortNow || (abortAt != 0 && bjsDeadlinePassed(abortAt, t)); }
};

static uint32_t maxSlice(const FakeClock &c) {
    uint32_t m = 0;
    for (uint32_t s : c.slices) if (s > m) m = s;
    return m;
}

int main() {
    {   // deadlines
        check(!bjsDeadlinePassed(0, 0) && !bjsDeadlinePassed(0, 123456), "0 is no deadline");
        check(!bjsDeadlinePassed(1000, 999) && bjsDeadlinePassed(1000, 1000) && bjsDeadlinePassed(1000, 5000),
              "before / at / after");
        check(!bjsDeadlinePassed(5u, 0xFFFFFFF0u) && bjsDeadlinePassed(5u, 6u), "wraps: deadline past 2^32");
        check(bjsDeadlinePassed(0xFFFFFFF0u, 3u), "wraps: now past 2^32");
        check(bjsAbortDue(true, 0, 0) && bjsAbortDue(false, 10, 10) && !bjsAbortDue(false, 10, 9) &&
              !bjsAbortDue(false, 0, 99999), "abort flag or deadline");
    }
    {   // a full sleep, nothing aborts
        FakeClock c(1000);
        check(bjsSleepSliced(95, c), "completes");
        check(c.t == 1095, "exact duration");
        check(maxSlice(c) <= BJS_SLEEP_SLICE_MS && c.slices.size() == 10, "slices of <= 10 ms");
        FakeClock z(0);
        check(bjsSleepSliced(0, z) && z.slices.empty(), "0 ms: no sleep");
    }
    {   // abort mid-way: ends within one slice
        FakeClock c(0, 15000);
        check(!bjsSleepSliced(60000, c), "aborted");
        check(c.t >= 15000 && c.t <= 15000 + BJS_SLEEP_SLICE_MS, "ends within one slice of the abort");
    }
    {   // already aborted: no sleep at all
        FakeClock c(500);
        c.abortNow = true;
        check(!bjsSleepSliced(60000, c) && c.slices.empty() && c.t == 500, "pre-aborted: returns at once");
    }
    {   // clock wraps during the sleep
        FakeClock c(0xFFFFFF00u);
        check(bjsSleepSliced(1000, c), "completes across the wrap");
        check(c.t == 0xFFFFFF00u + 1000u, "duration across the wrap");
    }
    {   // stop keys: every other read is pressed, per key; other keys never
        BjsStopKeys k;
        int reads = 0;
        while (reads < 10) { reads++; if (k.read(true, false)) break; }
        check(reads <= 2, "a loop that stops on ESC ends within two reads");
        int spins = 0;
        while (k.read(true, false) && spins < 1000) spins++;
        check(spins <= 1, "a wait-for-release loop on ESC ends");
        BjsStopKeys a;
        int anyTrue = 0, escTrue = 0;
        for (int i = 0; i < 6; i++) { anyTrue += a.read(false, true); escTrue += a.read(true, false); }
        check(anyTrue == 3 && escTrue == 3, "ESC and any-key alternate independently");
        check(!a.read(false, false) && !a.read(false, false), "other keys never pressed");
    }
    {   // script_run watch: execution bound -> abort, keep waiting; wall clock -> give up
        BjsRunWatch w(15000, 20000);
        check(w.step(14999, true, false) == BjsRunWatch::WAIT && !w.abortRequested, "before the bound");
        check(w.step(15000, true, false) == BjsRunWatch::WAIT && w.abortRequested, "bound: abort, still waiting");
        check(w.step(16000, false, false) == BjsRunWatch::DONE && w.abortRequested, "ended after the abort");
        check(bjsRunFailure(true, w.stopRequested) == nullptr, "interrupted by the bound: ok with its output");
        BjsRunWatch g(15000, 20000);
        g.step(15000, true, false);
        check(g.step(20000, true, false) == BjsRunWatch::GAVE_UP && g.abortRequested, "wall clock: give up");
        check(bjsRunFailure(false, g.stopRequested) != nullptr, "not finished -> failure");
    }
    {   // a stop requests the abort at once; a stop after the end changes nothing
        BjsRunWatch w(15000, 20000);
        check(w.step(100, true, true) == BjsRunWatch::WAIT && w.abortRequested && w.stopRequested, "stop");
        check(w.step(120, true, false) == BjsRunWatch::WAIT && w.abortRequested, "abort never withdrawn");
        check(w.step(140, false, false) == BjsRunWatch::DONE, "task ended");
        const char *m = bjsRunFailure(true, w.stopRequested);
        check(m != nullptr && std::string(m) == "script stopped", "stopped reply");
        BjsRunWatch late(15000, 20000);
        check(late.step(100, false, true) == BjsRunWatch::DONE && !late.stopRequested && !late.abortRequested,
              "late stop ignored");
        check(std::string(bjsRunFailure(false, false)) == "script timeout" &&
              std::string(bjsRunFailure(false, true)) == "script timeout", "timeout wins when not finished");
    }
    {   // wrap-around deadlines in the watch
        BjsRunWatch w(0xFFFFFFF0u + 15000u, 0xFFFFFFF0u + 20000u);
        check(w.step(0xFFFFFFF0u, true, false) == BjsRunWatch::WAIT && !w.abortRequested, "wrap: start");
        check(w.step(0xFFFFFFF0u + 15000u, true, false) == BjsRunWatch::WAIT && w.abortRequested, "wrap: bound");
    }
    {   // end-to-end model: script_run(delay(60000)), polled every 20 ms like system.cpp
        auto run = [](bool newWiring, uint32_t &busyUntil, bool &replyOk) {
            const uint32_t start = 0, exec = start + 15000, wall = start + 20000;
            bool abortFlag = false;
            uint32_t deadline = exec;
            // interpreter task: one native delay(60000)
            struct Clock {
                uint32_t *t; bool *abortFlag; uint32_t *deadline;
                uint32_t now() { return *t; }
                void sleep(uint32_t ms) { *t += ms; }
                bool aborted() { return bjsAbortDue(*abortFlag, *deadline, *t); }
            };
            uint32_t taskT = start;
            Clock tc{&taskT, &abortFlag, &deadline};
            uint32_t taskEnd;
            if (newWiring) {
                bjsSleepSliced(60000, tc);         // returns at the bound
                taskEnd = taskT;
            } else {
                taskEnd = start + 60000;           // plain vTaskDelay(60000)
            }
            BjsRunWatch w(exec, wall);
            uint32_t t = start;
            for (;;) {
                BjsRunWatch::Step s = w.step(t, t < taskEnd, false);
                if (w.abortRequested) abortFlag = true;
                if (s != BjsRunWatch::WAIT) { replyOk = (s == BjsRunWatch::DONE); break; }
                t += 20;
            }
            if (!newWiring) deadline = 0;          // old script_run cleared the bound when giving up
            busyUntil = taskEnd;
        };
        uint32_t busy = 0; bool ok = false;
        run(true, busy, ok);
        check(ok && busy <= 15000 + BJS_SLEEP_SLICE_MS, "new: delay(60000) ends at the 15 s bound, reply ok");
        run(false, busy, ok);
        check(!ok && busy == 60000, "old: script timeout and the interpreter busy for 60 s");
    }
    std::printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
'''


class ScriptAbortCore(unittest.TestCase):
    def test_core_on_host(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "driver.cpp"
            exe = Path(tmp) / "driver"
            src.write_text(DRIVER)
            cxx = os.environ.get("CXX", "c++")
            subprocess.run([cxx, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-fsanitize=undefined",
                            "-fno-sanitize-recover=all", "-I", str(JS), str(src), "-o", str(exe)], check=True)
            res = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
            self.assertIn(" 0 failures", res.stdout)


def code(rel):
    return strip_code((ROOT / rel).read_text())


class ScriptAbortWiring(unittest.TestCase):
    def test_interpreter(self):
        src = code("src/modules/bjs_interpreter/interpreter.cpp")
        handler = body_of(src, "static int bjs_interrupt_handler(")
        self.assertIn("return bjs_abort_requested() ? 1 : 0;", handler)
        req = body_of(src, "bool bjs_abort_requested()")
        self.assertIn("if (g_bjsAbort) return true;", req)
        self.assertIn("deadline != 0 && bjsDeadlinePassed(deadline, millis())", req)
        sleep = body_of(src, "bool bjs_sleep_ms(int32_t ms)")
        # Interactive use (no companion bound, no abort): the plain delay as before.
        self.assertRegex(sleep, r"if \(g_bjsDeadlineMs == 0 && !g_bjsAbort\) \{\s*vTaskDelay\(pdMS_TO_TICKS\(ms\)\);\s*return true;")
        self.assertIn("bjsSleepSliced(", sleep)
        key = body_of(src, "bool bjsStopKeyPressed(volatile bool &btn)")
        self.assertIn("xTaskGetCurrentTaskHandle() != interpreterTaskHandler) return false;", key)
        self.assertIn("return s_stopKeys.read(&btn == &EscPress, &btn == &AnyKeyPress);", key)
        self.assertLess(key.index("return false;"), key.index("s_stopKeys.read("))
        fin = body_of(src, "static void bjs_task_finished()")
        for stmt in ("g_bjsDeadlineMs = 0;", "g_bjsAbort = false;", "interpreterTaskHandler = NULL;"):
            self.assertLess(fin.index(stmt), fin.index("interpreter_state = -1;"), stmt)
        task = body_of(src, "void interpreterHandler(void *pvParameters)")
        # Every vTaskDelete(NULL) exit goes through bjs_task_finished().
        self.assertEqual(task.count("vTaskDelete(NULL);"), task.count("bjs_task_finished();\n        vTaskDelete(NULL);")
                         + task.count("bjs_task_finished();\n    vTaskDelete(NULL);"))
        self.assertNotIn("interpreter_state = -1;", task)
        for sig in ("bool run_bjs_script_headless(char *code)", "bool run_bjs_script_headless(FS &fs, const String &filename)"):
            body = body_of(src, sig)
            self.assertLess(body.index("g_bjsAbort = false;"), body.index("startInterpreterTask();"), sig)

    def test_natives(self):
        g = code("src/modules/bjs_interpreter/globals_js.cpp")
        delay = body_of(g, "JSValue native_delay(")
        self.assertIn("bjs_sleep_ms(ms)", delay)
        self.assertNotIn("vTaskDelay", delay)
        timers = body_of(g, "void run_timers(JSContext *ctx)")
        self.assertIn("while (interpreter_state >= 0 && !bjs_abort_requested())", timers)
        self.assertIn("bjs_sleep_ms((int32_t)min_delay)", timers)
        self.assertNotRegex(timers, r"\bdelay\(min_delay\)")
        wifi = body_of(code("src/modules/bjs_interpreter/wifi_js.cpp"), "JSValue native_wifiConnect(")
        self.assertIn("if (!bjs_sleep_ms(1000)) break;", wifi)
        nrf = code("src/modules/bjs_interpreter/nrf24_js.cpp")
        self.assertIn("if (bjs_abort_requested()) break;", nrf)
        notify = code("src/modules/bjs_interpreter/notification_js.cpp")
        self.assertIn("bjs_sleep_ms((int32_t)delayMs);", notify)
        # Module waits a native sits in: ESC ends them in companion mode (and the hook makes a stopped
        # script see ESC); outside companion mode the condition short-circuits before check().
        ir = body_of(code("src/modules/ir/ir_read.cpp"), "String IrRead::loop_headless(int max_loops)")
        self.assertIn("if (g_companionActive && check(EscPress))", ir)
        tag = code("src/modules/rfid/tag_o_matic.cpp")
        for fn in ("String TagOMatic::read_tag_headless(", "String TagOMatic::read_uid_headless(",
                   "int TagOMatic::write_tag_headless("):
            self.assertIn("if (g_companionActive && check(EscPress)) break;", body_of(tag, fn), fn)

    def test_check_hook(self):
        g = strip_code((ROOT / "include/globals.h").read_text())
        body = body_of(g, "extern inline bool check(volatile bool &btn, bool resetButtonStatus = true)")
        hook = "if (g_bjsAbort && bjsStopKeyPressed(btn)) return true;"
        self.assertIn(hook, body)
        # First statement, compiled out where there is no interpreter.
        head = body[body.index("{") + 1:body.index(hook)]
        self.assertEqual(head.split(), ["#if", "!defined(LITE_VERSION)", "&&", "!defined(DISABLE_INTERPRETER)"])

    def test_script_run(self):
        sysc = code("src/companion/system.cpp")
        run = body_of(sysc, "static void runScriptRun(const String &src, uint32_t id)")
        self.assertNotIn("while (interpreter_state == 1", run)
        self.assertLess(run.index("run_bjs_script_headless(code)"), run.index("interpreter_state = 2;"))
        self.assertIn("BjsRunWatch watch(g_bjsDeadlineMs, started + 20000);", run)
        self.assertIn("running && companionPollStop()", run)
        self.assertIn("if (watch.abortRequested) g_bjsAbort = true;", run)
        self.assertIn("bjsRunFailure(finished, watch.stopRequested)", run)
        # Only the failed start clears the bound; a run that may still be going keeps it.
        after_start = run[run.index("interpreter_state = 2;"):]
        self.assertNotIn("g_bjsDeadlineMs = 0", after_start)
        self.assertNotIn("g_bjsAbort = false", after_start)
        self.assertNotIn("interpreterTaskHandler = NULL", after_start)


if __name__ == "__main__":
    unittest.main(verbosity=2)
