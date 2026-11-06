# Radio features audit (batch 17, branch agent17/RADIO)

Scope: the feature-registry entries `org.brucia.subghz.rf`, `radio.fm`, `radio.lora`,
`24ghz.nrf24`, `24ghz.espnow`, `nfc.nfc`, `spam.spamnfc`, `bleir.ir`, `bleir.irremote`,
`bleir.ble`, `bleir.bleremote`, `bleir.badusb`, `spam.spamble`.

Method: each path was traced from the screen through the ViewModel/service, the exact command or
stream string, the app preflight (`CommandCapabilities`, CAPS features, LITE compile-outs) and the
firmware handler (`src/companion/*.cpp`, or the Bruce CLI through `routeCommand`), then back
through the reply/event parsing to what the screen shows, plus Stop, leave, rotation and
disconnect cleanup.

Limits: this is software evidence only. A hardware soak was running during the audit, so no
firmware was built, no serial port was opened and no emulator ran. Firmware changes are checked
by host-native compiles of the pure headers, by static source checks and by the protocol mock
(`tools/mock_device.py`, which mirrors the new behavior). They are not proof of device behavior;
the hardware checks below are still required. Nothing here adds a transmit mode, power, rate,
payload or frequency. Every firmware change is a rejection, an ownership check or an honest
result.

Severity: HIGH = device abort, data written without the user's knowledge, or freed memory in use;
MED = wrong result shown, a blocked link, or a radio owner lost; LOW = cosmetic, console-only or
wording.

## Per feature

