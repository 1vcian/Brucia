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
  `payload`. Transport-agnostic; the length prefix is not a self-synchronizing marker. Max frame
  size is `COMPANION_MAX_FRAME` (8192 bytes) on the device side; larger
  payloads are rejected. The largest frame a host may *send* is CAPS `rx_max`
  (see [Inbound frame capacity](#inbound-frame-capacity-rx_max)); hosts resync on
  device frames as described in [Resynchronisation](#resynchronisation-device--host).
  (COBS may be added later for self-synchronizing streams.)
  On the device the whole emission of one frame (its length prefix, payload and flush) is
  serialized by a recursive mutex in `writeFrame()` (`src/companion/protocol.cpp`), so a stream
  EVENT emitted from another task can never interleave its bytes with a response and desync the
  length prefix. Each frame is also written in full: `Serial.write` may accept only part of a
  buffer under host backpressure (HWCDC/USBCDC honour the Arduino short-write contract), so the
  device loops on the return value (`writeAll`, bounded) rather than truncating the prefix or
  payload. Every device frame goes through that one writer.
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

The device always serializes `v` first (`beginEnvelope()` in `src/companion/protocol.cpp`;
ArduinoJson keeps insertion order), so every device frame payload starts with the bytes
`{"v":`. Hosts rely on this for resynchronisation; `tools/test_firmware_serial_rx.py` checks it
for every frame the firmware builds. The app also writes `v` first; the firmware does not
depend on key order.

Android send helpers return `Result`: a frame over the negotiated receive limit is rejected
locally, no bytes are written and no sent-frame counter is incremented. USB write failures
propagate from the transport and become local command failures immediately; they do not wait
for a device reply. HELLO/PING failures end that companion session visibly. Rejected frame
sizes leave a healthy session usable. This does not change the wire envelope.

### Resynchronisation (device → host)

In companion mode the USB serial carries frames only, but text can still reach it: an unguarded
module `Serial.print`, an ESP-IDF log line from another task, boot output. The first hardware
run lost the link when `cc1101 Connection OK` arrived between frames (`cc11` read as a length).
Host readers therefore do not drop the link on a bad header. At a frame boundary they accept a
frame start only when the 4-byte LE length is in `5..8192` **and** the payload begins with
`{"v":`. Otherwise they skip forward to the next position where both hold, count the skipped
bytes as noise and continue (the app shows them in Diagnostics as "noise bytes"; the acceptance
runner records them as a warning with a sample). Bytes at the end of the buffer that could
still start a frame (at most 8) are kept until more data arrives. A frame with a valid header
and prefix whose JSON does not parse is skipped by its length and counted as malformed, as
before. Text inserted inside a frame destroys that one frame; the reader recovers on the next.
No framing or version change is involved: ASCII text cannot form a plausible length (it would
need `0x00` bytes), and older firmware already writes `v` first.

The device→host direction is not the only defence: in companion mode the firmware guards
module debug prints on companion paths with `if (!g_companionActive)` (enforced by
`tools/test_companion_print_hygiene.py`) and drops ESP-IDF/arduino log output
(`esp_log_set_vprintf`) until companion mode exits. Bare `printf`/stdout is muted as well, and the
ROM printf (`ets_printf`/`esp_rom_printf`, where Arduino `log_x` ends): on the ESP32-S3 by clearing
the ROM's USB-Serial-JTAG copy flag, on the ESP32 by replacing the ROM's two putc hooks (read at
their ROM addresses, verified by read-back, restored on exit; if the read-back does not match, ROM
printf stays as before). Other targets (ESP32-C5) and text a Bruce CLI command routed through `CMD`
writes outside its capture can still reach the wire; the resync rule covers them.

## Message kinds (`t`)

| value | name    | direction     | body (`d`) |
|-------|---------|---------------|------------|
| 1 | `HELLO`        | phone → ESP   | `{app, appVersion}` (ESP replies with `CAPS`) |
| 2 | `CAPS`         | ESP → phone   | device capabilities (see below) |
| 3 | `CMD`          | phone → ESP   | `{cmd: string, ...command-specific fields}`; most CLI arguments are inside `cmd` |
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

**Behavior during a blocking operation.** While the device is running a long/blocking
command (e.g. `responder`, `karma`, `brucegotchi`, a `mifare_keycheck` batch), the main
loop is paused but the device still drains one frame per poll so the link stays
responsive: a `PING` is answered with `PONG` as usual, and any **other** `CMD` that is
not a stop (`stop` / `*_stop`), and any `SUB`, is replied to immediately with `ERR {code:
409, msg: "device busy"}` carrying that frame's `id`, so the phone's await fails fast instead
of blocking until its own timeout (firmware before 2026-11-06 dropped a `SUB` unanswered). Frames are not queued or replayed; the phone resends the
rejected command after the operation ends. A `stop` / `*_stop` `CMD` is consumed to end
the operation and is **not** itself ACKed (the operation's own final reply follows). This
is why features serialize device commands and send their stop frames on a direct channel.

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
  "rx_max":     8192,
  "rx_buf":     8448,
  "battery":    87,
  "features":   {"verified_files": true, "transactional_uploads": true},
  "build": {
    "commit": "aa98dfb7c0de", "dirty": false,
    "id": "0.1.3+lilygo-t-embed-cc1101.aa98dfb7c0de",
    "time": "2026-11-03T10:22:01Z", "time_src": "commit",
    "env": "lilygo-t-embed-cc1101"
  },
  "caps": {
    "wifi": true, "ble": true, "cc1101": true, "nfc": true,
    "ir": true, "mic": true, "speaker": true,
    "nrf24": false, "fm": false, "lora": false, "eth": false
  },
  "evidence": {
    "cc1101": {"level": "probed", "probe": "present", "detail": "SPI PARTNUM 0x00 VERSION 0x14"},
    "nrf24": {"level": "configured", "probe": "absent", "detail": "SPI SETUP_AW readback invalid",
              "conflict": [{"gpio": 44, "role": "cs", "with": "eth", "with_role": "cs"},
                           {"gpio": 43, "role": "ce", "with": "eth", "with_role": "int"}]},
    "lora": {"level": "none"},
    "ibutton": {"level": "compiled", "detail": "1-Wire answers only with a key present; not probed"}
  }
}
```

`caps` is the per-module availability map. The app enables a module-dependent
feature only for a literal JSON `true`; omitted, null or malformed flags leave it
unavailable. Firmware without `caps` needs an update before these tools are enabled.
Fixed onboard interfaces use board definitions; addressable radios/readers are
probed at companion startup and cached to avoid disturbing active shared buses.
Changing attached hardware requires restarting the device. Availability does not
prove end-to-end operation: see [hardware capabilities](../docs/HARDWARE_CAPABILITIES.md).

`features` describes software protocol support, separately from physical modules.
Literal `features.verified_files: true` advertises correlated file DATA IDs, a
terminal size/CRC32 ACK and the legacy write grammar below. Managed transfers
require this flag. Uploads additionally require literal
`features.transactional_uploads: true`; absent/malformed support produces an
update-firmware error, never a fallback to truncate/append. Older verified-file
firmware remains usable for reads. Literal `features.capture_descriptor: true` advertises the
capture artifact descriptor on `wifi_handshake` / `wifi_pmkid` terminal ACKs (see "Capture artifact
descriptor" below). These are additive protocol v0 extensions; they do not
assert hardware health or that all firmware commands are implemented.

Literal `features.diag: true` advertises the `diag` command (recovery diagnostics, below).

LITE firmware builds (`LITE_VERSION`: the `LAUNCHER_*` envs and `m5stack-core4mb`) compile
some companion endpoint groups out for app-slot headroom; Bruce's own on-device features and
CLI are not affected. They are the only flags where a literal `false` carries meaning: it
reports the group as absent from this firmware, while an absent key (full builds, older
firmware) means the group is present. Each command of a dropped group answers
`ERR 501 "<name> not available in this (lite) build"`:

| `features` key | LITE value | commands |
|---|---|---|
| `mifare_keycheck` | `false` | `mifare_keycheck` (`mifare_keycheck_report` / `mifare_keycheck_stop` are not sent) |
| `ir_cmds` | `false` | `ir_tvbgone`, `ir_rx`, `ir_tx_raw`, `ir_jammer`, `ir_power_spam`; `caps.ir` stays, Bruce's `ir rx` / `ir tx` / `ir tx_raw` CLI still works |
| `ble_gatt` | `false` | `ble_gatt_connect`, `ble_gatt_read` |
| `diag` | `false` | `diag` (the link counters are not kept either) |

The NRF24 companion commands (`nrf24_*`) are dropped too; LITE reports them through
`caps.nrf24: false` and `evidence.nrf24` level `none` instead of a features key.

`build` is optional release provenance (additive, protocol v0). It does not repeat
`fw`, `proto` or `features`. Values are generated at build time by
`pre_build_provenance.py` (`tools/firmware_provenance.py`) into the gitignored
`include/brucia_build_info.h`:

- `commit`: 12-character Git SHA of `HEAD`, or `"unknown"` when the source tree has
  no Git metadata of its own (release tarball, Git missing, or a tree nested in an
  unrelated checkout). The build never fails for missing provenance.
- `dirty`: `true` when `git status --porcelain` (tracked and untracked, non-ignored
  files) was non-empty at build time; omitted when Git state is unknown.
- `id`: `BRUCIA_BUILD_ID` from the build environment when set (for example a CI run),
  otherwise `<VERSION>+<env>.<commit>[.dirty]`.
- `time` / `time_src`: UTC ISO 8601 from `SOURCE_DATE_EPOCH` (`source_date_epoch`),
  otherwise the `HEAD` commit time (`commit`), otherwise `"unknown"`. The wall clock is
  never used, so the same commit yields the same header; a dirty build carries its
  commit time, not the time of the uncommitted edits.
- `env`: the PlatformIO environment (`board` remains the display name).

All values are limited to `[A-Za-z0-9._+:-]`, at most 64 characters. The app accepts
only such strings and a literal boolean `dirty`, shows them on the device card, and
writes them into exported logs and session reports. Firmware without `build` remains
fully usable; the app shows "Build: not reported by this firmware". Provenance
identifies the source and build configuration, not a binary hash.

#### Capability evidence (`evidence`)

`evidence` is optional (additive, protocol v0). It explains `caps` per module; it does
not replace it. Each key is a module name (the `caps` names plus `sd`) and each value is:

- `level` (required): the highest positively established evidence, in increasing order:
  - `none`: no support built for this board environment.
  - `compiled`: support is built for this board environment (for `wifi`/`ble`, the SoC
    radio; its driver starts per operation).
  - `configured`: pins/bus are assigned by the board profile or the saved pin config. A
    GPIO definition is never reported as a probe.
  - `probed`: an electronic check ran this session and a bus transaction saw the chip.
  - `initialized`: the driver was brought up successfully this session (its bring-up
    includes chip identification).
- `probe` (optional): `present`, `absent` (the check ran and no chip answered) or `busy`
  (not probed because a bus might be owned by an active stream). Omitted when the module
  has no electronic check, its pins are not configured, or the check was skipped.
  `probed`/`initialized` always come with `present`; `absent`/`busy` stay at `configured`.
- `detail` (optional): short printable text, for example the register values read.
- `conflict` (optional): static pin findings from the active pin configuration, no bus
  traffic: `{gpio, role, with, with_role}` for each GPIO this module shares with another
  assignment. SCK/MISO/MOSI and SDA/SCL lines shared with the same role are one bus and
  are not conflicts; a shared CS, CE/IRQ/reset line or I2S/IR/1-Wire pin is. A conflict
  can be intentional board wiring (alternatives that cannot be used at the same time).

"Physically tested" is not a level: the firmware cannot know it. Acceptance results
belong to the hardware checklist, not to CAPS.

Probes run at most once per boot, in a serialized window at companion entry before the
service loop and any stream, and are cached. They are bounded, send no radio packets
or tag commands, and leave the peripheral idle (RC522 exception in the table). A probe that has not run when that window is
closed reports `busy` without touching the bus and is retried at the next companion
entry. Current checks:

| Module | Check | Level when found |
|---|---|---|
| `cc1101` | SPI PARTNUM/VERSION status-register read (no strobes, no writes) | `probed` |
| `nfc` | PN532: reset/wake + GetFirmwareVersion (RF field stays off) | `probed` |
| `nfc` | RC522/RFID2: driver init + version register; the MFRC522 init enables the 13.56 MHz carrier for a few ms and the probe switches it off. ST25R3916: RFAL init, powered down on release | `initialized` |
| `nrf24` | RF24 begin + SETUP_AW readback, then power down (CE low); skipped when a W5500 already answered on the same CS | `probed` |
| `eth` | W5500 VERSIONR read (read-only frame), run before the NRF24 probe | `probed` |
| `fm` | I2C address ACK at 0x63 only (no chip ID) | `probed` |
| `lora` | RadioLib begin (reset + version check + configuration), then sleep; no RX/TX | `initialized` |
| `sd` | Live mount state (`mounted`); not mounted is not reported as absent | `probed` |
| `ir`, `mic`, `speaker`, `ibutton` | None: GPIO/board profile only | `configured` |

The app parses `evidence` when present (absent = older firmware, nothing claimed),
shows it on the Device page and uses `probe: "absent"` to explain why a tool is
unavailable. Only a proved absence clears a `caps` flag; every other value leaves the
reported `caps` gating unchanged. Unknown levels/modules or malformed entries are ignored.

`modules` is a coarse compatibility list; it must not replace `caps` when gating
tools. `transports` currently contains only `usb`. `mtu` is
`COMPANION_MAX_FRAME`. `rx_max` (additive) is the largest frame payload the host may send and
`rx_buf` the serial RX buffer the device allocated; see
[Inbound frame capacity](#inbound-frame-capacity-rx_max). `battery` is the device's current
battery percentage (`getBattery()`), included so the app can show it right
after connecting without waiting for the first `PING`/`PONG`.

The Android command preflight uses explicit CAPS flags for native hardware commands
and its known radio CLI aliases, including commands submitted through macros/console.
FM with the device microphone requires both `fm` and `mic`. Cleanup commands remain
permitted without capability flags. This is not a sandbox for unknown CLI commands
or script contents; firmware remains responsible for validating those operations.

### Inbound frame capacity (`rx_max`)

`rx_max` (CAPS, additive, integer `1..8192`) is the largest frame **payload** (bytes after the
4-byte length) the device's serial RX buffer holds whole. A host must not send larger frames:
it sizes chunked transfers (`fs_upload_chunk`, `spkr_pcm_data`, `fm_audio_data`) to fit and
refuses larger single commands locally instead of losing them on the wire.

Why: every `Serial` class in the pinned arduino-esp32 core (3.3.9) drops inbound bytes when its
RX buffer is full and nothing reads it, and the companion loop is single-threaded (display
redraws, storage and handlers keep it out of `readFrame()` for tens of milliseconds):

| Board class | `Serial` | Overflow behaviour (pinned core) |
|---|---|---|
| ESP32-S3/C5 USB-Serial-JTAG (`ARDUINO_USB_MODE=1`: T-Embed, Cardputer, ...) | `HWCDC` | `HWCDC.cpp` RX ISR (`hw_cdc_isr_handler`, `USB_SERIAL_JTAG_INTR_SERIAL_OUT_RECV_PKT`) reads the 64-byte packet from the FIFO and `xQueueSendFromISR`s byte by byte; on a full queue it stops and the rest of the packet is lost. The FIFO is always drained, so USB never back-pressures the host. |
| TinyUSB CDC (`ARDUINO_USB_MODE=0`; no board in this repo) | `USBCDC` | `USBCDC.cpp` `_onRX()` waits 10 ticks per byte on a full queue, then drops the rest and posts `ARDUINO_USB_CDC_RX_OVERFLOW_EVENT`. |
| Classic ESP32 behind a USB-UART bridge (Core, CYD, ...) | `HardwareSerial` | `esp32-hal-uart.c` `uartBegin()` installs the IDF UART driver with a ring buffer of `setRxBufferSize()` bytes in front of the 128-byte hardware FIFO; when both are full the IDF driver reports `UART_BUFFER_FULL`/`UART_FIFO_OVF` (`HardwareSerial.cpp` `_uartEventTask`) and the overflowing bytes are lost (IDF `uart.c` is not vendored in the package). No flow control on these boards. At 115200 baud bytes arrive at ~11.5 per ms, so a stall loses less, but a long one still overflows. |

Runtime resizing is not safe: `HWCDC::setRxBufferSize()` deletes the queue the ISR may be
writing, `USBCDC` has the same race with the TinyUSB task, and `HardwareSerial` refuses after
`begin()`. The firmware therefore sizes the buffer once in `setup()`, before `Serial.begin()`
(`companionSerialRxSetup()`, `src/companion/serial_rx.h`). This applies to Bruce's serial CLI
too; a larger buffer only means fewer dropped bytes.

| Class | RX buffer | Extra internal heap vs before | `rx_max` |
|---|---|---|---|
| `BOARD_HAS_PSRAM` | 8448 B (was `SAFE_STACK_BUFFER_SIZE / 4` = 1024) | +7424 B | 8192 |
| no PSRAM | 2048 B (was 256) | +1792 B | 1792 |
| allocation failed, Bruce fallback | 1024 / 256 B | 0 | 768 / 192 |

`rx_max = rx_buf - min(256, rx_buf / 4)`, capped at 8192: the headroom holds the 4-byte prefix
and a small frame (PING, `stop`, `SUB` off) that may arrive while a large one is queued. The
HWCDC/USBCDC queue is a FreeRTOS queue of 1-byte items: one internal-RAM block of `rx_buf` bytes
plus the queue header (`pvPortMalloc` = `heap_caps_malloc(MALLOC_CAP_INTERNAL |
MALLOC_CAP_8BIT)` in the pinned IDF `libfreertos.a`), so the extra cost is exactly the size
difference. A board env may override the size with `-DCOMPANION_SERIAL_RX_BUFFER=<bytes>`.

Firmware without `rx_max` is assumed to hold `LEGACY_RX_MAX` = 768 bytes (the 1024-byte queue
of PSRAM boards, the class measured on hardware, minus the same headroom). On older non-PSRAM
firmware (256-byte queue) even that is not guaranteed; update the firmware. The app keeps its
previous PCM frame sizes for firmware without `rx_max` (speaker PCM was verified on hardware
with them) and only sizes uploads by the legacy value.

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
| `fs_read <path>` | SD/LittleFS path; spaces allowed | DATA frames echo the CMD request ID and carry `{stream:"file", seq, chunk}` (Base64, up to 768 raw bytes per chunk). Sequence starts at zero. Completion is `ACK {ok:true, size, crc32}`; CRC32 is the unsigned IEEE CRC-32 of all raw bytes. Empty files return size/CRC zero. Maximum size is 16 MiB; transfer time budget is 120 seconds. Premature EOF/read errors fail, not succeed with partial content. |
| `fs_write <path> <base64>` | Last space separates path from Base64 | Legacy direct truncate/write. Internal path spaces and empty Base64 are supported. Invalid Base64 is rejected before opening the destination. Paths follow the canonical upload rules below (relative paths gain `/`); reserved names and writes during an active upload are rejected. ACK `{ok:true,size}` counts bytes written by this command. |
| `fs_write_append <path> <base64>` | Same grammar | Appends one chunk and ACKs its byte count. Partial writes and directory destinations fail. Chunked writes are not an atomic file replacement; a failed upload can leave a partial destination and must not be reported as saved. |
| `fs_upload_begin <token> <size> <crc32> <path>` | Canonical absolute path; internal spaces allowed | Recover prior transaction, create journal and empty staging file; ACK `{ok:true,token,size:0}`. Destination untouched. |
| `fs_upload_chunk <token> <offset> <base64>` | Exact sequential offset, 1–3072 raw bytes | Append to staging; ACK `{ok:true,token,size}` where size is the cumulative byte count. |
| `fs_upload_commit <token>` | Current owner token | Verify staging length/CRC, promote; ACK `{ok:true,token,size,crc32,committed:true,cleanup_pending}`. |
| `fs_upload_abort <token>` | Current owner token | Discard staging/recover; ACK `{ok:true,token,size}` counts received bytes, not saved bytes. Unknown/inactive token rejects. |
| `scr <0\|1>` | `0`=off, `1`=on | Sets display brightness to 0 (off) or the configured brightness (on). Replies `ACK {ok:true}`. |
| `led <0\|1>` | `0`=off, `1`=on | Sets the status LED off (black) or to the configured LED color. Replies `ACK {ok:true}`. |
| `wifi_deauth <bssid> <channel> <seconds>` | BSSID as `AA:BB:CC:DD:EE:FF` or 12 hex chars; channel 1-14; seconds 1-60 (clamped) | Runs a time-bounded deauth flood against the target AP (`runDeauthBounded`), then replies `ACK {ok:true, out:"deauth ... done"}`. `ERR 400` on a malformed BSSID or missing args. |
| `wifi_handshake <bssid> <channel> <seconds> [ssid]` | channel 1-14; seconds 5-120 (clamped, default 30) | Runs a time-bounded WPA handshake capture against the target AP (`capture_handshake_bounded`); on success the handshake is saved under `/BrucePCAP/handshakes/` on the device. Replies `ACK {ok, out}` (`ok:false` and an explanatory `out` if no handshake was captured). With `features.capture_descriptor` the ACK also carries `d.desc` (see "Capture artifact descriptor"). `ERR 400` on malformed args. |
| `ble_spam <seconds>` | seconds 1-60 (clamped, default 8) | Runs a time-bounded BLE advertising flood (`runBleSpamBounded`), then replies `ACK {ok:true}`. |

These companion-specific commands are documented as part of the protocol
(not the generic CLI) because the app must know their exact argument order
and reply shape; everything else is "whatever the CLI prints".

`scr`/`led` state auto-restores (screen/LED back on) if the phone goes
silent for more than 15s, and always on leaving companion mode, so the device
never gets stuck dark if the app disconnects mid-session. After 60 s without a
frame the device leaves companion mode. Both silences are measured from the end of
handling the last frame, so a bounded command that blocks for about a minute
(NDEF push / emulate, TV-B-Gone) does not trip them before the PINGs queued
meanwhile are read.

#### Verb matching, idle stops and transmit results

Each companion command is matched by its exact verb: the bare verb or the verb followed by a
space and arguments. A longer word that only starts with a verb (`ir_jammer_stop`,
`speaker_volume_stop`, `evil_portal_start_x`) is not read as that verb; it reaches the Bruce CLI,
which answers `ok:false` for an unknown command. (Before, `ir_jammer_stop` started a jam window,
`ble_ibeacon_stop` a broadcast, `jam_detect_stop` a detection window and `speaker_volume_stop`
set the volume to 100 %.) While an operation runs, any `stop` / `*_stop` still ends or is consumed
by it as described above. On an idle device `responder_stop`, `karma_stop`, `brucegotchi_stop`,
`jam_detect_stop`, `ir_jammer_stop`, `ble_ibeacon_stop`, `nrf24_stop` and `ir_power_spam_stop`
reply `ACK {ok:true, out:"not running"}`.

`nrf24_scan`, `nrf24_sniff`, `nrf24_jammer` and `nrf24_mousejack` poll the link like every
blocking operation (2026-11-06; before, they read nothing until their window ended): a `stop` /
`*_stop` (`nrf24_stop` reads best) ends the window at once and the command's own ACK follows with
`(stopped)` in its text (`nrf24 mousejack: N device(s), stopped`; a stopped MouseJack never
injects). Other `CMD`s and `SUB`s meanwhile get `ERR 409 device busy`, so a host runs one
`nrf24_*` window at a time.

Audio peripherals have one owner: FM audio TX (`fm_audio_begin`) drives the speaker's I2S pins
and, with the `mic` source, reads the mic. Starting any of `fm_audio_begin`, `speaker_tone`,
`speaker_play`, `spkr_pcm_begin`, `mic_record` or a `mic.spectrum` / `mic.pcm` SUB ends the other
owner first (a `speaker_play` file reports `stopped`, reason `superseded`); a later
`fm_audio_data` then answers `FM audio not active`.

Results that used to report success when nothing was sent:

| `cmd` | reply now |
|---|---|
| `ir_tvbgone [na\|eu]` | `ok:true`, `TV-B-Gone done (sent <n> codes)`; `ok:false`, `TV-B-Gone sent 0 codes` when no code went out (IR TX busy, or the bound passed first) |
| `ir_jammer [secs]` | `ok:true`, `ir jammed (sent <n> pkts)`; `ok:false`, `ir jammer sent 0 pkts` |
| `ir_power_spam [secs]` | Blasts every power table (NA + EU + universal parsed + raw), repeating until `secs` (default 30, clamped 1..120) or a stop. Polls the link like a blocking op (a `stop` / `ir_power_spam_stop` ends it between codes; other CMDs/SUBs get 409). `ok:true`, `IR power spam sent <n> codes[ (stopped)]`; `ok:false`, `IR power spam sent 0 codes` when no code went out |
| `subghz_tx_raw <freqKHz> <te> <csv>` | `ok:true`, `tx <n> edges on <f> kHz` only when the radio came up and the RMT transmit accepted the timings; else `ok:false`, `tx failed: 0 of <n> edges sent (radio init or transmit)` |
| `subghz_replay <freqKHz> <secs> [repeats]` | `ok:true`, `replayed <e> edges on <f> kHz (<done>/<repeats> repeats[, stopped at the device])`; `ok:false`, `replay not sent (0/<repeats> repeats; ...)` when the radio did not initialize for TX or ESC came first; `ok:false`, `no signal captured` as before |
| `ble_ibeacon <secs> <uuid>` | `ERR {code:400, msg:"invalid uuid (expected 8-4-4-4-12 hex, ...)"}` for anything but the canonical 36-character UUID; nothing is broadcast. No UUID still means the default |
| `ble_hid_begin` | `ERR {code:503, msg:"ble init failed"}` when NimBLE does not initialize; `ERR {code:500, msg:"ble hid start failed (not advertising)"}` when the HID advertiser is not running after start (the session is torn down) |
| `nfc_ndef_push contact <Name[;+phone]>` | `ERR {code:400, msg:"contact too long (<n>-byte record, max 126)"}` before the reader starts, when the text/vcard record exceeds the PN532 NDEF limit (name + phone up to about 65 bytes, a name alone up to 71) |

Bruce CLI number arguments (routed `CMD`): `tone` / `beep <frequency> <duration>` take whole
numbers 0..20000 Hz and 0..10000 ms; `screen color hex <rrggbb>` a hexadecimal value up to
`FFFFFF`; `screen color rgb <r> <g> <b>` whole numbers 0..255. Anything else prints a reason
(`invalid frequency: ...`, `Invalid color: ...`) and returns `ok:false`; it used to abort the
device (`std::stoul` threw, backlog F03) or take a numeric prefix.

#### Device appearance and lite-control state in `CAPS` (additive)

`CAPS` also reports the device's look and its output state, so an app that connects or reconnects
shows what the device is doing rather than what it last asked for:

| key | type | meaning |
|---|---|---|
| `theme` | int | UI primary color, RGB565 |
| `led`, `ledBright`, `ledEffect` | int | LED color `0xRRGGBB`, brightness 0..100, animation 0..9 (only on boards with an addressable LED) |
| `bright` | int | configured screen brightness 0..100 (what `scr 1` and the 15 s restore return to) |
| `scrOn` | bool | `false` while `scr 0` holds the screen off |
| `ledOn` | bool | `false` while `led 0` holds the LED off (tracked on boards without an LED too) |
| `scrBright` | int | backlight level now, 0..100: `0` during a `scr 0` hold, the `scr_bright <n>` value after that command (not saved, so it differs from `bright`) |

`scr_bright <n>` (0..100, clamped) sets the backlight without saving; a level above 0 also ends a
`scr 0` hold (`scrOn` becomes `true` and the 15 s restore no longer applies). A reconnect within the
15 s window therefore sees `scrOn:false` / `ledOn:false`; after the window, or after leaving companion
mode, both read `true` again.

Compatibility: `scrOn`, `ledOn` and `scrBright` are new; firmware before them omits the keys, and the
app then keeps its previous behavior (the switches show what the app last set, and both read on after
a disconnect). Only literal booleans and an integer 0..100 are taken; anything else is treated as
absent. Older apps ignore the keys.

### Console input and scripts in companion mode

While companion mode is active the USB serial port carries only frames, and only the companion
frame reader (`readFrame()`, `companionPollStop()`) reads it. There is no console input on the
device in that state, and Brucia/Bruce commands and scripts do not read the app's frames
(`tools/test_companion_serial_input.py`; third-party libraries are not scanned):

- Bruce's CLI console `serialDevice` is replaced for the whole session by a device with no input
  and no output (inside a routed `CMD`, by the capture device behind `ACK.d.out`, also without
  input). The previous device (USB console, or the BLE API's) is restored on exit.
- CLI commands that read their payload from the console (`storage write`, `ir tx_from_buffer`,
  `subghz tx_from_buffer`, `badusb run_from_buffer`, `js run_from_buffer`,
  `crypto encrypt_to_file`) see empty input at once and print `serial input is not available in
  companion mode` into `ACK.d.out` (they used to wait 5 s); `js run_from_buffer` then fails
  instead of starting an empty script. `storage ymodem`
  fails at once without touching the target file. Upload files with `fs_upload_*`.

`script_run <js source>` (not on LITE builds; ERR 501 there) runs a BruceJS script headless with
a 15 s execution bound (20 s wall clock) and replies `ACK {ok, out}`, `out` being what the script
logged with `console.log()` (BruceJS has no global `print()`; calling it is a ReferenceError) and
any uncaught error, cut at 1800 characters. A script that ends on an uncaught error (a
ReferenceError, or `interrupted` when the bound stopped it) replies `ok:false` with that output,
which ends with the error text (`script error` when nothing was captured); `exit()` is not an error.
Firmware before batch 16 replied `ok:true` for these, so a host should not read `ok:true` as "no
error" from older firmware. Other `ok:false` replies: `script timeout`, `script stopped`,
`interpreter busy`, `script too large` (over 6000 characters) or `failed to start interpreter`.

`script_run_file <path>` (`features.script_run_file`; not on LITE builds) runs a script stored on
the device with the same bounds, stop handling and reply as `script_run`. `<path>` is absolute and
ends in `.js`, `.bjs` or `.bin` (compiled BruceJS bytecode, `mqjs -o file.bin -m32 file.js` with the
mquickjs version the firmware pins; `tools/bjs_compile.py`). The file system is chosen like
`fs_read` (SD if mounted, else LittleFS). Errors before the run: `ERR 400` (usage or not a script
extension), `ERR 404 file not found`, `ERR 503 no storage`; `ACK ok:false` with `empty script file`,
`script file too large` (over 256 KiB), `not a compiled BruceJS file` (a `.bin` without the
bytecode magic) or `interpreter busy`. A `.bin` whose structure does not check out (truncated,
corrupt, other bytecode version) ends as an uncaught error, `invalid or incompatible bytecode file`.
The check bounds the file's blocks and pointers; it does not verify the opcodes, so a bytecode file
is code the user chose to run, like a `.js`.

While it waits for the script the device behaves as during every blocking operation (see
"Behavior during a blocking operation"): a `PING` is answered, any other `CMD` gets `ERR 409
device busy`, and a `stop` / `*_stop` `CMD` (`script_stop` reads best) stops the script; it is not
ACKed itself, the `script_run` reply is `ok:false`, `script stopped`. Older firmware did not read
the link during `script_run` (frames waited until it ended), so an app should send the stop only
on a direct channel and treat a missing effect as old firmware. The `script stopped` reply is sent
once the interpreter task has ended (task handle cleared, abort flag and deadline dropped), so a
`script_run` sent right after it is accepted, not `interpreter busy`. The Brucia app sends
`script_stop` once per run, only while a `script_run` awaits its reply, and starts no new script
until that reply arrived and the stop frame was written (a stop that reaches an idle device gets
an ordinary reply, which the app ignores). Stopping, like the execution bound
and the wall clock, ends the script promptly also when it sits in a native wait: `delay()`,
timers, `wifi.connect()`, `nrf24.receive()`, `notify` blink, and the Bruce loops that end on ESC
(`subghz.read`, `ir.read`, `rfid.read`/`readUID`/`write`, `audio.playFile()`/`tone()`, CLI
commands run with `serial.cmd()`), which see ESC as pressed on the interpreter task only. A native
call that checks none of these (`ble.scan()`, an HTTP request, `badusb` typing) still runs to its
own end; the script then stops at once instead of continuing, and the interpreter is free for the
next `script_run` as soon as that call returns (before, a run that hit the wall clock went on with
no bound and could keep the interpreter busy until companion mode ended). The firmware also used to
let every `script_run` sit out the 20 s wall clock before the script started (it waited for a
state change nothing makes); it now starts at once. Inside it:

| Script API | In companion mode |
|---|---|
| `console.log()` and uncaught errors | captured into `out` (an uncaught error makes the reply `ok:false`); there is no global `print()` |
| `serial.print()` / `serial.println()` | dropped (they write the USB console, which is the frame stream) |
| `serial.readln([timeoutMs])` | returns `""` at once, without reading or flushing the port (outside companion mode it still waits up to the timeout for a line from the USB console) |
| `serial.cmd(cmd)`, `audio.playFile()`, `audio.tone()`, `badusb.runFile()`, `subghz.transmitFile()` (CLI-backed helpers) | run the CLI command with no console: its text is dropped and console input reads as empty; the return value is the CLI result |
| uncaught error | the device shows the error screen but does not wait for a key, so the interpreter ends and a later `script_run` is not left `interpreter busy` |
| `keyboard()`, `hexKeyboard()`, `numKeyboard()`, `dialog.prompt()` | return `""` at once, as when cancelled with ESC |
| `dialog.message()` | returns `""` at once (no button label) |
| `dialog.choice()`, `dialog.pickFile()` | return `""` at once, as when left without a choice |
| `menu.show()` | returns `-1` at once, as when left with back |
| `dialog.viewFile()` | returns at once without opening the viewer |
| `dialog.viewText()` | draws the text and returns at once instead of waiting for SEL |
| `dialog.info/success/warning/error(msg, true)` | show the message and return at once (Bruce's display helpers skip the key wait in companion mode) |
| `wifi.connectDialog()` | returns `false` at once (use `wifi.connect(ssid, timeout, password)`) |
| `wifi.httpFetch()` with no Wi-Fi | does not open the network menu; throws `WIFI Not Connected` as when no network is chosen |
| `subghz.read(t)` / `subghz.readRaw(t)` with `t <= 0` | return `""` at once (with `t <= 0` they wait for ESC; a positive timeout runs as usual) |
| `mic.recordWav(path, {maxMs: 0})` | returns `{ok:false, path:"", bytes:0, ...}` at once (with `maxMs: 0` it records until SEL, or for ever) |
| `display.drawGif(..., -1)` | plays the GIF once instead of looping until a key is pressed |
| `serial.cmd("loader open ...")` | `false`; `loader open` is refused in companion mode (also as a routed `CMD`), see below |
| `serial.cmd()` of `webui`, `sniffer`, `listen`, `sleep` / `power sleep` | `false`: these run until a key is pressed at the device and are refused inside a companion script; sent by the app as a routed `CMD`, `webui`, `sniffer` and `sleep` still run (the app's Sleep button), `listen` is refused there too (it asks for the port on the device keyboard) |
| `serial.cmd()` of other CLI commands that would wait for a person (`arp`, `ir rx` without an IR RX pin, `js <file>`, ...) | `false` at once, as for a routed `CMD` (table below) |

Each skipped helper above appends one line to `out`, `<api>: skipped, it waits for a person at the
device (companion mode)`, so the app shows why the script got the cancelled value. Waits that end on
their own are unchanged: radio reads with a positive timeout (`ir.read`, `rfid.read`, `subghz.read`,
`nrf24.receive`, `ble.scan`), `wifi.connect`, `delay()`, `audio.playFile()`, `mic.recordWav` with a
duration. They count against the 15 s execution bound, which now ends most of them early (see
above). `badusb.setup()` on USB-HID boards
waits for a USB host, not for a person, and is not changed (the app does not offer BadUSB over the
USB link). Scanned by `tools/test_companion_prompts.py`: every interpreter function that calls a
prompt helper (keyboard, menus, dialogs, file picker, viewers, Wi-Fi network menu, wait-for-key
loops) skips it in companion mode or is in its reasoned allowlist (the on-device Scripts menu).

`loader open <app>` (opens a Bruce app's on-device UI) answers `loader open is not available in
companion mode` and `ok:false` in companion mode, as a routed `CMD` too; use Remote control, which
leaves companion mode first. Outside companion mode it is unchanged.

Bruce CLI commands sent as a routed `CMD` (and run by a script's `serial.cmd()`) in companion mode,
where they would wait for a person at the device (`tools/test_companion_prompts.py` follows every
CLI command into the module code it calls):

| Command | Waited for | In companion mode |
|---|---|---|
| `loader open <app>` | the app's on-device UI | `ok:false`, `loader open is not available in companion mode` |
| `ir rx [raw]` with no usable IR RX pin | the pin menu | `ok:false`, `IR RX pin not configured (settings irRx <pin>)`; the companion `ir_rx` answers `ok:false`, `IR RX pin not configured`; a script's `ir.read()` returns `""` |
| `arp` | ESC to end the scan, then the host menu | `ok:false`, `arp opens a host menu at the device; not available in companion mode` |
| `listen` | the port on the device keyboard (and the network menu) | `ok:false`, `listen asks for the port at the device; not available in companion mode` (was refused only inside a script) |
| `webui -noAp` with no Wi-Fi | the network menu | `ok:false`, connect first with `wifi_connect`; `webui` (AP) and `webui -noAp` with Wi-Fi still run until ESC at the device, as before |
| `decrypt` / `crypto decrypt_from_file` / `crypto type_from_file` with an empty password | the password keyboard | `ok:false`, `a password is required in companion mode (no keyboard prompt)` |
| `js <file>` / `js run_from_file <file>` | the main loop to bring the script to the foreground (it never does in companion mode; later `script_run`s answered `interpreter busy`) | `ok:false`, `js is not available in companion mode (use script_run)`; `js exit` unchanged |
| `badusb run_from_file` / `run_from_buffer` | a USB HID host on the port that is the app link (`while (!tud_mounted())`) | `ok:false`, `badusb is not available in companion mode (the USB port is the app link; use BadBLE)` |
| `ble_badble` payloads | `WAIT_FOR_BUTTON_PRESS` in the payload | the payload stops there (`WAIT_FOR_BUTTON_PRESS: payload stopped, ...`) |
| IR transmit (`ir tx`, `ir tx_raw`, `ir tx_from_file`, `IRSend`, `ir_tvbgone`, `ir_tx_raw`, `ir_jammer`, `ir_power_spam`) with the IR TX pin outside the board's IR TX pin list | the pin menu | `ok:false`, `IR TX pin not configured`; nothing is transmitted |
| `nfc_emv_read` with no card | ESC at the device | gives up after 20 s (`ok:false`, `no EMV card present`); a `stop` ends it earlier |
| `lora_recv <secs>` | the listen window (up to 120 s) | PING answered; a `stop` ends it early (`received <n> packet(s) (stopped)`) |
| `settings` (no arguments) | nothing; it wrote the JSON straight onto the frame stream | the JSON goes into `ACK.d.out` (cut at 3500 bytes); pin setters no longer dump the pins file onto the link either |

Unchanged on purpose: `webui`, `sniffer` and `sleep` / `power sleep` run until a key at the device
(the app's console and Sleep button) and are refused only inside a companion script; menus they
show appear after a person pressed a key. `subghz rx` / `rf scan` end on their own timeout or step
count (the single-code `subghz tx` / `txp` / `keeloqtx` and `RfSend` reject a malformed or
out-of-range number with `ok:false` and a reason naming the argument instead of aborting:
frequency is a whole decimal uint32, `bits` 1..64, `te` 0..65535 µs, count/repeat 0..1000,
KeeLoq button 0..15, serial up to 28 bits hex, counter 0..65535), `ir tx_from_file` pauses only when SEL is pressed, `rfid emulate` on a PN532 runs up to 60 s.

Outside companion mode none of this applies: Bruce's console, `serial.readln` and the CLI behave
as upstream.

### Capture artifact descriptor (`wifi_handshake` / `wifi_pmkid`, additive)

Legacy capture ACKs only say `ok`/`out`, and the CLI directory listing has no completeness token, so
the host had to infer which file a capture run wrote by diffing `/BrucePCAP/handshakes/` listings and
file contents before and after the run. Firmware advertising `features.capture_descriptor: true` adds
a `desc` object to the **terminal ACK** of `wifi_handshake` and `wifi_pmkid`, describing the single
file that run actually wrote, so the host imports exactly that path and verifies it after transfer.
This is additive: `ok`/`out` are unchanged, and the device's standalone capture behavior (file
naming, menus) is identical — only the companion reply carries extra data.

```json
"desc": {
  "v": 1,
  "path": "/BrucePCAP/handshakes/HS_AABBCCDDEEFF_MyAP.pcap",
  "size": 812,
  "crc32": 2882341475,
  "frames": 5,
  "handshake": true,
  "complete": true,
  "stop": "complete",
  "appended": true,
  "prior_size": 300,
  "time_src": "device_uptime_ms",
  "uptime_ms": 183450
}
```

| field | type | meaning |
|---|---|---|
| `v` | uint | descriptor contract version (currently `1`) |
| `path` | string | absolute path of the file this run actually wrote. The handshake writer names the file from the SSID **resolved out of the captured frames** (`HS_<MAC>[_<SSID>].pcap`, MAC-only when unresolved), which may differ from the requested SSID, so this is the writer's reported path, not a predicted one. When nothing was written this run it is a best-effort predicted name and `complete` is `false`. |
| `size` | uint | byte length of the **whole** file at `path`; present only when this run created or grew it |
| `crc32` | uint | unsigned IEEE CRC-32 of the whole file — the **same** CRC the `fs_read` terminal ACK reports, so the host verifies the pulled bytes; present only when this run created or grew the file |
| `frames` | uint | pcap records this run actually appended to the file (measured: beacon + EAPOL M1..M4 for a handshake, EAPOL frames saved for PMKID — never a fixed assumption); present only when this run wrote the file |
| `handshake` | bool | `wifi_handshake` only: a usable 4-way handshake (M1..M4) was detected |
| `complete` | bool | `true` only when a usable capture was detected **and** a file was created/grown this run and read back |
| `stop` | string | `"complete"`, `"timeout"` (budget elapsed, nothing usable), `"aborted"` (device button ended the run), `"no_capture"` (ran out, nothing seen), or `"not_written"` (a usable capture was detected but no new file was created/grown this run — e.g. only a stale pre-existing file) |
| `appended` | bool | present and `true` when the file pre-existed and this run appended to it; `size`/`crc32` then describe the whole accumulated file (handshake pcaps accumulate across runs; PMKID files are truncated each run so this is absent) |
| `prior_size` | uint | file size before this run's appends, present only with `appended:true` |
| `time_src` | string | always `"device_uptime_ms"`: the device has no trustworthy wall clock and never claims a capture timestamp |
| `uptime_ms` | uint | device monotonic uptime (`millis()`) at stop — the only time the device can honestly report, not a capture time |

The device tracks which file the sniffer writer actually created or grew during the run (not a
predicted name), so a run that detects a handshake but writes nothing new — for instance when a stale
file from an earlier run already sits at the path — reports `complete:false, stop:"not_written"` and
makes no claim about the pre-existing file. `size`/`crc32`/`frames` are omitted whenever no file was
written (any `complete:false`). A `complete:false` descriptor means nothing usable was written this
run: the host reports the `stop` reason and does not import. The host also rejects a `path` whose
basename is not the expected per-BSSID capture name, and any pulled file whose size/CRC-32 does not
match the descriptor (it reads and fingerprints the whole file, which for `appended:true` includes the
accumulated prior content). Firmware without `capture_descriptor` omits `desc` entirely; the host keeps
the legacy listing-diff import and labels the result as inferred from a listing diff rather than a
device descriptor. The pure stop/CRC logic lives in the Arduino-free
`src/modules/wifi/capture_descriptor_core.cpp` (host-tested by
`tools/test_firmware_capture_descriptor.py`); the sniffer writer records the real path/record counts
(`src/modules/wifi/sniffer.cpp`) and the companion reads them back after flush and builds the JSON.

### Transactional upload and recovery

The token is 32 lowercase hexadecimal characters, chosen freshly by the client.
Size, offset and IEEE CRC32 are unsigned decimal integers; size is at most 16 MiB.
Only one upload is active. Begin pins SD if mounted, otherwise LittleFS; removal
never redirects chunks to the other filesystem. Keep the same medium mounted for
the entire transaction and recovery; the protocol does not identify SD cards.
Commands use normal CMD IDs in addition to the transaction token. The app validates
the token and cumulative byte count on every ACK, and literal `committed:true` plus
CRC on commit. Empty uploads use begin/commit with size=CRC=0 and no chunks.

Paths are absolute, 2–192 UTF-8 bytes. Empty, `.` and `..` segments, trailing `/`,
segment edge spaces/trailing dots, control characters and `\ : * ? " < > |` are
rejected. The case-insensitive root prefix `/.brucia-upload.` is reserved. The app
adds `/` to relative input. Parent directories must already exist.

The firmware owns `/.brucia-upload.pending` (journal), `/.brucia-upload.part`
(staging) and `/.brucia-upload.bak` (FAT backup). The journal is written and read
back before staging or destination mutation. It contains five newline-terminated
fields: `BRUCIA-UPLOAD-1`, token, size, CRC32, destination. It is bounded to 512 bytes.
Staging is closed and reread for exact length/CRC before any destination rename.
LittleFS replaces via one rename. FAT uses backup → promote → cleanup, with rollback
on promotion failure; it is recoverable, **not an atomic visibility guarantee**.
If rollback also fails, the previous file remains in the backup and the command
returns an error. A successful promotion with failed cleanup still ACKs committed,
with `cleanup_pending:true`; it must not be reported as a failed replacement.

Recovery runs at companion entry on mounted backends and before begin. A missing
destination with a valid journal/backup is restored. With both destination and
backup present, only a destination matching the journal's new size/CRC allows
backup deletion. Invalid journals, orphan reserved files and conflicting targets
fail closed, retaining artifacts; do not delete these blindly. Inspect/copy the
backup and journal through recovery tooling before resolving a conflict manually.
This assumes successful filesystem operations persist as reported; it does not
certify physical power-loss behavior, faulty media, FAT metadata or CRC authenticity.
Concurrent generic CLI/scripts and other filesystem writers are outside transaction
isolation; legacy `fs_write*` is blocked while an upload is active.

Idle transactions expire 120 s after the last successful upload ACK when the
companion loop next runs. Abort and
companion exit attempt cleanup; disconnect without an abort relies on idle expiry.
CRC readback has a 120 s budget, checked between reads (driver calls may block).
The app waits 130 s for begin/recovery and commit, 8 s per chunk, and up to 2 s for
best-effort abort. A timeout/cancellation after commit was sent cannot prove rollback:
verify the destination before retrying. There is no automatic commit/upload replay.
Malformed command grammar returns ERR 400; transaction failures return ERR 409.

### PCM transmit commands (existing v0 wire format)

The handlers in `src/companion/audio.cpp` use different argument layouts for FM
and speaker playback. Speaker parameters are fields of `d`, not appended CLI text:

| `d.cmd` | Other `d` fields | Meaning |
|---|---|---|
| `spkr_pcm_begin` | `sr: 16000` | Initialize mono S16LE playback; wait for `ACK.ok` before sending samples. |
| `spkr_pcm_data` | `pcm: "<base64>"` | PCM samples; app batches at most 2048 samples (4096 raw bytes). |
| `spkr_pcm_stop` | none | Close playback; ACK reports completion of the stop handler. |
| `fm_audio_begin <freqKHz> <phone\|mic>` | none | FM 87500–108000 kHz; the firmware probes the external Si4713. |
| `fm_audio_data <base64>` | none | Phone PCM, mono S16LE at 16 kHz; app batches at most 1024 samples. Reply carries accepted counts (below). |
| `fm_audio_stop` | none | End FM transmission. |

All these commands reply with correlated ACK/ERR. The app awaits begin, frame and
stop results, and paces PCM by sample count using a monotonic clock. An ACK is not
proof of audible/RF output.

#### `fm_audio_data` accepted-sample accounting (additive)

Current firmware adds these fields to every `fm_audio_data` ACK (no command, CAPS key
or on-air behavior changes):

| Field | Meaning |
|---|---|
| `samples` | Whole S16 samples decoded from this frame (a trailing odd byte is ignored). |
| `accepted` | Samples queued for transmission, always the first `accepted` samples of the frame, in order. |
| `free` | Free space in the device ring after this frame (usable capacity 8191 samples, about 0.5 s). |
| `lost` | Cumulative since `fm_audio_begin`: samples taken from the ring that the I2S write did not take within its 10 ms timeout. |

A full ring is flow control, not an error: the firmware accepts what fits (possibly 0)
and replies `ok:true` with `accepted < samples` and `out` = `ring full: accepted N of M
samples`; the samples after `accepted` were not queued. It never discards part of a
frame without saying so. Not active, mic source or invalid Base64 reply `ok:false`,
`accepted:0` and a reason in `out`.

The app resends only the unaccepted remainder after waiting for the ring to drain
(estimated from `free` at 16 samples/ms, each wait 4–128 ms), waits before a frame
when the last reported `free` is too small, counts real-time pacing and progress
from accepted samples, and fails the stream once it has waited 2 s of drain time
without any sample accepted, instead of looping. A `lost` above 0 is shown as "Device dropped N samples";
an impossible count (`accepted` outside 0..frame, `samples` different from the frame)
fails the stream. Older firmware replies without `accepted`: the app keeps the
previous behavior (one send per frame) and labels FM delivery as unknown.

Limits: `accepted` proves only that samples entered the device ring. `lost` counts
I2S short writes; ring underruns (the phone sending slower than the device drains)
are silence gaps and are not counted. None of this proves audible or RF output.

### Speaker file playback (`speaker_play`, additive)

Firmware with literal `features.speaker_play_async: true` in CAPS (boards with the onboard
speaker) plays files without blocking the companion loop; PING and other commands are served
during playback.

| Message | Fields | Meaning |
|---|---|---|
| `CMD speaker_play <path>` | none | Start a WAV/MP3/AAC/FLAC/MOD/Opus/RTTTL file from SD/LittleFS. |
| `ACK` (start) | `ok: true`, `out: "playing <path>"`, `play: <CMD id>` | The file is open and the decoder started. |
| `ACK` (start) | `ok: false`, `out` | `unsupported format`, `no storage`, `file not found`, `playback failed: <path>`. |
| `ERR 400` | `msg` | Empty path. |
| `EVENT ev="speaker.play"` | `data: {play, state, path, ms, reason?}` | Exactly one per started file, after its start ACK. |
| `CMD speaker_stop` | none | Stop playback; a playing file sends its EVENT (`stopped`, `reason: "stop"`) before the `ACK out:"stopped"`. |

`state` is `completed` (the decoder ended), `stopped` or `error`. `reason` accompanies
`stopped`: `stop` (speaker_stop) or `superseded` (another `speaker_play`, `speaker_tone`,
`speaker_rtttl`, `speaker_song`, `speaker_dtmf`, `spkr_pcm_begin`, `mic_record` or a mic
stream SUB took the audio peripherals). `ms` is elapsed wall time since start. The decoder
library does not distinguish end of stream from giving up on corrupt data, so both report
`completed`; the current firmware does not emit `error`, which clients must still accept.
Stopping waits at most about 2.5 s for the player task to release the decoder, file and I2S
output. Leaving companion mode stops playback without an EVENT. A `speaker_play` that passes
the format/storage/existence checks reports the previous file as `stopped`/`superseded` before
its own ACK. Clients correlate by `play`
(the starting CMD id); an EVENT can be read before the client has processed its start ACK.

Without the flag, `speaker_play` replies once after the whole file (`out: "played <path>"`)
and cannot be stopped; clients keep a long timeout for it.

### NFC MIFARE Classic key-check (`mifare_keycheck`)

`CMD {cmd:"mifare_keycheck <hexKey1,hexKey2,...>"}` tests a batch of up to 64
comma-separated 12-hex-char candidate keys against the present MIFARE Classic tag
(`src/companion/nfc.cpp`). The phone owns the dictionary and splits it into batches;
the device only tests. Replies:

- No RFID module: `ACK {ok:false, out:"no RFID module (PN532) found"}`.
- No tag / non-Classic: `ACK {ok:false, out:"no MIFARE Classic tag present ..."}`.
- Tag present: `ACK {ok:true, found:[{sector, type:"A"|"B", key:"<12 hex>"}, ...], ...}`.
- LITE build (`features.mifare_keycheck: false`): `ERR 501`; the app does not send it.

The `found` array is the recovered-key list and is unchanged. A **v1** firmware
(advertised by `features.mifare_keycheck_report: true` in CAPS) adds these additive
fields to the tag-present ACK so the app can tell a fully-tested batch from one cut
short and tie consecutive batches to the same card:

| field | type | meaning |
|---|---|---|
| `v` | uint | reply contract version for this field group (currently `1`) |
| `uid` | string | tag UID read at batch start, uppercase hex (empty if 0-length) |
| `sak` | uint | tag SAK byte (0x08=1K, 0x18=4K, 0x09=Mini) |
| `sectors` | uint | total sectors on this tag |
| `tested_sectors` | uint | sectors fully tested before the scan stopped |
| `keys` | uint | candidate keys supplied in this batch |
| `auth_attempts` | uint | individual authenticate operations performed |
| `complete` | bool | `true` only if every sector × (A,B) × key was tested |
| `stop` | string | `"complete"`, `"tag_lost"` (reselect failed mid-scan → partial), `"cancelled"` (phone stopped the batch mid-scan → partial), or `"no_tag"` |

A `complete:false` (`stop:"tag_lost"`) reply means the remaining sectors/keys were
**not** tested: the app must not claim an exhaustive scan or report "no key found"
for untested keys. The app rejects a batch sequence when `uid` changes between
batches (card swapped), and keeps the keys found before the change. Older firmware
without these fields still returns `found`; the app runs the sequence but labels the
result "completeness unknown" instead of asserting an exhaustive scan. The loop
control and completeness accounting live in the Arduino-free
`src/modules/rfid/mifare_keycheck_core.cpp` (host-tested by
`tools/test_firmware_mifare.py`); the PN532 is only the hardware adapter.

**Stopping an in-flight batch.** Firmware advertising `features.mifare_keycheck_stop: true`
in CAPS checks for a stop frame between every authenticate() call while a batch runs. The
phone ends a running batch by sending a direct, unlogged `CMD {cmd:"mifare_keycheck_stop"}`
(any `stop` / `*_stop` CMD is accepted); the firmware polls and consumes it but does **not**
ACK it. Instead the batch already in flight returns within one attempt as its own ACK with
`complete:false, stop:"cancelled"` and the `found` hits recovered so far (its remaining
sectors/keys were **not** tested — partial, like `tag_lost`). The phone sends the stop frame
only when the CAPS flag is present; it then waits a bounded time for that `cancelled` ACK and,
if it never arrives (e.g. a dropped link), finalizes the sequence as a partial stop locally and
drops any late reply. Older firmware without the flag ignores the frame and the app can only
stop its own batch sequencing, not an in-flight device batch.

The whole batch is naturally bounded: the core performs at most `sectors × 2 × keys`
authenticate() operations (≤ 40 × 2 × 64 per batch) and then returns — there is no unbounded
wall-clock loop — but per-auth time on hardware is uncharacterized, so a `mifare_keycheck` ACK
still waits up to the app's 650 s batch budget as a conservative ceiling; the stop handshake
above is what makes cancellation prompt. Card removal/swap fixtures remain a hardware follow-up.

### Recovery diagnostics (`diag`, additive)

Firmware with literal `features.diag: true` in CAPS answers `CMD {cmd:"diag"}` with one ACK.
LITE builds report `features.diag: false` and answer `diag` with ERR 501 (see `features`).
There is no automatic telemetry: the counters live in RAM only, nothing is sent unless the
app sends `diag`, and nothing is written to storage. Older firmware routes `diag` to the
generic CLI; the app does not send it without the flag and shows "not supported by this
firmware".

```json
{"ok": true, "v": 1, "reset": "poweron", "reset_code": 1, "uptime_s": 5123,
 "heap": {"free": 181220, "min_free": 120440, "largest": 110580},
 "psram": {"size": 8386295, "free": 8100000, "min_free": 8000000, "largest": 8257524},
 "link": {"sessions": 1, "rx_frames": 412, "tx_frames": 980, "tx_torn": 0, "rx_zero_len": 0,
          "rx_oversize": 0, "rx_alloc_fail": 0, "rx_stale": 1, "rx_bad_json": 0, "busy_409": 2,
          "err": {"400": 0, "404": 1, "409": 2, "413": 0, "4xx": 0, "5xx": 0, "other": 0}},
 "drops": {"fm_pcm_lost": 0, "fm_pcm_active": false, "mic_pcm_dropped": 0}}
```

| Field | Meaning |
|---|---|
| `v` | Reply contract version for this field group (currently 1). |
| `reset` | `esp_reset_reason()` of the current boot as a stable name: `unknown`, `poweron`, `ext`, `sw`, `panic`, `int_wdt`, `task_wdt`, `wdt`, `deepsleep`, `brownout`, `sdio`, `usb`, `jtag`, `efuse`, `pwr_glitch`, `cpu_lockup`, or `other` for a value this firmware does not name. |
| `reset_code` | The raw numeric reason (ESP-IDF 5.x numbering, checked at build time). |
| `uptime_s` | Seconds since boot (`esp_timer`, does not wrap like `millis()`). |
| `heap` | Internal heap bytes: `free` now, `min_free` (lowest free since boot), `largest` free block. |
| `psram` | Present only when the board has PSRAM: `size`, `free`, `min_free`, `largest`. |
| `link` | Companion link counters since boot (kept across companion-mode entries, reset only by a reboot). |
| `drops` | Existing per-stream loss figures, read from the same counters as their streams (absent keys = not compiled in or not readable now). |

`link` keys, in this order:

- `sessions`: companion-mode entries since boot.
- `rx_frames`: complete frames read by the frame decoder (valid length, before JSON parsing). The `diag` request itself is included.
- `tx_frames`: frames written whole, every kind. The `diag` reply itself is not included. A torn frame is not counted here.
- `tx_torn`: frames abandoned mid-write because the host stopped accepting bytes (no progress for the idle bound, or past the per-frame ceiling). The TX stream is then desynced and the firmware drops the link so the app reconnects onto a clean session; the count is visible on the next session's `diag` (the counters persist across companion-mode entries).
- `rx_zero_len`, `rx_oversize`: length prefix 0 or above `COMPANION_MAX_FRAME`; the decoder resets.
  (Bytes lost in the serial RX buffer itself are invisible to these counters; they surface as
  `rx_stale` or a malformed frame. Hosts keep frames within `rx_max` to avoid them.)
- `rx_alloc_fail`: valid length but the payload buffer could not be reserved.
- `rx_stale`: partial frame discarded after 2 s without bytes.
  The length prefix is not self-synchronizing: after a rejected length the following payload
  bytes are read as new headers, so one corrupt frame can raise several of these counters.
- `rx_bad_json`: complete frame whose payload is not JSON (ERR 400 in the service loop; dropped without reply while a blocking operation polls).
- `busy_409`: CMDs rejected with ERR 409 "device busy" by `companionPollStop` during a blocking operation (also counted in `err["409"]`).
- `err`: every ERR reply sent, by status code: `"400"`, `"404"`, `"409"`, `"413"`, `"4xx"` (other 4xx), `"5xx"`, `"other"`.

`drops` keys: `fm_pcm_lost` (with `fm_pcm_active`) on builds with FM audio is the
`fm_audio_data` `lost` value since the last `fm_audio_begin`; `mic_pcm_dropped` is the
`mic.pcm` EVENT `dropped` value since the current SUB, present only while that capture runs
and the driver reports overruns.

Every counter is a fixed-size `uint32` that **saturates** at 4294967295 (it never wraps);
that value means "at least this many". Increments are single additions in the frame reader,
frame writer and ERR path, with no allocation or I/O. Counters describe this boot only: a
reset clears them, which is why `reset` and `uptime_s` accompany them. The app shows them
in a Diagnostics section and adds them to log exports and session reports, together with its
own link counters (malformed frames it skipped, command timeouts, replies with no pending
command). None of this proves hardware health or radio behavior.

## Streams (`SUB` + `EVENT`/`DATA`)

A stream is a named channel the phone turns on/off with
`SUB {stream: "<name>", on: bool}` (the device replies `ACK {ok:true}`, or
`ERR {code:404}` for an unknown stream name). Starting `subghz.raw` or
`subghz.spectrum` replies `ERR {code:503}` when the radio or RMT receiver cannot
be initialized; the stream then stays off. A `subghz.spectrum` start with invalid
sweep parameters replies `ERR {code:400}` and changes no stream (see below).
Starting `ble.scan` replies `ERR {code:409}` while a BLE HID session (`ble_hid_begin`)
owns the stack and `ERR {code:503}` when BLE cannot be initialized; the stream then
stays off.

**One CC1101 owner.** While `subghz.raw` or `subghz.spectrum` runs, a command that drives
the CC1101 itself (every `subghz_*` command, `RfSend`, and the CLI `rf`/`subghz` verbs
`rx`, `tx`, `txp`, `scan`, `tx_from_file`, `tx_from_buffer`, `keeloqtx`) replies
`ERR {code:409, msg:"sub-ghz receiver busy (stop Listen/Spectrum first)"}` instead of
re-initializing and then idling the radio under the running stream. `rf/subghz mfcodes`
(file only) is unaffected. The check ignores letter case and covers every newline or `;;`
segment of the command, as the Bruce CLI does (`RF RX 433920000` and
`subghz mfcodes list;;subghz rx` are refused too). Likewise `ble_media` replies `ERR {code:409}`
while `ble.scan` runs.

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
- `subghz.raw` — raw sub-GHz OOK RMT edge timings for signal
  capture/replay tooling, one `EVENT` per received RMT symbol burst (≥5
  symbols): `ev = "subghz.raw"`,
  `data = {freq, symbols, max_symbols, timings: [uint, ...]}`
  (`freq` in Hz is the frequency the receiver is tuned to: the device RF
  frequency, 433.92 MHz by default, which `subghz_config` and the jammer also
  set, rounded to the nearest kHz so float artifacts such as 868349952 for
  868.35 MHz are not reported; `timings` is a flat array of pulse durations in µs (1 MHz RMT
  resolution), high and low alternating, with zero-duration end markers
  omitted). All decoding happens on the phone; the device only captures and
  forwards raw timings.
  `symbols` (RMT symbols stored for the burst) and `max_symbols` (receive
  buffer capacity, currently 64) are additive completeness metadata; firmware
  that omits them gives no completeness information. The ESP-IDF 5.5 RMT
  driver stores at most `max_symbols` and silently drops the rest, so
  `symbols < max_symbols` rules out a buffer cut, while
  `symbols == max_symbols` means completeness is unknown (the burst may have
  been longer). Neither value proves that every on-air pulse was captured
  (bursts between receive re-arms are not seen), and the firmware never
  reports proved loss. The app shows a burst at the limit as "possibly
  truncated" and appends both values as trailing `symbols,max_symbols` CSV
  columns (empty when absent); the `.sub` export format is unchanged.

  The inverse command is `subghz_tx_raw <freqKHz> <te> <csv of durations>` (a
  `CMD`, not a stream; needs `caps.cc1101`): it transmits an explicit OOK edge
  list the phone already holds, driving the same RMT raw-timings TX as a saved
  `.sub` RAW replay. Durations are signed µs played in CSV order (comma- or
  space-separated): a **positive** value holds the line HIGH, a **negative**
  value LOW, for `abs(value)` µs; `0` is a terminator and must not appear mid-
  list (`src/modules/rf/protocols/rf_encoder.cpp` `rf_tx_durations`). `<te>` is
  accepted for API symmetry but RAW uses the explicit durations directly. One
  call's payload must fit `rx_max`/`COMPANION_MAX_FRAME`, so a long waveform is
  split across several sequential `subghz_tx_raw` calls (each a separate on-air
  burst with a short gap). The ACK is `{ok, out:"tx N edges on F kHz"}`; like
  `.sub` transmit it reports that the command ran, not a verified radiation.
  The app's De Bruijn sweep (`subghz/DeBruijnTx.kt`) uses this: one half-pulse
  of `te` µs per sweep bit (`1`→`+te`, `0`→`-te`), split into overlapping bursts.
- `subghz.spectrum` — CC1101 RSSI sweep (not an IQ SDR), one `EVENT` per
  completed sweep: `ev = "subghz.spectrum"`,
  `data = {start, step, count, rssi: [dBm, ...]}` with `start`/`step` in kHz;
  point `i` was tuned to exactly `start + i * step` kHz. The SUB may carry
  optional numbers `f_start`, `f_end` and `step` in MHz (defaults 300, 348
  and 1). The firmware rounds them to whole kHz and replies `ERR {code:400}`
  instead of sweeping something else when a value is not a finite number,
  `f_start`/`f_end` fall outside 280–928 MHz, `f_end < f_start`, the step is
  below 1 kHz, or the grid exceeds 128 points (the step is not widened and the
  range is not swapped or clamped). A start while a sweep runs applies the new
  grid. `subghz.raw` and `subghz.spectrum` are mutually exclusive.
- `mic.pcm` (requires `caps.mic`) — continuous device-microphone PCM for
  phone-side DSP and recording. Unlike the streams above, the fields sit directly
  in `d` (no `data` object): `ev = "mic.pcm"`, `d = {sr, pcm, seq, dropped}`.
  `sr` is the sample rate (16000); `pcm` is Base64 of signed 16-bit little-endian
  mono samples, 2048 samples per event on current firmware (receivers must accept
  any even byte count). `seq` and `dropped` are additive continuity metadata;
  firmware that omits them gives no continuity information, and receivers treat
  their absence as unknown, not as zero loss:
  - `seq` (uint) counts emitted chunks and restarts at 0 on every `SUB on`. A
    jump between consecutive chunks means chunks were lost between device and
    phone; a restart mid-recording means the stream was resubscribed and a partial
    chunk was discarded. Either is a splice in the recorded audio.
  - `dropped` (uint) is the cumulative number of samples the ESP-IDF I2S driver
    discarded since that `SUB on` because the reader fell behind (RX queue
    overflow drops the oldest filled DMA buffer, 256 samples at the current DMA
    setting). It is sampled when the chunk is emitted, so a loss lies inside or
    just before the first chunk whose `dropped` increased; the exact sample
    position is not known. It is omitted when the driver refused the overflow
    callback (loss unknown), while `seq` is still sent.
  The device never inserts filler samples. The app reports splices with the
  recording result ("gaps detected: N", seq gaps plus chunks with a device
  overrun) and does not pad the WAV, because neither field locates the loss
  sample-exactly.

The Android SUB owner waits up to 8 seconds for a matching ACK/ERR. Missing or
failed acknowledgements clear the local running indication but retain a cleanup
obligation: Stop all retries OFF and disconnect attempts OFF on the old connection.
Superseded request callbacks are released, so late ACKs cannot reactivate a stream.
Monitor starts accept channel 1–13 or an omitted channel, and an optional six-byte
colon-separated BSSID. Neither a local timeout nor an OFF ACK proves physical
radio silence; those need device acceptance.

`fs_read` (above) is not a `SUB`-gated stream: it is a one-shot `CMD` that
produces a `DATA {stream:"file", ...}` burst followed by a terminal `ACK`.
The app serializes managed uploads/downloads, binds them to the connection that
started them and checks read sequence, size/CRC and upload token/offset/commit ACKs. Wrong IDs,
missing/duplicate chunks and malformed payloads cannot complete another transfer.
Its read buffer is capped at 16 MiB and a read has a 120-second total timeout.
Cancellation/disconnection releases the pending operation. CRC32 checks accidental
corruption; it does not authenticate the device or the file's origin.

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
