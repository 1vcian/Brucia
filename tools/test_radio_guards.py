#!/usr/bin/env python3
"""Radio-feature audit guards (docs/audit/radio.md): ownership, bounded waits, honest results.

Firmware is not built here (no PlatformIO in this test); each guard is pinned by
  - a host-native compile of the pure header it uses (src/companion/subghz_busy.h), with a mutation
    check that the classification is load-bearing, and
  - static source checks in the style of tools/test_companion_prompts.py / test_firmware_ble_adv.py
    (ordering of the guard before the radio call, no menu/unbounded wait in companion mode), and
  - protocol-level checks against tools/mock_device.py, which mirrors the firmware behavior.

Covered:
  RF   CC1101 single owner: one-shot radio commands refused with 409 while subghz.raw/spectrum run.
  LoRa lora_recv polls the app's `stop` (PING answered meanwhile); the app sends it on Stop.
  Link the 60 s app-silence exit is measured from the end of a handled frame (a ~60 s bounded
       command no longer drops the device out of companion mode right after its ACK).
  BLE  ble.scan refuses while a BLE HID session owns the stack (409) and reports init failure
       (503); ble_media refuses while the scan runs (its deinit freed the scanner).
  BadUSB `badusb run_from_*` refused in companion mode (USB port is the app link).
  IR   TX handlers refuse when the TX pin is not in IR_TX_PINS instead of opening the pin menu;
       `ir tx` rejects a protocol sendIRCommand ignores (was a false success).
  NFC  nfc_emv_read waits a bounded time and polls `stop`; a cancelled wait is never "parsed".
  ESP-NOW espnow_send keeps the Wi-Fi owner (no forced STA / disconnect).

Run: python3 tools/test_radio_guards.py
"""
import os
import pty
import re
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from mock_device import ACK, ERR, PING, PONG, SUB, MockDevice, subghz_needs_radio  # noqa: E402

CXX = os.environ.get("CXX", "c++")
SRC = ROOT / "src"


def read(rel):
    return (ROOT / rel).read_text()


