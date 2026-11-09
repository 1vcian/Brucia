# Brucia - usage guide

For authorized security research and education only. You are responsible for
complying with all applicable laws and for only testing systems you own or are
explicitly permitted to test.

## 1. Flash the firmware

Pick one:

- **Browser** (easiest): open https://1vcian.me/Brucia/ in desktop Chrome or Edge, plug the LilyGo T-Embed CC1101 with a **data**
  USB-C cable, click *Connect & Flash*.
- **Android app**: use Device → Updates with the board connected over USB-OTG.
- **From source**: `python3 tools/build_firmware.py -e lilygo-t-embed-cc1101`, then
  `pio run -e lilygo-t-embed-cc1101 -t upload` (setup in [CONTRIBUTING.md](../CONTRIBUTING.md#build--test)).

## 2. Install the app

Download the APK from the repo Releases and install it (allow unknown sources), or
build it: `cd android && ./gradlew :app:assembleDebug` (JDK 21-25, Android SDK platform 37; see
[CONTRIBUTING.md](../CONTRIBUTING.md#build--test)).

## 3. Connect

On the device: tap **Brucia** on the home screen (shows a QR to get the app, then
"waiting for app"). Plug the device into the phone with a **USB-C OTG** cable, open
Brucia, tap **Connect over USB**. The status pill turns green and the device card
shows board/chip/modules.

The device stays a full Bruce when no phone is attached (companion mode is additive).

## 4. Tabs

- **Wardrive** - toggle the `wifi.ap` stream. The phone tags each AP with GPS,
  dedups, estimates position (RSSI-weighted), draws a track + markers on an offline
  map, and computes stats (encryption/channels/vendors, best 2.4GHz channel,
  security findings). Export/share CSV (WiGLE), KML, GPX.
  - **Deauth**: tap an AP to expand it and *Deauth 8s* (the device sends deauth
    frames on that channel). Authorized testing only.
- **Crack** - a real WPA/WPA2 toolkit on the phone:
  - **Download real wordlists** in-app (rockyou, SecLists top-1M/100k/12k) straight
    to phone storage, then crack against them. Or import any `.txt`. A small offline
    quick list is built in.
  - Pull a captured `.pcap` from the device SD, or paste a PMKID (16800) / hashcat
    22000 line; crack the handshake/PMKID (PBKDF2/PTK/MIC) with mangling rules and a
    live keys/s counter. Export hashcat 22000.
- **IR** - a universal catalog (1600+ models, 800+ brands A-Z from Flipper-IRDB)
  browsed **category -> brand -> model** (TVs, ACs, LED lighting, soundbars, projectors,
  ...), or import any Flipper `.ir` file; *Learn* a remote with `ir rx` (saved on the
  phone); tap a button to transmit.
- **RF** (Sub-GHz) - pick a band (315/433.92/868.35/915 MHz), *Capture & save*
  (`rf rx`), *Replay* saved signals (`rf tx`), see the waveform, and decode OOK
  (Princeton/PT2262/EV1527) on the phone. *Decode sample* works with no hardware.
  **De Bruijn brute force**: pick a code length (8-16 bit) and pulse width, tap
  *Transmit de Bruijn* - the phone computes the shortest sequence covering every code
  and the device transmits it (fixed-code OOK gates/remotes). Authorized testing only.
- **NFC** - read/write/emulate/clone/info/save/erase/NDEF a tag (needs a PN532);
  phone-side security analysis (MIFARE/Crypto-1, clone risk, 65+ default-key dictionary).
- **BadUSB** - browse the DuckyScript library; copy a payload (run via BadBLE or
  standalone).
- **BLE** - scan nearby BLE devices; the phone lists, dedups and resolves vendors.
- **Device** - device info, **battery %** (also shown on every screen), free memory,
  uptime, I2C scan, sleep/reboot, and **Lite mode**: turn the device screen/LED off to
  save power while the phone drives it. Both auto-restore if the connection drops.
- **Console** - a **command palette** of every device command group (Wi-Fi, Sub-GHz,
  NFC/RFID, IR, BadUSB, Crypto, Storage, System): tap to run or to fill in `<args>`.
  Runs any Bruce CLI command and shows its output on the phone.
- **Remote** - **full remote control**: a D-pad drives the real device UI (`nav`) and the
  device screen is mirrored live in the app (`display start`), so you can reach *every*
  standalone Bruce feature from the phone (Evil Portal, Karma, Beacon spam, Sniffer,
  Reverse Shell, ...). Leave the device on its normal menu (not in Brucia/companion mode),
  open the Remote tab and tap *Start*. Works with the current firmware.

## 5. Live WPA attack (capture a real handshake, crack it on the phone)

This is the full "dictionary attack on a live connection" flow:

1. **Download rockyou** once: Crack tab -> *Download real wordlists* -> rockyou.
2. **Force a handshake**: in Wardrive, tap the target AP and *Deauth 8s* (needs the
   latest firmware). Deauthing makes clients reconnect so the 4-way handshake is sent.
3. **Capture it**: tap the AP in Wardrive -> *Capture handshake* (app-driven), or use
   Bruce's standalone capture on the device. Either writes a `.pcap` to `/BrucePCAP/handshakes/` on the SD card. The
   device does the capture; this always works standalone.
4. **Pull & crack**: back in the Crack tab, tap *List captured handshakes*, set the path to the saved `.pcap`, and tap *Pull* - the app streams it over USB, parses the handshake, and you
   *Crack* it against rockyou with real keys/s. Or paste a PMKID / hashcat 22000 line.

The heavy crypto (PBKDF2/PTK/MIC over millions of candidates) runs on the phone -
exactly what the ESP32 cannot do alone.

## 6. BLE and other attacks

Scanning and analysis run in the app (BLE tab). Active BLE attacks (advertising spam,
etc.) run from the device's own standalone menu today - Brucia keeps every standalone
Bruce feature, and app-driving them is on the [roadmap](../ROADMAP.md). Wi-Fi deauth is
already app-driven (above).

## 7. Principle

The ESP32 captures and transmits; the phone does the heavy computation, storage,
GUI, GPS and large datasets. See `docs/COMPARISON.md` for how this compares to
Flipper Zero and plain Bruce.

## Standalone WebUI identity

New configurations use `BruciaNet` as the access-point name; the inherited default
AP password remains `brucenet`. Existing saved Wi-Fi settings are retained. In
station mode the WebUI advertises `http://brucia.local` when mDNS is available;
the device also displays its IP address. About and CLI diagnostics identify the
firmware as Brucia and retain the Bruce/AGPL attribution.
