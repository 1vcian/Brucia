#include "core/bus_HAL.h"
#include "core/mykeyboard.h"
#include "internal.h"
#include <globals.h>
#if defined(COMPANION_HAS_NRF24)
#include "modules/NRF24/nrf_common.h"
#include "modules/NRF24/nrf_jammer.h"
#include "modules/NRF24/nrf_mousejack.h"
#endif

namespace brucia {
namespace companion {
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
        {0x55, 0x55},
        {0xAA, 0xAA},
        {0xA0, 0xAA},
        {0xAB, 0xAA},
        {0xAC, 0xAA},
        {0xAD, 0xAA}
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
    bool stopped = false;
    while ((int32_t)(millis() - endT) < 0) {
        if (check(EscPress)) break;
        // The app's Stop sends `nrf24_stop`/`stop`; without this poll it only took effect at the
        // end of the window. Once per sweep (~80 short dwells) keeps the stop prompt.
        if (companionPollStop()) {
            stopped = true;
            break;
        }
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
    companionAckMsg(id, true, stopped ? "nrf24 scan done (stopped)" : "nrf24 scan done");
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
        uint16_t crcGiven = ((uint16_t)buf[6 + payloadLength] << 9) | ((uint16_t)buf[7 + payloadLength] << 1);
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
    bool stopped = false;
    while (!stopped && (int32_t)(millis() - endT) < 0) {
        if (check(EscPress)) break;
        for (uint8_t ch = 2; ch <= 84; ch++) { // ESB channel range
            if (check(EscPress) || (int32_t)(millis() - endT) >= 0) break;
            if (companionPollStop()) { // app Stop: end now, not at the window's end
                stopped = true;
                break;
            }
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
    companionAckMsg(id, true, stopped ? "nrf24 sniff done (stopped)" : "nrf24 sniff done");
}

// nrf24_jammer <secs>: bounded 2.4GHz NRF24 jammer. AUTHORIZED / LAB USE ONLY - this is
// an active transmitter that disrupts the 2.4GHz band (BLE, Wi-Fi, Zigbee, proprietary
// ESB). Only run where you are authorized to. Reuses Bruce's const-carrier jammer via the
// headless wrapper nrf_jammer_headless(); secs is clamped 1..30; EscPress or the app stop aborts.
static void runNrf24Jammer(int secs, uint32_t id) {
    if (!companionNrf24Present()) {
        companionAckMsg(id, false, "no NRF24 module");
        return;
    }
    if (secs < 1) secs = 5;
    if (secs > 30) secs = 30;
    bool stopped = false;
    int ran = nrf_jammer_headless(secs, [&stopped]() { return stopped = stopped || companionPollStop(); });
    if (ran < 0) {
        companionAckMsg(id, false, "NRF24 init failed");
        return;
    }
    char buf[32];
    snprintf(buf, sizeof(buf), stopped ? "nrf24 jammed %ds (stopped)" : "nrf24 jammed %ds", ran);
    companionAckMsg(id, true, buf);
}

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
    bool stopped = false;
    int n = nrf_mousejack_headless(
        secs, txt.length() ? txt.c_str() : nullptr, companionMjOnTarget, nullptr, &injected,
        [&stopped]() { return stopped = stopped || companionPollStop(); }
    );
    char buf[64];
    if (stopped) snprintf(buf, sizeof(buf), "nrf24 mousejack: %d device(s), stopped", n);
    else if (injected) snprintf(buf, sizeof(buf), "nrf24 mousejack: %d device(s), injected", n);
    else if (txt.length())
        snprintf(buf, sizeof(buf), "nrf24 mousejack: %d device(s), none vulnerable to inject", n);
    else snprintf(buf, sizeof(buf), "nrf24 mousejack: %d device(s) found", n);
    companionAckMsg(id, true, buf);
}

#else  // !COMPANION_HAS_NRF24
// Reply used when an NRF24 command arrives on a board without the nRF24 wiring, or on a LITE
// build, which compiles the NRF24 companion endpoints out (config.h) and reports caps.nrf24:false.
static void sendNoNrf24(uint32_t id) {
#if defined(USE_NRF24_VIA_SPI) && defined(LITE_VERSION)
    sendErr(id, 501, "nrf24 not available in this (lite) build");
#else
    companionAckMsg(id, false, "no NRF24 module on this board");
#endif
}
#endif // COMPANION_HAS_NRF24

bool dispatchNrf24Command(const CommandRequest &request) {
    const String &cmdStr = request.command;
    const uint32_t id = request.id;
    do {

        // Idle stop guard: while an nrf24_* window runs its stop is consumed by companionPollStop
        // inside the loop; one reaching an idle device (window already over) must not reach the CLI.
        if (cmdStr == "nrf24_stop") {
            companionAckMsg(id, true, "not running");
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
        // (modules/NRF24/nrf_mousejack.cpp). COMPANION_HAS_NRF24 is never set on lite
        // builds, which reply through sendNoNrf24. AUTHORIZED / LAB USE ONLY.
        if (cmdStr.startsWith("nrf24_mousejack ") || cmdStr == "nrf24_mousejack") {
#if defined(COMPANION_HAS_NRF24)
            {
                String rest = cmdStr.length() > 16 ? cmdStr.substring(16) : String("");
                rest.trim();
                int sp = rest.indexOf(' ');
                int secs = (sp < 0 ? rest : rest.substring(0, sp)).toInt();
                String txt = sp < 0 ? String("") : rest.substring(sp + 1);
                runNrf24Mousejack(secs, txt, id);
            }
#else
            sendNoNrf24(id);
#endif
            break;
        }
        return false;
    } while (false);
    return true;
}

} // namespace companion
} // namespace brucia
