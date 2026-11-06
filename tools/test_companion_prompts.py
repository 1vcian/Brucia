#!/usr/bin/env python3
"""On-device prompts fail fast in companion mode instead of waiting for a person at the device.

A script run with `script_run` (or a CLI command the app routes) executes while companion mode owns
the device: nobody is pressing its buttons, and the script_run execution bound (a QuickJS interrupt
checked between bytecodes) cannot interrupt a native wait. A prompt there kept the interpreter task,
and with it every later `script_run`, busy, or the companion loop itself stuck, and the device looked
hung to the app.

Checked here (static, sibling of test_companion_serial_input.py):
  - every function in src/modules/bjs_interpreter/ that calls a blocking prompt helper (PROMPT:
    keyboard, menus, dialogs, pickers, viewers, Wi-Fi network menu, wait-for-key loops) calls
    `bjs_prompt_unavailable(...)` first, which returns true only in companion mode, or is in
    JS_ALLOWLIST with a reason;
  - every Bruce CLI file (src/core/serial_commands/, reached by the app's routed CMDs and by a
    script's serial.cmd() and CLI-backed helpers) calls a prompt helper only after an
    `if (g_companionActive) ... return` in the same function, or is in CLI_ALLOWLIST; a mode that
    runs until a key is pressed (CLI_MODE: Web UI, sniffer, TCP listen, sleep) at least after
    `if (cliFromCompanionScript()) ... return` (the app's own routed `sleep`, `webui` and
    `sniffer` keep working; a companion script cannot hang on them);
  - the JS natives whose wait for a key depends on an argument (subghz.read/readRaw without a
    timeout, mic.recordWav with maxMs 0, display.drawGif looping) check it in companion mode;
  - the core display helpers a script reaches with a wait flag (displayInfo/Success/Warning/Error)
    and the JS error screen keep their companion guard;
  - `bjs_prompt_unavailable` itself: false outside companion mode, and writes the note into the
    script output.

  - the CMD path: every Bruce CLI callback (what the app's routed CMD, a script's serial.cmd() and
    the CLI-backed JS helpers run) is followed through the module code it calls (heuristic call
    graph over src/: free functions, methods of the same class, constructors written `X{`,
    `X(` or `new X(`, and member calls whose name is defined once; `gset*Pin(true)` is the pin
    menu, `gset*Pin(false)` is not). Each prompt it can reach needs a companion guard that
    returns first (`if (... g_companionActive ...) ... return`, not negated, or
    `bjs_prompt_unavailable(`) in the callback or in a function on the way, before the call that
    leads to the prompt; or a CMD_ALLOWLIST entry (callback, function holding the prompt) with a
    reason. Not followed: virtual calls through an interface pointer with several
    implementations, function pointers and lambdas stored elsewhere, third-party libraries.

Not covered: prompts inside Bruce module code reached only from the on-device menus, third-party
libraries, and waits that end on their own (radio reads with a timeout argument; see the table in
proto/PROTOCOL.md "Console input and scripts in companion mode").

    python3 tools/test_companion_prompts.py
"""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_companion_print_hygiene import functions  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
JS_DIR = "src/modules/bjs_interpreter"
CLI_DIR = "src/core/serial_commands"
EXTS = {".c", ".cpp", ".h"}

# Helpers that wait for a person at the device with no time bound.
PROMPT = re.compile(
    r"\b(keyboard|hex_keyboard|num_keyboard|loopOptions|loopSD|displayMessage|viewFile|wifiConnectMenu|"
    r"optionsMenu|loopOptionsWebUi|qrcode_display|readFs)\s*\("
    r"|\.\s*show\s*\("                               # ScrollableTextArea::show: until SelPress
    r"|\bwhile\s*\(\s*!\s*check\s*\("                 # wait-for-key loop
)
# CLI modes that run until a key is pressed at the device (refused in a companion script only).
CLI_MODE = re.compile(r"\b(startWebUi|sniffer_setup|listenTcpPort|setSleepMode)\s*\(")
JS_GUARD = re.compile(r"\bbjs_prompt_unavailable\s*\(")
CLI_GUARD = re.compile(r"\bif\s*\(\s*g_companionActive\s*\)\s*(\{[^{}]*?)?\breturn\b")
CLI_SCRIPT_GUARD = re.compile(r"\bif\s*\(\s*(g_companionActive|cliFromCompanionScript\(\s*\))\s*\)\s*(\{[^{}]*?)?\breturn\b")

