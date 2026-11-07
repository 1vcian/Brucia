# Work queue — single operational ledger

Updated 2026-11-07. `VERSION` is 0.1.8; local HEAD is release commit `43f2972d`.
F32 LoRa-teardown and F34 Remote-watchdog fixes were committed in `85cf0d30`;
store branding followed in `a21a3965`. GitHub v0.1.8 is published with APK and
T-Embed firmware assets (API verified 2026-11-07). This does not close physical acceptance. The
d2e24911 firmware was reflashed successfully on the T-Embed (`/dev/cu.usbmodem2101`,
MAC 8c:bf:ea:8a:a4:78, hash verified, boot to main menu; full log in
hw-acceptance-reports/). Phone/device acceptance of F32/F34 is NOT RUN; v0.1.8 flash is NOT RUN.
F23 under actual radio-EVENT traffic remains open.
See [TESTS.md](../../../TESTS.md) for evidence and limits.
Independent review 2026-11-07 accepts the two software corrections: 46 targeted
tests rerun successfully, plus the held-dispatcher LoRa teardown probe. Upload log
and artifact hashes verified; no new hardware acceptance claimed. RemoteRun files
are committed; phone checks remain open despite publication.

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
S02 packets remain blocked by their missing prerequisites; S07 is done (2026-11-05).
W06 and W07 are done and wired. W08 (phone-only pilot), W09 (device-backed pilot, iButton) and W10 (firmware adapter, iButton) are done; W11 runs in batches, one at a time after review.
Release correctness and physical acceptance gates still apply before publication.

## Functional defects and acceptance follow-up — 2026-11-05

