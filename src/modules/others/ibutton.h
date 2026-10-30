#ifndef LITE_VERSION
#pragma once

#include "ibutton_core.h" // IButtonResult, id parse/format, CRC8, pin rule, ROM read
#include <OneWire.h>

void setup_ibutton();
void setiButtonPinMenu();

// Brucia companion: headless (no UI) 1-Wire iButton read / emulate. Reuse the same
// OneWire + RW1990 logic as the interactive menu.
//   ibutton_read_headless  -> IBUTTON_SUCCESS and idHexOut = 16 hex chars (8 bytes),
//                             IBUTTON_NO_KEY when nothing answers, IBUTTON_CRC_ERROR on bad CRC.
//   ibutton_write_headless -> parse 16 hex chars into the key buffer and emulate/write
//                             it onto an RW1990 blank; IBUTTON_FAILURE on a bad id.
IButtonResult ibutton_read_headless(String &idHexOut);
IButtonResult ibutton_write_headless(const String &idHex);

#endif
