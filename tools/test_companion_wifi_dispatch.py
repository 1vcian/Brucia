#!/usr/bin/env python3
"""Static checks on the companion Wi-Fi command dispatch (src/companion/wifi.cpp).

The dispatcher matches commands with ordered `cmdStr == "x"` / `cmdStr.startsWith("x")` tests and
the first match wins. A stop frame the app may send (CommandCapabilities.stops) must never fall into
a "start a run" handler: a bare-word `startsWith("karma")` would otherwise read `karma_stop` on an
idle device as `karma <secs>` and launch a ~60 s run (brucegotchi already guards against this with an
exact `cmdStr == "brucegotchi_stop"` before its startsWith). This test fails if any such `*_stop`
verb reaches a prefix handler instead of an exact-match (stop/idle) handler earlier in the order.

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
        self.text = open(WIFI).read()
        self.body = dispatch_body(self.text)
        self.conds = ordered_conditions(self.body)
        self.stops = app_stops(open(CAPS).read())

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
        # A stop is at risk when a bare-word prefix (no trailing space) is a STRICT prefix of it,
        # i.e. the handler would read it as "<verb> <args>" and launch that verb's run. A prefix
        # equal to the stop is the stop's own dedicated handler and is fine (e.g. wifi_disconnect).
        bare_prefixes = [lit for kind, lit, _ in self.conds if kind == "prefix" and not lit.endswith(" ")]
        at_risk = [s for s in self.stops if any(s.startswith(p) and s != p for p in bare_prefixes)]
        # The two newly guarded runs must appear here, else the test is not exercising anything.
        for expected in ("karma_stop", "responder_stop", "brucegotchi_stop"):
            self.assertIn(expected, at_risk, f"{expected} is no longer guarded by a bare prefix; update the test")
        for stop in at_risk:
            match = self._first_match(stop)
            self.assertIsNotNone(match, f"{stop} matched no handler")
            kind, lit = match
            # Acceptable: an exact guard, or the stop's own dedicated prefix handler (lit == stop).
            self.assertTrue(
                kind == "eq" or lit == stop,
                f"app stop '{stop}' falls into the start handler startsWith(\"{lit}\") before any "
                f"exact 'cmdStr == \"{stop}\"' guard; add an idle-stop guard like brucegotchi_stop",
            )
            if kind == "eq":
                self.assertEqual(stop, lit, f"'{stop}' should be caught by its own exact handler, got '{lit}'")

    def test_monitor_channel_clamped_to_1_13(self):
        mon = self.text[self.text.index("void monStart("):]
        mon = mon[: mon.index("void monStop(")]
        self.assertIn("channel >= 1 && channel <= 13", mon,
                      "monStart must lock the channel only for 1..13 (app/PROTOCOL range)")
        self.assertNotIn("channel <= 14", mon, "monStart still accepts channel 14; clamp to 13")


if __name__ == "__main__":
    unittest.main()
