# Changelog

All notable changes to Brucia are documented here. Brucia is a fork of
[Bruce](https://github.com/BruceDevices/firmware) plus a native Android companion app
that offloads computation to the phone. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions are the app/firmware release.

## Unreleased

- App targets Android 15 (SDK 35) and draws edge to edge; the bottom bar stays above
  the keyboard. AndroidX core, activity, lifecycle and Room updated (database schema
  unchanged).
- Firmware builds from a clean checkout now apply the bundled library fixes on the
  first build (the AAC decoder bounds fixes were previously skipped until a second
  build); the build stops if an expected library fix no longer applies.
- Builds report firmware size against the board's app partition and fail when it is
  nearly full.
- `ir rx` no longer leaks its reader when no signal arrives.
- `tools/hw_acceptance.py`: passive, allowlisted acceptance checks for a connected board.
  First run on a T-Embed CC1101 (2026-11-05): link diag keys are compared as a set (the
  firmware emits `err` first; JSON key order is not part of the contract), and a board left
  in Bruce's serial CLI is reported as such instead of "framing lost".
- Companion link fixes from the first hardware acceptance run (T-Embed CC1101, 2026-11-05;
  not yet re-verified on hardware):
  - Large frames from the app could be lost: the firmware's serial RX buffer (1024 bytes on
    PSRAM boards, 256 without) dropped whatever arrived while the companion loop was busy, so a
    1.4 KB upload chunk never got a reply. The buffer is now sized at boot to hold a whole frame:
    8448 bytes on PSRAM boards (+7.4 KB internal RAM), 2048 bytes on boards without PSRAM
    (+1.8 KB). This also applies to Bruce's serial CLI (fewer dropped bytes, nothing else
    changes). CAPS reports the safe frame size as `rx_max`; the app and the acceptance runner
    size upload chunks and speaker/FM audio frames to it, assume 768 bytes for uploads with
    older firmware, and refuse single commands above it instead of losing them.
  - Device text in the framed stream (for example `cc1101 Connection OK` when Sub-GHz receive
    starts) no longer drops the link: the app and the runner skip it and resync on the next
    frame, counting the skipped bytes ("noise bytes" in Diagnostics, a warning in runner
    reports). The Sub-GHz, NFC (EMV), IR receive/raw send, BLE spam/BadBLE, FM and NRF24
    debug prints on companion paths are silent in companion mode, and ESP-IDF log output is
    muted until companion mode exits. Bruce's own output outside companion mode is unchanged.
- CI runs on a pinned `ubuntu-24.04` image instead of `ubuntu-latest`.
- Contributor setup documented from a fresh clone (prerequisites and exact steps).
- Android build toolchain updated (Gradle 9.2.1, AGP 8.13.2, Kotlin 2.4.21, Room 2.8.5,
  location services 21.4.0); database schema unchanged.
- Jetpack Compose updated (BOM 2026.06.01: Compose UI 1.11.4, Material3 1.4.0) with
  AndroidX lifecycle 2.10.0. Progress bars and sliders use the new Material3 design
  (rounded track with a gap and an end dot) drawn in the Brucia colors: the accent on a
  dark accent-tinted track. Buttons, cards, switches, dialogs and menus keep their
  previous colors; outlined buttons keep accent-colored labels. Copy actions use the
  current Compose clipboard API.
- Companion firmware code is built without C++ exceptions (5-12 KB smaller depending on
  the board); a post-link check fails the build if any exception handler elsewhere in the
  firmware were lost.
- LITE firmware builds (the `LAUNCHER_*` boards and `m5stack-core4mb`) leave out five
  companion extras to free app-slot space: the MIFARE key check, the NRF24 commands, the
  companion IR commands (TV-B-Gone, IR receive/raw send, IR jammer), the BLE GATT explorer
  and `diag`. Bruce's own menus and CLI are unchanged, and so are full builds. CAPS reports
  the absence (`features.<group>: false`, `caps.nrf24: false`) and those commands answer
  ERR 501. The app disables these actions with a reason; on LITE firmware it sends IR
  receive and raw IR replay through Bruce's `ir rx` / `ir tx_raw` CLI instead.
  LAUNCHER_CYD-2432S028: 2,509,008 -> 2,488,848 B (98.17% -> 97.38% of its app slot);
  m5stack-core4mb 14,352 B smaller. Not tested on hardware.
- Replaying a saved raw IR button through Bruce's `ir tx_raw` CLI now quotes the samples
  (`ir tx_raw 38000 "9000 4500 ..."`); unquoted, Bruce's CLI parser rejected every raw
  signal with more than one duration. Raw items and geo-signals saved earlier are rewritten
  to the quoted form when replayed (stored data unchanged).

## [0.1.4] - 2026-11-05

Software-only release: everything below passed unit, host, emulator and build checks;
none of it has been verified on a device yet (see TESTS.md).

- Device diagnostics: new `diag` command (CAPS `features.diag`) reports reset reason,
  uptime, heap/PSRAM and bounded link counters since boot (frames, rejected frames by
  kind, ERR replies by code, busy replies) plus the existing FM/mic drop figures. Sent
  only when the app asks; nothing leaves the phone. The app now counts malformed frames
  it used to skip silently, timeouts and unmatched replies, shows both in a Diagnostics
  card and includes them in log exports and session reports.
- Wi-Fi capture descriptor (CAPS `features.capture_descriptor`): the handshake/PMKID
  capture reply names the file this run actually created or grew for the target AP,
  with size, CRC-32, measured record count, stop reason and whether the file accumulates
  earlier runs. The app imports exactly that file and verifies size/CRC; an incomplete
  capture imports nothing. Older firmware keeps the listing-diff import, labeled.
- Mifare key check: replies carry tag UID, sectors tested and a complete/partial flag;
  Stop now ends a batch already running on the device (`stop:"cancelled"`, hits so far
  kept). The app stops on a partial batch or a tag change between batches.
- During long blocking operations the device now answers PING and replies `ERR 409
  device busy` to other commands instead of silently dropping them.
- Speaker playback no longer blocks the link: it starts at once, can be stopped and
  reports its end (`features.speaker_play_async`). FM PCM reports accepted/lost
  samples and the app resends the remainder instead of losing audio silently.
- CAPS reports per-module evidence (compiled / configured / probed / initialized, probe
  result, static pin conflicts) and build provenance (commit, dirty, time); the app shows
  a Hardware evidence card. Probes are read-only where possible; the LoRa probe leaves the
  radio asleep and the RC522 antenna is switched off after its check.
- Sub-GHz receive: init failures are reported (ERR 503) instead of aborting or leaving the
  Listen switch on; spectrum sweeps are validated (ERR 400) on a whole-kHz grid; raw events
  say when the device buffer may have truncated a signal; saved-signal write failures are
  reported.
