# Brucia development status

Where each capability stands today. This is a snapshot of the current code, not a
plan - for what is next, see [ROADMAP.md](../ROADMAP.md). For the design split and
feature set, see the [README](../README.md), the [comparison](COMPARISON.md) and the
[protocol](../proto/PROTOCOL.md).

States used below:

- **Implemented** - built and exercised (unit/known-answer tested, or validated on the real device where noted).
- **Implemented, not hardware-tested** - code complete and build-verified, not yet validated on the real device.
- **Partial** - some of it works; gaps noted.
- **Planned** - designed for, not built.

Where an entry is validated on the LilyGo T-Embed CC1101 it says *hardware-validated*;
otherwise it is build-verified only.

## Status at a glance

### Platform

| Capability | State | Notes |
|---|---|---|
| USB-CDC transport + framed JSON protocol | Implemented | Hardware-validated; still the only transport (`CAPS.transports = ["usb"]`). |
| Wi-Fi (TCP/WS) and BLE transports | Planned | `Transport` abstraction allows for them; not built. |
| Runtime module detection | Implemented | CAPS reports real caps (cc1101/nfc/nrf24/fm/ir/mic/speaker) via I2C/SPI probes + board macros; the app gates each page. FM reports absent on this board. |
| Mode auto-negotiation | Implemented | `companion` command + headless entry; the app switches a device at its menu into companion mode on connect. |
| Home dashboard + bottom nav + status bar | Implemented | Hardware-validated. |
| In-app updates (app OTA + device FW over USB) | Implemented, not hardware-tested | App self-update from GitHub Releases; esptool-over-USB device flash is build-verified, on-hardware pending. |
| Fire theme + flame branding + animated splash | Implemented | Hardware-validated. |

### Wi-Fi

| Capability | State | Notes |
|---|---|---|
| Wardrive | Implemented | Hardware-validated. GPS tagging, Room storage, CSV/KML/GPX export, map, stats, WiGLE upload. |
| Deauth | Implemented | Tested. Headless, time-bounded, abortable from the device. |
| Handshake + PMKID capture -> phone crack | Implemented | Self-driven capture, auto-pull over USB, then crack. |
| WPA/PMKID cracker | Implemented | Known-answer tested. PBKDF2/PTK/MIC, rockyou/SecLists, mangling rules, hashcat 22000/16800. |
| Station sniffer | Implemented | Channel-hopping promiscuous sniffer stream (`wifi.sta`). |
| Probe-request profiling | Implemented, not hardware-tested | `wifi.probe` stream, grouped by SSID/client, CSV export. |
| Beacon spam | Implemented, not hardware-tested | Phone supplies SSIDs; device floods (authorized). |
| Monitor mode -> pcap | Implemented, not hardware-tested | `wifi.monitor` raw 802.11 -> phone pcap, Wireshark + PSK decrypt. |
| ARP-spoof MITM | Implemented, not hardware-tested | `arp_spoof`, poisons target + gateway (authorized). |
| MITM HTTPS intercept proxy (phone) | Implemented | Brucia CA + per-host leaf certs, full decrypt, transaction viewer; HSTS/pinned hosts fall back to metadata-only tunnels. |

### Sub-GHz (CC1101)

| Capability | State | Notes |
|---|---|---|
| Raw capture + CSV | Implemented | Hardware-validated capture; CSV export on the phone. |
| Decoder (rolling-code-aware) | Implemented | Princeton/CAME/NICE/Holtek + KeeLoq (fixed/rolling), on the phone. |
| `.sub` import/export | Implemented, not hardware-tested | Flipper-compatible `.sub` on the phone. |
| De Bruijn brute force | Implemented | Runs from the phone. |
| Replay / raw TX / jammer / config | Implemented, not hardware-tested | `subghz_replay/tx_raw/jammer/config` (jammer authorized/bounded). |
| Spectrum-sweep waterfall | Implemented, not hardware-tested | `subghz.spectrum` stream, live waterfall + RSSI-vs-frequency. |

### NFC / RFID

| Capability | State | Notes |
|---|---|---|
| Read / emulate / clone / write / NDEF + security analysis | Implemented, not hardware-tested | Driven via the CLI; phone-side security analysis. |
| MIFARE key dictionary attack | Implemented, not hardware-tested | `mifare_keycheck` offload; device tests keys, returns them per sector. |
| NDEF push / EMV read / magic-UID write | Implemented, not hardware-tested | `nfc_ndef_push`, `nfc_emv_read` (masked), `nfc_write_uid` (magic/Gen1A). |

