#include "core/display.h"
#include "core/mykeyboard.h"
#include "internal.h"
#include <globals.h>
#if defined(COMPANION_HAS_IR) && defined(COMPANION_HAS_IR_CMDS)
#include "modules/ir/TV-B-Gone.h"
#include "modules/ir/custom_ir.h"
#include "modules/ir/ir_read.h"
#endif

#if defined(COMPANION_HAS_IR) && defined(COMPANION_HAS_IR_CMDS)
#include "modules/ir/ir_jammer.h"
#endif

namespace brucia {
namespace companion {
#if defined(COMPANION_HAS_IR_CMDS)
// --- IR companion commands (parity with Bruce's onboard IR) ----------------------------
// Reuse Bruce's own IR code; on boards without IR the handlers compile to an ok:false reply.

// Brucia: headless TV-B-Gone. AUTHORIZED USE ONLY - blasts stored TV power-off codes to
// switch off nearby televisions. region = na (default) | eu selects the code table; the
// universal parsed + raw codes always follow. Bounded (time cap) and check(EscPress)-abortable
// inside runTvBGoneHeadless().  Usage: ir_tvbgone [region]
static void runIrTvbgone(const String &argsIn, uint32_t id) {
#ifdef COMPANION_HAS_IR
    String region = argsIn;
    region.trim();
    region.toLowerCase();
    bool useEU = region.startsWith("eu");
    if (!irTxPinReady()) { // no pin menu in companion mode (nobody at the device)
        companionAckMsg(id, false, "IR TX pin not configured");
        return;
    }
    // Cap the whole blast so the command stays bounded even if the full table is large.
    uint16_t sent = runTvBGoneHeadless(useEU, 60000);
    // ok only when codes went out: 0 means the IR TX lock was not available (or the bound passed
    // before the first code); it used to ACK ok:true "done (0 codes)".
    String out = sent ? "TV-B-Gone done (sent " + String(sent) + " codes)" : String("TV-B-Gone sent 0 codes");
    companionAckMsg(id, sent > 0, out.c_str());
#else
    companionAckMsg(id, false, "no IR");
#endif
}

// Brucia: IR power spam. AUTHORIZED USE ONLY - blasts every power table (both regions + universal),
// repeating until <secs> elapses or the app's stop is polled, so it covers all device types.
// Usage: ir_power_spam [secs]  (secs clamped 1..120, default 30)
static void runIrPowerSpam(const String &argsIn, uint32_t id) {
#ifdef COMPANION_HAS_IR
    // Parse before any driver work (F33): an absent argument uses the default, but a malformed one
    // (nonnumeric, trailing junk, negative, out of range) is refused - toInt() would have silently
    // mapped "abc"/"-5" to the default and taken a numeric prefix of "30x".
    String a = argsIn;
    a.trim();
    int secs = 30; // documented no-argument default
    if (a.length() > 0) {
        bool numeric = true;
        for (unsigned int i = 0; i < a.length(); i++)
            if (a[i] < '0' || a[i] > '9') { numeric = false; break; }
        long v = numeric ? a.toInt() : -1;
        if (!numeric || v < 1 || v > 120) {
            sendErr(id, 400, "usage: ir_power_spam [secs 1..120]");
            return;
        }
        secs = (int)v;
    }
    if (!irTxPinReady()) { // no pin menu in companion mode (nobody at the device)
        companionAckMsg(id, false, "IR TX pin not configured");
        return;
    }
    bool stopped = false;
    // The whole set repeats, so it would otherwise ignore the app's Stop until <secs> ended: poll it.
    uint16_t sent = runIrPowerSpamHeadless(
        (uint32_t)secs * 1000, [&stopped]() { return stopped = stopped || companionPollStop(); });
    String out = sent ? "IR power spam sent " + String(sent) + " codes" : String("IR power spam sent 0 codes");
    if (stopped) out += " (stopped)";
    companionAckMsg(id, sent > 0, out.c_str());
#else
    companionAckMsg(id, false, "no IR");
#endif
}

// Brucia: learn/receive one IR code. Waits up to ~10s for a button press on the IR receiver,
// decodes it via the same path the `ir rx` CLI uses (IrRead::loop_headless), and returns the
// decoded form.  Usage: ir_rx
static void runIrRx(uint32_t id) {
#ifdef COMPANION_HAS_IR
    IrRead *reader = new IrRead(true);    // headless mode; heap, like the `ir rx` CLI path
    if (!reader->rxPinReady()) {          // no pin menu in companion mode (nobody at the device)
        delete reader;
        companionAckMsg(id, false, "IR RX pin not configured");
        return;
    }
    String r = reader->loop_headless(10); // ~10s timeout
    delete reader;
    if (r.length() == 0) {
        companionAckMsg(id, false, "no IR signal");
        return;
    }
    // loop_headless returns a Flipper .ir block; pull out protocol/address/command for a
    // compact one-line summary, falling back to the full block if parsing fails.
    auto field = [&](const char *key) -> String {
        int i = r.indexOf(key);
        if (i < 0) return "";
        i += strlen(key);
        int e = r.indexOf('\n', i);
        String v = r.substring(i, e < 0 ? r.length() : e);
        v.trim();
        return v;
    };
    String protocol = field("protocol: ");
    String out;
    if (protocol.length() > 0) {
        out = protocol + " addr=" + field("address: ") + " cmd=" + field("command: ");
        String val = field("value: ");
        if (val.length() > 0) out += " (raw: " + val + ")";
    } else {
        // raw capture (no protocol line): report the data list
        String data = field("data: ");
        out = data.length() > 0 ? ("raw: " + data) : r;
    }
    companionAckMsg(id, true, out.c_str());
#else
    companionAckMsg(id, false, "no IR");
#endif
}

// Brucia: transmit a raw IR timing list the phone supplies (replay a learned/raw signal).
// Reuses sendRawCommand() (the `ir tx_raw` send path). The carrier is given in kHz and the
// durations as a comma-separated list of microseconds.  Usage: ir_tx_raw <khz> <csv durations>
static void runIrTxRaw(const String &argsIn, uint32_t id) {
#ifdef COMPANION_HAS_IR
    String args = argsIn;
    args.trim();
    int sp = args.indexOf(' ');
    if (sp < 0) {
        sendErr(id, 400, "usage: ir_tx_raw <khz> <csv durations>");
        return;
    }
    uint16_t khz = (uint16_t)args.substring(0, sp).toInt();
    if (khz == 0) khz = 38; // default IR carrier
    String csv = args.substring(sp + 1);
    csv.trim();
    if (csv.length() == 0) {
        sendErr(id, 400, "no durations");
        return;
    }
    if (!irTxPinReady()) { // no pin menu in companion mode (nobody at the device)
        companionAckMsg(id, false, "IR TX pin not configured");
        return;
    }
    // sendRawCommand() parses a space-separated list; accept comma-separated from the phone.
    csv.replace(",", " ");
    sendRawCommand(khz, csv, true); // true = headless (no on-screen "Sending.." UI)
    String out = "IR raw sent @" + String(khz) + "kHz";
    companionAckMsg(id, true, out.c_str());
#else
    companionAckMsg(id, false, "no IR");
#endif
}

bool dispatchInfraredCommand(const CommandRequest &request) {
    const String &cmdStr = request.command;
    const uint32_t id = request.id;
    do {

        // Brucia IR: headless TV-B-Gone power-off blast. AUTHORIZED USE ONLY.
        //   ir_tvbgone [na|eu]
        if (cmdStr == "ir_tvbgone" || cmdStr.startsWith("ir_tvbgone ")) {
            runIrTvbgone(cmdStr.length() > 10 ? cmdStr.substring(11) : String(""), id);
            break;
        }

        // Idle stop guard: ir_power_spam polls the link while it runs, so a stop reaching an idle
        // device (the spam already ended) must not start a new window via the startsWith below.
        if (cmdStr == "ir_power_spam_stop") {
            companionAckMsg(id, true, "not running");
            break;
        }

        // Brucia IR: power spam - all power tables, repeating, abortable. AUTHORIZED USE ONLY.
        //   ir_power_spam [secs]
        if (cmdStr == "ir_power_spam" || cmdStr.startsWith("ir_power_spam ")) {
            runIrPowerSpam(cmdStr.length() > 13 ? cmdStr.substring(14) : String(""), id);
            break;
        }

        // Brucia IR: learn/receive one IR code (~10s window).
        //   ir_rx
        if (cmdStr == "ir_rx") {
            runIrRx(id);
            break;
        }

        // Brucia IR: replay a raw IR timing list from the phone.
        //   ir_tx_raw <khz> <csv durations>
        if (cmdStr.startsWith("ir_tx_raw ")) {
            runIrTxRaw(cmdStr.substring(10), id);
            break;
        }

        // Idle stop guard: ir_jammer_headless() runs its whole window (it does not poll the link),
        // so an ir_jammer_stop is read only after it ended. It must not start a new jam window
        // (the old startsWith("ir_jammer") read it as "ir_jammer <secs>").
        if (cmdStr == "ir_jammer_stop") {
            companionAckMsg(id, true, "not running");
            break;
        }

        // Brucia: phone-driven, time-bounded IR jammer (abortable bursts).
        //   ir_jammer [secs]
        if (cmdStr == "ir_jammer" || cmdStr.startsWith("ir_jammer ")) {
#ifdef COMPANION_HAS_IR
            if (!irTxPinReady()) { // no pin menu in companion mode (nobody at the device)
                companionAckMsg(id, false, "IR TX pin not configured");
                break;
            }
#endif
            int secs = cmdStr.length() > 10 ? cmdStr.substring(10).toInt() : 0;
            uint32_t n = ir_jammer_headless(secs);
            // ok only when bursts went out (it used to ACK ok:true with 0 packets).
            char buf[40];
            if (n == 0) snprintf(buf, sizeof(buf), "ir jammer sent 0 pkts");
            else snprintf(buf, sizeof(buf), "ir jammed (sent %lu pkts)", (unsigned long)n);
            companionAckMsg(id, n > 0, buf);
            break;
        }
        return false;
    } while (false);
    return true;
}
#else  // !COMPANION_HAS_IR_CMDS
// LITE builds compile these companion IR endpoints out for app-slot headroom (config.h). Bruce's
// own IR menus and `ir` CLI are unchanged; CAPS keeps caps.ir and reports features.ir_cmds:false.
bool dispatchInfraredCommand(const CommandRequest &request) {
    const String &cmdStr = request.command;
    if (cmdStr == "ir_jammer_stop" || cmdStr == "ir_power_spam_stop") { // idle answer; never a start
        companionAckMsg(request.id, true, "not running");
        return true;
    }
    if (cmdStr == "ir_rx" || cmdStr == "ir_tvbgone" || cmdStr.startsWith("ir_tvbgone ") ||
        cmdStr.startsWith("ir_tx_raw ") || cmdStr == "ir_jammer" || cmdStr.startsWith("ir_jammer ") ||
        cmdStr == "ir_power_spam" || cmdStr.startsWith("ir_power_spam ")) {
        sendErr(request.id, 501, "ir companion commands not available in this (lite) build");
        return true;
    }
    return false;
}
#endif // COMPANION_HAS_IR_CMDS

} // namespace companion
} // namespace brucia
