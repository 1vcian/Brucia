# Brucia Android app - interactive controls test checklist

This is a **living hardware-test checklist**. It lists every interactive control (buttons,
toggles, sliders, dropdowns, tappable tiles/rows, text fields with an action, etc.) in the
Brucia Android companion app, grouped by home category and screen in navigation order.

How to use it:

- Mark `[x]` in the **Tested** column once the control has been exercised against real
  hardware (a connected device, or - for offline tools - verified on the phone).
- Put anything useful in **Notes**: firmware/board tested on, bugs found, "needs re-test",
  dates, tester initials, etc.
- Controls were enumerated directly from the source (`MainActivity.kt` plus the standalone
  `*Screen*.kt` files). When the code adds/removes a control, update this file to match.
- Many screens repeat the same "Connect over USB" / "Reconnect over USB" button shown only
  while disconnected; it is listed once per screen.

Source files: `android/app/src/main/java/app/brucia/MainActivity.kt` and the standalone screen
files (`AudioScreens.kt`, `MitmUi.kt`, `NetworkScanScreen.kt`, `WidsScreen.kt`,
`RfFingerprintScreen.kt`, `RfTimelineScreen.kt`, `EvilPortalScreen.kt`,
`ChannelAnalyzerScreen.kt`, `RfMap3dScreen.kt`, `BugSweepScreen.kt`, `PcapDissectScreen.kt`,
`GeoSignalsScreen.kt`).

---

# App chrome (always on screen)

## Top status bar (`TopStatusBar`)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | Back arrow (shown when a tool is open) | Returns to the home dashboard | |
| [ ] | Brucia logo + name (tappable) | Go home, reset the category filter | |
| [ ] | Connection pill ("Tap to connect" / "Connecting" / "Connected") | When not connected, tapping starts the USB connect flow | |
| [ ] | Help "?" icon button | Opens the Help & support dialog | |

## Bottom navigation (`BottomNav`)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | Device button (memory icon, shows update dot) | Opens Device info (tab 6) | |
| [ ] | Home button | Go home, reset category filter | |
| [ ] | Cmds button (terminal icon, running-count badge) | Opens the Command log dialog | |

## Home dashboard (`HomeDashboard`)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button (when disconnected) | Starts USB connect | |
| [ ] | "Buy the LilyGo T-Embed CC1101" outlined button (when disconnected) | Opens the AliExpress purchase link | |
| [ ] | "Search tools" text field | Live-filters tools; shows matching tool cards | |
| [ ] | Category tiles (one per category, tappable) | Open the category's tool list (or open the single tool directly for a 1-tool category) | Repeated per category |
| [ ] | Theme tile | Opens the Theme screen (tab 36) | |
| [ ] | Wiki tile | Opens the wiki in the browser | |
| [ ] | Support tile | Opens the Support dialog | |
| [ ] | Tool cards (one per tool, tappable) | Open that tool's screen | Repeated per tool; also used in search results |
| [ ] | Support dialog "Close" button | Dismiss | |

## Support card (`SupportCard`, in Support dialog, Help dialog, and dashboard footer)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Sponsor on GitHub" button | Opens the GitHub Sponsors link | |
| [ ] | "Buy me a coffee" outlined button | Opens the donation link | |
| [ ] | Bitcoin "Copy address" button | Copies the BTC address to the clipboard | |
| [ ] | Monero "Copy address" button | Copies the XMR address to the clipboard | |

## Command log dialog (`CommandLogDialog`)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Stop" text button (when a command is running) | `vm.stopAllRunning()` | |
| [ ] | "Clear" text button (when entries exist) | `vm.clearCommandLog()` | |
| [ ] | "Close" text button | Dismiss | |

## Help dialog (`HelpDialog`)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Close" text button | Dismiss | |
| [ ] | "Open the wiki" button | Opens the wiki in the browser | |
| [ ] | (embeds Support card) | Sponsor / Buy me a coffee / Copy BTC / Copy XMR | See Support card above |

---

# Wi-Fi

## Wardrive (`BruciaScreen`, tab 0)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Wardriving" switch | `vm.setWardriving()` - start/stop the `wifi.ap` stream | |
| [ ] | "CSV" button | Build WiGLE CSV, write file, share | |
| [ ] | "KML" button | Build KML, write file, share | |
| [ ] | "GPX" button | Build GPX from track, write file, share | |
| [ ] | "Clear" outlined button | `vm.clearSession()` | |
| [ ] | List / Map / Stats segmented selector | Switch dashboard view | |
| [ ] | Sort selector (Stable / Name / Signal / Risk) | Set AP list sort order | Shown only when list non-empty |
| [ ] | AP card (tappable row) | Expand/collapse to reveal per-AP actions | Repeated per AP |
| [ ] | "Capture + crack" button (per AP) | `vm.captureAndCrack()` then jump to Crack tab | Repeated per AP |
| [ ] | "PMKID (clientless)" button (per AP) | `vm.capturePmkidAndCrack()` then jump to Crack tab | Repeated per AP |
| [ ] | "Deauth (continuous)" / "Stop deauth" button (per AP) | `vm.startDeauth()` / `vm.stopDeauth()` | Repeated per AP |
| [ ] | WiGLE "API Name" field | Set API name (seeded from saved value) | |
| [ ] | WiGLE "API Token" field (password) | Set API token | |
| [ ] | "Upload to WiGLE" button | `vm.uploadToWigle()` | Enabled with data + both fields |
| [ ] | Beacon spam "SSID list" field (multiline) | Set fake-AP SSID list | |
| [ ] | Beacon spam "Number of fake APs (1-256)" field | Set copies | |
| [ ] | "Start beacon spam" / "Stop beacon spam" button | `vm.startBeaconSpam()` / `vm.stopBeaconSpam()` | |

