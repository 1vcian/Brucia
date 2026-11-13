# Scoped worker preparation

Status updated 2026-11-04: four local Claude workers started from packets
S08a, S09, S11a and S06-S10a; deliveries still to be reviewed. The explicitly
granted access verified Claude authentication (`loggedIn:true`) and Docker 27.5.1.
The user then requested **Claude Code installed directly on the Mac, without
Linux/Docker**: for this batch this instruction replaces the container/network
isolation requirement described below. The local mode uses allowlist copies,
`--safe-mode`, explicit tools and `--permission-mode dontAsk`, with no bypass flags;
it does not provide OS-level filesystem or network isolation. Host configuration `opus`,
CLI limit 5 USD estimated per worker; actual model recorded in the local JSON logs.
S02/S07 remain blocked by their respective prerequisites. The coordinator reviews every
delivery before integration. No new login or orchestration consent
is required. This index stays with the coordinator, not the workers.

## Authorized orchestration — S06/S08–S11, with prerequisites S02/S07

Local manifest: `.codex_tmp/claude-stabilization-20261104/orchestration.json`.
Each directory contains `task.json`, copies in `baseline/`, SHA-256 hashes and an allowlist;
S08a/S09 include editable copies in `work/` and `run_checks.py`. Only the public Maven
dependencies are exported into `deps/`; no entire cache, credential, APK,
Git history or full checkout was included. The extracts preserve the path,
lines and hash of the original file. These local packets are ignored by Git.
Baselines run by the coordinator: S08a **44 JVM tests**, S09 **27 JVM tests**, all
passed. These are existing tests overlapping with the main suite, not results
produced by Claude nor new stress/interoperability evidence.

| Packet | Assignment and acceptance criterion | Order |
|---|---|---|
| S08a | Deterministic stress of CommandSession/CompanionClient/StreamSubscriptions: fragmented frames, timeouts, cancellations, reconnection, residual callbacks, cleanup OFF. One reproduced fix and synthetic regression; does not certify Android heap or all of the app's queues. | First batch, independent |
| S09 | Scottie synchronization and independent fixtures; AudioDspTest baseline. Self-decoding alone does not prove interoperability. External fixtures/exported specs are still missing: request them with provenance, without inventing them. | First batch, initial review |
| S11a | Read-only audit of RF captures, truncation and capability; distinguish the native and standalone path. No extension of transmissions or radio operations. Later packets are needed to complete the S11 inventory. | First batch, independent |
| S06-S10a | Shared review of connection/port/flash ownership, permissions, Activity recreation and onCleared. Only the necessary coordinator extracts and adapters. Delivers states/owners and the contract of the first component to extract; implementation after coordinator review. | Avoid concurrent S06/S10 refactors |
| S02 | Build/CI configuration and APK/upgrade verification requirements; without signing keys. Do not declare acceptance without a genuinely usable build environment and emulator. | Blocked by prerequisites |
| S07 | Historical Room migration prerequisites. No implementation without authentic v1/v2 schemas/fixtures and no destructive fallback. | Blocked by missing data |

Ordinary rule before dispatch: authentication in the permitted runtime, actual isolation
verified, network limited to the provider and a fresh verification of the hashes. For the
local 2026-11-04 batch the explicit user waiver described above applies. Merely chmod-ing
the baselines and changing the cwd do **not** constitute isolation. No permission-bypass
flags and no exposure of the repo to work around the block.
The coordinator reviews and tests every delivery, integrates in sequence, then runs the
applicable full checks. A refusal or `NEEDS_INPUT` ends that attempt;
any follow-ups resolve technical gaps, they do not reframe the scope to evade controls.
The task status stays in the backlog; preparation is not equivalent to implementation.

## Archive: initial preparation W06 / T06

Explicit user priority: **migrate the existing features toward maintainable
modules before the other development tasks**. The coordinator prepares and
reviews; implementation is assigned to Claude only after the start
instruction. No worker has been launched and no source has been modified for
this migration. The first packet is described here; isolation and harness are
still to be set up.

## Operational contract

- The coordinator keeps the architecture, priorities, inter-module contracts and integration
  decisions. The worker verifies one component, proposes a small patch and stops.
- Minimal and truthful context: the component's purpose, relevant usage, contracts,
  limits and tests. Product narrative or unrelated features are not needed.
  Do not remove information necessary to correctly evaluate the work.
- The tasks are software-quality activities for a security-research project.
  Synthetic fixtures and local tests are used. If a future assignment concerns a
  dual-use capability, the packet must make its use and authorized scope explicit.
- No automatic execution of other tasks after a delivery. The coordinator
  reviews, tests and accepts/rejects; only then does it prepare any follow-ups.
- A `NO_CHANGE`, `NEEDS_INPUT` outcome or a refusal is recorded with its reason.
  Do not start reframing cycles to work around the model's controls.

## Isolation to set up before launch

**Not yet set up or verified.** The text of this file is not a
technical barrier. Changing directory or asking not to read anything else does not limit
filesystem access. A full worktree exposes the entire project anyway.

For each task the coordinator must:

1. Create a packet with only the copies of the allowlisted files, preserving package,
   copyright and licenses, plus a brief and a test harness limited to those files.
2. Record SHA-256 and a map of the original paths. Give the worker editable copies;
   keep the baseline and the harness read-only.
3. Run the worker in an environment with real restrictions: no mount of the repo,
   its ancestors or the maintainer's home; only the packet, output directory,
   temporaries and the necessary public toolchain/dependencies. A prompt is not enough.
4. Exclude Git metadata, chat history, global configurations, signing keys,
   GitHub/ADB credentials and other secrets. Do not mount the entire cache or home for
   convenience; export only the public artifacts needed by the compiler.
