#ifndef __BRUCIA_RESPONDER_H__
#define __BRUCIA_RESPONDER_H__
#ifndef LITE_VERSION
#include <Arduino.h>
#include <functional>

// Headless LLMNR/NBT-NS responder for the companion app. AUTHORIZED / LAB USE ONLY. This is the
// lightweight companion-mode variant of Bruce's interactive Responder (modules/wifi/responder.cpp,
// which also does SMB/NTLM capture but needs the screen+keyboard). The device must already be
// joined to Wi-Fi (station). It answers LLMNR (UDP 5355) and NBT-NS (UDP 137) name queries with
// the device's own IP for [durationMs], poisoning name resolution so victims connect to the
// device. Returns false if Wi-Fi isn't connected.
// [poisonedOut] = number of answered queries; [lastOut] = the last queried name.
// [shouldAbort], if set, is polled during the run and ends it early (app stop).
bool responder_run(
    uint32_t durationMs, uint32_t &poisonedOut, String &lastOut, std::function<bool()> shouldAbort = nullptr
);

#endif
#endif
