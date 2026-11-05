# Work queue — single operational ledger

Updated 2026-11-04. Target: `VERSION` (currently 0.1.3). The 0.1.2 checkpoint was committed, tagged and
published to the private BruciaDev repo (APK + T-Embed CC1101 firmware) by `release.sh`:
Python/site/native firmware/protocol E2E tests, 424 unit tests + lint, T-Embed build and
signed release APK passed. Only lilygo-t-embed-cc1101 was rebuilt (other boards at public
release, community-tested). Radio/hardware
features remain pending on-device acceptance (S05). See [TESTS.md](../../../TESTS.md)
for evidence. Recheck environment constraints before assuming an earlier
build/Git/network blocker persists.

Status vocabulary: OPEN, IN_PROGRESS, BLOCKED, DONE; worker packets also use PREPARED/HOLD. DONE needs a test/result;
record unavailable devices, missing schemas and environment restrictions explicitly.
When a task is finished, update its row and next action here, not a new status file.

## Current user priority

**2026-11-04: the user explicitly authorized Claude worker launch and orchestration
for the remaining software tasks.** Independent scoped packets may run together;
coordinator review/test/integration remains mandatory before implementation follow-ups.
This supersedes the earlier one-packet-per-user-message restriction for this batch.
Explicitly permitted execution verified host Claude authentication and Docker 27.5.1.
The user then explicitly requested the installed Mac Claude Code directly, without
Linux/Docker, overriding the packet OS/network isolation prerequisite for this batch.
Four local workers are running: S08a, S09, S11a, S06-S10a. Packet/source hashes match;
CLI tools are scoped, customizations disabled, no permission-bypass flags used.
This is not OS isolation. Results require coordinator review before integration.
S02/S07 packets remain blocked by their missing prerequisites.
W06 and W07 are done and wired. W08 (phone-only pilot), W09 (device-backed pilot, iButton) and W10 (firmware adapter, iButton) are done; W11 runs in batches, one at a time after review.
Release correctness and physical acceptance gates still apply before publication.

## Immediate queue

