"""tools/eh_catch_guard.py: source scan, .eh_frame/LSDA parsing and the LTO hazard.

Host tests: try/catch ranges from synthetic sources (comments, strings, lambdas,
nested and multi-handler blocks), hand-assembled .eh_frame and LSDA bytes, readelf
line-table text, and, when the PlatformIO Xtensa/RISC-V toolchains are installed,
a real LTO link that reproduces the hazard: a no-exception copy of an inline
function linked first removes the caller's catch and the guard must fail; linked
last the guard must pass.
"""
from contextlib import redirect_stderr, redirect_stdout
import io
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import eh_catch_guard as guard  # noqa: E402

PACKAGES = Path(os.environ.get("PLATFORMIO_CORE_DIR", Path.home() / ".platformio")) / "packages"
TOOLCHAINS = {
    "xtensa": (PACKAGES / "toolchain-xtensa-esp-elf/bin/xtensa-esp32-elf-", ["-mlongcalls"]),
    "riscv": (PACKAGES / "toolchain-riscv32-esp/bin/riscv32-esp-elf-", []),
}


def uleb(value):
    out = bytearray()
    while True:
        byte = value & 0x7f
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def sleb(value):
    out = bytearray()
    while True:
        byte = value & 0x7f
        value >>= 7
        done = (value == 0 and not byte & 0x40) or (value == -1 and byte & 0x40)
        out.append(byte | (0 if done else 0x80))
        if done:
            return bytes(out)


def lsda(actions):
    """LSDA: no lpstart, no ttype table, uleb128 call sites with the given action records.

    actions: list of call-site entries, each None (cleanup only) or a type filter."""
    action_table, sites = b"", b""
    for index, type_filter in enumerate(actions):
        offset = 0
        if type_filter is not None:
            offset = len(action_table) + 1
            action_table += sleb(type_filter) + sleb(0)
        sites += uleb(index * 4) + uleb(4) + uleb(0x40 + index) + uleb(offset)
    return bytes([0xff, 0xff, 0x01]) + uleb(len(sites)) + sites + action_table


def eh_frame(fdes, pcrel=False, base=0):
    """One 'zLR' CIE and FDEs (pc, length, lsda address or 0) with absptr or pcrel sdata4."""
    enc = 0x1b if pcrel else 0x00
    cie_body = (struct.pack("<I", 0) + bytes([1]) + b"zLR\0" + uleb(1) + sleb(-4) + bytes([0])
                + uleb(2) + bytes([enc, enc]))
    cie_body += b"\0" * (-(len(cie_body) + 4) % 4)
    out = struct.pack("<I", len(cie_body)) + cie_body
    for pc, length, lsda_address in fdes:
        record = len(out)
        body = struct.pack("<I", record + 4)  # CIE pointer: distance back to the CIE
        field = len(out) + 4 + len(body)
        body += struct.pack("<i", pc - (base + field) if pcrel else pc)
        body += struct.pack("<I", length) + uleb(4)
        field = len(out) + 4 + len(body)
        body += struct.pack("<i", (lsda_address - (base + field)) if (pcrel and lsda_address)
                            else lsda_address)
        body += b"\0" * (-(len(body) + 4) % 4)
        out += struct.pack("<I", len(body)) + body
    return out + struct.pack("<I", 0)


class SourceScanTest(unittest.TestCase):
    def test_try_ranges_ignore_comments_strings_and_find_the_function(self):
        text = "\n".join([
            "// catch (x) in a comment",                      # 1
            'const char *s = "try { } catch (...) {}";',      # 2
            "int f(int n) const {",                           # 3
            "    int r = 0;",                                 # 4
            "    try {",                                      # 5
            "        auto cb = [=]() { return n; };",         # 6
            "        r = cb();",                              # 7
            "    } catch (const std::out_of_range &e) {",     # 8
            "        r = 1;",                                 # 9
            "    } catch (...) { r = 2; }",                   # 10
            "    if (n) { try { r++; } catch (...) {} }",     # 11
            "    /* try { } catch (...) { } */",              # 12
            "    return r;",                                  # 13
            "}",                                              # 14
            "auto lambda = [](int v) { try { g(v); } catch (int) {} };",  # 15
        ])
        self.assertEqual([(5, 8, 3, 14), (11, 11, 3, 14), (15, 15, 15, 15)], guard.try_blocks(text))

    def test_catch_sites_scan_source_roots_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            for rel, text in {"src/a.cpp": "void a() { try { b(); } catch (...) {} }\n",
                              "src/plain.cpp": "int catcher = 1;\n",
                              "lib/x/y.h": "inline void y() {\ntry { z(); }\ncatch(int) {}\n}\n",
                              "lib/x/tool.pde": "try { } catch (e) { }\n",
                              "other/b.cpp": "void b() { try { } catch (...) {} }\n"}.items():
                path = Path(tmp) / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            self.assertEqual({"lib/x/y.h": [(2, 2, 1, 4)], "src/a.cpp": [(1, 1, 1, 1)]},
                             guard.catch_sites(tmp))

    def test_repository_catch_sites(self):
        sites = guard.catch_sites(str(Path(__file__).resolve().parents[1]))
        self.assertEqual(9, sum(len(blocks) for blocks in sites.values()))
        self.assertIn("src/modules/ethernet/ARPScanner.cpp", sites)