- Remaining request/response tools (NFC read, Sub-GHz receive, IR learn, device panel,
  file browser, capture list) moved to owned services: Stop all and disconnect end their
  waits, a newer request replaces an older one, and the file browser no longer stays on
  "Loading..." after Stop all. `rf rx`, `ir rx` and the device panel time out after 20 s
  (the firmware bounds) instead of 650 s.
- USB ownership: one owner per port between the companion link and flashing, a single
  permission receiver, and the connection service keeps the wake lock through a flash
  and does not restart itself after being killed.
- Updates: downloads verify their size/hash before use; concurrent or cancelled APK and
  firmware downloads can no longer overwrite each other or continue into a flash after an
  abort. Release catalog URLs are checked strictly on both app and website.
- MITM HTTP parser rejects more malformed framing (CR-less chunk lines, oversized chunk
  extensions, DEL, HTTP/1.0 with Transfer-Encoding, lowercase CONNECT).
- Recording saves never overwrite a same-second folder and remove partial folders on
  failure.
- Firmware builds are reproducible: the build year, `__DATE__/__TIME__` and source paths
  come from the commit, so two checkouts of the same commit produce identical binaries.
- Themed (monochrome) launcher icon; Kotlin/lint warnings cleaned up.

- SSTV batch and streaming decoders now follow each measured line sync, avoiding
  cumulative Scottie S1 horizontal drift from fractional sample timing. Four
  synthetic S1/S2 regressions cover first and last lines; external interoperability
  remains unverified.

- Managed device uploads now stage data, verify length/CRC and require an explicit
  matching commit ACK. New `transactional_uploads` CAPS support is mandatory for
  uploads; older verified-file firmware still supports reads, without unsafe upload
  fallback. Lost commit acknowledgements require destination verification before retry.
- Firmware adds bounded, single-owner `fs_upload_*` commands and journal recovery.
  LittleFS uses one rename; FAT preserves the previous file in a backup while
  promoting, restoring it on failure or retaining it for recovery if rollback fails.
  Cleanup failures after commit are reported separately from replacement success.
  Reserved paths and concurrent legacy writes reject. Simulated failure/reset tests
  cover the transaction; physical power-loss behavior is not certified.

- Centralized hardware preflight for native commands and the app's radio CLI aliases,
  including macros, console and structured PCM. Missing CAPS cannot enable hardware;
  cleanup commands remain usable. Offline Crack and mixed SSTV/Tone screens remain
  reachable without the capture module, with individual operations still gated.
- Extracted `StreamSubscriptions`: one owner per request, bounded ACK wait, validated
  monitor filters, exclusive stream replacement and reconnect/cancellation handling.
  Missing/failed ACKs retain a cleanup obligation, so Stop all/disconnect still try
  OFF instead of losing track of a possibly active radio.
- Fixed BurstLoop and WIDS flags stuck on immediate completion or a cancelled scope.
  Blank burst failures now terminate. WIDS/jam-detect/Sub-GHz duration checks prevent
  invalid or overflowing waits. LoRa Stop now cancels the app wait and suppresses
  late packets; send/config supersession and scope cancellation clear its indicator.
  LoRa receive durations are bounded to 1–120 s; the firmware listen window can continue.
- Audio auto-subscriptions react to connection/capability changes; missing/failed mic
  starts do not leave DTMF or recording active. Failed legacy RF/NFC reads are not
  persisted as successful captures. Added the software contract/evidence matrix to
  the existing controls checklist; hardware acceptance remains pending.

- App and firmware updates now load version, notes, APK and all board images from
  one release response, exposed as one UI state. Refresh immediately clears the
  previous assets and selection; network/parse failures cannot leave old downloads
  enabled. Obsolete board-menu selections are rejected, and duplicate checks share
  the active request. Cancelled checks cannot publish a late response.
- Refresh is disabled during APK download/installer handoff and firmware flashing;
  board selection is locked through bootloader re-enumeration. Missing or unsupported
  firmware images are rejected before taking over USB. An install-permission request
  is tied to its APK URL and invalidated by a refresh. Per-board URL validation,
  image validation and version ordering are unchanged. Live OTA/flash is unverified.

- FM/SSTV preparation, encoding, PCM streaming and finalization now belong to
  `PcmStreamService`. Stop works during encoding; runs keep their original session,
  reject duplicate starts, close their sources and serialize old cleanup before a
  newer begin. Late readers/finalizers cannot send PCM or overwrite a newer run.
- Fixed the speaker PCM wire format: the app now sends `spkr_pcm_begin` with `d.sr`
  and `spkr_pcm_data` with `d.pcm`, matching the existing firmware handlers. The
  previous appended-string arguments did not match those handlers. Added correlated
  structured CMD waits; no firmware command/schema/version change.
- Await begin and frame ACKs, preserve errors instead of showing “Finished”, and
  pace 16 kHz S16LE mono PCM with a monotonic clock. SSTV encoding and Android file
  decode check cancellation; decoder buffering is bounded to 16 MiB. FM microphone
  read errors become failures. Hardware stop, provider/codec behavior and FM buffer
  loss still need physical verification; firmware can ACK a partially dropped FM
  frame when its ring is full.

- File transfers now use `FileTransferService` and `SessionFileLink`: capture the
  connection when requested, cancel queued/active work on Stop all or disconnect,
  supersede older requests for the same consumer/destination, and suppress stale
  imports/callbacks. Capture reads share the verified session adapter and wire mutex.
  Existing download size/CRC/request-ID validation and upload chunk-size ACK checks
  are preserved; upload inputs are bounded snapshots.
- Manual pulls use unique local files; discarded results remove their staged copies.
  Exports verify the download before opening a destination, check cancellation between
  writes and serialize processing so an old blocking write finishes before a newer
  export. Provider writes already started cannot be rolled back; managed firmware
  uploads now use the transactional contract above. Legacy direct writes remain non-atomic.
- Template-upload cancellation now reaches the actual transfer in both directions;
  a cancelled upload cannot start the portal or leave its status at “Starting...”.
  Upload command-log entries receive a terminal cancellation result.

- Added an explicit service cleanup inventory shared by Stop all and disconnect.
  Stop all now reaches the migrated device services, capture workflow, parallel
  callbacks, stream subscriptions, FM/SSTV, phone mic and timeline. Firmware stop
  requests retain their real ACK status; the command log no longer marks every
  running entry failed before cleanup completes. Offline dictionary work is stopped
  by Stop all but remains independent of link disconnection.
- Disconnect clears GATT exploration, speaker song and portal ownership and cancels
  remote readers. Parallel BLE/GATT/speaker requests and legacy console/IR/NFC/RF
  callbacks are cancellable; queued IO captures its original connection. Removed
  the unused JSON callback dispatcher.