| ID | Priority | State | Next action / acceptance |
|---|---|---|---|
| W06 | P0 FIRST | DONE | Feature registry `features/FeatureRegistry`+`BuiltinFeatures` (50 tools/11 categories, stable IDs frozen) is now the only catalog: `MainActivity` renders the home grid, search, category availability/badges and `capForTab` from it; the local ToolEntry/ToolCategory list was removed after a field-by-field comparison (61/61 identical). Numeric route switch unchanged. Evidence: `:app:testDebugUnitTest` 292/292, `lintDebug` no new findings. On-device UI check pending (S05). |
| W07 | P0 | DONE | `session/CommandSession` (worker, worktree isolation; deviation recorded in T07) now owns all command/reply correlation and is wired into `WardriveViewModel`: one session per client, closed on disconnect; dispatchCmd/Json, sendAwait, fmBegin, SUB acks (setStream), fs_read, upload chunks (sendNoLog), ACK/ERR routing and the ping watchdog go through it; pendingAcks/pendingJsonAcks/transmitCommand removed; caps guard is the injected preflight. Coordinator added expect/transmit/fail/release/subscribe + onCallbackError and 3 tests (incl. close isolates a throwing callback). Evidence: `:app:testDebugUnitTest` 292/292 (CommandSessionTest 13), `lintDebug` clean for changed files. Behavior change: a late ACK after disconnect no longer reaches the console. Not hardware-verified (S05). |
| W08 | P0 | DONE | Phone-only pilot: PCAP dissect (tab 45) analysis moved to pure-JVM `wifi/PcapDissector` (dissect + `readBounded`); screen delegates with unchanged signature/texts/layout. Behavior changes: captures over 32 MiB rejected (was unbounded `readBytes`), cooperative cancellation every 256 records (CancellationException no longer shown as "Failed to read"), BSSID ties ranked count desc then BSSID asc. 7 fixture tests (PcapWriter). Coordinator review: scope respected (3 files), input hashes unchanged, full `:app:testDebugUnitTest` 299/299, `lintDebug` no findings in changed files. Worktree isolation (deviation as T07). Remaining: blocking stream read is bounded but not interruptible; on-device check pending (S05). |
| W09 | P0 | DONE | Device-backed pilot: iButton (tab 20) runs on `ibutton/IButtonService` over the new `session/CommandRunner` seam (VM adapter = `sendAwait` on IO, so the command log and "Stopped." entries are kept). Behavior: 10 s timeout (was 650 s; firmware op < 1 s), Stop button while busy, a new op supersedes the old one and late replies never overwrite status, local 16-hex id validation (normalized id sent), missing interface/not connected send nothing, disconnect shows "Disconnected". `hasInterface` reads the same caps as the session preflight. 10 tests (fake transport + real CommandSession, incl. reconnect). Coordinator review: scope respected, input hashes unchanged; full `:app:testDebugUnitTest` 309/309, `lintDebug` no new findings. Worktree isolation (deviation as T07). Physical iButton test needs an external 1-Wire probe (S05). |
| W10 | P0 | DONE | Firmware iButton adapter: Arduino-free `src/modules/others/ibutton_core.{h,cpp}` (id parse/format, Maxim CRC8, pin rule, ROM read over a bus interface) shared by the standalone menu (behavior preserved: Esc, stop on no presence, 20 CRC retries + screen, bad-CRC data kept), `.ibtn` loading and the companion; no global OneWire pointer swap. Companion `ibutton_*` -> ERR 409 "iButton pin not configured" before any GPIO use when the pin is 0 (unset; S3 strap pin) or -1; CAPS `ibutton` = configured pin (not probed), which the app already reads. Small behavior changes: headless read keeps keyBuffer on failure and returns CRC_ERROR when a key answered; `.ibtn` UID lines with non-hex chars are skipped. Evidence: `tools/test_firmware_ibutton.py` 71 checks (UBSan; ASan unavailable here), codec/framing/storage/web/build tool tests pass, `lilygo-t-embed-cc1101` build SUCCESS (RAM 47.1%, flash 25.6%). Other boards deferred to release (community testing). Not hardware-tested: needs an external 1-Wire probe on a configured GPIO (S05). |
| W11 | P0 | DONE | Remaining families in batches. Batch 1 DONE 2026-10-30 (T11a): `session/DeviceOperation` (single-owner status/busy/stop/supersede, 10 s default) derived from IButtonService, which now delegates to it (IButtonServiceTest unchanged, 10/10); clock (`device/ClockService`) and Wi-Fi MAC (`wifi/WifiMacService`) migrated and wired (timeouts 650 s -> 10 s; local epoch >= 1e9 and MAC-format checks mirror firmware). Evidence: `:app:testDebugUnitTest` 319/319, `lintDebug` no new findings. 61 `dispatchCmd` callers and 10 burst loops remain. Batch 2 DONE 2026-10-30 (T11b): `device/ScriptService` (25 s; blank/>6000-byte source rejected locally; clear keeps a running script's output), `device/DeviceAppearanceService` (theme/LED/brightness/effect/save share one owner, latest wins; color callbacks only for the owning request), `wifi/WifiJoinService` (join 30 s, others 10 s), `device/EspNowService`; `DeviceOperation` gained a per-call timeout and an atomic `clearStatus()` (coordinator replaced the worker's flag-based clear). DTMF no longer writes its unreachable not-connected text into the theme status. Evidence: `:app:testDebugUnitTest` 333/333, `lintDebug` no new findings. 51 `dispatchCmd` callers and 10 burst loops remain. Batch 3 DONE 2026-10-30 (T11c): `audio/SpeakerService` (volume/tone 15 s/file 600 s/song; tone 1..20000 Hz checked locally; song state guarded so a superseded start never clears a newer song; DTMF and speaker_stop sent alongside without taking over a pending tone/file wait - coordinator fix after review) and `audio/MicRecordService` (timeout min(secs,30)+10 s); `DeviceOperation` results may return null to keep the status. Evidence: `:app:testDebugUnitTest` 347/347, `lintDebug` no new findings. 44 `dispatchCmd` callers remain. Batch 4 launched 2026-10-30 as two parallel workers on disjoint files: T11d BLE (HID, BadBLE, media) and T11e IR. T11e DONE: `ir/IrReceiveService` (receive-once and remote-key capture on separate owners, 20 s; result/callback only for the owning request) and `ir/TvBGoneService` (75 s), wired; coordinator added `DeviceOperation.show()` (replacing the worker's connection-check workaround for markSaved and the remote "Sent:" note). BUG FIXED (pre-existing): the firmware `ir_rx` ACK is a one-line summary ("NEC addr=04 00 00 00 cmd=08 00 00 00" / "raw: <durations>"), which IrParser never decoded, so IR receive never produced a result and remote-key capture always failed on hardware; new `IrParser.parseRxSummary` fallback (IrRxSummaryTest 3). Suite 365/365, lint clean. Raw summaries carry no frequency (38 kHz assumed) - verify on hardware (S05). T11d DONE: `ble/BleHidService` (begin/end on one owner, end never leaves "Starting..."; status/key/type alongside; 10 s - coordinator lowered from 650 s since HID ACKs at once), `ble/BadBleService` and `ble/BleMediaService` (650 s kept: script length and connect cascade have no firmware bound), wired. Suite 377/377, lint clean. 34 `dispatchCmd` callers and 10 burst loops remain. Batch 5 launched 2026-10-30 in parallel: T11f LoRa+Ethernet (on `startJson`) and T11g NFC advanced tiles. T11f DONE: `lora/LoraService` (config 10 s - coordinator lowered from 650 s; send 650 s since airtime at narrow BW/SF12 can be minutes; recv secs+10 s; packet callback and receiving flag only for the owning receive) and `net/EthernetService` (status 16 s, config 18 s with one chained refresh, starve/flood secs+14 s; bounds from network.cpp), wired through the new VM `jsonRunner` (`sendAwaitJson`, command log kept). Suite 390/390, lint clean. T11g DONE: `nfc/NfcToolsService` with four independent lines (NDEF push 75 s; EMV read and UID write 650 s, no firmware bound; advanced tiles shared: srix/rfid125 secs(1..30)+15 s, amiibo/nested/mfkey32 10 s - coordinator lowered from 650 s since they ACK at once with fixed "not supported headless" texts), wired. The worker's first run hung on never-completed test gates; fixed, and its tests now carry 5 s JUnit timeouts. Suite 401/401, lint clean. 25 `dispatchCmd` callers and 10 burst loops remain. BUG FIXED 2026-10-30 (coordinator): `stopAllBurstLoops` (used by the Cmds "Stop all" button, the link watchdog and disconnect) missed the Sub-GHz jammer, IR jammer, ARP spoof and iBeacon loops, so "Stop all" left them running; added (not hardware-verified). Batch 6 DONE: T11h `session/BurstLoop`+`BurstLoopRegistry` (coordinator added a shared status line for NRF24 scan/sniff and an owner-only `onEnd` for the deauth target); all 11 burst loops migrated by the coordinator, `launchBurstLoop` removed, `stopAllBurstLoops` = `registry.stopAll` (loops are lazy and register when built, so none can be missed). T11i `session/LongOperation` + Responder/Karma/Brucegotchi services (timeouts secs clamped 5..600 + 30 s per wifi.cpp; `requestStop` keeps waiting for the final ACK); coordinator added a direct, unlogged stop channel (the firmware polls but never ACKs the stop frame), wired, and replaced the shared `stopLongOp` with dedicated Responder/Karma Stop buttons. Behavior change: a non-silent Stop no longer overwrites a rejection text with "Stopped.". Suite 424/424, lint clean. 22 `dispatchCmd` callers remain (Wi-Fi capture/crack, Evil Portal, GATT, console/RF/PCM streams, jam_detect/WIDS loops). Batch 7 launched after 0.1.2 in parallel: T11j monitors (jam_detect, WIDS - BUG: WIDS spins on "Not connected." after a disconnect and is outside stop-all - and Sub-GHz brute force) and T11k Evil Portal + GATT. Fire-and-forget/console/PCM-stream commands intentionally stay on `dispatchCmd`. T11k DONE: `wifi/EvilPortalService` (Stop wins over a pending template push or start; active guarded by a start token; coordinator fix: Stop while disconnected now clears `active`, as the VM did) and `ble/GattService` (reads alongside, stale reads after stop/reconnect suppressed; VM keeps the EVENT tree, cleared via onReset), both 650 s (no firmware bound: portal ACKs at once and runs in background; GATT connect 2x8 s + unbounded discovery), wired (pushTemplate adapts the callback `pushFile`). Suite 439/439, lint clean. T11j DONE: `monitor/JamDetectService`, `subghz/SubGhzBruteforceService` (timeouts (secs+8) s cover the firmware clamps 1..120 / 1..30) and `monitor/WidsService` - BUG FIXED: the loop now ends when the link closes or the device disconnects ("Monitor stopped (link closed).") instead of spinning on "Not connected.", and stop-all stops it; coordinator added a 1 s back-off after a failed window so an instant firmware rejection cannot spin either. Suite 455/455, lint clean. Subsequent capture/dictionary, Mifare and macro migrations are recorded in W11l–W11n below. Remaining callback wrappers and cross-service cleanup need W11o review; console/stream adapters may remain intentional. JSON-reply support added by the coordinator meanwhile (`session/JsonCommandRunner`, `DeviceOperation.startJson`, same ownership/texts; test added, suite 348/348); LoRa/Ethernet can now be migrated. Firmware follow-up: blocking `speaker_play` stalls the companion link for the whole file and cannot be stopped. Burst loop extraction is recorded in batch 6 above; cross-service cleanup remains under review. Batch 3 (SPK): `speaker_play` is non-blocking on firmware advertising `features.speaker_play_async` (Bruce PLAYBACK_ASYNC task; ACK at start with `play` id, one terminal EVENT `speaker.play` completed/stopped/error, speaker_stop bounded ~2.5 s, superseded by other speaker/mic/PCM users); app SpeakerService follows it (15 s start wait, Stop button), old firmware keeps 600 s. Only a read-only `isAudioPlaybackTaskActive()` added to Bruce audio module. Decoder cannot distinguish EOF from corrupt data (both `completed`). SpeakerServiceTest +10. 2026-11-05 batch 4 (W11CLOSE): closed. Remaining request/response callers moved to services: `nfc/NfcTagService` (rfid read/info/ndef, 650 s kept: driver read has no bound), `subghz/RfReceiveService` (`rf rx`, 650 -> 20 s: rfReceiveSignal(...,10,...)), `IrReceiveService.learn` (`ir rx`, 650 -> 20 s: loop_headless(10)), `device/DiagnosticsService` (info/free/uptime/i2c, 20 s; other commands rejected locally), `files/DeviceFileBrowser` (storage list/remove/mkdir and Crack capture list, 650 s kept); dead `captureHandshake` and `runWithResult` deleted, `dispatchCmd` no longer takes a callback. Intentional fire-and-forget left on `dispatchCmd`: scr/led/scr_bright toggles, ir/rf tx replay, transmitDeBruijn/transmitSub, ir_tx_raw, console. Behavior: Files no longer stuck on Loading after Stop all/disconnect; directory change clears old entries; latest-wins per line; offline no longer overwrites NFC/RF results. +25 tests. Upstream note: firmware `ir rx` leaks its IrRead on timeout (no delete before return false). |
| W11l | P0 | DONE | 2026-10-31 coordinator: offline dictionary lifecycle extracted into `wifi/DictionaryTestService`, wired into demo/imported/bundled callers. Per-run ownership + coroutine cancellation prevents old CPU work/results resurfacing after restart; readers close on all exits, target/rules snapshot at start, missing target rejects instead of silently using a synthetic one. Actual candidate counts and monotonic throughput; read errors no longer look like successful exhaustion. 7 synthetic service regressions; cached full Kotlin compile (146 production files) and 445 tests / 77 non-Paparazzi suites pass. Gradle retry denied global wrapper lock; no fresh lint/APK or hardware claim. |
| W11m | P0 | DONE | 2026-10-31 coordinator: `wifi/CaptureWorkflow` now owns guided capture/list/read/import. Capture ERR stops the chain; each run binds to its original session; Stop/reset/disconnect/supersede prevent late imports. Pre/post SHA-256 comparison permits only new/changed matching files, rejects ambiguous results, validates parsed AP MAC, caps candidate files at 8 and total workflow at 10 minutes. Directory absence is checked via parent listings; suspicious CLI truncation fails closed. New Cancel import control explicitly does not claim firmware abort. 11 synthetic tests plus complete cached Kotlin checks; see TESTS.md. Existing unchanged captures remain manually importable. Firmware/protocol unchanged; physical capture acceptance pending. |
| W11n | P0 | DONE | Mifare stage implemented 2026-10-31: `nfc/MifareKeyCheckService` owns existing 48-key batches and snapshots the connection/dictionary; Stop/reset/disconnect/supersede suppress late results, command failure stops the sequence and keeps prior hits, malformed replies reject without partial commit. Stop batches UI and Cmds Stop all are wired. The 650 s per-command wait is preserved; cancellation does not abort firmware. 11 new tests; full cached compile of 148 Kotlin files and 467 tests / 79 suites passed (release cache). Fresh Gradle unit/lint blocked by the global wrapper lock; no device test. See TESTS.md. Macro stage DONE 2026-10-31: `automation/MacroRunner` snapshots steps and original session; preserves the double-start guard, stops on first failure, ignores old completion after Stop/restart and clears state on scope cancellation. Cmds Stop all now stops macros. Raw-command timeout corrected from burst 12 s to generic 650 s; delays match persisted 0–60 s bounds. 12 new macro regressions; combined full cached compile/tests: 149 production files, 479 tests / 80 suites. No firmware/JSON-format changes; hardware and fresh lint/APK pending. Next W11o. |
| W11o | P0 | DONE | 2026-10-31 coordinator: reviewed migrated services and remaining callback wrappers; `ServiceLifecycle` now provides one explicit Stop all/disconnect inventory with isolated cleanup errors. `CommandTasks` owns parallel BLE/GATT/speaker/stop waits and legacy callbacks; unused JSON dispatcher removed. DeviceOperation checks ownership/cancellation before result mappers, rejects a cancelled scope, clears busy on cancellation and reports mapper failures. Disconnect abandons portal/speaker/GATT ownership and remote readers; Stop all also stops subscriptions, timeline/audio sources and phone-only dictionary work. Real firmware stop waits retain actual log results. 10 new regressions; full cached compilation 151 production files, 489 tests / 81 suites passed. Hardware/fresh lint/APK pending. Review found file coroutine ownership still separate: W11p. |
| W11p | P0 | DONE | 2026-11-01 coordinator: `FileTransferService` owns queued/active work, pins sessions at invocation, serializes processing, supersedes consumer/destination requests and guards imports/callbacks. `SessionFileLink` extracts the existing verified read/write wire adapter shared with guided capture. Stop all/disconnect cancel transfers; template cancellation propagates to the actual upload and back to the caller. Manual pulls use unique staged files removed if discarded; exports check cancellation before open/between chunks. Original size/CRC/request-ID and write ACK-size checks preserved. 18 new regressions; full cached compilation 153 files, 507 tests / 83 suites passed. S04 subsequently added firmware transactions; provider rollback, hardware/fresh lint/APK still pending. |
| W11q | P0 | DONE | 2026-11-01 coordinator: `PcmStreamService` owns FM/SSTV preparation, encoding, streaming and cleanup; `SessionPcmLink` pins the original session and awaits begin/data/stop replies. Stop/restart cannot publish old progress or send an old stop after the next begin; sources close before the wire lease is released. SSTV and file decode gained cancellation checks, file decoder buffers cap at 16 MiB, FM mic read errors propagate. BUG FIXED: speaker PCM was sent as appended CLI arguments, but existing firmware requires `d.sr`/`d.pcm`; new `awaitFields`/`cmdFields` send the existing schema with request correlation. 17 new tests; full cached compilation 155 production files, 524 tests / 85 suites passed. Physical/codec acceptance and FM buffer-loss evidence remain pending. S01 subsequently completed below; S03 subsequently completed; S04 subsequently completed below; next S06, do not auto-chain. |
| D01 | P0 | DONE | Consolidated 13 redundant documents into skill/backlog, TESTS, CHANGELOG and ROADMAP; skill validation, local documentation links and seven website tests passed. |
| S01 | P0 | DONE | 2026-11-01 coordinator: one `AppUpdater.fetchSnapshot` response feeds `ReleaseUpdateService` and a single UI state for version, notes, APK, board list and selection. Refresh clears old assets before IO; failures/cancellation cannot publish stale URLs, obsolete selections reject, duplicate checks are single-flight. Refresh is blocked during APK/flash handoff; flash preflight rejects missing/unsupported images before USB takeover. Nine fixture regressions; full cached compilation 156 production Kotlin files, 533 tests / 86 suites passed. Gradle/lint retry denied global wrapper lock; live OTA, permission UI and hardware flash remain unverified. S03 subsequently completed below; S04 subsequently completed below; next S06, do not auto-chain. Batch 3 (DLCONC): `update/AppDownloadService` single-flight APK download, `DownloadIO.downloadFile/downloadBytes/owned`; 15 deterministic tests (UpdateDownloadConcurrencyTest). Fixed: concurrent same-file downloads overwrote each other (now DownloadBusyException), APK download ignored cancellation and left a full APK (now stops at next read, files removed), cancel-before-start left `downloading=true`, firmware download continued to the flash write after the flash token ended (now token-owned; state written only while the token is held). Limits: VM not instantiated (extracted copies), cancellation only between reads (30 s read timeout). |
| S02 | P0 | IN_PROGRESS | 2026-11-04 Claude subagent, clean worktree, no keystore access: `clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug` (JDK 23) BUILD SUCCESSFUL, KSP fresh, 584/584, lint 0 errors (some compile tasks restored from the global Gradle build cache). Debug+release: app.brucia 0.1.3/1000103, minSdk 26, targetSdk 34; release unsigned as expected. Emulator (Medium_Phone_API_36.1): debug->debug `adb install -r` of 59f6f749 -> current kept Room v3 rows, identity hash, prefs and files; relaunch without crash (same versionCode, no UI drive). Signed release -> debug correctly refused (signature). CI gaps: Temurin 21 vs local 23, no assembleRelease, runner-signed debug APK only, no SDK setup step. Remaining: signed release build/verify and signed release->release upgrade (keystore), real versionCode upgrade, actual CI run. Batch 2 CI: all actions pinned to full SHAs (verified with git ls-remote against tags), Temurin 21 kept and documented, explicit android-actions/setup-android (platform 34, build-tools 34.0.0), CI builds unsigned assembleRelease, `tools/check_apk_identity.py` (+13 host tests) checks applicationId/VERSION/minSdk/targetSdk/debuggable on both APKs, artifacts renamed, iButton host test added to CI. Not executed on GitHub yet. Batch 4 (EMU, emulator API 36, debug signing only): real versionCode upgrade 1000103 -> 1000104 (VERSION bumped only for the build, not kept) kept Room v3 identity hash and all seeded rows, prefs and files byte-identical, no app FATAL; downgrade refused with INSTALL_FAILED_VERSION_DOWNGRADE. Still open: signed release build and release->release upgrade (keystore), actual GitHub CI run. 2026-11-05: 0.1.4 published to BruciaDev (release.sh). Batch 5 (EMU2, emulator API 36, published APKs, no keystore access): v0.1.3 and v0.1.4 signed by the same certificate (SHA-256 deac265d...c84b, v2 scheme); 0.1.3 -> 0.1.4 `install -r` kept firstInstallTime, a macro saved through the UI and the location grant, no FATAL; debug over release refused (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Release Room/prefs not directly observable (non-debuggable). First real CI run (0.1.4 push): android and protocol-e2e jobs PASS; firmware jobs built but the AAC noise-bounds test FAILED on all three boards - fresh-checkout patch-ordering defect, fixed in batch 5 (see Additional retained review points); CI rerun pending on the next push. Remaining: targetSdk 35 signed release upgrade on a phone. |
| S03 | P0 | DONE | 2026-11-04 coordinator: software matrix covers all 50 tools + Theme in APP_CONTROLS_CHECKLIST.md, with CMD/SUB/BURST/LOCAL ownership, requirements, deadlines, input validation and explicit evidence/limits. Added central CommandCapabilities and StreamSubscriptions; uncertain ACK outcomes remain eligible for OFF cleanup. Fixed BurstLoop/WIDS cancellation/startup, invalid duration waits, LoRa Stop/supersession, mixed-tool gates and failed-capture persistence. 24 new regressions; full cached Kotlin compilation 158 files, 557 tests / 88 suites passed. Tab matrix matches source (51/51); physical checkboxes untouched. Gradle/lint still denied global lock. Remaining Android/job/firmware boundaries stay S02/S04–S11; S04 subsequently completed below; next S06, do not auto-chain. |
| S04 | P0 | DONE | 2026-11-04 coordinator: additive transactional_uploads CAPS and fs_upload_* protocol; app validates token/cumulative bytes/commit CRC, never falls back to truncate, and reports uncertain commit outcomes. Firmware stages/verifies before promotion; LittleFS rename, FAT journal/backup/rollback with retained previous bytes on recovery failure. Reserved paths and concurrent legacy writes reject; idle expiry starts after slow IO. 41 UBSan engine scenarios plus real-handler fixtures, 563 cached JVM tests / 88 suites / 158 production files; three board builds (see TESTS.md). FAT visibility/power-loss durability, card swaps and Android provider rollback are not certified. No worker launched; next S06 only on explicit request. |
| S05 | P0 | BLOCKED | Final app/device acceptance and flash recovery; phone, board and appropriate peripherals required. |
| S06 | P1 NEXT | IN_PROGRESS | 2026-11-04 PortLease/ownership slice integrated: `transport/UsbOwnership` (PortLease close-once, FlashPortOwner token per flash with 90 s handoff deadline, stale/duplicate/expired ports closed on arrival, `launchFlashOwned` releases on cancellation-before-start via invokeOnCompletion; UsbPermissionRequests correlated by process-wide id + EXTRA_DEVICE name, 60 s deadline). MainActivity: one permission receiver onCreate..onDestroy, unique request codes, onDestroy cancels pending requests and aborts a waiting flash; Connect/openPort refused while flashing; transport construction failure closes the connection. VM: `flashEnteringBootloader()` returns a token, `flashFirmware(token, io, lease)` adopts by identity (no blanket isFlashing rejection), `flashAbort` ignores stale tokens, `onCleared` shuts the owner. Tests: UsbOwnershipTest 14. Behavior change: Activity recreation during the permission wait aborts the flash. Open: flash still calls `vm.disconnect()` (service/wake lock stopped during flash), companion-link vs flash arbitration (slice B), port.close()/connection semantics (D4/D5). No device evidence. Batch 2 (slice B): FlashPortOwner now arbitrates the USB device between companion link and flash by token (Connect/attach auto-connect via beginCompanionLink/connect(token)/abortCompanionLink; second open refused, flash preempts and closes the companion port, abandoned open expires after 10 s; "USB device busy" toast). D6a fixed: flash no longer calls disconnect(); ConnectionService switches to owner `flash:<token>` (keeps wake lock) and every flash end path (finish/abort/expiry/late handoff/cancel-before-start/onCleared) stops it once via onEnded. D4/D5 evidence from usb-serial-for-android 3.8.1 bytecode: `CommonUsbSerialPort.close()` closes the UsbDeviceConnection (l.155), `open()` throws "Already open" before taking the connection (l.114-115); new `releaseUsbPort(openedHere)` avoids killing a live link on a failed second open. onCleared disconnects before closing the owner. UsbOwnershipTest 22. Device-only: wake lock through a real flash, claimInterface on two connections, close() idempotency API 26-35. |
| S07 | P1 | BLOCKED | Obtain real Room v1/v2 schemas and fixtures before supporting historical migrations. |
| S08 | P1 | IN_PROGRESS | Long-session memory/queue/drop/Stop tests with deterministic virtual transport; physical stress remains separate. 2026-11-04 batch 2: `SessionStressTest` (6 tests, virtual clock + fake fragmenting/coalescing transport, no sleeps): 5,000 mixed commands with timeouts/cancellation/late replies, 20,000-frame flood incl. 8192-byte and malformed frames (buffer <= 12,292 bytes), 3,000-step SUB model check, 500 Stop-all cycles with rejected/missing/unwritten OFF, 300 reconnects with residual callbacks, exact 8000 ms deadlines. All pending maps/buffers/timers asserted empty per round; mutation check (5 injected leaks) caught every one; 5 consecutive runs green. No defect found, no production change. Limits: no app-side drop counter for malformed frames (silently skipped), VM reader loop/disconnect mimicked not executed, real threads/heap/USB out of scope. Batch 4 (DIAG): app-side `protocol/LinkCounters` now counts malformed and invalid-length frames (previously skipped silently), timeouts and unmatched replies; shown in the Diagnostics card and exports. |
| S09 | P1 | IN_PROGRESS | Local Claude patch reviewed and integrated: batch/stream decoders predict from the previous measured sync, preventing cumulative Scottie S1 rounding drift. Coordinator reproduced 2 failures before / 31 passing DSP tests after (27 baseline + 4 new). Fractional-time fixture is explicitly synthetic, not external evidence. Obtain a licensed authoritative timing reference and/or independently encoded recording with known source image before interoperability acceptance. |
| S10 | P1 | IN_PROGRESS | 2026-11-04: ConnectionService start rule extracted to pure `ConnectionServiceCommand`: null intent (system restart) or missing status stops the service instead of advertising Connected/taking the wake lock; START_NOT_STICKY. ConnectionServiceCommandTest 4. Activity recreation, receiver cleanup, swipe-away, foreground/wake-lock behavior still require instrumentation/device evidence. Batch 2: decision rule is owner-aware (companion vs `flash:<token>`): a companion stop/update cannot end or replace a flash, stale flash stops ignored, only a newer flash token replaces a shown flash; `stopSelf(startId)`. ConnectionServiceCommandTest 11. Batch 4 (EMU): first instrumentation tests (`app/src/androidTest`, androidx.test pinned), 7/7 on emulator API 36: START_NOT_STICKY + foreground + wake lock, stop releases everything, missing status and null-intent restart (fresh and while shown) stop without wake lock, companion vs flash token owner rules, Activity recreate x2 keeps exactly one USB permission receiver and destroy unregisters it (read from dumpsys, no hook); 5 deliberate mutations caught. Test-only `ConnectionService.live` reference. Device-only: real USB attach/permission, long screen-off session, swipe-away/process kill during link or flash. |
| S11 | P1 | IN_PROGRESS | 2026-11-04 firmware receive fixes integrated (no TX/replay changes): rfSaveSignal returns false on create/short-write failure and RFScan only marks lastSavedKey on success; companion `subghz.raw`/`subghz.spectrum` SUB now ERR 503 with everything released when CC1101/RMT init, queue, callback, enable or receive fails; failed raw re-arm retried (500 ms throttle); setup_rf_rx returns NULL instead of ESP_ERROR_CHECK abort; rf_scan starts at start_freq, N>0 bounds steps, Esc per step; rfReceiveSignal N<=0 waits for Esc (default no longer times out after one poll), headless init failure printed; raw event `freq` = tuned bruceConfigPins.rfFreq. Additive `symbols`/`max_symbols` (64): below max rules out a buffer cut, equal = completeness unknown (IDF 5.5.4 RMT drops silently without partial RX); never claims proved loss. PROTOCOL.md updated (timings unit corrected to us). Evidence: T-Embed build, host tests incl. protocol E2E. Open: app ignores new fields and leaves the Listen switch on after ERR; spectrum kHz rounding/range validation; rfScanCallback ignores rf_scan result. All behavior needs S05. Batch 2 follow-ups DONE in software: Listen/Spectrum UI driven by `subghz/SubGhzReceiveState` from StreamSubscriptions (ERR/timeout/rejection turns it off with the reason; OFF failures and peer replacement are not reported as start failures); `SubGhzRawEvent` parses symbols/max_symbols (absent/malformed = no information), waveform shows "possibly truncated (device buffer full)", CSV gains trailing symbols,max_symbols columns, .sub unchanged. Firmware: `subghz_spectrum_plan.h` validates sweeps (finite, 280-928 MHz, end >= start, step >= 1 kHz, <= 128 points) with ERR 400 instead of silent swap/clamp/widen; grid quantized to whole kHz (no cumulative truncation); start during a sweep applies the new grid; raw freq rounded to kHz. `rf scan` CLI reports found/none/init failure and refuses non-CC1101 modules. Tests: SubGhzReceiveTest 7, CSV +1, tools/test_firmware_spectrum.py (UBSan). Remaining: S05 hardware checks only. |
| W02 | P1 | DONE | 2026-11-04 `mitm/HttpWire`: header values trimmed only of SP/HTAB before validation (Unicode `trim()` hid VT/FF/FS-US/NBSP around Content-Length/Transfer-Encoding); response status must be exactly 3 digits (`+200`/`0200` were accepted and rewritten). HttpWireTest +2 (failed before). Noted, unchanged: bare LF on chunk-size lines, chunk-extension bytes bounded only by time budget, HTTP/1.0+TE keep-alive, DEL allowed in values. Batch 3 leftovers DONE: chunk-size lines require CRLF (field lines keep RFC 9112 bare-LF tolerance), chunk extensions capped at 4096 bytes/message and size digits at 16, DEL rejected in field values (obs-text kept), HTTP/1.0 + Transfer-Encoding rejected as framing error, only exact `CONNECT` tunnels (HttpWire + one line in MitmProxy). HttpWireTest +5. HEAD/body methods still case-insensitive by design. |
| W03 | P1 | DONE | 2026-11-04 app/web release catalog parity: both accept only the exact `https://github.com/1vcian/BruciaDev/releases/download/<tag>/<name>` string (web `new URL()` normalized 10 spellings the app rejected; app accepted `:443`); tag must be a string with 1-9 digits per part; web rejects two APKs like the app; app pre-scan rejects JSON nesting > 32 (Android org.json is unbounded, StackOverflowError not caught); null body -> empty notes. ReleaseCatalogTest +2, test_site_release.cjs +1. Open: no asset size/sha256 digest check on either side. Batch 2 follow-up DONE: `update/AssetIntegrity` + `DownloadIO.copyVerified`; app and web verify GitHub asset `size` and `digest` (sha256) before install/flash; absent = allowed but labeled unverified, malformed = asset rejected (still counts for ambiguity), mismatch = hard failure ("Nothing was installed/flashed"). Web page fetches, verifies with crypto.subtle and hands esp-web-tools only a blob: URL. ReleaseIntegrityTest 5, site tests 15. Coordinator: added AssetIntegrity.kt to tools/test_android_logic.py (was failing to compile) and replaced fixed event-loop waits in the page-script tests with waiting on pending digests (1 failure in 16 runs under CPU load before; 10/10 after). Behavior: the page downloads the firmware as soon as a release loads on Web Serial browsers. Not tested against a real release/browser; possible CORS limits. |
| W01 | P1 | DONE | 2026-11-04 RecordingBuffer reviewed, unchanged. `AudioExport.saveInto`: exclusive folder creation (`rec_<stamp>`, `_1`..`_99`) so same-second saves never overwrite; failure deletes the partial folder and rethrows (samples kept for retry). AudioExportTest 3. Open: IO read loop vs Main Stop race on `_recording`/`_recordingReady` (stale state or false limit text); firmware `mic.pcm` has no sequence/drop count (needs additive protocol change). Batch 2: both open items addressed in software. `RecordingBuffer.append` returns APPENDED/LIMIT_REACHED/INACTIVE/REJECTED and publishes its own state under its lock; VM `_recording`/`_recordingReady` removed, limit text only from a real LIMIT_REACHED (latch-ordered two-thread test). Firmware `mic.pcm` adds `seq` (restarts per SUB) and, when the I2S driver accepts the overflow callback, cumulative `dropped` samples; app reports "gaps detected: N (M samples dropped on device)" or "unknown" for old firmware, no filler inserted. RecordingBufferTest +10. Hardware: overflow callback firing and sample-unit conversion (assumes 16-bit mono channel data) on the T-Embed mic. |
| W04 | Later | HOLD | Codec follow-up only if first worker reviews reveal a concrete need. |

Current batch launch is explicitly authorized (see above and workers.md). Four
prepared packets are running locally under the user-requested isolation exception;
other packets do not become accepted or launchable merely by being prepared.

Coordinator review notes for the local batch:
- S06-S10a's proposed `UsbLinkOwner` states are useful design input, not an implemented
  contract. Do not apply a blanket `isFlashing()` rejection in `flashFirmware`: the
  legitimate handoff already enters with phase `download`; use operation identity.
  The double-open/same-port claim and USB library close semantics need additional
  evidence. An already-cancelled launch skips the body; cancellation after the body
  starts is different and must not be described as skipping `finally`.
- S11a: keep missing driver/caller evidence explicit. A full 64-symbol receive alone
  cannot establish truncation; metadata must distinguish completeness unknown from
  proved loss. Spectrum kHz quantization, numeric-range validation, allocation/drop
  accounting and selected-module/probe consistency remain review follow-ups. No
  changes to transmit/replay operations were made.
- Audit workers stopped after the launcher's output Write permission was denied.
  Their delivered report text was recovered by the coordinator from the tool logs,
  reviewed, and retained in the ignored packet outputs. This was a launcher grant
  error, not a task refusal; the launcher now also grants Edit for output paths.

## Extension architecture — incremental execution

Design is in [ARCHITECTURE.md](../../../ARCHITECTURE.md#proposed-extension-architecture).
These tasks reuse existing features; new capabilities remain behind stabilization.
No plugin SDK, package manager or runtime isolation has been implemented.

| ID | Priority | State | Acceptance / dependency |
|---|---|---|---|
| E01 | P0 | HOLD | After W06 review, prepare W07 to extract session ownership and small typed feature-service interfaces with fake transport; coordinate with S06/S10 rather than duplicate the refactor. |
| E02 | P0 | HOLD | W08/W09: pilot on an existing phone-only analyzer and device-backed reader; per-operation availability, bounded Stop, missing-device and disconnect tests. Inspect capture vs offline-analysis gating. Depends on E01. |
| E03 | P0 | PREPARED | W06 covers catalog/IDs first; full feature registration remains pending.  Stable namespaced IDs, descriptor validation and deterministic app/firmware registration; reject collisions; no central UI/ViewModel/dispatcher edits for an example feature beyond registration. Full lifecycle integration depends on E02; metadata extraction W06 may precede it. |
| E04 | P2 | HOLD | Source contribution template + scoped harness/CI and board matrix; document compatible pinned BruceJS APIs. Publish SDK only after both pilots pass. |
| E05 | P2 | HOLD | Bounded data-pack import/export, recipe compatibility and provenance; catalog only once local workflow works. Runtime native loading is a separate design decision. |

## W11o lifecycle review boundaries

- Migrated device services: cleanup inventory lives in `WardriveViewModel.deviceLifecycle`;
  Stop may send an existing firmware stop command, disconnect only abandons local
  ownership. Background states and pending parallel waits are cleared. Firmware
  blocking commands can still outlive cancellation.
- Remaining `dispatchCmd` callers: console, screen/LED/brightness controls, direct
  capture/transmit, legacy IR learning and PCM control frames. `runWithResult` also
  serves file metadata, legacy NFC/RF and diagnostics. These now share cancellable,
  session-bound callback ownership; extraction of their domain logic is not claimed.
- SUB streams now use the S03 StreamSubscriptions owner; Stop all also retries OFF
  for missing/failed ACKs and disconnect retains best-effort cleanup obligations.
  Timeline/phone-mic jobs stop; W11q now owns FM/SSTV encoding, sources and finalizers.
  Remote readers are cancelled on disconnect. Radio/stream shutdown still needs
  physical acceptance (S05), including blocking Android source/codec behavior.
- File payload transfers now use the W11p owner and verified session adapter;
  queued requests cannot change connections and portal Stop cancels the upload.
  Unique local copies prevent same-name overwrite, and provider writes serialize.
  Android provider writes cannot be rolled back. S04 now owns transactional firmware
  upload safety, with explicit FAT recovery and physical-durability limits.
  Do not describe all ViewModel work as lifecycle-complete yet.

## Firmware changes proposed, ordered

1. **Transactional uploads — software complete (S04):** staged size/CRC verification,
   canonical paths, collision handling and recovery tests are implemented. LittleFS
   uses rename; FAT uses journal/backup/promotion with rollback. A double rename
   failure retains the previous backup and returns an error. Power-loss/media/card
   swap acceptance remains S05. Legacy direct writes remain non-transactional.
2. **Resource ownership and Stop:** one explicit owner for shared radios/audio,
   bounded cancellation latency, release on disconnect, terminal result for every
   started operation. First map current command handlers; avoid a wholesale rewrite.
   Mifare-specific gap: `PN532::mifareKeyCheck` returns true with partial hits when
   tag reselection fails; the ACK has no completeness flag or tag UID. Add explicit
   complete/partial results and tag identity in a versioned contract before claiming
   exhaustive scans or guaranteeing that batches belong to the same card. Add
   removal/swap fixtures and a bounded firmware Stop path; the app cannot infer
   these facts from the current reply.
   **2026-11-04 batch 2:** key-check reply now carries `v:1, uid, sak, sectors,
   tested_sectors, keys, auth_attempts, complete, stop` (CAPS
   `features.mifare_keycheck_report`); loop moved to host-testable
   `mifare_keycheck_core` (28 host checks). App stops on partial batches and on a UID
   change between batches; old firmware is labeled "completeness unknown". Card
   removal/swap needs S05.
   **Batch 3:** in-flight batch stop: core polls `shouldStop()` per auth attempt, reply
   `stop:"cancelled"` with hits so far, CAPS `features.mifare_keycheck_stop`; app sends
   the direct stop frame, waits <= 15 s, shows "Stopped (partial)" (650 s ceiling kept,
   per-auth timing uncharacterized). `companionPollStop()` (all blocking ops: responder,
   karma, brucegotchi, key-check) now answers PING with PONG and other CMDs with
   ERR 409 "device busy" instead of silently dropping them; stop/*_stop still ends the op.
   **FM PCM (batch 3):** `fm_audio_data` ACKs carry `samples`, `accepted`, `free`, `lost`;
   full ring accepts what fits (ok:true, accepted < samples), never silent discard; app
   resends the remainder, paces on `free`, fails after 2 s without progress, shows device
   loss; old firmware labeled "Delivery unknown". Host-tested `fm_pcm_ring.h`. Needs a
   Si4713 board (reference T-Embed has none); `accepted` proves ring delivery, not air.
   FM PCM gap found during W11q: `runFmAudioData` can fill its ring, discard the
   remainder and still return `ok:true`. Design accepted-sample counts/flow control
   and overflow fixtures before claiming lossless transmission; the current app's
   ACK waits and pacing cannot prove the physical output or detect partial drops.
3. **Capability evidence — software complete (batch 3):** CAPS `d.evidence` per module
   {level none|compiled|configured|probed|initialized, probe present|absent|busy, detail,
   conflict[]}; probes once per boot at companion entry, read-only where possible
   (CC1101 PARTNUM/VERSION, W5500 VERSIONR, NRF24 SETUP_AW skipped on a W5500-claimed CS,
   PN532 firmware version, FM I2C ACK only, SD only if mounted, LoRa begin+sleep instead
   of the previous begin-into-RX, RC522 antenna switched off after begin). App shows a
   "Hardware evidence" card; only `probe:"absent"` clears a caps flag. T-Embed static
   conflicts: GPIO44 NRF24 CS/W5500 CS, GPIO43 NRF24 CE/W5500 INT, GPIO40 display reset/
   speaker WCLK, GPIO39 mic clock/speaker MCLK. "Physically tested" stays an S05 notion.
4. **Release provenance — software complete (2026-11-04):** CAPS `build`
   {commit, dirty, id, time, time_src, env} generated by `pre_build_provenance.py`
   (SOURCE_DATE_EPOCH or commit time, never wall clock; "unknown" without Git);
   app shows it on the device card and in log exports/session reports. Limit:
   identifies source commit, not a binary hash. Batch 3: `pre_build_current_year.py`
   uses SOURCE_DATE_EPOCH / commit time (wall clock only without Git), year floored at
   2025 (GPS date window); CLI `info` prints the CAPS commit (+dirty). Two clean T-Embed
   builds with fixed SOURCE_DATE_EPOCH gave identical firmware.bin/elf. Batch 4 (SDE):
   the pre-script exports SOURCE_DATE_EPOCH from the commit time when unset (os.environ and
   env["ENV"]; GCC 14.2 honours it for `__DATE__/__TIME__`, used by RadioLib BuildOpt.h and two
   Arduino-core files; no `__TIMESTAMP__` anywhere) and adds `-ffile-prefix-map` (probed once)
   for project/core/packages/libdeps. Two clean T-Embed builds from two different checkout
   paths, no manual env, gave identical firmware.bin (9e890aaf...) and elf; 39 absolute
   package paths and the checkout path are gone from bin/elf. Without Git: wall clock as
   before. Limits: same toolchain/libdeps versions required; tested on one machine only.
5. **Recovery diagnostics — software complete (batch 4):** `diag` CMD (CAPS
   `features.diag`), on request only, no telemetry: reset reason (stable names, static
   assert against the IDF enum), uptime, heap/PSRAM, saturating uint32 link counters since
   boot (sessions, rx/tx frames, rx rejects zero_len/oversize/alloc_fail/stale/bad_json,
   ERR by code bucket, busy 409) and existing FM `lost` / mic `dropped` read in place.
   Host-tested `diag_core.h`. App: Diagnostics card with Refresh, app-side link counters,
   both in log export header and session report. Limits: a corrupt length prefix can raise
   several reject counters (no resync); increments are not atomic across tasks (rare lost
   counts). Real reset reasons/heap under load need S05.

These are proposals, not implemented firmware behavior. Review the existing
handlers and resource conflicts before turning each item into a worker packet.

## Target: 9/10 software quality (excluding field tests)

Hardware readiness is a separate score. The software score should rise only when
reviewable evidence closes these gaps, not when more features are added.

Close S01–S04 and S06–S11 with reproducible software evidence: clean CI builds,
artifact identity and emulator upgrade checks, deterministic lifecycle failures,
versioned protocol replay/generative tests and failure-safe storage. S05 remains a
separate hardware acceptance gate. Complete the detailed operation checklist;
counts alone cannot establish coverage.

A software 9 also requires a reviewed release with no known critical/high-impact
correctness defects, documented remaining limitations and contributor instructions
that work from a fresh environment. No arbitrary test-count threshold: every test
must protect a meaningful behavior. Field RF/audio/USB performance and electrical
compatibility remain unscored until measured separately.

## Additional retained review points

- Review remaining compiler/lint warnings individually; do not suppress categories.
  Batch 3: Kotlin warnings 5 -> 0 (AutoMirrored icons), lint informational 39 -> 0
  (primitive state holders), ObsoleteSdkInt fixed (mipmap-anydpi), VectorPath justified
  per path, bcpkix TrustAllX509TrustManager ignored for that jar only (app/lint.xml),
  MonochromeLauncherIcon fixed in batch 4 (monochrome layer = outer flame path, inner flame
  as negative space, no suppression). Batch 5 (EMU2): compileSdk/targetSdk 35 with
  enableEdgeToEdge + inset padding (bottom nav above the IME, adjustResize), checked on the
  API 36 emulator (15 screens before/after, gesture and 3-button nav, landscape IME);
  core-ktx 1.16.0, activity-compose 1.10.1, lifecycle 2.8.7, androidx.test 1.7.0/ext 1.3.0,
  Room 2.7.2 (schema 3.json byte-identical, debug upgrade kept rows). Deferred: compose-bom
  (newer needs AGP 9.1; 2025.07 renders blank Paparazzi 1.3.5 snapshots), play-services-
  location 21.4 (Kotlin 2.3 metadata), Room 2.8 (needs newer KSP/Kotlin), SDK 36 (AGP 8.9.1).
  Lint 0 errors / 10 warnings. Physical Android 15+ cutout device and API 26-34 device pending.
- Concurrent update/install and download cancellation: covered by batch 3 (see S01).
- Root dependencies are pinned; board-specific overrides and the full environment
  matrix still need coverage. Current builds establish only the documented boards.
  Batch 4 (MATRIX, compile+link only): 12 envs build with no failures (T-Embed, Cardputer,
  core4mb LITE, CYD, LAUNCHER_CYD LITE, nesso-n1 C6, esp32-c5-tft, s3-devkitc, t-lora-pager,
  m5stack-cplus2 M5STICK/PN532-I2C path, smoochiee-board INMP441 mic, ES3C28P SD_MMC + speaker
  without FM); merged batch 4 rebuilt on T-Embed, Cardputer, core4mb, LAUNCHER_CYD, nesso-n1.
  App-partition headroom: LAUNCHER_CYD 2,515,159/2,555,904 B (~40 KB free; other LAUNCHER_*
  share custom_4Mb.csv, not built), T-Embed/t-lora-pager/ES3C28P/CYD at 93-94%. Next companion
  growth must be measured against LAUNCHER_* first. Two companion warnings fixed (LITE unused
  `copies`, -Wclass-memaccess memset in esp-now broadcast).
  Batch 5 (FLASHBUDGET): `tools/check_flash_budget.py` resolves each env's partition table
  (extends, [env], extra_configs, board default) and compares firmware.bin to the smallest
  app slot; run by build_firmware.py (CI, release.sh) after every build; warn > 92%, fail
  > 98%, LAUNCHER_* temporarily warn 95% / fail 99% (LAUNCHER_CYD is 98.43%). Companion on
  LAUNCHER_CYD = 87,728 B (code 64.6 KB, rodata 23 KB of which ~13.4 KB unwind tables).
  Measured option not applied: build src/companion with -fno-exceptions/no unwind tables
  (-7.4 KB, no catch in companion). Other options (LITE compile-outs of keycheck/NRF24/IR/
  diag/GATT, short error codes, NimBLE NDEBUG) need a product decision.
- Fresh-checkout library patch ordering (batch 5, CIFIX + FRESH): owner-less registry specs
  (`ESP8266Audio@2.4.1`, NTPClient, ESP32Time; also LibSSH-ESP32 and Adafruit Si4713 install
  late) are skipped by `pio pkg install` and installed by the LDF after pre-scripts, so the
  first build from a clean checkout compiled UNPATCHED libhelix-aac (CI noise test failed).
  Fixed twice over: patch_library_conflicts.py re-applies the table from a build middleware
  after dependency installation and before compilation, and fails the build when an expected
  pattern neither matches nor is already applied; build_firmware.py pre-installs them via
  `pio project metadata`. First-build evidence: T-Embed, core4mb, Cardputer noise test PASS.
  Release 0.1.4 firmware was built from patched cached sources (timestamps checked). Option
  not applied: owner prefixes in [env] and [env_light] lib_deps.
- Contributor setup checked from a fresh clone (batch 5, FRESH): ANDROID_HOME/local.properties,
  pyserial, JDK 17-23 (not 24+), Python 3.10-3.14 for PlatformIO, PlatformIO 6.2.0 venv install
  and UBSan host compiler were undocumented; CONTRIBUTING.md now lists them with copy-paste
  macOS/Linux steps. Not verified fresh: PlatformIO core/toolchain, SDK install, Linux.
- S05 helper (batch 5, HWKIT): `tools/hw_acceptance.py` runs passive checks against a connected
  board (CAPS/provenance/evidence, ping latency, diag counters, malformed frame, verified file
  read and scratch upload/remove, receive-only streams wifi.probe/wifi.sta/subghz.raw) behind a
  command allowlist; wifi.ap and ble.scan are active scans and are refused. Mock-tested only.
- Test concurrent updates/install requests and cancellation during downloads.
- Validate capability probes with pin/bus conflicts and firmware Stop/resource
  ownership; distinguish compiled, detected, initialized and tested.
- Fresh signed APK and final device pair are required before new demo media.
- Keep website release choices disabled when assets are absent/private; preserve
  the historical hosted manifest/binary pair until an intentional publication.

Capture follow-up: legacy CLI directory listings have no completeness token and
handshake ACKs expose no per-run path/hash. Current importer rejects detectable
truncation and unchanged files; content change is not proof of capture timestamp.
Batch 4 (CAPDESC): CAPS `features.capture_descriptor` (non-LITE); wifi_handshake/wifi_pmkid
terminal ACK carries `d.desc` {v:1, path, size, crc32 (same CRC as fs_read), frames
(measured pcap records this run), handshake, complete, stop complete|timeout|aborted|
no_capture|not_written, appended/prior_size, time_src:"device_uptime_ms"}. The sniffer
writer records the file it actually created or grew for the target AP only (handshake files
accumulate across runs; a stale pre-existing file is never reported as this run's). App
imports exactly that path, checks the expected per-BSSID basename and verifies size/CRC;
`complete:false` imports nothing; old firmware keeps the listing diff, labeled. No
wall-clock capture time is claimed. Needs S05 with live APs.
