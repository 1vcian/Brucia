#!/usr/bin/env python3
"""Compile the Arduino-free capture-descriptor core (src/modules/wifi/capture_descriptor_core.cpp)
on the host and exercise it: the unsigned IEEE CRC-32 (which MUST equal the verified-files fs_read
CRC and Python's zlib.crc32, so the descriptor's crc32 matches the file the phone later pulls), the
streaming/one-shot equivalence, and the stop-reason / complete classification the companion turns
into the capture descriptor ACK. No ESP32 or PlatformIO installation required. The radio capture and
filesystem I/O themselves need hardware; this pins the pure logic the descriptor contract depends on.
"""
import binascii
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "src/modules/wifi"

# Known IEEE CRC-32 values (also what Python zlib.crc32 / the fs_read ACK produce).
CRC_EMPTY = binascii.crc32(b"")
CRC_123456789 = binascii.crc32(b"123456789")
CRC_SAMPLE = binascii.crc32(b"BRUCIA-CAPTURE-\x00\x01\x02\xfe\xffpcap")

DRIVER = r'''
#include "capture_descriptor_core.h"
#include <cstdio>
#include <cstring>

static int failures = 0, checks = 0;
static void check(bool ok, const char *what) {
    checks++;
    if (!ok) { failures++; std::printf("FAIL: %s\n", what); }
}

int main() {
    // CRC-32 known vectors (match zlib.crc32 / the fs_read terminal ACK exactly).
    check(bruciaCrc32((const uint8_t *)"", 0) == @CRC_EMPTY@U, "crc32('') == 0");
    check(bruciaCrc32((const uint8_t *)"123456789", 9) == @CRC_123@U, "crc32('123456789')");
    const char *sample = "BRUCIA-CAPTURE-\x00\x01\x02\xfe\xffpcap";
    const size_t sampleLen = 15 + 5 + 4; // literal has an embedded NUL, so hardcode the length
    check(bruciaCrc32((const uint8_t *)sample, sampleLen) == @CRC_SAMPLE@U, "crc32(sample bytes)");

    // Streaming in two parts equals the one-shot CRC (how the companion folds a file in 512B reads).
    {
        uint32_t c = bruciaCrc32Begin();
        c = bruciaCrc32Update(c, (const uint8_t *)sample, 7);
        c = bruciaCrc32Update(c, (const uint8_t *)sample + 7, sampleLen - 7);
        check(bruciaCrc32Finish(c) == bruciaCrc32((const uint8_t *)sample, sampleLen),
              "streaming crc == one-shot crc");
    }
    // A null buffer must not change the running CRC.
    check(bruciaCrc32Update(0x1234u, nullptr, 10) == 0x1234u, "null update is a no-op");

    // Stop classification -> the complete/partial provenance the phone reads.
    // args: captureClassifyStop(usable, written, aborted, fullBudgetElapsed)
    check(captureClassifyStop(true, true, false, false) == CAPTURE_STOP_COMPLETE, "usable+written -> COMPLETE");
    check(captureClassifyStop(true, true, true, true) == CAPTURE_STOP_COMPLETE, "written wins over abort/timeout");
    check(captureClassifyStop(true, false, false, true) == CAPTURE_STOP_NOT_WRITTEN, "usable but nothing written -> NOT_WRITTEN");
    check(captureClassifyStop(true, false, true, false) == CAPTURE_STOP_NOT_WRITTEN, "not written wins over abort");
    check(captureClassifyStop(false, false, true, false) == CAPTURE_STOP_ABORTED, "button abort -> ABORTED");
    check(captureClassifyStop(false, false, false, true) == CAPTURE_STOP_TIMEOUT, "budget elapsed -> TIMEOUT");
    check(captureClassifyStop(false, false, false, false) == CAPTURE_STOP_NO_CAPTURE, "early exit -> NO_CAPTURE");

    check(std::strcmp(captureStopName(CAPTURE_STOP_COMPLETE), "complete") == 0, "name complete");
    check(std::strcmp(captureStopName(CAPTURE_STOP_TIMEOUT), "timeout") == 0, "name timeout");
    check(std::strcmp(captureStopName(CAPTURE_STOP_ABORTED), "aborted") == 0, "name aborted");
    check(std::strcmp(captureStopName(CAPTURE_STOP_NO_CAPTURE), "no_capture") == 0, "name no_capture");
    check(std::strcmp(captureStopName(CAPTURE_STOP_NOT_WRITTEN), "not_written") == 0, "name not_written");

    // Target-scoped append filter: only the target AP's writes are counted toward the descriptor.
    check(captureTrackShouldCount(0xAABBCCDDEEFFULL, 0xAABBCCDDEEFFULL), "same AP -> counted");
    check(!captureTrackShouldCount(0xAABBCCDDEEFFULL, 0x112233445566ULL), "other AP -> ignored");
    check(captureTrackShouldCount(0, 0x112233445566ULL), "unscoped target (0) -> counts any");

    std::printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
'''.replace("@CRC_EMPTY@", str(CRC_EMPTY)) \
   .replace("@CRC_123@", str(CRC_123456789)) \
   .replace("@CRC_SAMPLE@", str(CRC_SAMPLE))


def main():
    cxx = os.environ.get("CXX", "c++")
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        src = tmp / "driver.cpp"
        src.write_text(DRIVER)
        binary = tmp / "capdesc_test"
        compile_cmd = [
            cxx, "-std=c++17", "-I", str(CORE), "-Wall", "-Wextra",
            str(src), str(CORE / "capture_descriptor_core.cpp"), "-o", str(binary),
        ]
        print("compiling:", " ".join(compile_cmd))
        subprocess.run(compile_cmd, check=True)
        result = subprocess.run([str(binary)], capture_output=True, text=True)
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        if result.returncode != 0:
            print("capture descriptor core host test FAILED")
            sys.exit(1)
    print("capture descriptor core host test passed")


if __name__ == "__main__":
    main()