def strip_comments(text):
    """Drop // and /* */ comments, leaving string and char literals intact."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c in "\"'":
            j = i + 1
            while j < n and text[j] != c:
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        elif text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def body_of(src, signature_regex):
    """Brace-matched body of the first function whose signature matches signature_regex."""
    m = re.search(signature_regex + r"[^{;]*\{", src)
    if not m:
        raise AssertionError(f"function not found: {signature_regex}")
    depth, i = 1, m.end()
    while depth:
        depth += {"{": 1, "}": -1}.get(src[i], 0)
        i += 1
    return src[m.end():i - 1]


# --- host-native: the CC1101 command classifier ---------------------------------------------------
BUSY_DRIVER = r'''
#include "subghz_busy.h"
#include <cstdio>
using brucia::companion::subghzCommandNeedsRadio;
static int failures = 0;
static void expect(bool want, const char *cmd) {
    if (subghzCommandNeedsRadio(cmd) != want) { failures++; std::printf("FAIL: %s -> %d\n", cmd, !want); }
}
int main() {
    const char *radio[] = {"subghz_replay 433920 5", "subghz_tx_raw 433920 350 1,2", "subghz_jammer 433920 8",
        "subghz_config 433920", "subghz_bruteforce 0 433920000 12 5", "rf rx 433920000", "subghz rx",
        "rf tx 445533 433920000 350 10", "subghz txp Princeton 433920000 24 1 350 10", "rf scan 433 434",
        "subghz tx_from_file /a.sub", "rf tx_from_buffer", "subghz keeloqtx CAME 433920000",
        "RfSend {\"Data\":\"0x1\"}", "  rf  rx 433920000"};
    const char *free_[] = {"subghz mfcodes list", "rf mfcodes add a;b;c", "rfid read", "rfx rx",
        "subghzz rx", "ir tx NEC 00 00", "diag", "", "rf", "subghz", "rf txpx", "RfSendX"};
    for (const char *c : radio) expect(true, c);
    for (const char *c : free_) expect(false, c);
    expect(false, nullptr);
    std::printf("failures=%d\n", failures);
    return failures ? 1 : 0;
}
'''


def compile_run(header_dir):
    with tempfile.TemporaryDirectory(prefix="brucia-busy-") as tmp:
        tmp = Path(tmp)
        (tmp / "d.cpp").write_text(BUSY_DRIVER)
        exe = tmp / "busy"
        comp = subprocess.run([CXX, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-I", str(header_dir),
                               str(tmp / "d.cpp"), "-o", str(exe)], capture_output=True, text=True)
        if comp.returncode:
            return comp.returncode, comp.stderr
        run = subprocess.run([str(exe)], capture_output=True, text=True, timeout=30)
        return run.returncode, run.stdout


class SubghzBusyClassifier(unittest.TestCase):
    HEADER = SRC / "companion/subghz_busy.h"

    def test_classifier(self):
        rc, out = compile_run(self.HEADER.parent)
        self.assertEqual(0, rc, out)

    def test_companion_prefix_rule_is_load_bearing(self):
        src = self.HEADER.read_text()
        anchor = 'if (strncmp(cmd, "subghz_", 7) == 0) return true;'
        self.assertIn(anchor, src)
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "subghz_busy.h").write_text(src.replace(anchor, ""))
            rc, out = compile_run(Path(tmp))
        self.assertNotEqual(0, rc, "mutation survived: subghz_* commands not classified")

    def test_mock_mirrors_classifier(self):
        for c in ("subghz_replay 1 2", "rf rx 433920000", "subghz txp X 1 24 1", "RfSend {}"):
            self.assertTrue(subghz_needs_radio(c), c)
        for c in ("subghz mfcodes list", "rfid read", "diag"):
            self.assertFalse(subghz_needs_radio(c), c)


# --- static firmware guards ----------------------------------------------------------------------
class FirmwareGuards(unittest.TestCase):
    def test_subghz_dispatch_refuses_radio_commands_while_streaming(self):
        src = strip_comments(read("src/companion/subghz.cpp"))
        body = body_of(src, r"bool dispatchSubghzCommand\(const CommandRequest &request\)")
        guard = body.find("subghzReceiverBusy() && subghzCommandNeedsRadio(")
        self.assertGreaterEqual(guard, 0, "dispatchSubghzCommand has no CC1101 busy guard")
        self.assertIn("sendErr(id, 409,", body[guard:guard + 200])
        self.assertLess(guard, body.find('startsWith("subghz_replay ")'), "guard after the handlers")
        busy = body_of(src, r"static bool subghzReceiverBusy\(\)")
        self.assertIn("subRf || subSpec", busy)
        # dispatchSubghzCommand runs before routeCommand, so routed rf/subghz CLI verbs are covered.
        cmds = strip_comments(read("src/companion/commands.cpp"))
        self.assertLess(cmds.find("dispatchSubghzCommand,"), cmds.find("routeCommand(cmdStr, out)"))

    def test_subghz_config_reports_init_failure(self):
        src = strip_comments(read("src/companion/subghz.cpp"))
        body = body_of(src, r"static void runSubghzConfig\(")
        self.assertIn('const bool applied = initRfModule("rx"', body)
        self.assertIn("companionAckMsg(id, applied, out);", body)

    def test_lora_recv_polls_stop(self):
        body = body_of(strip_comments(read("src/companion/network.cpp")), r"static void runLoraRecv\(")
        loop = body[body.find("while ((int32_t)(millis() - end) < 0)"):]
        self.assertIn("companionPollStop()", loop, "lora_recv ignores the app's stop (up to 120 s)")

    def test_companion_idle_exit_measured_after_handling(self):
        src = strip_comments(read("src/companion/companion.cpp"))
        loop = body_of(src, r"static void runCompanionUSB\(bool headless\)")
        switch_end = loop.find('default: sendErr(id, 404, "unknown message kind"); break;')
        self.assertGreater(switch_end, 0)
        self.assertIn("lastFrame = millis();", loop[switch_end:switch_end + 400],
                      "lastFrame not refreshed after a handled frame: a ~60 s command trips the idle exit")

    def test_ble_scan_start_owner_checks(self):
        src = strip_comments(read("src/companion/bluetooth.cpp"))
        start = body_of(src, r"int bleStart\(\)")
        self.assertIn("if (g_companionHid) return 409;", start)
        self.assertIn("return 503;", start)
        self.assertLess(start.find("return 409;"), start.find("NimBLEDevice::init("))
        comp = strip_comments(read("src/companion/companion.cpp"))
        sub = comp[comp.find('strcmp(stream, "ble.scan") == 0'):comp.find('strcmp(stream, "subghz.raw") == 0')]
        self.assertIn("subBleOn = on && code == 0;", sub)
        self.assertIn("sendErr(id, 409,", sub)
        self.assertIn("sendErr(id, 503,", sub)
        self.assertNotIn("sendAck(id, true);\n", sub.replace("if (code == 0) sendAck(id, true);", ""))

    def test_ble_media_refuses_while_scanning(self):
        src = strip_comments(read("src/companion/bluetooth.cpp"))
        media = src[src.find('cmdStr.startsWith("ble_media ")'):]
        media = media[:media.find("runMediaCommandHijackHeadless(")]
        self.assertIn("} else if (subBle) {", media)
        self.assertIn('sendErr(id, 409, "ble scan active (stop the scan first)");', media)

    def test_badusb_refused_in_companion_mode(self):
        src = strip_comments(read("src/core/serial_commands/badusb_commands.cpp"))
        for name in ("badusbFileCallback", "badusbBufferCallback"):
            body = body_of(src, r"uint32_t " + name + r"\(cmd \*c\)")
            guard = body.find("badusbRefusedInCompanion()")
            self.assertGreaterEqual(guard, 0, f"{name} not refused in companion mode")
            self.assertLess(guard, body.find("ducky_startKb("), f"{name}: guard after USB HID setup")
        helper = body_of(src, r"static bool badusbRefusedInCompanion\(\)")
        self.assertIn("if (!g_companionActive) return false;", helper)

    def test_ir_tx_pin_checked_before_transmit(self):
        ir = strip_comments(read("src/companion/infrared.cpp"))
        for fn, call in ((r"static void runIrTvbgone\(", "runTvBGoneHeadless("),
                         (r"static void runIrTxRaw\(", "sendRawCommand(")):
            body = body_of(ir, fn)
            self.assertGreaterEqual(body.find("if (!irTxPinReady())"), 0, fn)
            self.assertLess(body.find("if (!irTxPinReady())"), body.find(call), fn)
        jam = ir[ir.find('cmdStr.startsWith("ir_jammer")'):]
        self.assertLess(jam.find("if (!irTxPinReady())"), jam.find("ir_jammer_headless("))
        tvb = strip_comments(read("src/modules/ir/TV-B-Gone.cpp"))
        chk = body_of(tvb, r"void checkIrTxPin\(\)")
        self.assertLess(chk.find("if (g_companionActive) return;"), chk.find("gsetIrTxPin(true)"),
                        "checkIrTxPin can open the pin menu in companion mode")
        cli = strip_comments(read("src/core/serial_commands/ir_commands.cpp"))
        for name, call in (("irTxCallback", "sendIRCommand("), ("irTxRawCallback", "sendIRCommand("),
                           ("irTxFileCallback", "txIrFile("), ("irSendCallback", "sendDecodedCommand(")):
            body = body_of(cli, r"uint32_t " + name + r"\(cmd \*c\)")
            self.assertLess(body.find("irTxRefusedHeadless()"), body.rfind(call), name)
            self.assertGreaterEqual(body.find("irTxRefusedHeadless()"), 0, name)

    def test_ir_tx_rejects_unsupported_protocol(self):
        cli = strip_comments(read("src/core/serial_commands/ir_commands.cpp"))
        body = body_of(cli, r"uint32_t irTxCallback\(cmd \*c\)")
        check = body.find("if (!irParsedProtocolSupported(protocol))")
        self.assertGreaterEqual(check, 0)
        self.assertIn("return false;", body[check:check + 160])
        # The supported list matches what sendIRCommand() actually dispatches.
        custom = strip_comments(read("src/modules/ir/custom_ir.cpp"))
        send = body_of(custom, r"void sendIRCommand\(IRCode \*code, bool hideDefaultUI\)")
        dispatched = set(re.findall(r'protocol\.equalsIgnoreCase\("([^"]+)"\)', send))
        listed = set(re.findall(r'"([A-Za-z0-9]+)"', body_of(custom, r"bool irParsedProtocolSupported\(")))
        self.assertEqual(dispatched, listed)

    def test_ir_ac_state_bounded_before_stack_buffer(self):
        custom = strip_comments(read("src/modules/ir/custom_ir.cpp"))
        body = body_of(custom, r"bool sendDecodedCommand\(")
        guard = body.find("if (stateLen == 0 || (value.length() + 2) / 3 > stateLen) return false;")
        self.assertGreaterEqual(guard, 0, "AC state parse can overflow uint8_t state[bits / 8]")
        self.assertLess(guard, body.find("uint8_t state[bits / 8]"))

    def test_emv_wait_bounded_and_cancel_not_parsed(self):
        nfc = strip_comments(read("src/companion/nfc.cpp"))
        body = body_of(nfc, r"static void runNfcEmvRead\(uint32_t id\)")
        self.assertLess(body.find("emv.setHeadlessWait(NFC_EMV_WAIT_MS, companionPollStop);"),
                        body.find("emv.readMaskedSummary(out)"))
        emv = strip_comments(read("src/modules/rfid/emv_reader.cpp"))
        aid = body_of(emv, r"std::vector<uint8_t> EMVReader::emv_ask_for_aid\(\)")
        self.assertIn("_waitMs && millis() - waitStart >= _waitMs", aid)
        self.assertIn("_pollStop && _pollStop()", aid)
        card = body_of(emv, r"EMVCard EMVReader::read_emv_card\(\)")
        self.assertEqual(2, card.count("res.parsed = false;\n        return res;"))

    def test_espnow_keeps_wifi_owner(self):
        body = body_of(strip_comments(read("src/companion/system.cpp")), r"static void runEspnowSend\(")
        self.assertNotIn("WiFi.disconnect(", body)
        self.assertNotRegex(body, r"\n\s*WiFi\.mode\(WIFI_STA\);")  # only under the WIFI_MODE_NULL check
        self.assertIn("if (mode == WIFI_MODE_NULL) WiFi.mode(WIFI_STA);", body)
        self.assertIn("else if (mode == WIFI_MODE_AP) WiFi.enableSTA(true);", body)


# --- protocol level against the mock ---------------------------------------------------------------
class MockProtocol(unittest.TestCase):
    def setUp(self):
        from test_protocol_e2e import FrameClient
        self.master, slave = pty.openpty()
        self.dev = MockDevice(poll_interval=0.002)
        self.stop = threading.Event()
        self.th = threading.Thread(target=self.dev.serve, args=(self.master, self.stop), daemon=True)
        self.th.start()
        self.cli = FrameClient(os.ttyname(slave))
        self.assertIsNotNone(self.cli.caps(1))

    def tearDown(self):
        self.cli.close()
        self.stop.set()
        self.th.join(timeout=5)

    def test_cc1101_busy_while_receive_stream_runs(self):
        self.cli.send(SUB, 2, {"stream": "subghz.raw", "on": True})
        self.assertIsNotNone(self.cli.reply(2, ACK))
        self.cli.send(3, 3, {"cmd": "rf rx 433920000"})  # 3 = CMD
        busy = self.cli.reply(3, ERR)
        self.assertIsNotNone(busy)
        self.assertEqual(409, busy["d"]["code"])
        self.assertIn("sub-ghz receiver busy", busy["d"]["msg"])
        self.assertTrue(self.cli.cmd(4, "subghz mfcodes list")["ok"])  # file only: allowed
        self.cli.send(SUB, 5, {"stream": "subghz.raw", "on": False})
        self.assertIsNotNone(self.cli.reply(5, ACK))
        self.assertIsNotNone(self.cli.cmd(6, "subghz_config 433920"))  # radio free again: ACK

    def test_lora_recv_answers_ping_and_ends_on_stop(self):
        t0 = time.time()
        self.cli.send(3, 10, {"cmd": "lora_recv 60"})
        time.sleep(0.1)
        self.cli.send(PING, 11, {})
        self.assertIsNotNone(self.cli.reply(11, PONG), "PING unanswered during lora_recv")
        self.cli.send(3, 12, {"cmd": "stop"})
        done = self.cli.reply(10, ACK, timeout=5)
        self.assertIsNotNone(done)
        self.assertIn("(stopped)", done["d"]["out"])
        self.assertLess(time.time() - t0, 10)


if __name__ == "__main__":
    unittest.main(verbosity=2)
