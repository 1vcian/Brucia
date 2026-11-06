#include "core/display.h"
#include "core/i2c_finder.h"
#include "core/mykeyboard.h"
#include "core/sd_functions.h"
#include "internal.h"
#include <globals.h>
#if defined(COMPANION_HAS_FM)
#include "driver/i2s_std.h"
#include "fm_pcm_ring.h"
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
static const int FM_RING_SAMPLES = 8192;        // ~0.5 s of 16 kHz mono PCM (8191 usable)
static FmPcmRing<FM_RING_SAMPLES> fmRing;       // filled by fm_audio_data, drained by fmAudioService
static uint32_t fmLostSamples = 0; // since begin: mono samples dequeued but not fully written to I2S

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
    if (n > 256) {
        fmLostSamples += (uint32_t)(n - 256); // accounting only; callers pass <= 256
        n = 256;
    }
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
    const size_t bytes = (size_t)j * sizeof(int16_t);
    i2s_channel_write(fmTxChan, out, bytes, &wrote, pdMS_TO_TICKS(10));
    fmLostSamples += fmLostMonoSamples(bytes, wrote); // reported in fm_audio_data replies as `lost`
}

static void fmAudioStopInternal() {
    if (!fmAudioActive) return;
    fmAudioActive = false;
    fmI2sTxEnd();
    fm_stop();
    fmRing.reset();
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
    fmRing.reset();
    fmLostSamples = 0;
    fmAudioActive = true;
    String out = "FM audio TX on " + String(khz / 1000.0, 2) + " MHz (" + (mic ? "mic" : "phone") + ")";
    companionAckMsg(id, true, out.c_str());
}

// fm_audio_data <base64 pcm>  (16 kHz mono 16-bit; only for the "phone" source)
// Every reply carries `accepted` (samples queued from this frame, in order from its start).
// A full ring accepts what fits (possibly 0) and says so: ok:true with accepted < samples is
// flow control, not a failure; the sender resends the remainder later. Never a silent drop.
static void sendFmAudioDataReply(uint32_t id, bool ok, const char *out, int samples, int accepted) {
    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = ok;
    d["out"] = out;
    d["samples"] = samples;          // whole samples decoded from this frame
    d["accepted"] = accepted;        // queued for transmission; samples - accepted were not
    d["free"] = fmRing.freeSlots();  // ring free space after this frame
    d["lost"] = fmLostSamples;       // cumulative since begin: dequeued, not fully written to I2S
    sendEnvelope(o);
}

