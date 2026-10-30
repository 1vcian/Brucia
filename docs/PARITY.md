# Brucia feature-parity report

**Question answered:** is every native Bruce feature reachable from the Brucia Android app?

**Status: historical source inventory, not a verified parity claim.** The tables
below identify dedicated tools, commands and possible Remote/Console paths.
Reachability through a mirrored menu does not prove native companion integration,
correct operation, hardware availability or current acceptance. Reconcile entries
with code under S11 in the [task ledger](../skills/brucia-maintenance/references/backlog.md).
Line numbers and tab indices below are historical navigation hints.

Mode constraint: the Remote/Console path requires the device to be on its normal UI and
is mutually exclusive with companion mode (`startRemote` cancels the companion loops,
`WardriveViewModel.kt:626`). In companion mode the device is locked to the app and shows the
status screen (`companionModeUSB`, `src/companion/companion.cpp:3770`); the user leaves
companion mode (or uses Remote/Console directly) to reach MIRROR-ONLY features.

Buckets: **APP-TOOL** (dedicated app screen) / **COMPANION-CMD** (companion command, reachable
from the app, at least via Console) / **MIRROR-ONLY** (no command, driven through the Remote
screen-mirror) / **EXTERNAL-DEVICE** (needs extra hardware) / **MISSING** (genuine gap).
Companion commands now live in the domain handlers under `src/companion/`; see
[the module map](../src/companion/README.md). App tools are `toolCategories` in
`android/app/src/main/java/app/brucia/MainActivity.kt:1211`.

---

## Wi-Fi (`src/core/menu_items/WifiMenu.cpp`, `src/modules/wifi/wifi_atks.cpp`)

| Native Bruce feature | Bucket | How (command / tool / note) |
|---|---|---|
| Connect to WiFi | APP-TOOL | Connect tool (tab 34) + `wifi_connect` |
| Start WiFi AP | MIRROR-ONLY | no command; Remote/Console |
| Turn Off WiFi | COMPANION-CMD | `wifi_disconnect` |
| AP info | COMPANION-CMD | `wifi_status` |
| Wifi Atks > Target Atks (Deauth / Clone Portal / Deauth+Clone(+Verify)) | APP-TOOL | Evil Portal (tab 30) + `wifi_deauth`, `evil_portal_start/stop/status` (wifi_atks.cpp:838) |
| Wifi Atks > Karma Attack | APP-TOOL | Karma (tab 38) + `karma` |
| Wifi Atks > Beacon SPAM | APP-TOOL | Spam (tab 16) + `wifi_beacon_spam` |
| Wifi Atks > Deauth Flood | COMPANION-CMD | `wifi_deauth` |
| Wifi Atks > Enhanced Deauth (All / Target List) | COMPANION-CMD | `wifi_deauth` (wifi_atks.cpp:1137) |
| Evil Portal | APP-TOOL | Evil Portal (tab 30) + `evil_portal_start/stop/status` |
| NetCut | COMPANION-CMD | `arp_spoof` |
| Listen TCP | MIRROR-ONLY | no command; Remote/Console |
| Client TCP | MIRROR-ONLY | no command; Remote/Console |
| SOCKS4 Proxy | MIRROR-ONLY | no command; Remote/Console |
| TelNET | MIRROR-ONLY | no command; Remote/Console |
| SSH | MIRROR-ONLY | no command; Remote/Console |
| Sniffer | APP-TOOL | Sniffer (tab 11) / Monitor (tab 17) |
| Channel Analyzer | APP-TOOL | Channels (tab 26) |
| Jam Detect | COMPANION-CMD | `jam_detect` |
| Scan Hosts | APP-TOOL | Network Scan (tab 31, on-phone) |
| Wireguard | MIRROR-ONLY | no command; Remote/Console |
| Responder | APP-TOOL | Responder (tab 40) + `responder` |
| Brucegotchi | APP-TOOL | Brucegotchi (tab 35) + `brucegotchi` |
| WiFi Pass Recovery | MIRROR-ONLY | no command; Remote/Console |
| Config > Change MAC | APP-TOOL | MAC (tab 33) + `wifi_get_mac`/`wifi_set_mac` |
| Config > Add/Remove Evil Wifi, Evil Wifi Settings | MIRROR-ONLY | no command; Remote/Console |

## Sub-GHz / RF (`src/core/menu_items/RFMenu.cpp`)

