#!/usr/bin/env python3
"""Compile a BruceJS script to bytecode (.bin) for Brucia firmware.

Builds the mqjs compiler once from the mquickjs library the firmware pins (the copy PlatformIO
installed under .pio/libdeps, patched like the firmware build) into .pio/bjs-compiler/, then runs
`mqjs -m32 -o out.bin in.js`. The bytecode format is tied to that mquickjs version: a .bin built
with another version fails the device's check ("invalid or incompatible bytecode file").

Usage: python3 tools/bjs_compile.py script.js [-o script.bin]
Run a firmware build once first so the library is installed.
"""
import argparse
import hashlib
import runpy
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".pio/bjs-compiler"


def installed_mquickjs():
    libdeps = ROOT / ".pio/libdeps"
    for env in sorted(libdeps.iterdir()) if libdeps.is_dir() else []:
        lib = env / "mquickjs"
        if (lib / "mquickjs.c").is_file():
            return lib
    return None


def source_digest(lib):
    h = hashlib.sha256()
    for f in sorted(lib.glob("*.[ch]")):
        h.update(f.name.encode())
        h.update(f.read_bytes())
    return h.hexdigest()[:16]


def build_compiler():
    lib = installed_mquickjs()
    if lib is None:
        sys.exit("mquickjs is not installed: run a firmware build once (pio run -e lilygo-t-embed-cc1101).")
    if shutil.which("make") is None:
        sys.exit("make and a C compiler are needed to build the mqjs compiler.")
    digest = source_digest(lib)
    out = CACHE / digest
    exe = out / "mquickjs" / "mqjs"
    if exe.is_file():
        return exe
    shutil.rmtree(out, ignore_errors=True)
    (out / "mquickjs").parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(lib, out / "mquickjs", ignore=shutil.ignore_patterns("*.o", "*.d", "mqjs", "mqjs_stdlib"))
    patcher = runpy.run_path(str(ROOT / "patch_library_conflicts.py"), run_name="bjs_compile")
    errors = [e for e in patcher["patch_tree"](str(out), log=lambda *_: None) if "mquickjs/" in e]
    if errors:
        sys.exit("\n".join(errors))
    r = subprocess.run(["make", "-C", str(out / "mquickjs"), "mqjs"], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(r.stderr[-3000:] or "mqjs build failed")
    return exe


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("script", type=Path)
    ap.add_argument("-o", "--output", type=Path)
    args = ap.parse_args()
    if not args.script.is_file():
        sys.exit(f"{args.script}: not found")
    output = args.output or args.script.with_suffix(".bin")
    exe = build_compiler()
    r = subprocess.run([str(exe), "-m32", "-o", str(output), str(args.script)], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(r.stderr or r.stdout or "compile failed")
    print(f"{output} ({output.stat().st_size} bytes, 32-bit bytecode)")


if __name__ == "__main__":
    main()
