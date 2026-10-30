# Scoped worker preparation

Stato: **PREPARATO, NESSUN WORKER AVVIATO**. Data: 2026-10-30.
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