| Feature | Path traced | Findings | Fixed? / test | Open | Hardware checks |
|---|---|---|---|---|---|
| `subghz.rf` (RF) | `SubGhzScreen` -> VM `setSubghzStream` (`subghz.raw` / `subghz.spectrum` SUB), `RfReceiveService` (`rf rx <Hz>`, 20 s), saved-item Replay (`rf tx`), `.sub` Transmit (`subghz txp`), burst card, `SubGhzBruteforceService`, De Bruijn (refused locally). Gating: `subghz_*`, `rf`, `subghz` need `cc1101`. Firmware: `companion.cpp` SUB handlers, `subghz.cpp` `dispatchSubghzCommand`, CLI `rf_commands.cpp` through `routeCommand`. Leave/disconnect: `LeaveEffect` and the lifecycle entries `rf-receive`, `subghz`, `bursts`. | HIGH F03: `std::stoul` on frequency/te/count/bits/button/serial/counter/repeat throws on text or an over-wide value. That is uncaught on the companion path, so the device aborts. HIGH: `txp` bits are not bounded, so a `.sub` `Bit: 2000000000` sizes `reserve()`, which throws `std::length_error`, and bits > 64 is a shift beyond the key width. A huge count/repeat does the same through `rf tx`/`RfSend`. MED: one-shot radio commands run while Listen/Spectrum owns the CC1101; they deinit the radio under the stream, which stays "on". MED: spectrum `start`/`step` are kHz but were read as Hz (labels 0.30-0.35 MHz). MED: saved Replay always sends `rf tx` (24-bit protocol 1), so a CAME/Nice/KeeLoq capture went out as a different frame. LOW: `txp`/`keeloqtx` return success without the TX result; `subghz_config` ACKs ok on CC1101 init failure; the app passes keys with zero padding over 16 digits that the firmware rejects; the brute-force text says "every code". | Yes. F03: non-throwing whole-token parser `src/core/serial_commands/cli_number.h`. Bounds: bits 1..64, te 0..65535, count/repeat 0..1000, KeeLoq button 0..15, serial 28-bit, counter 16-bit. `RfSend` gets the same bounds, the encoder refuses bits > 64, and the TX result is returned. CC1101 409 guard: `src/companion/subghz_busy.h` plus `dispatchSubghzCommand`. Spectrum: `SpectrumSweep.fromSweepEvent`. Replay: `CliParse.rfReplayCommand` returns null unless the protocol is RcSwitch/Princeton with 24 bits, and the Replay button is hidden otherwise. Also `subghz_config` ok follows init, the app bounds and strips zero padding, and the text is reworded. Tests: `tools/test_firmware_cli_number.py` (fails 6/9 on base), `tools/test_radio_guards.py` (classifier with a mutation check, guard order, mock 409), e2e F03 checks, `SubFileTest.transmitRefusesValuesOutsideTheFirmwareTxpBounds`, `SpectrumSweepTest`, `LogicTest.rfReplayOnlyForCapturesRfTxReproduces`. | Batch 18 fixed: `subghz_tx_raw`/`subghz_replay` report the TX result, and the 409 classifier ignores case. `.sub` Transmit and Replay are fire-and-forget (result only in the console). Saved items created before this change keep their `rf tx` payload. `rf tx` with an out-of-band frequency still transmits at 433.92 with a WARN in the ACK (Bruce behavior). Very large te x repeat can still exhaust heap on boards without PSRAM (`bad_alloc`). | H1, H2, H3, H4 |
| `subghz.rf` (RF) | `SubGhzScreen` -> VM `setSubghzStream` (`subghz.raw` / `subghz.spectrum` SUB), `RfReceiveService` (`rf rx <Hz>`, 20 s), saved-item Replay (`rf tx`), `.sub` Transmit (`subghz txp`), burst card, `SubGhzBruteforceService`, De Bruijn (single-code `subghz tx` path refused locally; a real sweep streams as overlapping `subghz_tx_raw` bursts via `DeBruijnTxService`, orders <=16, batch 18 - hardware check H16 pending). Gating: `subghz_*`, `rf`, `subghz` need `cc1101`. Firmware: `companion.cpp` SUB handlers, `subghz.cpp` `dispatchSubghzCommand`, CLI `rf_commands.cpp` through `routeCommand`. Leave/disconnect: `LeaveEffect` and the lifecycle entries `rf-receive`, `subghz`, `bursts`. | HIGH F03: `std::stoul` on frequency/te/count/bits/button/serial/counter/repeat throws on text or an over-wide value. That is uncaught on the companion path, so the device aborts. HIGH: `txp` bits are not bounded, so a `.sub` `Bit: 2000000000` sizes `reserve()`, which throws `std::length_error`, and bits > 64 is a shift beyond the key width. A huge count/repeat does the same through `rf tx`/`RfSend`. MED: one-shot radio commands run while Listen/Spectrum owns the CC1101; they deinit the radio under the stream, which stays "on". MED: spectrum `start`/`step` are kHz but were read as Hz (labels 0.30-0.35 MHz). MED: saved Replay always sends `rf tx` (24-bit protocol 1), so a CAME/Nice/KeeLoq capture went out as a different frame. LOW: `txp`/`keeloqtx` return success without the TX result; `subghz_config` ACKs ok on CC1101 init failure; the app passes keys with zero padding over 16 digits that the firmware rejects; the brute-force text says "every code". | Yes. F03: non-throwing whole-token parser `src/core/serial_commands/cli_number.h`. Bounds: bits 1..64, te 0..65535, count/repeat 0..1000, KeeLoq button 0..15, serial 28-bit, counter 16-bit. `RfSend` gets the same bounds, the encoder refuses bits > 64, and the TX result is returned. CC1101 409 guard: `src/companion/subghz_busy.h` plus `dispatchSubghzCommand`. Spectrum: `SpectrumSweep.fromSweepEvent`. Replay: `CliParse.rfReplayCommand` returns null unless the protocol is RcSwitch/Princeton with 24 bits, and the Replay button is hidden otherwise. Also `subghz_config` ok follows init, the app bounds and strips zero padding, and the text is reworded. Tests: `tools/test_firmware_cli_number.py` (fails 6/9 on base), `tools/test_radio_guards.py` (classifier with a mutation check, guard order, mock 409), e2e F03 checks, `SubFileTest.transmitRefusesValuesOutsideTheFirmwareTxpBounds`, `SpectrumSweepTest`, `LogicTest.rfReplayOnlyForCapturesRfTxReproduces`. | `subghz_tx_raw` and `subghz_replay` ACK ok without a TX result (`sendRfCommand` is void; console/macros only). `.sub` Transmit and Replay are fire-and-forget (result only in the console). Saved items created before this change keep their `rf tx` payload. `rf tx` with an out-of-band frequency still transmits at 433.92 with a WARN in the ACK (Bruce behavior). SimpleCLI case: a command spelled in upper case may bypass the 409 classifier (the app sends lower case). Very large te x repeat can still exhaust heap on boards without PSRAM (`bad_alloc`). | H1, H2, H3, H4 |
| `radio.fm` (FM) | `FmScreen` -> VM `fmStart*` -> `PcmStreamService` / `SessionPcmLink` (`fm_audio_begin <kHz> phone\|mic`, `fm_audio_data`, `fm_audio_stop`, 4 s each). Gating `fm` (+`mic`). Firmware `audio.cpp` probes 0x63 and checks the range (400). Leave: `LeaveEffect { fmStop() }`. Disconnect: `stopCompanionAudio()`. | MED: the FM I2S TX shares BCLK/WCLK/DOUT with `spkr_pcm` / `speaker_play`, and the FM mic source competes with `mic.spectrum`/`mic.pcm`; nothing stops the other owner. This is reachable through Console/Macros only, since the FM tab stops itself on leave. LOW: `fm_tx` does not probe 0x63; frequency text is parsed partially (`toInt`); the tone field is unbounded (aliases above 8 kHz). LOW: texts claimed working broadcast audio, but the firmware notes the digital routing is unverified. | Texts only: the screen and descriptor state that the Si4713 digital audio routing is unverified on hardware (`RadioAuditTextTest`). | I2S/mic ownership between FM, speaker and mic streams; `fm_tx` probe; tone bounds. | none added. Existing: `fm_audio_begin 90000 phone` on T-Embed should give ok:false "no FM module" |
| `radio.lora` (LoRa) | `LoRaScreen` -> `LoraService` (`lora_config` 10 s, `lora_send` 650 s, `lora_recv <1..120>` secs+10 s), `lora.rx` EVENT. Gating `lora`. Firmware `network.cpp`. Leave: `stopLoraRecv`. | MED: `lora_recv` blocked for up to 120 s polling only the device ESC key. App Stop only cancelled the local wait, so with no request pending the ping loop saw over 26 s of silence and reported "Reconnecting (device restarted?)" and stopped all loops. LOW: `lora_config` parses tokens partially (the echo shows the result); a failed send paints the companion screen. | Yes. `runLoraRecv` polls `companionPollStop()` (PING answered, `stop` ends it, ACK says "(stopped)"). `LoraService.stopReceive` sends `stop` on the direct stop channel. Tests: `LoraServiceTest.stopReceiveSendsDeviceStopSoTheListenWindowEndsEarly`, `tools/test_radio_guards.py` (static plus mock: PING answered and early stop). | Partial number parsing in `lora_config`. | H6 |
| `24ghz.nrf24` (NRF24) | `Nrf24Screen` -> VM `BurstLoop`s `nrf24_scan 8` / `nrf24_sniff 8` (12 s), jammer/MouseJack bursts. Events `nrf24.scan`/`dev`/`mj`. Gating `nrf24` (runtime probe); LITE gives 501. Firmware `nrf24.cpp` clamps seconds and probes presence. Leave stops all loops. | LOW-MED: scan/sniff bursts ignore `stop` (up to 8 s; PINGs wait, still under the watchdog). LOW: scan and sniff loops can run at the same time on one radio. LOW: high event volume (one EVENT per channel per sweep). | No. | All three. | Existing passive check: on T-Embed without a module, `nrf24_scan 2` gives ok:false "no NRF24 module" and the next PING answers. |
| `24ghz.espnow` (ESP-NOW) | `EspnowScreen` -> `EspNowService` (`espnow_send <msg>`, 10 s). Gating `wifi`. Firmware `system.cpp` (LITE 501). | MED: `WiFi.mode(WIFI_STA)` + `WiFi.disconnect(false)` on every send shut down a running Evil Portal AP (the app still showed it) and dropped the device's own network join. LOW-MED: texts said "any ESP32" / "picks it up", but the frame is a broadcast with no ACK on the current channel, so `ESP_OK` only means queued. LOW: silent truncation to `ESP_DATA_SIZE-1`. | Yes. Mode is only raised (off gives STA, AP gives AP+STA) and there is no disconnect. The reply says "queued ... (delivery not confirmed)". Texts reworded. Tests: `tools/test_radio_guards.py` `test_espnow_keeps_wifi_owner`, `RadioAuditTextTest`. | Truncation hint in the UI; `esp_now` never deinitialized. | H15 (sends one broadcast frame) |
| `nfc.nfc` (NFC) | `NfcScreen` -> VM: `NfcTagService` (`rfid read/info/ndef`, 650 s), `sendConsole` (`rfid emulate/clone/write/erase/save`), `NfcToolsService` (`nfc_ndef_push`, `nfc_emv_read`, `nfc_write_uid`, ...), `MifareKeyCheckService`. Gating `nfc`. Firmware `nfc.cpp`, CLI `rfid_commands.cpp`. Disconnect entries `nfc-tag`, `nfc`, `mifare`. | HIGH: the "Read NDEF" button sends `rfid ndef`, which WRITES the Brucia URL onto the tag on the reader. HIGH (link): `lastFrame` was set when a frame arrived, so a bounded command that blocks for about 60 s (`nfc_ndef_push`, `rfid emulate`, TV-B-Gone) tripped the 60 s idle exit right after its ACK, and the device left companion mode. MED-HIGH: `nfc_emv_read` waited for ESC at the device with no bound. A cancelled wait returned `parsed = true` (empty card reported as read). MED: NDEF push silently cut URL/text at 99/96 bytes and still ACKed "emulated"; the contact hint said "name,phone or vCard" but the firmware splits on `;`. MED: Erase/Write had no confirmation. LOW: the "Emulate tag" text overclaimed (PN532: partial UID, NDEF only). | Yes. The button is "Write NDEF URL..." with a confirmation dialog; Erase asks first. `lastFrame` is refreshed after each handled frame. EMV: `setHeadlessWait(20 s, companionPollStop)` and a cancel is not parsed. NDEF push: firmware 400 over 99/96/200 bytes, app `ndefPayloadProblem` (UTF-8 bytes, single line), hint "Name;+phone". Emulate text states the limits. Tests: `RadioAuditTextTest.nfcNdefButtonSaysItWritesAndAsksFirst`, `NfcToolsServiceTest.pushNdefRefusesWhatTheFirmwareWouldTruncate`, `tools/test_radio_guards.py` (EMV bound and cancel, idle exit). | Clone/Write/Emulate results show only in the console (no status line). Batch 18 fixed: the contact record size (126-byte PN532 limit) is checked first. NDEF push/emulate Stop only stops the app wait (about 60 s on the device). | H5, H7, H8, H14 |
| `spam.spamnfc` (Spam NFC) | `SpamScreen(filter = "nfc")` renders the same `NfcNdefPushCard` -> `vm.nfcNdefPush` -> `nfc_ndef_push`. | As NFC: truncation, contact format, about 60 s block (idle exit). | Yes, through the shared card and service (same tests). | As NFC (Stop). | H8 |
| `bleir.ir` (IR) | `IrScreen`: parsed buttons give `ir tx <proto> <addr> <cmd>` (CLI); raw gives `ir_tx_raw <khz> <csv>` (LITE: `ir tx_raw`). Actions `ir_tvbgone`, `ir_jammer` bursts, `ir_rx`. Gating `ir` plus the `ir_cmds` feature. Firmware `infrared.cpp`, CLI `ir_commands.cpp`, `custom_ir.cpp`. Cleanup `ir-receive`, `tvbgone`, jammer leave. | MED-HIGH (F10 TX side): `ir_tvbgone`/`ir_jammer`/`ir_tx_raw` and the CLI `ir tx*` call `checkIrTxPin()`, which opens the `loopOptions` pin menu when the TX pin is not in `IR_TX_PINS`, blocking the companion loop. MED: `ir tx` with a protocol `sendIRCommand` ignores returned success. MED: `sendDecodedCommand` AC-state parse writes past `uint8_t state[bits/8]` (stack) for a long value or a bit count wrapped in `uint8_t` (`IRSend`, `ir tx_from_file`, on-device files). MED: the app toasted "Sent"/"Replayed" before any reply. LOW: TV-B-Gone/jammer ACK ok with 0 codes; `ir_tx_raw` malloc unchecked / durations over 65535 truncated / bad kHz becomes 38; the jammer text promised an effect; `selectedRemote` is lost on rotation. | Yes. `irTxPinReady()` is checked before every companion and CLI-in-companion transmit, and `checkIrTxPin()` never opens the menu in companion mode. `irParsedProtocolSupported()` is enforced in `irTxCallback`. The AC-state length is checked before the stack buffer. Toasts say "Sending ... (device reply in the console)". Jammer text reworded. Tests: `tools/test_radio_guards.py` (pin order, protocol list equals the dispatch list, AC-state guard), `RadioAuditTextTest`. | IR sends are still fire-and-forget (a real result needs a `DeviceOperation`). Batch 18 fixed: TV-B-Gone/jammer 0-code ACK, `ir_jammer_stop` read as a start. `ir_tx_raw` parsing gaps. No Stop for TV-B-Gone; Learn not disabled while busy. `selectedRemote` uses `remember`. | H12, H13 |
| `bleir.irremote` (IR Remote) | `IrRemoteScreen` -> `captureRemoteKey` (`ir_rx`, LITE `ir rx`) / `pressRemoteKey` (`ir_tx_raw` or `ir tx`). | As IR: TX pin menu, unsupported protocol success, the status said "Sent". LOW: add/delete dialog state uses `remember`; no Stop for a pending capture. | Yes, through the IR fixes; the status reads "Sending ...". | Rotation of dialog state; capture Stop. | H12, H13 |
| `bleir.ble` (BLE) | `BleScreen` -> `setBleScan` (`ble.scan` SUB) -> `bleStart`/`bleService`/`bleStop` -> `ble.dev` events; GATT (`ble_gatt_connect/read`), iBeacon loop, media hijack (`ble_media`) on the same screen. Leave stops the scan, GATT and iBeacon. | HIGH: `ble_media` while scanning: its `NimBLEDevice::deinit(true)` deletes the scanner that `bleService()` keeps using (use after free). HIGH: `ble.scan` ON while a BLE HID session lives, then scan OFF runs `deinit(true)`, which frees the HID objects; later `ble_hid_*` use freed memory. The SUB always ACKed ok (no init check). MED: GATT/BadBLE stop the scan on the device silently, so the toggle stays on with no events. LOW: iBeacon UUID not validated. | Yes. `bleStart()` returns 0 / 409 (HID owns the stack) / 503 (init), and the SUB ACKs or ERRs and sets `subBleOn` from the result. `ble_media` gives 409 while scanning. The VM `releaseBleScan()` runs before GATT/BadBLE/media. Leave also stops the BLE spam loop. Tests: `tools/test_radio_guards.py`, `RadioAuditTextTest.bleOneShotsReleaseTheScanFirst`, `StreamLeaveHooksTest`. | Batch 18 fixed: iBeacon UUID validation, `ble_ibeacon_stop` read as a start. Frame order SUB-off then CMD relies on the same-dispatcher launch order. | H9, H10 |
| `bleir.bleremote` (BLE Remote) | `BleHidScreen` -> `BleHidService` (`ble_hid_begin/status/key/type/end`, 10 s). Disconnect entry `ble-hid`; companion exit tears it down. | HIGH: scan-ON-while-HID (above). LOW: `ble_hid_begin` reports "advertising" without checking init/`begin()`. LOW: the session keeps advertising after leaving the screen and the text does not say so. | Yes for the stack ownership (scan refused while HID). | Leave text (batch 18 fixed the `ble_hid_begin` init result). | H9 |
| `bleir.badusb` (BadUSB) | `BadUsbScreen`: reference library plus BadBLE (`ble_badble <script>`, 650 s) through `BadBleService`. The app never sends USB-HID commands. Bruce CLI `badusb run_from_file/run_from_buffer` is reachable from Console/Macros. | MED: `badusb run_from_*` in companion mode runs `ducky_startKb` on the port that is the app link, with an unbounded `while (!tud_mounted())`, so the link can hang. `run_from_buffer` has no console input there. MED: BadBLE stopped the scan silently (BLE row). LOW: Stop only stops the app wait (BadBLE runs to its end); not stated in the UI. | Yes. Both CLI callbacks refuse in companion mode with a reason (standalone unchanged). BadBLE releases the scan first. Tests: `tools/test_radio_guards.py` `test_badusb_refused_in_companion_mode`. | BadBLE Stop wording. | H11 |
| `spam.spamble` (Spam BLE) | `SpamScreen(filter = "ble")` / `BleScreen` cards -> `startBleSpam` burst loop (`ble_spam_platform <target> 8 <copies>`, 12 s). Batch 16 already: 409/500 surfaced, honest texts. | LOW/MED: leaving the screen did not stop the loop, so it kept broadcasting with no visible Stop (other loops stop on leave). | Yes. `LeaveEffect { vm.stopBleSpam() }` on SpamScreen (when BLE is shown) and in BleScreen's leave. Test: `StreamLeaveHooksTest`. | None (batch 18: the Wi-Fi beacon spam loop on the same hub also stops on leave). | none (no TX check) |

