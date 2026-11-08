#!/usr/bin/env python3
"""BLE API serial bridge (NUS, ported from upstream Bruce 20bb8b18) with Brucia's pairing rule.

- Host-compiles src/modules/ble_api/services/ble_line_buffer.h: complete lines only, a line over
  the bound is dropped whole and the stream resynchronises, CRLF; the peer gate accepts only an
  encrypted + passkey-authenticated link, one peer at a time, and forgets it on disconnect.
- Static checks of the NimBLE wiring: bonding + MITM + Secure Connections with a display-only
  passkey, RX writable only when encrypted and authenticated, output notified to the authorized
  connection only, an insecure link disconnected after pairing, the old va_list sprintf gone.
No radio is used; pairing with a real phone is a device check.
"""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SVC = ROOT / "src/modules/ble_api/services"
API = ROOT / "src/modules/ble_api/ble_api.cpp"

HARNESS = r'''
#include "ble_line_buffer.h"
#include <cassert>
#include <cstdio>
#include <cstring>
using namespace ble_api;
static void feed(LineBuffer &b, const char *s) { b.feed((const uint8_t *)s, strlen(s)); }
#define CHECK(c) do { if (!(c)) { printf("FAIL %d %s\n", __LINE__, #c); return 1; } } while (0)
int main() {
    { LineBuffer b; feed(b, "inf"); CHECK(b.available() == 0); CHECK(b.takeLine() == "");
      feed(b, "o\r\nfree\n"); CHECK(b.available() == 6); CHECK(b.takeLine() == "info");
      CHECK(b.takeLine() == "free"); CHECK(b.available() == 0); }
    { LineBuffer b; std::string big(LineBuffer::MAX_LINE + 1, 'x');
      feed(b, "ok\n"); feed(b, big.c_str()); feed(b, "tail-of-the-long-one\nnext\n");
      CHECK(b.takeLine() == "ok"); CHECK(b.takeLine() == "next"); CHECK(b.available() == 0); CHECK(b.size() == 0); }
    { LineBuffer b; std::string edge(LineBuffer::MAX_LINE, 'y'); feed(b, edge.c_str()); feed(b, "\n");
      CHECK(b.takeLine() == edge); }
    { LineBuffer b; feed(b, "ab"); CHECK(b.read() == -1); feed(b, "\n"); CHECK(b.read() == 'a');
      b.clear(); CHECK(b.available() == 0); }
    { PeerGate g; CHECK(!g.mayWrite(1, true, true));
      CHECK(!g.onAuthenticated(1, true, false)); CHECK(!g.onAuthenticated(1, false, true));
      CHECK(g.peer() == PeerGate::NONE);
      CHECK(g.onAuthenticated(1, true, true)); CHECK(g.mayWrite(1, true, true));
      CHECK(!g.mayWrite(2, true, true)); CHECK(!g.mayWrite(1, true, false));
      CHECK(!g.onAuthenticated(2, true, true));
      g.onDisconnected(2); CHECK(g.peer() == 1);
      g.onDisconnected(1); CHECK(g.peer() == PeerGate::NONE); CHECK(!g.mayWrite(1, true, true));
      CHECK(g.onAuthenticated(2, true, true)); }
    puts("ALL OK");
    return 0;
}
'''


def compiler():
    return shutil.which("c++") or shutil.which("clang++") or shutil.which("g++")


class LineBufferAndGate(unittest.TestCase):
    @unittest.skipUnless(compiler(), "no host C++ compiler")
    def test_host(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, exe = Path(tmp) / "h.cpp", Path(tmp) / "h"
            src.write_text(HARNESS)
            b = subprocess.run([compiler(), "-std=c++17", "-Wall", "-Werror", "-I", str(SVC), str(src), "-o", str(exe)],
                               capture_output=True, text=True)
            self.assertEqual(0, b.returncode, b.stderr)
            r = subprocess.run([str(exe)], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, r.returncode, r.stdout)
            self.assertIn("ALL OK", r.stdout)


class NimbleWiring(unittest.TestCase):
    def setUp(self):
        self.svc = (SVC / "BLESerialService.cpp").read_text()
        self.api = API.read_text()

    def test_pairing_is_required_with_a_passkey(self):
        self.assertIn("NimBLEDevice::setSecurityAuth(true, true, true);", self.api)
        self.assertIn("NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);", self.api)
        self.assertIn("NimBLEDevice::setSecurityPasskey(passkey_);", self.api)
        self.assertIn("passkey_ = esp_random() % 1000000;", self.api)
        auth = self.api[self.api.index("void onAuthenticationComplete"):]
        auth = auth[:auth.index("\n    }\n")]
        self.assertIn("authorize(", auth)
        self.assertIn("disconnect(connInfo.getConnHandle())", auth)

    def test_rx_needs_encryption_and_authentication(self):
        rx = self.svc[self.svc.index("rx_char = pService->createCharacteristic("):]
        rx = rx[:rx.index(";")]
        self.assertIn("NIMBLE_PROPERTY::WRITE_ENC", rx)
        self.assertIn("NIMBLE_PROPERTY::WRITE_AUTHEN", rx)
        feed = self.svc[self.svc.index("void BLESerialService::feedRx("):]
        feed = feed[:feed.index("\n}\n")]
        self.assertLess(feed.index("gate.mayWrite(handle, encrypted, authenticated)"), feed.index("rx.feed("))

    def test_output_goes_to_the_authorized_peer_only(self):
        n = self.svc[self.svc.index("void BLESerialService::notifyChunked("):]
        n = n[:n.index("\n}\n")]
        self.assertIn("if (peer == ble_api::PeerGate::NONE) return;", n)
        calls = re.findall(r"notify\(([^;]*)\);", n)
        self.assertTrue(calls)
        for c in calls:
            self.assertTrue(c.rstrip(")").endswith("peer"), c)  # never a broadcast notify
        self.assertNotIn("bleNotifyRetry(tx_char", self.svc)

    def test_vprintf_formats_with_vsnprintf(self):
        v = self.svc[self.svc.index("void BLESerialService::vprintf("):]
        v = v[:v.index("\n}\n")]
        self.assertNotIn("sprintf(str, fmt, args)", v)
        self.assertIn("va_copy(copy, args);", v)

    def test_disconnect_drops_a_half_line_and_the_peer(self):
        gone = self.svc[self.svc.index("void BLESerialService::peerGone("):]
        gone = gone[:gone.index("\n}\n")]
        self.assertIn("rx.clear()", gone)
        self.assertIn("gate.onDisconnected(handle)", gone)
        self.assertIn("api->serial().peerGone(connInfo.getConnHandle())", self.api)


if __name__ == "__main__":
    unittest.main()
