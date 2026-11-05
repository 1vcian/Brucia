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
