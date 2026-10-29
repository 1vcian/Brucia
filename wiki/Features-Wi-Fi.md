# Wi-Fi

Every Wi-Fi tool. The device is the radio front-end; the phone does the mapping,
cracking, analytics and storage. Each page is gated on `caps.wifi`.

> For authorized security research and education only. Deauth, beacon spam, evil
> portal, Karma, Responder, ARP-MITM and handshake capture are dual-use - only run
> them against networks you own or are explicitly permitted to test.

## Wardrive
Toggle the device's `wifi.ap` live stream; the phone GPS-tags each access point, dedups
by BSSID, estimates position (RSSI-weighted), and plots a track and markers on an
**offline map** (osmdroid). It shows encryption/channel/vendor analytics, a best 2.4 GHz
channel suggestion, and **IEEE OUI vendor lookup** against a bundled ~40k table.
**Export/share** to **WiGLE CSV**, KML and GPX. This is the flagship offload demo: the
GPS and map that Bruce needs an external module and a tiny screen for both come from the
phone.

## Crack (WPA/WPA2)
See the dedicated [[Features-Tools]] note - the handshake/PMKID capture lives here, the
cracking runs on the phone.

- **PMKID capture** (`wifi_pmkid`) and **4-way handshake capture** (`wifi_handshake`) on
  the device, pulled to the phone to crack.

## Deauth
Headless, time-bounded deauthentication of a chosen AP/channel (`wifi_deauth`). Bounded so
it can't run away; stop it from the Commands viewer.

## Station sniffer
Headless promiscuous, channel-hopping discovery of client stations (`wifi.sta` stream); the
phone lists and dedups them.

## Probe-request sniffer
Headless 802.11 probe-request profiler (`wifi.probe` stream) - which devices are looking for
which networks (a privacy-exposure demo).

## Beacon spam
Headless, time-bounded beacon flood from a phone-supplied SSID list (`wifi_beacon_spam`).
Also reachable from the Spam hub.

## Monitor mode
Full 802.11 promiscuous capture (`wifi.monitor` stream, optional channel lock and BSSID
filter), exported as **pcap for Wireshark**, with PSK-based decryption support. Use it to
inspect your own network.

## Evil Portal / evil twin
The device raises a fake AP and serves a captive page (the default one or an HTML template
pushed from the app), optionally deauthing or cloning a nearby network; captured
credentials stream to the app live. Runs as a phone-driven background session.

**In-app HTML editor:** pick "Custom" to author the captive page in the app - edit the HTML, see
a live **WebView preview**, and save named templates for reuse. On start the edited page is pushed
to the device and served. What it's for: iterate on the captive page comfortably on the phone
instead of the device's tiny screen or an external file. Authorized testing only.

## Jam / deauth detector
`jam_detect` listens for a flood of deauth/disassoc frames and reports how many it saw - a
**defensive** check for an active attack nearby.

## Change MAC
Set a custom or random Wi-Fi station MAC from the app (saved, re-applied on boot).

## Connect to Wi-Fi
Join the device to an AP (station mode) for device-side networking - a prerequisite for
tools that need an IP (for example Responder).

## Network Scan (on the phone)
An nmap-lite that discovers hosts, open ports and service versions on the LAN and flags
likely CVEs by version - all computed on the phone.

## Channel Analyzer
Live 2.4 GHz congestion per channel with best-channel advice.

## Karma attack
Evil-twin **probe-response lure**: the device answers client probe requests, luring devices
that auto-connect to known SSIDs. Headless and bounded (`karma <secs>`); the standalone menu
entry still works.

## Brucegotchi (Pwnagotchi parity)
A time-bounded background roam that hops channels and collects WPA handshakes to
`/BrucePCAP/handshakes` for later cracking.

## Responder (LLMNR/NBT-NS)
Answers LLMNR (UDP 5355) and NBT-NS (UDP 137) name queries with the device's IP, poisoning
name resolution so victims resolve to the device (`responder <secs>`). Needs Wi-Fi joined
first (Connect tool). The native interactive **SMB/NTLM-capturing** Responder still runs on
the device standalone.

## ARP MITM / NetCut
The device joins a Wi-Fi network and ARP-poisons a target and gateway so their traffic
routes through it (`arp_spoof`), pairing with the on-phone HTTPS intercept proxy (see
[[Features-Tools]]).

---
Back to [[Features]].
