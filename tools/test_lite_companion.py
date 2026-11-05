#!/usr/bin/env python3
"""LITE builds compile five companion endpoint groups out; CAPS and the dispatchers say so.

LITE_VERSION envs (LAUNCHER_*, m5stack-core4mb) drop mifare_keycheck, the NRF24 companion
commands, the companion IR commands (ir_tvbgone / ir_rx / ir_tx_raw / ir_jammer), the GATT
explorer (ble_gatt_*) and `diag` with its link counters, for app-slot headroom
(src/companion/config.h). This host test runs the real sources through the host C preprocessor
(includes stripped, board macros of a CYD-like board with NRF24 + IR wiring):

- config.h defines the COMPANION_HAS_* group macros on full builds only;
- the sendCaps() features block emits literal false for each dropped group on LITE, never the
  keycheck/diag true flags, and is unchanged on full builds;
- each dispatcher's LITE branch answers ERR 501 instead of calling the handler, and the handlers
  (and the diag counter hooks) are not compiled on LITE;
- tools/mock_device.py --profile lite reports the same CAPS features and 501 texts over a PTY.

It does not build firmware or validate anything on hardware.
Run: python3 tools/test_lite_companion.py
"""
import json
import os
from pathlib import Path
import pty
import re
import struct
import subprocess
import sys
import tempfile
import threading
import time
import tty
import unittest

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "src/companion"
sys.path.insert(0, str(ROOT / "tools"))
import mock_device  # noqa: E402

CXX = os.environ.get("CXX", "c++")
BOARD = ["-DUSE_NRF24_VIA_SPI", "-DTXLED=22"]  # CYD-2432S028: NRF24 + IR wired
GROUP_MACROS = {"COMPANION_HAS_MIFARE_KEYCHECK", "COMPANION_HAS_IR_CMDS", "COMPANION_HAS_GATT",
                "COMPANION_HAS_DIAG", "COMPANION_HAS_NRF24"}
LITE_FALSE = {"mifare_keycheck": False, "ir_cmds": False, "ble_gatt": False, "diag": False}
FULL_FEATURES = {"verified_files": True, "transactional_uploads": True, "mifare_keycheck_report": True,
                 "mifare_keycheck_stop": True, "diag": True, "capture_descriptor": True}
# (file, handler call that must vanish on LITE, ERR 501 text that replaces it)
DISPATCH = [
    ("nfc.cpp", "runMifareKeycheck(", "mifare_keycheck not available in this (lite) build"),
    ("infrared.cpp", "runIrRx(", "ir companion commands not available in this (lite) build"),
    ("infrared.cpp", "ir_jammer_headless(", "ir companion commands not available in this (lite) build"),
    ("bluetooth.cpp", "runBleGattConnect(", "ble_gatt not available in this (lite) build"),
    ("bluetooth.cpp", "runBleGattRead(", "ble_gatt not available in this (lite) build"),
    ("system.cpp", "runDiagCommand(", "diag not available in this (lite) build"),
    ("nrf24.cpp", "runNrf24Scan(", "nrf24 not available in this (lite) build"),
    ("nrf24.cpp", "runNrf24Mousejack(", "nrf24 not available in this (lite) build"),
    ("system.cpp", "runScriptRun(", "script_run not available in this (lite) build"),
]


def _defines(lite):
    return BOARD + (["-DLITE_VERSION=1"] if lite else [])


def config_macros(lite):
    """COMPANION_* macros config.h defines for this board, LITE or full."""
    with tempfile.TemporaryDirectory(prefix="brucia-lite-") as tmp:
        (Path(tmp) / "precompiler_flags.h").write_text("")
        out = subprocess.run([CXX, "-E", "-dM", "-x", "c++", "-I", tmp, *_defines(lite), str(CORE / "config.h")],
                             check=True, capture_output=True, text=True).stdout
    return {m.group(1) for m in re.finditer(r"#define (COMPANION_\w+)", out)}


