#include "core/display.h"
#include "core/mykeyboard.h"
#include "core/sd_functions.h"
#include "internal.h"
#include <globals.h>
#include <sys/time.h>
#include <time.h>
#if !defined(LITE_VERSION)
#include "core/connect/esp_connection.h"
#include "modules/others/ibutton.h"
#include <esp_now.h>
#endif
#if !defined(LITE_VERSION) && !defined(DISABLE_INTERPRETER)
#include "modules/bjs_interpreter/interpreter.h"
#include "modules/bjs_interpreter/bjs_abort_core.h"
#endif

namespace brucia {
namespace companion {
// --- ESP-NOW broadcast command ------------------------------------------------------------
//   espnow_send <message> : one-shot ESP-NOW broadcast. Reuses the Connect module's
//   EspConnection::Message wire struct and mirrors esp_connection.cpp::beginEspnow() +
//   createMessage() + esp_now_send() so a Bruce device running Connect receive picks it up.
//   Minimal and non-interactive (no peer-discovery menu). Dropped on lite builds.
#if !defined(LITE_VERSION)
static void runEspnowSend(const String &args, uint32_t id) {
    String text = args;
    // Keep interior spaces; only strip a trailing CR/LF the transport may have appended.
    while (text.length() &&
           (text.charAt(text.length() - 1) == '\r' || text.charAt(text.length() - 1) == '\n'))
        text.remove(text.length() - 1);
    if (text.length() == 0) {
        sendErr(id, 400, "usage: espnow_send <message>");
        return;
    }
    if ((int)text.length() >= ESP_DATA_SIZE) text = text.substring(0, ESP_DATA_SIZE - 1);

    static const uint8_t bcast[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    // ESP-NOW needs the Wi-Fi driver STARTED with the STA interface (the peer uses WIFI_IF_STA).
    // Start it only when needed and never take the radio from its current owner: forcing STA used to
    // shut down a running Evil Portal AP (the app still showed it running) and WiFi.disconnect()
    // dropped the device's own network join. Radio off -> STA; AP only -> AP+STA (the AP stays up);
    // STA / AP+STA -> unchanged (ESP-NOW sends on the current channel).
    wifi_mode_t mode = WiFi.getMode();
    if (mode == WIFI_MODE_NULL) WiFi.mode(WIFI_STA);
    else if (mode == WIFI_MODE_AP) WiFi.enableSTA(true);
    esp_err_t initRes = esp_now_init();
    // ESP_OK on first init; ESP_ERR_ESPNOW_EXIST means it was already up (fine). Anything else is
    // a real failure (usually the Wi-Fi driver not running) - report it honestly instead of
    // pretending the send happened.
    if (initRes != ESP_OK && initRes != ESP_ERR_ESPNOW_EXIST) {
        companionAckMsg(id, false, esp_err_to_name(initRes));
        return;
    }
    if (!esp_now_is_peer_exist(bcast)) {
        esp_now_peer_info_t peer = {};
        memcpy(peer.peer_addr, bcast, 6);
        peer.channel = 0; // use the current Wi-Fi channel
        peer.encrypt = false;
        esp_err_t peerRes = esp_now_add_peer(&peer);
        if (peerRes != ESP_OK) {
            companionAckMsg(id, false, esp_err_to_name(peerRes));
            return;
        }
    }
    EspConnection::Message msg; // Connect wire format (<=250 B)
    memset(static_cast<void *>(&msg), 0, sizeof(msg)); // all members are plain data: zero the wire bytes
    msg.dataSize = text.length();
    msg.totalBytes = text.length();
    msg.bytesSent = text.length();
    msg.done = true;
    strncpy(msg.data, text.c_str(), ESP_DATA_SIZE - 1);
    esp_err_t res = esp_now_send(bcast, (uint8_t *)&msg, sizeof(msg));
    if (res == ESP_OK) {
        // ESP_OK means queued for a broadcast: there is no ACK for broadcast frames.
        String out = "queued " + String((int)text.length()) + " bytes for ESP-NOW broadcast (delivery not confirmed)";
        companionAckMsg(id, true, out.c_str());
    } else {
        companionAckMsg(id, false, esp_err_to_name(res));
    }
}
#endif // !LITE_VERSION

// --- Brucia script_run: run a phone-supplied BruceJS script headless + return output ----
// Reuses the interpreter module (modules/bjs_interpreter). run_bjs_script_headless(char*)
// spawns the interpreter task; here we drive it to the foreground and wait synchronously
// (the way the main loop normally does), with console/print output captured into a buffer
// (bjs_capture_*) and execution bounded by a deadline (g_bjsDeadlineMs -> interrupt). While it
// waits, the link is polled like every blocking op (companionPollStop): PING answered, `stop` /
// `*_stop` (e.g. `script_stop`) stops the script, other CMDs get ERR 409. Once the bound passes,
// the wall clock runs out or a stop arrives, g_bjsAbort is set and stays set until the interpreter
// task ends (it clears it): the interrupt handler, delay(), timers and the Bruce loops a native is
// in (ESC, see check()) end promptly, and a native that ignores all of these stops the script when
// it returns instead of letting it run on. The whole module is #ifndef LITE_VERSION /
// DISABLE_INTERPRETER, so lite falls back to a 501.
#if !defined(LITE_VERSION) && !defined(DISABLE_INTERPRETER)
static void runScriptRun(const String &src, uint32_t id) {
    String s = src;
    s.trim();
    if (s.length() == 0) {
        sendErr(id, 400, "usage: script_run <js source>");
        return;
    }
    if (s.length() > 6000) { // keep within one protocol frame worth of source
        companionAckMsg(id, false, "script too large");
        return;
    }
    if (interpreter_state > 0 || interpreterTaskHandler != NULL) {
        companionAckMsg(id, false, "interpreter busy");
        return;
    }
    char *code = strdup(s.c_str()); // run_bjs_script_headless takes ownership (frees it)
    if (code == NULL) {
        companionAckMsg(id, false, "out of memory");
        return;
    }
    String captured;
    captured.reserve(256);
    const uint32_t started = millis();
    g_bjsDeadlineMs = started + 15000; // 15s execution bound
    bjs_capture_begin(&captured);
    if (!run_bjs_script_headless(code)) {
        bjs_capture_end();
        g_bjsDeadlineMs = 0;
        companionAckMsg(id, false, "failed to start interpreter");
        return;
    }
    // Drive like the main loop: move the task to the foreground (it waits for state 2), then wait
    // for it to finish. interpreter_state: 1 = waiting for foreground, 2/3 = foreground,
    // 0 = background, -1 = done. (This used to wait for state 1 to change first, which nothing does:
    // every run sat out the whole wall clock before the script could start.)
    interpreter_state = 2;
    BjsRunWatch watch(g_bjsDeadlineMs, started + 20000); // wall-clock cap (> execution bound)
    for (;;) {
        const bool running = interpreter_state > 0;
        const bool stop = running && companionPollStop();
        const BjsRunWatch::Step step = watch.step(millis(), running, stop);
        if (watch.abortRequested) g_bjsAbort = true; // cleared by the task when it ends
        if (step != BjsRunWatch::WAIT) break;
        vTaskDelay(pdMS_TO_TICKS(20));
    }
    // A backgrounded script (state 0) would keep running; force it to exit for a bounded op.
    if (interpreter_state == 0) {
        interpreter_state = -1;
        g_bjsAbort = true;
        uint32_t stopBy = millis() + 2000;
        while (interpreterTaskHandler != NULL && (int32_t)(millis() - stopBy) < 0) vTaskDelay(pdMS_TO_TICKS(20));
    }
    bool finished = (interpreter_state <= 0);
    bjs_capture_end(); // stop capturing BEFORE the buffer goes out of scope
    // g_bjsDeadlineMs / g_bjsAbort are not cleared here while the task still runs: it keeps its bound
    // and the abort, and clears both itself when it ends (bjs_task_finished in interpreter.cpp). Once
    // the task is gone, drop the abort the last step copied in after the task had cleared it (the
    // step that sees the end still copies abortRequested), so a stopped run leaves no abort set.
    if (interpreterTaskHandler == NULL) g_bjsAbort = false;

    if (captured.length() > 1800) {
        captured = captured.substring(0, 1800);
        captured += "...[truncated]";
    }
    const char *failure = bjsRunFailure(finished, watch.stopRequested);
    if (failure != nullptr) {
        companionAckMsg(id, false, failure);
        return;
    }
    // An uncaught error (ReferenceError, the bound's `interrupted`, ...) is not a success: ok:false
    // with the captured output, which ends with the error text. exit() is not an error.
    if (g_bjsThrew) {
        companionAckMsg(id, false, captured.length() ? captured.c_str() : "script error");
        return;
    }
    companionAckMsg(id, true, captured.c_str());
}
#endif // interpreter

// --- Brucia iButton: headless 1-Wire read / emulate (RW1990). Reuses ibutton.cpp. --------
#ifndef LITE_VERSION
// Refuse before touching any GPIO: 0 is the unset default (GPIO0 is a boot strap pin), -1 is off.
static bool iButtonPinReady(uint32_t id) {
    if (ibuttonPinUsable(bruceConfigPins.iButton, GPIO_NUM_MAX)) return true;
    sendErr(id, 409, "iButton pin not configured");
    return false;
}

static void runIButtonRead(uint32_t id) {
    if (!iButtonPinReady(id)) return;
    String hex;
    if (ibutton_read_headless(hex) == IBUTTON_SUCCESS) {
        String out = "id=" + hex;
        companionAckMsg(id, true, out.c_str());
    } else {
        companionAckMsg(id, false, "no iButton");
    }
}

static void runIButtonWrite(const String &arg, uint32_t id) {
    if (!iButtonPinReady(id)) return;
    String a = arg;
    a.trim();
    if (a.length() == 0) {
        sendErr(id, 400, "usage: ibutton_write <idhex> (16 hex chars)");
        return;
    }
    bool written = false;
    switch (ibutton_write_headless(a, &written)) {
        case IBUTTON_SUCCESS: companionAckMsg(id, true, "written and verified"); break;
        case IBUTTON_NO_KEY:
            companionAckMsg(
                id,
                false,
                written ? "written, but no key answered the read-back: read it to check"
                        : "no iButton on the probe: touch a writable blank, then write (nothing written)"
            );
            break;
        default:
            companionAckMsg(
                id,
                false,
                written ? "read-back differs: not a writable blank, or the write did not take"
                        : "invalid id (need 16 hex chars)"
            );
            break;
    }
}
#endif // !LITE_VERSION

// --- Brucia clock: get / set the device RTC. Reuses the RTC + settimeofday path shared by
//     init_clock()/updateClockTimezone(). Works on all boards (software ESP32Time `rtc` or a
//     hardware RTC chip under HAS_RTC); system time (settimeofday) is the source of truth. ---
static void sendClockIso(uint32_t id, bool ok) {
    time_t now = time(nullptr);
    struct tm tmv;
    gmtime_r(&now, &tmv);
    char iso[32];
    strftime(iso, sizeof(iso), "%Y-%m-%dT%H:%M:%SZ", &tmv);
    companionAckMsg(id, ok, iso);
}

static void runClockGet(uint32_t id) { sendClockIso(id, true); }

static void runClockSet(const String &arg, uint32_t id) {
    String a = arg;
    a.trim();
    if (a.length() == 0) {
        sendErr(id, 400, "usage: clock_set <epoch>");
        return;
    }
    long long epoch = atoll(a.c_str());
    if (epoch < 1000000000LL) { // sanity: reject implausible times (before 2001)
        companionAckMsg(id, false, "implausible epoch");
        return;
    }
    time_t t = (time_t)epoch;
#if defined(HAS_RTC)
    struct tm tmv;
    localtime_r(&t, &tmv);
    RTC_TimeTypeDef ts;
    ts.Hours = tmv.tm_hour;
    ts.Minutes = tmv.tm_min;
    ts.Seconds = tmv.tm_sec;
    _rtc.SetTime(&ts);
    RTC_DateTypeDef ds;
    ds.WeekDay = tmv.tm_wday;
    ds.Month = tmv.tm_mon + 1;
    ds.Date = tmv.tm_mday;
    ds.Year = tmv.tm_year + 1900;
    _rtc.SetDate(&ds);
#else
    rtc.setTime((unsigned long)epoch);
    clock_set = true;
#endif
    struct timeval tv = {.tv_sec = t};
    settimeofday(&tv, nullptr);
    sendClockIso(id, true); // echo back the newly set time
}

bool dispatchSystemCommand(const CommandRequest &request) {
    const String &cmdStr = request.command;
    const uint32_t id = request.id;
    do {

        // Brucia: run a phone-supplied BruceJS script headless and return its
        // console/print output. The interpreter module is dropped on lite builds.
        //   script_run <js source>
        if (cmdStr.startsWith("script_run ")) {
#if !defined(LITE_VERSION) && !defined(DISABLE_INTERPRETER)
            runScriptRun(cmdStr.substring(11), id);
#else
            sendErr(id, 501, "script_run not available in this (lite) build");
#endif
            break;
        }

        // Brucia: 1-Wire iButton read / emulate (RW1990). Reuses ibutton.cpp,
        // which is dropped on lite builds.
        //   ibutton_read   /   ibutton_write <idhex>
        if (cmdStr == "ibutton_read") {
#ifndef LITE_VERSION
            runIButtonRead(id);
#else
            sendErr(id, 501, "ibutton not available in this (lite) build");
#endif
            break;
        }

        if (cmdStr.startsWith("ibutton_write ")) {
#ifndef LITE_VERSION
            runIButtonWrite(cmdStr.substring(14), id);
#else
            sendErr(id, 501, "ibutton not available in this (lite) build");
#endif
            break;
        }

        // Brucia: get / set the device clock (RTC). Links on all boards.
        //   clock_get   /   clock_set <epoch>
        if (cmdStr == "clock_get") {
            runClockGet(id);
            break;
        }

        if (cmdStr.startsWith("clock_set ")) {
            runClockSet(cmdStr.substring(10), id);
            break;
        }

        // Brucia: recovery diagnostics, only when the app asks (no automatic telemetry).
        //   diag
        if (cmdStr == "diag") {
#if defined(COMPANION_HAS_DIAG)
            runDiagCommand(id);
#else
            sendErr(id, 501, "diag not available in this (lite) build");
#endif
            break;
        }

        // Brucia: one-shot ESP-NOW broadcast (Connect send path). Dropped on lite.
        //   espnow_send <message>
        if (cmdStr.startsWith("espnow_send ")) {
#if !defined(LITE_VERSION)
            runEspnowSend(cmdStr.substring(12), id);
#else
            sendErr(id, 501, "espnow not available in this (lite) build");
#endif
            break;
        }
        return false;
    } while (false);
    return true;
}

} // namespace companion
} // namespace brucia
