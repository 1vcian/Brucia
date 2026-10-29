#ifndef LITE_VERSION
#pragma once

#include <OneWire.h>

enum IButtonResult {
    IBUTTON_SUCCESS = 0,
    IBUTTON_FAILURE,
    IBUTTON_NO_KEY,
    IBUTTON_CRC_ERROR,
};

void setup_ibutton();
void setiButtonPinMenu();

// Brucia companion: headless (no UI) 1-Wire iButton read / emulate. Reuse the same
// OneWire + RW1990 logic as the interactive menu.
//   ibutton_read_headless  -> IBUTTON_SUCCESS and idHexOut = 16 hex chars (8 bytes),
//                             or IBUTTON_NO_KEY when nothing answers on the bus.
//   ibutton_write_headless -> parse 16 hex chars into the key buffer and emulate/write
//                             it onto an RW1990 blank; IBUTTON_FAILURE on a bad id.
IButtonResult ibutton_read_headless(String &idHexOut);
IButtonResult ibutton_write_headless(const String &idHex);

#endif