## Crack (`CrackScreen`, tab 7)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Dismiss" text button | `vm.resetHandshakeFlow()` | Shown for an inactive handshake flow |
| [ ] | "Built-in list" outlined button | `vm.crackBuiltin()` | |
| [ ] | "Demo" outlined button | `vm.demoCrack()` | |
| [ ] | "Stop" outlined button | `vm.cancelCrack()` | Enabled while cracking |
| [ ] | "Apply mangling rules (expand wordlist)" switch | `vm.setRules()` | |
| [ ] | "Manual import (fallback)" Show/Hide header row | Toggle the manual-import section | |
| [ ] | "Import .pcap" button | Pick a .pcap -> `vm.importPcap()` | |
| [ ] | "Wordlist" button | Pick a file -> `vm.importWordlist()` | |
| [ ] | "Device .pcap path" field | Set the device path to pull | |
| [ ] | "Pull" outlined button | `vm.pullFile()` | |
| [ ] | "List captures" outlined button | List `/BrucePCAP/handshakes` | |
| [ ] | PMKID / hashcat-22000 field | Set the hash to load | |
| [ ] | "Load target" outlined button | `vm.importPmkid()` | Enabled when field non-blank |
| [ ] | "Export hashcat 22000" outlined button | `vm.exportHashcat()` + share | |
| [ ] | Catalog row trailing button (Download / Cancel / Crack) | `vm.downloadWordlist()` / `vm.cancelDownload()` / `vm.crackWithWordlist()` | Repeated per catalog entry, state-dependent |
| [ ] | Bundled wordlist "Run" button | `vm.crackBundled()` | Repeated per bundled list |
| [ ] | Imported wordlist "Run" button | `vm.crackWithWordlist()` | Repeated per imported list |
| [ ] | Imported wordlist "Del" button | `vm.deleteWordlist()` | Repeated per imported list |

## Sniffer (`StationSnifferScreen`, tab 11)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Start" / "Stop" button | `vm.startStationSniff()` / `vm.stopStationSniff()` | |
| [ ] | "Export CSV" outlined button | `vm.exportStationsCsv()` + share | |
| [ ] | "Clear" outlined button | `vm.clearStations()` | |

## Probes (`WifiProbeScreen`, tab 15)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Start" / "Stop" button | `vm.startProbeSniff()` / `vm.stopProbeSniff()` | |
| [ ] | "Export CSV" outlined button | `vm.exportProbesCsv()` + share | |
| [ ] | "Clear" outlined button | `vm.clearProbes()` | |
| [ ] | "Group by SSID" / "Group by client" segmented selector | Regroup the probe view | |

## Monitor (`WifiMonitorScreen`, tab 17)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | Channel selector 1-13 | Set capture channel | Scrollable segmented group |
| [ ] | "Pick target AP" / "Hide APs" outlined button | Toggle the scanned-AP picker | |
| [ ] | "Clear" text button (next to target) | Clear the chosen target | Shown when a target is set |
| [ ] | AP picker rows (tappable) | Set target BSSID/SSID/channel | Repeated per scanned AP |
| [ ] | "Your network password (PSK)" field (password) | PSK for Wireshark export / PMK | |
| [ ] | "Start" / "Stop" button | `vm.startMonitor()` / `vm.stopMonitor()` | |
| [ ] | "Export pcap" outlined button | `vm.exportMonitorPcap()` + share | |
| [ ] | "Open pcap" outlined button | `vm.exportMonitorPcap()` + open-with | |
| [ ] | "Clear" outlined button | `vm.clearMonitor()` | |
| [ ] | Frame rows (tappable) | Toggle the hex-dump detail | Repeated per frame |
| [ ] | "Detect jamming (15s)" button (`JamDetectCard`) | `vm.jamDetect(15)` | |

## Evil Portal (`EvilPortalScreen`, tab 30)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Fake AP name (SSID)" field | Set portal SSID | Disabled while active |
| [ ] | Captive-page selector (Device default / Wi-Fi login / Firmware update / Custom) | Choose template | Radio rows |
| [ ] | "Edit" outlined button | Show the HTML editor | Custom only |
| [ ] | "Preview" outlined button | Render HTML in a sandboxed WebView | Custom only |
| [ ] | Captive-page HTML field | Edit custom HTML | Custom edit mode |
| [ ] | "Save as" field | Set template name | Custom mode |
| [ ] | "Save" button | `vm.saveCustomPortal()` | Custom mode |
| [ ] | Saved-template "<name>" button | Load template HTML | Repeated per saved template |
| [ ] | Saved-template "Del" button | `vm.deleteCustomPortal()` | Repeated per saved template |
| [ ] | "Deauth clients (evil twin)" switch | Toggle evil-twin deauth | Disabled while active |
| [ ] | "Channel (1-14)" field | Set channel | |
| [ ] | "Clone a nearby network" / "Hide networks" outlined button | Toggle target list; starts/stops scan | |
| [ ] | Target network rows (tappable) | Copy SSID/channel into fields, enable deauth | Repeated per scanned AP |
| [ ] | "Start Evil Portal" / "Start evil twin" / "Stop Evil Portal" button | `vm.startEvilPortal()` / `vm.stopEvilPortal()` | Label varies |
| [ ] | "Clear" text button | `vm.clearEvilCreds()` | Shown when credentials captured |

## Channels (`ChannelAnalyzerScreen`, tab 26)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | Only interactive control; scanning auto-starts/stops. Rest is a read-only congestion chart |

## MAC (`MacScreen`, tab 33)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Refresh" outlined button | `vm.getWifiMac()` | |
| [ ] | MAC address field | Set the MAC to apply | |
| [ ] | "Set MAC" button | `vm.setWifiMac()` | Enabled when field non-blank |
| [ ] | "Randomize" outlined button | `vm.setWifiMac("random")` | |

