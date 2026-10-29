#include "core/display.h"
#include "core/mykeyboard.h"
#include "internal.h"
#include <globals.h>
#if defined(COMPANION_HAS_IR)
#include "modules/ir/TV-B-Gone.h"
#include "modules/ir/custom_ir.h"
#include "modules/ir/ir_read.h"
#endif

#if defined(COMPANION_HAS_IR)
#include "modules/ir/ir_jammer.h"
#endif

namespace brucia {
namespace companion {
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
    // Cap the whole blast so the command stays bounded even if the full table is large.
    uint16_t sent = runTvBGoneHeadless(useEU, 60000);
    String out = "TV-B-Gone done (" + String(sent) + " codes)";
    companionAckMsg(id, true, out.c_str());
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

        // Brucia: phone-driven, time-bounded IR jammer (abortable bursts).
        if (cmdStr.startsWith("ir_jammer")) {
            int secs = cmdStr.length() > 10 ? cmdStr.substring(10).toInt() : 0;
            uint32_t n = ir_jammer_headless(secs);
            char buf[32];
            snprintf(buf, sizeof(buf), "ir jammed (%lu pkts)", (unsigned long)n);
            companionAckMsg(id, true, buf);
            break;
        }
        return false;
    } while (false);
    return true;
}

} // namespace companion
} // namespace brucia
