# Brucia - test checklist

On-hardware tests to run on the LilyGo T-Embed CC1101 (ESP32-S3) with the Android app, Record the app version, firmware version, commit and result for each run.
Use phone flashing to validate the in-app updater; PC flashing is also available for
development and recovery. Historical checks below are not proof for the current build.

## Latest software checkpoint — 2026-11-05, candidate 0.1.4

Four coordinated batches (backlog S01/S02/S06/S08/S10/S11/W01-W03/W11, firmware items 2-5).
Merged tree before release: Android `:app:testDebugUnitTest` 760/760, `lintDebug` 0 errors
(14 warnings: 13 GradleDependency, 1 OldTargetApi), `verifyPaparazziDebug` unchanged;
22/22 `tools/test_*.py`; website 15/15; emulator (API 36) instrumentation 7/7 and a debug
versionCode upgrade keeping Room v3 data. Firmware builds: T-Embed CC1101 (RAM 47.6%, flash
25.8% of chip), Cardputer, core4mb (LITE), LAUNCHER_CYD, nesso-n1; 12 envs compiled in the
matrix run. Reproducible: two checkouts of one commit gave identical firmware.bin. No device
was connected for any of this; every item below in "Batches 1-4 - verify on hardware" is open.

### Batch 10 (TARGET36) - targetSdk 36, software and emulator only

targetSdk 35 -> 36 after a review of the Android 16 behavior changes (developer.android.com
about/versions/16/behavior-changes-16 and behavior-changes-all). No `onBackPressed`,
`onKeyDown` or `KEYCODE_BACK` in the app; back goes through Compose `BackHandler`, Compose
dialogs (ComponentDialog) and Compose popups (registers an OnBackInvokedCallback on API 33+),
so predictive back works unchanged. No `screenOrientation`, `resizeableActivity`,
aspect-ratio or edge-to-edge opt-out attributes. New: System Back leaves the MITM request
detail to the list and goes one folder up in Files; the open tool and category survive
rotation / resize (`rememberSaveable`). Evidence: `:app:testDebugUnitTest` 790/790 (111
suites; one earlier run hit the `SessionFileLinkTest` upload timeout while the host was
swapping, passed on rerun), `lintDebug` 0 errors / 7 warnings (unchanged set), `verifyPaparazziDebug`
pass (snapshots unchanged), `assembleDebug`/`assembleRelease` pass, `check_apk_identity.py`
targetSdk 36 on both APKs, `connectedDebugAndroidTest` 12/12 on the API 36 emulator. Emulator
UI (gesture navigation): Help, command log and Files "New" dialogs close on Back; Files
editor -> Files -> Device category -> dashboard -> launcher, with the predictive
back-to-home animation shown mid-gesture; injected KEYCODE_BACK behaves the same; MITM
detail -> list -> dashboard; Sub-GHz kept across a phone rotation; 1920x1200 at 240 dpi
(sw800dp) in landscape and portrait: home, Sub-GHz and Files render without clipping
(stretched to full width, as before), Files kept across the landscape -> portrait resize.
Not covered: Files subfolder Back (needs a device listing), a DropdownMenu on Back (both
menus need a connected device), a real tablet/foldable, a physical Android 16 phone.

- [ ] targetSdk 36 on an Android 16 phone with gesture navigation: from the dashboard a slow
      back swipe shows the app shrinking (back-to-home preview) and releasing exits; from a
      tool, a category list, the Files editor, a dialog and an open dropdown (Updates > Board
      image, Theme effect) Back closes only that layer.
- [ ] Same with 3-button navigation (tap Back; long-press Back shows the preview on Android 16).
- [ ] Files: open a subfolder, Back goes up one folder at a time, then to the dashboard.
- [ ] Rotate the phone with a tool open (and with the device connected): same tool stays
      open, USB link and running streams continue.
- [ ] If a tablet, foldable or desktop window is available: dashboard and two tools in
      both orientations and after fold/unfold or window resize; nothing clipped.

### S07 (ROOMHIST) - Room v1/v2 migrations, software only

Room schemas 1 and 2 generated from their source commits (`85a74629`, `0c705937`; see
`android/app/schemas/README.md`); `3.json` and the v3 identity hash unchanged.
`BruciaMigrationTest` on the emulator (API 36, MigrationTestHelper): v1 -> v2 keeps 3
fixture rows (GPS fix, no fix, quote/SQL-like SSID) and seeds the accumulators; v2 -> v3
keeps `ap` and creates `saved`; v1 -> v3 validates and leaves the same stored table SQL
as a fresh v3 database; the app's builder opens v1 and v2 files with their rows. A
deliberately broken migration (no `saved`, lost latitude) failed all 5. JVM:
`BruciaMigrationsSeedTest` 3. Totals: `:app:testDebugUnitTest` 783/783 (110 suites),
`lintDebug` 0 errors (12 warnings, dependency/target-API only), `assembleDebug` pass,
`connectedDebugAndroidTest` 12/12, `tools/test_android_logic.py` 182. The debug build now
carries kotlinx-serialization-core 1.8.1 (needed by room-testing); release keeps 1.7.3.
Under heavy host load the 2 GB emulator's system server restarted (APK install failures,
one ConnectionService assertion); with `-memory 4096 -cores 4` all passed. No v1/v2
database from a real device was available.