## Connect (`WifiJoinScreen`, tab 34)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "SSID" field | Set SSID | |
| [ ] | "password (blank = open)" field | Set password | |
| [ ] | "Connect" button | `vm.wifiJoin()` | Enabled when SSID non-blank |
| [ ] | "Status" outlined button | `vm.wifiStatus()` | |
| [ ] | "Disconnect" outlined button | `vm.wifiLeave()` | |

## Karma (`KarmaScreen`, tab 38)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | Duration selector 30s / 60s / 120s / 300s | Set run duration | |
| [ ] | "Start (Ns)" button | `vm.startKarma()` | Shown when idle |
| [ ] | "Stop" button | `vm.stopLongOp()` | Shown when running |

## Brucegotchi (`BrucegotchiScreen`, tab 35)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | Duration selector 30s / 60s / 120s / 300s | Set run duration | |
| [ ] | "Start (Ns)" button | `vm.startBrucegotchi()` | Shown when idle |
| [ ] | "Stop" button | `vm.stopBrucegotchi()` | Shown when running |

---

# Sub-GHz

## RF (`SubGhzScreen`, tab 2)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Listen" switch | `vm.setSubghz()` - `subghz.raw` stream | |
| [ ] | Spectrum "Start" / "Stop" button | `vm.startSpectrum()` / `vm.stopSpectrum()` | |
| [ ] | Spectrum "Clear" outlined button | `vm.clearSpectrum()` | Shown when history non-empty |
| [ ] | Jammer "Start jammer" / "Stop jammer" button | `vm.startSubghzJammer()` / `vm.stopSubghzJammer()` | |
| [ ] | Brute-force protocol selector | Choose protocol | Toggle-button group (BRUTE_PROTOCOLS) |
| [ ] | Brute-force "Seconds (1-30)" field | Set duration | |
| [ ] | "Start brute force" button | `vm.subghzBruteforce()` | |
| [ ] | "Record to CSV" Start / Stop button | `vm.startRfCapture()` / `vm.stopRfCapture()` | |
| [ ] | "Export / Share CSV" button | `vm.exportRfCsv()` + share | |
| [ ] | Frequency selector (315 / 433.92 / 868.35 / 915 MHz) | Set frequency | |
| [ ] | "Capture & save (rf rx)" button | `vm.rfCapture()` | |
| [ ] | "Decode sample" outlined button | `vm.decodeSample()` | |
| [ ] | "Clear" outlined button | `vm.clearSubghz()` | Enabled when signals exist |
| [ ] | De Bruijn frequency selector | Set frequency | |
| [ ] | De Bruijn code-length selector (8/10/12/16/20/24) | Set bits | |
| [ ] | De Bruijn "Custom bits (4-24)" field | Set bits | |
| [ ] | De Bruijn pulse-width selector (200/300/400/500us) | Set te | |
| [ ] | De Bruijn "Custom te (50-2000 us)" field | Set te | |
| [ ] | "Transmit de Bruijn" button | `vm.transmitDeBruijn()` | |
| [ ] | "Import .sub" outlined button | Pick a .sub -> `vm.importSub()` | |
| [ ] | "Export .sub" outlined button | `vm.exportSub()` + share | Enabled when signals/wave exist |
| [ ] | "Transmit" button | `vm.transmitSub()` | Shown after a .sub import |
| [ ] | Saved signal "Replay" button | `vm.replaySaved()` | Repeated per saved signal |
| [ ] | Saved signal "Delete" button | `vm.deleteSaved()` | Repeated per saved signal |

---

# Radio

## FM (`FmScreen`, tab 14)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Frequency (kHz, 87500-108000)" field | Set transmit frequency | Disabled while streaming |
| [ ] | Audio-source selector (File / Tone / Phone mic / Device mic) | Choose source | Radio rows, disabled while streaming |
| [ ] | "Tone frequency (Hz)" field | Set tone frequency | Shown when Tone selected |
| [ ] | "Start transmitting" / "Stop" button | Start per source (`vm.fmStartFile/Tone/PhoneMic/DeviceMic`) or `vm.fmStop()` | Phone mic prompts for RECORD_AUDIO |

## LoRa (`LoRaScreen`, tab 22)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Frequency (MHz)" field | Set frequency | |
| [ ] | "BW" field | Set bandwidth | |
| [ ] | "SF" field | Set spreading factor | |
| [ ] | "CR" field | Set coding rate | |
| [ ] | "Apply config" button | `vm.loraConfig()` | |
| [ ] | "Message" field | Set message | |
| [ ] | "Send" button | `vm.loraSend()` | |
| [ ] | "Duration (seconds)" field | Set receive duration | |
| [ ] | "Start receive" / "Stop" button | `vm.loraRecv()` / `vm.stopLoraRecv()` | |
| [ ] | "Clear" outlined button | `vm.clearLoraPackets()` | Enabled when packets exist |

---

# 2.4GHz

## NRF24 (`Nrf24Screen`, tab 13)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | Scanner "Start scan" / "Stop" button | `vm.startNrf24Scan()` / `vm.stopNrf24Scan()` | |
| [ ] | Sniffer "Start sniff" / "Stop" button | `vm.startNrf24Sniff()` / `vm.stopNrf24Sniff()` | |
| [ ] | Sniffer "Clear" outlined button | `vm.clearNrf24Devices()` | Enabled when devices exist |
| [ ] | Jammer "Duration (seconds)" field | Set duration | |
| [ ] | Jammer "Start" / "Stop" button | `vm.startNrf24Jammer()` / `vm.stopNrf24Jammer()` | |
| [ ] | MouseJack "Duration (seconds)" field | Set duration | |
| [ ] | MouseJack "Inject text (optional)" field | Set inject text | |
| [ ] | MouseJack "Scan + inject" / "Stop" button | `vm.startNrf24MouseJack()` / `vm.stopNrf24MouseJack()` | |

## ESP-NOW (`EspnowScreen`, tab 32)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | In the not-connected card |
| [ ] | "message" field | Set message | |
| [ ] | "Broadcast (ESP-NOW)" button | `vm.espnowSend()` | Enabled when connected + message non-blank |

