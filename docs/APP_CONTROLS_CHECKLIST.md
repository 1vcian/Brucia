# Brucia Android app - interactive controls test checklist

This is a **living hardware-test checklist**. It lists every interactive control (buttons,
toggles, sliders, dropdowns, tappable tiles/rows, text fields with an action, etc.) in the
Brucia Android companion app, grouped by home category and screen in navigation order.

How to use it:

- Mark `[x]` in the **Tested** column once the control has been exercised against real
  hardware (a connected device, or - for offline tools - verified on the phone).
- Put anything useful in **Notes**: firmware/board tested on, bugs found, "needs re-test",
  dates, tester initials, etc.
- Controls were enumerated directly from the source (`MainActivity.kt` plus the standalone
  `*Screen*.kt` files). When the code adds/removes a control, update this file to match.
- Many screens repeat the same "Connect over USB" / "Reconnect over USB" button shown only
  while disconnected; it is listed once per screen.

Source files: `android/app/src/main/java/app/brucia/MainActivity.kt` and the standalone screen
files (`AudioScreens.kt`, `MitmUi.kt`, `NetworkScanScreen.kt`, `WidsScreen.kt`,
`RfFingerprintScreen.kt`, `RfTimelineScreen.kt`, `EvilPortalScreen.kt`,
`ChannelAnalyzerScreen.kt`, `RfMap3dScreen.kt`, `BugSweepScreen.kt`, `PcapDissectScreen.kt`,
`GeoSignalsScreen.kt`).


## Cross-feature integration acceptance — 2026-11-06

The current 50-tool catalog has a route for every entry. The following cases supplement
the per-control inventory with integration failures found in the current dirty tree
above 0.1.6 (`354a32db`). **No physical checkbox was completed in this review.**
HOST means actual service code with simulated replies; CODE means a source trace,
not an on-device reproduction. Full details/status belong to backlog F25-F31 and TESTS.md.
Software fixes for F25-F31 landed 2026-11-06 with host/source regressions; every physical
check below is still to perform.

| ID | Reproduction / evidence | Regression and physical acceptance to perform |
|---|---|---|
| F25 | **CODE:** Start Remote, navigate Home -> Device info/Files without pressing Remote Stop. Raw mirror still owns the reader while Connected/session allow framed operations. | Test navigation, Stop all, rapid Start/Stop and rotation using a fake transport first: one reader, no framed write in raw mode, no stale wait. On phone, leave Remote and run benign info/list successfully after a completed companion handshake. Rotation may retain Remote; actual leave must restore companion or visibly gate incompatible operations. |
| F26 | **HOST component + CODE integration:** LoRa global hook only cancels locally. Another service's `_stop` can be consumed by LoRa's generic stop poll without ACK, leaving that service's stop unexecuted. | Test receive + simulated active background service through the lifecycle inventory, both stop-frame orders, missed ACK and late result. Require termination of both operations with honest status and bounded waits. Then verify receive-only Stop on an equipped board; never infer that global Stop always leaves LoRa running. Disconnect cleanup must not send to a replacement device. |
| F27 | **HOST:** BLE HID end with ERR/timeout reports off; portal stop failure clears active. | Inject stop rejection, lost ACK, exception, success, delayed old ACK and disconnect. Failure must not claim inactive. Verify successful retry/state refresh restores an accurate state. Use fake drivers for portal lifecycle and a benign HID fixture for physical cessation; no payload/rate changes. |
| F28 | **CODE:** Start Wardrive, visit RF Fingerprint / RF Timeline / Evil Portal without starting a separate target scan, then Back: each can stop the prior Wi-Fi scan. | Test prior scan ON and OFF, pending SUB ON, two rotations, Stop all, reconnect and screen exit. Borrowed scans remain active; screen-owned scans stop once; stale callbacks cannot resurrect a stopped scan. Include BLE ownership as a control. |
| F29 | **CODE:** Open Channels disconnected -> connect without leaving; also disconnect/reconnect while staying there. Its Unit-keyed effect does not start again. | Expect one appropriate SUB after CAPS, updated channel samples and no duplicate SUB on rotation. Previously running Wardrive must remain owned by its original user after leaving Channels. |
| F30 | **HOST:** Files receives ERR/timeout/SD read failure and shows empty results with no error. | Service regression for each failure, successful empty directory, ordinary listing, truncated listing and retry. Phone check: absent/unreadable storage must say why; no error string becomes a filename and no failed listing is called empty success. |
| F31 | **CODE, extends DT07:** unguarded writes in Wardrive CSV/KML/GPX, NDEF, hc22000, RF CSV/.sub, station/probe exports. | Fault-inject file open/write/close failure and unavailable external directory. No uncaught exception, success toast or share of a partial file; preserve the source for retry. Then export each format on phone and reopen with an independent reader. |

Keep these family checks in the acceptance run; they are **NOT RUN here**, not extra
claims that each family is broken. Use controlled fixtures; unsupported/missing modules
must produce a bounded explanation. A start ACK alone is never proof of the physical effect.

| Catalog family (tools) | Essential remaining functional check |
|---|---|
| Wi-Fi (11) | Known AP/traffic fixture reaches scan, station/probe/monitor views; saved capture opens independently. Join failure/retry and special-character SSIDs; Channels reconnect and shared scan ownership; stop/error paths tested with fakes before any active network test. |
| Sub-GHz (1) | Passive known-signal capture, completeness metadata, save/reopen and receive-mode conflicts. Reject malformed inputs with no reboot; F24 leave/Remote cancellation and F21 legacy saved-item compatibility remain separate checks. |
| Radio: FM/LoRa (2) | Missing module vs actual initialization; LoRa receive and both Stops; FM/speaker/mic exclusive ownership and actual audio on equipped hardware. |
| 2.4 GHz: NRF24/ESP-NOW (2) | Missing-module errors and receive cancellation; one radio owner, subsequent ordinary Wi-Fi still usable. NRF24 stop gap remains open. |
| NFC (1) | Read-only action leaves a known tag unchanged; destructive actions require their confirmation; remove tag mid-operation, then retry. Unsupported headless actions must remain explicit. |
| BLE & IR (5) | Known BLE peripheral and IR remote; scan/HID/GATT transitions and stop failures; absent pins/headless rejection; save/reopen a learned IR button. BadUSB unsupported companion path must return promptly. |
| Spam (3) | Stub driver init/start/stop failures, leave/Stop/disconnect and reject conflicting ownership. For BLE visibility, separate observed packets from OS UI response; the Mac card is not verified by an advertising ACK. NFC cancel/length limits and Wi-Fi loop cleanup remain scoped checks. |
| Intercept (6) | Local fixture for proxy/scan with Wi-Fi + mobile/VPN and unavailable LAN; actual response forwarding; stop/restart/rotation; fingerprint/timeline scan ownership. Negative/cleanup paths for other network tools use fakes or an isolated bench. |
| Device (6) | Remote round trip, Files errors and transfer hashes, USB reconnect, live diagnostics/settings; update cancel/permission/integrity failure and recovery; optional Ethernet absence/link state. |
| Audio (4) | Known mic/tone/image input, independent output/decoder check, recording retention after failed save; mic/speaker/FM ownership, rotate/leave/disconnect and restart. |
| Tools (9) | Reports preserve authorized export policy; harmless script timeout followed by a fresh script; macro Stop; map/geo persistence; Bug Sweep ownership; PCAP malformed/bounded import; clock/iButton presence; Unicode QR round trip and other offline utilities. |

Cross-cutting: F23 needs a soak with **measured nonzero events** from the permitted
receive-only streams and zero framing findings/tx_torn. Record event counts and rates,
not only requested subscriptions. Add an isolated USB backpressure/recovery test that
keeps USB open and has a pending command; a clean quiet soak does not exercise that fault.
For each supported benign operation, run start -> rotate -> leave -> start -> disconnect
-> reconnect, followed by a known-good read. Retain versions, fixture, expected/observed
effect, errors, timing and PASS/FAIL/BLOCKED/NOT RUN separately.

### Follow-up regressions — 2026-11-06

Commit `d2e24911` closes the original Stop-all timeout branch and adds the awaited
Remote exit. Independent review still requires these two regression cases:

- **F32 LoRa teardown:** hold the coroutine dispatcher before the stop sender runs;
  request stop, disconnect, select a replacement session, then resume. No old stop
  reaches the replacement; a new receive is not permanently fenced. Repeat with
  explicit Stop receive separately, which must still send its intended stop.
- **F34 Remote failure cleanup:** reject or lose companion_exit ACK, retry several
  times, then Stop/disconnect. Assert one watchdog for a successful Remote session
  and none after a failed attempt or teardown; no old job can re-arm a later mirror.

Both host regressions now exist and pass (2026-11-07: `LoraServiceTest` held-link cases,
`RemoteRunTest`). The d2e24911 firmware was reflashed successfully (hash verified); the
phone cases above remain NOT RUN and gate hardware acceptance.

Review of integration `1db4b4d8` / release `33494d91`: the initial fixes are present,
but the following gaps remain. These checks use fake transports/output drivers first;
none was marked physically accepted by this review.

| Item | Required regression / acceptance |
|---|---|
| F26: Stop all timeout | Keep a blocking operation active after a failed/delayed stop or missing ACK, advance beyond 8 s. Remaining service stops must not be dispatched as if the device were idle; preserve a visible unknown/failure state. Freeze local macro/burst producers immediately. Test successful settlement, timeout and disconnect separately. |
| F32: delayed IR Stop | Start A, hold its stop before the write, let A complete, request B, then release the old sender. No old stop may affect B. Repeat with two Stop taps, teardown and a replacement session; retain the no-new-burst guarantee without claiming instantaneous physical cessation. |
| F33: invalid explicit duration | Stub IR initialization/output. Test nonnumeric input, trailing junk, negative and overflow; expect a visible error and zero driver calls. Test omitted argument separately for the documented default. |
| F34: Remote handover | Keep IR or a receive-only NRF24 window draining, then request Remote. Delay/reject `companion_exit`: no raw mirror starts before a successful handover; framed reader remains available for the terminal result. Timeout must recover visibly, and stop frames must not be gated away prematurely. |
| F21/A3: all audio owners | Simulate async song -> FM and FM -> song/RTTTL/DTMF, plus existing tone/file/PCM/mic cases. Old output must be released before the new one acquires I2S. Then repeat on an equipped board; no FM hardware is certified by a build. |

