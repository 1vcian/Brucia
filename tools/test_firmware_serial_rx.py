#!/usr/bin/env python3
"""Inbound frame capacity and device->host envelope prefix, checked on the host.

1. Compiles src/companion/serial_rx.h natively for each board class (PSRAM, non-PSRAM, env
   override) and checks the RX buffer sizes and the rx_max CAPS reports for them, including the
   fallback sizes setup() can end up with (Bruce's 1024/256 bytes, the core's 256 default).
2. Source checks: setup() sizes the RX buffer through companionSerialRxSetup() before
   Serial.begin(); CAPS emits rx_max/rx_buf; mock_device mirrors rxMaxForBuffer().
3. Every device frame payload starts with '{"v":' (the host resync rule in proto/PROTOCOL.md):
   beginEnvelope() writes "v" first and every sendEnvelope(x) in src/companion builds x with
   beginEnvelope(x, ...) before any other key. If ArduinoJson is in .pio/libdeps, a native
   build also serializes an envelope with the real library and checks the prefix.

Requires a C++ compiler. Run: python3 tools/test_firmware_serial_rx.py
"""
import glob
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import mock_device  # noqa: E402

CXX = os.environ.get("CXX", "c++")

CAPACITY = r'''
#include "serial_rx.h"
#include <cstdio>
using namespace brucia::companion;
uint32_t brucia::companion::serialRxBuffer() { return COMPANION_SERIAL_RX_BUFFER; }
int main() {
    printf("%u %u", (unsigned)COMPANION_SERIAL_RX_BUFFER,
           (unsigned)rxMaxForBuffer(COMPANION_SERIAL_RX_BUFFER, 8192));
    const unsigned sizes[] = {256, 1024, 2048, 4096, 8448, 16384};
    for (unsigned s : sizes) printf(" %u:%u", s, (unsigned)rxMaxForBuffer(s, 8192));
    printf("\n");
}
'''

ENVELOPE = r'''
#include <ArduinoJson.h>
#include <cstdio>
#include <string>
static const uint32_t BRUCIA_PROTO_VERSION = 0;
static void beginEnvelope(JsonDocument &out, uint8_t kind, uint32_t id) {
BODY
}
int main() {
    JsonDocument out;
    beginEnvelope(out, 4, 7);
    out["d"]["ok"] = true;
    std::string s;
    serializeJson(out, s);
    JsonDocument big;
    beginEnvelope(big, 5, 9);   // the oversize path in sendEnvelope(): clear() then rebuild
    big["d"]["x"] = "y";
    big.clear();
    beginEnvelope(big, 5, 9);
    big["d"]["code"] = 413;
    std::string t;
    serializeJson(big, t);
    printf("%s\n%s\n", s.c_str(), t.c_str());
}
'''


def compile_run(code, flags=(), includes=()):
    with tempfile.TemporaryDirectory(prefix="brucia-rx-") as tmp:
        cpp, exe = Path(tmp) / "t.cpp", Path(tmp) / "t"
        cpp.write_text(code)
        cmd = [CXX, "-std=c++17", "-Wall", "-Wextra", *flags, "-I", str(ROOT / "src/companion")]
        for inc in includes:
            cmd += ["-I", str(inc)]
        subprocess.run(cmd + [str(cpp), "-o", str(exe)], check=True)
        return subprocess.run([str(exe)], check=True, capture_output=True, text=True).stdout


def capacity(flags=()):
    out = compile_run(CAPACITY, flags).split()
    table = dict(tuple(map(int, kv.split(":"))) for kv in out[2:])
    return int(out[0]), int(out[1]), table


