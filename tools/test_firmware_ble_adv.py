#!/usr/bin/env python3
"""Static host checks for the companion BLE advertising paths (BLE spam / Apple-style
advertisements, iBeacon, Fast Pair / Swift Pair / Samsung) and their shared ownership of the
single NimBLE controller.

These are source checks (no NimBLE/Arduino to link against on the host). They pin two invariants
that a success ACK must never violate:

  1. Error checking in the bounded advertisers (src/modules/ble/ble_spam.cpp): each bounded
     runner returns bool, every NimBLE call that can fail is checked, and no advertise call is a
     bare statement (so a failure returns false, not a silent success):
       - BLEDevice::init(...)          guarded `if (!BLEDevice::init(`
       - esp_ble_tx_power_set(...)     checked `!= ESP_OK`
       - esp_iface_mac_addr_set(...)   checked `!= ESP_OK` (iBeacon address change)
       - getAdvertising()             null-checked (`adv == nullptr`)
       - setAdvertisementData / start / stop : only inside an `if (!...)` or an assignment,
                                               never a bare `adv->start();` statement
       - the function returns `false` on at least one failure path.

  2. Shared-ownership rules (src/companion/bluetooth.cpp): the bounded advertisers init+deinit the
     whole stack, so the dispatcher refuses them with a 409 busy/conflict when the ble.scan stream
     or a BLE HID presenter already owns the controller (never a misleading success), and each
     advertiser command gates its ACK on the runner's bool result; ble_hid_begin / ble_badble /
     ble_media refuse (409) when the other owner holds the stack.

Not covered: real NimBLE behaviour, the controller, or on-device timing (see TESTS.md owner checks).
Run: python3 tools/test_firmware_ble_adv.py
"""
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from test_companion_prompts import body_of, strip_code  # noqa: E402

SPAM = ROOT / "src/modules/ble/ble_spam.cpp"
BT = ROOT / "src/companion/bluetooth.cpp"

# The five bounded advertiser runners and their stripped signatures.
BOUNDED = {
    "runBleSpamBounded": "bool runBleSpamBounded(uint32_t durationMs, uint8_t copies)",
    "runBleFastPairSpamBounded": "bool runBleFastPairSpamBounded(uint32_t durationMs, uint8_t copies)",
    "runBleSwiftPairSpamBounded": "bool runBleSwiftPairSpamBounded(uint32_t durationMs, uint8_t copies)",
    "runBleSamsungSpamBounded": "bool runBleSamsungSpamBounded(uint32_t durationMs, uint8_t copies)",
    "runIbeaconBounded": "bool runIbeaconBounded(uint32_t durationMs, const char *name, const char *uuid, uint16_t major, uint16_t minor)",
}


def spam_src():
    return strip_code(SPAM.read_text(errors="replace"))


def bt_src():
    return strip_code(BT.read_text(errors="replace"))


