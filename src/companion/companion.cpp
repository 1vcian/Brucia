#include "companion.h"
#include "core/display.h"
#include "core/mykeyboard.h"
#include "core/serialcmds.h" // serialcmdsTaskHandle
#include "modules/others/qrcode_menu.h" // qrcode_display
#include "modules/rf/rf_utils.h"    // setup_rf_rx, initRfModule, RMT types
#include <ArduinoJson.h>
#include <SerialDevice.h>
#include <WiFi.h>
#include <globals.h> // serialCli, serialDevice

// ---------------------------------------------------------------------------
// Brucia companion protocol v0 (see proto/PROTOCOL.md).
// Framing: uint32 little-endian payload length, then <length> bytes of payload.
// Payload: a JSON object envelope {v, t, id, d}.
// ---------------------------------------------------------------------------

static const uint32_t BRUCIA_PROTO_VERSION = 0;
static const uint32_t COMPANION_MAX_FRAME = 8192;

enum CompanionKind : uint8_t {
    MSG_HELLO = 1,
    MSG_CAPS = 2,
    MSG_CMD = 3,
    MSG_ACK = 4,
    MSG_ERR = 5,
    MSG_EVENT = 6,
    MSG_DATA = 7,
    MSG_SUB = 8,
    MSG_PING = 9,
    MSG_PONG = 10,
};

#if defined(DEVICE_NAME)
#define COMPANION_BOARD_NAME DEVICE_NAME
#else
#define COMPANION_BOARD_NAME "unknown"
#endif

#if defined(BRUCE_VERSION)
#define COMPANION_FW_VERSION BRUCE_VERSION
#else
#define COMPANION_FW_VERSION "dev"
#endif

static const char *companionChip() {
#if defined(CONFIG_IDF_TARGET_ESP32S3)
    return "esp32-s3";
#elif defined(CONFIG_IDF_TARGET_ESP32C6)
    return "esp32-c6";
#elif defined(CONFIG_IDF_TARGET_ESP32C5)
    return "esp32-c5";
#elif defined(CONFIG_IDF_TARGET_ESP32C3)
    return "esp32-c3";
#else
    return "esp32";
#endif
}

