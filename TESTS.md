# Brucia - test checklist

On-hardware tests to run on the LilyGo T-Embed CC1101 (ESP32-S3) with the Android app, after
flashing the latest firmware from the phone. Flash from the phone only (no PC-USB). Tick each item.

## Confident these work (verified live this session or very low risk)
- [x] Cross-board firmware builds: lilygo-t-embed-cc1101 (primary) AND m5stack-core4mb (LITE,
      no RGB LED) both compile. A LITE break - companion.cpp used BruceConfig::ledColor/ledBright/
      ledEffect, which only exist under HAS_RGB_LED - is fixed (those caps fields and led_set are now
      guarded). CI builds ~70 boards; the LITE + non-LED path was the class that was failing.
- [x] Phone flashing over USB (esptool-stub path): 0.8.12 flashed on real hardware; boot shows the
      flashed version. Re-confirm the flash log reaches **verified OK (MD5 matches)**.
- [x] USB auto-connect / companion HELLO + CAPS; Device info shows board/chip/fw/battery.
- [x] RTTTL songs play correctly on the device speaker ("canzoni perfette").
- [x] LED animations, LED brightness and solid color apply LIVE (user-confirmed); solid color shows
      only when the animation is "Solid" (expected - an animation overrides a static color).
- [x] Wardrive + BLE scan stream (verified earlier on hardware).

## Core fixes to verify (implemented, need on-hardware confirmation)
- [ ] **Boot animation**: the flame only grows/shrinks (min = original size), no vertical bob, no
      white gap.
- [ ] **Theme applies live**: pick a theme color -> device UI AND app accent change immediately,
      WITHOUT pressing the device power button (which used to exit Brucia). "Save to device" persists.
- [ ] **LED sliders read the real device value**: the LED-brightness and screen-brightness sliders
      start at the device's actual value (e.g. 0%), not always 50%/100%.
- [ ] **Song STOP** actually stops a playing melody (async speaker_song + speaker_stop).
- [ ] **DTMF generator**: the DTMF keypad produces audible dual tones (speaker_dtmf). If silent,
      the AudioFileSourceFunction path may not drive this board - fall back to the RTTTL/async player.
- [ ] **DTMF decoder**: the Tone screen decodes DTMF from the mic (play tones into the device mic).
- [ ] **Updates card** shows App / Firmware / App+firmware correctly; the **Device footer button**
      shows the accent dot when an update exists; the **Device tab** has the Updates shortcut card.
- [ ] **ESP-NOW** tool broadcasts; **Change MAC** sets/randomizes the STA MAC; **Connect to Wi-Fi**
      joins an AP and reports the IP.
- [ ] **Brucegotchi** runs for the chosen duration and reports a handshake count.
- [ ] **Custom IR remote**: capture a button (ir_rx), it appears in the grid, tap replays it, the
      layout survives an app restart, long-press deletes.
- [ ] **Karma attack** (evil-twin / probe-response lure): `karma <secs>` runs headless, reports
      probes seen; standalone menu entry still works. Audit note: confirm the headless run ends on
      its own time budget (returnToMenu) without needing the device ESC, and that a victim submitting
      creds through the lure does NOT desync the USB link (the portal-save Serial prints are now
      guarded).
- [ ] **Responder** (LLMNR/NBT-NS): `responder <secs>` needs Wi-Fi joined first (Connect tool);
      reports poisoned-query count. The native interactive SMB/NTLM Responder still works too.
- [ ] **BLE Remote (HID)**: BLE keyboard/presenter pairs with a host; arrow/media/typed keys
      register. Begin/refresh/end advertising from the app.

## Previously added, re-verify end to end
- [ ] Evil Portal (AP + captive page + live creds), Sub-GHz jammer/bruteforce, IR jammer, jam detect,
      BLE iBeacon/media, Network Scan (hosts/ports/CVE), Channel Analyzer, ARP/NetCut + MITM proxy,
      WPA/PMKID crack, Sub-GHz capture/replay/decode, NFC (with PN532), SSTV RX/TX, mic
      spectrum/waterfall/oscilloscope, record, speaker volume.

