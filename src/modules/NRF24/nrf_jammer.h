#ifndef __NRF_JAMMER_H
#define __NRF_JAMMER_H
#include "modules/NRF24/nrf_common.h"
#include <RF24.h>

/*
Credits: @smoochiee https://github.com/smoochiee/Ble-jammer
         thank you for providing this simplified code for us!

Information:
    - This Jammer is supposed to be used for Educational purpouse only.
    - The signal is strong enaugh to mess with 2.4Ghz spectrum, so probaby 2.4Gz Wifi networks will be
affected causing a DOS attack
    - If the target is near the bluetooth source (smartphone right beside the bluetooth speaker), the
communication between them will be weakly affected (we don't do magic)

FLAFS:
    NRF24_CE_PIN=-1   ; Set this pin accordingly
    NRF24_SS_PIN=-1   ; Set this pin accordingly
    NRF24_MOSI_PIN=-1 ; Set this pin accordingly
    NRF24_SCK_PIN=-1  ; Set this pin accordingly
    NRF24_MISO_PIN=-1 ; Set this pin accordingly
*/

void nrf_jammer();

// Headless, bounded 2.4GHz jammer for the Brucia companion (no TFT, no on-device menu).
// AUTHORIZED / LAB USE ONLY. Reuses the same constant-carrier technique as nrf_jammer()
// (RF24 const carrier at max PA, hopping across the 2.4GHz band) but bounded to `secs`
// seconds (clamped 1..30) and abortable with EscPress. Returns the number of seconds
// actually run, or -1 if the nRF24 radio could not be started.
int nrf_jammer_headless(int secs);
#endif