**Status 2026-11-06 (fixed in the follow-up commit):** F26, F32, F33, F34 and F21/A3 are
resolved in software with host/source regressions (see backlog and TESTS.md). Still owed on
hardware: F32/F34 full-VM behaviour, the IR active-transmission + stop-latency check, and the FM
owner on an Si4713 board. The rows above remain as the on-device acceptance to perform.

IR UI acceptance must distinguish “request sent” from device success, describe its
bounded window, and avoid claiming all devices/tables were covered without evidence.
An idle-stop ACK neither exercises an active transmission nor measures stop latency.

## Functional acceptance priorities — 2026-11-05

This pass follows two owner reports: De Bruijn reboots the T-Embed, and the
Apple/iOS/Mac BLE card produces nothing visible on the Mac. It supplements the
per-control inventory below with end-to-end outcomes and failure recovery.
The batch 14 link suite passing 12/12 does **not** cover these operations.
No new physical check is marked passed by this audit.

Evidence labels: **HOST** = reproduced using production code with hardware stubs;
**CODE** = source finding, device consequence unverified; **REPORT** = owner's
observation; **PENDING** = acceptance test not run. A button changing color, a
command being sent, or an ACK alone is not proof that the intended operation worked.

### First: crashes, hangs and misleading success

| ID / priority | Function and current evidence | Required test and acceptance |
|---|---|---|
| F01 / P0 | **De Bruijn → legacy RF CLI. HOST + REPORT.** `hexKey(12)` generates 1,024 hex characters (4,096 bits), but `rfTxCallback` calls `std::stoull` for a single 64-bit integer. The actual callback throws `out_of_range` for n=8/10/12 before RF initialization. It fixes the transmitted width at 24 even for the smaller inputs it accepts. | Host-only rejection regression first: feed the actual UI-generated values through the real parser with RF stubbed. Oversized/incompatible input must yield a clear failure before radio initialization, never an exception, silent truncation or success. Do not use the existing button for live acceptance until the incompatible contract is resolved. Obtain the original device reset reason/backtrace to close the reboot diagnosis; do not claim a verified physical cause from the host test alone. |
| F02 / P0 | **De Bruijn UI bounds and feedback. HOST + CODE.** UI permits 23/24, generator permits at most 22; both throw `IllegalArgumentException`. Custom n=15 already produces 8,192 hex characters plus envelope/command overhead; preset n=16 produces 16,384. Both exceed the frame ceiling. The UI displays “Transmitting” without awaiting the outcome. | Offline/UI tests for every preset and both custom bounds: invalid input must be rejected before generating large data or writing USB; no app crash/freeze and no success toast on rejection. Include negotiated `rx_max`, not only the protocol maximum. Validate feedback independently of the generator's mathematical correctness. |
| F03 / P0 | **Console/macros: numeric CLI conversions. CODE.** RF CLI, `tone`/`beep` and screen-color callbacks use throwing `std::stoul`/`stoull`; the legacy route has no exception handler. | Stub all output drivers and exercise empty, nonnumeric, negative, trailing-junk and overflowing arguments through each relevant parser. Expect one correlated error, unchanged settings, no hardware access and a subsequent benign command succeeding. Host `unsigned long` width can differ from ESP32: include explicit target-width boundary cases. |
| F04 / P0 | **`arp` CLI: menu and network lock. CODE.** `scanHostsCallback` constructs the interactive `ARPScanner`. `setup()` has three early returns after `LOCK_TCPIP_CORE()` and before `UNLOCK_TCPIP_CORE()` (IP lookup, missing IP/netmask, unusable interface). | Fault-inject those three conditions on host first; assert balanced lock/unlock and no menu or wait-for-key in companion mode. Then verify the supported bounded/headless behavior on an owned network. Expected: recoverable error with network and USB still responsive. Real lock impact depends on the target's lwIP configuration; not reproduced on hardware here. |
| F05 / P1 | **Apple/iOS/Mac BLE visibility. CODE + REPORT.** The Mac-labelled card sends target `ios`, routed only to the Apple ProximityPair wrapper; it does not implement a separate Mac flow. | Record macOS/device/version and whether the expected result is a popup or a Settings entry. First validate the observer with an ordinary known BLE advertiser; separately record controller success, actual advertisement observation, and OS UI response. No popup is not proof that no packet was sent, and a captured packet is not proof of Mac compatibility. No claim of a pairable AirPods device from an advertisement alone. |
| F06 / P1 | **BLE false success. HOST + CODE.** The bounded wrapper ignores payload/advertising failure results and returns void; the dispatcher sends success after it returns. With an always-failing fake advertiser it still finishes normally. | Inject init/data/start failure without radio output. A failed start must become a correlated failure in the app, with no “sent/working” claim or continuing retry loop. Do not infer success on another device from the existing `spam done` reply. |
| F07 / P1 | **Shared BLE state: scan, HID, GATT, advertising. CODE, device effect PENDING.** Bounded advertising deinitializes the shared stack; it does not synchronize the scan owner's flag. | Exercise transitions with a benign test advertiser/peripheral: Scan → advertising → Stop → Scan, HID → Scan → HID, GATT → Scan; repeat, rotate and disconnect. Expect one explicit controller owner, honest status, fresh scan/HID operation afterwards, and no reset/stale handle. Verify that leaving a tool really completed its cleanup before the next acquires BLE. |
| F08 / P1 | **Stop latency and physical cessation. HOST + PENDING.** App bursts request 8 s; the BLE loop checks the deadline outside the copies loop. Virtual-clock failure stub: 8,009 ms with one copy, 9,205 ms with 100 copies, not a 2 s stop guarantee. | Host-test deadline and cancellation at each phase; use a benign advertiser for physical stop timing. Measure from app Stop to last observed activity and to recovered USB responsiveness. No new burst after Stop/leave/disconnect; report the in-flight bound separately from immediate UI cleanup. Apply the same distinction to all burst-driven tools. |
| F09 / P1 | **Script timeout/recovery. CODE, known batch-14 limit.** `runScriptRun` can return at 20 s while a native call/task remains busy; it clears the execution deadline on return. | Fault-inject an overlong native call; after timeout, run a harmless short script. Expected: a defined busy/termination outcome, no late side effect/output attributed to the second run, and eventual recovery without reboot. Separately check prompt APIs return their documented cancellation/error value. An error reply alone is not proof the interpreter stopped. |
| F10 / P1 | **IR receive with unconfigured pin. CODE.** `IrRead(headless)` still calls `setup()`, which calls `gsetIrRxPin(true)` when the configured pin is absent from the allowed list. | Stub the invalid-pin case: no pin-selection menu, no wait for device buttons; clear error and next command succeeds. On configured hardware, check known remote reception, no-signal timeout, cancel, save/reopen and ordinary replay on an owned receiver. |

### Then: actual effects, mode changes and persistence

These are **PENDING**, not findings that every listed feature is broken. Use
owned devices and controlled fixtures; disruptive modes use driver stubs or an
isolated bench. The criteria concern correctness and cleanup, not increasing
transmission rate, popup frequency or attack effectiveness.

| ID / priority | Functional scenario | Observable pass condition |
|---|---|---|
| F11 / P1 | RF Listen/Spectrum/capture and operation ownership: switch modes, rotate, leave, disconnect, then start a fresh passive capture. Exercise transmit conflicts with the output driver stubbed. | No two owners use CC1101/RMT simultaneously; no stale “on” state or radio left initialized for the wrong mode; the new capture receives a known fixture and completion/truncation metadata is preserved. Never equate raw timings with a verified complete capture. |
| F12 / P1 | Audio: mic/recording → tone/file/SSTV playback → mic; cancel source preparation and playback; disconnect while recording/exporting. | Real audio input/output, no silence falsely reported as success, no stuck I2S owner, retained recording on failed export, and successful next recording. Measure gaps and compare short known audio/image fixtures; external SSTV interoperability remains a separate check. |
| F13 / P1 | Optional modules and NFC: missing/uninitialized hardware, unsupported tag/operation, tag removed during a supported read, then a fresh read. | Capability/error matches the actual operation; no indefinite busy or fake success. Unsupported headless NFC features remain explicitly unsupported. LoRa/NRF24/LF RFID/iButton/Ethernet absence must not be inferred away from a generic radio/NFC capability. |
| F14 / P1 | Wi-Fi association vs scanning/monitoring: owned AP, wrong credentials, DHCP failure, SSID/password with spaces/Unicode; scan → join → scan. | Actual address/connectivity confirmed separately from command acceptance; no shell-token corruption, lost previous results or permanently broken Wi-Fi mode. Stop/disconnect returns ownership and a subsequent scan works. |
| F15 / P1 | Network Scan/MITM: denied LAN permission, unavailable gateway, refused TLS trust, long host names, start/stop/restart and rotation. | Denial/failure is visible; no scan shown as “empty success” solely because access was denied. MITM rows remain stable; an ordinary configured test-client request yields a verifiable response, while untrusted/pinned clients fail honestly. Confirm forwarding, not just proxy startup. |
| F16 / P1 | Files and captures: save, reopen, export, import; full storage, removed SD, interrupted transfer and lost final ACK. | Destination bytes/hash and metadata agree; old data is retained on failure, partial/unknown capture completeness is labelled, and a rejected transfer is not listed as successfully saved. No destructive retries after an uncertain commit. |
| F17 / P1 | USB/app lifecycle: stream active → two rotations → Back; screen off/background; unplug/replug; app force-stop and reconnect. | Intended stream survives rotation, leaving stops it exactly once, no orphaned pending operation, app flags agree with device state, firmware cleanup occurs after the documented link-loss timeout. Screen/LED values after reconnect come from device evidence where available. |
| F18 / P1 | APK/firmware update: wrong/missing asset, download cancel, USB permission denial, Activity recreation and bootloader re-enumeration. | Correct artifact/board identity and integrity, exactly-once port release, no flash after cancellation, recoverable errors; preserve user data on signed upgrade. Power interruption during writing requires a separate recovery setup, not the routine phone smoke test. |
| F19 / P2 | Phone-only exports and integrations: QR with Unicode, PCAP import, offline analysis, reports, macro/tone/SSTV editor state and document-provider denial. | Independent reader accepts exports; QR round-trip preserves exact data; invalid files fail boundedly; rotation does not discard work and cancelled/denied saves do not claim completion. |
| F20 / P2 | Repetition and board variants: 20 start/stop/reconnect cycles of each supported benign feature; repeat missing-module/error cases on one non-S3 and one LITE build. | Uptime does not reset, free/largest heap settle without a downward trend, resource counts recover, and framed USB contains no stray debug text. Physical results are board-specific; a successful build alone cannot tick this row. |

