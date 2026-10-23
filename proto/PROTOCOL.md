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
- **Payload**: CBOR map. JSON with the same keys is accepted in debug builds.

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

## Streams (`DATA` / `SUB`)

A stream is a named, high-rate channel the phone subscribes to. First stream:

- `wifi.ap` — access-point observations for wardriving. Each item:
  `{bssid, ssid, ch, rssi, enc, ts}`. The phone adds GPS/time, dedups, maps and
  exports (Wigle CSV / KML). The device does **not** need onboard GPS.

## Versioning

`PROTOCOL_VERSION = 0` while unstable. Both sides send `v`; a receiver that gets a
higher `v` than it knows should still parse the envelope and reply `ERR` with a
clear message for unknown kinds, never crash.
