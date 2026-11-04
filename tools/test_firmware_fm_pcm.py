#!/usr/bin/env python3
"""Host checks for the FM phone-PCM ring accounting (src/companion/fm_pcm_ring.h) used by
fm_audio_data. Compiles the Arduino-free header with a C++ driver: partial accept on a full
ring, zero accept, order preservation, I2S short-write loss counting and a sender that resends
the unaccepted remainder (lossless delivery into the ring under overflow). Also checks that
audio.cpp reports `accepted` in every fm_audio_data reply instead of discarding silently.
This does not validate I2S timing, the Si4713 or anything transmitted on air.
"""
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "src/companion"

DRIVER = r'''
#include "fm_pcm_ring.h"
#include <cstdio>
#include <vector>
using namespace brucia::companion;

static int failures = 0, checks = 0;
static void check(bool ok, const char *what) {
    checks++;
    if (!ok) { failures++; std::printf("FAIL: %s\n", what); }
}

int main() {
    {   // capacity, partial accept, zero accept
        FmPcmRing<8> r;
        int16_t in[10];
        for (int i = 0; i < 10; i++) in[i] = (int16_t)(i + 1);
        check(r.capacity() == 7 && r.freeSlots() == 7, "empty ring has N-1 free slots");
        check(r.push(in, 5) == 5 && r.freeSlots() == 2, "whole frame accepted");
        check(r.push(in + 5, 5) == 2 && r.freeSlots() == 0, "full ring accepts only what fits");
        check(r.push(in + 7, 3) == 0, "full ring accepts zero");
        int16_t out[16];
        int n = r.pop(out, 16);
        bool order = n == 7;
        for (int i = 0; i < n; i++) order = order && out[i] == i + 1;
        check(order, "accepted samples are the frame prefix, in order");
        check(r.push(nullptr, 3) == 0 && r.push(in, 0) == 0 && r.push(in, -1) == 0, "invalid input accepts zero");
        r.push(in, 3); r.reset();
        check(r.used() == 0 && r.freeSlots() == 7, "reset empties ring");
    }
    {   // wraparound keeps order
        FmPcmRing<5> r;
        int16_t a[4] = {1, 2, 3, 4}, b[4] = {5, 6, 7, 8}, out[8];
        r.push(a, 4); r.pop(out, 3);
        check(r.push(b, 4) == 3, "wrapped ring free space");
        int n = r.pop(out, 8);
        check(n == 4 && out[0] == 4 && out[1] == 5 && out[3] == 7, "wrapped pop order");
    }
    {   // I2S short-write accounting (8 bytes per mono sample)
        check(fmLostMonoSamples(2048, 2048) == 0, "full write loses nothing");
        check(fmLostMonoSamples(2048, 0) == 256, "zero write loses the block");
        check(fmLostMonoSamples(2048, 2040) == 1, "partial sample counts as lost");
        check(fmLostMonoSamples(2048, 1024) == 128, "half block lost");
        check(fmLostMonoSamples(16, 32) == 0, "overreport is not negative");
    }
    {   // sender resends the unaccepted remainder; consumer drains slower than frames arrive
        FmPcmRing<8192> r;
        const int total = 50000, frame = 1024, drain = 256;
        std::vector<int16_t> src(total), sink;
        for (int i = 0; i < total; i++) src[i] = (int16_t)(i * 7);
        int sent = 0, partial = 0, zero = 0;
        int16_t chunk[256];
        while ((int)sink.size() < total) {
            if (sent < total) {
                int n = total - sent < frame ? total - sent : frame;
                int acc = r.push(&src[sent], n);
                if (acc < n) partial++;
                if (acc == 0) zero++;
                sent += acc; // the remainder is resent from `sent` next round
            }
            int got = r.pop(chunk, drain);
            sink.insert(sink.end(), chunk, chunk + got);
        }
        check(partial > 0 && zero == 0, "overflow fixture produced partial accepts");
        check(sink == src, "resending the remainder delivers every sample in order");
    }
    std::printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
'''


def check_audio_source():
    text = (CORE / "audio.cpp").read_text()
    start = text.index("static void runFmAudioData(")
    body = text[start:text.index("static void runFmAudioStop(", start)]
    errors = []
    if "sendAck(" in body or "companionAckMsg(" in body:
        errors.append("runFmAudioData must reply through sendFmAudioDataReply (accepted/free/lost)")
    if "fmRing.push(" not in body:
        errors.append("runFmAudioData must queue through FmPcmRing::push")
    reply = text[text.index("static void sendFmAudioDataReply("):start]
    for field in ("ok", "samples", "accepted", "free", "lost"):
        if not re.search(r'd\["%s"\]' % field, reply):
            errors.append("fm_audio_data reply lacks field " + field)
    return errors


def main():
    errors = check_audio_source()
    for e in errors:
        print("FAIL:", e)
    cxx = os.environ.get("CXX", "c++")
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        src = tmp / "driver.cpp"
        src.write_text(DRIVER)
        binary = tmp / "fm_pcm_test"
        compile_cmd = [cxx, "-std=c++17", "-I", str(CORE), "-Wall", "-Wextra", "-Werror",
                       str(src), "-o", str(binary)]
        print("compiling:", " ".join(compile_cmd))
        subprocess.run(compile_cmd, check=True)
        result = subprocess.run([str(binary)], capture_output=True, text=True)
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        if result.returncode != 0 or errors:
            print("FM PCM ring host test FAILED")
            sys.exit(1)
    print("FM PCM ring host test passed")


if __name__ == "__main__":
    main()