---

# NFC

## NFC (`NfcScreen`, tab 3)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Read tag" button | `vm.nfcRead()` | |
| [ ] | "Clone tag" button | `vm.nfcClone()` | |
| [ ] | "Emulate tag" button | `vm.nfcEmulate()` | |
| [ ] | "Run key attack" button | `vm.runMifareKeyCheck()` | |
| [ ] | "Import keys" outlined button | Pick file -> `vm.importMifareKeys()` | |
| [ ] | "Clear" text button | `vm.clearMifareUserKeys()` | Shown when user keys imported |
| [ ] | "Write" outlined button | `vm.nfcWrite()` | |
| [ ] | "Info" outlined button | `vm.nfcInfo()` | |
| [ ] | "Save" outlined button | `vm.nfcSave()` | |
| [ ] | "Erase" outlined button | `vm.nfcErase()` | |
| [ ] | "Read NDEF" outlined button | `vm.nfcNdef()` | |
| [ ] | "Analyze security" outlined button | Run on-phone NFC security analysis | Enabled when a tag was read |
| [ ] | NDEF "URL" field | Set URL | |
| [ ] | "Build NDEF (share hex)" outlined button | Build NDEF TLV hex, write + share | |
| [ ] | NDEF push type selector (URL / Text / Contact) | Choose payload type (`NfcNdefPushCard`) | |
| [ ] | NDEF push payload field | Set payload | |
| [ ] | NDEF push "Send" button | `vm.nfcNdefPush()` | Enabled when connected + payload non-blank |
| [ ] | "Read bank card" (EMV) button | `vm.nfcEmvRead()` | |
| [ ] | "Hex UID" field | Set UID to write | |
| [ ] | "Write UID" button | `vm.nfcWriteUid()` | |
| [ ] | "Amiibo emulate" outlined button | `vm.nfcAmiibo("emulate")` | |
| [ ] | "Amiibo dump" outlined button | `vm.nfcAmiibo("dump")` | |
| [ ] | "Nested" outlined button | `vm.mifareNested()` | |
| [ ] | "mfkey32" outlined button | `vm.mifareMfkey32()` | |
| [ ] | "SRIX read" outlined button | `vm.srixRead()` | |
| [ ] | "125kHz read" outlined button | `vm.rfid125Read()` | |
| [ ] | Saved card "Delete" button | `vm.deleteSaved()` | Repeated per saved card |

---

# BLE & IR

## BLE (`BleScreen`, tab 5)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Scan" switch | `vm.setBleScan()` - `ble.scan` stream | |
| [ ] | BLE spam (Apple popups) card: copies field + Start/Stop | `vm.startBleSpam("ios")` / `vm.stopBleSpam()` | |
| [ ] | Fast Pair spam (Android popups) card: copies field + Start/Stop | `vm.startBleSpam("android")` / `vm.stopBleSpam()` | |
| [ ] | BadBLE "Load from library (N)" / "Hide library" outlined button | Toggle the Ducky payload picker | |
| [ ] | BadBLE payload list entries (tappable) | Load that script into the editor | Repeated per bundled script |
| [ ] | BadBLE "DuckyScript" field (multiline) | Edit payload | |
| [ ] | BadBLE "Run over BLE" button | `vm.bleBadble()` | |
| [ ] | GATT explorer "Close" outlined button | `vm.gattStop()` | Shown once a device is picked |
| [ ] | GATT service rows (tappable) | Expand/collapse characteristics | Repeated per service |
| [ ] | GATT "Read" outlined button | `vm.gattRead()` | Repeated per readable characteristic |
| [ ] | iBeacon "Proximity UUID (optional)" field | Set UUID | Disabled while active |
| [ ] | "Broadcast iBeacon" / "Stop iBeacon" button | `vm.startIbeacon()` / `vm.stopIbeacon()` | |
| [ ] | Media "Target MAC" field | Set target MAC | |
| [ ] | "Send media hijack" button | `vm.bleMedia()` | |
| [ ] | "Clear" outlined button | `vm.clearBle()` | Enabled when devices exist |
| [ ] | Per-device "Explore" row | `vm.gattConnect()` | Repeated per discovered device |

## IR (`IrScreen`, tab 1)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | Remotes / Catalog / Raw segmented selector | Switch IR sub-tab | |
| [ ] | "< Devices" outlined button | Back to the remote picker | Remotes tab, when a remote is open |
| [ ] | "Browse all devices (N)" / "Show TV remotes" outlined button | Toggle full device browse | |
| [ ] | "Search brand / model / category" field | Filter devices | Remotes + Catalog tabs |
| [ ] | "Categories" / breadcrumb text buttons | Navigate the device breadcrumb | |
| [ ] | Device/browse rows (tappable) | Drill into category/brand/model or open a remote | Repeated |
| [ ] | On-screen remote keys (`IrRemote`) | Transmit the mapped code (`vm.irTxRaw` / `vm.sendCommand`) | POWER, MUTE, VOL +/-, CH +/-, D-pad (UP/LEFT/OK/RIGHT/DOWN), INPUT, MENU, HOME, BACK, numbers 0-9 |
| [ ] | "Catalog (N)" / "Close catalog" button | Toggle the catalog browser | Catalog tab |
| [ ] | "Import .ir" outlined button | Pick + parse an .ir file | Catalog tab |
| [ ] | "Learn a remote (ir rx)" outlined button | `vm.learnIr()` | Catalog tab |
| [ ] | "Paste Flipper .ir content" field | Paste .ir content | Catalog tab |
| [ ] | "Load" button | Parse pasted content into buttons | Catalog tab |
| [ ] | "Samples" outlined button | Load sample buttons | Catalog tab |
| [ ] | IR button card "Replay raw" outlined button | `vm.irTxRaw()` | Raw buttons only; repeated |
| [ ] | IR button card "Send" button | `vm.sendCommand()` | Repeated per button |
| [ ] | Raw tab region selector (NA / EU) | Set TV-B-Gone region (`IrActionsSection`) | |
| [ ] | "TV-B-Gone (turn off TVs)" button | `vm.irTvBGone()` | |
| [ ] | "IR jammer (flood IR)" / "Stop IR jammer" button | `vm.startIrJammer()` / `vm.stopIrJammer()` | |
| [ ] | "Learn IR (receive)" outlined button | `vm.irReceiveOnce()` | |
| [ ] | "Save to learned" button | `vm.saveLearnedIr()` | After a capture |
| [ ] | "Replay raw" outlined button | `vm.irTxRaw()` | After a raw capture |

