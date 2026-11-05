#include "companion.h"
#include "core/display.h"
#include "core/led_control.h"
#include "core/mykeyboard.h"
#include "core/serialcmds.h"
#include "core/settings.h"
#include "diag_core.h"
#include "internal.h"
#include "modules/others/qrcode_menu.h"
#include <WiFi.h>
#include <brucia_identity.h>
#include <esp_log.h>
#include <stdio.h>
#include <globals.h>

volatile bool g_enterCompanionRequested = false;

namespace brucia {
namespace companion {
// Brucia: low-power ("lite") controls the phone can toggle - screen and LED off.
// They auto-restore if the app goes away, so the device never gets stuck dark.
static bool g_screenOff = false;
static bool g_ledOff = false;

// Output that does not go through Serial but reaches the same wire while companion mode owns it.
// Per the pinned framework sdkconfig (IDF 5.5.4, newlib): the console is UART0
// (CONFIG_ESP_CONSOLE_UART_NUM=0), which is the USB bridge that Serial uses on the ESP32 boards,
// and the ESP32-S3 adds the USB-Serial-JTAG as secondary console
// (CONFIG_ESP_CONSOLE_SECONDARY_USB_SERIAL_JTAG=y), which is the port HWCDC Serial uses there.
// Three paths are muted while g_companionActive and restored on exit:
//  1. esp_log writer (ESP_LOGx inside the precompiled IDF libraries).
//  2. stdout/stderr (bare printf/puts, also the esp_log default writer): the FILE objects are
//     shared by all tasks, so their write hook is swapped for one that drops output.
//  3. ESP32-S3 ROM printf (ets_printf/esp_rom_printf, and Arduino log_x/ESP_LOGx in code built
//     with the Arduino headers, which end in ets_printf via log_printf): the ROM's
//     uart_tx_one_char copies every character to the USB-Serial-JTAG while g_usb_print is set
//     (checked in esp32s3_rev0_rom.elf), so the flag is cleared. On the ESP32 the ROM printf goes
//     to UART0 through a putc hook with no getter to restore, so it is left alone; those builds
//     use CORE_DEBUG_LEVEL=0 (log_x compiled out) and only early/ISR error logs remain.
static vprintf_like_t g_prevLogVprintf = nullptr;
static int companionMutedLog(const char *fmt, va_list args) {
    if (g_companionActive) return 0;
    return g_prevLogVprintf ? g_prevLogVprintf(fmt, args) : vprintf(fmt, args);
}

using StdioWriteFn = decltype(((FILE *)nullptr)->_write);
static StdioWriteFn g_prevStdoutWrite = nullptr;
static StdioWriteFn g_prevStderrWrite = nullptr;
static FILE *g_mutedStdout = nullptr;
static FILE *g_mutedStderr = nullptr;
static _READ_WRITE_RETURN_TYPE
mutedStdoutWrite(struct _reent *r, void *cookie, const char *buf, _READ_WRITE_BUFSIZE_TYPE n) {
    if (g_companionActive || !g_prevStdoutWrite) return n;
    return g_prevStdoutWrite(r, cookie, buf, n);
}
static _READ_WRITE_RETURN_TYPE
mutedStderrWrite(struct _reent *r, void *cookie, const char *buf, _READ_WRITE_BUFSIZE_TYPE n) {
    if (g_companionActive || !g_prevStderrWrite) return n;
    return g_prevStderrWrite(r, cookie, buf, n);
}
static void swapStdioWrite(FILE *f, StdioWriteFn fn, StdioWriteFn &prev, FILE *&owner) {
    if (!f) return;
    flockfile(f);
    fflush(f);
    if (fn) {
        prev = f->_write;
        f->_write = fn;
        owner = f;
    } else if (owner == f && prev) {
        f->_write = prev;
        prev = nullptr;
        owner = nullptr;
    }
    funlockfile(f);
}

#if CONFIG_IDF_TARGET_ESP32S3
extern "C" bool g_usb_print; // ROM flag (esp32s3.rom.ld): ROM printf also writes the USB-Serial-JTAG
static bool g_prevRomUsbPrint = false;
#endif

static void muteNonSerialOutput(bool mute) {
    if (mute) {
        g_prevLogVprintf = esp_log_set_vprintf(companionMutedLog);
        swapStdioWrite(stdout, mutedStdoutWrite, g_prevStdoutWrite, g_mutedStdout);
        swapStdioWrite(stderr, mutedStderrWrite, g_prevStderrWrite, g_mutedStderr);
#if CONFIG_IDF_TARGET_ESP32S3
        g_prevRomUsbPrint = g_usb_print;
        g_usb_print = false;
#endif
    } else {
#if CONFIG_IDF_TARGET_ESP32S3
        g_usb_print = g_prevRomUsbPrint;
#endif
        // fflush inside drops what was buffered during companion mode (g_companionActive still set)
        swapStdioWrite(g_mutedStderr, nullptr, g_prevStderrWrite, g_mutedStderr);
        swapStdioWrite(g_mutedStdout, nullptr, g_prevStdoutWrite, g_mutedStdout);
        esp_log_set_vprintf(g_prevLogVprintf ? g_prevLogVprintf : vprintf);
        g_prevLogVprintf = nullptr;
    }
}

void companionSetScreen(bool on) {
    uint8_t b = bruceConfig.bright > 0 ? (uint8_t)bruceConfig.bright : 100;
    setBrightness(on ? b : 0, false);
    g_screenOff = !on;
}

void companionSetLed(bool on) {
#ifdef HAS_RGB_LED
    // NOTE: setLedColorConfig() is an INTERACTIVE menu (while(1) waiting for device
    // buttons) - calling it here hangs the device. Apply the configured colour directly.
    if (on) {
        // Make sure the LED is actually visible: restore brightness (it may be 0) and either
        // resume the configured animation or show the solid colour.
        setLedBrightness(bruceConfig.ledBright > 0 ? bruceConfig.ledBright : 50);
        if (bruceConfig.ledEffect > LED_EFFECT_SOLID) ledEffects(true);
        else setLedColor(CRGB(bruceConfig.ledColor));
    } else {
        ledEffects(false);
        setLedColor(CRGB::Black);
    }
#endif
    // Boards without an addressable LED just track the state (no-op).
    g_ledOff = !on;
}

static void companionRestoreOutputs() {
    if (g_screenOff) companionSetScreen(true);
    if (g_ledOff) companionSetLed(true);
}

// A themed status screen for companion mode: connection state + active streams.
void drawCompanionScreen(bool connected, bool wifi, bool ble, bool subghz) {
    drawMainBorderWithTitle(BRUCIA_PRODUCT_NAME " Companion");
    padprintln("");
    if (connected) {
        padprintln("App connected.");
        padprintln("");
        padprintln("[ LOCKED - controlled by phone ]");
    } else {
        padprintln("Download the app to connect:");
        padprintln("github.com/1vcian/BruciaDev");
    }
    padprintln("");
    String streams = "Streams: ";
    if (!wifi && !ble && !subghz) {
        streams += "idle";
    } else {
        if (wifi) streams += "WiFi ";
        if (ble) streams += "BLE ";
        if (subghz) streams += "SubGHz ";
    }
    padprintln(streams);
    padprintln("");
    // When the app is connected the device is locked to it; only a deliberate ESC hold exits.
    padprintln(connected ? "Hold or tap ESC to exit." : "Press ESC to exit.");
}

// While a bounded/blocking command runs, the companion loop is paused and can't read frames, so a
// long runner would ignore an app-sent stop. Runners call this (non-blocking) poll each loop: it
// reads one pending frame and returns true if it is a CMD "stop" or "<name>_stop", so one generic
// "stop" from the app ends whatever is running. Safe to call at high frequency (guards on
// Serial.available()).
//
// This poll drains a frame off the wire, so it owns answering it; otherwise a concurrent command
// would get no reply and block the app until its own timeout. To keep the link responsive during a
// blocking op WITHOUT queueing/replaying frames:
//   - MSG_PING  -> PONG (same payload as the main loop, incl. battery), so keepalives stay alive.
//   - MSG_CMD that is not stop/*_stop -> ERR 409 "device busy", so the app's await fails fast and
//     clearly (CommandSession surfaces it as a command failure) instead of hanging.
//   - anything else (e.g. a stray ACK) -> consumed and ignored, as before.
// Only stop/*_stop returns true.
bool companionPollStop() {
    if (!Serial.available()) return false;
    String p;
    if (!readFrame(p, 40)) return false;
    JsonDocument a;
    if (deserializeJson(a, p)) {
        diagNoteRxReject(DIAG_RX_BAD_JSON); // dropped as before; only counted
        return false;
    }
    uint8_t t = a["t"] | 0;
    uint32_t id = a["id"] | 0;
    if (t == MSG_PING) {
        sendPong(id);
        return false;
    }
    if (t != MSG_CMD) return false;
    String cs(a["d"]["cmd"] | "");
    if (cs == "stop" || cs.endsWith("_stop")) return true;
    // A different command arrived mid-op: we cannot service it now and must not leave it unanswered.
    diagNoteBusy();
    sendErr(id, 409, "device busy");
    return false;
}

static void runCompanionUSB(bool headless) {
    // When entered from the menu, show a QR to the latest release so the user can grab the
    // companion app, then wait for it to connect. (Press OK/ESC on the QR to continue.)
    // When entered headless (from the `companion` CLI command), the app is already talking
    // to us over serial, so skip the blocking QR screen and go straight to the loop.
    if (!headless) { qrcode_display("https://github.com/1vcian/BruciaDev/releases/latest"); }

    // Probe the shared-SPI peripherals (NRF24 / W5500) exactly once, here, before the status
    // screen starts redrawing in the service loop. This serializes the single risky bus probe
    // away from display traffic and guarantees it never repeats on the app's HELLO retries -
    // the primary fix for the "reboots while connected" report. See the caching note up top.
    companionWarmCaps();

    drawCompanionScreen(false, false, false, false);

    // Take exclusive ownership of the USB-CDC serial for the protocol.
    if (serialcmdsTaskHandle) vTaskSuspend(serialcmdsTaskHandle);
    while (Serial.available()) Serial.read(); // drain stale bytes
    resetFrameReader();
    diagNoteSession();
    muteNonSerialOutput(true); // stdout flushed through the old writer before the link turns framed
    g_companionActive = true;  // from here Serial is the framed protocol: mute module debug prints
    startCompanionStorage();

    bool helloSeen = false;
    bool subWifiAp = false;        // wifi.ap stream subscription
    bool subBleOn = false;         // ble.scan stream subscription (for the status screen)
    bool subGhzOn = false;         // subghz.raw stream subscription (for the status screen)
    bool scanning = false;         // an async WiFi scan is in flight
    uint32_t lastFrame = millis(); // for auto-restoring screen/LED if the app vanishes
    // App-mode input lock: once the app is connected (helloSeen), the device is "locked" to the
    // phone - the wheel/OK and a short ESC tap do nothing, so the only control is the app. A
    // deliberate ESC HOLD (~700ms) still exits companion mode (which disconnects the app); the
    // user then taps Connect in the app to re-enter the lock. Repeated ESC taps also accumulate
    // toward the hold threshold, so the device can never get stuck locked. Before the app
    // connects, a single ESC leaves immediately (e.g. if you opened companion mode by mistake).
    uint32_t escHoldStart = 0, escLastSeen = 0;
    int escEdges = 0;
    // Set by the "companion_exit" command: the app asks us to leave companion mode and return to the
    // normal UI (so its raw-CLI remote-control screen mirror works and the device is usable again).
    bool leaveForRemote = false;

    while (true) {
        serviceCompanionStorage();
        if (leaveForRemote) break;
        if (!helloSeen) {
            if (check(EscPress)) break; // not locked yet: a single ESC leaves
        } else {
            // Locked to the app: swallow local wheel/OK so they never act, and clear any encoder
            // backlog, so the device feels fully controlled by the phone (fixes the "flaky input
            // while connected" report).
            NextPress = false;
            PrevPress = false;
            SelPress = false;
            UpPress = false;
            DownPress = false;
            AnyKeyPress = false;
            RotaryNetSteps = 0;
            uint32_t now = millis();
            // Three independent ways out, so the device can NEVER get stuck locked on any board:
            //  (a) a deliberate ESC hold (~700ms) - works where the ESC pin re-fires while held;
            //  (b) several ESC taps (>=8) - works on boards that only edge-trigger ESC per press;
            //  (c) the app being gone for 60s (unplugged/crashed) - auto-leave companion mode.
            if (check(EscPress)) {
                if (escHoldStart == 0) escHoldStart = now;
                escLastSeen = now;
                escEdges++;
                if (now - escHoldStart > 700 || escEdges >= 8) break;
            } else if (escLastSeen != 0 && now - escLastSeen > 2000) {
                escHoldStart = 0; // long quiet: forget the partial hold / stray taps
                escEdges = 0;
            }
            if (now - lastFrame > 60000) break; // app vanished -> never stay locked forever
        }

        // Auto-restore screen/LED if the app has been silent for a while (e.g. it
        // disconnected): never leave the device stuck with the screen/LED off.
        if ((g_screenOff || g_ledOff) && millis() - lastFrame > 15000) { companionRestoreOutputs(); }

        // 1) Service one incoming frame if present. Short timeout keeps both the
        //    ESC check and the scan servicing below responsive.
        String payload;
        if (readFrame(payload, 20)) {
            lastFrame = millis();
            JsonDocument in;
            DeserializationError err = deserializeJson(in, payload);
            if (err) {
                diagNoteRxReject(DIAG_RX_BAD_JSON);
                sendErr(0, 400, "bad json");
            } else {
                uint8_t t = in["t"] | 0;
                uint32_t id = in["id"] | 0;
                switch (t) {
                    case MSG_HELLO:
                        sendCaps(id);
                        if (!helloSeen) {
                            helloSeen = true;
                            drawCompanionScreen(true, subWifiAp, subBleOn, subGhzOn);
                        }
                        break;
                    case MSG_PING: sendPong(id); break;
                    case MSG_CMD: {
                        CompanionSession commandSession{
                            helloSeen, subWifiAp, subBleOn, subGhzOn, leaveForRemote
                        };
                        handleCompanionCommand(in, id, commandSession);
                        leaveForRemote = commandSession.leaveForRemote;
                        break;
                    }

                    case MSG_SUB: {
                        const char *stream = in["d"]["stream"] | "";
                        bool on = in["d"]["on"] | false;
                        if (strcmp(stream, "ble.scan") == 0) {
                            if (on) bleStart();
                            else bleStop();
                            subBleOn = on;
                            sendAck(id, true);
                            drawCompanionScreen(helloSeen, subWifiAp, subBleOn, subGhzOn);
                        } else if (strcmp(stream, "subghz.raw") == 0) {
                            bool started = true;
                            if (on) {
#if defined(COMPANION_HAS_CC1101)
                                subghzSpecStop(); // raw + spectrum both own the CC1101
#endif
                                started = subghzStart();
                            } else {
                                subghzStop();
                            }
                            subGhzOn = on && started;
                            // Radio/RMT init failure: the receiver is released, so report it
                            // instead of acknowledging a stream that would never deliver.
                            if (started) sendAck(id, true);
                            else sendErr(id, 503, "sub-ghz receiver init failed");
                            drawCompanionScreen(helloSeen, subWifiAp, subBleOn, subGhzOn);
                        } else if (strcmp(stream, "subghz.spectrum") == 0) {
#if defined(COMPANION_HAS_CC1101)
                            // Optional sweep window (MHz); absent fields default to 300-348 MHz,
                            // step 1 MHz. Invalid values get ERR 400 before any stream changes.
                            SpecPlan plan = {};
                            const char *bad = nullptr;
                            if (on) {
                                JsonVariantConst req = in["d"];
                                double v[3] = {300.0, 348.0, 1.0};
                                const char *keys[3] = {"f_start", "f_end", "step"};
                                for (int k = 0; k < 3 && !bad; k++) {
                                    JsonVariantConst f = req[keys[k]];
                                    if (f.isNull()) continue;
                                    if (!f.is<double>()) bad = "f_start, f_end and step must be numbers (MHz)";
                                    else v[k] = f.as<double>();
                                }
                                if (!bad) bad = planSpectrumSweep(v[0], v[1], v[2], SPEC_MAX_POINTS, plan);
                            }
                            if (bad) {
                                sendErr(id, 400, bad); // nothing started or stopped
                            } else {
                                bool started = true;
                                if (on) {
                                    subghzStop(); // spectrum + raw both own the CC1101 -> exclusive
                                    started = subghzSpecStart(plan);
                                } else {
                                    subghzSpecStop();
                                }
                                subGhzOn = on && started;
                                if (started) sendAck(id, true);
                                else sendErr(id, 503, "CC1101 init failed");
                                drawCompanionScreen(helloSeen, subWifiAp, subBleOn, subGhzOn);
                            }
#else
                            sendErr(id, 501, "no CC1101 on this board");
#endif
                        } else if (strcmp(stream, "wifi.ap") == 0) {
                            subWifiAp = on;
                            if (on) {
                                staStop();   // wifi.ap scan, the sta sniffer, the probe
                                probeStop(); // sniffer and the monitor capture all share the
                                monStop();   // Wi-Fi radio -> mutually exclusive
                            }
                            WiFi.scanDelete();
                            scanning = false;
                            if (on) {
                                WiFi.mode(WIFI_STA);
                                WiFi.disconnect(false, true);
                            }
                            sendAck(id, true);
                            drawCompanionScreen(helloSeen, subWifiAp, subBleOn, subGhzOn);
                        } else if (strcmp(stream, "wifi.sta") == 0) {
                            if (on) {
                                subWifiAp = false; // mutually exclusive with the AP scan
                                probeStop();       // and with the probe sniffer (shared radio)
                                monStop();         // and with the monitor capture
                                WiFi.scanDelete();
                                scanning = false;
                                staStart();
                            } else {
                                staStop();
                            }
                            sendAck(id, true);
                            drawCompanionScreen(helloSeen, subWifiAp, subBleOn, subGhzOn);
                        } else if (strcmp(stream, "wifi.probe") == 0) {
                            if (on) {
                                subWifiAp = false; // mutually exclusive with wifi.ap and wifi.sta
                                staStop();
                                monStop(); // and with the monitor capture (shared radio)
                                WiFi.scanDelete();
                                scanning = false;
                                probeStart();
                            } else {
                                probeStop();
                            }
                            sendAck(id, true);
                            drawCompanionScreen(helloSeen, subWifiAp, subBleOn, subGhzOn);
                        } else if (strcmp(stream, "wifi.monitor") == 0) {
                            // Full 802.11 monitor-mode capture. Optional channel lock + BSSID filter.
                            if (on) {
                                subWifiAp = false; // shares the Wi-Fi radio with ap/sta/probe
                                staStop();
                                probeStop();
                                WiFi.scanDelete();
                                scanning = false;
                                int channel = in["d"]["channel"] | 0; // 0 = slow-hop the band
                                const char *bssid = in["d"]["bssid"] | "";
                                monStart(channel, bssid);
                            } else {
                                monStop();
                            }
                            sendAck(id, true);
                            drawCompanionScreen(helloSeen, subWifiAp, subBleOn, subGhzOn);
                        } else if (strcmp(stream, "mic.spectrum") == 0) {
#ifdef COMPANION_HAS_MIC
                            if (on) speakerPlayRelease("superseded"); // mic shares the I2S peripherals
                            if (on) micStart();
                            else micStop();
                            sendAck(id, true);
#else
                            sendErr(id, 501, "no microphone on this board");
#endif
                        } else if (strcmp(stream, "mic.pcm") == 0) {
#ifdef COMPANION_HAS_MIC
                            if (on) speakerPlayRelease("superseded"); // mic shares the I2S peripherals
                            if (on) micPcmStart();
                            else micPcmStop();
                            sendAck(id, true);
#else
                            sendErr(id, 501, "no microphone on this board");
#endif
                        } else {
                            sendErr(id, 404, "unknown stream");
                        }
                        break;
                    }
                    default: sendErr(id, 404, "unknown message kind"); break;
                }
            }
        }

        // 2) Service the wifi.ap stream: run back-to-back async scans and emit
        //    each access point as an EVENT. The radio is free because the
        //    transport is USB, not WiFi.
        if (subWifiAp) {
            if (!scanning) {
                WiFi.scanNetworks(/*async=*/true, /*show_hidden=*/true);
                scanning = true;
            } else {
                int n = WiFi.scanComplete();
                if (n >= 0) {
                    for (int i = 0; i < n; i++) {
                        sendWifiApEvent(
                            WiFi.BSSIDstr(i),
                            WiFi.SSID(i),
                            WiFi.channel(i),
                            WiFi.RSSI(i),
                            encToStr(WiFi.encryptionType(i))
                        );
                    }
                    WiFi.scanDelete();
                    scanning = false; // a fresh scan starts next iteration
                }
                // n == WIFI_SCAN_RUNNING: keep waiting
            }
        }

        // 3) Service the sub-ghz raw stream.
        subghzService();

        // 3b) Service the sub-ghz spectrum sweep stream (CC1101).
#if defined(COMPANION_HAS_CC1101)
        subghzSpecService();
#endif

        // 4) Service the BLE scan stream.
        bleService();

        // 5) Service the wifi.sta station sniffer stream.
        staService();

        // 5b) Service the wifi.probe (probe-request) sniffer stream.
        probeService();

        // 5c) Service the wifi.monitor full 802.11 capture stream.
        monService();

        // 5d) Service the Evil Portal (captive-portal AP) if one is running.
#if !defined(LITE_VERSION)
        evilPortalService();
#endif

        // 6) Service the mic.spectrum stream (onboard microphone).
#ifdef COMPANION_HAS_MIC
        micService();
        // 6b) Service the raw mic.pcm stream (gapless continuous capture for phone DSP).
        micPcmService();
#endif

        // 7) Service the FM audio I2S sink (phone PCM or onboard mic -> Si4713).
#ifdef COMPANION_HAS_FM_AUDIO
        fmAudioService();
#endif
        // 8) Report the end of a non-blocking speaker_play file (decoded in the player task).
        speakerPlayService();
        // The frame reader is non-blocking; yield a full scheduler tick so idle
        // tasks and the watchdog are serviced even when no streams are active.
        vTaskDelay(1);
    }

    WiFi.scanDelete();
    subghzStop();
#if defined(COMPANION_HAS_CC1101)
    subghzSpecStop();
#endif
    bleStop();
    staStop();
    probeStop();
    monStop();
#if !defined(LITE_VERSION)
    evilPortalStop(0, false); // tear down the Evil Portal AP if a session was left running
#endif
#ifdef COMPANION_HAS_MIC
    micStop();
    micPcmStop(); // release the continuous mic capture if a mic.pcm sub was left running
#endif
    stopCompanionAudio();
    stopCompanionStorage();
    stopCompanionNetwork();
    stopCompanionBluetoothControls();
    companionRestoreOutputs(); // leaving companion mode: screen/LED back on
    muteNonSerialOutput(false); // before clearing the flag, so buffered stdout is dropped, not sent
    g_companionActive = false;  // Serial is a normal console again
    if (serialcmdsTaskHandle) vTaskResume(serialcmdsTaskHandle);
}

} // namespace companion
} // namespace brucia

void companionModeUSB(bool headless) { brucia::companion::runCompanionUSB(headless); }
