# Work queue — single operational ledger

Updated 2026-10-30. Target: `VERSION` (currently 0.1.2). 0.1.2 is committed, tagged and
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

**Migration tasks run one packet at a time on explicit user request; do not chain a
next worker automatically.** This supersedes the earlier execution order for development work.
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
| W11 | P0 | IN_PROGRESS | Remaining families in batches. Batch 1 DONE 2026-10-30 (T11a): `session/DeviceOperation` (single-owner status/busy/stop/supersede, 10 s default) derived from IButtonService, which now delegates to it (IButtonServiceTest unchanged, 10/10); clock (`device/ClockService`) and Wi-Fi MAC (`wifi/WifiMacService`) migrated and wired (timeouts 650 s -> 10 s; local epoch >= 1e9 and MAC-format checks mirror firmware). Evidence: `:app:testDebugUnitTest` 319/319, `lintDebug` no new findings. 61 `dispatchCmd` callers and 10 burst loops remain. Batch 2 DONE 2026-10-30 (T11b): `device/ScriptService` (25 s; blank/>6000-byte source rejected locally; clear keeps a running script's output), `device/DeviceAppearanceService` (theme/LED/brightness/effect/save share one owner, latest wins; color callbacks only for the owning request), `wifi/WifiJoinService` (join 30 s, others 10 s), `device/EspNowService`; `DeviceOperation` gained a per-call timeout and an atomic `clearStatus()` (coordinator replaced the worker's flag-based clear). DTMF no longer writes its unreachable not-connected text into the theme status. Evidence: `:app:testDebugUnitTest` 333/333, `lintDebug` no new findings. 51 `dispatchCmd` callers and 10 burst loops remain. Batch 3 DONE 2026-10-30 (T11c): `audio/SpeakerService` (volume/tone 15 s/file 600 s/song; tone 1..20000 Hz checked locally; song state guarded so a superseded start never clears a newer song; DTMF and speaker_stop sent alongside without taking over a pending tone/file wait - coordinator fix after review) and `audio/MicRecordService` (timeout min(secs,30)+10 s); `DeviceOperation` results may return null to keep the status. Evidence: `:app:testDebugUnitTest` 347/347, `lintDebug` no new findings. 44 `dispatchCmd` callers remain. Batch 4 launched 2026-10-30 as two parallel workers on disjoint files: T11d BLE (HID, BadBLE, media) and T11e IR. T11e DONE: `ir/IrReceiveService` (receive-once and remote-key capture on separate owners, 20 s; result/callback only for the owning request) and `ir/TvBGoneService` (75 s), wired; coordinator added `DeviceOperation.show()` (replacing the worker's connection-check workaround for markSaved and the remote "Sent:" note). BUG FIXED (pre-existing): the firmware `ir_rx` ACK is a one-line summary ("NEC addr=04 00 00 00 cmd=08 00 00 00" / "raw: <durations>"), which IrParser never decoded, so IR receive never produced a result and remote-key capture always failed on hardware; new `IrParser.parseRxSummary` fallback (IrRxSummaryTest 3). Suite 365/365, lint clean. Raw summaries carry no frequency (38 kHz assumed) - verify on hardware (S05). T11d DONE: `ble/BleHidService` (begin/end on one owner, end never leaves "Starting..."; status/key/type alongside; 10 s - coordinator lowered from 650 s since HID ACKs at once), `ble/BadBleService` and `ble/BleMediaService` (650 s kept: script length and connect cascade have no firmware bound), wired. Suite 377/377, lint clean. 34 `dispatchCmd` callers and 10 burst loops remain. Batch 5 launched 2026-10-30 in parallel: T11f LoRa+Ethernet (on `startJson`) and T11g NFC advanced tiles. T11f DONE: `lora/LoraService` (config 10 s - coordinator lowered from 650 s; send 650 s since airtime at narrow BW/SF12 can be minutes; recv secs+10 s; packet callback and receiving flag only for the owning receive) and `net/EthernetService` (status 16 s, config 18 s with one chained refresh, starve/flood secs+14 s; bounds from network.cpp), wired through the new VM `jsonRunner` (`sendAwaitJson`, command log kept). Suite 390/390, lint clean. T11g DONE: `nfc/NfcToolsService` with four independent lines (NDEF push 75 s; EMV read and UID write 650 s, no firmware bound; advanced tiles shared: srix/rfid125 secs(1..30)+15 s, amiibo/nested/mfkey32 10 s - coordinator lowered from 650 s since they ACK at once with fixed "not supported headless" texts), wired. The worker's first run hung on never-completed test gates; fixed, and its tests now carry 5 s JUnit timeouts. Suite 401/401, lint clean. 25 `dispatchCmd` callers and 10 burst loops remain. BUG FIXED 2026-10-30 (coordinator): `stopAllBurstLoops` (used by the Cmds "Stop all" button, the link watchdog and disconnect) missed the Sub-GHz jammer, IR jammer, ARP spoof and iBeacon loops, so "Stop all" left them running; added (not hardware-verified). Batch 6 DONE: T11h `session/BurstLoop`+`BurstLoopRegistry` (coordinator added a shared status line for NRF24 scan/sniff and an owner-only `onEnd` for the deauth target); all 11 burst loops migrated by the coordinator, `launchBurstLoop` removed, `stopAllBurstLoops` = `registry.stopAll` (loops are lazy and register when built, so none can be missed). T11i `session/LongOperation` + Responder/Karma/Brucegotchi services (timeouts secs clamped 5..600 + 30 s per wifi.cpp; `requestStop` keeps waiting for the final ACK); coordinator added a direct, unlogged stop channel (the firmware polls but never ACKs the stop frame), wired, and replaced the shared `stopLongOp` with dedicated Responder/Karma Stop buttons. Behavior change: a non-silent Stop no longer overwrites a rejection text with "Stopped.". Suite 424/424, lint clean. 22 `dispatchCmd` callers remain (Wi-Fi capture/crack, Evil Portal, GATT, console/RF/PCM streams, jam_detect/WIDS loops). JSON-reply support added by the coordinator meanwhile (`session/JsonCommandRunner`, `DeviceOperation.startJson`, same ownership/texts; test added, suite 348/348); LoRa/Ethernet can now be migrated. Firmware follow-up: blocking `speaker_play` stalls the companion link for the whole file and cannot be stopped. Burst loops need their own design. |
| D01 | P0 | DONE | Consolidated 13 redundant documents into skill/backlog, TESTS, CHANGELOG and ROADMAP; skill validation, local documentation links and seven website tests passed. |
| S01 | P0 | OPEN | One release snapshot for metadata and board assets. `fetchLatest` / `fetchBoards` currently call latest independently. Test two changing API responses and stale selection after failed refresh. |
| S02 | P0 | BLOCKED | Clean-checkout signed Android build, KSP/lint, artifact identity/signature checks and emulator upgrade preserving data; previous environment denied cache/socket operations. Run in a permitted build environment. |
| S03 | P0 | OPEN | Complete command/stream lifecycle and capability matrix using the existing app-controls checklist; include cancellation, timeouts, invalid input and cleanup. |
| S04 | P0 | OPEN | Atomic firmware uploads with length/CRC and failure-injected fake filesystem tests; preserve previous destination. |
| S05 | P0 | BLOCKED | Final app/device acceptance and flash recovery; phone, board and appropriate peripherals required. |
| S06 | P1 | OPEN | Extract connection/session, then transfers/update ownership from ViewModel with lifecycle regressions. |
| S07 | P1 | BLOCKED | Obtain real Room v1/v2 schemas and fixtures before supporting historical migrations. |
| S08 | P1 | OPEN | Long-session memory/queue/drop/Stop tests with deterministic virtual transport; physical stress remains separate. |
| S09 | P1 | OPEN | Verify old Scottie external-sync warning against current DSP code and independent known-answer fixtures. Do not treat app self-roundtrip as interoperability. |
| S10 | P1 | OPEN | Check service/session ownership when Activity is destroyed or app swiped away; define supported recovery instead of assuming a foreground service owns the transport. |
| S11 | P1 | OPEN | Reconcile historical menu inventory with code, limits and operations; mirror/console reachability is not native companion parity. Audit RF capture truncation and optional module limitations. |
| W02 | P1 | PREPARED | HTTP parser review packet; coordinator's signed-length regression already fixed. |
| W03 | P1 | PREPARED | Release catalog review packet; exact paths and ambiguity already corrected. |
| W01 | P1 | PREPARED | Recording buffer review packet. |
| W04 | Later | HOLD | Codec follow-up only if first worker reviews reveal a concrete need. |

Worker preparation is not launch authorization. No packets/runtime isolation have
been provisioned; export briefs from workers.md only after the user requests launch.

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

## Firmware changes proposed, ordered

1. **Transactional uploads:** write to a bounded temporary file, verify length/CRC,
   then promote using filesystem-supported rename semantics. Specify collision,
   power-loss and cleanup behavior; preserve the previous destination on failure.
   Existing writes can leave a partial destination. This is a correctness priority.
2. **Resource ownership and Stop:** one explicit owner for shared radios/audio,
   bounded cancellation latency, release on disconnect, terminal result for every
   started operation. First map current command handlers; avoid a wholesale rewrite.
3. **Capability evidence:** distinguish compiled, probed, initialized and physically
   tested. Report per-operation limitations and pin/bus conflicts. Build this on
   current CAPS; do not label a GPIO definition as an electronic probe.
4. **Release provenance:** report firmware commit/build identifier and protocol
   capabilities in CAPS. Tie crash reports to the exact artifact, keeping Bruce
   credits and third-party licenses.
5. **Recovery diagnostics:** bounded reset-reason/heap/drop counters and a local
   support export. No automatic telemetry. Test counters before adding dashboard UI.

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
- Root dependencies are pinned; board-specific overrides and the full environment
  matrix still need coverage. Current builds establish only the documented boards.
- Test concurrent updates/install requests and cancellation during downloads.
- Validate capability probes with pin/bus conflicts and firmware Stop/resource
  ownership; distinguish compiled, detected, initialized and tested.
- Fresh signed APK and final device pair are required before new demo media.
- Keep website release choices disabled when assets are absent/private; preserve
  the historical hosted manifest/binary pair until an intentional publication.
