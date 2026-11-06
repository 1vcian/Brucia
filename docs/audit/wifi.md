# Wi-Fi feature audit (batch 17, branch agent17/WIFI)

Scope: the Wi-Fi features in the app feature registry
(`android/app/src/main/java/app/brucia/features/BuiltinFeatures.kt`):
`wifi.wardrive`, `wifi.probes`, `wifi.sniffer`, `wifi.monitor`, `wifi.channels`,
`wifi.connect`, `wifi.mac`, `wifi.crack`, `wifi.evilportal`, `wifi.karma`,
`wifi.brucegotchi`, `spam.spamwifi`.

Each feature was traced screen/UI -> ViewModel/service -> exact command/stream string ->
preflight/capability gating (`CommandCapabilities`, CAPS `features`, LITE compile-outs) ->
firmware handler (`src/companion/companion.cpp` SUB dispatch, `src/companion/wifi.cpp`
`dispatchWifiCommand`, `src/modules/wifi/*`) -> replies/events -> parsing -> UI ->
stop/leave/rotation/disconnect cleanup. Firmware RF/promiscuous behaviour was reviewed in
source only; nothing was run on hardware (a soak was in progress). RF effects are listed as
hardware checks, not run.

Severity: HIGH (wrong/dangerous behaviour, crash/abort, misleading success), MED (user-visible
correctness or honest-framing defect), LOW (cosmetic, latent/defensive, edge case).

## Summary

One MED defect fixed: the beacon-spam (`spam.spamwifi`) card text asserted a receiver reaction
as fact ("so nearby clients see bogus SSIDs ... appear as that many separate networks"), which is
exactly the kind of unverified-effect claim the BLE batch-16 honest-framing work removed. Reworded
to describe the device action and mark the receiver reaction unverified; pinned by a new source-scan
JVM test (`WifiSpamTextTest`) mirroring `BleSpamTextTest`.

**Follow-up (F22 closed):** the four LOW items were addressed. (a) Firmware now has idle
`karma_stop` / `responder_stop` guards mirroring `brucegotchi_stop`, so a stop reaching an idle
device answers "not running" instead of starting a run; a static host test
(`tools/test_companion_wifi_dispatch.py`) fails if any app stop verb can fall into a start handler.
Batch 18 generalized this to every dispatcher (`tools/test_companion_dispatch_stops.py`). The Wi-Fi
verbs that were bare prefixes (`evil_portal_start`, `wifi_disconnect`, `wifi_status`,
`wifi_get_mac`, `responder`, `karma`, `brucegotchi`, `jam_detect`) are now matched exactly, so
`evil_portal_start_x` or `wifi_status_stop` is not read as that command. `jam_detect_stop` on an
idle device answers "not running" instead of opening a detection window.
(b) The beacon-spam UI now shows the device's real SSID count from each reply (falling back to the
requested count only when a reply lacks it). (c) The Evil-Portal deauth sub-text no longer claims
the client joins the fake AP. (d) Firmware `monStart` now clamps the lock channel to 1..13 to match
the app and PROTOCOL.md (out-of-range falls through to hop mode, no exception). Firmware changes in
`src/companion/wifi.cpp` need a post-soak build; everything else is host/JVM-tested.

No crashes/aborts, no throwing numeric parsers, and no misleading ACKs were found on the native
Wi-Fi command/stream path. The companion Wi-Fi handlers parse arguments with non-throwing Arduino
helpers (`String::toInt`, `strtoul`, `sscanf` with a field-count check), so the batch-16 De Bruijn
`std::stoull` class of defect (backlog F01-F03) does not reach any in-scope native command. The app
side validates every command before sending (MAC regex, channel 1..13/1..14, BSSID regex, capture
descriptor path allowlist + CRC verify, duration clamps). Stream ON/OFF symmetry, exclusive-peer
teardown, rotation retention and Stop/disconnect cleanup are handled by `StreamSubscriptions` /
`LongOperation` / `DeviceOperation` and are already well covered by existing tests.

