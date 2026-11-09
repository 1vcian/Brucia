# Architecture

Brucia separates physical timing from phone-side analysis. The ESP32 operates its
radios, buses and sensors; Android provides the interface, GPS, storage and computation.

| Layer | Responsibility | Source |
|---|---|---|
| Firmware modules | Peripheral drivers and standalone functions | `src/modules/`, `src/core/` |
| Companion | Capability reporting, bounded commands, streams and resource ownership | `src/companion/` |
| Wire contract | Framing, envelopes, replies and versioned capabilities | `proto/PROTOCOL.md` |
| App session | Correlation, timeouts, disconnect and mode changes | `android/app/src/main/java/app/brucia/session/` |
| App tools | Services, processing and Compose UI | `android/app/src/main/java/app/brucia/` |

## Transports and modes

USB is the framed companion transport. The BLE console is a separate paired CLI
connection; it is not the full companion protocol over BLE. Other transport ideas
remain roadmap work unless the code and acceptance evidence explicitly say otherwise.

Remote mode leaves framed companion operation and uses the device's screen/navigation
interface. Entry waits for exit acknowledgement; failure must stay visible. Stop and
disconnect belong to each operation's lifecycle, including queued writes and callbacks.

## Compatibility and verification

Brucia preserves Bruce attribution, file formats and JavaScript APIs where compatible.
Standalone compatibility is a goal tested per path, not a universal superset guarantee.
Firmware build, unit test, simulated peer and physical acceptance are different evidence.

See the [full architecture](https://github.com/1vcian/Brucia/blob/main/ARCHITECTURE.md),
[companion code map](https://github.com/1vcian/Brucia/blob/main/src/companion/README.md) and [Protocol](Protocol.md).
