package app.brucia.features

/**
 * The built-in Brucia feature catalog, extracted verbatim from the `toolCategories`
 * list in [app.brucia.MainActivity]. Titles, descriptions, category/tool order, tab
 * ids and caps are preserved exactly; a null cap keeps its existing meaning (always
 * available). The Wi-Fi gate (cap = "wifi") on the dictionary/WPA tools is kept;
 * operation-level gating is a later migration.
 *
 * Each feature carries a FROZEN stable string id, derived deterministically from the
 * category and tool (reverse-DNS: "org.brucia.<categorySlug>.<toolSlug>"). These ids
 * are frozen constants here, NOT computed from localized labels at runtime. The full
 * legacy-tab -> stable-id mapping is listed in RESULT.md for coordinator review.
 *
 * Source inventory: 50 tools across 11 categories (navigation order preserved).
 */
object BuiltinFeatures {

    /** The validated, immutable production registry. */
    val registry: FeatureRegistry = FeatureRegistry.of(
        listOf(
            FeatureCategory("Wi-Fi", "Wi-Fi", listOf(
                FeatureDescriptor("org.brucia.wifi.wardrive", 0, "Wardrive", "Scan and map nearby access points", "wifi"),
                FeatureDescriptor("org.brucia.wifi.crack", 7, "Crack", "Crack captured handshakes and PMKIDs", "wifi"),
                FeatureDescriptor("org.brucia.wifi.sniffer", 11, "Sniffer", "Sniff nearby Wi-Fi client stations", "wifi"),
                FeatureDescriptor("org.brucia.wifi.probes", 15, "Probes", "Profile client probe requests - who seeks which networks", "wifi"),
                FeatureDescriptor("org.brucia.wifi.monitor", 17, "Monitor", "Monitor your own network - live packet list and pcap export", "wifi"),
                FeatureDescriptor("org.brucia.wifi.evilportal", 30, "Evil Portal", "Fake AP + captive login page - capture credentials (authorized use)", "wifi"),
                FeatureDescriptor("org.brucia.wifi.channels", 26, "Channels", "Live 2.4GHz channel congestion and best-channel advice", "wifi"),
                FeatureDescriptor("org.brucia.wifi.mac", 33, "MAC", "View, set or randomize the device Wi-Fi MAC", "wifi"),
                FeatureDescriptor("org.brucia.wifi.connect", 34, "Connect", "Join the device to a Wi-Fi network", "wifi"),
                FeatureDescriptor("org.brucia.wifi.karma", 38, "Karma", "Evil-twin probe-response lure (authorized use)", "wifi"),
                FeatureDescriptor("org.brucia.wifi.brucegotchi", 35, "Brucegotchi", "Pwnagotchi-style WPA handshake collector (authorized use)", "wifi"),
            )),
            FeatureCategory("Sub-GHz", "Sub-GHz", listOf(
                FeatureDescriptor("org.brucia.subghz.rf", 2, "RF", "Capture, decode and replay sub-GHz signals", "cc1101"),
            )),
            FeatureCategory("Radio", "Radio", listOf(
                FeatureDescriptor("org.brucia.radio.fm", 14, "FM", "Broadcast audio over the FM transmitter", "fm"),
                FeatureDescriptor("org.brucia.radio.lora", 22, "LoRa", "Configure, send and receive LoRa packets", "lora"),
            )),
            FeatureCategory("2.4GHz", "2.4G", listOf(
                FeatureDescriptor("org.brucia.24ghz.nrf24", 13, "NRF24", "2.4GHz scanner, ESB sniffer and MouseJack", "nrf24"),
                FeatureDescriptor("org.brucia.24ghz.espnow", 32, "ESP-NOW", "Broadcast a short message over ESP-NOW (any ESP32)"),
            )),
            FeatureCategory("NFC", "NFC", listOf(
                FeatureDescriptor("org.brucia.nfc.nfc", 3, "NFC", "Read, emulate and attack NFC tags", "nfc"),
            )),
            FeatureCategory("BLE & IR", "BLE/IR", listOf(
                FeatureDescriptor("org.brucia.bleir.ble", 5, "BLE", "Scan Bluetooth Low Energy devices", "ble"),
                FeatureDescriptor("org.brucia.bleir.ir", 1, "IR", "Send and browse infrared remotes", "ir"),
                FeatureDescriptor("org.brucia.bleir.irremote", 37, "IR Remote", "Build your own IR remote - capture buttons, tap to send", "ir"),
                FeatureDescriptor("org.brucia.bleir.bleremote", 39, "BLE Remote", "BLE presenter + keyboard - control a PC over Bluetooth", "ble"),
                FeatureDescriptor("org.brucia.bleir.badusb", 4, "BadUSB", "Run Ducky keystroke-injection scripts"),
            )),
            FeatureCategory("Spam", "Spam", listOf(
                FeatureDescriptor("org.brucia.spam.spamble", 49, "Spam BLE", "Bluetooth pairing-popup floods (iOS/Android/Windows/Samsung)", "ble"),
                FeatureDescriptor("org.brucia.spam.spamwifi", 50, "Spam Wi-Fi", "Beacon flood: broadcast fake Wi-Fi networks", "wifi"),
                FeatureDescriptor("org.brucia.spam.spamnfc", 51, "Spam NFC", "NFC NDEF push (tap-to-send)", "nfc"),
            )),
            FeatureCategory("Intercept", "MITM", listOf(
                FeatureDescriptor("org.brucia.intercept.mitm", 18, "MITM", "On-phone HTTPS intercepting proxy - authorized use only"),
                FeatureDescriptor("org.brucia.intercept.networkscan", 31, "Network Scan", "Scan the Wi-Fi for hosts, open ports, versions and likely CVEs (on-phone)"),
                FeatureDescriptor("org.brucia.intercept.responder", 40, "Responder", "LLMNR/NBT-NS name poisoning (authorized use)", "wifi"),
                FeatureDescriptor("org.brucia.intercept.wids", 44, "WIDS", "Detect deauth / evil-twin attacks around you (defensive)", "wifi"),
                FeatureDescriptor("org.brucia.intercept.rffingerprint", 46, "RF Fingerprint", "Record a place's RF signature and detect new devices", "wifi"),
                FeatureDescriptor("org.brucia.intercept.rftimeline", 47, "RF Timeline", "Persistent history of Wi-Fi/BLE devices seen over time", "wifi"),
            )),
            FeatureCategory("Device", "Device", listOf(
                FeatureDescriptor("org.brucia.device.deviceinfo", 6, "Device info", "Device info and CLI commands"),
                FeatureDescriptor("org.brucia.device.console", 8, "Console", "Raw serial console"),
                FeatureDescriptor("org.brucia.device.remote", 9, "Remote", "Mirror and control the device screen"),
                FeatureDescriptor("org.brucia.device.files", 10, "Files", "Browse the device SD / LittleFS"),
                FeatureDescriptor("org.brucia.device.updates", 12, "Updates", "Update the app and the device firmware"),
                FeatureDescriptor("org.brucia.device.ethernet", 23, "Ethernet", "Wired network status and configuration", "eth"),
            )),
            FeatureCategory("Audio", "Audio", listOf(
                FeatureDescriptor("org.brucia.audio.audio", 25, "Audio", "All mic visualizations live in parallel - spectrum, waterfall, scope, SSTV", "mic"),
                FeatureDescriptor("org.brucia.audio.oscilloscope", 29, "Oscilloscope", "2D time-domain scope plus XY / dual-mic vectorscope", "mic"),
                FeatureDescriptor("org.brucia.audio.sstv", 27, "SSTV", "Decode (live cascade, all modes) and transmit slow-scan TV", "mic"),
                FeatureDescriptor("org.brucia.audio.tone", 28, "Tone", "Detect tones / DTMF and generate them", "mic"),
            )),
            FeatureCategory("Tools", "Tools", listOf(
                FeatureDescriptor("org.brucia.tools.report", 21, "Report", "Session report and heuristic insights from everything captured"),
                FeatureDescriptor("org.brucia.tools.scripts", 19, "Scripts", "Write and run JavaScript on the device"),
                FeatureDescriptor("org.brucia.tools.macros", 41, "Macros", "Chain device commands into saved, one-tap automations"),
                FeatureDescriptor("org.brucia.tools.rfmap3d", 42, "RF Map 3D", "3D map of the RF sources around you (Wi-Fi/BLE/sub-GHz)"),
                FeatureDescriptor("org.brucia.tools.bugsweep", 43, "Bug Sweep", "Find hidden transmitters (bugs/spy-cams) by signal strength"),
                FeatureDescriptor("org.brucia.tools.pcapdissect", 45, "PCAP dissect", "Open an 802.11 capture and dissect it (Wireshark-lite, on-phone)"),
                FeatureDescriptor("org.brucia.tools.geosignals", 48, "Geo signals", "Pin sub-GHz/IR signals to a GPS spot and replay on return"),
                FeatureDescriptor("org.brucia.tools.gadgets", 20, "Gadgets", "iButton read / write and device clock"),
                FeatureDescriptor("org.brucia.tools.utilities", 24, "Utilities", "Offline phone tools - passwords, hashes, base64, MAC vendor, Wi-Fi QR"),
            )),
        )
    )
}
