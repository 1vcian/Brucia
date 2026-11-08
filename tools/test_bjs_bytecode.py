#!/usr/bin/env python3
"""Compiled BruceJS (.bin) structural check: JS_CheckBytecode, added to mquickjs by
patch_library_conflicts.py, on a host build with guard pages.

Builds the installed mquickjs (copied, patched like the firmware build) plus a harness, compiles
real scripts with the mqjs compiler, then checks:
  - valid bytecode passes, relocates, loads and runs (expected output);
  - truncations, wrong magic/version, misalignment and an empty file are rejected;
  - thousands of random corruptions (byte flips, word overwrites, cuts): whenever the check accepts
    one, relocation and loading must not touch memory outside the buffer. Every copy sits against a
    PROT_NONE page (after it, or before it on alternate runs), so any out-of-bounds access faults.
    (AddressSanitizer binaries do not start in the sandboxed agent shell; guard pages need no runtime.)
The accepted-but-corrupt files are not executed: the check bounds the file structure, not the
opcodes (documented limit). Host bytecode is 64-bit; the device loads 32-bit bytecode through the
same code compiled with JSW=4.
Skipped when the mquickjs library is not installed (run a firmware build first) or no C compiler.
"""
import os
import runpy
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LIBDEPS = ROOT / ".pio/libdeps"

HARNESS = r'''
#define main mqjs_main
#include "mqjs.c"
#undef main
#include <stdint.h>
#include <sys/mman.h>
#include <unistd.h>

/* Copy [src] into a mapping whose neighbour page is PROT_NONE: right after the (8-aligned) data when
   at_end, else right before it. Any access outside the copy faults. */
static uint8_t *g_map_base; static size_t g_map_len;
static uint8_t *guarded_copy(const uint8_t *src, size_t len, int at_end) {
    size_t pg = (size_t)getpagesize(), body = (len + 7) & ~(size_t)7, pages = (body + pg - 1) / pg;
    if (pages == 0) pages = 1;
    g_map_len = (pages + 2) * pg;
    g_map_base = mmap(NULL, g_map_len, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (g_map_base == MAP_FAILED) exit(2);
    mprotect(g_map_base, pg, PROT_NONE);
    mprotect(g_map_base + (pages + 1) * pg, pg, PROT_NONE);
    uint8_t *p = at_end ? g_map_base + (pages + 1) * pg - body : g_map_base + pg;
    if (len) memcpy(p, src, len);
    return p;
}
static void guarded_free(void) { munmap(g_map_base, g_map_len); }

static uint8_t *load(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) { perror(path); exit(2); }
    fseek(f, 0, SEEK_END); *len = (size_t)ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t *b = malloc(*len ? *len : 1);
    if (fread(b, 1, *len, f) != *len) exit(2);
    fclose(f);
    return b;
}

/* Exactly the firmware order: check, then relocate + load into a fresh context. run: execute too. */
static int load_bytecode(uint8_t *buf, size_t len, int run) {
    if (JS_CheckBytecode(buf, (uint32_t)len) != 0) return 1;
    size_t mem_size = 256 * 1024;
    uint8_t *mem = malloc(mem_size);
    JSContext *ctx = JS_NewContext(mem, mem_size, &js_stdlib);
    int rc = 0;
    if (JS_RelocateBytecode(ctx, buf, (uint32_t)len) != 0) rc = 3;
    else {
        JSValue v = JS_LoadBytecode(ctx, buf);
        if (JS_IsException(v)) rc = 4;
        else if (run) {
            v = JS_Run(ctx, v);
            if (JS_IsException(v)) rc = 5;
        }
    }
    JS_FreeContext(ctx);
    free(mem);
    return rc;
}

static uint32_t rng = 12345;
static uint32_t rnd(void) { rng ^= rng << 13; rng ^= rng >> 17; rng ^= rng << 5; return rng; }

int main(int argc, char **argv) {
    size_t len;
    uint8_t *orig = load(argv[2], &len);
    if (!strcmp(argv[1], "run")) { int at_end; for (at_end = 0; at_end < 2; at_end++) {
        uint8_t *g = guarded_copy(orig, len, at_end); int rc = load_bytecode(g, len, at_end); guarded_free();
        if (rc) return rc; } return 0; }
    if (!strcmp(argv[1], "check")) return JS_CheckBytecode(orig, (uint32_t)len) ? 1 : 0;
    if (!strcmp(argv[1], "cases")) {
        uint8_t *b = malloc(len + 16);
        size_t cut;
        for (cut = 1; cut < len; cut++) {            /* every truncation, both guard sides */
            int at_end;
            for (at_end = 0; at_end < 2; at_end++) {
                uint8_t *g = guarded_copy(orig, len - cut, at_end);
                if (JS_CheckBytecode(g, (uint32_t)(len - cut)) == 0 && load_bytecode(g, len - cut, 0) != 0) {
                    printf("truncation by %zu accepted but failed to load\n", cut); return 1;
                }
                guarded_free();
            }
        }
        memcpy(b, orig, len); ((JSBytecodeHeader *)b)->magic ^= 1;
        if (JS_CheckBytecode(b, (uint32_t)len) == 0) { puts("bad magic accepted"); return 1; }
        memcpy(b, orig, len); ((JSBytecodeHeader *)b)->version ^= 1;
        if (JS_CheckBytecode(b, (uint32_t)len) == 0) { puts("bad version accepted"); return 1; }
        memcpy(b + 1, orig, len);
        if (JS_CheckBytecode(b + 1, (uint32_t)len) == 0) { puts("misaligned accepted"); return 1; }
        if (JS_CheckBytecode(b, 0) == 0 || JS_CheckBytecode(NULL, 64) == 0) { puts("empty accepted"); return 1; }
        memcpy(b, orig, len);
        if (JS_CheckBytecode(b, (uint32_t)len + 8) == 0) { puts("trailing garbage accepted"); return 1; }
        puts("CASES OK");
        return 0;
    }
    if (!strcmp(argv[1], "fuzz")) {
        int iters = atoi(argv[3]), accepted = 0, it;
        rng = (uint32_t)atoi(argv[4]);
        uint8_t *b = malloc(len);
        for (it = 0; it < iters; it++) {
            memcpy(b, orig, len);
            size_t n = len;
            int kind = rnd() % 4, k, flips = 1 + rnd() % 4;
            for (k = 0; k < flips; k++) {
                size_t pos = sizeof(JSBytecodeHeader) / 2 + rnd() % (len - sizeof(JSBytecodeHeader) / 2);
                if (kind == 0) b[pos] ^= (uint8_t)(1 << (rnd() % 8));
                else if (kind == 1) b[pos] = (uint8_t)rnd();
                else if (kind == 2 && pos + sizeof(JSWord) <= len) {
                    JSWord w = (JSWord)rnd() * 2654435761u; memcpy(b + (pos & ~(sizeof(JSWord) - 1)), &w, sizeof(w));
                } else n = (pos & ~(sizeof(JSWord) - 1)) ? (pos & ~(sizeof(JSWord) - 1)) : n;
            }
            /* the mutated (possibly cut) file, flush against a guard page on alternate sides */
            uint8_t *g = guarded_copy(b, n, it & 1);
            if (JS_CheckBytecode(g, (uint32_t)n) == 0) { accepted++; load_bytecode(g, n, 0); }
            guarded_free();
        }
        free(b);
        printf("FUZZ OK %d/%d accepted\n", accepted, iters);
        return 0;
    }
    return 2;
}
'''

