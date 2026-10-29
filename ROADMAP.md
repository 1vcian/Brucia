# Brucia roadmap

Brucia pairs cheap ESP32 hardware as a radio/sensor front-end with a phone as the
compute, storage and GUI. This file tracks what works, how to test it on real
hardware, and where help is wanted.

Research-platform proposals are tracked separately in
[Research platform ideas](docs/RESEARCH_IDEAS.md): portable experiment bundles,
hardware diagnostics, reproducible recipes, synchronized evidence and offline replay.
These are proposals; completing the functional audit remains the immediate priority.

**Current scope:** stabilize and verify existing features before adding new ones.
The ideas backlog also records protocol expansion, optional modules, pentest tools,
signal generators and creative/community extensions. Check existing implementations
first; several proposed audio tools already exist and need validation, not duplication.

Contributions are welcome - see [CONTRIBUTING.md](CONTRIBUTING.md) and
[ARCHITECTURE.md](ARCHITECTURE.md). Items tagged **[help wanted]** are good first
tasks; **[priority]** is what matters most right now.

---

## Status (v0.8.14)

### Done
- **Split architecture**: the ESP captures/transmits; the phone computes, stores and
  visualizes. Companion protocol v0 over USB-CDC (length-prefixed JSON: HELLO / CAPS /
  CMD / ACK / ERR / EVENT / DATA / SUB / PING / PONG). See [proto/PROTOCOL.md](proto/PROTOCOL.md).
- **Runtime capability detection**: the device reports a real `caps{}` object on connect
  (`cc1101, nfc, nrf24, fm, ir, mic, speaker, lora, eth, wifi, ble`) via compile-time
  board macros + runtime I2C/SPI probes; every app page is gated accordingly.
- **Full tool set** (gated per hardware):
  - Wi-Fi: wardrive (GPS, offline map, WiGLE/KML/GPX export, RSSI position estimate),
    on-phone WPA/WPA2 + PMKID crack (rockyou/SecLists), deauth, station/probe sniffers,
    beacon spam, monitor mode (pcap/Wireshark, PSK decrypt), ARP-MITM + on-phone HTTPS proxy.
  - Sub-GHz (CC1101): capture/CSV, .sub import/export, OOK + rolling-code decode, spectrum
    waterfall, replay, raw TX, De Bruijn, jammer, config.
  - NFC/RFID (PN532), IR (1648-code catalog, TV-B-Gone, raw), BLE (scan, GATT explorer,
    spam hub, BadBLE), NRF24 (scan/sniff/jammer/MouseJack, optional add-on).
  - **Audio (device mic/speaker)**: spectrum, waterfall, SSTV RX/TX (Robot36/72, Martin,
    Scottie), tone/DTMF detector + generator, oscilloscope - all DSP on the phone over the
    `mic.pcm` / `spkr_pcm` streams.
  - Tools: on-device BruceJS scripting, iButton, clock, ESP-NOW, FM (gated), session report
    + OSINT, remote screen mirror + nav, console, file manager, offline Utilities.
- **Background lifecycle**: foreground service (`connectedDevice`) + wake lock so the link
  survives screen-off.
- **Distribution**: signed release APKs; multi-board web flasher (61 boards) + themed on-site
  wiki at the GitHub Pages site.

### Added since 0.6.x (phone-driven, caps-gated)
- **Evil Portal / evil twin**: device-side fake AP + captive page (default or a pushed HTML
  template), optional deauth + clone-a-nearby-network targeting; captured credentials stream to
  the app live (companion `evil_portal_start/stop/status`, EVENT `evil.cred`).
- **Jammers**: Sub-GHz (`subghz_jammer`), IR (`ir_jammer`), 2.4GHz (NRF24); **Sub-GHz brute force**
  (`subghz_bruteforce`); **Wi-Fi jam/deauth detector** (`jam_detect`).
- **BLE**: iBeacon advertiser (`ble_ibeacon`), media-control hijack (`ble_media`), plus the existing
  scan / spam / BadBLE / GATT explorer.
- **Beacon spam**: broadcasts many same-name networks via zero-width-char suffixes (up to 256).
- **ARP spoof / NetCut** MITM (`arp_spoof`) with an in-app target form.
- **Phone-powered "beyond" tools**: on-device **Network Scanner** (nmap-lite: host/port/service
  discovery + CVE heuristics, all on the phone), live **Channel Analyzer**, audio **Record**
  (WAV + spectrum/waterfall/scope PNGs) on every audio tool, a **Tone song player** (meme RTTTL
  melodies via `speaker_rtttl`), speaker **volume** control.