### Batch 8 (HWLINK) - companion link fixes from the first hardware run, software only

Fixes for the two link defects of the first `hw_acceptance.py` run on the T-Embed (inbound
frame loss in the serial RX queue; framing lost on `cc1101 Connection OK`). Evidence, all
simulated or host-side: Android `:app:testDebugUnitTest` 780/780 (109 suites), `lintDebug`
0 errors (12 pre-existing warnings), `verifyPaparazziDebug` and `assembleDebug` pass;
`unittest discover` 130 tests, every `tools/test_*.py` passes (incl. new
`test_firmware_serial_rx.py`, `test_companion_print_hygiene.py`; `test_hw_acceptance.py`
56/56 with a mock RX queue that drops bytes and injected device noise); website 15/15.
Firmware builds (vs snapshot 2e2f68ed, same toolchain): T-Embed CC1101 SUCCESS, static RAM
155,984 B (+8), app 4,310,976 B / 92.65% of slot (+1,520); Cardputer 158,184 B (+8), 84.49%
(+1,552); core4mb (LITE) 99,576 B (-8), 67.64% (+320); LAUNCHER_CYD 94,472 B (+16), 97.40%
(+704). AAC noise bounds pass on the first three. The RX buffer is heap, not static RAM. The
host-runner acceptance below was then run on a T-Embed; the app items remain open.

- [x] Rerun `tools/hw_acceptance.py` on the T-Embed with this firmware: `caps` shows `rx_max`
      8192 / `rx_buf` 8448; `fs_upload` passes with its 3072-byte first chunk (~4.2 KB frame);
      `--streams subghz.raw` passes with no noise warning (CC1101 prints guarded); `diag` heap
      free about 7.4 KB lower than the previous firmware at the same point.
      2026-11-05, T-Embed CC1101 flashed from the Mac (esptool, factory image, dirty build of
      snapshot 2e2f68ed + batch 8 working tree), report hw-acceptance-20261105T103009045Z:
      12/12 PASS. caps rx_max 8192 / rx_buf 8448; fs_upload 2 chunks (largest 3072 raw bytes),
      commit, read-back and remove verified; streams wifi.probe/wifi.sta/subghz.raw ON/OFF ACKed
      and silent after OFF, 0 noise bytes, 0 resyncs; ping 200/200 median 3.02 ms p99 4.18 ms,
      burst 20/20 in order; heap free 115,599 B at run start vs 123,175 B on the previous
      firmware (-7,576 B), stable 102,695 B over 20 diag calls; diag counters rx +260 = sent,
      tx +267 = received, no rejects. Limits: the three streams saw 0 events in 5 s each, so
      receive itself is not demonstrated (no probe traffic or Sub-GHz source was arranged); the
      app was not involved (host runner only).
- [x] App: upload a file larger than 3 KB to the device; Diagnostics shows "0 noise bytes" after
      Sub-GHz Listen on/off; speaker PCM/SSTV transmit still plays. Owner phone test of release
      0.1.5, 2026-11-05 (see "Phone test" below).

### Phone test 2026-11-05 - release 0.1.5 (owner-reported)

Owner test on their Android phone with the T-Embed CC1101, release v0.1.5 (9f4eefa4), reported as
all passing; no logs or Diagnostics export were collected:
1. App updated to 0.1.5; "What's new" shows the release notes.
2. T-Embed flashed to 0.1.5 from the phone over USB (Device > Updates); boot screen 0.1.5.
3. File larger than 3 KB uploaded from the app in companion mode.
4. Sub-GHz Listen on/off; Diagnostics shows 0 noise bytes.
5. Speaker tone/DTMF and an SSTV transmit play normally.
6. Saved raw IR button replays.
7. Material3 progress bars/sliders, orange outlined buttons; USB unplug mid-session recovers.
Not covered: phone model/Android version not recorded, cancel mid-download, wake lock through a
flash, swipe-away, over-the-air RF/IR reception by another receiver.

### Hardware run 2026-11-05 - batch 9 merge (candidate 0.1.5)