5. Limit the network to the transport needed for the provider; the task does not require
   browsing, installations or connections to test systems. Validate this
   configuration and the actual isolation before assigning code.

If the environment does not provide this separation, the status stays `NOT_READY`.
The model, CLI identifier, authentication and cost limits will be verified
at launch time: this file does not assume CLI options or available versions.

### Packet harness

The coordinator prepares a `run_checks` that compiles exclusively the task's files
and runs their tests. For the first three tasks Kotlin/JVM and JUnit are enough; the third
requires also org.json. Use the already-verified versions: Kotlin 2.4.21,
JUnit 4.13.2 and org.json 20260814, with the compiler's public dependencies.
Do not deliver `tools/check_android_changes.py` or an APK as the harness: they give access
to the whole application's dependencies/output and are not needed by the workers.

## Common rules to include in every brief

- Read and modify only the assigned files. Harness and dependencies are read-only.
- Keep the declared APIs and limits; no cross-cutting refactors, new libraries
  or changes to the security criteria to make a test pass.
- First reproduce a problem or identify a genuinely uncovered contractual case.
  Avoid tests that duplicate existing ones or rewrite the implementation.
- Propose at most one circumscribed fix and a few relevant tests. If other
  files or over 160 lines of diff are needed, return `NEEDS_INPUT` with the reason.
- Run only the packet harness. Report commands, outcome and real limits.
- Deliver a diff and `RESULT.md` (at most 20 lines): status, reproduced case, change,
  checks, any doubt. Do not integrate or commit directly into the repo.

## T06 — first task to export: registry of the existing features

**Title:** Extract the existing Android tool catalog into a validated JVM registry.

**Context:** This catalog describes tools in an Android security-research companion.
The task changes metadata organization and lookup only. It does not execute device
commands or change tool behavior. Existing screens use integer tab IDs, titles,
descriptions, category order and nullable hardware capability names.

**Outcome:** Existing tools can be enumerated and looked up from a component with
no Android, Compose, transport or global ViewModel dependencies. A later coordinator
patch connects this registry to the actual UI after review.

### Read-only packet inputs — prepared by coordinator

- `baseline/catalog.kt.txt`: exact `ToolEntry`, `ToolCategory` and `toolCategories`
  declarations from `MainActivity.kt`, through the end of the catalog. Include the
  adjacent compatibility comments and `capForTab` function. No full Activity.
- `baseline/catalog.json`: independently extracted expected category order and all
  tool fields, keyed by legacy tab ID; current inventory is 50 tools. Confirm count
  from source at packaging time; do not silently normalize names/descriptions/caps.
- `baseline/id-map.json`: coordinator-approved literal mapping from each legacy tab
  to an ID such as `org.brucia.wifi.wardrive`. Freeze the complete mapping before
  dispatch; identities must not be generated from localized labels at runtime.
- `run_checks`: Kotlin 2.4.21/JUnit 4.13.2 harness, limited to the files below.
  Coordinator-owned contract tests compare every legacy entry against catalog.json
  and every ID against id-map.json. Worker tests do not replace that baseline.

Record hashes of original source, extracted regions and packet files. Full repo,
Git history, APK, credentials, firmware and user captures are outside the packet.
These inputs are a preparation checklist, not files already generated.

### Writable output allowlist (paths relative to repository when integrated)

- `android/app/src/main/java/app/brucia/features/FeatureRegistry.kt`
- `android/app/src/main/java/app/brucia/features/BuiltinFeatures.kt`
- `android/app/src/test/java/app/brucia/FeatureRegistryTest.kt`
- `RESULT.md` in packet output only; it is not a new repository document.

### Contract

1. Preserve every existing tab ID, category/tool order, title, description and cap.
   A null cap keeps its existing meaning. Do not remove the Wi-Fi gate on the
   dictionary tool in this extraction; operation-level gating is a later migration.
2. Expose immutable descriptors, ordered categories, lookup by stable ID and lookup
   by legacy tab. Unknown lookups return absence, not a fallback tool.
3. Reject duplicate stable IDs and duplicate tab IDs across categories; reject
   malformed/blank IDs. Validate before exposing a usable registry.
4. Registry construction must not retain mutable caller-owned category/tool lists.
   Test input-list mutation after construction; immutable metadata must stay stable.
5. No UI rendering, reflection, classpath discovery, dynamic code loading, network,
   filesystem, new dependencies or changes to protocol/firmware/Gradle.
6. Keep legacy IDs as an adapter, not the public extension identity. This is an
   internal registry; do not invent a versioned public plugin API yet.

### Acceptance and delivery

- Coordinator baseline checks preserve all 50 entries and the approved stable IDs.
- Tests cover cross-category duplicate IDs/tabs, unknown lookup, order preservation
  and input-list mutation; include a custom fixture feature registered without
  changing registry implementation. Do not test only the hardcoded production list.
- Report exact test command/result and return patch plus RESULT.md. `NEEDS_INPUT`
  is required if packet inputs are incomplete or the scope needs another file.
- Task-specific diff allowance: up to 350 added/changed source/test lines because
  this packet includes the existing catalog. This overrides the common 160-line
  guideline for T06 only. Do not rewrite unrelated metadata to fit the budget.

### Coordinator integration gate (after worker delivery, not part of worker scope)

Verify all baseline fields and hashes. Replace the Activity's local catalog/model
and cap lookup with registry imports/adapter; keep its rendering and numeric route
switch unchanged. Review dashboard ordering/search, category availability, badges,
existing navigation and unknown routes. Compile all production Kotlin/Compose and
run unit suites; run fresh Gradle checks when available. Do not mark full feature
modularization complete: controllers, screens and firmware adapters remain coupled.

## T07 — W07 session/command contract (launched 2026-10-30)