// A SerialDevice sink that discards everything. Used to keep the framed channel
// clean while routing a CMD through the existing text-oriented CLI.
// NOTE (F1 limitation): a few legacy CLI commands print to `Serial` directly
// instead of `serialDevice`; those still leak into the stream until the CLI gains
// a proper output-capture path. The handshake (HELLO/CAPS/PING) is always clean.
class NullSerialDevice : public SerialDevice {
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

// --- framing over USB-CDC (Serial) ---

static bool readExact(uint8_t *buf, size_t n, uint32_t timeoutMs) {
    uint32_t last = millis();
    size_t got = 0;
    while (got < n) {
        if (Serial.available()) {
            int c = Serial.read();
            if (c < 0) continue;
            buf[got++] = (uint8_t)c;
            last = millis();
        } else {
            if (millis() - last > timeoutMs) return false;
            delay(1);
        }
    }
    return true;
}

static bool readFrame(String &payload, uint32_t timeoutMs) {
    uint8_t lenbuf[4];
    if (!readExact(lenbuf, 4, timeoutMs)) return false;
    uint32_t len = (uint32_t)lenbuf[0] | ((uint32_t)lenbuf[1] << 8) |
                   ((uint32_t)lenbuf[2] << 16) | ((uint32_t)lenbuf[3] << 24);
    if (len == 0 || len > COMPANION_MAX_FRAME) return false;
    payload = "";
    payload.reserve(len + 1);
    uint8_t b;
    for (uint32_t i = 0; i < len; i++) {
        if (!readExact(&b, 1, timeoutMs)) return false;
        payload += (char)b;
    }
    return true;
}

static void writeFrame(const String &payload) {
    uint32_t len = payload.length();
    uint8_t lenbuf[4] = {
        (uint8_t)(len & 0xFF),
        (uint8_t)((len >> 8) & 0xFF),
        (uint8_t)((len >> 16) & 0xFF),
        (uint8_t)((len >> 24) & 0xFF),
    };
    Serial.write(lenbuf, 4);
    Serial.write((const uint8_t *)payload.c_str(), len);
    Serial.flush();
}

static void beginEnvelope(JsonDocument &out, uint8_t kind, uint32_t id) {
    out["v"] = BRUCIA_PROTO_VERSION;
    out["t"] = kind;
    out["id"] = id;
}

static void sendEnvelope(JsonDocument &out) {
    String s;
    serializeJson(out, s);
    writeFrame(s);
}

static void sendAck(uint32_t id, bool ok) {
    JsonDocument out;
    beginEnvelope(out, MSG_ACK, id);
    out["d"]["ok"] = ok;
    sendEnvelope(out);
}

static void sendErr(uint32_t id, uint32_t code, const char *msg) {
    JsonDocument out;
    beginEnvelope(out, MSG_ERR, id);
    out["d"]["code"] = code;
    out["d"]["msg"] = msg;
    sendEnvelope(out);
}

static void sendPong(uint32_t id) {
    JsonDocument out;
    beginEnvelope(out, MSG_PONG, id);
    out["d"].to<JsonObject>();
    sendEnvelope(out);
}

static void sendCaps(uint32_t id) {
    JsonDocument out;
    beginEnvelope(out, MSG_CAPS, id);
    JsonObject d = out["d"].to<JsonObject>();
    d["board"] = COMPANION_BOARD_NAME;
    d["chip"] = companionChip();
    d["fw"] = COMPANION_FW_VERSION;
    d["proto"] = BRUCIA_PROTO_VERSION;
    // TODO(F2+): detect modules at runtime instead of a static list.
    JsonArray modules = d["modules"].to<JsonArray>();
    modules.add("wifi");
    modules.add("ble");
    modules.add("rf");
    modules.add("ir");
    JsonArray transports = d["transports"].to<JsonArray>();
    transports.add("usb");
    d["mtu"] = COMPANION_MAX_FRAME;
    sendEnvelope(out);
}

static bool routeCommand(const String &cmd) {
    // Run the existing Bruce CLI with output redirected to a null sink so the
    // framed channel stays (mostly) clean. The CLI task is already suspended.
    SerialDevice *prev = serialDevice;
    NullSerialDevice nullDev;
    serialDevice = &nullDev;
    bool ok = serialCli.parse(cmd);
    serialDevice = prev;
    return ok;
}

// --- wifi.ap stream (wardriving) ---

static const char *encToStr(wifi_auth_mode_t enc) {
    switch (enc) {
        case WIFI_AUTH_OPEN: return "open";
        case WIFI_AUTH_WEP: return "wep";
        case WIFI_AUTH_WPA_PSK: return "wpa";
        case WIFI_AUTH_WPA2_PSK: return "wpa2";
        case WIFI_AUTH_WPA_WPA2_PSK: return "wpa/wpa2";
        case WIFI_AUTH_WPA2_ENTERPRISE: return "wpa2-ent";
        case WIFI_AUTH_WPA3_PSK: return "wpa3";
        case WIFI_AUTH_WPA2_WPA3_PSK: return "wpa2/wpa3";
        default: return "unknown";
    }
}

static void sendWifiApEvent(
    const String &bssid, const String &ssid, int ch, int rssi, const char *enc
) {
    JsonDocument out;
    beginEnvelope(out, MSG_EVENT, 0);
    JsonObject d = out["d"].to<JsonObject>();
    d["ev"] = "wifi.ap";
    JsonObject r = d["data"].to<JsonObject>();
    r["bssid"] = bssid;
    r["ssid"] = ssid;
    r["ch"] = ch;
    r["rssi"] = rssi;
    r["enc"] = enc;
    r["ts"] = (uint32_t)millis(); // device uptime; the app stamps real time + GPS
    sendEnvelope(out);
}

// --- sub-ghz raw capture (OOK): the ESP captures RMT edge timings and streams
// them; all decoding happens on the phone (see app/.../subghz). Modeled on
// src/modules/rf/record.cpp. Runs only while the "subghz.raw" stream is on.

static QueueHandle_t rfQueue = nullptr;
static rmt_channel_handle_t rfRxCh = nullptr;
static bool subRf = false;
static rmt_symbol_word_t rfItem[64];
static const float SUBGHZ_FREQ_MHZ = 433.92f;

static bool companion_rf_rx_done(
    rmt_channel_t *channel, const rmt_rx_done_event_data_t *edata, void *user_data
) {
    BaseType_t high_task_wakeup = pdFALSE;
    QueueHandle_t q = (QueueHandle_t)user_data;
    xQueueSendFromISR(q, edata, &high_task_wakeup);
    return high_task_wakeup == pdTRUE;
}

static void subghzStart() {
    if (subRf) return;
    initRfModule("rx", SUBGHZ_FREQ_MHZ);
    rfRxCh = setup_rf_rx();
    if (rfRxCh == nullptr) {
        deinitRfModule();
        return;
    }
    rfQueue = xQueueCreate(4, sizeof(rmt_rx_done_event_data_t));
    rmt_rx_event_callbacks_t cbs = {};
    cbs.on_recv_done = companion_rf_rx_done;
    rmt_rx_register_event_callbacks(rfRxCh, &cbs, rfQueue);
    rmt_enable(rfRxCh);
    rmt_receive_config_t cfg = {};
    cfg.signal_range_min_ns = 3000;
    cfg.signal_range_max_ns = 12000000;
    rmt_receive(rfRxCh, rfItem, sizeof(rfItem), &cfg);
    subRf = true;
}

static void subghzStop() {
    if (!subRf) return;
    subRf = false;
    if (rfRxCh) {
        rmt_disable(rfRxCh);
        rfRxCh = nullptr;
    }
    if (rfQueue) {
        vQueueDelete(rfQueue);
        rfQueue = nullptr;
    }
    deinitRfModule();
}

static void subghzService() {
    if (!subRf || rfQueue == nullptr) return;
    rmt_rx_done_event_data_t rx;
    if (xQueueReceive(rfQueue, &rx, 0) != pdPASS) return;
    size_t n = rx.num_symbols;
    if (n >= 5) {
        JsonDocument out;
        beginEnvelope(out, MSG_EVENT, 0);
        JsonObject d = out["d"].to<JsonObject>();
        d["ev"] = "subghz.raw";
        JsonObject data = d["data"].to<JsonObject>();
        data["freq"] = (uint32_t)(SUBGHZ_FREQ_MHZ * 1000000.0f);
        JsonArray t = data["timings"].to<JsonArray>();
        for (size_t i = 0; i < n; i++) {
            if (rx.received_symbols[i].duration0) t.add(rx.received_symbols[i].duration0);
            if (rx.received_symbols[i].duration1) t.add(rx.received_symbols[i].duration1);
        }
        sendEnvelope(out);
    }
    rmt_receive_config_t cfg = {};
    cfg.signal_range_min_ns = 3000;
    cfg.signal_range_max_ns = 12000000;
    rmt_receive(rfRxCh, rfItem, sizeof(rfItem), &cfg);
}

void companionModeUSB() {
    // Show a QR to the latest release so the user can grab the companion app,
    // then wait for it to connect. (Press OK/ESC on the QR to continue.)
    qrcode_display("https://github.com/1vcian/Brucia/releases/latest");

    drawMainBorderWithTitle("Connect with Brucia");
    padprintln("");
    padprintln("Waiting for app...");
    padprintln("Press ESC to exit.");

    // Take exclusive ownership of the USB-CDC serial for the protocol.
    if (serialcmdsTaskHandle) vTaskSuspend(serialcmdsTaskHandle);
    while (Serial.available()) Serial.read(); // drain stale bytes

    bool helloSeen = false;
    bool subWifiAp = false; // wifi.ap stream subscription
    bool scanning = false;  // an async WiFi scan is in flight

    while (true) {
        if (check(EscPress)) break;

        // 1) Service one incoming frame if present. Short timeout keeps both the
        //    ESC check and the scan servicing below responsive.
        String payload;
        if (readFrame(payload, 20)) {
            JsonDocument in;
            DeserializationError err = deserializeJson(in, payload);
            if (err) {
                sendErr(0, 400, "bad json");
            } else {
                uint8_t t = in["t"] | 0;
                uint32_t id = in["id"] | 0;
                switch (t) {
                    case MSG_HELLO:
                        sendCaps(id);
                        if (!helloSeen) {
                            helloSeen = true;
                            drawMainBorderWithTitle("Connect with Brucia");
                            padprintln("");
                            padprintln("App connected.");
                            padprintln("Press ESC to exit.");
                        }
                        break;
                    case MSG_PING: sendPong(id); break;
                    case MSG_CMD: {
                        const char *cmd = in["d"]["cmd"] | "";
                        bool ok = routeCommand(String(cmd));
                        sendAck(id, ok);
                        break;
                    }
                    case MSG_SUB: {
                        const char *stream = in["d"]["stream"] | "";
                        bool on = in["d"]["on"] | false;
                        if (strcmp(stream, "subghz.raw") == 0) {
                            if (on) subghzStart(); else subghzStop();
                            sendAck(id, true);
                        } else if (strcmp(stream, "wifi.ap") == 0) {
                            subWifiAp = on;
                            WiFi.scanDelete();
                            scanning = false;
                            if (on) {
                                WiFi.mode(WIFI_STA);
                                WiFi.disconnect(false, true);
                            }
                            sendAck(id, true);
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
                            WiFi.BSSIDstr(i), WiFi.SSID(i), WiFi.channel(i), WiFi.RSSI(i),
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
    }

    WiFi.scanDelete();
    subghzStop();
    if (serialcmdsTaskHandle) vTaskResume(serialcmdsTaskHandle);
}
