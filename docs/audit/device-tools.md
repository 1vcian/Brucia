# Device and tools audit (agent17/DEVICE, 2026-11-06)

Scope: the Device category (`org.brucia.device.console`, `deviceinfo`, `ethernet`, `files`,
`remote`, `updates`) and the Tools entries `gadgets`, `macros`, `pcapdissect`, `report`,
`scripts`, `utilities` of `android/app/src/main/java/app/brucia/features/BuiltinFeatures.kt`,
plus the shared paths they use: transport and connect/disconnect, Diagnostics card and export,
`BruciaLog`, saved items / Room, the in-app updater (APK and firmware download, verification,
flashing through `EspFlasher`) and settings persistence. Base `7abe1143` (batch 16).

Method: each feature traced from its Compose screen through the ViewModel/service, the exact
command strings, `CommandCapabilities` preflight, the firmware handler (companion dispatch or Bruce
CLI through `routeCommand`/SimpleCLI), the reply parsing and the UI, then cleanup on stop, leave,
rotation and disconnect. Nothing here was run on hardware: a 7-hour soak occupied the only board,
so no serial port was opened, no firmware was built and no emulator was started. Firmware changes
are host-tested only and need a T-Embed build plus the hardware checks listed below.

Severity: HIGH = wrong/unsafe result likely in normal use or integrity at risk; MED = wrong
result, misleading success or data exposure in a plausible case; LOW = cosmetic, edge case or
robustness.

## Summary

- 14 fixes: 9 MED, 5 LOW. 12 have tests that fail on the base and pass after; #13 is a text
  change and #14 (UI-only clipboard flag) has no unit test. No HIGH finding. Update integrity was re-verified end to end and is intact; one
  fix makes a failed flash leave the device in a running state instead of in the ROM loader.
- Firmware change needing a T-Embed build: iButton headless write (`src/modules/others/ibutton*.{h,cpp}`,
  `src/companion/system.cpp`). Every other fix is app-only.
- Open items: 9 at first (DT01-DT09 in `skills/brucia-maintenance/references/backlog.md`). After
  the follow-up (section below) only DT01 (owner decision on the cracked Wi-Fi key in exports)
  and the hardware confirmations DT03/DT04 remain open.

Fixed and their tests (failing on the base, passing after):

