# Brucia architecture

Brucia splits a security-research multitool across two devices: a cheap ESP32 does
the radio/sensor work, and a phone does the computation, storage and UI. The phone
is the brain; the ESP32 is the antenna.

## The split

```
   ESP32-S3 (Brucia firmware)                Android (Brucia app)
   ──────────────────────────                ────────────────────
   Radio / sensor FRONT-END      <=USB=>      Compute + storage + GUI
   - capture / transmit                       - decode / crypto / analysis
   - realtime timing                          - Room DB, big datasets
   - raw-data framing + stream                - GPS, offline maps, viz
         │                                           │
         └──────── Transport (abstract) ─────────────┘
                   USB-CDC  |  Wi-Fi  |  BLE   (USB today)
                        └── one Protocol ──┘
```

**Guiding principle:** whatever needs the peripherals (CC1101, Wi-Fi/BLE radio,
PN532, IR) stays on the ESP; whatever is compute, memory, UI or geolocation moves to
the phone. Radio transmit is never delegated.

**Compatibility principle (non-negotiable):** Brucia is a **superset** of Bruce.
Every Bruce feature keeps working, and with no phone connected the device is still a
full Bruce (standalone mode). Companion mode is strictly additive.

## Layers

```
feature logic   (wardrive, crack, ir, ... - never touches the wire)
  └─ protocol   (message kinds + JSON payloads; proto/PROTOCOL.md)
       └─ framing     (length-prefixed frames on a byte stream)
            └─ transport   (USB-CDC today; Wi-Fi / BLE behind the same interface)
```

## Firmware side (`src/companion/`)

The companion is split into independent translation units: session lifecycle,
framing/responses, capability probing, command dispatch and feature services.
Each service owns its command matching and private state; the dispatcher retains
the legacy CLI fallback. See the [module ownership map](src/companion/README.md)
and [hardware availability contract](docs/HARDWARE_CAPABILITIES.md).


- `companionModeUSB()` takes exclusive ownership of the USB-CDC serial, shows a
  themed status screen, and runs the message loop.
- **HELLO -> CAPS**: reports board, chip, modules and transports.
- **CMD**: routed into Bruce's existing serial CLI, output captured and returned in
  the `ACK` (this exposes the whole device feature set to the phone). `fs_read`
  streams a file as base64 `DATA` frames.
- **SUB**: starts/stops streams - `wifi.ap` (async Wi-Fi scan), `ble.scan` (NimBLE),
  `subghz.raw` (RMT capture) - each device result is emitted as an `EVENT`.
- Reuses Bruce's CLI and scan code rather than reinventing it; see [firmware module map](src/companion/README.md).

## App side (`android/`, package `app.brucia`)