## IR Remote (`IrRemoteScreen`, tab 37)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | Captured-key tile (tap) | `vm.pressRemoteKey()` | Repeated per captured key |
| [ ] | Captured-key tile (long-press) | Open the delete dialog | Repeated per captured key |
| [ ] | "Add button (capture)" button | Open the Add dialog | |
| [ ] | Add dialog "Button name" field | Set key label | |
| [ ] | Add dialog "Capture" text button | `vm.captureRemoteKey()` | |
| [ ] | Add dialog "Cancel" text button | Dismiss | |
| [ ] | Delete dialog "Delete" text button | `vm.deleteRemoteKey()` | |
| [ ] | Delete dialog "Cancel" text button | Dismiss | |

## BLE Remote (`BleHidScreen`, tab 39)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Start" button | `vm.bleHidBegin()` | |
| [ ] | "Refresh" outlined button | `vm.bleHidRefresh()` | |
| [ ] | "Stop" outlined button | `vm.bleHidEnd()` | |
| [ ] | Presenter keys (< Prev, Next >, Start (F5), Esc, Pg Up, Pg Dn) | `vm.bleHidKey()` | Six buttons |
| [ ] | "text to type" field | Set text | |
| [ ] | "Type" button | `vm.bleHidType()` | |

## BadUSB (`BadUsbScreen`, tab 4)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | Over-USB mode shows a "Not available over USB" info card |
| [ ] | BadBLE "Load from library" / "Hide library" outlined button | Toggle the payload picker | |
| [ ] | BadBLE payload list entries (tappable) | Load that script | Repeated |
| [ ] | BadBLE "DuckyScript" field | Edit payload | |
| [ ] | BadBLE "Run over BLE" button | `vm.bleBadble()` | |
| [ ] | Payload library cards (tappable) | Expand/collapse the payload | Repeated |
| [ ] | "Copy script" outlined button | Copy script to clipboard | Shown when a card is expanded |

---

# Spam

> The three Spam tiles (BLE / Wi-Fi / NFC) all open the same `SpamScreen` composable with a
> filter. Each BLE platform card has an identical "Copies / advertisements (1-100)" field plus
> a Start/Stop toggle button (`vm.startBleSpam(target, copies)` / `vm.stopBleSpam()`) - that
> Start/Stop pattern repeats across all BLE platform cards and is noted once below.

## Spam BLE (`SpamScreen` filter=ble, tab 49)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | Apple / iOS / Mac card: copies field + Start/Stop | `vm.startBleSpam("ios")` / stop | Start/Stop pattern (see note) |
| [ ] | Android card: copies field + Start/Stop | `vm.startBleSpam("android")` / stop | |
| [ ] | Windows / PC card: copies field + Start/Stop | `vm.startBleSpam("windows")` / stop | |
| [ ] | Samsung card: copies field + Start/Stop | `vm.startBleSpam("samsung")` / stop | |
| [ ] | All BLE platforms card: copies field + Start/Stop | `vm.startBleSpam("all")` / stop | |

## Spam Wi-Fi (`SpamScreen` filter=wifi, tab 50)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "SSID list (one per line)" field | Set fake-AP SSID list (`BeaconSpamCard`) | |
| [ ] | "Number of fake APs (1-256)" field | Set copies | |
| [ ] | "Start beacon spam" / "Stop beacon spam" button | `vm.startBeaconSpam()` / `vm.stopBeaconSpam()` | |

## Spam NFC (`SpamScreen` filter=nfc, tab 51)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | NDEF push type selector (URL / Text / Contact) | Choose payload type (`NfcNdefPushCard`) | |
| [ ] | NDEF push payload field | Set payload | |
| [ ] | "Send" button | `vm.nfcNdefPush()` | |

---

# Intercept

## MITM (`MitmScreen`, tab 18)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | ARP spoof "Wi-Fi SSID" field | Set SSID | |
| [ ] | ARP spoof "Wi-Fi password" field | Set password | |
| [ ] | ARP spoof "Target IP" field | Set target IP | |
| [ ] | ARP spoof "Gateway IP" field | Set gateway IP | |
| [ ] | "Start ARP spoof" / "Stop ARP spoof" button | `vm.startArpSpoof()` / `vm.stopArpSpoof()` | |
| [ ] | "Listen port" field | Set proxy port | Disabled while running |
| [ ] | "Start proxy" / "Stop proxy" button | `MitmController.start()` / `.stop()` | |
| [ ] | "Export CA certificate (.crt)" outlined button | Export + share the CA cert | |
| [ ] | "Clear" outlined button | `MitmLog.clear()` | Shown when transactions exist |
| [ ] | Transaction row cards (tappable) | Open the transaction detail | Repeated per transaction |
| [ ] | Detail "Back to list" outlined button | Clear selection | |

## Network Scan (`NetworkScanScreen`, tab 31)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Scan network" / "Stop scan" button | Run / cancel the on-phone network scan | |
| [ ] | Vuln badge rows (tappable) | Open the CVE URL in the browser | Repeated per vuln |

## Responder (`ResponderScreen`, tab 40)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | Duration selector 30s / 60s / 120s / 300s | Set run duration | |
| [ ] | "Start (Ns)" button | `vm.startResponder()` | Shown when idle |
| [ ] | "Stop" button | `vm.stopLongOp()` | Shown when running |