| # | Sev | Feature | Defect | Fix | Test |
|---|---|---|---|---|---|
| 1 | MED | ethernet | Status read `link`/`ip` at the top of the reply; the firmware nests them in `d.data` (`ethSendStatus`), so a real device always showed "Link: down", "IP: -". ERR replies (`msg`) showed "No Ethernet on this device." | `EthernetService.statusOf` reads `d.data` (top-level fallback); a reply with link state maps it even when ok=false (cable in, no lease); `msg` shown for ERR | `EthernetServiceTest.refreshReadsTheFirmwareNestedDataObject`, `errMessageIsShownForRefresh` |
| 2 | MED | ethernet | Blank config sent bare `eth_config`, which the firmware does not handle (`startsWith("eth_config ")`): it fell to the Bruce CLI and showed "No Ethernet on this device." A successful static config was followed by `eth_status`, which brings the W5500 up again on DHCP and hid the static result | Blank means `eth_config dhcp`; the config ACK (which carries the link state) is mapped directly, no chained refresh; screen text says settings are a link test, not kept | `EthernetServiceTest.configureMapsItsOwnAckWithoutAFollowUpRefresh`, `configureFailureShowsTheDeviceText` |
| 3 | MED | files | `storage list/remove/mkdir <path>` sent unquoted to SimpleCLI: a space made "unknown argument" (folders with spaces could not be opened, deletes silently failed) and `;;` inside a name ran a second CLI command | `DeviceFileBrowser.cliPath`: double-quote paths with space, `;` or leading `-`; refuse `"`, `\`, control chars, edge spaces locally with a message | `DeviceFileBrowserTest.pathsWithSpacesOrSeparatorsAreQuotedForSimpleCli`, `pathsSimpleCliCannotCarryAreRefusedLocally`; `tools/test_storage_cli_paths.py` (drives the real pinned SimpleCLI C sources when `.pio/libdeps` or `SIMPLECLI_SRC` is present; checked against b23d3779) |
| 4 | MED | files | The companion caps a routed CLI reply at 3500 bytes (`CapturingSerialDevice::CAP`) without a marker: large folders were silently partial and the cut last line could show a wrong size ("x.bin\t40" of "\t4096"). The capture workflow already guarded this; the browser did not | `FileList.parseListing` drops a final line without newline and flags a reply at the limit; the Files screen shows "The device's reply limit (3500 bytes) cut this listing: N entries shown, more may exist." | `DeviceFileBrowserTest.aListingCutByTheReplyLimitSaysSoAndDropsThePartialLine`, `FileListTest.parseListingDropsAPartialFinalLine` |
| 5 | MED | files | Device-supplied names were used as local file names ("Open with..." `File(cacheDir/open, name)`); a corrupt or hostile listing line with `/` could write outside that folder (app-private storage) | `FileList.parse` drops names with `/` or control characters (Bruce prints only the part after the last `/`) | `FileListTest.dropsNamesThatCouldEscapeAFolder` |
| 6 | MED | cross-cutting | `android:allowBackup="true"` without rules: Android auto backup (Drive) and device transfer carried the MITM CA private key (`files/mitm/brucia_mitm_ca.p12`, fixed keystore password) and the WiGLE API token | `backup_rules.xml` (API 26-30) and `data_extraction_rules.xml` (31+) exclude `mitm/` and `wigle.xml`; everything else still backs up | `tools/test_android_backup_rules.py` (names read from the sources) |
| 7 | MED | gadgets | Firmware headless iButton write ran the RW1990 sequence immediately (the menu waits for the blank) and always replied `written`, even with nothing on the probe | `ibuttonWriteVerified` (core, host-testable): wait for presence (100 x 30 ms), write, read the ROM back, compare all 8 bytes; replies `written and verified` / "no iButton on the probe ... (nothing written)" / "written, but no key answered the read-back" / "read-back differs" | `tools/test_firmware_ibutton.py` (6 new cases; the base fails to compile them). **Needs firmware build** |
| 8 | MED | gadgets | "Sync from phone" sent a UTC epoch, but Bruce keeps local wall time in the system clock (`updateClockTimezone`, menu set), so the device showed UTC; `clock_get` labels that wall clock with a false `Z` | App sends the phone's local wall-clock epoch (`ClockService.wallClockEpoch`, DST from the phone's zone); `Z` dropped in the shown text | `ClockServiceTest.syncFromPhoneSendsTheLocalWallClockLikeBrucesNtpSync`, `getAndSetTextsAndCommands` (updated) |
| 9 | MED | updates | The Activity reboots the device into the ROM loader before the firmware is downloaded; a download, integrity or image-check failure (or cancel) ended there and left the device dark in the loader until unplugged | `EspFlasher.resetToApp()` (the flasher's hard reset) is issued for any failure before the write starts while the operation still owns the port; messages say "Nothing was flashed. The device was reset into its current firmware." | `EspFlasherTest.resetToAppLeavesTheLoaderWithoutWritingAnything` (VM wiring is untestable on JVM; hardware check U2) |
| 10 | LOW | updates | An MD5 mismatch after writing showed "Firmware flashed - WARNING: ... MISMATCH" (phase error, text started as a success) | `EspFlasher.Outcome.of`: mismatch reads "Flash failed verification ..."; MATCH "flashed and verified"; UNVERIFIED "written but not verified" | `EspFlasherTest.aMismatchedReadBackIsReportedAsAFailedFlashNotAFlashedOne` |
| 11 | LOW | macros | A macro saved without a name got "Macro ${size+1}"; saving replaces by name, so after a deletion it overwrote an existing macro | `Macro.defaultName` picks the first unused "Macro N" | `MacroStoreTest.defaultNameNeverReplacesASavedMacro` |
| 12 | LOW | pcapdissect | A parse whose blocking read was still running when the user left (or opened another file) wrote its file name and `loading=false` back over the newer state | Lazy-started job identity; only the current job publishes | `PcapDissectSessionTest.resetWhileTheBlockingReadRunsLeavesTheStateCleared`, `anAbandonedParseNeverOverwritesANewerOpen` |
| 13 | LOW | macros | Step hint "Command (e.g. wifi_connect ...)": that command needs a 0x1F separator that cannot be typed | Hint "e.g. uptime" | text only |
| 14 | LOW | utilities | Generated passwords and the Wi-Fi QR string (contains the key) were copied as ordinary clips (Android 13+ shows them in the copy preview / keyboard suggestions) | `rememberTextCopier(sensitive = true)` sets `android.content.extra.IS_SENSITIVE` for those two | none (Compose/ClipData; no Robolectric in the project) |

Also changed: Files screen shows the device's answer to Delete / New folder (it was discarded; a
failed delete only showed the file again after the relist).

## Per-feature table

| Feature | Path traced | Findings | Fixed / test | Open items | Hardware checks |
|---|---|---|---|---|---|
| device.console (tab 8) | `ConsoleScreen` -> `vm.sendConsole` -> `dispatchCmd` -> `CommandSession.transmit` (`CommandCapabilities.check`) -> CMD `{"cmd"}` -> `handleCompanionCommand`: built-ins (`scr`, `led`, `theme_set`...), `dispatch*` handlers, else `routeCommand` (SimpleCLI with `CapturingSerialDevice`, 3500 B cap) -> ACK `{ok,out}` -> console (last 500 lines). Palette `CliCatalog`. Export log: `BruciaLog.exportToDownloads` + `exportTail` share | LOW: any routed CLI output over 3500 B is cut without a marker (fixed for the Files browser only). INFO: raw console can run `companion_exit` or several commands joined with `;;` (SimpleCLI line split) by design. Log export: no `BruciaLog` call interpolates passwords/tokens (grep of all call sites); header holds firmware provenance and diagnostics counters | - | DT05: firmware `truncated` flag on routed ACKs | C1 |
| device.deviceinfo (tab 6) | `DeviceScreen` -> `DiagnosticsService` (`info`, `free`, `uptime`, `i2c` only, 20 s); `DeviceOutputs` (`scr`/`led`, restored on disconnect); `scr_bright`; `ThemeLedCard`; `DiagnosticsCard` (`diag` on Refresh); `power` sleep/reboot; "Generate security report" (`ReportBuilder`) and "Export session JSON" (`SessionExport`) written to the external files dir and shared | MED (decision): the security report and session JSON carry the recovered WPA key in plaintext ("WPA crack result: <key>", `crackResult`). LOW: Sleep / Reboot act on one tap without confirmation. LOW: report/JSON written on the main thread without try/catch; `getExternalFilesDir` null would crash; exports accumulate | - | DT01 (key in exports, decide), DT07 (confirm power actions, guarded writes) | D1 |
| device.ethernet (tab 23) | `EthernetScreen` -> `EthernetService` (`eth_status` 16 s, `eth_config <dhcp|static ip gw mask [dns]>` 18 s, `eth_dhcp_starve 10`, `eth_mac_flood 10`) via `jsonRunner`/`commandRunner` -> `src/companion/network.cpp` (`ethBringUp`, `ethSendStatus` `{ok,out,data:{link,up,ip,gw,mask,dns,mac}}`, helper stopped after every command) -> status card | MED: nested `data` never read (always "down"); ERR text lost. MED: blank config unsupported; static result hidden by a DHCP refresh. LOW: "Configuration" overclaimed persistence (settings are not kept after the reply) | Fixed 1, 2 (`EthernetServiceTest` 4 new, 2 replaced); screen text corrected | - | E1, E2 |
| device.files (tab 10) | `FilesScreen` -> `DeviceFileBrowser` (`storage list|remove|mkdir`, Bruce CLI via `routeCommand`, 650 s) ; pull/open/edit -> `FileTransferService` -> `SessionFileLink.read` (`fs_read`: DATA chunks with `seq`, ACK size + CRC32, 16 MiB, 120 s) ; save/new file -> `SessionFileLink.write` (`fs_upload_begin token size crc path`, chunks sized by CAPS `rx_max`, `fs_upload_commit` must echo committed+CRC, abort on any failure; firmware `storage_upload` stages, validates reserved names, verifies CRC before promote, pins SD/LittleFS per transaction). Disconnect: `fileTransfers.cancelAll`, `fileReader.disconnect`, browser stop | MED: unquoted paths (spaces, `;;`). MED: silent truncation at 3500 B. MED: unsafe device names as local file names. LOW: delete/mkdir result discarded. Transfer integrity reviewed: no defect (seq gaps, size, CRC, partial upload abort, commit uncertainty reported, storage-full surfaces as firmware write error) | Fixed 3, 4, 5 + change status (`DeviceFileBrowserTest` 3 new, `FileListTest` 2 new, `tools/test_storage_cli_paths.py`) | DT06: "Pull (app folder)" keeps `filesDir/pulled/pull-*.bin` forever and they are not reachable for non-captures; Delete has no confirmation; browsing LittleFS while an SD is mounted is impossible (Bruce `getFsStorage` parity); Bruce `listCallback` glues two names when a file cannot be opened (upstream) | F1-F4 |
| device.remote (tab 9) | `RemoteScreen` -> `startRemote`: `companion_exit`, cancels read/ping loops, `RemoteController` writes raw `display start`, `nav <dir>`; `TftProtocol`/`TftRenderer`; watchdog re-arms mirror; `stopRemote` -> `display stop`, `startCompanionLoops` (HELLO). `_remoteRunning` in the VM survives rotation; disconnect cancels both jobs | MED: starting Remote does not stop running companion operations (macro, file transfer, script, streams, bursts): their replies can no longer arrive, so they wait until their own timeouts (up to 650 s) and their stop frames would go to the raw CLI | - (needs a hardware-checked ordering of stops before `companion_exit`) | DT02 | R1 |
| device.updates (tab 12) | `UpdatesScreen` -> `checkUpdates` -> `ReleaseUpdateService` (one snapshot: `ReleaseCatalog.parse` exact `<release download prefix from brand.json>/<tag>/<name>`, nesting bound, stable only, size/`sha256:` digest, malformed = rejected) ; APK: `AppDownloadService` single flight -> `DownloadIO.downloadFile` (`.part`, `copyVerified`, promote only on match, busy guard) -> `AppUpdater.install` (package name must match; Android enforces the signing key and no downgrade) ; firmware: Activity board gate -> `flashEnteringBootloader` (owner token, 90 s handoff) -> reset into ROM, re-enumerate, permission -> `flashFirmware` -> `DownloadIO.owned` + `downloadBytes` (verified in memory) -> `FirmwareImage.validateTEmbed` (ESP32-S3 chip id, segment checksums, appended SHA-256, partition table MD5, factory app at 0x10000 within bounds) -> `EspFlasher.flash` (stub, SPI attach, FLASH_BEGIN/DATA/END, MD5 verify, hard reset; reset also on failure) | Integrity intact: no path flashes or installs bytes that failed size/digest/image checks; unverified (no digest) assets are labelled. MED: pre-write failure left the device in the ROM loader. LOW: mismatch text read as success. LOW: the verified APK waits in the external files dir; on Android 8-10 an app with storage permission could replace it before the installer reads it (the installer's signature and package checks still refuse a foreign or older APK) | Fixed 9, 10 (`EspFlasherTest` 2 new) | DT08: copy the verified APK to app-private storage (or use `PackageInstaller` sessions) before install. No cancel button for the APK download (cancellation exists in code) | U1-U4 |
| tools.gadgets (tab 20) | `ToolsScreen` iButton card -> `IButtonService` (`ibutton_read`, `ibutton_write <16 hex>`, 10 s, interface gate) -> `system.cpp` `runIButtonRead/Write` (`iButtonPinReady`, `ibutton_*_headless`) ; Clock card -> `ClockService` (`clock_get`, `clock_set <epoch>`) -> `runClockGet/Set` | MED: write unverified, "written" always. MED: clock set to UTC. LOW: read polls only about 0.3 s (10 x 30 ms): the key must already touch the probe when Read is tapped (text says so) | Fixed 7 (firmware, host test), 8 (app) | - | G1-G3 |
| tools.macros (tab 41) | `MacroScreen` -> `vm.saveMacro` (SharedPreferences `macros`, replace by name) -> `MacroRunner` (sequential, 650 s per step, ERR/timeout ends the run, Stop cancels sequencing only, disconnect stops) -> `commandRunner` (logged, preflighted) | LOW: default name overwrote a saved macro. LOW: untypeable hint. INFO: steps are stored and backed up in plaintext; a step that carries a secret (for example a typed password argument) goes with them | Fixed 11, 13 (`MacroStoreTest` 1 new) | DT09: no step edit/reorder; same-name save replaces without asking | - |
| tools.pcapdissect (tab 45) | `PcapDissectScreen` (SAF `OpenDocument`) -> `PcapDissectViewModel.session` (Activity-scoped) -> `PcapDissector.readBounded` (32 MiB) on IO -> `dissect` on Default with cancellation checks -> summary + first `MAX_ROWS` rows; `LeaveEffect` resets | LOW: stale parse overwrote state after leave/new open | Fixed 12 (`PcapDissectSessionTest` 2 new) | - | - |
| tools.report (tab 21) | `ReportScreen` -> `vm.buildFullReport` (`SessionReport.markdown`: APs, stations, probes, handshakes, crack result, sub-GHz, BLE, NRF24, monitor and MITM counts, heuristic insights, diagnostics) -> `.md`/`.txt` in the external files dir -> share | MED (decision): "Crack result: <key>" in the shared report (same as DT01). LOW: SSIDs in probes/BLE names/handshake text are not Markdown-escaped (`mdCell` only on the AP table) - cosmetic. LOW: unguarded main-thread write | - | DT01, DT07 | - |
| tools.scripts (tab 19) | `ScriptsScreen` -> `ScriptService` (`script_run <src>`, local 6000-byte limit, 25 s; Stop sends `script_stop` once through the unlogged direct sender, run's own reply shows the outcome; disconnect abandons) -> `runScriptRun` (batches 15-16) | No new defect. A source whose escaped frame exceeds CAPS `rx_max` is refused locally with "Command exceeds the device receive buffer" (honest, not a silent loss) | - | - | covered by batch 16 checks (TESTS.md) |
| tools.utilities (tab 24) | `UtilitiesScreen` -> `PhoneUtils` (SecureRandom password, MD5/SHA-1/SHA-256, Base64, Wi-Fi QR payload escaping `\ ; , : "`), `OuiLookup`, `QrCodes`; no device | LOW: secrets copied as normal clips. LOW: "Select at least one character set" appears as a copyable result; length over 128 silently clamped | Fixed 14 (no unit test) | - | X1 |

