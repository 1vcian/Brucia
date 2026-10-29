# Brucia

**The phone is the brain. The ESP32 is the antenna.**

Brucia is an open-source platform for low-cost security research. It pairs cheap
ESP32 hardware, used as a radio/sensor front-end, with a native Android app that
provides the heavy computation, large storage and a full-size touch GUI.

Brucia is a fork/derivative of the [Bruce](https://github.com/BruceDevices/firmware)
ESP32 firmware. It keeps and credits that work, and is released under the same
license (AGPL-3.0). Huge thanks to the Bruce project and its authors.

> For authorized security research and education only. You are responsible for
> complying with all applicable laws and for only testing systems you own or are
> explicitly permitted to test.

## What Brucia is

Bruce already lets a phone *remote-control* the device, but all computation stays on
the ESP32: little RAM/CPU, a tiny screen, and an external GPS module is needed for
wardriving. Brucia flips this. The ESP32 captures and transmits; the phone decodes,
computes, stores and visualizes.

```
   ESP32-S3 (Brucia firmware)                Android (Brucia app)
   --------------------------                --------------------
   Radio / sensor FRONT-END      <=USB=>      Compute + storage + GUI
   - capture / transmit                       - decode / crypto / analysis
   - realtime timing                          - Room DB, big datasets
   - raw-data framing + stream                - GPS, offline maps, viz
         |                                           |
         +-------- Transport (abstract) -------------+
                   USB-CDC  |  Wi-Fi  |  BLE
                        +-- one Protocol --+
```

Cheap hardware (~$30-50) plus the power and UX of a phone you already own.

## What is in v0.6.x

Brucia has grown into a full multitool. The app opens on a **dashboard** with a live
device card and a **bottom navigation bar**, and every page is **capability-gated**: on
connect the device reports a real `caps{}` object and the app shows only the modules the
attached board actually has. Highlights:

- **Wi-Fi**: wardrive (+WiGLE/KML/GPX export, offline maps), PMKID/handshake capture
  cracked on the phone, deauth, station and probe sniffers, beacon spam, **monitor mode**
  (pcap/Wireshark, PSK decryption) and **ARP-MITM** with an on-phone HTTPS proxy.
- **Sub-GHz (CC1101)**: capture/CSV and .sub import/export, OOK and rolling-code decode,
  spectrum waterfall, replay, raw TX, De Bruijn brute force, jammer, config.
- **Audio (device mic/speaker)**: live spectrum, a waterfall spectrogram, an SSTV decoder
  and transmitter (Robot36 and more), a tone/DTMF detector and generator, and an
  oscilloscope - all DSP on the phone over the `mic.pcm` / `spkr_pcm` streams.
- **NFC/RFID**: read/clone/emulate/NDEF, dictionary attack, NDEF push, EMV read,
  magic-UID write. **IR**: catalog + learn + TV-B-Gone + raw replay.
- **BLE**: scan, GATT explorer, BadBLE, and a **Spam hub** (Apple / Android / Windows /
  Samsung). **NRF24**: scan / sniff / jammer / mousejack (optional add-on).
- **Tools**: on-device scripts, iButton, clock, speaker, mic + live mic spectrum,
  ESP-NOW. **FM audio** is implemented but gated (no Si4713 on this board).
- **Offload**: on-phone WPA crack, MITM proxy, and a generated **session report** with
  OSINT and insights.
- **In-app updates**: update the app (OTA) and flash the device firmware over USB from
  inside the app.
- Plus Console CLI, file manager, and a **remote mirror** (D-pad + live screen).

See [[Features]] for the full breakdown.

## The split architecture

- **ESP32-S3 (LilyGo T-Embed CC1101)** is the radio/sensor front-end: Wi-Fi, BLE,
  Sub-GHz (CC1101), IR, NFC (PN532). It captures and transmits in real time. Radio
  transmit is never delegated.
- **Android app (package `app.brucia`, Kotlin + Jetpack Compose)** is the brain:
  decode, crypto/analysis, Room database, large datasets, GPS, offline maps and a
  rich touch UI.
- A single **companion protocol** connects them over an abstract transport
  (USB-CDC today; Wi-Fi and BLE planned).
- A **Remote control** mode lets the app drive the real device UI (a D-pad sends
  navigation input and the device screen is mirrored live), so every standalone
  Bruce feature stays reachable from the phone.

Brucia is a strict **superset** of Bruce: with no phone connected the device is
still a full Bruce (standalone mode). Companion mode is purely additive.

## Verified on real hardware

Brucia runs end-to-end on a LilyGo T-Embed CC1101 (ESP32-S3) today: the runtime
capability handshake, live Wi-Fi wardrive, Wi-Fi monitor/sniffers, live BLE scan,
Sub-GHz capture, and CLI command routing, all streamed over USB-CDC to the phone. See
[[FAQ]] and the repository's hardware verification document for details.

## Where to go next

- [[Getting Started]] - flash the firmware, install the app, connect and take first steps.
- [[Features]] - every app tab and what the phone adds on top of the device.
- [[Architecture]] - the split model, protocol layering, transports and Remote control.
- [[Protocol]] - the companion wire protocol (message kinds, framing, streams, commands).
- [[Building]] - build the firmware and the app, release signing, host-side tools.
- [[Contributing]] - project rules and how to add a module cleanly.
- [[FAQ]] - hardware, legality, FM/LoRa, how Brucia differs from Bruce, licensing.

## License and support

Brucia is 100% free and open: firmware and app are both AGPL-3.0. No ads, no paid
tiers, no telemetry. It is a derivative of Bruce and complementary to its official
app. If it helps you, donations are optional and welcome.
