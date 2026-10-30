# Work queue — single operational ledger

Updated 2026-10-30. Target: `VERSION` (currently 0.1.1). Work remains uncommitted;
0.1.1 T-Embed firmware built locally, no fresh APK or published release. See
[TESTS.md](../../../TESTS.md) for evidence. Recheck environment constraints before
assuming an earlier build/Git/network blocker persists.

Status vocabulary: OPEN, IN_PROGRESS, BLOCKED, DONE; worker packets also use PREPARED/HOLD. DONE needs a test/result;
record unavailable devices, missing schemas and environment restrictions explicitly.
When a task is finished, update its row and next action here, not a new status file.

## Current user priority

**Prepare migration tasks for Claude; do not implement the migration or launch a
worker yet.** This supersedes the earlier execution order for development work.
W06 is first; W07–W11 remain held until reviewed results establish their scope.
Release correctness and physical acceptance gates still apply before publication.

## Immediate queue

| ID | Priority | State | Next action / acceptance |
|---|---|---|---|
| W06 | P0 FIRST | PREPARED | Existing feature registry extraction; full brief T06 in workers.md. Packet baseline/ID map/harness and real isolation still to prepare before launch. No code changes yet. |
| W07–W11 | P0 NEXT | HOLD | Session interfaces → phone-only pilot → device-backed pilot → firmware adapter → remaining families. Prepare each scoped packet after reviewing the previous result. |
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