## WIDS (`WidsScreen`, tab 44)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Window: Ns per listen" slider | Set listen window (5-30s) | Shown while inactive |
| [ ] | "Alert threshold" slider | Set deauth-frames threshold (3-100) | Shown while inactive |
| [ ] | "Start monitoring" / "Stop monitoring" button | `vm.startWids()` / `vm.stopWids()` | |
| [ ] | "Clear" text button | `vm.clearWidsEvents()` | Shown when events exist |

## RF Fingerprint (`RfFingerprintScreen`, tab 46)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Place name" field | Set fingerprint name | |
| [ ] | "Capture" button | `vm.captureFingerprint()` | Enabled when connected + emitters seen |
| [ ] | "Close" text button | Clear the active comparison | In comparison card |
| [ ] | "Compare now" button (per saved fingerprint) | Compare against this fingerprint | Repeated |
| [ ] | "Delete" outlined button (per saved fingerprint) | `vm.deleteFingerprint()` | Repeated |

## RF Timeline (`RfTimelineScreen`, tab 47)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Clear" text button | `vm.clearTimeline()` | Shown when sightings exist |
| [ ] | "Filter" field | Filter the sightings list | |

---

# Device

## Device info (`DeviceScreen`, tab 6)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | Updates shortcut card (tappable) | Open the Updates screen | Highlighted when an update is available |
| [ ] | "Device screen" switch | `vm.setDeviceScreen()` | |
| [ ] | "Device LED" switch | `vm.setDeviceLed()` | |
| [ ] | "Screen brightness: N%" slider | `vm.setScreenBright()` | Enabled when screen on |
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Info" outlined button | `vm.deviceCmd("info")` | |
| [ ] | "Memory" outlined button | `vm.deviceCmd("free")` | |
| [ ] | "Uptime" outlined button | `vm.deviceCmd("uptime")` | |
| [ ] | "I2C scan" outlined button | `vm.deviceCmd("i2c")` | |
| [ ] | "Sleep" outlined button | `vm.powerSleep()` | |
| [ ] | "Reboot" outlined button | `vm.powerReboot()` | |
| [ ] | "Generate security report" button | Build report, write file + share | |
| [ ] | "Export session JSON" button | Build JSON, write file + share | |
| [ ] | Theme/LED card controls (`ThemeLedCard`) | See the Theme screen below | Same card is embedded here |

## Console (`ConsoleScreen`, tab 8)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | CLI group selector | Choose a command group | Segmented, per `CliCatalog.groups` |
| [ ] | Per-command outlined buttons | Fill input (templated) or `vm.sendConsole()` | Repeated per command |
| [ ] | "command" field | Set the command line | |
| [ ] | "Run" button | `vm.sendConsole()` | Enabled when connected + non-blank |
| [ ] | "Clear" outlined button | `vm.clearConsole()` | |
| [ ] | "Export log" outlined button | Export the log + share | |

## Remote (`RemoteScreen`, tab 9)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Start" button | `vm.startRemote()` | |
| [ ] | "Stop" outlined button | `vm.stopRemote()` | |
| [ ] | D-pad (Up / Prev / OK / Next / Down) | `vm.remoteNav()` | Enabled while running |
| [ ] | "Back" outlined button | `vm.remoteNav("esc")` | |
| [ ] | "PrevPg" outlined button | `vm.remoteNav("prevpage")` | |
| [ ] | "NextPg" outlined button | `vm.remoteNav("nextpage")` | |

## Files (`FilesScreen`, tab 10)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Up" outlined button | Go to the parent directory | Enabled when not at root |
| [ ] | "Refresh" outlined button | Refresh the listing | |
| [ ] | "New / template" outlined button | Open the new-file dialog | |
| [ ] | "New editor" outlined button | Open a blank editor | |
| [ ] | Entry cards (tappable) | Enter a directory / open the file action sheet | Repeated per entry |
| [ ] | Per-file "Pull" outlined button | `vm.pullFile()` to the app folder | Repeated per file |
| [ ] | Action sheet "Pull (app folder)" | `vm.pullFile()` | |
| [ ] | Action sheet "Pull to... (choose destination)" | SAF create document -> `vm.pullFileToUri()` | |
| [ ] | Action sheet "Open with..." | Pull bytes + open-with intent | |
| [ ] | Action sheet "Edit" | Pull bytes + open the editor | Editable files only |
| [ ] | Action sheet "Delete" | `vm.deleteDeviceFile()` | |
| [ ] | Action sheet "Cancel" | Dismiss | |
| [ ] | New dialog "File / folder name" field | Set name | |
| [ ] | New dialog "Create empty file on device" | `vm.pushFile()` | |
| [ ] | New dialog "Create folder on device" | `vm.makeDeviceDir()` | |
| [ ] | New dialog template buttons | Load a template into the editor | Repeated per template |
| [ ] | New dialog "Cancel" | Dismiss | |
| [ ] | Editor "Close" (`FileEditorScreen`) | Close the editor | |
| [ ] | Editor "Edit" / "View" segmented selector | Toggle HTML view mode | HTML files only |
| [ ] | Editor body field | Edit text | |
| [ ] | Editor "Device path" field | Set the save path | |
| [ ] | Editor "Save to phone" | SAF create document + write | |
| [ ] | Editor "Save to device" | `vm.pushFile()` | |
| [ ] | Editor "Open with..." | Write temp + open-with intent | |

## Updates (`UpdatesScreen`, tab 12)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Check for app update" outlined button | `vm.checkUpdates()` | |
| [ ] | "Download & install <ver>" button | `vm.requestAppInstall()` | Shown when a newer APK exists |
| [ ] | "Board image" dropdown button | Open the board menu | |
| [ ] | "Auto (connected device)" dropdown item | `vm.selectBoard(null)` | |
| [ ] | Per-board dropdown items | `vm.selectBoard()` | Repeated per board |
| [ ] | "Flash latest firmware" / "Flash <board>" button | `vm.resetFlash()` + `onFlash()` | |
| [ ] | Flash log "Clear" text button | `vm.clearFlashLog()` | Shown when log non-empty |