- Fixed shared `DeviceOperation` cancellation: obsolete replies cannot invoke result
  mappers or chain follow-up commands; cancelled scopes cannot leave busy stuck.
  Mapper errors become terminal failures. BLE HID Stop now reports “Stopped.”
  instead of keeping “Starting...”. Local Stop does not prove firmware execution
  ended; audio encoder/finalizer ownership is now covered by W11q above.

- Macro execution moved to `automation/MacroRunner`: snapshot steps and the original
  connection, preserve the double-start guard, and ignore old results after Stop
  and restart. The first failed command/timeout stops the sequence instead of
  advancing and eventually displaying “finished”. Unexpected errors retain their
  cause; cancellation also clears running state when the parent scope is closed.
- Cmds Stop all now stops macro sequencing. Raw macro commands use the generic
  650 s ACK wait instead of the 12 s radio-burst default. Stop cancels the app
  sequence; a command already sent can continue on the device. Direct/builder
  pauses use the same 0–60 s bounds as saved macros; saved JSON is unchanged.

- Mifare dictionary batches moved to `MifareKeyCheckService`: Stop/reset/disconnect
  and restart discard stale replies; a run keeps its original connection and key
  list. Failures stop subsequent batches and preserve earlier hits. Invalid reply
  data is rejected instead of becoming a recovered key or an empty success.
- Added Stop batches and integrated it into Cmds Stop all. Cancellation stops app
  sequencing; the current firmware batch may continue. Error/cancelled runs no
  longer display the completed-run “no keys” message. Existing commands, batch
  size and firmware are unchanged.

## [0.1.3] - 2026-10-31

App and T-Embed CC1101 firmware, published to the private BruciaDev repo. Hardware
features remain pending on-device acceptance (see TESTS.md).

- Guided capture/import moved to `CaptureWorkflow`: fail on command errors, pin
  connection identity and ignore late results after cancellation/reconnect/restart.
  Compare matching file contents before/after; reject unchanged, foreign-BSSID or
  ambiguous captures instead of guessing freshness from names. Validate parsed AP
  identity, bound candidate reads and detect suspicious directory truncation.
- Added Cancel import (firmware capture can continue to its own timeout). Accepted
  captures are copied locally under unique names so delayed saves cannot overwrite
  newer ones; a local copy failure is logged while the parsed target stays usable.

- Extracted offline dictionary test lifecycle from the ViewModel into
  `DictionaryTestService`. A cancelled/superseded job cannot publish into a newer
  run; cancellation is checked by the CPU loop and readers close on completion.
- Dictionary tests require a loaded target; synthetic targets are exclusive to the
  demo. Count actual candidates on early matches/exhaustion, preserve target/rules
  per run, and display IO errors separately from a completed run with no match.

- Fixed: the WIDS monitor kept re-sending commands at full speed after a disconnect and was
  not stopped by "Stop all"; it now ends when the link closes, backs off after a failed
  window, and stops with everything else.
- Evil Portal, GATT exploration, jam detection and Sub-GHz brute force moved to tested
  services. Stop now wins over an Evil Portal start that is still pushing its template,
  and a stale GATT read no longer reappears after a stop or reconnect.

## [0.1.2] - 2026-10-30

App and T-Embed CC1101 firmware, published to the private BruciaDev repo. Hardware
features remain pending on-device acceptance (see TESTS.md).


- Extracted the Android tool catalog into a pure-JVM validated feature registry
  (`features/FeatureRegistry` + `BuiltinFeatures`): 50 tools / 11 categories, titles,
  descriptions, caps (incl. the Wi-Fi gate) and order preserved; lookup by stable ID
  or legacy tab ID (absent lookups return null); duplicate/blank-ID validation and
  defensive copies; unit-tested. `MainActivity` now renders the home grid, search and
  capability gating from it; its duplicate local catalog was removed.
- Extracted command/reply correlation into a pure-JVM `session/CommandSession`: register
  before send; release on reply, timeout, cancellation or local send failure (reported as
  ERR, never thrown); injected caps preflight; idempotent `close` that fails each pending
  command once and ignores late replies. Unit-tested with a fake transport. Wired into
  `WardriveViewModel`: commands, stream SUB acks, file reads/uploads, ACK/ERR routing and
  the ping watchdog use one session per connection; the duplicated pending maps are gone.
  A late reply after disconnect no longer appears in the console.
- PCAP dissect analysis moved to a pure-JVM, unit-tested `wifi/PcapDissector`. Captures
  over 32 MiB are now rejected instead of read whole; leaving the screen cancels the
  analysis; BSSIDs with equal counts are listed in a stable order.
- iButton read/write moved to a unit-tested `ibutton/IButtonService` behind a small
  `session/CommandRunner` interface. Waits at most 10 s (was 650 s), can be stopped from
  the Gadgets screen, ignores replies from superseded operations and validates the
  16-hex id before sending.
- Device clock and Wi-Fi MAC moved to unit-tested services on a shared single-owner
  `session/DeviceOperation` (also used by iButton). They wait at most 10 s (was 650 s) and
  reject an implausible epoch or a malformed MAC before sending.
- Script runner, device theme/LED controls, device Wi-Fi join and ESP-NOW moved to the
  same tested services. Bounded waits (script 25 s, join 30 s, others 10 s) replace the
  650 s default; rapid theme/LED changes keep only the latest result; an oversized script
  is rejected before sending; clearing the script output no longer drops a running
  script's result.
- Speaker (volume, tone, file, song, DTMF) and mic recording moved to tested services with
  bounded waits (tone 15 s, file 10 min, recording its length + 10 s). A tone above
  20 kHz is rejected before sending, and a late reply no longer clears the name of a newer
  song. Known firmware limit: `speaker_play` blocks the link until the file ends.
- IR: receive-once and custom-remote key capture now decode the firmware's one-line `ir_rx`
  reply ("NEC addr=.. cmd=.." or "raw: .."); before, they never produced a result on the
  device. IR receive/capture and TV-B-Gone moved to tested services (waits 20 s and 75 s).
- BLE HID keyboard/presenter, BadBLE and media control moved to tested services. HID waits
  at most 10 s (was 650 s), stopping HID never leaves a stale "Starting...", and key presses
  no longer interfere with a pending start.
- LoRa and Ethernet moved to tested services with bounded waits taken from the firmware
  (LoRa receive and the Ethernet tests wait their duration plus a margin); a late LoRa
  reply no longer delivers a stale packet or clears a newer receive indicator.
- NFC advanced tools (NDEF push, masked EMV read, UID write, Amiibo, nested, mfkey32, SRIX,
  125 kHz) moved to one tested service with four independent status lines and waits taken
  from the firmware (NDEF 75 s, timed reads their duration + 15 s, unsupported-headless
  tools 10 s).
