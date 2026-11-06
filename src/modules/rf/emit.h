#ifndef RF_REPLAY_H
#define RF_REPLAY_H

#include "core/display.h"
#include "record.h"
#include "structs.h"
#include <vector>
void rf_raw_emit_draw(void *parameter);
// Returns whether the radio was initialized for TX (the emit runs as before either way; Bruce's
// own caller ignores the result, the companion replay reports it).
bool rf_raw_emit(RawRecording &recorded, bool &returnToMenu);
#endif
