#pragma once

#if !defined(LITE_VERSION)

#include <functional>
#include <stdint.h>

// Deauth/disassoc flood ("jamming") detector.
// Watches management-frame deauth rate on the selected channel and raises a
// visual alert when it crosses a user-adjustable threshold.
void jam_detect_setup();

// Headless, time-bounded detector for the companion / CLI. Hops channels 1-11 for `secs`
// seconds (clamped 1..120) in promiscuous mode and returns the total number of deauth/disassoc
// management frames seen (deauthOut) and the total frames captured (totalOut). No screen UI or
// key handling. [shouldAbort], if set, is polled during the sweep and ends it early (app stop).
void jam_detect_run(
    int secs, uint32_t &deauthOut, uint32_t &totalOut, std::function<bool()> shouldAbort = nullptr
);

#endif
