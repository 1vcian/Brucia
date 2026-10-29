# Brucia architecture

Brucia splits a security-research multitool across two devices: a cheap ESP32 does
the radio/sensor work, and a phone does the computation, storage and UI. The phone
is the brain; the ESP32 is the antenna.

## The split

```
   ESP32-S3 (Brucia firmware)                Android (Brucia app)
   ──────────────────────────                ────────────────────
   Radio / sensor FRONT-END      <=USB=>      Compute + storage + GUI
   - capture / transmit                       - decode / crypto / analysis
   - realtime timing                          - Room DB, big datasets
   - raw-data framing + stream                - GPS, offline maps, viz
         │                                           │
         └──────── Transport (abstract) ─────────────┘
                   USB-CDC  |  Wi-Fi  |  BLE   (USB today)
                        └── one Protocol ──┘
```

**Guiding principle:** whatever needs the peripherals (CC1101, Wi-Fi/BLE radio,
PN532, IR) stays on the ESP; whatever is compute, memory, UI or geolocation moves to
the phone. Radio transmit is never delegated.

**Compatibility principle (non-negotiable):** Brucia is a **superset** of Bruce.
Every Bruce feature keeps working, and with no phone connected the device is still a
full Bruce (standalone mode). Companion mode is strictly additive.

## Layers

```
feature logic   (wardrive, crack, ir, ... - never touches the wire)
  └─ protocol   (message kinds + JSON payloads; proto/PROTOCOL.md)
       └─ framing     (length-prefixed frames on a byte stream)
            └─ transport   (USB-CDC today; Wi-Fi / BLE behind the same interface)
```

## Firmware side (`src/companion/`)

The companion is split into independent translation units: session lifecycle,
framing/responses, capability probing, command dispatch and feature services.
Each service owns its command matching and private state; the dispatcher retains
the legacy CLI fallback. See the [module ownership map](src/companion/README.md)
and [hardware availability contract](docs/HARDWARE_CAPABILITIES.md).


- `companionModeUSB()` takes exclusive ownership of the USB-CDC serial, shows a
  themed status screen, and runs the message loop.
- **HELLO -> CAPS**: reports board, chip, modules and transports.
- **CMD**: routed into Bruce's existing serial CLI, output captured and returned in
  the `ACK` (this exposes the whole device feature set to the phone). `fs_read`
  streams a file as base64 `DATA` frames.
- **SUB**: starts/stops streams - `wifi.ap` (async Wi-Fi scan), `ble.scan` (NimBLE),
  `subghz.raw` (RMT capture) - each device result is emitted as an `EVENT`.
- Reuses Bruce's CLI and scan code rather than reinventing it; see [MAPPING.md](MAPPING.md).

## App side (`android/`, package `app.brucia`)

- **transport/** - `Transport` interface; `UsbTransport` (usb-serial-for-android).
- **protocol/framing** - mirrors `proto/PROTOCOL.md`.
- **feature packages** - `wardrive`, `wifi` (WPA/PMKID crypto), `nfc`, `subghz`, `ir`,
  `ble`, `badusb`, `wordlist`, `report` - the heavy work the ESP cannot do.
- **ui/** - Jetpack Compose, flame theme; Paparazzi covers representative fixture layouts; real screen interactions need on-device tests.
- **Storage** - Room DB, bundled assets (OUI table, IR catalog, dictionaries), and
  phone storage for rockyou-scale wordlists.

## The protocol

Length-prefixed (uint32 LE) JSON envelopes `{v, t, id, d}`. Kinds: HELLO, CAPS, CMD,
ACK, ERR, EVENT, DATA, SUB, PING, PONG. It is **the single source of truth** for both
sides - see [proto/PROTOCOL.md](proto/PROTOCOL.md). Never add a message to one side
only; bump `PROTOCOL_VERSION` on schema changes.

## Transports

USB-CDC is implemented (native on the S3, high bandwidth, leaves the radios free).
Wi-Fi (TCP/WebSocket) and BLE (commands/light data only) are planned behind the same
`Transport` interface - see [ROADMAP.md](ROADMAP.md).

## Why this matters

A LilyGo T-Embed CC1101 is ~$30-50. Paired with a phone most people already own, it
reaches capabilities (rockyou-scale cracking, GPS wardriving with maps, large
catalogs, rich visualization) that a standalone device in this price class cannot -
an affordable, open alternative for research and education.