class SerialRxCapacity(unittest.TestCase):
    def test_psram_class_holds_a_full_frame(self):
        buf, rx_max, _ = capacity(["-DBOARD_HAS_PSRAM"])
        self.assertEqual(buf, 8448)
        self.assertEqual(rx_max, 8192)

    def test_non_psram_class(self):
        buf, rx_max, _ = capacity()
        self.assertEqual(buf, 2048)
        self.assertEqual(rx_max, 1792)

    def test_board_override(self):
        buf, rx_max, _ = capacity(["-DCOMPANION_SERIAL_RX_BUFFER=4096"])
        self.assertEqual((buf, rx_max), (4096, 3840))

    def test_fallback_sizes(self):
        # Bruce's own sizes (setup() fallback) and the core default: the 1024 one is the legacy
        # default hosts assume when CAPS has no rx_max.
        _, _, t = capacity()
        self.assertEqual(t[1024], 768)
        self.assertEqual(t[256], 192)
        self.assertEqual(t[16384], 8192)  # never above COMPANION_MAX_FRAME
        for size, rx_max in t.items():
            self.assertEqual(mock_device.rx_max_for_buffer(size), rx_max, size)

    def test_hosts_share_the_legacy_default(self):
        hw = (ROOT / "tools/hw_acceptance.py").read_text()
        self.assertIn("LEGACY_RX_MAX = 768", hw)
        kt = (ROOT / "android/app/src/main/java/app/brucia/protocol/FrameBudget.kt").read_text()
        self.assertRegex(kt, r"const val LEGACY_RX_MAX = 768\b")


class SetupAndCaps(unittest.TestCase):
    def test_setup_sizes_before_begin(self):
        main = (ROOT / "src/main.cpp").read_text()
        setup = main[main.index("void setup() {"):]
        rx = setup.index("companionSerialRxSetup(SAFE_STACK_BUFFER_SIZE / 4)")
        begin = setup.index("Serial.begin(115200)")
        self.assertLess(rx, begin)
        self.assertNotIn("Serial.setRxBufferSize", main)

    def test_caps_reports_rx_max(self):
        caps = (ROOT / "src/companion/capabilities.cpp").read_text()
        self.assertIn('d["rx_max"] = rxMaxForBuffer(serialRxBuffer(), COMPANION_MAX_FRAME);', caps)
        self.assertIn('d["rx_buf"] = serialRxBuffer();', caps)


def strip_comments(s):
    s = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group()), s, flags=re.S)
    return re.sub(r"//[^\n]*", "", s)


class EnvelopePrefix(unittest.TestCase):
    def begin_body(self):
        src = (ROOT / "src/companion/protocol.cpp").read_text()
        m = re.search(r"void beginEnvelope\(JsonDocument &out, uint8_t kind, uint32_t id\) \{(.*?)\n\}", src, re.S)
        self.assertIsNotNone(m)
        return m.group(1)

    def test_begin_envelope_writes_v_first(self):
        keys = re.findall(r'out\["(\w+)"\]', self.begin_body())
        self.assertEqual(keys[:1], ["v"])

    def test_every_sender_begins_the_envelope_first(self):
        for f in sorted(glob.glob(str(ROOT / "src/companion/*.cpp"))):
            s = strip_comments(Path(f).read_text())
            for m in re.finditer(r"sendEnvelope\((\w+)\)", s):
                var, pre = m.group(1), s[:m.start()]
                decls = list(re.finditer(r"JsonDocument\s+" + var + r"\b", pre))
                if not decls:
                    self.assertTrue(f.endswith("protocol.cpp"), f"{f}: {var} has no local JsonDocument")
                    continue
                seg = pre[decls[-1].end():]
                b = re.search(r"beginEnvelope\(" + var + r"\b", seg)
                self.assertIsNotNone(b, f"{f}: sendEnvelope({var}) without beginEnvelope({var})")
                self.assertIsNone(re.search(var + r"\[", seg[:b.start()]),
                                  f"{f}: {var} gets a key before beginEnvelope()")

    def test_real_arduinojson_serialization(self):
        libs = sorted(glob.glob(str(ROOT / ".pio/libdeps/*/ArduinoJson/src")))
        if not libs:
            self.skipTest("ArduinoJson not in .pio/libdeps (run a firmware build first)")
        out = compile_run(ENVELOPE.replace("BODY", self.begin_body()), includes=[libs[0]]).splitlines()
        self.assertEqual(len(out), 2)
        for line in out:
            self.assertTrue(line.startswith('{"v":0,'), line)


if __name__ == "__main__":
    unittest.main(verbosity=2)
