# Flipper Zero vs Bruce vs Brucia

> **Early development.** This comparison shows what Brucia is **designed** to do. Everything
> compiles and the phone-side logic is unit-tested, but most radio/hardware features are **not yet
> verified end-to-end on real hardware** - treat any capability as "builds, pending on-device
> verification" until it is checked off in [TESTS.md](../TESTS.md).

How the three stack up for low-cost security research. **Brucia** = the Bruce
firmware plus the native Android companion app, with the phone doing the heavy
computation, storage, GUI, geolocation, and - new - **remote control of the whole
device from the app**.

## The core idea

- **Flipper Zero**: a polished, standalone all-in-one. Everything runs on the
  device's small MCU and mono screen; a phone app exists mainly for sync/control.
- **Bruce**: turns cheap ESP32 hardware into a Flipper-like multitool. More radios
  than Flipper (native Wi-Fi + BLE), but all computation stays on the ESP32 - little
  RAM/CPU, a tiny screen, and an external GPS module is needed for wardriving.
- **Brucia**: keeps the ESP32 as a radio/sensor front-end and **moves the heavy work
  to the phone**. The phone brings a fast CPU, gigabytes of RAM/storage, GPS, maps,
  internet, big datasets and a full-size touch GUI. The app can drive the device's
  menus directly (navigation + a live screen mirror), so the ESP32's own tiny
  screen stops being the bottleneck.

## Killer features

The standouts only Brucia has - the phone makes them possible on cheap ESP32 hardware.
Rows marked **[KILLER]** in the tables below are these. (No Flipper or standalone Bruce
equivalent, or only with external modules and far less capability.)

- **WPA/WPA2 cracking on the phone** - capture the handshake/PMKID on the device, crack it
  with hashcat-style rules and real wordlists on the phone's CPU. Impossible on a Flipper or
  on the ESP32 alone.
- **Audio DSP suite** (spectrum, waterfall, SSTV, oscilloscope, DTMF) - the Flipper has **no
  audio input at all**, so this is physically impossible there; the ESP has no screen or compute
  for it.
- **Wardrive with built-in GPS, offline maps and WiGLE/KML export** - no external GPS module,
  a full-size map, and analytics computed on the phone.
- **On-phone MITM HTTPS proxy** + network scan with version/CVE hints - a laptop-class recon
  step from a phone.
- **Universal IR catalog** (~1600 models) carried on the phone, not a tiny SD subset.
- **In-app firmware flashing over USB** - no PC, no web flasher; update the device from the app.
- **Full remote control + live screen mirror** - the app drives every device menu, so the tiny
  screen stops being the bottleneck.
- **On-phone PCAP dissection** - open an 802.11 capture the device took in monitor mode and dissect
  it (types, addresses, BSSIDs, EAPOL) on the phone; the device could never show this.
- **WIDS** - continuous deauth/evil-twin detection with a timestamped alert log; a defensive tool,
  which almost nothing in this class offers.
- **Bug / spy-cam RF sweep** - home in on a hidden transmitter by signal strength across Wi-Fi, BLE
  and sub-GHz at once.
- **Phone-side macros** and a **3D RF-source map** - orchestration and visualization the device alone
  cannot do.

## Hardware

