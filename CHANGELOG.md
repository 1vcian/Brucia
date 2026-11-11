# Changelog

All notable changes to Brucia are documented here. Brucia is a fork of
[Bruce](https://github.com/BruceDevices/firmware) plus a native Android companion app
that offloads computation to the phone. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions are the app/firmware release.

## Unreleased

## [0.1.13] - 2026-11-11

Changes since 0.1.12. Hardware/phone acceptance remains pending; this release is for
installing the fixes and performing those checks. Software evidence is in TESTS.md.

- Dictionary downloads now use Brucia-hosted copies of Probable WPA and
  Flipper/Proxmark3 Classic lists in the wordlists release. Pinned source revisions,
  hashes and complete license texts accompany the four unchanged data files.

- Public tutorials now show actual app screen recordings: navigation, the built-in
  synthetic WPA demo and NFC dictionary imports. Replaced six text-slide videos;
  emulator provenance and captions are included. The wordlists release now hosts
  copies of the four new dictionaries with their licenses.
- App Store installer (backlog F35, residual): the bootstrap download has a 60 s overall
  budget (a slow or trickling server fails with "download took too long" and any previous
  install is kept), and the certificate validity dates the device's TLS stack does not check
  are now checked after the handshake: notAfter always against the later of the firmware
  build time and a synced clock, notBefore only with a clock read from NTP (otherwise the
  result says start dates were not checked). The store script's own plain-HTTP catalog and
  app downloads are unchanged. Portable policy host tests and firmware builds; not checked on a device.
- Imports (F36/F37): a manual wordlist import stages to a temporary file (at most 512 MiB,
  content-checked) and replaces the list only when complete, so a failed or cancelled import
  keeps the previous list; Cancel and a 30-minute total limit also end a read blocked in the
  file provider. An import and a download of the same list cannot run together. IR imports
  run off the main thread with a 4 MiB limit; manual PCAP imports read at most 32 MiB. JVM
  tests only; not checked on a phone.
- BLE console (F39): callbacks, waits and timeouts are bound to the connection they were
  started for, so a late event of an old session cannot act on a new one; a failed chunk ends
  its line, and a possibly partial line ends the session with the reason. JVM tests with a
  fake link only; not checked on a phone or device.
- Dictionary transfers (F41/F42): Cancel and total time limits close a stalled or trickling
  download or provider read; every byte is content-checked, not only the start; pinned sources
  (Probable WPA and the two NFC dictionaries) are checked for size and SHA-256 while streaming,
  and a mismatch keeps the previous list. JVM tests only; not checked on a phone.
- Soak tooling (F43): `tools/hw_acceptance.py --require-events STREAM[:MIN]` reports radio
  coverage (sufficient / insufficient / not run) separately from the link verdict; a soak that
  saw no stream events can no longer be cited as radio-traffic evidence. Host tests only.
- Release (F40): `release.sh` keeps a verified bundle in `.release-staging/v<version>/` and, if
  tagging, pushing, creating, uploading or publishing fails after the release commit,
  `./release.sh resume` publishes the same version without a new bump or rebuild. Existing
  tags and assets are verified and never overwritten; the release stays a draft until every
  asset matches. Tested with a local harness only (no real GitHub run).
- CI (F44): screenshot baselines are now compared (`verifyPaparazziDebug`) in CI and in
  `release.sh`, and a new emulator job runs the instrumented tests, including a navigation,
  reconnect and Stop flow over a simulated device link. The stale phone home-grid baseline
  was re-recorded. Remote CI status is recorded separately from local verification.
- Crack target (F38): the imported Wi-Fi target (a handshake or a PMKID) is now one
  generation-fenced value instead of three fields written from four places. A slow or failed
  import can no longer relabel or revive a target a newer import, pull or capture set, and an
  import that finds nothing clears the target instead of leaving a stale one that Run and export
  would still use. The shown label always matches what Run and export act on. JVM tests only; not
  checked on a phone. The follow-up reserves capture ownership at start, refuses a late
  capture after a newer import, and clears the target after empty/failed PCAP pulls.
  Generic file downloads report their status separately without touching target selection.
- Crack screen copy (F46): the target line no longer states that Run uses a demo handshake - Run
  with no target does nothing and asks for one; only the Demo button uses a synthetic handshake.
  The header no longer claims an absolute Flipper comparison. No behaviour change; a regression
  test pins that a run without a target never fabricates one.

## [0.1.12] - 2026-11-10

Release commit `21b8f80d`. Verification recorded in the release notes: automated host and
Android checks, lint, T-Embed firmware build and signed APK build in the release pipeline; the
four new upstream downloads checked on the development host. Phone download/import/
cancellation/rotation and physical MIFARE Classic key checks were NOT run; Mac benchmarks are
not Android performance figures.

- Dictionaries: added Probable WPA top 4,800 / 204k alongside RockYou and SecLists,
  plus on-demand Flipper and Proxmark3 MIFARE Classic dictionaries in NFC. Imports
  accept comments/BOM, deduplicate keys and report errors without discarding earlier
  keys. Extra NFC keys last for the app session; downloading does not test a tag.
- Dictionary downloads now require HTTPS across redirects, bound response sizes,
  reject incomplete responses and HTML/archive bodies, and preserve the existing
  wordlist until a replacement is ready. Cancel/Clear fences late NFC load results.
  Software tests and source downloads checked; phone/tag acceptance remains open.
- Community documentation refresh: a shorter README with clear setup and contribution
  paths, a task-oriented wiki home, linked feature guides, and practical issue/PR templates.
  Removed explanations of the project's jokes and repeated introductory audit text;
  hardware limits, media labels and detailed test evidence remain available.
- Reproducible host processing benchmark and speed-comparison methodology: actual Kotlin
  WPA2/PMKID, FFT, DTMF and SSTV with synthetic fixtures, raw samples and source hashes.
  Mac results are explicitly separate from pending phone/device comparisons; corrected
  the WPA source comment that incorrectly claimed ESP devices cannot do dictionary recovery.
- Public feature comparison: restored explicit De Bruijn, WPA/MIFARE dictionary and BLE/Wi-Fi/IR
  spam listings; distinguish Android processing/integration from capabilities already offered
  by Bruce, Flipper's official apps and community projects, with primary-source references.
- Public documentation: rewritten README and core wiki guides, linked feature pages and
  setup/recovery tutorials, refreshed public-site copy, and six captioned illustrated videos.
  Media distinguishes Compose fixture previews and instructions from physical hardware evidence;
  source/output hashes and a repeatable renderer are included. No new hardware acceptance claimed.

## [0.1.11] - 2026-11-09

Release commit `aa19f9b2`. Release notes: not yet verified on hardware. (They also repeat the
Spam IR entry, which first shipped in 0.1.10.)

- Repository is now public as 1vcian/Brucia, site https://1vcian.me/Brucia/. Firmware, app
  updater, web flasher and docs point to the new name. Apps from earlier versions still find
  this update through GitHub's redirect.
- App Store install (F35 follow-up): the downloaded script is written with storage sync errors
  checked and read back before it replaces the old one; if the old script cannot be put back it
  stays in `App Store.js.bak`, an error says so, and the next install attempt restores it.

## [0.1.10] - 2026-11-08

Release commit `09ef4a0f`. Release notes: not yet verified on hardware.

- App: IR power spam is also in the Spam category ("Spam IR" tile and an IR section in the
  Spam hub). It stays on the IR screen too; both drive the same run, so Stop works from either.
- From upstream Bruce (dev branch): ESP-NOW hardening (short or malformed frames are dropped,
  the peer list is capped, and a command from an unknown device asks for approval on screen),
  LoRa "change username" no longer reads an uninitialized value, KeeLoq `.sub` files open the
  emulation screen, Recent RF stays open after sending, Wi-Fi joins the exact network chosen and
  auto-connect also joins saved open networks.
- Ethernet ARP scan: the network stack is no longer locked for the whole scan, so replies are
  processed while it runs.
- From upstream Bruce: DuckyScript `DEFINE` constants; a `led` serial command for the RGB LED
  (color, brightness, effect, off; malformed numbers are rejected); a Show/Hide button for
  passwords on touch keyboards.
- Cardputer ADV audio: the I2S clock pins no longer collide (MCLK was routed onto LRCLK), and short
  tones use their own short-lived output so they cannot turn into a continuous squeal.
- Compiled scripts: BruceJS `.bin` bytecode (built with `tools/bjs_compile.py`) runs from the
  device's JS Interpreter and file browser and from the app (Files > Run on device). The device
  checks the file's structure first and refuses a truncated, corrupt or incompatible one.
- BLE console: the BLE API (Config > Advanced > Toggle BLE API) is now a standard Nordic UART
  service and requires pairing with the 6-digit PIN the device shows; only the paired phone can run
  commands or see their output. The app has a new Tools > BLE console. Before, the BLE API accepted
  commands from any nearby device without pairing.

## [0.1.9] - 2026-11-07

Release commit `c41bca36`. Release notes: host-tested (Python 312, including download/storage
fault tests and TLS checks) and built for T-Embed CC1101 and Cardputer. Not checked on a
device: App Store install over Wi-Fi, plus the open checks from 0.1.8. The store script
itself still downloads its catalog and apps over plain HTTP.

- App Store installer: the download is now verified against pinned root certificates (it used to
  accept any server), and the script is installed only when it arrived complete and was fully
  written; an interrupted download or full storage shows an error and keeps any previous install.

## [0.1.8] - 2026-11-07

Release commit `43f2972d`. Release notes: host-tested (Kotlin 1120, Python 306) and firmware
built for T-Embed CC1101 and Cardputer. On-device checks pending: LoRa disconnect, Remote
failed start, IR blast, FM owner, App Store install.

- Follow-up hardening of the 0.1.7 stop/IR work (host-tested; transmit paths still need a device
  check):
  - IR power spam / jam detect / LoRa receive: a Stop can no longer land on the *next* run - a
    restart waits until the previous stop has actually been sent (and teardown drops an unsent stop).
  - Global Stop: if a blocking operation is still running after the settle window, the other
    services' stops are no longer fired blindly (they would be swallowed by it); the app reports it
    and freezes local loops immediately instead.
  - IR power spam: a malformed duration (e.g. "abc", "30x", "-5", too large) is rejected instead of
    silently starting the default window.
  - Remote: entering Remote now waits for the device to confirm it left companion mode and for any
    IR/LoRa/jam/NRF24 window to end first; if the device does not confirm, Remote aborts visibly
    instead of switching to raw mode against a device still in companion mode.
  - Audio: FM audio now releases an async melody (song/RTTTL) and DTMF too, in both directions, so
    FM, speaker and microphone never fight over the I2S pins (fixes a gap in the 0.1.7 FM owner fix).
  - LoRa: disconnecting while a receive-stop was still queued no longer lets that stop reach the
    next device connected; a new receive is not blocked afterwards.
  - Remote: a failed start (device did not confirm leaving companion mode) no longer leaves the
    mirror watchdog running; retries, Stop and disconnect end it.
- App Store: the menu entry is now "Install Bruce App Store" and shows, before installing, that it
  is the Bruce community store on a third-party host whose apps download over plain HTTP. The
  store address is now set in `brand.json` like the other links.

## [0.1.7] - 2026-11-06

Release commit `33494d91`; its notes carry no verification statement. The feature commit
`1db4b4d8` records Kotlin 1105 and Python 305 tests, firmware static checks and mock e2e, the
T-Embed CC1101 firmware built and flashed, and a passive device acceptance (busy SUB 409, idle
stops) PASS. The IR blast and the FM owner were not checked on a device. The F23 follow-up soak
below is link evidence only (zero radio-stream events).

- New IR power spam (authorized use only): `ir_power_spam` blasts the power-off codes for TVs,
  projectors, audio and more - both regions (NA + EU) and the universal parsed + raw tables - and
  repeats the whole set until you Stop, so it covers all device types rather than one region's TVs.
  Start/Stop is in the IR actions card; the device reads the link while it runs so Stop ends it at
  once, and it joins the global Stop. Needs an on-device check (it transmits IR).

- Stops that only cancelled the wait on the phone now reach the device (firmware + app; needs a
  build and a device check):
  - NRF24 scan, sniff, jammer and MouseJack read the link while they run, so Stop ends the window
    at once instead of after up to 8 s (a stopped MouseJack never injects). The app sends
    `nrf24_stop`, waits for the device's answer, and never runs a scan and a sniff together.
  - Jam detect: Stop all now sends `jam_detect_stop`; before, the device kept listening for up to
    120 s and answered every other command "busy".
  - A stream subscription sent while the device runs a blocking operation is answered "device
    busy" instead of being dropped (the app waited for its timeout).
- FM audio, speaker and microphone have one owner at the firmware level, also from Console and
  Macros: starting one ends the other (as speaker and mic already did).
- Saved RF signals stored before the replay check, whose `rf tx` command would send a different
  frame (CAME, Nice, KeeLoq, wider keys), no longer offer Replay or Geo pinning; the saved items
  are kept unchanged.
- Bruce ARP scanner (standalone): the lwIP lock is released before the "Ethernet not ready" key
  wait.
- App integration fixes from the 2026-11-06 cross-feature review (backlog F25-F31; host-tested,
  phone acceptance pending):
  - Remote: leaving the Remote screen ends Remote and hands USB back to companion mode; while the
    raw mirror owns USB, framed commands, direct stops and SUBs are refused with a clear message
    instead of being written to the device CLI. Stop all ends Remote too.
  - Stop all: a blocking device operation (LoRa receive, script, responder, karma, brucegotchi,
    Remote) is ended first through its own stop; the other services' stops follow once it has
    answered (bounded at 8 s), so the firmware's stop poll can no longer swallow, for example, the
    Evil Portal stop during a LoRa receive.
  - BLE keyboard and Evil Portal no longer report "off"/inactive after a rejected or unanswered
    stop; the status says the stop failed and the state is kept until a stop is acknowledged.
  - Channels, RF Fingerprint, RF Timeline and the Evil Portal target list stop only a Wi-Fi scan
    they started; a running Wardrive scan survives visiting them. Channels starts its scan on a
    connect or reconnect made while it is open.
  - Files shows "Listing failed: <reason>" instead of an empty folder when the device cannot list.
  - Exports (Wardrive CSV/KML/GPX, NDEF, hashcat 22000, RF CSV/.sub, stations, probes, monitor
    pcap) show "Export failed" on unavailable or full storage instead of crashing, fall back to
    internal storage when external is missing, and never share a partial file.

- Companion frames, follow-up to 0.1.6 (backlog F23; firmware):
  - Partial writes: `Serial.write(buf, len)` is not guaranteed to accept all `len` bytes - on the
    T-Embed (`ARDUINO_USB_MODE=1`, so `Serial` is HWCDC) and on the TinyUSB path (USBCDC) it returns
    a short count under host backpressure (Arduino Stream::write contract; the classic-ESP32 UART path
    always writes all). The code ignored both `Serial.write` return values, so a short write truncated
    the length prefix or the payload and desynced the stream even with a single writer. A new bounded
    `writeAll` loops on the return value and distinguishes a host that is draining from one that is
    gone: the idle timer resets on every accepted byte (a delayed/partial write that resumes
    completes), while a host accepting nothing is bounded by a 1.5 s idle timeout and a trickling host
    by a 4 s per-frame ceiling. The bound also accounts for the driver's OWN blocking, which is subtle:
    a single `HWCDC::write` can run for seconds because its consecutive-timeout counter resets on every
    accepted ring chunk. So `writeAll` caps each `Serial.write` to one ring chunk (64 bytes) - a single
    call then covers one chunk and cannot be reset-amplified - and companion mode shrinks the CDC
    per-write timeout (restored on exit; no-op on the classic-ESP32 UART). With the deadline re-checked
    before each capped write, worst-case torn-frame stall is ~4.2 s, far under the 60 s idle-exit. When a frame still cannot be placed the stream is torn and cannot be resynced in place,
    so the firmware now RECOVERS: `writeFrame` stops emitting, the companion loop drops the link, and
    the app reconnects onto a fresh, frame-aligned session. The torn frame is counted in a new `diag`
    counter `tx_torn` (not `tx_frames`), visible on the next session.
  The wire format is unchanged; the firmware builds (`lilygo-t-embed-cc1101`) and was flashed and
  soak-verified clean on the T-Embed (2026-11-06: 15-min soak, 0 findings, `tx_torn` 0, link counters
  exact, no noise/rejects/reboot, heap flat). Honesty note: that soak - like the three before it -
  recorded ZERO events from the radio streams, so it does not prove stability under radio traffic (the
  remaining gate); partial writes are the leading hypothesis for the corruption, not a definitively
  established cause; the mutex is a safeguard for future multi-task emitters, not a demonstrated cause. Host test `tools/test_firmware_frame_lock.py` compiles the real
  `writeFrame`/`writeAll` and proves, each with a mutation: lock atomicity, `writeAll` completion
  under short writes, a stall-then-resume completing (vs tearing without the idle-timer reset), and a
  gone host tearing in bounded time, counting `tx_torn`, suppressing further emission, and recovering
  after a fresh session. The sinks advance virtual time per write so the asserted wait includes the
  driver's own block (chunked, with the consec-timeout reset), proving the chunk cap bounds one write
  while removing it lets a single write exceed the whole ceiling. `CommandSessionTest` proves the real
  app recovery for the actual fault - the device leaves companion but USB stays OPEN, often after a
  TRUNCATED frame (valid header, incomplete payload) whose parked state would otherwise swallow the CLI
  echo. The decoder matches the CLI banner ("COMMAND:") over non-frame bytes only - noise, a
  parse-failed frame, and a partial frame discarded only after ~2 s of INACTIVITY (no new bytes, like
  the firmware's receiver; not a total duration) - so it catches a truncated-frame-then-echo, a
  fragmented or noise-prefixed banner, never false-alarms on "COMMAND:" inside valid JSON, and still
  decodes a large valid frame that keeps advancing even if it takes longer than 2 s. The app then fails the pending command at once (instead of waiting up to
  650 s), keeping the transport open and the session usable so it re-enters companion - plus the unplug
  case (transport closed) separately.
- Tooling: `tools/hw_acceptance.py` now enters companion mode on its own (sends the raw `companion`
  CLI nudge and retries HELLO, as the app does) and drops DTR/RTS after opening, so a soak works right
  after a flash even though opening the ESP32-S3 USB-Serial-JTAG port resets the board to its CLI.
- Recovered Wi-Fi keys stay in plaintext in the security report, session JSON and session report
  (backlog DT01, authorized decision): these are authorized-assessment artifacts and the operator needs
  the actual key, so it is not masked. The "rotate it if this is your network" advisory remains.
- De Bruijn sweep lifecycle (backlog F24; app): a running sweep now stops when you leave the RF
  screen and when Remote control takes over the device (it was only stopped by Stop-all/disconnect
  before), so it can no longer keep transmitting `subghz_tx_raw` bursts after you leave. A rotation
  does not stop it. Stop now reports honestly: it always cancels the remaining bursts, but a burst
  already sent to the device is on air and cannot be recalled, so the status says so instead of
  claiming the whole sweep stopped. No new transmit capability.

## [0.1.6] - 2026-11-06

Release commit `354a32db`. Release notes: verified on the T-Embed CC1101 over USB (acceptance
suite, script control and a clean 40-minute link soak); radio transmit/receive still needs
case-by-case field testing. The 40-minute soak recorded zero radio-stream events (TESTS.md),
so it is link evidence only. Entries below that say "not yet checked on hardware" were
written before that session; only the checks named here were run on the device.

- Companion frames are now emitted atomically and completely (backlog F23; firmware change, needs a
  build and a clean hardware re-soak). `writeFrame()` had two framing-integrity defects behind the
  2026-11-06 soak corruption (stray non-frame bytes, an undecodable frame, a tx/rx counter mismatch;
  the app resynced, no crash or leak):
  - Cross-task interleave: the length prefix, payload and flush were written with no lock, so a
    stream EVENT (wifi.sta, wifi.probe, subghz.raw, ...) emitted from another task could interleave
    its bytes with a response. A recursive FreeRTOS mutex now wraps the whole emission, so two tasks
    can never interleave. No emitter runs in an ISR (the RMT and Wi-Fi promiscuous callbacks only
    queue/ring-buffer; frames are emitted later from the loop), so blocking on the mutex is safe.
  - Partial writes: `Serial.write(buf, len)` is not guaranteed to accept all `len` bytes - the
    ESP32-S3 USB-Serial-JTAG path (HWCDC) and the TinyUSB path (USBCDC) return a short count under
    host backpressure (Arduino Stream::write contract; the classic-ESP32 UART path always writes
    all). The code ignored both `Serial.write` return values, so a short write truncated the length
    prefix or the payload and desynced the stream even with a single writer. A new bounded `writeAll`
    loops on the return value, writing the remainder with a 1 ms yield up to a 2 s per-frame deadline
    so a vanished host cannot wedge the companion loop; a frame that still cannot be completed is left
    torn (link already dead) and is not counted in `tx_frames`.
  The wire format is unchanged. Host test `tools/test_firmware_frame_lock.py` compiles the real
  `writeFrame`/`writeAll` and proves (a) two concurrent writers never interleave with the lock and do
  interleave once it is removed, and (b) a sink that accepts only a few bytes per write still yields
  the complete framed stream with `writeAll` but a truncated one once it is reduced to the pre-fix
  single write.
- Stops, results and parsers (batch 18, software only; firmware changes need a build and the
  hardware checks H16-H19 in `docs/audit/radio.md`):
  - A stop could start the operation it named. Several companion commands were matched by a bare
    prefix, so `ir_jammer_stop` started an IR jam window, `ble_ibeacon_stop` an iBeacon broadcast,
    `jam_detect_stop` a detection window and `speaker_volume_stop` set the volume to 100 %;
    `evil_portal_start_x` started the portal. Every companion verb is now matched exactly; those
    stops answer "not running" on an idle device and other unknown words are refused by the CLI.
    A new host test replays the dispatch order of every dispatcher (full and LITE builds).
  - Honest transmit results: `subghz_tx_raw` and `subghz_replay` report whether anything was sent
    (and how many replay repeats); TV-B-Gone and the IR jammer answer `ok:false` with 0 codes.
  - `ble_ibeacon` refuses a malformed UUID (firmware and app); `ble_hid_begin` reports a BLE init
    or advertising failure instead of "advertising"; an NDEF contact over the PN532's 126-byte
    record is refused before the reader starts (firmware and app).
  - While Listen/Spectrum owns the CC1101, a radio command spelled in upper case or hidden in a
    later `;;`/newline segment (both run by the Bruce CLI) is refused with `ERR 409` too.
  - Leaving the Spam hub also stops the Wi-Fi beacon spam loop.
  - CLI numbers (backlog F03 remainder): `tone`/`beep` and `screen color hex/rgb` reject malformed
    or out-of-range numbers with a reason instead of aborting the device (`std::stoul` threw).
    No throwing conversion is left in the CLI callbacks or companion handlers.
- De Bruijn sweep now actually transmits (batch 18, app-side only; no firmware change). The single-
  code `subghz tx` path still cannot carry a sweep and is still refused with a reason, but the
  device's existing raw-timing companion command `subghz_tx_raw <freqKHz> <te> <csv durations>`
  (src/companion/subghz.cpp) can: a new pure planner `subghz/DeBruijnTx.kt` expands
  `DeBruijn.sequence(n)` to signed edge durations in the firmware RAW convention (positive us = HIGH,
  negative = LOW, in sequence order; rf_encoder.cpp rf_tx_durations), linearizes the cyclic sequence
  (appends the first n-1 bits) and splits it into OVERLAPPING bursts (overlap n-1 bits) each under
  the companion frame budget, so every n-bit code is sent intact, including windows straddling a cut.
  A new service `subghz/DeBruijnTxService.kt` streams the bursts sequentially with honest progress
  ("burst 3/12..."), a real per-burst result and a final summary, and stops cleanly on Stop or
  disconnect (sends nothing further). Orders are capped at 16 (`MAX_TX_ORDER`); larger orders are
  refused as impractical to stream, never silently truncated. No new transmit capability beyond the
  existing `subghz_tx_raw`. Hardware verification against an owned receiver is pending (soak running);
  see the De Bruijn owner check in `docs/audit/radio.md` - it is the only step that proves it opens
  anything. Tests: `DeBruijnTxTest` (coverage/overlap/budget/sign-vector/rejection/determinism plus a
  remove-the-overlap mutation check) and `DeBruijnTxServiceTest` (exact burst sequence, Stop/
  disconnect mid-stream, nothing sent when rejected/offline, progress/summary text).
- Radio features audit (batch 17, software only; firmware changes need a build and the hardware
  checks in `docs/audit/radio.md`):
  - RF CLI numbers (backlog F03): `subghz tx`, `subghz txp`, `subghz keeloqtx` and `RfSend` no longer
    abort the device on a malformed or out-of-range number. `std::stoul` threw on text or an
    over-wide value (Bruce CLI keeps exceptions and nothing on the companion path catches them);
    numbers now go through a non-throwing whole-token parser (`src/core/serial_commands/cli_number.h`)
    and are rejected with a reason naming the argument before the radio is touched. Bounds:
    frequency a decimal uint32 (as before), `bits` 1..64 (more shifted the 64-bit key out of range and
    a `.sub` `Bit: 2000000000` sized a huge encoder buffer, also refused in the encoder itself),
    `te` 0..65535 us, count/repeat 0..1000, KeeLoq button 0..15, serial 28-bit hex, counter 0..65535.
    `txp` and `keeloqtx` now report a failed encode/transmit instead of success. Nothing new is
    transmitted; the app refuses a `.sub` outside the same bounds.
  - Sub-GHz: the spectrum labels showed 0.30-0.35 MHz for a 300-348 MHz sweep (kHz read as Hz);
    fixed. While Listen or Spectrum owns the CC1101, one-shot radio commands (capture, transmit,
    replay, config, brute force, burst) are refused with `ERR 409` instead of idling the radio under
    the running stream. A saved capture is offered for Replay only when `rf tx` (24-bit protocol 1)
    reproduces it; a CAME/Nice/KeeLoq capture was "replayed" as a different frame.
    `subghz_config` reports a CC1101 init failure. The brute-force text no longer says every code
    is sent.
  - NFC: the "Read NDEF" button WROTE the Brucia URL onto the tag on the reader (`rfid ndef` is a
    write). It is now "Write NDEF URL..." behind a confirmation, and Erase also asks first. NDEF
    push refuses a URL over 99 / text over 96 bytes (firmware and app) instead of sending a silently
    truncated record. `nfc_emv_read` gives up after 20 s or on Stop instead of waiting for ESC at the
    device, and a cancelled wait is no longer reported as a read card. The Emulate text states the
    PN532 limits.
  - Companion link: the 60 s app-silence exit and the 15 s screen/LED restore are measured from the
    end of a handled frame, so a ~60 s bounded command (NDEF push, TV-B-Gone) no longer drops the
    device out of companion mode right after it answers.
  - LoRa: Stop sends `stop`, which `lora_recv` now polls (PINGs are answered during the window);
    a long receive no longer makes the app report "device restarted?".
  - BLE: turning the scan on while a BLE Remote (HID) session is live is refused with `ERR 409`
    (stopping the scan later freed the HID objects), and a BLE init failure is `ERR 503`; the media
    hijack is refused while the scan runs (its stack teardown freed the scanner). GATT, BadBLE and
    the media hijack end the scan from the app first, so the Scan toggle no longer stays on with no
    events. Leaving the BLE or Spam screen stops the BLE advertising loop.
  - BadUSB: `badusb run_from_file/run_from_buffer` are refused in companion mode (USB-HID setup on
    the port that is the app link could hang it); standalone Bruce behavior is unchanged.
  - IR: transmit commands refuse "IR TX pin not configured" instead of opening the on-device pin
    menu in companion mode; `ir tx` with a protocol the sender ignores fails instead of reporting
    success; an AC-state code longer than its bit count is rejected instead of overflowing a stack
    buffer. The app no longer shows "Sent" before the device replied, and the IR jammer text no
    longer promises an effect.
  - ESP-NOW: `espnow_send` no longer forces Wi-Fi STA mode and disconnects (that shut down a running
    Evil Portal AP and dropped the device's network join); the reply says the broadcast was queued
    and delivery is not confirmed. ESP-NOW and FM texts no longer claim "any ESP32" or verified
    broadcast audio.
- Wi-Fi audit batch 17 (features wardrive/probes/sniffer/monitor/channels/connect/mac/crack/
  evilportal/karma/brucegotchi/spamwifi):
  - Spam Wi-Fi (beacon spam) honest framing: the beacon-spam card no longer claims the broadcast is
    seen by nearby clients ("so nearby clients see bogus SSIDs ... appear as that many separate
    networks"). It now describes what the device does - broadcasts beacon frames advertising the
    given network names - and states that whether a nearby device's Wi-Fi scanner lists them is not
    verified here, matching the honest framing already applied to the BLE advertising tools.
  - Spam Wi-Fi now reports the device's real SSID count: each `wifi_beacon_spam` burst replies with
    the number of SSIDs the device actually broadcast (after its own 9000-character list cap and
    comma handling), so the status line shows that count once a reply arrives and falls back to the
    requested count only when a reply lacks it.
  - Evil Portal: the "deauth clients (evil twin)" sub-text no longer claims the disconnected clients
    join the fake AP; it states deauth frames are sent and whether, and to which AP, a client
    reconnects is up to the client and is not verified here.
  - Firmware (`src/companion/wifi.cpp`, needs a build): a `karma_stop` / `responder_stop` arriving
    while nothing is running now answers "not running" instead of being misread by the
    `startsWith("karma")` / `startsWith("responder")` handlers as a new ~60 s run (mirrors the
    existing `brucegotchi_stop` guard). The app sends a plain `stop`, so this closes a latent
    footgun (e.g. a user macro sending the literal `*_stop`).
  - Firmware (`src/companion/wifi.cpp`, needs a build): the Wi-Fi monitor lock channel is clamped to
    1..13, matching the app and the protocol (was 1..14); an out-of-range channel falls through to
    band-hopping as before, with no exception.
  - Tests: `WifiSpamTextTest`, `BeaconSpamTest`, extended `BurstLoop`;
    `tools/test_companion_wifi_dispatch.py` (static dispatch guard + monitor-channel checks).
- Network Scan and MITM work only on the active Wi-Fi network (backlog B17-01). The phone's address
  used to come from the first site-local interface of any kind, with the subnet always assumed /24,
  so with mobile data or a VPN up the scan or the "point your proxy at" address could belong to the
  wrong network while the screen said Wi-Fi. Both tools now read the active network from the
  system and refuse, with the reason on screen, when it is not Wi-Fi, is a VPN or has no IPv4. The
  scan uses the real prefix: every host of a /24 to /31, and on a larger Wi-Fi (for example a /16)
  only this phone's /24, saying so. (`net/WifiLan.kt`, `WifiLanSelectorTest`.)
- MITM proxy: request and response bodies of any size now stream through (backlog B17-02); before,
  anything over 4 MiB failed and the client got a closed connection. The log keeps the first 4 KiB
  of each body and marks it as truncated. HEAD, 204 and 304 responses keep the server's
  Content-Length instead of being rewritten to 0 (B17-03). (`mitm/HttpRelay.kt`, `MitmStreamingTest`,
  loopback only.)
- Geo signals: Replay shows "Replaying ...", then the device's answer (done or the failure reason)
  instead of announcing "Replayed" before anything was sent (B17-04, `GeoReplayServiceTest`).
- Bug Sweep: leaving the sweep stops only the scans it started itself; a Wi-Fi/BLE scan or spectrum
  sweep that was already running is left alone (B17-05, `SweepStreamsTest`).
- Network Scan (intercept): the OpenSSH "regreSSHion" (CVE-2024-6387) heuristic no longer
  false-flags OpenSSH 8.0-8.4. The rule's own text says it affects 8.5-9.7, but the regex matched
  the whole 8.x range, so a patched/unaffected 8.0-8.4 server was shown a high-severity RCE flag it
  does not have. The version range now matches the stated 8.5-9.7 (`netscan/VulnRules.kt`); legacy
  `<7.4` detection is unchanged. Covered by `VulnRulesTest`.
- RTTTL song parser (Audio / Tone, hardening): a melody header with a non-positive tempo (`b=0` or
  negative) or default duration (`d=0`) no longer divides by zero and produces absurd note lengths
  (`Int.MAX_VALUE` ms) or negative durations; the parser falls back to RTTTL defaults
  (`audio/Songs.kt`). Covered by `RtttlTest`. (The firmware plays presets/custom songs natively; this
  guards the phone-side parser.)
- Branding and links (backlog BRAND): one central config for Brucia's public links. `brand.json`
  at the repository root holds the name, the GitHub repository (`1vcian/BruciaDev` for now), the
  site and the Bruce upstream credit links, plus the future `Brucia` values; `docs/LAUNCH.md`
  describes the switch (`python3 tools/brand.py launch`) and the documentation to regenerate at
  launch. The firmware reads it through the generated `include/brucia_brand.h`, the app through
  `BuildConfig` (`BrandLinks`), the website through the generated `docs/brand.js`, `release.sh`
  through `tools/brand.py get`. The app updater and the web flasher accept release assets under
  both `BruciaDev` and `Brucia`, so builds from this release on keep finding updates after the
  rename (older builds need one manual install). `tools/test_brand.py` fails on a hardcoded
  Brucia repo/site or Bruce link outside the config and a reasoned allowlist.
  Firmware (needs a build, not yet checked on a device): default BLE/Wi-Fi names shown to users
  are now Brucia (`Brucia-*` BLE names, `BruciaShell`, `BruciaKarma`, `BruciaSniffer`, iBeacon,
  pwngrid name, FM RDS, BLE keyboard manufacturer, U2F attestation subject); the serial banner
  reads BRUCIA; Device Info and `info` show the source repository and the Bruce upstream; NFC
  emulation and PN532 BLE default to the Brucia site; GPX metadata names Brucia; the AP-mode WebUI
  screen shows the configured AP SSID/password instead of a hardcoded "BruceNet", and the AP SSID
  menu default is BruciaNet. Folders, file formats, JS API names, config keys and the BLE API name
  used by Bruce's official app are unchanged; credits to Bruce stay.
- Device and tools audit (agent17/DEVICE, `docs/audit/device-tools.md`; not yet checked on
  hardware):
  - Ethernet: the status card now reads the link state and IP where the firmware sends them (it
    always showed "Link: down" and no IP); device error texts are shown instead of "No Ethernet on
    this device."; a blank configuration means DHCP (it sent an unsupported bare `eth_config`); a
    static configuration shows its own result instead of a DHCP re-query that hid it. The screen
    now says the settings are applied for a link test and not kept.
  - Files: folder and file names with spaces (or `;;`) work for browse, delete and new folder (they
    were split by the device CLI: folders could not be opened, deletes silently failed, `;;` ran a
    second command); names the CLI cannot carry (`"`, `\`, control characters) are refused with a
    message. A listing cut by the device's 3500-byte reply limit now says so and never shows a cut
    entry with a wrong size. The device's answer to Delete / New folder is shown. Device file
    names containing `/` are ignored, so "Open with" can no longer write outside its folder.
  - Backups: Android cloud backup and device transfer no longer include the MITM CA private key or
    the WiGLE API token; other app data is still backed up.
  - iButton write (firmware): waits briefly for a key, writes it and reads it back; the reply says
    "written and verified", "no iButton on the probe ... (nothing written)" or "read-back differs".
    It used to write without a key present and always answered "written".
  - Clock "Sync from phone" sets the device to the phone's local time (Bruce keeps local time in
    its clock; the app sent UTC). "Get time" no longer shows a misleading "Z".
  - Firmware update: if the download, integrity check or image check fails after the device was
    rebooted into its bootloader, the app resets it back into its current firmware (it stayed dark
    until unplugged). A write whose MD5 read-back does not match is reported as a failed flash,
    not as "Firmware flashed - WARNING".
  - Macros: saving without a name no longer overwrites an existing macro; the command hint shows a
    command that can actually be typed. PCAP dissect: leaving the tool during a parse no longer
    brings the old file name back. Utilities: generated passwords and the Wi-Fi QR string are
    copied as sensitive clips (hidden from the Android 13+ copy preview).
  - Remote: starting it first stops a running macro, script (sends `script_stop`), file transfer
    (the upload is aborted on the device) and burst loops, before the device leaves companion
    mode; they used to keep waiting for replies that could no longer arrive.
  - Console and every CLI-backed tool: output cut by the device's 3500-byte reply limit now ends
    with "(output truncated by the device at 3500 bytes)".
  - Files: Delete asks for confirmation; local copies of pulled files are limited to the newest 20.
    Device: Sleep and Reboot ask for confirmation. Report and session exports no longer crash when
    storage is unavailable (an error is shown) and only the newest 10 of each are kept; device
    names in reports can no longer break the Markdown layout.
  - Updates: the downloaded APK waits in app-private storage (not the shared app folder) and is
    removed after the update; a running APK download can be cancelled.
  - Macros: saved macros can be edited (loaded back into the builder) and steps moved up; the save
    button says when it will replace a macro of the same name.

- De Bruijn (backlog F01-F02): fix the T-Embed reboot on "Transmit de Bruijn". The phone's
  generator produces a hex key far longer than one 64-bit code (order 12 = 1024 hex characters); the
  CLI parsed it with `std::stoull`, which threw `std::out_of_range`. That code is Bruce CLI
  (exceptions enabled) and nothing on the companion routing path catches it, so the throw reached
  `std::terminate` and aborted the device (host-reproduced natively for n=8/10/12). Firmware now
  parses `subghz tx`/`subghz txp` keys with `rfhex::parseKey64` (`src/modules/rf/rf_hexkey.h`): a
  key that is empty, non-hex or longer than 16 hex digits is rejected with a clear error, no
  exception, device state preserved, next command accepted. The device transmit path is unchanged
  (still one fixed code of at most 64 bits); nothing larger goes on air. The app no longer offers
  orders the path cannot carry: the custom field is bounded to the generator range (1-22, was 4-24
  which threw in the generator at 23/24), presets drop 24, and `transmitDeBruijn` refuses with a
  clear reason and transmits NOTHING instead of showing a false "Transmitting" and sending a
  key the device cannot carry. Host-native parser tests with a mutation check, app JVM tests for the
  UI bounds and the rejection message, and protocol e2e/mock coverage of the rejected key.

- BLE advertising tools (BLE spam / Apple-style advertisements, iBeacon, Fast Pair / Swift Pair /
  Samsung) now report the device's real result and no longer promise an effect on a receiver
  (not yet checked on hardware):
  - The headless bounded advertisers check every NimBLE call that can fail (stack init, TX power,
    `getAdvertising`, the iBeacon MAC address change, and each advertisement set / start / stop)
    and return success or failure. The companion answers `ACK` only when the whole run succeeded,
    otherwise `ERR 500 "ble advertise failed"` - it used to answer success unconditionally.
  - The advertisers share the single BLE radio with the `ble.scan` stream and the BLE HID
    presenter. Starting one while a scan or HID session owns the stack now returns `ERR 409`
    (busy) instead of tearing that session down and reporting a false success; `ble_hid_begin`
    likewise refuses while a scan runs, and BadBLE / media hijack refuse while a HID session is
    active. The app's tool surfaces the busy/error status instead of a generic success.
  - The app texts (Spam BLE, the native BLE cards, the Spam hub tiles, iBeacon) describe what is
    broadcast (advertisements of a given type) and state that whether a receiver shows a pairing
    popup or prompt depends on its OS version and settings and has not been verified. The earlier
    "pairing popups to nearby devices" / "will see it" effect claims are removed.
- Scripts: Stop now stops the script on the device. While a script runs, the Scripts screen shows
  Stop instead of Run; Stop sends `script_stop` once (direct, unlogged, as the Responder/Karma
  stops) and keeps waiting for the run's own reply, which shows the outcome: `Script stopped.`, or
  the output with a note when the script ended before the stop took effect (or the firmware does
  not read stops during a script; after 3 s the screen says the stop is still pending). A new run
  starts only after that reply and after the stop frame is written, so a stop can never reach a
  later script; Stop with nothing running, a second Stop and a Stop after the end send nothing.
  Stop all sends the stop too; disconnecting drops the wait and says the output is lost. Before,
  Stop only stopped waiting while the device ran the script on. Firmware: after a stopped or
  bounded run the abort flag was left set (the last poll copied it in after the interpreter task
  had cleared it) until the next script started; script_run now drops it once the task is gone.
  The next script was not affected (every start resets the flag). `tools/hw_acceptance.py --only
  script_stop` (opt-in) runs a looping script, stops it and runs a short one; the mock models
  script_run/script_stop. JVM and host tests only; not yet checked on hardware.
- Scripts that end on an uncaught error now fail: `script_run` replies `ok:false` with the captured
  output, which ends with the error (for example `ReferenceError: variable 'print' is not
  defined`, or `interrupted` when the 15 s bound stopped the script); it used to reply `ok:true`.
  `exit()` is still a success. The app's script examples used `print()`, which BruceJS does not
  define (found on hardware); they now use `console.log()`, the output script_run returns.
- `tools/hw_acceptance.py --soak <minutes>`: an opt-in passive soak for long-session evidence.
  Each interval repeats PINGs (sequential and a pipelined burst), `storage list /`, a CRC-checked
  `fs_read` of a small existing file, the scratch upload/read-back/remove cycle and one
  receive-only stream ON/OFF (`wifi.probe`, `wifi.sta`, `subghz.raw` in turn), keeps the link
  alive with PINGs, and ends with `diag`. The report adds a soak summary, findings, operation
  timing and a CSV time series (heap free/min_free/largest, uptime/reset, link counter deltas
  against the frames sent/received, rejects, noise, latency percentiles). Any counter mismatch,
  reject, ERR, noise byte or resync, failed operation, unexpected reboot or a heap.free decline
  above max(2048 B/h, 4096 B over the run) FAILs it; Ctrl-C cleans up and still writes the
  reports. Same allowlist, nothing new is sent. Simulated with `tools/mock_device.py` only (new
  `reboot` fault and companion idle exit); not yet run on hardware.
- Phone flashing keeps device replies that arrive in the same USB read. The flasher used to
  decode one read, return at the reply it was waiting for and throw the rest of that read away,
  so the esptool stub's "OHAI" greeting was lost when it came in the same read as the MEM_END
  reply (or before it, or with no MEM_END reply at all). The flash then went on against the
  stub as if the ROM loader were still running: the ROM form of SPI_ATTACH, which the stub
  rejects, and an MD5 that could not be read. Replies now go through a receive buffer that
  persists across reads and commands: frames are taken one at a time, bytes after a frame
  (including a partial frame or a split escape) stay for the next read, and a greeting seen
  while waiting for MEM_END still counts. A frame left unfinished (junk after a stray 0xC0) no
  longer swallows the next reply. Status lengths are unchanged (ROM 4 bytes until OHAI, stub 2).
  The 0.1.5 hardware session, replayed byte for byte, sends the same requests as before. JVM
  tests only; not yet checked on hardware.
- Fixes found while closing unit-test gaps (JVM tests only; not yet checked on hardware):
  - Phone flashing read the ESP32-S3 ROM loader's replies as if they ended in two status bytes;
    the ROM sends four (status, error, two reserved zeros), so every ROM-side error read as
    success. A rejected stub upload or FLASH_BEGIN now stops the flash (and still reboots the
    device) instead of writing on; the esptool stub's two-byte status is used once it runs.
  - WPA handshake import kept the last EAPOL message with a MIC, so a capture holding the full
    4-way exchange used M3/M4 as the client half and could never crack. Only a real M2 (no ACK
    bit, non-zero nonce) is now taken.
  - A pcap with a corrupt record length near 2 GiB crashed the PCAP dissector (out of memory) and
    made handshake/PMKID import throw; parsing now stops at the bad record. Nanosecond pcaps
    (magic a1b23c4d) showed packet times 1000x too large in the dissector.
  - Transmitting an imported `.sub` file sent the file's values unchecked to `subghz txp`, whose
    firmware parser aborts on non-numeric text: a multi-word unknown protocol (for example
    `CAME Atomo`), a non-hex or over-64-bit key, or a frequency outside 32 bits. Such files now
    have no Transmit command.
  - The TFT mirror's screen-info packet carries rotation as one byte; the app read it as a
    two-byte value and always got 0 (rotation is not used for drawing yet).
  - App logging could throw a LinkageError off-device (JVM tests run in isolation); logcat
    failures are now always swallowed. No change on a phone.
- Scripts run from the app (`script_run`) start at once and stop promptly. Until now the firmware
  waited for a state change that never happens, so every `script_run` answered `script timeout`
  after its 20 s wall clock and only then let the script run, with no bound, in the background;
  later `script_run`s answered `interpreter busy`. Now, once the 15 s bound passes or a `stop` /
  `script_stop` arrives during the run, `delay()`, timers, `wifi.connect()`, `nrf24.receive()`,
  the `notify` blink and the Bruce loops that end on ESC (`subghz.read`, `ir.read`,
  `rfid.read/write`, `audio.playFile()`/`tone()`, CLI commands run with `serial.cmd()`) end; a
  native call that checks none of these stops the script when it returns, and the interpreter is
  free for the next run. While a script runs, `PING`s are answered and other commands get
  `ERR 409 device busy`, as during other blocking operations. Host test
  `tools/test_firmware_script_abort.py`; `tools/companion_test.py` gains `--wait/--then/--after/
  --ping-after` for the hardware check. Not yet checked on hardware.
- Bruce CLI commands sent from the app console (or a script's `serial.cmd()`) no longer leave the
  device waiting for a person in companion mode: `ir rx` with no usable IR RX pin (and the
  companion `ir_rx`), `arp`, `listen`, `webui -noAp` without Wi-Fi, `decrypt` /
  `crypto decrypt_from_file` / `crypto type_from_file` with an empty password and `js <file>` now
  answer `ok:false` with the reason at once, and a BadUSB payload's `WAIT_FOR_BUTTON_PRESS` ends
  the payload. `settings` with no arguments and the pin setters no longer write JSON straight onto
  the link. The ARP scan now releases the lwIP core lock on its early error returns (it kept it
  held, outside companion mode too). `tools/test_companion_prompts.py` follows every CLI command
  into the module code it calls. Outside companion mode nothing else changes. Not yet checked on
  hardware.
- ESP32 (non-S3) boards: ROM printf (`ets_printf`, Arduino `log_x`) is muted in companion mode as
  on the ESP32-S3: the ROM's two output hooks are read at their ROM addresses (the same in both
  ESP32 ROM revisions), replaced, checked by read-back and restored on exit. Not yet checked on
  hardware.

- Device screen/LED state is now reported by the device: `CAPS` carries `scrOn`, `ledOn` and
  `scrBright` (backlight level now), and the app's Device screen switches and screen brightness
  slider start from them on connect. Reconnecting within the device's 15 s hold after turning the
  screen or LED off now shows them off instead of on. `scr_bright` with a level above 0 also ends
  a `scr 0` hold. Older firmware omits the keys and the app behaves as before; older apps ignore
  them. Host and JVM tests only; not yet checked on hardware.
- Scripts run from the app (`script_run`) no longer hang on on-device prompts: `keyboard()`,
  `dialog.message/choice/pickFile/viewFile/viewText/prompt`, `menu.show`, `wifi.connectDialog`,
  the Wi-Fi menu of `wifi.httpFetch`, `subghz.read/readRaw` without a timeout,
  `mic.recordWav` with `maxMs: 0` and an endless `display.drawGif` return their cancelled value
  at once in companion mode and add a line saying so to the script output. In a companion script,
  `serial.cmd()` refuses `webui`, `sniffer`, `listen` and `sleep` (they run until a key is pressed
  at the device); `loader open` is refused in companion mode, also from the app console. Outside
  companion mode nothing changes. New static check `tools/test_companion_prompts.py`. Not yet
  checked on hardware.
- Firmware libraries are pinned to exact releases for every board: registry libraries as
  `owner/name @ x.y.z` (ranges such as `M5Unified @ ^0.2.11`, `XPowersLib @^0.3.3` and
  `Adafruit TCA8418 @ ^1.0.2`, and bare `m5stack/M5Unified`, now name the release a fresh
  install picks today), Git libraries at full commits (ESP32_Button, TouchLib) and the
  T-Lora Pager audio driver at its v0.1.3 commit archive (same contents). M5GFX 0.2.36 and
  AsyncTCP 3.5.0, pulled in by M5Unified and ESPAsyncWebServer through ranges, are pinned
  next to them. This closes the 608 B core4mb difference between an older `.pio/libdeps`
  (M5Unified 0.2.28, M5GFX 0.2.35, still inside the old ranges) and a fresh install
  (0.2.29/0.2.36); `tools/build_firmware.py` now also quarantines another installed release
  of a pinned library that only a dependency's range would reach, and warns about libraries
  still reached only through a range. Firmware unchanged: T-Embed CC1101, Cardputer and
  core4mb build byte-identical firmware before and after, and core4mb built from a copy of
  the old libdeps now matches the fresh build. The all-board `buil_parallel.yml` and manual
  build workflows cache libraries only for identical ini files and library patches (no
  partial-key restore). How to bump a pinned library: CONTRIBUTING.md.
- Firmware builds no longer depend on leftover libraries: before building, `tools/build_firmware.py`
  moves any library in `.pio/libdeps/<env>` that the board's declared dependencies do not
  account for to `.pio/libdeps-orphans/<env>/` and prints it. A local Cardputer build had
  linked an undeclared M5Unified/M5GFX left in that directory (about 97 KB larger, and UI
  beeps routed to M5Unified's speaker, which is never started on the Cardputer); it now gives
  the same firmware as a clean or CI build, byte for byte. T-Embed and core4mb unchanged.
- Android build moved to AGP 9.3.3 (built-in Kotlin, still Kotlin 2.4.21) and Gradle 9.7.1,
  compiling against Android 17 (SDK 37) with AndroidX core 1.19.1, activity 1.13.0,
  lifecycle 2.11.0 and Compose BOM 2026.09.00 (Compose UI 1.12.1; Material3 stays 1.4.0).
  This move kept the Android 15 (SDK 35) target (raised separately, next entry); no behavior
  change intended, back navigation checked on the API 36 emulator. Database schema unchanged. Building now needs JDK 21 or
  newer: the screenshot tests use Paparazzi 2.0.0-alpha05.1 (a pre-release; no stable
  Paparazzi renders with SDK 36+), whose newer renderer changes text antialiasing slightly
  and wraps one home-screen line one word later. Snapshots re-recorded after review.
- App targets Android 16 (SDK 36). Reviewed against the Android 16 behavior changes: back
  is handled only through the AndroidX back dispatcher, so the predictive back gesture (on by
  default for SDK 36) keeps working; the app has no orientation or resizability limits, so
  tablets and foldables are unaffected by Android 16 ignoring them; it already draws edge to
  edge. System Back now also leaves the MITM request detail to the request list and moves
  one folder up in Files (both previously went straight to the dashboard), and the open tool
  is kept across rotation, fold/unfold and window resizing instead of returning to the
  dashboard. Checked on the API 36 emulator (gesture navigation, phone and a 1920x1200
  tablet-size window in both orientations; the Files subfolder case needs a connected
  device and was not exercised); not yet checked on a phone.
- MITM: going Back from a request detail returns to the same place in the request list;
  before, the list always jumped back to the top. Checked with a JVM Compose runtime test,
  not yet on a phone.
- MITM: when new traffic arrives, the request list keeps showing the request you were looking
  at, also when you come back from a request detail; before, the new requests at the top
  pushed the visible rows down. A request that failed after it was logged (for example while
  closing the connection to the server) no longer adds a second line for the same request.
  Checked with JVM tests and on the API 36 emulator, not yet on a phone.
- MITM: the ARP spoof Wi-Fi name, password, target and gateway fields are no longer cleared
  when a request detail is opened and closed. After a rotation the password is still left
  empty on purpose, as before. Checked with JVM tests, not yet on a phone.
- Tablets and other wide windows: the home category grid adds columns as the window widens
  (tiles of at least 160dp; 2 columns on phones in portrait as before, 4 on a 1280x800dp
  tablet in portrait, 5 in landscape, about 4 on a phone in landscape) and the dashboard is
  centered at up to 1040dp; tool screens and category tool lists are centered at up to
  840dp instead of stretching across the screen (on a phone in landscape this leaves narrow
  side margins). Phone portrait layout unchanged (screenshot tests pixel-identical).
  Checked on the API 36 emulator at 1920x1200 / 240 dpi in both orientations; not yet on
  a real tablet.
- Network Scan and the MITM proxy now check local network access before they start. On
  Android 17 for an app that targets Android 17 they would ask for the Nearby devices
  (local network) permission with an explanation and stay disabled, with a link to the app
  settings, when it is denied; this app still targets Android 16, where Android grants the
  access implicitly, so no permission is declared or requested yet. Independently, if
  Android refuses a test packet to the local network (EPERM, e.g. with the Android 16
  developer restriction enabled), the tool shows "Local network blocked by Android" with
  Retry instead of running a scan that can only time out. Responder is unaffected: the
  device, not the phone, answers on the LAN.
- App libraries updated: BouncyCastle 1.78.1 -> 1.86 (MITM certificates; picks up the
  upstream security fixes since 1.78.1, none of which were in the certificate-building code
  the app uses), ZXing core 3.5.3 -> 3.5.4 (QR codes), and the release build now uses the same
  kotlinx-serialization-core version as the debug and test builds (1.12.0; it was 1.7.3 in
  release only). Release APK about 640 KB smaller. Test-only: org.json 20260814.
- MITM: hosts with names longer than 64 characters keep working with BouncyCastle 1.86,
  which refuses such a certificate common name (the host now goes only into the certificate's
  alternative name when it does not fit), and a host name is no longer parsed as a
  distinguished name.
  Checked with JVM TLS handshake tests, not yet on a phone.
- Wi-Fi QR generator: network names and passwords outside Latin-1 (for example Cyrillic or
  Chinese) were encoded as question marks; they are now encoded as UTF-8. Codes for Latin-1
  text are unchanged. Checked by decoding the generated codes in JVM tests, not yet with a
  phone camera.
- Text drawn directly on the screen background without its own color was black on the
  near-black background (contrast 1.09:1): the Sub-GHz "Listen" and BLE "Scan" switch
  labels, the Wardrive "Wardriving" label, the Wi-Fi monitor target network name, NFC
  security finding titles and the Report preview lines. The theme now gives such text and
  icons the regular light text color (17.1:1). Present since 0.1; checked with a JVM test
  and screenshot tests, not yet on a phone.
- Rotating, resizing or folding no longer loses tool state. A running Network Scan keeps
  going (not restarted, not duplicated) and its results stay; a PCAP being parsed and its
  packet list stay; the Files editor keeps unsaved edits; SSTV images decoded so
  far, macro steps, the tone composer melody and the Wardrive "Found" order stay; typed
  inputs and chosen options on most tools stay (frequencies, payloads, scripts, portal
  HTML, durations, form fields). Remote control no longer shows "Start" with the mirror
  still running after a rotation. Leaving a tool still stops and clears it as before.
  Passwords and keys typed into forms are deliberately not kept. Checked with JVM tests and
  on the API 36 emulator; not yet on a phone.
- Device streams keep running across rotation, resizing and folding. Before, turning the
  phone stopped Sub-GHz Listen, the spectrum and the jammer, the BLE scan, GATT exploration
  and iBeacon, the device mic in the Audio, SSTV, Oscilloscope and Tone tools (and a
  recording in progress), Bug sweep, RF timeline and RF fingerprint scans, the station,
  probe and Wi-Fi monitor sniffers, the channel analyzer, the Evil Portal target scan, WIDS,
  the IR and NRF24 jammers and scans, LoRa receive, FM and ARP spoofing; the switch then
  showed off. Now the device gets no extra stream start or stop from a rotation, the
  screen shows the running stream and Stop works; leaving the tool stops it as before (also
  when the tool is left while the screen is turning), and so do Stop all and disconnecting.
  Leaving a tool no longer sends a stop for a stream that was not running. The BLE Scan
  switch now follows the device's answer like Sub-GHz Listen (it turns off if the device
  refuses the scan).
- Device screen: the device screen and LED switches no longer jump back to on after a
  rotation; they show what was last set on the device and go back if the device refuses.
  Disconnecting from the app turns a screen or LED that the app switched off back on at once
  (the device itself does it after 15 s without the app), so reconnecting quickly no
  longer finds the device dark with the switches showing on.
  Both checked with JVM tests and on the API 36 emulator without a device; not yet on a
  phone with the T-Embed.

## [0.1.5] - 2026-11-05

- App database: a database left at schema version 1 or 2 by a pre-0.1 development build
  (2026-10-23 to 2026-10-27, never in a release) is now migrated to version 3 with all
  its access points kept. Before, 0.1 deleted such a database and 0.1.1-0.1.4 could not
  open it (no migration path). Version 3 itself is unchanged. Room schemas 1 and 2 are generated from the commits that defined them.
- App targets Android 15 (SDK 35) and draws edge to edge; the bottom bar stays above
  the keyboard. AndroidX core, activity, lifecycle and Room updated (database schema
  unchanged).
- Companion mode: nothing but the companion frame reader reads the USB serial port any more.
  A script run from the app (`script_run`) that called `serial.readln()` used to read the
  app's protocol frames and break the link; it now returns an empty string at once. Bruce's CLI
  console (`serialDevice`) has no input and no output for the whole companion session, so a
  script's `serial.cmd()` and the JS audio/BadUSB/Sub-GHz file helpers can no longer print into
  or read from the frame stream; CLI commands that wait for console input (`storage write`,
  `*_from_buffer`, `crypto encrypt_to_file`) answer at once instead of after 5 s, and
  `storage ymodem` refuses without truncating its target file; a script error no longer waits
  for a key press on the device, which left the interpreter (and every later `script_run`)
  busy. Text a command routed through `CMD` prints from another task is no longer appended
  to that command's reply. Outside companion mode Bruce behaves as before. What scripts can
  do in companion mode is documented in proto/PROTOCOL.md. Host-tested
  (`tools/test_companion_serial_input.py`); not yet checked on hardware.
- All 17 `LAUNCHER_*` firmware builds measured against their 2,496 KiB app slot: 13 fit
  (96.7-97.7%); the four Elecrow CrowPanel LAUNCHER builds compile but are over the 98% budget
  (98.5-98.7%, 34-37 KB still free). Their size check now fails above 99% instead of 98%
  (accepted as is, not reduced), so they report a warning instead of failing the build; all
  other `LAUNCHER_*` builds still fail above 98%.
- Firmware builds from a clean checkout now apply the bundled library fixes on the
  first build (the AAC decoder bounds fixes were previously skipped until a second
  build); the build stops if an expected library fix no longer applies.
- Builds report firmware size against the board's app partition and fail when it is
  nearly full.
- `ir rx` no longer leaks its reader when no signal arrives.
- SSTV transmit keeps the nominal line period of each mode. Rounding every tone to whole
  samples made lines short or long (Martin M2 28 ms short over a frame, about 124 pixels of
  slant on a receiver that runs on the nominal clock; Scottie S1 7.7 ms long; Martin M1 and
  Scottie S2 about 1-2 ms short); the decoder's in-line sample positions are corrected the same
  way. Timing constants are now checked against a cited reference table (JL Barber, Dayton
  2000, cross-checked with the SSTV Handbook and MMSSTV). Not yet verified against an
  independently encoded recording or over the air.
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
  - Follow-up (batch 9; not verified on hardware): the remaining text that could reach the
    framed stream is handled. Bruce CLI commands the app sends through the console (`ir tx`,
    `subghz rx/scan/tx_from_file`, `play`, `tts`, `wifi on`, `storage free`, `nav`, ...) now
    return the text they print over USB in their reply instead of writing it into the stream;
    speaker/mic, Ethernet, LoRa, Evil Portal/karma, Wi-Fi join, brucegotchi, MouseJack, BLE
    keyboard, script and config-save paths are silent in companion mode; bare `printf` output
    and, on ESP32-S3 boards, ROM/Arduino log output (`log_e`) are muted until companion mode
    exits. Outside companion mode Bruce prints exactly what it printed before.
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
