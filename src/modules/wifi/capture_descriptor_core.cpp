#include "capture_descriptor_core.h"

uint32_t bruciaCrc32Begin() { return 0xFFFFFFFFu; }

uint32_t bruciaCrc32Update(uint32_t crc, const uint8_t *data, size_t n) {
    if (!data) return crc;
    for (size_t i = 0; i < n; ++i) {
        crc ^= data[i];
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
        }
    }
    return crc;
}

uint32_t bruciaCrc32Finish(uint32_t crc) { return crc ^ 0xFFFFFFFFu; }

uint32_t bruciaCrc32(const uint8_t *data, size_t n) {
    return bruciaCrc32Finish(bruciaCrc32Update(bruciaCrc32Begin(), data, n));
}

const char *captureStopName(CaptureStop s) {
    switch (s) {
        case CAPTURE_STOP_COMPLETE: return "complete";
        case CAPTURE_STOP_TIMEOUT: return "timeout";
        case CAPTURE_STOP_ABORTED: return "aborted";
        case CAPTURE_STOP_NOT_WRITTEN: return "not_written";
        case CAPTURE_STOP_NO_CAPTURE:
        default: return "no_capture";
    }
}

bool captureTrackShouldCount(uint64_t targetKey, uint64_t apKey) {
    return targetKey == 0 || targetKey == apKey;
}

CaptureStop captureClassifyStop(bool usable, bool written, bool aborted, bool fullBudgetElapsed) {
    if (usable && written) return CAPTURE_STOP_COMPLETE;
    if (usable && !written) return CAPTURE_STOP_NOT_WRITTEN;
    if (aborted) return CAPTURE_STOP_ABORTED;
    if (fullBudgetElapsed) return CAPTURE_STOP_TIMEOUT;
    return CAPTURE_STOP_NO_CAPTURE;
}