| | Flipper Zero | Bruce (T-Embed CC1101) | Brucia (same HW + phone) |
|---|---|---|---|
| MCU | STM32WB55 (M4 @64MHz, 256KB RAM) | ESP32-S3 (@240MHz dual, 512KB RAM + 8MB PSRAM) | ESP32-S3 **+ phone SoC** (8 cores, GBs RAM) |
| Screen | 1.4" mono 128x64 | 1.9" color | **phone display** (full touch, maps) + device screen mirrored on the phone |
| Storage | microSD | microSD | **phone storage** (tens of GB) |
| GPS | external module | external module | **phone GPS** (built in, free) |
| Internet | no | Wi-Fi | Wi-Fi + **phone LTE/Wi-Fi** |
| Price | ~$169 | ~$30-50 | ~$30-50 (+ a phone you own) |
| Battery **[SERIAL-KILLER]** | own battery, dies | own battery, dies | **never dies - charges from your phone over USB (it's the phone that dies)** |

## Radios and I/O

| Capability | Flipper Zero | Bruce | Brucia |
|---|---|---|---|
| Sub-GHz (CC1101) | yes (**same CC1101 chip** as the T-Embed) | yes | yes (capture/TX on ESP, **rolling-code decode (KeeLoq) + De Bruijn + spectrum sweep on phone**) |
| Wi-Fi | only via ESP32 dev board | **native** | native, plus **app-driven deauth / handshake+PMKID capture / sniffer / monitor-mode pcap** |
| BLE | yes | **native** | native, plus **GATT explorer, multi-platform spam hub, BadBLE** |
| NRF24 2.4GHz | no | via add-on | via add-on (**scan / ESB sniff / jammer / MouseJack**) |
| IR | yes | yes | yes (+ **phone-side universal catalog, TV-B-Gone, learn/replay**) |
| NFC 13.56 | yes (mature) | via PN532 | via PN532 (+ **NDEF push, masked EMV read, magic-UID write**) |
| 125kHz RFID | yes | partial (add-on) | **read (rfid125_read) on boards with the LF reader; no native write/clone** |
| iButton | yes | yes | yes |
| BadUSB | yes | yes | yes (+ **BadBLE: Ducky over BLE**) |
| FM transmit | no | no | TX path implemented, **needs a Si4713** (absent on this board) |
| GPIO | yes | yes | yes |

Correction worth stating plainly: **the Flipper's sub-GHz radio is a CC1101 - the
same chip as the T-Embed.** So sub-GHz is genuine parity, not a Flipper hardware
advantage. The Flipper's edge there is software maturity (years of protocol
decoders and a polished UI), not silicon; Brucia closes that gap with the phone's
decoders (KeeLoq/rolling-code, De Bruijn, spectrum).

## Flipper parity, by module

The honest way to read Flipper parity is per-part: what the capability needs, whether
it is on the T-Embed CC1101 out of the box, or an add-on that Brucia caps-gates (the
tool appears only when `caps{}` detects the part). "Can never" means no add-on path on
this board - not a software limit.

| Flipper capability | Part it needs | On T-Embed? | Brucia status |
|---|---|---|---|
| Sub-GHz 300-928 MHz | CC1101 | **onboard** | parity (same chip) |
| Wi-Fi | Wi-Fi radio | **onboard** (Flipper needs an ESP devboard) | Brucia ahead (native) |
| BLE | BLE radio | **onboard** | parity + GATT/spam/BadBLE |
| IR TX/RX | IR LED + receiver | **onboard** | parity + phone catalog |
| iButton / 1-Wire | 1-Wire on a GPIO | **onboard** (GPIO) | have (`ibutton_*`) |
| NFC 13.56 MHz | PN532 or ST25R3916 | PN532 (external on T-Embed) | have; `caps.nfc`-gated |
| 125 kHz LF read | UART LF reader (RDM6300) | add-on | read: `rfid125_read` (`caps`-gated) |
| 125 kHz LF **write / clone to T5577** | LF read/write front-end (EM4095 / U2270B) + coil | add-on | achievable: needs that module + a firmware driver (not yet built), then `caps`-gated |
| 125 kHz LF **arbitrary emulation** | external LF device (e.g. Proxmark3) | external gadget | external-device only (like Chameleon/PN532Killer for HF) |
| NRF24 2.4 GHz | NRF24 module | add-on (QWIIC) | have; `caps.nrf24`-gated |
| USB BadUSB / HID | native USB (S3 has it) | **onboard** | BadBLE now; USB-HID path addable |
| U2F / FIDO token | native USB HID | **onboard** | addable (USB HID), not yet built |
| FM transmit | Si4713 | add-on | TX path done, `caps.fm`-gated (no Si4713 here) |
| Ethernet attacks | W5500 | add-on | have; `caps.eth`-gated |
| GPIO / UART / I2C / SPI | exposed pins | partial (Grove/QWIIC, fewer pins) | fewer breakout pins than Flipper |

