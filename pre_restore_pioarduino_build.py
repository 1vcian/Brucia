"""
Keep the shared pioarduino-build.py of framework-arduinoespressif32-libs pristine.

pioarduino's component_manager.py edits the GLOBAL
  <libs>/<chip>/pioarduino-build.py
in place at script-load time (lib_ignore -> CPPPATH removal, LTO / picolibc
flag rewrites), saves the original as pioarduino-build.py.<chip>, and puts the
original back ONLY as a post-action of the "checkprogsize" target. Any run that
does not reach checkprogsize (compile/link error, Ctrl-C, -t envdump/idedata/
compiledb/buildfs, ...) leaves the edited file behind, and every later env of
the same chip inherits it. E.g. `lib_ignore = SD` maps to the "fatfs" component
and strips include/fatfs/{diskio,src,vfs}, so FFat.cpp stops compiling for every
esp32s3 env until the file is restored.

This pre: script runs before the platform's framework script:
  1. on start: if a stale backup exists, restore it (self-heal);
  2. on exit (any outcome): restore again if the platform did not.
It never edits anything itself, so the env's own lib_ignore behaviour is intact.
Not safe for CONCURRENT pio processes sharing one PLATFORMIO_CORE_DIR; use
separate core dirs for parallel builds.
"""
import atexit
import glob
import os
import shutil

Import("env")  # noqa: F821  (SCons global)

_PKG = "framework-arduinoespressif32-libs"
_NAME = "pioarduino-build.py"


def _libs_dir():
    try:
        return env.PioPlatform().get_package_dir(_PKG) or ""  # noqa: F821
    except Exception:
        return ""


def _restore_stale(reason):
    libs = _libs_dir()
    if not libs or not os.path.isdir(libs):
        return
    for backup in glob.glob(os.path.join(libs, "*", _NAME + ".*")):
        target = os.path.join(os.path.dirname(backup), _NAME)
        try:
            shutil.copy2(backup, target)
            os.remove(backup)
            print("[pioarduino-guard] %s: restored %s" % (reason, os.path.relpath(target, libs)))
        except OSError as exc:
            print("[pioarduino-guard] %s: could not restore %s: %s" % (reason, target, exc))


_restore_stale("pre-build")
atexit.register(_restore_stale, "exit")
