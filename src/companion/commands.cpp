#include "core/display.h"
#include "core/led_control.h"
#include "core/settings.h"
#include "internal.h"
#include <SerialDevice.h>
#include <brucia_identity.h>
#include <globals.h>

namespace brucia {
namespace companion {
// While routeCommand runs a CLI command: the capture device and the task running it. usbConsole()
// appends to it only from that task, so text from other tasks never touches the String.
static SerialDevice *volatile g_cliCapture = nullptr;
static TaskHandle_t volatile g_cliCaptureTask = nullptr;

// What usbConsole() returns in companion mode: captured into the routed CLI command's ACK, or dropped.
class CompanionConsoleSink : public Print {
public:
    size_t write(uint8_t c) override { return write(&c, 1); }
    size_t write(const uint8_t *buffer, size_t size) override {
        SerialDevice *cap = g_cliCapture;
        if (cap && xTaskGetCurrentTaskHandle() == g_cliCaptureTask) cap->write(const_cast<uint8_t *>(buffer), size);
        return size;
    }
};
static CompanionConsoleSink g_consoleSink;

// A SerialDevice that collects the CLI output into the ACK (bounded to one frame), so the framed
// channel stays clean while a CMD is routed through the existing text-oriented CLI. Bruce CLI code
// that prints to `serialDevice` or `usbConsole()` lands here; direct `Serial`/printf output is
// handled by the print-hygiene guards (tools/test_companion_print_hygiene.py) and the stdout mute
// in companion.cpp.
class CapturingSerialDevice : public SerialDevice {
public:
    explicit CapturingSerialDevice(String &buffer) : buf(buffer) {}
    size_t println(const String &s) override {
        append(s);
        append("\n");
        return 0;
    }
    size_t println(size_t n) override {
        append(String(n));
        append("\n");
        return 0;
    }
    size_t println(uint32_t n) override {
        append(String(n));
        append("\n");
        return 0;
    }
    size_t println() override {
        append("\n");
        return 0;
    }
    size_t println(int n, int format) override {
        append(String(n, format));
        append("\n");
        return 0;
    }
    size_t print(int n, int format) override {
        append(String(n, format));
        return 0;
    }
    size_t print(const String &s) override {
        append(s);
        return 0;
    }
    void vprintf(const char *fmt, va_list args) override {
        char tmp[256];
        vsnprintf(tmp, sizeof(tmp), fmt, args);
        append(tmp);
    }
    int read() override { return -1; }
    size_t write(uint8_t *str, size_t size) override {
        if (!onOwnerTask()) return size;
        for (size_t i = 0; i < size && buf.length() < CAP; i++) buf += (char)str[i];
        return size;
    }
    void flush() override {}
    int available() override { return 0; }
    String readStringUntil(char) override { return String(); }

private:
    static const int CAP = 3500; // keep the ACK within one frame
    String &buf;
    // serialDevice is global: another task (tftLogger's async writer, a script's interpreter task)
    // can print to it while a routed command runs. Only the routing task touches the String.
    const TaskHandle_t owner = xTaskGetCurrentTaskHandle();
    bool onOwnerTask() const { return xTaskGetCurrentTaskHandle() == owner; }
    void append(const String &s) {
        if (!onOwnerTask()) return;
        if (buf.length() < CAP) buf += s.substring(0, CAP - buf.length());
    }
    void append(const char *s) { append(String(s)); }
};

// serialDevice for the whole companion session (outside routeCommand, which swaps in a capture
// device). Bruce's CLI reads its input and writes its replies through serialDevice, which is
// USBserial -> Serial: the framed port. Code that reaches the CLI off the routed path (a script's
// serial.cmd()/audio/badusb/subghz helpers during script_run, a module's headless error print,
// tftLogger's mirror writer) would otherwise print raw text into the frame stream and, for commands
// that wait for input (`storage write`, `*_from_buffer`, `storage ymodem`), consume the app's frames.
// This device has no input and drops output.
class CompanionNullSerialDevice : public SerialDevice {
public:
    size_t println(const String &) override { return 0; }
    size_t println(size_t) override { return 0; }
    size_t println(uint32_t) override { return 0; }
    size_t println() override { return 0; }
    size_t println(int, int) override { return 0; }
    size_t print(int, int) override { return 0; }
    size_t print(const String &) override { return 0; }
    void vprintf(const char *, va_list) override {}
    int read() override { return -1; }
    size_t write(uint8_t *, size_t size) override { return size; }
    void flush() override {}
    int available() override { return 0; }
    String readStringUntil(char) override { return String(); }
};
static CompanionNullSerialDevice g_nullSerialDevice;
static SerialDevice *g_serialDeviceBeforeCompanion = nullptr;

void companionClaimSerialDevice(bool claim) {
    if (claim) {
        g_serialDeviceBeforeCompanion = serialDevice;
        serialDevice = &g_nullSerialDevice;
    } else if (serialDevice == &g_nullSerialDevice) {
        serialDevice = g_serialDeviceBeforeCompanion ? g_serialDeviceBeforeCompanion : &USBserial;
        g_serialDeviceBeforeCompanion = nullptr;
    }
}

static bool routeCommand(const String &cmd, String &out) {
    // Run the existing Bruce CLI with output captured, so the app can display the
    // result of any command. The CLI task is already suspended.
    SerialDevice *prev = serialDevice;
    CapturingSerialDevice cap(out);
    serialDevice = &cap;
    g_cliCaptureTask = xTaskGetCurrentTaskHandle();
    g_cliCapture = &cap;
    bool ok = serialCli.parse(cmd);
    g_cliCapture = nullptr;
    g_cliCaptureTask = nullptr;
    serialDevice = prev;
    return ok;
}

void handleCompanionCommand(JsonDocument &in, uint32_t id, CompanionSession &session) {
    const bool helloSeen = session.helloSeen;
    const bool subWifiAp = session.subWifiAp;
    const bool subBleOn = session.subBleOn;
    const bool subGhzOn = session.subGhzOn;
    bool &leaveForRemote = session.leaveForRemote;
    do {

        const char *cmd = in["d"]["cmd"] | "";
        String cmdStr(cmd);
        // Leave companion mode on request (used before remote control, which drives the
        // normal UI over raw CLI and needs the serial task back + no app-lock).
        if (cmdStr == "companion_exit") {
            companionAckMsg(id, true, "leaving companion mode");
            leaveForRemote = true;
            break;
        }

        // Brucia lite controls: screen/LED on-off from the phone.
        if (cmdStr.startsWith("scr ")) {
            companionSetScreen(cmdStr.substring(4).toInt() != 0);
            sendAck(id, true);
            break;
        }

        if (cmdStr.startsWith("led ")) {
            companionSetLed(cmdStr.substring(4).toInt() != 0);
            sendAck(id, true);
            break;
        }

        // Brucia: theme/LED commands APPLY LIVE but do NOT persist (no saveFile) -
        // saveFile() writes to the shared SPI flash/SD and is heavy; persisting on
        // every click stalls the link. The app's "Save" button sends config_save once.
        //   theme_set <hex565>  (app matches the device color)
        if (cmdStr.startsWith("theme_set ")) {
            uint16_t c = (uint16_t)strtoul(cmdStr.substring(10).c_str(), nullptr, 16);
            bruceConfig._setUiColor(c); // apply only, no saveFile
            // Redraw the companion screen so the new color shows LIVE - otherwise the
            // user had to press the device power button to force a redraw (which exits
            // companion mode).
            drawCompanionScreen(helloSeen, subWifiAp, subBleOn, subGhzOn);
            companionAckMsg(id, true, "theme set");
            break;
        }

        //   led_set <hexRRGGBB>
        if (cmdStr.startsWith("led_set ")) {
#ifdef HAS_RGB_LED
            uint32_t c = (uint32_t)strtoul(cmdStr.substring(8).c_str(), nullptr, 16);
            bruceConfig.ledColor = c; // apply only, no saveFile
            if (bruceConfig.ledEffect == LED_EFFECT_SOLID) {
                ledEffects(false);
                setLedColor(CRGB(c));
            }
            companionAckMsg(id, true, "led set");
#else
            companionAckMsg(id, false, "no rgb led");
#endif
            break;
        }

        //   led_bright <0-100>
        if (cmdStr.startsWith("led_bright ")) {
#ifdef HAS_RGB_LED
            int v = cmdStr.substring(11).toInt();
            if (v < 0) v = 0;
            if (v > 100) v = 100;
            bruceConfig.ledBright = v; // apply only, no saveFile
            setLedBrightness(v);
            if (bruceConfig.ledEffect == LED_EFFECT_SOLID) setLedColor(CRGB(bruceConfig.ledColor));
            char o[24];
            snprintf(o, sizeof(o), "led bright %d%%", v);
            companionAckMsg(id, true, o);
#else
            companionAckMsg(id, false, "no rgb led");
#endif
            break;
        }

        //   led_effect <0-9> (0=solid)
        if (cmdStr.startsWith("led_effect ")) {
#ifdef HAS_RGB_LED
            int e = cmdStr.substring(11).toInt();
            if (e < 0) e = 0;
            if (e > 9) e = 9;
            bruceConfig.ledEffect = e; // apply only, no saveFile
            if (e == LED_EFFECT_SOLID) {
                ledEffects(false);
                setLedColor(CRGB(bruceConfig.ledColor));
            } else {
                ledEffects(true); // runs as a background FreeRTOS task
            }
            char o[24];
            snprintf(o, sizeof(o), "led effect %d", e);
            companionAckMsg(id, true, o);
#else
            companionAckMsg(id, false, "no rgb led");
#endif
            break;
        }

        // Brucia: persist the current config to flash (the app's theme "Save" button).
        if (cmdStr == "config_save") {
            bruceConfig.saveFile();
            companionAckMsg(id, true, "saved");
            break;
        }

        // Brucia: device screen brightness 0..100 (%).  scr_bright <n>
        if (cmdStr.startsWith("scr_bright ")) {
            int v = cmdStr.substring(11).toInt();
            if (v < 0) v = 0;
            if (v > 100) v = 100;
            setBrightness((uint8_t)v, false);
            char o[24];
            snprintf(o, sizeof(o), "screen %d%%", v);
            companionAckMsg(id, true, o);
            break;
        }
        const CommandRequest request{cmdStr, id, in, session};
        using Handler = bool (*)(const CommandRequest &);
        static const Handler handlers[] = {
            dispatchStorageCommand,
            dispatchWifiCommand,
            dispatchSubghzCommand,
            dispatchInfraredCommand,
            dispatchNfcCommand,
            dispatchBluetoothCommand,
            dispatchAudioCommand,
            dispatchNrf24Command,
            dispatchSystemCommand,
            dispatchNetworkCommand,
        };
        for (Handler handler : handlers) {
            if (handler(request)) return;
        }

        String out;
        bool ok = routeCommand(cmdStr, out);
        JsonDocument o;
        beginEnvelope(o, MSG_ACK, id);
        JsonObject d = o["d"].to<JsonObject>();
        d["ok"] = ok;
        d["out"] = out;
        sendEnvelope(o);
        break;

    } while (false);
}

} // namespace companion
} // namespace brucia

Print &usbConsole() {
    if (!g_companionActive) return Serial;
    return brucia::companion::g_consoleSink;
}
