# Architecture

Brucia splits a security-research multitool across two devices: a cheap ESP32 does
the radio/sensor work, and a phone does the computation, storage and UI. The phone
is the brain; the ESP32 is the antenna.

## The split

```
   ESP32-S3 (Brucia firmware)                Android (Brucia app)
   --------------------------                --------------------
   Radio / sensor FRONT-END      <=USB=>      Compute + storage + GUI
   - capture / transmit                       - decode / crypto / analysis
   - realtime timing                          - Room DB, big datasets
   - raw-data framing + stream                - GPS, offline maps, viz
         |                                           |
         +-------- Transport (abstract) -------------+
                   USB-CDC  |  Wi-Fi  |  BLE   (USB today)
                        +-- one Protocol --+
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
  +- protocol   (message kinds + JSON payloads; see [[Protocol]])
       +- framing     (length-prefixed frames on a byte stream)
            +- transport   (USB-CDC today; Wi-Fi / BLE behind the same interface)
```

Feature logic never touches the wire; it talks to the protocol layer. The protocol
layer defines message kinds and payloads; framing turns messages into
length-prefixed frames on a byte stream; the transport is just a byte pipe.

## Firmware side (`src/companion/`)

- `companionModeUSB()` takes exclusive ownership of the USB-CDC serial, shows a
  themed status screen, and runs the message loop.
- **HELLO -> CAPS**: reports board, chip, firmware, transports and a real
  per-peripheral `caps{}` capability object (see below).
- **CMD**: a set of companion-specific commands is intercepted first (streaming or
  direct-control commands); everything else is routed into Bruce's existing serial CLI
  with the output captured into the `ACK`, exposing the whole device feature set.
  `fs_read` streams a file as base64 `DATA` frames.
- **SUB**: starts/stops streams - `wifi.ap`, `wifi.sta`, `wifi.probe`, `wifi.monitor`
  (promiscuous 802.11), `ble.scan` (NimBLE), `subghz.raw` and `subghz.spectrum`
  (CC1101), and `mic.spectrum` - each device result is emitted as an `EVENT`.
- Reuses Bruce's CLI and scan code rather than reinventing it. The companion layer
  wraps the CLI with framing and streaming; it does not duplicate it.

### Runtime capability detection

The device no longer advertises a static feature list. `CAPS` carries a `caps{}` object
computed by a hybrid of compile-time board macros (fixed onboard peripherals: `cc1101`,
`ir`, `mic`, `speaker`, and the always-present `wifi`/`ble`) and quick runtime probes
for the optional/uncertain ones (`nfc` via a PN532 I2C probe, `nrf24` via an SPI
presence probe, `fm` via a Si4713 I2C probe). The app uses this map to show or hide
each page, so the same APK adapts to whatever board and add-ons are actually attached
(for example FM is implemented in firmware but reports `fm:false` on the T-Embed CC1101,
and that page stays hidden). A legacy coarse `modules` array is still sent for
backward compatibility.

### ESP capture vs phone stack

The split follows a clear line: the ESP does everything that needs the radio in real
time, and the phone does everything built on captured bytes. Wi-Fi **monitor mode** is
the clearest example - the ESP runs the promiscuous 802.11 capture and streams raw
frames (`wifi.monitor`, base64), while the phone assembles the pcap, runs Wireshark-style
analysis and does PSK-based decryption. The same pattern applies to sub-GHz (ESP
captures RMT edge timings; phone decodes OOK and rolling codes) and to WPA (ESP captures
the handshake/PMKID; phone cracks it).

The companion code lives in `src/companion/companion.cpp` and `companion.h`.

## App side (`android/`, package `app.brucia`)