T-Embed CC1101 flashed from the Mac with the merged batch 9 firmware (dirty build on 080f8176),
report hw-acceptance-20261105T121303430Z: 12/12 PASS (caps rx_max 8192, upload 2 chunks with a
3072-byte chunk, streams ON/OFF clean with 0 noise bytes, ping 200/200 median 3.03 ms p99
4.09 ms, heap stable 102,887 B over 20 diag calls, diag counters match). Covers the link only;
the print-hygiene items below that need a phone or peripherals remain open.

### Batch 9 (PRINTS) - companion print hygiene follow-up, software only

Text that could still reach the framed USB stream in companion mode: Bruce CLI commands routed
through `CMD` now print through `usbConsole()` (Serial outside companion mode; captured into the
ACK on the companion task, dropped elsewhere), remaining module/task prints on companion paths
are guarded, and companion mode mutes stdout/stderr and, on ESP32-S3, the ROM printf copy to
USB-Serial-JTAG (`g_usb_print`). Evidence, host-side only: `test_companion_print_hygiene.py`
(58 files, reasoned allowlist, 7 tests), `unittest discover` 132 tests, every `tools/test_*.py`
passes. Firmware builds SUCCESS (compared with the batch 8 figures above, not a rebuild of the
base): T-Embed CC1101 static RAM 156,016 B (+32), app 4,312,896 B / 92.69% (+1,920);
Cardputer 158,224 B (+40), 4,319,504 B / 84.50%; core4mb (LITE) 99,608 B (+32), 2,660,688 B /
67.66%; LAUNCHER_CYD 94,504 B (+32), 2,490,480 B / 97.44% (warn > 95%, fail > 98%). AAC noise
bounds pass on the first three. Nothing hardware-verified.

- [ ] T-Embed, app console in companion mode: `ir tx NEC 04000000 08000000`, `subghz rx 433920000`,
      `subghz scan 433000000 434000000`, `storage free`, `wifi on`: the reply shows the text Bruce
      prints over USB (e.g. `Sent NEC Command`, `timeout`, `SDCARD mounted successfully`) and
      Diagnostics stays at 0 noise bytes.
- [ ] Same board: theme Save (`config_save`), speaker tone/DTMF, mic record, BLE HID connect from
      a host, Evil Portal start/stop: 0 noise bytes; after leaving companion mode Bruce's serial
      CLI prints as before (e.g. `nav next` shows `Next Pressed`).

### S09 (SSTVREF) - SSTV timing reference, software only

`SstvTimingReferenceTest` (7 tests) holds sync, porch, separator, scan, line time and VIS
values for Robot 36/72, Martin M1/M2 and Scottie S1/S2 with a citation per value (JL Barber,
Dayton 2000; SSTV Handbook ch. 3-5; MMSSTV source for the Robot 72 porch conflict). It checks
the encoder/decoder tables exactly, decoder sample offsets within half a sample at 16 kHz, the
encoded frame length within one sample and long tones in the encoded audio within 50 Hz. Before
the fix the two realised-timing tests failed (Martin M1 frame 2.176 ms short; Martin M2 decoder
offset 1267 vs 1267.552 samples); after it Android `:app:testDebugUnitTest` 787/787 (110
suites), `lintDebug` 0 errors. Limits: Brucia's own encoder and FM discriminator only.

- [ ] Decode an SSTV recording made by another program (e.g. MMSSTV or QSSTV) from a known
      source image, in each of the six modes, and compare against that image.
- [ ] Over the air: send each mode from the app to another receiver, and receive another
      transmitter in the app; note slant and colour.

### Batches 1-4 - verify on hardware
- [ ] Run `python3 tools/hw_acceptance.py --port <port>` first (passive, allowlisted; see
      docs/HARDWARE_VERIFICATION.md) and attach its report; it covers the protocol side of
      the diag, evidence and file items below. UI, Stop all/unplug and RF items stay manual.
- [ ] targetSdk 35 build: edge-to-edge layout on an Android 15+ phone with a camera cutout and
      on an API 26-34 phone; keyboard over Console/Macros; signed upgrade from 0.1.4.
- [ ] `diag` card: Refresh shows reset reason (power-on, then after a reset/brownout if
      reproducible), heap, link counters rising with traffic; busy 409 during a long op.
- [ ] Wi-Fi handshake/PMKID capture with descriptor: imported file matches size/CRC; a second
      capture on the same AP reports `appended`; Esc abort and timeout import nothing.
- [ ] Mifare key check: UID/sectors/complete shown; Stop mid-batch returns "Stopped (partial)"
      within seconds; swapping the card between batches stops the run.
- [ ] Speaker file playback with the link alive; Stop ends it; end event arrives.
- [ ] Hardware evidence card on the T-Embed: CC1101/PN532/SD levels and pin conflicts plausible;
      LoRa/RC522 probes leave the peripherals idle.