SCRIPTS = {
    "small.js": 'var a = 1 + 2; console.log("sum " + a);',
    "funcs.js": (
        'function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }\n'
        'var names = ["alpha", "beta", "gamma"];\n'
        'var o = { k: 3.25, s: "text", arr: [1, 2, 3] };\n'
        'for (var i = 0; i < names.length; i++) console.log(names[i] + " " + fib(i + 10));\n'
        'console.log(JSON.stringify(o));\n'),
}
EXPECTED = {"small.js": "sum 3\n", "funcs.js": "alpha 55\nbeta 89\ngamma 144\n{\"k\":3.25,\"s\":\"text\",\"arr\":[1,2,3]}\n"}


def installed_mquickjs():
    if not LIBDEPS.is_dir():
        return None
    for env in sorted(LIBDEPS.iterdir()):
        lib = env / "mquickjs"
        if (lib / "mquickjs.c").is_file():
            return lib
    return None


def compiler():
    return shutil.which("cc") or shutil.which("clang") or shutil.which("gcc")


@unittest.skipUnless(installed_mquickjs() and compiler() and shutil.which("make"),
                     "mquickjs not installed (build the firmware once) or no C toolchain")
class BytecodeCheck(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        libroot = tmp / "libdeps"
        lib = libroot / "mquickjs"
        shutil.copytree(installed_mquickjs(), lib, ignore=shutil.ignore_patterns("*.o", "*.d", "mqjs", "mqjs_stdlib"))
        patcher = runpy.run_path(str(ROOT / "patch_library_conflicts.py"), run_name="brucia_test")
        errors = [e for e in patcher["patch_tree"](str(libroot), log=lambda *_: None) if "mquickjs/" in e]
        assert not errors, errors
        assert "int JS_CheckBytecode(" in (lib / "mquickjs.c").read_text()
        make = subprocess.run(["make", "-C", str(lib), "mqjs"], capture_output=True, text=True)
        assert make.returncode == 0, make.stderr[-2000:]
        cls.mqjs = lib / "mqjs"
        (lib / "harness.c").write_text(HARNESS)
        objs = [str(lib / f) for f in ("mquickjs.c", "dtoa.c", "libm.c", "cutils.c", "readline_tty.c", "readline.c")]
        cls.harness = tmp / "harness"
        build = subprocess.run([compiler(), "-g", "-O1", "-D_GNU_SOURCE", "-I", str(lib), str(lib / "harness.c")] + objs + ["-lm", "-o", str(cls.harness)],
                               capture_output=True, text=True)
        assert build.returncode == 0, build.stderr[-3000:]
        cls.bins = {}
        for name, src in SCRIPTS.items():
            js = tmp / name
            js.write_text(src)
            out = tmp / (name + ".bin")
            r = subprocess.run([str(cls.mqjs), "-o", str(out), str(js)], capture_output=True, text=True)
            assert r.returncode == 0, r.stderr
            cls.bins[name] = out

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_harness(self, *args):
        return subprocess.run([str(self.harness)] + [str(a) for a in args], capture_output=True, text=True,
                              timeout=600)

    def test_valid_bytecode_passes_and_runs(self):
        for name, path in self.bins.items():
            r = self.run_harness("run", path)
            self.assertEqual(0, r.returncode, r.stdout + r.stderr)
            self.assertEqual(EXPECTED[name], r.stdout, name)

    def test_truncated_and_bad_headers_are_rejected(self):
        for path in self.bins.values():
            r = self.run_harness("cases", path)
            self.assertEqual(0, r.returncode, r.stdout + r.stderr[-3000:])
            self.assertIn("CASES OK", r.stdout)

    def test_random_corruption_never_reaches_out_of_bounds_access(self):
        for seed, path in enumerate(self.bins.values(), start=7):
            r = self.run_harness("fuzz", path, 4000, seed)
            self.assertEqual(0, r.returncode, r.stdout + r.stderr[-3000:])
            self.assertIn("FUZZ OK", r.stdout)

    def test_unchecked_relocation_is_what_the_check_prevents(self):
        # Control: without the check, a corrupt block size sends the relocation out of bounds.
        lib = self.mqjs.parent
        src = (lib / "harness.c").read_text().replace(
            "if (JS_CheckBytecode(buf, (uint32_t)len) != 0) return 1;", "")
        (lib / "harness_nocheck.c").write_text(src)
        exe = Path(self.tmp.name) / "harness_nocheck"
        objs = [str(lib / f) for f in ("mquickjs.c", "dtoa.c", "libm.c", "cutils.c", "readline_tty.c", "readline.c")]
        b = subprocess.run([compiler(), "-g", "-O1", "-D_GNU_SOURCE", "-I", str(lib),
                            str(lib / "harness_nocheck.c")] + objs + ["-lm", "-o", str(exe)], capture_output=True, text=True)
        self.assertEqual(0, b.returncode, b.stderr[-2000:])
        data = bytearray(self.bins["funcs.js"].read_bytes())
        cut = Path(self.tmp.name) / "cut.bin"
        cut.write_bytes(bytes(data[: len(data) // 2]))
        r = subprocess.run([str(exe), "run", str(cut)], capture_output=True, text=True, timeout=120)
        self.assertNotEqual(0, r.returncode)  # faults on the guard page (or fails) without the check
        self.assertEqual(1, self.run_harness("run", cut).returncode)  # rejected with the check


class FirmwareWiring(unittest.TestCase):
    """Source checks that need no toolchain: the interpreter checks before relocating, loads
    bytecode before any global is registered, and script_run_file validates before starting."""

    def test_interpreter_checks_then_relocates_before_globals(self):
        src = (ROOT / "src/modules/bjs_interpreter/interpreter.cpp").read_text()
        h = src[src.index("void interpreterHandler("):src.index("void startInterpreterTask()")]
        check, reloc, load = h.index("JS_CheckBytecode("), h.index("JS_RelocateBytecode("), h.index("JS_LoadBytecode(")
        self.assertLess(check, reloc)
        self.assertLess(reloc, load)
        self.assertLess(load, h.index("js_timers_init(ctx);"))
        self.assertLess(load, h.index('"__filepath"'))
        self.assertIn("codeSize = scriptSize ? scriptSize : strlen(script)", h)
        self.assertNotIn("strlen(script);\n    log_d", h)
        self.assertIn("readBigFile(&fs, filename, true, &size)", src)

    def test_script_run_file_validates_before_running(self):
        src = (ROOT / "src/companion/system.cpp").read_text()
        f = src[src.index("static void runScriptRunFile("):]
        f = f[:f.index("\n}\n")]
        start = f.index("runHeadlessScript(")
        for needle in ('!path.startsWith("/")', 'lower.endsWith(".bin")', "sendErr(id, 404", "SCRIPT_FILE_MAX_BYTES",
                       "bjs_file_is_bytecode(*fs, path)", "scriptInterpreterBusy(id)"):
            self.assertLess(f.index(needle), start, needle)
        caps = (ROOT / "src/companion/capabilities.cpp").read_text()
        self.assertIn('d["features"]["script_run_file"] = true;', caps)
        self.assertIn("script_run_file", (ROOT / "proto/PROTOCOL.md").read_text())


if __name__ == "__main__":
    unittest.main()