## Ethernet (`EthernetScreen`, tab 23)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Refresh status" button | `vm.ethStatus()` | |
| [ ] | "eth_config arguments" field | Set config args | |
| [ ] | "Apply config" button | `vm.ethConfig()` | |
| [ ] | "DHCP starve" outlined button | `vm.ethDhcpStarve()` | |
| [ ] | "MAC flood" outlined button | `vm.ethMacFlood()` | |

---

# Audio

## Audio (`AudioDashboardScreen`, tab 25)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect (`AudioHeader`) | |
| [ ] | "Record" / "Stop & save" button | `vm.startRecording()` / `vm.stopRecordingAndExport()` + share (`RecordBar`) | |
| [ ] | Scope mode selector (Time / XY mono / XY 2-mic) | Switch scope rendering (`ScopePanel`) | |
| [ ] | Trigger-mode selector (Rising / Falling / Auto) | Set trigger mode | Time mode |
| [ ] | "Trigger level" slider | Set trigger level | Time mode; disabled in Auto |
| [ ] | "Time base" slider | Set samples/screen | Time mode |
| [ ] | "Vertical gain" slider | Set gain | Time mode |
| [ ] | "Delay d" slider | Set XY delay | XY mono mode |
| [ ] | "Grant mic access" outlined button | Request RECORD_AUDIO | XY 2-mic mode, when denied |

## Oscilloscope (`OscilloscopeScreen`, tab 29)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Record" / "Stop & save" button | `vm.startRecording()` / `vm.stopRecordingAndExport()` + share | |
| [ ] | Scope controls (`ScopePanel`) | Same as Audio screen scope panel | See Audio screen |

## SSTV (`SstvScreen`, tab 27)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Record" / "Stop & save" button | Record + share | |
| [ ] | Transmit mode radio list (Robot36 / Robot72 / Martin M1 / M2 / Scottie S1 / S2) | Set TX mode | Disabled while transmitting |
| [ ] | "Stop" outlined button | `vm.sstvTransmitStop()` | Shown while transmitting |
| [ ] | "Pick image and transmit" button | Pick image -> `vm.sstvTransmit()` | Needs connected + speaker |