- [ ] Sub-GHz Listen/Spectrum: init failure turns the switch off with a reason; sweep limits.
- [ ] NFC read, `rf rx`, IR learn, device panel, Files: Stop all and unplug end the wait.
- [ ] USB: permission prompt once, flash takes the port from the companion link, wake lock held
      through a flash, service gone after the app is swiped away.
- [ ] In-app update: APK + firmware download, cancel mid-download, flash after verification.
      Partly covered 2026-11-05 (0.1.5 phone test): APK update with release notes and firmware
      flash from the phone to the T-Embed passed; cancel mid-download not exercised.
- [ ] FM PCM (needs a Si4713 board): accepted/lost counts, no silent loss.

## Latest software checkpoint — 2026-11-04

Claude orchestration: six scoped packets in
`.codex_tmp/claude-stabilization-20261104/`; source and packet hashes rechecked before
launch. Historical coordinator JVM baselines passed 44 tests (S08a) and 27 (S09),
overlapping the main suite. Explicitly permitted execution verified host Claude
authentication and Docker 27.5.1. The user requested local Mac Claude Code instead
of Linux/Docker: four workers launched (S08a/S09/S11a/S06-S10a), review pending.
CLI permissions and packet scope do not provide OS filesystem/network isolation.
S02/S07 remain blocked; launch does not prove completion or interoperability.

W11l: `DictionaryTestService` is wired into the existing offline dictionary paths.
Seven synthetic tests cover missing targets, early-match counts, read failures,
cancel/restart with a blocked old reader, exhausted dictionaries, the existing
handshake verifier and an already-cancelled parent scope. No live network tests.

W11m: 11 synthetic `CaptureWorkflowTest` cases cover same-size file overwrites,
unchanged/foreign/ambiguous files, parsed AP identity, command and verified-read
failures, failed baseline listing, PMKID routing, stopped/superseded blocked reads,
and missing hardware/link prerequisites. Tests use fake IO and parser fixtures;
actual RF capture, USB timing and the final UI remain pending on-device acceptance.

W11n (Mifare stage): 11 `MifareKeyCheckServiceTest` tests cover 48-key batching,
normalization and dictionary snapshots, preserving prior hits on failure, malformed
replies, missing connection/module, Stop/reset/supersede with a delayed reply,
cancelled parent scope, empty success and IO errors. Real `CommandSession` over a
simulated transport verifies timeout/cancellation cleanup, late ACK suppression and
reconnect isolation. No NFC hardware was connected. Firmware ACKs do not expose tag
identity or partial scans; a completed app sequence is not proof of an exhaustive
scan of one card. Stop does not abort a batch already running on the device.

W11n (macro stage): 12 `MacroRunnerTest` cases cover ordered commands/pauses,
step snapshots, double-start prevention, failure/exception/timeout short-circuiting,
empty/blank steps, missing connection, Stop during a pause, Stop/restart with a
non-cancellable old reply, parent cancellation before/during execution and consistent
delay bounds for saved/direct macros. Real `CommandSession` with simulated transport
covers timeout/Stop cleanup, late ACKs and reconnect isolation. Firmware commands
and saved JSON are unchanged; hardware execution and final UI are unverified.

W11o: 10 `LifecycleRegressionTest` cases cover cancelled scopes before/during a
command, stale mapper side effects, mapper errors, a cancelled Ethernet config
that must not trigger refresh, queued and delayed callback cancellation, late BLE
refresh, portal template completion after disconnect, combined cleanup of real
services over a simulated `CommandSession`, and cleanup continuing after a failing
hook. Updated the HID Stop regression to require a terminal status. Existing
service suites still pass. Stream/UI/device stop behavior has not been physically
validated. File-transfer ownership was subsequently covered by W11p below.

W11p: 11 `FileTransferServiceTest` cases cover invocation-time session capture,
queued/active cancellation, reconnect, delayed non-cancellable reads, serialized
writes after supersession, discarded local-file cleanup, failed download before
opening a destination, unavailable verified files, cancelled parent scopes,
bidirectional caller/transfer cancellation and actual portal/service integration.
The original seven `SessionFileLinkTest` cases exercise real `CommandSession` and
`DeviceFileReader` with simulated transport: matching ID/size/CRC, chunked/empty
uploads, bad write ACKs, timeout/cancellation cleanup, queued wire operations,
late data and invalid/oversized/closed-link requests. Existing reader fixtures
continue to cover corrupt and missing chunks. All data is synthetic.

Android document-provider behavior and physical USB timing remain unverified. A
provider write that already truncated a document has no guaranteed rollback.
Firmware upload safety is now covered by S04 below; legacy direct truncate/append
still can leave partial files. Provider writes are not certified as atomic.

