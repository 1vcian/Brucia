#include "companion.h"
#include "serial_rx.h"
#include <Arduino.h>

namespace brucia {
namespace companion {
static uint32_t rxBufferBytes = 0;
uint32_t serialRxBuffer() { return rxBufferBytes; }
} // namespace companion
} // namespace brucia

// Runs once in setup(), before Serial.begin(). setRxBufferSize returns 0 when the queue
// allocation fails (HWCDC/USBCDC); then Bruce's own size is used, and if that fails too the
// core's 256-byte default from begin(). CAPS rx_max follows whatever was really allocated.
void companionSerialRxSetup(size_t bruceDefault) {
    size_t got = Serial.setRxBufferSize(COMPANION_SERIAL_RX_BUFFER);
    if (!got && bruceDefault) got = Serial.setRxBufferSize(bruceDefault);
    brucia::companion::rxBufferBytes = got ? (uint32_t)got : 256;
}
