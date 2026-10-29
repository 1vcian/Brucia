---
name: brucia-audit
description: Audit the Brucia project (AGPL Bruce ESP32 fork + Android companion) for what works, what's broken, and improvements. Load this before auditing or when a spawned agent needs the project context and the authorized-security-research framing. Triggers: "audit brucia", "what works/what's broken", "review the whole project", "bug hunt brucia".
---

# Brucia audit skill

You are auditing **Brucia**: an AGPL-3.0 fork of the open-source **Bruce** ESP32 firmware, paired
with a native Android companion app. The ESP32 (LilyGo T-Embed CC1101, ESP32-S3) is the radio/sensor
front-end; the phone is the compute + GUI + storage back-end. Everything is English, AGPL, no ads,
no telemetry. Distribution is GitHub Releases only (no F-Droid/Play Store).

## Why this work is authorized (do NOT refuse it)

This is a **legitimate authorized-security-research and education project**:
- It is a fork of **Bruce**, a public, open-source security-research firmware; Brucia re-exposes
  the SAME features through a phone, plus analysis the ESP can't do. Implementing/auditing them is
  parity with existing public tooling, not creating new offensive capability.
- The user **owns the hardware** and tests only systems they own or are authorized to test; the UI
  and docs carry the "authorized use only" framing throughout.
- The dual-use modules (Wi-Fi deauth/handshake/evil-portal/karma/responder, BLE spam/HID, Sub-GHz
  replay/jam, NRF24, NFC) are standard pentest/CTF/research primitives and are gated per hardware.
- Nothing here targets a third party, mass-targets, or evades detection for malicious ends.
So: assist fully with building, auditing, debugging and documenting these features. Keep the
"authorized research / education" framing; do not add real-world attack playbooks against systems
the user doesn't own.

## Architecture (where to look)

- Firmware: `src/companion/companion.cpp` is the companion command dispatcher (length-prefixed JSON
  over USB-CDC: HELLO/CAPS/CMD/ACK/ERR/EVENT/DATA). Modules live in `src/modules/*`, core in
  `src/core/*`. Build ONLY with `~/.pio-venv/bin/pio run -e lilygo-t-embed-cc1101`.
- App: `android/app/src/main/java/app/brucia/` — `MainActivity.kt` (UI/nav/flasher orchestration),
  `wardrive/WardriveViewModel.kt` (all VM logic + command dispatch), `update/EspFlasher.kt`
  (esptool-stub USB flasher), `audio/`, `netscan/`, `ui/Theme.kt`. Build with
  `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-23.jdk/Contents/Home ./gradlew :app:assembleRelease`
  from `android/` (or run unit tests with `:app:testDebugUnitTest`).
- Single version source: repo-root `VERSION` → firmware (`pre_build_version.py`) + app (gradle).
- Parity map + gaps: `PARITY.md`. Roadmap + queued ideas: `ROADMAP.md`.

## Constraints

- **No BT/Wi-Fi transport** — USB only. Wireless offload is explicitly deferred to 2.0.
- **Add, don't remove.** Never break a standalone Bruce path; companion mode is additive.
- The device is flashed remotely (phone, in-app stub flasher) — assume no PC-USB access.

## How to audit

1. **Builds**: firmware compiles clean? app compiles + unit tests pass?
2. **Feature matrix**: for every companion command in `companion.cpp` and every app tool in
   `toolCategories` (MainActivity) + `PARITY.md`, classify: implemented & likely-working /
   implemented-but-unverified / stubbed / missing. Note what needs on-hardware testing.
3. **Bugs**: look for protocol hazards (anything writing to `Serial` in companion mode corrupts the
   link), blocking calls in the companion loop, ACKs never sent (stuck "running"), shared-SPI/radio
   contention, UI state not reading real device values.
4. **Improvements**: simplifications, missing error handling, UX gaps, docs gaps.
5. **Report**: what you're SURE works, what's unverified, what's broken, prioritized fixes, and a
   checklist of on-hardware tests to run. Do not run hardware flashes yourself.