## Tone (`ToneScreen`, tab 28)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | DTMF "Clear" outlined button | `vm.clearDtmfHeard()` | Shown when heard non-empty |
| [ ] | DTMF "Start listening" button | `vm.startDtmfDecode()` | |
| [ ] | DTMF "Stop" button | `vm.stopDtmfDecode()` | |
| [ ] | Generator "Frequency" slider | Set tone frequency (100-4000 Hz) | |
| [ ] | Generator "Duration" slider | Set duration (50-2000 ms) | |
| [ ] | Generator "Speaker volume" slider | `vm.setSpeakerVolume()` | |
| [ ] | Generator "Play tone" button | `vm.speakerTone()` | |
| [ ] | Songs RTTTL preset buttons | `vm.playSong()` / `vm.stopSong()` | Repeated per preset |
| [ ] | Songs "Stop" button | `vm.stopSong()` | Shown while playing |
| [ ] | Composer note-length selector (1/1 ... 1/16) | Set note length | |
| [ ] | Composer octave "-" / "+" | Change octave | |
| [ ] | Composer note buttons (C ... B, 12) | Append a note | Repeated per note |
| [ ] | Composer "Rest" button | Append a rest | |
| [ ] | Composer "Undo" button | Drop the last step | |
| [ ] | Composer "Clear" button | Clear steps | |
| [ ] | Composer "Tempo" slider | Set BPM (40-300) | |
| [ ] | Composer "Play" button | `vm.playSong("Composer", ...)` | |
| [ ] | Composer "Stop" outlined button | `vm.stopSong()` | |
| [ ] | Composer "Name" field | Set song name | |
| [ ] | Composer "Save" button | `vm.saveCustomSong()` | |
| [ ] | My songs play button (per song) | `vm.playSong()` / `vm.stopSong()` | Repeated per saved song |
| [ ] | My songs "Del" button (per song) | `vm.deleteCustomSong()` | Repeated per saved song |
| [ ] | DTMF keypad (16 keys: 0-9, A-D, *, #) | `vm.sendDtmf()` | Repeated per key |

---

# Tools

## Report (`ReportScreen`, tab 21)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Generate report (.md)" button | Write a Markdown report + share | Enabled when data exists |
| [ ] | "Export .txt" outlined button | Write a .txt report + share | Enabled when data exists |

## Scripts (`ScriptsScreen`, tab 19)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | Example chips | Load an example into the editor | Repeated per example |
| [ ] | "JavaScript source" field | Edit the script | |
| [ ] | "Run" button | `vm.scriptRun()` | Enabled when connected + non-blank |
| [ ] | "Clear output" outlined button | `vm.clearScriptOutput()` | |

## Macros (`MacroScreen`, tab 41)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" / "Reconnect over USB" button | USB connect | |
| [ ] | "Stop macro" button | `vm.stopMacro()` | Shown while running |
| [ ] | "Macro name" field | Set macro name | |
| [ ] | "Command" field | Set the step command | |
| [ ] | "Wait ms" field | Set the step delay | |
| [ ] | "Add step" outlined button | Append a step | |
| [ ] | Per-step "x" button | Remove that step | Repeated per step |
| [ ] | "Save macro" button | `vm.saveMacro()` | |
| [ ] | Saved macro "Run" button | `vm.runMacro()` | Repeated per macro |
| [ ] | Saved macro "Delete" button | `vm.deleteMacro()` | Repeated per macro |

## RF Map 3D (`RfMap3dScreen`, tab 42)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | WebView canvas (drag to rotate) | Rotate the 3D scene | Handled in embedded JS |
| [ ] | "Zoom" slider | Set zoom (`window.setZoom`) | |
| [ ] | "Refresh" button | Rebuild + push source data | |
| [ ] | "Save PNG" outlined button | Save the canvas as a PNG | |

## Bug Sweep (`BugSweepScreen`, tab 43)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | "Wi-Fi" switch | Toggle the Wi-Fi band | Disabled while sweeping |
| [ ] | "BLE" switch | Toggle the BLE band | Disabled while sweeping |
| [ ] | "Sub-GHz (CC1101)" switch | Toggle the sub-GHz band | Disabled while sweeping |
| [ ] | "Start sweep" / "Stop sweep" button | Start/stop the selected band streams | Needs connected + a band selected |

## PCAP dissect (`PcapDissectScreen`, tab 45)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Open .pcap from phone" / "Parsing..." button | Pick + parse a pcap on-phone | |

## Geo signals (`GeoSignalsScreen`, tab 48)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | Pinned signal "Replay" button | `vm.replayGeoSignal()` | Repeated per pinned signal |
| [ ] | Pinned signal "Delete" button | `vm.deleteGeoSignal()` | Repeated per pinned signal |
| [ ] | Saved signal "Pin here" outlined button | `vm.addGeoSignal()` | Repeated per saved signal; needs a location fix |

## Gadgets (`ToolsScreen`, tab 20)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | iButton "Read" button | `vm.ibuttonRead()` | |
| [ ] | iButton "id (hex)" field | Set the id to write | |
| [ ] | iButton "Write" button | `vm.ibuttonWrite()` | |
| [ ] | Clock "Get time" outlined button | `vm.clockGet()` | |
| [ ] | Clock "Sync from phone" button | `vm.clockSet()` | |
| [ ] | Speaker "freq (Hz)" field | Set tone frequency | Speaker card hidden if no speaker |
| [ ] | Speaker "duration (ms)" field | Set tone duration | |
| [ ] | Speaker "Play tone" button | `vm.speakerTone()` | |
| [ ] | Speaker "file path" field | Set the file path | |
| [ ] | Speaker "Play file" button | `vm.speakerPlay()` | |
| [ ] | Mic "seconds" field | Set record duration | Mic card shown only if mic present |
| [ ] | Mic "Record" button | `vm.micRecord()` | |
| [ ] | ESP-NOW "message" field | Set message | |
| [ ] | ESP-NOW "Broadcast (ESP-NOW)" button | `vm.espnowSend()` | |

## Utilities (`UtilitiesScreen`, tab 24) - offline phone tools

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | Password "Length" field | Set length | |
| [ ] | Password switches (Lowercase / Uppercase / Digits / Symbols) | Toggle character sets | Four switches |
| [ ] | Password "Generate" button | `PhoneUtils.generatePassword()` | |
| [ ] | Password "Copy" button | Copy the password | Shown when a result exists |
| [ ] | Hash "Text to hash" field | Set input | |
| [ ] | Hash "Copy" buttons (MD5 / SHA-1 / SHA-256) | Copy each hash | Three buttons, shown when input non-empty |
| [ ] | Base64 "Text" field | Set input | |
| [ ] | Base64 "Encode" button | `PhoneUtils.base64Encode()` | |
| [ ] | Base64 "Decode" button | `PhoneUtils.base64Decode()` | |
| [ ] | Base64 "Copy" button | Copy the result | Shown when output exists |
| [ ] | MAC vendor "MAC / BSSID" field | Set the MAC | |
| [ ] | MAC vendor "Look up" button | OUI lookup | |
| [ ] | Wi-Fi QR "SSID" field | Set SSID | |
| [ ] | Wi-Fi QR "Password" field | Set password | |
| [ ] | Wi-Fi QR auth selector (WPA / WEP / Open) | Set auth type | |
| [ ] | Wi-Fi QR "Generate QR" button | Build the Wi-Fi QR | |
| [ ] | Wi-Fi QR "Copy" button | Copy the Wi-Fi string | Shown when a QR exists |

---

# Theme (reached via the home "Theme" tile)

## Theme & LED (`ThemeScreen` / `ThemeLedCard`, tab 36)

| Tested | Control | What it does | Notes |
|---|---|---|---|
| [ ] | "Connect over USB" button | USB connect | |
| [ ] | Theme color swatches | `vm.setDeviceTheme()` | Repeated per swatch (`THEME_SWATCHES`) |
| [ ] | LED color swatches | `vm.setDeviceLedColor()` | Repeated per swatch |
| [ ] | "LED brightness: N%" slider | `vm.setLedBright()` | |
| [ ] | "LED animation" dropdown button + items | `vm.setLedEffect()` | One item per `LED_EFFECTS` entry |
| [ ] | "Save to device" button | `vm.saveDeviceConfig()` | |

---

## Coverage summary

- **Screens / destinations catalogued: 51** - 50 tool tiles across 11 home categories, plus
  the Theme screen (reached from the home grid) - plus the always-on app chrome (top status
  bar, bottom navigation, home dashboard, Support card, Command log dialog, Help dialog).
- **Interactive controls catalogued: ~480** (counting each distinct control once; groups of
  near-identical repeated controls - per-AP action buttons, per-wordlist rows, BLE platform
  spam cards, on-screen remote keys, DTMF/composer keypads, color swatches, saved-item rows -
  are listed once with a "repeated per ..." note, so the live total is higher at runtime).
- The three Spam tiles share one `SpamScreen` composable (filtered BLE / Wi-Fi / NFC); several
  helper cards (`ThemeLedCard`, `BadBleSection`, `NfcNdefPushCard`, `BeaconSpamCard`,
  `ScopePanel`) appear on more than one screen and are cross-referenced where reused.
