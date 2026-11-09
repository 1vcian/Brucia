# Tools, Crack and MITM

> JavaScript runs on the device. Review third-party scripts before installing or running them.

General-purpose device tools, the phone-side WPA/WPA2 cracking toolkit, the on-phone MITM
proxy, and session offload/reporting.

> For authorized security research and education only. Crack, the MITM proxy and the
> Network Scan CVE flags are for systems you own or are explicitly permitted to test.

## Scripts
Runs on-device BruceJS (JavaScript) headless and returns its output (`script_run`).

Scripts stored on the device can be run from **Files**: open a `.js`, `.bjs` or compiled
`.bin` and choose **Run on device** (`script_run_file`, firmware from this release on). The
output and a Stop button stay in a dialog; Stop all ends the run as for `script_run`.

A compiled script (`.bin`) is BruceJS bytecode built on a computer with
`python3 tools/bjs_compile.py script.js` (it builds the `mqjs` compiler from the mquickjs
version the firmware pins; run a firmware build once first). Before loading a `.bin` the device
checks its structure: a truncated, corrupt or other-version file is refused ("invalid or
incompatible bytecode file"). The check does not verify the code itself, so run only `.bin`
files you trust, as with any script. On the device, the JS Interpreter menu and the file
browser also list `.bin` files that carry the bytecode header.

## BLE console
A plain-text console over Bluetooth for when the device is not on USB. On the device enable
**Config > Advanced > Toggle BLE API**: it advertises as "Bruce" and shows a 6-digit PIN
(new each time it is turned on). In the app, **Tools > BLE console**: Scan, tap the device and
pair with that PIN. Only the paired phone can run commands and see their output; any CLI command
works, one per line (for example `info`). **Forget BLE pairs** in the same device menu removes
every paired phone. The USB companion link is unaffected; this is a separate path.

## iButton
1-Wire read and RW1990 rewrite (`ibutton_read`, `ibutton_write`). The write waits about 3 s
for a key on the probe, writes it and reads it back, and reports whether the id landed
(firmware from this release on; older firmware writes without checking).

## Clock
Gets or sets the device clock (`clock_get`, `clock_set`). Like Bruce's own NTP sync, the
device clock holds local time: "Sync from phone" sends the phone's local time.

## Speaker tools
Plays a tone or an audio file on the onboard I2S speaker (`speaker_tone`,
`speaker_play`). The live spectrum, waterfall, SSTV, tone and oscilloscope tools are in
[Features-Audio](Features-Audio.md).

## Microphone recording
Records to a WAV on SD (`mic_record`). The rest of the mic-driven tools are in
[Features-Audio](Features-Audio.md).

## Utilities
Phone-only, always available: password generator, hash calculator (MD5/SHA-1/SHA-256),
Base64 encode/decode, MAC vendor lookup and a Wi-Fi QR generator.

## ESP-NOW
A one-shot broadcast of a short message to nearby ESP32 devices (`espnow_send`).

## Crack (WPA/WPA2 on the phone)
A WPA/WPA2 toolkit running on the phone. Bruce also has on-device password recovery;
Brucia moves this workflow into Android with capture import, wordlists and results. The
device captures a PMKID (`wifi_pmkid`) or a 4-way handshake (`wifi_handshake`); the phone
does the rest:

- **Download real wordlists** in-app (rockyou, SecLists top-1M / 100k / 12k), plus a
  built-in offline quick list and import of any `.txt`.
- Several built-in dictionaries: common WPA, numeric/dates, keyboard walks, Italian,
  router defaults, plus a rockyou-scale import manager.
- Pull a captured `.pcap` from the device SD over USB (`fs_read`) or import one, or paste
  a PMKID (hashcat 16800) / hashcat 22000 line.
- Crack the handshake/PMKID (**PBKDF2 / PTK / MIC**) with **hashcat-style mangling rules**
  and a live **keys/s** counter.
- **hashcat 22000 import/export**.

Authorized testing only.

## On-phone MITM proxy
A **MITM HTTPS proxy that runs on the phone**, used together with the Wi-Fi ARP-MITM
redirect (see [Features-Wi-Fi](Features-Wi-Fi.md)) so intercepted traffic can be inspected on the device
you already own. Authorized testing only.

## Session report
A generated report of the session with **OSINT** enrichment and **insights**, built from
everything captured during the session.

## Offload
The crack toolkit, OOK/rolling-code decode, MITM proxy and report generation are all
Android processing integrated with device captures. This is not a claim that an ESP32
cannot implement any of those algorithms or that phone-side computation is unique to Brucia.

## Macros (automation)
Chain device commands into a saved, one-tap sequence (each step is a command plus a pause).
What it's for: run a repeatable workflow - for example join a Wi-Fi, start a capture, then a
scan - without driving every step by hand. The phone provides a saved sequencing UI;
device-side scripting is a separate existing option. Macros persist across restarts; a run is cancellable and shows
per-step progress.

## 3D RF map
A 3D view of the RF sources around you - Wi-Fi APs (orange), BLE devices (blue) and sub-GHz
spectrum peaks (red) - placed by signal strength (weaker = farther) and coloured by band.
What it's for: get an at-a-glance picture of what is transmitting nearby and roughly how close.
Drag to rotate, slider to zoom, Save exports a PNG. Approximate by design (RSSI -> distance is
noisy): it maps RF *sources*, not room geometry.

## Bug sweep (hidden-transmitter finder)
A defensive "hotter / colder" meter across Wi-Fi, BLE and - only possible with the CC1101 -
sub-GHz at once. What it's for: physically home in on a hidden transmitter (a bug, a spy camera,
a tracker) by walking the device around a room and watching the strength rise as you approach.
Live trend, combined meter with peak-hold, per-band bars. Authorized / defensive use.

## WIDS (wireless intrusion detection)
Continuously listens in 802.11 monitor mode (which only the ESP can do, not a phone) for
deauth/disassoc floods and raises a timestamped ALERT when a window crosses a threshold. What
it's for: know when someone is running a deauth or evil-twin attack on the air around you - the
defensive counterpart to the Evil Portal / deauth tools. Keeps an event log. Authorized use.

## RF fingerprint (place change-detection)
Records the set of Wi-Fi/BLE emitters present at a place as a named "fingerprint". What it's for:
later Compare against the live scan to see what is NEW (a device that appeared can mean new
surveillance), MISSING or unchanged - counter-surveillance for a room or vehicle you control.

## RF timeline (presence intelligence)
A persistent, cross-session history of every Wi-Fi/BLE device the ESP has seen, with first-seen /
last-seen and a hit count. What it's for: build a picture of who/what is around over time - storage
and compute a Flipper or the device alone cannot do. Filter and clear included.

## PCAP dissect
Opens an 802.11 capture the device recorded in monitor mode and dissects it on the phone
(Wireshark-lite): a summary (frame counts by type, EAPOL/handshake count, top BSSIDs) and a packet
list (type/subtype, source -> destination, BSSID, EAPOL flag). What it's for: inspect your own
captures without moving them to a PC.

## Geo signals (geofenced capture/replay)
Pin a saved sub-GHz or IR signal to the current GPS spot, then replay it when you return - the
device transmits, the phone supplies the GPS it lacks. What it's for: a gate/garage remote that is
ready on arrival, or any location-bound signal. Shows live distance to each pinned signal and
highlights when you are near. Authorized use (your own gates/remotes).

---
Back to [Features](Features.md).

---

[All tools](Features.md) · [Tutorials](Tutorials.md) · [Share a result](Contributing.md) ·
[Device test results](https://github.com/1vcian/Brucia/blob/main/TESTS.md)
