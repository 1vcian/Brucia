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
    // ESP-NOW needs the Wi-Fi driver STARTED. In companion mode the radio may be off or left in
    // AP mode by another feature, so force STA (this calls esp_wifi_start) before init. Without
    // this, esp_now_init() fails and the broadcast silently goes nowhere (reported: "doesn't
    // work"). WiFi.disconnect(false) drops any stale STA association but keeps the driver up.
    WiFi.mode(WIFI_STA);
    WiFi.disconnect(false);
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
    memset(&msg, 0, sizeof(msg));
    msg.dataSize = text.length();
    msg.totalBytes = text.length();
    msg.bytesSent = text.length();
    msg.done = true;
    strncpy(msg.data, text.c_str(), ESP_DATA_SIZE - 1);
    esp_err_t res = esp_now_send(bcast, (uint8_t *)&msg, sizeof(msg));
    if (res == ESP_OK) {
        String out = "broadcast " + String((int)text.length()) + " bytes via ESP-NOW";
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
// (bjs_capture_*) and execution bounded by a deadline (g_bjsDeadlineMs -> interrupt). The
// whole module is #ifndef LITE_VERSION / DISABLE_INTERPRETER, so lite falls back to a 501.
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
    g_bjsDeadlineMs = millis() + 15000; // 15s execution bound
    bjs_capture_begin(&captured);
    if (!run_bjs_script_headless(code)) {
        bjs_capture_end();
        g_bjsDeadlineMs = 0;
        companionAckMsg(id, false, "failed to start interpreter");
        return;
    }
    // Drive like the main loop: move the task to the foreground, then wait for it to finish.
    // interpreter_state: 1 = waiting for foreground, 2 = foreground, 0 = background, -1 = done.
    const uint32_t deadline = millis() + 20000; // wall-clock cap (> execution bound)
    while (interpreter_state == 1 && (int32_t)(millis() - deadline) < 0) vTaskDelay(pdMS_TO_TICKS(5));
    interpreter_state = 2;
    while (interpreter_state > 0 && (int32_t)(millis() - deadline) < 0) vTaskDelay(pdMS_TO_TICKS(20));
    // A backgrounded script (state 0) would keep running; force it to exit for a bounded op.
    if (interpreter_state == 0) {
        interpreter_state = -1;
        uint32_t stopBy = millis() + 2000;
        while (interpreter_state == 0 && (int32_t)(millis() - stopBy) < 0) vTaskDelay(pdMS_TO_TICKS(20));
    }
    bool finished = (interpreter_state <= 0);
    if (interpreter_state == -1) interpreterTaskHandler = NULL;
    bjs_capture_end(); // stop capturing BEFORE the buffer goes out of scope
    g_bjsDeadlineMs = 0;

    if (captured.length() > 1800) {
        captured = captured.substring(0, 1800);
        captured += "...[truncated]";
    }
    if (!finished) {
        companionAckMsg(id, false, "script timeout");
        return;
    }
    companionAckMsg(id, true, captured.c_str());
}
#endif // interpreter

// --- Brucia iButton: headless 1-Wire read / emulate (RW1990). Reuses ibutton.cpp. --------
#ifndef LITE_VERSION
static void runIButtonRead(uint32_t id) {
    String hex;
    if (ibutton_read_headless(hex) == IBUTTON_SUCCESS) {
        String out = "id=" + hex;
        companionAckMsg(id, true, out.c_str());
    } else {
        companionAckMsg(id, false, "no iButton");
    }
}

static void runIButtonWrite(const String &arg, uint32_t id) {
    String a = arg;
    a.trim();
    if (a.length() == 0) {
        sendErr(id, 400, "usage: ibutton_write <idhex> (16 hex chars)");
        return;
    }
    if (ibutton_write_headless(a) == IBUTTON_SUCCESS) companionAckMsg(id, true, "written");
    else companionAckMsg(id, false, "invalid id (need 16 hex chars)");
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
