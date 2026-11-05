#!/usr/bin/env python3
"""No unguarded Serial output in code the companion handlers reach.

In companion mode the USB serial carries length-prefixed frames; a Serial.print from a module
lands between (or, from another task, inside) frames. The first hardware run lost framing on
"cc1101 Connection OK" from initRfModule(). Two companion-safe forms are accepted:
  - `if (!g_companionActive) Serial.print...` (debug prints; batch 8 convention), also with the
    guard in a multi-line `if (...)` head or an enclosing `if (...!g_companionActive...) { }` block;
  - `usbConsole().print...` (include/globals.h): Serial itself outside companion mode, so Bruce's
    output is byte-identical; in companion mode the text is captured into the ACK of a CLI command
    the app routed through `CMD` (when printed by it on the companion task) and dropped otherwise.
Bare printf/stdout, esp_log and the ROM printf (ESP32-S3 USB flag, ESP32 putc hooks, the latter
checked against the ROM ELFs when tool-esp-rom-elfs is installed) are muted in companion.cpp; see
test_companion_mutes_non_serial_output and test_esp32_rom_putc_hooks. ArduinoJson writes straight
to Serial (`serializeJson*(doc, Serial)`) anywhere under src/ need the same guard
(test_json_to_serial_is_guarded).
The input side (nothing but the frame reader reads Serial) is tools/test_companion_serial_input.py.

This scans the module files whose functions src/companion/*.cpp calls (radio/NFC/IR/BLE/FM/NRF24,
speaker/mic, Ethernet, LoRa, Evil Portal/karma, Wi-Fi join, brucegotchi, scripts), every Bruce CLI
command file (src/core/serial_commands/*.cpp, reached through routeCommand) and the module
functions those commands call, and fails on any Serial.print/println/printf/write that is
  - not guarded as above,
  - not inside a compiled-out `#ifdef ..._DEBUG` region, and
  - not in ALLOWLIST (file, function) with a reason.
Allowlist entries that no longer match anything also fail, so the list cannot rot.

Not covered (documented in skills/brucia-maintenance/references/backlog.md): interactive Bruce
apps a CLI command can open (`loader`, file manager, menus; their prompts are
tools/test_companion_prompts.py), third-party libraries under .pio/libdeps, and ROM printf on
targets other than the ESP32 and ESP32-S3 (the ESP32-C5 boards).

    python3 tools/test_companion_print_hygiene.py
"""
import re
import shutil
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Files reached from src/companion/*.cpp handlers and from Bruce CLI commands routed through CMD.
SCANNED = (
    "src/modules/rf/rf_utils.cpp",          # subghz.raw/spectrum/capture/TX: initRfModule, setMHZ, setup_rf_rx
    "src/modules/rf/rf_send.cpp",           # subghz TX: sendRfCommand; CLI subghz tx_from_file
    "src/modules/rf/rf_scan.cpp",           # CLI subghz rx / scan: rfReceiveSignal, rf_scan
    "src/modules/ir/ir_read.cpp",           # ir_rx, CLI ir rx: IrRead::loop_headless
    "src/modules/ir/custom_ir.cpp",         # ir_tx_raw: sendRawCommand; CLI ir tx / tx_from_file
    "src/modules/rfid/emv_reader.cpp",      # nfc_emv_read: EMVReader::readMaskedSummary
    "src/modules/rfid/PN532.cpp",           # nfc read/emulate/keycheck: PN532 driver
    "src/modules/rfid/srix_tool.cpp",       # nfc_srix_read: SRIXTool::read_tag_headless
    "src/modules/rfid/srix_tool.h",
    "src/modules/rfid/ST25R3916.cpp",       # nfc on ST25R3916 boards
    "src/modules/ble/ble_spam.cpp",         # ble_spam*: runBle*SpamBounded
    "src/modules/badusb_ble/ducky_typer.cpp",  # ble_badble: ducky_startKb, safeCleanupDuckyBLE
    "lib/Bad_Usb_Lib/BleKeyboard.cpp",      # ble_hid_* / ble_badble: NimBLE host-task callbacks
    "src/core/radio_mem.h",                 # ble_badble / BLE paths: radioHasMemForBle
    "src/modules/fm/fm.cpp",                # fm_tx / fm_audio: fm_begin
    "src/modules/NRF24/nrf_common.cpp",     # nrf24_*: nrf_start
    "src/modules/NRF24/nrf_mousejack.cpp",  # nrf24_mousejack: nrf_mousejack_headless
    "src/modules/others/audio.cpp",         # speaker_*: playTone/playDtmf/playAudioFile; CLI play/tts
    "src/modules/others/mic.cpp",           # mic_record: mic_record_wav_to_path
    "src/modules/ethernet/EthernetHelper.cpp",  # eth_*: EthernetHelper::setup + event handler
    "src/modules/ethernet/DHCPStarvation.cpp",  # eth_dhcp_starve
    "src/modules/ethernet/MACFlooding.cpp",     # eth_mac_flood
    "src/modules/lora/LoRaRF.cpp",          # lora_*: lora_companion_*
    "src/modules/wifi/evil_portal.cpp",     # evil_portal_start, karma portals
    "src/modules/wifi/karma_attack.cpp",    # karma: karma_run_headless
    "src/modules/wifi/jam_detect.cpp",      # jam_detect: jam_detect_run
    "src/modules/wifi/wifi_atks.cpp",       # wifi_beacon_spam / wifi_deauth / wifi_handshake
    "src/modules/pwnagotchi/pwngrid.cpp",   # brucegotchi: promiscuous callback
    "src/modules/wifi/sniffer.cpp",         # CLI sniffer
    "src/modules/wifi/tcp_utils.cpp",       # CLI listen
    "src/core/wifi/wifi_common.cpp",        # wifi_connect; CLI wifi on/add
    "src/core/wifi/wifi_mac.cpp",
    "src/core/wifi/webInterface.cpp",       # CLI webui; Wi-Fi features stop the web UI
    "src/core/display.cpp",                 # displayError/Warning/Info/Success/TextLine, loopOptions
    "src/core/sd_functions.cpp",            # config_save (setupSdCard, copyToFs), CLI storage
    "src/core/settings.cpp",                # ir_tvbgone: gsetIrTxPin
    "src/core/passwords.cpp",               # CLI crypto decrypt_file
    "src/core/utils.cpp",                   # script_run: printMemoryUsage
    "src/modules/bjs_interpreter/helpers_js.cpp",   # script_run, CLI js: console output
    "src/modules/bjs_interpreter/serial_js.cpp",
    "src/modules/bjs_interpreter/wifi_js.cpp",
    "src/modules/bjs_interpreter/display_js.cpp",
) + tuple(sorted(str(p.relative_to(ROOT)) for p in (ROOT / "src/core/serial_commands").glob("*.cpp")))

_MENU = "interactive on-device menu/app; not called by src/companion or a headless CLI path"
# (file, function) -> reason. Prints in these functions are not on a companion path.
ALLOWLIST = {
    ("src/modules/rf/rf_scan.cpp", "RFScan::fast_scan"): "interactive RFScan screen; CLI subghz rx/scan use rfReceiveSignal/rf_scan",
    ("src/modules/rf/rf_scan.cpp", "RFScan::decode_signal"): "interactive RFScan screen; CLI subghz rx/scan use rfReceiveSignal/rf_scan",
    ("src/modules/rf/rf_scan.cpp", "RFScan::read_raw"): "interactive RFScan screen; CLI subghz rx/scan use rfReceiveSignal/rf_scan",
    ("src/modules/rf/rf_scan.cpp", "RFScan::save_signal"): "interactive RFScan screen; CLI subghz rx/scan use rfReceiveSignal/rf_scan",
    ("src/modules/rf/rf_scan.cpp", "rfSaveSignal"): "only caller is RFScan::save_signal (interactive save)",
    ("src/modules/ir/ir_read.cpp", "IrRead::append_to_file_str"): "the print is in the UNKNOWN-protocol branch; loop_headless returns before it (not raw) or takes the raw branch",
    ("src/modules/ir/custom_ir.cpp", "chooseCmdIrFile"): "on-device IR file picker; not called by src/companion or the CLI",
    ("src/modules/rfid/emv_reader.cpp", "EMVReader::setup"): "interactive EMV menu; the companion constructs EMVReader(false) and never runs setup()",
    ("src/modules/rfid/emv_reader.cpp", "EMVReader::emv_ask_for_app_name"): "not called by read_emv_card() (no caller in the tree)",
    ("src/modules/ble/ble_spam.cpp", "ibeacon"): "on-device iBeacon menu; the companion ble_ibeacon handler drives NimBLE itself",
    ("src/modules/badusb_ble/ducky_typer.cpp", "ducky_setup"): "BadUSB/BadBLE file menu; companion ble_badble calls ducky_startKb/key_input directly",
    ("src/modules/fm/fm.cpp", "fm_scan"): "on-device FM scan menu; not called by src/companion",
    ("src/modules/fm/fm.cpp", "fm_setup"): "on-device FM broadcast menu; fm_tx_companion calls fm_begin, not fm_setup",
    ("src/modules/NRF24/nrf_common.cpp", "nrf_setMode"): "interactive SPI/UART chooser; companion NRF24 calls nrf_start(NRF_MODE_SPI) directly",
    ("src/modules/NRF24/nrf_mousejack.cpp", "mj_parseDuckyLine"): "DuckyScript file injection from the on-device MouseJack menu; headless types text via mj_typeString",
    ("src/modules/others/mic.cpp", "mic_test_one_task"): "on-device mic spectrum app; " + _MENU,
    ("src/modules/others/mic.cpp", "mic_test"): "on-device mic spectrum app; " + _MENU,
    ("src/modules/others/mic.cpp", "mic_record_app"): "on-device recorder UI; companion mic_record calls mic_record_wav_to_path",
    ("src/modules/lora/LoRaRF.cpp", "startLoraRadio"): "LoRa chat app (lorachat); companion uses lora_companion_begin",
    ("src/modules/lora/LoRaRF.cpp", "reciveMessage"): "LoRa chat app (lorachat); companion polls with lora_companion_poll",
    ("src/modules/lora/LoRaRF.cpp", "render"): "LoRa chat app (lorachat); " + _MENU,
    ("src/modules/lora/LoRaRF.cpp", "sendmsg"): "LoRa chat app (lorachat); companion sends with lora_companion_send",
    ("src/modules/lora/LoRaRF.cpp", "upress"): "LoRa chat app (lorachat); " + _MENU,
    ("src/modules/lora/LoRaRF.cpp", "downpress"): "LoRa chat app (lorachat); " + _MENU,
    ("src/modules/lora/LoRaRF.cpp", "lorachat"): "LoRa chat app entry; " + _MENU,
    ("src/modules/wifi/karma_attack.cpp", "saveProbesToPCAP"): "no caller in the tree; karma_run_headless never reaches it",
    ("src/modules/wifi/wifi_atks.cpp", "capture_handshake"): "on-device handshake screen; companion wifi_handshake uses capture_handshake_bounded",
    ("src/modules/wifi/sniffer.cpp", "printAddress"): "no caller in the tree",
    ("src/modules/wifi/tcp_utils.cpp", "clientTCP"): "Wi-Fi menu 'Client TCP' only; no CLI command or companion handler",
    ("src/core/display.cpp", "showJpeg"): "image viewer / menu artwork: " + _MENU,
    ("src/core/display.cpp", "drawBmp"): "image viewer / menu artwork: " + _MENU,
    ("src/core/display.cpp", "drawPNG"): "image viewer / menu artwork: " + _MENU,
    ("src/core/sd_functions.cpp", "deleteFromSd"): "file manager (loopSD); companion storage deletes via its own fs_* handlers",
    ("src/core/sd_functions.cpp", "readFs"): "file manager (loopSD); " + _MENU,
    ("src/core/sd_functions.cpp", "loopSD"): "file manager; " + _MENU,
    ("src/core/settings.cpp", "runClockLoop"): "on-device clock screen; " + _MENU,
    ("src/core/settings.cpp", "enableBLEAPI"): "settings menu toggle for the BLE API; " + _MENU,
}

PRINT = re.compile(r"\bSerial\.(print|println|printf|write)\s*\(")
FUNC = re.compile(r"^[A-Za-z_][\w:<>\*&\s,]*?\b([A-Za-z_]\w*(?:::~?\w+)?)\s*\(([^;{}]*)\)\s*(?:const\s*)?(?:override\s*)?\{",
                  re.M)
GUARD = re.compile(r"!\s*g_companionActive\b")


_TOKENS = re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'', re.S)


def strip_comments(s):
    """Comments blanked to spaces, keeping offsets and line numbers. One left-to-right pass, so a
    `/*` inside a line comment (`stop/*_stop`) or a string does not open a block comment."""
    return _TOKENS.sub(lambda m: re.sub(r"[^\n]", " ", m.group()) if m.group().startswith("/") else m.group(), s)


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


def guarded_by_statement(code, pos):
    """True if the print is the body of a brace-less `if (...)` whose condition (possibly spanning
    several lines) contains !g_companionActive."""
    start = max(code.rfind(";", 0, pos), code.rfind("{", 0, pos), code.rfind("}", 0, pos)) + 1
    head = code[start:pos]
    return bool(re.match(r"\s*(else\s+)?if\s*\(", head)) and bool(GUARD.search(head))


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
        if GUARD.search(before) or guarded_by_statement(code, m.start()) or line_no in dbg \
                or guarded_by_block(code, m.start()):
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
                     "ducky_startKb", "safeCleanupDuckyBLE", "fm_tx_companion", "nrf_start",
                     "nrf_mousejack_headless", "playTone", "playDtmf", "mic_record_wav_to_path",
                     "lora_companion_begin", "lora_companion_send", "karma_run_headless",
                     "jam_detect_run", "brucegotchi_run_headless", "capture_handshake_bounded",
                     "run_bjs_script_headless", "_connectToWifiNetwork", "serialCli.parse"):
            self.assertRegex(comp, r"\b" + re.escape(name) + r"\s*\(", name)
        self.assertRegex(comp, r"new EthernetHelper\(\)")
        self.assertRegex(comp, r"new EvilPortal\(")

    def test_cli_capture_and_usb_console(self):
        # routeCommand registers the capture device + task that usbConsole() appends to, and
        # usbConsole() is Serial outside companion mode (Bruce output unchanged).
        cmds = (ROOT / "src/companion/commands.cpp").read_text()
        self.assertIn("Print &usbConsole()", (ROOT / "include/globals.h").read_text())
        self.assertRegex(cmds, r"Print &usbConsole\(\) \{\s*if \(!g_companionActive\) return Serial;")
        route = cmds[cmds.index("static bool routeCommand"):]
        route = route[:route.index("\n}\n")]
        for needle in ("g_cliCaptureTask = xTaskGetCurrentTaskHandle()", "g_cliCapture = &cap",
                       "serialCli.parse(cmd)", "g_cliCapture = nullptr"):
            self.assertIn(needle, route)
        self.assertLess(route.index("g_cliCapture = &cap"), route.index("serialCli.parse(cmd)"))
        self.assertLess(route.index("serialCli.parse(cmd)"), route.index("g_cliCapture = nullptr"))
        self.assertRegex(cmds, r"cap && xTaskGetCurrentTaskHandle\(\) == g_cliCaptureTask")

    def test_companion_mutes_non_serial_output(self):
        # stdout/stderr (bare printf, esp_log default writer), esp_log and the ESP32-S3 ROM printf
        # reach the same wire (console UART0 / secondary USB-Serial-JTAG); muted on entry and
        # restored on exit, with the order that keeps pre-companion output and drops companion-era
        # buffered output.
        src = (ROOT / "src/companion/companion.cpp").read_text()
        for needle in ("esp_log_set_vprintf(companionMutedLog)", "f->_write = fn", "f->_write = prev",
                       "swapStdioWrite(stdout,", "swapStdioWrite(stderr,", "g_usb_print = false",
                       "g_usb_print = g_prevRomUsbPrint"):
            self.assertIn(needle, src)
        on = src.index("muteNonSerialOutput(true)")
        off = src.index("muteNonSerialOutput(false)")
        self.assertLess(on, src.index("g_companionActive = true", on))
        self.assertLess(off, src.index("g_companionActive = false", off))
        self.assertEqual(src.count("muteNonSerialOutput(true)"), 1)
        self.assertEqual(src.count("muteNonSerialOutput(false)"), 1)

    def test_json_to_serial_is_guarded(self):
        # serializeJson*(doc, Serial) is a raw print too; the pins file used to be dumped onto the
        # frame stream by every pin setter (save -> load). Guarded like a print, or by a companion
        # branch that returns first in the same function.
        pat = re.compile(r"\bserializeJson\w*\s*\([^;]*,\s*Serial\s*\)")
        found, bad = 0, []
        for path in sorted((ROOT / "src").rglob("*")):
            if path.suffix not in (".c", ".cpp", ".h") or not path.is_file():
                continue
            code = strip_comments(path.read_text(errors="replace"))
            spans = functions(code)
            for m in pat.finditer(code):
                found += 1
                if guarded_by_statement(code, m.start()) or guarded_by_block(code, m.start()):
                    continue
                owner = [(a, b) for _, a, b in spans if a <= m.start() <= b]
                head = code[owner[-1][0]:m.start()] if owner else ""
                if re.search(r"\bif\s*\(\s*g_companionActive\s*\)\s*\{[^{}]*?\breturn\b", head):
                    continue
                bad.append(f"{path.relative_to(ROOT)}:{code.count(chr(10), 0, m.start()) + 1}")
        self.assertGreaterEqual(found, 3)
        self.assertEqual(bad, [], "ArduinoJson to Serial without a companion guard:\n" + "\n".join(bad))

    def test_esp32_rom_putc_hooks(self):
        # ESP32 (non-S3): ROM printf is muted through its two putc hooks, read at fixed ROM .bss
        # addresses; the firmware verifies them by reading back its own sink before counting as muted.
        src = (ROOT / "src/companion/companion.cpp").read_text()
        addrs = {name: int(v, 16) for name, v in
                 re.findall(r"kRom(Putc[12]) = reinterpret_cast<RomPutc \*>\((0x[0-9a-fA-F]+)\)", src)}
        self.assertEqual(set(addrs), {"Putc1", "Putc2"})
        body = src[src.index("static void muteRomPutc(bool mute)"):]
        body = body[:body.index("\n}\n") + 3]
        sink = body.index("ets_install_putc1(romPutcSink);")
        self.assertLess(body.index("RomPutc prev1 = *hook1, prev2 = *hook2;"), sink)
        self.assertIn("if (*hook1 == romPutcSink && *hook2 == romPutcSink)", body[sink:])
        self.assertIn("uart_install_putc();", body)                       # unknown layout: Arduino's hooks back
        self.assertIn("if (*hook1 == romPutcSink) ets_install_putc1(g_prevRomPutc1);", body)
        self.assertIn("if (*hook2 == romPutcSink) ets_install_putc2(g_prevRomPutc2);", body)
        mute = src[src.index("static void muteNonSerialOutput(bool mute)"):]
        self.assertRegex(mute, r"#elif CONFIG_IDF_TARGET_ESP32\s+muteRomPutc\(true\);")
        self.assertRegex(mute, r"#elif CONFIG_IDF_TARGET_ESP32\s+muteRomPutc\(false\);")
        # esp_rom_printf is the ROM ets_printf on the ESP32 (so one pair of hooks covers both).
        libs = Path.home() / ".platformio/packages/framework-arduinoespressif32-libs/esp32/ld/esp32.rom.api.ld"
        if libs.exists():
            self.assertRegex(libs.read_text(), r"PROVIDE \( esp_rom_printf\s*= ets_printf \);")
        roms = Path.home() / ".platformio/packages/tool-esp-rom-elfs"
        elfs = [roms / "esp32_rev0_rom.elf", roms / "esp32_rev300_rom.elf"]
        nm = shutil.which("xtensa-esp32-elf-nm") or next(
            (str(p) for p in (Path.home() / ".platformio/packages").glob("toolchain-xtensa-esp*/bin/xtensa-esp32-elf-nm")), None)
        if not nm or not all(e.exists() for e in elfs):
            self.skipTest("ESP32 ROM ELFs or xtensa nm not installed; addresses not cross-checked")
        for elf in elfs:
            syms = {}
            for line in subprocess.run([nm, str(elf)], capture_output=True, text=True, check=True).stdout.splitlines():
                parts = line.split()
                if len(parts) == 3:
                    syms[parts[2]] = int(parts[0], 16)
            self.assertEqual(syms.get("_putc1"), addrs["Putc1"], elf.name)
            self.assertEqual(syms.get("_putc2"), addrs["Putc2"], elf.name)
            self.assertEqual(syms.get("ets_install_putc1"), 0x40007d18, elf.name)  # esp32.rom.ld
            self.assertEqual(syms.get("ets_install_putc2"), 0x40007d38, elf.name)

    def test_detector_catches_regressions(self):
        code = strip_comments('void f() {\n  Serial.println("x");\n  if (!g_companionActive) Serial.println("y");\n'
                              '  if (!g_companionActive) {\n    Serial.printf("z");\n  }\n}\n')
        unguarded = [m for m in PRINT.finditer(code)
                     if not GUARD.search(code[code.rfind("\n", 0, m.start()) + 1:m.start()])
                     and not guarded_by_block(code, m.start())]
        self.assertEqual(len(unguarded), 1)
        multi = strip_comments('void g() {\n  if (err != 0 && !g_companionActive)\n    Serial.printf("e");\n'
                               '  if (err != 0)\n    Serial.printf("x");\n  usbConsole().println("ok");\n}\n')
        flagged = [m for m in PRINT.finditer(multi) if not guarded_by_statement(multi, m.start())]
        self.assertEqual(len(flagged), 1)
        self.assertIn('"x"', multi[flagged[0].start():flagged[0].start() + 20])
        self.assertEqual(debug_lines("#ifdef X_DEBUG\nSerial.print(1);\n#else\nSerial.print(2);\n#endif\n"), {2})


if __name__ == "__main__":
    unittest.main(verbosity=2)