## Firmware changes that need a build

All software-verified only. One build covers all of them:

- `src/core/serial_commands/rf_commands.cpp`, new `src/core/serial_commands/cli_number.h` (F03, bounds, TX result).
- `src/modules/rf/protocols/rf_encoder.cpp` (bits > 64 refused).
- `src/companion/subghz.cpp`, new `src/companion/subghz_busy.h` (CC1101 409 guard, `subghz_config` result).
- `src/companion/companion.cpp` (`lastFrame` after handling; `ble.scan` SUB result).
- `src/companion/bluetooth.cpp`, `bluetooth.h` (`bleStart` codes, `ble_media` vs scan).
- `src/companion/network.cpp` (`lora_recv` stop poll).
- `src/companion/nfc.cpp`, `src/modules/rfid/emv_reader.cpp`, `emv_reader.hpp` (EMV bound and cancel, NDEF push limits).
- `src/companion/system.cpp` (ESP-NOW mode).
- `src/companion/infrared.cpp`, `src/modules/ir/TV-B-Gone.cpp`, `custom_ir.cpp`, `custom_ir.h`, `src/core/serial_commands/ir_commands.cpp` (TX pin, protocol, AC state).
- `src/core/serial_commands/badusb_commands.cpp` (companion refusal).

Build notes: `cli_number.h` and `subghz_busy.h` are pure C++ (host-compiled with `-Wall -Wextra -Werror`). No new `try`/`catch` was added, so `tools/eh_catch_guard.py` is unaffected. `badusbRefusedInCompanion` is LITE-guarded to avoid an unused-function warning. `irTxPinReady`/`irParsedProtocolSupported` are declared in `custom_ir.h`. Check the build for `WiFi.enableSTA` and `WIFI_MODE_NULL`/`WIFI_MODE_AP` (arduino-esp32 3.x).

