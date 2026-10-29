/*
Thanks to thoses developers for their projects:
* @7h30th3r0n3 : https://github.com/7h30th3r0n3/Evil-M5Core2 and https://github.com/7h30th3r0n3/PwnGridSpam
* @viniciusbo : https://github.com/viniciusbo/m5-palnagotchi
* @sduenasg : https://github.com/sduenasg/pio_palnagotchi

Thanks to @bmorcelli for his help doing a better code.
*/
#if !defined(LITE_VERSION)
#include <functional>
void brucegotchi_start();
void brucegotchi_setup();
void brucegotchi_update();

// Headless, time-bounded Brucegotchi run for the companion app. Mirrors brucegotchi_start()'s
// setup + state machine (recon/interact/advertise, WPA handshake capture to /BrucePCAP/handshakes)
// but runs for [durationMs] instead of until EscPress, takes no on-device input, and reports the
// number of handshakes captured in [handshakesOut]. [shouldAbort], if set, is polled each loop and
// ends the run early when it returns true (used so the app can stop it mid-run). AUTHORIZED / LAB USE ONLY.
bool brucegotchi_run_headless(
    uint32_t durationMs, uint16_t &handshakesOut, std::function<bool()> shouldAbort = nullptr
);
#endif
