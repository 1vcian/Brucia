#ifndef __RF_SEND_H__
#define __RF_SEND_H__

#include "structs.h"

#define COUNTER_STEP 1
#define REPEAT 2

void sendCustomRF();
bool txSubFile(RfCodes &selected_code, bool hideDefaultUI = false);
bool readSubFile(FS *fs, const String &filepath, RfCodes &data);

// Returns true when the frame was handed to the transmitter (radio initialized and the RMT
// transmit accepted); false for an unsupported preset/modulation/protocol data, a radio init
// failure or a failed transmit. Bruce callers ignore it; the companion reports it.
bool sendRfCommand(struct RfCodes rfcode, bool hideDefaultUI = false);

// Native RMT transmit wrappers (formerly the library send / _RAW_send /
// _RAW_Bit_send paths). Arguments unchanged; they delegate to the RMT encoder and return its
// result (true = transmitted), which existing callers may ignore.
bool rfTransmitCode(uint64_t data, unsigned int bits, int pulse = 0, int protocol = 1, int repeat = 10);
bool rfTransmitRawBits(RfCodes data);
bool rfTransmitRawTimings(int *ptrtransmittimings);

void display_info(RfCodes &data);
void loopEmulate(RfCodes &data);

#endif