The four follow-up items (a firmware idle `karma_stop`/`responder_stop` guard gap, the beacon-spam
count-vs-payload-cap mismatch, the Evil-Portal deauth sub-text phrasing, and the monitor channel
clamp) are now fixed; see the Follow-up note below and the per-feature rows. The only Wi-Fi item
still open is the on-hardware/RF acceptance.

## Per-feature table

| Feature | Path traced | Findings (severity) | Fixed? / test | Open items / hardware checks |
|---|---|---|---|---|
| `wifi.wardrive` | `MainActivity` StreamToggleRow "Wardriving" / Wardrive screen -> `WardriveViewModel.setWardriving` -> `StreamSubscriptions.set("wifi.ap")` -> `SUB wifi.ap` -> `companion.cpp` wifi.ap branch: `WiFi.scanNetworks(async)` back-to-back -> `EVENT wifi.ap {bssid,ssid,ch,rssi,enc,ts}` -> VM dedup/GPS/map/Wigle+KML export. | None. Exclusive peers (sta/probe/monitor) stopped on start; OFF sent on Stop/leave/disconnect; honest text (device only captures, phone maps). | n/a (existing coverage: StreamSubscriptionsTest, StreamRotationTest, Wigle/KML tests). | HW: real AP observation, enc classification, no-GPS wardrive flow. |
| `wifi.channels` | `ChannelAnalyzerScreen` -> reuses `wifi.ap` stream (`vm.setWardriving`) -> `ChannelAdvisor` (phone-side congestion/recommend). | None. Rotation retained via `rememberRetained { wardriving }`; LeaveEffect stops only if this screen started it (not if wardrive already ran). Honest ("Recommended channel", "strongest signal seen"). | n/a. | HW: congestion accuracy vs a real band. |
| `wifi.sniffer` | `MainActivity` station-sniffer tab -> `WardriveViewModel.startStationSniff` -> `setStream("wifi.sta")` -> `companion.cpp` wifi.sta -> `staStart/staService` promiscuous data+mgmt, channel-hop (280 ms), ISR-safe ring(48) -> `EVENT wifi.sta {mac,bssid,rssi,ch}`. | None. sig_len>=24 checked before addr reads; exclusive with ap/probe/monitor; `staStop` clears promiscuous cb; LeaveEffect stops on tab leave. | n/a. | HW: real station capture, channel-hop coverage, radio release. |
| `wifi.probes` | probe tab -> `startProbeSniff` -> `setStream("wifi.probe")` -> `probeStart/probeService`: mgmt frames, FC type0/subtype4, SSID IE parsed with bounds checks -> `EVENT wifi.probe {mac,ssid,rssi,ch}`. | None. SSID IE length clamped to 32 and `sig_len` bounded before memcpy; wildcard (empty) handled. Exclusive peers stopped; LeaveEffect stops. | n/a. | HW: real probe capture; wildcard vs directed probe. |
| `wifi.monitor` | monitor tab -> `startMonitor(channel,bssid)` -> `setStream("wifi.monitor",channel,bssid)` (app validates channel 1..13, BSSID regex) -> `companion.cpp` reads `d.channel`/`d.bssid` -> `monStart`: lock-or-hop, optional BSSID filter -> `EVENT wifi.monitor {ts,ch,rssi,len,data(b64)}` -> `PcapWriter` export. | FIXED (d): firmware `monStart` now clamps the lock channel to 1..13 (was 1..14); an out-of-range value falls through to hop mode, no exception. | FIXED (firmware, needs build): `tools/test_companion_wifi_dispatch.py::test_monitor_channel_clamped_to_1_13`. | HW: full-frame capture, BSSID filter correctness, pcap validity on Wireshark. |
| `wifi.connect` | Connect tool -> `WifiJoinService` (single-owner `DeviceOperation`): `wifi_status` / `wifi_connect <ssid>\x1f<pass>` / `wifi_disconnect` -> `dispatchWifiCommand` -> `_connectToWifiNetwork` / `wifiDisconnect`. | None. Local empty-SSID guard; 0x1F separator lets ssid/pass hold spaces; 30 s join timeout; honest status ("Connected - IP ..."). | n/a (WifiJoinServiceTest). | HW: real join/DHCP, failure text on bad password. |
| `wifi.mac` | MAC tool -> `WifiMacService`: `wifi_get_mac` / `wifi_set_mac <mac|random>` (local `isValidMacArg` mirrors firmware `validateMACFormat`) -> firmware validates then `sscanf` + `esp_wifi_set_mac` + persist. | None. Bad arg -> `ERR 400` on both sides, no throw. | n/a (WifiMacServiceTest). | HW: MAC actually changes on air; interaction if a stream is active (forces WIFI_STA). |
| `wifi.crack` | Capture + crack: `captureAndCrack`/`capturePmkidAndCrack` -> `CaptureWorkflow.start` (validates MAC regex, channel 1..14, SSID newline/NUL, duration clamp) -> `wifi_deauth` provoke + `wifi_handshake`/`wifi_pmkid` -> `capture_*_bounded` (bssid via `sscanf` count check, no throw) -> capture-descriptor path allowlist + CRC verify -> pull -> `WpaCracker` (phone dictionary). | LOW: CrackScreen header "WPA/WPA2 dictionary attack - heavy crypto on the phone (what Flipper cannot do)" is a competitive comparison; borderline vs the no-positioning rule (factual, not an effect claim). | n/a (CaptureWorkflowTest, CaptureDescriptorTest, HandshakeParser/PMKID/PcapReader via COV). | HW: real handshake/PMKID capture + end-to-end crack. |
| `wifi.evilportal` | `EvilPortalScreen` -> `EvilPortalService` (single-owner; Stop wins over pending template push/start; start token) -> `evil_portal_start <ssid>\t<tpl>\t<ch>\t<deauth>` / `_stop` / `_status` -> `EvilPortal` (autoMode, backgroundMode) -> `evilPortalService` streams `EVENT evil.cred {ap,pass}`. | FIXED (c): the deauth sub-text no longer claims the client joins the fake AP; it now says deauth frames are sent and whether/where a client reconnects is unverified. Main text already honest. | FIXED: `WifiSpamTextTest::noUnverifiedReceiverEffectClaimInEvilPortalDeauth`. | HW: AP up + captive page served + credential stream; deauth-driven roam; teardown restores WiFi mode. |
| `wifi.karma` | Karma tool -> `KarmaService` (`LongOperation`): `karma <secs>` (clamped 5..600) -> `karma_run_headless(..., companionPollStop)`; Stop sends generic `stop` (consumed by `companionPollStop`). | FIXED (a): firmware now has an idle `cmdStr == "karma_stop"` guard (and `responder_stop`) before `startsWith("karma")`, so a stop reaching an idle device answers "not running" instead of starting a run; mirrors `brucegotchi_stop`. | FIXED (firmware, needs build): `tools/test_companion_wifi_dispatch.py::test_app_stop_frames_never_start_a_run` (mutation-checked: fails if a guard is removed). | HW: lure behaviour, screen redraw after run, Stop latency. |
| `wifi.brucegotchi` | Brucegotchi tool -> `BrucegotchiService`: `brucegotchi <secs>` (5..600) -> `brucegotchi_run_headless`; dedicated `brucegotchi_stop` frame; explicit idle `brucegotchi_stop` guard before `startsWith("brucegotchi")`; companion screen redrawn after run. | None. Reference implementation for the idle-stop guard karma/responder lack. | n/a (HeadlessWifiOpsTest). | HW: handshake collection to /BrucePCAP/handshakes, Stop latency. |
| `spam.spamwifi` | `BeaconSpamCard` -> `WardriveViewModel.startBeaconSpam` -> `BurstLoop` of `wifi_beacon_spam <secs> <ssids>` (~2 s bursts) -> `runWifiBeaconSpam` (clamp 1..120 s, list normalised, capped 9000 chars, final '\n' ensured) -> `beaconSpamListBounded` -> firmware ACK "beacon spam done (Xs, N ssids)". | **MED (honest framing, FIXED):** card text asserted the receiver reaction ("so nearby clients see bogus SSIDs ... appear as that many separate networks"). LOW: for large counts / long base names the 9000-char payload cap and the 32-byte SSID limit silently drop SSIDs, so the device may broadcast fewer than the app's stated count (firmware ACK reports the real count; the burst-loop UI shows the requested count). | FIXED: reworded card text (`WifiSpamTextTest`); the UI now shows the device's real SSID count from each reply, falling back to the requested count only when a reply lacks it (`BeaconSpamTest`, `BeaconSpam.kt`, `BurstLoop` progress hook). | HW: beacon frames on air; whether a scanner lists them (explicitly unverified in the text now). |

