# Getting Started

This guide takes you from a blank device to a connected Brucia setup.

> For authorized security research and education only. You are responsible for
> complying with all applicable laws and for only testing systems you own or are
> explicitly permitted to test.

## What you need

- A **LilyGo T-Embed CC1101 (ESP32-S3)** - the supported, hardware-verified board.
- A **data** USB-C cable (not a charge-only cable).
- An **Android phone** and a **USB-C OTG** cable to connect it to the device.
- Optional: a PN532 module for NFC, an NRF24 QWIIC add-on.

## 1. Flash the firmware

Pick one method.

### Browser flasher (easiest)

1. Open **https://1vcian.me/BruciaDev/** in Chrome or Edge (desktop, or Chrome on
   Android via USB-OTG).
2. Plug in the T-Embed CC1101 with a **data** USB-C cable.
3. Click **Connect & Flash**.

This uses Web Serial / ESP Web Tools and flashes the merged, ready-to-use image.
Once Brucia is installed, later firmware updates can also be flashed over USB from the
app's **Updates** screen.

### PlatformIO

```sh
python3 tools/build_firmware.py -e lilygo-t-embed-cc1101   # first build
pio run -e lilygo-t-embed-cc1101 -t upload
```

See [[Building]] for the full toolchain setup.

## 2. Install the app

- Download the APK from the project
  [Releases](https://github.com/1vcian/BruciaDev/releases) and install it (allow
  installation from unknown sources), **or**
- Build it yourself:

  ```sh
  cd android && ./gradlew :app:assembleDebug
  ```

  Requires JDK 17-25 (per Gradle/AGP; verified with 23, CI uses 21) and Android SDK 35.
  See [[Building]].

## 3. Connect

Brucia offers two ways to use the device from the phone.

### Companion mode (offload)

1. On the device, tap **Brucia** on the home screen. It shows a QR code to get the
   app, then "waiting for app".
2. Plug the device into the phone with a **USB-C OTG** cable.
3. Open the Brucia app and tap **Connect over USB**.
4. The status pill turns green and the device **dashboard** card shows board, chip,
   modules and live battery percentage.

In companion mode the device becomes a radio/sensor front-end and the phone does the
heavy work. This is where the native offload screens (Wardrive, Crack, and the rest)
operate. The app opens on a **dashboard** with a **bottom navigation bar**, and every
page is **capability-gated**: when the device reports its `caps{}` object, the app shows
only the modules the attached board actually has (so NFC, NRF24, mic, speaker and FM
pages appear only when that hardware is present).

### Remote control mode

If you want to drive the real device UI from the phone instead:

1. Leave the device on its **normal menu** (do not enter Brucia/companion mode).
2. Open the **Remote** tab in the app and tap **Start**.
3. A D-pad drives the real device UI and the device screen is mirrored live in the
   app.

This reaches every standalone Bruce feature (Evil Portal, Karma, Beacon spam,
Sniffer, Reverse Shell, Scripts, and so on) from the phone.

## 4. First steps

- From the **dashboard**, confirm the device card (info, free memory, uptime, battery).
- Try **Wardrive**: toggle the Wi-Fi AP stream and watch live access points appear,
  GPS-tagged and mapped on the phone; export to WiGLE CSV, KML or GPX.
- Open the **Console** command palette to run any Bruce CLI command and see its output.
- Explore the capability-gated pages: **Crack** (download a wordlist, then crack a
  captured PMKID/handshake), **Monitor** (full 802.11 capture to pcap for Wireshark),
  **MITM** (ARP redirect plus the on-phone HTTPS proxy), the **Spam hub** (Apple /
  Android / Windows / Samsung), Sub-GHz, NFC, IR, BLE and the Tools.
- Generate a **session Report** with OSINT and insights when you are done.
- Check the **Updates** screen to update the app (OTA) and flash the device firmware
  over USB from inside the app.
- Explore the rest in [[Features]].

The device stays a full Bruce when no phone is attached: companion mode is additive,
so nothing standalone is lost.