- **transport/** - `Transport` interface; `UsbTransport` (usb-serial-for-android).
- **protocol/framing** - mirrors `proto/PROTOCOL.md`.
- **feature packages** - `wardrive`, `wifi` (WPA/PMKID crypto), `nfc`, `subghz`, `ir`,
  `ble`, `badusb`, `wordlist`, `report` - the heavy work the ESP cannot do.
- **ui/** - Jetpack Compose, flame theme; Paparazzi covers representative fixture layouts; real screen interactions need on-device tests.
- **Storage** - Room DB, bundled assets (OUI table, IR catalog, dictionaries), and
  phone storage for rockyou-scale wordlists.

## The protocol

Length-prefixed (uint32 LE) JSON envelopes `{v, t, id, d}`. Kinds: HELLO, CAPS, CMD,
ACK, ERR, EVENT, DATA, SUB, PING, PONG. It is **the single source of truth** for both
sides - see [proto/PROTOCOL.md](proto/PROTOCOL.md). Never add a message to one side
only; bump `PROTOCOL_VERSION` on schema changes.

## Transports

USB-CDC is implemented (native on the S3, high bandwidth, leaves the radios free).
Wi-Fi (TCP/WebSocket) and BLE (commands/light data only) are planned behind the same
`Transport` interface - see [ROADMAP.md](ROADMAP.md).

## Why this matters

A LilyGo T-Embed CC1101 is ~$30-50. Paired with a phone most people already own, it
reaches capabilities (rockyou-scale cracking, GPS wardriving with maps, large
catalogs, rich visualization) that a standalone device in this price class cannot -
an affordable, open alternative for research and education.


## Follow-up isolation boundaries

The Android orchestration now delegates bounded file acquisition to
`files/DeviceFileReader.kt`, PCM recording/export ownership to
`audio/RecordingBuffer.kt`, HTTP inspection framing to `mitm/HttpWire.kt`, and
release parsing/download/preflight rules to the `update` helpers. These modules
accept testable byte/JSON/state inputs without requiring live hardware.
`protocol/AwaitReply.kt` owns bounded callback lifetime; `StreamPolicy.kt` records
shared-peripheral exclusion groups. The ViewModel remains the integration owner.

Managed transfers require `CAPS.features.verified_files`; hardware capabilities
remain a separate map. See the protocol document for correlation, size/CRC and
failure semantics. The cached Kotlin test runner does not regenerate KSP/Android
resources or produce an APK.

The [worker briefs](skills/brucia-maintenance/references/workers.md) define future external worker packets. A worker
receives only its allowlisted sources/tests and a limited harness after actual
filesystem isolation is established. Patch acceptance and integration remain with
the master; a worker report is not a substitute for those checks.

## Proposed extension architecture

Design proposal, not an implemented plugin SDK. Stabilization remains first;
introduce these boundaries by migrating existing features, not rewriting the app.
Track execution in the maintenance backlog (E01–E05).

### Shared contract, separate implementations

A feature owns a stable namespaced ID, metadata, operation contracts, tests and
optional app/firmware adapters. Do not use numeric UI tab indices as public IDs.
Its descriptor declares:

- ID, version, author/license and supported extension API range;
- individual operation IDs, bounded inputs/results/events and supported transports;
- per-operation hardware/driver/API prerequisites (all-of / any-of where needed);
- resource claims: Wi-Fi mode, CC1101, I2S, SPI chip/bus configuration, storage;
- execution location (phone/device), deadlines, cancellation and cleanup semantics;
- storage/network needs and whether an operation transmits or writes externally;
- fixture paths and separately recorded software/hardware verification evidence.

A descriptor never proves hardware presence or enforces permissions by itself.
The runtime resolves availability and resource ownership; the firmware rechecks
requirements before executing commands. Missing support yields an explicit reason.
A local file-analysis operation may work offline while capture requires hardware.
For example, inspect the current Wi-Fi category gate on the phone-side dictionary
checker when splitting capture from analysis; preserve capability gates on capture.

Keep the first contract internal until two unlike existing features use it.
Version changes to extension APIs separately from firmware/app release versions;
wire compatibility remains governed by proto/PROTOCOL.md. Additive capability
negotiation and old-firmware adapters must preserve existing v0 behavior.

### Android composition

Start with logical boundaries, then introduce Gradle modules where dependency
visibility and isolated builds justify them:

```text
app (navigation, registry, dependency wiring)
  -> feature implementations (screen, state, controller, tests)
      -> small feature API + domain-specific service interfaces
          <- core implementations (session, protocol, storage, jobs)
```

Features depend on contracts, never the whole WardriveViewModel or MainActivity.
A feature exposes its screen/state and typed operations. The application shell
composes registered features and resolves navigation by stable ID. Cross-feature
workflows use typed shared services/events, not direct imports of another screen.
Keep constructors/interfaces explicit; a generic service locator would merely
hide the current coupling. Phone-only features must not need USB lifecycle code.

The core owns session identity, bounded command queues, cancellation, stream
subscriptions, resource leases and release on disconnect. A feature cannot retain
an obsolete connection. Stop must finish or report failure within a declared bound.
UI progress distinguishes accepted, running and completed; an ACK is not proof of
physical effect. Headless and screen-off behavior must have an explicit owner.

### Firmware integration and Bruce compatibility

Build on the existing handler registry in src/companion/commands.cpp. Separate
feature logic from standalone menu, CLI and companion adapters so they invoke the
same implementation. Firmware features declare availability, command/stream hooks
and explicit service/stop/cleanup lifecycle. Recognition remains distinct from
success: a handled error must never fall through into legacy CLI execution.

Introduce cooperative resource ownership before concurrent plugins. Bus locking
alone cannot resolve pin conflicts or incompatible Wi-Fi/I2S modes. Build registration
must reject duplicate IDs and omit unsupported drivers for board/LITE variants.
Prefer a deterministic generated/static registry, not dynamic loading of C++ binaries.

Keep BruceJS as the device scripting option where the pinned interpreter exposes
the required API. Test compatibility against the actual interpreter in this repo;
upstream documentation may describe newer APIs. A manifest cannot grant missing
bindings, timing guarantees or safe preemption. Native changes need a firmware build.
Generic driver/standalone improvements can be proposed upstream separately; acceptance
by Bruce maintainers is independent. Brucia-only contracts do not automatically run
on unmodified Bruce, and a Compose screen does not become device UI.

### Community extension tiers

| Tier | Examples | Integration/distribution |
|---|---|---|
| Data packs | IR catalogs, sample captures, layouts, decoder fixtures | Versioned schema + bounded importer; no executable code; export/import first |
| Scripts/recipes | Existing BruceJS scripts, bounded sequences of existing operations | Reuse interpreter/macros; explicit API and resource limits; availability checks |
| Native modules | New driver, DSP implementation, Compose feature | Reviewed source contribution, compile/link into signed app/board firmware |

A future catalog indexes metadata, compatible versions, licenses, provenance and
verification evidence. Start with source PRs and inspectable pack imports; a store,
remote code loader and payment system are not prerequisites. Never auto-run imported
scripts. Native linked modules are trusted application code, not isolated plugins.
Third-party runtime execution requires a separately designed isolation model; an
interface or manifest alone does not provide one.

### Contributor workflow and acceptance

1. Choose a tier and an existing example; implement only required sides (phone,
   firmware, or both). Preserve source/license provenance.
2. Supply the descriptor and deterministic fixtures. Provide a fake device/service
   adapter so app tests run without a physical board.
3. Run a feature-scoped check: descriptor validation, unique IDs, API compatibility,
   resource/cancellation behavior, malformed data, missing hardware and lifecycle.
4. CI also runs shared contract regressions and affected firmware board variants.
   A native module's tests passing alone does not certify integration.
5. Review source and artifacts; record verified versions/boards. Failures should
   identify the feature and operation, not just the transport exception.

Proof that the architecture works: migrate an existing phone-only analyzer and an
existing device-backed reader. Add a small fixture-based example through registration
without editing MainActivity, WardriveViewModel or central command conditionals.
Only then publish a versioned SDK/template generator and stabilize external APIs.

Design references: [Android modularization patterns](https://developer.android.com/topic/modularization/patterns),
[modularization tradeoffs](https://developer.android.com/topic/modularization),
[Bruce JS interpreter](https://wiki.bruce.computer/features/js-interpreter/).