### IR

| Capability | State | Notes |
|---|---|---|
| Universal catalog | Implemented | 1648 models, category -> brand -> model. |
| Learn/RX, TV-B-Gone, raw replay | Implemented, not hardware-tested | `ir_rx`, `ir_tvbgone` (NA/EU), `ir_tx_raw`. |

### BLE

| Capability | State | Notes |
|---|---|---|
| Scan | Implemented | Device scans; phone lists and resolves vendors. |
| Spam hub (Apple/Fast Pair/Swift Pair/Samsung) | Implemented, not hardware-tested | `ble_spam_platform <ios\|android\|windows\|samsung\|all>` (authorized). |
| GATT explorer | Implemented, not hardware-tested | `ble_gatt_connect/read`, expandable services/characteristics tree. |
| BadBLE (Ducky over BLE) | Implemented, not hardware-tested | `ble_badble`; the real BadUSB execution path (needs a host to pair). |

### NRF24 (2.4 GHz add-on)

| Capability | State | Notes |
|---|---|---|
| Scan / ESB sniff / jammer / MouseJack | Implemented, not hardware-tested | Gated on an attached NRF24 add-on; jammer/MouseJack authorized-use. |

### Tools and sensors

| Capability | State | Notes |
|---|---|---|
| Mic spectrum / record, speaker tone/play | Implemented, not hardware-tested | `mic.spectrum`, `mic_record`, `speaker_tone/play`. |
| FM audio TX | Implemented, gated off | `fm_audio_*`; needs a Si4713 (absent on this board, reports absent). |
| iButton read/write, clock/RTC sync, ESP-NOW send | Implemented, not hardware-tested | `ibutton_*`, `clock_*`, `espnow_send`. |
| On-device JavaScript (BruceJS) | Implemented, not hardware-tested | `script_run`, output returned to the phone. |
| Remote control (nav + live TFT mirror) | Implemented | Hardware-validated. D-pad nav plus live screen mirror. |
| Device panel (info/diagnostics/power) | Implemented | Hardware-validated. Battery %, lite mode. |
| Console command palette | Implemented | Runs any Bruce CLI command, output on the phone. |
| Files | Partial | Browse and pull via `fs_read`; delete/mkdir exist in the VM but are not surfaced; no upload/push. |
| Session report / OSINT enrichment | Implemented, not hardware-tested | Markdown report export, OUI/OSINT vendor enrichment, heuristic insights. |

## What's missing / known limitations

- **Transports** - Wi-Fi/BLE companion transports are not built; USB-CDC is the only link.
- **FM radio** - needs a Si4713, which is absent on the T-Embed CC1101, so FM TX is gated
  off on this board (the audio path works where the chip is wired).
- **Add-on-only hardware** - 125 kHz RFID, GPS and LoRa need add-on modules; the NRF24
  features need the NRF24 add-on attached.
- **NFC edge cases** - amiibo and nested / MFKey32 MIFARE need external dongles; they are
  surfaced but honestly report "not supported headless".
- **Hardware validation** - many new commands are build-verified but not yet exercised on
  the real device (see the per-capability table above).
- **File manager** - no upload/push, and delete/mkdir are not surfaced in the app.
- **Sub-GHz** - the companion RMT buffer (~128 edges) can truncate long OOK frames.
- **MITM proxy** - HSTS / certificate-pinned targets cannot be decrypted; they fall back
  to metadata-only tunnels.

## Inspired by / adopted from other forks

Brucia builds on the Bruce ecosystem and tracks ideas from other forks. Credit to
their authors; items below are being adopted or considered, not claimed as done.

**[BruceButBetter](https://github.com/Yoursel71/BruceButBetter)**

- Hardware self-test / runtime module probing - *adopted* (replaced the hardcoded
  `CAPS.modules` with real runtime detection).
- Spectrum -> CSV capture - *adopted*, moved to the phone.
- Si5351 signal generator - considered (optional hardware).
- Dual-NRF24 spectrum - considered.

**[ESP32 Marauder](https://github.com/justcallmekoko/ESP32Marauder)**

- PMKID / EAPOL capture - *adopted*.
- Direct WiGLE upload - *adopted*.
- Channel analyzer - partial (wardrive best-2.4 GHz-channel stats).
- AirTag monitor - candidate.

Brucia's distinctive combination - compute offload, a live TFT mirror with remote
navigation, and an integrated capture -> crack flow - is not found in the other forks.
The closest phone app, ESP32-Marauder-Mobile, only visualizes streamed data.
