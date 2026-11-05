# Hardware verification

Brucia has been verified end-to-end on real hardware: a **LilyGo T-Embed CC1101
(ESP32-S3)** flashed with the Brucia firmware, driven over USB-CDC by the companion
protocol from a host.

Device: tap `Brucia` on the home screen, then run `tools/companion_test.py` (or the Android app).

## Verified (2026-10-27)

```
=== BRUCIA LIVE HARDWARE VERIFICATION ===
[1] HELLO -> CAPS OK
    {"board":"Lilygo T-Embed CC1101","chip":"esp32-s3","fw":"dev","proto":0,
     "modules":["wifi","ble","rf","ir"],"transports":["usb"],"mtu":8192}
[2] PING -> PONG OK
[3] CMD 'wifi scan' -> ACK OK (CLI output captured and returned)
[4] SUB wifi.ap on -> live Wi-Fi AP stream   (7 unique APs in 8s)
[5] SUB ble.scan on -> live BLE scan stream  (19 devices in 6s)
```

Wi-Fi scan, BLE scan, PING/PONG, capability handshake and CLI command routing with
output capture all work over USB-CDC against the real device.

## Also verified on hardware (2026-10-27)

- **Remote control**: `nav` drives the real Bruce UI and `display start` streams the TFT
  draw-ops; real frames (WiFi/NRF24 menus) render in the app's mirror. "Everything Bruce
  does, from the app."
- **Flame theme**: on-device config reads `priColor=fb63, secColor=fd80, bgColor=0`
  (ember/amber on black) - verified via `storage read /bruce.conf`.
- **Battery**: CAPS and every PONG report `"battery"` (read live: 100%).
- **Lite mode**: `scr 0/1` (screen off/on) and `led 0/1` return `ok`, with auto-restore
  if the app disconnects.

Pending (need an AP you own / physical input): live deauth, BLE spam and handshake
capture against a real target, and the physical wheel feel.

## Live wardrive stream (real capture)

Each line is a `wifi.ap` EVENT streamed from the device to the host, deduplicated
by BSSID on the host side:

```
[NEW] F8:0D:A9:1E:32:57  ch 11  -50dBm  wpa2/wpa3  'Lord of the pings'
[NEW] E4:4E:12:7E:CF:6F  ch  2  -51dBm  wpa2/wpa3  'FASTWEB-Q7U3EN'
[NEW] 3A:07:16:3B:4B:A4  ch  6  -65dBm  wpa2       'domotica'
[NEW] 3C:EF:42:F6:92:12  ch  1  -79dBm  wpa/wpa2   'WINDTRE-9212'
[NEW] A8:29:48:68:9E:99  ch 10  -91dBm  wpa2       'TP-Link_9E99'
[NEW] D8:EC:E5:EA:52:A9  ch 11  -94dBm  wpa2/wpa3  'Wind3 HUB-EA52A9'
[NEW] 76:64:B8:94:74:9E  ch 10  -95dBm  wpa2       'TIM-36813708'
```

The host (phone) adds GPS tags, dedup, RSSI-weighted position estimates, vendor
lookup, analytics and WiGLE/KML export on top of this raw stream - none of which
the ESP32 does on its own.

The Android app during a live capture (real APs streamed over USB):

![Brucia app, live wardrive over USB](img/app-wardrive.jpg)

## Reproduce

```sh
# Flash
pio run -e lilygo-t-embed-cc1101 -t upload
# On the device: tap Brucia on the home screen
python3 tools/companion_test.py /dev/tty.usbmodemXXXX           # handshake + PING
python3 tools/companion_test.py /dev/tty.usbmodemXXXX --wardrive # live AP stream
```

## Passive acceptance runner (S05 helper)

`tools/hw_acceptance.py` runs the passive part of device acceptance from a host and writes a
JSON and a Markdown report (firmware build/provenance, UTC timestamps, PASS/FAIL/SKIP per
check, raw evidence and every frame it sent). It needs pyserial and a board connected to
the computer by USB and already in companion mode (tap `Brucia` on the device).

