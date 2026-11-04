#include "internal.h"
#include <globals.h>
#if !defined(LITE_VERSION)
#include "modules/rfid/ST25R3916.h"
#endif
#include "modules/rfid/PN532.h"
#include "modules/rfid/RFID2.h"
#include "modules/rfid/apdu.h"
#include "modules/rfid/rfid125.h"
#include "modules/rfid/srix_tool.h"
#if !defined(LITE_VERSION)
#include "modules/rfid/emv_reader.hpp"
#endif

namespace brucia {
namespace companion {
// Brucia: MIFARE Classic key-dictionary check (offload). The phone holds the dictionary
// and streams candidate keys; the device only tests them against the present tag. Reuses
// the driver's existing MIFARE authenticate primitive (RFIDInterface::mifareKeyCheck).
RFIDInterface *createCompanionRfidModule() {
    switch (bruceConfigPins.rfidModule) {
        case PN532_I2C_MODULE: return new PN532(PN532::CONNECTION_TYPE::I2C);
#ifdef M5STICK
        case PN532_I2C_SPI_MODULE: return new PN532(PN532::CONNECTION_TYPE::I2C_SPI);
#endif
        case PN532_SPI_MODULE: return new PN532(PN532::CONNECTION_TYPE::SPI);
        case RC522_SPI_MODULE: return new RFID2(false);
#if !defined(LITE_VERSION)
        case ST25R3916_SPI_MODULE: return new ST25R3916(ST25R3916::SPI_MODE);
        case ST25R3916_I2C_MODULE: return new ST25R3916(ST25R3916::I2C_MODE);
#endif
        case M5_RFID2_MODULE:
        default: return new RFID2();
    }
}

// mifare_keycheck <hexKey1,hexKey2,...>  (comma-separated 12-hex-char keys, up to 64)
static void runMifareKeycheck(const String &argsIn, uint32_t id) {
    String args = argsIn;
    args.trim();
    if (args.length() == 0) {
        sendErr(id, 400, "usage: mifare_keycheck <hexKey1,hexKey2,...>");
        return;
    }

    static const int MAX_KEYS = 64;
    uint8_t keyList[MAX_KEYS][6];
    int nKeys = 0;
    int start = 0;
    while (start < (int)args.length() && nKeys < MAX_KEYS) {
        int comma = args.indexOf(',', start);
        String tok = comma < 0 ? args.substring(start) : args.substring(start, comma);
        tok.trim();
        if (tok.length() == 12) {
            bool good = true;
            for (int i = 0; i < 6; i++) {
                String bytestr = tok.substring(i * 2, i * 2 + 2);
                char *end = nullptr;
                long v = strtol(bytestr.c_str(), &end, 16);
                if (end == bytestr.c_str() || *end != '\0') {
                    good = false;
                    break;
                }
                keyList[nKeys][i] = (uint8_t)v;
            }
            if (good) nKeys++;
        }
        if (comma < 0) break;
        start = comma + 1;
    }

    if (nKeys == 0) {
        sendErr(id, 400, "no valid 12-hex keys");
        return;
    }

    RFIDInterface *rfid = createCompanionRfidModule();
    if (!rfid || !rfid->begin()) {
        if (rfid) delete rfid;
        JsonDocument o;
        beginEnvelope(o, MSG_ACK, id);
        JsonObject d = o["d"].to<JsonObject>();
        d["ok"] = false;
        d["out"] = "no RFID module (PN532) found";
        sendEnvelope(o);
        return;
    }

    std::vector<RFIDInterface::MifareKeyHit> hits;
    MifareKeyCheckResult info;
    // Cooperative cancellation: the companion loop is blocked while the batch runs, so the driver
    // polls the serial between authenticate() calls for a phone-sent stop frame (same channel the
    // responder/karma long ops use - companionPollStop consumes a "stop"/"*_stop" CMD and never
    // ACKs it). A stopped batch returns the hits found so far with complete:false, stop:"cancelled".
    bool tag = rfid->mifareKeyCheck(keyList, (size_t)nKeys, hits, &info, companionPollStop);
    delete rfid;

    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    if (!tag) {
        d["ok"] = false;
        d["out"] = "no MIFARE Classic tag present (or module can't do Classic auth)";
        sendEnvelope(o);
        return;
    }
    d["ok"] = true;
    // Versioned completeness contract (v1): additive fields older apps ignore. They let the
    // app tell a fully-tested batch from one cut short (tag lost), and tie consecutive batches
    // to the same card via the UID read at batch start. `found` stays the recovered-key list.
    d["v"] = 1;
    char uidHex[21];
    for (uint8_t i = 0; i < info.uidLen; i++) snprintf(uidHex + i * 2, 3, "%02X", info.uid[i]);
    uidHex[info.uidLen * 2] = '\0';
    d["uid"] = uidHex;
    d["sak"] = info.sak;
    d["sectors"] = info.sectors;              // total sectors on this tag
    d["tested_sectors"] = info.sectorsCompleted; // sectors fully tested before stopping
    d["keys"] = info.keysInBatch;            // candidate keys in this batch
    d["auth_attempts"] = info.authAttempts;  // individual auth operations performed
    d["complete"] = (info.stop == MIFARE_KC_COMPLETE);
    const char *stopStr = "no_tag";
    switch (info.stop) {
        case MIFARE_KC_COMPLETE: stopStr = "complete"; break;
        case MIFARE_KC_TAG_LOST: stopStr = "tag_lost"; break;
        case MIFARE_KC_CANCELLED: stopStr = "cancelled"; break; // phone asked to stop mid-scan
        case MIFARE_KC_NO_TAG: default: stopStr = "no_tag"; break;
    }
    d["stop"] = stopStr;
    JsonArray found = d["found"].to<JsonArray>();
    for (auto &h : hits) {
        JsonObject e = found.add<JsonObject>();
        e["sector"] = h.sector;
        e["type"] = h.keyB ? "B" : "A";
        char kb[13];
        snprintf(
            kb,
            sizeof(kb),
            "%02X%02X%02X%02X%02X%02X",
            h.key[0],
            h.key[1],
            h.key[2],
            h.key[3],
            h.key[4],
            h.key[5]
        );
        e["key"] = kb;
    }
    sendEnvelope(o);
}

// --- NFC parity commands (reuse Bruce's existing RFID stack) ---------------
// All of these reuse the same always-linking RFID stack as mifare_keycheck, so
// they are NOT lite-guarded (createCompanionRfidModule / RFIDInterface always
// link). Only nfc_emv_read depends on emv_reader.cpp, which is dropped in lite
// builds, so it is guarded at the dispatch site.

// Brucia: emulate an NDEF message (url/text/contact) so a phone tap receives it.
// Reuses the existing RFID NDEF path: RFIDInterface::buildNdefMessage for
// url/text, Ndef::mimeRecord (same helper as buildWifiNdef) for a vCard contact,
// then the driver's emulate() (PN532 target mode). emulate() self-bounds to ~60s.
//   nfc_ndef_push <url|text|contact> <payload...>
static void runNfcNdefPush(const String &argsIn, uint32_t id) {
    String args = argsIn;
    args.trim();
    int sp = args.indexOf(' ');
    if (sp < 0) {
        sendErr(id, 400, "usage: nfc_ndef_push <url|text|contact> <payload>");
        return;
    }
    String type = args.substring(0, sp);
    type.toLowerCase();
    String payload = args.substring(sp + 1);
    payload.trim();
    if (payload.length() == 0) {
        sendErr(id, 400, "usage: nfc_ndef_push <url|text|contact> <payload>");
        return;
    }
    if (type != "url" && type != "text" && type != "contact") {
        sendErr(id, 400, "type must be url|text|contact");
        return;
    }
    // Clamp to what the NDEF buffers / single-byte TLV length can carry.
    if (payload.length() > 200) payload = payload.substring(0, 200);

    RFIDInterface *rfid = createCompanionRfidModule();
    if (!rfid || !rfid->begin()) {
        if (rfid) delete rfid;
        companionAckMsg(id, false, "no RFID module (PN532) found");
        return;
    }

    if (type == "contact") {
        // Build a minimal vCard and wrap it as a "text/vcard" MIME NDEF record,
        // reusing the same Ndef::mimeRecord helper as buildWifiNdef(). Payload may
        // be "Full Name" or "Full Name;+123456789".
        String name = payload;
        String tel = "";
        int semi = payload.indexOf(';');
        if (semi >= 0) {
            name = payload.substring(0, semi);
            tel = payload.substring(semi + 1);
            name.trim();
            tel.trim();
        }
        String vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:" + name + "\r\n";
        if (tel.length() > 0) vcard += "TEL:" + tel + "\r\n";
        vcard += "END:VCARD\r\n";
        std::vector<uint8_t> body(vcard.begin(), vcard.end());
        rfid->rawNdefRecord = Ndef::mimeRecord("text/vcard", body);
    } else {
        rfid->buildNdefMessage(type, payload); // url / text (RFIDInterface base)
    }

    // backToMenu()/ESC leaves this set, which would make emulate() bail on its
    // first iteration - clear it so the ~60s target-mode window actually runs.
    returnToMenu = false;
    int result = rfid->emulate();
    String msg;
    bool ok;
    if (result == RFIDInterface::SUCCESS) {
        msg = "NDEF (" + type + ") emulated; phone tap received";
        ok = true;
    } else if (result == RFIDInterface::TAG_NOT_PRESENT) {
        msg = "NDEF (" + type + ") emulated ~60s; no phone tap detected";
        ok = true; // hardware armed fine, just no interaction
    } else {
        msg = "NDEF emulate failed: " + rfid->statusMessage(result);
        ok = false;
    }
    delete rfid;
    companionAckMsg(id, ok, msg.c_str());
}

#if !defined(LITE_VERSION)
// Brucia: read a contactless EMV bank card. Reuses emv_reader.cpp's protocol path
// (EMVReader::readMaskedSummary -> read_emv_card) headlessly. AUTHORIZED USE ONLY:
// the middle PAN digits are masked and the full PAN is never returned.
//   nfc_emv_read
static void runNfcEmvRead(uint32_t id) {
    EMVReader emv(false); // construct WITHOUT running the interactive GUI setup()
    String out;
    bool ok = emv.readMaskedSummary(out);
    companionAckMsg(id, ok, out.c_str());
}
#endif

// Brucia: amiibo. Bruce's amiibo support drives an EXTERNAL Amiibolink BLE dongle
// through an interactive GUI (BLE device search + on-screen dump file picker);
// there is no headless PN532 amiibo emulate/dump primitive in the codebase to
// reuse. So we report it unsupported rather than inventing one.
//   nfc_amiibo <emulate|dump>
static void runNfcAmiibo(const String &argsIn, uint32_t id) {
    String action = argsIn;
    action.trim();
    action.toLowerCase();
    if (action != "emulate" && action != "dump") {
        sendErr(id, 400, "usage: nfc_amiibo <emulate|dump>");
        return;
    }
    companionAckMsg(
        id, false, "amiibo not supported headless (Bruce uses an external Amiibolink dongle via GUI)"
    );
}

// Brucia: write a UID to a magic / Gen1A / UID-changeable MIFARE card. AUTHORIZED
// USE ONLY. Reuses the existing magic-card write path exactly as tag_o_matic's
// custom-UID clone does: read the present card to capture SAK/ATQA/size, override
// the UID bytes, then RFIDInterface::clone() (PN532 mifareclassic_WriteBlock0
// backdoor, falling back to a direct block-0 write).
//   nfc_write_uid <hexuid>   (4, 7 or 10 bytes, must match the card's UID size)
static void runNfcWriteUid(const String &argsIn, uint32_t id) {
    String hex = argsIn;
    hex.trim();
    hex.replace(" ", "");
    hex.toUpperCase();
    if (hex.length() == 0) {
        sendErr(id, 400, "usage: nfc_write_uid <hexuid> (4, 7 or 10 bytes)");
        return;
    }
    if (hex.length() % 2 != 0) {
        sendErr(id, 400, "hex UID must have an even number of chars");
        return;
    }
    int nbytes = hex.length() / 2;
    if (nbytes != 4 && nbytes != 7 && nbytes != 10) {
        sendErr(id, 400, "UID must be 4, 7 or 10 bytes");
        return;
    }
    uint8_t newUid[10];
    for (int i = 0; i < nbytes; i++) {
        String b = hex.substring(i * 2, i * 2 + 2);
        char *end = nullptr;
        long v = strtol(b.c_str(), &end, 16);
        if (end == b.c_str() || *end != '\0') {
            sendErr(id, 400, "invalid hex in UID");
            return;
        }
        newUid[i] = (uint8_t)v;
    }

    RFIDInterface *rfid = createCompanionRfidModule();
    if (!rfid || !rfid->begin()) {
        if (rfid) delete rfid;
        companionAckMsg(id, false, "no RFID module (PN532) found");
        return;
    }

    // Read the present (magic) card first so SAK/ATQA/size match - clone() checks
    // the physical card's SAK against uid.sak before writing block 0.
    int rd = rfid->read();
    if (rd != RFIDInterface::SUCCESS) {
        delete rfid;
        companionAckMsg(id, false, "no MIFARE card present to write");
        return;
    }
    if ((int)rfid->uid.size != nbytes) {
        String msg = "card UID size (" + String(rfid->uid.size) + " bytes) != requested (" + String(nbytes) +
                     " bytes)";
        delete rfid;
        companionAckMsg(id, false, msg.c_str());
        return;
    }
    for (int i = 0; i < nbytes; i++) rfid->uid.uidByte[i] = newUid[i];

    int result = rfid->clone();
    String msg = "write UID: " + rfid->statusMessage(result);
    bool ok = (result == RFIDInterface::SUCCESS);
    delete rfid;
    companionAckMsg(id, ok, msg.c_str());
}

// Brucia: SRIX / ST25TB (ISO14443B) read. Reuses the SRIX tool's headless path
// (SRIXTool(true) + read_tag_headless), which drives the onboard PN532 - no screen,
// no interactive loop. Not #if-gated: with no PN532 the tool simply reads nothing and
// we ack "no tag / no reader", so it is safe on any board. Returns the tool's own JSON
// ({"uid","blocks","size","data"}) on success, or a timeout ack when no tag is seen.
//   srix_read [secs]   (default 8s)
static void runSrixRead(const String &argsIn, uint32_t id) {
    int secs = argsIn.length() ? argsIn.toInt() : 8;
    if (secs < 1) secs = 1;
    if (secs > 30) secs = 30;
    SRIXTool srix(true); // headless constructor: no UI, no loop
    String out = srix.read_tag_headless(secs);
    if (out.length() == 0) {
        companionAckMsg(id, false, "no SRIX/ST25TB tag (or no PN532 reader)");
        return;
    }
    companionAckMsg(id, true, out.c_str());
}

// Brucia: 125 kHz LF read for boards that have the UART LF front-end (not on the T-Embed
// CC1101). Reuses RFID125's headless path. Not #if-gated: with no reader it just times out
// and we ack "no tag". Returns the tool's JSON ({"data","checksum"}) on success.
//   rfid125_read [secs]   (default 8s)
static void runRfid125Read(const String &argsIn, uint32_t id) {
    int secs = argsIn.length() ? argsIn.toInt() : 8;
    if (secs < 1) secs = 1;
    if (secs > 30) secs = 30;
    RFID125 lf(true); // headless constructor: opens the LF UART only
    String out = lf.read_headless(secs);
    if (out.length() == 0) {
        companionAckMsg(id, false, "no 125kHz tag (or no LF reader on this board)");
        return;
    }
    companionAckMsg(id, true, out.c_str());
}

bool dispatchNfcCommand(const CommandRequest &request) {
    const String &cmdStr = request.command;
    const uint32_t id = request.id;
    do {

        // Brucia: phone-driven MIFARE Classic key-dictionary check (offload).
        //   mifare_keycheck <hexKey1,hexKey2,...>
        if (cmdStr.startsWith("mifare_keycheck ")) {
            runMifareKeycheck(cmdStr.substring(16), id);
            break;
        }

        // Brucia: NFC parity commands (reuse the existing RFID stack).
        //   nfc_ndef_push <url|text|contact> <payload>  (emulate NDEF to a phone)
        if (cmdStr.startsWith("nfc_ndef_push ")) {
            runNfcNdefPush(cmdStr.substring(14), id);
            break;
        }

        //   nfc_emv_read  (masked PAN + expiry + scheme; lite build drops EMV)
        if (cmdStr == "nfc_emv_read" || cmdStr.startsWith("nfc_emv_read ")) {
#if !defined(LITE_VERSION)
            runNfcEmvRead(id);
#else
            companionAckMsg(id, false, "EMV reader not available in this (lite) build");
#endif
            break;
        }

        //   nfc_amiibo <emulate|dump>  (reports unsupported - external dongle only)
        if (cmdStr == "nfc_amiibo" || cmdStr.startsWith("nfc_amiibo ")) {
            runNfcAmiibo(cmdStr.length() > 10 ? cmdStr.substring(11) : String(""), id);
            break;
        }

        //   mifare_nested / mifare_mfkey32  (no native primitive: Bruce recovers
        //   MIFARE keys only via the dictionary check or an external PN532Killer
        //   sniffer - report that honestly instead of a bare unknown-command error).
        if (cmdStr == "mifare_nested" || cmdStr.startsWith("mifare_nested ")) {
            companionAckMsg(
                id,
                false,
                "nested recovery needs an external PN532Killer sniffer; use the "
                "dictionary key-check (mifare_keycheck) with the onboard PN532"
            );
            break;
        }

        if (cmdStr == "mifare_mfkey32" || cmdStr.startsWith("mifare_mfkey32 ")) {
            companionAckMsg(
                id,
                false,
                "mfkey32 sniffing needs an external PN532Killer; the onboard PN532 "
                "supports the dictionary key-check (mifare_keycheck)"
            );
            break;
        }

        //   nfc_write_uid <hexuid>  (write UID to a magic/Gen1A MIFARE card)
        if (cmdStr.startsWith("nfc_write_uid ")) {
            runNfcWriteUid(cmdStr.substring(14), id);
            break;
        }

        //   srix_read [secs]  (SRIX / ST25TB ISO14443B read via PN532)
        if (cmdStr == "srix_read" || cmdStr.startsWith("srix_read ")) {
            runSrixRead(cmdStr.length() > 9 ? cmdStr.substring(10) : String(""), id);
            break;
        }

        //   rfid125_read [secs]  (125 kHz LF read; boards with the UART LF reader)
        if (cmdStr == "rfid125_read" || cmdStr.startsWith("rfid125_read ")) {
            runRfid125Read(cmdStr.length() > 12 ? cmdStr.substring(13) : String(""), id);
            break;
        }
        return false;
    } while (false);
    return true;
}

} // namespace companion
} // namespace brucia