## Cross-cutting notes

- **Argument validation (both sides).** No in-scope native Wi-Fi command reaches a throwing
  conversion. Firmware uses `String::toInt`/`strtoul`/`sscanf` (field-count checked for BSSIDs),
  all non-throwing; the app validates before send. Backlog F03 ("legacy numeric parsers" via the CLI
  fallback) does not apply to the native `wifi_*` / `evil_portal_*` / `karma` / `brucegotchi` /
  `wifi_beacon_spam` handlers, which are intercepted before the CLI fallback.
- **Stream ON/OFF symmetry and resource ownership.** `wifi.ap`, `wifi.sta`, `wifi.probe`,
  `wifi.monitor` are mutually exclusive on the shared Wi-Fi radio and each start stops the others
  (`companion.cpp`). `StreamSubscriptions` is single-owner (not ref-counted) but the in-scope
  consumers of `wifi.ap` (wardrive, channels, Evil-Portal target scan) are different screens reached
  sequentially, so no live double-owner conflict. Stop/leave/disconnect send exactly one OFF per
  started stream.
- **BLE/Wi-Fi coexistence.** Starting a Wi-Fi stream does not stop the `ble.scan` stream and vice
  versa; the two coexist on the 2.4 GHz radio. Not changed; flagged as a hardware check (verify a
  `wifi.*` + `ble.scan` combination does not starve either).
