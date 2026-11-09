# Features

This is an implementation guide, not a certification of every physical function.
Check the connected board's capabilities and [test evidence](https://github.com/1vcian/Brucia/blob/main/TESTS.md).
Use [Tutorials](Tutorials.md) for first workflows and expected results.

## Active research tools

The catalog includes **De Bruijn**, **WPA/WPA2 dictionary attacks**, **MIFARE dictionary
checks**, **BLE advertising spam**, **Wi-Fi beacon spam/deauth**, **IR power spam**,
portals, MITM and HID tools. See the family pages below for behavior and limits.
These are not all new inventions: Brucia's additions often lie in phone-side
processing, storage and a unified workflow. Use them only in an authorized lab.

The distinctive workspaces are Android audio/DSP (including SSTV), saved Wi-Fi/BLE
history and place comparisons, phone-hosted proxy inspection, and combined capture,
dictionary verification and reporting. See the repository's docs/COMPARISON.md for
the primary-source comparison and its limits.

| Family | Workspace | Boundary |
|---|---|---|
| [Wi-Fi](Features-Wi-Fi.md) | Observations, wardrive, maps, captures and analysis | Radio and driver limits; test stop/reconnect and export behavior. |
| [Sub-GHz](Features-Sub-GHz.md) | Waveforms, saved captures, decoding and supported sends | CC1101 is not an IQ SDR; decoding and replay are protocol-specific. |
| [NFC](Features-NFC.md) | Supported tag operations and NDEF | PN532 and tag-type limits; writing/erasing changes the tag. |
| [BLE and IR](Features-BLE-and-IR.md) | BLE observations, paired console and IR catalog/control | Advertising does not promise an OS popup; IR needs a compatible receiver. |
| [Optional radios](Features-Radio.md) | NRF24, LoRa, FM and ESP-NOW tools | Stock T-Embed does not include every optional module; check ownership conflicts. |
| [Audio](Features-Audio.md) | Spectrum, waveform, recording, tones and SSTV | DSP tests do not verify the physical microphone/speaker path. |
| [Ethernet](Features-Ethernet.md) | Link status and network tools | Requires supported Ethernet hardware and a lab network. |
| [Tools](Features-Tools.md) | Scripts, macros, reports, BLE console and local analysis | Device scripts and third-party downloads require review; stop and disconnect matter. |
| [Device and files](Features-Device.md) | Information, files, Remote, settings and updates | Keep the correct firmware/board pair; verify mode changes and recovery. |

USB carries the companion workspace. The paired BLE console is a separate CLI path.
The app retains state across some lifecycle changes; this does not guarantee a radio
operation stopped. Inspect the reply, wait for completion and confirm recovery before
starting another operation on that peripheral.