static void runFmAudioData(const String &b64, uint32_t id) {
    if (!fmAudioActive || fmAudioMic) {
        // not streaming, or mic source owns the audio
        sendFmAudioDataReply(id, false, fmAudioActive ? "mic source active" : "FM audio not active", 0, 0);
        return;
    }
    size_t outLen = 0;
    uint8_t *pcm = companionB64decode(b64, &outLen);
    if (!pcm) {
        sendFmAudioDataReply(id, false, "invalid base64 PCM", 0, 0);
        return;
    }
    int nSamples = (int)(outLen / 2);
    int accepted = fmRing.push((const int16_t *)pcm, nSamples);
    free(pcm);
    if (accepted < nSamples) {
        String out = "ring full: accepted " + String(accepted) + " of " + String(nSamples) + " samples";
        sendFmAudioDataReply(id, true, out.c_str(), nSamples, accepted);
    } else {
        sendFmAudioDataReply(id, true, "", nSamples, accepted);
    }
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
        int n = fmRing.pop(chunk, 256);
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
    micPcmStop();
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
//     EVENT ev="mic.pcm" d { sr:16000, pcm:"<base64 S16LE mono>", seq, dropped? }, 2048 samples/chunk.
//     seq: chunk counter, 0 at every SUB on. dropped: cumulative samples the I2S driver discarded
//     (RX overrun) since that SUB; omitted when the driver cannot report overruns.
//     Uses mic_stream_begin/read/end() (persistent I2S) so chunks are gapless; the plain
//     mic.spectrum sampler re-inits I2S per call and would leave holes. The I2S peripheral
//     is opened lazily on first service and released on micPcmStop(), so it never contends
//     with mic.spectrum (both use I2S_NUM_0 but only one stream runs at a time).
#define MIC_PCM_CHUNK 2048               // samples per EVENT: 4096 bytes -> ~5462 b64 < frame
static bool subPcm = false;              // phone subscribed to mic.pcm
static bool micPcmOpen = false;          // the continuous I2S capture is running
static int micPcmFill = 0;               // samples accumulated in micPcmBuf so far
static int16_t micPcmBuf[MIC_PCM_CHUNK]; // assembles one chunk across service calls
static uint32_t micPcmSeq = 0;           // next chunk sequence number (per subscription)
static uint32_t micPcmDropBase = 0;      // driver overrun count when this subscription began

void micPcmStart() {
    micStop();
    subPcm = true;
    micPcmFill = 0; // a partial chunk is discarded; the seq restart below shows the app a gap
    micPcmSeq = 0;
    micPcmDropBase = 0;
    uint32_t dropped = 0;
    if (micPcmOpen && mic_stream_dropped_samples(&dropped)) micPcmDropBase = dropped;
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
        micPcmDropBase = 0; // mic_stream_begin() restarts the driver overrun count
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
    d["seq"] = micPcmSeq++;
    uint32_t dropped = 0;
    if (mic_stream_dropped_samples(&dropped)) d["dropped"] = dropped - micPcmDropBase;
    sendEnvelope(out);
}
#endif // COMPANION_HAS_MIC

// --- Onboard speaker (NS4168 / MAX98357A) companion commands ------------------------------
//   speaker_tone <freqHz> <ms> : play a tone on the onboard I2S speaker.
//   speaker_play <path>        : start an audio file (WAV/MP3/...) from SD/LittleFS (async).
// Both reuse modules/others/audio.cpp. Guarded by HAS_NS4168_SPKR; boards without the onboard
// speaker reply ok:false "no speaker" at the dispatch site.
#if defined(HAS_NS4168_SPKR)
static void speakerFileAbort(const char *reason, bool notify = true); // speaker_play, below

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
    speakerFileAbort("superseded"); // playTone opens its own I2S output
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

// --- speaker_play: non-blocking file playback (CAPS features.speaker_play_async) ---------
//   speaker_play <path> ACKs as soon as the decoder has started (ok:true, out "playing <path>",
//   play:<CMD id>) or fails at once (ok:false). The file is then decoded by Bruce's own
//   PLAYBACK_ASYNC player task (the path the standalone audio player uses; the blocking
//   variant is untouched), so the companion loop keeps serving PING and other commands.
//   speakerPlayService() polls the task from the companion loop and sends exactly one terminal
//   EVENT ev="speaker.play" d.data{play, state:"completed"|"stopped"|"error", path, ms, reason?}.
//   speaker_stop, a newer speaker/mic command or leaving companion mode stops it; the player
//   task stops within one decoder step and stopAudioPlayback() waits at most ~2.5 s for it to
//   release the decoder, file and I2S output.
static bool spkrPcmActive = false; // spkr_pcm_* session open (handlers below)
static bool spkFileActive = false;
static uint32_t spkFilePlay = 0;    // CMD id of the speaker_play that started the file
static String spkFilePath;
static uint32_t spkFileStartMs = 0;
static uint32_t spkFileLastPoll = 0;
static const uint32_t SPK_FILE_POLL_MS = 25;

static void speakerFileEnd(const char *state, const char *reason, bool notify) {
    if (!spkFileActive) return;
    spkFileActive = false;
    if (notify) {
        JsonDocument out;
        beginEnvelope(out, MSG_EVENT, 0);
        JsonObject d = out["d"].to<JsonObject>();
        d["ev"] = "speaker.play";
        JsonObject data = d["data"].to<JsonObject>();
        data["play"] = spkFilePlay;
        data["state"] = state;
        data["path"] = spkFilePath;
        data["ms"] = (uint32_t)(millis() - spkFileStartMs);
        if (reason) data["reason"] = reason;
        sendEnvelope(out);
    }
    spkFilePath = "";
}

// Stop the file started by speaker_play, if any; reports state "stopped" with [reason].
static void speakerFileAbort(const char *reason, bool notify) {
    if (!spkFileActive) return;
    stopAudioPlayback(); // signals the player task and waits (bounded) until it has released I2S
    speakerFileEnd("stopped", reason, notify);
}

static void runSpeakerPlay(const String &args, uint32_t id) {
    String path = args;
    path.trim();
    if (path.length() == 0) {
        sendErr(id, 400, "usage: speaker_play <path>");
        return;
    }
    if (!path.startsWith("/")) path = "/" + path;
    String lower = path;
    lower.toLowerCase();
    if (!isAudioFile(lower)) {
        companionAckMsg(id, false, "unsupported format");
        return;
    }
    FS *fs;
    if (!getFsStorage(fs)) {
        companionAckMsg(id, false, "no storage");
        return;
    }
    if (!fs->exists(path)) {
        companionAckMsg(id, false, "file not found");
        return;
    }
    // One speaker owner at a time: report a previous file as superseded, close a streamed-PCM
    // session and (half-duplex, as spkr_pcm_begin) a live mic.pcm capture before opening I2S.
    speakerFileAbort("superseded");
    if (spkrPcmActive) {
        audio_pcm_end();
        spkrPcmActive = false;
    }
#if defined(COMPANION_HAS_MIC)
    micPcmStop();
#endif
    // Force sound enabled + an audible volume floor for this explicit phone-requested playback
    // (the output gain scales with soundVolume; a muted/low saved volume yields silence). The
    // async start captures the gain before returning, so the saved values are restored at once.
    int prevSound = bruceConfig.soundEnabled;
    int prevVol = bruceConfig.soundVolume;
    bruceConfig.soundEnabled = 1;
    if (bruceConfig.soundVolume < 60) bruceConfig.soundVolume = 80; // audible floor
    bool ok = playAudioFile(fs, path, PLAYBACK_ASYNC); // opens + begins the decoder, then returns
    bruceConfig.soundEnabled = prevSound;
    bruceConfig.soundVolume = prevVol;
    if (!ok) {
        String out = "playback failed: " + path;
        companionAckMsg(id, false, out.c_str());
        return;
    }
    spkFileActive = true;
    spkFilePlay = id;
    spkFilePath = path;
    spkFileStartMs = millis();
    spkFileLastPoll = spkFileStartMs;
    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = true;
    d["out"] = "playing " + path;
    d["play"] = id;
    sendEnvelope(o);
}

// --- Streamed raw-PCM speaker playback (spkr_pcm_begin / spkr_pcm_data / spkr_pcm_stop). ---
//     Modeled on the fm_audio_* path but routed to the onboard I2S speaker via audio.cpp's
//     audio_pcm_* helpers (AudioOutputI2S, auto-allocated I2S peripheral - distinct from the
//     FM path on I2S_NUM_1 and the mic on I2S_NUM_0). S16LE mono, 16 kHz.

static void runSpkrPcmBegin(uint32_t sr, uint32_t id) {
    if (sr == 0) sr = 16000;
    speakerFileAbort("superseded"); // a speaker_play file would contend for the I2S output
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

// Companion loop hook: report the end of a speaker_play file once the player task is gone.
// Polled every SPK_FILE_POLL_MS; the decoding itself runs in the player task, not here.
void speakerPlayService() {
#if defined(HAS_NS4168_SPKR)
    if (!spkFileActive) return;
    uint32_t now = millis();
    if (now - spkFileLastPoll < SPK_FILE_POLL_MS) return;
    spkFileLastPoll = now;
    if (isAudioPlaybackTaskActive()) return;
    // The decoder ended on its own. ESP8266Audio does not distinguish end of stream from a
    // decoder giving up on corrupt data, so both are reported as "completed".
    speakerFileEnd("completed", nullptr, true);
#endif
}

// Stop a speaker_play file before another user of the audio peripherals (e.g. the mic) starts.
void speakerPlayRelease(const char *reason) {
#if defined(HAS_NS4168_SPKR)
    speakerFileAbort(reason);
#else
    (void)reason;
#endif
}

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
    // Leaving companion mode: release the decoder/file/I2S. No EVENT, the link may be gone.
    speakerFileAbort("link", false);
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
            speakerFileAbort("superseded");
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
            speakerFileAbort("superseded"); // the player replaces the file with the song
            bruceConfig.soundEnabled = 1; // ensure audible; user volume kept
            bool okm = playAudioRTTTLString(rt, PLAYBACK_ASYNC);
            companionAckMsg(id, okm, okm ? "playing" : "song failed");
#else
            companionAckMsg(id, false, "no speaker");
#endif
            break;
        }

        //   speaker_stop : stop any background playback (song or speaker_play file). A file
        //   reports its EVENT state "stopped" (reason "stop") before this ACK.
        if (cmdStr == "speaker_stop") {
#if defined(HAS_NS4168_SPKR)
            if (spkFileActive) speakerFileAbort("stop");
            else stopAudioPlayback();
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
            speakerFileAbort("superseded");
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
        // Exact verb: "speaker_volume_stop" is not a volume (it set 100%), it falls through and is
        // refused by the CLI as an unknown command.
        if (cmdStr == "speaker_volume" || cmdStr.startsWith("speaker_volume ")) {
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
            speakerPlayRelease("superseded"); // mic and speaker share the I2S peripherals
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

// Recovery diagnostics (`diag`): read the existing drop counters, never a copy of them.
bool fmAudioLostSamples(uint32_t &lost, bool &active) {
#if defined(COMPANION_HAS_FM_AUDIO)
    lost = fmLostSamples; // since the last fm_audio_begin, same value as fm_audio_data `lost`
    active = fmAudioActive;
    return true;
#else
    (void)lost;
    (void)active;
    return false;
#endif
}

bool micPcmDroppedSamples(uint32_t &dropped) {
#if defined(COMPANION_HAS_MIC)
    uint32_t total = 0;
    // Same figure as the mic.pcm EVENT `dropped`: driver overruns since the current SUB.
    if (!subPcm || !micPcmOpen || !mic_stream_dropped_samples(&total)) return false;
    dropped = total - micPcmDropBase;
    return true;
#else
    (void)dropped;
    return false;
#endif
}

} // namespace companion
} // namespace brucia