# (file, function) -> reason. Prompt callers in the interpreter that need no companion guard.
JS_ALLOWLIST = {
    ("src/modules/bjs_interpreter/interpreter.cpp", "run_bjs_script"):
        "the on-device Scripts menu (script picker) opened from Bruce's main menu; script_run and "
        "the js CLI start scripts through run_bjs_script_headless instead",
    ("src/modules/bjs_interpreter/interpreter.cpp", "getScriptsOptionsList"):
        "builds the on-device Scripts menu entries (folder submenus) for run_bjs_script; not "
        "reachable from a script or the companion",
    ("src/modules/bjs_interpreter/helpers_js.cpp", "print_errorMessage"):
        "JS error screen: returns `if (g_companionActive)` before its wait-for-key loop "
        "(test_companion_serial_input.py checks the order)",
}

# (file, function) -> reason. Prompt callers in the CLI that need no companion guard.
CLI_ALLOWLIST = {}


# --- CMD path: CLI callbacks followed into the module code they call -------------------------
# A prompt for the CMD scan: the PROMPT helpers plus a pin chooser asked to open its menu.
CMD_PROMPT = re.compile(PROMPT.pattern + r"|\bgset\w+Pin\s*\(\s*true\s*\)")
# Companion guard that returns before the prompt: a non-negated g_companionActive in an if condition
# whose body returns, or the script helper.
CMD_GUARD = re.compile(
    r"\bif\s*\((?:[^;{}]*?[^!\w\s]|\s*)?\s*g_companionActive\b[^;{}]*\)\s*(\{[^{}]*?)?\breturn\b"
    r"|\bbjs_prompt_unavailable\s*\(")
CALL = re.compile(r"\b([A-Za-z_]\w*)\s*[({]")
# Helpers that are themselves the prompt: reported where they are called, not entered.
PROMPT_HELPERS = {"keyboard", "hex_keyboard", "num_keyboard", "loopOptions", "loopSD", "displayMessage",
                  "viewFile", "wifiConnectMenu", "optionsMenu", "loopOptionsWebUi", "qrcode_display", "readFs"}
NOT_CALLS = {"if", "for", "while", "switch", "return", "sizeof", "catch", "defined"}
# Member names too generic to resolve by name alone.
GENERIC_MEMBERS = {"close", "begin", "end", "load", "open", "read", "write", "setup", "available", "print",
                   "println", "printf", "flush", "remove", "exists", "clear", "push_back", "size", "loop",
                   "init", "stop", "start", "run", "send", "get", "set", "update", "draw", "show",
                   "c_str", "length", "substring", "indexOf", "toInt", "trim", "save", "erase", "emulate",
                   "clone", "reset", "parse"}
CTOR = re.compile(r"^(\w+)::(\1)\s*\([^;{}]*\)\s*(?::[^{;]*)?\{", re.M)
MAX_DEPTH = 7


def _span_end(code, brace):
    depth, i = 0, brace
    while i < len(code):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(code) - 1


def index_functions(texts):
    """{short name: [(qualified name, file, body)]} for every function (and constructor) in texts
    ({rel path: stripped code})."""
    defs = {}
    for rel, code in texts.items():
        spans = list(functions(code))
        for m in CTOR.finditer(code):
            spans.append((f"{m.group(1)}::{m.group(1)}", m.start(), _span_end(code, m.end() - 1)))
        for name, a, b in spans:
            defs.setdefault(name.split("::")[-1], []).append((name, rel, code[a:b + 1]))
    return defs