Takeaways for the modules: there is **no true "never" on this board**. 125 kHz LF
write/clone is achievable with an EM4095/U2270B read-write front-end + coil plus a
firmware driver we would add (then caps-gated); only *arbitrary* LF emulation needs a
separate external device (Proxmark3), the LF equivalent of Chameleon/PN532Killer for
HF. Everything else is onboard, already caps-gated for its module, or software-addable
(USB HID: BadUSB, U2F).

## Computation and data (where Brucia pulls ahead)

| | Flipper Zero | Bruce | Brucia |
|---|---|---|---|
| OUI vendor DB | small/none | small (SD) | **full IEEE DB (40k) on phone** |
| IR code catalog | SD files | SD files | **universal catalog (1600+ models), category -> brand -> model, searchable** |
| Wardriving | app/module needed | on-device, needs GPS module | **phone GPS + map + dedup + WiGLE/KML export** |
| Signal analysis | on-device | on-device (limited) | **phone: decode, stats, heatmaps, RSSI localization** |
| Wordlists/dictionaries | SD, size-limited | SD, size-limited | **full WPA toolkit: built-in lists + in-app rockyou/SecLists download, not just a small token list** |
| Traffic analysis | no | no | **on-phone HTTPS intercept proxy (decrypt + viewer) + monitor-mode pcap for Wireshark** |
| Reporting / OSINT | no | no | **Markdown session reports, OUI/OSINT vendor enrichment, heuristic insights** |
| Visualization | mono screen | small color screen | **full GUI, maps, charts, live device-screen mirror** |

## Ecosystem

| | Flipper Zero | Bruce | Brucia |
|---|---|---|---|
| Open source | firmware yes | **fully** (AGPL) | **fully** (AGPL) |
| Cost of entry | high | low | low |
| Community apps | very large, mature app store | growing | builds on Bruce + new app |
| Standalone (no phone) | yes | yes | yes (companion is additive) |

## Honest trade-offs

**Where Flipper still wins:** a single polished device, no phone needed, a mature
NFC/RFID stack, a more mature and better-integrated sub-GHz RF hardware/UX, and a
large, established app store built over years.

**Where Bruce wins:** price, native Wi-Fi/BLE, and open firmware on cheap hardware.
It is the foundation Brucia is built on, and Brucia stays a full, usable Bruce
device even with no phone attached.

**Where Brucia wins:** computation and data scale (the phone), GPS and maps for
free, unlimited storage and large offline databases (wordlists, IR catalog, OUI
DB), far richer visualization and analysis, remote control of the device's full
UI from the phone with a live screen mirror, and the lowest total cost - while
staying a full Bruce when no phone is attached.

> Brucia is complementary to Bruce and its official app, and for authorized security
> research and education only. See [SECURITY.md](../SECURITY.md).

## Brucia app - feature matrix

The Brucia companion app (phone) vs what Flipper Zero offers out of the box.

