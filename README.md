# Brucia ð¥

**Brucia** is an open-source platform for low-cost security research. It is a
derivative of the [Bruce](https://github.com/BruceDevices/firmware) ESP32
firmware, extended so the device becomes a **radio/sensor front-end** driven by a
**native Android companion app** that provides the heavy computation, storage and
a full-size GUI.

Cheap hardware (ESP32, e.g. the LilyGo T-Embed CC1101) + the power and UX of a
phone. The goal is to be *the* reference open-source stack for affordable
security research.

> â ï¸ For **authorized security research and education only.** You are responsible
> for complying with all applicable laws and for only testing systems you own or
> are explicitly permitted to test.

## Why Brucia?

Bruce already lets a phone *remote-control* the device over Wi-Fi, but **all the
computation stays on the ESP32** â which has little RAM/CPU, a tiny screen, and
needs an external GPS module for wardriving. Brucia flips this: the ESP captures
and transmits; the **phone computes, stores, visualizes, and provides GPS/maps**.

## Brucia vs. the official Bruce app

[BruceDevices/App](https://github.com/BruceDevices/App) is the official companion: it
**controls** the device — firmware flashing, a serial terminal, and live screen
mirroring. Brucia is a different thing: it **offloads computation** to the phone. The
ESP captures and streams; the phone brings GPS, storage, maps, large datasets and
analysis the device cannot hold. Brucia is **complementary** to Bruce and its official
app, not a replacement — and it credits the original project.

## Principles

- **Superset of Bruce.** Every Bruce feature keeps working. With no phone
  connected, the device is still a full Bruce (standalone mode). Companion mode is
  strictly additive.
- **Hardware boundary.** Radio/peripheral work stays on the ESP; compute, memory,
  UI and geolocation move to the phone.
- **100% free & open.** Firmware and app are both AGPL-3.0. No ads, no paid
  tiers, no telemetry.

## Repository layout

| Path        | What |
|-------------|------|
| *(root)* | Fork of Bruce firmware (AGPL): `src/`, `platformio.ini`, boards, partitions. Companion code in `src/companion/`. |
| `android/`  | Native Android app (Kotlin + Jetpack Compose). |
| `proto/`    | The companion wire protocol â single source of truth for both sides. |
| `docs/`     | Browser-based firmware flasher (GitHub Pages). |
| `MAPPING.md`| Where Brucia hooks into the existing Bruce code. |

## Status

Early development. Roadmap (see the project plan):

1. **F0** â Fork, rebrand, agent skill, baseline build â *in progress*
2. **F1** â Transport + protocol over USB-CDC
3. **F2** â Wardriving proof-of-concept (ESP scan â phone GPS/map/export)
4. **F3** â Real offload (dedup/correlation/heatmaps on the phone)
5. **F4** â Web flasher + Wi-Fi/BLE transports
6. **F5** â Full module parity + phone-enabled extensions (IR catalog, wordlists, large DBs)
7. **F6** â Branding, F-Droid release

## License

[AGPL-3.0](LICENSE). Derivative of Bruce; see [`NOTICE`](NOTICE) and
`THIRD_PARTY.md`.

## Support

Brucia is free. If it helps you, you can optionally
[buy me a coffee â](https://buymeacoffee.com/1vcian).