def _callees(qual, body, defs):
    """[(callee key, call offset in body)], first call of each callee."""
    cls = qual.split("::")[0] if "::" in qual else None
    out, seen = [], set()
    for m in CALL.finditer(body, body.index("{")):
        n = m.group(1)
        if n in NOT_CALLS or n in PROMPT_HELPERS or re.match(r"gset\w+Pin$", n):
            continue
        cands = defs.get(n, [])
        if not cands:
            continue
        pre = body[max(0, m.start() - 2):m.start()]
        member = pre.endswith(".") or pre.endswith("->")
        if member and (len(cands) > 1 or n in GENERIC_MEMBERS):
            continue
        if cls and not member:
            own = [c for c in cands if c[0] == f"{cls}::{n}"]
            if own:
                cands = own
        ctor = [c for c in cands if c[0] == f"{n}::{n}"]
        if ctor:
            cands = ctor
        if len(cands) > 3:
            continue
        for c in cands:
            key = (c[0], c[1])
            if key not in seen:
                seen.add(key)
                out.append((key, m.start()))
    return out


def reachable_prompts(texts, cli_dir=CLI_DIR):
    """[(callback, owner function, owner file, line, call text, guarded, path)] for every prompt the
    CLI callbacks in cli_dir can reach."""
    defs = index_functions(texts)
    bodies = {(q, f): b for v in defs.values() for q, f, b in v}
    found = []
    for rel in sorted(t for t in texts if t.startswith(cli_dir + "/")):
        for name, _, _ in functions(texts[rel]):
            if not name.endswith("Callback"):
                continue
            # (node, path of (qualified name, body, offset of the call/prompt in it) so far)
            stack = [((name, rel), [])]
            visited = set()
            while stack:
                node, path = stack.pop()
                if node in visited or len(path) >= MAX_DEPTH:
                    continue
                visited.add(node)
                body = bodies[node]
                for m in CMD_PROMPT.finditer(body):
                    hops = path + [(node[0], body, m.start())]
                    guarded = any(CMD_GUARD.search(b[:off]) for _, b, off in hops)
                    code = texts[node[1]]
                    line = code.count("\n", 0, code.index(body)) + body.count("\n", 0, m.start()) + 1
                    found.append((name, node[0], node[1], line, m.group(0).strip(), guarded,
                                  " > ".join(q for q, _, _ in hops)))
                for key, off in _callees(node[0], body, defs):
                    stack.append((key, path + [(node[0], body, off)]))
    return found


def src_texts():
    return {str(p.relative_to(ROOT)): strip_code(p.read_text(errors="replace"))
            for p in sorted((ROOT / "src").rglob("*")) if p.suffix in EXTS and p.is_file()}


# (callback, function holding the prompt) -> reason. CMD-path prompts that need no companion guard.
CMD_ALLOWLIST = {
    ("snifferCallback", "sniffer_setup"):
        "raw sniffer mode: runs until ESC at the device by design (the app's console sniffer); "
        "refused in a companion script; its capture-mode menu opens only when a person presses SEL",
    ("irTxFileCallback", "txIrFile"):
        "pause loop entered only after a person presses SEL at the device during the transmission; "
        "without a person the file is sent to its end",
    ("irTxBufferCallback", "txIrFile"):
        "same txIrFile pause, entered only after a person presses SEL; the buffer is empty in "
        "companion mode anyway (no console input)",
    ("rfRxCallback", "rfReceiveSignal"):
        "`while (!check(EscPress))` receive loop bounded by its timeout argument (the CLI passes 10 s); "
        "ESC only ends it early, and a stopped companion script sees ESC",
    ("rfScanCallback", "rf_scan"):
        "`while (!check(EscPress))` sweep bounded by its step count (the CLI passes 10000 steps); ESC "
        "only ends it early",
}


