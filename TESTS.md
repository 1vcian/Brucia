# Brucia - test checklist

On-hardware tests to run on the LilyGo T-Embed CC1101 (ESP32-S3) with the Android app, Record the app version, firmware version, commit and result for each run.
Use phone flashing to validate the in-app updater; PC flashing is also available for
development and recovery. Historical checks below are not proof for the current build.

## Project audit — 2026-11-10

Base `78ef13dc`, clean tree at audit start. Scope: all 52 catalog descriptors/IDs/tabs
inventoried (no duplicates); targeted review of app input/ownership/cancellation,
BLE console, companion framing/stop, store installer, release/CI and evidence records.
This is not end-to-end acceptance of all 52 tools. This audit changes only this file
and the existing backlog (F36–F47); no production fix, firmware build, flash, live
radio/tag action, release or physical phone test was performed.

Fresh checks:
- `cd android && ./gradlew --offline :app:testDebugUnitTest :app:lintDebug`:
  1,137 tests, 0 failures/errors/skips; lint no errors, one OldTargetApi diagnostic.
- `python3 -m unittest discover -s tools -p 'test_*.py'`: 330 tests, 2 skipped, OK.
- `python3 tools/test_firmware_frame_lock.py`: 20/20.
- `python3 tools/test_radio_guards.py`: 37/37.
- `python3 tools/test_companion_dispatch_stops.py`: 13/13.

Independent counterexamples (all isolated, no real publication or radio):
1. **Wordlist loss, F37:** compiled unchanged `WordlistStore.kt` with minimal Android
   context/resolver stubs. A provider returned 4 bytes then threw IOException.
   Import threw, but the old final file had already become `bad\n`.
2. **Target ownership and size, F36/F38:** extracted unchanged `importPcap` method into
   a coroutine harness with provider/parser fixtures. A blocked import A, then B,
   completed as target B then A: older completion wins. A synthetic 33 MiB stream
   was read completely before the parser was called. This demonstrates missing input
   bounds; no phone OOM was induced. The parser was a fixture, not a real PCAP decode.
3. **Cancellation/validation, F41/F42:** compiled actual DictionaryDownload,
   MifareDictionaryService, MifareDictionary and update IO helpers with fake HTTP
   connections. Cancel left one blocking reader active while busy=false; restart
   raised active readers to two. Releasing the fixture allowed cleanup. A NUL after
   3,000 bytes was accepted/promoted by the file-download path.
4. **Release recovery, F40:** existing release harness plus injected git-push or gh
   failure (exit 7). Commit/tag calls had occurred, VERSION remained advanced to
   0.1.1, and no temporary release bundle remained. All git/gh/Gradle/PIO were shims;
   nothing was actually committed, pushed or published by this probe. The ordinary
   Gradle output APK can still exist; total artifact loss is not claimed.
5. **Soak coverage, F43:** actual runner with MockDevice emission disabled:
   exit 0, PASS, zero findings, `stream_events={"wifi.probe": 0}`. Link PASS alone
   is not evidence of F23 stability under radio traffic.

Source-only findings: F39 BLE callback/session ownership (not exercised on Android
Bluetooth); F44 missing explicit screenshot/instrumented CI gates (installed Paparazzi
bytecode defaults verification false without `paparazzi.test.verify`); F45/F46 stale
release/status/UI claims. Local S3 SDK configs still omit HAVE_TIME_DATE while defining
HAVE_TIME: F35 device certificate-date enforcement remains unverified/unimplemented,
not established by host ssl tests. Existing physical gates stay OPEN.

Reproduction scripts, raw outputs and source hashes are under
`.codex_tmp/audit-20261110/` (ignored): `probe_imports.py`, `probe_downloads.py`,
`probe_release.py`, `probe_soak.py`, `imports-result.txt`, `download-result.txt`,
`release-result.json`, `zero-events-soak.json`, `android-result.json`,
`feature-inventory.json`, `reviewed-source-hashes.json`, and host/frame/radio/dispatch
logs. These are local evidence, not shipped application assets or additional status docs.
The ordered tasks and acceptance criteria live only in
[the maintenance backlog](skills/brucia-maintenance/references/backlog.md).

## Brucia-hosted dictionary snapshots — 2026-11-10

Four unchanged dictionaries uploaded as assets to the existing `wordlists` release:
`probable-wpa-4800.txt` (45,276 B / 4,800 entries), `probable-wpa-204k.txt`
(2,121,750 B / 203,806 entries), `flipper-mf-classic.nfc` (38,847 B / 2,038 unique
keys), `proxmark3-mfc-default-keys.dic` (46,037 B / 2,511 unique keys).
`docs/dictionary-sources.json` records pinned upstream revisions, source URLs,
SHA-256, sizes, attribution and licenses; the same JSON plus `dictionary-licenses.zip`
are release assets. The ZIP includes all three full license texts and notices.

All six published assets downloaded again from Brucia and compared byte-for-byte
with the prepared copies: PASS. License archive integrity and enclosed texts checked.
WPA entries are all 8–40 characters; NFC counts use normalized 12-hex unique keys.
Existing RockYou/SecLists assets were not changed.

App source now uses BrandLinks.DOWNLOAD_PREFIX + wordlists for every catalog
entry, including NFC; there is no upstream fallback. Focused Android tests:
BrandLinksTest 3, MifareDictionaryTest 5, DictionaryDownloadTest 4 — 12 PASS,
0 failures/errors. This compiled production/test Kotlin; it did not assemble or
publish a new APK. The previously published 0.1.12 APK still has its upstream URLs.
No firmware change, device flash or physical tag test.

## App recordings and dictionary release links — 2026-11-10

Actual signed release APK 0.1.12 (digest below) installed on a temporary read-only
Android API 36.1 arm64 emulator, serial emulator-5580. No physical phone, board or
NFC tag connected. Android screenrecord captures replace six text-slide videos:
- Home category navigation to NFC, with disconnected state visible.
- Explicit built-in Demo: synthetic handshake, 401/401 candidates, known passphrase
  `brucia-demo-pass`. Displayed 118 keys/s is emulator-only, not a phone benchmark.
- Real Android HTTPS imports: Flipper 2,038 keys; Proxmark3 adds 473, total 2,511.
  No tag command or radio transmission. This checks import and deduplication in the
  release APK; cancellation, rotation and physical tag acceptance remain open.

Raw captures retained in `.codex_tmp/app-recordings-0.1.12/` (ignored), with hashes
and APK identity in `docs/media/manifest.json`. Full viewport retained; provenance
banner, H.264 encoding, captions and 4-second final hold only. No fabricated results.
The still-image generator no longer overwrites videos or Tutorials.
Verification: site 17/17, brand 11 PASS + 1 SKIP, all new local links and manifest
asset hashes checked; all three MP4s decode fully. Representative frames and final
results visually inspected; captions aligned to observed state changes.

GitHub `wordlists` release notes updated and read back: four upstream links for
Probable WPA 4,800/204k and Flipper/Proxmark3 Classic. Existing four RockYou/SecLists
assets unchanged; new sources are links, not mirrored assets.

Observed copy follow-up: the released Crack screen still contains “what Flipper
cannot do” and “Run uses a demo handshake”. The recording is unchanged; those
strings need a scoped wording correction in a future app build.

## Release 0.1.12 — published verification

Published to Brucia at tag `v0.1.12`, release commit `21b8f80d` (feature commit
`3bb6a3fe`). release.sh exit 0; full log in
`hw-acceptance-reports/release-0.1.12.log`. Host discovery: 330 tests, 2 skipped;
site tests 17/17; firmware codec/storage/framing and mock protocol pass. Android
full unit run: 1,137 tests, 0 failures/errors/skips (XML totals observed before
release clean); lint passes. T-Embed build and real-dependency AAC noise test pass;
fresh signed APK assembled. APK identity `app.brucia`, version 0.1.12 / 1000112,
minSdk 26, targetSdk 36, debuggable false; apksigner verification passes.

GitHub release is published, not draft/prerelease; both assets uploaded. Remote
SHA-256 digests match the local APK and firmware:
- APK: `5f59691ddf5fcde46072e99c167c8e547ac6d272a0c294a129a0c4d8f900d083`.
- Firmware: `3341813c7799d619d54ee20b3aba57662ac4c27ede4963c54ae3685e1932d010`.

Build CI and Pages deployment completed successfully for `21b8f80d`. No physical
phone/tag tests or device flash performed for the release. Emulator media preparation
is separate from that physical acceptance.

## Additional Wi-Fi / NFC dictionaries — 2026-11-10

Probable WPA top 4,800 and top 204k added to the existing Wi-Fi catalog. NFC adds
Flipper/Proxmark3 Classic sources to the existing bundled/imported key-check path;
no firmware or cryptographic attack implementation changed. NFC keys remain in memory
for the app session. Source downloads do not start key checks or write to a tag.

Host source validation (curl with TLS verification, no RF/tag action): Probable files
45,276 B / 4,800 lines and 2,121,750 B / 203,806 lines; all nonempty lines 8–40
characters. Flipper dictionary 38,847 B / 2,038 unique Classic keys; Proxmark3
46,037 B / 2,511 keys. Counts describe the retrieved upstream branches, not fixed
future content. No data files bundled. These host requests do not test Android TLS.

Focused JVM regressions cover truncated/oversized/HTML/archive/error downloads,
cancellation, preservation of previous files, HTTPS redirect refusal, comments/BOM,
key deduplication, malformed imports and late-result fencing after Clear. Existing
MIFARE batch service, catalog and brand-link checks included: 36 tests, 0 failures.
`:app:lintDebug` passes (0 errors, 1 OldTargetApi warning); `:app:assembleDebug` passes.
Brand host checks: 11 PASS, 1 SKIP. Debug APK compiled;
no installation or flash. Full release verification is recorded after publication.

Phone/tag acceptance still NOT RUN: add each source, cancel during download, import
a commented .dic/.nfc, Clear and re-add, rotate during load, and check known keys on
an owned MIFARE Classic tag. Confirm no card operation starts on download/import.
NTAG/Ultralight/DESFire are outside this Classic dictionary workflow.

## Community documentation refresh — 2026-11-09

README, wiki home/feature/contribution guides, FAQ, navigation, usage, site copy and
issue/PR templates revised for public contributors. Explanations of the battery joke
removed; the site punchline remains. Feature-specific hardware notes, preview labels,
comparison/benchmark limits and detailed acceptance records retained.

Checks: `python3 tools/test_brand.py` — 11 PASS, 1 SKIP; `node --test
tools/test_site_release.cjs` — 17 PASS. 160 local Markdown/image targets and all 18 wiki
routes inspected; `git diff --check` clean. No app/firmware changes, build, flash or
new physical acceptance. No new images/videos generated. Website rendering/deployment
not verified in a browser in this pass.

## Processing benchmark — 2026-11-09 (host only)

Command: `python3 tools/benchmark_android_logic.py --output docs/benchmarks/2026-11-09-host.json`.
PASS: compiles and runs the production WPA2/PMKID, FFT, DTMF and SSTV Kotlin modules;
independent Python hashlib/hmac fixture checks and audio output sanity checks pass.
Three warm-up batches, seven recorded batches. Apple M2 Pro / macOS 26.6 / Java 23
HotSpot / SunJCE. Median WPA2 963.31 candidates/s; PMKID 980.70 candidates/s;
FFT 2048 0.04047 ms/window; 3.84 s DTMF 1.3798 ms; Robot36 encode 5.8034 ms,
decode 92.0886 ms (generated audio 36.91 s). Raw samples, synthetic fixtures and
production/harness source hashes are preserved in the JSON above. Source HEAD
4da92c8c plus uncommitted documentation, benchmark tools and a WPA comment correction;
no processing implementation changed. Initial exploratory run is ignored under
.codex_tmp/benchmarks/20261109-host.json; the documented figures use the final run.

ADB device list empty and no usbmodem/usbserial ports available. No phone/firmware
benchmark, radio action, flash, APK installation or device acceptance performed.
Bruce's 13–14 passwords/s is an upstream source comment, not an A/B measurement.
The 91,400 keys/s screenshot fixture is invented preview data, not benchmark evidence.
docs/COMPARISON.md records results, methodology, limits and remaining device comparisons.

## Public documentation and illustrated media — 2026-11-09

Follow-up comparison review: restored named active tools in README/wiki/site and checked
novelty against official Bruce firmware/Wi-Fi/companion documentation, Bruce password-recovery
source, Flipper's official mobile guide and a primary community De Bruijn inventory.
Sources and scope are recorded in docs/COMPARISON.md. Bruce already performs dictionary
recovery; both platforms have official mobile workflows. Android DSP, persistent observation
analysis and phone-hosted proxy are presented as differentiators inferred from the reviewed
scope, not universal exclusives. No benchmark, new feature or physical acceptance is implied.
Removed the stale "impossible on Bruce" and device-interpreter limitations from the tools wiki.