- Fixed: "Stop all" (and the link watchdog) left the Sub-GHz jammer, IR jammer, ARP spoof and
  iBeacon loops running. All 11 burst loops now run on one tested engine and register with
  a registry, so stopping all cannot miss one.
- Responder, Karma and Brucegotchi run on a tested long-operation engine: Stop asks the
  device to end early and still shows the final result; Responder and Karma have their own
  Stop instead of a shared one.
- Firmware iButton: one Arduino-free core (`ibutton_core`) shared by the device menu, `.ibtn`
  loading and the companion, with a native host test. Companion iButton commands now refuse
  with "iButton pin not configured" (ERR 409) instead of driving the default GPIO0, and CAPS
  reports `ibutton` when a pin is configured on the device, which enables the app controls.
- Build: `patch.py` loads `tools/web_resources.py` by path, so another `tools` package
  imported inside PlatformIO no longer breaks the pre-build step.

## [0.1.1] - 2026-10-30

Continues 0.1; the local 0.0.2 candidate was superseded without relabelling its
artifact. Android versionCode retains the 1,000,000 epoch. Published to the private
BruciaDev repo (APK + T-Embed CC1101 firmware). Current build evidence and
limitations are in TESTS.md; radio features remain pending on-hardware verification.

Additional fixes: exact URL/asset identity and ambiguous-board/APK rejection;
strict HTTP length/chunk syntax; accurate website TLS/NFC/network-use copy.
Documentation consolidated into the project maintenance skill, its task ledger,
TESTS.md and ROADMAP.md. Historical upstream version entries below remain historical.

### Stabilization changes

| Area | Result |
|---|---|
| File downloads | One serialized, bounded reader; exact request IDs, ordered chunks, terminal size/CRC32, 16 MiB cap and 120-second deadline. Missing/duplicate/invalid chunks, premature EOF, timeout and disconnection fail explicitly. A growing device file is limited to its initial size. |
| File uploads | Managed reads/writes share a mutex. Uploads retain their original connection and validate each acknowledged byte count. Empty files and internal path spaces work; malformed Base64 is rejected before truncation. Directory writes fail. Writes remain non-atomic: failure may leave a partial destination, never a successful save. |
| Protocol support | `features.verified_files` explicitly advertises the transfer contract. Missing/malformed support blocks managed transfers with an update message, instead of guessing which request owns legacy ID-zero data. Protocol version remains v0 with an optional feature field. |
| Command lifecycle | Replies require literal boolean success and exact IDs. Callback ownership is released on cancellation, timeout, send failure and disconnect. General command waits allow the existing 600-second operations; stream and file waits have shorter bounds. Invalid frame lengths close the session instead of silently retaining an unknown boundary. |
| Streams and disconnect | Serialized SUB writes ignore superseded requests; shared-radio peers lose stale running indicators. Firmware audio modes are exclusive. Disconnect stops macros, DTMF/FM/playback jobs, resets stream/recording indicators and completes outstanding operations. Watchdogs use monotonic time. |
| Recording | A bounded PCM buffer preserves audio at the recording limit and on screen exit/disconnect. Pending audio can be saved offline. Failed export retains samples for retry; completion of an older export cannot delete a newer recording. Odd PCM is rejected and large backing buffers are released after explicit discard/save. |
| Updates | One stable release supplies APK/board assets; immutable asset URLs replace historical fallbacks. Redirects require HTTPS and have bounded hops/cleanup. Downloads reject empty/truncated/oversized bodies, honor cancellation and promote temporary files only after completion. APK installation checks the package identity. |
| Firmware preflight | Before USB writes: target ESP32-S3 headers, partition bounds/overlap and MD5, segment lengths/XOR checksums and appended SHA-256 are validated. Wrong-board selection fails. Capability migration handles old firmware 0.8.x versus the fork's 0.1 version line. |
| HTTP inspection | The extracted parser bounds lines, headers, body allocation and total message time; rejects ambiguous lengths and partial bodies; handles interim responses and HEAD framing. Worker/connection queues and leaf-certificate cache are bounded. Stop closes tracked sockets; failed connection/handshake paths release resources. Application context replaces a retained generic Context. |
| Persistence | Destructive migration fallback removed without changing database version 3. The current schema was recovered from Room-generated SQL/column validation/identity metadata and checked with SQLite. Future KSP builds export schema history. Unknown old schemas are not guessed or deleted. |
| Native dependencies | Full/light root Git libraries use observed immutable commits; main registry dependencies use exact installed versions. Fixed NimBLE return-type ABI mismatch, M5GFX/TFT_eSPI class-layout inconsistencies, libmad's output-size declaration and observed AAC SBR noise bounds. Licenses/attribution are retained. |
| Diagnostics/UI | Logging tolerates an unavailable logcat sink and synchronizes date formatting. Fixed locale-dependent technical labels, modifier parameter order and redundant API branches below minSdk 26. |
| Website/docs | The flasher discovers exact assets from the same stable release, disables unavailable choices and handles private/404 releases explicitly. No `lastRelease` placeholder or silent historical image fallback. Three.js and the battery meme remain. Comparisons distinguish hardware, implementation and verification. |

## [0.6.15] - 2026-10-28

### Fixed
- **"No response from device" on beacon/BLE spam and the other Start/Stop ops**: the bounded ops
  only ACK after running for the full burst (8s), but the app waited only 6s for the ACK, so every
  burst timed out and reported "no response". The ACK wait now scales with the burst duration.

## [0.6.14] - 2026-10-28

### Added
- **IR graphical remotes ("telecomandini")**: the IR tool now has a Remotes tab with real on-screen
  remote controls - Power, Mute, Volume/Channel rockers, a D-pad + OK, number pad, Input/Menu/Home/
  Back - that transmit the mapped IR codes on tap. Ready-made TV remotes (Samsung, LG, Sony, Philips,
  Panasonic, Vizio, TCL, Hisense, ...) are surfaced up front, and any of the 1648 catalog devices can
  be opened as a remote (buttons mapped by fuzzy name). The Catalog and Raw (TV-B-Gone / learn /
  raw-tx) views are kept.

## [0.6.13] - 2026-10-28

### Added
- **File manager overhaul**: pull a device file to a location you choose (SAF), open files in other
  phone apps ("Open with..." -> Wireshark for .pcap, etc.), an in-app editor + viewer for text/JS
  and an HTML preview (sandboxed WebView), default templates bundled in the app (script.js,
  payload.ducky, signal.sub, page.html, config.conf, notes.txt), and push files/templates to the
  device SD ("Save to device" / new file) via the new firmware write command. The Monitor screen
  gets an "Open pcap" button straight to Wireshark.
- **Firmware: `fs_write` / `fs_write_append`** - the app can now write/create files on the device
  SD/LittleFS (base64, chunked for large files). Templates live in the app and are pushed on demand
  so they do not consume device memory.
