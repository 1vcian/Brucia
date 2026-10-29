# Brucia companion protocol (v0)

Single source of truth for the wire protocol between the **device** (ESP32) and
the **Android app**. Both the firmware (C++, `src/companion/companion.cpp`) and
the app (Kotlin) must mirror this document. Never add a message to one side
only. Bump `PROTOCOL_VERSION` on any schema change and keep both sides
backward-aware.

There are two independent paths over the same USB-CDC link, used at different
times and never mixed on the wire:

1. **Companion mode** (`companionModeUSB()`): the device leaves its normal UI
   loop and speaks the framed, JSON-envelope protocol described in most of
   this document (HELLO/CAPS/CMD/ACK/ERR/EVENT/DATA/SUB/PING/PONG).
2. **Remote-control mode**: the device keeps running its normal UI/menus, and
   the host instead drives it with plain, newline-terminated CLI text lines
   (`nav ...`, `display start`/`display stop`) through the existing Bruce CLI
   (`src/core/serial_commands`). This path is *not* length-prefixed JSON; see
   "Remote control (unframed)" below.

## Layering

```
feature logic            (wardriving, ir, rf, ... — never touches the wire)
  └─ protocol  (this doc: message kinds + JSON payloads)
       └─ framing   (length-prefixed frames on a byte stream)
            └─ transport  (USB-CDC  →  Wi-Fi TCP/WS  →  BLE)
```

- **Transport** is a byte pipe. Order of implementation: USB-CDC (primary,
  implemented today as `Serial`), Wi-Fi TCP/WebSocket, BLE (commands / light
  data only).
- **Framing**: each frame = `uint32 length` (little-endian, payload bytes) +
  `payload`. Simple, transport-agnostic, resync by reading length. Max frame
  size is `COMPANION_MAX_FRAME` (8192 bytes) on the device side; larger
  payloads are rejected. (COBS may be added later for self-synchronizing
  streams.)
- **Payload**: a JSON object (v0 baseline; parsed with ArduinoJson on the
  device). A compact binary encoding (MessagePack on the ESP via ArduinoJson,
  CBOR on Android) may replace JSON later behind the same envelope — bump the
  version when it does.

This layering applies only to companion mode. Remote-control mode bypasses
framing entirely and reuses the plain-text Bruce CLI transport.

## Envelope (every message, companion mode)

| key  | type   | meaning |
|------|--------|---------|
| `v`  | uint   | protocol version (currently 0) |
| `t`  | uint   | message kind (see below) |
| `id` | uint   | correlation id (request ↔ ACK/ERR); 0 for unsolicited events |
| `d`  | any    | kind-specific body |

## Message kinds (`t`)

| value | name    | direction     | body (`d`) |
|-------|---------|---------------|------------|
| 1 | `HELLO`        | phone → ESP   | `{app, appVersion}` (ESP replies with `CAPS`) |
| 2 | `CAPS`         | ESP → phone   | device capabilities (see below) |
| 3 | `CMD`          | phone → ESP   | `{cmd: string, args: {...}}` |
| 4 | `ACK`          | ESP → phone   | `{ok: bool, out?: string, size?: uint, ...}` |
| 5 | `ERR`          | ESP → phone   | `{code: uint, msg: string}` |
| 6 | `EVENT`        | ESP → phone   | `{ev: string, data: {...}}` discrete event |
| 7 | `DATA`         | ESP → phone   | `{stream: string, seq: uint, chunk: bytes}` raw stream |
| 8 | `SUB`          | phone → ESP   | `{stream: string, on: bool}` start/stop a stream |
| 9 | `PING`         | phone → ESP   | keepalive |
| 10 | `PONG`        | ESP → phone   | `{battery: int}` keepalive reply (battery kept fresh for periodic phone pings) |

Unknown message kinds get `ERR {code: 404, msg: "unknown message kind"}`;
malformed JSON gets `ERR {code: 400, msg: "bad json"}` (both with `id` 0 when
no envelope could be parsed). The device never crashes on a bad frame — it
replies with an error and keeps the loop running.

### `CAPS` body

Sent in reply to `HELLO`:

```json
{
  "board":      "<DEVICE_NAME build flag, e.g. lilygo-t-embed-cc1101>",
  "chip":       "esp32-s3 | esp32-c6 | esp32-c5 | esp32-c3 | esp32",
  "fw":         "<BRUCE_VERSION build flag, or \"dev\">",
  "proto":      0,
  "modules":    ["wifi", "ble", "rf", "ir"],
  "transports": ["usb"],
  "mtu":        8192,
  "battery":    87
}
```

`modules`/`transports` are currently a static list (TODO: detect modules at
runtime). `mtu` is `COMPANION_MAX_FRAME`. `battery` is the device's current
battery percentage (`getBattery()`), included so the app can show it right
after connecting without waiting for the first `PING`/`PONG`.

### `CMD` mapping to existing Bruce CLI

