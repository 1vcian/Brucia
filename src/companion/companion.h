#ifndef BRUCIA_COMPANION_H
#define BRUCIA_COMPANION_H

// Brucia companion mode.
//
// The device becomes a radio/sensor front-end driven by a native Android app that
// performs the heavy computation, storage and GUI. See proto/PROTOCOL.md for the
// wire protocol (length-prefixed JSON envelope, v0).
//
// companionModeUSB() takes over the USB-CDC serial, suspends the standard serial
// CLI task for the duration, speaks the protocol, and returns when the user
// presses ESC. Standalone Bruce behavior is untouched unless this mode is entered.
//
// When `headless` is true (entered from the `companion` CLI command rather than the
// menu/QR), the opening qrcode_display() and its blocking key-press wait are skipped:
// the app is already talking to us over serial, so we go straight to the protocol loop.
void companionModeUSB(bool headless = false);

// Set by the `companion` CLI command (which runs in the serialcmds task and therefore
// cannot enter companion mode itself - that would suspend/block its own task). The main
// loop polls this flag and enters companion mode from the main task instead.
extern volatile bool g_enterCompanionRequested;

// Called from setup() before Serial.begin(): sizes Serial's RX buffer so one whole inbound
// companion frame fits (src/companion/serial_rx.h), falling back to `bruceDefault` (Bruce's own
// SAFE_STACK_BUFFER_SIZE / 4) if the larger allocation fails. Applies to the Serial CLI too:
// a larger buffer only means fewer dropped bytes; it cannot be resized later at runtime.
#include <stddef.h>
void companionSerialRxSetup(size_t bruceDefault);

#endif // BRUCIA_COMPANION_H
