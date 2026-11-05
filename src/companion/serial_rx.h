#pragma once
// Inbound (host -> device) frame capacity of the companion link.
//
// Every Serial class in the pinned arduino-esp32 core drops inbound bytes once its RX buffer is
// full and nothing reads it (HWCDC.cpp RX ISR, USBCDC.cpp _onRX, the IDF UART driver behind
// HardwareSerial); see proto/PROTOCOL.md "Inbound frame capacity (rx_max)". The companion loop
// is single-threaded and can spend tens of milliseconds outside readFrame() (display redraws,
// storage, handlers), so an inbound frame is only reliable when the whole frame fits in the RX
// buffer. The buffer is sized before Serial.begin() (runtime resizing deletes the queue the
// HWCDC ISR is using) and CAPS reports the resulting safe frame size as `rx_max`.
//
// Pure helpers live here (no Arduino types) so tools/test_firmware_serial_rx.py can compile them.
#include <stddef.h>
#include <stdint.h>

#if defined(BOARD_HAS_PSRAM)
#define COMPANION_SERIAL_RX_DEFAULT (8192 + 256) // one maximal 8192-byte frame + headroom
#else
#define COMPANION_SERIAL_RX_DEFAULT 2048 // non-PSRAM: smaller internal heap; rx_max 1792
#endif
#ifndef COMPANION_SERIAL_RX_BUFFER // a board env may override with -DCOMPANION_SERIAL_RX_BUFFER=<bytes>
#define COMPANION_SERIAL_RX_BUFFER COMPANION_SERIAL_RX_DEFAULT
#endif

namespace brucia {
namespace companion {

// Bytes kept free beyond one inbound frame payload: its 4-byte length prefix plus a small frame
// the host may send while the large one is still queued (PING, stop, SUB off: 30-60 bytes).
// A quarter of the buffer for small buffers, so a 256-byte fallback still reports 192.
constexpr uint32_t rxHeadroom(uint32_t rxBuffer) { return rxBuffer / 4 < 256 ? rxBuffer / 4 : 256; }

// Largest inbound frame payload (the bytes after the length prefix) that fits the RX buffer.
constexpr uint32_t rxMaxForBuffer(uint32_t rxBuffer, uint32_t maxFrame) {
    return rxBuffer - rxHeadroom(rxBuffer) < maxFrame ? rxBuffer - rxHeadroom(rxBuffer) : maxFrame;
}

// RX buffer bytes Serial actually got in setup (0 before companionSerialRxSetup ran).
uint32_t serialRxBuffer();

} // namespace companion
} // namespace brucia
