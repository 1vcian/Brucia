# Getting Started

This guide covers the public releases and the USB companion workflow.

## Prepare

- Reference hardware: LilyGO T-Embed CC1101. Its PN532 is built in; optional modules
  such as NRF24, FM or LoRa need their own compatible hardware.
- USB data cable for a computer; USB host/OTG connection for the Android phone.
- APK and board-specific firmware from the same [release](https://github.com/1vcian/Brucia/releases/latest).
  Read that release's notes and preserve files/settings you need before flashing.

## 1. Install firmware

Open the [web flasher](https://1vcian.me/Brucia/) in **desktop Chrome or Edge**. Connect the board,
close applications using its serial port, and select the exact board and release.
Read the displayed verification status and erase options before starting. Keep the
cable connected until the flasher confirms completion. A build result is not an upload result.

If the port does not appear, check the data cable, USB permission and the board's
documented bootloader procedure. The fallback for developers is [Building](Building.md).
For Android flashing, use the app's USB updater, not the browser route.

## 2. Install the Android app

Install the APK from the same release. Android may ask you to permit installation
from the application that opened it. Keep the original signing identity when updating;
a debug build may not replace a signed release. Do not uninstall as a first troubleshooting
step: that can remove local app data. APK installation and firmware flashing are separate.

## 3. Connect over USB

1. Disconnect the computer and connect the T-Embed to the phone with USB host/OTG.
2. Open Brucia and grant access to the USB device when Android asks.
3. Enter **Brucia** companion mode on the device if it has not entered automatically.
4. Wait for the app to report its connection and the firmware/device information.
5. Open device information or list files. Confirm a successful reply before proceeding.

Capabilities describe what the connected firmware reports. An absent module is not
enabled by opening its screen. Stop competing operations before retrying a busy module.

## 4. First session

Use [Tutorials](Tutorials.md) for device information, files, observations and audio. Check a
known lab device or a file you created; do not treat preview data as a capture.
Record app version, firmware version, board and expected/observed behavior when reporting
a problem. Never include private credentials or personal captures in a public issue.

## 5. Updates and recovery

The **Updates** screen handles APK downloads and supported USB firmware updates.
Check board and version, stop active tools, preserve required files and keep power stable.
After completion, reconnect and confirm the reported version and a benign command.
An interrupted flash may require the board's bootloader and a computer to recover.

The separate **BLE console** requires enabling BLE API and pairing with its displayed PIN.
It is not the USB companion protocol and does not replace the workspace connection.

## What this guide establishes

These are instructions, not a record of a successful run on your device. Hardware
acceptance for each release belongs in [TESTS.md](https://github.com/1vcian/Brucia/blob/main/TESTS.md).