| Capability | Flipper Zero | Brucia (app + device) |
|---|---|---|
| Remote control | n/a (it is the device) | drive the full device menu from the phone, with a live screen mirror |
| Wi-Fi wardriving | add-on/apps | built in: live AP stream, phone GPS, track + map, dedup |
| OUI vendor lookup | limited | full IEEE DB (40k) on the phone |
| Wi-Fi security analysis | no | encryption/channel breakdown, best channel, findings |
| RSSI AP localization | no | weighted estimate, improves over time |
| Deauthentication | no | app-driven, device-executed, for authorized testing only |
| Handshake capture | no | app-driven capture, saved to the device, pulled for cracking |
| Station sniffer | no | channel-hopping sniffer reporting clients and the BSSID they talk to |
| Probe-request profiling | no | logs the SSIDs nearby clients look for, grouped by SSID/client, CSV export |
| Beacon spam | no | app supplies the SSID list, device floods (authorized) |
| Wi-Fi monitor mode / pcap | no | raw 802.11 streamed to the phone as a real pcap (open in Wireshark + your PSK to decrypt) |
| ARP-spoof MITM | no | app-driven transparent redirect (authorized) |
| HTTPS intercept proxy | no | on-phone proxy, Brucia CA + per-host leaf certs, full decrypt + transaction viewer (HSTS/pinning -> metadata-only tunnel) |
| WiGLE upload | no | upload collected APs straight to wigle.net from the phone |
| Export | SD files | WiGLE CSV + KML + GPX, shared via the OS share sheet |
| IR catalog | SD .ir files | universal catalog, browse category -> brand -> model, 1600+ models, searchable |
| IR learn | yes | yes (`ir rx`), parsed and saved on the phone |
| IR library size | SD-limited | phone-sized, unlimited |
| Sub-GHz capture | yes | yes (raw timings), decoded on the phone, CSV export |
| Sub-GHz decode | on-device | on the phone, rolling-code-aware (Princeton/CAME/NICE/Holtek + KeeLoq fixed/rolling) |
| Sub-GHz replay | yes | yes (`rf tx`), saved signals persisted |
| Sub-GHz `.sub` import/export | yes | import/export Flipper-style `.sub` on the phone |
| Sub-GHz De Bruijn brute force | partial | minimal brute-force sequence generated and run from the phone |
| Sub-GHz spectrum sweep | yes | live waterfall + RSSI-vs-frequency rendered on the phone |
| Sub-GHz jammer | partial | bounded, authorized-use only |
| NFC/RFID | mature | read/write/emulate/clone/info/erase (PN532) |
| NFC NDEF push / EMV read | partial | tap-to-send NDEF; masked EMV read (PAN/expiry/scheme) |
| NFC magic-UID write | yes | magic/Gen1A card UID write |
| BadUSB | yes | unlimited DuckyScript library on the phone; **BadBLE** runs a script as a BLE HID keyboard |
| BLE scan | limited | device scans, phone lists + resolves vendors |
| BLE GATT explorer | partial (apps) | connect, discover services/characteristics, read values (expandable tree) |
| BLE spam | no | app-driven, device-executed, for authorized testing only |
| Spam hub (multi-platform) | no | Apple/iOS/Mac, Android (Fast Pair), Windows (Swift Pair), Samsung, Wi-Fi - pick the target, app picks the transport |
| NRF24 scan / ESB sniff | no (needs add-on) | with the NRF24 add-on attached |
| MouseJack | no | keystroke injection of vulnerable wireless keyboards/mice (NRF24 add-on, authorized) |
| WPA/WPA2 handshake crack | no | **dictionary attack on the phone** (PBKDF2/PTK/MIC) from a captured .pcap **[KILLER]** |
| PMKID (clientless) crack | no | phone-side (hashcat 16800), with keys/s |
| Wordlist source | SD, size-limited | full WPA toolkit: bundled lists + **in-app rockyou/SecLists download**, not just a small token list |
| Mangling rules | no | hashcat-style wordlist expansion on the phone |
| hashcat 22000 export/import | no | export AND crack pasted 22000/16800 lines on the phone |
| Pull capture over USB | manual SD | pull the .pcap from the device SD over USB, then crack |
| NFC NDEF builder / key dictionary | partial | compose NDEF, host a MIFARE key dictionary on the phone |
| Wordlist manager | no | import/store large wordlists on the phone, including downloaded sets |
| NFC security analysis | manual | phone flags Crypto-1/Ultralight/clone risk + 65+ default-key dictionary |
| Full CLI console | limited | console with a command palette, any Bruce command with output on the phone |
| File manager | SD browser on-device | browse/manage device storage from the phone |
| Device panel | menus | info, free memory, uptime, I2C, power, **battery % and a lite mode for low-power phones** |
| Mic / speaker | partial | live mic spectrum, record to WAV, speaker tone/play |
| iButton | yes | read/write |
| ESP-NOW | no | broadcast an ESP-NOW message from the phone |
| On-device JavaScript | no | run BruceJS from the phone, output returned |
| Clock / RTC sync | yes | sync the device RTC from the phone |
| Runtime module detection | n/a | pages gated by the hardware the device actually reports |
| Session report / OSINT | no | Markdown report export, OUI/OSINT vendor enrichment, heuristic insights |
| In-app updates | qFlipper (desktop) | **app OTA self-update + device firmware update over USB** (esptool-over-USB) |
| Firmware flashing | qFlipper | browser flasher (Web Serial) + web page |
| Price | ~$169 | ~$30-50 + a phone you own |