**Title:** Extract command correlation ownership into a JVM `CommandSession`.

**Context:** `WardriveViewModel` owns `pendingAcks`/`pendingJsonAcks`, `transmitCommand`,
`dispatchCmd`, `dispatchCmdJson`, `sendAwait`, `fmBegin`, ACK/ERR routing in `handle()`
and the pending-failure part of `disconnect()`. These duplicate the same
register-before-send / timeout / cancel / fail-on-disconnect logic. This task moves
that one responsibility into a tested component; it does not wire it into the ViewModel.

**Read-only inputs (SHA-256 at dispatch):** `protocol/CompanionClient.kt` cfe85065…,
`protocol/AwaitReply.kt` aeb4985a…, `protocol/CommandReply.kt` 0b2175aa…,
`protocol/Protocol.kt` 998a2287…, `transport/Transport.kt` 862d5694…,
`wardrive/WardriveViewModel.kt` 7dd65575… (lines ~476-490, 527-540, 760-830, 930-940,
2965-2980, 3386-3420 only), tests `ProtocolTest.kt` 1102a0b8…, `AwaitReplyTest.kt` 4d3a6028….

**Writable allowlist:** `android/app/src/main/java/app/brucia/session/CommandSession.kt`,
`android/app/src/test/java/app/brucia/CommandSessionTest.kt` (may define a test-local
fake transport). Existing protocol files stay unchanged unless `NEEDS_INPUT` is returned.

**Contract:**
1. One session per `CompanionClient`; reusable `awaitReply`/`routeCommandReply`, request
   ids from `client.nextId()` (unique across reconnects).
2. `await(cmd, timeoutMs)` / `awaitJson(cmd, timeoutMs)`: register before send; release on
   reply, timeout, cancellation and send failure; timeout text "No response from the device."
3. Local send failure (closed/changed transport, preflight rejection, write exception,
   transport closed after write) completes through the same ERR path, never a throw.
   The caps preflight (`rfid125_read`/`ibutton_`) is an injected guard, not a ViewModel read.
4. `route(message)` resolves only the named id; returns the output text for the console.
5. `close(reason)` is idempotent: marks the session closed, fails each pending callback
   exactly once (`(false, reason)` / `(false, {"msg": reason})`) outside any lock, and later
   sends fail immediately. Late replies after close are ignored.
6. `hasPending` for the ping/watchdog logic. No Android, logging, UI state or command log.
7. Pure JVM: kotlinx.coroutines + org.json only. No Gradle/protocol/firmware changes.

**Tests (fake transport, no device):** immediate reply, timeout release, cancel release,
write failure -> ERR, closed transport, preflight rejection, cross-id routing with two
concurrent requests, close fails pending once and ignores late replies, send after close.

**Acceptance:** `cd android && ./gradlew --offline :app:testDebugUnitTest --tests
app.brucia.CommandSessionTest` plus the existing `ProtocolTest`/`AwaitReplyTest`. Diff
<= 300 lines. Coordinator then reviews and, as a separate step, replaces the ViewModel
duplicates (dispatchCmd/sendAwait/fmBegin/handle/disconnect) with the session.

**Isolation actually used:** a Claude subagent in a disposable git worktree (Agent tool
`isolation: worktree`). Scope is enforced by the brief and coordinator diff review, not
technically: the worktree contains the full repository (no signing keys or local.properties,
which are gitignored). This is weaker than the packet isolation above; recorded as a deviation.

## T08 — W08 phone-only pilot: PCAP dissect (launched 2026-10-30)

**Title:** Extract the PCAP dissect analysis and screen state into tested JVM code.

**Context:** `PcapDissectScreen` (tab 45, cap null) opens a user-picked classic pcap and
summarises 802.11 frames on the phone; no device is involved (`vm`/`onConnect` unused).
Its analysis and state live inside the Composable, the file is read with an unbounded
`readBytes()`, the Default-dispatcher loop cannot be cancelled cooperatively, and BSSID
ties are ordered by HashMap iteration.

**Read-only inputs (SHA-256 prefix at dispatch):** `wifi/PcapReader.kt` 3eea7d34,
`wifi/Dot11Frame.kt` 8ed1c47c, `wifi/PcapWriter.kt` b78cce15, test `PcapWriterTest.kt` e316fce9.

**Writable allowlist:** `PcapDissectScreen.kt` (2d6f241d), new `wifi/PcapDissector.kt`,
new test `PcapDissectorTest.kt`.

**Contract:**
1. `PcapDissector` (pure JVM, no Android/Compose): summary (frames, mgmt, ctrl, data,
   eapol, top 8 BSSIDs by count) and rows (first 1000, same fields as today); null when
   the input has no records. Ties ordered deterministically (count desc, BSSID asc).
2. Cooperative cancellation: an injected check called periodically during the loop.
3. Bounded read helper: reject captures over 32 MiB with a clear message instead of
   reading them fully; accept exactly the limit.
4. Screen keeps its signature, route, texts ("Not a classic pcap, or no frames found.",
   "Failed to read: ...") and layout; it only delegates to the extracted code.
5. No changes to PcapReader/Dot11Frame/PcapWriter, Gradle, protocol or firmware.

**Tests (synthetic fixtures via PcapWriter, no device):** per-type counts and EAPOL,
row cap at 1000, deterministic BSSID ranking with ties, invalid/empty/truncated input,
cancellation, size limit boundary.

**Acceptance:** `:app:testDebugUnitTest` (full suite) passes; diff <= 300 lines.
**Isolation:** same worktree deviation as T07.

## T09 — W09 device-backed pilot: iButton (launched 2026-10-30)

**Title:** Extract iButton read/write into a service on a small command-runner interface.