W11q: 12 `PcmStreamServiceTest` cases cover begin gating, exact frame boundaries
and monotonic pacing, failed begin/data/read/stop, Stop during non-cancellable
preparation/read, source cleanup, ordered old-stop/new-begin, reconnect, background
device-mic ownership, duplicate/invalid/unavailable starts, parent cancellation,
empty-read backoff and cooperative SSTV cancellation with unchanged normal samples.
Five `SessionPcmLinkTest` cases validate actual framed JSON through `CommandSession`:
speaker `d.sr`/`d.pcm`, exact S16LE bytes, FM text grammar, failed begin without PCM,
structured wait timeout/cancellation, stale/closed links and frame bounds.

Android MediaCodec/AudioRecord and physical FM/speaker playback are unverified.
A blocking source must return before its serialized cleanup/new run can proceed.
Stop invalidates app ownership immediately; it does not prove hardware silence.
The FM firmware ACK has no accepted-sample count and can hide ring-buffer loss;
protocol documentation records that remaining limitation. No firmware was changed.

S01: nine `ReleaseUpdateServiceTest` cases exercise `AppUpdater.fetchSnapshot`
with injected HTTP bodies: two successive releases, one request per refresh,
metadata/asset consistency, immediate invalidation while IO is pending, duplicate
refresh suppression, failed-refresh retry, obsolete/foreign/unsupported board
selection, missing assets without fallback, malformed/ambiguous metadata,
cancellation during a blocked fetch, already-cancelled scope and immutable parsed
board lists. Home and Updates now consume one state; download progress is separate.
APK/USB handoff guards were reviewed and compiled, but permission UI, live GitHub
requests, installer behavior and physical flashing were not exercised.

S03: the software matrix in `docs/APP_CONTROLS_CHECKLIST.md` maps all 50 tool tabs
plus Theme to prerequisites, ownership, deadlines, input checks, cancellation and
evidence/remaining integration. A source-to-table check found no missing, extra or
duplicate tab after reconciliation; hardware checkboxes remain unmarked.

Five `CommandCapabilitiesTest` cases cover all 13 hardware families (including
cross-family rejection), composite FM/mic requirements, cleanup exceptions and real
CommandSession text/JSON/structured rejection without writes or pending leaks.
Ten `StreamSubscriptionsTest` cases use real framed SUB/ACK on fake transport:
missing modules, unknown streams, monitor input rejection, immediate/missing ACK,
exclusive and independent streams, failed OFF retry, Stop/supersede, reconnect,
queued capability/session changes, scope cancellation, write failure and close.

Nine additional regressions cover BurstLoop immediate/cancelled-scope completion;
WIDS startup/cancellation and invalid windows; jam-detect/Sub-GHz duration overflow;
and LoRa receive supersession, cancellation and invalid duration. Existing burst
blank-failure and LoRa Stop tests now require termination and no late delivery.
Registry tests require offline Crack and mixed SSTV/Tone screens to remain reachable.
The older burst cancellation test now waits for runner entry, avoiding its former
race against an optimistic UI flag. Initial SUB tests returned non-Unit values and
were rejected by JUnit; that harness issue was fixed before the passing run.

UI wiring now restarts automatic audio subscriptions on connection/capability
changes, requires explicit speaker/mic support, clears DTMF when its SUB fails, and
prevents recording after a failed immediate start. Failed RF/NFC reads no longer
persist a synthetic success. These Android adapters were reviewed/compiled;
Compose lifecycle, Android codecs/providers and actual radio shutdown remain
unverified. LoRa Stop cancels the app wait; firmware has no receive abort.

S04: managed uploads now use a separate transactional capability and begin/chunk/
commit/abort contract. Twelve `SessionFileLinkTest` cases exercise real correlated
sessions with synthetic transport: empty/chunked transfers, strict token/size/CRC/
commit ACKs, lost commit replies without retries, old-firmware reads/no upload
fallback, cancellation/abort, reconnect, late replies and canonical path limits.
`CapsTest` independently rejects missing/malformed transactional support.

`python3 tools/test_firmware_upload.py` passed 41 failure/recovery scenarios with
the real C++ engine and UBSan: both rename models, old/new/empty destinations,
partial writes, bad/truncated/oversized staging, read/rename/rollback/cleanup errors,
reset boundaries, artifact collisions, invalid journals, conflicting recovery and
idle-clock wrap. `test_firmware_storage.py` passed against the real dispatcher and
FS adapter, including transaction ACKs, legacy-write exclusion and reserved-path
aliases. Codec/framing and all five mock protocol E2E checks also passed. The new
upload harness is included in CI. Mock protocol E2E itself still covers reads;
the new upload contract is covered by the JVM/native fixtures above.

FAT backup/promote recovery has a visibility gap. If promotion and rollback both
fail, the previous bytes remain in the backup; no success is claimed. Failed cleanup
after successful commit is explicitly reported. Physical media durability, power
interruption, card swaps and Android provider/USB behavior remain unverified (S05).
Generic CLI/scripts are outside upload isolation. No release or flash performed.

