#include "core/display.h"
#include "core/i2c_finder.h"
#include "core/mykeyboard.h"
#include "core/sd_functions.h"
#include "internal.h"
#include <globals.h>
#if defined(COMPANION_HAS_FM)
#include "driver/i2s_std.h"
#include "modules/fm/fm.h"
#endif
#if defined(COMPANION_HAS_MIC)
#include "modules/others/mic.h"
#include <cmath>
#include <fft.h>
#endif
#if defined(HAS_NS4168_SPKR)
#include "modules/others/audio.h"
#endif

namespace brucia {
namespace companion {
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
static const uint32_t FM_AUDIO_IN_RATE = 16000; // phone/mic PCM rate (mono 16-bit)
static const uint32_t FM_AUDIO_TX_RATE = 32000; // Si4713 digital min is 32 kHz (AN332)
static const int FM_RING_SAMPLES = 8192;        // ~0.5 s of 16 kHz mono PCM
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
void fmAudioService() {
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

// --- mic.spectrum stream: sample the onboard PDM mic, run a small FFT, and emit
//     EVENT ev="mic.spectrum" data { bins:[16], level:0..100 } at a modest rate.
//     Reuses mic.cpp's mic_capture_samples() (self-managed I2S init/deinit). ---
#ifdef COMPANION_HAS_MIC
static bool subMic = false;
static uint32_t micLastEmit = 0;
static const uint32_t MIC_PERIOD_MS = 120; // emit roughly 8x/second
static const int MIC_FFT_N = 256;          // short window per service call
static const int MIC_BINS = 16;            // small, so one frame holds the array

void micStart() {
    subMic = true;
    micLastEmit = 0;
}

void micStop() { subMic = false; }

void micService() {
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
#define MIC_PCM_CHUNK 2048               // samples per EVENT: 4096 bytes -> ~5462 b64 < frame
static bool subPcm = false;              // phone subscribed to mic.pcm
static bool micPcmOpen = false;          // the continuous I2S capture is running
static int micPcmFill = 0;               // samples accumulated in micPcmBuf so far
static int16_t micPcmBuf[MIC_PCM_CHUNK]; // assembles one chunk across service calls

void micPcmStart() {
    subPcm = true;
    micPcmFill = 0;
    // The I2S peripheral is opened lazily in micPcmService() (first call), so a transient
    // init failure just retries instead of failing the SUB ack.
}

void micPcmStop() {
    subPcm = false;
    micPcmFill = 0;
    if (micPcmOpen) {
        mic_stream_end();
        micPcmOpen = false;
    }
}

void micPcmService() {
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
    if (ms <= 0) ms = 200;      // sensible default
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
    playTone((unsigned int)freq, (unsigned long)ms, 0);             // square wave -> I2S
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
    bool ok = playAudioFile(fs, path, PLAYBACK_BLOCKING);           // decode -> I2S (blocks until done)
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
    if (secs < 1) secs = 1; // clamp 1..30
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

void stopCompanionAudio() {
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
}

bool dispatchAudioCommand(const CommandRequest &request) {
    const String &cmdStr = request.command;
    const uint32_t id = request.id;
    const JsonDocument &in = request.envelope;
    do {

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
        return false;
    } while (false);
    return true;
}

} // namespace companion
} // namespace brucia
