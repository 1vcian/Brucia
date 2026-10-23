# Brucia companion protocol (v0)

Single source of truth for the wire protocol between the **device** (ESP32) and
the **Android app**. Both the firmware (C++) and the app (Kotlin) must mirror this
document. Never add a message to one side only. Bump `PROTOCOL_VERSION` on any
schema change and keep both sides backward-aware.

## Layering

```
feature logic            (wardriving, ir, rf, ... — never touches the wire)
  └─ protocol  (this doc: message kinds + CBOR payloads)
       └─ framing   (length-prefixed frames on a byte stream)
            └─ transport  (USB-CDC  →  Wi-Fi TCP/WS  →  BLE)
```

- **Transport** is a byte pipe. Order of implementation: USB-CDC (primary),
  Wi-Fi TCP/WebSocket, BLE (commands / light data only).
- **Framing**: each frame = `uint32 length` (little-endian, payload bytes) +
  `payload`. Simple, transport-agnostic, resync by reading length. (COBS may be
  added later for self-synchronizing streams.)
- **Payload**: a JSON object (v0 baseline; parsed with ArduinoJson on the device). A compact binary encoding (MessagePack on the ESP via ArduinoJson, CBOR on Android) may replace JSON later behind the same envelope — bump the version when it does.

## Envelope (every message)

| key  | type   | meaning |
|------|--------|---------|
| `v`  | uint   | protocol version (currently 0) |
| `t`  | uint   | message kind (see below) |
| `id` | uint   | correlation id (request ↔ ACK/ERR); 0 for unsolicited events |
| `d`  | any    | kind-specific body |

## Message kinds (`t`)

| value | name    | direction     | body (`d`) |
|-------|---------|---------------|------------|
| 1 | `HELLO`        | phone → ESP   | `{app, appVersion}` |
| 2 | `CAPS`         | ESP → phone   | device capabilities (see below) |
| 3 | `CMD`          | phone → ESP   | `{cmd: string, args: {...}}` |
| 4 | `ACK`          | ESP → phone   | `{ok: true, result?: any}` |
| 5 | `ERR`          | ESP → phone   | `{code: uint, msg: string}` |
| 6 | `EVENT`        | ESP → phone   | `{ev: string, data: {...}}` discrete event |
| 7 | `DATA`         | ESP → phone   | `{stream: string, seq: uint, chunk: bytes}` raw stream |
| 8 | `SUB`          | phone → ESP   | `{stream: string, on: bool}` start/stop a stream |
| 9 | `PING`/`PONG`  | both          | keepalive |

### `CAPS` body

```
{
  "board":    "lilygo-t-embed-cc1101",
  "chip":     "esp32-s3",
  "fw":       "<brucia version>",
  "proto":    0,
  "modules":  ["wifi", "cc1101", "ir", "nfc?", "nrf24?", "gps?"],
  "transports": ["usb"],      // what this link supports
  "mtu":      4096            // max useful frame payload on this transport
}
```

### `CMD` mapping to existing Bruce CLI

Brucia reuses the existing Bruce command dispatcher
(`src/core/serial_commands/cli.cpp` and the per-domain `*_commands.cpp`).
A `CMD` with `cmd:"wifi scan"` is routed through that dispatcher, so most device
control is available for free. Companion-specific commands (streaming hooks) are
added as new entries rather than a parallel system.

## Streams (`SUB` + `EVENT`/`DATA`)

A stream is a named channel the phone turns on/off with
`SUB {stream: "<name>", on: bool}` (the device replies `ACK`).

- **Structured streams** deliver `EVENT {ev: "<name>", data: {...}}` messages.
- **Raw streams** (future, e.g. RF samples) deliver `DATA {stream, seq, chunk}`.

First stream (F2):

- `wifi.ap` — access-point observations for wardriving, one `EVENT` per AP:
  `data = {bssid, ssid, ch, rssi, enc, ts}` (`ts` is device uptime in ms). The
  device runs back-to-back async Wi-Fi scans; the phone adds GPS/real time,
  dedups, maps and exports (Wigle CSV / KML). The device needs **no** onboard GPS.

## Versioning

`PROTOCOL_VERSION = 0` while unstable. Both sides send `v`; a receiver that gets a
higher `v` than it knows should still parse the envelope and reply `ERR` with a
clear message for unknown kinds, never crash.