Cross-cutting paths:

| Path | Traced | Result |
|---|---|---|
| Transport selection, connect/disconnect | `UsbTransport` is the only `Transport` (no BLE link exists; screens say "Connect over USB"). `disconnectLink`: `ServiceLifecycle.disconnect()` over 37 entries (each feature's stop/abandon), unsubscribes streams, restores screen/LED, closes the session and the USB lease through `FlashPortOwner` | No defect found. Remote mode is the gap (DT02) |
| Diagnostics card and export | `refreshDiagnostics` (`diag` on request only), `Diagnostics.exportLines` in log exports and reports | Counters only; no secrets |
| Logging (`BruciaLog`) | 256 KB rolling file in the external files dir, export to Download/Brucia and share | No call interpolates a secret; exceptions carry messages only |
| Saved items / Room | `BruciaDb` v3, explicit `BruciaMigrations.ALL`, no destructive fallback | No defect |
| Settings persistence / backup | SharedPreferences `macros`, `portals`, `wigle`, `ir_remote`, `songs`, `geo_signals`, `rf_*`, `osmdroid`; `files/mitm` | Fixed 6 (secrets out of backups) |

## Follow-up fixes (coordinator request, same day)

DT01 (recovered WPA key in reports and exports): DECIDED 2026-11-06 (authorized) - KEEP the key in
plaintext in the security report, session JSON and session report. These are authorized-assessment
artifacts and the operator needs the actual key, so it is not masked; each emission point has a `DT01`
comment and tests (`SessionExportTest`, `ReportTest`, `SessionReportTest.recoveredWifiKeyIsShownInPlaintext`)
pin the decision. The "rotate it if this is your network" advisory stays.

| Item | Sev | Fix | Test |
|---|---|---|---|
| DT02 Remote hand-over | MED | `remote/RemoteHandover`: before `companion_exit`, while framed replies are still read, `startRemote` ends a running macro (`MacroRunner.stop`, no frame), a waiting script (`ScriptService.stopForHandover`: `script_stop` once, written before the wait is dropped), file transfers (`FileTransferService.cancelAllAndJoin`: waits for the upload's own `fs_upload_abort` and its ACK) and burst loops; then `companion_exit` | `RemoteHandoverTest` (3: frame order on a real `CommandSession` over a recording transport, nothing-running case, no repeated `script_stop`) |
| DT05 CLI reply cap | LOW | App-only. `protocol/CliReplyLimit`: an ACK `out` at the 3500-byte capture limit (+-3 bytes for a split UTF-8 character) gets a final line "(output truncated by the device at 3500 bytes)" in `routeCommandReply`, so the console and every CLI-backed tool show it; parsers get the exact device text back with `unmark` (the Files listing still drops the cut last line). Native handlers never reach the cap (script output is cut by the firmware at about 1.8 KB). No firmware change | `CliReplyLimitTest` (3) |
| DT06 Files | LOW | Delete asks for confirmation. Local pulled copies (`filesDir/pulled`, `pull-*` and `capture-*`) keep the newest 20 (`utils/FileRetention`) | `LocalFilesCleanupTest.pruneKeepsTheNewestMatchingFilesOnly`; dialog: none (Compose) |
| DT07 Device / Report | LOW | Sleep and Reboot ask for confirmation. Report, security report and session JSON exports go through `report/ExportFiles` (internal files dir when external storage is missing, failure shown as "Export failed: ...", newest 10 per kind kept). Device-supplied text in report list items (probe SSIDs, BLE names, handshake info, sub-GHz protocol) is escaped like table cells | `LocalFilesCleanupTest.exportsAreWrittenAndBoundedPerKind`, `anExportFailureIsAnIOExceptionNotACrash`, `deviceTextCannotBreakTheReportStructure` |
| DT08 APK cache | LOW | The verified APK now waits in the app-private cache (`AppUpdater.apkDir` = `cacheDir/update`, FileProvider `cache-path`), not the external files dir. Update downloads older than 30 minutes (old location included) are deleted at app start. The Updates screen has "Cancel download" (`AppDownloadService.cancel`) | `LocalFilesCleanupTest.staleUpdateDownloadsAreRemovedFreshOnesKept` |
| DT09 Macros | LOW | Saved macros have "Edit" (loads name and steps into the builder; saving under the same name replaces it), steps have "Up" to reorder, and the save button says "Save (replaces \"name\")" when it would replace one | `MacroStoreTest.moveStepReordersWithinBounds` |

Not changed (other auditors' screens, same pattern): the wardrive CSV/KML/GPX, NDEF and hc22000
exports still write to the external files dir without a guard.

## Hardware checks (not run: the board was busy with a soak)

Each needs the T-Embed CC1101 on USB with the companion; firmware-backed items need a build of this
branch (`lilygo-t-embed-cc1101`).

- C1 Console: `storage list /` on a folder with more than about 150 entries; the output ends with
  "(output truncated by the device at 3500 bytes)".
- D1 Device info: Generate security report after a crack session; confirm the key appears (DT01
  decision).
- E1 Ethernet with a W5500 and a DHCP LAN: Refresh shows "Link: up" and the leased IP (was always
  down/-); unplug the cable: "Link: down" with the device text.
- E2 Ethernet: Apply with a blank field (DHCP) shows the leased IP; apply `static 192.168.1.50
  192.168.1.1 255.255.255.0` shows `link up, ip 192.168.1.50`; a bad static string shows the usage
  text; on a board without W5500 "W5500 not found" / "no Ethernet on this board".
- F1 Files: create a folder "My Files" from the app, open it, create "a b.txt", delete it; the
  status line shows "Directory created" / "File removed" and the listing updates.
- F2 Files: a name containing `;;` (create with the editor, e.g. `/x;;y.txt`): list and delete only
  touch that file.
- F3 Files: a folder with 300 small files: the truncation notice appears and no entry shows a
  wrong size; a folder with 10 files shows no notice.
- F4 Files on SD and on LittleFS (SD removed): list, pull (CRC verified), save from the editor
  (transactional upload), delete.
- R1 Remote: start a macro with a long step, a script (`while (true) { delay(100); }`) and an upload
  of a large file, then start Remote: the script shows "Stopped: Remote control took over the
  device.", the upload is not reported as saved and no `.brucia-upload.*` file is left, the macro
  shows it was stopped, the device leaves companion mode and the mirror starts; Stop returns to
  companion mode with everything usable.
- U1 Updates: normal flash of the current release; flash log shows the MD5 verdict and the
  device boots the new firmware.
- U2 Updates: break the firmware download after the bootloader handoff (airplane mode right after
  "Rebooting into bootloader..."): the message says "Nothing was flashed. The device was reset
  into its current firmware." and the device boots its old firmware without unplugging.
- U3 Updates: APK download and install from a release with digests; then a release without
  digests shows the "Unverified" label.
- U4 Updates (stub path): confirm FLASH_BEGIN with the 5-word payload is accepted by the esptool
  stub on the S3 (already exercised by U1).
- G1 Gadgets (firmware build of this branch, iButton pin set): Write with no key on the probe:
  "no iButton on the probe ... (nothing written)" within about 3 s. Write onto an RW1990 blank:
  "written and verified"; Read returns the same id.
- G2 Gadgets: Write onto a DS1990A (read-only) key: "read-back differs ...".
- G3 Gadgets: Sync from phone, then compare the device status-bar clock with the phone (same local
  time, including DST); Get time shows it without "Z".
- X1 Utilities (Android 13+): copy a generated password; the system copy preview hides it.
- U5 Updates: Download & install, then confirm the system installer still opens the APK from the
  app-private cache; tap "Cancel download" during a download: "Download cancelled.", no installer.

## Verification run for this audit

- `python3 -m unittest discover -s tools -p 'test_*.py'`: 204 tests OK (3 skipped: no
  `.pio/libdeps` in the worktree; the new SimpleCLI check was run separately against the real sources).
- `python3 tools/test_android_logic.py`: 249 tests OK.
- `python3 tools/test_firmware_ibutton.py`: PASS, 84 checks (UBSan; ASan unavailable in this shell).
- `SIMPLECLI_SRC=<libdeps copy> python3 tools/test_storage_cli_paths.py`: real SimpleCLI b23d3779
  matches the port.
- Gradle `--max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:verifyPaparazziDebug
  :app:assembleDebug`: BUILD SUCCESSFUL, 973 unit tests, 0 failures, lint 0 errors, Paparazzi
  verified. (A first run failed one test, `LifecycleRegressionTest`, that encoded the old chained
  Ethernet refresh; it now asserts the new contract.)
- Fail-before evidence: the new Ethernet, PCAP and iButton tests were run against the base sources
  and fail (Ethernet 4 of 8, PCAP 2 of 6, iButton does not compile); the Files, Clock, Macro and
  flasher tests use new API, so they do not compile against the base.
- Follow-up (DT02, DT05-DT09): Gradle full run BUILD SUCCESSFUL, 985 unit tests, 0 failures, lint
  0 errors, Paparazzi verified (the first follow-up invocation stopped at a 12 s compile error, no
  tests run; fixed and rechecked with a compile-only task before the full run).
  `test_android_logic.py` 252 OK, tools unittest 204 OK.