`python3 tools/check_android_changes.py --variant release --all-main --unit-tests`
passed compilation of 158 production Kotlin files and 563 tests in 88 non-Paparazzi
suites. The checker now accepts an explicit cached build variant; only release
outputs were available after 0.1.3. This count excludes screenshot tests and is not
directly comparable to the 455-test Gradle checkpoint below. A new Gradle unit/lint
attempt failed before execution because the global wrapper lock was not writable.
Current lint, fresh APK and device acceptance are not established by these cached-source
checks. `git diff --check` passed.

Final S04 firmware builds passed on all three targets:

| Target | RAM | Flash |
|---|---|---|
| LilyGO T-Embed CC1101 | 154,384 B (47.1%) | 4,304,398 B (25.7%) |
| M5Stack Core 4 MB (LITE, no RGB LED) | 98,152 B (30.0%) | 2,672,063 B (63.7%) |
| M5Stack Cardputer | 156,584 B (47.8%) | 4,309,563 B (51.4%) |

`python3 tools/test_android_logic.py --firmware Bruce-lilygo-t-embed-cc1101.bin`
also passed: the 4,370,480-byte merged T-Embed image passed the production Kotlin
preflight, and 121 standalone JVM tests passed (overlapping the full suite).

Firmware S04 build verification uses installed dependencies copied into the ignored
`.pio/local-core` directory, leaving global caches untouched. Reproduce after provisioning
that local cache with:

```sh
PLATFORMIO_CORE_DIR="$PWD/.pio/local-core" ~/.platformio/penv/bin/pio run \
  -e lilygo-t-embed-cc1101 -e m5stack-core4mb -e m5stack-cardputer
```

The normal global-cache attempt was denied `platforms.lock`. The local build still
warns that esptool dependency installation cannot reach PyPI, then uses the cached
installation successfully. This is a cached-toolchain build, not a clean online
provisioning check. Generated binaries retain VERSION 0.1.3 and are local development
artifacts, not a new release. Full Android Gradle/lint remains blocked at its global
wrapper lock; the cached Kotlin result above does not substitute for it.

## Current evidence — 2026-10-30, candidate 0.1.1

- 113 production Kotlin files compiled with cached dependencies/generated resources;
  253 unit tests passed. The standalone subset passed 120 tests (overlapping).
- 0.1.2 app (W06-W11): `./gradlew --offline :app:testDebugUnitTest`
  passed 455/455 including `FeatureRegistryTest`, `CommandSessionTest` (13, fake
  transport), `PcapDissectorTest` (7, synthetic pcaps), `IButtonServiceTest` (10),
  `DeviceOperationTest` (8), `ClockServiceTest` (2), `WifiMacServiceTest` (3),
  `ScriptServiceTest` (3), `DeviceAppearanceServiceTest` (4), `WifiJoinServiceTest` (3)
  `EspNowServiceTest` (2), `SpeakerServiceTest` (10) and `MicRecordServiceTest` (3); `lintDebug` passed with no
  findings in changed files. `CommandSession`
  is wired into the ViewModel and the registry into MainActivity. No device test of either.
- 0.1.2 firmware (W10): `tools/test_firmware_ibutton.py` passed 71 checks
  (UBSan only; ASan hangs in this macOS shell); codec, framing, storage, web-resource and
  build-tool tests passed; `lilygo-t-embed-cc1101` built successfully (RAM 47.1%, flash
  25.6%). Other boards were not rebuilt (deferred to release). Build with PlatformIO's own
  interpreter: `~/.platformio/penv/bin/python tools/build_firmware.py -e
  lilygo-t-embed-cc1101 --pio ~/.platformio/penv/bin/pio` (default python3 3.15 is rejected).
- 21 Python and seven website tests passed. Native storage/codec/framing, mock
  protocol E2E and AAC bounds with UBSan passed in the stabilization session.
- T-Embed 0.1.1 firmware compiled and passed production Kotlin image preflight.
  Local package `.codex_tmp/releases/0.1.1/`, 4,360,800 bytes; SHA-256
  `20fbda74c7a67b700108e8b1c1928af7f123911a60d99dabe349f6272dd8334e`.
- Earlier stabilization builds passed on Core 4 MB and Cardputer; this is not
  evidence for every configured board or for physical operation.
- Fresh Gradle/KSP/lint/signed APK and installation checks remain pending. Previous
  environment denied Git metadata/global Gradle writes and daemon sockets; GitHub
  was unreachable. Recheck those limitations in the current environment.
- Physical acceptance for the final pair, UI lifecycle, flash/recovery and sustained
  operation remain pending. Historical checks below do not certify current source.

See [the task ledger](skills/brucia-maintenance/references/backlog.md) for outstanding
work and [the interactive controls](docs/APP_CONTROLS_CHECKLIST.md) for the per-control
acceptance inventory. Latest known fixes are in CHANGELOG.md.