- **Firmware: Remote mirror no longer starts all-black** - `display start` now replays the current
  screen's draw ops (TFT log), so the mirror shows the device screen immediately.

## [0.6.12] - 2026-10-28

### Changed
- **Audio tools redesigned**: a new combined "Audio" dashboard shows Spectrum + Waterfall +
  Oscilloscope + SSTV as separate panels all live at once, from a single device-mic subscription.
- **Oscilloscope is now a real 2D scope** with a graticule/grid, trigger and Vpp/RMS/freq readouts,
  plus two XY "draw with sound" vectorscope modes: XY-mono (phase plot from the ESP mic) and
  XY dual-mic (X = ESP mic, Y = phone mic - place each near one speaker).
- **SSTV is now continuous always-listening** and decodes ALL modes in parallel (Robot36/72,
  Martin M1/M2, Scottie S1/S2) in a scrolling cascade - it latches onto a transmission whenever it
  appears, no need to pre-select the mode or start at the exact instant.

## [0.6.11] - 2026-10-28

### Fixed
- **Mitigate the spontaneous device reboot during spam**: each spam/op burst re-initialized the
  device radio (BLE/Wi-Fi init+deinit per command); at 2s bursts that churned the radio ~30x/min,
  which can crash/reboot the ESP32. Bursts are now 8s (still abortable within 8s), cutting the churn
  ~4x. The definitive fix (keep the radio up / run the op in a device-side task) is a firmware
  change tracked separately.

## [0.6.10] - 2026-10-28

### Fixed
- **Wi-Fi beacon spam "copies" now spams YOUR SSID N times** instead of random generated names:
  each SSID you type is broadcast `copies` times. Default SSID is now `1vcian.me/BruciaDev`.
- **App stuck on "Connected" after the device reboots**: if the device restarts out of companion
  mode while the USB-CDC stays open, every command errored but the UI still said Connected. A link
  watchdog now detects the device has stopped answering (missed pings) and automatically
  re-negotiates companion mode (status shows "Reconnecting..."), recovering once the device is back;
  it also stops any running op loops that would keep erroring.

## [0.6.9] - 2026-10-28

### Changed
- **All remaining long/continuous ops are now non-blocking Start/Stop** (not blocking one-shots):
  Wi-Fi deauth and NRF24 jammer / sniffer / MouseJack / scanner run as short cancellable ~2s
  bursts (shared engine, like spam), so the device and companion link stay responsive and you can
  stop them any time. Leaving the screen or opening an audio tool stops any running op; disconnect
  stops them all. Handshake / PMKID capture stay single bounded captures (chunking would fragment
  the pcap). Quick single-shot commands (IR, Sub-GHz replay/tx, NFC, speaker, etc.) are unchanged.

## [0.6.8] - 2026-10-28

### Added
- **Spam is now continuous Start/Stop instead of a blocking one-shot** (BLE and Wi-Fi): it runs as
  short ~2s bursts you can Stop at any time, so the device/link stay responsive and you can leave
  and do other things. Opening an audio tool (Spectrum/Waterfall/Oscilloscope/SSTV) auto-stops spam
  so the mic stream is serviced - fixes the "audio tools show nothing while spamming" problem.
- **Number of copies**: choose how many advertisements (BLE) / fake APs (Wi-Fi) to blast. BLE now
  broadcasts a burst of N distinct advertisements per cycle (firmware) so many devices appear at
  once; Wi-Fi generates N fake SSIDs. Default 10, 1..100.
- **Command queue / log viewer**: a "Cmds" button in the top bar opens a popup showing every
  command sent to the device as RUNNING / DONE / FAILED with its result and timing (badge shows the
  running count) - visibility into the single-threaded link so nothing looks mysteriously stuck.

### Firmware
- BLE spam blasts a configurable burst of distinct advertisements per cycle (`ble_spam_platform
  <target> <secs> [copies]`, default 10, clamp 1..100); default behavior unchanged for old callers.

## [0.6.7] - 2026-10-28

### Fixed
- **BLE spam froze the app/link when fired repeatedly**: BLE spam is a blocking, time-bounded op on
  the device (it holds the companion loop for its whole duration), and tapping it again (e.g. iOS
  then Android) queued more runs that piled up and made everything look frozen. The app now rejects
  a new BLE spam while one is already running, disables the spam buttons and shows a clear
  "device busy for Ns" status (with a safety timeout so the controls never stick), so requests can
  no longer accumulate.

## [0.6.6] - 2026-10-28

### Fixed
- **Connect crash hardening (follow-up to 0.6.4/0.6.5)**: in addition to the foreground-service
  fix, the companion read loop now catches any read/parse/handle error instead of letting it reach
  the coroutine's default handler and crash the app. Combined with the `startService()` change, the
  Connect flow can no longer be crashed by the background service or by a malformed frame.

## [0.6.5] - 2026-10-28

### Fixed
- **App still crashed on Connect on some devices**: the connection service used
  `startForegroundService()`, which forces the service to call `startForeground()` within ~5s or
  the OS crashes the app - impossible to honor when the `connectedDevice` foreground type is
  refused. Switched to `startService()` (no such contract) with a best-effort foreground upgrade,
  fully wrapped, so Connect can never be crashed by the background service; it just degrades
  (no screen-off survival) where the platform refuses the foreground type.

## [0.6.4] - 2026-10-28

### Fixed
- **App crashed on Connect (Android 14+)**: the foreground connection service started with the
  `connectedDevice` type but the app lacked a qualifying permission, so `startForeground()` threw
  (SecurityException) inside the service and crashed the app the moment you tapped Connect. Fixed by
  (a) adding the normal `CHANGE_WIFI_STATE` / `CHANGE_NETWORK_STATE` permissions that satisfy the
  Android 14+ precondition, and (b) making the service start fully defensive - if the platform still
  refuses it, the service degrades gracefully (no background survival) instead of ever crashing.

### Changed
- **SSTV Scottie S1/S2** are now broadcast-accurate (correct sync-pulse placement and line timing),
  so a genuine Scottie signal from another station/app decodes correctly - not just Brucia's own
  transmissions. Robot36/72 and Martin M1/M2 unchanged.

## [0.6.3] - 2026-10-28

### Added
- Website: a 12th feature card ("Private and local") so the toolkit grid is balanced; the
  comparison table now names the reference board (LilyGo T-Embed CC1101).

### Changed
- **Safety: in-app firmware flashing is now gated to the LilyGo T-Embed CC1101.** The in-app
  flasher and bundled image are T-Embed-specific; on any other board the update is refused with a
  message pointing to the per-board firmware + web flasher, so a wrong image is never written.
  (ESP32 boards cannot be permanently bricked by flashing - the ROM bootloader always allows
  recovery - but this avoids the bad experience of flashing the wrong firmware.)

## [0.6.2] - 2026-10-28

