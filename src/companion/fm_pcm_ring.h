#pragma once
// Arduino-free accounting for the FM phone-PCM ring (fm_audio_data). Host-tested by
// tools/test_firmware_fm_pcm.py. Accounting only: it never changes what is written to I2S.
#include <cstddef>
#include <cstdint>

namespace brucia {
namespace companion {

// Single-producer/single-consumer ring of mono S16 samples. Both sides run on the companion
// loop task. One slot stays empty, so the usable capacity is N - 1 samples.
template <int N> struct FmPcmRing {
    static_assert(N > 1, "ring needs at least two slots");
    int16_t data[N];
    int head = 0; // next write slot
    int tail = 0; // next read slot

    void reset() { head = tail = 0; }
    int capacity() const { return N - 1; }
    int used() const { return (head - tail + N) % N; }
    int freeSlots() const { return capacity() - used(); }

    // Copy as many samples as fit, in order; returns the number accepted (0..n). The caller
    // reports the remainder instead of discarding it silently.
    int push(const int16_t *samples, int n) {
        if (!samples || n <= 0) return 0;
        int accepted = n < freeSlots() ? n : freeSlots();
        for (int i = 0; i < accepted; i++) {
            data[head] = samples[i];
            head = (head + 1) % N;
        }
        return accepted;
    }

    // Move up to max samples into out; returns the number moved.
    int pop(int16_t *out, int max) {
        int n = 0;
        while (n < max && tail != head) {
            out[n++] = data[tail];
            tail = (tail + 1) % N;
        }
        return n;
    }
};

// Mono input samples not fully written by one I2S write of the x2-upsampled stereo block
// (each mono sample becomes 2 stereo frames = 4 int16 = 8 bytes). A partly written sample
// counts as lost.
inline uint32_t fmLostMonoSamples(size_t expectedBytes, size_t wroteBytes) {
    if (wroteBytes >= expectedBytes) return 0;
    return (uint32_t)((expectedBytes - wroteBytes + 7) / 8);
}

} // namespace companion
} // namespace brucia