- **Print hygiene / blocking prompts.** The native handlers use `companionAckMsg`/`sendErr`/`EVENT`
  only; long ops poll `companionPollStop` (PING answered, other CMDs -> ERR 409, stop ends the run).
  No unguarded `Serial` prints or device-keyboard prompts on the in-scope path
  (covered by `tools/test_companion_print_hygiene.py`, `tools/test_companion_prompts.py`).

## Hardware checks needed (do NOT run during the soak; RF/authorized-lab only)

1. Wardrive/channels: real `wifi.ap` observations, encryption classification, best-channel advice.
2. Sniffer/probes/monitor: promiscuous capture on real traffic, channel-hop coverage, monitor
   BSSID filter + channel lock, pcap opens cleanly in Wireshark, radio released on Stop.
3. Connect/MAC: real join + DHCP, bad-password error text; MAC actually changes on air and the
   change survives the `WIFI_STA` mode forced by `wifi_set_mac`.
4. Crack: real 4-way handshake and PMKID capture + descriptor/CRC round-trip + a successful
   dictionary crack end-to-end.
5. Evil Portal: AP up, captive page served (default + custom template), credential `EVENT` stream,
   deauth-driven client roam, teardown restores WiFi mode on Stop/disconnect/exit.
6. Karma / Brucegotchi: lure / handshake collection, Stop latency, companion screen redraw after the
   run paints over it.
7. Spam Wi-Fi: beacon frames on air; confirm the device's reported SSID count matches intent at
   large counts / long base names (payload-cap edge).
8. BLE/Wi-Fi coexistence: a `wifi.*` stream plus `ble.scan` does not starve either radio user.
