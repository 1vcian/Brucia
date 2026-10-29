#include "core/bus_HAL.h"
#include "core/i2c_finder.h"
#include "internal.h"
#include "modules/rfid/RFIDInterface.h"
#include <globals.h>
#if !defined(LITE_VERSION)
#include "modules/rfid/ST25R3916.h"
#endif
#include "modules/rfid/PN532.h"
#if defined(COMPANION_HAS_NRF24)
#include "modules/NRF24/nrf_common.h"
#endif
#if defined(COMPANION_HAS_LORA)
#include "modules/lora/LoRaRF.h"
#endif

namespace brucia {
namespace companion {
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
    if (bruceConfigPins.NRF24_bus.checkConflict(GPIO_NUM_NC) || bruceConfigPins.NRF24_bus.io0 == GPIO_NUM_NC)
        return false; // pins unconfigured
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
            spi, rf24_gpio_pin_t(bruceConfigPins.NRF24_bus.io0), rf24_gpio_pin_t(bruceConfigPins.NRF24_bus.cs)
        )) {
        return false;
    }
    bool present = NRFradio.isChipConnected();
    NRFradio.powerDown(); // leave the radio idle and its CS deselected (shared bus)
    return present;
}
// Cached wrapper: probe once, reuse. See the caching note above.
bool companionNrf24Present() {
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
// Probe only at companion startup, before streams use the shared SPI/I2C buses.
// A pin assignment or a saved reader selection is not proof of a connected chip.
static bool companionCc1101Present() {
#if defined(COMPANION_HAS_CC1101)
    static bool checked = false, present = false;
    if (checked) return present;
    checked = true;
    const auto &pins = bruceConfigPins.CC1101_bus;
    if (pins.sck == GPIO_NUM_NC || pins.miso == GPIO_NUM_NC || pins.mosi == GPIO_NUM_NC ||
        pins.cs == GPIO_NUM_NC)
        return false;
    SPIClass *spi = acquireSPIBus(pins.sck, pins.miso, pins.mosi);
    if (!spi) return false;
    pinMode(pins.cs, OUTPUT);
    digitalWrite(pins.cs, HIGH);
    spi->beginTransaction(SPISettings(4000000, MSBFIRST, SPI_MODE0));
    digitalWrite(pins.cs, LOW);
    // CS wakes a sleeping CC1101. Never wait indefinitely on an empty socket.
    uint32_t started = millis();
    while (digitalRead(pins.miso) == HIGH && millis() - started < 5) delay(1);
    if (digitalRead(pins.miso) == LOW) {
        spi->transfer(0xF0); // PARTNUM status register: read + burst bits
        uint8_t part = spi->transfer(0);
        digitalWrite(pins.cs, HIGH);
        digitalWrite(pins.cs, LOW);
        spi->transfer(0xF1); // VERSION status register
        uint8_t version = spi->transfer(0);
        present = part == 0 && version != 0 && version != 0xFF;
    }
    digitalWrite(pins.cs, HIGH);
    spi->endTransaction();
    return present;
#else
    return false;
#endif
}

static bool companionNfcPresent() {
    static bool checked = false, present = false;
    if (checked) return present;
    checked = true;
    const auto module = bruceConfigPins.rfidModule;
    if (module == PN532_I2C_MODULE || module == PN532_SPI_MODULE || module == PN532_I2C_SPI_MODULE) {
        PN532::CONNECTION_TYPE type = PN532::I2C;
        if (module == PN532_SPI_MODULE) {
            if (bruceConfigPins.PN532_bus.checkConflict(GPIO_NUM_NC)) return false;
            type = PN532::SPI;
        } else if (module == PN532_I2C_SPI_MODULE) {
#ifdef M5STICK
            type = PN532::I2C_SPI;
#else
            return false;
#endif
        } else if (bruceConfigPins.i2c_bus.checkConflict(GPIO_NUM_NC)) return false;
        PN532 reader(type);
        present = reader.probe(); // wakes the chip and checks its firmware response
    } else {
#if !defined(LITE_VERSION)
        if (module == ST25R3916_SPI_MODULE && bruceConfigPins.ST25R_bus.checkConflict(GPIO_NUM_NC))
            return false;
#endif
        RFIDInterface *reader = createCompanionRfidModule();
        present = reader && reader->begin();
        delete reader;
    }
    return present;
}

static bool companionLoraPresent() {
#if defined(COMPANION_HAS_LORA)
    static bool checked = false, present = false;
    if (!checked) {
        checked = true;
        // Initialization verifies the RadioLib chip; RX only, no packet is sent.
        present = lora_companion_begin(433.0f, 0, 0, 0);
        lora_companion_end();
    }
    return present;
#else
    return false;
#endif
}

void companionWarmCaps() {
    (void)companionCc1101Present();
    (void)companionNfcPresent();
    (void)companionLoraPresent();
    (void)companionNrf24Present();
    (void)companionW5500Present();
}

void sendCaps(uint32_t id) {
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
    caps["cc1101"] = companionCc1101Present();
    caps["nfc"] = companionNfcPresent();
    static bool fmProbed = false, fmI2c = false;
    if (!fmProbed) {
        fmProbed = true;
#ifdef COMPANION_HAS_FM
        fmI2c = check_i2c_address(0x63);
#endif
    }
    // NRF24: runtime SPI presence probe (cached; false when no module is actually attached).
    caps["nrf24"] = companionNrf24Present();
    // FM (Si4713): runtime I2C probe at 0x63 (cached). Absent on the T-Embed/CYD -> fm:false.
    caps["fm"] = fmI2c;
#if defined(TXLED) || defined(RXLED) || defined(IR_RX_PINS) || defined(IR_TX_PINS)
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
    caps["lora"] = companionLoraPresent();
    // Ethernet (W5500): runtime SPI probe (false when no module is actually attached / not wired).
    caps["eth"] = companionW5500Present();
    caps["wifi"] = true;
    caps["ble"] = true;

    // Legacy coarse module list (kept for backward compatibility with older app builds).
    JsonArray modules = d["modules"].to<JsonArray>();
    modules.add("wifi");
    modules.add("ble");
    if (caps["cc1101"].as<bool>()) modules.add("rf");
    if (caps["ir"].as<bool>()) modules.add("ir");
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
    d["bright"] = (int)bruceConfig.bright; // screen brightness 0..100
    sendEnvelope(out);
}

} // namespace companion
} // namespace brucia