User requested investigation and a functional test plan after batch 14; this is
not authorization to declare every existing feature accepted from aggregate test
counts. Full plan: [APP_CONTROLS_CHECKLIST.md](../../../docs/APP_CONTROLS_CHECKLIST.md#functional-acceptance-priorities--2026-11-05),
F01–F20. Diagnostic evidence/limits: [TESTS.md](../../../TESTS.md#functional-contract-investigation--2026-11-05-after-batch-14).
No production change or on-device reproduction was made in this investigation.

| Item | Priority / state | Finding and next acceptance |
|---|---|---|
| F01–F02: De Bruijn | P1 FIXED (sw), reboot trace OPEN | Batch 16 (DEBRUIJN). Reproduced on the base natively (`tools/test_firmware_debruijn.py`, independent De Bruijn generator mirroring the app): order 8/10/12 hex keys are 64/256/1,024 chars and `std::stoull(key, …, 16)` throws `std::out_of_range` for all three, before RF init. Confirmed the path is Bruce CLI, not companion: `rfTxCallback` lives in `src/core/serial_commands/rf_commands.cpp` (exceptions enabled; only `src/companion/**` is `-fno-exceptions`), and `src/companion/commands.cpp` has no try/catch around `serialCli.parse`, so the throw reaches `std::terminate` and aborts — consistent with the reported reboot (an on-device panic trace is still the only missing tie). Fix: firmware parses `subghz tx`/`subghz txp` keys with `rfhex::parseKey64` (`src/modules/rf/rf_hexkey.h`) — rejects empty/non-hex/over-16-hex-digit keys with a clear error, no throw, state preserved, next command accepted; transmit capability unchanged (one 64-bit fixed code, nothing larger on air). App: custom order bounded to 1..22 (was 4..24, which threw at 23/24 in the generator), presets drop 24, and `transmitDeBruijn` refuses with a reason and sends nothing instead of dispatching an uncarriable key + false "Transmitting" toast (the `subghz tx` single-code path cannot carry any De Bruijn sweep — no silent truncation, no larger frame). Tests: host-native parser incl. the exact app-generated De Bruijn keys and a mutation check on the length guard; app JVM tests for the UI bounds and the rejection message; protocol e2e + `mock_device.py` reject an over-long key with ok:False and confirm the device stays responsive. Open: physical panic/reset trace on hardware (do-not-transmit scope; see TESTS.md rejection-only check). Batch 18 (DEBRUIJNTX): a REAL sweep path now exists app-side, no firmware change. The single-code `subghz tx` path is still uncarriable and still refused, but the device's existing raw primitive `subghz_tx_raw <freqKHz> <te> <csv>` (src/companion/subghz.cpp) is driven by a new pure planner `subghz/DeBruijnTx.kt` (expands `DeBruijn.sequence(n)` to signed edge durations in the RAW convention - positive us = HIGH, negative = LOW, sequence order, per rf_encoder.cpp `rf_tx_durations`; linearizes the cyclic sequence and splits into overlapping n-1-bit bursts under the frame budget, so every n-bit window is sent intact) and a new `subghz/DeBruijnTxService.kt` streams the bursts sequentially (honest progress/summary, stops on Stop/disconnect). Orders capped at 16 (`MAX_TX_ORDER`); larger orders refused as impractical, never truncated. Tests: `DeBruijnTxTest` (coverage/overlap/budget/hand-checked sign vector/rejection/determinism + overlap-removal mutation) and `DeBruijnTxServiceTest` (exact sequence, Stop/disconnect mid-stream, nothing sent when rejected/offline). Open: hardware check against an owned receiver is the only proof it opens anything (soak running; see the De Bruijn owner check in docs/audit/radio.md). |
| F03: legacy numeric parsers | P0 FIXED (sw), build + hardware OPEN | Throwing conversions also occur in RF named/key commands, tone/beep and screen color, reached through the CLI fallback. App side (COV, 2026-11-05): `.sub` Transmit no longer sends a multi-word name, non-hex/over-64-bit key or non-uint32 frequency to `subghz txp`. Batch 17 (RADIO), software only: every `std::stoul` in `rf_commands.cpp` (`subghz tx`, `txp`, `keeloqtx`) is replaced by the non-throwing whole-token parser `clinum::parseU32/parseHexU32` (`src/core/serial_commands/cli_number.h`); malformed/out-of-range numbers print a reason naming the argument and return false before `initRfModule`. Bounds (rejection only): frequency uint32 (unchanged), bits 1..64, te 0..65535, count/repeat 0..1000, KeeLoq button 0..15 / serial 28-bit / counter 16-bit; `RfSend` gets the same Bits/Pulse/Repeat bounds and `rf_encode_protocol` refuses bits > 64 for every caller (a `.sub` `Bit: 2000000000` sized `reserve()` -> `std::length_error` abort, also from `tx_from_file`); `txp`/`keeloqtx` return the TX result. App `SubFile.transmitCommand` applies the same bounds. Evidence: `tools/test_firmware_cli_number.py` (11, fails 6/9 on the base rf_commands.cpp; driver shows the legacy `std::stoul` throws), e2e F03 rejections, `SubFileTest`. Batch 18 (STOPS), software only: `tone`/`beep` (0..20000 Hz, 0..10000 ms; the defaults "500UL", which only stoul's prefix parse accepted, are now "500") and `screen color hex` (24-bit) / `rgb` (0..255 whole token, was atoi) parse through `clinum` before any side effect. No `std::sto*` is left in `src/core/serial_commands/` or `src/companion/` (static check). Evidence: `tools/test_firmware_cli_number.py` (`SoundScreenCliCallbacks`, `NoThrowingConversionInCliOrCompanion`; the contract lists 9 violations on the base sources). Open: firmware build + the rejection-only hardware checks in docs/audit/radio.md (H1, H2, H19). |
| F04: ARP CLI ownership | P0 OPEN | CLI `arp` invokes interactive ARPScanner; its setup has three early returns after LOCK_TCPIP_CORE without unlock. First fault-inject the error paths and guarantee balanced ownership plus headless rejection. Actual deadlock depends on target lwIP configuration; not claimed as reproduced. Batch 15 (FWMENUS), software only: the pinned sdkconfig has CONFIG_LWIP_TCPIP_CORE_LOCKING=1, so the leak is real when hit; the three early returns now release the lock (static check in test_companion_prompts.py, no fault injection: lwIP is not host-built), and `arp` is refused in companion mode (routed CMD and serial.cmd()) with a reason in ACK.d.out. Open: on-device fault injection (no IP / no netif) and the `displayError(..., true)` key wait still inside the locked section outside companion mode. |
| F05–F08: BLE visibility/results/ownership/Stop | P1 OPEN | Owner sees no Mac effect. Mac card routes to ios only; remote popup support unknown. Host failure stub shows bounded wrapper returns normally despite every advertising start failing; dispatcher then reports success. 8 s request with 100 copies lasts 9,205 virtual ms (driver timing not measured). Review error reporting, single BLE owner and post-deinit scan/HID state; use a benign advertiser to separate observed packets from OS UI behavior. No payload/rate improvements or new platform claims. Batch 16 (BLEADV), software only: the five bounded advertisers now return bool and check every NimBLE call (init, TX power, getAdvertising, iBeacon MAC set, each setAdvertisementData/start/stop); the dispatcher ACKs only on success, else ERR 500. `bleAdvertiserConflict()` refuses ble_spam/ble_ibeacon/ble_fastpair_spam/ble_spam_platform with ERR 409 while the ble.scan stream (subBle) or the BLE HID presenter (g_companionHid) owns the stack, instead of deinit-ing it and reporting success; ble_hid_begin refuses while a scan runs (its end() deinits the stack), BadBLE/media refuse while a HID session is active. App texts (Spam BLE descriptor, native BLE cards, Spam hub tiles, iBeacon) describe the advertisement broadcast and state the receiver reaction is unverified (no more "popups to nearby devices"/"will see it" claims); the burst loop surfaces the 409/500 as the tool status. Evidence: `tools/test_firmware_ble_adv.py` 11, app `BleSpamTextTest`. Open: the on-device owner checks in TESTS.md "Batch 16 (BLEADV)" (scan-active busy, start/stop leaves scan intact, HID conflict) and any receiver-reaction observation (recorded as observed, not promised). Batch 17 (RADIO), software only: the reverse direction is closed too - `ble.scan` ON refuses with 409 while a BLE HID session owns the stack (its later `bleStop` deinit freed the HID objects) and 503 on NimBLE init failure (`subBleOn` follows the result); `ble_media` refuses with 409 while the scan runs (its `deinit(true)` freed the scanner `bleService` still used); the app ends the scan before GATT/BadBLE/media and stops the spam loop on leave. Evidence: `tools/test_radio_guards.py`, `RadioAuditTextTest`, `StreamLeaveHooksTest`. Hardware: docs/audit/radio.md H9-H10. Batch 18 (STOPS, software only): `ble_hid_begin` answers 503/500 when NimBLE init fails or the HID advertiser is not running (it said "advertising" regardless); `ble_ibeacon` refuses a malformed UUID with ERR 400 (app checks first); `ble_ibeacon_stop` no longer starts a broadcast (see F21). |
| F09–F10: headless recovery | P1 OPEN | Script timeout can leave interpreter busy; IR headless setup can still call pin chooser. These are reachable-function gaps beyond the direct prompt-call checks. Require bounded, visible failure followed by a successful benign command/script; no menu waiting on the device. Batch 15 (FWMENUS), software only: both fixed and the CMD path is now scanned through module code (see "Batch 15 (FWMENUS)" under the prompt notes below); open: the hardware checks in TESTS.md "Batch 15 (FWMENUS)". Batch 17 (RADIO): the IR TX side - companion `ir_tvbgone`/`ir_tx_raw`/`ir_jammer` and the CLI `ir tx*`/`IRSend` in companion mode refuse "IR TX pin not configured" when the pin is outside IR_TX_PINS, and `checkIrTxPin()` never opens the pin menu in companion mode (static checks in `tools/test_radio_guards.py`; hardware docs/audit/radio.md H12). Batch 18 (STOPS): `ir_tvbgone`/`ir_jammer` answer ok:false when 0 codes/packets went out, and `ir_jammer_stop` no longer starts a jam window (see F21). |
| F11–F20: functional acceptance | P1/P2 OPEN | Exercise mode transitions, actual audio/capture/network effects, missing modules, storage failures, USB/background/rotation, update recovery, exports and repeated cycles on supported boards. Keep FAIL/BLOCKED/NOT RUN distinct from PASS; general link/build/unit suites are insufficient evidence. |
| F21: radio audit follow-ups (batch 17) | P1/P2 OPEN | Audit of RF, FM, LoRa, NRF24, ESP-NOW, NFC, Spam NFC, IR, IR Remote, BLE, BLE Remote, BadUSB, Spam BLE in [docs/audit/radio.md](../../../docs/audit/radio.md). Fixed in batch 17 (software only; firmware needs a build): F03 RF parsers, CC1101 single owner (409), spectrum kHz labels, replay only when `rf tx` reproduces the capture, NFC "Read NDEF" that wrote the tag (now confirmed write), NDEF push length refusal, bounded `nfc_emv_read`, companion 60 s idle exit measured after handling, LoRa receive Stop, BLE scan/HID/media ownership, BadUSB CLI refused in companion mode, IR TX pin menu / unsupported protocol / AC-state stack overflow, ESP-NOW radio ownership, honest texts. Batch 18 (STOPS), software only, firmware needs a build: (1) no `*_stop` or made-up `<verb>_<suffix>` reaches another command's handler. Every companion verb is matched exactly; the old bare prefixes made `ir_jammer_stop`, `ble_ibeacon_stop` and `jam_detect_stop` start their operation, and `speaker_volume_stop` set 100 %. Idle-stop guards answer "not running". Evidence: `tools/test_companion_dispatch_stops.py`, full + LITE chains, 59 problems per build on base, 9 mutations. (2) `subghz_tx_raw`/`subghz_replay` report the TX result (`sendRfCommand`/`rf_raw_emit` return it). (3) TV-B-Gone/`ir_jammer` give ok:false with 0 codes. (4) iBeacon UUID validated (firmware ERR 400 + app). (5) `ble_hid_begin` checks init/advertising. (6) The NDEF contact record is pre-checked against 126 bytes (firmware + app). (7) The Wi-Fi beacon spam loop stops on leaving the Spam hub. (8) The CC1101 409 classifier is case-insensitive and checks every newline/`;;` segment. Tests: `tools/test_radio_guards.py` (`Batch18Headers`, `Batch18Firmware`, classifier mutations, mock), JVM `IbeaconUuidTest`, `NfcToolsServiceTest`, `TvBGoneServiceTest`, `StreamLeaveHooksTest`. Open (software): IR/RF one-shot sends are still fire-and-forget (reply only in the console); the Wi-Fi beacon card on the Wardrive tab does not stop on leave (not rechecked). Closed in software 2026-11-06: NRF24 stop polling and scan/sniff overlap, old saved RF replay (A2/A4). FM ownership A3 remains PARTIAL after the follow-up review below. Hardware: H1-H19 in the audit file. |
| F22: Wi-Fi audit batch 17 | P2 DONE (sw) | Full trace of the 12 Wi-Fi features in `docs/audit/wifi.md`. All four follow-up items closed (2026-11-06): (a) FIXED firmware — idle `cmdStr == "karma_stop"` / `"responder_stop"` guards added before the `startsWith` handlers in `src/companion/wifi.cpp` (answer "not running", mirror `brucegotchi_stop`); static host test `tools/test_companion_wifi_dispatch.py` fails if any app stop verb (CommandCapabilities.stops) can fall into a start handler (mutation-checked). (b) FIXED app — `wifi/BeaconSpam.kt` + a `BurstLoop` progress hook show the device's real SSID count from each `wifi_beacon_spam` reply, requested count as fallback; `BeaconSpamTest`. (c) FIXED app — Evil-Portal deauth sub-text reworded (no "join this one" claim); `WifiSpamTextTest`. (d) FIXED firmware — `monStart` clamps the lock channel to 1..13 (out-of-range -> hop, no exception); same static test. Needs a firmware build for (a) and (d) (`src/companion/wifi.cpp`); host/JVM tested otherwise. No throwing parsers or misleading ACKs on the native Wi-Fi path (F03 does not reach `wifi_*`/`evil_portal_*`/`karma`/`brucegotchi`/`wifi_beacon_spam`). Batch 18: the stop-dispatch check now covers every dispatcher (`tools/test_companion_dispatch_stops.py`). The Wi-Fi bare prefixes are matched exactly, and `jam_detect_stop` gets an idle guard (`src/companion/wifi.cpp`, needs a build). Remaining: only the hardware/RF checks listed in `docs/audit/wifi.md` (S05). |
| F23: companion frame partial writes + interleave guard | P1 FIXED (sw), firmware FLASHED + soak-verified clean, re-soak UNDER radio traffic OPEN | Hardware 2026-11-06: flashed this branch to the T-Embed ("Hash of data verified"); live `diag` carries the new `tx_torn` key (fix is running). `hw_acceptance --soak 15` (wifi.probe,wifi.sta,subghz.raw): PASS, 30 intervals, 0 findings, `tx_torn` 0->0, counters exact (tx 116->1738, rx 103->1515), 0 rx-rejects, no noise, no reboot, heap flat. BUT stream_events were 0 on all three (RF-quiet; only those three are allowed receive-only, no TX), so it did not stress emission under radio-EVENT load - that re-soak is the remaining gate (TESTS.md). Update 2026-11-06 (point 2): `writeAll` recovery completed and `tx_torn` diagnostic added; firmware now builds (`lilygo-t-embed-cc1101` SUCCESS). Honest framing: all three soaks recorded ZERO events from the radio streams, so none exercised the link under actual radio-EVENT traffic; the final 40-min re-soak (report soak-20261106T0746Z-010c0adc-fix) is PASS but does not prove stability under radio traffic. Partial writes are the leading hypothesis consistent with the symptoms, NOT a definitively established cause (the new `tx_torn` counter will name it if it recurs). The cross-task mutex is a SAFEGUARD for future multi-task emitters (every emitter runs on the loop task today), not a demonstrated cause. Current `writeAll` behavior: idle timer reset on progress (a delayed/partial write that resumes completes), bounded by `COMPANION_TX_IDLE_MS` (1500 ms, host accepts nothing) and an absolute per-frame ceiling `COMPANION_TX_FRAME_MS` (4000 ms, trickling host); a torn frame sets `g_txLinkBroken` -> `writeFrame` stops emitting and the companion loop drops the link (`companionTxLinkBroken`) so the app reconnects clean, and is counted as `tx_torn` not `tx_frames`. Time bound now accounts for the DRIVER'S blocking CORRECTLY (review 2026-11-06): a single `HWCDC::write` can run far past `20*tx_timeout` because `consec_timeouts` RESETS on every accepted chunk (HWCDC.cpp:606) - measured ~6 s for a full frame. Fix: `writeAll` caps each `Serial.write` to `COMPANION_TX_CHUNK` (64 B <= one 256-B ring chunk), so one call covers a single chunk (no mid-call reset) and blocks at most `20*COMPANION_TX_WRITE_MS`; combined with `companionApplyTxTimeout(true)` (tx_timeout 10 ms, restored on exit) and the deadline checked BEFORE each write, the worst-case torn-frame stall is ~`COMPANION_TX_FRAME_MS + 20*COMPANION_TX_WRITE_MS` (~4.2 s). `tools/test_firmware_frame_lock.py` now runs a FAITHFUL HWCDC model (chunked, consec-reset): with the cap each single write is bounded and the frame tears at the ceiling; the mutation that removes the cap lets ONE write exceed the whole ceiling (the ~6 s "counted as succeeded"). The ACTUAL app recovery for THIS fault (device leaves companion, USB stays OPEN) is proven in `CommandSessionTest.truncatedFrameThenCliEchoFailsPendingFastThenSameSessionRecovers` WITHOUT an artificial `close()`, STARTING from a truncated frame (valid header + incomplete payload) followed by the CLI echo. Review follow-up 2026-11-06: the first detector only fired during resync, so a parked truncated frame swallowed the echo and `takeCliLeftCompanion()` stayed false; now `CompanionClient` runs a rolling CLI-banner match over NON-frame bytes only (noise, a parse-failed frame, and a stall-discarded partial frame), so it catches a truncated-frame-then-echo, a fragmented banner and a noise-prefixed banner, and does NOT false-alarm on "COMMAND:" inside a valid JSON payload. Second review follow-up 2026-11-06: the stall was on TOTAL duration, which would discard (and scan) a large valid frame still arriving after 2 s - a false alarm; it is now on INACTIVITY (time since the last byte), matching the firmware's `rxLastByte` rule, and the receiver limits match the firmware (`MAX_FRAME`=8192). Tests (`CompanionClientCliTest`) include a valid JSON payload containing "COMMAND:" delivered in fragments spanning > 2 s but always advancing - decoded, never flagged - and a never-completing truncated frame recovered via the inactivity stall within a defined time. The read loop then calls `CommandSession.failPending` so a pending command fails at once instead of waiting up to 650 s, the session stays usable and re-enters companion. Firmware FLASHED + soak-verified (`tx_torn` 0, counters exact), but still ZERO radio events (RF-quiet), so the under-radio-traffic soak remains the open gate. `tx_torn` added to `test_firmware_diag.py`/`mock_device.py`/`hw_acceptance.py` (soak finding) and `proto/PROTOCOL.md`. Remaining gate: a re-soak UNDER real radio-EVENT load (see TESTS.md checkbox). Original analysis follows. Two root causes from the 2026-11-06 hardware soak (TESTS.md "Hardware soak 2026-11-06 - FINDING"). (1) PARTIAL WRITES (leading hypothesis; corrupts even with a single writer): `writeFrame()` ignored the return of both `Serial.write` calls. `Serial.write(buf,len)` is not guaranteed to accept all `len` bytes - the ESP32-S3 USB-Serial-JTAG path (HWCDC, the port `Serial` uses on the T-Embed) returns a short count under sustained host backpressure (HWCDC.cpp ~L540-623: after ~20*100ms of a stalled host it does `size = so_far; break;` and the core comment says "the caller sees a short write (Arduino's Stream::write contract)"; also returns 0 if the tx_lock is not taken within tx_timeout_ms=100), and USBCDC (TinyUSB, USBCDC.cpp L395+) likewise returns `so_far` on disconnect/timeout/zero-write; only the classic-ESP32 UART (HardwareSerial::write -> uartWriteBuf -> `uart_write_bytes`, blocks until all queued) always writes all. A short write truncates the length prefix or payload -> stray bytes + undecodable frame + tx/rx mismatch, exactly the soak symptoms. FIX: new bounded `writeAll(buf,len,deadline)` loops on the return value writing only the remainder, with a 1 ms yield between tries up to a 2 s per-frame deadline (`COMPANION_TX_FRAME_MS`) so a vanished host cannot wedge the companion loop; a frame that still cannot complete is left torn (link already dead) and is NOT counted in `tx_frames` (`if (whole) diagNoteTxFrame()`), so a torn frame no longer inflates the counter. (2) CROSS-TASK INTERLEAVE: `writeFrame()` emitted a frame's 4-byte length prefix, payload and `Serial.flush()` with no lock. Companion responses (PONG/ACK/ERR/DATA) are emitted from the companion loop task; stream EVENTs (wifi.sta/wifi.probe/subghz.raw, ble.dev, mic/fm PCM, evil.cred, lora.rx, ble.gatt) are emitted from `*Service()` on the same loop today, but the framing relied on that single-task assumption with nothing enforcing it, and the soak observed interleaving (~2 undecodable frames/run, a battery-PONG fragment inside another frame, stray bytes, a counter mismatch, a lost ping; the app resynced, no crash/leak). FIXED (sw): a recursive FreeRTOS mutex `g_frameLock` (created at static init, null-safe fallback for very early boot) now wraps the whole emission in `writeFrame()` — the length write, the payload write and the flush, and nothing else — so two tasks can never interleave. Recursive so a nested emission on the same task cannot self-deadlock. The wire format is unchanged. ISR analysis: no frame emitter runs in an ISR — the only ISR is the RMT `on_recv_done` callback (subghz.cpp) which merely `xQueueSendFromISR`s, and the esp_wifi promiscuous RX callbacks run on the Wi-Fi task but only fill ring buffers; both defer the actual frame to `*Service()` on the loop, so taking a blocking mutex in `writeFrame` is always safe. Every frame emitter goes through `sendEnvelope`/`writeFrame`; `writeFrame` holds the only `Serial.write` in `src/companion` (verified by `tools/test_firmware_frame_lock.py`). That host test compiles the real `writeFrame`/`writeAll` and proves, with a mutation for each fix: (a) two concurrent writers never interleave with the lock and do interleave once it is removed (7812 interleaved frames in the mutation); (b) a Serial sink that accepts only a few bytes per write still yields the complete, correctly-framed stream with `writeAll`, but a truncated/desynced stream once `writeAll` is reduced to the pre-fix single write. Firmware BUILDS (`lilygo-t-embed-cc1101` SUCCESS, 2026-11-06). The clean hardware re-soak UNDER real radio-EVENT traffic (`tools/hw_acceptance.py --soak` with wifi.probe,wifi.sta,subghz.raw actually emitting: 0 undecodable frames, 0 noise bytes, `tx_torn` 0, counters exact) is the remaining validation gate; the prior PASS re-soak saw zero radio-stream events. |
| F24: De Bruijn TX lifecycle (stop / mode change) | P1 FIXED (sw), hardware check OPEN | 2026-11-06 (point 1). `DeBruijnTxService` was stopped by Stop-all/disconnect (lifecycle entry `debruijn-tx`) but NOT when leaving the RF screen nor on the Remote hand-over, so an in-flight sweep kept streaming `subghz_tx_raw` bursts. Fixed: (a) `SubGhzScreen` `LeaveEffect` now calls `vm.stopDeBruijnTx()` (rotation-safe: `LeaveEffect` does not fire on config change, so a rotation keeps the sweep and the recreated screen re-binds); (b) a `RemoteHandover.Step("De Bruijn sweep", ...)` ends the sweep before `companion_exit`; (c) `stop()` now distinguishes cancelling the remaining bursts (always guaranteed) from a burst already dispatched to the device (`burstInFlight`, on air, not recallable) and shows `STOPPED_IN_FLIGHT` ("no further bursts... but the burst already sent may still be transmitting") instead of claiming the sweep stopped. No new transmit capability. Tests: `DeBruijnTxServiceTest` (in-flight stop message + nothing more sent), `RemoteHandoverTest.inFlightDeBruijnSweepIsStoppedBeforeCompanionExitAndSendsNoMoreBursts` (real `DeBruijnTxService` + `CommandSession` through `RemoteHandover`). Open: on-device confirmation that leaving RF / switching to Remote halts the sweep and the device finishes only the in-flight burst (radio.md H16 scope, do-not-transmit otherwise). |

### Cross-feature integration review — 2026-11-06

All 50 catalogued tools have screen routes; this is navigation coverage, NOT functional
acceptance. Reviewed the existing four feature audits against screen lifecycle, shared
services, mode changes and failure handling. Fresh checks: 300 Python discovery tests,
265 selected Kotlin tests, and the service probes described in TESTS.md. No device use,
firmware build/flash, production edit, commit or release in this review.

| Item | Priority / state | Finding and next acceptance |
|---|---|---|
| F25: Remote mode isolation | DONE (sw, wiring/source-checked) 2026-11-06; phone acceptance OPEN | `RemoteScreen` stops Remote in a `LeaveEffect` (actual leave, not rotation). `remoteOwnsLink` is set after the hand-over stops and a confirmed `companion_exit`, and every framed path refuses while it is set: session preflight, `directStop`, SUB supplier (`REMOTE_OWNS_LINK`). `stopRemote` clears it, drops the stale handshake and re-negotiates; Stop all ends Remote first and waits for CAPS (`remoteHandback`) before framed stops; disconnect resets both. Tests: `RemoteLinkGateTest` (source-level, VM needs Android), `StreamLeaveHooksTest`, `StopAllSequenceTest`. Still owed on phone: Remote -> Home -> Device info/Files, rapid Start/Stop, rotation, disconnect; one reader, benign info/list after the handshake. |
| F26: global Stop ordering / LoRa hook | FIXED (targeted HOST); device ordering OPEN | `d2e24911`: `StopAllSequence` now withholds remaining device stops and calls onTimeout if a blocking operation remains active at the settle deadline. The independent production-class probe confirms this branch. Local macro/burst producers are frozen before waiting. Existing successful LoRa/portal ordering and disconnect tests still pass. No full-VM/on-device ordering acceptance in this review. |
| F27: failed Stop reported as inactive | DONE (HOST) 2026-11-06 | `BleHidService.end` reports `off` only on ACK; ERR/timeout/exception show `STOP_FAILED` ("may still be advertising"). `EvilPortalService.stop` keeps `active` on failure with `STOP_FAILED`; only an ACK clears it (no link still clears: link loss ended ownership). Late replies of superseded stops are ignored. Tests: `BleHidServiceTest` (rejected, lost, retried, late), `EvilPortalServiceTest` (rejected, lost, retried, late). |
| F28: Wi-Fi scan ownership on leave | DONE (sw) 2026-11-06; phone acceptance OPEN | `session/StreamClaim` (retained across rotation): start only when off and remember it; on leave stop only an owned scan that may still run (`streamMayRun`). Applied to RF Fingerprint, RF Timeline and the Evil Portal target list (hide/select/leave). An explicit portal start still ends any scan because the portal needs the radio. Tests: `StreamClaimTest` (borrowed scan, owned scan once, Stop all/disconnect, reconnect, rejected start), `StreamLeaveHooksTest`. Composable wiring not instrumented. |
| F29: Channels connection lifecycle | DONE (sw) 2026-11-06; phone acceptance OPEN | `ChannelAnalyzerScreen` keys its start on `connected` with the same `StreamClaim`, so connect and reconnect while open start the scan; rotation re-binds without a second SUB; a prior Wardrive scan stays its owner. Tests: `StreamClaimTest`; composable wiring not instrumented. |
| F30: Files listing errors disappear | DONE (HOST) 2026-11-06 | `DeviceFileBrowser.list` never parses a failed reply: entries are cleared and `listStatus` shows `Listing failed: <reason>` (ERR, timeout, runner exception), distinct from an empty successful directory; a successful retry clears it. Tests: `DeviceFileBrowserTest`. |
| F31: export failures can escape UI callbacks | DONE (sw) 2026-11-06; phone storage-failure check OPEN | Every share/open export goes through `shareExport` (UI) and `ExportFiles.writeNamed` (internal dir fallback, any failure an `IOException`, partial file deleted, nothing shared after a failure): Wardrive CSV/KML/GPX, NFC NDEF, hc22000, RF CSV/.sub, stations, probes, monitor pcap; VM export helpers throw `IOException` instead of escaping. Retention unchanged (named files are not pruned). Tests: `LocalFilesCleanupTest` (unavailable dir, mid-write failure, non-IO failure, success). No phone with full storage exercised. |
| F32: stop ownership across runs | FIXED (HOST) 2026-11-07; phone acceptance OPEN | IR/Jam from `d2e24911`. LoRa: new `abandon()` (disconnect entry) cancels an unwritten receive-stop and lifts the fence; `stop()`/`stopReceive()` keep the explicit stop's write. Tests: `LoraServiceTest` held-link cases (queued stop never reaches replacement link B; explicit stop still written to A; fence until written/abandoned); mutation-checked. Phone: LoRa receive -> Stop all -> unplug -> reconnect, no stray `stop`; new receive works. |
| F33: IR duration parser | FIXED in source; target rejection check OPEN | `d2e24911` validates explicit arguments as decimal digits and requires 1..120 before pin/driver work; absent/trimmed-empty keeps default 30. The prior nonnumeric/trailing-junk start path is removed. `test_radio_guards` passes; numeric overflow currently relies on the installed String.toInt/atol conversion and is not independently exercised by this review. Prefer target-width boundary tests in the existing parser suite. No physical output was attempted. |
| F34: Remote entry and failed-start cleanup | FIXED (HOST) 2026-11-07; phone acceptance OPEN | `remote/RemoteRun`: one job per attempt; the mirror watchdog is its child, started only after a confirmed `companion_exit`, ended with the mirror loop; Stop/disconnect cancel both; `remoteWatchdogJob` removed. Tests: `RemoteRunTest` (failed exits + retry + Stop -> none started; one per successful run; restart during pending exit never stacks; disconnect with late ACK -> none; mirror end), `RemoteLinkGateTest` wiring; mutation-checked. Phone: rejected/lost exit, retry, Stop, disconnect; reboot re-arm in a successful session. |

Concrete regression/phone cases: [integration acceptance checklist](../../../docs/APP_CONTROLS_CHECKLIST.md#cross-feature-integration-acceptance--2026-11-06).

### Remaining work — ordered (2026-11-06 integration review)

Reviewed against code; split by what blocks each. A feature is NOT closed on test counts alone.

**New feature (2026-11-06, user-requested):** IR power spam - `ir_power_spam [secs]` blasts every
power table (NA + EU + universal parsed + raw), repeating until the window ends or Stop, with a default 30-second window. Table presence alone does not prove every table
was reached or every device type is supported. Firmware polls the link (stop ends it between codes;
idle `ir_power_spam_stop` answers "not running"); the Bruce NRF24-style abort hook was added to the
TV-B-Gone batch helpers. App `IrPowerSpamService` (Start/Stop in the IR actions card, leave-stops,
joins Stop all's blocking phase). Builds: T-Embed CC1101 flashed. Tests: `IrPowerSpamServiceTest`,
`test_radio_guards.py` (poll + all tables), `test_companion_dispatch_stops.py` (idle guard), mock
e2e idle stop. Device check owed: the actual blast transmits IR, so it is not run by the passive
acceptance runner; a human must confirm it switches a permitted display.

**A. Software defects still to fix (host/JVM-testable, no device needed):**
F32 LoRa disconnect cleanup and F34 Remote failed-start cleanup are fixed with host
regressions (2026-11-07). v0.1.8 was published before phone acceptance; these and
other open device checks remain unverified. Host results do not close them.

- **P1 F35 OPEN — App Store installer trust and incomplete-file handling.**
  `src/core/settings.cpp:1730` calls `HTTPClient.begin(url)` without a CA; the installed
  Arduino core delegates HTTPS to a null-CA transport and calls `setInsecure()`.
  Thus bootstrap HTTPS does not authenticate the server. The installer also writes
  directly to the final JS path, ignores the `file.print` result, and reports success;
  `appStoreInstalled()` checks existence only. `getString()` ignores its underlying
  transfer error, so interrupted transfers/storage-full can leave an incomplete file
  advertised as installed. These pre-existing integration defects are not fixed by
  the new notice. Next: authenticated bootstrap and an explicit integrity policy;
  bounded, checked download to staging with failure cleanup/preservation of any
  previous installation. Test invalid certificates, truncated/empty/oversized
  responses and short/full-storage writes; no success or new installed marker on failure.
  Downstream plain-HTTP catalog/apps remain a separate trust problem in the third-party
  script. Source review only; no script executed or hardware/network fault injected.
  **FIXED (HOST) 2026-11-07, uncommitted; device install NOT RUN.** `installAppStoreJS`
  now uses `NetworkClientSecure.setCACert(BRUCIA_APP_STORE_ROOTS)` (`src/core/app_store_roots.h`:
  GTS R1-R4, GlobalSign Root CA, ISRG X1/X2, SSL.com ECC/RSA; the host is behind Cloudflare,
  which rotates between these CAs) and `http.begin(tls, url)`; no `setInsecure()` call remains.
  Integrity policy: authenticated TLS + exact Content-Length (HTTP/1.0, no chunking; missing
  length refused), 64 KiB bound, HTML/blank body refused. Portable `app_store::install` stages
  to `<dest>.part`, checks every write, flush and the re-read size, then replaces via
  `<dest>.bak` with rollback; a leftover backup is restored first. Failure never shows success
  and keeps an existing install. Tests: `tools/test_app_store_install.py` (host-compiled
  core: interrupted/timeout/unsized/empty/blank/oversized/HTML, full storage, flush/size/rename
  failures, with and without a previous install; mutation-checked), adapter source checks,
  pinned-root fingerprints, untrusted local TLS server rejected; live (`BRUCIA_NET_TESTS=1`)
  store host accepted and badssl self-signed/expired/wrong-host/untrusted-root rejected.
  These TLS checks use Python ssl, not mbedtls. Owed on device: install over Wi-Fi succeeds,
  store opens; a pulled Wi-Fi/unplug mid-download leaves no new install. Downstream
  plain-HTTP catalog/apps still OPEN (separate item).

1. ~~P1 DT01~~ DONE 2026-11-06: decision taken (authorized) to keep the recovered key unmasked in reports/exports; comments + tests pin it. See the DT01 row above.
2. ~~P1 NRF24 app-stop responsiveness~~ DONE (sw) 2026-11-06: scan/sniff loops poll `companionPollStop()`; jammer/MouseJack get an optional abort callback in the Bruce modules (`nrf_jammer_headless`, `nrf_mousejack_headless`; a stopped MouseJack never injects); ACK text says `(stopped)`; idle `nrf24_stop` answers "not running". App: NRF24 `BurstLoop`s send `nrf24_stop` on Stop and wait for the burst reply (`draining`), scan/sniff never run two windows at once, Stop all ends them in its first phase. Tests: `test_radio_guards.py` (poll + mock idle stop), `test_companion_dispatch_stops.py`, `BurstLoopTest`. Firmware builds T-Embed CC1101 + Cardputer. Open: device check (C8). Jammer/MouseJack can still overlap a scan/sniff (the second gets 409).
3. ~~P2 F21 FM I2S single owner~~ DONE (sw) 2026-11-06: `speaker_song`, `speaker_rtttl` and `speaker_dtmf` now call `fmAudioRelease(false)` before acquiring the speaker, and `speakerOutputRelease` stops an async song (`isAudioPlaybackTaskActive()` -> `stopAudioPlayback()`), not only a tracked file. Both directions covered. Test: `test_radio_guards.py::test_fm_i2s_single_owner` (song/rtttl/dtmf release + async-song stop). Physical FM check still pending (no Si4713 here); firmware builds T-Embed + Cardputer.
4. ~~P2 F21 saved-RF replay payload~~ DONE (sw) 2026-11-06: read-time guard, no migration (no user data rewritten): `CliParse.rfPayloadReplayable(payload, protocol)` requires a protocol `rf tx` reproduces (or none recorded) and a key that fits 24 bits. Saved RF rows hide Replay, the VM refuses, Geo pinning lists only replayable RF items and `GeoReplayService` refuses an `rf tx` pin with a wider key. Tests: `LogicTest`, `GeoReplayServiceTest` (fixtures corrected to the real `rf tx <key> <freq> <te> <count>` order).
5. ~~P2 F04 non-companion ARP~~ DONE (sw, static) 2026-11-06: the "Ethernet not ready" path releases `LOCK_TCPIP_CORE` before the `displayError(..., true)` key wait; `test_companion_prompts.py` checks every early return releases before any key wait (mutation verified). The scan loop itself still holds the lock while it probes (upstream design). Open: on-device fault injection.
6. ~~P3 F21 one-shot IR/RF sends~~ DECIDED 2026-11-06: keep the console result (no per-tool awaited result). The result already appears in the console, De Bruijn streams per-burst ACKs, and surfacing an awaited result at every fire-and-forget `dispatchCmd` call site is a broad change for low value with real regression risk. Reconsider only if a specific tool needs confirmation (e.g. a single `rf tx` with a visible ACK). The dedicated services (TV-B-Gone, IR power spam, jam detect, LoRa) already surface their result in their screen.

**B. Missing automated checks (can be added now, no device):**
1. ~~A host test for NRF24 app-stop responsiveness~~ DONE 2026-11-06 (static + mock + `BurstLoopTest`).
2. ~~An FM single-owner service test~~ DONE 2026-11-06 (static firmware check; the firmware is not host-buildable).
3. ~~A saved-RF migration/relabel test~~ DONE 2026-11-06 (read-time guard tests).
4. ~~F25-F31 integration regressions~~ DONE 2026-11-06 (host/source level): `StopAllSequenceTest`, `StreamClaimTest`, `RemoteLinkGateTest`, plus additions to the BLE HID, Evil Portal, file browser, cleanup and leave-hook tests. Composable wiring and the full VM remain uninstrumented.

**C. Checks needing a phone or peripherals (hardware/RF; this phase is local-only):**
1. P1 F23 gate: re-soak UNDER real radio-EVENT traffic (APs/stations/RF in range) with wifi.probe/wifi.sta/subghz.raw actually emitting; expect 0 findings, `tx_torn` 0, counters exact. DONE in part 2026-11-06: flashed this branch (incl. the chunk-cap fix) + two soaks (15 min, then 10 min after the fix) both PASS, `tx_torn` 0, clean (reports under `hw-acceptance-reports/soak-20261106T13{23}Z-*` and `...T1422Z-354a32db-f23fix/`), but stream_events were 0 both times (RF-quiet), so the under-traffic part is still owed (`hw_acceptance.py` allows only those three receive-only streams; `wifi.monitor` is rejected; no TX permitted).
2. F24 / radio.md H16: leaving RF and switching to Remote halts an in-flight De Bruijn sweep; the device finishes only the burst already sent; De Bruijn sweep against an owned receiver.
3. radio.md H1-H19: RF/FM/LoRa/NRF24/ESP-NOW/NFC/IR/BLE rejection-only + single-owner (409) + IR-TX-pin + LoRa/BLE ownership on device.
4. F05-F08 BLE: receiver-reaction observations (recorded as observed, not promised); single owner / post-deinit scan/HID state.
5. F09-F10: headless script-timeout and IR setup recovery on device (bounded visible failure then a successful benign command).
8. A2-A5 device checks (needs a flash of this tree): NRF24 Stop ends scan/sniff/jammer/MouseJack within ~1 s with `(stopped)` (needs an NRF24 module); a SUB during `jam_detect` gets 409 promptly; FM audio vs speaker/mic owner on an FM-equipped board; ARP "Ethernet not ready" key wait with Ethernet unplugged.
7. F25-F31 phone acceptance per [the integration checklist](../../../docs/APP_CONTROLS_CHECKLIST.md#cross-feature-integration-acceptance--2026-11-06): Remote leave/Stop all then benign info/list; Stop all during LoRa receive with the portal active (both end, portal ACKed); BLE HID/portal failed stop shown as failed; Wardrive kept across Fingerprint/Timeline/Portal/Channels; Channels connect/reconnect; Files on missing SD; exports with storage unavailable.
6. F11-F20 functional acceptance: mode transitions, real audio/capture/network effects, missing modules, storage failures, USB/background/rotation, update recovery, exports, repeated cycles. Keep FAIL/BLOCKED/NOT RUN distinct from PASS.
7. DT03 iButton verified write (1-Wire probe, G1/G2); DT04 flash-abort reset (U2); DT05 CLI 3500-byte truncation marker (C1); DT08 update recovery (U5).
8. B17-01 Wi-Fi LAN selection (Wi-Fi+data, Wi-Fi+VPN, mobile-only, /23+); B17-02 >4 MiB MITM streaming through an owned target; B17-04 geo/replay against an owned gate/remote.

### Batch 17 audit — Intercept & Audio (2026-11-06)

Full trace and findings: [docs/audit/intercept-audio.md](../../../docs/audit/intercept-audio.md). Fixed in the audit:
VulnRules regreSSHion range (`VulnRulesTest`) and RTTTL parser divide-by-zero (`RtttlTest`). The five
open items were fixed in the coordinator follow-up (software only, not hardware-verified):

| Item | Priority / state | Finding and next acceptance |
|---|---|---|
| B17-01: LAN subnet from wrong interface | P2 FIXED (sw), HW check OPEN | Was: first site-local IPv4 of any interface, always /24. Now `net/WifiLan.kt` (`WifiLanSelector`): ConnectivityManager active network only; refused with an on-screen reason when it is not Wi-Fi, is a VPN or has no IPv4; real LinkAddress prefix; `scanPlan` scans all hosts of a /24../31, bounds larger subnets to the phone's /24 with a note, /32 nothing. Network Scan and MITM (start refused off Wi-Fi; shown IP from the Wi-Fi). Tests `WifiLanSelectorTest` 10, `NetworkScanSessionTest`. Open: on-device check with Wi-Fi+mobile data, Wi-Fi+VPN, mobile only, and a /23+ Wi-Fi. |
| B17-02: MITM proxy 4 MiB body cap | P2 FIXED (sw), HW check OPEN | Bodies now stream through at any size (`HttpWire` head/body split + `mitm/HttpRelay.kt`): Content-Length kept, chunked re-chunked for HTTP/1.1 clients, close-delimited for HTTP/1.0; the log keeps a 4 KiB prefix with `reqTruncated`/`respTruncated` and a truncation marker. Streamed bodies are bounded by per-read socket timeouts, not the 120 s head budget. Tests `MitmStreamingTest` 5 (loopback only, 20 MiB generated in memory, Content-Length + chunked, both directions); `HttpWireTest` unchanged and green. Open: a >4 MiB download through the proxy from an owned target. |
| B17-03: MITM HEAD/304 Content-Length rewrite | P3 FIXED (sw) | HEAD/204/304 heads are relayed exactly as the server sent them (no body). Test `MitmStreamingTest.headAnd304KeepTheServersContentLengthAndTheConnectionStaysUsable`. |
| B17-04: geo/replay optimistic status | P3 FIXED (sw), HW check OPEN | `wardrive/GeoReplayService.kt` on a single-owner `DeviceOperation`: "Replaying …", then "Replayed …" only on the device ACK, else "Replay of … failed: <reason>". Test `GeoReplayServiceTest` 4. `replaySaved` (RF/IR saved lists) still fire-and-forget. Open: replay an owned gate/remote and see the device's answer. |
| B17-05: BugSweep stream stop asymmetry | P3 FIXED (sw) | `SweepStreams` (BugSweepScreen.kt) starts only streams not already on (`streamMayRun`) and stops only those the sweep started, for Wi-Fi, BLE and spectrum. Test `SweepStreamsTest` 3. |

## Device and tools audit open items — 2026-11-06 (agent17/DEVICE)

Full audit, fixes and hardware checks: [docs/audit/device-tools.md](../../../docs/audit/device-tools.md).
14 fixes landed in software (Ethernet status/config, Files path quoting/truncation/name safety,
backup exclusions, verified iButton write (firmware build needed), clock sync local time, flash
abort reset, macro/PCAP/clipboard); the follow-up the same day closed DT02 and DT05-DT09 in
software. Status:

| Item | Priority / state | Finding and next action |
|---|---|---|
| DT01 | P1 DONE (decision 2026-11-06) | DECISION (authorized by the owner): KEEP the recovered Wi-Fi key in plaintext in the security report (`ReportBuilder`), session JSON (`SessionExport`) and session report (`SessionReport`). Rationale: these are authorized-security-assessment artifacts and the operator needs the actual key; no masking. Each emission point carries a `DT01 ... do not mask` comment so the decision is not silently reverted. Tests pin it: `SessionExportTest` (crackResult verbatim), `ReportTest` (key in markdown), `SessionReportTest.recoveredWifiKeyIsShownInPlaintext` (plaintext + not masked + the "rotate it" advisory). No hardware needed. |
| DT02 | P1 DONE (sw) | 2026-11-06 follow-up: `remote/RemoteHandover` ends macro, script (`script_stop` written first), file transfers (upload abort acknowledged) and burst loops before `companion_exit`. `RemoteHandoverTest` 3. Hardware check R1 in docs/audit/device-tools.md. |
| DT03 | P2 OPEN | Firmware iButton verified write needs a T-Embed build and checks G1/G2 (RW1990 blank, read-only key, no key). |
| DT04 | P2 OPEN | Flash-abort reset (`EspFlasher.resetToApp` after a pre-write failure) needs check U2 on the S3 USB-Serial-JTAG. |
| DT05 | P2 DONE (sw) | App-only: `protocol/CliReplyLimit` marks an ACK `out` at the 3500-byte capture limit with "(output truncated by the device at 3500 bytes)" for every CLI-backed screen; parsers unmark. No firmware change. `CliReplyLimitTest` 3. Hardware check C1. |
| DT06 | P3 DONE (sw) | Delete confirmation; pulled local copies keep the newest 20 (`utils/FileRetention`). Not changed: LittleFS cannot be browsed while an SD is mounted (Bruce `getFsStorage` parity). `LocalFilesCleanupTest`. |
| DT07 | P3 DONE (sw) | Sleep/Reboot confirmation; report/session exports via `report/ExportFiles` (fallback dir, error shown, newest 10 kept); report list items escape device text. Other screens' exports (wardrive CSV/KML/GPX, NDEF, hc22000) still unguarded. `LocalFilesCleanupTest`. |
| DT08 | P3 DONE (sw) | Verified APK kept in `cacheDir/update` (app-private); stale update downloads (>30 min, old location too) removed at start; "Cancel download". `LocalFilesCleanupTest`. Hardware check U5. |
| DT09 | P3 DONE (sw) | Macro Edit (load into builder), step Up, "Save (replaces ...)" label. `MacroStoreTest`. |

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
| S02 | P0 | IN_PROGRESS | 2026-11-04 Claude subagent, clean worktree, no keystore access: `clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug` (JDK 23) BUILD SUCCESSFUL, KSP fresh, 584/584, lint 0 errors (some compile tasks restored from the global Gradle build cache). Debug+release: app.brucia 0.1.3/1000103, minSdk 26, targetSdk 34; release unsigned as expected. Emulator (Medium_Phone_API_36.1): debug->debug `adb install -r` of 59f6f749 -> current kept Room v3 rows, identity hash, prefs and files; relaunch without crash (same versionCode, no UI drive). Signed release -> debug correctly refused (signature). CI gaps: Temurin 21 vs local 23, no assembleRelease, runner-signed debug APK only, no SDK setup step. Remaining: signed release build/verify and signed release->release upgrade (keystore), real versionCode upgrade, actual CI run. Batch 2 CI: all actions pinned to full SHAs (verified with git ls-remote against tags), Temurin 21 kept and documented, explicit android-actions/setup-android (platform 34, build-tools 34.0.0), CI builds unsigned assembleRelease, `tools/check_apk_identity.py` (+13 host tests) checks applicationId/VERSION/minSdk/targetSdk/debuggable on both APKs, artifacts renamed, iButton host test added to CI. Not executed on GitHub yet. Batch 4 (EMU, emulator API 36, debug signing only): real versionCode upgrade 1000103 -> 1000104 (VERSION bumped only for the build, not kept) kept Room v3 identity hash and all seeded rows, prefs and files byte-identical, no app FATAL; downgrade refused with INSTALL_FAILED_VERSION_DOWNGRADE. Still open: signed release build and release->release upgrade (keystore), actual GitHub CI run. 2026-11-05: 0.1.4 published to BruciaDev (release.sh). Batch 5 (EMU2, emulator API 36, published APKs, no keystore access): v0.1.3 and v0.1.4 signed by the same certificate (SHA-256 deac265d...c84b, v2 scheme); 0.1.3 -> 0.1.4 `install -r` kept firstInstallTime, a macro saved through the UI and the location grant, no FATAL; debug over release refused (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Release Room/prefs not directly observable (non-debuggable). First real CI run (0.1.4 push): android and protocol-e2e jobs PASS; firmware jobs built but the AAC noise-bounds test FAILED on all three boards - fresh-checkout patch-ordering defect, fixed in batch 5 (see Additional retained review points); CI rerun pending on the next push. Remaining: targetSdk 35 signed release upgrade on a phone. CI rerun on the batch 5 push (4743f248): all jobs PASS (firmware x3 incl. AAC noise bounds on a fresh runner, android, protocol-e2e); artifacts uploaded. Note: ubuntu-latest moves to Ubuntu 26 (runner annotation); consider pinning the runner image. |
| S03 | P0 | DONE | 2026-11-04 coordinator: software matrix covers all 50 tools + Theme in APP_CONTROLS_CHECKLIST.md, with CMD/SUB/BURST/LOCAL ownership, requirements, deadlines, input validation and explicit evidence/limits. Added central CommandCapabilities and StreamSubscriptions; uncertain ACK outcomes remain eligible for OFF cleanup. Fixed BurstLoop/WIDS cancellation/startup, invalid duration waits, LoRa Stop/supersession, mixed-tool gates and failed-capture persistence. 24 new regressions; full cached Kotlin compilation 158 files, 557 tests / 88 suites passed. Tab matrix matches source (51/51); physical checkboxes untouched. Gradle/lint still denied global lock. Remaining Android/job/firmware boundaries stay S02/S04–S11; S04 subsequently completed below; next S06, do not auto-chain. |
| S04 | P0 | DONE | 2026-11-04 coordinator: additive transactional_uploads CAPS and fs_upload_* protocol; app validates token/cumulative bytes/commit CRC, never falls back to truncate, and reports uncertain commit outcomes. Firmware stages/verifies before promotion; LittleFS rename, FAT journal/backup/rollback with retained previous bytes on recovery failure. Reserved paths and concurrent legacy writes reject; idle expiry starts after slow IO. 41 UBSan engine scenarios plus real-handler fixtures, 563 cached JVM tests / 88 suites / 158 production files; three board builds (see TESTS.md). FAT visibility/power-loss durability, card swaps and Android provider rollback are not certified. No worker launched; next S06 only on explicit request. |
| S05 | P0 | BLOCKED | Final app/device acceptance and flash recovery; phone, board and appropriate peripherals required. 2026-11-05: first `tools/hw_acceptance.py` run on the T-Embed CC1101 found an inbound frame loss (RX queue too small for upload chunks) and a framing loss on `cc1101 Connection OK`; both fixed in software in batch 8 (HWLINK, see "S05 helper" below). Rerun on the same board with the batch 8 firmware: 12/12 PASS (upload with 3072-byte chunks, subghz.raw with 0 noise bytes, heap -7,576 B as predicted; report hw-acceptance-20261105T103009045Z, details in TESTS.md). 2026-11-05 owner phone test of release 0.1.5 (9f4eefa4): app update, firmware flash from the phone, upload > 3 KB, Sub-GHz Listen with 0 noise bytes, speaker/DTMF/SSTV, raw IR replay, new UI and USB unplug recovery all reported passing (TESTS.md "Phone test"). Still open: live RX traffic, NFC/RF/IR peripherals beyond replay, cancel mid-download, wake lock/swipe-away, flash recovery. |
| S06 | P1 NEXT | IN_PROGRESS | 2026-11-04 PortLease/ownership slice integrated: `transport/UsbOwnership` (PortLease close-once, FlashPortOwner token per flash with 90 s handoff deadline, stale/duplicate/expired ports closed on arrival, `launchFlashOwned` releases on cancellation-before-start via invokeOnCompletion; UsbPermissionRequests correlated by process-wide id + EXTRA_DEVICE name, 60 s deadline). MainActivity: one permission receiver onCreate..onDestroy, unique request codes, onDestroy cancels pending requests and aborts a waiting flash; Connect/openPort refused while flashing; transport construction failure closes the connection. VM: `flashEnteringBootloader()` returns a token, `flashFirmware(token, io, lease)` adopts by identity (no blanket isFlashing rejection), `flashAbort` ignores stale tokens, `onCleared` shuts the owner. Tests: UsbOwnershipTest 14. Behavior change: Activity recreation during the permission wait aborts the flash. Open: flash still calls `vm.disconnect()` (service/wake lock stopped during flash), companion-link vs flash arbitration (slice B), port.close()/connection semantics (D4/D5). No device evidence. Batch 2 (slice B): FlashPortOwner now arbitrates the USB device between companion link and flash by token (Connect/attach auto-connect via beginCompanionLink/connect(token)/abortCompanionLink; second open refused, flash preempts and closes the companion port, abandoned open expires after 10 s; "USB device busy" toast). D6a fixed: flash no longer calls disconnect(); ConnectionService switches to owner `flash:<token>` (keeps wake lock) and every flash end path (finish/abort/expiry/late handoff/cancel-before-start/onCleared) stops it once via onEnded. D4/D5 evidence from usb-serial-for-android 3.8.1 bytecode: `CommonUsbSerialPort.close()` closes the UsbDeviceConnection (l.155), `open()` throws "Already open" before taking the connection (l.114-115); new `releaseUsbPort(openedHere)` avoids killing a live link on a failed second open. onCleared disconnects before closing the owner. UsbOwnershipTest 22. Device-only: wake lock through a real flash, claimInterface on two connections, close() idempotency API 26-35. |
| S07 | P1 | DONE | 2026-11-05: v1/v2 sources found in the squashed pre-0.1 history (v1 `85a74629`, v2 `0c705937`, v3 `7c541da4`; reachable only as local unreferenced objects). Neither shipped: every tag v0.1-v0.1.4 is v3, the first APK-building CI job (`83de6259`) came after v3, and the old public repo is gone. `schemas/1.json`/`2.json` generated by Room 2.8.5 KSP from those sources; Room 2.6.1 (their original toolchain) gives the same tables and identity hashes, and the pipeline reproduces `3.json` (unchanged). `BruciaMigrations` 1->2 (rebuild `ap`, rows kept, accumulators seeded from the v1 sighting) and 2->3 (create `saved`), registered in `BruciaDb.get`; no destructive fallback. Tests: `BruciaMigrationTest` (MigrationTestHelper, emulator API 36) and `BruciaMigrationsSeedTest` (JVM); emulator 12/12, JVM 783/783. Debug build carries kotlinx-serialization-core 1.8.1 for room-testing (release unchanged, 1.7.3). Batch 12 (DEPS): release aligned on 1.8.1. Batch 14 (APPPOLISH): 1.12.0 in every build type, BruciaMigrationTest passes with it on the emulator. No real-device v1/v2 file was tested. See `android/app/schemas/README.md`. |
| S08 | P1 | IN_PROGRESS | Long-session memory/queue/drop/Stop tests with deterministic virtual transport; physical stress remains separate. 2026-11-04 batch 2: `SessionStressTest` (6 tests, virtual clock + fake fragmenting/coalescing transport, no sleeps): 5,000 mixed commands with timeouts/cancellation/late replies, 20,000-frame flood incl. 8192-byte and malformed frames (buffer <= 12,292 bytes), 3,000-step SUB model check, 500 Stop-all cycles with rejected/missing/unwritten OFF, 300 reconnects with residual callbacks, exact 8000 ms deadlines. All pending maps/buffers/timers asserted empty per round; mutation check (5 injected leaks) caught every one; 5 consecutive runs green. No defect found, no production change. Limits: no app-side drop counter for malformed frames (silently skipped), VM reader loop/disconnect mimicked not executed, real threads/heap/USB out of scope. Batch 4 (DIAG): app-side `protocol/LinkCounters` now counts malformed and invalid-length frames (previously skipped silently), timeouts and unmatched replies; shown in the Diagnostics card and exports. Batch UPLOADTEST (2026-11-05): `SessionFileLinkTest.uploadChunksFollowTheDeviceRxMax` failed once with `fs_upload_abort` while the Mac was swapping. Test-only cause: under runBlocking, withTimeout/withTimeoutOrNull are not run by the test's event loop but by the kotlinx DefaultExecutor thread at wall-clock time, so the fixture's 40 ms per-command upload timeout could cancel a command while the test thread was descheduled, although the fake replies synchronously. Reproduced deterministically with a 60 ms sleep in the fake wire (5/14 SessionFileLinkTest tests fail on runBlocking, the observed `Unexpected command fs_upload_abort` included; 0 on virtual time). Fix: the virtual clock moved from SessionStressTest to test `VirtualTime.kt`: `runVirtual` (runBlocking drop-in on that clock; jumps to the next timer when idle like runTest; fails instead of hanging, and fails when coroutine work arrives from another thread) and `virtualUnconfined` (Unconfined-like dispatcher for injection, with virtual delays/timeouts). 341 tests in 49 classes moved to it, no assertion changed; WidsServiceTest 3.3 s -> 0.1 s. Test seam only: `CaptureWorkflow(compute = Dispatchers.Default)` (parse/hash dispatcher, default unchanged); DictionaryTestServiceTest uses its existing dispatcher parameter. No kotlinx-coroutines-test dependency added. Real-time by design (real threads or blocking calls; bounds of 1-5 s on waits that normally complete at once are hang guards): UpdateDownloadConcurrencyTest, UsbOwnershipTest (launchFlashOwned on Dispatchers.Default), RecordingBufferTest two-thread races, ProtocolTest thread pool, ReleaseUpdateServiceTest 2 blocking-fetch tests, PcmStreamServiceTest blocking read, DictionaryTestServiceTest blocked open, BurstLoopTest Default-scope cancellation; JUnit `@Test(timeout)` values are wall-clock hang guards only. A one-off run that sampled all threads at every virtual-time jump found no other thread running app code in the converted tests. Evidence: 5 consecutive full suite runs green with 16 `yes` hogs on 12 cores (load average up to 49). CPU load alone did not reproduce the original failure (0/8 runs of the affected classes with 24 hogs, before and after); the stall simulation did. No production behavior change. Batch 15 (SOAK, 2026-11-05): physical part prepared, not yet run: `tools/hw_acceptance.py --soak <minutes>` repeats passive allowlisted intervals (PINGs, `storage list /`, CRC-checked `fs_read`, scratch upload/read-back/remove, one receive-only stream ON/OFF in turn, keepalive PINGs, `diag`) and records heap free/min_free/largest (Theil-Sen slope per hour after a warm-up; FAIL above max(2048 B/h, 4096 B over the window)), per-interval link counters against the runner's frames (any mismatch, reject, ERR, noise byte or resync is a finding), PING percentiles, reset/uptime (reboot stops and FAILs), timing of every operation; JSON/Markdown plus CSV time series; Ctrl-C cleans up and still reports. Host evidence only: `test_hw_acceptance.py` against `mock_device.py` (new `reboot` fault and companion idle exit): healthy soak with a 1 s idle exit, too-slow keepalive, heap leak, reboot, noise, Ctrl-C in a stream and after an upload commit; 5 mutations of the soak code each caught. Open: run it on the T-Embed (20 min first, then multi-hour); app-side long sessions remain separate. |
| S09 | P1 | IN_PROGRESS | Local Claude patch reviewed and integrated: batch/stream decoders predict from the previous measured sync, preventing cumulative Scottie S1 rounding drift. Coordinator reproduced 2 failures before / 31 passing DSP tests after (27 baseline + 4 new). Fractional-time fixture is explicitly synthetic, not external evidence. 2026-11-05 timing reference (agent9/SSTVREF): `SstvTimingReferenceTest` (7 tests) holds a per-value-cited table for all six modes from JL Barber N7CXI "Proposal for SSTV Mode Specifications" (Dayton, 20 May 2000; offered "for use or publication by anyone"), cross-checked with M. Bruchanov OK2MNM "Image Communication on Short Waves" ch. 3-5 (free download, no licence stated) and the MMSSTV transmitter source (LGPL-3.0+, n5ac/mmsstv 8060b5f1); numbers only, no documents copied. All Sstv constants (sync, porches, separators, scans, VIS codes, header) match. Source conflict: Robot 72 porch before B-Y is 1500 Hz in Barber, 1900 Hz in MMSSTV; Sstv keeps 1900. Found and fixed: per-segment sample rounding made encoded line periods deviate from nominal (Martin M2 -487 ppm, 28.3 ms short per frame; S1 +70, M1 -19, S2 -16 ppm) and offset decoder sample positions by up to 1.8 samples; encoder and decoder now place boundaries on an exact time axis. Before: 2/7 new tests failed; after: 787/787, lint 0 errors. Still open: no independently encoded recording and no over-the-air test; the audio checks use Brucia's own encoder and discriminator. |
| S10 | P1 | IN_PROGRESS | 2026-11-04: ConnectionService start rule extracted to pure `ConnectionServiceCommand`: null intent (system restart) or missing status stops the service instead of advertising Connected/taking the wake lock; START_NOT_STICKY. ConnectionServiceCommandTest 4. Activity recreation, receiver cleanup, swipe-away, foreground/wake-lock behavior still require instrumentation/device evidence. Batch 2: decision rule is owner-aware (companion vs `flash:<token>`): a companion stop/update cannot end or replace a flash, stale flash stops ignored, only a newer flash token replaces a shown flash; `stopSelf(startId)`. ConnectionServiceCommandTest 11. Batch 4 (EMU): first instrumentation tests (`app/src/androidTest`, androidx.test pinned), 7/7 on emulator API 36: START_NOT_STICKY + foreground + wake lock, stop releases everything, missing status and null-intent restart (fresh and while shown) stop without wake lock, companion vs flash token owner rules, Activity recreate x2 keeps exactly one USB permission receiver and destroy unregisters it (read from dumpsys, no hook); 5 deliberate mutations caught. Test-only `ConnectionService.live` reference. Device-only: real USB attach/permission, long screen-off session, swipe-away/process kill during link or flash. Batch FLAKY (2026-11-05): the intermittent notification-removed failure (about 1 run in 4) was a test race, not a product bug: the Stop path already calls `stopForeground(STOP_FOREGROUND_REMOVE)` before `stopSelf(startId)`, but system_server drops the service record synchronously while NotificationManagerService cancels the notification later on its handler; probe on API 36: 1/40 stops still listed the notification right after the record was gone, removed 8 ms later. The 3 post-stop single reads are now a bounded poll (`awaitNotificationRemoved`, 15 s deadline, final state asserted); wake lock asserts stay strict (released before stopSelf). 13/13 consecutive full `connectedDebugAndroidTest` runs green (12 tests each, emulator API 36); mutation `STOP_FOREGROUND_DETACH` fails at the new poll (notification still listed after 15 s), restored. No product change. |
| S11 | P1 | IN_PROGRESS | 2026-11-04 firmware receive fixes integrated (no TX/replay changes): rfSaveSignal returns false on create/short-write failure and RFScan only marks lastSavedKey on success; companion `subghz.raw`/`subghz.spectrum` SUB now ERR 503 with everything released when CC1101/RMT init, queue, callback, enable or receive fails; failed raw re-arm retried (500 ms throttle); setup_rf_rx returns NULL instead of ESP_ERROR_CHECK abort; rf_scan starts at start_freq, N>0 bounds steps, Esc per step; rfReceiveSignal N<=0 waits for Esc (default no longer times out after one poll), headless init failure printed; raw event `freq` = tuned bruceConfigPins.rfFreq. Additive `symbols`/`max_symbols` (64): below max rules out a buffer cut, equal = completeness unknown (IDF 5.5.4 RMT drops silently without partial RX); never claims proved loss. PROTOCOL.md updated (timings unit corrected to us). Evidence: T-Embed build, host tests incl. protocol E2E. Open: app ignores new fields and leaves the Listen switch on after ERR; spectrum kHz rounding/range validation; rfScanCallback ignores rf_scan result. All behavior needs S05. Batch 2 follow-ups DONE in software: Listen/Spectrum UI driven by `subghz/SubGhzReceiveState` from StreamSubscriptions (ERR/timeout/rejection turns it off with the reason; OFF failures and peer replacement are not reported as start failures); `SubGhzRawEvent` parses symbols/max_symbols (absent/malformed = no information), waveform shows "possibly truncated (device buffer full)", CSV gains trailing symbols,max_symbols columns, .sub unchanged. Firmware: `subghz_spectrum_plan.h` validates sweeps (finite, 280-928 MHz, end >= start, step >= 1 kHz, <= 128 points) with ERR 400 instead of silent swap/clamp/widen; grid quantized to whole kHz (no cumulative truncation); start during a sweep applies the new grid; raw freq rounded to kHz. `rf scan` CLI reports found/none/init failure and refuses non-CC1101 modules. Tests: SubGhzReceiveTest 7, CSV +1, tools/test_firmware_spectrum.py (UBSan). Remaining: S05 hardware checks only. |
| W02 | P1 | DONE | 2026-11-04 `mitm/HttpWire`: header values trimmed only of SP/HTAB before validation (Unicode `trim()` hid VT/FF/FS-US/NBSP around Content-Length/Transfer-Encoding); response status must be exactly 3 digits (`+200`/`0200` were accepted and rewritten). HttpWireTest +2 (failed before). Noted, unchanged: bare LF on chunk-size lines, chunk-extension bytes bounded only by time budget, HTTP/1.0+TE keep-alive, DEL allowed in values. Batch 3 leftovers DONE: chunk-size lines require CRLF (field lines keep RFC 9112 bare-LF tolerance), chunk extensions capped at 4096 bytes/message and size digits at 16, DEL rejected in field values (obs-text kept), HTTP/1.0 + Transfer-Encoding rejected as framing error, only exact `CONNECT` tunnels (HttpWire + one line in MitmProxy). HttpWireTest +5. HEAD/body methods still case-insensitive by design. |
| W03 | P1 | DONE | 2026-11-04 app/web release catalog parity: both accept only the exact `https://github.com/1vcian/BruciaDev/releases/download/<tag>/<name>` string (web `new URL()` normalized 10 spellings the app rejected; app accepted `:443`); tag must be a string with 1-9 digits per part; web rejects two APKs like the app; app pre-scan rejects JSON nesting > 32 (Android org.json is unbounded, StackOverflowError not caught); null body -> empty notes. ReleaseCatalogTest +2, test_site_release.cjs +1. Open: no asset size/sha256 digest check on either side. Batch 2 follow-up DONE: `update/AssetIntegrity` + `DownloadIO.copyVerified`; app and web verify GitHub asset `size` and `digest` (sha256) before install/flash; absent = allowed but labeled unverified, malformed = asset rejected (still counts for ambiguity), mismatch = hard failure ("Nothing was installed/flashed"). Web page fetches, verifies with crypto.subtle and hands esp-web-tools only a blob: URL. ReleaseIntegrityTest 5, site tests 15. Coordinator: added AssetIntegrity.kt to tools/test_android_logic.py (was failing to compile) and replaced fixed event-loop waits in the page-script tests with waiting on pending digests (1 failure in 16 runs under CPU load before; 10/10 after). Behavior: the page downloads the firmware as soon as a release loads on Web Serial browsers. Not tested against a real release/browser; possible CORS limits. |
| W01 | P1 | DONE | 2026-11-04 RecordingBuffer reviewed, unchanged. `AudioExport.saveInto`: exclusive folder creation (`rec_<stamp>`, `_1`..`_99`) so same-second saves never overwrite; failure deletes the partial folder and rethrows (samples kept for retry). AudioExportTest 3. Open: IO read loop vs Main Stop race on `_recording`/`_recordingReady` (stale state or false limit text); firmware `mic.pcm` has no sequence/drop count (needs additive protocol change). Batch 2: both open items addressed in software. `RecordingBuffer.append` returns APPENDED/LIMIT_REACHED/INACTIVE/REJECTED and publishes its own state under its lock; VM `_recording`/`_recordingReady` removed, limit text only from a real LIMIT_REACHED (latch-ordered two-thread test). Firmware `mic.pcm` adds `seq` (restarts per SUB) and, when the I2S driver accepts the overflow callback, cumulative `dropped` samples; app reports "gaps detected: N (M samples dropped on device)" or "unknown" for old firmware, no filler inserted. RecordingBufferTest +10. Hardware: overflow callback firing and sample-unit conversion (assumes 16-bit mono channel data) on the T-Embed mic. |
| W04 | Later | HOLD | Codec follow-up only if first worker reviews reveal a concrete need. |
| COV | P1 | DONE (software) | 2026-11-05 worker agent15/COVERAGE: opt-in JaCoCo (`-Pbrucia.coverage :app:createDebugUnitTestCoverageReport`, TESTS.md "Unit-test coverage"). Risk-ranked gaps closed with 37 tests: EspFlasher against a scripted ROM/stub (0% -> 75% lines), HandshakeParser/PMKID, PcapReader/Dot11Frame, FirmwareImage rule-by-rule, FrameBudget vs real wire bytes, TftProtocol vs `tftLogger.h`, `.sub` transmit, command-reply edges, macro store. Bugs fixed (failing then passing): ROM 4-byte status read from reserved zeros (ROM errors looked like success), M3/M4 taken as handshake M2, pcap Int overflow (OOM crash) and ns timestamps, `.sub` values reaching throwing firmware conversions, TFT rotation byte, logcat LinkageError off-device. Remaining: Room/service/MITM/USB need instrumentation or a device; EspFlasher reset/timeout paths are real-time; flash fix needs S05 hardware confirmation. 2026-11-05 batch 16 (FLASHREAD): the flasher's reply reader dropped the rest of a USB read after the reply it waited for, losing the stub "OHAI" when it shared a read with the MEM_END reply; fixed with a persistent receive buffer (`SlipCodec.Receiver`, frames taken one at a time, partial frames and split escapes kept across reads, OHAI seen during the MEM_END wait remembered). `SlipReceiverTest` 12 (also in `tools/test_android_logic.py`), `EspFlasherTest` +8 (one read for everything, 1..24-byte reads, OHAI before/without the MEM_END reply, boot text, partial SYNC at a timeout, the byte-exact S3 session at every read size); the old reader fails 6 of them, the byte-exact per-reply session and ROM-error tests pass on both. Phone flash recheck: TESTS.md "Batch 16 (FLASHREAD)". |
| BRAND | P1 | IN_PROGRESS | 2026-11-06 worker agent17/BRAND: `brand.json` is the single source of the public links (name, `1vcian/BruciaDev`, site, upstream credit) with the future `Brucia` values in its `launch` block; `tools/brand.py` generates `include/brucia_brand.h` and `docs/brand.js`, rewrites links in managed docs/site/WebUI files, and feeds `release.sh`; Gradle reads it into `BuildConfig.BRAND_*` (`BrandLinks.kt`: updater API, accepted asset prefixes for both BruciaDev and Brucia, wordlists, wiki, About footer, beacon SSID). `tools/test_brand.py`: sync, launch switch on a copy, firmware macro/include hygiene, link scanner with reasoned allowlist; app `BrandLinksTest`; site and release tests derive URLs from brand. Firmware user-facing names now Brucia (BLE/AP defaults, serial banner, Device Info/`info` source + upstream lines, NFC default URL, GPX, AP-mode WebUI shows the configured SSID); formats, folders, JS API and the Bruce-app BLE API name unchanged. Next: coordinator firmware build, hardware checks in `docs/LAUNCH.md`, then a bridge release before the rename. Launch procedure and docs regeneration list: `docs/LAUNCH.md`. |

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
   Batch 13 (REPRO): the same commit gave a different Cardputer firmware depending on what was
   already in `.pio/libdeps/<env>` (main tree 4,320,368 B / 84.52% with M5Unified + M5GFX
   installed there on 2026-10-30 by an unrecorded command, fresh/CI 4,223,152 B / 82.62%):
   `bus_HAL.cpp`/`audio.cpp` use `__has_include(<M5Unified.h>)` (upstream Bruce code) and the
   LDF links any installed match. `tools/build_firmware.py` now, after install/normalize and
   before `pio run`, asks `pio pkg list -e <env> --only-libraries -v` (PlatformIO's own walk of
   lib_deps plus each package's declared dependencies over installed packages) and moves every
   unlisted package dir to `.pio/libdeps-orphans/<env>/`, printing it; refuses if nothing
   matches. A clone of the main tree's polluted libdeps: M5GFX and M5Unified quarantined,
   firmware 4,223,152 B, sha256 5b6cada1... byte-identical to a fresh-libdeps build. T-Embed
   4,313,744 B and core4mb 2,661,552 B built fresh with nothing quarantined; AAC noise bounds
   pass on all three. Limits: only `.pio/libdeps/<env>` is cleaned (project `lib/`,
   `lib_extra_dirs` and framework libraries are fixed by the tree and platform pin); plain
   `pio run` still links whatever is in libdeps (the upstream `buil_parallel.yml`/
   `manual_build_sel_env.yml` libdeps caches are exact-keyed since batch 14);
   a dependency declared only in a library's manifest for another platform is not listed and
   would be quarantined if present (harmless: it is not used for this env); the listing format
   is PlatformIO 6.x (6.2.0 here). Open owner decision: whether the Cardputer should declare
   M5Unified (see TESTS.md batch 13 REPRO); default kept = upstream (not declared).
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
  Batch 6 (TOOLCHAIN): Gradle 9.2.1, AGP 8.13.2, Kotlin 2.4.21, KSP 2.3.12, Room 2.8.5
  (3.json byte-identical, identity hash unchanged, emulator upgrade kept rows),
  play-services-location 21.4.0; Kotlin warnings 0; Paparazzi snapshots byte-identical.
  New AGP 8.13 lint findings fixed (UseKtx x10 to KTX forms) or justified per site (Recycle
  x2, false positives on `use {}`). Lint 0 errors / 11 warnings, all held versions: SDK 36
  (Paparazzi 1.3.5 crashes on the API 36 android.jar), compose-bom (done in batch 7 below;
  the blank-snapshot cause is understood: Paparazzi needs compileSdkVersion = 34 in the test
  environment for Compose 1.8+), core/activity/lifecycle (see batch 7), zxing and
  BouncyCastle (need their own review), org.json (test-only). Gradle 9.3+ breaks Paparazzi
  1.3.5 reports; AGP 9 not attempted.
  Batch 7 (COMPOSE): compose-bom 2024.06.00 -> 2026.06.01 (Compose UI 1.11.4, Material3
  1.2.1 -> 1.4.0), lifecycle 2.8.7 -> 2.10.0; newest steps that keep AGP 8.13.2/compileSdk 35
  (BOM 2026.08+ = UI 1.12 and lifecycle 2.11 need AGP 9.1/compileSdk 37; core-ktx 1.17+ and
  activity 1.11+ need compileSdk 36, so core-ktx 1.16.0 / activity-compose 1.10.1 stay).
  Look kept centrally: `ui/BruciaButtons.kt` wraps Button/OutlinedButton/TextButton with
  1.2 colors (outlined label = primary, 1 dp `outline` border in every state, disabled
  onSurface 38 %/12 %), screens import them and `BruciaButtonDefaults` (guarded by
  ButtonThemeUsageTest); Theme sets surfaceContainerHighest = surfaceVariant grey (Card,
  Switch track), surfaceContainerHigh/surfaceContainer = surface tinted at 6/3 dp (AlertDialog,
  DropdownMenu as before), secondaryContainer = surface/primary blend (new slider and progress
  track). Paparazzi compileSdkVersion = 34 workaround was already in the test; snapshots
  not blank; pixel-exact diff: 13/17 identical, crack/crackDownloads changed only in the
  progress bar (new design, Brucia track), the two mirror snapshots only in the unthemed
  letterbox (default light surface FFFBFE -> FEF7FF). Emulator API 36 before/after: home,
  utilities (switches/fields), updates, support dialog pixel-identical below the status bar;
  sliders and progress bars changed as intended. Kotlin warnings 0 (LocalClipboardManager ->
  LocalClipboard; screenHeightDp -> LocalWindowInfo for the two new lint warnings). Lint 0
  errors / 11 warnings, same categories as before: held versions (compileSdk 36, core-ktx,
  activity, compose-bom 2026.09, lifecycle 2.11, zxing, org.json, BouncyCastle x2) and
  OldTargetApi. Room schema unchanged.
  Batch 9 (SDK36): held toolchain unblocked. No stable Paparazzi supports SDK 36+: 1.3.5
  (2024-11, last stable) crashes in Renderer.configureBuildProperties on the API 36+
  android.jar (cashapp/paparazzi#1877, fixed only in 2.0 alphas); forcing layoutlib 15.2.3
  under 1.3.5 fails Bridge init. 2.0.0-alpha05 works on AGP 8.13; alpha05.1 (2026-09-28,
  AGP 9 support, layoutlib 16.2.3, consumers need JDK 21+) is required on AGP 9 (alpha05.1
  on AGP 8.13 breaks mergeDebugResources via sdk-common 32). Test-only, not in the APK.
  Now: AGP 9.3.3 (built-in Kotlin, kotlin-android plugin removed; KGP 2.4.21 via the
  Compose plugin), Gradle 9.7.1, KSP 2.3.12, compileSdk 37 (platforms;android-37.0,
  build-tools 36.0.0), core-ktx 1.19.1, activity-compose 1.13.0, compose-bom 2026.09.00
  (UI 1.12.1, Material3 1.4.0), lifecycle 2.11.0; targetSdk stays 35. Ceiling: KGP 2.4.21
  declares AGP <= 9.3.1 / Gradle <= 9.7.0; AGP 9.5.0 + Gradle 9.8.2 (+ KSP 2.3.13 for the
  Gradle 9.8 setVisible deprecation) also built and tested clean but sit outside that range;
  AGP 8.x cannot run on Gradle 9.6+ (removed internal API). Snapshots: layoutlib 14 -> 16
  re-render, identical across AGP 8.13/9.3/9.5 and SDK 36/37; all 17 diffs reviewed: glyph
  and rounded-corner antialiasing, mirror bitmap edge filtering, one wrap change (home
  "Device not connected" text breaks one word later); layout, colors, content unchanged;
  verify passes twice; compileSdkVersion = 34 override removed (no effect under 2.0).
  New AGP 9.3 lint: UseKtx x8 (WardriveViewModel SharedPreferences.edit) fixed. Lint 0
  errors / 7 warnings: OldTargetApi (held), Gradle 9.8.2 (outside KGP 2.4.21 range),
  zxing, org.json, kotlinx-serialization-core 1.8.1 (S07 debug pin), BouncyCastle x2.
  Kotlin warnings 0 (all source sets recompiled). Rebased on 0.1.5: unit 790/790 (111
  suites); emulator API 36 instrumentation 12/12 incl. BruciaMigrationTest (schema assets
  and room-testing work under built-in Kotlin); lifecycle 2.11.0 still brings
  serialization-core 1.7.3, so the debug-only 1.8.1 pin stays. ConnectionServiceInstrumented
  Test notification-removed asserts flake about 1 run in 4 on both this toolchain and the
  0.1.5 toolchain (notification still listed right after the service record is gone):
  test race, not a toolchain regression (fixed in the test, see S10 batch FLAKY).
  Back navigation (tool -> home, dialog close, home -> launcher) checked with activity 1.13. Room schemas byte-identical.
  tools/check_android_changes.py reads AGP 9 built_in_kotlinc output and the compileSdk
  platform (its MutableInteractionSource classpath-order error predates this batch).
  targetSdk 36 still needs its own review: predictive back on by default (onBackPressed /
  KEYCODE_BACK no longer delivered; BackHandler is fine), orientation/resizability/aspect
  ratio ignored on sw600dp+ screens, edge-to-edge opt-out removed (already edge to edge),
  elegantTextHeight ignored, JobScheduler/intent-safety changes.
  Batch 10 (TARGET36): targetSdk 36. Audit: no onBackPressed/onKeyDown/KEYCODE_BACK;
  BackHandler in AppRoot (tool/category -> dashboard), FileEditorScreen, now also MitmDetail
  (-> list) and FilesScreen (subfolder -> up); Compose Dialog/AlertDialog use
  ComponentDialog and Compose 1.12.1 PopupLayout registers an OnBackInvokedCallback
  (DropdownMenu); logcat shows androidx.navigationevent OnBackInvokedInput registered. No
  screenOrientation/resizeableActivity/min/maxAspectRatio/setRequestedOrientation, no
  windowOptOutEdgeToEdgeEnforcement or elegantTextHeight, no JobScheduler/WorkManager/
  DownloadManager, no scheduleAtFixedRate, no BODY_SENSORS, no phone-side Bluetooth, no
  MediaStore#getVersion, no nested-intent relaunch (intent redirection), no broadcast
  priorities; outgoing intents are ACTION_VIEW/ACTION_SEND choosers, Settings
  MANAGE_UNKNOWN_APP_SOURCES and the APK install via FileProvider. tab/category now
  rememberSaveable (before, a rotation or resize recreated the Activity and reset to the
  dashboard; Sub-GHz and Files now stay open).
  Native libs (androidx.graphics.path only) 16 KB aligned. Lint 0 errors / 7 warnings, same
  set (OldTargetApi now means 37). Build script: androidTest assets srcDir() (deprecated
  in AGP 9.3, the one build-script Kotlin warning) -> directories.add(); schemas 1-3 still
  packaged, BruciaMigrationTest passes. Emulator evidence and open phone checks: TESTS.md
  "Batch 10 (TARGET36)". Follow-ups: tablet layouts stretched (done in batch 11 UI-ADAPT);
  the MITM list scroll position resets after leaving the detail (fixed in batch UPLOADTEST:
  list state remembered above the list/detail switch, `ListDetailSwitch`;
  ListDetailSwitchTest 2, incl. a control with the old shape); Local Network Protection
  (groundwork done in batch 11 UI-ADAPT).
  Batch 11 (UI-ADAPT): `ui/AdaptiveLayout.kt`: home grid columns = tiles of >= 160dp with
  12dp gaps, min 2 / max 6 (phones in portrait stay 2 below 504dp of grid width), dashboard
  centered at <= 1040dp, tool screens (AppRoot wrapper) and category lists at <= 840dp
  (`CenteredMaxWidth`; same constraints as before on narrower windows). Phone landscape
  (> 504dp grid width) now gets about 4 columns. Local network: per developer.android.com
  privacy-and-security/local-network-permission, ACCESS_LOCAL_NETWORK (API 37, NEARBY_DEVICES
  group) gates TCP out/in and UDP uni/multi/broadcast both ways to LAN addresses, enforced
  only for target 37 on Android 17; apps targeting <= 36 get it implicitly and must not
  declare or request it. So: `net/LocalNetworkAccess` (pure: required only when device and
  target >= 37; status READY / NEEDS_PERMISSION / DENIED / BLOCKED; EPERM classifier; probe
  target) + `net/LocalNetworkGate` (request on tap, settings link, re-check on resume, one
  UDP datagram to the subnet .1 discard port; EPERM -> BLOCKED). Wired into Network Scan
  (now `NetworkScanContent`, stateless) and MITM proxy start. Responder is device-side
  (`ResponderService` sends a CLI command; the ESP answers LLMNR/NBT-NS), so it is not
  gated. `LocalNetworkAccessTest` tripwire: targetSdk >= 37 requires the manifest
  declaration, < 37 forbids it. Before targetSdk 37: declare ACCESS_LOCAL_NETWORK, test the
  prompt/denial on an Android 17 image (the in-app prompt path never ran: the API 36.1
  emulator has no such permission), consider `NsdManager` FLAG_SHOW_PICKER where a single
  device is enough. Found, not fixed: Sub-GHz "Listen" label is near-invisible (dark on
  dark, phone and tablet); Network Scan results are lost on rotation/resize (plain
  `remember`, scan job cancelled). Evidence: TESTS.md "Batch 11 (UI-ADAPT)". Both fixed in
  batch 12 (UIFIX).
  Batch 12 (DEPS): the held library versions reviewed. ZXing core 3.5.3 -> 3.5.4 (bug
  fixes, no CVE; QR pixels byte-identical for Latin-1 payloads). BouncyCastle bcprov/bcpkix
  (+ bcutil) 1.78.1 -> 1.86: OSV lists CVE-2025-8916, CVE-2026-5588, CVE-2025-14813,
  CVE-2026-0636, CVE-2026-5598, CVE-2026-8763, CVE-2026-13506 for 1.78.1, plus the 1.85/1.86
  advisories (OpenPGP, CMS, MLS, PBKDF/keystore DoS, PKIX); none sit in the X.509 build path
  the app uses (MitmCertFactory: X500Name, extensions, JcaContentSignerBuilder, converter).
  1.85+ refuses a CN over 64 chars, so leaf generation threw for longer hosts (DNS allows
  253): `MitmCertFactory.leafSubject` now sets CN only up to 64 chars (literal value via
  X500NameBuilder, so ',', '+', '=' in a CONNECT target no longer add RDNs), else
  O=Brucia/OU; the host stays in the SAN. 1.85/1.85.1 crashed loading PKCS12 below API 33
  (fixed 1.85.2; the app's PKCS12 keystore is the platform one anyway); 1.86 base classes are
  AnimalSniffer-checked against API 26 upstream, and a local scan of base-class references
  against SDK 37 api-versions.xml finds nothing newer than API 26 that 1.78.1 did not already
  reference (JNDI LDAP/DANE fetchers, inert on Android). Packaging: META-INF/LICENSE.md is in
  all three 1.86 jars (identical), pickFirst. TrustAllX509TrustManager: lint without the
  ignore reports only bcpkix-jdk18on-1.86.jar (est.jcajce.JcaJceUtils), so app/lint.xml
  still targets the right jar. org.json (test-only) 20240303 -> 20260814 (number-length DoS
  CVE-2026-59171 fixed in 20260719; tools/test_android_logic.py follows). kotlinx-
  serialization-core: the 1.8.1 pin moved from debugImplementation to implementation, so
  release ships the version debug and the instrumentation tests run (release was 1.7.3, from
  savedstate/lifecycle; no app code uses it). 1.12.0 held: room-testing 2.8.5 and savedstate
  1.4.0 were built against 1.8/1.7 and the S07 AbstractMethodError shows the test APK is
  sensitive to this version; moving needs a `connectedDebugAndroidTest` run
  (BruciaMigrationTest) first. Found and fixed: QR text outside Latin-1 (Cyrillic/CJK SSID)
  was encoded as '?' (ZXing byte mode defaults to ISO-8859-1); now UTF-8 with ECI, Latin-1
  output unchanged. Tests: QrCodesTest 5 (encode -> ZXing decode round trip), MitmTlsTest 6
  (in-memory SSLEngine handshake against MitmCa.serverContext with HTTPS endpoint
  identification: DNS, long-host and IP SANs, wrong CA and wrong host rejected, PKCS12
  round trip), MitmCaTest +3; reverting the CN fix fails 3, dropping the UTF-8 hint 1. Release APK 19,548,155 ->
  18,892,115 B (BC 1.86 drops the Picnic tables), debug 27,199,177 -> 26,657,882 B; no R8
  (isMinifyEnabled false). Lint 0 errors / 3 warnings: OldTargetApi, Gradle 9.8.2,
  kotlinx-serialization-core 1.12.0. Not checked on a phone: MITM interception with BC 1.86,
  UTF-8 Wi-Fi QR with a phone camera.
  Batch 12 (UIFIX): Listen label cause: AppRoot hosts tools in a Column with a background,
  not a Surface, so a Text/Icon with no color outside a Card/Surface/Button read
  LocalContentColor's default (black): 1.09:1 on #0E0E10, present since 0.1 (not a
  Material3 1.4 role change). `BruciaTheme` now provides `LocalContentColor =
  onBackground` (17.12:1); Surface/Card/Button still override it. Sweep (script over every
  Text/Icon without color/tint and no Surface-like ancestor in its function): the same
  defect hit Wardrive "Wardriving", BLE "Scan", Wi-Fi monitor target SSID, NFC finding
  titles (`FindingRow` outside a Card) and Report preview lines; no hardcoded dark text
  colors found. The three stream switches share `StreamToggleRow`. `ThemeContrastTest` (WCAG
  ratios for onBackground/onSurface/onSurfaceVariant on background/surface, with accents) and
  `ContentColorScreenshotTest` (no Surface, as AppRoot; fails when the provider is removed).
  State across configuration changes: `ui/RetainedState.kt` (`rememberRetained`: Activity
  ViewModel map keyed by a saved per-call-site id, dropped when the screen leaves for good;
  `LeaveEffect`: onDispose skipped while `isChangingConfigurations`). Network Scan ->
  `NetworkScanSession` in `NetworkScanViewModel` (run-id tagged updates, start refused while
  running, stop/reset); PCAP dissect -> `PcapDissectSession`; Remote `running` ->
  `WardriveViewModel.remoteRunning`; retained: Files editor (edits now in `EditorState`),
  SSTV cascade decoders/images, macro steps, tone composer steps, Wardrive found order, IR
  parsed buttons; `rememberSaveable`: 106 form inputs/options across tools plus the Files
  path and pending SAF pull path. Left in plain `remember` on purpose: dialog/menu/expand
  flags, secrets (Wi-Fi PSK/passwords, WiGLE token, generated password, Wi-Fi QR payload),
  values re-read from the device (LED/screen brightness, volume), derived/cached values,
  live-stream buffers whose stream is stopped on dispose, MITM selected transaction, RF
  fingerprint compare target, 3D map WebView. Found in batch 12, fixed in batch 13 (STREAMS,
  below; software and emulator only): tool screens stopped device streams in
  `DisposableEffect`, so a rotation stopped them; DeviceScreen screen/LED switches reset to
  on after a rotation. Evidence: TESTS.md "Batch 12 (UIFIX)".
  Batch 13 (STREAMS): every stream/radio stop that ran in an `onDispose` (23 hooks in 9
  files: Sub-GHz raw/spectrum/jammer/capture, BLE scan/GATT/iBeacon, mic.pcm in Audio/SSTV/
  Oscilloscope plus recording, phone mic, SSTV TX, DTMF, Bug sweep, RF timeline/fingerprint,
  station/probe/monitor sniffers, channel analyzer, Evil Portal target scan, WIDS, IR
  jammer, NRF24, LoRa RX, FM, ARP spoof) now runs in `LeaveEffect`. Ownership stays where it
  already was (`StreamSubscriptions` in `WardriveViewModel`, VM loops); screens keep only
  re-bindable flags in `rememberRetained` (Bug sweep sweeping/bleStartedHere/peak, timeline
  and fingerprint bleStartedHere, channel analyzer wasScanning, Evil Portal target list).
  `LeaveEffect` parks a skipped leave in `ParkedLeaves` (Activity ViewModel) under its saved
  id; the recreated screen claims it, otherwise `SettleParkedLeaves` (MainActivity root, after
  two frames) runs it, so a tool left during recreation still stops; `onCleared` settles the
  rest. Auto-starting screens start only what is not on (`StreamSubscriptions.isOn`,
  `startMicPcm` no-op while on); leave sends OFF only when `mayRun` (on, starting or
  unconfirmed, no OFF in flight). New `WardriveViewModel.bleScanning` (BLE Scan switch was a
  local `remember`); `StreamSubscriptions.disconnect` (detach + best-effort OFF, used by
  `disconnectLink`). `device/DeviceOutputs`: screen/LED state in the VM (request token,
  failed reply undone unless superseded), reset to on at disconnect after sending `scr 1` /
  `led 1` for an output the app turned off. Process death: the app sends nothing; the
  firmware leaves companion mode after 60 s without frames and stops every stream there.
  Open: phone + T-Embed checks in TESTS.md "Batch 13 (STREAMS)"; a firmware report of
  screen/LED state would replace the app-side record.
  Batch 14 (FWSTATE): the firmware reports it. CAPS adds `scrOn`/`ledOn` (false while `scr 0` /
  `led 0` hold the output off) and `scrBright` (`currentScreenBrightness`); `scr_bright <n>` with
  n > 0 ends a `scr 0` hold (`companionSetScreenLevel`). The app applies them on CAPS
  (`DeviceOutputs.reported`, skipped for an output with a request still on the wire) and the
  screen brightness slider uses `DeviceInfo.screenBrightShown`; older firmware: keys absent, app
  unchanged. Evidence: `DeviceOutputsTest` (+3), e2e checks against `mock_device.py` (new state,
  reconnect, scr_bright, 15 s restore, `--legacy-outputs`), hw_acceptance CAPS validator. Open:
  T-Embed + phone check in TESTS.md "Batch 14 (FWSTATE)".
  Batch 14 (APPPOLISH): MITM request rows keyed by `MitmTxn.id` (`mitmRows`, `items(key =
  { it.id })`); the list state, kept above the list/detail switch since batch UPLOADTEST, now
  anchors on the key, so rows added at the top while the list or a detail is shown no longer
  shift the visible row. Duplicate ids: `MitmLog.nextId()` was taken before the exchange and
  the catch logged an error entry under the same id; a real path was a success entry followed
  by an exception from closing the upstream socket (`socket.use` close, e.g. SSL close_notify).
  Now `MitmLog.add` assigns the id under its lock only when the entry is stored (consecutive,
  not reset by Clear), and `RequestLog` gives each proxied request one entry at most (the
  failure entry only when nothing was logged). ARP spoof card: its fields were
  `rememberSaveable`/`remember` inside a LazyColumn item, dropped with the list while a detail
  is shown (rememberSaveable only covers recreation); now `ArpSpoofForm` in `MitmScreen`
  above the switch, saved with a Saver that leaves the password out (password still not kept
  across rotation, as for every secret). Tests: `MitmLogTest` 6 (success then close failure ->
  one entry; build failure -> one error entry with the next id; 8 threads x 30 requests with
  late failures -> 240 unique ids), `ArpSpoofFormTest` 3 (incl. a control with the old
  in-list shape, and save/restore: SSID and addresses back, password empty), instrumentation
  `MitmRowKeysTest` 2 (real LazyColumn: first visible row id 30 kept after 3 live and 5
  during-detail insertions; control with unkeyed `items(txns)` shifts). Test deps added:
  compose `ui-test-junit4` (androidTest, BOM), `ui-test-manifest` (debug only, declares the
  empty test Activity), espresso-core 3.7.0 (3.5.0 from ui-test 1.12.1 fails on API 36:
  `NoSuchMethodException InputManager.getInstance` while waiting for idle). kotlinx-
  serialization-core 1.8.1 -> 1.12.0 (newest; room-testing's serialization-json resolves to
  1.12.0 too): `connectedDebugAndroidTest` 14/14 twice on the API 36.1 emulator
  (BruciaMigrationTest 5/5, the 12 earlier tests plus MitmRowKeysTest 2); the same 14 passed
  on 1.8.1 first. Kotlin warnings 0 (compile tasks rerun), lint 0 errors / 2 warnings
  (OldTargetApi, Gradle 9.8.2; the serialization NewerVersionAvailable warning is gone, no new
  one). Release APK 18,924,883 B with either version (dex about 24 KB larger uncompressed with 1.12.0).
  `:app:testDebugUnitTest` 879/879 (128 suites), `verifyPaparazziDebug` pass, assemble
  debug/release pass, `tools/test_android_logic.py` 199. Open: phone checks in TESTS.md
  "Batch 14 (APPPOLISH)".
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
  Batch 6 (NOEXC) applied: `pre_build_companion_flags.py` compiles only src/companion/** with
  -fno-exceptions -fno-(asynchronous-)unwind-tables (no try/catch in companion or its 554
  included headers; no catch above companion entry points). LTO hazard found and handled:
  header-inline copies prevail by link order, so companion objects are linked last, and the
  post-link `tools/eh_catch_guard.py` verifies that every function holding one of the 9 source
  catch sites still has an unwind entry and a real catch handler (fails the build otherwise;
  without readelf the flags are not applied). Negative LTO fixtures on Xtensa and RISC-V prove
  the guard fails when the order is wrong. Saved 4.9-12 KB per env (T-Embed 92.62%,
  LAUNCHER_CYD 98.17% so its 99% override stays, CYD 93.77%, lora-pager 92.92%). Other options (LITE compile-outs of keycheck/NRF24/IR/
  diag/GATT, short error codes, NimBLE NDEBUG) need a product decision.
  Batch 7 (LITE, owner-approved): LITE_VERSION builds compile out the companion layer only for
  mifare_keycheck, nrf24_*, ir_tvbgone/ir_rx/ir_tx_raw/ir_jammer, ble_gatt_* and diag (+ its
  counter hooks); group macros in src/companion/config.h. Bruce's menus/CLI untouched (TV-B-Gone,
  NRF24 menus, `ir rx`/`ir tx_raw` still linked on LITE). LITE CAPS: features.mifare_keycheck/
  ir_cmds/ble_gatt/diag = literal false (absent = present, so full builds and older firmware are
  unaffected), no mifare_keycheck_report/_stop, caps.nrf24 false + evidence level none with a
  detail; the commands answer ERR 501 "... not available in this (lite) build". App: central
  CommandCapabilities preflight rejects those commands with "Not available on this firmware
  (compiled out of LITE builds).", key-check/TV-B-Gone/IR-jammer buttons disabled with that
  reason, GATT Explore hidden; IR receive and raw IR replay fall back to Bruce's `ir rx` /
  `ir tx_raw` CLI; Diagnostics already shows "not supported by this firmware". Measured on
  LAUNCHER_CYD-2432S028, cumulative firmware.bin deltas in this order: keycheck -1,680 B,
  IR -3,568, GATT -9,152, diag -2,192, NRF24 -3,568; total -20,160 (2,509,008 -> 2,488,848 B,
  98.17% -> 97.38%, 67,056 B free). LTO repartitioning makes single-step deltas approximate
  (the GATT step includes layout shifts outside companion code). m5stack-core4mb 2,673,872 ->
  2,659,520 B (-14,352; 68.00% -> 67.64%). T-Embed (4,309,632 B) and Cardputer (4,317,392 B)
  byte-identical before/after; non-LITE preprocessed companion TUs are token-identical to HEAD
  for three board macro sets. LAUNCHER_* override lowered to warn 95 / fail 98 (not removed:
  CYD above 92% and the other 16 LAUNCHER_* envs unmeasured). Host: tools/test_lite_companion.py
  (preprocessor-checked CAPS/dispatch on LITE vs full, no-op diag hooks, mock --profile lite).
  Left for a later decision: Brucia's driver-side PN532::mifareKeyCheck (~1 KB, virtual so it
  stays linked on LITE). Not hardware-tested.
  Batch 12 (FWJS): every LAUNCHER_* env built once (tree 401a94f6 + the batch 12 companion serial
  input change, PlatformIO 6.2.0, `tools/build_firmware.py`, budget from check_flash_budget.py).
  All 17 compile; all use custom_4Mb.csv, smallest app slot `factory` 2,555,904 B:

  | env | firmware.bin B | % of slot | free B | status (warn 95 / fail 98) |
  |---|---|---|---|---|
  | LAUNCHER_CYD-2USB | 2,490,640 | 97.45 | 65,264 | WARN |
  | LAUNCHER_CYD-2432S028 | 2,490,704 | 97.45 | 65,200 | WARN |
  | LAUNCHER_CYD-2432W328C | 2,490,240 | 97.43 | 65,664 | WARN |
  | LAUNCHER_CYD-2432W328R-or-S024R | 2,491,920 | 97.50 | 63,984 | WARN |
  | LAUNCHER_CYD-3248S035R | 2,491,424 | 97.48 | 64,480 | WARN |
  | LAUNCHER_CYD-3248S035C | 2,498,064 | 97.74 | 57,840 | WARN |
  | LAUNCHER_elecrow-24B | 2,521,536 | 98.66 | 34,368 | FAIL (+16,751 B over the 98% line) |
  | LAUNCHER_elecrow-28B | 2,521,536 | 98.66 | 34,368 | FAIL (+16,751 B) |
  | LAUNCHER_elecrow-35B | 2,518,656 | 98.54 | 37,248 | FAIL (+13,871 B) |
  | LAUNCHER_elecrow-35Bv2_2 | 2,518,640 | 98.54 | 37,264 | FAIL (+13,855 B) |
  | LAUNCHER_m5stack-cplus1_1 | 2,492,704 | 97.53 | 63,200 | WARN |
  | LAUNCHER_Marauder-V4-V6 | 2,473,920 | 96.79 | 81,984 | WARN |
  | LAUNCHER_Marauder-v61 | 2,473,888 | 96.79 | 82,016 | WARN |
  | LAUNCHER_WaveSentry-R1 | 2,476,192 | 96.88 | 79,712 | WARN |
  | LAUNCHER_Marauder-Mini | 2,471,344 | 96.69 | 84,560 | WARN |
  | LAUNCHER_Marauder-v7 | 2,471,584 | 96.70 | 84,320 | WARN |
  | LAUNCHER_Phantom_S024R | 2,490,416 | 97.44 | 65,488 | WARN |

  Static RAM 93,764-94,576 B on all of them. LAUNCHER_CYD-2432S028 is +224 B against batch 9
  (2,490,480). The 4 elecrow LAUNCHER envs are not in upstream's CI matrices (buil_parallel.yml,
  manual_build_sel_env.yml) nor in Brucia's build.yml; the manual PR_All_envs.yml "all boards"
  run would fail them. Cause is Brucia's companion layer, not upstream: with the companion made
  unreachable (temporary `return` at the top of runCompanionUSB, LTO drops the rest)
  LAUNCHER_elecrow-24B is 2,461,168 B (96.29%), so the companion costs 60,368 B there. The
  elecrow envs carry ~31 KB more Bruce code than the CYD ones with the same companion macro set
  (CC1101/NRF24/W5500/IR, no FM/mic on LITE); that gap was not investigated. Companion-only
  reductions measured on LAUNCHER_elecrow-24B (temporary guards, reverted; LTO makes them
  non-additive): companion Sub-GHz commands/streams out -10,480 B (98.25%), companion NFC
  dispatch out -6,576 B (98.40%), companion Wi-Fi dispatch + sta/probe/monitor streams out
  -9,024 B (98.30%); Sub-GHz + NFC -17,296 B -> 2,504,240 B (97.98%, 545 B under the line);
  Sub-GHz + Wi-Fi -16,896 B -> 2,504,640 B (97.99%, 145 B under). No single companion group
  is enough; the smallest fix that passes needs two of the main app features gone on those
  boards, with almost no margin. Not applied (product decision, like batch 7): options are
  (a) an elecrow-only LITE compile-out of companion Sub-GHz + NFC, (b) an elecrow-only budget
  override at fail 99% (98.66% still leaves 34 KB of the slot free), (c) find what the elecrow
  envs add over CYD on the Bruce side. Threshold decision: LAUNCHER_* stays warn 95 / fail 98;
  it cannot be tightened (fail 97.5 would also fail CYD-3248S035C at 97.74%, and all 17 are
  above 95%, so the warning carries no signal on these envs).
  Batch 13 (ELECROW): accepted at 99% by the owner, not investigated. Option (b) applied:
  ENV_THRESHOLDS gains `LAUNCHER_elecrow-*` warn 95 / fail 99 ahead of `LAUNCHER_*` (first
  match wins), so the 4 elecrow LAUNCHER envs now report WARN (98.54-98.66%, 34,368-37,264 B
  free; the 99% line is 2,530,344 B, about 8.8 KB above LAUNCHER_elecrow-24B). Every other
  LAUNCHER_* env keeps fail 98. Options (a) and (c) not pursued; the ~31 KB elecrow-vs-CYD gap
  stays unexplained. Test: tools/test_flash_budget.py
  test_elecrow_launcher_envs_fail_at_99_percent_other_launchers_at_98. No firmware rebuilt.
  Review fix: Bruce's `ir tx_raw` has two positional args and SimpleCLI (rennancockles b23d3779)
  splits on unquoted spaces, so a third word is "unknown argument"; IrButton.cliRaw now emits
  one double-quoted samples word (also fixes the pre-existing saved-raw-button replay via
  toCli on every build; rows saved before are rewritten at replay by IrButton.replayable in
  the VM's single replayPayload path, used by saved items and geo-signals; no schema change). tools/test_ir_cli_tokenizer.py
  runs the app tests' literals through the real SimpleCLI C sources when .pio/libdeps exists
  (cited port otherwise) plus sendRawCommand's split. The LITE `ir rx` fallback reply comes back
  through commands.cpp routeCommand (CapturingSerialDevice, 3500-char cap) and carries
  "Waiting for signal..." / "Turning off IR LED" (the `ir` composite callback), which the app strips.
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
  First hardware run (T-Embed CC1101, 2026-11-05) found two link defects: (1) a 1.45 KB
  `fs_upload_chunk` frame was lost (no reply in 3 s; a later abort showed size 0) because the
  HWCDC RX ISR drops bytes when the 1024-byte RX queue (`SAFE_STACK_BUFFER_SIZE / 4`) is full
  while the companion loop is busy - app uploads (3072-byte chunks, ~4.2 KB frames) would fail
  the same way; (2) `SUB subghz.raw on` printed `cc1101 Connection OK` (rf_utils.cpp
  initRfModule) into the framed stream and the host lost framing. Fixed in software (batch 8,
  HWLINK): RX buffer sized at boot for a whole frame (PSRAM 8448 B / rx_max 8192, no PSRAM
  2048 B / rx_max 1792), CAPS `rx_max`/`rx_buf`, app and runner size large frames to it (768
  without it), host readers resync on `len + {"v":` and count noise, companion-path module
  prints guarded and IDF logs muted in companion mode (tools/test_companion_print_hygiene.py).
  Simulated (mock RX queue drops bytes, injected noise); nothing re-verified on hardware until
  the coordinator reruns the runner. Batch 9 (PRINTS) closed the remaining print paths in
  software (not hardware-verified): `usbConsole()` (include/globals.h) is Serial outside
  companion mode and, in companion mode, captures text printed on the companion task while
  routeCommand runs a Bruce CLI command into that command's ACK (dropped otherwise). CLI files
  and the module functions they call (custom_ir send*/txIrFile, rf_send readSubFile/txSubFile,
  rf_scan rfReceiveSignal/rf_scan, audio play/tts, sd_functions setupSdCard/copyToFs, display*
  helpers, loopOptions, wifi_common, webui/sniffer/listen, crypto, JS print APIs) print through
  it; task/callback debug prints (audio playback task, Ethernet events, BleKeyboard, pwngrid,
  radio_mem, LoRa/Ethernet SPI fallback, karma/portal, MouseJack, heap) use
  `if (!g_companionActive)`. companion.cpp now also mutes stdout/stderr (bare printf, IDF esp_log
  default writer; console UART0 = ESP32 Serial, secondary USB-Serial-JTAG = S3 HWCDC Serial per
  the pinned sdkconfig) by swapping the shared FILE write hook, and on ESP32-S3 the ROM printf
  copy to USB-Serial-JTAG (`g_usb_print`, checked in the ROM ELF), which is where Arduino
  `log_x`/`ESP_LOGx` in Arduino-compiled code end (log_printf -> ets_printf; the batch 8
  esp_log hook does not see them). tools/test_companion_print_hygiene.py scans 58 files with a
  reasoned allowlist. Still open: interactive apps a CLI command can open (`loader`, file
  manager, menus; they also block the companion loop), third-party libraries in .pio/libdeps,
  ESP32 (non-S3) ROM printf (putc hook has no getter; those envs build with CORE_DEBUG_LEVEL=0;
  closed in batch 15 FWMENUS through the ROM hook addresses, software only),
  JS `serial.readln` reading the framed port during `script_run` (closed in batch 12, below),
  and the hardware rerun.
- Companion serial input (batch 12, FWJS; software only, not hardware-verified). Audit of every
  reader of the USB console that can run in companion mode: direct `Serial` reads in
  src/include/lib are only the frame reader (protocol.cpp `readFrame`, companion.cpp
  `companionPollStop` and the entry drain) and JS `serial.readln` (serial_js.cpp), which now
  returns "" at once in companion mode without reading or flushing. Bruce's CLI reads and
  writes `serialDevice` (USBserial -> Serial): routeCommand already swapped in a capture device
  with no input, but a script's `serial.cmd()` and the JS audio/badusb/subghz helpers call
  `serialCli.parse` on the interpreter task, outside that swap, so `storage write`,
  `*_from_buffer`, `crypto encrypt_to_file` and `storage ymodem` would read the app's frames and
  every CLI reply would print into them (also rf_scan's headless error print and tftLogger's
  async writer). Fix: companion.cpp swaps `serialDevice` for a no-input, no-output device for
  the whole session (`companionClaimSerialDevice`, previous device - USB or BLE API - restored
  on exit); `_readFileFromSerial` and `storage ymodem` return at once in companion mode (ymodem
  before truncating its file); `js run_from_buffer` does not start an empty script; the JS
  error dialog does not wait for a key in companion mode (it kept the interpreter task, and
  every later script_run, busy). The capture device ignores writes from other tasks (it was a
  String data race with tftLogger's async writer). Checked and left: the serialcmds task is
  suspended in companion mode; VectorDisplay's SerialDisplayClass (screenless boards) only calls
  `Serial.available()` and its read is stubbed; HardwareSerial(1)/(2) users (ducky_typer,
  NRF24 UART, RFID125) are other UARTs. tools/test_companion_serial_input.py scans every
  src/include/lib file with a reasoned allowlist and checks the swap, the fail-fast paths and
  the known `serialDevice` assignments (main.cpp, BLE API settings toggle). Its comment
  stripper also fixed test_companion_print_hygiene.py's (a `/*` inside a line comment, e.g.
  `stop/*_stop` in companion.cpp, hid code until the next `*/`). Not covered: on-device prompts
  a script opens under script_run (`keyboard()`, `dialog`, menus) still wait for a person at
  the device and the execution deadline does not interrupt them; interactive apps a routed CLI
  command opens (`loader`, file manager); third-party libraries. Hardware check in TESTS.md.
  Batch 14 (FWSTATE): prompts audited (every JS native and the CLI callbacks a script reaches).
  `bjs_prompt_unavailable(api)` (helpers_js) returns true only in companion mode and writes
  "<api>: skipped, ..." into the script output; keyboard/hex/num (and dialog.prompt) -> "",
  dialog.message/choice/pickFile -> "", menu.show -> -1, dialog.viewFile skipped, viewText drawn
  without the SEL wait, wifi.connectDialog -> false, httpFetch without Wi-Fi skips the menu,
  subghz.read/readRaw(t <= 0) -> "", mic.recordWav maxMs 0 -> ok:false, drawGif(-1) plays once.
  CLI: `loader open` refused in companion mode (routed CMD too); `webui`, `sniffer`, `listen`,
  `sleep` refused only on the interpreter task in companion mode (`cliFromCompanionScript`), so
  the app's Sleep button and console `webui`/`sniffer` keep working. Already guarded before:
  displayInfo/Success/Warning/Error wait flags, the JS error screen, serial.readln. Bruce's
  `loopOptions(..., interpreter=false)` already returns after one pass while a script is in the
  foreground (display.cpp), so menu.show/wifi menu were not blocking from a foreground script
  (menu.show returned index 0 there, an upstream quirk left as is outside companion mode).
  New `tools/test_companion_prompts.py` (10 tests, reasoned allowlist). Left open: a routed `ir rx`
  with an unconfigured IR RX pin and `arp` open a menu (not reachable from a script);
  `badusb.setup()` on USB-HID boards waits for a USB host; a native call that outlives the 20 s
  wall clock (long `delay()`, long radio timeout) still leaves later script_runs `interpreter busy`
  until it returns. Hardware check in TESTS.md "Batch 14 (FWSTATE)".
  Batch 15 (FWMENUS), software only, closing the three leftovers above:
  (1) CMD path. `tools/test_companion_prompts.py` now follows every Bruce CLI callback into the
  module code it calls (heuristic call graph over src/: free functions, same-class methods,
  constructors, member calls with a unique name; `gset*Pin(true)` is the pin menu) and requires a
  companion guard returning first on the way, or a reasoned CMD_ALLOWLIST entry (sniffer mode, the
  SEL-only txIrFile pause, subghz rx / rf scan bounded by their arguments). Audit result, now
  `ok:false` with the reason in ACK.d.out in companion mode: `ir rx` without a usable IR RX pin
  (IrRead skips the pin menu headless in companion mode; companion `ir_rx` answers `IR RX pin not
  configured`), `arp` (host menu, ESC-ended scan), `listen` (port keyboard; was refused only in a
  script), `webui -noAp` without Wi-Fi (network menu), `decrypt`/`crypto decrypt_from_file`/
  `type_from_file` with an empty password (keyboard), `js <file>` (left a task waiting for a
  foreground nobody gives in companion mode, so later script_runs stayed busy); a BadUSB
  `WAIT_FOR_BUTTON_PRESS` ends the payload. Found on the way and fixed: `settings` (no args) and
  every pin setter (BruceConfigPins save -> load) wrote JSON straight to Serial (new hygiene check
  for `serializeJson*(..., Serial)`), ARPScanner early returns kept the lwIP lock (F04). Left as
  is on purpose: routed `webui`/`sniffer`/`sleep` (ESC/key modes the app's console and Sleep
  button use), `rfid emulate` on PN532 (bounded 60 s), `badusb run_from_file` waiting for a USB
  host on USB-HID boards (a host, not a person).
  (2) script_run. Found first: runScriptRun waited for interpreter_state to leave 1, which nothing
  does, so every run answered `script timeout` after 20 s and then ran unbounded (deadline cleared)
  in the background; the stale interpreterTaskHandler also kept later runs `interpreter busy` and,
  by source reading, could stall the on-device Scripts menu after leaving companion mode. Now:
  foreground at once; `bjs_abort_core.h` (host-tested) + `g_bjsAbort`, set when the 15 s bound
  passes, the 20 s wall clock ends or a `stop`/`*_stop` CMD arrives (script_run now polls
  companionPollStop like other blocking ops: PING answered, other CMDs ERR 409), cleared only by
  the interpreter task when it ends (with the deadline and the task handle). Interruptible: the
  QuickJS interrupt, `delay()` (10 ms slices while a companion bound is set; plain vTaskDelay
  otherwise), timers, `wifi.connect`, `nrf24.receive`, notify blink, and every Bruce loop that
  ends on ESC/any-key (check() in globals.h reports them pressed every other read on the
  interpreter task only, while the abort is set; IrRead::loop_headless and the TagOMatic headless
  loops gained a companion-only ESC exit). Not interruptible (documented): `ble.scan`, HTTP,
  `badusb` typing, `mic.recordWav` with a duration; the script stops when they return. No new
  command: `script_stop` is the existing stop convention; the app's ScriptService.stop() still
  only stops waiting (app follow-up: send `script_stop` on the direct channel). Batch 16
  (SCRIPTSTOP), software only: done. ScriptService.stop() sends `script_stop` once through the
  direct stop sender (preflight: stops always permitted) and keeps the script_run wait, whose reply
  shows the outcome; Run is refused until that reply and the stop write, Stop is a no-op when
  idle/after the end/twice, disconnect drops the wait (JVM tests with the real CommandSession and
  the virtual clock). Firmware review: every interpreter exit goes through bjs_task_finished (handle,
  abort, deadline), the stopped reply is sent only after the task ended, an immediate script_run is
  accepted; gap found and fixed: the last poll re-set g_bjsAbort after the task had cleared it (left
  set until the next start; benign, every start resets it), now dropped once the task is gone
  (host model in test_firmware_script_abort.py). Hardware: `hw_acceptance.py --only
  caps,script_stop,diag_counters` (TESTS.md "Batch 16 (SCRIPTSTOP)"); first hardware run: stop
  PASS, follow-up failed on `print` (BruceJS has no global print; now console.log). Also batch 16:
  an uncaught script error (ReferenceError, the bound's `interrupted`) replies ok:false with the
  output instead of ok:true (g_bjsThrew set in js_fatal_error_handler, reset per run; exit() is
  not an error); app examples switched from print() to console.log().
  (3) ESP32 ROM printf: muted through the ROM putc hooks (`_putc1` 0x3ffae014, `_putc2`
  0x3ffae010 in esp32_rev0 and rev300 ROM ELFs, checked by test_companion_print_hygiene.py when
  tool-esp-rom-elfs is installed), verified by read-back of a sink, restored on exit; unknown
  layout falls back to Arduino's uart_install_putc and stays unmuted. ESP32-C5 ROM printf not
  handled. Evidence: host tests only (see TESTS.md "Batch 15 (FWMENUS)").
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
- Batch 14 (PINS), closing the batch 13 merge note (main-tree core4mb 2,660,944 B vs fresh
  2,661,552 B): the main tree's libdeps (installed 2026-10-29) held M5Unified 0.2.28 + M5GFX
  0.2.35, both inside `m5stack/M5Unified @ ^0.2.11` and M5Unified's `M5GFX >=0.2.35`; a fresh
  install takes 0.2.29 + 0.2.36 (published 2026-10-30; M5GFX adds font clip culling, M5Unified
  raises the speaker task stack), which CI's uncached build.yml installs on every run since. Every
  lib_deps entry of all 70 envs is now exact (registry `owner/name @ x.y.z`, Git at a 40-hex
  commit, lora-pager audio driver as commit archive; only `FFat`, an Arduino-ESP32 built-in,
  is unversioned); owner-less specs got their registry owner; M5GFX 0.2.36 and AsyncTCP 3.5.0
  are pinned next to M5Unified / ESPAsyncWebServer. `tools/test_dependency_pins.py` resolves
  extends/`${...}` for every env and fails on any non-exact entry, a missing transitive pin, or
  a duplicate. PlatformIO itself reinstalls a changed pin: `pio pkg install -e` uninstalls the
  specs that left lib_deps (integrity.dat diff) and installs the pinned release when the
  installed one differs (old one moved to `name@version`); `build_firmware.py` now quarantines
  any other installed release of a registry-pinned library that a dependency's range still
  reaches (pkg list prints each package once, so the pin's version, not the printed spec,
  decides) and warns about range-only libraries. Evidence (TESTS.md batch 14): byte-identical
  firmware before/after on all three CI envs and from a clone of the main tree's libdeps.
  Limits: Adafruit SH110X/GFX (installed by Adafruit TCA8418's manifest, not compiled) still
  float; transitive dependencies of non-CI envs (SensorLib, TouchLib, LiteLED, audio driver)
  were not installed or audited; the upstream workflows still `pip install platformio`
  unpinned and keep the `~/.platformio` cache with prefix restore-keys (platform and packages
  are URL-pinned).
