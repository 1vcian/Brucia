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
void companionModeUSB();

#endif // BRUCIA_COMPANION_H
