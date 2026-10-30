# Flipper Zero, Bruce and Brucia: an evidence-based comparison

Reference setup: **stock LilyGO T-Embed CC1101 + Android phone**, compared with
stock Flipper Zero and Bruce on the same LilyGO board. Add-ons are separate.
This is an implementation comparison, not a claim that every listed Brucia path
has passed physical acceptance. See [current tests](../TESTS.md),
[hardware capabilities](HARDWARE_CAPABILITIES.md) and the
[latest audit](../TESTS.md).

Brucia retains Bruce firmware functionality and attribution, adds a device-side
companion protocol and an Android workspace. Many radio features originate in
Bruce. The phone extends analysis, storage and orchestration; it cannot supply a
missing antenna, analog front end or radio modulation capability.

| Area | Stock Flipper Zero | Bruce on stock T-Embed CC1101 | Brucia on stock T-Embed + phone |
|---|---|---|---|
| Wi-Fi | External Wi-Fi board | Native ESP32 Wi-Fi | Native; phone-side maps, capture analysis and workflow |
| BLE | Built in; mobile app and community apps | Built in; board-dependent tools | Device radio plus Android analysis and controls |
| Sub-GHz | CC1101 with supported protocol library | CC1101 on this board | Same radio; protocol coverage and RF performance require individual verification |
| NFC 13.56 MHz | ST25R3916; card-specific reading/emulation | PN532 on this board | PN532: compatible reads/writes, Type 4 NDEF and identity-only FeliCa target paths; no universal emulation |
| RFID 125 kHz | Native read/emulate/write for supported tags | No stock LF hardware | Unavailable on stock T-Embed; compatible add-on integration required |
| iButton | Built-in contacts and supported protocols | No stock contacts | Unavailable on stock T-Embed; hardware/interface support required |
| Infrared | Built-in TX/RX | Built-in TX/RX on this board | Device TX/RX with phone catalog and custom layouts |
| Audio | No built-in microphone | Board microphone/speaker and audio tools | Existing DSP, SSTV RX/TX, DTMF, scope and RTTTL composer; physical signal chain pending |
| NRF24 / LoRa / FM / Ethernet | External hardware where supported | External modules where supported | Optional modules; presence and operation support gate each tool |
| Mobile workflow | Official mobile app exists | Primarily device UI / WebUI / CLI | Native Android companion; phone compute, storage, GPS and display |
| Firmware updates | Official update tools and mobile workflow | Board-specific distribution | Release parsing/download/flash code; final installation and recovery acceptance pending |
| Power | Standalone battery | Standalone board battery | USB power depends on phone/OTG/cable; also consumes the phone battery |

## What software can and cannot close

- Sub-GHz and IR are good candidates for additional compatible protocols. Matching
  CC1101 silicon is not proof of equal antenna performance, range, decoders or
  transmit behavior. RSSI waterfall is not an IQ spectrogram from a wideband SDR.
- PN532 target mode is not full MIFARE Classic/Ultralight emulation. The current
  FeliCa path emulates identity, not arbitrary card contents. Flipper's documented
  NFC-V support is another hardware/protocol gap. An NDEF record transferred via
  Type 4 does not clone its original card technology.
- LF RFID needs a coil and analog front end; iButton needs an electrical interface
  and contacts. Extra phone processing alone does not enable either.
- Recognizing a rolling-code format does not recover keys, defeat counters or
  guarantee that replay will be accepted. Protected NFC content likewise requires
  appropriate keys/access; neither device is a universal credential duplicator.
- Flipper's standalone operation, integrated hardware and existing protocol/app
  ecosystem are meaningful strengths. Brucia's phone-assisted workflow is the
  product direction, not evidence of universal superiority or exclusive novelty.
- Proposed bundles, replayable research sessions and community extensions remain
  in [the ideas backlog](../ROADMAP.md). Existing audio tools are not new ideas.

## Verification and distribution

USB connection, device information and passive AP scanning were observed on an
intermediate APK with firmware 0.8.23. They do not certify the current rebuilt
firmware and app. Logical/native tests and three representative board builds are
recorded separately from physical acceptance; the full configured-board matrix
has not been established by those three builds.

The repository intentionally remains private while the existing base is validated.
Anonymous GitHub release discovery is expected to be unavailable until publication.
No maintainer token is embedded. Missing release assets must produce an error,
not a silent download of unrelated or older firmware.

The **infinite battery** line is a community joke: the device may draw USB power
from the phone. Actual availability and charging depend on the phone, OTG, cable
and power-management behavior, and runtime remains finite.

## Primary references

Checked during this audit; operation support remains card/protocol-specific:

- [Flipper NFC cards and emulation](https://docs.flipper.net/zero/nfc/read)
- [Flipper LF RFID hardware](https://blog.flipper.net/rfid/)
- [Flipper iButton hardware and protocols](https://docs.flipper.net/zero/ibutton)
- [Official LilyGO T-Embed CC1101 hardware](https://github.com/Xinyuan-LilyGO/T-Embed-CC1101)
- Brucia implementation: `src/companion`, `src/modules/rfid/PN532.cpp`, Android
  `audio`, `protocol`, `update` and `wardrive` packages. Implementation presence
  alone does not certify a successful physical operation.