For authorized security research and education only. See [SECURITY.md](../SECURITY.md)
for the project's responsible-use policy.

---

## Full feature index (every Brucia tool vs Flipper & native Bruce)

Legend: **yes** = present · **add-on** = needs an extra module · **no** = absent · **n/a** = not applicable.
"Brucia" = the companion app driving the ESP32; items marked *(planned)* are on the roadmap.

### Wi-Fi
| Feature | Flipper Zero | Bruce (native) | Brucia |
|---|---|---|---|
| Scan / wardrive | add-on app | yes (needs GPS module) | **yes + phone GPS, live map, dedup, WiGLE/KML/GPX** **[KILLER]** |
| Station / client sniffer | no | yes | **yes (phone list, vendor resolve)** |
| Probe-request profiling | no | yes | **yes (grouped, CSV)** |
| Monitor mode -> pcap | no | sniff only | **yes (Wireshark pcap + PSK decrypt)** |
| Deauth | no | yes | **yes (app-driven, authorized)** |
| Handshake capture | no | yes | **yes (pulled to phone)** |
| PMKID capture | no | yes | **yes** |
| WPA/WPA2 dictionary crack | no | no | **yes (on the phone, rockyou/SecLists)** **[KILLER]** |
| PMKID crack (hashcat 16800) | no | no | **yes (on the phone)** **[KILLER]** |
| Evil Portal / evil twin + captive page | no | yes | **yes (live captured creds to the app)** |
| Karma attack | no | yes | **yes (karma, headless lure)** |
| Beacon spam | no | yes | **yes (app supplies SSIDs)** |
| ARP spoof / NetCut MITM | no | yes | **yes (+ on-phone HTTPS intercept proxy)** **[KILLER]** |
| Responder (LLMNR/NBT-NS) | no | yes | **yes (responder; native SMB/NTLM one too)** |
| Jam / deauth detector | no | yes | **yes (jam_detect)** |
| Change MAC | no | yes | **yes (wifi_set_mac, random)** |
| Connect to Wi-Fi (station) | n/a | yes | **yes (wifi_connect)** |
| Network scan (hosts/ports/CVE) | no | hosts only | **yes (on-phone nmap-lite + CVE heuristics)** **[KILLER]** |
| Channel analyzer (congestion) | no | partial | **yes (live, best-channel advice)** |
| Brucegotchi / Pwnagotchi | no | yes | **yes (headless, handshakes to SD)** |

### Sub-GHz (CC1101)
| Feature | Flipper Zero | Bruce | Brucia |
|---|---|---|---|
| Capture / replay / raw TX | yes (mature) | yes | **yes** |
| `.sub` import/export | yes | yes | **yes (on the phone)** |
| OOK + rolling-code decode (KeeLoq/Princeton/CAME/NICE/Holtek) | yes | partial | **yes (on the phone)** |
| Spectrum / waterfall | yes | partial | **yes (phone render)** |
| De Bruijn / brute force | partial | yes | **yes (subghz_bruteforce)** |
| Jammer | partial | yes | **yes (bounded, authorized)** |

### BLE
| Feature | Flipper Zero | Bruce | Brucia |
|---|---|---|---|
| Scan | limited | yes | **yes (+ vendor resolve)** |
| GATT explorer | apps | partial | **yes** |
| Spam hub (Apple/Android/Win/Samsung) | no | yes | **yes** |
| BadBLE (Ducky over BLE HID) | no | yes | **yes** |
| iBeacon advertiser | no | yes | **yes (ble_ibeacon)** |
| Media-control hijack | no | yes | **yes (ble_media)** |
| BLE Presenter / Keyboard (HID) | no | yes | **yes (ble_hid_*, BLE Remote tool)** |