## Hardware checks (not run; prefer the rejection paths, which transmit nothing)

- H1 (F03): from the app console:
  - `subghz tx 445533 abc 174 10`, then `subghz tx 1 433920000 0 4000000000`, then `subghz txp Princeton 433920000 65 1 0 10`, then `subghz txp Princeton 433920000 24 1 -5 10`.
  - Each returns ok:false with "invalid frequency/count/bits/te ...", no reboot, and the next `info`/PING is answered.
  - The radio is never initialized, so nothing is transmitted. Before the fix the first case aborted the device.
- H2 (F03): `subghz keeloqtx CAME 433920000 16 0 1`, then `subghz keeloqtx CAME 433920000 1 1FFFFFFF 1`, then `RfSend {"Data":"0x1","Bits":65}`.
  - Each returns ok:false with a reason. Nothing is transmitted.
- H3 (CC1101 owner, receive only):
  - Turn Listen on (`subghz.raw`), then send `rf rx 433920000`. Expect ERR 409 "sub-ghz receiver busy". Raw events keep arriving (press a remote you own near it).
  - `subghz mfcodes list` is still answered.
  - Turn Listen off; `rf rx 433920000` is accepted and ends after about 11 s.
- H4: start Spectrum with the defaults. The labels read 300.00-348.00 MHz.
- H5 (idle exit, receive only): run `jam_detect 70` from the app console (passive, polls stop).
  - After its ACK, PING and a CMD `diag` are answered without a new HELLO, and the device screen still shows companion mode.
  - On the old firmware the device left companion mode right after the ACK.
