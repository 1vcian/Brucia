#include "codec.h"
#include <cstdlib>

namespace brucia {
namespace companion {
// --- file pull: stream an SD file to the phone as base64 DATA frames. ---

static const char B64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

String b64encode(const uint8_t *d, size_t n) {
    String o;
    o.reserve((n + 2) / 3 * 4);
    size_t i = 0;
    while (i + 3 <= n) {
        uint32_t v = ((uint32_t)d[i] << 16) | ((uint32_t)d[i + 1] << 8) | d[i + 2];
        o += B64[(v >> 18) & 63];
        o += B64[(v >> 12) & 63];
        o += B64[(v >> 6) & 63];
        o += B64[v & 63];
        i += 3;
    }
    size_t rem = n - i;
    if (rem == 1) {
        uint32_t v = (uint32_t)d[i] << 16;
        o += B64[(v >> 18) & 63];
        o += B64[(v >> 12) & 63];
        o += "==";
    } else if (rem == 2) {
        uint32_t v = ((uint32_t)d[i] << 16) | ((uint32_t)d[i + 1] << 8);
        o += B64[(v >> 18) & 63];
        o += B64[(v >> 12) & 63];
        o += B64[(v >> 6) & 63];
        o += "=";
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
    return -1;
}

uint8_t *companionB64decode(const String &in, size_t *outLen) {
    const size_t n = in.length();
    *outLen = 0;
    uint8_t *out = static_cast<uint8_t *>(malloc((n / 4) * 3 + 3));
    if (!out) return nullptr;
    uint32_t acc = 0; // unsigned shifts have defined wraparound for long PCM/file chunks
    int bits = 0;
    size_t used = 0, symbols = 0, padding = 0;
    bool valid = true;
    for (size_t i = 0; i < n; ++i) {
        const char c = in[i];
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') continue;
        if (c == '=') {
            if (++padding > 2) {
                valid = false;
                break;
            }
            continue;
        }
        const int v = companionB64val(c);
        if (v < 0 || padding != 0) {
            valid = false;
            break;
        }
        ++symbols;
        acc = (acc << 6) | static_cast<uint32_t>(v);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out[used++] = static_cast<uint8_t>((acc >> bits) & 0xFF);
        }
    }
    const size_t rem = symbols % 4;
    if (rem == 1 || (padding && (rem == 0 || padding != 4 - rem)) ||
        (bits && (acc & ((1u << bits) - 1u)) != 0))
        valid = false;
    if (!valid || used == 0) {
        free(out);
        return nullptr;
    }
    *outLen = used;
    return out;
}

} // namespace companion
} // namespace brucia
