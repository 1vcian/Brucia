# FAQ

Practical questions and answers about Brucia.

## Which hardware do I need?

A **LilyGo T-Embed CC1101 (ESP32-S3)** - the supported, hardware-verified board.
Brucia runs end-to-end on it today. You also need an Android phone, a **data** USB-C
cable for flashing, and a **USB-C OTG** cable to connect the phone to the device.
Optional: a PN532 for NFC, and an NRF24 QWIIC add-on. Total device cost is roughly
$30-50, plus a phone you already own.

## Is it legal? Who can use it?

Brucia is **for authorized security research and education only**. You are responsible
for complying with all applicable laws and for only testing systems you own or are
explicitly permitted to test. Dual-use modules (deauth, BLE spam, De Bruijn brute
force, WPA cracking) are provided for authorized testing. Anything whose only purpose
is to attack systems you do not own is out of scope.

## Does FM radio or LoRa work?

LoRa is **not present on the T-Embed CC1101**. FM broadcast (Si4713) is fully
implemented in the firmware and app, but there is no Si4713 fitted on this board, so
the device reports `fm:false` and the app keeps the FM page hidden (capability-gated);
it lights up on a board that has the chip. **NRF24** is supported only as an optional
QWIIC add-on. The board's actual radios are Wi-Fi, BLE, Sub-GHz (CC1101) and IR, plus
NFC via an external PN532.

## How is Brucia different from Bruce?

Bruce turns cheap ESP32 hardware into a Flipper-like multitool, but **all computation
stays on the ESP32**: little RAM/CPU, a tiny screen, and an external GPS module is
needed for wardriving. Brucia keeps the ESP32 as a radio/sensor front-end and **moves
the heavy work to the phone** - fast CPU, gigabytes of RAM/storage, GPS, maps,
internet, big datasets and a full-size touch GUI. This unlocks things a standalone
device in this price class cannot do: rockyou-scale WPA cracking, GPS wardriving with
offline maps, large IR/OUI catalogs and rich visualization.

Brucia is a strict **superset** of Bruce. Every Bruce feature keeps working, and with
no phone connected the device is still a full Bruce (standalone mode). Companion mode
is purely additive.

## How is it different from the official Bruce app?

The [official Bruce app](https://github.com/BruceDevices/App) *controls* the device;
Brucia *offloads computation* to the phone. They are complementary, not competitors.
Brucia credits the original project and aims to upstream its firmware hooks to Bruce
as an optional module. Brucia also offers a **Remote control** mode that drives the
real device UI (D-pad navigation plus a live screen mirror), so every standalone
Bruce feature stays reachable from the phone.

## Why Android only? What about iOS?

iOS is explicitly out of scope: there is no reliable USB serial path, and there is
app-store friction with AGPL licensing. Brucia is Android only.

## Is it free?

Yes. Both the firmware and the app are **AGPL-3.0**. No ads, no paid tiers, no
telemetry. If Brucia helps you, donations are optional and welcome, but nothing is
paywalled.

## How do the phone and device talk?

Over a single **companion protocol** (length-prefixed JSON frames) across an abstract
transport. **USB-CDC** is implemented today; Wi-Fi (TCP/WebSocket) and BLE are planned
behind the same interface. See [[Protocol]] and [[Architecture]].

## Has it actually been tested on real hardware?

Yes. Capability handshake, live Wi-Fi wardrive, live BLE scan and CLI command routing
with output capture all work over USB-CDC against a real LilyGo T-Embed CC1101.
Remote control (nav plus TFT mirror), the flame theme, live battery reporting and lite
mode are also verified. See the repository's hardware verification document for the
captured logs.

## Where do I start?

Read [[Getting Started]], then explore [[Features]]. To contribute, see
[[Contributing]] and [[Building]].