- H6 (LoRa, receive only):
  - Without a module: `lora_recv 5` gives ok:false "run lora_config first".
  - With a module: send `lora_config 868`, then `lora_recv 60`. A PING after 30 s gets a PONG. Pressing Stop in the app gives the ACK "received 0 packet(s) (stopped)" within about 1 s, and the app does not show "device restarted?".
- H7 (NFC write confirm): put a scratch NTAG with known content on the reader and tap "Write NDEF URL...".
  - A dialog appears. Cancel leaves the tag unchanged (check with a phone reader app).
  - Only "Write" changes it.
- H8 (NDEF limits): `nfc_ndef_push url <150-char URL>` gives ERR 400 "url too long (max 99 bytes)" before the PN532 starts. No emulation.
- H9 (BLE owner):
  - Send `ble_hid_begin`, then SUB `ble.scan` on. Expect ERR 409 and the toggle back off.
  - `ble_hid_status` is still answered.
  - Then `ble_hid_end`; scan on now works.
- H10 (BLE media): with the scan on, `ble_media AA:BB:CC:DD:EE:FF` from the console gives ERR 409. Scan events continue and PING is answered. No connection is attempted.
- H11 (BadUSB): `badusb run_from_file /x.txt` from the app console gives ok:false "badusb is not available in companion mode ...". The link stays up.
- H12 (IR TX pin):
  - `settings irTx <a GPIO not in the board IR TX list>`, then `ir_tvbgone`, then `ir tx NEC 04000000 08000000`.
  - Each gives ok:false "IR TX pin not configured". No pin menu on the device, nothing transmitted.
  - Restore the pin afterwards.
