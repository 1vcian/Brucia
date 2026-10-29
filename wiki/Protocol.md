# Protocol

The Brucia companion protocol (v0) is the single source of truth for the wire format
between the **device** (ESP32, C++) and the **Android app** (Kotlin). Both sides must
mirror it exactly. Never add a message to one side only. Bump `PROTOCOL_VERSION` on
any schema change and keep both sides backward-aware.

The authoritative document is `proto/PROTOCOL.md` in the repository; this page
summarizes it. The firmware implementation is `src/companion/companion.cpp`.

There are two independent paths over the same USB-CDC link, never mixed on the wire:

1. **Companion mode** (`companionModeUSB()`) - the framed JSON-envelope protocol
   (HELLO / CAPS / CMD / ACK / ERR / EVENT / DATA / SUB / PING / PONG) described here.
2. **Remote-control mode** - the device keeps its normal UI and the host drives it with
   plain newline-terminated CLI lines (`nav ...`, `display start`/`stop`). This path is
   not length-prefixed JSON; see [[Architecture]].

## Layering

```
feature logic            (wardriving, crack, ir, rf, ... - never touches the wire)
  +- protocol  (message kinds + payloads)
       +- framing   (length-prefixed frames on a byte stream)
            +- transport  (USB-CDC  ->  Wi-Fi TCP/WS  ->  BLE)
```

- **Transport** is a byte pipe. Order of implementation: USB-CDC (primary), Wi-Fi
  TCP/WebSocket, then BLE (commands / light data only).
- **Framing**: each frame is a `uint32 length` (little-endian, payload byte count)
  followed by the `payload`. Max frame is `COMPANION_MAX_FRAME` (8192 bytes); larger
  payloads are rejected. Resyncs by reading the length.
- **Payload**: a JSON object in the v0 baseline (ArduinoJson on the device). A compact
  binary encoding may replace JSON later behind the same envelope; the version is
  bumped when it does.

## Envelope (every message)

| key  | type | meaning |
|------|------|---------|
| `v`  | uint | protocol version (currently 0) |
| `t`  | uint | message kind (see below) |
| `id` | uint | correlation id (request <-> ACK/ERR); 0 for unsolicited events |
| `d`  | any  | kind-specific body |

## Message kinds (`t`)

| value | name | direction | body (`d`) |
|-------|------|-----------|------------|
| 1 | `HELLO` | phone -> ESP | `{app, appVersion}` (ESP replies `CAPS`) |
| 2 | `CAPS`  | ESP -> phone | device capabilities (see below) |
| 3 | `CMD`   | phone -> ESP | `{cmd: string, args?: {...}}` |
| 4 | `ACK`   | ESP -> phone | `{ok: bool, out?: string, size?: uint, ...}` |
| 5 | `ERR`   | ESP -> phone | `{code: uint, msg: string}` |
| 6 | `EVENT` | ESP -> phone | `{ev: string, data: {...}}` discrete event |
| 7 | `DATA`  | ESP -> phone | `{stream: string, seq: uint, chunk: bytes}` raw stream |
| 8 | `SUB`   | phone -> ESP | `{stream: string, on: bool, ...}` start/stop a stream |
| 9 | `PING`  | phone -> ESP | keepalive |
| 10 | `PONG` | ESP -> phone | `{battery: int}` keepalive reply |

Unknown kinds get `ERR {code: 404, msg: "unknown message kind"}`; malformed JSON gets
`ERR {code: 400, msg: "bad json"}`. The device never crashes on a bad frame.

## CAPS body

Sent in reply to `HELLO`. CAPS now carries a real, per-peripheral `caps{}` object the
app uses to show/hide pages (runtime capability detection):

```json
{
  "board":   "<DEVICE_NAME build flag, e.g. lilygo-t-embed-cc1101>",
  "chip":    "esp32-s3 | esp32-c6 | esp32-c5 | esp32-c3 | esp32",
  "fw":      "<BRUCE_VERSION build flag, or \"dev\">",
  "proto":   0,
  "caps": {
    "wifi":    true,
    "ble":     true,
    "cc1101":  true,
    "nfc":     false,
    "nrf24":   false,
    "fm":      false,
    "ir":      true,
    "mic":     false,
    "speaker": false
  },
  "modules":    ["wifi", "ble", "rf", "ir"],
  "transports": ["usb"],
  "mtu":        8192,
  "battery":    87
}
```

