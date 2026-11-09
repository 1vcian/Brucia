# Features

The full Brucia feature set. The device captures and transmits; the phone
does the heavy computation, storage, GUI, GPS, crypto and large datasets. Module
controls use reported capabilities; the current app also requires explicit firmware
support for verified file transfers. Detection is distinct from an end-to-end
hardware test. See [[Architecture]] and the current repository audit.

> For authorized security research and education only. You are responsible for
> complying with all applicable laws and for only testing systems you own or are
> explicitly permitted to test. The dual-use modules below (deauth, spam, jammers,
> cracking, MITM, De Bruijn) are provided for authorized testing only.

## Killer features

Existing phone-assisted tools to verify and demonstrate; this is not an exclusive-novelty claim:

- **WPA/WPA2 cracking on the phone** (capture on the device, crack with hashcat-style rules and real
  wordlists on the phone's CPU).
- **Audio DSP suite** - spectrum, waterfall, SSTV, oscilloscope, DTMF. A Flipper has no audio input
  built in; external accessories are a separate comparison.
- **Wardrive with built-in GPS, offline maps and WiGLE/KML export** - no external GPS module.
- **On-phone MITM HTTPS proxy** and network scan with version/CVE hints.
- **Universal IR catalog** (~1600 models) on the phone, not a tiny SD subset.
- **In-app firmware flashing over USB** - no PC and no web flasher needed.
- **Full remote control + live screen mirror** - the app drives every device menu.

See [[Features-Tools]], [[Features-Audio]], [[Features-Wi-Fi]] and [[Features-Device]] for details,
and the [feature comparison](https://github.com/1vcian/Brucia/blob/main/docs/COMPARISON.md) for the
current scope, hardware limits and verification status.

Features are organized into the pages below. Each page has one section per feature, with
what it does, the hardware it needs, and a safety note for dual-use tools.

- [[Features-Wi-Fi]] - wardrive, deauth, sniffers, monitor mode, Evil Portal, Karma,
  Responder, ARP-MITM, Network Scan, Channel Analyzer, Brucegotchi and more.
- [[Features-Sub-GHz]] - CC1101 capture/decode/replay, spectrum waterfall, De Bruijn
  brute force, jammer and config.
- [[Features-NFC]] - PN532 read/write/emulate/clone, dictionary attack, NDEF, EMV read,
  magic-UID write, SRIX read, and the honest note on Chameleon/amiibo/nested/mfkey.
- [[Features-BLE-and-IR]] - BLE scan/GATT, BadBLE, BLE Remote, the spam hub, the IR
  catalog, learn/transmit, custom IR remote builder, TV-B-Gone and IR jammer.
- [[Features-Radio]] - NRF24 scan/sniff/jammer/MouseJack, LoRa, and FM (FM is hidden on
  the T-Embed CC1101).
- [[Features-Audio]] - spectrum, waterfall, SSTV, tone/DTMF, oscilloscope, song player
  and recording, driven by the device mic and speaker.
- [[Features-Ethernet]] - W5500 wired status/config, DHCP starvation and MAC flooding
  (not fitted on the T-Embed CC1101).
- [[Features-Tools]] - general device tools, the phone-side WPA/WPA2 crack toolkit, the
  on-phone MITM proxy, and session offload/reports.
- [[Features-Device]] - device page, lite mode, console CLI, file manager, remote
  control/mirror, theme sync, LED control, in-app flashing and ESP-NOW.

## Added since 0.6.x (phone-driven, caps-gated)

Every item below is explained so the wiki documents each feature. Dual-use tools are for
authorized testing only.

### Wi-Fi
- **Evil Portal / evil twin**: the device raises a fake AP and serves a captive page (the default
  one or an HTML template pushed from the app), optionally deauthing or cloning a nearby network;
  captured credentials stream to the app live.
- **Jam / deauth detector** (`jam_detect`): listens for a flood of deauth/disassoc frames and
  reports how many it saw - a defensive check for an active attack nearby.
- **Change MAC**: set a custom or random Wi-Fi station MAC from the app (saved, re-applied on boot).
- **Connect to Wi-Fi**: join the device to an AP (station mode) for device-side networking.
- **Network Scan** (on the phone): an nmap-lite that discovers hosts, open ports and service
  versions on the LAN and flags likely CVEs by version - all computed on the phone.
- **Channel Analyzer**: live 2.4GHz congestion per channel with best-channel advice.
- **Brucegotchi** (bounded Wi-Fi capture): a time-bounded background roam that hops channels and
  collects WPA handshakes to `/BrucePCAP/handshakes` for later cracking.
- **NetCut / ARP-MITM**: transparent ARP redirect of a target through the device (authorized),
  pairing with the on-phone HTTPS intercept proxy.

### Sub-GHz
- **Jammer** (`subghz_jammer`) and **brute force** (`subghz_bruteforce`): bounded, authorized-use.

### BLE
- **iBeacon advertiser** (`ble_ibeacon`) and **media-control hijack** (`ble_media`).

### IR
- **IR jammer** (`ir_jammer`): floods IR to disrupt nearby receivers (authorized).

### Audio
- **Native DTMF** generator (dual-tone through the device speaker) and a **DTMF decoder** that
  reads touch-tones from the live mic on the phone.
- **Song player**: meme RTTTL melodies played natively on the device, now in the background so
  **Stop** actually interrupts them; **speaker volume** control.
- Per-tool **recording** (WAV + spectrum/waterfall/oscilloscope PNGs).

### Device, theme and LED
- **Theme sync**: the app matches the device's UI color (the device always wins), and can set the
  device theme live; **Save to device** persists it.
- **LED control**: color, brightness and 9 animations (solid, breathe, color cycle/wheel, chase,
  rainbow, disco, fire) driven from the phone; **screen brightness** and a lite power mode.
- **ESP-NOW**: broadcast a short ESP-NOW message from the phone.

### Flashing and updates
- **In-app firmware flash over USB**: uploads and runs the esptool stub, writes the merged image,
  verifies it with an on-device MD5, and keeps a persistent **flash log**; the app distinguishes an
  **app** vs **firmware** vs **both** update and highlights it.

### Added since 0.8.x (now shipped)
- **Karma attack** (evil-twin probe-response lure), **Responder** (LLMNR/NBT-NS poisoning, alongside
  the native interactive SMB/NTLM Responder), **BLE Remote (HID)** keyboard/presenter, and the
  **custom IR remote builder** (a grid of taught buttons you place yourself).
- **SRIX / ST25TB read** (`srix_read`) on the onboard PN532.
- **Ethernet attacks** (`eth_dhcp_starve`, `eth_mac_flood`): bounded DHCP-starvation and MAC-flooding
  over a W5500, gated on `caps.eth` - in the Ethernet tool's Attacks card.
- **Melody composer**: build a custom tune note-by-note in the Tone tool, play it on the device
  speaker (via the existing RTTTL player) and save it - no new firmware needed.
- **Macros** (Tools): chain device commands into saved, one-tap automations the phone sequences.
- **3D RF-source map** (Tools): Wi-Fi/BLE/sub-GHz emitters plotted in 3D by RSSI; rotate, zoom, save.
- **Bug Sweep** (Tools): home in on a hidden transmitter by signal strength across Wi-Fi/BLE/sub-GHz.
- **WIDS** (Intercept): continuous deauth / evil-twin detection with a timestamped alert log.
- **PCAP dissect** (Tools): open an 802.11 capture and dissect it on the phone (Wireshark-lite).
- **RF Fingerprint** (Intercept): record a place's Wi-Fi/BLE signature, later detect NEW devices.
- **RF Timeline** (Intercept): persistent cross-session history of Wi-Fi/BLE devices seen over time.
- **Evil Portal HTML editor**: author/preview the captive page in-app (WebView) and save templates.
- **Tool search** on the home screen, and a **file-based crash logger** (`last_crash.txt`) so startup
  crashes can be reported without adb.

### Planned (roadmap)
- NFC Chameleon / amiibo / mfkey and LF write/clone stay external-device features (no on-board
  primitive) and are reported as such in the app. See [[Features]] pages for the shipped set.

## Hardware scope

The T-Embed CC1101 physically has Wi-Fi, BLE, Sub-GHz (CC1101) and IR on board, NFC
via an external PN532, and an optional NRF24 QWIIC add-on. **FM** (Si4713) and **LoRa**
are not fitted on this board; the FM code is present but capability-gated off. See
[[FAQ]].