- H13 (IR rejection): `ir tx FOO 00000000 00000000` gives ok:false "Unsupported IR protocol: FOO".
  - After the build only: `IRSend {"Protocol":"DAIKIN","Bits":16,"Data":"11 22 33 44 55"}` gives ok:false and no reset. It is rejected before sending.
- H14 (EMV bound): `nfc_emv_read` with no card returns ok:false "no EMV card present" after about 20 s.
  - Repeat and press Stop after 3 s: it ends early.
  - The next command is answered.
- H15 (ESP-NOW owner): join the device to a network you own (`wifi_connect`), then `espnow_send x`.
  - The reply says "queued ... (delivery not confirmed)" and the device stays joined.
  - This sends one ESP-NOW broadcast frame; run it only where that is allowed.
- H16 (De Bruijn real sweep - THE ONLY STEP THAT PROVES IT OPENS ANYTHING; batch 18, TRANSMITS):
  This transmits a brute-force sweep. Run it ONLY against a receiver you own, on a frequency and in a
  place where you are authorized; a De Bruijn sweep is designed to exercise every fixed code.
  - Preconditions: a T-Embed-CC1101 (or a board whose CAPS report `cc1101`) in companion mode; a
    device you own that reacts to a known fixed-code OOK remote (e.g. a bench gate/relay receiver or
    an OOK remote plus an SDR/second CC1101 to watch), tuned to the remote's frequency (e.g.
    433.92 MHz) and its pulse width `te` (commonly 300-400 us).
  - In the app, Sub-GHz screen, De Bruijn card: pick that frequency, set the order to the remote's
    code length (<=16; e.g. 12 for a 12-bit PT2262-class remote), set `te` to match the remote, and
    press "Transmit de Bruijn". The status shows "burst i/N ... " advancing to a final
    "sweep sent: N/N bursts". Expect the owned receiver to actuate at least once during the sweep
    (the window carrying its exact code), and the ACK count of edges per burst to be non-zero.
  - With a receiver/SDR, confirm the on-air waveform is OOK at the chosen `te`, with HIGH for the
    `+te` durations and LOW for `-te` (the sign convention this planner encodes), and that bursts are
    separate transmissions with a short gap between them.
  - Stop mid-sweep: press Stop (or unplug) between bursts; streaming halts and no further burst is
    sent (the status stops advancing). Order > 16 is refused with a reason and transmits nothing.
  - Caveat: `subghz_tx_raw` ACKs ok without a real TX result (`sendRfCommand` is void), so a green
    ACK alone does NOT prove radiation - only an owned receiver reacting or a scope/SDR capture does.
    Host/JVM tests prove the planned durations and the burst sequence; they cannot prove emission.