- `caps{}` is the authoritative, per-peripheral capability map. It is computed by a
  hybrid of compile-time board macros (for fixed onboard peripherals: `cc1101`, `ir`,
  `mic`, `speaker`, and `wifi`/`ble` which are always present on these ESP32 targets)
  and quick runtime probes for the optional/uncertain ones (`nfc` via a PN532 I2C
  probe, `nrf24` via an SPI presence probe, `fm` via a Si4713 I2C probe at 0x63).
- `modules` is the legacy coarse list, kept for backward compatibility with older app
  builds; new code reads `caps{}`.
- `transports` is what this link supports; `mtu` is `COMPANION_MAX_FRAME`.
- `battery` (also in every `PONG`) is the live battery percentage.

## CMD mapping to the Bruce CLI

A small set of companion-specific commands (below) are intercepted first because they
need streaming replies or direct device control. Everything else falls through to the
existing Bruce CLI dispatcher (`routeCommand()` -> `src/core/serial_commands/`), with
the CLI's text output captured into `ACK.d.out`. So most device control is available
for free; companion commands are added as new entries, not a parallel system.

## Companion commands (intercepted before the CLI)

All dual-use commands are headless, time-bounded, and for authorized testing only.

### Storage, lite mode, clock

| `cmd` | behavior |
|---|---|
| `fs_read <path>` | Stream a file as `DATA {stream:"file", seq, chunk}` base64 frames, then `ACK {ok, size}`. `ERR 404` if unavailable. |
| `scr <0\|1>` | Display off / on (Lite mode). Auto-restores on silence/disconnect. |
| `led <0\|1>` | Status LED off / on (Lite mode). Auto-restores on silence/disconnect. |
| `clock_get` | Read the device RTC. |
| `clock_set <epoch>` | Set the device RTC. |

### Wi-Fi

| `cmd` | behavior |
|---|---|
| `wifi_deauth <bssid> <channel> <seconds>` | Time-bounded deauth flood of a target AP. |
| `wifi_handshake <bssid> <channel> <seconds> [ssid]` | Time-bounded 4-way handshake capture, saved to `/BrucePCAP/handshakes/`. |
| `wifi_pmkid <bssid> <channel> <seconds>` | Time-bounded PMKID capture. |
| `wifi_beacon_spam <secs> <ssid1\nssid2...>` | Beacon spam from a phone-supplied SSID list. |
| `arp_spoof <ssid> <pass> <target_ip> <gateway_ip> <secs>` | ARP MITM: join the Wi-Fi and poison target <-> gateway. |

### Sub-GHz (CC1101)

| `cmd` | behavior |
|---|---|
| `subghz_replay <freqKHz> <secs> [repeats]` | Record then replay a raw signal. |
| `subghz_tx_raw <freqKHz> <te> <csv durations>` | Transmit an explicit edge list. |
| `subghz_jammer <freqKHz> <secs>` | Bounded noise jammer. |
| `subghz_config <freqKHz> [modulation] [bandwidth] [datarate]` | Set RX/TX parameters. |

### NFC / RFID (PN532)

| `cmd` | behavior |
|---|---|
| `mifare_keycheck <hexKey1,hexKey2,...>` | MIFARE Classic key-dictionary check. |
| `nfc_ndef_push <url\|text\|contact> <payload>` | Emulate an NDEF tag to a phone. |
| `nfc_emv_read` | Masked PAN + expiry + scheme (dropped on lite builds). |
| `nfc_write_uid <hexuid>` | Write the UID on a magic / Gen1A card (4, 7 or 10 bytes). |
| `nfc_amiibo <emulate\|dump>` | Reports unsupported (external dongle only). |

### IR

| `cmd` | behavior |
|---|---|
| `ir_tvbgone [na\|eu]` | TV-B-Gone power-off blast (region optional). |
| `ir_rx` | Learn / receive one IR code (~10s window). |
| `ir_tx_raw <khz> <csv durations>` | Replay a raw IR timing list. |

### BLE

| `cmd` | behavior |
|---|---|
| `ble_spam <secs>` | Apple ProximityPair spam (1..60, clamped). |
| `ble_fastpair_spam <secs>` | Google Fast Pair (Android) spam. |
| `ble_spam_platform <ios\|android\|windows\|samsung\|all> <secs>` | Unified spam by platform (Swift Pair for windows, Samsung, or all cycled). |
| `ble_gatt_connect <mac>` | Discover every service + characteristic; streams `EVENT ev="ble.gatt" {svc, chr, props}`. |
| `ble_gatt_read <mac> <char_uuid>` | Connect and read one characteristic. |
| `ble_badble <ducky script text>` | Run a Ducky/BadUSB script over BLE HID (dropped on lite builds). |