Brucia reuses the existing Bruce command dispatcher
(`src/core/serial_commands/cli.cpp` and the per-domain `*_commands.cpp`).
A `CMD` with `cmd:"wifi scan"` is routed through that dispatcher
(`routeCommand()`), with the CLI's text output captured into `ACK.d.out`
instead of going to `Serial` directly, so most device control is available
for free and the app can show the result. (A few legacy CLI commands still
print straight to `Serial` instead of `serialDevice` and are not captured;
the handshake messages — HELLO/CAPS/PING — are always clean.) The ACK's `ok`
reflects whatever the CLI dispatcher returned.

Before falling through to the generic CLI dispatcher, a small set of command
strings are intercepted and handled directly by the companion loop because
they need streaming replies or direct device control rather than captured CLI
text:

| `cmd` prefix | args | behavior |
|---|---|---|
| `fs_read <path>` | SD/LittleFS path | Streams the file as `DATA {stream:"file", seq, chunk}` frames (base64-encoded, up to ~768 raw bytes per chunk), then replies `ACK {ok:true, size: <total bytes>}`. `ERR 404` if storage or the file is unavailable. |
| `scr <0\|1>` | `0`=off, `1`=on | Sets display brightness to 0 (off) or the configured brightness (on). Replies `ACK {ok:true}`. |
| `led <0\|1>` | `0`=off, `1`=on | Sets the status LED off (black) or to the configured LED color. Replies `ACK {ok:true}`. |
| `wifi_deauth <bssid> <channel> <seconds>` | BSSID as `AA:BB:CC:DD:EE:FF` or 12 hex chars; channel 1-14; seconds 1-60 (clamped) | Runs a time-bounded deauth flood against the target AP (`runDeauthBounded`), then replies `ACK {ok:true, out:"deauth ... done"}`. `ERR 400` on a malformed BSSID or missing args. |
| `wifi_handshake <bssid> <channel> <seconds> [ssid]` | channel 1-14; seconds 5-120 (clamped, default 30) | Runs a time-bounded WPA handshake capture against the target AP (`capture_handshake_bounded`); on success the handshake is saved under `/BrucePCAP/handshakes/` on the device. Replies `ACK {ok, out}` (`ok:false` and an explanatory `out` if no handshake was captured). `ERR 400` on malformed args. |
| `ble_spam <seconds>` | seconds 1-60 (clamped, default 8) | Runs a time-bounded BLE advertising flood (`runBleSpamBounded`), then replies `ACK {ok:true}`. |

These companion-specific commands are documented as part of the protocol
(not the generic CLI) because the app must know their exact argument order
and reply shape; everything else is "whatever the CLI prints".

`scr`/`led` state auto-restores (screen/LED back on) if the phone goes
silent for more than 15s, and always on leaving companion mode, so the device
never gets stuck dark if the app disconnects mid-session.

## Streams (`SUB` + `EVENT`/`DATA`)

A stream is a named channel the phone turns on/off with
`SUB {stream: "<name>", on: bool}` (the device replies `ACK {ok:true}`, or
`ERR {code:404}` for an unknown stream name).

- **Structured streams** deliver `EVENT {ev: "<name>", data: {...}}` messages
  (`id` is always 0 — these are unsolicited).
- **Raw streams** deliver `DATA {stream, seq, chunk}` messages.

Current streams:

- `wifi.ap` — access-point observations for wardriving, one `EVENT` per AP:
  `ev = "wifi.ap"`, `data = {bssid, ssid, ch, rssi, enc, ts}` (`enc` is one of
  `open, wep, wpa, wpa2, wpa/wpa2, wpa2-ent, wpa3, wpa2/wpa3, unknown`; `ts` is
  device uptime in ms). The device runs back-to-back async Wi-Fi scans while this
  stream is on; the phone adds GPS/real time, dedups, maps and exports (Wigle
  CSV / KML). The device needs **no** onboard GPS.
- `ble.scan` — nearby BLE advertisements, one `EVENT` per device seen:
  `ev = "ble.dev"`, `data = {mac, name, rssi}`. The device runs an active
  NimBLE scan (2s windows) while this stream is on; the phone lists, dedups
  and analyzes results.
- `subghz.raw` — raw sub-GHz (433.92 MHz OOK) RMT edge timings for signal
  capture/replay tooling, one `EVENT` per received RMT symbol burst (≥5
  symbols): `ev = "subghz.raw"`, `data = {freq, timings: [uint, ...]}`
  (`freq` in Hz; `timings` is a flat array of pulse durations in ns, high and
  low alternating). All decoding happens on the phone; the device only
  captures and forwards raw timings.

`fs_read` (above) is not a `SUB`-gated stream: it is a one-shot `CMD` that
produces a `DATA {stream:"file", ...}` burst followed by a terminal `ACK`.

## Remote control (unframed)

Separate from companion mode: while the device runs its normal UI, the host
can send raw, newline-terminated CLI lines over the same USB-CDC link to the
*existing* Bruce CLI (`src/core/serial_commands/util_commands.cpp`) — no
length prefix, no JSON envelope. This lets a host (e.g. the Android app's
"Remote" screen) drive the on-device menus and mirror the screen without
putting the device into companion mode.