## Batch 18 follow-ups (branch agent18/STOPS, software only)

The same limits as above apply: no firmware build, no serial port and no emulator. Each change is a
rejection, an exact match or an honest result. Nothing new is transmitted.

| Item | Change | Test |
|---|---|---|
| Stop read as start (all dispatchers) | Every companion verb is now matched exactly (`== "v"` or `startsWith("v ")`). The bare prefixes were `ir_jammer`, `ble_ibeacon`, `speaker_volume`, `jam_detect`, `evil_portal_start`, `wifi_disconnect`, `wifi_status`, `wifi_get_mac`, `responder`, `karma` and `brucegotchi`. Effects before the fix: `ir_jammer_stop` started a jam window, `ble_ibeacon_stop` a broadcast, `jam_detect_stop` a detection window, `speaker_volume_stop` set the volume to 100 %, and `evil_portal_start_x` started the portal. Idle-stop guards (`ACK ok "not running"`) were added for `jam_detect_stop`, `ir_jammer_stop` (full and LITE) and `ble_ibeacon_stop`. Other `<verb>_<suffix>` words reach the CLI, which refuses them. | `tools/test_companion_dispatch_stops.py`: replays the first-match order through `commands.cpp` and every dispatcher of `handlers[]`, for the full and the LITE build. Candidates: the app stops, the firmware stop verbs, and `<verb>_stop` / `_zz` / `_end` / `_x 5` for every verb. It also checks that no real verb is shadowed and that the namespace handlers (`ble_hid_`, `fs_upload_`) reject unknown sub-verbs. On base it finds 59 problems per build. 9 mutation checks. `tools/test_companion_wifi_dispatch.py` was updated, and the mock mirrors the idle stops. |
| `subghz_tx_raw` / `subghz_replay` result | `sendRfCommand` and the raw/bit/code TX wrappers return the transmit result (Bruce callers ignore it), and the RAW timings `calloc` is checked. `rf_raw_emit` returns whether the radio initialized. `subghz_tx_raw` answers `ok:false` "tx failed: 0 of n edges sent". `subghz_replay` counts the repeats sent: `ok:false` for 0, otherwise the ACK says "(done/repeats repeats)". | `tools/test_radio_guards.py` `Batch18Firmware` (static). |
| TV-B-Gone / IR jammer | `ok:false` "TV-B-Gone sent 0 codes" / "ir jammer sent 0 pkts". The success texts give the count sent. | `Batch18Firmware.test_ir_zero_codes_is_not_ok`, `TvBGoneServiceTest`. |
| iBeacon UUID | Firmware `ibeacon_uuid.h` accepts only the canonical 8-4-4-4-12 form; anything else gets ERR 400 before the stack is touched. The app `IbeaconUuid` refuses the same before the burst loop starts. | Host-compiled `Batch18Headers.test_ibeacon_uuid` (2 mutations), `IbeaconUuidTest`. |
| `ble_hid_begin` | Before answering "advertising" it checks `NimBLEDevice::init` (503) and that the HID advertiser runs after `begin()` (500, and the session is torn down). | `Batch18Firmware.test_ble_hid_begin_checks_init_and_advertising`. |
| NDEF contact size | `ndef_contact.h` builds the vCard and measures the text/vcard record. Over 126 bytes gets ERR 400 before the reader starts. The app's `ndefPayloadProblem` uses the same size. | Host-compiled `Batch18Headers.test_ndef_contact` (1 mutation), `NfcToolsServiceTest.contactRecordSizeMatchesTheFirmwareVcard`. |
| Wi-Fi beacon spam on leave | `SpamScreen` has `LeaveEffect { vm.stopBeaconSpam() }` when the Wi-Fi section is shown. | `StreamLeaveHooksTest`. |
| CC1101 409 bypass | `subghz_busy.h` compares case-insensitively and classifies every newline/`;;` segment, which SimpleCLI runs as separate commands. Quotes and backslashes are ignored. A bare radio verb in a later segment counts once `rf`/`subghz` appears. | Host-compiled classifier (new upper-case and segment cases, 2 new mutations), the mock mirror, and a mock 409 for `RF RX`. |
| F03 remainder | `tone`/`beep` (0..20000 Hz, 0..10000 ms, defaults "500") and `screen color hex` (24-bit) / `rgb` (0..255) parse through `clinum` before any side effect. No `std::sto*` is left in `src/core/serial_commands/` or `src/companion/`. | `tools/test_firmware_cli_number.py` `SoundScreenCliCallbacks` and `NoThrowingConversionInCliOrCompanion`; the driver shows the legacy `std::stoul` behavior. |

