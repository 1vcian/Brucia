# Where Brucia hooks into Bruce

Integration points found in `` (commit from upstream `main`). Use these
instead of reinventing; keep every standalone path working.

## Command interface (reuse as the CMD backbone)

- `src/core/serialcmds.{h,cpp}` — serial command task; entry point
  `parseSerialCommand(const String&, bool waitForResponse)`.
- `src/core/serial_commands/cli.{h,cpp}` — the dispatcher.
- `src/core/serial_commands/*_commands.cpp` — per-domain commands
  (`wifi_`, `rf_`, `ir_`, `rfid_`, `badusb_`, `crypto_`, `gpio_`, `power_`,
  `screen_`, `settings_`, `sound_`, `storage_`, `util_`, `interpreter_`).
  → Companion `CMD` messages route through this dispatcher. Add companion-only
    commands here as new entries.
- `src/core/connect/serial_commands.{h,cpp}` and `esp_connection.*` —
  existing connect/transfer plumbing (file_sharing) worth studying for framing.
- `src/core/USBSerial/USBSerial.h` — USB CDC serial access (primary
  transport).

## Menu entry for companion mode

- `src/core/menu_items/ConnectMenu.cpp` — add the "Brucia / Companion"
  entry here (puts the device into stream-to-phone mode).
- `src/core/main_menu.{h,cpp}` — top-level menu wiring.

## Wi-Fi (F2 proof-of-concept)

- `src/modules/wifi/` — `sniffer.cpp` (AP/packet capture),
  `ap_info.*`, `channel_analyzer.*`, `scan_hosts.h`.
  → Hook the sniffer to emit `wifi.ap` stream items over the transport instead of
    (only) drawing to screen.
- `src/modules/gps/` — existing GPS module. In companion mode the phone's
  GPS replaces it, but keep the module for standalone wardriving.

## Board / build

- `platformio.ini` — target env `lilygo-t-embed-cc1101` (currently
  commented in `default_envs`; select it). ESP32-S3, native USB CDC.
- Partition CSVs: `custom_*.csv`.

## New code goes here

- `src/companion/` — transport layer, framing, protocol dispatcher,
  capability (`HELLO`/`CAPS`) handshake, stream hooks. Does NOT duplicate the CLI;
  it wraps it with framing + streaming.