### Hardware-free logic checks

The normal Android check remains `cd android && ./gradlew --offline testDebugUnitTest lintDebug`.
For a restricted environment with cached dependencies:

```sh
python3 tools/test_android_logic.py
python3 tools/check_android_changes.py --variant release --all-main --unit-tests
node --test tools/test_site_release.cjs
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/test_firmware_storage.py
python3 tools/test_firmware_upload.py
python3 tools/test_firmware_codec.py
python3 tools/test_firmware_framing.py
python3 tools/test_audio_noise_bounds.py -e lilygo-t-embed-cc1101
```

The small runner currently exercises 120 real Kotlin/JUnit tests with only the
version constant generated for the host. Add `--firmware <merged-image.bin>` to
validate a real T-Embed image with the production preflight. The cached-app checker
compiles all 113 current production Kotlin files (Compose included) and runs 253
non-Paparazzi tests; it requires a previous debug build and cached SDK/dependencies.
Those counts describe the original debug-cache checkpoint; see the latest release-cache
results above. It does not regenerate Room/KSP output, run lint or create an APK. Counts overlap.

Native storage/framing checks exercise actual firmware code with host IO shims.
The Base64 test checks 134 vectors against Python and uses UBSan. The AAC test
requires installed firmware dependencies and checks real routines with synthetic
state, not a complete malformed-media corpus. The five checks in
`tools/test_protocol_e2e.py` use a mock device; they are not a physical integration test.

## Historical verification (retest on the current build)
- [x] Cross-board firmware builds: lilygo-t-embed-cc1101 (primary) AND m5stack-core4mb (LITE,
      no RGB LED) both compile. A LITE break - companion.cpp used BruceConfig::ledColor/ledBright/
      ledEffect, which only exist under HAS_RGB_LED - is fixed (those caps fields and led_set are now
      guarded). The manual full-matrix workflow covers configured boards; the LITE + non-LED path was the class that was failing.
- [x] Phone flashing over USB (esptool-stub path): 0.8.12 flashed on real hardware; boot shows the
      flashed version. Re-confirm the flash log reaches **verified OK (MD5 matches)**.
- [x] USB auto-connect / companion HELLO + CAPS; Device info shows board/chip/fw/battery.
- [x] RTTTL songs play correctly on the device speaker ("canzoni perfette").
- [x] LED animations, LED brightness and solid color apply LIVE (user-confirmed); solid color shows
      only when the animation is "Solid" (expected - an animation overrides a static color).
- [x] Wardrive + BLE scan stream (verified earlier on hardware).

## Core fixes to verify (implemented, need on-hardware confirmation)
- [ ] **Boot animation**: the flame only grows/shrinks (min = original size), no vertical bob, no
      white gap.
- [ ] **Theme applies live**: pick a theme color -> device UI AND app accent change immediately,
      WITHOUT pressing the device power button (which used to exit Brucia). "Save to device" persists.
- [ ] **LED sliders read the real device value**: the LED-brightness and screen-brightness sliders
      start at the device's actual value (e.g. 0%), not always 50%/100%.
- [ ] **Song STOP** actually stops a playing melody (async speaker_song + speaker_stop).
- [ ] **DTMF generator**: the DTMF keypad produces audible dual tones (speaker_dtmf). If silent,
      the AudioFileSourceFunction path may not drive this board - fall back to the RTTTL/async player.
- [ ] **DTMF decoder**: the Tone screen decodes DTMF from the mic (play tones into the device mic).
- [ ] **Updates card** shows App / Firmware / App+firmware correctly; the **Device footer button**
      shows the accent dot when an update exists; the **Device tab** has the Updates shortcut card.
- [ ] **ESP-NOW** tool broadcasts; **Change MAC** sets/randomizes the STA MAC; **Connect to Wi-Fi**
      joins an AP and reports the IP.
- [ ] **Brucegotchi** runs for the chosen duration and reports a handshake count.
- [ ] **Custom IR remote**: capture a button (ir_rx), it appears in the grid, tap replays it, the
      layout survives an app restart, long-press deletes.
- [ ] **Karma attack** (evil-twin / probe-response lure): `karma <secs>` runs headless, reports
      probes seen; standalone menu entry still works. Audit note: confirm the headless run ends on
      its own time budget (returnToMenu) without needing the device ESC, and that a victim submitting
      creds through the lure does NOT desync the USB link (the portal-save Serial prints are now
      guarded).
- [ ] **Responder** (LLMNR/NBT-NS): `responder <secs>` needs Wi-Fi joined first (Connect tool);
      reports poisoned-query count. The native interactive SMB/NTLM Responder still works too.