## Known bugs to investigate (could not be reproduced remotely)
- [ ] **Grey icons on base theme**: some home-grid/tool icons appear grey until the theme is changed.
      Check whether it's capability-gating (intended grey for absent modules) vs the device reporting
      a dark theme color vs a recomposition issue. Note which specific icons and in which state
      (connected? which modules?).
- [ ] **APK update one-tap** (fix implemented): first tap on "Download & install" starts the
      install; after granting "install unknown apps" it auto-retries on resume. Confirm on device.

## Remaining Bruce parity - status
Karma, Responder, BLE HID (above), SRIX read and the Ethernet attacks are now implemented.

Implemented, verify on hardware:
- [ ] **SRIX / ST25TB** read (`srix_read`, NFC screen -> advanced). Uses the onboard PN532.
- [ ] **Ethernet DHCP-starvation / MAC-flooding** (`eth_dhcp_starve` / `eth_mac_flood`, Ethernet
      screen -> Attacks). Needs a W5500 add-on; gated on `caps.eth`.
- [ ] NRF24 spectrum/scan, LoRa chat/send/recv: already wired as companion commands (`nrf24_scan`,
      `lora_*`); verify once the NRF24 / LoRa hardware is attached.

Not portable to companion (external device only - documented, not a gap):
- NFC Chameleon / amiibo: drive an external ChameleonUltra / Amiibolink over serial/BLE through an
  interactive GUI; no headless PN532 primitive exists. The app reports this honestly.
- MIFARE nested / mfkey32: no native cracker; needs an external PN532Killer sniffer. The onboard
  PN532 does the dictionary key-check (`mifare_keycheck`). The app buttons now say so.
- 125 kHz LF write/clone/T5577: no native write path (read is interactive UART only).

## Recently shipped - verify on hardware
- [ ] **Melody composer** (Tone tool): tap notes/rests, set tempo, Play on the device speaker,
      Save -> appears under "My songs" and survives an app restart.
- [ ] **Companion status screen**: when launched from the device menu it shows a download QR, then
      "Download the app to connect: github.com/1vcian/BruciaDev" while waiting and "App connected."
      once the app attaches.
- [ ] **App-mode input lock**: while connected, the wheel/OK and a short ESC tap do nothing; a long
      ESC hold (~700ms) exits companion mode (disconnects); reconnect via the app re-locks. Confirm
      the hold timing works on the T-Embed and that it never gets stuck locked.
- [ ] **125 kHz LF read** (NFC advanced -> "125kHz read"): on a board with the UART LF reader,
      returns a tag; safe no-op elsewhere.
- [ ] **Macros** (Tools -> Macros): build a command sequence, save, run one-tap; per-step progress;
      Stop cancels; macros persist across restarts.
- [ ] **Evil Portal HTML editor** (Evil Portal -> Custom): edit HTML, Preview renders in the WebView,
      Save keeps a named template; starting with Custom pushes the edited page to the device and the
      captive portal serves it.
- [ ] **3D RF map** (Tools -> RF Map 3D): start Wi-Fi/BLE scans + sub-GHz sweep, Refresh plots the
      sources, drag rotates, slider zooms, Save PNG writes a file. (Approximate placement by RSSI.)

> AUDIT SCOPE: two Opus audit passes are done. (1) The Karma/Responder/BLE-HID/SRIX/Ethernet/
> composer batch (8 findings, all fixed). (2) The feature sprint - input-lock, 125 kHz, macros,
> Evil Portal editor, 3D map, bug sweep, boot embers (8 findings: fixed the macro double-launch
> race, two WebView leaks, the dead Bug-Sweep trend/peak logic, the BLE over-stop, the empty-PNG
> notice, and hardened the ESC-hold exit with a tap-count + 60s app-gone auto-exit so the lock can
> never strand the device). 125 kHz, LED/LITE guards, Ethernet dtors, input-lock Serial-safety and
> all the persistence paths were confirmed correct. Everything still needs on-hardware confirmation,
> but the code has been reviewed.

## Queued feature ideas (roadmap)
- [ ] Device-side background companion (keep using the device UI while the app is connected) - a
      larger architectural change, deferred (risks the "reboots while connected" class of bug).