def strip_code(s):
    """Comments and string/char literal contents blanked (offsets kept), so a prompt name in a log
    message or a comment is not a call."""
    tok = re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'', re.S)

    def blank(m):
        t = m.group()
        if t.startswith("/"):
            return re.sub(r"[^\n]", " ", t)
        return t[0] + re.sub(r"[^\n]", " ", t[1:-1]) + t[-1]
    return tok.sub(blank, s)


def files(rel_dir):
    for p in sorted((ROOT / rel_dir).rglob("*")):
        if p.suffix in EXTS and p.is_file():
            yield p


def scan(rel, code, guard, pattern=PROMPT):
    """(rel, line, function, call, guarded) for each prompt call; guarded when the guard appears
    earlier in the same function."""
    spans = functions(code)
    out = []
    for m in pattern.finditer(code):
        owners = [(name, a, b) for name, a, b in spans if a <= m.start() <= b]
        func, start = (owners[-1][0], owners[-1][1]) if owners else ("?", 0)
        guarded = bool(owners) and bool(guard.search(code[start:m.start()]))
        out.append((rel, code.count("\n", 0, m.start()) + 1, func, m.group(0).strip(), guarded))
    return out


def scan_dir(rel_dir, guard, pattern=PROMPT):
    found = []
    for p in files(rel_dir):
        rel = str(p.relative_to(ROOT))
        found += scan(rel, strip_code(p.read_text(errors="replace")), guard, pattern)
    return found


def body_of(src, signature):
    start = src.index(signature)
    i = src.index("{", start)
    depth = 0
    while i < len(src):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[start:i + 1]
        i += 1
    raise AssertionError(signature)


def unguarded(found, allow):
    return [r for r in found if not r[4] and (r[0], r[2]) not in allow]


def fmt(rows):
    return "\n".join(f"{f}:{n} {fn}: {c}" for f, n, fn, c, _ in rows)


