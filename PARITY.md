# Bruce -> Brucia parity audit

Goal: every native Bruce feature is reachable from the Brucia Android app (companion mode),
caps-gated per hardware. This file maps Bruce's on-device menus to the app and lists the real
gaps, prioritised by the sensors actually present on the reference board (LilyGo T-Embed CC1101:
Wi-Fi, BLE, CC1101 sub-GHz, IR, mic/speaker, RGB LED; NFC/NRF24/LoRa/Ethernet are add-on modules).

Legend: [done] in the app - [partial] exists but incomplete/less discoverable - [gap] missing.
Tag: (benign) utility - (offensive) authorized-research/dual-use - (gated) needs an add-on module.

## Present sensors (priority)

### Wi-Fi
- Wardrive / GPS wardriving - [done] (phone GPS + map + export)
- Sniffer, Probes, Monitor, Channel Analyzer - [done]
- Crack (handshake/PMKID) - [done] (on-phone)
- Evil Portal / evil twin - [done] (`evil_portal_*`)
- Jam Detect - [done] (`jam_detect`)
- NetCut - [done] via the MITM screen (`startArpSpoof` -> `arp_spoof <ssid> <pass> <target> <gw> <secs>`); a dedicated one-tap entry is optional UX only (offensive)
- Scan Hosts - [done] via on-phone Network Scan (nmap-lite, richer than device-side)
- Brucegotchi / Pwnagotchi - [gap] (offensive) - firmware module `modules/pwnagotchi/` exists
- Karma / "Evil Wifi" - [gap] (offensive) - firmware `modules/wifi/karma_attack.*` exists (large
  interactive module; needs a headless companion wrapper like Evil Portal's autoMode)
- Responder - [gap] (offensive) - LLMNR/NBT-NS poisoning; a prior subagent attempt tripped a
  safeguard, revert left clean; implement directly with authorized-use gating
- Change MAC - [gap] (benign) - simple `wifi set mac <mac|random>` companion command + field
- Connect to Wi-Fi - [gap] (benign) - `wifi connect <ssid> <pass>`; also unlocks a future Wi-Fi transport
- ReverseShell / SSH / SOCKS4 / TCP client+listen - [gap] (offensive/advanced) - device-side net
  tools; revisit under the offload model (may live phone-side instead)

### BLE
- Scan, Sniffer, Spam, Bad BLE, GATT explorer, Media Cmds, iBeacon - [done]
- Presenter mode - [gap] (benign) - BLE HID slide remote (next/prev/black)
- BLE Keyboard - [gap] (benign) - BLE HID keyboard driven from the phone
- Ninebot - [gap] (offensive) - scooter unlock

### Sub-GHz (CC1101)
- Capture/decode/replay, Record RAW, Scan/copy, Custom, Jammer, Bruteforce - [done]
- Spectrum / Spectogram / RSSI / SquareWave spectrum - [partial] waterfall exists; dedicated
  RSSI/spectogram views are a [gap] (benign)

### IR
- Read, Send, Custom, TV-B-Gone, Jammer - [done]

### Audio (mic/speaker)
- Spectrum, Waterfall, Oscilloscope, SSTV RX/TX, Tone/DTMF gen+detect, Record - [done]
- Tone song player (RTTTL presets) - [done]
- MIDI song composer + recording-to-song - [gap, queued] (benign) - see ROADMAP "Audio - queued ideas"

### Device / LED
- Device info, Console, Remote screen+nav, Files, Updates, Theme+LED sync - [done]
- Clock / Timer - [gap] (benign) - small utility
- Megalodon - [gap] - clarify what it maps to before implementing

## Add-on modules (gated; implement behind caps so owners who fit the module get them)
- NFC/RFID (PN532): Chameleon, SRIX Tool, Read 125kHz, Read EMV, Amiibolink, mfkey/Add MIF Key,
  Emulate/Write NDEF, nested - [gap/partial] (gated)
- NRF24: scan/sniff/jammer/MouseJack - [done]; Spectrum - [gap] (gated)
- LoRa: send/recv/config - [done]; Chat - [gap] (gated)
- Ethernet (W5500): status/config + Scan Hosts - [partial]; DHCP Starvation, MAC Flooding - [gap]
  (offensive, gated)
- FM: broadcast - [done]; FM Spectrum, Hijack TA - [gap] (gated)
- USB HID (host): USB Keyboard, USB Clicker, USB U2F - [gap] - needs USB host on the device side

## Recommended order (after phone-flash is confirmed on hardware, so each ships verifiable)
1. BLE Presenter mode + BLE Keyboard (benign, present sensor, high utility)
2. Change MAC + Connect to Wi-Fi (benign Wi-Fi utilities)
3. Brucegotchi, then Karma (offensive, headless wrappers; authorized-use gating)
4. Sub-GHz RSSI/spectogram views
5. Add-on-module features (NFC advanced, NRF24 spectrum, LoRa chat, Ethernet attacks), caps-gated
6. Clock/Timer, Responder, device-side net tools - revisit under the offload model

Note: items are listed after the in-app flash is verified end to end (see brucia-project memory /
ROADMAP) so new firmware commands are testable rather than piled up unverified.