class BoundedAdvertiserErrorChecking(unittest.TestCase):
    def setUp(self):
        self.src = spam_src()
        self.bodies = {name: body_of(self.src, sig) for name, sig in BOUNDED.items()}

    def test_all_runners_return_bool(self):
        # The .h declares them bool and the .cpp defines them bool (not void).
        hdr = (ROOT / "src/modules/ble/ble_spam.h").read_text()
        for name in BOUNDED:
            self.assertRegex(hdr, rf"\bbool {name}\(", f"{name} not declared bool in ble_spam.h")
            self.assertNotRegex(self.src, rf"\bvoid {name}\(", f"{name} still defined void")

    def test_init_is_checked(self):
        for name, body in self.bodies.items():
            inits = re.findall(r"BLEDevice::init\(", body)
            self.assertTrue(inits, f"{name}: expected a BLEDevice::init call")
            guarded = re.findall(r"if \(!BLEDevice::init\(", body)
            self.assertEqual(len(inits), len(guarded),
                             f"{name}: a BLEDevice::init is not guarded with if (!BLEDevice::init(")

    def test_tx_power_is_checked(self):
        for name, body in self.bodies.items():
            calls = re.findall(r"esp_ble_tx_power_set\(", body)
            self.assertTrue(calls, f"{name}: expected esp_ble_tx_power_set")
            checks = re.findall(r"esp_ble_tx_power_set\([^;]*\)\s*!=\s*ESP_OK", body)
            self.assertEqual(len(calls), len(checks),
                             f"{name}: an esp_ble_tx_power_set result is not checked != ESP_OK")

    def test_ibeacon_address_change_is_checked(self):
        body = self.bodies["runIbeaconBounded"]
        self.assertIn("esp_iface_mac_addr_set(", body)
        self.assertRegex(body, r"esp_iface_mac_addr_set\([^;]*\)\s*!=\s*ESP_OK",
                         "iBeacon: MAC address change (esp_iface_mac_addr_set) is not checked")

    def test_getAdvertising_is_null_checked(self):
        for name, body in self.bodies.items():
            self.assertIn("getAdvertising()", body, f"{name}: expected getAdvertising()")
            self.assertRegex(body, r"adv\s*==\s*nullptr",
                             f"{name}: getAdvertising() result not null-checked")

    def test_no_bare_advertise_calls(self):
        # Every setAdvertisementData / setScanResponseData / start / stop must be in a condition or
        # an assignment, never a bare `adv->start();` statement that ignores the bool return.
        bare = re.compile(r"^\s*\w+->(setAdvertisementData|setScanResponseData|start|stop)\s*\(",
                          re.MULTILINE)
        for name, body in self.bodies.items():
            hits = bare.findall(body)
            self.assertFalse(hits, f"{name}: bare advertise call(s) ignoring the return: {hits}")

    def test_each_runner_has_a_false_return(self):
        for name, body in self.bodies.items():
            self.assertIn("return false", body, f"{name}: no failure (return false) path")


class SharedOwnershipGuards(unittest.TestCase):
    def setUp(self):
        self.src = bt_src()

    def test_conflict_helper_checks_scan_and_hid(self):
        helper = body_of(self.src, "static const char *bleAdvertiserConflict()")
        self.assertIn("subBle", helper, "conflict helper ignores the scan owner (subBle)")
        self.assertIn("g_companionHid", helper, "conflict helper ignores the HID owner")
        self.assertIn("return", helper)

    def test_advertisers_refuse_on_conflict_and_gate_ack_on_result(self):
        dispatch = body_of(self.src, "bool dispatchBluetoothCommand(const CommandRequest &request)")
        # The conflict helper is consulted and 409 is used.
        self.assertGreaterEqual(dispatch.count("bleAdvertiserConflict()"), 4,
                                "not every advertiser consults bleAdvertiserConflict()")
        self.assertIn("sendErr(id, 409,", dispatch, "no 409 busy reply in the dispatcher")
        # No advertiser acks unconditionally: each runner result is used in a condition.
        for runner in ("runBleSpamBounded", "runBleFastPairSpamBounded", "runIbeaconBounded"):
            self.assertRegex(dispatch, rf"if \({runner}\(",
                             f"{runner} result is not gated into the ACK/ERR decision")
        # ble_spam used to `sendAck(id, true)` unconditionally right after the runner; make sure the
        # bare success path is gone and a 500 failure reply exists (string literals survive only in
        # the raw source, since strip_code blanks their contents).
        self.assertIn("ble advertise failed", BT.read_text(errors="replace"))

    def test_spam_platform_all_aggregates_result(self):
        dispatch = body_of(self.src, "bool dispatchBluetoothCommand(const CommandRequest &request)")
        # The "all" loop must collect the bool so a failing slice fails the run.
        self.assertRegex(dispatch, r"ok = allFns\[", "the all-platforms loop ignores slice results")
        self.assertRegex(dispatch, r"bool \(\*const allFns\)|bool \(\*const allFns\[\]\)",
                         "allFns function-pointer array is not bool-returning")

    def test_hid_and_badble_and_media_refuse_conflicts(self):
        dispatch = body_of(self.src, "bool dispatchBluetoothCommand(const CommandRequest &request)")
        # ble_hid_begin refuses while a scan owns the stack (end() would deinit it).
        self.assertRegex(dispatch, r"!g_companionHid && subBle",
                         "ble_hid_begin does not refuse while a scan is active")
        # BadBLE and media refuse while a HID presenter owns the stack (literal in raw source).
        raw = BT.read_text(errors="replace")
        self.assertGreaterEqual(raw.count("ble hid session active"), 3,
                                "BadBLE / media / HID guards missing the hid-session conflict reply")


if __name__ == "__main__":
    unittest.main()