- **Theme sync**: the app matches the device's UI color (the device wins) and can set the device
  theme + LED (`theme_set`/`led_set`, CAPS reports `theme`/`led`).
- **UX**: home is an icon grid of categories (Material icons) with a Support tile; module-gated
  tools are shown greyed with a "Needs <module>" hint instead of hidden; USB auto-connect; a
  compact footer (Home / Cmds / Help with wiki + donations).
- **Flashing**: reliable in-app flashing of the ESP32-S3 (1200-baud touch to enter the ROM
  bootloader + USB re-enumeration + a READ_REG bootloader check, so it writes for real instead of
  faking success) and an in-app **board selector** to flash any published per-board image.

### Verification status (honest)
- **LilyGo T-Embed CC1101 (ESP32-S3)**: the reference board - verified on real hardware
  (flash, boot, companion HELLO/CAPS + PING/PONG, correct caps, live wardrive + BLE scan).
  See [docs/HARDWARE_VERIFICATION.md](docs/HARDWARE_VERIFICATION.md).
- **Audio path**: verified live on the T-Embed over USB from an Android phone - the `mic.pcm`
  stream drives the Spectrum (live peak-frequency + level), Waterfall and Oscilloscope in
  parallel on a real acoustic signal. SSTV TX and speaker playback on a real transmission are
  still to be exercised end to end, but the capture->DSP pipeline is confirmed on hardware.
- **All other boards (~70 build targets)**: build-verified by CI only - **not** runtime
  tested. Treat them as experimental until someone verifies them (see below).

---

## Testing the devices  **[help wanted]**

The single biggest way to help: **verify Brucia on a board you own** and report the result.
CI proves a board *builds*; only a person with the hardware can prove it *runs*.