### Evidence to retain for each run

Record ID, exact APK/firmware version and build identifier, board/peripherals,
phone/receiver OS, inputs, prior active modes, observed effect, ACK/ERR and timing.
Take `diag` before and after where advertised: uptime, reset reason, free/minimum/
largest heap and link/drop counters. After a reboot collect the panic/backtrace
with the matching ELF if available; USB disconnect alone does not identify a reset
cause. Keep one host/process owning serial at a time. A controlled restart is not
the same as the spontaneous reset under investigation.

Verdict per case: PASS with evidence / FAIL with reproduction / BLOCKED with missing
fixture / NOT RUN. Return to a simple known-good function after every error case.
Complete host rejection/recovery checks for F01–F04 before routine phone acceptance.

Apple's documented [Mac pairing flow](https://support.apple.com/en-us/108786) and
[iPhone setup animation](https://support.apple.com/en-us/104989) are different;
neither source certifies compatibility of Brucia's synthetic advertisements.
Mac visibility therefore remains unknown pending observation on the reported OS.

## Software contract matrix — 2026-11-04 (S03)

This inventory covers all 50 catalogued tools plus Theme. It describes software
contracts and their evidence; **none of the hardware checkboxes below is changed
by JVM tests**. `TESTS.md` records the latest executed suite and build limitations.
A passing shared primitive does not prove every Android screen, firmware driver,
argument combination or peripheral works. Outstanding integration is identified
below and in S02/S04–S11 of the maintenance backlog.

Shared contracts used in the matrix:

- **CMD:** `CommandSession` checks `CommandCapabilities` before native hardware
  commands and the app's `rf`/`subghz`/`ir`/`rfid` CLI aliases, including commands
  from macros/console and structured PCM bodies. Only explicit CAPS true permits
  use. Cleanup commands remain allowed without CAPS. This is an app preflight,
  not a sandbox for arbitrary scripts/unknown Bruce CLI commands; those remain
  firmware-validated. LF RFID and iButton do not inherit NFC support.
- **OP:** `DeviceOperation`/`CommandTasks` bind waits to their original connection;
  cancellation, replacement and disconnect suppress stale callbacks. ACK waits are
  bounded by each service; timeout is not proof that firmware execution stopped.
  Shared tests: `CommandSessionTest`, `DeviceOperationTest`, `LifecycleRegressionTest`,
  `CommandCapabilitiesTest`. Generic legacy callbacks wait up to 650 seconds.
- **SUB:** `StreamSubscriptions` owns all nine known SUB names, an 8-second ACK
  deadline and exclusive Wi-Fi/CC1101/mic peers. Unknown names, missing modules,
  invalid monitor channel/BSSID reject before writing. Starts are optimistic until
  ACK. Failed/missing ACKs clear the UI flag but retain cleanup obligations; Stop all
  retries OFF and disconnect sends best-effort OFF on the original link. Late replies,
  queued changes, parent cancellation and write failures have fake-transport tests.
  An OFF ACK confirms the handler reply, not physical radio silence.
- **BURST:** `BurstLoop` owns repeated short commands. Stop prevents another burst;
  blank and explicit failures now end the loop. Cancelled scopes and immediate
  replies cannot leave active stuck. `BurstLoopTest` covers sequencing and cleanup;
  a burst already on the firmware can finish after app Stop.
- **LOCAL:** phone work needs no ESP capability. Parsing/DSP fixtures cover the listed
  pure components; Android storage/permissions, background behavior and UI lifecycle
  still need S02/S10. A blank screen capability is permission to open the screen,
  not permission to start every contained operation.

| Tabs / tools | Requirements, owner and deadline | Input / cancellation evidence and remaining limits |
|---|---|---|
| 0, 11, 15, 17, 26 — Wardrive, Sniffer, Probes, Monitor, Channels | Wi-Fi SUB; monitor channel 1–13 and optional six-byte colon-separated BSSID | `CapsTest`, `StreamSubscriptionsTest`, `PcapWriterTest`; Room/export, automatic screen subscriptions and phone location require Android acceptance |
| 7 — Crack | LOCAL dictionary/import; guided capture requires Wi-Fi and verified files; `CaptureWorkflow` total 600 s, `DictionaryTestService` owns CPU work | Parser/hash fixtures, 11 capture and 7 dictionary regressions; Stop closes owned readers, rejects stale/ambiguous captures; no firmware capture abort or artifact descriptor |
| 30 — Evil Portal | Wi-Fi CMD/OP; `EvilPortalService`; custom template requires verified files + transactional uploads; 650 s command wait | `EvilPortalServiceTest`, `FileTransferServiceTest`, native upload fixtures; Stop invalidates pending template/start; FAT recovery is not atomic visibility; physical shutdown/media behavior remain S05 |
| 33, 34 — MAC, Connect | Wi-Fi CMD/OP; MAC 10 s, join 30 s, status/leave 10 s | `WifiMacServiceTest`, `WifiJoinServiceTest`; MAC/blank inputs checked; actual association/DHCP pending |
| 35, 38, 40 — Brucegotchi, Karma, Responder | Wi-Fi CMD; independent `LongOperation`; firmware duration 5–600 s plus 30 s margin | `HeadlessWifiOpsTest`, `LongOperationTest`; explicit stop channel keeps final ACK wait, disconnect abandons; joined-network requirement for Responder still firmware/runtime-dependent |
| 44 — WIDS | Wi-Fi CMD; `WidsService`; windows 1–120 s plus 8 s margin, positive alert threshold | `WidsServiceTest`; cancellation/link loss terminates, failure retries back off 1 s, invalid/overflow durations reject; physical counts unverified |
| 2 — RF | CC1101 CMD/SUB; BURST jammer, OP brute-force (1–30 s + 8 s), generic CLI capture/replay | `SubGhzBruteforceServiceTest`, `SubFileTest`, decoder/encoder/CSV tests; failed CLI capture is no longer saved as a signal; RF ranges/protocol interoperability and capture truncation remain S05/S11 |
| 14 — FM | FM CMD; device-mic source additionally needs mic; `PcmStreamService` owns preparation through stop | `PcmStreamServiceTest`, `SessionPcmLinkTest`; ACK 4 s, cleanup 5 s, source/frequency checks; firmware can ACK dropped samples, no physical silence/lossless guarantee |
| 22 — LoRa | LoRa CMD/OP; config 10 s, send 650 s, receive 1–120 s + 10 s | `LoraServiceTest`; Stop waiting cancels delivery, send/config supersede receive, scope cancellation clears flag; firmware has no receive-abort command. Numeric config compatibility remains firmware-validated |
| 13 — NRF24 | NRF24 CMD/BURST for scan/sniff/jammer/MouseJack | `CommandCapabilitiesTest`, `BurstLoopTest`; no module sends nothing; actual ESB/radio behavior unverified |
| 50 — Spam Wi-Fi | Wi-Fi CMD/BURST; each bounded burst awaits its ACK | `CommandCapabilitiesTest`, `BurstLoopTest`; blank SSID list rejected by caller; Stop prevents subsequent bursts, physical beacon cessation pending |
| 32 — ESP-NOW | Wi-Fi CMD/OP; `EspNowService`, 10 s; nonblank message | `EspNowServiceTest`; device/build support beyond Wi-Fi still may return firmware ERR |
| 3, 51 — NFC, Spam NFC | NFC CMD/OP; LF read separately needs lf_rfid; Mifare batches 48 keys / 650 s; NDEF 75 s, other readers per service | `NfcToolsServiceTest`, `MifareKeyCheckServiceTest`, NDEF/UID fixtures; Stop ends local batches; tag identity/completeness missing from firmware ACK; unsupported headless helpers remain unsupported |
| 5, 39, 49 — BLE, BLE Remote, Spam BLE | BLE SUB/CMD; HID 10 s; GATT/media/BadBLE 650 s; BURST advertising | `BleHidServiceTest`, `GattServiceTest`, `BleMediaServiceTest`, `BadBleServiceTest`, shared SUB/BURST tests; disconnect clears local trees/pending reads; physical pairing/advertising cleanup pending |
| 1, 37 — IR, IR Remote | IR CMD/OP; receive 20 s, TV-B-Gone 75 s, CLI 650 s, BURST jammer | `IrReceiveServiceTest`, `IrRxSummaryTest`, `TvBGoneServiceTest`, catalog/parser tests; raw receive summaries lack frequency (38 kHz assumption); on-device capture/persistence pending |
| 4 — BadUSB | Saved/script editor LOCAL; BadBLE requires BLE CMD/OP; legacy USB scripts use firmware CLI | `BadBleServiceTest`, `CommandCapabilitiesTest`; CAPS has no separate USB-HID flag and CLI may reject native-USB-incompatible commands; do not claim USB HID support from screen availability |
| 18 — MITM | Phone network/service; no ESP capability | `HttpWireTest`, `MitmCaTest` validate bounded parsing/CA; sockets, foreground lifecycle, TLS trust and routing require S02/S10 and controlled end-to-end tests |
| 31 — Network Scan | Phone network and screen-owned job | Network scan result/Android network lifecycle is not certified by shared CMD tests; cancellation/background integration stays S10 |
| 46, 47 — RF Fingerprint, RF Timeline | Wi-Fi screen gate; component scans use individually gated Wi-Fi/BLE SUB; local comparisons/history | SUB tests cover radio prerequisites; fingerprint persistence/timeline retention and screen lifecycle remain Android/S08/S10 acceptance |
| 6, 8 — Device info, Console | Connection required; CMD preflight for known hardware; generic callbacks 650 s | CMD/OP tests; unknown CLI and scripts remain firmware authority; arbitrary command side effects/output completeness are not covered by hardware preflight |
| 9 — Remote | Separate unframed mirror/control transport owner in ViewModel | `TftProtocolTest`; disconnect cancels remote readers; mode-switch/reconnect, touch gestures and Activity teardown remain S06/S10 |
| 10 — Files | Verified files for reads; additionally transactional uploads for writes; `FileTransferService` / `SessionFileLink`; CLI metadata uses OP | Reader/chunk/CRC/request-ID/token/commit bounds, cancellation and failure recovery tested; lost commit ACK requires verification. LittleFS rename / FAT backup recovery; Android provider rollback and physical durability remain unverified |
| 12 — Updates | Phone network; one `ReleaseUpdateService` snapshot; supported T-Embed image required before bootloader takeover | `ReleaseUpdateServiceTest`, `ReleaseCatalogTest`, `DownloadIOTest`, `FirmwareImageTest`, `EspProtocolTest`; URL/image selection tested; installer/USB permissions, cancellation and re-enumeration remain S02/S06/S10 |
| 23 — Ethernet | eth CMD/OP; status 16 s, config 18 s, timed operations duration + 14 s | `EthernetServiceTest`; failed config does not chain status, stale mapping suppressed; IP/driver validation and physical DHCP/SPI need acceptance |
| 25, 29 — Audio, Oscilloscope | Device mic SUB; optional phone mic requires Android permission; `RecordingBuffer` retains unsaved samples | SUB and recording/export/DSP fixtures; auto-subscribe effect now reacts to connection/capability changes; Android source/export and physical timing pending |
| 27, 28 — SSTV, Tone | Mixed LOCAL editing/DSP, mic SUB for receive, speaker CMD/PCM for transmit; no blanket mic screen gate | PCM/SSTV/DTMF/speaker fixtures; missing/failed mic start cannot leave DTMF/recording active; speaker requires explicit support. Codec/permission UI and external SSTV sync remain S05/S09/S10 |
| 21 — Report | LOCAL snapshots of captured data | `ReportTest`, `SessionReportTest`, `SessionExportTest`; report/share UI and provider export need Android acceptance |
| 19 — Scripts | Connection; `ScriptService` OP 25 s; nonblank source <=6000 bytes; Stop sends `script_stop` once and waits for the run's reply; no new run until then | `ScriptServiceTest`; firmware interpreter validates execution/build availability; a native call that ignores the stop (`ble.scan`, HTTP, `badusb`) still runs to its end before the script stops |
| 41 — Macros | LOCAL persistence; `MacroRunner` pins original session, commands 650 s, delays 0–60000 ms; CMD gates per step | `MacroRunnerTest`; failed command ends sequence; Stop cancels future steps, not already-running firmware code |
| 42, 43 — RF Map 3D, Bug Sweep | Composite views; each Wi-Fi/BLE/CC1101 SUB gated independently | Shared SUB tests; RSSI positions are approximate; PNG export and view lifecycle need Android checks; no SDR/IQ claim |
| 45 — PCAP dissect | LOCAL; bounded 32 MiB import, cancellation checkpoints every 256 records | `PcapDissectorTest`, `PcapWriterTest`; blocked provider reads still must return before cancellation completes |
| 48 — Geo signals | Phone location/persistence; replay uses IR/CC1101 CMD gate | `CommandCapabilitiesTest`, signal parser fixtures; geofence persistence, permissions and background replay behavior remain S10 |
| 20 — Gadgets | Per-operation iButton, speaker, mic, Wi-Fi requirements; clock needs connection only | `IButtonServiceTest`, `ClockServiceTest`, `SpeakerServiceTest`, `MicRecordServiceTest`, `EspNowServiceTest`; no inferred modules; hardware and GPIO acceptance pending |
| 24 — Utilities | LOCAL password/hash/Base64/OUI/QR tools | `PhoneUtilsTest`, `OuiLookupTest`; no device gate; clipboard/QR rendering needs Android UI verification |
| 36 — Theme & LED | Connection; `DeviceAppearanceService` OP 10 s | Service tests cover bounds/supersession; no LED-presence capability exists, so unsupported hardware reports firmware ERR; save/display behavior needs device acceptance |

Software evidence is **not** all-green production acceptance. S02 owns fresh builds
and emulator upgrade checks; S04's software upload fixtures do not certify media durability; S06/S10 own
remaining ViewModel/Activity/installer jobs; S08 owns sustained sessions and late
EVENT/data pressure; S09/S11 own interoperability, unsupported commands and inventory
limits. Keep per-control hardware results separate below.

---

# App chrome (always on screen)

## Top status bar (`TopStatusBar`)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | Back arrow (shown when a tool is open) | Returns to the home dashboard | |
| [ ] | Brucia logo + name (tappable) | Go home, reset the category filter | |
| [ ] | Connection pill ("Tap to connect" / "Connecting" / "Connected") | When not connected, tapping starts the USB connect flow | |
| [ ] | Help "?" icon button | Opens the Help & support dialog | |

## Bottom navigation (`BottomNav`)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | Device button (memory icon, shows update dot) | Opens Device info (tab 6) | |
| [ ] | Home button | Go home, reset category filter | |
| [ ] | Cmds button (terminal icon, running-count badge) | Opens the Command log dialog | |

## Home dashboard (`HomeDashboard`)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button (when disconnected) | Starts USB connect | |
| [ ] | "Buy the LilyGo T-Embed CC1101" outlined button (when disconnected) | Opens the AliExpress purchase link | |
| [ ] | "Search tools" text field | Live-filters tools; shows matching tool cards | |
| [ ] | Category tiles (one per category, tappable) | Open the category's tool list (or open the single tool directly for a 1-tool category) | Repeated per category |
| [ ] | Theme tile | Opens the Theme screen (tab 36) | |
| [ ] | Wiki tile | Opens the wiki in the browser | |
| [ ] | Support tile | Opens the Support dialog | |
| [ ] | Tool cards (one per tool, tappable) | Open that tool's screen | Repeated per tool; also used in search results |
| [ ] | Support dialog "Close" button | Dismiss | |

## Support card (`SupportCard`, in Support dialog, Help dialog, and dashboard footer)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Sponsor on GitHub" button | Opens the GitHub Sponsors link | |
| [ ] | "Buy me a coffee" outlined button | Opens the donation link | |
| [ ] | Bitcoin "Copy address" button | Copies the BTC address to the clipboard | |
| [ ] | Monero "Copy address" button | Copies the XMR address to the clipboard | |

## Command log dialog (`CommandLogDialog`)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Stop" text button (when a command is running) | `vm.stopAllRunning()` | |
| [ ] | "Clear" text button (when entries exist) | `vm.clearCommandLog()` | |
| [ ] | "Close" text button | Dismiss | |

## Help dialog (`HelpDialog`)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Close" text button | Dismiss | |
| [ ] | "Open the wiki" button | Opens the wiki in the browser | |
| [ ] | (embeds Support card) | Sponsor / Buy me a coffee / Copy BTC / Copy XMR | See Support card above |

---

# Wi-Fi

## Wardrive (`BruciaScreen`, tab 0)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Wardriving" switch | `vm.setWardriving()` - start/stop the `wifi.ap` stream | |
| [ ] | "CSV" button | Build WiGLE CSV, write file, share | |
| [ ] | "KML" button | Build KML, write file, share | |
| [ ] | "GPX" button | Build GPX from track, write file, share | |
| [ ] | "Clear" outlined button | `vm.clearSession()` | |
| [ ] | List / Map / Stats segmented selector | Switch dashboard view | |
| [ ] | Sort selector (Stable / Name / Signal / Risk) | Set AP list sort order | Shown only when list non-empty |
| [ ] | AP card (tappable row) | Expand/collapse to reveal per-AP actions | Repeated per AP |
| [ ] | "Capture + crack" button (per AP) | `vm.captureAndCrack()` then jump to Crack tab | Repeated per AP |
| [ ] | "PMKID (clientless)" button (per AP) | `vm.capturePmkidAndCrack()` then jump to Crack tab | Repeated per AP |
| [ ] | "Deauth (continuous)" / "Stop deauth" button (per AP) | `vm.startDeauth()` / `vm.stopDeauth()` | Repeated per AP |
| [ ] | WiGLE "API Name" field | Set API name (seeded from saved value) | |
| [ ] | WiGLE "API Token" field (password) | Set API token | |
| [ ] | "Upload to WiGLE" button | `vm.uploadToWigle()` | Enabled with data + both fields |
| [ ] | Beacon spam "SSID list" field (multiline) | Set fake-AP SSID list | |
| [ ] | Beacon spam "Number of fake APs (1-256)" field | Set copies | |
| [ ] | "Start beacon spam" / "Stop beacon spam" button | `vm.startBeaconSpam()` / `vm.stopBeaconSpam()` | |

## Crack (`CrackScreen`, tab 7)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Dismiss" text button | `vm.resetHandshakeFlow()` | Shown for an inactive handshake flow |
| [ ] | "Cancel import" button | `vm.cancelHandshakeFlow()` | Cancels guided import; not firmware capture |
| [ ] | "Built-in list" outlined button | `vm.crackBuiltin()` | |
| [ ] | "Demo" outlined button | `vm.demoCrack()` | |
| [ ] | "Stop" outlined button | `vm.cancelCrack()` | Enabled while cracking |
| [ ] | "Apply mangling rules (expand wordlist)" switch | `vm.setRules()` | |
| [ ] | "Manual import (fallback)" Show/Hide header row | Toggle the manual-import section | |
| [ ] | "Import .pcap" button | Pick a .pcap -> `vm.importPcap()` | |
| [ ] | "Wordlist" button | Pick a file -> `vm.importWordlist()` | |
| [ ] | "Device .pcap path" field | Set the device path to pull | |
| [ ] | "Pull" outlined button | `vm.pullFile()` | |
| [ ] | "List captures" outlined button | List `/BrucePCAP/handshakes` | |
| [ ] | PMKID / hashcat-22000 field | Set the hash to load | |
| [ ] | "Load target" outlined button | `vm.importPmkid()` | Enabled when field non-blank |
| [ ] | "Export hashcat 22000" outlined button | `vm.exportHashcat()` + share | |
| [ ] | Catalog row trailing button (Download / Cancel / Crack) | `vm.downloadWordlist()` / `vm.cancelDownload()` / `vm.crackWithWordlist()` | Repeated per catalog entry, state-dependent |
| [ ] | Bundled wordlist "Run" button | `vm.crackBundled()` | Repeated per bundled list |
| [ ] | Imported wordlist "Run" button | `vm.crackWithWordlist()` | Repeated per imported list |
| [ ] | Imported wordlist "Del" button | `vm.deleteWordlist()` | Repeated per imported list |

## Sniffer (`StationSnifferScreen`, tab 11)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Start" / "Stop" button | `vm.startStationSniff()` / `vm.stopStationSniff()` | |
| [ ] | "Export CSV" outlined button | `vm.exportStationsCsv()` + share | |
| [ ] | "Clear" outlined button | `vm.clearStations()` | |

## Probes (`WifiProbeScreen`, tab 15)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Start" / "Stop" button | `vm.startProbeSniff()` / `vm.stopProbeSniff()` | |
| [ ] | "Export CSV" outlined button | `vm.exportProbesCsv()` + share | |
| [ ] | "Clear" outlined button | `vm.clearProbes()` | |
| [ ] | "Group by SSID" / "Group by client" segmented selector | Regroup the probe view | |

## Monitor (`WifiMonitorScreen`, tab 17)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | Channel selector 1-13 | Set capture channel | Scrollable segmented group |
| [ ] | "Pick target AP" / "Hide APs" outlined button | Toggle the scanned-AP picker | |
| [ ] | "Clear" text button (next to target) | Clear the chosen target | Shown when a target is set |
| [ ] | AP picker rows (tappable) | Set target BSSID/SSID/channel | Repeated per scanned AP |
| [ ] | "Your network password (PSK)" field (password) | PSK for Wireshark export / PMK | |
| [ ] | "Start" / "Stop" button | `vm.startMonitor()` / `vm.stopMonitor()` | |
| [ ] | "Export pcap" outlined button | `vm.exportMonitorPcap()` + share | |
| [ ] | "Open pcap" outlined button | `vm.exportMonitorPcap()` + open-with | |
| [ ] | "Clear" outlined button | `vm.clearMonitor()` | |
| [ ] | Frame rows (tappable) | Toggle the hex-dump detail | Repeated per frame |
| [ ] | "Detect jamming (15s)" button (`JamDetectCard`) | `vm.jamDetect(15)` | |

## Evil Portal (`EvilPortalScreen`, tab 30)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Fake AP name (SSID)" field | Set portal SSID | Disabled while active |
| [ ] | Captive-page selector (Device default / Wi-Fi login / Firmware update / Custom) | Choose template | Radio rows |
| [ ] | "Edit" outlined button | Show the HTML editor | Custom only |
| [ ] | "Preview" outlined button | Render HTML in a sandboxed WebView | Custom only |
| [ ] | Captive-page HTML field | Edit custom HTML | Custom edit mode |
| [ ] | "Save as" field | Set template name | Custom mode |
| [ ] | "Save" button | `vm.saveCustomPortal()` | Custom mode |
| [ ] | Saved-template "<name>" button | Load template HTML | Repeated per saved template |
| [ ] | Saved-template "Del" button | `vm.deleteCustomPortal()` | Repeated per saved template |
| [ ] | "Deauth clients (evil twin)" switch | Toggle evil-twin deauth | Disabled while active |
| [ ] | "Channel (1-14)" field | Set channel | |
| [ ] | "Clone a nearby network" / "Hide networks" outlined button | Toggle target list; starts/stops scan | |
| [ ] | Target network rows (tappable) | Copy SSID/channel into fields, enable deauth | Repeated per scanned AP |
| [ ] | "Start Evil Portal" / "Start evil twin" / "Stop Evil Portal" button | `vm.startEvilPortal()` / `vm.stopEvilPortal()` | Label varies |
| [ ] | "Clear" text button | `vm.clearEvilCreds()` | Shown when credentials captured |

## Channels (`ChannelAnalyzerScreen`, tab 26)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | Only interactive control; scanning auto-starts/stops. Rest is a read-only congestion chart |

## MAC (`MacScreen`, tab 33)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Refresh" outlined button | `vm.getWifiMac()` | |
| [ ] | MAC address field | Set the MAC to apply | |
| [ ] | "Set MAC" button | `vm.setWifiMac()` | Enabled when field non-blank |
| [ ] | "Randomize" outlined button | `vm.setWifiMac("random")` | |

## Connect (`WifiJoinScreen`, tab 34)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "SSID" field | Set SSID | |
| [ ] | "password (blank = open)" field | Set password | |
| [ ] | "Connect" button | `vm.wifiJoin()` | Enabled when SSID non-blank |
| [ ] | "Status" outlined button | `vm.wifiStatus()` | |
| [ ] | "Disconnect" outlined button | `vm.wifiLeave()` | |

## Karma (`KarmaScreen`, tab 38)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | Duration selector 30s / 60s / 120s / 300s | Set run duration | |
| [ ] | "Start (Ns)" button | `vm.startKarma()` | Shown when idle |
| [ ] | "Stop" button | `vm.stopKarma()` | Shown when running |

## Brucegotchi (`BrucegotchiScreen`, tab 35)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | Duration selector 30s / 60s / 120s / 300s | Set run duration | |
| [ ] | "Start (Ns)" button | `vm.startBrucegotchi()` | Shown when idle |
| [ ] | "Stop" button | `vm.stopBrucegotchi()` | Shown when running |

---

# Sub-GHz

## RF (`SubGhzScreen`, tab 2)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Listen" switch | `vm.setSubghz()` - `subghz.raw` stream | |
| [ ] | Spectrum "Start" / "Stop" button | `vm.startSpectrum()` / `vm.stopSpectrum()` | |
| [ ] | Spectrum "Clear" outlined button | `vm.clearSpectrum()` | Shown when history non-empty |
| [ ] | Jammer "Start jammer" / "Stop jammer" button | `vm.startSubghzJammer()` / `vm.stopSubghzJammer()` | |
| [ ] | Brute-force protocol selector | Choose protocol | Toggle-button group (BRUTE_PROTOCOLS) |
| [ ] | Brute-force "Seconds (1-30)" field | Set duration | |
| [ ] | "Start brute force" button | `vm.subghzBruteforce()` | |
| [ ] | "Record to CSV" Start / Stop button | `vm.startRfCapture()` / `vm.stopRfCapture()` | |
| [ ] | "Export / Share CSV" button | `vm.exportRfCsv()` + share | |
| [ ] | Frequency selector (315 / 433.92 / 868.35 / 915 MHz) | Set frequency | |
| [ ] | "Capture & save (rf rx)" button | `vm.rfCapture()` | |
| [ ] | "Decode sample" outlined button | `vm.decodeSample()` | |
| [ ] | "Clear" outlined button | `vm.clearSubghz()` | Enabled when signals exist |
| [ ] | De Bruijn frequency selector | Set frequency | |
| [ ] | De Bruijn code-length selector (8/10/12/16/20/24) | Set bits | |
| [ ] | De Bruijn "Custom bits (4-24)" field | Set bits | |
| [ ] | De Bruijn pulse-width selector (200/300/400/500us) | Set te | |
| [ ] | De Bruijn "Custom te (50-2000 us)" field | Set te | |
| [ ] | "Transmit de Bruijn" button | `vm.transmitDeBruijn()` | |
| [ ] | "Import .sub" outlined button | Pick a .sub -> `vm.importSub()` | |
| [ ] | "Export .sub" outlined button | `vm.exportSub()` + share | Enabled when signals/wave exist |
| [ ] | "Transmit" button | `vm.transmitSub()` | Shown after a .sub import |
| [ ] | Saved signal "Replay" button | `vm.replaySaved()` | Repeated per saved signal |
| [ ] | Saved signal "Delete" button | `vm.deleteSaved()` | Repeated per saved signal |

---

# Radio

## FM (`FmScreen`, tab 14)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Frequency (kHz, 87500-108000)" field | Set transmit frequency | Disabled while streaming |
| [ ] | Audio-source selector (File / Tone / Phone mic / Device mic) | Choose source | Radio rows, disabled while streaming |
| [ ] | "Tone frequency (Hz)" field | Set tone frequency | Shown when Tone selected |
| [ ] | "Start transmitting" / "Stop" button | Start per source (`vm.fmStartFile/Tone/PhoneMic/DeviceMic`) or `vm.fmStop()` | Phone mic prompts for RECORD_AUDIO |

## LoRa (`LoRaScreen`, tab 22)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Frequency (MHz)" field | Set frequency | |
| [ ] | "BW" field | Set bandwidth | |
| [ ] | "SF" field | Set spreading factor | |
| [ ] | "CR" field | Set coding rate | |
| [ ] | "Apply config" button | `vm.loraConfig()` | |
| [ ] | "Message" field | Set message | |
| [ ] | "Send" button | `vm.loraSend()` | |
| [ ] | "Duration (seconds)" field | Set receive duration | |
| [ ] | "Start receive" / "Stop waiting" button | `vm.loraRecv()` / `vm.stopLoraRecv()` | 1–120 s; Stop suppresses late results, firmware window can continue |
| [ ] | "Clear" outlined button | `vm.clearLoraPackets()` | Enabled when packets exist |

---

# 2.4GHz

## NRF24 (`Nrf24Screen`, tab 13)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | Scanner "Start scan" / "Stop" button | `vm.startNrf24Scan()` / `vm.stopNrf24Scan()` | |
| [ ] | Sniffer "Start sniff" / "Stop" button | `vm.startNrf24Sniff()` / `vm.stopNrf24Sniff()` | |
| [ ] | Sniffer "Clear" outlined button | `vm.clearNrf24Devices()` | Enabled when devices exist |
| [ ] | Jammer "Duration (seconds)" field | Set duration | |
| [ ] | Jammer "Start" / "Stop" button | `vm.startNrf24Jammer()` / `vm.stopNrf24Jammer()` | |
| [ ] | MouseJack "Duration (seconds)" field | Set duration | |
| [ ] | MouseJack "Inject text (optional)" field | Set inject text | |
| [ ] | MouseJack "Scan + inject" / "Stop" button | `vm.startNrf24MouseJack()` / `vm.stopNrf24MouseJack()` | |

## ESP-NOW (`EspnowScreen`, tab 32)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | In the not-connected card |
| [ ] | "message" field | Set message | |
| [ ] | "Broadcast (ESP-NOW)" button | `vm.espnowSend()` | Enabled when connected + message non-blank |

---

# NFC

## NFC (`NfcScreen`, tab 3)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Read tag" button | `vm.nfcRead()` | |
| [ ] | "Clone tag" button | `vm.nfcClone()` | |
| [ ] | "Emulate tag" button | `vm.nfcEmulate()` | |
| [ ] | "Run key attack" button | `vm.runMifareKeyCheck()` | |
| [ ] | "Stop batches" button | `vm.stopMifareKeyCheck()` | Cancels local sequencing; current firmware batch can continue |
| [ ] | "Import keys" outlined button | Pick file -> `vm.importMifareKeys()` | |
| [ ] | "Clear" text button | `vm.clearMifareUserKeys()` | Shown when user keys imported |
| [ ] | "Write" outlined button | `vm.nfcWrite()` | |
| [ ] | "Info" outlined button | `vm.nfcInfo()` | |
| [ ] | "Save" outlined button | `vm.nfcSave()` | |
| [ ] | "Erase" outlined button | `vm.nfcErase()` | |
| [ ] | "Read NDEF" outlined button | `vm.nfcNdef()` | |
| [ ] | "Analyze security" outlined button | Run on-phone NFC security analysis | Enabled when a tag was read |
| [ ] | NDEF "URL" field | Set URL | |
| [ ] | "Build NDEF (share hex)" outlined button | Build NDEF TLV hex, write + share | |
| [ ] | NDEF push type selector (URL / Text / Contact) | Choose payload type (`NfcNdefPushCard`) | |
| [ ] | NDEF push payload field | Set payload | |
| [ ] | NDEF push "Send" button | `vm.nfcNdefPush()` | Enabled when connected + payload non-blank |
| [ ] | "Read bank card" (EMV) button | `vm.nfcEmvRead()` | |
| [ ] | "Hex UID" field | Set UID to write | |
| [ ] | "Write UID" button | `vm.nfcWriteUid()` | |
| [ ] | "Amiibo emulate" outlined button | `vm.nfcAmiibo("emulate")` | |
| [ ] | "Amiibo dump" outlined button | `vm.nfcAmiibo("dump")` | |
| [ ] | "Nested" outlined button | `vm.mifareNested()` | |
| [ ] | "mfkey32" outlined button | `vm.mifareMfkey32()` | |
| [ ] | "SRIX read" outlined button | `vm.srixRead()` | |
| [ ] | "125kHz read" outlined button | `vm.rfid125Read()` | |
| [ ] | Saved card "Delete" button | `vm.deleteSaved()` | Repeated per saved card |

---

# BLE & IR

## BLE (`BleScreen`, tab 5)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Scan" switch | `vm.setBleScan()` - `ble.scan` stream | |
| [ ] | BLE spam (Apple popups) card: copies field + Start/Stop | `vm.startBleSpam("ios")` / `vm.stopBleSpam()` | |
| [ ] | Fast Pair spam (Android popups) card: copies field + Start/Stop | `vm.startBleSpam("android")` / `vm.stopBleSpam()` | |
| [ ] | BadBLE "Load from library (N)" / "Hide library" outlined button | Toggle the Ducky payload picker | |
| [ ] | BadBLE payload list entries (tappable) | Load that script into the editor | Repeated per bundled script |
| [ ] | BadBLE "DuckyScript" field (multiline) | Edit payload | |
| [ ] | BadBLE "Run over BLE" button | `vm.bleBadble()` | |
| [ ] | GATT explorer "Close" outlined button | `vm.gattStop()` | Shown once a device is picked |
| [ ] | GATT service rows (tappable) | Expand/collapse characteristics | Repeated per service |
| [ ] | GATT "Read" outlined button | `vm.gattRead()` | Repeated per readable characteristic |
| [ ] | iBeacon "Proximity UUID (optional)" field | Set UUID | Disabled while active |
| [ ] | "Broadcast iBeacon" / "Stop iBeacon" button | `vm.startIbeacon()` / `vm.stopIbeacon()` | |
| [ ] | Media "Target MAC" field | Set target MAC | |
| [ ] | "Send media hijack" button | `vm.bleMedia()` | |
| [ ] | "Clear" outlined button | `vm.clearBle()` | Enabled when devices exist |
| [ ] | Per-device "Explore" row | `vm.gattConnect()` | Repeated per discovered device |

## IR (`IrScreen`, tab 1)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | Remotes / Catalog / Raw segmented selector | Switch IR sub-tab | |
| [ ] | "< Devices" outlined button | Back to the remote picker | Remotes tab, when a remote is open |
| [ ] | "Browse all devices (N)" / "Show TV remotes" outlined button | Toggle full device browse | |
| [ ] | "Search brand / model / category" field | Filter devices | Remotes + Catalog tabs |
| [ ] | "Categories" / breadcrumb text buttons | Navigate the device breadcrumb | |
| [ ] | Device/browse rows (tappable) | Drill into category/brand/model or open a remote | Repeated |
| [ ] | On-screen remote keys (`IrRemote`) | Transmit the mapped code (`vm.irTxRaw` / `vm.sendCommand`) | POWER, MUTE, VOL +/-, CH +/-, D-pad (UP/LEFT/OK/RIGHT/DOWN), INPUT, MENU, HOME, BACK, numbers 0-9 |
| [ ] | "Catalog (N)" / "Close catalog" button | Toggle the catalog browser | Catalog tab |
| [ ] | "Import .ir" outlined button | Pick + parse an .ir file | Catalog tab |
| [ ] | "Learn a remote (ir rx)" outlined button | `vm.learnIr()` | Catalog tab |
| [ ] | "Paste Flipper .ir content" field | Paste .ir content | Catalog tab |
| [ ] | "Load" button | Parse pasted content into buttons | Catalog tab |
| [ ] | "Samples" outlined button | Load sample buttons | Catalog tab |
| [ ] | IR button card "Replay raw" outlined button | `vm.irTxRaw()` | Raw buttons only; repeated |
| [ ] | IR button card "Send" button | `vm.sendCommand()` | Repeated per button |
| [ ] | Raw tab region selector (NA / EU) | Set TV-B-Gone region (`IrActionsSection`) | |
| [ ] | "TV-B-Gone (turn off TVs)" button | `vm.irTvBGone()` | |
| [ ] | "IR jammer (flood IR)" / "Stop IR jammer" button | `vm.startIrJammer()` / `vm.stopIrJammer()` | |
| [ ] | "Learn IR (receive)" outlined button | `vm.irReceiveOnce()` | |
| [ ] | "Save to learned" button | `vm.saveLearnedIr()` | After a capture |
| [ ] | "Replay raw" outlined button | `vm.irTxRaw()` | After a raw capture |

## IR Remote (`IrRemoteScreen`, tab 37)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | Captured-key tile (tap) | `vm.pressRemoteKey()` | Repeated per captured key |
| [ ] | Captured-key tile (long-press) | Open the delete dialog | Repeated per captured key |
| [ ] | "Add button (capture)" button | Open the Add dialog | |
| [ ] | Add dialog "Button name" field | Set key label | |
| [ ] | Add dialog "Capture" text button | `vm.captureRemoteKey()` | |
| [ ] | Add dialog "Cancel" text button | Dismiss | |
| [ ] | Delete dialog "Delete" text button | `vm.deleteRemoteKey()` | |
| [ ] | Delete dialog "Cancel" text button | Dismiss | |

## BLE Remote (`BleHidScreen`, tab 39)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Start" button | `vm.bleHidBegin()` | |
| [ ] | "Refresh" outlined button | `vm.bleHidRefresh()` | |
| [ ] | "Stop" outlined button | `vm.bleHidEnd()` | |
| [ ] | Presenter keys (< Prev, Next >, Start (F5), Esc, Pg Up, Pg Dn) | `vm.bleHidKey()` | Six buttons |
| [ ] | "text to type" field | Set text | |
| [ ] | "Type" button | `vm.bleHidType()` | |

## BadUSB (`BadUsbScreen`, tab 4)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | Over-USB mode shows a "Not available over USB" info card |
| [ ] | BadBLE "Load from library" / "Hide library" outlined button | Toggle the payload picker | |
| [ ] | BadBLE payload list entries (tappable) | Load that script | Repeated |
| [ ] | BadBLE "DuckyScript" field | Edit payload | |
| [ ] | BadBLE "Run over BLE" button | `vm.bleBadble()` | |
| [ ] | Payload library cards (tappable) | Expand/collapse the payload | Repeated |
| [ ] | "Copy script" outlined button | Copy script to clipboard | Shown when a card is expanded |

---

# Spam

> The three Spam tiles (BLE / Wi-Fi / NFC) all open the same `SpamScreen` composable with a
> filter. Each BLE platform card has an identical "Copies / advertisements (1-100)" field plus
> a Start/Stop toggle button (`vm.startBleSpam(target, copies)` / `vm.stopBleSpam()`) - that
> Start/Stop pattern repeats across all BLE platform cards and is noted once below.

## Spam BLE (`SpamScreen` filter=ble, tab 49)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | Apple / iOS / Mac card: copies field + Start/Stop | `vm.startBleSpam("ios")` / stop | Start/Stop pattern (see note) |
| [ ] | Android card: copies field + Start/Stop | `vm.startBleSpam("android")` / stop | |
| [ ] | Windows / PC card: copies field + Start/Stop | `vm.startBleSpam("windows")` / stop | |
| [ ] | Samsung card: copies field + Start/Stop | `vm.startBleSpam("samsung")` / stop | |
| [ ] | All BLE platforms card: copies field + Start/Stop | `vm.startBleSpam("all")` / stop | |

## Spam Wi-Fi (`SpamScreen` filter=wifi, tab 50)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "SSID list (one per line)" field | Set fake-AP SSID list (`BeaconSpamCard`) | |
| [ ] | "Number of fake APs (1-256)" field | Set copies | |
| [ ] | "Start beacon spam" / "Stop beacon spam" button | `vm.startBeaconSpam()` / `vm.stopBeaconSpam()` | |

## Spam NFC (`SpamScreen` filter=nfc, tab 51)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | NDEF push type selector (URL / Text / Contact) | Choose payload type (`NfcNdefPushCard`) | |
| [ ] | NDEF push payload field | Set payload | |
| [ ] | "Send" button | `vm.nfcNdefPush()` | |

## Spam IR (`SpamScreen` filter=ir, tab 53)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "IR power spam (all power codes)" / "Stop IR power spam" button | `vm.irPowerSpamStart()` / `vm.irPowerSpamStop()` (`IrPowerSpamControls`) | Same run as the IR screen button; leaving the screen stops it; disabled on LITE (`ir_cmds` omitted) |

---

# Intercept

## MITM (`MitmScreen`, tab 18)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | ARP spoof "Wi-Fi SSID" field | Set SSID | |
| [ ] | ARP spoof "Wi-Fi password" field | Set password | |
| [ ] | ARP spoof "Target IP" field | Set target IP | |
| [ ] | ARP spoof "Gateway IP" field | Set gateway IP | |
| [ ] | "Start ARP spoof" / "Stop ARP spoof" button | `vm.startArpSpoof()` / `vm.stopArpSpoof()` | |
| [ ] | "Listen port" field | Set proxy port | Disabled while running |
| [ ] | "Start proxy" / "Stop proxy" button | `MitmController.start()` / `.stop()` | |
| [ ] | "Export CA certificate (.crt)" outlined button | Export + share the CA cert | |
| [ ] | "Clear" outlined button | `MitmLog.clear()` | Shown when transactions exist |
| [ ] | Transaction row cards (tappable) | Open the transaction detail | Repeated per transaction |
| [ ] | Detail "Back to list" outlined button | Clear selection | |

## Network Scan (`NetworkScanScreen`, tab 31)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Scan network" / "Stop scan" button | Run / cancel the on-phone network scan | |
| [ ] | Vuln badge rows (tappable) | Open the CVE URL in the browser | Repeated per vuln |

## Responder (`ResponderScreen`, tab 40)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | Duration selector 30s / 60s / 120s / 300s | Set run duration | |
| [ ] | "Start (Ns)" button | `vm.startResponder()` | Shown when idle |
| [ ] | "Stop" button | `vm.stopResponder()` | Shown when running |

## WIDS (`WidsScreen`, tab 44)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Window: Ns per listen" slider | Set listen window (5-30s) | Shown while inactive |
| [ ] | "Alert threshold" slider | Set deauth-frames threshold (3-100) | Shown while inactive |
| [ ] | "Start monitoring" / "Stop monitoring" button | `vm.startWids()` / `vm.stopWids()` | |
| [ ] | "Clear" text button | `vm.clearWidsEvents()` | Shown when events exist |

## RF Fingerprint (`RfFingerprintScreen`, tab 46)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Place name" field | Set fingerprint name | |
| [ ] | "Capture" button | `vm.captureFingerprint()` | Enabled when connected + emitters seen |
| [ ] | "Close" text button | Clear the active comparison | In comparison card |
| [ ] | "Compare now" button (per saved fingerprint) | Compare against this fingerprint | Repeated |
| [ ] | "Delete" outlined button (per saved fingerprint) | `vm.deleteFingerprint()` | Repeated |

## RF Timeline (`RfTimelineScreen`, tab 47)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Clear" text button | `vm.clearTimeline()` | Shown when sightings exist |
| [ ] | "Filter" field | Filter the sightings list | |

---

# Device

## Device info (`DeviceScreen`, tab 6)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | Updates shortcut card (tappable) | Open the Updates screen | Highlighted when an update is available |
| [ ] | "Device screen" switch | `vm.setDeviceScreen()` | |
| [ ] | "Device LED" switch | `vm.setDeviceLed()` | |
| [ ] | "Screen brightness: N%" slider | `vm.setScreenBright()` | Enabled when screen on |
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Info" outlined button | `vm.deviceCmd("info")` | |
| [ ] | "Memory" outlined button | `vm.deviceCmd("free")` | |
| [ ] | "Uptime" outlined button | `vm.deviceCmd("uptime")` | |
| [ ] | "I2C scan" outlined button | `vm.deviceCmd("i2c")` | |
| [ ] | "Sleep" outlined button | `vm.powerSleep()` | |
| [ ] | "Reboot" outlined button | `vm.powerReboot()` | |
| [ ] | "Generate security report" button | Build report, write file + share | |
| [ ] | "Export session JSON" button | Build JSON, write file + share | |
| [ ] | Theme/LED card controls (`ThemeLedCard`) | See the Theme screen below | Same card is embedded here |

## Console (`ConsoleScreen`, tab 8)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | CLI group selector | Choose a command group | Segmented, per `CliCatalog.groups` |
| [ ] | Per-command outlined buttons | Fill input (templated) or `vm.sendConsole()` | Repeated per command |
| [ ] | "command" field | Set the command line | |
| [ ] | "Run" button | `vm.sendConsole()` | Enabled when connected + non-blank |
| [ ] | "Clear" outlined button | `vm.clearConsole()` | |
| [ ] | "Export log" outlined button | Export the log + share | |

## Remote (`RemoteScreen`, tab 9)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Start" button | `vm.startRemote()` | |
| [ ] | "Stop" outlined button | `vm.stopRemote()` | |
| [ ] | D-pad (Up / Prev / OK / Next / Down) | `vm.remoteNav()` | Enabled while running |
| [ ] | "Back" outlined button | `vm.remoteNav("esc")` | |
| [ ] | "PrevPg" outlined button | `vm.remoteNav("prevpage")` | |
| [ ] | "NextPg" outlined button | `vm.remoteNav("nextpage")` | |

## Files (`FilesScreen`, tab 10)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Up" outlined button | Go to the parent directory | Enabled when not at root |
| [ ] | "Refresh" outlined button | Refresh the listing | |
| [ ] | "New / template" outlined button | Open the new-file dialog | |
| [ ] | "New editor" outlined button | Open a blank editor | |
| [ ] | Entry cards (tappable) | Enter a directory / open the file action sheet | Repeated per entry |
| [ ] | Per-file "Pull" outlined button | `vm.pullFile()` to the app folder | Repeated per file |
| [ ] | Action sheet "Pull (app folder)" | `vm.pullFile()` | |
| [ ] | Action sheet "Pull to... (choose destination)" | SAF create document -> `vm.pullFileToUri()` | |
| [ ] | Action sheet "Open with..." | Pull bytes + open-with intent | |
| [ ] | Action sheet "Edit" | Pull bytes + open the editor | Editable files only |
| [ ] | Action sheet "Delete" | `vm.deleteDeviceFile()` | |
| [ ] | Action sheet "Cancel" | Dismiss | |
| [ ] | New dialog "File / folder name" field | Set name | |
| [ ] | New dialog "Create empty file on device" | `vm.pushFile()` | |
| [ ] | New dialog "Create folder on device" | `vm.makeDeviceDir()` | |
| [ ] | New dialog template buttons | Load a template into the editor | Repeated per template |
| [ ] | New dialog "Cancel" | Dismiss | |
| [ ] | Editor "Close" (`FileEditorScreen`) | Close the editor | |
| [ ] | Editor "Edit" / "View" segmented selector | Toggle HTML view mode | HTML files only |
| [ ] | Editor body field | Edit text | |
| [ ] | Editor "Device path" field | Set the save path | |
| [ ] | Editor "Save to phone" | SAF create document + write | |
| [ ] | Editor "Save to device" | `vm.pushFile()` | |
| [ ] | Editor "Open with..." | Write temp + open-with intent | |

## Updates (`UpdatesScreen`, tab 12)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Check for app update" outlined button | `vm.checkUpdates()` | One snapshot; disabled during download/flash, failed refresh clears old assets |
| [ ] | "Download & install <ver>" button | `vm.requestAppInstall()` | Shown when a newer APK exists |
| [ ] | "Board image" dropdown button | Open the board menu | |
| [ ] | "Auto (connected device)" dropdown item | `vm.selectBoard(null)` | |
| [ ] | Per-board dropdown items | `vm.selectBoard()` | Repeated per board |
| [ ] | "Flash latest firmware" / "Flash <board>" button | `vm.resetFlash()` + `onFlash()` | Requires matching T-Embed image in current snapshot before USB takeover |
| [ ] | Flash log "Clear" text button | `vm.clearFlashLog()` | Shown when log non-empty |

## Ethernet (`EthernetScreen`, tab 23)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Refresh status" button | `vm.ethStatus()` | |
| [ ] | "eth_config arguments" field | Set config args | |
| [ ] | "Apply config" button | `vm.ethConfig()` | |
| [ ] | "DHCP starve" outlined button | `vm.ethDhcpStarve()` | |
| [ ] | "MAC flood" outlined button | `vm.ethMacFlood()` | |

---

# Audio

## Audio (`AudioDashboardScreen`, tab 25)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect (`AudioHeader`) | |
| [ ] | "Record" / "Stop & save" button | `vm.startRecording()` / `vm.stopRecordingAndExport()` + share (`RecordBar`) | |
| [ ] | Scope mode selector (Time / XY mono / XY 2-mic) | Switch scope rendering (`ScopePanel`) | |
| [ ] | Trigger-mode selector (Rising / Falling / Auto) | Set trigger mode | Time mode |
| [ ] | "Trigger level" slider | Set trigger level | Time mode; disabled in Auto |
| [ ] | "Time base" slider | Set samples/screen | Time mode |
| [ ] | "Vertical gain" slider | Set gain | Time mode |
| [ ] | "Delay d" slider | Set XY delay | XY mono mode |
| [ ] | "Grant mic access" outlined button | Request RECORD_AUDIO | XY 2-mic mode, when denied |

## Oscilloscope (`OscilloscopeScreen`, tab 29)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Record" / "Stop & save" button | `vm.startRecording()` / `vm.stopRecordingAndExport()` + share | |
| [ ] | Scope controls (`ScopePanel`) | Same as Audio screen scope panel | See Audio screen |

## SSTV (`SstvScreen`, tab 27)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Record" / "Stop & save" button | Record + share | |
| [ ] | Transmit mode radio list (Robot36 / Robot72 / Martin M1 / M2 / Scottie S1 / S2) | Set TX mode | Disabled while transmitting |
| [ ] | "Stop" outlined button | `vm.sstvTransmitStop()` | Shown while transmitting |
| [ ] | "Pick image and transmit" button | Pick image -> `vm.sstvTransmit()` | Needs connected + speaker |

## Tone (`ToneScreen`, tab 28)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | DTMF "Clear" outlined button | `vm.clearDtmfHeard()` | Shown when heard non-empty |
| [ ] | DTMF "Start listening" button | `vm.startDtmfDecode()` | |
| [ ] | DTMF "Stop" button | `vm.stopDtmfDecode()` | |
| [ ] | Generator "Frequency" slider | Set tone frequency (100-4000 Hz) | |
| [ ] | Generator "Duration" slider | Set duration (50-2000 ms) | |
| [ ] | Generator "Speaker volume" slider | `vm.setSpeakerVolume()` | |
| [ ] | Generator "Play tone" button | `vm.speakerTone()` | |
| [ ] | Songs RTTTL preset buttons | `vm.playSong()` / `vm.stopSong()` | Repeated per preset |
| [ ] | Songs "Stop" button | `vm.stopSong()` | Shown while playing |
| [ ] | Composer note-length selector (1/1 ... 1/16) | Set note length | |
| [ ] | Composer octave "-" / "+" | Change octave | |
| [ ] | Composer note buttons (C ... B, 12) | Append a note | Repeated per note |
| [ ] | Composer "Rest" button | Append a rest | |
| [ ] | Composer "Undo" button | Drop the last step | |
| [ ] | Composer "Clear" button | Clear steps | |
| [ ] | Composer "Tempo" slider | Set BPM (40-300) | |
| [ ] | Composer "Play" button | `vm.playSong("Composer", ...)` | |
| [ ] | Composer "Stop" outlined button | `vm.stopSong()` | |
| [ ] | Composer "Name" field | Set song name | |
| [ ] | Composer "Save" button | `vm.saveCustomSong()` | |
| [ ] | My songs play button (per song) | `vm.playSong()` / `vm.stopSong()` | Repeated per saved song |
| [ ] | My songs "Del" button (per song) | `vm.deleteCustomSong()` | Repeated per saved song |
| [ ] | DTMF keypad (16 keys: 0-9, A-D, *, #) | `vm.sendDtmf()` | Repeated per key |

---

# Tools

## Report (`ReportScreen`, tab 21)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Generate report (.md)" button | Write a Markdown report + share | Enabled when data exists |
| [ ] | "Export .txt" outlined button | Write a .txt report + share | Enabled when data exists |

## Scripts (`ScriptsScreen`, tab 19)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | Example chips | Load an example into the editor | Repeated per example |
| [ ] | "JavaScript source" field | Edit the script | |
| [ ] | "Run" button | `vm.scriptRun()` | Enabled when connected + non-blank |
| [ ] | "Clear output" outlined button | `vm.clearScriptOutput()` | |

## Macros (`MacroScreen`, tab 41)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Stop macro" button | `vm.stopMacro()` | Shown while running |
| [ ] | "Macro name" field | Set macro name | |
| [ ] | "Command" field | Set the step command | |
| [ ] | "Wait ms" field | Set the step delay | |
| [ ] | "Add step" outlined button | Append a step | |
| [ ] | Per-step "x" button | Remove that step | Repeated per step |
| [ ] | "Save macro" button | `vm.saveMacro()` | |
| [ ] | Saved macro "Run" button | `vm.runMacro()` | Repeated per macro |
| [ ] | Saved macro "Delete" button | `vm.deleteMacro()` | Repeated per macro |

## RF Map 3D (`RfMap3dScreen`, tab 42)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | WebView canvas (drag to rotate) | Rotate the 3D scene | Handled in embedded JS |
| [ ] | "Zoom" slider | Set zoom (`window.setZoom`) | |
| [ ] | "Refresh" button | Rebuild + push source data | |
| [ ] | "Save PNG" outlined button | Save the canvas as a PNG | |

## Bug Sweep (`BugSweepScreen`, tab 43)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Wi-Fi" switch | Toggle the Wi-Fi band | Disabled while sweeping |
| [ ] | "BLE" switch | Toggle the BLE band | Disabled while sweeping |
| [ ] | "Sub-GHz (CC1101)" switch | Toggle the sub-GHz band | Disabled while sweeping |
| [ ] | "Start sweep" / "Stop sweep" button | Start/stop the selected band streams | Needs connected + a band selected |

## PCAP dissect (`PcapDissectScreen`, tab 45)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Open .pcap from phone" / "Parsing..." button | Pick + parse a pcap on-phone | |

## Geo signals (`GeoSignalsScreen`, tab 48)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | Pinned signal "Replay" button | `vm.replayGeoSignal()` | Repeated per pinned signal |
| [ ] | Pinned signal "Delete" button | `vm.deleteGeoSignal()` | Repeated per pinned signal |
| [ ] | Saved signal "Pin here" outlined button | `vm.addGeoSignal()` | Repeated per saved signal; needs a location fix |

## Gadgets (`ToolsScreen`, tab 20)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | iButton "Read" button | `vm.ibuttonRead()` | |
| [ ] | iButton "id (hex)" field | Set the id to write | |
| [ ] | iButton "Write" button | `vm.ibuttonWrite()` | |
| [ ] | Clock "Get time" outlined button | `vm.clockGet()` | |
| [ ] | Clock "Sync from phone" button | `vm.clockSet()` | |
| [ ] | Speaker "freq (Hz)" field | Set tone frequency | Speaker card hidden if no speaker |
| [ ] | Speaker "duration (ms)" field | Set tone duration | |
| [ ] | Speaker "Play tone" button | `vm.speakerTone()` | |
| [ ] | Speaker "file path" field | Set the file path | |
| [ ] | Speaker "Play file" button | `vm.speakerPlay()` | |
| [ ] | Mic "seconds" field | Set record duration | Mic card shown only if mic present |
| [ ] | Mic "Record" button | `vm.micRecord()` | |
| [ ] | ESP-NOW "message" field | Set message | |
| [ ] | ESP-NOW "Broadcast (ESP-NOW)" button | `vm.espnowSend()` | |

## Utilities (`UtilitiesScreen`, tab 24) - offline phone tools

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | Password "Length" field | Set length | |
| [ ] | Password switches (Lowercase / Uppercase / Digits / Symbols) | Toggle character sets | Four switches |
| [ ] | Password "Generate" button | `PhoneUtils.generatePassword()` | |
| [ ] | Password "Copy" button | Copy the password | Shown when a result exists |
| [ ] | Hash "Text to hash" field | Set input | |
| [ ] | Hash "Copy" buttons (MD5 / SHA-1 / SHA-256) | Copy each hash | Three buttons, shown when input non-empty |
| [ ] | Base64 "Text" field | Set input | |
| [ ] | Base64 "Encode" button | `PhoneUtils.base64Encode()` | |
| [ ] | Base64 "Decode" button | `PhoneUtils.base64Decode()` | |
| [ ] | Base64 "Copy" button | Copy the result | Shown when output exists |
| [ ] | MAC vendor "MAC / BSSID" field | Set the MAC | |
| [ ] | MAC vendor "Look up" button | OUI lookup | |
| [ ] | Wi-Fi QR "SSID" field | Set SSID | |
| [ ] | Wi-Fi QR "Password" field | Set password | |
| [ ] | Wi-Fi QR auth selector (WPA / WEP / Open) | Set auth type | |
| [ ] | Wi-Fi QR "Generate QR" button | Build the Wi-Fi QR | |
| [ ] | Wi-Fi QR "Copy" button | Copy the Wi-Fi string | Shown when a QR exists |

---

# Theme (reached via the home "Theme" tile)

## Theme & LED (`ThemeScreen` / `ThemeLedCard`, tab 36)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | Theme color swatches | `vm.setDeviceTheme()` | Repeated per swatch (`THEME_SWATCHES`) |
| [ ] | LED color swatches | `vm.setDeviceLedColor()` | Repeated per swatch |
| [ ] | "LED brightness: N%" slider | `vm.setLedBright()` | |
| [ ] | "LED animation" dropdown button + items | `vm.setLedEffect()` | One item per `LED_EFFECTS` entry |
| [ ] | "Save to device" button | `vm.saveDeviceConfig()` | |

---

## Coverage summary

- **Screens / destinations catalogued: 51** - 50 tool tiles across 11 home categories, plus
  the Theme screen (reached from the home grid) - plus the always-on app chrome (top status
  bar, bottom navigation, home dashboard, Support card, Command log dialog, Help dialog).
- **Interactive controls catalogued: ~480** (counting each distinct control once; groups of
  near-identical repeated controls - per-AP action buttons, per-wordlist rows, BLE platform
  spam cards, on-screen remote keys, DTMF/composer keypads, color swatches, saved-item rows -
  are listed once with a "repeated per ..." note, so the live total is higher at runtime).
- The three Spam tiles share one `SpamScreen` composable (filtered BLE / Wi-Fi / NFC); several
  helper cards (`ThemeLedCard`, `BadBleSection`, `NfcNdefPushCard`, `BeaconSpamCard`,
  `ScopePanel`) appear on more than one screen and are cross-referenced where reused.
