<div align="center">

<img src="docs/img/banner.png" alt="Brucia" width="100%">

**The phone is the brain. The ESP32 is the antenna.**

### _Let your phone burn, so your ESP32 doesn't._

An open-source platform for low-cost security research: cheap ESP32 hardware as a
radio/sensor front-end, a native Android app as the compute, storage and GUI.

> **Brucia** means *"it burns"* in Italian. It's a phonetic nod to the original *Bruce* firmware,
> but with a completely inverted architecture: let your Android CPU burn through the heavy DSP, UI
> and WPA-cracking tasks, so your little ESP32 doesn't literally *brucia*.

> **Brucia started from [Bruce](https://github.com/BruceDevices/firmware)** - it is a
> fork/derivative of the Bruce firmware, and it keeps and credits that work (AGPL-3.0).
> Huge thanks to the Bruce project and its authors.

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-E23E00.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/device-ESP32--S3-FF6D1A.svg)](#hardware)
[![App](https://img.shields.io/badge/app-Android-FFB300.svg)](#the-app)
[![Status: early development](https://img.shields.io/badge/status-early%20development-FFB300.svg)](docs/STATUS.md)
[![No ads · No telemetry](https://img.shields.io/badge/no%20ads-no%20telemetry-444.svg)](#principles)

[Usage](docs/USAGE.md) ·
[Comparison](docs/COMPARISON.md) ·
[Hardware proof](docs/HARDWARE_VERIFICATION.md) ·
[Protocol](proto/PROTOCOL.md) ·
[Code map](MAPPING.md)

<br>

<a href="https://s.click.aliexpress.com/e/_c33qeCLh" target="_blank" rel="noopener sponsored"><img src="https://img.shields.io/badge/Buy%20the%20board-AliExpress-FF4747?logo=aliexpress&logoColor=white&style=for-the-badge" alt="Buy the LilyGo T-Embed CC1101 on AliExpress" height="34"></a>
<a href="https://github.com/sponsors/1vcian" target="_blank"><img src="https://img.shields.io/badge/Sponsor-GitHub-EA4AAA?logo=githubsponsors&logoColor=white&style=for-the-badge" alt="Sponsor on GitHub" height="34"></a>
<a href="https://www.buymeacoffee.com/1vcian" target="_blank"><img src="https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?logo=buymeacoffee&logoColor=black&style=for-the-badge" alt="Buy Me A Coffee" height="34"></a>
<a href="#crypto"><img src="https://img.shields.io/badge/Bitcoin-F7931A?logo=bitcoin&logoColor=white&style=for-the-badge" alt="Donate Bitcoin" height="34"></a>
<a href="#crypto"><img src="https://img.shields.io/badge/Monero-FF6600?logo=monero&logoColor=white&style=for-the-badge" alt="Donate Monero" height="34"></a>

</div>

> **For authorized security research and education only.** You are responsible for
> complying with all applicable laws and for only testing systems you own or are
> explicitly permitted to test.

> **Project status - early development, honest disclaimer.** Brucia is young and moving fast.
> Build and test evidence is recorded in the [current audit](docs/AUDIT_2026-10-29.md);
> it does not establish hardware coverage for every supported board. Phone-side pure logic (WPA/PMKID cracking, OOK/rolling-code
> decode, MIFARE key checks, OUI lookup, protocol, ...) has **unit-test coverage**. But most of the
> **radio/hardware features have not yet been verified end-to-end on real hardware** - they are
> implemented, not proven. Treat any capability as "builds, pending on-device verification" until
> it is checked off in [TESTS.md](TESTS.md) / [docs/STATUS.md](docs/STATUS.md). Use it on hardware
> you can afford to re-flash, and don't rely on a feature until you've tested it yourself.

---

## Brucia vs Flipper Zero vs Bruce

Same cheap radios - but the phone adds compute, storage, GPS and a full-size screen.
Several of these have **never existed on a Flipper Zero or on Bruce** (marked **NEW**).

Brucia's reference hardware is the **LilyGo T-Embed CC1101 (ESP32-S3)** (the board it is
verified on; the firmware also builds for ~70 Bruce-supported boards).

| Capability | Flipper Zero | Bruce | Brucia (LilyGo T-Embed CC1101) |
|---|---|---|---|
| Wi-Fi recon / wardrive | Add-on board | Yes, tiny screen | **Yes + GPS, live map, WiGLE** |
| WPA / PMKID crack (dictionary) **· NEW** | No | No | **Yes - rockyou/SecLists, on the phone** |
| GPS + live map + WiGLE export **· NEW** | Add-on, no map | Add-on, no map | **Yes - uses the phone** |
| MITM HTTPS intercept proxy **· NEW** | No | No | **Yes - on-phone CA, TLS decrypt** |
| Wi-Fi monitor + pcap to Wireshark **· NEW** | No | Sniff only | **Yes - pcap export + PSK decrypt** |
| Sub-GHz capture / replay / decode | Yes | Yes | **Yes + waterfall, rolling-code, big screen** |
| NFC / RFID read, emulate, attack | Yes | Yes | **Yes** |
| Infrared + universal remote catalog | Yes | Yes | **Yes - 1648 codes, phone-hosted** |
| BLE scan + GATT explorer + spam | Partial | Spam | **Scan + GATT + spam hub** |
| 2.4 GHz NRF24 / MouseJack | Add-on | Add-on | **Add-on, phone-driven** |
| Microphone tools (needs audio in) **· NEW** | No microphone | Mic, no tools | **Full audio suite** |
| SSTV decode to image + transmit **· NEW** | No | No | **Yes - Robot36 and more** |
| Oscilloscope + waterfall from mic **· NEW** | No | No | **Yes** |
| Session report + OSINT insights **· NEW** | No | No | **Yes** |
| Flash firmware from the companion app **· NEW** | n/a | Web / serial only | **Yes - over USB, in-app** |
| Flash from the browser (no install) | No (qFlipper desktop) | Yes (web flasher) | **Yes - Web Serial page** |
| OTA update (app + firmware, in-app notes) **· NEW** | Desktop only | No | **Yes - app self-update + device firmware, shows release notes** |
| Full-size touch GUI + big datasets | Tiny screen | Tiny screen | **Yes - the phone** |
| Open source | Yes | AGPL-3.0 | **AGPL-3.0** |
| Hardware cost | ~$169 | ~$30-50 | **~$30-50 + a phone you own** |
| Never runs out of battery **· [SERIAL-KILLER]** | Own battery, dies | Own battery, dies | **Never - charges from your phone (it's the phone that dies)** |

*Fair notes: Flipper Zero firmware is open source with a large community-app ecosystem; some
rows (NRF24, rolling-code, GATT) also exist on Flipper add-ons or on Bruce - there the gain is
the phone's compute, storage and screen, not novelty. The rows marked **NEW** are genuinely
absent on Bruce and, for the microphone tools, physically impossible on a Flipper (no audio input).*

See the [full feature comparison](docs/COMPARISON.md).

---

## What is Brucia?

**Brucia** is a derivative of the [Bruce](https://github.com/BruceDevices/firmware)
ESP32 firmware, extended so the device becomes a **radio/sensor front-end** driven by
a **native Android companion app** that provides the heavy computation, large storage
and a full-size touch GUI.

Bruce already lets a phone *remote-control* the device, but **all computation stays on
the ESP32** - little RAM/CPU, a tiny screen, and an external GPS module is needed for
wardriving. Brucia flips this:

```
   ESP32-S3 (Brucia firmware)                Android (Brucia app)
   ──────────────────────────                ────────────────────
   Radio / sensor FRONT-END      <=USB=>      Compute + storage + GUI
   - capture / transmit                       - decode / crypto / analysis
   - realtime timing                          - Room DB, big datasets
   - raw-data framing + stream                - GPS, offline maps, viz
         │                                           │
         └──────── Transport (abstract) ─────────────┘
                   USB-CDC  |  Wi-Fi  |  BLE
                        └── one Protocol ──┘
```

Cheap hardware (~$30-50) plus the power and UX of a phone you already own.

## Verified on real hardware

Brucia runs end-to-end on a **LilyGo T-Embed CC1101 (ESP32-S3)** today: capability
handshake, live Wi-Fi wardrive, live BLE scan and CLI command routing - all streamed
over USB-CDC to the phone. See [the hardware proof](docs/HARDWARE_VERIFICATION.md).

<div align="center">
<img src="docs/img/demo.gif" alt="Brucia live wardrive over USB-CDC" width="80%">
<br><em>Real APs streamed from the device over USB; the phone adds GPS, dedup, vendor lookup and WiGLE/KML export.</em>
<br><br>
<img src="docs/img/app-ui.png" alt="Brucia Android app, flame theme" width="260">
<br><em>The Android app (flame theme), rendered from the real Compose UI.</em>
</div>

## Features

Grouped by domain. Everything below is driven from the phone over USB unless noted,
and each page is shown or disabled by **runtime module detection**: on connect the
device reports which hardware it actually has (cc1101, nfc, nrf24, fm, ir, mic,
speaker) and the app enables only the matching pages.

**Wi-Fi** - wardrive (phone GPS, Room storage, dedup, offline map, CSV/KML/GPX export,
direct WiGLE upload); WPA handshake + PMKID capture pulled over USB and cracked on the
phone (rockyou / SecLists, mangling rules, hashcat 22000/16800); deauth, station
sniffer, probe-request profiling, beacon spam; monitor mode (raw 802.11 streamed to the
phone as a pcap for Wireshark + PSK decrypt); ARP-spoof MITM and an on-phone HTTPS
intercept proxy (Brucia CA + per-host leaf certs, full decrypt, transaction viewer;
HSTS/pinned hosts fall back to metadata-only tunnels).

**Sub-GHz (CC1101)** - raw OOK capture to CSV; rolling-code-aware decoding on the phone
(Princeton/CAME/NICE/Holtek + KeeLoq fixed/rolling); `.sub` import/export; De Bruijn
brute force; replay; spectrum-sweep waterfall; jammer (authorized); radio config
(frequency/modulation/bandwidth/data-rate).

**NFC / RFID** - read / clone / emulate / write / NDEF; MIFARE key-dictionary attack;
NDEF push (tap-to-send); EMV read (masked PAN/expiry/scheme); magic/Gen1A UID write.

**IR** - universal catalog (1648 models), learn/RX, TV-B-Gone, raw replay.

**BLE** - scan; GATT explorer (services/characteristics/values); BadBLE (Ducky over
BLE); spam hub (Apple / Android Fast Pair / Windows Swift Pair / Samsung).

**NRF24 (2.4 GHz add-on)** - scanner, ESB sniffer, jammer, MouseJack; gated on the
add-on module being attached.

**Tools** - live mic spectrum, speaker tone/play, mic record, iButton read/write,
clock/RTC sync, ESP-NOW send, on-device JavaScript (BruceJS) run from the phone, Console
CLI passthrough, file manager, and a live remote screen mirror with D-pad navigation. A
platform-organized **spam hub** (Apple/iOS/Mac, Android, Windows, Samsung, Wi-Fi, All)
lets you pick the target and have the app pick the transport. FM audio TX is implemented
but gated off on this board (no Si4713; the module reports absent).

**Offload & housekeeping** - session report export (Markdown), OSINT / OUI vendor
enrichment and heuristic insights; in-app updates (app OTA self-update plus device
firmware update over USB via esptool-over-USB).

## The app

The device captures/transmits; the phone does the heavy work. A home dashboard, bottom
navigation and a persistent connection/battery status bar tie the screens together, and
every page is gated per detected hardware.

| Screen | What the phone adds on top of the device |
|-----|------------------------------------------|
| **Wardrive** | Live Wi-Fi AP stream, GPS tagging, dedup, RSSI-weighted position estimate, offline map (osmdroid), analytics (encryption/channel/vendor, best 2.4 GHz channel, security findings), IEEE OUI lookup (40k), WiGLE CSV / KML export. |
| **Crack** | Full WPA toolkit: pull a `.pcap` from the device SD over USB (or import one), crack WPA handshakes and PMKID (PBKDF2 / PTK / MIC) with several built-in dictionaries (common WPA, numeric/date, keyboard walks, Italian, router defaults) plus a rockyou-scale import manager, hashcat-style mangling rules and keys/s, plus hashcat 22000 import/export. Impossible on Flipper/Bruce alone. |
| **NFC** | Read/emulate/clone a tag; phone-side security analysis (MIFARE Classic/Crypto-1, Ultralight/NTAG, clone-risk, 65+ known MIFARE default keys). |
| **IR** | Bundled universal catalog (Flipper-IRDB subset) with search, import any `.ir`, learn a remote, transmit via the device. |
| **RF (Sub-GHz)** | Capture raw OOK timings on the device, decode on the phone (Princeton/PT2262/EV1527), encode and replay. |
| **BLE** | Scan on the device; list, dedup and resolve vendors on the phone. |
| **BadUSB** | Unlimited DuckyScript library on the phone (browse, view, copy); execute via BadBLE / standalone. |
| **Device** | Info, free memory, uptime, I2C scan, sleep/reboot. |
| **Console** | Run any Bruce CLI command from the phone and see its output - the full device feature set, exposed through the phone. |
| **Remote** | Full remote control: a D-pad drives the real device UI and the device screen is mirrored live in the app, so every standalone Bruce feature (Evil Portal, Karma, Beacon, Sniffer, ...) is reachable from the phone. |

Full walkthrough in [docs/USAGE.md](docs/USAGE.md).

### Parity with Bruce

Everything Bruce does is reachable from the app:

- **Native offload screens** for every module the device reports - Wi-Fi (wardrive,
  deauth, handshake/PMKID capture, sniffer, monitor mode), BLE (scan, GATT, spam hub),
  Sub-GHz (capture, decode, De Bruijn, spectrum, replay), IR, NFC and the NRF24 add-on -
  plus **Tools**, a **File manager**, a **Console** (every CLI command, grouped) and
  **battery / lite mode**.
- **Remote control** (the **Remote** tab) drives the real device UI - a D-pad sends `nav`
  and the device screen is mirrored live - so any remaining standalone feature (Evil
  Portal, Karma, Sniffer, Reverse Shell, Scripts, ...) is operable from the phone.
- **Hardware scope:** pages are enabled by runtime module detection. FM (Si4713) and LoRa
  are not present on the T-Embed CC1101, so those pages report the module absent; NRF24 is
  an optional add-on and its page enables when the module is attached.

### Design

A single coherent flame theme across every screen (dark, ember/amber accents).
These are the **real Compose UI**, rendered on the JVM (Paparazzi) - not mockups.

<div align="center">
<img src="docs/img/app-ui.png" alt="Wardrive screen" width="31%">
<img src="docs/img/app-crack-downloads.png" alt="Download real wordlists (rockyou, SecLists)" width="31%">
<img src="docs/img/app-nfc.png" alt="NFC Security screen" width="31%">
</div>

<div align="center"><em>Real wordlists (rockyou, SecLists) download straight to the phone - a real cracking tool, not a token list.</em></div>

<div align="center">
<img src="docs/img/app-ir.png" alt="Universal IR catalog browsed by category" width="31%">
</div>

<div align="center"><em>Universal IR catalog (1600+ models, 800+ brands) browsed by category -> brand -> model.</em></div>

## Get the hardware

Brucia's reference board is the **LilyGo T-Embed CC1101** (ESP32-S3, Sub-1 GHz CC1101, optional
PN532 NFC, built-in 1300mAh battery). If you do not own one yet:

> **LILYGO® T-Embed CC1101** - ESP32-S3 low-power Sub-1 GHz RF transceiver + PN532 NFC/RFID + LCD, 1300mAh battery.
> **~EUR 77.69** - [Buy on AliExpress](https://s.click.aliexpress.com/e/_c33qeCLh)

*(AliExpress affiliate link - it costs you nothing extra and helps fund the project. Any board on
the [supported list](ROADMAP.md) works; this is just the one Brucia is verified on.)*

## Quick start

**1. Flash the firmware** (pick one)

- **Browser** (easiest): open **https://1vcian.me/BruciaDev/** in Chrome/Edge, plug in the
  T-Embed CC1101 with a *data* USB-C cable, click *Connect & Flash*.
- **PlatformIO**: `pio run -e lilygo-t-embed-cc1101 -t upload`

**2. Install the app** - download the APK from
[Releases](https://github.com/1vcian/BruciaDev/releases), or build it:
`cd android && ./gradlew :app:assembleDebug` (JDK 21+).

**3. Connect** - plug the device into the phone and open the app. The device switches into
companion mode and the app links up automatically; if it doesn't, tap **Connect over USB**.

For how Brucia compares to Flipper Zero and Bruce, see the [feature comparison](docs/COMPARISON.md).

## Principles

- **Superset of Bruce.** Every Bruce feature keeps working. With no phone connected,
  the device is still a full Bruce (standalone mode). Companion mode is strictly additive.
- **Hardware boundary.** Radio/peripheral work stays on the ESP; compute, memory, UI and
  geolocation move to the phone.
- **100% free and open.** Firmware and app are both AGPL-3.0. No ads, no paid tiers,
  no telemetry.
- **Private and local.** Everything runs on your phone and the device - no accounts, no
  analytics, no tracking; nothing is collected or stored on any server of ours, and your
  captures stay on your phone. Brucia uses the internet only to download databases you choose
  (e.g. wordlists) and to check for updates - plus any export you trigger yourself (e.g. WiGLE).
- **Complementary, not a takeover.** The [official Bruce app](https://github.com/BruceDevices/App)
  *controls* the device; Brucia *offloads computation* to the phone. Brucia credits the
  original project and aims to upstream the firmware hooks.

## Repository layout

| Path | What |
|------|------|
| *(root)* | Fork of Bruce firmware (AGPL): `src/`, `platformio.ini`, boards. Companion code in `src/companion/`. |
| `android/` | Native Android app (Kotlin + Jetpack Compose). |
| `proto/` | The [companion wire protocol](proto/PROTOCOL.md) - single source of truth for both sides. |
| `docs/` | [Usage](docs/USAGE.md), [comparison](docs/COMPARISON.md), [hardware proof](docs/HARDWARE_VERIFICATION.md), and the browser flasher (GitHub Pages). |
| `tools/` | Host-side protocol test clients (`companion_test.py`, mock device, e2e). |
| [`MAPPING.md`](MAPPING.md) | Where Brucia hooks into the existing Bruce code. |

## Documentation

- [Project wiki](https://github.com/1vcian/BruciaDev/wiki) - getting started, features, architecture, protocol, building, FAQ
- [docs/STATUS.md](docs/STATUS.md) - where each capability stands today, and what is missing
- [SECURITY.md](SECURITY.md) - authorized-use policy and responsible disclosure
- [docs/USAGE.md](docs/USAGE.md) - flash, connect, and use every tab
- [docs/COMPARISON.md](docs/COMPARISON.md) - Flipper Zero vs Bruce vs Brucia
- [docs/HARDWARE_VERIFICATION.md](docs/HARDWARE_VERIFICATION.md) - proof it runs on real hardware
- [ARCHITECTURE.md](ARCHITECTURE.md) - the split design and how the layers fit
- [proto/PROTOCOL.md](proto/PROTOCOL.md) - the companion wire protocol
- [MAPPING.md](MAPPING.md) - integration points into Bruce
- [ROADMAP.md](ROADMAP.md) - what works, what is next, where help is wanted
- [CONTRIBUTING.md](CONTRIBUTING.md) - build, rules, and how to add a module
- [CHANGELOG.md](CHANGELOG.md) - release history

## Contributing

Contributions are welcome - especially additional [transports](ROADMAP.md)
(Wi-Fi/BLE), translations, and new modules.
Start with [CONTRIBUTING.md](CONTRIBUTING.md) and [ARCHITECTURE.md](ARCHITECTURE.md);
the [roadmap](ROADMAP.md) marks good first tasks.

## License

[AGPL-3.0](LICENSE). Derivative of Bruce; see [`NOTICE`](NOTICE) and
[`THIRD_PARTY.md`](THIRD_PARTY.md).

## Support

Brucia is free. If it helps you, you can optionally sponsor it via
[GitHub Sponsors](https://github.com/sponsors/1vcian) or
[buy me a coffee](https://buymeacoffee.com/1vcian).

### Crypto

| | Address |
|---|---|
| <img src="docs/img/btc-qr.png" alt="Bitcoin QR" width="110"> | **Bitcoin**<br>`bc1qee8a06x886ng8ce6qy84l6u6x54jnjsesgqym7` |
| <img src="docs/img/xmr-qr.png" alt="Monero QR" width="110"> | **Monero**<br>`46qDyafRkn1at3E2GMwuESBeqzpt74xE8S2kjPe35EUyKiS57Ho7cLB1vD2zH4hPmjYcYzWWxSSLZAYB5Hp1yDFrKnPWkR7` |