Context: source HEAD `aa19f9b2`, VERSION 0.1.11, public identity from brand.json.
Prepared locally: rewritten README and core wiki guides, refreshed feature references,
Tutorials/transcripts, public website/usage copy, and 18 working wiki routes including
all feature families. Markdown page links work in repository views as well as the site.
Wiki error/source links now target the actual wiki/*.md sources rather than a separate
GitHub wiki that would need independent publication. Existing local LAUNCH.md edits preserved.

Media: `python3 tools/render_public_media.py` (Pillow + ffmpeg) generated two public
images and six silent illustrated guides, each H.264 / yuv420p, 1280x720, 40 seconds,
four VTT cues, with a poster and complete transcript. Manifest lists source revision,
preview and generator hashes, and output hashes. Dashboard/report/IR images are
Compose preview fixtures, NOT phone screenshots; videos are text/diagram instructions,
NOT hardware demonstrations. Historical captures remain historical.

Checks performed:
- `cd android && ./gradlew --offline :app:verifyPaparazziDebug --tests '*FlameUiScreenshotTest'`:
  SUCCESS, **17 tests / 0 failures / 0 skips**. Build compilation reused cache;
  preview verification ran. Approved cache access outside sandbox; no device involved.
- `python3 tools/brand.py check`: PASS. `python3 -m unittest discover -s tools -p 'test_brand.py'`:
  **11 PASS / 1 SKIP** (the rename test skips because brand.json already has the launch values).
- `node --test tools/test_site_release.cjs`: **17/17 PASS**; `node --check docs/wiki/wiki.js`: PASS.
- Independent router check `.codex_tmp/public-launch-20261109/check-wiki.cjs`: all
  **18 routes**, legacy wiki-link normalization and source/error targets PASS.
- Local Markdown target check: **96 targets exist**. Manifest hashes match source/assets;
  ffprobe confirms six 40-second H.264 videos and caption files each have four cues.
  Two frames per video decoded; representative contact sheet and both public images inspected.
- `git diff --check`: PASS.

Not performed: fresh signed APK/firmware build, phone/device capture, hardware acceptance,
remote link availability checks, browser playback/accessibility testing, publication or push.
Current app/device boot, Device Info and companion screenshots and physical workflow videos
remain pending an accepted release pair as required by the maintenance skill. These materials
do not close F23 or any peripheral/phone acceptance item.

## Tone, compiled JS, BLE console (upstream features, reworked) — 2026-11-07

Tree: HEAD `c41bca36` (v0.1.9) plus uncommitted changes (this section and the two below).

**1. Compilation and host tests (PASS)**

- Firmware `pio run -e lilygo-t-embed-cc1101 -e m5stack-cardputer`: **SUCCESS**. App slot:
  T-Embed 4,355,168 / 4,653,056 B (93.60%, warning), Cardputer 83.39%.
- Android `./gradlew :app:testDebugUnitTest`: **1128 tests, 0 failures**; `:app:lintDebug`: 0 errors,
  no warnings in the new files. New: `NusProtocolTest`, `ScriptServiceTest` (runFile command,
  local rejections, stop), `CapsTest.scriptRunFileRequiresItsLiteralFlag`, `FeatureRegistryTest`
  (51 tools, BLE console tab 52).
- Python `discover -s tools`: **328 OK, 1 skipped** (live network). New:
  - `test_bjs_bytecode.py`: mquickjs built on the host with the Brucia patch; real bytecode from
    `mqjs -o`; valid files pass and run with the expected output; every truncation, bad
    magic/version, misalignment, empty and trailing-garbage file rejected; 2 x 4000 random
    corruptions with each copy against a PROT_NONE guard page (after it or before it): no fault in
    the check, relocation or load of any accepted copy; control: without the check the same
    truncated file faults. Guard pages instead of AddressSanitizer: ASan binaries do not start in
    the sandboxed agent shell. Host bytecode is 64-bit; the device runs the same code with JSW=4.
  - `test_ble_api_security.py`: line buffer and peer gate host-compiled; pairing, RX
    permissions, per-peer notify, disconnect on an insecure link, vprintf fix (static).
  - `test_cardputer_tone.py`: MCLK not routed on the Cardputer; tone channel per call, deleted
    on every path, on `I2S_NUM_AUTO`.
  - Updated: `test_firmware_script_abort.py` (shared driver), `test_lite_companion.py` (feature).
- `tools/bjs_compile.py` produced a 32-bit `.bin` (magic `fb ac`) from a test script.

**2. Flash: NOT RUN** (owner flashes from the phone).

**3. Hardware: NOT RUN.** Owed: Cardputer ADV tones and songs (no squeal, LRCLK intact);
`.bin` run from the device menu and from the app (Files > Run on device), a corrupt `.bin`
refused; BLE console: enable on the device, PIN shown, phone pairs, `info` works, an unpaired
phone cannot run commands or read output, Forget BLE pairs, reconnect after a device reboot.

## F04 ARP lock + upstream Bruce fixes — 2026-11-07

Tree: HEAD `c41bca36` (v0.1.9) plus uncommitted changes. Upstream fixes ported from
`upstream/dev`: 8fdada3c, be8f3733, 7d9c1e7c, aa4c286f, ad24e96d, 59c0da06 (details in the backlog).

**1. Compilation and host tests (PASS)**

- `pio run -e lilygo-t-embed-cc1101 -e m5stack-cardputer`: **SUCCESS**.
- `python3 -m unittest discover -s tools -p 'test_*.py'`: **312 OK, 1 skipped** (live net).
  F04 static check in `test_companion_prompts.py` fails on the previous ARPScanner (1 lock
  vs 4 unlocks) and passes now. The upstream fixes have no new host tests here.

Second batch: 76c5f66d DuckyScript DEFINE, 63881836 `led` CLI (clinum parsing,
`LedCliCallbacks`), c7aad444 touch password Show/Hide. Rebuilt T-Embed + Cardputer: **SUCCESS**;
Python **313 OK, 1 skipped**. T-Embed app slot 4,350,048 / 4,653,056 B (93.49%).

**2. Flash: NOT RUN** (owner flashes from the phone).


**3. Hardware: NOT RUN.** Owed: ARP scan on an Ethernet board; ESP-NOW receive commands
(unknown peer asks for approval, "Always allow" then runs without asking, malformed/short
frames dropped) and file share between two devices; LoRa change username; KeeLoq `.sub`
send with code stepping; Recent RF loop; Wi-Fi join to the chosen BSSID and auto-connect
to a saved open network; `led rgb/hex/br/effect/off` on the T-Embed ring and rejection of
`led r abc`; a DuckyScript with `DEFINE`; Show/Hide on a touch board's password field.

## F35 App Store installer: authenticated, checked install — 2026-11-07

Original work was based on `43f2972d` (v0.1.8), then committed as `11fa008b` and
included in local release commits 0.1.9/0.1.10. Independent review 2026-11-08
observed HEAD advance to `09ef4a0f`; remote publication of these versions was not
checked by this reviewer.

**Independent follow-up 2026-11-08: F35 not fully closed in software.**
`python3 tools/test_app_store_install.py`: 6 discovered, **5 PASS / 1 SKIP** (live
network). Initial sandbox run could not bind the localhost TLS server; approved
rerun outside the sandbox passed. No device, new build, flash or remote TLS check.

- Flush gap (source confirmed): `FsFiles::endWrite` checks `getWriteError()` after
  void `File.flush()`. Installed Arduino FS `vfs_api.cpp` discards `fflush`, `fsync`
  and `fclose` return values; `FS.cpp` does not set the Print error flag for them.
  The fake's flush failure return is not supplied by this real adapter. A correct
  size alone does not establish successful sync. Need an adapter-level regression
  for sync failure despite all writes/size appearing successful, and error propagation.
- Rollback gap (host reproduced): `.codex_tmp/review-f35-20261108/probe.py` compiles
  the actual installer with both final promotion and backup restoration failing.
  Result: generic error, no destination, old bytes retained in `.bak`, no staging.
  A subsequent retry restores the old path when rename is available. Source hash,
  harness and output are retained beside the probe. This is recovery-required,
  not loss of the old bytes; the unconditional old-install-preserved claim is too broad.
- Add device TLS case: cold boot with unset/stale clock, then valid clock. The
  installer does not arrange clock synchronization; host Python TLS uses host time.
  Certificate errors must remain visible without any insecure fallback.

No production code changed by this review. Existing hardware NOT RUN statuses remain.

**Fix for both gaps 2026-11-09 (uncommitted, on top of `09ef4a0f`).**

- Flush: the device adapter no longer uses Arduino `File`. `app_store::PosixFiles` writes
  through POSIX on the FS mountpoint (`fs->mountpoint()` + path, the ESP-IDF VFS) and
  `endWrite()` fails on `ferror`, `fflush`, `fsync` or `fclose`. After the close the
  installer also reads the staging file back and compares it (length + FNV-1a 64) with the
  downloaded bytes ("stored script differs from download"). Limit: a read-back can still be
  served from a cache; it does not prove the medium survives a power loss.
- Rollback: when both staging->destination and backup->destination fail, `install` returns a
  message starting with `RECOVERY REQUIRED:` naming `App Store.js.bak` and saying the next
  attempt restores it; the device shows it with `displayError`. Same for a leftover backup
  that cannot be restored at the start (nothing is downloaded then). Header contract narrowed.
- Clock: this Arduino build has `CONFIG_MBEDTLS_HAVE_TIME_DATE` unset (esp32s3 sdkconfig), so
  mbedtls does not check certificate validity dates: an unset clock does not by itself block
  the handshake, and an expired or not-yet-valid certificate is not rejected on date (chain to
  a pinned root and hostname are still checked). The cold-boot device case stays in the list.

Compilation and host tests (PASS):
- `tools/test_app_store_install.py`: 8 discovered, 7 PASS / 1 SKIP (live network). New cases:
  bytes changed after every write and the flush reported success; read-back error; final
  rename and rollback both failing (old bytes in `.bak`, recovery message, next attempt
  restores); leftover backup not restorable; the real `PosixFiles` on a temp directory for
  install/update, `fsync` failing and `fclose` failing after successful writes (old install
  kept, no `.part`/`.bak`), missing directory; source checks that `settings.cpp` uses
  `PosixFiles` and shows recovery as an error.
- Mutation check: ignoring the `fsync` result, ignoring the `fclose` result, skipping the
  read-back and ignoring the rollback result each make the suite fail.
- Full suite `python3 -m unittest discover -s tools -p 'test_*.py'`: 330 OK, 1 skipped.
- Firmware builds: `lilygo-t-embed-cc1101` SUCCESS, `m5stack-cardputer` SUCCESS.
- The reviewer's `.codex_tmp/review-f35-20261108/probe.py` was not rerun: its fake storage
  predates the read-back methods added to `Files`; the same scenario is now in the suite.

Flash: NOT RUN. Hardware: NOT RUN (install on SD and on LittleFS, Wi-Fi pulled mid-download,
cold boot with unset clock, full storage).

**Independent verification 2026-11-09: the two storage/recovery fixes are accepted
at source/host level.** Fresh `python3 tools/test_app_store_install.py`: **7 PASS,
1 live-network SKIP**. The localhost TLS test required an approved run outside the
sandbox (initial bind was denied); it then passed. Independent mutations in copies
of the production module, `.codex_tmp/review-f35-20261109/mutations.py`, all failed
the behavioral harness as expected: ignore sync result, ignore close result, bypass
read-back comparison, ignore failed rollback. Source hash and results retained beside
the script; production files were not mutated. The POSIX adapter uses the selected
Arduino FS mountpoint and checks all three completion return values.

TLS limit independently confirmed in the installed ESP32-S3 sdkconfig and mbedtls
`esp_config.h`: `CONFIG_MBEDTLS_HAVE_TIME_DATE` unset disables date verification;
the secure-client source still sets `MBEDTLS_SSL_VERIFY_REQUIRED` and the hostname.
Python's expired-certificate rejection must not be presented as device acceptance.
Certificate validity-date enforcement remains a separate security follow-up; the
cold-boot case alone cannot establish it. No fresh firmware build, flash, remote TLS
test or hardware acceptance was performed in this independent review.

**1. Compilation and host tests (PASS)**

- `python3 -m unittest discover -s tools -p 'test_*.py'`: **312 OK, 1 skipped** (the live
  network test, skipped without its env var).
- `BRUCIA_NET_TESTS=1 ... -p 'test_app_store_install.py'`: **6 OK**. Live: the store host
  validates against the 9 pinned roots only; `self-signed`, `expired`, `wrong.host` and
  `untrusted-root` badssl hosts are rejected. Python ssl, not mbedtls on the device.
- Host-compiled `app_store::install` harness: interrupted stream, read timeout, no
  Content-Length, empty, blank, oversized (refused before reading), HTML page, storage
  full mid-write and at 0 bytes, flush failure, size re-read mismatch, final-rename failure,
  failure to move the previous install aside, stale staging, backup restore after a crash
  between renames. Each with and without a previous install: no success, no staging left,
  previous install unchanged. Mutation check: treating an early end of stream as complete
  fails the harness.
- `pio run -e lilygo-t-embed-cc1101 -e m5stack-cardputer`: **SUCCESS**. T-Embed app slot
  4,343,904 / 4,653,056 B (93.36%, warning threshold 92%; +16 KB for roots and installer).

**2. Flash: NOT RUN.** The T-Embed was not enumerated (no `/dev/cu.usbmodem*`); the upload
attempt failed at port open, nothing was written and no log file was created.

**3. Hardware: NOT RUN.** Owed: install over Wi-Fi succeeds through mbedtls with the
pinned roots; the store opens from JS Interpreter -> Tools; Wi-Fi drop mid-download shows
the error and leaves no new install; an existing install survives a failed update.

## Release v0.1.8 — publication review 2026-11-07

HEAD `43f2972d`, following `85cf0d30` (F32/F34) and `a21a3965` (store branding).
GitHub API independently confirms v0.1.8 published at 2026-11-06 23:27:53 UTC,
not draft/prerelease, with uploaded `Brucia-0.1.8.apk` (19,015,839 B) and
`brucia-t-embed-cc1101.bin` (4,393,072 B). This review inspected metadata, not
downloaded artifact contents. Local `hw-acceptance-reports/release-0.1.8.log`
records successful checks/builds, signed APK packaging and publication.
App partition usage: 4,327,536 / 4,653,056 B (93.00%, 325,520 B free);
warning threshold 92%, failure threshold 98%. This is the app slot, not total flash.

v0.1.8 flash and phone/device acceptance: **NOT RUN**. Historical d2e24911
flash/boot evidence below does not verify the released pair. F32/F34 phone checks,
IR/NRF24 stop behavior, FM ownership, store installation and F23 under real radio
EVENT traffic remain open. No flash, script execution, commit or release by this reviewer.

## App Store source (Bruce community store) — 2026-11-07

Finding: "Install App Store" downloads `App Store.js` from the Bruce community store
(`ghp.iceis.co.uk`, third-party host). That script then fetches its catalog and every app over
plain `http://ghp.iceis.co.uk` without an integrity check, and the apps run in the JS
interpreter. This is the only runtime Bruce endpoint in the firmware; the other Bruce
references are credits and links already in `brand.json`.

Change: the store is kept (Bruce JS apps run unchanged), but its URL now lives in
`brand.json` (`upstream.app_store_url` -> `BRUCIA_UPSTREAM_APP_STORE_URL`). The menu entry
reads "Install Bruce App Store", and a notice before install names the third-party host and
the plain-HTTP downloads.

- Compilation: `pio run -e lilygo-t-embed-cc1101 -e m5stack-cardputer` **SUCCESS**.
  `python3 -m unittest discover -s tools -p 'test_*.py'`: **306 OK** (new
  `test_app_store_endpoint_comes_only_from_brand_json`).
- Flash: NOT RUN for this change (the 2026-11-07 flash below is d2e24911 firmware).
- Hardware: NOT RUN. Owed: menu label, notice, install over Wi-Fi, store opens from
  JS Interpreter -> Tools.

Independent source review (F35, OPEN): `installAppStoreJS` uses `HTTPClient.begin(url)`
without a CA. In the installed Arduino core's `HTTPClient.cpp`, `begin(String)`
delegates HTTPS to `begin(url, nullptr)` (lines 212–228), and `TLSTraits::verify`
calls `setInsecure()` for a null CA (lines 61–65): the bootstrap's HTTPS URL does
not authenticate the server. This is a local driver/source finding, not a live TLS test.
The installer writes directly to the final path and ignores `file.print`'s returned
count; `getString()` also ignores `writeToStream`'s error. Success is shown regardless
of completeness, and installed state is only file existence. These inherited defects
need installer fixes and fault tests (invalid certificate, interrupted/empty/oversized
download, full storage/short write, preservation of an existing install).
The notice alone does not supply integrity. This review did not retrieve/execute
the remote script; its catalog/app behavior above is the previous auditor's evidence.

## F32 LoRa teardown + F34 Remote watchdog fixes, reflash — 2026-11-07

Tree: HEAD `d2e24911` plus uncommitted app changes (`LoraService.abandon`, `RemoteRun`,
ViewModel wiring, tests). Firmware sources are identical to `d2e24911`. `VERSION` 0.1.7.
Not released. The three levels below are separate claims; one does not imply the next.

Independent review 2026-11-07: F32 teardown and F34 watchdog fixes accepted for the
reviewed software paths; phone acceptance remains NOT RUN. Fresh command:
`cd android && ./gradlew --offline :app:testDebugUnitTest --tests '*RemoteRunTest' --tests '*RemoteLinkGateTest' --tests '*LoraServiceTest' --tests '*IrPowerSpamServiceTest' --tests '*JamDetectServiceTest' --tests '*StopAllSequenceTest' --rerun`
executed **46 tests in 6 suites**, zero failures/errors/skips (compilation reused
up-to-date outputs). Independent held-dispatcher probe
`python3 .codex_tmp/review-f32-f34-20261107/probe.py` also passes: queued LoRa stop
cancelled before its sender starts, no write to replacement B; IR/Jam controls and
F26 timeout withholding still pass. Source hashes/output retained beside that probe.
The reviewer verified the full upload log hash below, four successful data-hash
checks and exit 0; current local firmware.bin SHA256 matches the artifact hash
recorded in that log. Boot capture reaches setup-end/first-mainMenu. These are
artifact/log checks, not a new flash or phone test; no device was opened by the reviewer.

**1. Compilation and host tests (PASS)**

- Fix F32: `LoraService.abandon()` cancels a receive-stop not yet written and lifts its
  fence; disconnect uses it (`Entry("lora", stop, abandon)`), explicit Stop receive still
  uses `stopReceive()` and keeps its write. Fix F34: `remote/RemoteRun` runs one Remote
  attempt as one job; the mirror watchdog is launched as its child only after a confirmed
  `companion_exit`, ends with the mirror loop, and Stop/disconnect cancel both. The
  separate `remoteWatchdogJob` is gone.
- New regressions: `LoraServiceTest` (queued stop + disconnect + replacement link B -> no
  write to B, new receive allowed; explicit stopReceive still writes to A; fence holds until
  written or abandoned), `RemoteRunTest` (3 failed exits + Stop -> 0 watchdogs ever started;
  failed then successful -> exactly 1 until Stop; restart during pending exit or mirror -> never
  more than 1; disconnect during pending exit with late ACK -> none; mirror end -> watchdog
  ends), `RemoteLinkGateTest.watchdogIsOwnedByTheRemoteAttempt` (source wiring).
- Mutation check: with `abandon()` reduced to `stop()` and the watchdog launched outside
  the attempt, the LoRa/RemoteRun suites fail (7 of 19); restored afterwards.
- `./gradlew --offline :app:testDebugUnitTest`: **1120 tests, 0 failures/errors/skips**.
  Focused: RemoteRun 5, RemoteLinkGate 6, LoraService 14, IrPowerSpam 6, JamDetect 6,
  StopAllSequence 9, all PASS. Debug unit-test compile only; not a signed release APK build.
- `python3 -m unittest discover -s tools -p 'test_*.py'`: **305 tests OK**.
- `pio run -e lilygo-t-embed-cc1101`: **SUCCESS**. `firmware.bin` sha256
  `de4d1818c0e1d8d3b758bf252277b89b88a2bc63b9baa8ee426aac8127c23a36`. Cardputer not rebuilt
  (no firmware source change since d2e24911).

**2. Flash (SUCCESS)**

- Device on `/dev/cu.usbmodem2101`: USB 303A:1001 "USB JTAG/serial debug unit", esptool
  reports ESP32-S3 (QFN56) rev v0.2, embedded PSRAM 8MB, 16MB flash, MAC
  `8c:bf:ea:8a:a4:78` = the T-Embed CC1101 used in earlier verified runs.
- `pio run -e lilygo-t-embed-cc1101 -t upload --upload-port /dev/cu.usbmodem2101`: SUCCESS,
  exit 0; bootloader @0x0, partitions @0x8000, boot_app0 @0xe000, app 4327392 bytes @0x10000,
  "Hash of data verified" for all four. This supersedes the failed d2e24911 upload above.
- Complete log (local, gitignored): `hw-acceptance-reports/flash-20261106-235930-d2e24911-tembed.log`
  (402 lines, sha256 `ea17822b16577ce12cd7008a7adc2de3e84b9d31dd268896ee31581d26e000de`);
  passive boot capture: `...tembed.boot-full.log` next to it.

**3. Hardware verification (boot only; feature acceptance NOT RUN)**

- Passive serial read (no bytes sent, DTR/RTS low): ROM boot `SPI_FAST_FLASH_BOOT`, RAMLOG
  stages through `setup-end` (8566 ms) and `first-mainMenu`. Known benign: theme file
  missing -> default theme. The boot screen version string is TFT-only and was not read;
  firmware identity rests on the verified hash of this build.
- NOT RUN on phone/device: F32 (LoRa receive -> Stop all/Remote -> USB unplug -> reconnect,
  no stray `stop` on the new link), F34 (rejected/lost companion_exit, retry, Stop, disconnect;
  mirror recovers after a device reboot only in a successful session), plus every earlier
  open device check (IR blast, FM owner, Remote leave/rotation, Stop-all ordering, F23).
  These app-side fixes need the matching APK on a phone; none was installed in this run.
- Release gate: **closed**. F32/F34 are fixed at host level only until the phone checks pass.

## Commit d2e24911 review — 2026-11-06

Read-only code review; only review records updated. No upload, serial-port open,
device command, commit, push or release. The reported failed upload cannot be
classified from compilation success. The raw upload log was not found among the
reviewed repository reports. `/dev/cu.usbmodem2101` was visible during this review;
enumeration alone establishes neither the running firmware nor a successful flash.

Fresh verification:

- `cd android && ./gradlew --offline :app:testDebugUnitTest --tests '*IrPowerSpamServiceTest' --tests '*StopAllSequenceTest' --tests '*LoraServiceTest' --tests '*JamDetectServiceTest' --tests '*RemoteLinkGateTest' --rerun`:
  **37 tests PASS**, 5 XML suites, zero failures/errors/skips; test task executed.
  Compilation reused up-to-date outputs. The full 1111-test and board-build claims
  below remain implementation evidence, not fresh checks by this review.
- `python3 -m unittest tools.test_radio_guards`: **37 tests PASS**, 5.337 s.
- `python3 .codex_tmp/review-d2e24911/probe.py`: real services with a held coroutine
  dispatcher and simulated transport. **LoRa failure reproduced:** queue its stop,
  call teardown `stop()`, select link B, resume the sender -> old stop targets B.
  **IR and JamDetect controls PASS:** the queued stop is cancelled. **F26 PASS:**
  still-busy timeout reports failure and does not execute remaining device stops.
  Source hashes and output retained in that ignored directory's `result.json`.

F34 CODE finding: unsuccessful companion_exit returns from remoteJob but leaves the
separately launched watchdog alive; retry overwrites its handle. Test failed start,
retry, Stop and disconnect with zero orphan watchdogs. The ACK gate itself is now
present; the current source-level test does not verify this cleanup. FM song/RTTTL/
DTMF release and async-song cleanup changes are present; no physical FM check here.

F32/F34 remain partially open despite passing tests. The upload status for this
revision is FAILED/UNVERIFIED as reported, not “flashed”; older passive results
must retain their original firmware identity.

## Integration and IR follow-up review — 2026-11-06

Reviewed the F25-F31 changes and new IR service. During the review another session
created integration commit `1db4b4d8` and release commit `33494d91` (0.1.7). The
service sources used by the probes remained byte-identical across that boundary.
This review did not modify production code, use hardware, flash, commit or publish.

Fresh checks:

- `python3 -m unittest discover -s tools -p 'test_*.py'`: **305 PASS**, 49.110 s.
- `cd android && ./gradlew --offline :app:testDebugUnitTest --rerun`:
  **BUILD SUCCESSFUL**, 27 s, test task actually executed (the preceding run was
  UP-TO-DATE). Compilation tasks reused their outputs. This is not a fresh firmware,
  lint or device run; the 1105 count below is the implementation report's count.
- `python3 .codex_tmp/integration-followup-20261106/probe.py`: real Kotlin services
  with simulated runners, source hashes and output retained in `result.json`.
  **F32 reproduced:** a delayed stop for IR run A is delivered during run B after A
  naturally completes and B is admitted. **F26 reproduced:** `StopAllSequence` calls
  the remaining stops after its timeout although the blocking predicate is still
  true (40 ms injected bound, same branch as the production 8 s; no firmware run).
- `c++ -std=c++17 .codex_tmp/integration-followup-20261106/audio_owner.cpp -o /tmp/brucia-audio-owner-review && /tmp/brucia-audio-owner-review`:
  **F21/A3 reproduced:** actual `speakerOutputRelease` and `speakerFileAbort` bodies,
  stubbed audio hardware; an async song remains active with `spkFileActive=false`.
  This demonstrates incomplete cleanup, not a physical I2S collision on a board.

Additional CODE findings: F33 malformed explicit IR duration starts the default
window; F34 Remote entry does not await IR/other blocking completion or a successful
`companion_exit`; FM song/RTTTL/DTMF paths omit the release used by tone/file/PCM.
F27-F31's basic fixes are present; phone/Compose acceptance remains pending.
F26 and FM ownership must not remain fully DONE. New regression criteria are in
the backlog and [checklist](docs/APP_CONTROLS_CHECKLIST.md#follow-up-regressions--2026-11-06).

IR output has not been exercised by this review or by the recorded passive
`idle_stops` check. The UI text “until you Stop” does not describe its default
30-second window. Table enumeration is not proof of complete coverage within a
bounded window or of receiver compatibility; Stop is polled between codes, not
inside an individual send. Keep those limits visible rather than claiming immediate
cessation or universal device coverage.

## Follow-up fixes F26/F32/F33/F34 + FM owner (A3) — 2026-11-06

Implementation follow-up to integration `1db4b4d8` / release 0.1.7. The original
five paths were changed; the independent d2e24911 review above retains F32/F34 gaps.

- `./gradlew :app:testDebugUnitTest`: **1111 tests, 0 failures**. New/changed: `IrPowerSpamServiceTest`
  (restart fenced until the stop is written; teardown cancels an unsent stop), `StopAllSequenceTest`
  (timeout withholds the device stops and fires `onTimeout`; the old "runs after the bound" case is
  inverted), `RemoteLinkGateTest` (handover IR/LoRa/jam steps + `companion_exit` awaited before the
  gate/reader).
- `python3 -m unittest discover -s tools -p 'test_*.py'`: **305 tests PASS**. `test_radio_guards.py`
  checks the IR strict parse (reject before pin/driver, range, nonnumeric) and the FM owner for
  song/RTTTL/DTMF plus the async-song stop in `speakerOutputRelease`.
- Firmware builds reported SUCCESS: T-Embed CC1101 and Cardputer. The attempted
  upload of this revision FAILED mid-transfer according to the implementation
  handoff and commit message; device de-enumerated. Successful flash and on-device
  acceptance of this revision remain UNVERIFIED.
- Limits: F32/F34/F26 are host/source level (the full VM and the firmware poll are not executed on
  device); the IR blast and FM owner still need an on-device check (transmit / no Si4713 here). The
  earlier passive device results (busy SUB-409, idle stops) are historical evidence
  for their tested firmware, not acceptance of this revision after a failed upload.

## IR power spam (new feature) — 2026-11-06

User-requested: `ir_power_spam [secs]` blasts every power table (NA + EU + universal parsed + raw),
repeating until the window ends or a stop. Firmware (`runIrPowerSpamHeadless` + companion dispatch,
polling `companionPollStop`), app `IrPowerSpamService` + IR actions card.

- `./gradlew :app:testDebugUnitTest`: **1105 tests, 0 failures** (`IrPowerSpamServiceTest`: clamp,
  running/result texts, busy-ignored, polled stop keeps waiting for the reply, not-connected).
- `python3 -m unittest discover -s tools -p 'test_*.py'`: **305 tests PASS** (`test_radio_guards.py`
  checks the loop covers all four tables and polls stop; `test_companion_dispatch_stops.py` and the
  mock add `ir_power_spam_stop` as an idle stop).
- Firmware: `pio run -e lilygo-t-embed-cc1101` SUCCESS; flashed to the device.
- On device (T-Embed flashed with this firmware): idle `ir_power_spam_stop` -> ok "not running"
  **PASS** (hw_acceptance `idle_stops`), and `caps` PASS.
- **Device check owed (not run):** the spam itself transmits IR, so the passive acceptance runner
  does not send it. A human must confirm on permitted hardware that it switches displays off and
  that Stop ends it promptly.

## Backlog A2-A5 + stop follow-ups — 2026-11-06

Scope: uncommitted tree above `354a32db`; app and firmware (NRF24 loops and Bruce NRF24 module
abort hooks, audio owner, `companionPollStop` SUB reply, ARPScanner lock order). No device
command, flash, RF output, commit or release.

- `./gradlew :app:testDebugUnitTest`: **1101 tests, 0 failures** (155 classes).
- `python3 -m unittest discover -s tools -p 'test_*.py'`: **304 tests PASS**, 46.3 s (includes
  the mock e2e SUB-409 and idle `nrf24_stop` cases and the selected Kotlin run).
- `tools/test_firmware_diag.py` 19/19, `tools/test_companion_prompts.py` (ARP order, mutation
  verified to fail on the old order), `tools/test_radio_guards.py`, `tools/test_companion_dispatch_stops.py`.
- Firmware: `pio run -e lilygo-t-embed-cc1101 -e m5stack-cardputer` **SUCCESS** on the final tree (Cardputer compiles FM audio + NRF24; T-Embed has no Si4713). Build only, not flashed.
- On device (T-Embed CC1101, this tree flashed over USB `pio run -t upload`, `hw_acceptance.py`,
  all passive / non-transmitting): `busy` **PASS** - PONG, a CMD and a SUB all get ERR 409 while
  `jam_detect` runs, and `stop` ended it in 0.11 s; `idle_stops` **PASS** - `nrf24_stop` and
  `jam_detect_stop` answer ok "not running" on an idle device; `script_stop`, `caps`, `provenance`,
  `evidence`, `ping` (median 3.0 ms), `malformed`, `fs_list/read/upload`, `streams`, `diag_counters`
  all **PASS**. `heap` fell on the first run (one-time Wi-Fi allocation from `jam_detect` setup);
  two repeat runs held free heap flat (68823 -> 68823), so no leak. Not exercised on device:
  NRF24 Stop timing (no NRF24 module fitted), FM owner (no Si4713), the ARP key-wait path.
- Remaining hardware limits: NRF24 stop timing, FM ownership (no Si4713 here), and
  the ARP key wait were not exercised. The implementation report immediately above
  records SUB 409 on the T-Embed; the earlier claim that it was only simulated is superseded.

## F25-F31 software fixes — 2026-11-06

Scope: uncommitted tree above `354a32db`; app-only changes (no firmware, protocol or
Python tool edits). No device command, flash, RF/BLE output, commit or release.

- `./gradlew :app:testDebugUnitTest` (JDK 23, cached Gradle): **1094 tests, 0 failures,
  0 errors, 0 skipped** across 155 classes. Not a lint, instrumentation, screenshot,
  signed-release or firmware build.
- New/extended regressions: `StopAllSequenceTest` (F26: real `LoraService` +
  `EvilPortalService` against a model of the firmware stop poll; frame order `lora_recv`,
  `stop`, `evil_portal_stop`, portal ACKed and inactive, benign command served; the
  unordered control still loses the portal stop), `StreamClaimTest` (F28/F29),
  `RemoteLinkGateTest` (F25, source-level), `BleHidServiceTest` / `EvilPortalServiceTest`
  (F27), `DeviceFileBrowserTest` (F30), `LocalFilesCleanupTest` (F31),
  `StreamLeaveHooksTest` (Remote, Channels, Portal leave hooks).
- Limits: the ViewModel and composables are not executed (F25 wiring and the
  F28/F29 screen effects are checked at source level or through the extracted
  classes); the firmware poll is modelled, not run; no phone storage failure was
  induced. The probe table below records the pre-fix tree and is superseded for the
  rows marked fixed in the backlog. Python suite not rerun (unchanged sources).

## Cross-feature integration review — 2026-11-06

Scope: current uncommitted tree above release `354a32db`, including the latest
CompanionClient inactivity fix. Audited catalog/screen routes and cross-feature
ownership, cancellation and error handling against the existing four feature audits.
All 50 stable catalog IDs are unique and have screen routes. This does not establish
that all controls or physical effects work. No production source edits, device commands,
flash, RF/BLE output, commit or release were performed by this review.

Fresh verification:

- `python3 -m unittest discover -s tools -p 'test_*.py'`: **300 tests PASS**, 48.835 s.
- `python3 tools/test_android_logic.py`: **265 selected Kotlin/JUnit tests PASS**.
  Cached compiler/dependencies; this is not a fresh full Android/Gradle, lint,
  instrumentation, screenshot, firmware or signed-release build.
- `python3 .codex_tmp/integration-review-20261106/probe.py`: production Kotlin
  services compiled with cached dependencies and simulated command runners. Four
  service defects reproduced, with a LoRa positive control and a combined lifecycle model. No physical side effects.
  Source hashes/results retained in that ignored directory's `result.json`.

| Probe | Actual result on this tree | Required result after a fix |
|---|---|---|
| F26: `LoraService.receive(120)` then `stop()` (the hook selected by global Stop all) | `receiving=false`, status `Stopped.`, **zero** stop frames | LoRa hook must request device stop; disconnect remains local cleanup |
| F26 control: receive then `stopReceive()` | Exactly one `stop` frame | Keep this behavior and test wiring through the lifecycle inventory |
| F26 combined model: real LoRa/portal/lifecycle classes, simulated firmware suffix-stop rule | Portal stop consumed by LoRa, simulated portal still active, portal reply pending | Stop blocking operation first, then confirm background-service stops without consuming the wrong request |
| F27: BLE HID begin succeeds, end returns false/Timeout | Status becomes `off` | Stop failure/unknown state must remain visible; do not assert device off |
| F27: portal start succeeds, stop returns false/Timeout | `active=false`, status Timeout | Do not assert stopped based on a failed reply; reconcile actual state |
| F30: Files list returns false/SD read failed | Empty entries, blank listStatus, loading=false | Visible listing error, distinct from an empty successful directory |

F26 caveat: the isolated LoRa hook sending zero frames does NOT prove global Stop
never stops LoRa. Other lifecycle hooks send `evil_portal_stop`/`speaker_stop`, and
`companionPollStop` accepts any `_stop` without replying. Those can end LoRa but be
lost to their intended service. The combined probe models that firmware rule; it
does not execute the full Android VM or the firmware poll. On-device ordering remains
unverified, and no guaranteed 120-second continuation is claimed.

Additional source findings, not device reproductions: F25 Remote/raw mode leaks into
normal navigation; F28 screens stop another owner's Wi-Fi scan; F29 Channels does not
start/restart after connecting on the same screen; F31 export IO errors escape several
button callbacks (extends DT07). Details and acceptance are in the backlog and
[new checklist](docs/APP_CONTROLS_CHECKLIST.md#cross-feature-integration-acceptance--2026-11-06).
These findings remain OPEN despite the passing suites. The earlier F23 quiet soaks
are unchanged: zero radio events is not a stress test under radio-EVENT load. BLE
packet emission and receiver/macOS reaction remain separate, unverified outcomes.

## Functional contract investigation — 2026-11-05 (after batch 14)

Scope: investigate owner-reported De Bruijn reboot and missing Apple/Mac BLE
visibility; source review plus host-only diagnostics. No production source change,
device command, RF/BLE transmission, firmware flash or fresh full-suite run.
Existing batch-14 results below remain historical evidence for their stated scope.

Local reproducible diagnostic:
`python3 .codex_tmp/functional-contracts-20261105/probe.py`.
It compiles the actual `DeBruijn.kt` and `FrameBudget.kt` with cached Kotlin 2.4.21,
then extracts the actual `rfTxCallback` and `runBleSpamBounded` function bodies into
C++ host harnesses. All radio/device calls and the BLE payload builder are stubs.
The harness catches exceptions to inspect failure before any hardware call;
it does not emulate an ESP32 panic or prove the reset cause on the owner's board.

Results observed:

| Case | Result |
|---|---|
| De Bruijn n=4 / 5 / 6 | 16 / 32 / 64 generated bits; CLI stub receives hardcoded 24-bit transmission width in all three cases |
| n=7 / 8 / 10 / 12 / 14 | All raise `std::out_of_range` in the actual CLI callback, zero RF-init calls; default n=12 supplies 1,024 hex characters |
| n=15 / 16 / 20 / 22 | Command payload upper bounds 8,263 / 16,455 / 262,215 / 1,048,647 bytes; all exceed the 8,192 ceiling, even before considering a smaller advertised `rx_max` |
| n=23 / 24 (offered by UI) | Actual generator throws `IllegalArgumentException: n out of range`; UI click handler has no catch |
| BLE advertising failure stub, 8 s / copies=1 | 348 failed start attempts, zero successes, wrapper returns normally after 8,009 virtual ms |
| Same, copies=100 | 400 failed start attempts, zero successes, wrapper returns normally after 9,205 virtual ms |

The legacy route (`commands.cpp` → SimpleCLI → `rfTxCallback`) has no catch around
the throwing numeric conversion. This is a concrete contract failure and a strong
explanation for the report, pending device reset/backtrace evidence. The existing
two `DeBruijnTest` cases were rerun and both PASS: they check sequence mathematics
and hex length, not this route. This illustrates a coverage gap, not a failed unit
suite. Input hashes and diagnostic results are retained in the local `result.json`.
Larger De Bruijn values already fail the app's frame limit, so the host parser result
for those values must not be reported as proof they reach firmware from the app.

BLE dispatcher success is unconditional after the void bounded wrapper returns;
driver failures and remote UI appearance are not measured. The Mac-labelled card
routes to `ios`, not a separate Mac implementation. No macOS compatibility result
was obtained. Source review also identified shared BLE owner-state risks, the IR
pin-selection path, script timeout/busy recovery, and early-return TCP/IP lock
imbalance in `ARPScanner::setup`; these are not new on-device reproductions.

The prioritized [20-case functional acceptance plan](docs/APP_CONTROLS_CHECKLIST.md#functional-acceptance-priorities--2026-11-05)
records expected effects, negative cases, ownership transitions and recovery.
No physical checkbox was changed. F01–F04 require host rejection/recovery work;
the general 12/12 hardware link suite does not close them.

## Unit-test coverage (JaCoCo) — 2026-11-05, branch agent15/COVERAGE

How to measure (opt-in; the normal build is not instrumented):
`cd android && ./gradlew -Pbrucia.coverage :app:createDebugUnitTestCoverageReport`
(JDK 23 as for the other Gradle commands). The flag sets AGP `enableUnitTestCoverage` on the
debug build type; the report is `app/build/reports/coverage/test/debug/` (`index.html`,
`report.xml`). It covers `:app:testDebugUnitTest` only: Paparazzi screenshots run in that task,
instrumentation tests (Room migrations, ConnectionService, Compose UI) do not.

Line / branch coverage per package, before (base 6a0f9931, 882 tests) and after (919 tests):

| Package | Before | After | Notes |
|---|---|---|---|
| update | 62.5% / 67.1% | 83.4% / 81.0% | `EspFlasher` 0% -> 74.7% / 58.3% (scripted ROM/stub); `FirmwareImage` branches 63% -> 87% |
| wifi | 85.4% / 60.9% | 96.0% / 74.0% | `HandshakeParser` 50% -> 97% lines (PMKID path was 0%); `PcapReader`, `Dot11Frame` 100% lines |
| protocol | 96.6% / 90.5% | 97.9% / 97.1% | `FrameBudget` bounds checked against the bytes `CompanionClient` writes |
| subghz | 87.8% / 66.0% | 90.9% / 76.4% | `.sub` transmit validation, parse fallbacks, RAW line split |
| remote | 59.0% / 48.7% | 69.1% / 60.5% | `TftProtocol` 100% lines: every op checked against `include/tftLogger.h` argument order |
| automation | 90.0% / 69.6% | 100% / 89.1% | saved-macro store round trip and corrupt store |
| session / files / transport | 98.1 / 93.5 / 75.9% lines | unchanged | already covered; `UsbTransport` needs a USB device |
| data (Room) | 2.0% / 11.1% | unchanged | DAOs and migrations run in `BruciaMigrationTest` (emulator), not on the JVM |
| service, mitm proxy/CA, app UI (`app.brucia`, `wardrive` view model) | low | unchanged | Android framework/Context bound; UI layout covered by Paparazzi, not counted as a gap |
| Total | 34.8% / 36.9% | 36.2% / 39.1% | totals are dominated by Compose UI and `WardriveViewModel` |

Gaps closed were chosen by risk (device input parsing, flash path, user data), not count. Seven
tests failed against the base code and pass after minimal fixes (CHANGELOG, Unreleased):
`EspFlasherTest.romErrorStatusIsNotMaskedByTheReservedBytes` (ROM status read from the reserved
bytes), `HandshakeParserTest.completeFourWayHandshakeKeepsMessageTwo` (M3/M4 taken as M2),
`HandshakeParserTest.hugeRecordLengthStopsParsingWithoutThrowing` and
`PcapReaderTest.truncatedAndCorruptRecordsStopCleanly` (Int overflow; the reader hit
OutOfMemoryError), `PcapReaderTest.nanosecondCaptureIsConvertedToMicroseconds`,
`SubFileTest.transmitRefusesFilesThatWouldBreakTheDeviceCommand` and
`TftProtocolTest.screenInfoCarriesSizeAndOneByteRotation`. `LoggingTest` failed when run on its
own (layoutlib `android.util.Log` throws UnsatisfiedLinkError); `BruciaLog` now also swallows
LinkageError from logcat.

Flash status length, sources: esptool 4.7 (`/Applications/M5Burner.app/.../esptool`)
`targets/esp32.py:31` `ESP32ROM.STATUS_BYTES_LENGTH = 4`, inherited by `ESP32S3ROM(ESP32ROM)`
(`targets/esp32s3.py:15`) and the C3/S2/C6/H2/C2 ROM classes; every `*StubLoader` sets 2
(`esp32s3.py:318`, `esp32.py:381`, ...); `loader.py:258` base (ESP8266) 2; `loader.py:446` reads
`data[-STATUS_BYTES_LENGTH:]`. The pinned esptool 5.3.0
(`~/.platformio/packages/tool-esptoolpy/esptool/loader.py:631-659`) instead reads the first two
bytes after the response data and notes that ROM loaders except ESP8266 append two reserved
bytes. The app flashes only the ESP32-S3 (board check `t-embed`+`cc1101`, `FirmwareImage` chip id
9, ESP32-S3 stub), so ROM 4 / stub 2 is right for every image it accepts; there is no chip
detection. A reply shorter than expected (a 2-byte stub reply while ROM length is assumed) is read
from its first two bytes, as esptool does. The old code read the last two of the ROM's four
bytes, the reserved zeros: S3 successes were accepted, ROM failures too.
`EspFlasherTest.byteExactS3RomThenStubSessionSucceedsAsBefore` replays a byte-exact S3 ROM + stub
session (8 SYNC replies, READ_REG, stub upload, OHAI, SPI_ATTACH/SET_PARAMS, FLASH_BEGIN/DATA/END,
stub MD5) and passes on both the old and the new code; every reply also passes the old 2-byte
reading.
Found here and fixed in batch 16 (FLASHREAD, above): the reply reader dropped the rest of a USB
read after the reply it waited for, so an "OHAI" sharing a read with the MEM_END reply was lost.

Still uncovered and why: `EspFlasher` reset sequences, SYNC failure and timeouts sleep in real
time (seconds per case) and are not exercised; the flash flow is not hardware-verified by these
tests (S05). The firmware side of the `.sub` problem (`rfTxByNameCallback` uses throwing
`std::stoul/stoull`) stays under F03. Firmware companion pure cores (`*_core`, codec, framing,
upload, spectrum, FM ring) already have host tests; remaining companion handlers mix parsing
with drivers and were not split for this task.

Verification on this branch: `:app:testDebugUnitTest` 919/919 (921/921 after the flash replay
tests), `lintDebug` 0 errors
(2 warnings: OldTargetApi, AndroidGradlePluginVersion), `verifyPaparazziDebug` unchanged,
`assembleDebug` OK; `python3 tools/test_android_logic.py` 235 tests OK (now also runs
FrameBudgetTest, HandshakeParserTest, PcapReaderTest, SubFileTest);
`python3 -m unittest discover -s tools -p 'test_*.py'` 175 OK (2 skipped); every
`tools/test_*.py` passes except `test_audio_noise_bounds.py`, which needs `.pio/libdeps` (no
firmware build or dependency install in this worktree). No device was connected.

## Latest software checkpoint — 2026-11-05, candidate 0.1.4

Four coordinated batches (backlog S01/S02/S06/S08/S10/S11/W01-W03/W11, firmware items 2-5).
Merged tree before release: Android `:app:testDebugUnitTest` 760/760, `lintDebug` 0 errors
(14 warnings: 13 GradleDependency, 1 OldTargetApi), `verifyPaparazziDebug` unchanged;
22/22 `tools/test_*.py`; website 15/15; emulator (API 36) instrumentation 7/7 and a debug
versionCode upgrade keeping Room v3 data. Firmware builds: T-Embed CC1101 (RAM 47.6%, flash
25.8% of chip), Cardputer, core4mb (LITE), LAUNCHER_CYD, nesso-n1; 12 envs compiled in the
matrix run. Reproducible: two checkouts of one commit gave identical firmware.bin. No device
was connected for any of this; every item below in "Batches 1-4 - verify on hardware" is open.

### Batch 16 (FLASHREAD) - flash replies that share or split USB reads, software only

The coverage work (batch 15, section "Unit-test coverage") found that `EspFlasher.readResponse`
decoded one USB read, returned at the reply it was waiting for and dropped the rest of that read.
The esptool stub's "OHAI" greeting was then lost whenever it shared a read with the MEM_END reply,
came before it, or arrived without one (it was skipped as a non-response while waiting for
MEM_END); the flash went on as if the ROM loader were still running. Now `SlipCodec.Receiver` (in
`EspProtocol.kt`, pure) keeps one receive buffer across reads and commands: frames are taken one at
a time, the bytes after a frame stay buffered, a partial frame or an escape split between reads is
completed by the next read, and a greeting seen during the MEM_END wait is remembered for the stub
check. Frames are parsed when taken, so the batch 15 status lengths hold (ROM 4 bytes until OHAI,
stub 2; short replies read from their start). `FrameReader` now treats the END that closes a frame
as a possible opening of the next one, so junk after a stray 0xC0 that never completes is closed
by the next reply instead of swallowing it.

Evidence: `SlipReceiverTest` 12 (two replies in one read, reply + OHAI, OHAI + next reply, byte by
byte, every split point of an escaped reply plus the following frame, DB DC / DB DD split across
reads, boot text before a frame, a partial frame left at a timeout, an abandoned junk frame,
clear, buffer growth/compaction); `EspFlasherTest` +8 against the scripted fakes (now also handing
out everything in one read or 1..24-byte reads; the fake stub rejects the ROM form of SPI_ATTACH
as the real one does): reply + OHAI in one read, OHAI before the MEM_END reply (one read and
separate reads), OHAI with no MEM_END reply, every read size with an image whose stub MD5 holds
0xC0 and 0xDB, the ROM fallback and ROM FLASH_BEGIN error at several read sizes, boot text before
the first reply, a SYNC reply cut at a timeout and completed by the retry's read, and the byte-exact
0.1.5 S3 session at every read size (same requests, MD5 match). Kept and rerun unchanged in
substance: `byteExactS3RomThenStubSessionSucceedsAsBefore` (per-reply reads, as on hardware) and
`romErrorStatusIsNotMaskedByTheReservedBytes`. Mutations: the base `EspFlasher.kt` (drop after
reply) fails 6 of the 8 new flasher tests (all the read-sharing ones; the two passing are the
per-read-safe ROM error and partial-SYNC cases), and passes both kept tests; a `Receiver` that drops
the rest of a read fails 7 receiver and 3 flasher tests; the old frame-close rule fails
`abandonedJunkFrameDoesNotSwallowTheNextReply` only. Counts: `:app:testDebugUnitTest` 941/941 (921 + 20), `lintDebug` 0 errors (2 warnings: OldTargetApi,
AndroidGradlePluginVersion), `assembleDebug` OK, `python3 tools/test_android_logic.py` 247 OK (now also
runs SlipReceiverTest with `update/EspProtocol.kt`). No device connected.

- [ ] Phone flash recheck (T-Embed CC1101, ESP32-S3): flash a release from the app over USB. Expected
      as with 0.1.5: the flash log shows the stub started (no "command 0x0D failed"), every block
      written, **verified OK (MD5 matches)**, and the device boots the flashed version.

### Batch 16 (DEBRUIJN) - De Bruijn transmit crash fixed and bounded, software only

Backlog F01-F02. Reproduced the defect on the base before fixing, host-only:
`tools/test_firmware_debruijn.py` compiles the single-code key parser natively with an independent
De Bruijn generator that mirrors `android/.../subghz/DeBruijn.kt`; order 8/10/12 produce 64/256/1,024
hex characters, and the original `std::stoull(key, …, 16)` throws `std::out_of_range` for all three.
The throwing path is Bruce CLI, not companion: `rfTxCallback` is in
`src/core/serial_commands/rf_commands.cpp` (exceptions enabled; only `src/companion/**` is built
`-fno-exceptions`) and `src/companion/commands.cpp` has no try/catch around `serialCli.parse`, so the
throw reaches `std::terminate` and aborts — consistent with the owner's reboot. (The host harness
cannot emulate the ESP32 panic; the physical reset trace is still unobtained.)

Fix. Firmware: `src/modules/rf/rf_hexkey.h` `rfhex::parseKey64` parses `subghz tx`/`subghz txp`
keys and rejects empty, non-hex or over-16-hex-digit input with a clear error and `return false`
(no throw, device state preserved, next command accepted). Transmit capability is unchanged — one
fixed code of at most 64 bits, nothing larger on air. App: the custom order field is bounded to the
generator range (1-22; was 4-24, which threw at 23/24), presets drop 24, and `transmitDeBruijn`
refuses with a reason and transmits nothing instead of dispatching an uncarriable key plus a false
"Transmitting" toast.

Evidence, software only:
- `tools/test_firmware_debruijn.py` 3/3 (valid short/max/0x keys parse; empty/null/non-hex/17-digit
  and the exact app-generated De Bruijn keys for every offered order rejected without throwing; a
  mutation of the length guard `> 16` -> `> 17` is caught — the test then fails).
- App JVM `DeBruijnTest` (added cases): offered orders within 1..22, transmit refused for every
  offered order with a reason naming the sweep size and "Nothing was transmitted", out-of-range
  orders rejected with the bounds, and every offered sweep exceeds the 64-bit device code.
- `tools/test_protocol_e2e.py` and `tools/mock_device.py`: a valid single code is accepted, an
  over-long sweep key gets an error ACK (ok:False, "invalid key"), and the device is still
  responsive (diag) afterwards.
- Totals: Android `:app:testDebugUnitTest` 925/925, `lintDebug` 0 errors, `assembleDebug` OK;
  `unittest discover -s tools` 188 OK; 35/35 `tools/test_*.py` (incl. `test_audio_noise_bounds.py
  -e lilygo-t-embed-cc1101`); T-Embed CC1101 build OK, eh-catch-guard OK (9 sites), RAM 47.6%,
  flash 25.7% (firmware.bin 4,318,928 bytes).
- Not changed here (backlog F03): `std::stoul` on frequency/te/count/bits in the same callbacks can
  still throw on malformed numbers; the app never sends those for De Bruijn any more.

Nothing hardware-verified. Owner check below uses rejection-only inputs (nothing transmits).

- [ ] T-Embed CC1101 in companion mode, rejection-only (NOTHING should go on air): in the app's
      Sub-GHz screen, De Bruijn card, press "Transmit de Bruijn" at the default order 12 (and at 8,
      10, 16, 20, 22). Expected each time: a toast/console line saying the device cannot carry the
      sweep and nothing was transmitted, NO reboot, the screen stays in companion mode, and the next
      action (e.g. a frequency change or `diag`) still works. The device must NOT transmit; any check
      that would put a signal on air is out of scope for this task.

### Batch 16 (BLEADV) - BLE advertiser error checking, shared-stack ownership, honest texts; software only

Closes the two BLE-advertiser gaps the 2026-11-05 functional-contract investigation named: the
dispatcher reported success unconditionally after the (void) bounded wrappers returned, and the
advertisers shared the single NimBLE controller with the `ble.scan` stream and the BLE HID presenter
with no ownership guard.

Firmware (`src/modules/ble/ble_spam.cpp`, `src/companion/bluetooth.cpp`): the five bounded runners
(`runBleSpamBounded`, `runBleFastPairSpamBounded`, `runBleSwiftPairSpamBounded`,
`runBleSamsungSpamBounded`, `runIbeaconBounded`) now return `bool` and check every NimBLE call that
can fail - `BLEDevice::init`, `esp_ble_tx_power_set`, `getAdvertising()` (null), the iBeacon
`esp_iface_mac_addr_set` address change, and every `setAdvertisementData` / `start` / `stop` - so a
driver failure returns `false` and the stack is still torn down on every exit path. The dispatcher
turns that into `ACK` only on success and `ERR 500 "ble advertise failed"` otherwise (was always a
success `ACK`). Ownership: a new `bleAdvertiserConflict()` refuses `ble_spam` / `ble_ibeacon` /
`ble_fastpair_spam` / `ble_spam_platform` with `ERR 409` when a scan (`subBle`) or a BLE HID
presenter (`g_companionHid`) already owns the stack, rather than deinit it mid-flight and report a
false success; `ble_hid_begin` refuses (409) while a scan runs (its `end()` calls
`BLEDevice::deinit(true)`), and `ble_badble` / `ble_media` refuse (409) while a HID presenter owns
the stack. No Bruce menu path changed (the on-device `ibeacon()` / spam menu are untouched).

App: the BLE spam / iBeacon / Spam-hub texts no longer promise a popup or prompt on a receiver.
They describe what is broadcast (advertisements of type X) and state that a receiver's reaction
depends on its OS version and settings and has not been verified. The burst loop already surfaces a
command's `ERR` text, so the firmware 409/500 now shows as the tool's status instead of a silent
success.

Evidence, host-side only: new `tools/test_firmware_ble_adv.py` (11 cases: every advertiser call
checked, no bare advertise statement, bool returns, 409 scan/HID conflict guards, ACK gated on the
bool result, the all-platforms loop aggregates failures, HID/BadBLE/media HID guards); new app JVM
`BleSpamTextTest` (banned effect-promise phrasings absent, honest wording present, a firmware busy
reply surfaced via `BurstLoop` not reported as success). `tools/test_firmware_ble_adv.py` 11/11;
`test_companion_prompts.py`, `test_companion_print_hygiene.py`, `test_companion_serial_input.py`,
`test_lite_companion.py` still pass. Firmware builds SUCCESS: T-Embed CC1101 app 4,319,344 B / 92.83% (+656 B against the
batch 15 figure 4,318,688 B); core4mb (LITE) app 2,662,736 B / 67.72% (+192 B against 2,662,544 B);
AAC noise bounds pass on both. Android: `:app:testDebugUnitTest` 925/925, `lintDebug` 0 errors,
`verifyPaparazziDebug` unchanged, `assembleDebug` OK; `unittest discover -s tools` 196 tests OK.
Nothing hardware-verified.

Owner checks (do not overclaim: observing a receiver's reaction is optional and recorded as
observed, never promised):

- [ ] T-Embed CC1101, app connected, BLE tool: turn the Scan stream ON, then press Start on "BLE
      spam (Apple ProximityPair)". Expected: the tile shows a busy error (the device's 409 "ble
      scan active" message), not "Sending..."; the scan list keeps updating. Turn Scan OFF, press
      Start again: it runs (status "Sending ios...").
- [ ] Normal start/stop does not break the scan: with Scan OFF, Start then Stop the Apple spam a few
      times; then turn Scan ON. Expected: the scan still lists devices (the advertiser's init/deinit
      left the stack clean); no reboot, the app stays connected.
- [ ] BLE HID presenter active (`ble_hid_begin`, pair "Brucia"): press Start on any BLE spam target
      and run BadBLE. Expected: both show a busy error ("ble hid session active"), the HID presenter
      stays paired and usable; `ble_hid_end` afterwards still stops cleanly.
- [ ] iBeacon: Broadcast for a few seconds, then Stop. Expected: status "ibeacon done" on success,
      or the device's error text on failure - never a success when the radio refused.
- [ ] Optional, recorded as observed only (not a pass/fail): with a nearby Mac/iPhone/Android in
      range and its Bluetooth settings open, note whether any pairing prompt appears while a spam
      target runs. Record the receiver OS version and the observation; absence of a prompt is not a
      firmware failure.

### Batch 16 (SCRIPTSTOP) - the app's script Stop stops the device script; software only

App: on the Scripts screen Run turns into Stop while a script runs. Stop sends `script_stop` once
(direct and unlogged, like the Responder/Karma stops; preflight lets every stop through) and keeps
waiting for the `script_run` reply, which shows the outcome (`Script stopped.`; the output plus a
note when the script ended before the stop took effect or the firmware ignores it; after 3 s a
"still waiting" text). No new run until that reply arrived and the stop frame was written; Stop
when idle, twice, or after the end sends nothing; Stop all sends the stop; disconnect drops the wait.
Firmware (source review): every interpreter exit runs bjs_task_finished (deadline, abort, task
handle, state -1); `script stopped` is sent only after the task ended, so an immediate `script_run`
is accepted. Gap fixed: the poll that saw the task end copied the abort in again, leaving
`g_bjsAbort` set until the next start (benign: every start clears it); script_run now drops it once
the task is gone. Evidence, host-side only: `ScriptServiceTest` 12 tests (GateRunner and the real
CommandSession on a recording transport with the virtual clock: exact CMD envelopes
`script_run <src>` / `script_stop`, one stop per run, no stop when idle/after the end, Run refused
until the reply and the stop write, late replies to the run and to the stop ignored, local timeout,
disconnect while stopping), `DeviceOperationTest` +1; `test_firmware_script_abort.py` stop-then-new-
run model (stopped reply within a few polls, handle/abort/deadline cleared, next run accepted and
not aborted, late stop harmless; the old wiring left the abort set) plus wiring checks;
`test_hw_acceptance.py` +7 (script_stop PASS on the mock, FAIL when the firmware ignores the stop or
stays busy, SKIP on LITE, allowlist). `unittest discover` 185 tests, `test_hw_acceptance.py`
108/108, `test_android_logic.py` 235 tests, Gradle unit tests, lint and assembleDebug pass. Firmware
builds SUCCESS: T-Embed CC1101 app 4,318,704 B / 92.81% (+16 B against the batch 15 FWMENUS
figure), static RAM 156,040 B (unchanged). Nothing hardware-verified.

- [ ] T-Embed CC1101 in companion mode, app disconnected (pyserial):
      `python3 tools/hw_acceptance.py --port <port> --only caps,script_stop,diag_counters`.
      Expected: exit 0, `script_stop` PASS: no reply for 2 s, PONG while the script runs,
      `ok:false` `script stopped` within 2 s of `script_stop`, no reply to the stop itself, then
      `script_run console.log('brucia-after-stop');` answers `ok:true` `brucia-after-stop` (not
      `interpreter busy`); `diag_counters` PASS. The runner sends only the two fixed scripts (a
      loop of `delay(100)`, one `console.log`) and `script_stop`; the script screen shows on the
      device. First run (batch 15 firmware, check then using `print`): stop part PASS on hardware
      (`script stopped` 0.564 s after the stop, next run accepted after 1.05 s), FAIL only on the
      follow-up's `ReferenceError: variable 'print' is not defined` (reported `ok:true`).
- [ ] With this batch's firmware: `--cmd "script_run print(1)"` answers `ok:false` with the
      ReferenceError text; `--cmd "script_run console.log(1)"` answers `ok:true` `1`;
      `--cmd "script_run exit()"` answers `ok:true`; a loop of delay()s left to the 15 s bound
      answers `ok:false` ending in `interrupted`. App Scripts: the three examples print their lines.
      Firmware from before batch 15 FAILs it (the run ends at the 15 s bound).
- [ ] App, Scripts: run `for (var i = 0; i < 300; i++) { delay(100); }`, tap Stop after 2 s:
      the button reads Stopping..., then the output `Script stopped.` within about a second and
      Run returns; Run `console.log("hi")` right after answers `hi`. Stop in the command log dialog
      during a run ends it the same way. Unplug during a run: the output says it was lost, no crash.

### Hardware run 2026-11-05 - batch 15 merge, first soak

T-Embed CC1101 flashed from the Mac with the merged batch 15 firmware (dirty build on 6a0f9931).
Full suite hw-acceptance-20261105T214722891Z: 12/12 PASS (ping median 4.0 ms p99 4.25 ms, upload
with a 3072-byte chunk, streams clean, heap stable). Soak hw-acceptance-20261105T214746268Z
(--soak 20, streams wifi.probe/wifi.sta/subghz.raw): PASS, 40 intervals, heap.free slope 0.0 B/h
(detection limit 13,285 B/h for 20 min; free about 101.3 KB throughout), link counters exact
(rx +1882 = sent, tx +2162 = received), no rejects, no noise, PING p50 3.05 ms p99 4.64 ms, no
reboot (uptime 189 -> 1389 s). Leaks below about 13 KB/h are not ruled out; a 4-hour soak would
bring the limit to 2 KB/h. script_run and the CLI fail-fast items need the app.

### Soak runs that surfaced it (2026-11-06)

Three soaks, all with wifi.probe/wifi.sta/subghz.raw subscribed. In all three those radio streams
recorded ZERO events (no APs/stations/RF in range), so none of the runs exercised the link under
actual radio-EVENT traffic - the condition that would most stress frame emission. Read the results
with that limit in mind.

- 6h45 on batch 16 firmware 7abe1143 (23:30-06:15 UTC, 811 intervals): FAIL, 9 findings in two
  clusters (~04:01, ~04:05); otherwise clean. No reboot, heap 102,899->101,195 B (no leak),
  p99 max 62 ms. Report soak-20261105T2330Z-7abe1143/.
- 30 min on batch 18 release firmware f50c03ac, idle host (06:22-06:52 UTC): FAIL, 10 findings -
  about 2 undecodable frames despite ~13x less host traffic, which argues against host load as the
  trigger and points at the writeFrame emission path below.
  Report soak-20261106T0622Z-f50c03ac/.
- Re-soak after the fix (commit 010c0adc), 40 min / 80 intervals on the T-Embed,
  2026-11-06 07:46-08:26 UTC: PASS, 0 findings - link counters exact (rx +3762, tx +4322), no noise,
  no undecodable frames, no rejects, no reboot, heap flat (23 B/h), p99 4.19 ms. Report
  soak-20261106T0746Z-010c0adc-fix/. This soak is PASS but, like the other two, saw zero radio-stream
  events, so it does NOT prove stability under radio traffic; a re-soak under actual radio EVENT load
  is still owed (checkbox below).

### Hardware soak 2026-11-06 - FINDING (companion frame integrity, backlog F23)

A companion soak with wifi.probe/wifi.sta/subghz.raw subscribed surfaced a framing-integrity defect
(no crash, no leak; the app resynced): about 2 undecodable frames per run, stray bytes between
frames, a link counter mismatch, one lost ping, and - the tell - a battery-PONG fragment embedded
inside another frame. Two changes to `writeFrame()`/`writeAll()` (`src/companion/protocol.cpp`)
address it; they are on this branch and the firmware builds (`lilygo-t-embed-cc1101` SUCCESS):

1. PARTIAL WRITES - the leading hypothesis, and a real defect regardless of whether it caused every
   observed episode. `writeFrame` ignored the return of both `Serial.write` calls, and
   `Serial.write(buf,len)` is NOT guaranteed to accept all `len` bytes. The T-Embed CC1101 builds with
   `ARDUINO_USB_MODE=1`, so `Serial` is the ESP32-S3 USB-Serial-JTAG path (HWCDC). Under sustained host
   backpressure `HWCDC::write` returns a SHORT count (its own comment: "the caller sees a short write
   (Arduino's Stream::write contract)"), and returns 0 if the tx_lock is not taken within
   `tx_timeout_ms` (100). The TinyUSB `USBCDC::write` likewise returns `so_far` on disconnect/timeout.
   (The classic-ESP32 UART path is safe: `uart_write_bytes` blocks until all bytes are buffered.) A
   short write truncates the length prefix or payload -> stray bytes, an undecodable frame and a tx/rx
   counter mismatch, matching the symptoms. This is consistent with the observations but is NOT
   proven as the definitive cause without further on-hardware evidence (see the owed re-soak and the
   new `tx_torn` counter, which will name it if it recurs).
   FIX (completing the earlier stop-gap, not just a bigger timeout): `writeAll` loops on the return
   writing only the remainder and distinguishes a host that is *draining* from one that is *gone* -
   the idle timer resets on every accepted byte, so a delayed/partial write that resumes completes,
   while a host that accepts nothing is bounded by `COMPANION_TX_IDLE_MS` (1500 ms) and a trickling
   host by an absolute per-frame ceiling `COMPANION_TX_FRAME_MS` (4000 ms). A frame that still cannot
   be placed is torn: the length-prefixed stream cannot be resynced in place (no sync marker), so
   `writeFrame` sets `g_txLinkBroken`, stops emitting (no more bytes onto the corrupt stream), and the
   companion loop drops the link so the app reconnects onto a fresh, frame-aligned session. The torn
   frame is counted as `tx_torn` (new diag counter), not `tx_frames`; the count is visible on the next
   session's `diag` (the counters persist across sessions).
   TIME BOUND INCLUDING THE DRIVER BLOCK: this is subtle. A single `HWCDC::write(buf,len)` splits the
   buffer into 256-byte ring chunks and waits up to `tx_timeout_ms` per chunk, giving up after 20
   CONSECUTIVE timeouts - but that counter RESETS whenever a chunk is accepted (HWCDC.cpp:606). So a
   host that drains just enough to accept one chunk now and then keeps a SINGLE large write() alive far
   past `20 * tx_timeout_ms` (measured ~6 s for a full frame, counted as a success). Shrinking
   `tx_timeout_ms` alone does NOT fix this. The real bound needs two things: (a) `companionApplyTxTimeout(true)`
   shrinks the per-attempt `tx_timeout_ms` to `COMPANION_TX_WRITE_MS` (10 ms, restored on exit; no-op on
   the classic UART that never returns short); (b) `writeAll` caps each `Serial.write` to
   `COMPANION_TX_CHUNK` (64 bytes <= one ring chunk), so a single call covers ONE chunk whose
   consec-timeout counter cannot be reset mid-call -> one call blocks at most `20 * COMPANION_TX_WRITE_MS`,
   and `writeAll` re-checks the deadline BEFORE the next call. Net worst-case loop stall for a torn
   frame is then about `COMPANION_TX_FRAME_MS + 20 * COMPANION_TX_WRITE_MS` (~4.2 s) - far under the 60 s
   idle-exit, task blocked (not spinning) so the watchdog is fed. `tools/test_firmware_frame_lock.py`
   runs a faithful HWCDC model (chunked, consec-reset) and proves: with the cap each single write is
   bounded and the frame tears at the ceiling; removing the cap lets ONE write exceed the whole ceiling.
2. CROSS-TASK INTERLEAVE - a SAFEGUARD, not a demonstrated cause. In this tree every frame emitter
   runs on the companion loop task (the RMT ISR and the esp_wifi promiscuous callbacks only
   queue/ring-buffer; the `*Service()` emitters run from the loop), so no interleaving could actually
   occur here and this was not shown to produce the observed episodes. But the framing relied on that
   single-task invariant with nothing enforcing it. FIX: a recursive FreeRTOS mutex `g_frameLock`
   (static init, null-safe fallback for very early boot) wraps the whole emission - the two writes and
   the flush, nothing else. Recursive so a nested emission on the same task cannot self-deadlock. No
   emitter runs in an ISR, so blocking on the mutex is safe. It protects future multi-task emitters;
   it is not advanced as the root cause of the soak findings.

The wire format is unchanged. Evidence, host-side only: `tools/test_firmware_frame_lock.py` compiles
the real `writeFrame`/`writeAll` and proves, with a mutation for each fix: two threads give 0
interleaved frames with the lock and interleave once it is stripped; a sink that accepts only a few
bytes per write yields the complete framed stream with `writeAll` but a truncated one once `writeAll`
assumes the full count; a host that stalls under the idle bound and resumes still completes (and tears
once the idle-timer reset is removed); and a host that accepts nothing tears in bounded time, is
counted as `tx_torn` not `tx_frames`, suppresses all further emission, and recovers on a fresh session.
The sinks ADVANCE VIRTUAL TIME per write to model the driver's own block, so the asserted wait includes
it: a trickling host is torn at the ceiling with `waited <= COMPANION_TX_FRAME_MS + one driver window`,
and removing the pre-write ceiling check breaks that bound. The ACTUAL app recovery for THIS fault - the device leaves companion but USB stays OPEN - is proven in
`CommandSessionTest.truncatedFrameThenCliEchoFailsPendingFastThenSameSessionRecovers`: with
`transport.isOpen` kept TRUE (no artificial `close()`), the device sends a TRUNCATED frame (valid
header, incomplete payload) and then its CLI echoes our frames as "COMMAND:". The decoder detects the
CLI banner even though the parked frame would otherwise swallow the echo as payload
(`CompanionClient`: a rolling match over non-frame bytes - noise, a parse-failed frame, and a
stall-discarded partial frame - never over a valid payload, so "COMMAND:" inside valid JSON is not a
false alarm; a parked frame is abandoned only after ~2 s of INACTIVITY - no new bytes, mirroring the
firmware's `rxLastByte` rule and `COMPANION_MAX_FRAME`=8192 - NOT after a total duration, so a large
valid frame that keeps advancing still decodes even if it takes longer than 2 s). Within a bounded number of
read cycles the read loop calls `CommandSession.failPending(...)`, so a command that would otherwise
wait out its up-to-650 s timeout fails AT ONCE, the session stays usable, and the SAME session
re-enters companion and serves a new command. `CompanionClientCliTest` covers the decoder directly:
truncated-frame-then-echo, a never-completing truncated frame recovered via the inactivity stall within
a defined time, a banner split across reads, a banner after leading noise, NO false alarm for
"COMMAND:" inside a valid JSON payload, and a valid JSON payload containing "COMMAND:" delivered in
fragments spanning MORE than 2 s but always advancing - decoded, never flagged (inactivity, not total
duration). The unplug path (transport actually closed) is covered separately by
`unpluggedTransportFailsPendingCommandThenAFreshSessionRecovers`. `tools/test_firmware_diag.py` covers the new `tx_torn`
counter; `proto/PROTOCOL.md` documents it. A clean hardware re-soak UNDER RADIO TRAFFIC is still the
gate that would actually validate this end to end.

- [x] T-Embed CC1101 flashed with THIS branch and soak-verified clean (2026-11-06). The booted
      firmware's live `diag` carries the new `tx_torn` key (= the fix is running), PING/CAPS ok. Two
      soaks, both PASS, 0 findings, `tx_torn` 0, link counters exact, no noise, no reboot, heap flat:
      a 15-min run before the review's two corrections
      (report `hw-acceptance-reports/soak-20261106T1323Z-354a32db-prefix-run/`), then a 10-min run after
      re-flashing with the chunk-cap fix (20 intervals, PING p99 4.09 ms,
      report `hw-acceptance-reports/soak-20261106T1422Z-354a32db-f23fix/`). So the fix runs clean on
      hardware with no framing corruption and no regression.
- [ ] STILL OPEN - re-soak UNDER real radio-EVENT traffic. The run above saw ZERO events on all three
      streams (RF-quiet environment; only wifi.probe/wifi.sta/subghz.raw are allowed as receive-only,
      and we do not transmit), so it did not stress frame emission under stream-EVENT load. Repeat where
      APs/stations/RF are active (non-zero `stream_events`) and confirm 0 findings, `tx_torn` 0, no
      undecodable frames, counters exact. This is the gate that would actually validate F23 end to end.

### Batch 15 (SOAK) - passive soak mode for hw_acceptance.py, software only

`tools/hw_acceptance.py --soak <minutes>` (backlog S08, physical part): intervals of PINGs,
`storage list /`, CRC-checked `fs_read`, the scratch upload/read-back/remove cycle, one
receive-only stream ON/OFF in turn and keepalive PINGs, each ending with `diag`; heap trend,
per-interval link counters, latency percentiles, reset/uptime and the timing of every operation
go into the JSON/Markdown report and a CSV time series. Rules and limits:
docs/HARDWARE_VERIFICATION.md "Soak mode". Evidence, host-side only: `test_hw_acceptance.py`
99/99 (58 before) against `mock_device.py` (new `reboot` fault and companion `idle_exit`): heap-trend unit
cases (noisy flat, 8 KB/h leak, 1 KB/h, 20-minute window, outlier, too few samples), soak
arguments and dry run, a healthy soak kept alive against a 1 s idle exit, a too-slow keepalive
(FAIL), heap leak (FAIL, slope per hour), reboot (FAIL, soak stops), device noise (FAIL, counters
still exact), Ctrl-C during a stream and after an upload commit (OFF sent, scratch removed,
reports written, exit 130). Mutations of the soak code (no keepalive, no reboot detection, no
noise finding, no summary on Ctrl-C, no leak verdict) each made the test fail. `unittest discover -s tools` 175 tests OK; 32/33 `tools/test_*.py` pass, `test_audio_noise_bounds.py` needs `.pio/libdeps` installed (absent in this worktree, unrelated).
Nothing hardware-verified.

- [ ] T-Embed CC1101 in companion mode: `python3 tools/hw_acceptance.py --port <port> --soak 20
      --streams wifi.probe,wifi.sta,subghz.raw`; attach the JSON/Markdown/CSV. Expected: soak
      PASS, 0 findings, heap slope and detection limit reported, `diag_counters` PASS, no
      `/brucia-acceptance-*` file left (`storage list /`).
- [ ] Then a multi-hour soak (e.g. `--soak 240`) for a detection limit near 2 KB/h.

### Batch 15 (FWMENUS) - CLI commands do not wait for a person, script_run starts and stops promptly, ESP32 ROM printf muted; software only

In companion mode, Bruce CLI commands the app routes (and a script's `serial.cmd()`) that would
wait at an on-device menu/keyboard answer `ok:false` with the reason (`ir rx` without a usable IR
RX pin, `arp`, `listen`, `webui -noAp` without Wi-Fi, `decrypt` with an empty password, `js <file>`);
a BadUSB `WAIT_FOR_BUTTON_PRESS` ends the payload; `settings` and the pin setters no longer write
JSON onto the link; the ARP scan releases the lwIP lock on its early returns. `script_run` starts
the script at once (it used to answer `script timeout` after 20 s for every script and then run it
unbounded) and a stop / the 15 s bound ends native waits; ESP32 ROM printf is muted through the
ROM putc hooks (see proto/PROTOCOL.md). Evidence, host-side only: new
`test_firmware_script_abort.py` (core compiled with UBSan on a fake clock: sliced sleep, stop keys,
script_run watch, wrap-around, an end-to-end model of `delay(60000)`; plus wiring checks),
`test_companion_prompts.py` +3 (CMD path through module code with a detector self-test; IR, ARP
lock, js, ducky, settings specifics), `test_companion_print_hygiene.py` +2 (JSON to Serial; ROM
hook addresses against esp32_rev0/rev300 ROM ELFs); mutations caught (guards removed from `arp`,
`IrRead::setup`, `decrypt`; abort check removed from the sliced sleep; bound not requesting the
abort; the pins JSON unguarded). `unittest discover` 185 tests and all 34 `tools/test_*.py` pass
(`test_audio_noise_bounds.py` per env); `node tools/test_site_release.cjs` passes. Firmware builds
SUCCESS, sizes against the base commit 6a0f9931 built the same way: T-Embed CC1101 app 4,318,688 B /
92.81% (+2,768 B), static RAM 156,040 B (+16 B); core4mb (LITE) app 2,662,544 B / 67.71% (+816 B),
RAM 99,624 B (+8 B); AAC noise bounds pass on both. Nothing hardware-verified.

`companion_test.py` below means `python3 tools/companion_test.py <port>` (pyserial) with the device
in companion mode and the app disconnected.
Note (batch 16): BruceJS has no global `print()` (a ReferenceError); use `console.log()` in the
scripts below. With batch 16 firmware a script stopped by the 15 s bound replies `ok:false` (output
ending in `interrupted`) instead of `ok:true`.

- [ ] T-Embed, app connected, Scripts tool: `console.log("hi")` replies `hi` within about a second (it
      used to take 20 s and say `script timeout`); a second run right after works.
- [ ] `--cmd 'script_run delay(60000); console.log("never")' --wait 25`: `ok:true` with an `interrupted`
      error (no `never`) after 15-16 s; then `--cmd 'script_run console.log(1)'` answers `1` at once.
- [ ] `--cmd 'script_run setInterval(function(){console.log(1)}, 1000)' --wait 25`: ends at about 15 s
      with the printed lines; the next `script_run` is not `interpreter busy`.
- [ ] `--cmd 'script_run delay(60000)' --wait 25 --then script_stop --after 2 --ping-after 1`:
      PONG at about 1 s, `ok:false` `script stopped` at about 2 s, no reply to `script_stop` itself;
      with `--then 'uptime'` instead: `ERR 409 device busy` for it, the script runs to the 15 s bound.
- [ ] Same with `subghz.read(60)` (CC1101) and `ir.read(60)`: both end at the 15 s bound (ESC seen by
      the interpreter task only: the device stays in companion mode, no other stream stops).
- [ ] Leave companion mode after these runs: the device menu Scripts still runs a script (a stale
      task handle used to be able to stall it).
- [ ] Console (app or `--cmd`): `settings irRx 99` (or any pin not in the board's IR RX list), then
      `ir rx`: `ok:false`, `IR RX pin not configured (settings irRx <pin>)` at once, no pin menu on
      the device; IR tool Learn (`ir_rx`): `IR RX pin not configured`. Restore the pin afterwards.
- [ ] Console: `arp`, `listen` (with Wi-Fi), `webui -noAp` (no Wi-Fi), `decrypt /x.enc ""`,
      `js /x.js` each answer `ok:false` with their reason at once; nothing opens on the device.
      `webui` (AP) and `sleep` still behave as before.
- [ ] Console: `settings` returns the JSON in the output and Diagnostics shows no new noise bytes;
      `settings rfFreq 433.92` adds no noise bytes either.
- [ ] Outside companion mode (serial terminal): `ir rx` with an unconfigured pin still opens the pin
      menu; `arp` with Wi-Fi still scans and shows the host menu; `settings` prints the JSON;
      `js /x.js` runs the script.
- [ ] core4mb (ESP32): a companion session with Wi-Fi scan, BLE scan and a failing command (e.g.
      `subghz rx` without a CC1101) shows no noise bytes from ROM printf; after `companion_exit` a
      serial monitor shows `log_e` / ROM output again.

### Hardware run 2026-11-05 - batch 14 merge

T-Embed CC1101 flashed from the Mac with the merged batch 14 firmware (dirty build on db5d8d34,
pinned libraries), report hw-acceptance-20261105T201650625Z: 12/12 PASS; CAPS carries scrOn
true, ledOn true, scrBright 100 (rx_max 8192); ping 200/200 median 3.01 ms p99 4.05 ms; upload
with a 3072-byte chunk; streams clean; heap stable 102,639 B; diag counters match. Screen/LED
off-and-reconnect and script prompt items below still need the app.

### Batch 14 (FWSTATE) - device reports screen/LED state, scripts do not wait for on-device prompts; software only

CAPS adds `scrOn`, `ledOn` and `scrBright`; the app's Device switches and screen brightness slider
start from them (older firmware: keys absent, app unchanged). In companion mode the prompt helpers a
script reaches (keyboard, dialogs, menus, pickers, viewers, Wi-Fi menu, endless reads/recordings/GIF)
return their cancelled value at once with a note in the script output; `loader open` is refused in
companion mode and `webui`/`sniffer`/`listen`/`sleep` inside a companion script (see
proto/PROTOCOL.md). Evidence, host-side only: `test_protocol_e2e.py` 7 new checks against
`mock_device.py` (state after `scr 0`/`led 0` seen by a reconnect, `scr_bright`, 15 s restore,
`--legacy-outputs`), `hw_acceptance` CAPS validator for the new keys, new
`test_companion_prompts.py` (10 tests, reasoned allowlist); `unittest discover` 159 tests and all
33 `tools/test_*.py` pass. Android `:app:testDebugUnitTest` 873/873 (`DeviceOutputsTest` +3: new
firmware CAPS, old firmware CAPS, report vs a request on the wire), `lintDebug` 0 errors,
`assembleDebug` OK. Firmware builds SUCCESS: T-Embed CC1101 static RAM 156,024 B, app 4,315,920 B /
92.75% (+2,176 B over batch 13); core4mb (LITE) 99,616 B, 2,661,120 B / 67.68%; AAC noise bounds
pass on both. Nothing hardware-verified.

- [ ] T-Embed, app connected: Device screen, turn the screen and the LED off; without tapping
      Disconnect, force-stop the app and reconnect within 15 s - both switches show off and the
      device is still dark; turn them on from the app. Reconnect after more than
      15 s of silence: both show on and the device is lit.
- [ ] Same: screen off, Console `scr_bright 40`: the screen lights; reconnect - screen switch on,
      brightness slider at 40.
- [ ] Older firmware (any build before batch 14) with this app: switches behave as before (show
      what the app set, on after a disconnect).
- [ ] T-Embed, app connected, Scripts tool: `print("a"); var s = keyboard("x"); print("got[" + s + "]");`
      replies at once with `a`, `keyboard(): skipped, it waits for a person at the device
      (companion mode)` and `got[]`; no keyboard appears on the device and the next script_run is
      not `interpreter busy`. Same for `dialog.choice(["a","b"])`, `menu.show("t", ["a"])` (-1),
      `dialog.viewText("hi")` (text drawn, returns at once).
- [ ] Same session: a script with `serial.cmd("sleep")` returns `false` at once and the device stays
      awake; the Device screen's Sleep button still puts the device to sleep (key press wakes it).
- [ ] Outside companion mode (device menu Scripts, or a serial terminal `js run_from_file`), a script
      calling `keyboard()` still opens the on-device keyboard and returns the typed text.

### Batch 14 (APPPOLISH) - MITM list keys and ARP fields, serialization 1.12.0, software and emulator only

MITM request rows are keyed by their log id; ids are assigned by the log when an entry is
stored and one proxied request logs one entry at most. The ARP spoof card fields live in
the MITM screen above the list/detail switch. kotlinx-serialization-core 1.12.0 in every
build type. Evidence: `MitmLogTest` 6, `ArpSpoofFormTest` 3 (JVM Compose runtime with a
saveable-state registry, incl. a control with the old shape), `MitmRowKeysTest` 2 on the
API 36.1 emulator (real LazyColumn, incl. an unkeyed control that shifts);
`:app:testDebugUnitTest` 879/879 (128 suites), Kotlin warnings 0, `lintDebug` 0 errors /
2 warnings (OldTargetApi, Gradle 9.8.2), `verifyPaparazziDebug` pass, `assembleDebug` and
`assembleRelease` pass, `tools/test_android_logic.py` 199, `connectedDebugAndroidTest`
14/14 twice with 1.12.0 (BruciaMigrationTest 5/5). No traffic was sent through the proxy.

Phone (MITM proxy running, a second device using it as HTTP proxy):
- [ ] Scroll the request list down to a request, keep browsing on the second device: the
      request you were looking at stays at the top of the screen while new ones arrive
      above it; open a request, let more traffic arrive, Back: the same request is shown.
- [ ] Type an SSID, password, target and gateway in the ARP spoof card, open a request and
      go Back: all four fields keep their text. Rotate: SSID, target and gateway stay, the
      password is empty.
- [ ] Clear the log, browse again: the list fills without a crash.

### Batch 14 (PINS) - exact library pins, software only

All lib_deps entries of the 70 envs are exact (`tools/test_dependency_pins.py`, 10 tests: every
env resolved through extends/`${...}`, transitive pins, workflow cache keys); the 608 B core4mb
gap was M5Unified 0.2.28/M5GFX 0.2.35 in an older libdeps vs 0.2.29/0.2.36 fresh.
`test_build_firmware.py` 23 tests (9 new: pin-aware quarantine, range-only warning). `unittest
discover` 165 tests, all 32 `tools/test_*.py`, website 15/15 pass. Firmware builds with the same
uncommitted-tree state (dirty flag set in both), sha256 of `firmware.bin` before -> after pinning,
each from empty libdeps: core4mb 2,661,552 B 2da92de1... -> 2da92de1...; T-Embed CC1101
4,313,744 B 2f266684... -> 2f266684...; Cardputer 4,223,152 B 87ecc2ad... -> 87ecc2ad... (an
attempt that also pinned Adafruit SH110X/GFX in lib_deps gave 4,223,184 B and was dropped). AAC
noise bounds pass on all three. core4mb from an APFS clone of the main tree's libdeps:
`pio pkg install` removed the specs that left lib_deps and installed M5Unified 0.2.29 + M5GFX
0.2.36, firmware 2da92de1... (identical). Same libdeps with M5GFX swapped back to 0.2.35 (pins
unchanged): `pio pkg install` installed 0.2.36 and moved 0.2.35 to `M5GFX@0.2.35`, the wrapper
quarantined it, firmware 2da92de1... again. Nothing hardware-verified; no device behavior is
meant to change.

### Batch 13 (REPRO) - builds independent of leftover libraries, software only

`tools/build_firmware.py` quarantines packages in `.pio/libdeps/<env>` that `pio pkg list -e
<env> --only-libraries -v` does not list (declared lib_deps plus their declared dependencies) to
`.pio/libdeps-orphans/<env>/` before `pio run`. Evidence: `test_build_firmware.py` 14 tests (8 new,
fake libdeps tree with M5Unified/M5GFX next to declared and transitive packages); `unittest
discover` 148 tests and every `tools/test_*.py` pass. Cardputer built from an APFS clone of the main
tree's libdeps: M5GFX and M5Unified quarantined, app 4,223,152 B / 82.62% (was 4,320,368 B / 84.52%
with them), static RAM 155,588 B, sha256 5b6cada1... identical to a build from empty libdeps (nothing
quarantined there). Fresh builds, nothing quarantined: T-Embed CC1101 4,313,744 B / 92.71% (same as
batch 12), core4mb 2,661,552 B / 67.69%. AAC noise bounds pass on all three. A stale CYD libdeps
(installed before the git pins) was updated in place by `pio pkg install` and then had no orphans.
The upstream Bruce 1.16.1 Cardputer release contains no M5Unified strings (its core2 image does), so
the clean build matches upstream's configuration. Nothing hardware-verified; no device behavior is
meant to change for a build from clean libdeps.

- [ ] Cardputer flashed from a wrapper build: menu beeps and the speaker test play (NS4168 via
      ESP8266Audio), and on a Cardputer ADV the keyboard and I2C scan on Grove still work.

### Hardware run 2026-11-05 - batch 12 merge

T-Embed CC1101 flashed from the Mac with the merged batch 12 firmware (dirty build on 401a94f6,
libraries installed fresh), report hw-acceptance-20261105T172010651Z: 12/12 PASS with the
serial device claimed for the companion session (caps, provenance, evidence, diag, ping
200/200 median 3.01 ms p99 4.12 ms, malformed, fs list/read/upload 3072-byte chunk, streams
clean, heap stable 102,639 B, diag counters match). Script and console-input items below
still need the app.

### Batch 13 (STREAMS) - device streams and Device switches across rotation, software and emulator only

Every tool hook that stopped a device stream or radio loop in `onDispose` (23 in 9 files)
now uses `LeaveEffect`: skipped while the Activity is recreated, parked in `ParkedLeaves`,
claimed back by the recreated screen or run by `SettleParkedLeaves` after two frames when
the tool did not come back. Auto-starting tools start only what is not on; leaving sends an
OFF only for a stream that may run. Device screen/LED switches read `DeviceOutputs` in the
ViewModel. JVM evidence (fake transport, virtual clock, real `StreamSubscriptions` and
`ParkedLeaves`), `StreamRotationTest` 11, exact SUB frames:
- Sub-GHz Listen: start, rotate, rotate, Stop -> `subghz.raw on`, `subghz.raw off`; leaving
  afterwards sends nothing. Start, rotate, leave -> `on`, `off`. Start, rotate with the tool
  left meanwhile -> `off` sent when the new UI settles. Start, disconnect -> `on`, then the
  best-effort `off` on the closing link; rotating and leaving later send nothing.
- BLE scan: start, rotate x2, stop, leave -> `ble.scan on`, `ble.scan off`; start, rotate,
  leave or disconnect -> `on`, `off`.
- Audio mic (opened tool starts mic.pcm): open, rotate x2 -> one `mic.pcm on`; leave -> one
  `off`; open, rotate, disconnect, rotate, leave -> `on`, `off`.
- RF timeline: open, rotate x2 -> `wifi.ap on`, `ble.scan on` once; leave -> both `off`.
- Stop all after a rotation: one `off` per stream, nothing more on leave; a start whose ACK
  never came is still stopped on leave.
Also `StreamLeaveHooksTest` 3 (no `onDispose` in production code calls a stream stop; the
stream tools stop in `LeaveEffect`), `ParkedLeavesTest` 3, `DeviceOutputsTest` 3,
`StreamSubscriptionsTest` +2 (`disconnect` with a failing send, `mayRun`). Mutations:
Sub-GHz screen reverted to `DisposableEffect(Unit) { onDispose { ... } }` -> 2 of 3
`StreamLeaveHooksTest` fail; `ParkedLeaves.leave` ignoring the configuration change (the
old dispose behavior for every tool) -> 6 of 11 `StreamRotationTest` fail (for example
`[subghz.raw on, subghz.raw off]` after two rotations, mic `on, off, on, off, on`);
`settle` not running parked leaves -> the left-during-rotation tests fail. Totals:
`:app:testDebugUnitTest` 870/870 (126 suites), Kotlin warnings 0, `lintDebug` 0 errors /
3 warnings (OldTargetApi, Gradle 9.8.2, NewerVersionAvailable; none new),
`verifyPaparazziDebug` pass, `assembleDebug` pass, `tools/test_android_logic.py` 199,
`connectedDebugAndroidTest` 12/12 on the API 36.1 emulator. Emulator, no device (`wm
user-rotation`, MainActivity destroyed and recreated each time per the event log, same
process): Sub-GHz and BLE tools kept across portrait -> landscape -> portrait with Listen /
Scan off and disabled (offline, consistent); Back sent together with a rotation lands on
the dashboard; Device screen switches show on (offline default) across two rotations; no
crash or exception in logcat. Process death: the app cannot send anything; the firmware
leaves companion mode after 60 s without frames and stops all streams (companion.cpp),
not tested here. Not covered: anything with a connected device.

Phone + T-Embed CC1101 (connected over USB, companion mode):
- [ ] Sub-GHz: Listen on, note the Cmds log; rotate to landscape and back (two rotations):
      Listen stays on, signals keep arriving, the Cmds log shows exactly one `SUB
      subghz.raw on` and no `off`; tap Listen off: one `SUB subghz.raw off`, the device
      status screen shows the Sub-GHz stream stopped. Then leave the tool: no further SUB.
- [ ] BLE: Scan on, rotate once: Scan still on, the list keeps updating, one `SUB ble.scan
      on` in Cmds; leave the tool: exactly one `SUB ble.scan off`.
- [ ] Leave mid-rotation: Sub-GHz Listen on, then press Back and rotate the phone at the
      same moment (or rotate, then Back as soon as the dashboard redraws): the device stops
      Sub-GHz (one `SUB subghz.raw off` in Cmds, device status screen idle) within a
      second; reopening Sub-GHz shows Listen off.
- [ ] Audio dashboard open (mic level moving), rotate twice: the level keeps moving
      without a gap longer than a rotation, one `SUB mic.pcm on`; leave: one `off`.
- [ ] RF timeline open, rotate: Wi-Fi and BLE counts keep rising, no second `on`; leave:
      `wifi.ap off` and `ble.scan off` once each.
- [ ] Device screen: turn the device screen and LED off (device dark), rotate twice: both
      switches still show off and the device stays dark; turn the screen on: lights up.
      Turn both off, tap Disconnect, reconnect within 15 s: the device is lit again and
      the switches show on.
- [ ] Sub-GHz Listen on, tap Disconnect: the device status screen shows the stream stopped;
      reconnect with the tool still open and rotate: Listen shows off, no SUB sent. Listen
      on, then force-stop the app: the device leaves companion mode within about 60 s with
      everything stopped.

### Batch 12 (DEPS) - library updates, software only

BouncyCastle 1.86, ZXing core 3.5.4, kotlinx-serialization-core 1.8.1 in release too,
org.json 20260814 (test-only). JVM evidence: `QrCodesTest` 5 (generated QR decoded back
with ZXing: escaped Wi-Fi payload, Cyrillic/CJK as UTF-8, Latin-1 bytes unchanged),
`MitmTlsTest` 6 (in-memory TLS handshake against the MITM server context with hostname
verification: DNS, 98-character host and IP SANs; wrong CA and wrong host refused; CA
PKCS12 store round trip), `MitmCaTest` 7. `:app:testDebugUnitTest` 827/827 (117 suites),
`lintDebug` 0 errors / 3 warnings, `verifyPaparazziDebug` pass, `tools/test_android_logic.py`
182, `tools/test_*.py` 132 (2 skipped), `check_apk_identity.py` OK on debug and release.
No emulator run in this batch: BruciaMigrationTest last passed with 1.8.1 in the debug build.
Open phone checks:
- [ ] MITM: CA export/install on a test phone, then an HTTPS request to a normal host and to a
  host name longer than 64 characters is decrypted (BouncyCastle 1.86 certificates).
- [ ] Wi-Fi QR with a Cyrillic or Chinese SSID: a phone camera joins the network; a Latin-1
  SSID still works.
- [ ] Donation QR codes still scan.

### Batch 12 (UIFIX) - text contrast and state across rotation, software and emulator only

Contrast: text and icons with no color of their own, drawn straight on the app background
(Sub-GHz "Listen", BLE "Scan", Wardrive "Wardriving", Wi-Fi monitor target name, NFC
finding titles, Report preview lines) were black on #0E0E10 (1.09:1); the theme now gives
them onBackground #F3F1EF (17.12:1, WCAG AA needs 4.5:1). Rotation/resize: Network Scan
(running or finished) and PCAP dissect live in Activity-scoped ViewModels; the Files editor,
SSTV images, macro and tone composer steps, Wardrive found order and IR parsed buttons are
retained; 106 tool inputs plus the Files path are saved; Remote "running" comes from the
ViewModel. Leaving a tool still stops and clears it. Evidence: `:app:testDebugUnitTest`
834/834 (120 suites; new `NetworkScanSessionTest` 7, `PcapDissectSessionTest` 4,
`RetainedValuesTest` 4, `ThemeContrastTest` 4, `ContentColorScreenshotTest` 2), `lintDebug`
0 errors / 7 warnings (same set), `verifyPaparazziDebug` pass, `assembleDebug` pass,
`tools/test_android_logic.py` 182. Paparazzi: all 25 older snapshots re-recorded
byte-identical; the 2 new ones (no Surface, as AppRoot; default and magenta device accent)
reviewed, and they fail with the theme change reverted. API 36.1 emulator (phone, portrait
<-> landscape via user_rotation, Activity recreated): Network Scan started, rotated at 7/254
and back at 128/254, kept running to "Done: 2 hosts" (10.0.2.2, 10.0.2.3) with no restart;
rotated after the end, results kept; Back and reopen -> empty as before. Sub-GHz "Listen"
measured 17.12:1 on screenshots in system light and dark mode (the app has one dark
scheme; it ignores the system mode) and in landscape, 1.09:1 on the batch 11 tablet
screenshot. Macros: name, command and an added step kept across a rotation. Not covered:
a connected device (accent color, Remote, Files editor on device files, SSTV with audio),
PCAP open across a rotation (needs a file on the emulator), a real phone or tablet.

- [ ] Phone, dark room brightness: Sub-GHz "Listen", BLE "Scan" and Wardrive "Wardriving"
      labels readable; same with a device connected whose theme color is not orange.
- [ ] Phone: start Network Scan on Wi-Fi, rotate twice mid-scan: progress continues (does
      not restart at 0), one result list; rotate after "Done": hosts still listed; leave the
      tool and return: empty.
- [ ] Phone: open a .pcap in PCAP dissect, rotate while "Parsing..." and after: packet list
      kept.
- [ ] Phone with device: Files -> open a text file, edit, rotate: edits kept; Save to device
      writes the edited text. Remote: Start, rotate: Stop still enabled and mirror running.
- [ ] Phone: SSTV receive with a transmission in progress, rotate: images decoded so far
      kept (since batch 13 the mic stream is not restarted by the rotation).

### Batch 12 (FWJS) - companion serial input, LAUNCHER flash budget; software only

Nothing but the companion frame reader reads the USB serial port in companion mode: JS
`serial.readln` returns "" at once, Bruce's CLI console (`serialDevice`) has no input and no
output for the whole session, console-input CLI commands and `storage ymodem` answer at once, the
JS error screen does not wait for a key (see proto/PROTOCOL.md, "Console input and scripts in
companion mode"). Evidence, host-side only: new `test_companion_serial_input.py` (8 tests; every
direct `Serial` read in src/include/lib is the frame reader or companion-guarded), its comment
stripper also used by `test_companion_print_hygiene.py`; `unittest discover` 140 tests and all
32 `tools/test_*.py` pass. Firmware builds SUCCESS with the change: T-Embed CC1101 static RAM
156,024 B, app 4,313,744 B / 92.71%; Cardputer 158,232 B, 4,320,368 B / 84.52%; AAC noise
bounds pass on both. All 17 `LAUNCHER_*` envs compile; 4 elecrow envs are above the 98% budget
(table in the backlog). Nothing hardware-verified.

- [ ] T-Embed, app connected (companion mode), Scripts tool: run
      `print("start"); var l = serial.readln(3000); print("got[" + l + "]");` - the reply is
      `start` / `got[]` at once (not after 3 s), the link stays up (ping keeps answering, the next
      command works) and Diagnostics shows no new malformed frames or noise bytes.
- [ ] Same session: a script with `serial.cmd("storage write /x.txt 10")` returns within a second
      without breaking the link, `/x.txt` is not created; a script with a syntax error replies with
      the error text and a second `script_run` right after is not `interpreter busy`.
- [ ] Same session, app console: `storage ymodem /y.bin` answers at once with "not available in
      companion mode" and an existing `/y.bin` is unchanged.
- [ ] Leave companion mode, open a serial terminal: `js run_from_buffer 20` then paste a script and
      `EOF` still runs it, and a script calling `serial.readln()` reads a typed line as before.

### Batch 11 (UI-ADAPT) - wide-window layout and local network gate, software and emulator only

Wide windows: home grid columns follow the grid width (tiles >= 160dp, 2-6 columns),
dashboard centered at <= 1040dp, tool screens and category lists centered at <= 840dp.
Local network: `ACCESS_LOCAL_NETWORK` is not declared or requested while targetSdk is 36
(developer.android.com/privacy-and-security/local-network-permission, split permission
model); the gate asks only when device and target are both >= 37, and a UDP probe that
fails with EPERM shows "Local network blocked by Android" for Network Scan / MITM proxy.
Evidence: `:app:testDebugUnitTest` 811/811 on the batch alone, 813/813 (115 suites) merged
with batch UPLOADTEST (new `AdaptiveLayoutTest` 4,
`LocalNetworkAccessTest` 9 incl. a manifest/targetSdk tripwire, `AdaptiveLayoutScreenshotTest`
8), `lintDebug` 0 errors / 7 warnings (same set as batch 10), no Kotlin warnings,
`verifyPaparazziDebug` pass, `assembleDebug`/`assembleRelease` pass,
`connectedDebugAndroidTest` 12/12, `tools/test_android_logic.py` 182, `unittest discover` 132
(2 skipped). Paparazzi: real `HomeDashboard` and Network Scan phone snapshots recorded on
the unmodified layout, then re-rendered after the change byte-identical (all 17 older
snapshots too); new tablet snapshots (1920x1200 at 240 dpi, landscape 5 / portrait 4
columns) and the NEEDS_PERMISSION / BLOCKED states reviewed before keeping. API 36.1
emulator: phone home and Network Scan unchanged; `am compat enable RESTRICT_LOCAL_NETWORK
app.brucia` + reboot -> Scan shows the blocked card (logcat `sendto failed: EPERM`), flag
reset + reboot -> scan runs and finds 10.0.2.2 / 10.0.2.3; `wm size 1920x1200` + density
240: home 5 columns, Sub-GHz / Network Scan / Intercept list centered at 840dp; portrait
1200x1920: home 4 columns, tools full width (800dp < 840dp). Display and flag restored.
Not covered: the Android 17 permission prompt and denial path (no API 37 image; the app
targets 36 so it never asks), MITM proxy gate on the emulator, a real tablet or foldable.

- [ ] Phone (portrait): home grid still 2 columns, tools full width, Network Scan scans as
      before (no new prompt, no blocked card on a normal Wi-Fi).
- [ ] Phone in landscape: home grid about 4 columns, tool screens nearly full width (cap
      840dp), nothing clipped.
- [ ] Tablet or foldable (if available), both orientations and after unfold/resize: home grid
      gains columns, tools centered, category lists centered; nothing clipped.
- [ ] Android 16 phone, developer check: `adb shell am compat enable RESTRICT_LOCAL_NETWORK
      app.brucia`, reboot, Network Scan and MITM "Start proxy" show "Local network blocked by
      Android"; `am compat reset RESTRICT_LOCAL_NETWORK app.brucia`, reboot, Scan works.
- [ ] Before raising targetSdk to 37 (Android 17 device): declare the permission, then
      first Scan shows the reason card and the Nearby devices prompt; deny twice -> "Open app
      settings"; allow in settings and return -> Scan enabled.

### Batch 10 (TARGET36) - targetSdk 36, software and emulator only

targetSdk 35 -> 36 after a review of the Android 16 behavior changes (developer.android.com
about/versions/16/behavior-changes-16 and behavior-changes-all). No `onBackPressed`,
`onKeyDown` or `KEYCODE_BACK` in the app; back goes through Compose `BackHandler`, Compose
dialogs (ComponentDialog) and Compose popups (registers an OnBackInvokedCallback on API 33+),
so predictive back works unchanged. No `screenOrientation`, `resizeableActivity`,
aspect-ratio or edge-to-edge opt-out attributes. New: System Back leaves the MITM request
detail to the list and goes one folder up in Files; the open tool and category survive
rotation / resize (`rememberSaveable`). Evidence: `:app:testDebugUnitTest` 790/790 (111
suites; one earlier run hit the `SessionFileLinkTest` upload timeout while the host was
swapping, passed on rerun), `lintDebug` 0 errors / 7 warnings (unchanged set), `verifyPaparazziDebug`
pass (snapshots unchanged), `assembleDebug`/`assembleRelease` pass, `check_apk_identity.py`
targetSdk 36 on both APKs, `connectedDebugAndroidTest` 12/12 on the API 36 emulator. Emulator
UI (gesture navigation): Help, command log and Files "New" dialogs close on Back; Files
editor -> Files -> Device category -> dashboard -> launcher, with the predictive
back-to-home animation shown mid-gesture; injected KEYCODE_BACK behaves the same; MITM
detail -> list -> dashboard; Sub-GHz kept across a phone rotation; 1920x1200 at 240 dpi
(sw800dp) in landscape and portrait: home, Sub-GHz and Files render without clipping
(stretched to full width, as before), Files kept across the landscape -> portrait resize.
Not covered: Files subfolder Back (needs a device listing), a DropdownMenu on Back (both
menus need a connected device), a real tablet/foldable, a physical Android 16 phone.

- [ ] targetSdk 36 on an Android 16 phone with gesture navigation: from the dashboard a slow
      back swipe shows the app shrinking (back-to-home preview) and releasing exits; from a
      tool, a category list, the Files editor, a dialog and an open dropdown (Updates > Board
      image, Theme effect) Back closes only that layer.
- [ ] Same with 3-button navigation (tap Back; long-press Back shows the preview on Android 16).
- [ ] Files: open a subfolder, Back goes up one folder at a time, then to the dashboard.
- [ ] Rotate the phone with a tool open (and with the device connected): same tool stays
      open, USB link and running streams continue.
- [ ] If a tablet, foldable or desktop window is available: dashboard and two tools in
      both orientations and after fold/unfold or window resize; nothing clipped.

### S07 (ROOMHIST) - Room v1/v2 migrations, software only

Room schemas 1 and 2 generated from their source commits (`85a74629`, `0c705937`; see
`android/app/schemas/README.md`); `3.json` and the v3 identity hash unchanged.
`BruciaMigrationTest` on the emulator (API 36, MigrationTestHelper): v1 -> v2 keeps 3
fixture rows (GPS fix, no fix, quote/SQL-like SSID) and seeds the accumulators; v2 -> v3
keeps `ap` and creates `saved`; v1 -> v3 validates and leaves the same stored table SQL
as a fresh v3 database; the app's builder opens v1 and v2 files with their rows. A
deliberately broken migration (no `saved`, lost latitude) failed all 5. JVM:
`BruciaMigrationsSeedTest` 3. Totals: `:app:testDebugUnitTest` 783/783 (110 suites),
`lintDebug` 0 errors (12 warnings, dependency/target-API only), `assembleDebug` pass,
`connectedDebugAndroidTest` 12/12, `tools/test_android_logic.py` 182. The debug build now
carries kotlinx-serialization-core 1.8.1 (needed by room-testing); release keeps 1.7.3.
Under heavy host load the 2 GB emulator's system server restarted (APK install failures,
one ConnectionService assertion); with `-memory 4096 -cores 4` all passed. No v1/v2
database from a real device was available.

### Batch 8 (HWLINK) - companion link fixes from the first hardware run, software only

Fixes for the two link defects of the first `hw_acceptance.py` run on the T-Embed (inbound
frame loss in the serial RX queue; framing lost on `cc1101 Connection OK`). Evidence, all
simulated or host-side: Android `:app:testDebugUnitTest` 780/780 (109 suites), `lintDebug`
0 errors (12 pre-existing warnings), `verifyPaparazziDebug` and `assembleDebug` pass;
`unittest discover` 130 tests, every `tools/test_*.py` passes (incl. new
`test_firmware_serial_rx.py`, `test_companion_print_hygiene.py`; `test_hw_acceptance.py`
56/56 with a mock RX queue that drops bytes and injected device noise); website 15/15.
Firmware builds (vs snapshot 2e2f68ed, same toolchain): T-Embed CC1101 SUCCESS, static RAM
155,984 B (+8), app 4,310,976 B / 92.65% of slot (+1,520); Cardputer 158,184 B (+8), 84.49%
(+1,552); core4mb (LITE) 99,576 B (-8), 67.64% (+320); LAUNCHER_CYD 94,472 B (+16), 97.40%
(+704). AAC noise bounds pass on the first three. The RX buffer is heap, not static RAM. The
host-runner acceptance below was then run on a T-Embed; the app items remain open.

- [x] Rerun `tools/hw_acceptance.py` on the T-Embed with this firmware: `caps` shows `rx_max`
      8192 / `rx_buf` 8448; `fs_upload` passes with its 3072-byte first chunk (~4.2 KB frame);
      `--streams subghz.raw` passes with no noise warning (CC1101 prints guarded); `diag` heap
      free about 7.4 KB lower than the previous firmware at the same point.
      2026-11-05, T-Embed CC1101 flashed from the Mac (esptool, factory image, dirty build of
      snapshot 2e2f68ed + batch 8 working tree), report hw-acceptance-20261105T103009045Z:
      12/12 PASS. caps rx_max 8192 / rx_buf 8448; fs_upload 2 chunks (largest 3072 raw bytes),
      commit, read-back and remove verified; streams wifi.probe/wifi.sta/subghz.raw ON/OFF ACKed
      and silent after OFF, 0 noise bytes, 0 resyncs; ping 200/200 median 3.02 ms p99 4.18 ms,
      burst 20/20 in order; heap free 115,599 B at run start vs 123,175 B on the previous
      firmware (-7,576 B), stable 102,695 B over 20 diag calls; diag counters rx +260 = sent,
      tx +267 = received, no rejects. Limits: the three streams saw 0 events in 5 s each, so
      receive itself is not demonstrated (no probe traffic or Sub-GHz source was arranged); the
      app was not involved (host runner only).
- [x] App: upload a file larger than 3 KB to the device; Diagnostics shows "0 noise bytes" after
      Sub-GHz Listen on/off; speaker PCM/SSTV transmit still plays. Owner phone test of release
      0.1.5, 2026-11-05 (see "Phone test" below).

### Phone test 2026-11-05 - release 0.1.5 (owner-reported)

Owner test on their Android phone with the T-Embed CC1101, release v0.1.5 (9f4eefa4), reported as
all passing; no logs or Diagnostics export were collected:
1. App updated to 0.1.5; "What's new" shows the release notes.
2. T-Embed flashed to 0.1.5 from the phone over USB (Device > Updates); boot screen 0.1.5.
3. File larger than 3 KB uploaded from the app in companion mode.
4. Sub-GHz Listen on/off; Diagnostics shows 0 noise bytes.
5. Speaker tone/DTMF and an SSTV transmit play normally.
6. Saved raw IR button replays.
7. Material3 progress bars/sliders, orange outlined buttons; USB unplug mid-session recovers.
Not covered: phone model/Android version not recorded, cancel mid-download, wake lock through a
flash, swipe-away, over-the-air RF/IR reception by another receiver.

### Hardware run 2026-11-05 - batch 9 merge (candidate 0.1.5)

T-Embed CC1101 flashed from the Mac with the merged batch 9 firmware (dirty build on 080f8176),
report hw-acceptance-20261105T121303430Z: 12/12 PASS (caps rx_max 8192, upload 2 chunks with a
3072-byte chunk, streams ON/OFF clean with 0 noise bytes, ping 200/200 median 3.03 ms p99
4.09 ms, heap stable 102,887 B over 20 diag calls, diag counters match). Covers the link only;
the print-hygiene items below that need a phone or peripherals remain open.

### Batch 9 (PRINTS) - companion print hygiene follow-up, software only

Text that could still reach the framed USB stream in companion mode: Bruce CLI commands routed
through `CMD` now print through `usbConsole()` (Serial outside companion mode; captured into the
ACK on the companion task, dropped elsewhere), remaining module/task prints on companion paths
are guarded, and companion mode mutes stdout/stderr and, on ESP32-S3, the ROM printf copy to
USB-Serial-JTAG (`g_usb_print`). Evidence, host-side only: `test_companion_print_hygiene.py`
(58 files, reasoned allowlist, 7 tests), `unittest discover` 132 tests, every `tools/test_*.py`
passes. Firmware builds SUCCESS (compared with the batch 8 figures above, not a rebuild of the
base): T-Embed CC1101 static RAM 156,016 B (+32), app 4,312,896 B / 92.69% (+1,920);
Cardputer 158,224 B (+40), 4,319,504 B / 84.50%; core4mb (LITE) 99,608 B (+32), 2,660,688 B /
67.66%; LAUNCHER_CYD 94,504 B (+32), 2,490,480 B / 97.44% (warn > 95%, fail > 98%). AAC noise
bounds pass on the first three. Nothing hardware-verified.

- [ ] T-Embed, app console in companion mode: `ir tx NEC 04000000 08000000`, `subghz rx 433920000`,
      `subghz scan 433000000 434000000`, `storage free`, `wifi on`: the reply shows the text Bruce
      prints over USB (e.g. `Sent NEC Command`, `timeout`, `SDCARD mounted successfully`) and
      Diagnostics stays at 0 noise bytes.
- [ ] Same board: theme Save (`config_save`), speaker tone/DTMF, mic record, BLE HID connect from
      a host, Evil Portal start/stop: 0 noise bytes; after leaving companion mode Bruce's serial
      CLI prints as before (e.g. `nav next` shows `Next Pressed`).

### S09 (SSTVREF) - SSTV timing reference, software only

`SstvTimingReferenceTest` (7 tests) holds sync, porch, separator, scan, line time and VIS
values for Robot 36/72, Martin M1/M2 and Scottie S1/S2 with a citation per value (JL Barber,
Dayton 2000; SSTV Handbook ch. 3-5; MMSSTV source for the Robot 72 porch conflict). It checks
the encoder/decoder tables exactly, decoder sample offsets within half a sample at 16 kHz, the
encoded frame length within one sample and long tones in the encoded audio within 50 Hz. Before
the fix the two realised-timing tests failed (Martin M1 frame 2.176 ms short; Martin M2 decoder
offset 1267 vs 1267.552 samples); after it Android `:app:testDebugUnitTest` 787/787 (110
suites), `lintDebug` 0 errors. Limits: Brucia's own encoder and FM discriminator only.

- [ ] Decode an SSTV recording made by another program (e.g. MMSSTV or QSSTV) from a known
      source image, in each of the six modes, and compare against that image.
- [ ] Over the air: send each mode from the app to another receiver, and receive another
      transmitter in the app; note slant and colour.

### Batches 1-4 - verify on hardware
- [ ] Run `python3 tools/hw_acceptance.py --port <port>` first (passive, allowlisted; see
      docs/HARDWARE_VERIFICATION.md) and attach its report; it covers the protocol side of
      the diag, evidence and file items below. UI, Stop all/unplug and RF items stay manual.
- [ ] targetSdk 35 build: edge-to-edge layout on an Android 15+ phone with a camera cutout and
      on an API 26-34 phone; keyboard over Console/Macros; signed upgrade from 0.1.4.
- [ ] `diag` card: Refresh shows reset reason (power-on, then after a reset/brownout if
      reproducible), heap, link counters rising with traffic; busy 409 during a long op.
- [ ] Wi-Fi handshake/PMKID capture with descriptor: imported file matches size/CRC; a second
      capture on the same AP reports `appended`; Esc abort and timeout import nothing.
- [ ] Mifare key check: UID/sectors/complete shown; Stop mid-batch returns "Stopped (partial)"
      within seconds; swapping the card between batches stops the run.
- [ ] Speaker file playback with the link alive; Stop ends it; end event arrives.
- [ ] Hardware evidence card on the T-Embed: CC1101/PN532/SD levels and pin conflicts plausible;
      LoRa/RC522 probes leave the peripherals idle.
- [ ] Sub-GHz Listen/Spectrum: init failure turns the switch off with a reason; sweep limits.
- [ ] NFC read, `rf rx`, IR learn, device panel, Files: Stop all and unplug end the wait.
- [ ] USB: permission prompt once, flash takes the port from the companion link, wake lock held
      through a flash, service gone after the app is swiped away.
- [ ] In-app update: APK + firmware download, cancel mid-download, flash after verification.
      Partly covered 2026-11-05 (0.1.5 phone test): APK update with release notes and firmware
      flash from the phone to the T-Embed passed; cancel mid-download not exercised.
- [ ] FM PCM (needs a Si4713 board): accepted/lost counts, no silent loss.

## Latest software checkpoint — 2026-11-04

Claude orchestration: six scoped packets in
`.codex_tmp/claude-stabilization-20261104/`; source and packet hashes rechecked before
launch. Historical coordinator JVM baselines passed 44 tests (S08a) and 27 (S09),
overlapping the main suite. Explicitly permitted execution verified host Claude
authentication and Docker 27.5.1. The user requested local Mac Claude Code instead
of Linux/Docker: four workers launched (S08a/S09/S11a/S06-S10a), review pending.
CLI permissions and packet scope do not provide OS filesystem/network isolation.
S02/S07 remain blocked; launch does not prove completion or interoperability.

W11l: `DictionaryTestService` is wired into the existing offline dictionary paths.
Seven synthetic tests cover missing targets, early-match counts, read failures,
cancel/restart with a blocked old reader, exhausted dictionaries, the existing
handshake verifier and an already-cancelled parent scope. No live network tests.

W11m: 11 synthetic `CaptureWorkflowTest` cases cover same-size file overwrites,
unchanged/foreign/ambiguous files, parsed AP identity, command and verified-read
failures, failed baseline listing, PMKID routing, stopped/superseded blocked reads,
and missing hardware/link prerequisites. Tests use fake IO and parser fixtures;
actual RF capture, USB timing and the final UI remain pending on-device acceptance.

W11n (Mifare stage): 11 `MifareKeyCheckServiceTest` tests cover 48-key batching,
normalization and dictionary snapshots, preserving prior hits on failure, malformed
replies, missing connection/module, Stop/reset/supersede with a delayed reply,
cancelled parent scope, empty success and IO errors. Real `CommandSession` over a
simulated transport verifies timeout/cancellation cleanup, late ACK suppression and
reconnect isolation. No NFC hardware was connected. Firmware ACKs do not expose tag
identity or partial scans; a completed app sequence is not proof of an exhaustive
scan of one card. Stop does not abort a batch already running on the device.

W11n (macro stage): 12 `MacroRunnerTest` cases cover ordered commands/pauses,
step snapshots, double-start prevention, failure/exception/timeout short-circuiting,
empty/blank steps, missing connection, Stop during a pause, Stop/restart with a
non-cancellable old reply, parent cancellation before/during execution and consistent
delay bounds for saved/direct macros. Real `CommandSession` with simulated transport
covers timeout/Stop cleanup, late ACKs and reconnect isolation. Firmware commands
and saved JSON are unchanged; hardware execution and final UI are unverified.

W11o: 10 `LifecycleRegressionTest` cases cover cancelled scopes before/during a
command, stale mapper side effects, mapper errors, a cancelled Ethernet config
that must not trigger refresh, queued and delayed callback cancellation, late BLE
refresh, portal template completion after disconnect, combined cleanup of real
services over a simulated `CommandSession`, and cleanup continuing after a failing
hook. Updated the HID Stop regression to require a terminal status. Existing
service suites still pass. Stream/UI/device stop behavior has not been physically
validated. File-transfer ownership was subsequently covered by W11p below.

W11p: 11 `FileTransferServiceTest` cases cover invocation-time session capture,
queued/active cancellation, reconnect, delayed non-cancellable reads, serialized
writes after supersession, discarded local-file cleanup, failed download before
opening a destination, unavailable verified files, cancelled parent scopes,
bidirectional caller/transfer cancellation and actual portal/service integration.
The original seven `SessionFileLinkTest` cases exercise real `CommandSession` and
`DeviceFileReader` with simulated transport: matching ID/size/CRC, chunked/empty
uploads, bad write ACKs, timeout/cancellation cleanup, queued wire operations,
late data and invalid/oversized/closed-link requests. Existing reader fixtures
continue to cover corrupt and missing chunks. All data is synthetic.

Android document-provider behavior and physical USB timing remain unverified. A
provider write that already truncated a document has no guaranteed rollback.
Firmware upload safety is now covered by S04 below; legacy direct truncate/append
still can leave partial files. Provider writes are not certified as atomic.

W11q: 12 `PcmStreamServiceTest` cases cover begin gating, exact frame boundaries
and monotonic pacing, failed begin/data/read/stop, Stop during non-cancellable
preparation/read, source cleanup, ordered old-stop/new-begin, reconnect, background
device-mic ownership, duplicate/invalid/unavailable starts, parent cancellation,
empty-read backoff and cooperative SSTV cancellation with unchanged normal samples.
Five `SessionPcmLinkTest` cases validate actual framed JSON through `CommandSession`:
speaker `d.sr`/`d.pcm`, exact S16LE bytes, FM text grammar, failed begin without PCM,
structured wait timeout/cancellation, stale/closed links and frame bounds.

Android MediaCodec/AudioRecord and physical FM/speaker playback are unverified.
A blocking source must return before its serialized cleanup/new run can proceed.
Stop invalidates app ownership immediately; it does not prove hardware silence.
The FM firmware ACK has no accepted-sample count and can hide ring-buffer loss;
protocol documentation records that remaining limitation. No firmware was changed.

S01: nine `ReleaseUpdateServiceTest` cases exercise `AppUpdater.fetchSnapshot`
with injected HTTP bodies: two successive releases, one request per refresh,
metadata/asset consistency, immediate invalidation while IO is pending, duplicate
refresh suppression, failed-refresh retry, obsolete/foreign/unsupported board
selection, missing assets without fallback, malformed/ambiguous metadata,
cancellation during a blocked fetch, already-cancelled scope and immutable parsed
board lists. Home and Updates now consume one state; download progress is separate.
APK/USB handoff guards were reviewed and compiled, but permission UI, live GitHub
requests, installer behavior and physical flashing were not exercised.

S03: the software matrix in `docs/APP_CONTROLS_CHECKLIST.md` maps all 50 tool tabs
plus Theme to prerequisites, ownership, deadlines, input checks, cancellation and
evidence/remaining integration. A source-to-table check found no missing, extra or
duplicate tab after reconciliation; hardware checkboxes remain unmarked.

Five `CommandCapabilitiesTest` cases cover all 13 hardware families (including
cross-family rejection), composite FM/mic requirements, cleanup exceptions and real
CommandSession text/JSON/structured rejection without writes or pending leaks.
Ten `StreamSubscriptionsTest` cases use real framed SUB/ACK on fake transport:
missing modules, unknown streams, monitor input rejection, immediate/missing ACK,
exclusive and independent streams, failed OFF retry, Stop/supersede, reconnect,
queued capability/session changes, scope cancellation, write failure and close.

Nine additional regressions cover BurstLoop immediate/cancelled-scope completion;
WIDS startup/cancellation and invalid windows; jam-detect/Sub-GHz duration overflow;
and LoRa receive supersession, cancellation and invalid duration. Existing burst
blank-failure and LoRa Stop tests now require termination and no late delivery.
Registry tests require offline Crack and mixed SSTV/Tone screens to remain reachable.
The older burst cancellation test now waits for runner entry, avoiding its former
race against an optimistic UI flag. Initial SUB tests returned non-Unit values and
were rejected by JUnit; that harness issue was fixed before the passing run.

UI wiring now restarts automatic audio subscriptions on connection/capability
changes, requires explicit speaker/mic support, clears DTMF when its SUB fails, and
prevents recording after a failed immediate start. Failed RF/NFC reads no longer
persist a synthetic success. These Android adapters were reviewed/compiled;
Compose lifecycle, Android codecs/providers and actual radio shutdown remain
unverified. LoRa Stop cancels the app wait; firmware has no receive abort.

S04: managed uploads now use a separate transactional capability and begin/chunk/
commit/abort contract. Twelve `SessionFileLinkTest` cases exercise real correlated
sessions with synthetic transport: empty/chunked transfers, strict token/size/CRC/
commit ACKs, lost commit replies without retries, old-firmware reads/no upload
fallback, cancellation/abort, reconnect, late replies and canonical path limits.
`CapsTest` independently rejects missing/malformed transactional support.

`python3 tools/test_firmware_upload.py` passed 41 failure/recovery scenarios with
the real C++ engine and UBSan: both rename models, old/new/empty destinations,
partial writes, bad/truncated/oversized staging, read/rename/rollback/cleanup errors,
reset boundaries, artifact collisions, invalid journals, conflicting recovery and
idle-clock wrap. `test_firmware_storage.py` passed against the real dispatcher and
FS adapter, including transaction ACKs, legacy-write exclusion and reserved-path
aliases. Codec/framing and all five mock protocol E2E checks also passed. The new
upload harness is included in CI. Mock protocol E2E itself still covers reads;
the new upload contract is covered by the JVM/native fixtures above.

FAT backup/promote recovery has a visibility gap. If promotion and rollback both
fail, the previous bytes remain in the backup; no success is claimed. Failed cleanup
after successful commit is explicitly reported. Physical media durability, power
interruption, card swaps and Android provider/USB behavior remain unverified (S05).
Generic CLI/scripts are outside upload isolation. No release or flash performed.

`python3 tools/check_android_changes.py --variant release --all-main --unit-tests`
passed compilation of 158 production Kotlin files and 563 tests in 88 non-Paparazzi
suites. The checker now accepts an explicit cached build variant; only release
outputs were available after 0.1.3. This count excludes screenshot tests and is not
directly comparable to the 455-test Gradle checkpoint below. A new Gradle unit/lint
attempt failed before execution because the global wrapper lock was not writable.
Current lint, fresh APK and device acceptance are not established by these cached-source
checks. `git diff --check` passed.

Final S04 firmware builds passed on all three targets:

| Target | RAM | Flash |
|---|---|---|
| LilyGO T-Embed CC1101 | 154,384 B (47.1%) | 4,304,398 B (25.7%) |
| M5Stack Core 4 MB (LITE, no RGB LED) | 98,152 B (30.0%) | 2,672,063 B (63.7%) |
| M5Stack Cardputer | 156,584 B (47.8%) | 4,309,563 B (51.4%) |

`python3 tools/test_android_logic.py --firmware Bruce-lilygo-t-embed-cc1101.bin`
also passed: the 4,370,480-byte merged T-Embed image passed the production Kotlin
preflight, and 121 standalone JVM tests passed (overlapping the full suite).

Firmware S04 build verification uses installed dependencies copied into the ignored
`.pio/local-core` directory, leaving global caches untouched. Reproduce after provisioning
that local cache with:

```sh
PLATFORMIO_CORE_DIR="$PWD/.pio/local-core" ~/.platformio/penv/bin/pio run \
  -e lilygo-t-embed-cc1101 -e m5stack-core4mb -e m5stack-cardputer
```

The normal global-cache attempt was denied `platforms.lock`. The local build still
warns that esptool dependency installation cannot reach PyPI, then uses the cached
installation successfully. This is a cached-toolchain build, not a clean online
provisioning check. Generated binaries retain VERSION 0.1.3 and are local development
artifacts, not a new release. Full Android Gradle/lint remains blocked at its global
wrapper lock; the cached Kotlin result above does not substitute for it.

## Current evidence — 2026-10-30, candidate 0.1.1

- 113 production Kotlin files compiled with cached dependencies/generated resources;
  253 unit tests passed. The standalone subset passed 120 tests (overlapping).
- 0.1.2 app (W06-W11): `./gradlew --offline :app:testDebugUnitTest`
  passed 455/455 including `FeatureRegistryTest`, `CommandSessionTest` (13, fake
  transport), `PcapDissectorTest` (7, synthetic pcaps), `IButtonServiceTest` (10),
  `DeviceOperationTest` (8), `ClockServiceTest` (2), `WifiMacServiceTest` (3),
  `ScriptServiceTest` (3), `DeviceAppearanceServiceTest` (4), `WifiJoinServiceTest` (3)
  `EspNowServiceTest` (2), `SpeakerServiceTest` (10) and `MicRecordServiceTest` (3); `lintDebug` passed with no
  findings in changed files. `CommandSession`
  is wired into the ViewModel and the registry into MainActivity. No device test of either.
- 0.1.2 firmware (W10): `tools/test_firmware_ibutton.py` passed 71 checks
  (UBSan only; ASan hangs in this macOS shell); codec, framing, storage, web-resource and
  build-tool tests passed; `lilygo-t-embed-cc1101` built successfully (RAM 47.1%, flash
  25.6%). Other boards were not rebuilt (deferred to release). Build with PlatformIO's own
  interpreter: `~/.platformio/penv/bin/python tools/build_firmware.py -e
  lilygo-t-embed-cc1101 --pio ~/.platformio/penv/bin/pio` (default python3 3.15 is rejected).
- 21 Python and seven website tests passed. Native storage/codec/framing, mock
  protocol E2E and AAC bounds with UBSan passed in the stabilization session.
- T-Embed 0.1.1 firmware compiled and passed production Kotlin image preflight.
  Local package `.codex_tmp/releases/0.1.1/`, 4,360,800 bytes; SHA-256
  `20fbda74c7a67b700108e8b1c1928af7f123911a60d99dabe349f6272dd8334e`.
- Earlier stabilization builds passed on Core 4 MB and Cardputer; this is not
  evidence for every configured board or for physical operation.
- Fresh Gradle/KSP/lint/signed APK and installation checks remain pending. Previous
  environment denied Git metadata/global Gradle writes and daemon sockets; GitHub
  was unreachable. Recheck those limitations in the current environment.
- Physical acceptance for the final pair, UI lifecycle, flash/recovery and sustained
  operation remain pending. Historical checks below do not certify current source.

See [the task ledger](skills/brucia-maintenance/references/backlog.md) for outstanding
work and [the interactive controls](docs/APP_CONTROLS_CHECKLIST.md) for the per-control
acceptance inventory. Latest known fixes are in CHANGELOG.md.

### Hardware-free logic checks

The normal Android check remains `cd android && ./gradlew --offline testDebugUnitTest lintDebug`.
For a restricted environment with cached dependencies:

```sh
python3 tools/test_android_logic.py
python3 tools/check_android_changes.py --variant release --all-main --unit-tests
node --test tools/test_site_release.cjs
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/test_firmware_storage.py
python3 tools/test_firmware_upload.py
python3 tools/test_firmware_codec.py
python3 tools/test_firmware_framing.py
python3 tools/test_audio_noise_bounds.py -e lilygo-t-embed-cc1101
```

The small runner currently exercises 120 real Kotlin/JUnit tests with only the
version constant generated for the host. Add `--firmware <merged-image.bin>` to
validate a real T-Embed image with the production preflight. The cached-app checker
compiles all 113 current production Kotlin files (Compose included) and runs 253
non-Paparazzi tests; it requires a previous debug build and cached SDK/dependencies.
Those counts describe the original debug-cache checkpoint; see the latest release-cache
results above. It does not regenerate Room/KSP output, run lint or create an APK. Counts overlap.

Native storage/framing checks exercise actual firmware code with host IO shims.
The Base64 test checks 134 vectors against Python and uses UBSan. The AAC test
requires installed firmware dependencies and checks real routines with synthetic
state, not a complete malformed-media corpus. The five checks in
`tools/test_protocol_e2e.py` use a mock device; they are not a physical integration test.

## Historical verification (retest on the current build)
- [x] Cross-board firmware builds: lilygo-t-embed-cc1101 (primary) AND m5stack-core4mb (LITE,
      no RGB LED) both compile. A LITE break - companion.cpp used BruceConfig::ledColor/ledBright/
      ledEffect, which only exist under HAS_RGB_LED - is fixed (those caps fields and led_set are now
      guarded). The manual full-matrix workflow covers configured boards; the LITE + non-LED path was the class that was failing.
- [x] Phone flashing over USB (esptool-stub path): 0.8.12 flashed on real hardware; boot shows the
      flashed version. Re-confirm the flash log reaches **verified OK (MD5 matches)**.
- [x] USB auto-connect / companion HELLO + CAPS; Device info shows board/chip/fw/battery.
- [x] RTTTL songs play correctly on the device speaker ("canzoni perfette").
- [x] LED animations, LED brightness and solid color apply LIVE (user-confirmed); solid color shows
      only when the animation is "Solid" (expected - an animation overrides a static color).
- [x] Wardrive + BLE scan stream (verified earlier on hardware).

## Core fixes to verify (implemented, need on-hardware confirmation)
- [ ] **Boot animation**: the flame only grows/shrinks (min = original size), no vertical bob, no
      white gap.
- [ ] **Theme applies live**: pick a theme color -> device UI AND app accent change immediately,
      WITHOUT pressing the device power button (which used to exit Brucia). "Save to device" persists.
- [ ] **LED sliders read the real device value**: the LED-brightness and screen-brightness sliders
      start at the device's actual value (e.g. 0%), not always 50%/100%.
- [ ] **Song STOP** actually stops a playing melody (async speaker_song + speaker_stop).
- [ ] **DTMF generator**: the DTMF keypad produces audible dual tones (speaker_dtmf). If silent,
      the AudioFileSourceFunction path may not drive this board - fall back to the RTTTL/async player.
- [ ] **DTMF decoder**: the Tone screen decodes DTMF from the mic (play tones into the device mic).
- [ ] **Updates card** shows App / Firmware / App+firmware correctly; the **Device footer button**
      shows the accent dot when an update exists; the **Device tab** has the Updates shortcut card.
- [ ] **ESP-NOW** tool broadcasts; **Change MAC** sets/randomizes the STA MAC; **Connect to Wi-Fi**
      joins an AP and reports the IP.
- [ ] **Brucegotchi** runs for the chosen duration and reports a handshake count.
- [ ] **Custom IR remote**: capture a button (ir_rx), it appears in the grid, tap replays it, the
      layout survives an app restart, long-press deletes.
- [ ] **Karma attack** (evil-twin / probe-response lure): `karma <secs>` runs headless, reports
      probes seen; standalone menu entry still works. Audit note: confirm the headless run ends on
      its own time budget (returnToMenu) without needing the device ESC, and that a victim submitting
      creds through the lure does NOT desync the USB link (the portal-save Serial prints are now
      guarded).
- [ ] **Responder** (LLMNR/NBT-NS): `responder <secs>` needs Wi-Fi joined first (Connect tool);
      reports poisoned-query count. The native interactive SMB/NTLM Responder still works too.
- [ ] **BLE Remote (HID)**: BLE keyboard/presenter pairs with a host; arrow/media/typed keys
      register. Begin/refresh/end advertising from the app.

## Previously added, re-verify end to end
- [ ] Evil Portal (AP + captive page + live creds), Sub-GHz jammer/bruteforce, IR jammer, jam detect,
      BLE iBeacon/media, Network Scan (hosts/ports/CVE), Channel Analyzer, ARP/NetCut + MITM proxy,
      WPA/PMKID crack, Sub-GHz capture/replay/decode, NFC (with PN532), SSTV RX/TX, mic
      spectrum/waterfall/oscilloscope, record, speaker volume.

## Known bugs to investigate (could not be reproduced remotely)
- [ ] **Grey icons on base theme**: some home-grid/tool icons appear grey until the theme is changed.
      Check whether it's capability-gating (intended grey for absent modules) vs the device reporting
      a dark theme color vs a recomposition issue. Note which specific icons and in which state
      (connected? which modules?).
- [ ] **APK update one-tap** (fix implemented): first tap on "Download & install" starts the
      install; after granting "install unknown apps" it auto-retries on resume. Confirm on device.

## Remaining Bruce parity - status
Karma, Responder, BLE HID (above), SRIX read and the Ethernet attacks are now implemented.

Implemented, verify on hardware:
- [ ] **SRIX / ST25TB** read (`srix_read`, NFC screen -> advanced). Uses the onboard PN532.
- [ ] **Ethernet DHCP-starvation / MAC-flooding** (`eth_dhcp_starve` / `eth_mac_flood`, Ethernet
      screen -> Attacks). Needs a W5500 add-on; gated on `caps.eth`.
- [ ] NRF24 spectrum/scan, LoRa chat/send/recv: already wired as companion commands (`nrf24_scan`,
      `lora_*`); verify once the NRF24 / LoRa hardware is attached.

Not portable to companion (external device only - documented, not a gap):
- NFC Chameleon / amiibo: drive an external ChameleonUltra / Amiibolink over serial/BLE through an
  interactive GUI; no headless PN532 primitive exists. The app reports this honestly.
- MIFARE nested / mfkey32: no native cracker; needs an external PN532Killer sniffer. The onboard
  PN532 does the dictionary key-check (`mifare_keycheck`). The app buttons now say so.
- 125 kHz LF write/clone/T5577: no native write path (read is interactive UART only).

## Recently shipped - verify on hardware
- [ ] **Melody composer** (Tone tool): tap notes/rests, set tempo, Play on the device speaker,
      Save -> appears under "My songs" and survives an app restart.
- [ ] **Companion status screen**: when launched from the device menu it shows a download QR, then
      "Download the app to connect: github.com/1vcian/BruciaDev" while waiting and "App connected."
      once the app attaches.
- [ ] **App-mode input lock**: while connected, the wheel/OK and a short ESC tap do nothing; a long
      ESC hold (~700ms) exits companion mode (disconnects); reconnect via the app re-locks. Confirm
      the hold timing works on the T-Embed and that it never gets stuck locked.
- [ ] **125 kHz LF read** (NFC advanced -> "125kHz read"): on a board with the UART LF reader,
      returns a tag; safe no-op elsewhere.
- [ ] **Macros** (Tools -> Macros): build a command sequence, save, run one-tap; per-step progress;
      Stop cancels; macros persist across restarts.
- [ ] **Evil Portal HTML editor** (Evil Portal -> Custom): edit HTML, Preview renders in the WebView,
      Save keeps a named template; starting with Custom pushes the edited page to the device and the
      captive portal serves it.
- [ ] **3D RF map** (Tools -> RF Map 3D): start Wi-Fi/BLE scans + sub-GHz sweep, Refresh plots the
      sources, drag rotates, slider zooms, Save PNG writes a file. (Approximate placement by RSSI.)

> AUDIT SCOPE: two Opus audit passes are done. (1) The Karma/Responder/BLE-HID/SRIX/Ethernet/
> composer batch (8 findings, all fixed). (2) The feature sprint - input-lock, 125 kHz, macros,
> Evil Portal editor, 3D map, bug sweep, boot embers (8 findings: fixed the macro double-launch
> race, two WebView leaks, the dead Bug-Sweep trend/peak logic, the BLE over-stop, the empty-PNG
> notice, and hardened the ESC-hold exit with a tap-count + 60s app-gone auto-exit so the lock can
> never strand the device). 125 kHz, LED/LITE guards, Ethernet dtors, input-lock Serial-safety and
> all the persistence paths were confirmed correct. Everything still needs on-hardware confirmation,
> but the code has been reviewed.

## Queued feature ideas (roadmap)
- [ ] Device-side background companion (keep using the device UI while the app is connected) - a
      larger architectural change, deferred (risks the "reboots while connected" class of bug).
