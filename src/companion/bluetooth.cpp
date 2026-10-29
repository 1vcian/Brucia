#include "core/display.h"
#include "core/mykeyboard.h"
#include "internal.h"
#include <NimBLEDevice.h>
#include <globals.h>
#if !defined(LITE_VERSION)
#include "core/serial_commands/helpers.h"
#include "modules/badusb_ble/ducky_typer.h"
#endif

#include "modules/ble/ble_spam.h"
#include <brucia_identity.h>
#if !defined(LITE_VERSION)
#include <BleKeyboard.h>
#include <keys.h>
bool runMediaCommandHijackHeadless(NimBLEAddress target);
#endif

namespace brucia {
namespace companion {
#if !defined(LITE_VERSION)
// Self-contained BLE HID keyboard for the phone-driven presenter/keyboard. Kept alive across
// commands (ble_hid_begin advertises, the target pairs, then ble_hid_key / ble_hid_type).
static BleKeyboard *g_companionHid = nullptr;
// Map a presenter/keyboard key name to a HID keycode (0 = type the literal text instead).
static uint8_t companionHidKey(const String &name) {
    if (name == "next" || name == "right") return KEY_RIGHT_ARROW;
    if (name == "prev" || name == "left") return KEY_LEFT_ARROW;
    if (name == "up") return KEY_UP_ARROW;
    if (name == "down") return KEY_DOWN_ARROW;
    if (name == "pageup") return KEY_PAGE_UP;
    if (name == "pagedown") return KEY_PAGE_DOWN;
    if (name == "start" || name == "f5") return KEY_F5;
    if (name == "end" || name == "esc") return KEY_ESC;
    if (name == "enter") return KEY_RETURN;
    if (name == "space") return KEY_SPACE;
    if (name == "tab") return KEYTAB;
    return 0;
}
#endif

// --- BLE scan stream: the ESP scans, the phone lists/dedups/analyzes. ---

static bool subBle = false;
static NimBLEScan *bleScan = nullptr;

void bleStart() {
    if (subBle) return;
    NimBLEDevice::init("");
    bleScan = NimBLEDevice::getScan();
    if (bleScan) bleScan->setActiveScan(true);
    subBle = true;
}

void bleStop() {
    if (!subBle) return;
    subBle = false;
    bleScan = nullptr;
    NimBLEDevice::deinit(true);
}

void bleService() {
    if (!subBle || bleScan == nullptr) return;
    // Short blocking window (called every loop iteration) so ESC, PING and the other streams
    // are serviced ~3x/second instead of being starved for 2s each scan.
    NimBLEScanResults results = bleScan->getResults(350, false);
    int n = results.getCount();
    for (int i = 0; i < n; i++) {
        const NimBLEAdvertisedDevice *dev = results.getDevice(i);
        if (dev == nullptr) continue;
        JsonDocument out;
        beginEnvelope(out, MSG_EVENT, 0);
        JsonObject d = out["d"].to<JsonObject>();
        d["ev"] = "ble.dev";
        JsonObject data = d["data"].to<JsonObject>();
        data["mac"] = String(dev->getAddress().toString().c_str());
        data["name"] = String(dev->getName().c_str());
        data["rssi"] = dev->getRSSI();
        sendEnvelope(out);
    }
    bleScan->clearResults();
}

// --- Brucia BLE GATT explorer (BLE central) ---------------------------------------
// Connect to a peripheral by MAC, walk its GATT table, read a characteristic. NimBLE
// is always compiled in, so no board macro is needed. These share the single BLE
// controller with the ble.scan stream, so each op stops the scan first (bleStop) and
// the phone re-subscribes ble.scan afterwards if it still wants the stream.
// AUTHORIZED / LAB USE ONLY: only probe devices you own or are permitted to test.

// Open a client to <mac>. We only have the MAC string, not its address type, so we
// try public then random. Returns a connected client or nullptr. Bounded by an 8s
// connect timeout per attempt.
static NimBLEClient *gattOpen(const String &macArg) {
    String mac = macArg;
    mac.trim();
    if (!NimBLEDevice::isInitialized()) NimBLEDevice::init("");
    const uint8_t types[2] = {BLE_ADDR_PUBLIC, BLE_ADDR_RANDOM};
    for (int t = 0; t < 2; t++) {
        NimBLEClient *client = NimBLEDevice::createClient();
        if (!client) return nullptr;
        client->setConnectTimeout(8000); // ms
        NimBLEAddress addr(std::string(mac.c_str()), types[t]);
        if (client->connect(addr)) return client;
        NimBLEDevice::deleteClient(client);
    }
    return nullptr;
}

static void gattClose(NimBLEClient *client) {
    if (!client) return;
    if (client->isConnected()) client->disconnect();
    NimBLEDevice::deleteClient(client);
}

// ble_gatt_connect <mac> : discover every service + characteristic and stream one
// EVENT ev="ble.gatt" data { svc, chr, props:"rwWnib" } per characteristic, then
// ACK { ok, out:"discovered <s> services, <c> characteristics" }. Disconnects after.
static void runBleGattConnect(const String &args, uint32_t id) {
    String mac = args;
    mac.trim();
    if (mac.length() < 11) {
        sendErr(id, 400, "usage: ble_gatt_connect <mac>");
        return;
    }
    if (subBle) bleStop(); // shared controller: stop the scan stream for the op
    NimBLEClient *client = gattOpen(mac);
    if (!client) {
        companionAckMsg(id, false, "connect failed");
        return;
    }
    int nsvc = 0, nchr = 0;
    const std::vector<NimBLERemoteService *> &svcs = client->getServices(true);
    for (auto *svc : svcs) {
        if (!svc) continue;
        nsvc++;
        String su = String(svc->getUUID().toString().c_str());
        const std::vector<NimBLERemoteCharacteristic *> &chrs = svc->getCharacteristics(true);
        for (auto *chr : chrs) {
            if (!chr) continue;
            nchr++;
            char props[8];
            int pi = 0;
            if (chr->canRead()) props[pi++] = 'r';
            if (chr->canWrite()) props[pi++] = 'w';
            if (chr->canWriteNoResponse()) props[pi++] = 'W';
            if (chr->canNotify()) props[pi++] = 'n';
            if (chr->canIndicate()) props[pi++] = 'i';
            if (chr->canBroadcast()) props[pi++] = 'b';
            props[pi] = 0;
            JsonDocument out;
            beginEnvelope(out, MSG_EVENT, 0);
            JsonObject d = out["d"].to<JsonObject>();
            d["ev"] = "ble.gatt";
            JsonObject data = d["data"].to<JsonObject>();
            data["svc"] = su;
            data["chr"] = String(chr->getUUID().toString().c_str());
            data["props"] = props;
            sendEnvelope(out);
        }
    }
    gattClose(client);
    String outMsg = "discovered " + String(nsvc) + " services, " + String(nchr) + " characteristics";
    companionAckMsg(id, true, outMsg.c_str());
}

// ble_gatt_read <mac> <char_uuid> : connect, find the characteristic across all
// services, read it, and ACK { ok, out:"hex=<..> text=<..>" } (text only when the
// value is printable ASCII).
static void runBleGattRead(const String &args, uint32_t id) {
    String a = args;
    a.trim();
    int sp = a.indexOf(' ');
    if (sp < 0) {
        sendErr(id, 400, "usage: ble_gatt_read <mac> <char_uuid>");
        return;
    }
    String mac = a.substring(0, sp);
    mac.trim();
    String cu = a.substring(sp + 1);
    cu.trim();
    if (mac.length() < 11 || cu.length() == 0) {
        sendErr(id, 400, "usage: ble_gatt_read <mac> <char_uuid>");
        return;
    }
    if (subBle) bleStop();
    NimBLEClient *client = gattOpen(mac);
    if (!client) {
        companionAckMsg(id, false, "connect failed");
        return;
    }
    NimBLEUUID target(std::string(cu.c_str()));
    NimBLERemoteCharacteristic *found = nullptr;
    const std::vector<NimBLERemoteService *> &svcs = client->getServices(true);
    for (auto *svc : svcs) {
        if (!svc) continue;
        NimBLERemoteCharacteristic *c = svc->getCharacteristic(target);
        if (c) {
            found = c;
            break;
        }
    }
    if (!found) {
        gattClose(client);
        companionAckMsg(id, false, "characteristic not found");
        return;
    }
    if (!found->canRead()) {
        gattClose(client);
        companionAckMsg(id, false, "characteristic not readable");
        return;
    }
    NimBLEAttValue val = found->readValue();
    const uint8_t *p = val.data();
    size_t n = val.length();
    String hex;
    hex.reserve(n * 2);
    String txt;
    txt.reserve(n);
    static const char *HEXD = "0123456789abcdef";
    bool printable = n > 0;
    for (size_t i = 0; i < n; i++) {
        hex += HEXD[p[i] >> 4];
        hex += HEXD[p[i] & 0xF];
        if (p[i] >= 0x20 && p[i] <= 0x7e) txt += (char)p[i];
        else printable = false;
    }
    gattClose(client);
    String outMsg = "hex=" + (n ? hex : String("(empty)"));
    if (printable) outMsg += " text=" + txt;
    companionAckMsg(id, true, outMsg.c_str());
}

#if !defined(LITE_VERSION)
// --- Brucia Bad BLE (Ducky over BLE HID): headless, bounded runner ----------------
// Advertises the device as a BLE HID keyboard (reusing Bruce's ducky_typer BLE path:
// ducky_startKb -> key_input), waits a bounded window for a host to pair, then types
// the phone-supplied Ducky/BadUSB script (STRING, DELAY, ENTER, GUI, ... handled by
// the existing Ducky parser in key_input). ACK { ok, out:"ran <n> lines" }.
// AUTHORIZED / LAB USE ONLY: only run against hosts you own or are permitted to test.
static void runBleBadble(const String &script, uint32_t id) {
    String s = script;
    s.replace("\r\n", "\n");
    if (s.length() == 0) {
        sendErr(id, 400, "usage: ble_badble <ducky script text>");
        return;
    }
    if (subBle) bleStop(); // shared controller: scan and the HID keyboard are exclusive

    // Stage the script in PSRAM-backed FS so we can reuse key_input(FS, path, hid).
    if (!_setupPsramFs()) {
        companionAckMsg(id, false, "psram fs init failed");
        return;
    }
    const String tmp = "/badble.txt";
    File f = PSRamFS.open(tmp, FILE_WRITE);
    if (!f) {
        companionAckMsg(id, false, "temp script open failed");
        return;
    }
    f.write((const uint8_t *)s.c_str(), s.length());
    f.close();

    // Count non-empty lines for the ACK (the parser skips blank lines the same way).
    int lines = 0;
    int len = s.length();
    for (int from = 0; from <= len;) {
        int nl = s.indexOf('\n', from);
        String ln = (nl < 0) ? s.substring(from) : s.substring(from, nl);
        ln.trim();
        if (ln.length() > 0) lines++;
        if (nl < 0) break;
        from = nl + 1;
    }

    // Bring up the BLE HID keyboard and advertise (functionId 2 = BadUSB).
    safeCleanupDuckyBLE(hid_ble);
    ducky_startKb(hid_ble, true, 2);
    if (returnToMenu || hid_ble == nullptr) {
        returnToMenu = false;
        safeCleanupDuckyBLE(hid_ble);
        PSRamFS.remove(tmp);
        companionAckMsg(id, false, "ble hid init failed (low RAM?)");
        return;
    }

    // Bounded wait (30s) for a host to pair; abortable on-device with ESC.
    const uint32_t deadline = millis() + 30000;
    while (!hid_ble->isConnected() && (int32_t)(millis() - deadline) < 0) {
        if (check(EscPress)) break;
        vTaskDelay(pdMS_TO_TICKS(20));
    }
    if (!hid_ble->isConnected()) {
        safeCleanupDuckyBLE(hid_ble);
        PSRamFS.remove(tmp);
        companionAckMsg(id, false, "no host paired within 30s");
        return;
    }
    BLEConnected = true;
    delay(500); // let the host finish HID setup before the first keystroke

    key_input(PSRamFS, tmp, hid_ble);

    safeCleanupDuckyBLE(hid_ble);
    PSRamFS.remove(tmp);
    BLEConnected = false;

    String out = "ran " + String(lines) + " lines";
    companionAckMsg(id, true, out.c_str());
}
#endif // !LITE_VERSION

void stopCompanionBluetoothControls() {
#if !defined(LITE_VERSION)
    if (g_companionHid) { // a BLE HID session left advertising: tear it down so it doesn't linger
        g_companionHid->end();
        delete g_companionHid;
        g_companionHid = nullptr;
    }
#endif
}

bool dispatchBluetoothCommand(const CommandRequest &request) {
    const String &cmdStr = request.command;
    const uint32_t id = request.id;
    do {

        // Brucia: phone-driven, time-bounded BLE (Apple ProximityPair) spam.
        if (cmdStr.startsWith("ble_spam ")) {
            int secs = cmdStr.substring(9).toInt();
            if (secs < 1) secs = 8;
            if (secs > 60) secs = 60;
#if !defined(LITE_VERSION)
            runBleSpamBounded((uint32_t)secs * 1000);
            sendAck(id, true);
#else
            sendErr(id, 501, "ble_spam not available in this (lite) build");
#endif
            break;
        }

        // Brucia: phone-driven, time-bounded iBeacon advertiser.
        //   ble_ibeacon <secs> [uuid]
        if (cmdStr.startsWith("ble_ibeacon")) {
            String a = cmdStr.length() > 12 ? cmdStr.substring(12) : "";
            a.trim();
            int sp = a.indexOf(' ');
            int secs = (sp < 0 ? a : a.substring(0, sp)).toInt();
            String uuid = sp < 0 ? "" : a.substring(sp + 1);
            uuid.trim();
            if (secs < 1) secs = 8;
            if (secs > 60) secs = 60;
            runIbeaconBounded(
                (uint32_t)secs * 1000,
                "Bruce iBeacon",
                uuid.length() ? uuid.c_str() : "8ec76ea3-6668-48da-9866-75be8bc86f4d"
            );
            companionAckMsg(id, true, "ibeacon done");
            break;
        }

        // Brucia: phone-driven, time-bounded BLE Google Fast Pair (Android) spam.
        // Advertises Fast Pair "device nearby" popups to nearby Android phones.
        // AUTHORIZED / LAB USE ONLY.   ble_fastpair_spam <secs>
        if (cmdStr.startsWith("ble_fastpair_spam ") || cmdStr == "ble_fastpair_spam") {
            int secs = cmdStr.length() > 18 ? cmdStr.substring(18).toInt() : 0;
            if (secs < 1) secs = 8;
            if (secs > 60) secs = 60; // clamp 1..60
#if !defined(LITE_VERSION)
            runBleFastPairSpamBounded((uint32_t)secs * 1000);
            String out = "fast pair spam done (" + String(secs) + "s)";
            companionAckMsg(id, true, out.c_str());
#else
            sendErr(id, 501, "ble_fastpair_spam not available in this (lite) build");
#endif
            break;
        }

        // Brucia: unified phone-driven BLE spam — one command covering every
        // platform so the app's Spam hub can target by platform. Routes to the
        // same bounded wrappers as ble_spam / ble_fastpair_spam.
        // AUTHORIZED / LAB USE ONLY.
        //   ble_spam_platform <ios|android|windows|samsung|all> <secs> [copies]
        //   copies = how many distinct advertisements to blast per cycle (burst);
        //   defaults to 10 when the token is absent (old 2-token app still works),
        //   clamped 1..100.
        if (cmdStr.startsWith("ble_spam_platform ")) {
            String args = cmdStr.substring(18);
            args.trim();
            int sp = args.indexOf(' ');
            String target = sp < 0 ? args : args.substring(0, sp);
            target.toLowerCase();
            // Remaining tokens after <target>: "<secs> [copies]"
            String rest = sp < 0 ? "" : args.substring(sp + 1);
            rest.trim();
            int sp2 = rest.indexOf(' ');
            int secs = rest.length() == 0 ? 0 : (sp2 < 0 ? rest.toInt() : rest.substring(0, sp2).toInt());
            if (secs < 1) secs = 8;
            if (secs > 60) secs = 60; // clamp 1..60
            // Optional 3rd token: copies. Absent -> default 10 (many-at-once).
            uint8_t copies;
            if (sp2 < 0) {
                copies = 10;
            } else {
                int c = rest.substring(sp2 + 1).toInt();
                if (c < 1) c = 1;
                if (c > 100) c = 100; // clamp 1..100
                copies = (uint8_t)c;
            }
#if !defined(LITE_VERSION)
            bool known = true;
            uint32_t ms = (uint32_t)secs * 1000;
            if (target == "ios") {
                runBleSpamBounded(ms, copies);
            } else if (target == "android") {
                runBleFastPairSpamBounded(ms, copies);
            } else if (target == "windows") {
                runBleSwiftPairSpamBounded(ms, copies);
            } else if (target == "samsung") {
                runBleSamsungSpamBounded(ms, copies);
            } else if (target == "all") {
                // Cycle through every platform in 1s slices for the duration.
                static void (*const allFns[])(uint32_t, uint8_t) = {
                    runBleSpamBounded,
                    runBleFastPairSpamBounded,
                    runBleSwiftPairSpamBounded,
                    runBleSamsungSpamBounded
                };
                uint32_t endAll = millis() + ms;
                int idx = 0;
                while ((int32_t)(millis() - endAll) < 0) {
                    if (check(EscPress)) break;
                    uint32_t rem = endAll - millis();
                    uint32_t slice = rem < 1000 ? rem : 1000;
                    allFns[idx & 3](slice, copies);
                    idx++;
                }
            } else {
                known = false;
            }
            if (known) {
                String out = target + " spam done (" + String(secs) + "s)";
                companionAckMsg(id, true, out.c_str());
            } else {
                sendErr(id, 400, "bad target (use ios|android|windows|samsung|all)");
            }
#else
            sendErr(id, 501, "ble_spam_platform not available in this (lite) build");
#endif
            break;
        }

        // Brucia BLE GATT explorer (central). NimBLE is always present,
        // so no board gate; the op stops the ble.scan stream (shared
        // controller) for its duration. AUTHORIZED / LAB USE ONLY.
        //   ble_gatt_connect <mac>            (discover services+chars)
        //   ble_gatt_read <mac> <char_uuid>   (read one characteristic)
        if (cmdStr.startsWith("ble_gatt_connect ")) {
            runBleGattConnect(cmdStr.substring(17), id);
            break;
        }

        if (cmdStr.startsWith("ble_gatt_read ")) {
            runBleGattRead(cmdStr.substring(14), id);
            break;
        }

        // Brucia Bad BLE: run a Ducky/BadUSB script over BLE HID. Pulls the
        // ducky_typer BLE keyboard stack, which is dropped from lite builds.
        // AUTHORIZED / LAB USE ONLY.   ble_badble <ducky script text>
        if (cmdStr.startsWith("ble_badble ")) {
#if !defined(LITE_VERSION)
            runBleBadble(cmdStr.substring(11), id);
#else
            sendErr(id, 501, "ble_badble not available in this (lite) build");
#endif
            break;
        }

        // Brucia BLE: one-shot media-control hijack of a paired audio device.
        // Connects to the target and injects the media-control command sequence.
        // Caps: ble.   ble_media <mac>
        if (cmdStr.startsWith("ble_media ")) {
#if !defined(LITE_VERSION)
            String mac = cmdStr.substring(10);
            mac.trim();
            if (mac.length() < 11) {
                sendErr(id, 400, "usage: ble_media <mac>");
            } else {
                NimBLEAddress target(std::string(mac.c_str()), BLE_ADDR_PUBLIC);
                bool okm = runMediaCommandHijackHeadless(target);
                companionAckMsg(id, okm, okm ? "media hijack sent" : "media hijack failed (no connect?)");
            }
#else
            sendErr(id, 501, "ble_media not available in this (lite) build");
#endif
            break;
        }

        // BLE HID presenter / keyboard (parity with Bruce "BLE Keyboard"/"Presenter").
        //   ble_hid_begin           -> advertise as a BLE keyboard (pair from the target)
        //   ble_hid_status          -> connected | advertising | off
        //   ble_hid_key <name>      -> press a presenter key (next/prev/pageup/.../esc)
        //   ble_hid_type <text>     -> type text
        //   ble_hid_end             -> stop advertising / disconnect
        if (cmdStr.startsWith("ble_hid_")) {
#if !defined(LITE_VERSION)
            if (cmdStr == "ble_hid_begin") {
                if (!g_companionHid) {
                    if (!NimBLEDevice::isInitialized()) NimBLEDevice::init("Brucia");
                    g_companionHid = new BleKeyboard(BRUCIA_PRODUCT_NAME, BRUCIA_MANUFACTURER, 100);
                    g_companionHid->begin();
                }
                companionAckMsg(id, true, "advertising - pair 'Brucia' from the target");
            } else if (cmdStr == "ble_hid_status") {
                const char *st =
                    !g_companionHid ? "off" : (g_companionHid->isConnected() ? "connected" : "advertising");
                companionAckMsg(id, true, st);
            } else if (cmdStr == "ble_hid_end") {
                if (g_companionHid) {
                    g_companionHid->end();
                    delete g_companionHid;
                    g_companionHid = nullptr;
                }
                companionAckMsg(id, true, "stopped");
            } else if (cmdStr.startsWith("ble_hid_key ")) {
                if (!g_companionHid || !g_companionHid->isConnected()) {
                    companionAckMsg(id, false, "not connected - ble_hid_begin + pair first");
                } else {
                    uint8_t k = companionHidKey(cmdStr.substring(12));
                    if (k == 0) {
                        sendErr(id, 400, "unknown key");
                        break;
                    }
                    g_companionHid->press(k);
                    delay(20);
                    g_companionHid->releaseAll();
                    companionAckMsg(id, true, "key");
                }
            } else if (cmdStr.startsWith("ble_hid_type ")) {
                if (!g_companionHid || !g_companionHid->isConnected()) {
                    companionAckMsg(id, false, "not connected - ble_hid_begin + pair first");
                } else {
                    g_companionHid->print(cmdStr.substring(13).c_str());
                    companionAckMsg(id, true, "typed");
                }
            } else {
                sendErr(id, 400, "unknown ble_hid_ command");
            }
#else
            sendErr(id, 501, "ble_hid not available in this (lite) build");
#endif
            break;
        }
        return false;
    } while (false);
    return true;
}

} // namespace companion
} // namespace brucia
