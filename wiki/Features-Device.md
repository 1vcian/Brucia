# Device and UX

Device management, in-app flashing, and the quality-of-life features that make the phone
feel like the device's own screen.

## Device page
Shows device info, **battery %** (also in the device card, refreshed each ping), free
memory, uptime, an I2C scan, and sleep/reboot controls.

## Lite mode
Turns the device screen (`scr`) and LED (`led`) off to save power; both auto-restore if
the phone goes silent for more than 15 seconds or on disconnect. A **lite power mode**
toggle and **screen brightness** control live alongside it.

## Console CLI
A command palette covering every device CLI group (Wi-Fi, Sub-GHz, NFC/RFID, IR, BadUSB,
Crypto, Storage, System); run any Bruce command and see its raw output.

**Export log.** The Console tab has an **Export log** button that shares the last ~2000 lines of
the app's rolling log file. Brucia writes everything to that file as well as to logcat - including
errors that would otherwise be silently swallowed (a WakeLock denied, a foreground-service upgrade
refused by the ROM, a transport write that failed). On the many different Android builds out there
(LineageOS, GrapheneOS, vendor skins) this is what makes a report like "it stops logging APs after
a few minutes with the screen off" diagnosable: attach the exported log to a GitHub issue and the
cause is in it, no adb/logcat needed. A crash also folds its stack trace into the same log.

## File manager
Pulls files from the device SD over USB (for example captured `.pcap` handshakes from
`/BrucePCAP/handshakes/`) via `fs_read`.

## Remote control / remote mirror
A D-pad sends `nav` input and the device screen is mirrored live (`display start`),
reaching every standalone Bruce feature (Evil Portal, Karma, Reverse Shell, Scripts, and
so on) without wrapping each one individually.

## Theme sync
The app matches the device's UI color (the device always wins), and can set the device
theme live; **Save to device** persists it.

## LED control
Color, brightness and 9 animations (solid, breathe, color cycle/wheel, chase, rainbow,
disco, fire) driven from the phone.

## In-app updates and flashing
Updates the Android app itself (app OTA) and flashes the device firmware over USB from
inside the app: uploads and runs the esptool stub, writes the merged image, verifies it
with an on-device MD5, and keeps a persistent **flash log**. The app distinguishes an
**app** vs **firmware** vs **both** update and highlights it, so firmware and app stay in
step.

## ESP-NOW
Broadcasts a short ESP-NOW message from the phone to nearby ESP32 devices
(`espnow_send`).

## Runtime capability detection
The device reports a real `caps{}` object on connect, and the app shows or hides each
page accordingly (Wi-Fi, BLE, Sub-GHz, NFC, IR, NRF24, LoRa, Ethernet, mic, speaker, FM).
Pages for hardware the board lacks are gated off automatically.

## BadUSB
An unlimited **DuckyScript library** on the phone; execute scripts over BadBLE or
standalone on the device. See [[Features-BLE-and-IR]].

---
Back to [[Features]].
