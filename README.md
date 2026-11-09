<div align="center">
<img src="docs/img/public-overview.png" alt="Brucia: ESP32 peripherals connected over USB to Android analysis and storage" width="100%">

**The phone is the brain. The ESP32 is the antenna.**

*Let your phone burn, so your ESP32 doesn't.*

[Get started](wiki/Getting-Started.md) · [Releases](https://github.com/1vcian/Brucia/releases/latest) ·
[Public wiki](https://1vcian.me/Brucia/wiki/) · [Tutorials](wiki/Tutorials.md) · [Test evidence](TESTS.md)
</div>

Brucia pairs Bruce-derived ESP32 firmware with a native Android app for device control,
analysis, maps and storage. The reference board is the **LilyGO T-Embed CC1101**.
The firmware also has a standalone menu. Brucia is free software under **AGPL-3.0**,
with attribution to [Bruce](https://github.com/BruceDevices/firmware) and its contributors.

## Start here

1. Download the APK and the firmware for your exact board from the same release.
2. For the T-Embed CC1101, use the [web flasher](https://1vcian.me/Brucia/) in desktop Chrome or Edge
   with a USB **data** cable. Check the release and board shown before flashing.
3. Install the APK on Android. Connect the board to the phone with USB host/OTG support,
   grant USB access and enter **Brucia** companion mode on the device.
4. Check the reported firmware and capabilities before opening a tool. Start with device
   information or a file listing; follow the [connection tutorial](wiki/Getting-Started.md).

The Android app also has a USB firmware updater. Browser flashing on Android is not the
documented route. Installation, upgrades and recovery are covered in [Tutorials](wiki/Tutorials.md).

## What you can explore

| Workspace | App and device responsibilities | Important limit |
|---|---|---|
| Wi-Fi and BLE observations | Device radio; phone lists, maps, storage and exports | Observe your own lab; receiver behavior and active functions need separate checks. |
| Sub-GHz and infrared | Device timing; phone waveforms, catalogs and saved items | Protocol support varies. A successful command is not proof of a receiver response. |
| NFC | PN532 interaction plus phone presentation | Tag-specific support; no universal cloning or emulation claim. |
| Audio | Device mic/speaker; phone DSP, spectrum and analysis | Physical audio paths require their own verification. |
| Files and scripts | Phone file workflow; JavaScript runs on the device | Third-party scripts are executable code, not trusted by default. |
| Remote and device tools | Screen mirror, navigation, settings, diagnostics and updates | Remote changes the link mode; stop operations before switching. |
| Optional modules | NRF24, LoRa, FM, Ethernet and other interfaces | Requires compatible hardware, firmware support and runtime availability. |

See the [feature guide](wiki/Features.md) for module details and [hardware capabilities](docs/HARDWARE_CAPABILITIES.md)
for what the stock board includes. USB is the companion transport. The separate paired
BLE console is a command interface, not a wireless replacement for the USB workspace.

## Verification status

Brucia is public and under active development. **Published does not mean every function
has passed a physical test.** [TESTS.md](TESTS.md) records the tested revision, setup,
results and limits. Historical hardware runs demonstrated parts of the USB path;
they do not certify the latest app/firmware pair or every configured board.

Open physical checks include interrupted updates, mode changes and operation stopping,
module-specific RF/IR/NFC/audio behavior, and the serial-link soak with actual radio events.
Use [the functional checklist](docs/APP_CONTROLS_CHECKLIST.md) to report a repeatable result.
Missing peripherals, blocked checks and tests not run are not passes.

## Images and video guides

<img src="docs/img/public-ui.png" alt="Brucia Compose preview renders with fixture data; not phone captures" width="100%">

These are verified **Compose preview renders with fixture data**, not captures from a
phone. The [illustrated videos and transcripts](wiki/Tutorials.md) explain setup and
workflows; they are not recordings of hardware acceptance. Provenance is recorded in
[the media manifest](docs/media/manifest.json). Earlier device captures remain in
[historical hardware evidence](docs/HARDWARE_VERIFICATION.md).

## The phone/device split

The ESP32 owns physical interfaces and timing. Android handles analysis, GPS, databases,
exports and the larger interface. Standalone compatibility is a maintenance goal,
not a guarantee that every upstream Bruce function has been tested in this fork.
Brucia is a separate project from Bruce and its official app.

**Infinite battery / SERIAL-KILLER** is the project joke: USB may power the board from
the phone; both batteries remain finite.

## Build and contribute

Read [CONTRIBUTING.md](CONTRIBUTING.md) for supported toolchains and checks,
[Architecture](wiki/Architecture.md) for the source map, and
[Protocol](proto/PROTOCOL.md) for the wire contract. Build results, simulated tests and
physical acceptance must be reported separately. Keep source, firmware and app versions
with any bug report; omit personal captures and credentials.

## License and use

Use active functions only on equipment you own or are authorized to test.
See [SECURITY.md](SECURITY.md). Firmware and app are AGPL-3.0: [LICENSE](LICENSE),
[NOTICE](NOTICE) and [THIRD_PARTY.md](THIRD_PARTY.md) preserve the upstream credits.
Source for a release is available at its matching tag in this repository.

## Support

Brucia is free. If it helps you, you can optionally sponsor it via
[GitHub Sponsors](https://github.com/sponsors/1vcian) or
[buy me a coffee](https://buymeacoffee.com/1vcian).

### Crypto

| | Address |
|---|---|
| <img src="docs/img/btc-qr.png" alt="Bitcoin QR" width="110"> | **Bitcoin**<br>`bc1qee8a06x886ng8ce6qy84l6u6x54jnjsesgqym7` |
| <img src="docs/img/xmr-qr.png" alt="Monero QR" width="110"> | **Monero**<br>`46qDyafRkn1at3E2GMwuESBeqzpt74xE8S2kjPe35EUyKiS57Ho7cLB1vD2zH4hPmjYcYzWWxSSLZAYB5Hp1yDFrKnPWkR7` |