**Context:** `WardriveViewModel.ibuttonRead/ibuttonWrite` (Gadgets, tab 20) send
`ibutton_read` / `ibutton_write <16 hex>` through `dispatchCmd` with a 650 s timeout,
although the firmware op is bounded (~10 x 30 ms read, < 1 s write; ACK `id=<hex>` /
`no iButton` / `written` / `invalid id (need 16 hex chars)`). There is no Stop, no
single owner for overlapping operations, and the id is validated only on the device.
Stock T-Embed has no iButton interface, so the missing-module path is the common case.

**Read-only inputs (SHA-256 prefix):** `session/CommandSession.kt` a2aac60c,
`CommandSessionTest.kt` 06cb8b56, `WardriveViewModel.kt` f1b27799 (lines 3840-3890 only),
firmware `src/companion/system.cpp` 5b12e327 (145-170, 236-256), `src/modules/others/
ibutton.cpp` e33f95b6 (150-192).

**Writable allowlist:** new `session/CommandRunner.kt`, new `ibutton/IButtonService.kt`,
new test `IButtonServiceTest.kt`. ViewModel/UI wiring is the coordinator's step.

**Contract:**
1. `CommandRunner`: `suspend fun run(cmd, timeoutMs): Pair<Boolean, String>`, the small
   service interface features use instead of the ViewModel (the VM adapter adds logging).
2. `IButtonService` (pure JVM): status `StateFlow<String>`, busy flag, `read()`,
   `write(hex)`, `stop()`. Status texts preserved from the ViewModel; not connected and
   missing interface fail locally without sending ("No confirmed iButton interface").
3. Bounded timeout (default 10 s, named constant) instead of 650 s.
4. One owner: a new operation or `stop()` cancels the previous one ("Stopped."); a
   superseded or cancelled operation never overwrites status.
5. Local id validation mirroring firmware (strip `:`/spaces, exactly 16 hex chars);
   invalid input fails locally with the firmware message, normalized id is sent.
6. Disconnect (session close) reports "Disconnected"; after reconnect a new runner
   session works. Protocol v0 and firmware unchanged.

**Tests:** fake transport + real `CommandSession` behind the runner: read ok/no key,
write ok/invalid local/firmware reject, not connected, missing interface (no frame),
timeout, stop, supersede, disconnect then reconnect.

**Acceptance:** full `:app:testDebugUnitTest`; diff <= 300 lines. Isolation as T07;
the worktree HEAD predates `CommandSession`, so the worker copies the two read-only
session files from the main checkout without modifying them.

## T10 — W10 firmware adapter: iButton (launched 2026-10-30)

**Title:** Share one Arduino-free iButton core between standalone, file and companion paths.

**Context:** `ibutton.cpp` duplicates ROM read (interactive `readKey`, headless read),
hex parsing (headless write, `loadKey`) and swaps the global `oneWire` pointer for a
headless write. Companion `ibutton_*` has no board guard: it drives `bruceConfigPins.iButton`,
whose default 0 is the S3 boot strap pin. CAPS never reports `ibutton`, so the app keeps
the controls disabled (docs/HARDWARE_CAPABILITIES.md asks for a configuration path first).
The Bruce serial CLI has no iButton command; adding one is out of scope.

**Inputs (SHA-256 prefix):** `src/modules/others/ibutton.cpp` e33f95b6, `ibutton.h` 40a28242,
`src/companion/system.cpp` 5b12e327 (iButton parts), `src/companion/capabilities.cpp`
7367a90d (sendCaps), `src/core/configPins.h` f702b91e (read-only),
`tools/test_firmware_storage.py` 1a60cd58 (read-only pattern), `docs/HARDWARE_CAPABILITIES.md` 4a1dca83.

**Writable allowlist:** new `src/modules/others/ibutton_core.{h,cpp}`, `ibutton.cpp`,
`ibutton.h`, `src/companion/system.cpp` and `capabilities.cpp` (iButton lines only),
new `tools/test_firmware_ibutton.py`, the iButton row of HARDWARE_CAPABILITIES.md.

**Contract:**
1. Core (no Arduino/OneWire/String): id parse (strip ':'/spaces, exactly 16 hex), format,
   Dallas CRC8, pin-usable rule (pin > 0 and < GPIO max; 0 = unset/strap, -1 = disabled),
   ROM read with retries over a small bus interface (reset/write/read/delay/cancel/retry hook).
2. Standalone menu behavior preserved exactly (stop on no presence, 20 CRC retries with
   its screen messages, Esc cancel, bad-CRC data kept and shown). Headless read keeps
   10 attempts x 30 ms and success only with valid CRC. No global pointer swap.
3. Companion: `ibutton_read/write` return ERR 409 "iButton pin not configured" when the pin
   is not usable, before touching any GPIO. CAPS `ibutton` = compiled (not LITE) and pin
   usable (configured, not probed: 1-Wire presence needs a key). Protocol v0 unchanged.
4. Native host test (clang++/g++ via Python, like test_firmware_storage.py): parse/format,
   CRC known vectors, pin rule, ROM read success/no presence/CRC retry/exhaustion/cancel.

**Acceptance:** native test passes; coordinator builds only `lilygo-t-embed-cc1101` (other
boards at release, community-tested). Isolation as T07.

## T11a — W11 batch 1: operation base + clock + Wi-Fi MAC (launched 2026-10-30)

**Title:** Derive a reusable single-owner device operation from IButtonService; migrate
clock and Wi-Fi MAC onto it.

**Context:** W09's `IButtonService` holds the tested pattern (status/busy flows, generation
token, supersede, Stop, local checks, bounded timeout over `CommandRunner`). The VM still
has 65 `dispatchCmd` callers. Clock (`clock_get`/`clock_set <epoch>`, firmware rejects
epoch < 1e9 "implausible epoch") and Wi-Fi MAC (`wifi_get_mac`, `wifi_set_mac <aa:bb:..|random>`,
firmware requires 17 chars xx:xx:..) are bounded instant commands with VM-owned status.

