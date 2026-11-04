#include "core/display.h"
#include "core/mykeyboard.h"
#include "internal.h"
#include "modules/rf/rf_utils.h"
#include <globals.h>
#if defined(COMPANION_HAS_CC1101)
#include "modules/rf/emit.h"
#include "modules/rf/rf_bruteforce.h"
#include "modules/rf/rf_jammer.h"
#include "modules/rf/rf_send.h"
#include "modules/rf/structs.h"
#endif

namespace brucia {
namespace companion {
// --- sub-ghz raw capture (OOK): the ESP captures RMT edge timings and streams
// them; all decoding happens on the phone (see app/.../subghz). Modeled on
// src/modules/rf/record.cpp. Runs only while the "subghz.raw" stream is on.

static QueueHandle_t rfQueue = nullptr;
static rmt_channel_handle_t rfRxCh = nullptr;
static bool subRf = false;
static bool rfArmed = false;     // a receive job is pending on rfRxCh
static uint32_t rfArmRetryAt = 0; // millis() of the last failed re-arm attempt
static const size_t RF_RAW_MAX_SYMBOLS = 64;
static rmt_symbol_word_t rfItem[RF_RAW_MAX_SYMBOLS];
static const float SUBGHZ_FREQ_MHZ = 433.92f;

static bool
companion_rf_rx_done(rmt_channel_t *channel, const rmt_rx_done_event_data_t *edata, void *user_data) {
    BaseType_t high_task_wakeup = pdFALSE;
    QueueHandle_t q = (QueueHandle_t)user_data;
    xQueueSendFromISR(q, edata, &high_task_wakeup);
    return high_task_wakeup == pdTRUE;
}

static bool rfRawArm() {
    rmt_receive_config_t cfg = {};
    cfg.signal_range_min_ns = 3000;
    cfg.signal_range_max_ns = 12000000;
    rfArmed = rmt_receive(rfRxCh, rfItem, sizeof(rfItem), &cfg) == ESP_OK;
    return rfArmed;
}

// setup_rf_rx() (re)initializes the module at bruceConfigPins.rfFreq (which subghz_config and the
// jammer also update), so that, not SUBGHZ_FREQ_MHZ, is the frequency the receiver is tuned to.
// setMHZ() falls back to 433.92 MHz for out-of-band values; mirror that here.
static float rfRawTunedMHz() {
    float f = bruceConfigPins.rfFreq;
    if (f > 928 || f < 280) f = SUBGHZ_FREQ_MHZ;
    return f;
}

static void rfRawRelease() {
    rfArmed = false;
    if (rfRxCh) {
        rmt_disable(rfRxCh); // harmless error when the channel was never enabled
        rmt_del_channel(rfRxCh); // release the channel; the S3 has only ~4 (was leaking -> reboot)
        rfRxCh = nullptr;
    }
    if (rfQueue) {
        vQueueDelete(rfQueue);
        rfQueue = nullptr;
    }
    deinitRfModule();
}

// Returns false (with everything released) when the radio or the RMT receiver cannot be started,
// so the SUB handler can answer ERR instead of acknowledging a stream that will never deliver.
bool subghzStart() {
    if (subRf) return true;
    if (!initRfModule("rx", SUBGHZ_FREQ_MHZ)) return false; // e.g. CC1101 not answering on SPI
    rfRxCh = setup_rf_rx();
    if (rfRxCh == nullptr) {
        deinitRfModule();
        return false;
    }
    rfQueue = xQueueCreate(4, sizeof(rmt_rx_done_event_data_t));
    rmt_rx_event_callbacks_t cbs = {};
    cbs.on_recv_done = companion_rf_rx_done;
    if (rfQueue == nullptr || rmt_rx_register_event_callbacks(rfRxCh, &cbs, rfQueue) != ESP_OK ||
        rmt_enable(rfRxCh) != ESP_OK || !rfRawArm()) {
        rfRawRelease();
        return false;
    }
    subRf = true;
    return true;
}

void subghzStop() {
    if (!subRf) return;
    subRf = false;
    rfRawRelease();
}

void subghzService() {
    if (!subRf || rfQueue == nullptr) return;
    // A failed re-arm used to end the stream silently; retry it (throttled: the driver logs each failure).
    if (!rfArmed) {
        if (millis() - rfArmRetryAt >= 500 && !rfRawArm()) rfArmRetryAt = millis();
        return;
    }
    rmt_rx_done_event_data_t rx;
    if (xQueueReceive(rfQueue, &rx, 0) != pdPASS) return;
    rfArmed = false;
    size_t n = rx.num_symbols;
    if (n >= 5) {
        JsonDocument out;
        beginEnvelope(out, MSG_EVENT, 0);
        JsonObject d = out["d"].to<JsonObject>();
        d["ev"] = "subghz.raw";
        JsonObject data = d["data"].to<JsonObject>();
        // Hz, rounded to the nearest kHz: a float MHz * 1e6 product reports artifacts such as
        // 868349952 for 868.35 MHz (the CC1101 synthesizer step is ~397 Hz anyway).
        data["freq"] = (uint32_t)lroundf(rfRawTunedMHz() * 1000.0f) * 1000u;
        // Completeness metadata (additive; absent from older firmware = unknown). The IDF 5.5 RMT
        // driver copies at most the buffer size and silently drops the rest (no overflow flag
        // without en_partial_rx), so symbols < max_symbols rules out a buffer cut, while
        // symbols == max_symbols leaves completeness unknown. Neither proves loss or completeness
        // of the over-the-air burst.
        data["symbols"] = (uint32_t)n;
        data["max_symbols"] = (uint32_t)RF_RAW_MAX_SYMBOLS;
        JsonArray t = data["timings"].to<JsonArray>();
        for (size_t i = 0; i < n; i++) {
            if (rx.received_symbols[i].duration0) t.add(rx.received_symbols[i].duration0);
            if (rx.received_symbols[i].duration1) t.add(rx.received_symbols[i].duration1);
        }
        sendEnvelope(out);
    }
    if (!rfRawArm()) rfArmRetryAt = millis();
}

// --- sub-ghz spectrum sweep (CC1101): tune the CC1101 across a frequency range reading its
//     RSSI register per step, and stream one compact EVENT ev="subghz.spectrum" per completed
//     sweep: data { start, step (both kHz), count, rssi:[dBm...] }. The grid is validated and
//     quantized to whole kHz by planSpectrumSweep() (subghz_spectrum_plan.h) before the radio is
//     touched, so point i is tuned to exactly start + i * step kHz as reported. The RSSI-per-frequency read
//     (setMHZ -> ELECHOUSE_cc1101.getRssi, with the T-Embed shared-SPI/TFT workaround) is reused
//     from modules/rf/rf_waterfall.cpp::rf_waterfall_run(). Non-blocking: each service call sweeps
//     only a small bounded chunk of steps. Shares the CC1101 with subghz.raw -> mutually exclusive.
#if defined(COMPANION_HAS_CC1101)
#ifndef TFT_MOSI
#define TFT_MOSI -1 // boards without a defined TFT MOSI never match the shared-bus workaround
#endif
static const int SPEC_CHUNK = 8; // steps swept per service call (keeps each call ~1ms, non-blocking)
static bool subSpec = false;
static uint32_t specStartKHz = 300000;
static uint32_t specStepKHz = 1000;
static int specCount = 0;
static int specIdx = 0;
static int16_t specRssi[SPEC_MAX_POINTS];

// `plan` must come from planSpectrumSweep() with maxPoints <= SPEC_MAX_POINTS. A start while the
// sweep is already running applies the new grid (restarting the sweep) instead of ignoring it.
bool subghzSpecStart(const SpecPlan &plan) {
    if (plan.count < 1 || plan.count > SPEC_MAX_POINTS) return false;
    if (!subSpec) {
        // CC1101 not found -> stay off; the SUB handler answers ERR 503.
        if (!initRfModule("rx", plan.startKHz / 1000.0f)) return false;
        ELECHOUSE_cc1101.setRxBW(200);
    }
    specStartKHz = plan.startKHz;
    specStepKHz = plan.stepKHz;
    specCount = plan.count;
    specIdx = 0;
    subSpec = true;
    return true;
}

void subghzSpecStop() {
    if (!subSpec) return;
    subSpec = false;
    deinitRfModule();
}

void subghzSpecService() {
    if (!subSpec) return;
    // Sweep a bounded chunk of steps this call (non-blocking).
    for (int n = 0; n < SPEC_CHUNK && specIdx < specCount; n++, specIdx++) {
        setMHZ((float)((specStartKHz + (uint32_t)specIdx * specStepKHz) / 1000.0));
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
        data["start"] = specStartKHz; // kHz, exactly the tuned grid
        data["step"] = specStepKHz;   // kHz
        data["count"] = specCount;
        JsonArray r = data["rssi"].to<JsonArray>();
        for (int i = 0; i < specCount; i++) r.add(specRssi[i]);
        sendEnvelope(out);
        specIdx = 0; // keep sweeping continuously while the stream is on
    }
}
#endif // COMPANION_HAS_CC1101

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
        out,
        sizeof(out),
        "freq=%.0fkHz mod=%s bw=%.0fkHz dr=%.0fkBaud",
        g_rfCfg.freqMHz * 1000.0f,
        modName(g_rfCfg.modulation),
        g_rfCfg.rxBW,
        g_rfCfg.dataRate
    );
    companionAckMsg(id, true, out);
#else
    (void)argsIn;
    sendErr(id, 501, "no CC1101 on this board");
#endif
}

bool dispatchSubghzCommand(const CommandRequest &request) {
    const String &cmdStr = request.command;
    const uint32_t id = request.id;
    do {

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
                uint32_t sent = rf_bruteforce_headless(protoIdx, freqHz, bits, (uint32_t)secs * 1000UL);
                char out[64];
                snprintf(out, sizeof(out), "sent %lu codes", (unsigned long)sent);
                companionAckMsg(id, sent > 0, out);
            }
#else
            sendErr(id, 501, "no CC1101 on this board");
#endif
            break;
        }
        return false;
    } while (false);
    return true;
}

} // namespace companion
} // namespace brucia