- [ ] **BLE Remote (HID)**: BLE keyboard/presenter pairs with a host; arrow/media/typed keys
      register. Begin/refresh/end advertising from the app.

## Previously added, re-verify end to end
- [ ] Evil Portal (AP + captive page + live creds), Sub-GHz jammer/bruteforce, IR jammer, jam detect,
      BLE iBeacon/media, Network Scan (hosts/ports/CVE), Channel Analyzer, ARP/NetCut + MITM proxy,
      WPA/PMKID crack, Sub-GHz capture/replay/decode, NFC (with PN532), SSTV RX/TX, mic
      spectrum/waterfall/oscilloscope, record, speaker volume.

## Known bugs to investigate (could not be reproduced remotely)
- [ ] **Grey icons on base theme**: some home-grid/tool icons appear grey until the theme is changed.
      Check whether it's capability-gating (intended grey for absent modules) vs the device reporting
      a dark theme color vs a recomposition issue. Note which specific icons and in which state
      (connected? which modules?).
- [ ] **APK update one-tap** (fix implemented): first tap on "Download & install" starts the
      install; after granting "install unknown apps" it auto-retries on resume. Confirm on device.

## Remaining Bruce parity - status
Karma, Responder, BLE HID (above), SRIX read and the Ethernet attacks are now implemented.

Implemented, verify on hardware:
- [ ] **SRIX / ST25TB** read (`srix_read`, NFC screen -> advanced). Uses the onboard PN532.
- [ ] **Ethernet DHCP-starvation / MAC-flooding** (`eth_dhcp_starve` / `eth_mac_flood`, Ethernet
      screen -> Attacks). Needs a W5500 add-on; gated on `caps.eth`.
- [ ] NRF24 spectrum/scan, LoRa chat/send/recv: already wired as companion commands (`nrf24_scan`,
      `lora_*`); verify once the NRF24 / LoRa hardware is attached.

Not portable to companion (external device only - documented, not a gap):
- NFC Chameleon / amiibo: drive an external ChameleonUltra / Amiibolink over serial/BLE through an
  interactive GUI; no headless PN532 primitive exists. The app reports this honestly.
- MIFARE nested / mfkey32: no native cracker; needs an external PN532Killer sniffer. The onboard
  PN532 does the dictionary key-check (`mifare_keycheck`). The app buttons now say so.
- 125 kHz LF write/clone/T5577: no native write path (read is interactive UART only).

## Recently shipped - verify on hardware
- [ ] **Melody composer** (Tone tool): tap notes/rests, set tempo, Play on the device speaker,
      Save -> appears under "My songs" and survives an app restart.
- [ ] **Companion status screen**: when launched from the device menu it shows a download QR, then
      "Download the app to connect: github.com/1vcian/BruciaDev" while waiting and "App connected."
      once the app attaches.
- [ ] **App-mode input lock**: while connected, the wheel/OK and a short ESC tap do nothing; a long
      ESC hold (~700ms) exits companion mode (disconnects); reconnect via the app re-locks. Confirm
      the hold timing works on the T-Embed and that it never gets stuck locked.
- [ ] **125 kHz LF read** (NFC advanced -> "125kHz read"): on a board with the UART LF reader,
      returns a tag; safe no-op elsewhere.
- [ ] **Macros** (Tools -> Macros): build a command sequence, save, run one-tap; per-step progress;
      Stop cancels; macros persist across restarts.
- [ ] **Evil Portal HTML editor** (Evil Portal -> Custom): edit HTML, Preview renders in the WebView,
      Save keeps a named template; starting with Custom pushes the edited page to the device and the
      captive portal serves it.
- [ ] **3D RF map** (Tools -> RF Map 3D): start Wi-Fi/BLE scans + sub-GHz sweep, Refresh plots the
      sources, drag rotates, slider zooms, Save PNG writes a file. (Approximate placement by RSSI.)

> AUDIT SCOPE: two Opus audit passes are done. (1) The Karma/Responder/BLE-HID/SRIX/Ethernet/
> composer batch (8 findings, all fixed). (2) The feature sprint - input-lock, 125 kHz, macros,
> Evil Portal editor, 3D map, bug sweep, boot embers (8 findings: fixed the macro double-launch
> race, two WebView leaks, the dead Bug-Sweep trend/peak logic, the BLE over-stop, the empty-PNG
> notice, and hardened the ESC-hold exit with a tap-count + 60s app-gone auto-exit so the lock can
> never strand the device). 125 kHz, LED/LITE guards, Ethernet dtors, input-lock Serial-safety and
> all the persistence paths were confirmed correct. Everything still needs on-hardware confirmation,
> but the code has been reviewed.

## Queued feature ideas (roadmap)
- [ ] Device-side background companion (keep using the device UI while the app is connected) - a
      larger architectural change, deferred (risks the "reboots while connected" class of bug).