### NFC / RFID (PN532 add-on) & IR
| Feature | Flipper Zero | Bruce | Brucia |
|---|---|---|---|
| NFC read/write/emulate/clone | yes (mature) | add-on | **add-on (+ phone key dictionary, security analysis)** |
| NDEF push / EMV read / magic-UID | partial | add-on | **add-on** |
| SRIX / ST25TB read | no | add-on | **yes (srix_read, onboard PN532)** |
| Chameleon / amiibo / mfkey / LF clone | partial | add-on (external device) | external device only (reported honestly) |
| IR send / learn / TV-B-Gone | yes | yes | **yes (+ 1600+-model phone catalog)** **[KILLER]** |
| IR jammer | no | yes | **yes (ir_jammer)** |
| Custom IR remote builder (grid of taught buttons) | no | no | **yes - NEW (build your own remote)** |

### Audio (mic/speaker) - mostly impossible on Flipper (no audio in)
| Feature | Flipper Zero | Bruce | Brucia |
|---|---|---|---|
| Mic spectrum / waterfall / oscilloscope | no | no | **yes (phone DSP)** **[KILLER]** |
| SSTV RX -> image / TX | no | no | **yes (Robot36/Martin/Scottie)** **[KILLER]** |
| Tone + DTMF generator | partial | partial | **yes (native dual-tone)** |
| DTMF decoder (from mic) | no | no | **yes (on the phone)** **[KILLER]** |
| Record to WAV | no | yes | **yes (+ spectrum/scope PNGs)** |
| RTTTL song player (meme presets) + stop | no | yes | **yes (background, stoppable)** |
| Melody composer (build + save tunes) | no | no | **yes - NEW (Tone tool composer)** |
| 2.4GHz NRF24 scan/sniff/jammer/MouseJack | add-on | add-on | **add-on** |

### Ethernet (W5500 add-on)
| Feature | Flipper Zero | Bruce | Brucia |
|---|---|---|---|
| Wired link status / DHCP / static config | no | yes | **yes (eth_status, eth_config)** |
| DHCP starvation | no | yes | **yes (eth_dhcp_starve, bounded/headless)** |
| MAC flooding | no | yes | **yes (eth_mac_flood, bounded/headless)** |
| ARP scan / poison / spoof (wired) | no | yes | standalone (device menu) |

### Device / UX / platform
| Feature | Flipper Zero | Bruce | Brucia |
|---|---|---|---|
| Full touch GUI + big datasets | no (mono) | no (small) | **yes (the phone)** |
| Live device-screen mirror + remote nav | partial | n/a | **yes** |
| Theme sync (device color wins) | no | device-only | **yes (app matches, live)** |
| LED color / brightness / 9 animations from phone | no | device-only | **yes** |
| Screen brightness / lite power mode from phone | no | device-only | **yes** |
| ESP-NOW broadcast | no | yes | **yes** |
| On-device JavaScript (BruceJS) | no | yes | **yes (from phone)** |
| In-app firmware flash (USB, esptool stub) | qFlipper (desktop) | web flasher | **yes (from the phone, in-app)** |
| App OTA self-update | desktop | n/a | **yes (GitHub Releases)** |
| iButton / clock / file manager / console | yes | yes | **yes** |
| Session report + OSINT enrichment | no | no | **yes** |
| Price | ~$169 | ~$30-50 | **~$30-50 + a phone you own** |

### Analysis, automation and defense (phone-side, new)
| Feature | Flipper Zero | Bruce | Brucia |
|---|---|---|---|
| On-phone PCAP dissection (Wireshark-lite) | no | no | **yes (types/addr/BSSID/EAPOL) [KILLER]** |
| WIDS - deauth / evil-twin detection + log | no | partial (jam detect) | **yes (continuous, timestamped alerts) [KILLER]** |
| Hidden-transmitter / bug & spy-cam RF sweep | no | no | **yes (Wi-Fi+BLE+sub-GHz hotter/colder) [KILLER]** |
| 3D RF-source map (rotate / zoom / save PNG) | no | no | **yes** |
| RF place-fingerprint (change / new-device detection) | no | no | **yes (defensive) [KILLER]** |
| Unified RF presence timeline (Wi-Fi/BLE over time) | no | no | **yes (persistent, cross-session) [KILLER]** |
| Phone-side automation macros | no | no | **yes (chain device commands)** |
| Melody composer | no | no | **yes** |
| Evil Portal HTML editor + live preview | no | SD templates | **yes (edit + WebView preview + saved templates)** |
