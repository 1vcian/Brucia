# Features

Find a workspace, see what it offers and check the hardware it needs.
New to Brucia? Start with [Getting Started](Getting-Started.md) or follow a
[tutorial](Tutorials.md).

## Active research tools

The catalog includes **De Bruijn**, **WPA/WPA2 dictionary attacks**, **MIFARE dictionary
checks**, **BLE advertising spam**, **Wi-Fi beacon spam/deauth**, **IR power spam**,
portals, MITM and HID tools. See the family pages below for behavior and limits.
Use active tools on equipment you own or have permission to test.

The distinctive workspaces are Android audio/DSP (including SSTV), saved Wi-Fi/BLE
history and place comparisons, phone-hosted proxy inspection, and combined capture,
dictionary verification and reporting. The
[comparison](https://github.com/1vcian/Brucia/blob/main/docs/COMPARISON.md) covers
Bruce, Flipper and Brucia, including the measured processing benchmarks.

| Family | Workspace | Hardware and usage notes |
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
Wait for an operation to finish before starting another one on the same peripheral.
For board- and version-specific checks, see
[recorded results](https://github.com/1vcian/Brucia/blob/main/TESTS.md).

---

[Wiki home](Home.md) · [Tutorials](Tutorials.md) · [Share a result](Contributing.md)
