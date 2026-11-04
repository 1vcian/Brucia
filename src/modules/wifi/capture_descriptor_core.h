#pragma once
// Brucia: Arduino-free helpers for the capture artifact descriptor the companion attaches to the
// terminal ACK of a bounded Wi-Fi capture (wifi_handshake / wifi_pmkid). This owns the two pieces
// of pure logic worth testing on the host (see tools/test_firmware_capture_descriptor.py):
//   1. the unsigned IEEE CRC-32 used to fingerprint the file that was actually written this run.
//      It is deliberately the SAME polynomial/seed/final-xor as the verified-files fs_read ACK
//      (src/companion/storage.cpp), so the descriptor's crc32 equals the crc32 the phone sees when
//      it later pulls the file - that identity is what makes post-transfer verification meaningful.
//   2. the stop-reason / complete classification the phone turns into a provenance statement.
// The companion builds the JSON (ArduinoJson) and gathers path/size/frames; this file never touches
// the filesystem, the radio or the wire.

#include <stddef.h>
#include <stdint.h>

// Unsigned IEEE CRC-32 (reflected, polynomial 0xEDB88320), identical to the fs_read terminal ACK.
// Streaming form: seed with bruciaCrc32Begin(), fold each buffer with bruciaCrc32Update(), then
// finalize with bruciaCrc32Finish(). The one-shot bruciaCrc32() is Begin+Update+Finish.
uint32_t bruciaCrc32Begin();
uint32_t bruciaCrc32Update(uint32_t crc, const uint8_t *data, size_t n);
uint32_t bruciaCrc32Finish(uint32_t crc);
uint32_t bruciaCrc32(const uint8_t *data, size_t n);

// Why the bounded capture stopped. Drives the explicit complete/partial provenance to the phone.
// The device never asserts it captured "the" handshake at a wall-clock time it cannot know; this
// only says whether a usable file was written and, if not, why the run ended.
enum CaptureStop {
    CAPTURE_STOP_COMPLETE = 0, // a usable capture was detected AND a file was written this run
    CAPTURE_STOP_TIMEOUT,      // the time budget elapsed without a usable capture
    CAPTURE_STOP_ABORTED,      // the device button (Esc) ended the run early, before a usable capture
    CAPTURE_STOP_NO_CAPTURE,   // ran to the end but nothing usable was seen (e.g. no clients/EAPOL)
    CAPTURE_STOP_NOT_WRITTEN,  // a usable capture was detected but no file was created/grown this run
                               // (e.g. only a stale pre-existing file) - do not claim it as this run's
};

// Stable wire string for a CaptureStop (the ACK desc.stop value).
const char *captureStopName(CaptureStop s);

// Classify the stop reason from the raw run outcome:
//   usable      - a usable capture was detected this run (handshake complete / >=1 PMKID frame)
//   written     - a capture file was actually created or grown this run (not just a stale file found)
//   aborted     - the run ended because the device button was pressed (not because time ran out)
// A detected+written run is COMPLETE regardless of aborted (button pressed after success). Detected
// but not written is NOT_WRITTEN (the file this run wrote is the authority; a pre-existing file is not
// claimed). An undetected run is ABORTED when the button ended it, TIMEOUT when the budget elapsed,
// else NO_CAPTURE.
CaptureStop captureClassifyStop(bool usable, bool written, bool aborted, bool fullBudgetElapsed);

// Target-scoped write tracking filter. During one capture the sniffer may append to several APs'
// handshake files (a beacon for any AP heard on the channel, another AP completing a 4-way), so an
// append is only counted toward the descriptor when its AP equals the capture's target. Returns true
// when an append for AP `apKey` should be counted for a capture targeting `targetKey`. A `targetKey`
// of 0 means "unscoped" (count any) - the default before a target is set.
bool captureTrackShouldCount(uint64_t targetKey, uint64_t apKey);