| Native Bruce feature | Bucket | How (command / tool / note) |
|---|---|---|
| Scan/copy | APP-TOOL | RF (tab 2) + `subghz_replay` (capture/replay) |
| Record RAW | COMPANION-CMD | `subghz_tx_raw` / `subghz_replay` (+ RF tool) |
| Custom SubGhz | COMPANION-CMD | `subghz_tx_raw`, `subghz_config` |
| Spectrum | MIRROR-ONLY | no command; Remote/Console |
| RSSI Spectrum | MIRROR-ONLY | no command; Remote/Console |
| SquareWave Spec | MIRROR-ONLY | no command; Remote/Console |
| Spectogram (waterfall) | MIRROR-ONLY | no command; Remote/Console |
| Listen | MIRROR-ONLY | no command; Remote/Console |
| Bruteforce | COMPANION-CMD | `subghz_bruteforce` |
| Jammer | COMPANION-CMD | `subghz_jammer` |
| Config (TX/RX pin, module, frequency) | COMPANION-CMD | `subghz_config` (freq/module); pins via Remote/Console |

## NFC / RFID (`src/core/menu_items/RFIDMenu.cpp`)

| Native Bruce feature | Bucket | How (command / tool / note) |
|---|---|---|
| Read tag (TagOMatic) | APP-TOOL | NFC (tab 3) + `mifare_keycheck`, `nfc_ndef_push`, `mifare_nested`, `mifare_mfkey32` |
| Read EMV | COMPANION-CMD | `nfc_emv_read` |
| Read 125kHz | COMPANION-CMD | `rfid125_read` |
| Scan tags | APP-TOOL | NFC (tab 3) / Remote |
| Load file | APP-TOOL | NFC (tab 3) + `fs_read` / `nfc_ndef_push` |
| Erase data | APP-TOOL | NFC (tab 3) / Remote |
| Emulate NDEF | APP-TOOL | NFC (tab 3) / Remote |
| Write NDEF | COMPANION-CMD | `nfc_ndef_push` |
| Write UID (magic tag) | COMPANION-CMD | `nfc_write_uid` |
| Mifare nested / mfkey32 | COMPANION-CMD | `mifare_nested`, `mifare_mfkey32` |
| SRIX Tool | EXTERNAL-DEVICE | needs PN532; `srix_read` |
| Config (RFID module / Add MIF Key) | COMPANION-CMD | `mifare_keycheck` (keys); module via Remote/Console |
| Amiibolink | EXTERNAL-DEVICE | Amiibolink device; `nfc_amiibo` |
| Chameleon | EXTERNAL-DEVICE | ChameleonUltra device |
| PN532 BLE | EXTERNAL-DEVICE | PN532 BLE device |
| PN532 UART (PN532Killer) | EXTERNAL-DEVICE | PN532-Killer device |

## IR (`src/core/menu_items/IRMenu.cpp`)

| Native Bruce feature | Bucket | How (command / tool / note) |
|---|---|---|
| TV-B-Gone | APP-TOOL | IR (tab 1) + `ir_tvbgone` |
| Custom IR | APP-TOOL | IR (tab 1) / IR Remote (tab 37) + `ir_tx_raw` |
| IR Read | APP-TOOL | IR Remote (tab 37) + `ir_rx` |
| IR Jammer | COMPANION-CMD | `ir_jammer` |
| Config (TX/RX pin, repeats) | MIRROR-ONLY | no command; Remote/Console |

## BLE (`src/core/menu_items/BleMenu.cpp`, `src/modules/ble/`)

| Native Bruce feature | Bucket | How (command / tool / note) |
|---|---|---|
| Media Cmds | COMPANION-CMD | `ble_media` |
| BLE Scan | APP-TOOL | BLE (tab 5) |
| iBeacon | COMPANION-CMD | `ble_ibeacon` |
| Bad BLE | COMPANION-CMD | `ble_badble` |
| BLE Keyboard | APP-TOOL | BLE Remote (tab 39) + `ble_hid_begin/key/type/end` |
| BLE Spam | APP-TOOL | Spam (tab 16) + `ble_spam`, `ble_spam_platform`, `ble_fastpair_spam` |
| BLE Suite (12-item suite: vuln scan, profiling, FastPair, HFP, audio, HID, memory corruption, DoS, payload, testing, universal chain, sniffer) | MIRROR-ONLY | partial commands (`ble_gatt_connect`, `ble_gatt_read`, `ble_fastpair_spam`, `ble_badble`); full suite via Remote/Console (BLE_Suite.cpp:4919) |
| Ninebot | MIRROR-ONLY | no command; Remote/Console |
| Presenter mode | APP-TOOL | BLE Remote (tab 39) + `ble_hid_*` |
| BLE Sniffer | MIRROR-ONLY | BLE scan via BLE tool (tab 5); raw sniffer via Remote/Console |