```sh
python3 tools/hw_acceptance.py --port /dev/cu.usbmodemXXXX --dry-run   # print the frames, open nothing
python3 tools/hw_acceptance.py --port /dev/cu.usbmodemXXXX --out hw-acceptance-reports
python3 tools/hw_acceptance.py --port /dev/cu.usbmodemXXXX --only caps,ping,diag_counters
python3 tools/hw_acceptance.py --port /dev/cu.usbmodemXXXX --only busy  # opt-in, see below
```

Checks: `caps` (CAPS shape, features, modules), `provenance` (`build`), `evidence` (levels,
probes, pin conflicts), `diag_shape`, `ping` (200 sequential PINGs: latency distribution and
loss; a 20-frame burst must come back in order), `malformed` (one bad-JSON frame gives ERR
400 and `rx_bad_json` +1), `fs_list` (`storage list /`), `fs_read` (smallest file at `/`
up to 64 KiB, or `--read-path`; DATA id/seq, size and CRC32 verified), `fs_upload` (a
transactional upload of `/brucia-acceptance-<UTC>-<hex>.txt`, commit, read-back, remove,
listing confirms it is gone), `streams` (receive-only `wifi.probe` by default, also
`wifi.sta` or `subghz.raw` with `--streams`: ON/OFF ACKed, no events after OFF), `heap`
(no downward trend over repeated `diag`), `diag_counters` (link counters across the run
equal what the runner sent and received, no rejects, no reboot). File checks are skipped
without `features.verified_files` / `features.transactional_uploads`, diag checks without
`features.diag`.

Link handling: the reader resyncs after device text in the framed stream instead of failing
(proto/PROTOCOL.md "Resynchronisation"): skipped bytes are recorded per check as a warning with a
sample, and in the report's `link` section and Markdown header; they are not a FAIL by
themselves (a frame destroyed by text inside it still shows up in `diag_counters`). Frames the
runner sends never exceed CAPS `rx_max` (768 bytes for firmware without it); the `fs_upload`
check uses the largest chunk that fits (3072 raw bytes at `rx_max` 8192) for its first chunk and
half of it for the second, so it exercises the frame size that was lost on the first hardware
run, and records each chunk frame size in its evidence.

Safety: every frame passes an explicit allowlist (`check_allowed`); any other command or
stream raises before anything is written. Nothing that transmits is allowed: no deauth,
jamming, beacons, BLE advertising, IR/RF/Sub-GHz TX, replay or speaker. `wifi.ap` and
`ble.scan` are refused because they are active scans (probe/scan requests). The only
storage change is the scratch file the run creates and removes. `--only busy` runs
`jam_detect` (promiscuous receive) to check PONG and ERR 409 while busy and that `stop`
ends it; it is opt-in because its Wi-Fi setup disconnects a joined AP, which sends a
disassociation. Ctrl-C sends OFF for an active stream, `stop` for a busy operation and
abort/remove for the scratch upload, then writes a report marked interrupted. Exit codes:
0 no FAIL, 1 at least one FAIL, 2 link error, 130 interrupted.

The runner covers the protocol/transport part of the TESTS.md items for the `diag` card,
the hardware evidence card (recorded, plausibility is still a human judgement) and the
Files transport. App UI behavior, captures, Mifare, speaker, Sub-GHz UI, USB
permission/flash, in-app update, FM PCM and anything that transmits remain manual.
`tools/test_hw_acceptance.py` exercises it against `tools/mock_device.py`; a simulated pass
is not hardware evidence.

### Soak mode (`--soak <minutes>`, long-session evidence for S08)

`--soak` repeats the same passive, allowlisted operations for a set time and records how the
device holds up. It needs `features.diag`; without `--only` it runs `caps`, `provenance`,
`diag_shape`, the soak, then `diag_counters` over the whole run.

```sh
python3 tools/hw_acceptance.py --port /dev/cu.usbmodemXXXX --dry-run --soak 20 \
    --streams wifi.probe,wifi.sta,subghz.raw                     # print the interval frames
python3 tools/hw_acceptance.py --port /dev/cu.usbmodemXXXX --soak 20 \
    --streams wifi.probe,wifi.sta,subghz.raw --out hw-acceptance-reports
```