class CompanionPrompts(unittest.TestCase):
    def test_js_prompts_fail_fast_in_companion_mode(self):
        bad = unguarded(scan_dir(JS_DIR, JS_GUARD), JS_ALLOWLIST)
        self.assertEqual(bad, [], "on-device prompt reachable from script_run without a companion guard; "
                         "return the API's cancelled value `if (bjs_prompt_unavailable(\"<api>\"))` "
                         "before it, or add a JS_ALLOWLIST entry with a reason:\n" + fmt(bad))

    def test_cli_prompts_fail_fast_in_companion_mode(self):
        bad = unguarded(scan_dir(CLI_DIR, CLI_GUARD), CLI_ALLOWLIST)
        self.assertEqual(bad, [], "CLI command opens an on-device prompt without `if (g_companionActive) "
                         "... return` first (routed CMD / serial.cmd() would hang), or add a "
                         "CLI_ALLOWLIST entry with a reason:\n" + fmt(bad))

    def test_cli_modes_refused_in_companion_scripts(self):
        rows = scan_dir(CLI_DIR, CLI_SCRIPT_GUARD, CLI_MODE)
        self.assertEqual({r[3].split("(")[0] for r in rows},
                         {"startWebUi", "sniffer_setup", "listenTcpPort", "setSleepMode"})
        bad = [r for r in rows if not r[4]]
        self.assertEqual(bad, [], "CLI mode that runs until a key is pressed at the device without "
                         "`if (cliFromCompanionScript()) ... return` first:\n" + fmt(bad))
        helpers = strip_code((ROOT / CLI_DIR / "helpers.cpp").read_text())
        body = body_of(helpers, "bool cliFromCompanionScript()")
        self.assertIn("g_companionActive && interpreterTaskHandler != NULL", body)
        self.assertIn("xTaskGetCurrentTaskHandle() == interpreterTaskHandler", body)

    def test_cmd_path_prompts_fail_fast_in_companion_mode(self):
        rows = reachable_prompts(src_texts())
        bad = [r for r in rows if not r[5] and (r[0], r[1]) not in CMD_ALLOWLIST]
        self.assertEqual(bad, [], "a CLI command (routed CMD / serial.cmd()) reaches an on-device prompt "
                         "with no companion guard returning first on the way; guard it (`if "
                         "(g_companionActive) { serialDevice->println(\"<why>\"); return false; }`) or add a "
                         "CMD_ALLOWLIST entry with a reason:\n" +
                         "\n".join(f"{r[2]}:{r[3]} {r[4]}  via {r[6]}" for r in bad))
        # The batch 14 leftovers, now guarded on the way (not allowlisted).
        guarded = {(r[0], r[1]) for r in rows if r[5]}
        for key in (("irRxCallback", "IrRead::setup"), ("scanHostsCallback", "ARPScanner::setup"),
                    ("listenTCPCallback", "listenTcpPort"), ("decryptFileCallback", "readDecryptedFile"),
                    ("loaderCallback", "loaderCallback")):
            self.assertIn(key, guarded, key)
        self.assertGreater(len(rows), 15, "the call graph stopped finding CLI prompts")
        for key, reason in CMD_ALLOWLIST.items():
            self.assertIn(key, {(r[0], r[1]) for r in rows if not r[5]}, f"stale CMD_ALLOWLIST entry: {key}")
            self.assertGreater(len(reason), 40, key)

    def test_cmd_path_detector(self):
        texts = {
            CLI_DIR + "/x_commands.cpp": strip_code(
                'uint32_t aCallback(cmd *c) {\n  helperA();\n  return true;\n}\n'
                'uint32_t bCallback(cmd *c) {\n  if (g_companionActive) {\n    p("no");\n    return false;\n  }\n'
                '  helperA();\n  return true;\n}\n'
                'uint32_t cCallback(cmd *c) {\n  Reader{1};\n  return true;\n}\n'
                'uint32_t dCallback(cmd *c) {\n  if (!g_companionActive) return false;\n  helperA();\n  return true;\n}\n'
                'uint32_t eCallback(cmd *c) {\n  gsetIrRxPin(false);\n  r->onlyHere();\n  return true;\n}\n'),
            "src/modules/m.cpp": strip_code(
                'void helperA() {\n  helperB(3);\n}\n'
                'int helperB(int n) {\n  return loopOptions(options);\n}\n'
                'Reader::Reader(int x) {\n  setup();\n}\n'
                'void Reader::setup() {\n  if (headless && g_companionActive) {\n    missing = true;\n    return;\n  }\n'
                '  gsetIrRxPin(true);\n}\n'
                'void Other::onlyHere() {\n  String s = keyboard("x");\n}\n'),
        }
        rows = {(r[0], r[1], r[4], r[5]) for r in reachable_prompts(texts)}
        self.assertIn(("aCallback", "helperB", "loopOptions(", False), rows)
        self.assertIn(("bCallback", "helperB", "loopOptions(", True), rows)
        self.assertIn(("cCallback", "Reader::setup", "gsetIrRxPin(true)", True), rows)
        self.assertIn(("dCallback", "helperB", "loopOptions(", False), rows)  # negated: not a guard
        self.assertIn(("eCallback", "Other::onlyHere", "keyboard(", False), rows)  # unique member name
        self.assertFalse(any(r[0] == "eCallback" and "gsetIrRxPin" in r[2] for r in rows))
        texts[CLI_DIR + "/x_commands.cpp"] = texts[CLI_DIR + "/x_commands.cpp"].replace(
            "if (headless && g_companionActive)", "if (headless)")
        texts["src/modules/m.cpp"] = texts["src/modules/m.cpp"].replace("if (headless && g_companionActive)", "if (headless)")
        rows = {(r[0], r[1], r[4], r[5]) for r in reachable_prompts(texts)}
        self.assertIn(("cCallback", "Reader::setup", "gsetIrRxPin(true)", False), rows)

    def test_cmd_path_specific_behavior(self):
        # The routed `listen` is refused in companion mode (not only in a script): it always asks for
        # the port on the device keyboard.
        wifi = strip_code((ROOT / CLI_DIR / "wifi_commands.cpp").read_text())
        listen = body_of(wifi, "uint32_t listenTCPCallback(")
        self.assertLess(listen.index("if (g_companionActive)"), listen.index("listenTcpPort()"))
        webui = body_of(wifi, "uint32_t webuiCallback(")
        self.assertLess(webui.index("if (g_companionActive && noAp && !WiFi.isConnected())"), webui.index("startWebUi("))
        # IR: no pin menu in companion mode; the read fails at once and the companion ir_rx says why.
        ir = strip_code((ROOT / "src/modules/ir/ir_read.cpp").read_text())
        setup = body_of(ir, "void IrRead::setup()")
        self.assertLess(setup.index("if (headless && g_companionActive)"), setup.index("gsetIrRxPin(true)"))
        self.assertIn("if (rxPinMissing)", body_of(ir, "String IrRead::loop_headless(int max_loops)"))
        infra = strip_code((ROOT / "src/companion/infrared.cpp").read_text())
        rx = body_of(infra, "static void runIrRx(uint32_t id)")
        self.assertLess(rx.index("if (!reader->rxPinReady())"), rx.index("loop_headless("))
        # BadUSB payload: WAIT_FOR_BUTTON_PRESS ends the payload in companion mode before its loop.
        ducky = strip_code((ROOT / "src/modules/badusb_ble/ducky_typer.cpp").read_text())
        wait = ducky[ducky.index("PriCmd->type == DuckyCommandType_WaitForButtonPress"):]
        self.assertLess(wait.index("if (g_companionActive)"), wait.index("while (!waitSelect)"))
        self.assertLess(wait.index("goto EXIT;"), wait.index("while (!waitSelect)"))
        # `js <file>` in companion mode would leave a task waiting for a foreground nobody gives it
        # (and later script_runs `interpreter busy`): refused before a script is started.
        js = body_of(strip_code((ROOT / CLI_DIR / "interpreter_commands.cpp").read_text()), "static uint32_t jsCallback(")
        self.assertLess(js.index("interpreter_state = -1;"), js.index("if (g_companionActive) {"))
        self.assertLess(js.index("if (g_companionActive) {"), js.index("run_bjs_script_headless(*fs, filepath)"))
        # ARPScanner::setup (Bruce `arp`, Ethernet ARP scan): every early return between taking the
        # lwIP core lock (CONFIG_LWIP_TCPIP_CORE_LOCKING=1 in the pinned sdkconfig) and releasing
        # it releases it first (backlog F04).
        arp = strip_code((ROOT / "src/modules/ethernet/ARPScanner.cpp").read_text())
        setup = body_of(arp, "void ARPScanner::setup()")
        locked = setup[setup.index("LOCK_TCPIP_CORE();"):setup.index("\n    UNLOCK_TCPIP_CORE();")]
        returns = [m.start() for m in re.finditer(r"\breturn\b", locked)]
        self.assertEqual(len(returns), 3)
        for pos in returns:
            branch = locked[locked.rfind("if (", 0, pos):pos]
            self.assertIn("UNLOCK_TCPIP_CORE();", branch, branch)
            # A key wait (displayError(..., true)) on the standalone path comes after the release.
            wait = branch.find("displayError(")
            self.assertTrue(wait < 0 or wait > branch.index("UNLOCK_TCPIP_CORE();"), branch)
        # `settings` with no arguments printed the JSON straight to Serial (the frame stream).
        settings = body_of(strip_code((ROOT / CLI_DIR / "settings_commands.cpp").read_text()), "uint32_t settingsCallback(")
        self.assertLess(settings.index("if (g_companionActive)"), settings.index("serializeJsonPretty(jsonDoc, Serial)"))

    def test_allowlists_are_live_and_reasoned(self):
        for allow, rows in ((JS_ALLOWLIST, scan_dir(JS_DIR, JS_GUARD)), (CLI_ALLOWLIST, scan_dir(CLI_DIR, CLI_GUARD))):
            hits = {(f, fn) for f, _, fn, _, guarded in rows if not guarded}
            for key, reason in allow.items():
                self.assertIn(key, hits, f"stale allowlist entry (no unguarded prompt left): {key}")
                self.assertGreater(len(reason), 40, key)

    def test_guarded_natives_cover_the_prompt_apis(self):
        # The script APIs that open a prompt, and the native each one maps to (mqjs_stdlib.c table).
        found = {(f.rsplit("/", 1)[1], fn) for f, _, fn, _, g in scan_dir(JS_DIR, JS_GUARD) if g}
        expected = {
            ("keyboard_js.cpp", "keyboard_wrapper"),          # keyboard(), hexKeyboard(), numKeyboard()
            ("dialog_js.cpp", "native_dialogMessage"),        # dialog.message()
            ("dialog_js.cpp", "native_dialogPickFile"),       # dialog.pickFile()
            ("dialog_js.cpp", "native_dialogChoice"),         # dialog.choice()
            ("dialog_js.cpp", "native_dialogViewFile"),       # dialog.viewFile()
            ("dialog_js.cpp", "native_dialogViewText"),       # dialog.viewText()
            ("menu_js.cpp", "native_menuShow"),               # menu.show()
            ("wifi_js.cpp", "native_wifiConnectDialog"),      # wifi.connectDialog()
            ("wifi_js.cpp", "native_httpFetch"),              # fetch() without Wi-Fi: network menu
        }
        self.assertEqual(expected - found, set())

    def test_cancelled_values(self):
        js = {n: strip_code((ROOT / JS_DIR / n).read_text()) for n in
              ("keyboard_js.cpp", "dialog_js.cpp", "menu_js.cpp", "wifi_js.cpp")}
        kb = body_of(js["keyboard_js.cpp"], "static JSValue keyboard_wrapper(")
        self.assertRegex(kb, r"if \(bjs_prompt_unavailable\(names\[type\]\)\) return JS_NewString\(ctx, \"\"\);")
        self.assertLess(kb.index("bjs_prompt_unavailable"), kb.index("keyboard("))
        menu = body_of(js["menu_js.cpp"], "JSValue native_menuShow(")
        self.assertIn("return JS_NewInt32(ctx, -1);", menu[menu.index("bjs_prompt_unavailable"):])
        view = body_of(js["dialog_js.cpp"], "JSValue native_dialogViewText(")
        guard = view[view.index("bjs_prompt_unavailable"):]
        self.assertLess(guard.index("area.draw(true);"), guard.index("area.show(true);"))
        wifi = body_of(js["wifi_js.cpp"], "JSValue native_wifiConnectDialog(")
        self.assertIn("return JS_NewBool(false);", wifi[wifi.index("bjs_prompt_unavailable"):])
        # mic.recordWav({maxMs: 0}) stops only on SEL at the device (a stop callback, not a PROMPT
        # call): refused in companion mode before recording starts.
        mic = body_of(strip_code((ROOT / JS_DIR / "mic_js.cpp").read_text()), "JSValue native_micRecordWav(")
        self.assertIn("maxMs == 0 && bjs_prompt_unavailable(", mic)
        self.assertLess(mic.index("bjs_prompt_unavailable"), mic.index("mic_record_wav_to_path("))
        # subghz.read/readRaw(t <= 0) wait for ESC: "" (the timeout result) before the receiver runs.
        sub = strip_code((ROOT / JS_DIR / "subghz_js.cpp").read_text())
        for fn in ("JSValue native_subghzRead(", "JSValue native_subghzReadRaw("):
            body = body_of(sub, fn)
            self.assertRegex(body, r"if \(t <= 0 && bjs_prompt_unavailable\([^)]*\)\)\s*return JS_NewString\(ctx, \"\"\);")
            self.assertLess(body.index("bjs_prompt_unavailable"), body.index("rfReceiveSignal(bruceConfigPins.rfFreq, t,"))
        # display.drawGif(..., -1) loops until a key: played once instead.
        gif = strip_code((ROOT / JS_DIR / "display_js.cpp").read_text())
        gif = gif[gif.index("JSValue native_drawGif("):]
        self.assertLess(gif.index("if (playDurationMs < 0 && bjs_prompt_unavailable("), gif.index("showGif("))

    def test_prompt_unavailable_helper(self):
        src = strip_code((ROOT / JS_DIR / "helpers_js.cpp").read_text())
        body = body_of(src, "bool bjs_prompt_unavailable(const char *api)")
        self.assertRegex(body, r"^bool bjs_prompt_unavailable\(const char \*api\) \{\s*if \(!g_companionActive\) return false;")
        self.assertIn("bjs_capture_write(api, strlen(api));", body)
        raw = (ROOT / JS_DIR / "helpers_js.cpp").read_text()
        self.assertIn("skipped, it waits for a person at the device (companion mode)", raw)

    def test_core_wait_flags_return_in_companion_mode(self):
        disp = strip_code((ROOT / "src/core/display.cpp").read_text())
        for fn in ("displayError", "displayWarning", "displayInfo", "displaySuccess"):
            body = body_of(disp, f"void {fn}(const String &txt, bool waitKeyPress)")
            self.assertLess(body.index("if (g_companionActive) return;"), body.index("while (waitKeyPress"), fn)

    def test_cli_interactive_commands(self):
        util = strip_code((ROOT / CLI_DIR / "util_commands.cpp").read_text())
        loader = body_of(util, "uint32_t loaderCallback(")
        self.assertLess(loader.index("if (g_companionActive)"), loader.index("optionsMenu()"))
        self.assertLess(loader.index("if (g_companionActive)"), loader.index("loopSD("))
        # The app's Sleep button sends a routed `sleep`: only a companion script is refused.
        power = strip_code((ROOT / CLI_DIR / "power_commands.cpp").read_text())
        self.assertNotIn("g_companionActive", body_of(power, "uint32_t sleepCallback("))

    def test_detector_catches_regressions(self):
        code = strip_code(
            'JSValue a(JSContext *ctx) {\n  String r = keyboard("x");\n  return r;\n}\n'
            'JSValue b(JSContext *ctx) {\n  if (bjs_prompt_unavailable("b()")) return JS_NULL;\n  loopOptions(o);\n}\n'
            'JSValue c(JSContext *ctx) {\n  log("keyboard(");\n  // loopSD(fs)\n  return 0;\n}\n'
            'void d() {\n  area.show(true);\n}\n'
            'void e() {\n  while (!check(AnyKeyPress)) delay(50);\n}\n'
            'uint32_t f(cmd *c) {\n  if (g_companionActive) {\n    p("no");\n    return false;\n  }\n  loopSD(LittleFS);\n}\n'
            'uint32_t g(cmd *c) {\n  if (cliFromCompanionScript()) return false;\n  setSleepMode();\n}\n')
        js = {(fn, g) for _, _, fn, _, g in scan("synthetic", code, JS_GUARD)}
        self.assertEqual(js, {("a", False), ("b", True), ("d", False), ("e", False), ("f", False)})
        cli = {(fn, g) for _, _, fn, _, g in scan("synthetic", code, CLI_GUARD)}
        self.assertIn(("f", True), cli)
        self.assertIn(("a", False), cli)
        modes = {(fn, g) for _, _, fn, _, g in scan("synthetic", code, CLI_SCRIPT_GUARD, CLI_MODE)}
        self.assertEqual(modes, {("g", True)})
        self.assertEqual({(fn, g) for _, _, fn, _, g in scan("synthetic", code.replace("if (cliFromCompanionScript()) return false;", ""), CLI_SCRIPT_GUARD, CLI_MODE)}, {("g", False)})


if __name__ == "__main__":
    unittest.main(verbosity=2)
