# Hardware availability and feature gating

Reference device: **LILYGO T-Embed CC1101**, without expansion modules. The
manufacturer documents the [hardware and pin map](https://github.com/Xinyuan-LilyGO/T-Embed-CC1101)
and [product specification](https://github.com/Xinyuan-LilyGO/documentation/blob/master/en/products/t-embed-series/t-embed-cc1101/index.md).
Do not confuse the standard CC1101 model with the Plus variant or another T-Embed.

## Reference inventory

| Hardware | Stock board | Companion availability check | Verification still needed |
|---|---|---|---|
| ESP32-S3 Wi-Fi / BLE | Built in | Target support; individual operations still return errors | Continuous capture, coexistence and recovery on the new build |
| CC1101 Sub-GHz | Built in | Bounded SPI PARTNUM/VERSION read | Capture against a known transmitter and RF path verification |
| PN532 NFC, 13.56 MHz | Built in | Wake/init plus firmware response; a saved reader setting is not presence | Read/write/emulation with suitable test tags |
| IR transmitter / receiver | Built in | Board wiring declares the interface | Known signal reception and verification at an external receiver |
| PDM microphone / I2S speaker | Built in | Board profile declares these fixed interfaces | Audio capture, playback and measured loopback; initialization alone is not an acoustic test |
| ST7789 display, encoder, RGB LEDs | Built in | Board profile and normal device initialization | Physical controls, colors, brightness and restore after disconnection |
| BQ25896 / BQ27220 battery circuitry | Built in | Existing board power/fuel-gauge drivers | Charging and battery measurement under known loads |
| TF / microSD socket | Built in; card not included | Storage mount/open/write result | A real card, full storage and removal during operations |
| NRF24L01 | Expansion | Configured SPI/CE pins plus RF24 chip identification | Attach an expansion to verify positive detection and operation |
| Si4713 FM transmitter | Not fitted | Driver availability plus I2C address probe | An actual Si4713 and its audio wiring |
| LoRa SX127x / SX126x | Not fitted | LoRa driver initialization succeeds on a supported board | A LoRa-equipped board; CC1101 is not a LoRa modem |
| W5500 Ethernet | Expansion | SPI VERSIONR identification | A real module and network link |
| 125 kHz LF reader / iButton interface | Not fitted | No confirmed capability is reported by current firmware | A supported presence-check/configuration path before enabling companion controls |
| GPS receiver | Not fitted | The companion currently uses phone location | Android permission, location fixes and exported coordinates |

The standard board has 16 MB flash and 8 MB PSRAM. Its SPI wiring is SCK 11,
MISO 10, MOSI 9. CC1101, display and SD share this bus. NRF24 and W5500
configuration also shares expansion pins: do not assume both modules can use the
same CS/IRQ wiring concurrently. The vendor's `BOARD_LORA_*` pin labels in examples
refer to the CC1101 wiring on this model; they are not evidence of a LoRa chip.

## Contract

- Module-dependent controls require a literal JSON `true` in `CAPS.d.caps`.
  Missing, malformed and unreported capabilities remain unavailable. Offline
  phone tools do not require a device module.
- Composite screens and stream subscriptions use the same capability checks as
  individual pages. SUB replies are correlated by ID; failures and timeouts clear
  the affected active state instead of leaving a false running indication.
- Physical probes run before streams start and are cached for the device lifetime.
  Restart after changing wiring or expansion modules. Repeated HELLO/PING traffic
  must not reset radios or interfere with the display's shared SPI bus.
- GPIO wiring and successful driver initialization are different from a passing
  end-to-end test. Fixed audio/IR interfaces have no general chip-identification
  operation. Their availability comes from the board profile; actual operation
  needs stimulus/response tests.
- NFC availability describes the selected supported reader. It does not imply
  support for 125 kHz tags, Chameleon/Amiibolink, or external nested/mfkey32 tools.
  Unsupported headless actions stay disabled.

## Acceptance checks on the stock T-Embed

1. Boot and connect without add-ons: CC1101/NFC/IR/mic/speaker are available when
   initialization succeeds; NRF24/FM/LoRa/Ethernet are unavailable.
2. Feed absent, null, mistyped or partial capability maps into the app: no
   unrelated module becomes available; navigation and offline utilities still work.
3. Start a composite view: it must not subscribe to an unavailable radio/microphone.
4. Fail or withhold a SUB response: show failure and clear the running state.
5. Repeat HELLO while streaming: no repeated probe/reset or firmware reboot.
6. Restart with a supported add-on attached: identify it before enabling its tools;
   initialization/capture errors must remain visible even after presence detection.
7. Run real stimulus/response tests before marking a feature hardware-verified.
   Without a tag, receiver or known transmitter, a successful command ACK is insufficient.