## Others (`src/core/menu_items/OthersMenu.cpp`)

| Native Bruce feature | Bucket | How (command / tool / note) |
|---|---|---|
| QRCodes | MIRROR-ONLY | no command; Remote/Console |
| Megalodon (shark_setup, pcap capture) | APP-TOOL | Monitor (tab 17) / Sniffer (tab 11) |
| Microphone > Spectrum / Record | APP-TOOL | Audio (tab 25) + `mic_record` |
| BadUSB & HID > BadUSB | APP-TOOL | BadUSB (tab 4) |
| BadUSB & HID > USB Keyboard | MIRROR-ONLY | no command (USB HID); Remote/Console |
| BadUSB & HID > USB Clicker | MIRROR-ONLY | no command; Remote/Console |
| BadUSB & HID > USB U2F | MIRROR-ONLY | no command; Remote/Console |
| iButton | APP-TOOL | Gadgets (tab 20) + `ibutton_read`/`ibutton_write` |

## FM (`src/core/menu_items/FMMenu.cpp`)

| Native Bruce feature | Bucket | How (command / tool / note) |
|---|---|---|
| Broadcast std | APP-TOOL | FM (tab 14) + `fm_tx`, `fm_audio_begin/data/stop` |
| Broadcast reserved | APP-TOOL | FM (tab 14) + `fm_tx` (reserved freq) |
| Broadcast stop | COMPANION-CMD | `fm_stop` |
| FM Spectrum | MIRROR-ONLY | no command; Remote/Console |
| Hijack TA | MIRROR-ONLY | no command; Remote/Console |

## LoRa (`src/core/menu_items/LoRaMenu.cpp`)

| Native Bruce feature | Bucket | How (command / tool / note) |
|---|---|---|
| Chat | APP-TOOL | LoRa (tab 22) + `lora_send`, `lora_recv` |
| Change username | MIRROR-ONLY | no command; Remote/Console |
| Change Frequency | COMPANION-CMD | `lora_config` (+ LoRa tool) |

## 2.4GHz / NRF24 (`src/core/menu_items/NRF24.cpp`)

| Native Bruce feature | Bucket | How (command / tool / note) |
|---|---|---|
| Information | APP-TOOL | NRF24 (tab 13) + `nrf24_scan` |
| Spectrum | APP-TOOL | NRF24 (tab 13) + `nrf24_scan` / `nrf24_sniff` |
| MouseJack | APP-TOOL | NRF24 (tab 13) + `nrf24_mousejack` |
| NRF Jammer | APP-TOOL | NRF24 (tab 13) + `nrf24_jammer` |
| ESB Sniff | COMPANION-CMD | `nrf24_sniff` |
| Config pins | MIRROR-ONLY | no command; Remote/Console |
| ESP-NOW broadcast (companion extension) | APP-TOOL | ESP-NOW (tab 32) + `espnow_send` |

## Ethernet (`src/core/menu_items/EthernetMenu.cpp`)

| Native Bruce feature | Bucket | How (command / tool / note) |
|---|---|---|
| Scan Hosts | APP-TOOL | Ethernet (tab 23) + `eth_status` |
| DHCP Starvation | COMPANION-CMD | `eth_dhcp_starve` |
| MAC Flooding | COMPANION-CMD | `eth_mac_flood` |
| Config | APP-TOOL | Ethernet (tab 23) + `eth_config` |

## GPS (`src/core/menu_items/GpsMenu.cpp`)

| Native Bruce feature | Bucket | How (command / tool / note) |
|---|---|---|
| Wardriving (WiFi / BLE / Both) | APP-TOOL | Wardrive (tab 0, uses phone GPS) |
| GPS Tracker | MIRROR-ONLY | no command; Remote/Console |
| Config (Baudrate, GPS Pins) | MIRROR-ONLY | no command; Remote/Console |

## Clock (`src/core/menu_items/ClockMenu.cpp`)

| Native Bruce feature | Bucket | How (command / tool / note) |
|---|---|---|
| Clock (show / set) | APP-TOOL | Gadgets (tab 20) + `clock_get`/`clock_set` |
| Timer | MIRROR-ONLY | no command; Remote/Console |