**Inputs (SHA-256 prefix):** `ibutton/IButtonService.kt` 05bd869d, `IButtonServiceTest.kt`
54acc294 (must pass unchanged), `session/CommandRunner.kt` 4e919bbb, `session/CommandSession.kt`
a2aac60c, `WardriveViewModel.kt` f21dbb2c (clock/MAC regions only), firmware `wifi.cpp`
30717217 (815-850), `system.cpp` 5b8ab2b3 (175-230), `src/core/wifi/wifi_mac.cpp` (30-40).

**Writable allowlist:** new `session/DeviceOperation.kt`; `ibutton/IButtonService.kt`
(refactor onto it, public API unchanged); new `device/ClockService.kt`,
`wifi/WifiMacService.kt`; new tests `DeviceOperationTest.kt`, `ClockServiceTest.kt`,
`WifiMacServiceTest.kt`. VM/UI wiring stays with the coordinator.

**Contract:** status texts preserved from the VM; local validation mirrors firmware
(epoch >= 1e9; MAC format or "random", blank -> random); 10 s default timeout; Stop and
supersede semantics identical to IButtonService; `IButtonServiceTest` unchanged and green.

**Acceptance:** full `:app:testDebugUnitTest`; diff <= 450 lines. Isolation as T07.

## T11b — W11 batch 2: script, device appearance, Wi-Fi join, ESP-NOW (launched 2026-10-30)

**Title:** Migrate four small device tool families onto `DeviceOperation`.