class BytesTest(unittest.TestCase):
    def test_lsda_catch_and_cleanup(self):
        self.assertTrue(guard.lsda_has_catch(lsda([None, 1]), 0x1000))
        self.assertTrue(guard.lsda_has_catch(lsda([2]), 0x1000))
        self.assertFalse(guard.lsda_has_catch(lsda([None, None]), 0x1000))
        self.assertFalse(guard.lsda_has_catch(lsda([-1]), 0x1000))  # exception spec, not a catch
        self.assertFalse(guard.lsda_has_catch(lsda([]), 0x1000))

    def test_eh_frame_absptr_and_pcrel(self):
        fdes = [(0x40080000, 0x20, 0x3f400100), (0x40080020, 0x10, 0)]
        for pcrel in (False, True):
            with self.subTest(pcrel=pcrel):
                parsed = guard.parse_eh_frame(eh_frame(fdes, pcrel, 0x3f401000) + b"trailing",
                                              0x3f401000)
                self.assertEqual([(0x40080000, 0x40080020, 0x3f400100), (0x40080020, 0x40080030, None)],
                                 parsed)

    def test_decoded_line_table(self):
        text = "\n".join([
            "Contents of the .debug_line section:", "",
            "CU: ./src/modules/a.cpp:",
            "File name                            Line number    Starting address    View    Stmt",
            "a.cpp                                         10          0x400d0000               x",
            "/abs/project/lib/Adafruit PN532/b.h:",
            "b.h                                           3          0x400d0010       1       x",
            "a.cpp                                         -          0x400d0020",
        ])
        rows = guard.parse_decoded_lines(text, ["src/modules/a.cpp", "lib/Adafruit PN532/b.h"])
        self.assertEqual({"src/modules/a.cpp": [(10, 0x400d0000)],
                          "lib/Adafruit PN532/b.h": [(3, 0x400d0010)]}, rows)
        with self.assertRaises(guard.GuardError):
            guard.parse_decoded_lines("CU: x.c:\n", ["x.c"])

    def test_find_readelf(self):
        with tempfile.TemporaryDirectory() as tmp:
            tool = Path(tmp) / "riscv32-esp-elf-readelf"
            tool.write_text("#!/bin/sh\n")
            tool.chmod(0o755)
            self.assertEqual(str(tool), guard.find_readelf(str(Path(tmp) / "riscv32-esp-elf-gcc")))
            self.assertIsNone(guard.find_readelf(str(Path(tmp) / "xtensa-esp32-elf-gcc")))
            self.assertIsNone(guard.find_readelf(None))


BOX = """#include <vector>
template <class T> struct Box {
    std::vector<T> v;
    __attribute__((noinline)) void grow(int n) { v.resize(n); }
};
"""
COMPANION = '#include "box.h"\n__attribute__((used)) int companion_side(int n) ' \
            '{ Box<int> b; b.grow(n); return (int)b.v.size(); }\n'
CATCHER = """#include "box.h"
__attribute__((used)) int module_side(int n) {
    int result = 0;
    try {
        Box<int> b;
        b.grow(n);
        result = 1;
    } catch (...) {
        result = -1;
    }
    return result;
}
"""


class LtoHazardTest(unittest.TestCase):
    """The hazard the guard exists for, reproduced with the firmware toolchains."""

    def link(self, tmp, prefix, extra, order):
        common = [*extra, "-g", "-Os", "-flto", "-ffunction-sections", "-fexceptions"]
        noexc = ["-fno-exceptions", "-fno-asynchronous-unwind-tables", "-fno-unwind-tables"]
        for source, flags in (("companion.cpp", noexc), ("src/catcher.cpp", [])):
            subprocess.run([f"{prefix}g++", *common, *flags, "-I.", "-c", source,
                            "-o", Path(source).stem + ".o"], cwd=tmp, check=True)
        subprocess.run([f"{prefix}g++", *common, "-nostdlib", "-nostartfiles", "-Wl,-e,0",
                        "-Wl,--unresolved-symbols=ignore-all", *order, "-o", "out.elf"],
                       cwd=tmp, check=True, capture_output=True)
        return guard.check(str(Path(tmp) / "out.elf"), f"{prefix}readelf", tmp, roots=("src",))

    def test_guard_fails_when_the_no_exception_copy_wins(self):
        ran = 0
        for arch, (prefix, extra) in TOOLCHAINS.items():
            if not Path(f"{prefix}g++").exists():
                continue
            ran += 1
            with self.subTest(arch=arch), tempfile.TemporaryDirectory() as tmp:
                os.mkdir(Path(tmp, "src"))
                for name, text in (("box.h", BOX), ("companion.cpp", COMPANION),
                                   ("src/catcher.cpp", CATCHER)):
                    Path(tmp, name).write_text(text)
                problems, summary = self.link(tmp, str(prefix), extra, ["companion.o", "catcher.o"])
                self.assertEqual(1, len(problems), summary)
                self.assertIn("src/catcher.cpp:4-8", problems[0])
                problems, summary = self.link(tmp, str(prefix), extra, ["catcher.o", "companion.o"])
                self.assertEqual([], problems)
                self.assertTrue(summary.startswith("1 try/catch site(s) verified"), summary)
                with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                    self.assertEqual(0, guard.main(["--project-dir", tmp, "--root", "src",
                                                    "--readelf", f"{prefix}readelf",
                                                    str(Path(tmp) / "out.elf")]))
                    self.assertEqual(2, guard.main(["--project-dir", tmp, "--readelf",
                                                    f"{prefix}readelf", str(Path(tmp) / "none.elf")]))
        if not ran:
            self.skipTest("PlatformIO Xtensa/RISC-V toolchains not installed")


if __name__ == "__main__":
    unittest.main()
