#!/usr/bin/env python3
"""No unguarded Serial output in module code the companion handlers reach.

In companion mode the USB serial carries length-prefixed frames; a Serial.print from a module
lands between (or, from another task, inside) frames. The first hardware run lost framing on
"cc1101 Connection OK" from initRfModule(). Module prints on companion paths therefore use the
`if (!g_companionActive)` guard (Bruce's own behaviour outside companion mode is unchanged).

This scans the module files whose functions src/companion/*.cpp calls for subghz.raw,
subghz.spectrum, Sub-GHz TX/RX, NFC, IR, BLE (spam, BadBLE), FM and NRF24, and fails on any
Serial.print/println/printf/write that is
  - not guarded on its own line or by an enclosing `if (...!g_companionActive...) { }` block,
  - not inside a compiled-out `#ifdef ..._DEBUG` region, and
  - not in ALLOWLIST (file, function) with a reason.
Allowlist entries that no longer match anything also fail, so the list cannot rot.

Out of scope here (documented in skills/brucia-maintenance/references/backlog.md): speaker/mic
(modules/others), Ethernet, Wi-Fi portal setup and Bruce CLI commands the app routes through
the companion's `CMD` passthrough; the host readers resync over such text.

    python3 tools/test_companion_print_hygiene.py
"""
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Module files reached from src/companion/*.cpp for the radio/NFC/IR/BLE/FM handlers.
SCANNED = (
    "src/modules/rf/rf_utils.cpp",          # subghz.raw/spectrum/capture/TX: initRfModule, setMHZ, setup_rf_rx
    "src/modules/rf/rf_send.cpp",           # subghz TX: sendRfCommand
    "src/modules/ir/ir_read.cpp",           # ir_rx: IrRead::loop_headless
    "src/modules/ir/custom_ir.cpp",         # ir_tx_raw: sendRawCommand
    "src/modules/rfid/emv_reader.cpp",      # nfc_emv_read: EMVReader::readMaskedSummary
    "src/modules/rfid/PN532.cpp",           # nfc read/emulate/keycheck: PN532 driver
    "src/modules/rfid/srix_tool.cpp",       # nfc_srix_read: SRIXTool::read_tag_headless
    "src/modules/rfid/srix_tool.h",
    "src/modules/rfid/ST25R3916.cpp",       # nfc on ST25R3916 boards
    "src/modules/ble/ble_spam.cpp",         # ble_spam*: runBle*SpamBounded
    "src/modules/badusb_ble/ducky_typer.cpp",  # ble_badble: ducky_startKb, safeCleanupDuckyBLE
    "src/modules/fm/fm.cpp",                # fm_tx / fm_audio: fm_begin
    "src/modules/NRF24/nrf_common.cpp",     # nrf24_*: nrf_start
)

# (file, function) -> reason. Prints in these functions are not on a companion path.
ALLOWLIST = {
    ("src/modules/rf/rf_send.cpp", "readSubFile"): "Bruce .sub menu / CLI `subghz tx_from_file`; not called by src/companion",
    ("src/modules/rf/rf_send.cpp", "txSubFile"): "Bruce .sub menu / CLI `subghz tx_from_file`; not called by src/companion",
    ("src/modules/ir/ir_read.cpp", "IrRead::append_to_file_str"): "on-device IR save UI; loop_headless does not call it",
    ("src/modules/ir/custom_ir.cpp", "txIrFile"): "IR file menu / CLI `ir tx_from_file`; not called by src/companion",
    ("src/modules/ir/custom_ir.cpp", "sendNECCommand"): "CLI `ir tx` and IR menus; src/companion only calls sendRawCommand",
    ("src/modules/ir/custom_ir.cpp", "sendNECextCommand"): "CLI `ir tx` and IR menus; src/companion only calls sendRawCommand",
    ("src/modules/ir/custom_ir.cpp", "sendRC5Command"): "CLI `ir tx` and IR menus; src/companion only calls sendRawCommand",
    ("src/modules/ir/custom_ir.cpp", "sendRC6Command"): "CLI `ir tx` and IR menus; src/companion only calls sendRawCommand",
    ("src/modules/ir/custom_ir.cpp", "sendSamsungCommand"): "CLI `ir tx` and IR menus; src/companion only calls sendRawCommand",
    ("src/modules/ir/custom_ir.cpp", "sendSonyCommand"): "CLI `ir tx` and IR menus; src/companion only calls sendRawCommand",
    ("src/modules/ir/custom_ir.cpp", "sendKaseikyoCommand"): "CLI `ir tx` and IR menus; src/companion only calls sendRawCommand",
    ("src/modules/ir/custom_ir.cpp", "sendDecodedCommand"): "CLI `ir tx` and IR menus; src/companion only calls sendRawCommand",
    ("src/modules/ir/custom_ir.cpp", "chooseCmdIrFile"): "on-device IR file picker; not called by src/companion",
    ("src/modules/rfid/emv_reader.cpp", "EMVReader::setup"): "interactive EMV menu; the companion constructs EMVReader(false) and never runs setup()",
    ("src/modules/rfid/emv_reader.cpp", "EMVReader::emv_ask_for_app_name"): "not called by read_emv_card() (no caller in the tree)",
    ("src/modules/ble/ble_spam.cpp", "ibeacon"): "on-device iBeacon menu; the companion ble_ibeacon handler drives NimBLE itself",
    ("src/modules/badusb_ble/ducky_typer.cpp", "ducky_setup"): "BadUSB/BadBLE file menu; companion ble_badble calls ducky_startKb/key_input directly",
    ("src/modules/fm/fm.cpp", "fm_scan"): "on-device FM scan menu; not called by src/companion",
    ("src/modules/fm/fm.cpp", "fm_setup"): "on-device FM broadcast menu; fm_tx_companion calls fm_begin, not fm_setup",
    ("src/modules/NRF24/nrf_common.cpp", "nrf_setMode"): "interactive SPI/UART chooser; companion NRF24 calls nrf_start(NRF_MODE_SPI) directly",
}

PRINT = re.compile(r"\bSerial\.(print|println|printf|write)\s*\(")
FUNC = re.compile(r"^[A-Za-z_][\w:<>\*&\s,]*?\b([A-Za-z_]\w*(?:::~?\w+)?)\s*\(([^;{}]*)\)\s*(?:const\s*)?(?:override\s*)?\{",
                  re.M)
GUARD = re.compile(r"!\s*g_companionActive\b")


def strip_comments(s):
    """Comments blanked to spaces, keeping offsets and line numbers."""
    s = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group()), s, flags=re.S)
    return re.sub(r"//[^\n]*", lambda m: " " * len(m.group()), s)


def debug_lines(raw):
    """Line numbers compiled only when a *_DEBUG macro is defined (positive branch)."""
    out, stack = set(), []
    for n, line in enumerate(raw.splitlines(), 1):
        t = line.strip()
        if re.match(r"#\s*if(n?def)?\b", t):
            stack.append(bool(re.match(r"#\s*(ifdef|if)\s+\w*DEBUG", t)))
        elif re.match(r"#\s*(else|elif)\b", t) and stack:
            stack[-1] = False
        elif re.match(r"#\s*endif\b", t) and stack:
            stack.pop()
        elif any(stack):
            out.add(n)
        if re.match(r"#\s*define\s+\w+\(", t) and any(stack):
            out.add(n)
    return out


def functions(code):
    spans = []
    for m in FUNC.finditer(code):
        if m.group(1) in ("if", "for", "while", "switch", "return"):
            continue
        depth, i = 0, m.end() - 1
        while i < len(code):
            if code[i] == "{":
                depth += 1
            elif code[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        spans.append((m.group(1), m.start(), i))
    return spans


def guarded_by_block(code, pos):
    """True if an enclosing brace block is opened by a condition with !g_companionActive."""
    depth = 0
    for i in range(pos - 1, -1, -1):
        c = code[i]
        if c == "}":
            depth += 1
        elif c == "{":
            if depth:
                depth -= 1
                continue
            head = code[max(0, i - 200):i]
            last = head.rsplit(";", 1)[-1].rsplit("}", 1)[-1]
            if re.search(r"\bif\s*\(.*" + GUARD.pattern, last, re.S):
                return True
    return False


def scan(rel):
    raw = (ROOT / rel).read_text(errors="replace")
    code = strip_comments(raw)
    dbg = debug_lines(raw)
    spans = functions(code)
    found = []
    for m in PRINT.finditer(code):
        line_no = code.count("\n", 0, m.start()) + 1
        line_start = code.rfind("\n", 0, m.start()) + 1
        before = code[line_start:m.start()]
        owner = [name for name, a, b in spans if a <= m.start() <= b]
        func = owner[-1] if owner else "?"
        if GUARD.search(before) or line_no in dbg or guarded_by_block(code, m.start()):
            continue
        found.append((rel, line_no, func, raw.splitlines()[line_no - 1].strip()))
    return found


class PrintHygiene(unittest.TestCase):
    def test_scanned_files_exist(self):
        for rel in SCANNED:
            self.assertTrue((ROOT / rel).is_file(), rel)

    def test_no_unguarded_prints_on_companion_paths(self):
        bad = []
        for rel in SCANNED:
            bad += [f for f in scan(rel) if (f[0], f[2]) not in ALLOWLIST]
        self.assertEqual(bad, [], "unguarded Serial output on a companion path; add "
                         "`if (!g_companionActive)` or an ALLOWLIST entry with a reason:\n" +
                         "\n".join(f"{f}:{n} {fn}: {t}" for f, n, fn, t in bad))

    def test_allowlist_is_live_and_reasoned(self):
        hits = {(f, fn) for rel in SCANNED for f, _, fn, _ in scan(rel)}
        for key, reason in ALLOWLIST.items():
            self.assertIn(key[0], SCANNED, key)
            self.assertIn(key, hits, f"stale allowlist entry (nothing unguarded left): {key}")
            self.assertGreater(len(reason), 20, key)

    def test_companion_calls_the_guarded_entry_points(self):
        # The functions this file treats as companion paths are still the ones src/companion calls.
        comp = "".join(p.read_text() for p in sorted((ROOT / "src/companion").glob("*.cpp")))
        for name in ("initRfModule", "setMHZ", "setup_rf_rx", "sendRfCommand", "loop_headless",
                     "sendRawCommand", "readMaskedSummary", "read_tag_headless", "runBleSpamBounded",
                     "ducky_startKb", "safeCleanupDuckyBLE", "fm_tx_companion", "nrf_start"):
            self.assertRegex(comp, r"\b" + name + r"\s*\(", name)

    def test_detector_catches_regressions(self):
        code = strip_comments('void f() {\n  Serial.println("x");\n  if (!g_companionActive) Serial.println("y");\n'
                              '  if (!g_companionActive) {\n    Serial.printf("z");\n  }\n}\n')
        unguarded = [m for m in PRINT.finditer(code)
                     if not GUARD.search(code[code.rfind("\n", 0, m.start()) + 1:m.start()])
                     and not guarded_by_block(code, m.start())]
        self.assertEqual(len(unguarded), 1)
        self.assertEqual(debug_lines("#ifdef X_DEBUG\nSerial.print(1);\n#else\nSerial.print(2);\n#endif\n"), {2})


if __name__ == "__main__":
    unittest.main(verbosity=2)