def preprocess(name, lite):
    """The source with includes stripped, preprocessed with the board, LITE and config.h macros."""
    src = "\n".join("" if line.lstrip().startswith("#include") else line
                    for line in (CORE / name).read_text().splitlines())
    macros = [f"-D{m}=1" for m in config_macros(lite)]
    with tempfile.TemporaryDirectory(prefix="brucia-lite-") as tmp:
        path = Path(tmp) / name
        path.write_text(src)
        return subprocess.run([CXX, "-E", "-P", "-w", "-x", "c++", *_defines(lite), *macros, str(path)],
                              check=True, capture_output=True, text=True).stdout


def caps_features(lite):
    text = preprocess("capabilities.cpp", lite)
    body = text[text.index("void sendCaps("):]
    feats = {}
    for key, value in re.findall(r'd\["features"\]\["(\w+)"\]\s*=\s*(true|false)\s*;', body):
        if key in feats:
            raise AssertionError(f"features.{key} assigned twice")
        feats[key] = value == "true"
    return feats


class LiteCompileOutTest(unittest.TestCase):
    def test_group_macros_only_on_full_builds(self):
        self.assertEqual(GROUP_MACROS, config_macros(False) & GROUP_MACROS)
        self.assertEqual(set(), config_macros(True) & GROUP_MACROS)
        self.assertIn("COMPANION_HAS_IR", config_macros(True))  # IR hardware stays: Bruce's `ir` CLI

    def test_lite_caps_report_each_dropped_group_as_literal_false(self):
        self.assertEqual({"verified_files": True, "transactional_uploads": True, **LITE_FALSE}, caps_features(True))

    def test_full_caps_features_are_unchanged(self):
        self.assertEqual(FULL_FEATURES, caps_features(False))

    def test_lite_nrf24_evidence_explains_the_absence(self):
        text = preprocess("capabilities.cpp", True)
        probe = text[text.index("static void probeNrf24("):text.index("bool companionNrf24Present()")]
        self.assertIn("NRF24 companion commands not built into this LITE firmware", probe)
        self.assertNotIn("NRFradio", probe)
        self.assertNotIn("not built into this LITE", preprocess("capabilities.cpp", False))

    def test_lite_dispatchers_answer_501_and_handlers_are_gone(self):
        for name, handler, message in DISPATCH:
            lite, full = preprocess(name, True), preprocess(name, False)
            self.assertNotIn(handler, lite, f"{name}: {handler} still compiled on LITE")
            self.assertIn(f'501, "{message}"', lite, f"{name}: no ERR 501 on LITE")
            self.assertIn(handler, full, f"{name}: {handler} missing on full builds")
            self.assertNotIn(message, full, f"{name}: LITE reply leaked into full builds")
        self.assertNotIn("runDiagCommand(", preprocess("diag.cpp", True))
        self.assertNotIn("diagNoteErr(", preprocess("protocol.cpp", True))
        self.assertIn("diagNoteErr(", preprocess("protocol.cpp", False))

    def test_lite_diag_hooks_are_no_ops(self):
        driver = r'''
#include "diag_core.h"
#include <cstdio>
using namespace brucia::companion;
int main() {
    diagNoteSession(); diagNoteRxFrame(); diagNoteTxFrame(); diagNoteBusy();
    diagNoteRxReject(DIAG_RX_BAD_JSON); diagNoteErr(409);
    const DiagLinkCounters &c = diagLink();
    unsigned sum = c.sessions + c.rxFrames + c.txFrames + c.busy409;
    for (int k = 0; k < DIAG_RX_REJECT_KINDS; k++) sum += c.rxRejected[k];
    for (int b = 0; b < DIAG_ERR_BUCKETS; b++) sum += c.err[b];
    std::printf("%u\n", sum);
    return 0;
}
'''
        with tempfile.TemporaryDirectory(prefix="brucia-lite-") as tmp:
            cpp, exe = Path(tmp) / "d.cpp", Path(tmp) / "d"
            cpp.write_text(driver)
            subprocess.run([CXX, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-DLITE_VERSION=1", "-I", str(CORE),
                            str(cpp), "-o", str(exe)], check=True)
            self.assertEqual("0", subprocess.run([str(exe)], check=True, capture_output=True,
                                                 text=True).stdout.strip())

    def test_mock_lite_table_matches_the_firmware(self):
        self.assertEqual({m for _, _, m in DISPATCH}, {msg for _, msg in mock_device.LITE_501})
        self.assertEqual(LITE_FALSE, mock_device.LITE_OMITTED_FEATURES)


