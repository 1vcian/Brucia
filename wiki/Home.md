# Brucia

**The phone is the brain. The ESP32 is the antenna.**

Brucia is a public, AGPL-3.0 project pairing Bruce-derived firmware with an Android
workspace. Start with the LilyGO T-Embed CC1101, a USB data cable and an Android phone
with USB host/OTG support. The phone handles analysis and storage; the ESP32 handles
peripherals and timing. Standalone device operation remains available.

- [Getting Started](Getting-Started.md): install the app and matching firmware, then connect over USB.
- [Tutorials](Tutorials.md): illustrated video guides, transcripts and expected results.
- [Features](Features.md): the tool families and their hardware limits.
- [Architecture](Architecture.md) and [Protocol](Protocol.md): source and transport contracts.
- [Building](Building.md) and [Contributing](Contributing.md): reproduce a build or contribute a change.
- [FAQ](FAQ.md): compatibility, verification and recovery.

## Public does not mean fully verified

Use the [release notes](https://github.com/1vcian/Brucia/releases) for changes and
[TESTS.md](https://github.com/1vcian/Brucia/blob/main/TESTS.md) for evidence tied to an app/firmware revision.
Previous hardware captures establish only what was exercised at the time. Radio output,
peripheral behavior and phone lifecycle checks still need individual acceptance.

## Credits

Brucia derives from [Bruce](https://github.com/BruceDevices/firmware); its authors and libraries remain credited in
NOTICE and THIRD_PARTY.md. It is a separate project, not the official Bruce app.
Both firmware and app are AGPL-3.0. Use equipment you own or are authorized to test.
