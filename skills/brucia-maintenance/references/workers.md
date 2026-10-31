# Scoped worker preparation

Stato: **0.1.2 pubblicata; W11 lotto 7 (T11j monitor + T11k Evil Portal/GATT) completato e revisionato (esiti in backlog)**; altri pacchetti preparati, non avviati.
L'avvio richiede la successiva istruzione dell'utente. Questo indice rimane al
coordinatore; al worker si consegna soltanto il pacchetto del task selezionato.

## Prossima assegnazione: W06 / T06

Priorità esplicita dell'utente: **migrare le funzionalità esistenti verso moduli
manutenibili prima degli altri task di sviluppo**. Il coordinatore prepara e
revisiona; l'implementazione viene assegnata a Claude soltanto dopo l'istruzione
di avvio. Nessun worker è stato lanciato e nessun sorgente è stato modificato per
questa migrazione. Il primo pacchetto è descritto qui; isolamento e harness sono
ancora da predisporre.

## Contratto operativo

- Il coordinatore conserva architettura, priorità, contratti fra moduli e decisioni
  d'integrazione. Il worker verifica un componente, propone una patch piccola e termina.
- Contesto minimo e veritiero: scopo del componente, impiego rilevante, contratti,
  limiti e test. Non servono narrazione del prodotto o funzionalità estranee.
  Non eliminare informazioni necessarie a valutare correttamente il lavoro.
- I task sono attività di qualità software per un progetto di ricerca in sicurezza.
  Si usano fixture sintetiche e test locali. Se un incarico futuro riguarda una
  capacità duale, il pacchetto deve esplicitarne uso e perimetro autorizzato.
- Nessuna esecuzione automatica di altri task dopo una consegna. Il coordinatore
  rivede, prova e accetta/rifiuta; solo allora prepara eventuali follow-up.
- Un esito `NO_CHANGE`, `NEEDS_INPUT` o un rifiuto si registra con la motivazione.
  Non avviare cicli di riformulazione per aggirare controlli del modello.

## Isolamento da predisporre prima dell'avvio

**Non ancora predisposto né verificato.** Il testo di questo file non è una
barriera tecnica. Cambiare directory o chiedere di non leggere altro non limita
l'accesso al filesystem. Un worktree completo espone comunque l'intero progetto.

Per ogni task il coordinatore dovrà:

1. Creare un pacchetto con le sole copie dei file in allowlist, preservando package,
   copyright e licenze, più un brief e un harness di test limitato a quei file.
2. Registrare SHA-256 e mappa dei percorsi originali. Dare al worker copie modificabili;
   conservare la baseline e l'harness in sola lettura.
3. Eseguire il worker in un ambiente con restrizioni reali: nessun mount della repo,
   dei suoi antenati o della home del maintainer; solo pacchetto, directory output,
   temporanei e toolchain/dependency pubbliche necessarie. Non basta un prompt.
4. Escludere Git metadata, cronologia chat, configurazioni globali, chiavi di firma,
   credenziali GitHub/ADB e altri segreti. Non montare l'intera cache o home per
   comodità; esportare soltanto gli artefatti pubblici necessari al compilatore.
5. Limitare la rete al trasporto necessario al provider; il task non richiede
   navigazione, installazioni o connessioni a sistemi di prova. Validare questa
   configurazione e l'isolamento effettivo prima di assegnare codice.

Se l'ambiente non offre questa separazione, lo stato resta `NOT_READY`.
Modello, identificativo CLI, autenticazione e limiti di costo saranno verificati
al momento dell'avvio: questo file non assume opzioni CLI o versioni disponibili.

### Harness del pacchetto

Il coordinatore prepara un `run_checks` che compila esclusivamente i file del task
e avvia i relativi test. Per i primi tre task bastano Kotlin/JVM e JUnit; il terzo
richiede anche org.json. Usare le versioni già verificate: Kotlin 2.0.21,
JUnit 4.13.2 e org.json 20240303, con le dipendenze pubbliche del compilatore.
Non consegnare `tools/check_android_changes.py` o un APK come harness: danno accesso
a dipendenze/output dell'intera applicazione e non sono necessari ai worker.

## Regole comuni da includere in ogni brief

- Leggi e modifica solo i file assegnati. Harness e dependency sono in sola lettura.
- Mantieni le API e i limiti dichiarati; niente refactor trasversali, nuove librerie
  o cambi dei criteri di sicurezza per far passare un test.
- Prima riproduci un problema o identifica un caso contrattuale realmente scoperto.
  Evita test che duplicano quelli esistenti o riscrivono l'implementazione.
- Proponi al massimo una correzione circoscritta e pochi test pertinenti. Se servono
  altri file o oltre 160 righe di diff, restituisci `NEEDS_INPUT` con la ragione.
- Esegui solo l'harness del pacchetto. Riporta comandi, esito e limiti reali.
- Consegna diff e `RESULT.md` (massimo 20 righe): stato, caso riprodotto, cambiamento,
  verifiche, eventuale dubbio. Non integrare o committare direttamente nella repo.

## T06 — primo task da esportare: registro delle feature esistenti

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
- `run_checks`: Kotlin 2.0.21/JUnit 4.13.2 harness, limited to the files below.
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

## T01 — brief da esportare

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

## T02 — brief da esportare

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

## T03 — brief da esportare

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

## T04 — non esportare ancora

Possible later packet: `src/companion/codec.cpp`, `src/companion/codec.h` and
`tools/test_firmware_codec.py`. Review RFC Base64 edge cases against an independent
reference while preserving documented unpadded/whitespace compatibility. Prepare
its full contract and harness only after the first batch's master review.

## S01 — coordinator integration review, not a worker packet

`AppUpdater.fetchLatest()` e `fetchBoards()` leggono separatamente `/latest`.
Verificare i call site in `WardriveViewModel`: un cambio release fra richieste può
mescolare metadati e lista schede. Definire un unico snapshot immutabile e un test
con due risposte successive diverse. Accettazione: versione, APK, selezione scheda
e immagine provengono dallo stesso snapshot; refresh fallito non conserva una
selezione incompatibile. È una verifica d'integrazione del coordinatore: non dare
il ViewModel completo al worker. Preparare un eventuale modulo isolato solo dopo
averne estratto e testato il contratto. Nessuna modifica assegnata a Claude ora.

## Coordinator acceptance

Before packaging, compute SHA-256 for each allowlisted input and record it in the
packet manifest. Reject automatic patch application if any source has changed.
Review the diff and its real call sites, rerun module and integration tests, and
record the outcome under the corresponding W task in backlog.md. No worker auto-commits.
