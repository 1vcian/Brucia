#!/usr/bin/env python3
"""Nothing but the companion frame reader reads the framed port while companion mode is active.

In companion mode the USB serial carries the app's length-prefixed frames. Any other reader takes
bytes out of those frames and the link desyncs: JS `serial.readln` used to call
`Serial.readStringUntil` during `script_run`, and Bruce CLI commands that wait for console input
(`storage write`, `ir`/`subghz`/`badusb`/`js` `*_from_buffer`, `crypto`, `storage ymodem`) read
`serialDevice`, which is USBserial -> Serial unless routeCommand has swapped in its capture device
(a script's `serial.cmd()` and the JS audio/badusb/subghz helpers call the CLI off that path).

Checked here:
  - every direct `Serial.`/`USBserial.` read (read, readString*, readBytes*, available, peek,
    parseInt/parseFloat, find*) in src/, include/ and lib/ (Bruce's own libraries; examples
    excluded) is either the companion's own frame reader (INPUT_ALLOWLIST, with a reason) or sits
    in a function that returns early `if (g_companionActive)` / is guarded by `!g_companionActive`;
  - `serialDevice` (the CLI console) is a device with no input for the whole companion session
    (companion.cpp claims it before `g_companionActive = true` and releases it before clearing the
    flag) and routeCommand's capture device has no input either; every other assignment to
    `serialDevice` is known and off the companion path;
  - the CLI paths that wait for console input, and the JS error dialog that waits for a key, return
    at once in companion mode instead of blocking the companion loop / interpreter task;
  - the screenless SerialDisplayClass (VectorDisplay) never consumes Serial.

Not covered: third-party libraries under .pio/libdeps. On-device prompts a script can open
(keyboard(), dialog, menu) wait for a person at the device, not for Serial: see
tools/test_companion_prompts.py.

    python3 tools/test_companion_serial_input.py
"""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_companion_print_hygiene import functions, guarded_by_block, guarded_by_statement, strip_comments  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SCAN_DIRS = ("src", "include", "lib")
EXTS = {".c", ".cpp", ".h", ".hpp", ".ino"}

READ = re.compile(r"\b(Serial|USBserial)\s*\.\s*(read|readString|readStringUntil|readBytes|readBytesUntil|available|"
                  r"peek|parseInt|parseFloat|find|findUntil)\s*\(")
GUARD = re.compile(r"!\s*g_companionActive\b")
EARLY_RETURN = re.compile(r"\bif\s*\(\s*g_companionActive\s*\)\s*(\{[^{}]*?)?\breturn\b")

# (file, function) -> reason. The only direct readers of the framed port in companion mode.
INPUT_ALLOWLIST = {
    ("src/companion/protocol.cpp", "readFrame"):
        "the companion frame reader itself: owns Serial input for the whole companion session",
    ("src/companion/companion.cpp", "companionPollStop"):
        "frame reader poll used by blocking companion runners; reads whole frames via readFrame and "
        "answers each one (PONG / stop / 409 busy)",
    ("src/companion/companion.cpp", "runCompanionUSB"):
        "drains stale console bytes once on entry, after suspending the serialcmds task and before "
        "the link turns framed",
}

# Assignments to the global `serialDevice` outside src/companion, with why they cannot replace the
# companion's no-input device during a session.
SERIAL_DEVICE_ASSIGNMENTS = {
    ("src/main.cpp", "SerialDevice *serialDevice = &USBserial;"):
        "the boot-time definition; companion mode saves and restores whatever is current",
    ("src/modules/ble_api/ble_api.cpp", "serialDevice = &serial_service;"):
        "BLE_API::setup, reached only from the settings menu toggle (enableBLEAPI)",
    ("src/modules/ble_api/ble_api.cpp", "serialDevice = &USBserial;"):
        "BLE_API::end, reached only from the settings menu toggle (enableBLEAPI)",
}


def source_files():
    for d in SCAN_DIRS:
        for p in sorted((ROOT / d).rglob("*")):
            if p.suffix in EXTS and p.is_file() and "examples" not in p.parts:
                yield p


def companion_guarded(code, pos, spans):
    """True if the read is guarded by !g_companionActive or its function returns early in companion mode."""
    if guarded_by_statement(code, pos) or guarded_by_block(code, pos):
        return True
    line_start = code.rfind("\n", 0, pos) + 1
    if GUARD.search(code[line_start:pos]):
        return True
    owners = [(a, b) for _, a, b in spans if a <= pos <= b]
    if owners:
        start = owners[-1][0]
        return bool(EARLY_RETURN.search(code[start:pos]))
    return False


