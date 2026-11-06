#!/usr/bin/env python3
"""Stop frames and unknown `<verb>_<suffix>` commands never reach another command's handler.

Every companion CMD goes through handleCompanionCommand (src/companion/commands.cpp): a few inline
tests, then the dispatchers in the order of its `handlers[]` array, each matching with ordered
`cmdStr == "x"` / `cmdStr.startsWith("x")` tests where the first match wins, then the Bruce CLI.
A bare-word prefix (no trailing space) also matches `x_stop` or `x_anything`: batch 17 found that
`ir_jammer_stop` started an IR jam window, `ble_ibeacon_stop` a broadcast, `jam_detect_stop` a
detection window and `speaker_volume_stop` set the volume to 100 %, instead of stopping or being
refused (the Wi-Fi karma/responder stops were the first case, tools/test_companion_wifi_dispatch.py).

This test replays that first-match order over the real sources, for the full build and for the
LITE build (where a file defines a second, `#else` dispatcher), and fails when
  - a `*_stop` verb (app CommandCapabilities.stops, every stop verb the firmware handles, and
    `<verb>_stop` for every companion verb) or a made-up `<verb>_<suffix>` that is not a real
    command lands in a handler that belongs to a different command. Acceptable outcomes are the
    candidate's own exact handler (an idle-stop guard), a namespace handler (`ble_hid_`,
    `fs_upload_`) that rejects unknown sub-verbs, or no companion handler (the CLI refuses it);
  - a real verb (bare or with arguments) is shadowed by another command's handler;
  - an idle-stop guard does anything but answer "not running".
Mutation checks re-run the analysis on sources with each batch-18 guard reverted.

Static only (the dispatchers include Arduino/ESP headers and are not host-buildable).
Run: python3 tools/test_companion_dispatch_stops.py
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMPANION = ROOT / "src" / "companion"
CAPS = ROOT / "android/app/src/main/java/app/brucia/protocol/CommandCapabilities.kt"

# Namespace handlers: a prefix ending in "_" whose block dispatches exact sub-verbs and rejects the
# rest. Value: the rejection text that must be present in that handler.
GROUP_REJECTIONS = {"ble_hid_": '"unknown ble_hid_ command"', "fs_upload_": '"unknown upload operation"'}
# Dispatch-level helper calls that act as a namespace handler: call -> (file, helper signature).
GROUP_HELPERS = {"uploadCommand(cmdStr": ("storage.cpp", r"bool uploadCommand\(const String &cmd, uint32_t id\)")}
# Idle-stop guards added in batches 17/18; each must exist and only answer "not running".
IDLE_STOPS = ("responder_stop", "karma_stop", "brucegotchi_stop", "jam_detect_stop", "ir_jammer_stop",
              "ble_ibeacon_stop")
JUNK_SUFFIXES = ("_stop", "_zz", "_end", "_x 5")


def mask(text):
    """Comments removed and string/char literal *contents* blanked (same length), so brace and
    paren matching ignore them. Returns (code_with_literals, masked)."""
    out, masked, i, n = [], [], 0, len(text)
    while i < n:
        c = text[i]
        if c in "\"'":
            j = i + 1
            while j < n and text[j] != c:
                j += 2 if text[j] == "\\" else 1
            lit = text[i:j + 1]
            out.append(lit)
            masked.append(c + " " * (len(lit) - 2) + c if len(lit) >= 2 else lit)
            i = j + 1
        elif text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
        else:
            out.append(c)
            masked.append(c)
            i += 1
    return "".join(out), "".join(masked)


def match_close(masked, open_pos, open_ch, close_ch):
    depth = 0
    for i in range(open_pos, len(masked)):
        if masked[i] == open_ch:
            depth += 1
        elif masked[i] == close_ch:
            depth -= 1
            if depth == 0:
                return i
    raise AssertionError("unbalanced source")


def function_bodies(code, masked, signature_regex):
    """[(start, end)] of the body of every definition matching signature_regex (in source order)."""
    spans = []
    for m in re.finditer(signature_regex + r"\s*\{", masked):
        open_pos = m.end() - 1
        spans.append((open_pos + 1, match_close(masked, open_pos, "{", "}")))
    return spans


ATOM = re.compile(r'cmdStr\s*(==|\.startsWith\()\s*"((?:[^"\\]|\\.)*)"')


class Handler:
    def __init__(self, where, atoms, block):
        self.where = where      # "file:line"
        self.atoms = atoms      # [(kind, literal)] in condition order
        self.block = block      # handler block text (with literals)
        self.group = next((lit for kind, lit in atoms if kind == "prefix" and lit.endswith("_")), None)
        self.inner = []         # sub-verbs of a namespace handler

    def verbs(self):
        if self.group:
            return set(self.inner)
        return {lit.rstrip(" ") for _, lit in self.atoms}

    def matches(self, command):
        return any(command == lit if kind == "eq" else command.startswith(lit) for kind, lit in self.atoms)


def handlers_in(fname, code, masked, start, end):
    """Top-level command handlers of the body code[start:end], in order."""
    body_masked = masked[start:end]
    found = []
    for m in re.finditer(r"\bif\s*\(", body_masked):
        p_open = start + m.end() - 1
        p_close = match_close(masked, p_open, "(", ")")
        atoms = [("eq" if a.group(1) == "==" else "prefix", a.group(2)) for a in ATOM.finditer(code[p_open:p_close])]
        if not atoms:
            continue
        depth = body_masked[:m.start()].count("{") - body_masked[:m.start()].count("}")
        b_open = masked.index("{", p_close)
        b_close = match_close(masked, b_open, "{", "}")
        line = code[:p_open].count("\n") + 1
        found.append((depth, p_open, Handler(f"{fname}:{line}", atoms, code[b_open:b_close + 1]), (b_open, b_close)))
    for call, (hfile, sig) in GROUP_HELPERS.items():
        pos = code.find(call, start, end)
        if pos >= 0:
            hcode, hmasked = mask((COMPANION / hfile).read_text())
            (hs, he), = function_bodies(hcode, hmasked, sig)
            hbody = hcode[hs:he]
            g = re.search(r'cmd\.startsWith\("([^"]+)"\)', hbody)
            h = Handler(f"{hfile}:{call}", [("prefix", g.group(1))], hbody)
            h.inner = re.findall(r'verb == "([^"]+)"', hbody)
            found.append((-1, pos, h, None))
    if not found:
        return []
    top = min(d for d, _, _, _ in found if d >= 0)
    result = []
    for depth, pos, h, span in sorted(found, key=lambda t: t[1]):
        if depth not in (top, -1):
            continue
        if any(o is not h and ospan and span and ospan[0] < pos < ospan[1] for _, _, o, ospan in found
               if o.group and o is not h):
            continue  # inside a namespace handler: an inner sub-verb, collected below
        if h.group and span:
            h.inner = sorted({a.group(2).rstrip(" ") for a in ATOM.finditer(code[span[0]:span[1]])})
        result.append(h)
    return result


def dispatch_chain(sources, variant):
    """Ordered handlers a CMD meets: commands.cpp inline tests, then each dispatcher of handlers[].
    variant 'full' takes the first definition of a dispatcher, 'lite' the last (the #else one)."""
    cmd_code, cmd_masked = mask(sources["commands.cpp"])
    (s, e), = function_bodies(cmd_code, cmd_masked, r"void handleCompanionCommand\([^)]*\)")
    cut = cmd_code.index("const CommandRequest request{", s)
    chain = handlers_in("commands.cpp", cmd_code, cmd_masked, s, cut)
    names = re.findall(r"\b(dispatch\w+Command),", cmd_code[cut:e])
    assert names, "handlers[] array not found in commands.cpp"
    for name in names:
        defs = []
        for fname, text in sources.items():
            code, masked = mask(text)
            for span in function_bodies(code, masked, r"bool " + name + r"\(const CommandRequest &request\)"):
                defs.append((fname, code, masked, span))
        assert defs, f"{name} has no definition"
        fname, code, masked, (bs, be) = defs[0] if variant == "full" else defs[-1]
        chain.extend(handlers_in(fname, code, masked, bs, be))
    return chain


def first_match(chain, command):
    for h in chain:
        if h.matches(command):
            return h
    return None


def app_stops(caps_text):
    block = re.search(r"private val stops = setOf\((.*?)\)", caps_text, re.S)
    assert block, "stops set not found in CommandCapabilities.kt"
    return re.findall(r'"([^"]+)"', block.group(1))


def problems(sources, variant, caps_text):
    chain = dispatch_chain(sources, variant)
    out = []
    real = set()
    for h in chain:
        real |= h.verbs()
        if h.group:
            if h.group not in GROUP_REJECTIONS:
                out.append(f"{h.where}: namespace prefix startsWith(\"{h.group}\") has no known rejection")
            elif GROUP_REJECTIONS[h.group] not in h.block:
                out.append(f"{h.where}: namespace {h.group} no longer rejects unknown sub-verbs")
    # A real verb, bare or with an argument, reaches a handler that owns it.
    for verb in sorted(real):
        for command in (verb, verb + " 1"):
            h = first_match(chain, command)
            if h is not None and verb not in h.verbs():
                out.append(f"'{command}' is shadowed by {h.where} ({sorted(h.verbs())})")
    stop_verbs = {lit for h in chain for kind, lit in h.atoms if kind == "eq" and lit.endswith("_stop")}
    stop_verbs |= {v for h in chain for v in h.inner if v.endswith(("_stop", "_end", "_abort"))}
    candidates = set(app_stops(caps_text)) | stop_verbs
    candidates |= {v + s for v in real for s in JUNK_SUFFIXES}
    for command in sorted(candidates):
        verb = command.split(" ")[0]
        if verb in real:
            continue
        h = first_match(chain, command)
        if h is None or h.group:
            continue  # the CLI refuses it / the namespace handler rejects an unknown sub-verb
        if ("eq", command) in h.atoms:
            continue  # its own exact handler (idle-stop guard)
        out.append(f"'{command}' lands in {h.where} (startsWith of {sorted(h.verbs())}): it would run "
                   f"that command; use exact verb matching or an idle-stop guard")
    for stop in IDLE_STOPS:
        h = first_match(chain, stop)
        if h is None or ("eq", stop) not in h.atoms:
            out.append(f"idle-stop guard for {stop} missing")
        elif '"not running"' not in h.block or "_run" in h.block or "_headless(" in h.block:
            out.append(f"{h.where}: idle-stop guard for {stop} must only answer \"not running\"")
    return out


def load_sources():
    return {p.name: p.read_text() for p in sorted(COMPANION.glob("*.cpp"))}


class CompanionDispatchStopsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = load_sources()
        cls.caps = CAPS.read_text()

    def test_chain_parsed(self):
        chain = dispatch_chain(self.sources, "full")
        verbs = set().union(*(h.verbs() for h in chain))
        for v in ("companion_exit", "fs_read", "fs_upload_begin", "wifi_deauth", "karma", "subghz_replay",
                  "ir_jammer", "nfc_ndef_push", "ble_ibeacon", "ble_hid_end", "speaker_volume", "nrf24_scan",
                  "script_run", "lora_recv", "jam_detect"):
            self.assertIn(v, verbs, f"{v} not found in the parsed dispatch chain")
        self.assertGreater(len(chain), 80)

    def test_full_build(self):
        found = problems(self.sources, "full", self.caps)
        self.assertEqual([], found, "\n".join(found))

    def test_lite_build(self):
        found = problems(self.sources, "lite", self.caps)
        self.assertEqual([], found, "\n".join(found))

    def test_batch17_cases_answer_idle_or_fall_through(self):
        chain = dispatch_chain(self.sources, "full")
        for stop in ("ir_jammer_stop", "ble_ibeacon_stop", "jam_detect_stop", "karma_stop"):
            h = first_match(chain, stop)
            self.assertIn(("eq", stop), h.atoms, f"{stop} handled by {h.where}")
        self.assertIsNone(first_match(chain, "speaker_volume_stop"),
                          "speaker_volume_stop must fall through to the CLI (unknown command)")
        self.assertIsNone(first_match(chain, "evil_portal_start_stop"))

    # --- mutation checks: each reverted guard is detected -------------------------------------
    def _mutant(self, fname, old, new, variant="full"):
        src = dict(self.sources)
        self.assertIn(old, src[fname], f"mutation anchor missing in {fname}: {old!r}")
        src[fname] = src[fname].replace(old, new)
        return problems(src, variant, self.caps)

    def test_mutation_ir_jammer_prefix(self):
        found = self._mutant("infrared.cpp", 'cmdStr == "ir_jammer_stop"', 'cmdStr == "ir_jammer_stop_unused"')
        self.assertTrue(any("ir_jammer_stop" in p for p in found), found)
        found = self._mutant("infrared.cpp", 'if (cmdStr == "ir_jammer" || cmdStr.startsWith("ir_jammer ")) {',
                             'if (cmdStr.startsWith("ir_jammer")) {')
        self.assertTrue(any("'ir_jammer_zz' lands in" in p for p in found), found)

    def test_mutation_ibeacon_prefix(self):
        found = self._mutant("bluetooth.cpp",
                             'if (cmdStr == "ble_ibeacon" || cmdStr.startsWith("ble_ibeacon ")) {',
                             'if (cmdStr.startsWith("ble_ibeacon")) {')
        self.assertTrue(any("'ble_ibeacon_zz' lands in" in p for p in found), found)

    def test_mutation_speaker_volume_prefix(self):
        found = self._mutant("audio.cpp",
                             'if (cmdStr == "speaker_volume" || cmdStr.startsWith("speaker_volume ")) {',
                             'if (cmdStr.startsWith("speaker_volume")) {')
        self.assertTrue(any("'speaker_volume_stop' lands in" in p for p in found), found)

    def test_mutation_jam_detect(self):
        found = self._mutant("wifi.cpp", 'if (cmdStr == "jam_detect" || cmdStr.startsWith("jam_detect ")) {',
                             'if (cmdStr.startsWith("jam_detect")) {')
        self.assertTrue(any("'jam_detect_zz' lands in" in p for p in found), found)
        found = self._mutant("wifi.cpp", 'if (cmdStr == "jam_detect_stop") {', 'if (cmdStr == "jam_detect_stop_x") {')
        self.assertTrue(any("jam_detect_stop" in p for p in found), found)

    def test_mutation_karma_guard(self):
        found = self._mutant("wifi.cpp", 'if (cmdStr == "karma" || cmdStr.startsWith("karma ")) {',
                             'if (cmdStr.startsWith("karma")) {')
        self.assertTrue(any("'karma_zz' lands in" in p for p in found), found)

    def test_mutation_lite_ir(self):
        found = self._mutant("infrared.cpp", 'cmdStr == "ir_jammer" || cmdStr.startsWith("ir_jammer ")) {\n        sendErr',
                             'cmdStr.startsWith("ir_jammer")) {\n        sendErr', variant="lite")
        self.assertTrue(any("'ir_jammer_zz' lands in" in p for p in found), found)

    def test_mutation_group_rejection(self):
        found = self._mutant("bluetooth.cpp", '"unknown ble_hid_ command"', '"ok"')
        self.assertTrue(any("ble_hid_ no longer rejects" in p for p in found), found)

    def test_mutation_shadowed_verb(self):
        # fs_write before fs_write_append as a bare prefix would swallow the append.
        found = self._mutant("storage.cpp", 'cmdStr.startsWith("fs_write ")', 'cmdStr.startsWith("fs_write")')
        self.assertTrue(found, "a bare fs_write prefix was not detected")

    def test_mutation_idle_guard_must_not_start(self):
        found = self._mutant("bluetooth.cpp", '''        if (cmdStr == "ble_ibeacon_stop") {
            companionAckMsg(id, true, "not running");''', '''        if (cmdStr == "ble_ibeacon_stop") {
            runIbeaconBounded(8000, "x", "");''')
        self.assertTrue(any("ble_ibeacon_stop" in p for p in found), found)


if __name__ == "__main__":
    unittest.main(verbosity=2)
