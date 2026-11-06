#!/usr/bin/env python3
"""Static checks on the companion Wi-Fi command dispatch (src/companion/wifi.cpp).

The dispatcher matches commands with ordered `cmdStr == "x"` / `cmdStr.startsWith("x")` tests and
the first match wins. A stop frame the app may send (CommandCapabilities.stops) must never fall into
a "start a run" handler: a bare-word `startsWith("karma")` would otherwise read `karma_stop` on an
idle device as `karma <secs>` and launch a ~60 s run (brucegotchi already guards against this with an
exact `cmdStr == "brucegotchi_stop"` before its startsWith). This test fails if any such `*_stop`
verb reaches a prefix handler instead of an exact-match (stop/idle) handler earlier in the order.
Batch 18: every verb is now matched exactly (`== "x"` or `startsWith("x ")`); the same check for all
dispatchers, with made-up `<verb>_<suffix>` commands and mutation checks, is
tools/test_companion_dispatch_stops.py.

Also pins the monitor channel clamp (monStart) at 1..13 to match the app and PROTOCOL.md.

Static only (the dispatcher includes Arduino/esp_wifi headers and is not host-buildable); this is a
source-shape check in the style of tools/test_companion_prompts.py.
"""
import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WIFI = os.path.join(ROOT, "src", "companion", "wifi.cpp")
CAPS = os.path.join(
    ROOT, "android", "app", "src", "main", "java", "app", "brucia", "protocol", "CommandCapabilities.kt"
)


def dispatch_body(text):
    """The body of bool dispatchWifiCommand(...), where the ordered cmd tests live."""
    start = text.index("bool dispatchWifiCommand(")
    return text[start:]


def ordered_conditions(body):
    """In-order list of (kind, literal): kind 'eq' for cmdStr == "x", 'prefix' for startsWith("x")."""
    out = []
    for m in re.finditer(r'cmdStr\s*(==|\.startsWith\()\s*"((?:[^"\\]|\\.)*)"', body):
        kind = "eq" if m.group(1) == "==" else "prefix"
        lit = m.group(2).encode().decode("unicode_escape")
        out.append((kind, lit, m.start()))
    return out


def app_stops(caps_text):
    block = re.search(r"private val stops = setOf\((.*?)\)", caps_text, re.S)
    assert block, "could not find the stops set in CommandCapabilities.kt"
    return re.findall(r'"([^"]+)"', block.group(1))


class CompanionWifiDispatchTest(unittest.TestCase):
    def setUp(self):
        with open(WIFI) as f:
            self.text = f.read()
        self.body = dispatch_body(self.text)
        self.conds = ordered_conditions(self.body)
        with open(CAPS) as f:
            self.stops = app_stops(f.read())

    def test_sources_present(self):
        self.assertTrue(self.conds, "no cmd conditions parsed from dispatchWifiCommand")
        self.assertIn("karma_stop", self.stops)
        self.assertIn("responder_stop", self.stops)
        self.assertIn("brucegotchi_stop", self.stops)

    def _first_match(self, command):
        """The first condition that matches `command`, as the real first-match dispatch would."""
        for kind, lit, _pos in self.conds:
            if kind == "eq" and command == lit:
                return kind, lit
            if kind == "prefix" and command.startswith(lit):
                return kind, lit
        return None

    def test_app_stop_frames_never_start_a_run(self):
        # Batch 18 generalized this check to every dispatcher (tools/test_companion_dispatch_stops.py)
        # and replaced the bare-word prefixes with exact verb matching. Here: each app stop either
        # reaches its own exact handler (idle-stop guard / wifi_disconnect) or no Wi-Fi handler.
        for stop in self.stops:
            match = self._first_match(stop)
            if match is None:
                continue
            kind, lit = match
            self.assertEqual(stop, lit.rstrip(" "),
                             f"app stop '{stop}' falls into the handler of '{lit}'; use exact verb matching")
        for guarded in ("karma_stop", "responder_stop", "brucegotchi_stop", "jam_detect_stop"):
            self.assertEqual(("eq", guarded), self._first_match(guarded), f"{guarded} idle guard missing")

    def test_no_bare_word_prefix_left(self):
        bare = [lit for kind, lit, _ in self.conds if kind == "prefix" and not lit.endswith(" ")]
        self.assertEqual([], bare, "bare-word startsWith() also matches '<verb>_stop'; match the verb exactly")

    def test_monitor_channel_clamped_to_1_13(self):
        mon = self.text[self.text.index("void monStart("):]
        mon = mon[: mon.index("void monStop(")]
        self.assertIn("channel >= 1 && channel <= 13", mon,
                      "monStart must lock the channel only for 1..13 (app/PROTOCOL range)")
        self.assertNotIn("channel <= 14", mon, "monStart still accepts channel 14; clamp to 13")


if __name__ == "__main__":
    unittest.main()