## Files / Connect (`src/core/menu_items/FileMenu.cpp`, `ConnectMenu.cpp`)

| Native Bruce feature | Bucket | How (command / tool / note) |
|---|---|---|
| SD Card / LittleFS browse | APP-TOOL | Files (tab 10) + `fs_read`/`fs_write`/`fs_write_append` |
| WebUI (device web server) | MIRROR-ONLY | no command; Remote/Console |
| Mass Storage (USB MSC) | MIRROR-ONLY | no command; Remote/Console |
| Connect > Send/Recv File (ESP-to-ESP) | MIRROR-ONLY | no command; Remote/Console |
| Connect > Send/Recv Cmds (ESP-to-ESP) | MIRROR-ONLY | no command; Remote/Console |

## Scripts (`src/core/menu_items/ScriptsMenu.cpp`)

| Native Bruce feature | Bucket | How (command / tool / note) |
|---|---|---|
| Run / Load .js script | APP-TOOL | Scripts (tab 19) + `script_run` |

## Config / System (`src/core/menu_items/ConfigMenu.cpp`)

| Native Bruce feature | Bucket | How (command / tool / note) |
|---|---|---|
| Display & UI > Brightness | COMPANION-CMD | `scr_bright` |
| Display & UI > UI Color / UI Theme | COMPANION-CMD | `theme_set` |
| Display & UI > Dim Time / Orientation | MIRROR-ONLY | no command; Remote/Console |
| LED Config (Color / Effect / Brightness) | COMPANION-CMD | `led_set`, `led_effect`, `led_bright` |
| Audio Config > Sound Volume | COMPANION-CMD | `speaker_volume` |
| System Config > Clock | APP-TOOL | Gadgets (tab 20) + `clock_set` |
| System Config > Startup App / Hide-Show Apps | MIRROR-ONLY | no command; Remote/Console |
| System Config > Advanced (pins, BLE API, BadUSB/BLE, BLE name, net creds, factory reset) | MIRROR-ONLY | no command; Remote/Console |
| Power (Deep Sleep / Sleep / Restart / Power Off) | MIRROR-ONLY | no command; Remote/Console |
| Dev Mode (serial USB/UART, disable dev) | MIRROR-ONLY | no command; Remote/Console |
| Pins Setup (I2C finder, SPI/I2C/UART/GPS pins) | MIRROR-ONLY | no command; Remote/Console |
| About (device info) | APP-TOOL | Device info (tab 6) |
| Save config | COMPANION-CMD | `config_save` |

## Sound / speaker (companion, surfaced via Tone + scripts)

| Native Bruce feature | Bucket | How (command / tool / note) |
|---|---|---|
| Tone / DTMF generate | APP-TOOL | Tone (tab 28) + `speaker_tone`, `speaker_dtmf` |
| RTTTL / song / play / PCM stream / stop | COMPANION-CMD | `speaker_rtttl`, `speaker_song`, `speaker_play`, `spkr_pcm_begin/data/stop`, `speaker_stop` |

---

## Coverage still to establish

Do not infer complete parity from this inventory. Dedicated app tools, companion
commands and mirrored standalone controls have different lifecycles and module
requirements. Verify each path in the control checklist and record missing or
unsupported behavior under S11; current evidence is in [TESTS.md](../TESTS.md).

## EXTERNAL-DEVICE (needs extra hardware, not an onboard sensor)

These are reachable from the app but require a separate companion device plugged into / paired
with the Bruce hardware:

- **SRIX Tool** — needs a PN532 reader (`srix_read`).
- **Amiibolink** — needs an Amiibolink device (`nfc_amiibo`).
- **Chameleon** — needs a ChameleonUltra.
- **PN532 BLE** — needs a PN532 (BLE link).
- **PN532 UART (PN532Killer)** — needs a PN532-Killer.

## Notes

- `ReverseShell` exists as a module (`src/modules/reverseShell/`) but its menu entry is
  commented out in the firmware (`WifiMenu.cpp:68`), so it is not a currently-surfaced native
  feature.
- The app adds phone-powered tools beyond native Bruce parity (not gaps): Crack, Probes,
  Monitor, MITM, Network Scan, Report, Macros, RF Map 3D, Bug Sweep, Utilities, Oscilloscope,
  SSTV, Tone, Updates, Console, Remote.