Firmware files that need a build for batch 18:

- `src/companion/`: `wifi.cpp`, `infrared.cpp`, `bluetooth.cpp`, `audio.cpp`, `subghz.cpp`, `subghz_busy.h`, `nfc.cpp`
- new `src/companion/ibeacon_uuid.h` and `ndef_contact.h`
- `src/modules/rf/`: `rf_send.cpp`/`.h`, `emit.cpp`/`.h`
- `src/core/serial_commands/`: `sound_commands.cpp`, `screen_commands.cpp`

`sendRfCommand`, `rfTransmitCode`/`RawBits`/`RawTimings` and `rf_raw_emit` changed from `void` to
`bool`; existing callers ignore the result.

Hardware checks (not run; the rejection paths come first):

- H16, nothing transmitted:
  - On an idle device, send `ir_jammer_stop`, `ble_ibeacon_stop` and `jam_detect_stop`. Each answers ok "not running" at once, with no IR LED activity, no iBeacon in a scanner app and no monitor-mode window.
  - `speaker_volume_stop` gives ok:false (unknown command). `speaker_volume 30` still sets 30 %.
- H17: `ble_ibeacon 2 xyz` gives ERR 400 and no advertisement. `nfc_ndef_push contact <80-char name>` gives ERR 400 before the PN532 starts.
- H18: with Listen on, `RF RX 433920000` and `subghz mfcodes list;;subghz rx` give ERR 409, and raw events continue.
- H19: `beep abc`, `tone 99999999999999999999 1` and `screen color hex zz` each give ok:false with a reason. No reboot, and the next PING is answered.

## Summary

| | |
|---|---|
| Features audited | 13 |
| Defects fixed | HIGH 6:<br>- F03 RF parser abort, plus the bits/repeat encoder abort<br>- NFC "Read NDEF" writing the tag<br>- companion idle exit after a ~60 s command<br>- `ble_media` freeing the live scanner<br>- scan ON under a live HID session<br>- (with F03) the `.sub` bits abort<br><br>MED 13:<br>- CC1101 single owner<br>- spectrum units<br>- replay protocol<br>- LoRa Stop and watchdog<br>- EMV unbounded wait and false read<br>- NDEF truncation<br>- BLE scan state after GATT/BadBLE<br>- BadUSB on the app link<br>- IR TX pin menu<br>- IR unsupported protocol success<br>- IR AC-state stack overflow<br>- ESP-NOW taking the Wi-Fi radio<br>- premature "Sent"<br><br>Plus LOW wording and result fixes. |
| Open (software) | Listed in the table and backlog F21. Main ones:<br>- FM I2S ownership<br>- NRF24 stop polling<br>- fire-and-forget IR/RF result display<br>- batch 18 closed the `subghz_tx_raw`/`replay` result, the remaining F03 callers and the items in "Batch 18 follow-ups" |
| Verification run | Host:<br>- `python3 -m unittest discover -s tools -p 'test_*.py'`: 227 OK, 2 skipped<br>- `tools/test_protocol_e2e.py`: E2E PASS<br>- `tools/test_android_logic.py`: 248 OK<br>- `tools/test_firmware_cli_number.py`: 11 OK<br>- `tools/test_radio_guards.py`: 17 OK<br><br>Android (`--max-workers=2`):<br>- `testDebugUnitTest`: 971 tests, 1 failure, which was a hang in the test harness of the new LoRa stop test, fixed and re-run green (`LoraServiceTest` 11/11)<br>- `lintDebug`, `verifyPaparazziDebug`, `assembleDebug`: BUILD SUCCESSFUL<br><br>Base check: run against base `src`, the new firmware tests fail (11/16 guards, 8/11 F03), so they detect the defects. |
| Not verified | Any device behavior. The firmware is not built and H1-H15 are not run. |
