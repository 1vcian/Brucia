# Brucia 🔥

**Brucia** is an open-source platform for low-cost security research. It is a
derivative of the [Bruce](https://github.com/BruceDevices/firmware) ESP32
firmware, extended so the device becomes a **radio/sensor front-end** driven by a
**native Android companion app** that provides the heavy computation, storage and
a full-size GUI.

Cheap hardware (ESP32, e.g. the LilyGo T-Embed CC1101) + the power and UX of a
phone. The goal is to be *the* reference open-source stack for affordable
security research.

> ⚠️ For **authorized security research and education only.** You are responsible
> for complying with all applicable laws and for only testing systems you own or
> are explicitly permitted to test.

## Why Brucia?

Bruce already lets a phone *remote-control* the device over Wi-Fi, but **all the
computation stays on the ESP32** — which has little RAM/CPU, a tiny screen, and
needs an external GPS module for wardriving. Brucia flips this: the ESP captures
and transmits; the **phone computes, stores, visualizes, and provides GPS/maps**.

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
| `proto/`    | The companion wire protocol — single source of truth for both sides. |
| `docs/`     | Browser-based firmware flasher (GitHub Pages). |
| `MAPPING.md`| Where Brucia hooks into the existing Bruce code. |

## Status

Early development. Roadmap (see the project plan):

1. **F0** — Fork, rebrand, agent skill, baseline build ← *in progress*
2. **F1** — Transport + protocol over USB-CDC
3. **F2** — Wardriving proof-of-concept (ESP scan → phone GPS/map/export)
4. **F3** — Real offload (dedup/correlation/heatmaps on the phone)
5. **F4** — Web flasher + Wi-Fi/BLE transports
6. **F5** — Full module parity + phone-enabled extensions (IR catalog, wordlists, large DBs)
7. **F6** — Branding, F-Droid release

## License

[AGPL-3.0](LICENSE). Derivative of Bruce; see [`NOTICE`](NOTICE) and
`THIRD_PARTY.md`.

## Support

Brucia is free. If it helps you, you can optionally
[buy me a coffee ☕](https://buymeacoffee.com/1vcian).
