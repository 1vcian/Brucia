---
name: brucia-dev
description: Work on the Brucia project — an AGPL fork of the Bruce ESP32 firmware plus a native Android companion app that offloads computation from the device to the phone. Use for any task touching the firmware (src/companion, transports, protocol), the Android app (transport/protocol/data/feature/ui), the companion wire protocol, the GitHub Pages web flasher, or rebrand/licensing compliance. Triggers: "add a companion module", "companion protocol", "offload", "wardriving", "web flasher", "rebrand Bruce to Brucia", "AGPL compliance".
---

# Brucia developer skill

Brucia = **superset of Bruce**. The ESP32 (LilyGo T-Embed CC1101, ESP32-S3) is the radio/sensor
front-end; a native Android app is the compute + GUI + storage back-end. Everything is AGPL-3.0,
free, no ads, no telemetry. **All code, comments, docs and default UI strings are in English.**

## Non-negotiable principles

1. **Parity first.** Every Bruce feature keeps working. With no phone connected the device is a
   full Bruce (standalone mode). Companion mode is strictly *additive* — never remove or break an
   existing Bruce code path. When you touch a Bruce module, verify its standalone path still works.
2. **Hardware boundary.** Anything needing a peripheral (CC1101, Wi-Fi/BLE radio, PN532, IR,
   timing-critical TX/RX) stays on the ESP. Compute, large memory, rich UI, GPS/maps go to the
   phone. Never try to delegate radio transmission to the phone.
3. **AGPL + attribution.** Keep `LICENSE`, `THIRD_PARTY.md` and all upstream
   credits intact. Keep `NOTICE` (origin + summary of changes) current. The Android app is AGPL
   too. Never strip a copyright header during rebrand.
4. **Transport-agnostic.** Features talk through the protocol, never directly to a wire. Order of
   transports: USB-CDC (primary) → Wi-Fi TCP/WS → BLE (commands/light data only — ESP32-S3 has
   BLE only, no BT Classic, so no heavy streaming over BLE).

## Repo layout

- *(repo root)* — fork of Bruce (keep upstream remote for merges). New code lives in
  `src/companion/`.
- `android/` — Kotlin + Jetpack Compose app. Layers: `transport / protocol / data / feature / ui`.
- `docs/` — static GitHub Pages web flasher (ESP Web Tools + manifest).
- `proto/` — the single source of truth for the companion wire protocol (see below).

## The companion protocol is the source of truth

The message schema is defined once in `proto/` and mirrored in both firmware (C++) and app
(Kotlin). **Never** add a message on one side only. Framing = length-prefixed; payload = CBOR
(JSON allowed for debug). Message kinds: `HELLO`/capabilities handshake, `CMD` (phone→ESP),
`EVENT`/`DATA` (ESP→phone incl. raw streams), `ACK`, `ERR`. The protocol is versioned — bump the
version when you change the schema and keep both sides compatible.

## Playbook: add a companion module (end to end)

1. **Define the messages** in `proto/` (request/response/stream), bump version if needed.
2. **Firmware**: add a handler under `src/companion/` that hooks the existing Bruce module
   (reuse its scan/capture/TX functions — do not reimplement) and emits `DATA`/`EVENT` or runs a
   `CMD`. Guard it so standalone behavior is unchanged.
3. **Android**: add the Kotlin message types, a repository/handler in `data/`, the feature logic in
   `feature/`, and UI in `ui/`.
4. **Verify**: standalone path still works (phone disconnected); companion path works over USB
   first; protocol round-trips (`HELLO` → capability → CMD/ACK).

## Build & flash

- Firmware (from repo root): `pio run -e lilygo-t-embed-cc1101`; flash over USB with
  `pio run -e lilygo-t-embed-cc1101 -t upload`.
- **Use the venv pio, not Homebrew's.** The pioarduino toolchain rejects Python 3.15 (what
  `brew install platformio` currently pulls). Build with `~/.pio-venv/bin/pio` (a venv on
  Python 3.11). Create it once: `python3 -m venv ~/.pio-venv && ~/.pio-venv/bin/pip install platformio`.
- **FastLED is pinned to `3.10.3`** in `platformio.ini`. The upstream range `^3.10.3` resolves to
  3.10.8, whose `fl/stl/json.h` does not compile with this toolchain. Do not loosen the pin.
- Web flasher binary: merge with `esptool merge_bin` (CI does this) and reference it from
  `docs/manifest.json`.
- Android: standard Gradle (`./gradlew assembleDebug`) once the app module exists.

## Rebrand rules (Bruce → Brucia)

- Rename user-facing product strings and identifiers to Brucia, but **keep** every upstream
  copyright/license header and `THIRD_PARTY.md` entry. When in doubt, add rather than remove.
- Track what changed in `NOTICE`.