- `nav <next|prev|up|down|select|esc|nextpage|prevpage> [duration]` — injects
  a virtual button press (`sel` is also accepted as an alias for `select`).
  `duration` is an optional hold time in ms (default 1). The device prints a
  human-readable line such as `"Next Pressed"` / `"and Released after <n>ms"`
  followed by the current options list; this is plain text for a human/log,
  not a structured reply.
- `display start` — starts the TFT mirror: the device begins logging every
  draw call and streams them as binary draw-op packets (format below) over
  the same serial line, async, until stopped.
- `display stop` — stops the mirror stream.
- `display status` — prints whether mirroring is active.
- `display info` — prints `<width>x<height>x<rotation>` as plain text.
- `display dump` — prints the current log buffer as a hex dump (debugging
  aid, not used by the live mirror).

### TFT mirror packet format

Implemented in `include/tftLogger.h` / `src/core/tftLogger/tftLogger.cpp` on
the device and mirrored by `android/app/src/main/java/app/brucia/remote/TftProtocol.kt`
on the phone. Each draw operation the firmware performs while mirroring is
active is captured and written to serial as one binary packet:

```
0xAA  [size:u8]  [fn:u8]  [arg0:u16BE] [arg1:u16BE] ...
```

- `0xAA` — fixed header byte (`LOG_PACKET_HEADER`). A reader resyncs by
  scanning for this byte.
- `size` — total packet length in bytes, including the header and size byte
  themselves (so a reader with `size < 3` or an incomplete tail should
  discard/wait).
- `fn` — one of the `tftFuncs` opcodes below.
- args — a run of big-endian `uint16` values (packed MSB, LSB), one per
  geometric parameter. Colors are `RGB565` and must be converted to RGB888
  for rendering.

`tftFuncs` opcodes (`include/tftLogger.h`; **order is fixed, never renumber —
only append**):

| value | name | args (u16 each, in order) |
|---|---|---|
| 0 | `FILLSCREEN` | `color` |
| 1 | `DRAWRECT` | `x, y, w, h, color` |
| 2 | `FILLRECT` | `x, y, w, h, color` |
| 3 | `DRAWROUNDRECT` | `x, y, w, h, r, color` |
| 4 | `FILLROUNDRECT` | `x, y, w, h, r, color` |
| 5 | `DRAWCIRCLE` | `x, y, r, color` |
| 6 | `FILLCIRCLE` | `x, y, r, color` |
| 7 | `DRAWTRIANGLE` | `x1, y1, x2, y2, x3, y3, color` |
| 8 | `FILLTRIANGLE` | `x1, y1, x2, y2, x3, y3, color` |
| 9 | `DRAWELIPSE` | `x, y, rx, ry, color` |
| 10 | `FILLELIPSE` | `x, y, rx, ry, color` |
| 11 | `DRAWLINE` | `x1, y1, x2, y2, color` |
| 12 | `DRAWARC` | `x, y, rOuter, rInner, startAngle, endAngle, color` |
| 13 | `DRAWWIDELINE` | `ax, ay, bx, by, width, color` |
| 14 | `DRAWCENTRESTRING` | `x, y, size, fg, bg` then raw UTF-8 string bytes (no length prefix; runs to end of packet) |
| 15 | `DRAWRIGHTSTRING` | same as `DRAWCENTRESTRING` |
| 16 | `DRAWSTRING` | same as `DRAWCENTRESTRING` |
| 17 | `PRINT` | same as `DRAWCENTRESTRING` (cursor position instead of explicit x,y) |
| 18 | `DRAWIMAGE` | `x, y, center, ms, imageSlot:u8` then the image's file path as raw bytes (no fixed-width fields; the phone does not receive image pixel data, only enough to know an image was drawn and where/from what path) |
| 19 | `DRAWPIXEL` | `x, y, color` |
| 20 | `DRAWFASTVLINE` | `x, y, h, color` |
| 21 | `DRAWFASTHLINE` | `x, y, w, color` |
| 99 | `SCREEN_INFO` | `width, height` (u16) then `rotation` as a single **byte** (not u16) — sent once right after `display start` (and first in a `display dump`) so the phone knows the canvas size/orientation before any draw ops arrive |

For the four text ops (14/16/17: centre/right/draw/print), the packet layout
after the header is `x, y, size, fg, bg` (5 × u16 = 10 bytes) immediately
followed by the string's raw UTF-8 bytes, with no explicit length — the
string's length is `size - 3 (header) - 10 (fixed args)`.

The device only emits a packet when the drawn content actually changed
(`pushLogIfUnique`), so the mirror is a diff stream, not a full-frame stream;
a renderer should keep its own framebuffer and apply ops incrementally,
starting from the `SCREEN_INFO` packet to size that framebuffer.

## Versioning

`PROTOCOL_VERSION = 0` while unstable. Both sides send `v`; a receiver that
gets a higher `v` than it knows should still parse the envelope and reply
`ERR` with a clear message for unknown kinds, never crash. The remote-control
(unframed) path has no version field — it is the pre-existing, independent
Bruce CLI text protocol and is versioned implicitly alongside the CLI itself.
