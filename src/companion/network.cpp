#include "core/bus_HAL.h"
#include "internal.h"
#include <globals.h>
#if defined(COMPANION_HAS_LORA)
#include "modules/lora/LoRaRF.h"
#endif
#if defined(COMPANION_HAS_ETH)
#include "modules/ethernet/DHCPStarvation.h"
#include "modules/ethernet/EthernetHelper.h"
#include "modules/ethernet/MACFlooding.h"
#include <ETH.h>
#endif

namespace brucia {
namespace companion {
// --- LoRa (SX127x / SX126x) commands --------------------------------------
// Headless parity for the LoRa chat radio, reusing modules/lora/LoRaRF.cpp's RadioLib driver via
// its lora_companion_* helpers. Boards without a LoRa chip still LINK and reply "no LoRa module".
// LEGAL NOTE: respect your region's ISM band / duty-cycle rules - authorized use only.
#if defined(COMPANION_HAS_LORA)
//   lora_config <freqMHz> [bwKHz] [sf] [cr]   (bw/sf/cr optional; defaults SF9, 31.25 kHz, 4/8)
static void runLoraConfig(const String &argsIn, uint32_t id) {
    String a = argsIn;
    a.trim();
    if (a.length() == 0) {
        sendErr(id, 400, "usage: lora_config <freqMHz> [bwKHz] [sf] [cr]");
        return;
    }
    // Split up to 4 whitespace-separated tokens.
    float freq = 0;
    float bw = 0;
    int sf = 0, cr = 0;
    int idx = 0;
    int from = 0;
    while (from < (int)a.length() && idx < 4) {
        int sp = a.indexOf(' ', from);
        String tok = (sp < 0) ? a.substring(from) : a.substring(from, sp);
        tok.trim();
        if (tok.length() > 0) {
            switch (idx) {
                case 0: freq = tok.toFloat(); break;
                case 1: bw = tok.toFloat(); break;
                case 2: sf = tok.toInt(); break;
                case 3: cr = tok.toInt(); break;
            }
            idx++;
        }
        if (sp < 0) break;
        from = sp + 1;
    }
    // Bounds: LoRa sub-GHz/2.4GHz bands, discrete SF 6..12, coding-rate denominator 5..8.
    if (freq < 100.0f || freq > 1100.0f) {
        sendErr(id, 400, "freqMHz out of range (100..1100)");
        return;
    }
    if (bw != 0 && (bw < 7.0f || bw > 510.0f)) {
        sendErr(id, 400, "bwKHz out of range (7..510)");
        return;
    }
    if (sf != 0 && (sf < 6 || sf > 12)) {
        sendErr(id, 400, "sf out of range (6..12)");
        return;
    }
    if (cr != 0 && (cr < 5 || cr > 8)) {
        sendErr(id, 400, "cr out of range (5..8)");
        return;
    }
    bool ok = lora_companion_begin(freq, bw, sf, cr);
    if (!ok) {
        companionAckMsg(id, false, "LoRa init failed (pins/radio)");
        return;
    }
    char out[80];
    snprintf(
        out,
        sizeof(out),
        "LoRa up @ %.3f MHz bw=%s sf=%s cr=%s",
        freq,
        bw != 0 ? String(bw, 2).c_str() : "def",
        sf != 0 ? String(sf).c_str() : "def",
        cr != 0 ? String(cr).c_str() : "def"
    );
    companionAckMsg(id, true, out);
}

//   lora_send <text>   (transmit one packet; requires a prior lora_config)
static void runLoraSend(const String &argsIn, uint32_t id) {
    String text = argsIn;
    if (text.length() == 0) {
        sendErr(id, 400, "usage: lora_send <text>");
        return;
    }
    if (!lora_companion_ready()) {
        companionAckMsg(id, false, "run lora_config first");
        return;
    }
    if (text.length() > 250) text = text.substring(0, 250); // LoRa payload cap
    bool ok = lora_companion_send(text);
    companionAckMsg(id, ok, ok ? "sent" : "LoRa send failed");
}

//   lora_recv <secs>   (listen up to secs; stream EVENT ev="lora.rx" {data,rssi,snr} per packet)
static void runLoraRecv(const String &argsIn, uint32_t id) {
    if (!lora_companion_ready()) {
        companionAckMsg(id, false, "run lora_config first");
        return;
    }
    int secs = argsIn.toInt();
    if (secs < 1) secs = 5;
    if (secs > 120) secs = 120; // bound the listen window
    uint32_t end = millis() + (uint32_t)secs * 1000;
    int count = 0;
    while ((int32_t)(millis() - end) < 0) {
        if (check(EscPress)) break;
        String data;
        float rssi = 0, snr = 0;
        if (lora_companion_poll(data, rssi, snr) == 1) {
            count++;
            JsonDocument out;
            beginEnvelope(out, MSG_EVENT, 0);
            JsonObject d = out["d"].to<JsonObject>();
            d["ev"] = "lora.rx";
            JsonObject r = d["data"].to<JsonObject>();
            r["data"] = data;
            r["rssi"] = rssi;
            r["snr"] = snr;
            sendEnvelope(out);
        } else {
            delay(5);
        }
    }
    char out[48];
    snprintf(out, sizeof(out), "received %d packet(s)", count);
    companionAckMsg(id, true, out);
}
#else  // !COMPANION_HAS_LORA
static void sendNoLora(uint32_t id) { companionAckMsg(id, false, "no LoRa module on this board"); }
#endif // COMPANION_HAS_LORA

// --- Ethernet (W5500) commands --------------------------------------------
// Headless parity for the on-device Ethernet menu, reusing modules/ethernet/EthernetHelper.cpp
// (ETH.begin over SPI + link/IP state). Boards without a W5500 still LINK and reply "no Ethernet".
#if defined(COMPANION_HAS_ETH)
// Bring up the W5500 and wait (bounded, ESC-abortable) for link + DHCP lease. Returns the helper
// (connected or not) via *outUp; caller must eth->stop() + delete.
static EthernetHelper *ethBringUp(bool &outUp, uint32_t waitMs) {
    EthernetHelper *eth = new EthernetHelper();
    if (!eth->setup()) { // pins unset or W5500 not found
        delete eth;
        outUp = false;
        return nullptr;
    }
    uint32_t end = millis() + waitMs;
    while (!eth->is_connected() && (int32_t)(millis() - end) < 0) {
        if (check(EscPress)) break;
        delay(50);
    }
    outUp = eth->is_connected();
    return eth;
}

static void ethSendStatus(uint32_t id, bool up) {
    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = up;
    JsonObject r = d["data"].to<JsonObject>();
    r["link"] = ETH.linkUp();
    r["up"] = up;
    r["ip"] = ETH.localIP().toString();
    r["gw"] = ETH.gatewayIP().toString();
    r["mask"] = ETH.subnetMask().toString();
    r["dns"] = ETH.dnsIP().toString();
    r["mac"] = ETH.macAddress();
    String out = up ? ("link up, ip " + ETH.localIP().toString()) : String("no link / no DHCP lease");
    d["out"] = out;
    sendEnvelope(o);
}

//   eth_status   (bring up the W5500, report link state + DHCP IP)
static void runEthStatus(uint32_t id) {
    bool up = false;
    EthernetHelper *eth = ethBringUp(up, 6000);
    if (!eth) {
        companionAckMsg(id, false, "W5500 not found");
        return;
    }
    ethSendStatus(id, up);
    eth->stop();
    delete eth;
}

//   eth_config <dhcp | static <ip> <gw> <mask> [dns]>
static void runEthConfig(const String &argsIn, uint32_t id) {
    String a = argsIn;
    a.trim();
    int sp = a.indexOf(' ');
    String mode = (sp < 0) ? a : a.substring(0, sp);
    mode.toLowerCase();
    if (mode != "dhcp" && mode != "static") {
        sendErr(id, 400, "usage: eth_config <dhcp | static <ip> <gw> <mask> [dns]>");
        return;
    }
    IPAddress ip, gw, mask, dns;
    if (mode == "static") {
        String rest = (sp < 0) ? String("") : a.substring(sp + 1);
        rest.trim();
        String toks[4];
        int ntok = 0;
        int from = 0;
        while (from < (int)rest.length() && ntok < 4) {
            int s = rest.indexOf(' ', from);
            String tok = (s < 0) ? rest.substring(from) : rest.substring(from, s);
            tok.trim();
            if (tok.length() > 0) toks[ntok++] = tok;
            if (s < 0) break;
            from = s + 1;
        }
        if (ntok < 3 || !ip.fromString(toks[0]) || !gw.fromString(toks[1]) || !mask.fromString(toks[2]) ||
            (ntok >= 4 && !dns.fromString(toks[3]))) {
            sendErr(id, 400, "usage: eth_config static <ip> <gw> <mask> [dns]");
            return;
        }
    }
    bool up = false;
    EthernetHelper *eth = ethBringUp(up, 2000); // short wait; reconfigure below then re-wait
    if (!eth) {
        companionAckMsg(id, false, "W5500 not found");
        return;
    }
    // dhcp -> all-INADDR_NONE reverts to DHCP; static -> apply the parsed addresses.
    if (mode == "dhcp") ETH.config();
    else ETH.config(ip, gw, mask, dns);
    uint32_t end = millis() + 6000;
    while (!ETH.linkUp() && (int32_t)(millis() - end) < 0) {
        if (check(EscPress)) break;
        delay(50);
    }
    up = (mode == "static") ? ETH.linkUp() : eth->is_connected();
    ethSendStatus(id, up);
    eth->stop();
    delete eth;
}

// Brucia: Ethernet DHCP-starvation parity. Reuses modules/ethernet/DHCPStarvation with the
// headless path (no GUI, bounded, ESC-abortable). Brings the W5500 up first. AUTHORIZED USE.
//   eth_dhcp_starve [secs]   (default 10s, 1..60)
static void runEthDhcpStarve(const String &argsIn, uint32_t id) {
    int secs = argsIn.length() ? argsIn.toInt() : 10;
    bool up = false;
    EthernetHelper *eth = ethBringUp(up, 4000);
    if (!eth) {
        companionAckMsg(id, false, "W5500 not found");
        return;
    }
    DHCPStarvation dhcp(true);
    int n = dhcp.run_headless(secs);
    eth->stop();
    delete eth;
    if (n < 0) {
        companionAckMsg(id, false, "no Ethernet interface to flood");
        return;
    }
    char msg[72];
    snprintf(msg, sizeof(msg), "sent %d DHCP DISCOVER packets", n);
    companionAckMsg(id, true, msg);
}

// Brucia: Ethernet MAC-flooding parity. Reuses modules/ethernet/MACFlooding headless path.
// AUTHORIZED USE.
//   eth_mac_flood [secs]   (default 10s, 1..60)
static void runEthMacFlood(const String &argsIn, uint32_t id) {
    int secs = argsIn.length() ? argsIn.toInt() : 10;
    bool up = false;
    EthernetHelper *eth = ethBringUp(up, 4000);
    if (!eth) {
        companionAckMsg(id, false, "W5500 not found");
        return;
    }
    MACFlooding mf(true);
    int n = mf.run_headless(secs);
    eth->stop();
    delete eth;
    if (n < 0) {
        companionAckMsg(id, false, "no Ethernet interface to flood");
        return;
    }
    char msg[72];
    snprintf(msg, sizeof(msg), "sent %d flood frames", n);
    companionAckMsg(id, true, msg);
}
#else  // !COMPANION_HAS_ETH
static void sendNoEth(uint32_t id) { companionAckMsg(id, false, "no Ethernet on this board"); }
#endif // COMPANION_HAS_ETH

void stopCompanionNetwork() {
#ifdef COMPANION_HAS_LORA
    lora_companion_end();
#endif
}

bool dispatchNetworkCommand(const CommandRequest &request) {
    const String &cmdStr = request.command;
    const uint32_t id = request.id;
    do {

        // Brucia: LoRa (SX127x/SX126x) parity. Guarded so boards without a LoRa
        // radio still link (reply "no LoRa module"). AUTHORIZED USE ONLY.
        //   lora_config <freqMHz> [bwKHz] [sf] [cr]
        //   lora_send <text>   /   lora_recv <secs>
        if (cmdStr.startsWith("lora_config ") || cmdStr == "lora_config") {
#if defined(COMPANION_HAS_LORA)
            runLoraConfig(cmdStr.length() > 12 ? cmdStr.substring(12) : String(""), id);
#else
            sendNoLora(id);
#endif
            break;
        }

        if (cmdStr.startsWith("lora_send ")) {
#if defined(COMPANION_HAS_LORA)
            runLoraSend(cmdStr.substring(10), id);
#else
            sendNoLora(id);
#endif
            break;
        }

        if (cmdStr.startsWith("lora_recv ") || cmdStr == "lora_recv") {
#if defined(COMPANION_HAS_LORA)
            runLoraRecv(cmdStr.length() > 10 ? cmdStr.substring(10) : String(""), id);
#else
            sendNoLora(id);
#endif
            break;
        }

        // Brucia: Ethernet (W5500) parity. Guarded so boards without the W5500
        // wiring still link (reply "no Ethernet").
        //   eth_status   /   eth_config <dhcp | static <ip> <gw> <mask> [dns]>
        if (cmdStr == "eth_status" || cmdStr.startsWith("eth_status ")) {
#if defined(COMPANION_HAS_ETH)
            runEthStatus(id);
#else
            sendNoEth(id);
#endif
            break;
        }

        if (cmdStr.startsWith("eth_config ")) {
#if defined(COMPANION_HAS_ETH)
            runEthConfig(cmdStr.substring(11), id);
#else
            sendNoEth(id);
#endif
            break;
        }

        //   eth_dhcp_starve [secs]   (DHCP starvation flood, AUTHORIZED USE)
        if (cmdStr == "eth_dhcp_starve" || cmdStr.startsWith("eth_dhcp_starve ")) {
#if defined(COMPANION_HAS_ETH)
            runEthDhcpStarve(cmdStr.length() > 15 ? cmdStr.substring(16) : String(""), id);
#else
            sendNoEth(id);
#endif
            break;
        }

        //   eth_mac_flood [secs]   (MAC flooding, AUTHORIZED USE)
        if (cmdStr == "eth_mac_flood" || cmdStr.startsWith("eth_mac_flood ")) {
#if defined(COMPANION_HAS_ETH)
            runEthMacFlood(cmdStr.length() > 13 ? cmdStr.substring(14) : String(""), id);
#else
            sendNoEth(id);
#endif
            break;
        }
        return false;
    } while (false);
    return true;
}

} // namespace companion
} // namespace brucia