- **transport/** - a `Transport` interface; `UsbTransport` built on
  usb-serial-for-android.
- **protocol/framing** - mirrors the protocol document exactly.
- **feature packages** - `wardrive`, `wifi` (WPA/PMKID crypto, monitor/pcap), `mitm`
  (on-phone HTTPS proxy), `nfc`, `subghz`, `ir`, `ble`, `nrf24`, `badusb`, `wordlist`,
  `report` (OSINT + insights), `update` (app OTA + device USB flash) and `remote` - the
  heavy work the ESP cannot do.
- **ui/** - Jetpack Compose, flame theme; screens are verified with Paparazzi
  screenshot tests on the JVM.
- **Storage** - Room DB, bundled assets (OUI table, IR catalog, dictionaries), and
  phone storage for rockyou-scale wordlists.

## The protocol

Length-prefixed (uint32 little-endian) JSON envelopes `{v, t, id, d}`. Kinds: HELLO,
CAPS, CMD, ACK, ERR, EVENT, DATA, SUB, PING, PONG. The protocol is **the single
source of truth** for both sides. Never add a message to one side only; bump
`PROTOCOL_VERSION` on schema changes. Full detail in [[Protocol]].

## On-phone offload

Several features exist only because the phone, not the ESP, does the compute:

- **WPA crack** - captured handshakes/PMKIDs are cracked on the phone (PBKDF2/PTK/MIC)
  against rockyou-scale wordlists with hashcat-style rules.
- **MITM HTTPS proxy** - once the ESP sets up the ARP redirect (`arp_spoof`), an HTTPS
  proxy runs on the phone to inspect the redirected traffic. The radio work is on the
  ESP; the interception and TLS handling are on the phone.
- **Session report** - a report generator on the phone combines the session's findings
  with OSINT enrichment and insights.
- **Decoders and catalogs** - OOK/rolling-code decode, OUI/IR catalogs, offline maps and
  visualization all run on the phone from the ESP's raw captures.

## Transports

- **USB-CDC** is implemented: native on the ESP32-S3, high bandwidth, leaves the
  radios free.
- **Wi-Fi (TCP/WebSocket)** and **BLE** (commands / light data only) are planned
  behind the same `Transport` interface. See the roadmap.

## Remote control (nav + TFT mirror)

Separate from companion offload mode, Remote control drives the device's real UI from
the phone using two firmware primitives:

- `nav <next|prev|up|down|select|esc|nextpage|prevpage>` injects input into the real
  UI (the same mechanism Bruce's WebUI uses).
- `display start` (`src/core/tftLogger`) streams the TFT draw operations over serial
  so the app can mirror the screen live.

The app's Remote tab sends `nav` from a D-pad and renders the device screen from that
draw-op stream, while the device runs its normal UI (the serial CLI task runs in the
background). This yields every standalone feature - Evil Portal, Karma, Beacon spam,
Sniffer, Reverse Shell, Scripts - without wrapping each one.

## Where things live (code map)

Brucia hooks into the existing Bruce code rather than reinventing it. Key integration
points:

| Area | Location | Role |
|------|----------|------|
| Serial command task | `src/core/serialcmds.{h,cpp}` | entry point `parseSerialCommand(...)` |
| CLI dispatcher | `src/core/serial_commands/cli.{h,cpp}` | routes every `CMD` |
| Per-domain commands | `src/core/serial_commands/*_commands.cpp` | `wifi_`, `rf_`, `ir_`, `rfid_`, `badusb_`, `crypto_`, `gpio_`, `power_`, `screen_`, `settings_`, `sound_`, `storage_`, `util_`, `interpreter_` |
| Connect / transfer plumbing | `src/core/connect/serial_commands.*`, `esp_connection.*` | framing reference (file_sharing) |
| USB CDC access | `src/core/USBSerial/USBSerial.h` | primary transport |
| Companion menu entry | `src/core/menu_items/ConnectMenu.cpp`, `src/core/main_menu.{h,cpp}` | the Brucia / Companion entry |
| Wi-Fi modules | `src/modules/wifi/` (`sniffer.cpp`, `ap_info.*`, `channel_analyzer.*`, `scan_hosts.h`) | AP/packet capture; sniffer emits the `wifi.ap` stream |
| GPS module | `src/modules/gps/` | kept for standalone; phone GPS replaces it in companion mode |
| Board / build | `platformio.ini` env `lilygo-t-embed-cc1101`, `custom_*.csv` partitions | ESP32-S3, native USB CDC |
| New companion code | `src/companion/` | transport, framing, dispatcher, HELLO/CAPS handshake, stream hooks |

`CMD` messages route through the existing CLI dispatcher, so most device control is
available for free; companion-only commands (streaming hooks) are added there as new
entries.

## Why this matters

A LilyGo T-Embed CC1101 is ~$30-50. Paired with a phone most people already own, it
reaches capabilities (rockyou-scale cracking, GPS wardriving with maps, large
catalogs, rich visualization) that a standalone device in this price class cannot -
an affordable, open alternative for research and education.