def scan_reads(rel, code=None):
    if code is None:
        code = strip_comments((ROOT / rel).read_text(errors="replace"))
    spans = functions(code)
    found = []
    for m in READ.finditer(code):
        owner = [name for name, a, b in spans if a <= m.start() <= b]
        func = owner[-1] if owner else "?"
        guarded = companion_guarded(code, m.start(), spans)
        line_no = code.count("\n", 0, m.start()) + 1
        found.append((rel, line_no, func, m.group(0), guarded))
    return found


def all_reads():
    out = []
    for p in source_files():
        text = p.read_text(errors="replace")
        if not READ.search(text):
            continue
        out += scan_reads(str(p.relative_to(ROOT)))
    return out


def body_of(src, signature):
    """Text of the function whose definition starts with `signature` (first match)."""
    start = src.index(signature)
    brace = src.index("{", start)
    depth, i = 0, brace
    while i < len(src):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[start:i + 1]
        i += 1
    raise AssertionError(signature)


class CompanionSerialInput(unittest.TestCase):
    def test_no_direct_console_reads_outside_frame_reader(self):
        bad = [r for r in all_reads() if not r[4] and (r[0], r[2]) not in INPUT_ALLOWLIST]
        self.assertEqual(bad, [], "direct Serial read reachable in companion mode; return early "
                         "`if (g_companionActive)` or add an INPUT_ALLOWLIST entry with a reason:\n" +
                         "\n".join(f"{f}:{n} {fn}: {t}" for f, n, fn, t, _ in bad))

    def test_input_allowlist_is_live_and_reasoned(self):
        hits = {(f, fn) for f, _, fn, _, guarded in all_reads() if not guarded}
        for key, reason in INPUT_ALLOWLIST.items():
            self.assertIn(key, hits, f"stale allowlist entry (no unguarded read left): {key}")
            self.assertGreater(len(reason), 40, key)

    def test_js_serial_readln_returns_empty_in_companion_mode(self):
        src = strip_comments((ROOT / "src/modules/bjs_interpreter/serial_js.cpp").read_text())
        body = body_of(src, "JSValue native_serialReadln(")
        guard = body.index("if (g_companionActive) return JS_NewString(ctx, \"\");")
        self.assertLess(guard, body.index("Serial.flush()"))
        self.assertLess(guard, body.index("Serial.available()"))
        self.assertLess(guard, body.index("Serial.readStringUntil"))

    def test_serial_device_has_no_input_in_companion_mode(self):
        comp = strip_comments((ROOT / "src/companion/companion.cpp").read_text())
        claim, release = "companionClaimSerialDevice(true)", "companionClaimSerialDevice(false)"
        self.assertEqual(comp.count(claim), 1)
        self.assertEqual(comp.count(release), 1)
        self.assertLess(comp.index("vTaskSuspend(serialcmdsTaskHandle)"), comp.index(claim))
        self.assertLess(comp.index(claim), comp.index("g_companionActive = true"))
        self.assertLess(comp.index(release), comp.index("g_companionActive = false"))
        self.assertLess(comp.index("g_companionActive = false"), comp.index("vTaskResume(serialcmdsTaskHandle)"))

        cmds = strip_comments((ROOT / "src/companion/commands.cpp").read_text())
        for cls in ("class CompanionNullSerialDevice", "class CapturingSerialDevice"):
            body = body_of(cmds, cls)
            self.assertIn("int read() override { return -1; }", body, cls)
            self.assertIn("int available() override { return 0; }", body, cls)
            self.assertIn("String readStringUntil(char) override { return String(); }", body, cls)
        null = body_of(cmds, "class CompanionNullSerialDevice")
        self.assertNotRegex(null, r"\bSerial\b|usbConsole|USBserial")
        claim_fn = body_of(cmds, "void companionClaimSerialDevice(bool claim)")
        self.assertIn("g_serialDeviceBeforeCompanion = serialDevice;", claim_fn)
        self.assertIn("serialDevice = &g_nullSerialDevice;", claim_fn)
        self.assertIn("serialDevice = g_serialDeviceBeforeCompanion", claim_fn)
        route = body_of(cmds, "static bool routeCommand(")
        self.assertLess(route.index("SerialDevice *prev = serialDevice;"), route.index("serialDevice = &cap;"))
        self.assertLess(route.index("serialCli.parse(cmd)"), route.index("serialDevice = prev;"))
        # Writes from another task (tftLogger's async writer, an interpreter task) never touch the
        # routed command's String.
        cap = body_of(cmds, "class CapturingSerialDevice")
        self.assertIn("if (!onOwnerTask()) return size;", cap)
        self.assertIn("if (!onOwnerTask()) return;", cap)

    def test_serial_device_assignments_are_known(self):
        seen = set()
        for p in source_files():
            rel = str(p.relative_to(ROOT))
            if rel.startswith("src/companion/"):
                continue
            code = strip_comments(p.read_text(errors="replace"))
            for line in code.splitlines():
                if re.search(r"\bserialDevice\s*=[^=]", line):
                    seen.add((rel, line.strip()))
        self.assertEqual(seen, set(SERIAL_DEVICE_ASSIGNMENTS),
                         "new assignment to serialDevice: make sure it cannot run during a companion "
                         "session, then add it with a reason")
        settings = (ROOT / "src/core/settings.cpp").read_text()
        self.assertIn("bleApi.setup();", body_of(settings, "void enableBLEAPI()"))
        callers = [p for p in source_files() if "enableBLEAPI()" in p.read_text(errors="replace")
                   and not p.name.startswith("settings.")]
        self.assertEqual([str(p.relative_to(ROOT)) for p in callers], ["src/core/menu_items/ConfigMenu.cpp"])

    def test_cli_input_waits_return_at_once(self):
        helpers = strip_comments((ROOT / "src/core/serial_commands/helpers.cpp").read_text())
        rf = body_of(helpers, "char *_readFileFromSerial(")
        self.assertLess(rf.index("if (g_companionActive)"), rf.index("while (true)"))
        storage = strip_comments((ROOT / "src/core/serial_commands/storage_commands.cpp").read_text())
        ym = body_of(storage, "uint32_t ymodemReceiveCallback(")
        self.assertLess(ym.index("if (g_companionActive)"), ym.index("getFsStorage(fs)"))
        self.assertLess(ym.index("if (g_companionActive)"), ym.index("fs->open("))
        js = strip_comments((ROOT / "src/core/serial_commands/interpreter_commands.cpp").read_text())
        buf = js[js.index('first == "run_from_buffer"'):]
        self.assertLess(buf.index("if (g_companionActive && (txt == NULL || txt[0] == '\\0'))"),
                        buf.index("return run_bjs_script_headless(txt);"))
        err = strip_comments((ROOT / "src/modules/bjs_interpreter/helpers_js.cpp").read_text())
        dlg = body_of(err, "void print_errorMessage(")
        self.assertLess(dlg.index("if (g_companionActive) return;"), dlg.index("while (!check(AnyKeyPress))"))

    def test_serial_display_class_does_not_consume_serial(self):
        vd = strip_comments((ROOT / "include/VectorDisplay.h").read_text())
        cls = body_of(vd, "class SerialDisplayClass")
        read = body_of(cls, "virtual int remoteRead() override")
        self.assertNotRegex(read, r"\bs\.read\s*\(")
        # The only reader of remoteRead (readMessage) is reached from sendCommandWithAck after an
        # unconditional return.
        ack = body_of(vd, "void sendCommandWithAck(")
        self.assertLess(ack.index("return;"), ack.index("readMessage("))

    def test_detector_catches_regressions(self):
        code = strip_comments(
            'int a() {\n  return Serial.read();\n}\n'
            'String b() {\n  if (g_companionActive) return String();\n  return Serial.readStringUntil(10);\n}\n'
            'void c() {\n  if (!g_companionActive && Serial.available()) x();\n}\n'
            'void d() {\n  if (g_companionActive) {\n    log();\n    return;\n  }\n  USBserial.available();\n}\n'
            'void e() {\n  while (Serial.available()) Serial.read();\n}\n')
        res = {(fn, g) for _, _, fn, _, g in scan_reads("synthetic", code)}
        self.assertEqual(res, {("a", False), ("b", True), ("c", True), ("d", True), ("e", False)})
        # A `/*` inside a line comment or a string must not hide the code after it.
        hidden = strip_comments('// stop/*_stop\nint f() {\n  const char *s = "/*";\n  return Serial.read();\n}\n// */\n')
        self.assertEqual([(fn, g) for _, _, fn, _, g in scan_reads("synthetic", hidden)], [("f", False)])


if __name__ == "__main__":
    unittest.main(verbosity=2)