Each interval (`--soak-interval`, default 30 s) sends, in this order: 20 sequential PINGs and a
10-PING pipelined burst (`--soak-pings`, `--soak-burst`); `storage list /`; `fs_read` of the
smallest file at `/` (or `--read-path`), whose size and CRC32 must equal the first read; one
scratch upload cycle exactly as the `fs_upload` check (new token and
`/brucia-acceptance-<UTC>-<hex>.txt` each time, commit, read-back, `storage remove`, listing
confirms it is gone); one receive-only stream ON, `--stream-secs` of listening, OFF and
`--quiet-secs` that must stay silent, taking `--streams` in turn (`wifi.probe`, `wifi.sta`,
`subghz.raw` only); then PINGs whenever `--soak-keepalive` (default 5 s) passed since the last
frame, until the interval ends; then `diag`. The device leaves companion mode after 60 s without
a host frame, so the keepalive must stay at or under 30 s and `--stream-secs`/`--quiet-secs`
under 30 s with `--soak`. Nothing outside the existing allowlist is sent: no active scan
(`wifi.ap`, `ble.scan`), no `jam_detect`, nothing that transmits.

Recorded per interval (one row per `diag`, in the JSON report and in
`hw-acceptance-<UTC>-soak.csv`): heap free/min_free/largest, uptime and reset reason, device
`rx_frames`/`tx_frames` deltas next to the frames the runner sent/received, reject, ERR,
noise-byte, resync and undecodable-frame deltas, PING count/loss and p50/p95/p99/max, the
stream and its event count, and the duration of every operation (all operations with their
start time and result are in the JSON `soak.ops`). The Markdown report adds a soak summary,
findings, an operation-timing table and the time series.

Every finding FAILs the soak (exit 1): a failed or timed-out operation (lost PING, burst out
of order, fs_read CRC change, upload/remove failure, SUB not ACKed, events after OFF), device
link counters that differ from the runner's frame counts in any interval, any reject
(`rx_zero_len`, `rx_oversize`, `rx_alloc_fail`, `rx_stale`, `rx_bad_json`), ERR, `busy_409`,
new session, non-frame byte or resync (device text in companion mode is a regression since
batch 8), an unexpected reboot (reset reason changed, uptime or counters went back; the soak
stops there), an overall PING p99 above `--max-p99-ms`, or a heap leak. A single interval with
p99 above the limit, a falling largest free block and a heap trend that could not be assessed
are warnings.

Heap leak rule: samples from the first `max(1, number of streams)` intervals are a warm-up and
are excluded (the first ON of each stream sets up Wi-Fi/CC1101 driver memory once). Over the
remaining samples the runner fits a Theil-Sen slope of `heap.free` (median of pairwise slopes,
robust against single outliers) and reports it in bytes per hour with the least-squares slope.
It FAILs when the decline is steeper than `max(--soak-max-leak, --heap-tolerance / hours)`:
`--soak-max-leak` defaults to 2048 B/h (the T-Embed had about 100-115 KB free in companion
mode; 2 KB/h would take about half of that in a 24-hour session), and `--heap-tolerance`
(4096 B, the allocator noise floor the `heap` check already uses) keeps short runs from
failing on noise. The report states this detection limit: about 13-14 KB/h for a 20-minute
run, 2048 B/h from about 2 hours on. A leak smaller than the limit is not ruled out.

Ctrl-C (or a lost link) ends the soak, sends OFF for an active stream and aborts or removes the
scratch upload, then writes the JSON, Markdown and CSV reports marked interrupted (exit 130).

What a soak PASS proves: over that time, on that board and firmware, the companion link
carried the passive mix with exact frame accounting, no rejects or device noise, no reboot, no
heap decline above the stated limit, scratch files cleaned up and streams silent after OFF. It
does not prove radio receive quality (the streams may see 0 events), app-side behaviour, USB
unplug/replug, battery/thermal behaviour, long sessions with transmitting features, or that a
leak below the detection limit is absent. `tools/test_hw_acceptance.py` runs seconds-long soaks
against `tools/mock_device.py` (heap leak, reboot, noise, idle exit, Ctrl-C); those prove the
runner, not the device.