### NRF24 (optional add-on)

| `cmd` | behavior |
|---|---|
| `nrf24_scan <secs>` | 2.4 GHz channel-activity sweep; streams `EVENT ev="nrf24.scan" {ch, level}`. |
| `nrf24_sniff <secs>` | Frame sniff; streams `EVENT ev="nrf24.dev" {addr, ch, rssi}`. |
| `nrf24_jammer <secs>` | Bounded 2.4 GHz jammer. |
| `nrf24_mousejack <secs> [inject text]` | Scan + optional injection; streams `EVENT ev="nrf24.mj" {addr, ch, type}` (dropped on lite builds). |

### Scripts, audio, ESP-NOW, FM

| `cmd` | behavior |
|---|---|
| `script_run <js source>` | Run a BruceJS script headless and return its output (dropped on lite builds). |
| `ibutton_read` | Read a 1-Wire iButton (RW1990). |
| `ibutton_write <idhex>` | Emulate / write an iButton id (16 hex chars). |
| `speaker_tone <freqHz> <ms>` | Play a tone on the onboard I2S speaker. |
| `speaker_play <path>` | Play an audio file on the onboard I2S speaker. |
| `mic_record <secs 1..30>` | Record the onboard microphone to a WAV on SD. |
| `espnow_send <message>` | One-shot ESP-NOW broadcast (dropped on lite builds). |
| `fm_tx <freqKHz> [rds_text]` / `fm_stop` | FM carrier broadcast (Si4713; `ok:false` on boards without one). |
| `fm_audio_begin <freqKHz> <phone\|mic>` / `fm_audio_data <base64 pcm>` / `fm_audio_stop` | FM audio broadcast over the Si4713 I2S input (capability-gated). |

Commands for absent hardware reply `ERR 501` ("no CC1101/microphone/..."), or `ok:false`
with an explanatory `out`, so the app can gray out unsupported features.

## Streams (SUB + EVENT / DATA)

A stream is a named channel the phone turns on/off with
`SUB {stream: "<name>", on: bool}` (the device replies `ACK`, or `ERR 404` for an
unknown name). Structured streams deliver `EVENT {ev, data}`; raw streams deliver
`DATA {stream, seq, chunk}`.

Streams sharing the Wi-Fi radio (`wifi.ap`, `wifi.sta`, `wifi.probe`, `wifi.monitor`)
are mutually exclusive, as are the two CC1101 streams (`subghz.raw`, `subghz.spectrum`).

| stream | subscribe args | emits |
|---|---|---|
| `wifi.ap` | - | `EVENT ev="wifi.ap" {bssid, ssid, ch, rssi, enc, ts}` (wardrive; back-to-back async scans) |
| `wifi.sta` | - | `EVENT ev="wifi.sta" {mac, bssid, rssi, ch}` (station/client sniffer) |
| `wifi.probe` | - | `EVENT ev="wifi.probe" {mac, ssid, rssi, ch}` (probe-request profiler) |
| `wifi.monitor` | `channel` (0 = hop), `bssid` (filter) | `EVENT ev="wifi.monitor" {ts, ch, rssi, len, data}` (full 802.11, `data` is base64; pcap/Wireshark) |
| `ble.scan` | - | `EVENT ev="ble.dev" {mac, name, rssi}` (NimBLE active scan) |
| `subghz.raw` | - | `EVENT ev="subghz.raw" {freq, timings[]}` (RMT edge timings; decode on phone) |
| `subghz.spectrum` | `f_start`, `f_end`, `step` (MHz) | `EVENT ev="subghz.spectrum" {...}` (CC1101 RSSI waterfall) |
| `mic.spectrum` | - | `EVENT ev="mic.spectrum" {bins[16], level}` (onboard mic FFT) |

Command-driven event streams (not `SUB`-gated) use the same `EVENT` channel while a
command runs: `ble.gatt`, `nrf24.scan`, `nrf24.dev`, `nrf24.mj` (see the command tables
above). `fs_read` produces a `DATA {stream:"file", ...}` burst then a terminal `ACK`.

## Versioning

`PROTOCOL_VERSION = 0` while unstable. Both sides send `v`. A receiver that gets a
higher `v` than it knows should still parse the envelope and reply `ERR` for unknown
kinds - never crash. The remote-control (unframed) path has no version field; it is the
pre-existing Bruce CLI text protocol, versioned with the CLI.