class MockLiteProfileTest(unittest.TestCase):
    def setUp(self):
        self.master, self.slave = pty.openpty()
        tty.setraw(self.slave)
        self.dev = mock_device.MockDevice(poll_interval=0.002, profile="lite")
        self.stop = threading.Event()
        self.th = threading.Thread(target=self.dev.serve, args=(self.master, self.stop), daemon=True)
        self.th.start()
        self.buf = b""
        self.next_id = 1

    def tearDown(self):
        self.stop.set()
        self.th.join(timeout=10)
        os.close(self.master)
        os.close(self.slave)

    def request(self, kind, d):
        mid = self.next_id
        self.next_id += 1
        payload = json.dumps({"v": 0, "t": kind, "id": mid, "d": d}).encode()
        os.write(self.slave, struct.pack("<I", len(payload)) + payload)
        deadline = time.time() + 5
        while time.time() < deadline:
            while len(self.buf) >= 4:
                n = struct.unpack("<I", self.buf[:4])[0]
                if len(self.buf) < 4 + n:
                    break
                msg = json.loads(self.buf[4:4 + n])
                self.buf = self.buf[4 + n:]
                if msg.get("id") == mid:
                    return msg
            os.set_blocking(self.slave, False)
            try:
                self.buf += os.read(self.slave, 4096)
            except BlockingIOError:
                time.sleep(0.005)
        self.fail(f"no reply to {d}")

    def test_lite_caps_and_501_replies(self):
        caps = self.request(mock_device.HELLO, {})
        self.assertEqual(mock_device.CAPS, caps["t"])
        self.assertEqual(caps_features(True), caps["d"]["features"])
        self.assertEqual("none", caps["d"]["evidence"]["nrf24"]["level"])
        for cmd, message in [
            ("mifare_keycheck FFFFFFFFFFFF", DISPATCH[0][2]), ("ir_rx", DISPATCH[1][2]),
            ("ir_tvbgone eu", DISPATCH[1][2]), ("ir_tx_raw 38 9000,4500", DISPATCH[1][2]),
            ("ir_jammer 3", DISPATCH[1][2]), ("ble_gatt_connect AA:BB:CC:DD:EE:FF", DISPATCH[3][2]),
            ("ble_gatt_read AA:BB:CC:DD:EE:FF 2a00", DISPATCH[3][2]), ("diag", DISPATCH[5][2]),
            ("nrf24_scan 5", DISPATCH[6][2]), ("nrf24_mousejack 5", DISPATCH[6][2]),
            ("script_run print('x');", DISPATCH[8][2]),
        ]:
            reply = self.request(mock_device.CMD, {"cmd": cmd})
            self.assertEqual((mock_device.ERR, 501, message), (reply["t"], reply["d"]["code"], reply["d"]["msg"]), cmd)
        # Bruce's own CLI and the remaining companion commands still answer.
        for cmd in ("ir rx", 'ir tx_raw 38000 "9000 4500"', "rfid read", "nfc_ndef_push text hi"):
            self.assertEqual(mock_device.ACK, self.request(mock_device.CMD, {"cmd": cmd})["t"], cmd)

    def test_full_profile_is_unchanged(self):
        self.assertEqual("full", mock_device.PROFILES[0])
        full = mock_device.MockDevice()._caps()
        self.assertTrue(full["features"]["diag"] and full["features"]["mifare_keycheck_stop"])
        self.assertFalse((set(LITE_FALSE) - {"diag"}) & set(full["features"]))
        self.assertFalse(mock_device.MockDevice().lite)


if __name__ == "__main__":
    unittest.main()