### Verification levels
- **L0 - builds**: produced by CI (`Build and Push Releases`). Already true for all boards.
- **L1 - boots + companion**: the board boots Brucia, enters companion mode, and the app
  gets a correct `caps{}` (matches the board's real modules) with working PING/PONG.
- **L2 - modules live**: each capability the board reports actually works end to end.

### Per-board checklist (to claim a board "verified")
1. **Flash**: use the [web flasher](https://1vcian.me/BruciaDev/) (pick your board in the
   dropdown) or `~/.pio-venv/bin/pio run -e <env> -t upload`. Confirm the write verifies and
   the device reboots into Brucia. (You cannot brick an ESP32 by flashing - BOOT+RESET
   re-enters download mode if anything goes wrong.)
2. **Boot (L1)**: the device shows the Brucia UI; the top-level **Brucia App** entry enters
   companion mode.
3. **Connect (L1)**: open the Android app, **Connect over USB**. Confirm HELLO/CAPS returns,
   the device card shows the right board/chip, and `caps{}` matches the hardware (e.g. no
   CC1101 -> `cc1101:false`). Confirm PING keeps the battery fresh.
4. **Modules (L2)**: for every `true` cap, run its tool once:
   - wifi -> Wardrive sees APs; Sniffer/Monitor stream.
   - cc1101 -> Sub-GHz RX shows a capture; TX a known-safe signal.
   - nfc -> read a tag you own. ir -> send a code. ble -> scan.
   - mic -> Spectrum/Oscilloscope react to sound. speaker -> Tone plays.
   - nrf24 (if the module is attached) -> scan. lora/eth (if wired) -> status.
   - Remote mirror renders the screen and `nav` moves the UI.
5. **Report**: open an issue titled `verify: <board>` with the board name, the `caps{}` JSON
   (visible in the app's Device info / Console `info`), which modules passed/failed, and the
   firmware/app version. We will mark the board verified in the matrix below.

### Board status matrix
| Board | Chip | Flash | L1 boot+companion | L2 modules |
|---|---|---|---|---|
| LilyGo T-Embed CC1101 | ESP32-S3 | yes | verified | wardrive + BLE + CC1101 + audio live |
| all other ~70 targets | mixed | builds in CI | not tested | not tested |

(Help us fill this in - each verified board becomes a row.)

### Known hardware notes
- Shared SPI bus on the T-Embed (CC1101 / SD / NRF24 / W5500 / TFT) - one device at a time;
  mic and speaker are half-duplex.
- NRF24 on the T-Embed is an external nRF24L01+ on QWIIC Port #2 (CE=43, CSN=44, shared
  SPI); `caps.nrf24` auto-detects it.
- The in-app firmware updater only flashes the T-Embed CC1101; other boards must use the web
  flasher with their matching per-board image.

---

## Roadmap

### Priority (finish what exists)
- **Remaining Bruce parity (firmware companion commands + app UI)** - still missing: Karma attack,
  Pwnagotchi/Brucegotchi, BLE Presenter/Keyboard (HID), a dedicated NetCut, Responder (headless),
  Scan Hosts / WebUI launcher UIs, and the add-on-module features (NFC nested/mfkey32/chameleon/
  SRIX/125kHz, NRF24 spectrum, LoRa chat, Ethernet attacks) - all caps-gated for users who fit the
  module. **[priority]**
- **Exercise audio TX end to end** on hardware (SSTV transmit + speaker playback on a real
  signal); mic capture + the Spectrum/Waterfall/Oscilloscope RX path are already verified live.
- **Verify more boards** to L1/L2 (see above). **[priority] [help wanted]**
- **SSTV Scottie broadcast sync**: Scottie currently uses a self-consistent sync that
  round-trips in-app but will not decode an external broadcast-Scottie signal; implement the
  real mid-line sync. **[help wanted]**
- **Background lifecycle, pillar 3**: move the transport + long-running jobs out of the
  Activity-scoped ViewModel so the link survives a full app swipe-away (not just screen-off).

### App-driven attacks (harden + validate)
- More BLE spam variants, PMKID flows, and live validation of deauth/handshake/spam on
  targets you own. **[help wanted]**

### Distribution & i18n
- The app ships **only via GitHub Releases** (signed APK) + in-app OTA self-update. No F-Droid, no
  Play Store.
- **Internationalization** - source strings are English; add translations (Italian first).
  **[help wanted]**

### Data & firmware
- Offline map-tile bundling for wardriving without a connection.
- Larger datasets: expanded IR/Sub-GHz catalogs, per-vendor NFC key sets.
- Upstream the companion hooks to [Bruce](https://github.com/BruceDevices/firmware) as an
  optional module, so Brucia stays a thin layer.
- More capture streams (raw 802.11 frames, extra Sub-GHz modes); more mic decoders (CW/RTTY/
  POCSAG) reusing the `mic.pcm` pipeline. **[help wanted]**

### IR - queued ideas
- **Custom remote builder**: let the user build their own remote as a grid of user-placed buttons,
  each taught by capturing one IR signal at a time (IR Read), named and positioned freely; tapping a
  button replays its captured code. Saved layouts persist on the phone and can be shared/exported.

### Device-side companion - queued ideas
- **Background companion + minimal status screen**: run the USB companion link as a background task
  so the device shows a tiny status instead of a dedicated page - "Connected" when a Brucia app is
  attached over USB, else "Download the app to connect" with a QR to the app. Must guard shared
  hardware: the CC1101/SD/NRF24/TFT SPI bus and the single Wi-Fi/BLE radio can't be driven by the
  phone and a device-side tool at once, so lock out the conflicting side while the other holds a
  radio. (USB only - no BT/Wi-Fi transport, that's 2.0.)

### Audio - queued ideas
- **Melody composer** (shipped): the Tone tool now has a note-by-note composer (pick length/octave,
  tap notes/rests, set tempo) that builds an RTTTL string and plays it on the device speaker via the
  existing background player, with save/replay of custom songs. A future step could add
  recording-to-song (derive notes from a captured waveform) and a fuller piano-roll editor.

---

## Out of scope
- **iOS** - no reliable USB-serial path + store friction with AGPL. Android only.
- **Anything that only works to attack systems you do not own.** Brucia is for authorized
  research and education; the dual-use modules keep that framing.

## Phone-powered research ideas (queued) - impossible on standalone Bruce/Flipper

The phone brings compute, storage, internet, GPS and an NPU the ESP32 does not have. The hard rule:
every idea here must CENTER on something only the ESP's radios/sensors can do - sub-GHz (CC1101),
Wi-Fi monitor/injection, IR TX/RX, PN532 NFC, the device mic/speaker - with the phone as the
compute/storage/GPS back-end. A pure-phone feature (e.g. camera OCR) does not belong: if it does not
need the device, it is not a Brucia feature. USB only (no BT/Wi-Fi transport, that is 2.0).
Authorized research only.

- **On-phone signal ML**: modulation recognition and protocol identification from raw sub-GHz
  timings, and device fingerprinting from BLE/Wi-Fi behavior - inference the ESP cannot run.
- **Temporal intelligence DB** (SHIPPED - RF Timeline): log BLE/Wi-Fi/sub-GHz sightings with timestamp + GPS over days;
  study MAC-randomization patterns and correlate rotating identifiers across time. Needs the
  phone's storage and compute; impossible on a Flipper.
- **RF heatmaps on the map**: beyond Wi-Fi wardrive - sub-GHz source localization, BLE density and
  interference/jammer maps, all GPS-tagged on the offline map.
- **Automated recon report** (PARTLY SHIPPED - honest heuristic insights incl. BLE MAC trackability; no misleading per-vendor CVE): OUI -> vendor, optional
  online enrichment, exported as PDF/HTML. The phone has internet; the device does not.
- **On-phone PCAP dissection** (SHIPPED): a Wireshark-lite that decodes captured 802.11/pcap on the phone -
  filter, follow streams, extract handshakes - not just capture.
- **Protocol fuzzing orchestration**: the phone generates payload sets (sub-GHz / IR / BLE), the
  device transmits, the phone logs responses - iterative fuzzing with the phone's compute.
- **Rolling-code / counter analysis**: capture many sub-GHz samples; the phone diffs and visualizes
  the rolling counter / keystream.
- **MIFARE crypto offload**: when nonces can be captured (right reader), the phone runs the
  nested/darkside key recovery - the compute part the ESP cannot do.
- **Phone-side automation** (SHIPPED - Macros): a macro/script engine on the phone chaining device commands
  (scan -> capture -> analyze -> report), far beyond the device's tiny on-board interpreter.
- **3D RF-source map** (SHIPPED): plot Wi-Fi APs / BLE beacons / sub-GHz emitters as points in a 3D scene
  around you, positioned by RSSI multilateration as you move (phone IMU + GPS), with signal-strength
  clouds. Realistic as an approximate visualization (RSSI->distance is noisy, +/- several metres);
  it maps RF *sources*, not room geometry. True RF imaging of walls/objects needs UWB/radar/phased
  arrays - out of scope for a single ESP32+CC1101. A coarse motion/presence layer via ESP32 Wi-Fi
  CSI is a separate research step (firmware CSI capture).

## Original / "never seen" ideas (queued) - our sensors, both attack and defense

Most ESP tools are offensive-only. Brucia can be a serious *defensive* tool too - and the key is
that the ESP's radios do sensing a phone physically cannot (sub-GHz on the CC1101, Wi-Fi
promiscuous/monitor mode, IR), while the phone adds GPS, storage and correlation. Authorized use only.

Defensive (blue-team) - novel:
- **"Are you being followed?" counter-surveillance**: the ESP watches in Wi-Fi monitor mode (every
  nearby device, not just connectable ones - a phone can't) plus BLE and sub-GHz, and flags any
  emitter whose identifier keeps reappearing as YOU move (phone GPS); cross-vendor tracker and
  tailing-device detection, including sub-GHz tags a phone cannot even hear.
- **Hidden-device / bug & spy-cam RF sweep** (SHIPPED - Bug Sweep): a "hotter/colder" sweep that localizes hidden
  transmitters (Wi-Fi/BLE/sub-GHz) by RSSI as you walk the device around a room.
- **Portable WIDS** (SHIPPED): passively watch for deauth floods, evil-twin/Karma APs cloning your SSID, and
  rogue DHCP, and auto-save timestamped + GPS-tagged evidence (pcap) to the phone.
- **RF fingerprint of a place** (SHIPPED - RF Fingerprint): record a location's RF signature (which APs/beacons/emitters) and
  later detect if the environment changed - new devices appearing can mean new surveillance.

Offensive / research - novel fusion only the phone enables:
- **Cross-radio identity fusion** (NOT building - deliberate): the honest version (grouping by OUI
  vendor across radios) is already in the session report's vendor/platform tally; the useful version
  (linking a device's Wi-Fi<->BLE MAC) is unreliable and misleading because of MAC randomization, so
  presenting it as 'identity' would be dishonest.
- **Geofenced capture/replay** (SHIPPED - Geo signals, manual replay): pin a saved sub-GHz/IR signal
  to a GPS spot and replay on return; the device transmits, the phone supplies GPS + proximity.
  (Auto-replay on a background geofence is a possible follow-up; current version is manual/safe.)
- **Ultrasonic beacon detect/emit**: use the DEVICE mic/speaker (not the phone's) to detect retail/ad
  ultrasonic tracking beacons and to emit ultrasonic signaling - the device is the always-on sensor,
  the phone does the DSP.
- **Unified RF presence timeline**: one timeline fusing Wi-Fi/BLE/sub-GHz/NFC sightings over time
  with GPS - a personal "spectrum-of-things" recorder (the temporal DB, elevated).

## How to help
Pick an item (especially **[help wanted]** / **[priority]**), open an issue to claim it, and
read [CONTRIBUTING.md](CONTRIBUTING.md) + [ARCHITECTURE.md](ARCHITECTURE.md). Keeping the
[protocol](proto/PROTOCOL.md) the single source of truth for both firmware and app is the one
hard rule. Verifying a board, or filing a clean bug with the `caps{}` JSON, is always welcome.