**Scope:** `scriptRun` (firmware: 15 s execution bound, > 6000 chars "script too large",
"interpreter busy"), theme/LED family (`theme_set`, `led_set`, `config_save`, `led_bright`,
`led_effect`; shared `themeStatus`; success updates the app's DeviceInfo theme/LED),
Wi-Fi station join (`wifi_status`, `wifi_connect <ssid>\x1f<pass>` up to ~10 s,
`wifi_disconnect`), ESP-NOW (`espnow_send`). Fire-and-forget `scr`, `led`, `scr_bright`
stay as they are.

**Inputs (SHA-256 prefix):** `session/DeviceOperation.kt` 97036a18 and its test d7063429
(additive change allowed: per-call timeout), `wifi/WifiMacService.kt` dcc77c82 and test
af851910 (pattern), `WardriveViewModel.kt` 27cfeae9 (the four regions only), firmware
`system.cpp` 5b8ab2b3, `commands.cpp` 20e5b6a1, `wifi.cpp` 30717217 (handlers only).

**Writable allowlist:** `DeviceOperation.kt` (+test), new `device/ScriptService.kt`,
`device/DeviceAppearanceService.kt`, `wifi/WifiJoinService.kt`, `device/EspNowService.kt`
and one test each. VM/UI wiring stays with the coordinator.

**Contract:** texts preserved from the VM; local checks mirror firmware where it rejects
(script blank/too large, SSID blank, message blank); timeouts: script 25 s, join 30 s,
others 10 s; appearance success callbacks injected (no Android types).

**Acceptance:** full `:app:testDebugUnitTest`; diff <= 500 lines. Isolation as T07.

## T11c — W11 batch 3: speaker and mic recording (launched 2026-10-30)

**Title:** Migrate speaker volume/tone/file/song/DTMF and mic recording onto `DeviceOperation`.

**Firmware facts (audio.cpp 49938c55):** `speaker_tone` freq 1..20000, blocks <= 10 s;
`speaker_play <path>` blocks until the file ends (no stop); `speaker_song <rtttl>` is
async and `speaker_stop` interrupts it; `speaker_dtmf <key> [ms]`; `speaker_volume <0-100>`
persists; `mic_record <secs>` clamps 1..30. PCM streaming (`spkr_pcm_*`) stays as is.

**Inputs:** `session/DeviceOperation.kt` 40d163ee (+test b9b4b96c; additive change allowed:
a result of null keeps the status), `device/DeviceAppearanceService.kt` c07016ab (pattern),
`WardriveViewModel.kt` 9c21f395 (speaker/mic/DTMF regions only).

**Writable:** `DeviceOperation.kt` (+test), new `audio/SpeakerService.kt`,
`audio/MicRecordService.kt` and tests. VM/UI wiring stays with the coordinator.

**Contract:** VM texts preserved; local checks mirror firmware (tone freq 1..20000 and
ms > 0, blank path, secs <= 0); timeouts: tone 15 s, file 600 s (blocking play), mic
min(secs, 30) + 10 s, others 10 s; song state (`songPlaying`) cleared on failure and on
stop; `speaker_stop`, song start success and DTMF do not change the status text.

**Acceptance:** full `:app:testDebugUnitTest`; diff <= 450 lines. Isolation as T07.

## T11d / T11e — W11 batch 4, two parallel workers (launched 2026-10-30)

Run in parallel on disjoint files. Neither may modify shared files (`session/*`, other
services, VM, UI, firmware, Gradle); both only add new service + test files. Both families
are existing, authorized-use features of the research tool; the task changes app-side
command ownership only (no new capability, no change to what is sent).

**T11d - BLE (HID keyboard/presenter, BadBLE, media hijack):** `ble/BleHidService`,
`ble/BadBleService`, `ble/BleMediaService` (+tests). HID keys/typing are sent alongside
(never strand a status), status texts preserved, BLE MAC format check kept, timeouts
derived from `bluetooth.cpp` bounds. GATT stays in the VM (event-driven tree).

**T11e - IR (TV-B-Gone, receive once, capture remote key):** `ir/IrReceiveService`
(receive-once result and remote-key capture with injected save callback), `ir/TvBGoneService`
(+tests). Timeouts from `infrared.cpp`: `ir_rx` ~10 s -> 20 s, TV-B-Gone up to 60 s -> 75 s.
`learnIr` (legacy `ir rx`, persists directly) and fire-and-forget `ir_tx_raw` stay in the VM.

**Inputs (SHA-256 prefix):** `session/DeviceOperation.kt` ae652b2f, `DeviceOperationTest.kt`
35e570d1, `audio/SpeakerService.kt` 929dfcec (alongside pattern), `WardriveViewModel.kt`
1ba1bdc0 (family regions only), firmware `bluetooth.cpp` f7256dc9, `infrared.cpp` e4feb6c9.

**Acceptance (each):** full `:app:testDebugUnitTest` in its worktree; diff <= 450 lines;
coordinator integrates both, wires VM/UI and reruns the full suite. Isolation as T07.

## T11f / T11g — W11 batch 5, two parallel workers (launched 2026-10-30)

Same rules as T11d/T11e: disjoint new files only, no shared-file edits, existing
authorized-use features, app-side command ownership only.

**T11f - LoRa + Ethernet:** `lora/LoraService` (config/send on one owner; `lora_recv` via
`startJson`, packet from the ACK data handed to an injected callback, receiving flag,
local stop) and `net/EthernetService` (`eth_status` via `startJson` -> `EthStatus`,
`eth_config` refreshing status on success, `eth_dhcp_starve` / `eth_mac_flood`). Timeouts
from `network.cpp` / LoRa handlers (recv: secs + margin).

**T11g - NFC (advanced tiles):** `nfc/NfcToolsService` with separate owners for NDEF push,
EMV read (masked), UID write and the shared advanced line (amiibo, nested, mfkey32,
srix_read, rfid125_read). Timeouts from `nfc.cpp`. Legacy `rfid ...` console commands,
saved-item persistence and the Mifare key-check batches stay in the VM.

**Inputs (SHA-256 prefix):** `session/DeviceOperation.kt` 6152af8d, `JsonCommandRunner.kt`
e0046a5c, `DeviceOperationTest.kt` 974fbbcc, `WardriveViewModel.kt` f7debe6e (family regions
and the LoraPacket/EthStatus classes only), firmware `network.cpp` f3e63366, `nfc.cpp` 665a6bde.

**Acceptance (each):** full `:app:testDebugUnitTest` in its worktree; diff <= 450 lines;
coordinator integrates both, adds the VM JSON runner adapter, wires and reruns the suite.

## T11h / T11i — W11 batch 6: loop and long-operation engines (launched 2026-10-30)

Design (coordinator): the remaining VM families need two shared engines before migration.
Workers build the engines and their tests only; the coordinator migrates the VM callers.

**T11h - `session/BurstLoop` + `BurstLoopRegistry`:** generalizes `launchBurstLoop` and the
inline BLE-spam loop (11 loops). Single owner (start replaces a running loop silently),
sequential bursts each awaited through `CommandRunner`, ends when the link closes, a
non-blank failure ends it and becomes the status, only the owning job resets state, and
stop(silent) restores the stopped text only while the running text is still shown. Every
loop registers at construction, so `registry.stopAll(silent)` cannot miss one (the
2026-10-30 bug: Sub-GHz/IR jammer, ARP spoof and iBeacon were missing from stopAll).

**T11i - `session/LongOperation`:** a bounded one-shot command that the firmware can end
early with a separate command (`stop`, `brucegotchi_stop`). `requestStop()` sends that command
alongside and keeps waiting for the final ACK (unlike `DeviceOperation.stop`, which abandons
it); timeout = duration + margin. Thin services for Responder, Karma and Brucegotchi on it.

**Inputs:** `session/DeviceOperation.kt` + test, `WardriveViewModel.kt` (launchBurstLoop,
startBleSpam/stopBleSpam, one loop caller, stopAllBurstLoops; Responder/Karma/Brucegotchi/
stopLongOp/stopBrucegotchi), firmware `wifi.cpp` long-op handlers. Isolation as T07.

## T11j / T11k — W11 batch 7, two parallel workers (launched 2026-10-30, after 0.1.2)

Remaining VM callers: fire-and-forget/console/PCM-stream commands stay on `dispatchCmd`
(no state to own; logged and bounded). Wi-Fi capture+crack is the next, separate batch.

**T11j - bounded monitors:** `monitor/JamDetectService` (one-shot `jam_detect`),
`monitor/WidsService` (back-to-back `jam_detect` windows, threshold alerts, event list;
BUG to fix: after a disconnect the VM loop spins on immediate "Not connected." failures
and is not covered by stop-all - it must end when the link is gone and be stoppable from
the coordinator's stop-all), `subghz/SubGhzBruteforceService` (one-shot, bounded).

**T11k - event-driven sessions:** `wifi/EvilPortalService` (optional template push through an
injected suspend pusher, start/stop, active flag; captured creds stay in the VM EVENT
handler) and `ble/GattService` (connect with connecting flag and an injected reset callback for
the VM-owned EVENT tree; per-characteristic reads alongside, results map).

Same rules as earlier batches: disjoint new files, no shared-file edits, texts preserved,
timeouts from firmware bounds, `@Test(timeout = 5_000)`. Isolation as T07.

## T11l — offline dictionary lifecycle (coordinator, completed 2026-10-31)

Implemented directly at the user's request, without launching a worker. New
`wifi/DictionaryTestService` owns CPU job cancellation and status; ViewModel keeps
Android storage/asset adapters and target import. Existing crypto unchanged.
Seven synthetic regressions and full cached-source checks passed; see TESTS.md.
W11m (capture workflow) was subsequently implemented by the coordinator; see below.

## T11m — capture orchestration (coordinator, completed 2026-10-31)

Implemented at the user's request with existing commands and parsers unchanged.
`wifi/CaptureWorkflow` owns orchestration, freshness comparison and guarded import;
the VM supplies session-bound IO and retains the selected target/local-copy adapter.
11 synthetic regressions; full cached Kotlin compile/tests recorded in TESTS.md.
No worker launched. Next task W11n is tracked in backlog.md.

## T11n — Mifare batches / macro runner (coordinator, completed 2026-10-31)

Mifare stage implemented directly at the user's request; no worker launched.
`nfc/MifareKeyCheckService` owns batches/state/cancellation; the ViewModel provides
an original-session JSON runner and existing dictionary source. UI exposes Stop
batches and distinguishes failure/cancellation from completion. Tests cover fake
runners and real `CommandSession` over simulated transport; see TESTS.md.
The firmware does not report tag identity or partial scans and cannot abort a
running batch; follow-up is tracked in backlog.md.
Macro stage subsequently implemented on explicit request, without workers:
`automation/MacroRunner` owns sequencing, original-session binding, step snapshots,
error handling and cancellation. ViewModel retains persistence and logged IO.
Twelve macro tests plus combined full cached checks passed; see TESTS.md.
W11o was subsequently completed by the coordinator; see below.

## T11o — service lifecycle review (coordinator, completed 2026-10-31)

Implemented directly on explicit request; no worker launched. Reviewed remaining
callback adapters, wired the explicit service cleanup inventory, extracted parallel
command ownership, removed the unused JSON dispatcher and fixed stale result mapper
side effects/cancelled-scope busy state. Tests use actual services and CommandSession
with simulated IO; counts and limitations are in TESTS.md.
W11p was subsequently completed by the coordinator; see below.

## T11p — file transfer ownership (coordinator, completed 2026-11-01)

Implemented directly on explicit request; no worker launched. `FileTransferService`
owns jobs, per-consumer supersession and local processing; `SessionFileLink` reuses
CommandSession/DeviceFileReader and the shared wire mutex. ViewModel retains Android
URI/storage and capture parsing adapters. The portal awaits the actual owned upload,
with bidirectional cancellation and no late start. Local staging, original-session
binding and verified-file checks are tested with synthetic IO; see TESTS.md.
S04 below now covers firmware transactions; provider writes remain non-transactional. W11q was subsequently
completed by the coordinator; see below.

## T11q — FM/SSTV lifecycle (coordinator, completed 2026-11-01)

Implemented directly on explicit request; no worker launched. `PcmStreamService`
owns preparation through cleanup; `SessionPcmLink` provides original-session IO.
Corrected speaker JSON arguments to the existing firmware contract, added correlated
structured command waits and cooperative encode/decode cancellation. The ViewModel
keeps Android factories and image snapshots; PCM format and SSTV samples are unchanged.
Seventeen regression cases plus the full cached suite passed; see TESTS.md for exact
evidence and physical/codec/firmware buffer-loss limits.
S01 was subsequently completed by the coordinator (see below). The next scoped
software task is S06 in backlog.md; do not launch workers or chain it automatically.

## Migration follow-ups — HOLD, no automatic chaining

After accepting T06, prepare one packet at a time with actual source boundaries:

1. **W07: session/service contracts.** Extract ownership and fake transport support
   for one narrow existing path; preserve cancellation/disconnect behavior. Coordinate
   E01 with S06/S10. Do not pass the entire ViewModel to a worker.
2. **W08: phone-only pilot.** Extract one existing file analyzer, its state and UI
   adapter. Prove it runs with fixtures without a connected device; no new analyzer.
3. **W09: device-backed pilot.** Extract one existing reader using W07 services;
   verify missing hardware, timeout, Stop and reconnect. Preserve protocol v0.
4. **W10: firmware adapter.** Reuse the corresponding service/driver behind standalone,
   CLI and companion interfaces; prove cleanup and board guards with native tests
   and affected builds. Avoid importing Android concepts into firmware drivers.
5. **W11+: remaining feature families.** Migrate in batches only after both pilots
   are accepted. Then derive templates/SDK from the tested pattern.

Each follow-up needs a new explicit file allowlist, frozen baseline and acceptance
harness after reviewing the prior result. No bulk rewrite or feature additions.

## T01 — brief to export

**Title:** Review bounded PCM recording and export ownership.

**Context:** This module buffers S16LE mono audio for a research application.
The consumer may pause/disconnect, export a snapshot asynchronously, retry an
export after IO failure, or explicitly discard a recording.

**Read/write allowlist:**

- `android/app/src/main/java/app/brucia/audio/RecordingBuffer.kt`
- `android/app/src/test/java/app/brucia/RecordingBufferTest.kt`

**Contract:** Capture never exceeds its even-byte limit. Odd PCM chunks are rejected.
Automatic stop retains unsaved samples. Starting must not overwrite pending audio.
Failed export does not commit a snapshot. A successful old export cannot delete
a later recording. UI and IO threads can call the module concurrently.

**Task:** Review uncovered boundary/race cases. Add up to four focused regression
cases and fix only a demonstrated violation, preserving the existing public API.
Do not change the default duration, UI or export format.

**Acceptance:** Assigned suite passes; memory stays bounded; snapshot ownership and
cancellation remain correct. `NO_CHANGE` is valid when the review finds no gap.

## T02 — brief to export

**Title:** Review bounded HTTP/1 inspection parser.

**Context:** This is a byte parser used by an authorized laboratory inspection
proxy. Work consists of in-memory fixtures; sockets, interception and live targets
are outside this task. The caller owns and closes the input stream.

**Read/write allowlist:**

- `android/app/src/main/java/app/brucia/mitm/HttpWire.kt`
- `android/app/src/test/java/app/brucia/HttpWireTest.kt`

**Contract:** Body cap 4 MiB, line cap 8192 bytes, at most 100 headers/64 KiB and a
120-second message budget. Incomplete or ambiguous framing throws an IOException,
not a successful partial message. Preserve next-message boundaries, HEAD/no-body
semantics and bounded interim responses. Expect and protocol switching remain
explicitly unsupported; do not add protocols or weaken resource limits.

**Task:** Review uncovered framing/progress/deadline cases and add up to four
meaningful tests. Make only a minimal correction for a reproduced violation.
Use synthetic streams and a fake monotonic clock where needed.

**Acceptance:** Valid fixtures still parse; malformed/truncated messages cannot
escape bounds or silently consume another message. No live network tests required.

## T03 — brief to export

**Title:** Review deterministic release asset selection.

**Context:** This module parses release JSON for application/firmware updates.
It accepts metadata for one configured GitHub repository; it does not perform
network requests. Fixtures are synthetic and contain no credentials.

**Read/write allowlist:**

- `android/app/src/main/java/app/brucia/update/ReleaseCatalog.kt`
- `android/app/src/test/java/app/brucia/ReleaseCatalogTest.kt`

**Contract:** Only stable numeric release tags. Reject malformed draft/prerelease
flags. Assets must use credential-free HTTPS, the configured GitHub repository and
the same release. Missing images have no implicit fallback. Preserve case-sensitive
board environment names and the existing T-Embed alias. Keep existing return types.

**Task:** Review malformed URLs/metadata, duplicate or ambiguous assets and boundary
names. Add up to four nonduplicative tests; fix only a demonstrated contract breach.
Duplicate canonical boards or multiple APK candidates must fail explicitly; do not pick the first asset. URL paths must identify the declared filename exactly, with no query/fragment or nonstandard port.

**Acceptance:** Existing fixtures pass; invalid metadata never selects an unrelated
asset; valid board identities are preserved. No API calls or installer execution.

## T04 — do not export yet

Possible later packet: `src/companion/codec.cpp`, `src/companion/codec.h` and
`tools/test_firmware_codec.py`. Review RFC Base64 edge cases against an independent
reference while preserving documented unpadded/whitespace compatibility. Prepare
its full contract and harness only after the first batch's master review.

## S01 — coordinator integration review, not a worker packet

Completed by the coordinator on 2026-11-01. `AppUpdater.fetchSnapshot()` reads
`/latest` once; `ReleaseUpdateService` publishes version, notes, APK, boards and
selection in the same state. Refresh invalidates before the IO; errors and cancellation
do not restore previous assets. Clicks from stale selections are rejected.
Home and Updates consume the same snapshot; the download progress is separate.
Refresh blocked during download/installer and flash; a compatible image is required
before taking the USB port. No change to protocol, firmware or version.

Nine synthetic regressions; full cached compilation: 156 Kotlin files,
533 tests / 86 non-Paparazzi suites. Gradle/lint blocked by the global lock; live OTA,
Android permissions and physical flash not verified. For the evidence see TESTS.md.
S03 was subsequently completed (see below); S04 completed below; next S06, S02 remains blocked.
No worker to launch automatically. The full APK/flash lifecycle stays
in S06/S10; this integration covers the snapshot and its consumption points.

## S03 — coordinator capability/lifecycle matrix, completed 2026-11-04

Implemented directly; no worker launched. The existing controls checklist now has
a software matrix covering all 51 destinations, separating actual JVM evidence
from Android/hardware acceptance. `CommandCapabilities` gates known hardware CMDs;
`StreamSubscriptions` owns SUB waits, supersession, input checks and cleanup even
when ACKs are missing. Fixed BurstLoop/WIDS startup/cancellation, duration bounds,
LoRa Stop/receive flags, mixed offline/device screen gating and failed RF/NFC saves.
Audio subscriptions react to connection/capability changes; DTMF/recording respect
failed mic starts. Firmware/protocol wire format and release version are unchanged.

Evidence: 24 added regressions; cached full Kotlin compilation 158 files and
557 tests / 88 non-Paparazzi suites pass. 51/51 matrix-to-catalog correspondence;
Gradle/lint still blocked by the global wrapper lock. No device, phone permission,
UI lifecycle or physical shutdown acceptance. See TESTS.md and the matrix's open
boundaries, tracked in S02/S04–S11. S04 was subsequently completed below; next S06
only on explicit request; no automatic worker launch.

## S04 — coordinator transactional uploads, completed 2026-11-04

Implemented directly; no worker launched. Managed uploads require a new additive
CAPS flag and matching begin/chunk/commit ACKs; older verified-file reads continue
working. The Arduino-free transaction engine stages/verifies, journals FAT backups,
rolls back failed promotion and retains recovery artifacts when rollback is impossible.
LittleFS uses rename replacement. Abort/idle/exit cleanup and legacy-write exclusion
are wired into the companion lifecycle. Lost commit replies never trigger replay.

Evidence: 563 JVM tests / 88 suites with 158 production Kotlin files, 41 UBSan engine
failure/reset scenarios, native storage/codec/framing and mock protocol checks, plus
three firmware targets; see TESTS.md for build details and physical/Android limits.
FAT is recoverable, not atomically visible or certified against power loss. Next S06:
connection/session and installer/flash job ownership. S02/S05/S07 stay blocked for
their recorded prerequisites; do not automatically launch workers or chain tasks.

## Coordinator acceptance

Before packaging, compute SHA-256 for each allowlisted input and record it in the
packet manifest. Reject automatic patch application if any source has changed.
Review the diff and its real call sites, rerun module and integration tests, and
record the outcome under the corresponding W task in backlog.md. No worker auto-commits.
