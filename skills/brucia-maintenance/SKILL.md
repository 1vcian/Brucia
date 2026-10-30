---
name: brucia-maintenance
description: Maintain and stabilize the Brucia Android companion, Bruce-derived ESP32 firmware and local website. Use for repository fixes, audits, release preparation, backlog updates and scoped worker tasks.
---

# Brucia maintenance

## Start here

Read `references/backlog.md` for ordered work and current blockers. Paths below are
repository-relative. Inspect the current diff before editing: previous sessions
may leave substantial tested but uncommitted work. Do not reset it.

Brucia pairs Bruce-derived firmware with an Android app (`app.brucia`) over framed
USB-CDC. Reference hardware is LilyGO T-Embed CC1101. Preserve standalone device
operation, Bruce attribution and AGPL/third-party licenses. No device is assumed
connected: discover it before claiming physical results.

Priority: existing functionality correct and stable, then new features. The repo
stays private until the base is accepted. Keep the site's Three.js visuals and
infinite-battery joke; use concrete, verifiable copy. Do not add demos presented as
current until their app/firmware pair has passed acceptance.

## Code and contracts

- `src/companion/README.md`: firmware module ownership; handlers in
  `src/companion/`, underlying drivers in `src/modules/` and `src/core/`.
- `proto/PROTOCOL.md`: wire contract. Exact request correlation, bounded frames,
  cancellation and terminal errors matter. Update both ends and tests together.
- `android/app/src/main/java/app/brucia/`: app. `wardrive/WardriveViewModel.kt`
  still coordinates most lifecycles. Prefer extracting one tested responsibility
  at a time; avoid broad mechanical splits.
- `files/DeviceFileReader`, `audio/RecordingBuffer`, `mitm/HttpWire`,
  `protocol/AwaitReply` and `update/` contain extracted contracts and unit tests.
- Managed file transfers require `features.verified_files`; size/CRC and request
  identity must agree. Uploads are not yet atomic. Failed exports retain audio.
- Module presence, driver initialization and physical verification are different.
  Optional hardware gates operations; CC1101 is not an IQ SDR; stock hardware does
  not supply LF RFID/iButton. See `docs/HARDWARE_CAPABILITIES.md`.
- Android Room v3 is preserved; v1/v2 schemas are unavailable. Never invent migration
  schemas or delete user data to make a migration pass.
- `VERSION` drives app/firmware versions. Android versionCode has a 1,000,000 epoch;
  keep upgrade ordering. Never relabel an old APK/binary. `release.sh` bumps versions
  and publishes: inspect its behavior before executing it for an exact-version task.

## Verification and handoff

Use `TESTS.md` for commands, evidence and limits; `CONTRIBUTING.md` for normal builds.
Cached Kotlin checks are not a fresh KSP/lint/signed APK build. Simulated protocol
results are not hardware acceptance; three board builds do not certify all targets.
Run focused regressions and the relevant integration checks for the changed contract.

For restricted local builds, use project-local caches if necessary. Past sessions
could read installed toolchains but could not write Git metadata/global Gradle caches
or reach GitHub. These are observed environment limits, not permanent project rules.
Recheck when needed; report blocks without attempting to bypass permissions.

Update the existing sources of truth at task completion:

- `references/backlog.md`: status, next action, acceptance evidence or blocker.
- `TESTS.md`: latest verified results and exact limitations; never add passed checks
  based only on code presence or inherited historical claims.
- `CHANGELOG.md`: meaningful behavior changes, marked unreleased until published.
- `ROADMAP.md`: prioritized future ideas; inspect existing implementations first.

Do not create another session/audit/status Markdown file. Keep actionable findings
in the backlog and useful results in the documents above. Remove superseded claims
instead of appending contradictory checkpoints. Do not copy secrets into documents.

## Feature contributions

For modularization or community extensions, read the proposed extension architecture
in `ARCHITECTURE.md` and tasks E01–E05. Keep it marked proposed until implemented.
Migrate existing features first; use operation-level requirements and small service
interfaces, preserving the current protocol and standalone firmware behavior.

## Workers

No Claude worker has been launched. Preparation alone does not authorize launch;
follow the user's current instructions. For an explicitly requested delegation,
read `references/workers.md`: truthful scoped briefs, allowlisted source copies,
actual isolation, fresh input hashes and coordinator review before integration.
Keep worker task status in the backlog; worker briefs are not another status ledger.
