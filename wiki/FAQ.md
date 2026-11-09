# FAQ

## Which board should I start with?

The reference is LilyGO T-Embed CC1101. It includes CC1101, PN532 and IR hardware;
FM, LoRa, NRF24, Ethernet, LF RFID and iButton require their own supported interfaces.
Check [hardware capabilities](https://github.com/1vcian/Brucia/blob/main/docs/HARDWARE_CAPABILITIES.md) rather than
assuming a compiled menu proves a peripheral is installed.

## Where can I check support for my setup?

Start with the feature page for your tool and check your board's reported capabilities.
[TESTS.md](https://github.com/1vcian/Brucia/blob/main/TESTS.md) records the exact versions,
setups and results checked so far. Some physical workflows still need testing; a release
or preview image alone does not establish support for every peripheral.

## Is Brucia the official Bruce app?

No. Brucia is a separate Bruce-derived firmware and Android workspace. Bruce authors
remain credited. Standalone compatibility is maintained per feature, not guaranteed
for every upstream function. See NOTICE and THIRD_PARTY.md in the repository.

## Does BLE replace the USB cable?

The paired BLE console sends CLI commands. It does not carry the full USB companion
workspace. A BLE advertisement also does not guarantee a popup on macOS or another OS.

## Why does a tool say unavailable or busy?

The firmware may not report the required module/operation, or another task owns it.
Check the board, capabilities and returned reason. Stop the competing operation;
do not interpret unavailable, blocked or not tested as successful.

## How do I recover a failed connection or update?

Check the data cable, USB access, companion mode and port ownership. For a failed
flash use the board's documented bootloader procedure and the correct board image.
Keep app data and required device files backed up. See [Getting Started](Getting-Started.md) and [Tutorials](Tutorials.md).

## Is the community App Store part of Brucia?

It is a third-party Bruce community service. Its script downloads catalog/apps over
plain HTTP. Those downloads are outside the bootstrap installer's checks. Review code
before running it. This build also does not enforce TLS certificate validity dates;
see the F35 evidence in TESTS.md for the exact limits.

## What may I publish in a bug report?

Versions, board, steps, expected/observed behavior and redacted logs. Remove passwords,
keys, personal captures and device identifiers unrelated to the issue. Use active
tools only on systems you own or are authorized to test. For sensitive vulnerabilities,
follow [SECURITY.md](https://github.com/1vcian/Brucia/blob/main/SECURITY.md).

---

[Wiki home](Home.md) · [Getting Started](Getting-Started.md) · [Contribute](Contributing.md)