### Added
- **Crypto donations**: Bitcoin and Monero addresses added as support options, each with a QR
  code and copy-to-clipboard - in the app (Home support card, QR generated on-device), on the
  website (support section) and in the README.

## [0.6.1] - 2026-10-28

### Added
- **GitHub Sponsors** as a support option alongside Buy Me a Coffee: in-app (a "Sponsor on
  GitHub" button on the Home support card), on the website (header + support section) and in the
  README, plus `github: [1vcian]` in `.github/FUNDING.yml` so the repo Sponsor button shows it.

## [0.6.0] - 2026-10-28

### Added
- **Audio suite (5 new tools, all driven by the ESP microphone/speaker)**: Audio Spectrum (live
  FFT bars), Waterfall spectrogram (scrolling time x frequency image), SSTV (RX decodes Robot36 /
  Robot72 / Martin M1-M2 / Scottie S1-S2 into an image; TX encodes a chosen image and plays it out
  the ESP speaker), Tone tool (dominant-frequency + DTMF detector, tone/DTMF generator), and an
  Oscilloscope (time-domain waveform with edge trigger, adjustable time base/gain, Vpp/RMS/freq
  readouts). All DSP runs on the phone; the ESP only captures and plays I2S.
- **Firmware audio streams**: a new continuous raw-PCM mic stream (`mic.pcm`, gapless 16 kHz for
  SSTV) and streamed raw-PCM speaker playback (`spkr_pcm_begin`/`spkr_pcm_data`/`spkr_pcm_stop`),
  both capability-gated and cross-board safe.
- **Background connection lifecycle**: a foreground service (`connectedDevice`) with a persistent
  notification plus a single, guaranteed-release wake lock, so the USB link and active captures keep
  running when the screen is off (replaces the old keep-screen-on workaround).

### Notes
- SSTV Scottie uses a self-consistent sync (round-trips in the app); decoding an external
  broadcast-Scottie signal is a known fidelity gap. Robot36 is broadcast-accurate.
- The audio mic/speaker path is build- and unit-test verified (SSTV encode->decode round-trips);
  live hardware capture/playback is pending the device being reconnected.
- Surviving a full app swipe-away (moving the transport out of the Activity-scoped ViewModel) is a
  follow-up; screen-off is already covered.

## [0.5.5] - 2026-10-28

### Fixed
- **Remote screen mirror froze after a device reboot**: if the ESP32 rebooted while the Remote
  mirror was open, nav kept working but the mirrored screen went blank/frozen - the firmware's
  async-serial stream resets to off on boot while the USB link survives. The app now runs a
  mirror watchdog that detects the device is alive (raw bytes arriving) but sending no screen
  frames, and automatically re-arms `display start`, so the mirror resumes on its own. It never
  fires on a genuinely idle screen (no bytes -> no re-arm).

## [0.5.4] - 2026-10-28

### Fixed
- **Android Back gesture closed the app**: the app's custom tab navigation had no back
  handler, so the system Back swipe/button exited instead of going back. Back now returns
  from any tool to the Home dashboard (mirroring the top-bar back arrow, keeping the active
  category filter); on the Home dashboard Back falls through to the system normally.

### Added
- **In-app Donate button**: a "Support Brucia" card at the bottom of the Home dashboard with a
  "Buy me a coffee" button (buymeacoffee.com/1vcian), mirroring the website.
- **Utilities tool** (new, phone-only, always available): password generator (length + charset
  toggles, SecureRandom), hash calculator (MD5 / SHA-1 / SHA-256), Base64 encode/decode, MAC
  vendor lookup (reuses the OUI database), and a Wi-Fi QR generator (SSID/password/auth ->
  standard WIFI: QR image via ZXing).

## [0.5.3] - 2026-10-28

### Fixed
- **Crack couldn't be stopped**: the PBKDF2 dictionary loop was a tight non-suspending loop that
  `Job.cancel()` couldn't interrupt. It now checks a cancel flag every candidate, so the Stop
  button halts it within one candidate; status shows "Cancelled" and a new crack can start after.
- **Wordlist downloads broken (only rockyou worked)**: the SecLists paths had been renamed
  (`10-million-*`/`probable-v2` -> `Pwdb_top-*`). Fixed the URLs and then **mirrored all wordlists
  on our own GitHub release** (rockyou + top-1M/100k/10k) so downloads no longer depend on
  third-party URLs that can move.

## [0.5.2] - 2026-10-28

### Fixed
- **App unusable - missing scroll**: several screens (Wardrive, Device, Remote, IR, Wi-Fi
  monitor, station/probe sniffers, Report) were non-scrolling or had a nested same-axis list, so
  lower controls/content were unreachable. Each is now a single scroll container - the whole app
  is navigable.
- **Screen/LED (lite mode) not findable**: the device screen-off/on + LED toggles are now a
  prominent "Screen & LED (lite mode)" card at the top of the Device screen.
- **Device reboot while connected**: the runtime NRF24/W5500 capability probes ran on the shared
  display SPI bus and re-ran on every HELLO retry, corrupting the bus and resetting the device.
  The probes now run once and cache, warmed at companion entry away from display traffic.
- **nrf24_sniff watchdog reset**: its dwell loop busy-waited without yielding; added a yield.
- **Speaker silent**: `speaker_tone`/`speaker_play` didn't raise the (possibly muted) volume;
  added an audible volume floor for the duration.
- **ESP-NOW**: `espnow_send` now brings WiFi up to STA and checks init/peer/send results.

## [0.5.1] - 2026-10-28

### Added
- **LoRa + Ethernet** (final Bruce-menu parity): `lora_config` / `lora_send` / `lora_recv`
  (reusing the RadioLib SX127x/SX126x driver) and `eth_status` / `eth_config` (W5500), with
  `caps.lora` / `caps.eth` and capability-gated LoRa + Ethernet app screens. On the T-Embed
  CC1101, LoRa is absent and Ethernet works if a W5500 module is plugged into the QWIIC port
  (runtime-probed).

## [0.5.0] - 2026-10-28

### Fixed
- **Cross-board builds**: the FM-audio I2S path now compiles only on boards that have the
  speaker pins (gated by HAS_NS4168_SPKR), and the boot flame buffer is heap-allocated instead
  of static - fixing undeclared-pin errors on speaker-less FM boards and DRAM overflow on the
  classic-ESP32 CYD boards. All ~60 CI boards build again.

### Changed
- **App UX pass**: scrollable bottom navigation (no truncated labels), a "Connect over USB"
  banner on the home dashboard + a tappable status pill, single-tool categories open their tool
  directly, a Connect button on the BadUSB screen, and clearer tool names (Gadgets, Device info).

### Added
- **Session report + OSINT + insights**: a Report tool that exports a Markdown report of
  everything captured this session, **OUI vendor enrichment** (offline IEEE OUI table) shown
  across AP/station/BLE lists, and a heuristic "insights" summary (platform mix, security
  posture, privacy risks) - clearly labelled as rule-of-thumb, not ML.
- **Speaker / mic / ESP-NOW**: `speaker_tone`, `speaker_play` (play audio on the onboard
  amp), `mic_record` (record the mic to a WAV), and `espnow_send` (broadcast an ESP-NOW message);
  surfaced in the Tools screen (capability-gated).
- **On-device JavaScript** (`script_run`): run BruceJS from the phone and get the output back -
  phone-driven automation. Plus **iButton** read/write and **clock** get/sync (RTC) companion
  commands, surfaced in new Scripts + Tools screens.
- **NRF24 jammer + MouseJack**: `nrf24_jammer` (bounded 2.4GHz jam) and `nrf24_mousejack`
  (headless discovery + keystroke injection of vulnerable wireless keyboards/mice). Authorized-use.
- **BLE GATT explorer**: `ble_gatt_connect` / `ble_gatt_read` - connect to a peripheral,
  discover services/characteristics (props), read values; app shows an expandable GATT tree.
- **BadBLE (Ducky over BLE)**: `ble_badble` runs a Ducky script as a BLE HID keyboard - the real
  device-execution path the USB-disabled BadUSB screen points to (needs a host to pair).
- **IR suite**: `ir_tvbgone` (headless TV-B-Gone, NA/EU), `ir_rx` (learn/decode a remote),
  `ir_tx_raw` (raw replay) - on top of the universal IR catalog; app adds a Device IR actions
  section (TV-B-Gone, Learn, Replay raw, Save to learned).
- **NFC suite**: `nfc_ndef_push` (tap-to-send URL/text/contact, also fills the Spam hub NFC
  tile), `nfc_emv_read` (masked PAN/expiry/scheme), `nfc_write_uid` (magic/Gen1A card UID write).
  amiibo and nested/MFKey32 are surfaced but honestly report "not supported headless" (Bruce only
  has them via external dongles / GUI).
- **MITM HTTPS intercept proxy** (on the phone): a Burp/mitmproxy-style intercepting proxy with
  a generated Brucia CA + dynamic per-host leaf certs, full HTTPS decrypt, a transaction viewer,
  and CA export. Authorized-use only; HSTS/pinning targets fall back to metadata-only tunnels.
- **Sub-GHz replay / jammer / config**: `subghz_replay`, `subghz_tx_raw` (phone-supplied edge
  list), `subghz_jammer` (authorized/bounded), and `subghz_config` (freq/modulation/bw/datarate).
- **Wi-Fi monitor mode** (`wifi.monitor` stream): the ESP captures raw 802.11 over the air
  (channel lock or hop, optional BSSID filter) and streams frames to the phone, which builds a
  real pcap with a Wireshark-style packet list and exports it (open in Wireshark + your PSK to
  decrypt) - the whole-network capture a phone can't do on its own. "Monitor my own network."
- **ARP-spoof** companion command (`arp_spoof`) for authorized transparent MITM redirect
  (reuses NetCut; poisons target+gateway so traffic routes via the phone proxy).
- **Spam hub**: a platform-organized screen (Apple/iOS/Mac, Android, Windows, Samsung, Wi-Fi,
  All) so you pick the target and the app picks the transport; unified
  `ble_spam_platform <ios|android|windows|samsung|all>` firmware command (adds Windows Swift
  Pair + Samsung alongside Apple/Fast Pair). The same actions stay in their native BLE/Wi-Fi
  screens, sharing one code path.
- **Sub-GHz spectrum sweep** (`subghz.spectrum` stream): the CC1101 sweeps RSSI across a
  frequency window and the app renders a live waterfall + RSSI-vs-frequency line.
- **Wi-Fi probe-request profiling** (`wifi.probe` stream): logs which SSIDs nearby clients are
  looking for (device fingerprinting), grouped by SSID or client, with CSV export.
- **BLE Fast Pair spam** (`ble_fastpair_spam`): Android/Google Fast Pair popups (the Android
  counterpart to the Apple ProximityPair spam). Authorized-use only.
- **FM audio transmit (both modes)**: `fm_audio_begin/data/stop` streams phone audio (file /
  tone / phone mic) or the device mic to the Si4713 over I2S. Fully capability-gated - shows
  disabled on boards without the Si4713 (this board), works where the chip is wired.
- **Wi-Fi beacon spam**: `wifi_beacon_spam <secs> <ssids>` - the phone supplies the SSID list,
  the device floods fake networks for the duration (authorized-use only).

## [0.4.0] - 2026-10-28

### Added
- **Runtime module detection**: CAPS now reports real `caps` (cc1101, nfc, nrf24, fm, ir, mic,
  speaker) via I2C/SPI probes + board macros; the app shows or disables each tool page per the
  hardware present ("this device has no X"). FM correctly reports absent on this board.
- **NRF24 (2.4GHz)**: `nrf24_scan` (channel-activity sweep), `nrf24_sniff` (ESB device sniff),
  gated on an actual NRF24 module being attached; new NRF24 tab in the app.
- **In-app updates** (new Updates screen): **app OTA self-update** (checks GitHub Releases,
  downloads and installs the APK) and **device firmware update over USB** (esptool-over-USB:
  reset to bootloader, SLIP, FLASH_BEGIN/DATA/END, then reboot) pulling the latest release bin.
  The USB flash path is implemented and build-verified; on-hardware verification pending.
- **Live mic spectrum** (`mic.spectrum` stream): the device streams an audio spectrum + level
  from the onboard microphone for the phone to visualise.
- **Home dashboard + bottom navigation** in the app, replacing the endless horizontal tab row;
  tools are grouped by category (Wi-Fi / Sub-GHz / NFC / BLE & IR / Device) with a persistent
  connection + battery status bar.
- **PMKID (clientless) capture -> crack**: `wifi_pmkid` companion command captures the
  PMKID/EAPOL to a pcap; the app pulls it, extracts the PMKID and cracks it with rockyou.
- **MIFARE key dictionary attack** (offload): the phone drives a key dictionary via
  `mifare_keycheck`; the device tests keys with the PN532 and returns recovered keys per sector.
- **Mode auto-negotiation**: a `companion` CLI command plus a headless entry path, so the app
  automatically switches a device sitting at its normal menu into companion mode on connect.
- **Wi-Fi station sniffer** (`wifi.sta` stream + app tab): a headless, channel-hopping
  promiscuous sniffer that reports nearby stations (clients) and the BSSID they talk to, so the
  phone can discover devices, not just APs.
- **Self-driven handshake capture + crack**: tap an AP -> the app deauths, captures the
  handshake on the device, auto-pulls the capture over USB and loads it into the cracker - no
  manual pcap pull or path typing.
- **WiGLE upload** for wardrive: upload collected APs straight to wigle.net from the phone
  (enter your WiGLE API name/token once), inspired by Marauder's direct Wigle upload.

### Fixed
- **GitHub multi-board release build** failed on every board: the companion LED code used
  `bruceConfig.ledColor`/`setLedColor`/`CRGB` unconditionally, which only exist on boards with
  an addressable LED (`HAS_RGB_LED`). Guarded it so `companion.cpp` compiles on all boards.
- **App layout**: De Bruijn controls no longer squished, the wordlist list scrolls as one
  smooth list, and NFC clone/emulate and BLE spam are now prominent, clearly labelled actions.
- **Sub-GHz capture to CSV** on the phone: record the live `subghz.raw` stream to a CSV
  (time, frequency, edge count, raw timings) and export/share it - inspired by
  BruceButBetter's spectrum-to-CSV, moved to the phone.
- **docs/STATUS.md**: a per-capability status snapshot and an honest "what's missing" list,
  plus the ideas being adopted from other forks (BruceButBetter, Marauder).

### Fixed
- Long bounded operations (deauth / handshake capture / BLE spam) can now be aborted from the
  device button mid-run, so the device is no longer stuck for the whole duration.

## [0.3.0] - 2026-10-27

### Added
- **Unified flame branding**: the Brucia flame (from a single SVG) is now the app icon,
  the app header, the device home-menu icon and the boot splash. On the device it uses the
  two shades of the selected theme; on Android and the website it uses the fire palette.
- **Animated splash**: the boot flame does a smooth candle flicker (no more black flashing),
  replacing the old Bruce shark ASCII animation.
- **Remote control**: drive the device UI from the phone (D-pad `nav`) with a live mirror of
  the real TFT screen rendered in the app.
- **GitHub wiki**: Home, Getting Started, Features, Architecture, Protocol, Building,
  Contributing, FAQ.
- **SECURITY.md**: authorized-use policy, active-transmission responsibility, and disclosure.
- **Redesigned website**: a GPU three.js ember-flame hero with scroll-reveal, plus the
  browser flasher (ESP Web Tools) for the T-Embed CC1101.
- **Real wordlists**: in-app downloader for rockyou and SecLists (top-1M/100k/12k)
  straight to phone storage, then crackable. Removed the tiny token lists.
- **Full IR catalog**: 1648 models across 808 brands (A-Z) and 45 categories
  (TVs, ACs, LED lighting, soundbars, projectors, ...), converted from Flipper-IRDB,
  browsable **category -> brand -> model**.
- **De Bruijn brute force** (Sub-GHz): transmit the shortest sequence covering every
  fixed OOK code; length (4-24 bit) and pulse width fully customizable, with sequence
  size and estimated transmit time.
- **Command palette** in the Console: every device CLI group (Wi-Fi, Sub-GHz, NFC/RFID,
  IR, BadUSB, Crypto, Storage, System), tap to run or fill in `<args>`.
- **Phone-driven Wi-Fi deauth**: tap an AP in Wardrive -> Deauth (headless, time-bounded
  `wifi_deauth` companion command).
- **Battery %** shown in the device card on every screen, refreshed each ping.
- **Lite mode**: turn the device screen/LED off from the app to save power; both
  auto-restore if the connection drops, so the device never stays dark.
- Live WPA capture->crack flow documented end to end (download rockyou, deauth,
  capture on device, pull the pcap over USB, crack on the phone).

### Changed
- Home-menu entry renamed from "Brucia" to "Brucia App".
- **BadUSB** is disabled while connected over USB (the device's USB port is the link to the
  phone, so it cannot also act as a HID); the payload library stays browsable as reference.
  It remains usable in standalone mode or over a future wireless transport.

### Fixed
- **Device crash on repeated Sub-GHz toggles**: the companion RMT RX channel was disabled but
  never deleted, exhausting the S3's few channels and rebooting the device. It is now released.
- **BLE scan stall**: the companion loop blocked ~2s per BLE scan, starving ESC/ping/other
  streams; the scan window is now short and polled, keeping the loop responsive.
- **Remote mode left companion features dead**: stopping Remote now resumes the companion
  read/ping loops instead of leaving the connection silently broken.
- Response correlation by message id (concurrent commands no longer clobber each other),
  streams are stopped on disconnect/tab-leave, the status shows "Connecting..." until the
  first handshake, and USB writes are off the UI thread.

### Notes
- Firmware additions (flame splash, deauth, battery, lite mode, RMT/BLE fixes) require
  flashing the latest build.

## [0.2.0] - 2026-10-27

Verified live on real hardware (LilyGo T-Embed CC1101, ESP32-S3).

### Added
- **"Brucia" home button** on the device that enters companion/offload mode directly,
  with a themed status screen showing connection state and active streams (WiFi/BLE/SubGHz).
- **Fire theme on the firmware**: default UI palette is ember/amber; a one-time,
  non-destructive migration switches any pre-rebrand config to the flame theme once
  (`bruciaThemeVersion`), then keeps the user's own color choices.
- **Signed release APK** (git-ignored keystore; unsigned fallback for CI/clones).
- **Multiple built-in Wi-Fi dictionaries** (common WPA, numeric/dates, keyboard walks,
  Italian, router defaults), selectable in the Crack screen, plus rockyou-scale import.
- **Expanded MIFARE default-key dictionary** (26 -> 65 documented keys).
- **Real UI screenshots** rendered on the JVM (Paparazzi) for Wardrive, Crack and NFC,
  verified in CI.
- **Docs**: `ARCHITECTURE.md`, `ROADMAP.md` (with help-wanted items), `CONTRIBUTING.md`;
  a polished web flasher + guide page; `docs/HARDWARE_VERIFICATION.md`.
- **Flame branding**: logo, banner, app launcher icon, GitHub Sponsor button.

### Changed
- Companion mode moved out of the Connect submenu into the top-level Brucia home button.
- README rewritten with banner, hardware-verified callout, feature table, architecture
  diagram and links to every doc.

### Notes
- Every standalone Bruce feature keeps working; companion mode is purely additive.

## [0.1.0] - 2026-10-23

### Added
- First companion release: USB-CDC protocol (HELLO/CAPS/CMD/ACK/ERR/EVENT/DATA/SUB/
  PING/PONG), wardrive (live AP stream, GPS, Room, offline map, WiGLE CSV), WPA/PMKID
  crack on the phone, NFC security analysis, IR catalog, Sub-GHz decode, BLE scan,
  BadUSB library, console, device panel.
- Browser-based web flasher (ESP Web Tools) on GitHub Pages.

[0.2.0]: https://github.com/1vcian/BruciaDev/releases/tag/v0.2.0
[0.1.0]: https://github.com/1vcian/BruciaDev/releases/tag/v0.1.0
