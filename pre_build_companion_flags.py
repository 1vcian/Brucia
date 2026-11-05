"""Compile only the Brucia companion sources (src/companion/**) without C++ exceptions.

The companion neither throws nor catches, and no caller above its frames catches.
Dropping exception support and unwind tables for these translation units removes
their .eh_frame/.gcc_except_table data on tight LAUNCHER_* app slots. An exception
that reaches a companion frame still ends in std::terminate, as before when nothing
caught it. Every other source keeps the framework flags; the flags are appended
after the inherited ones, so they win over -fexceptions.

Header-defined inline/template functions (std::vector, ArduinoJson, ...) are
emitted by several translation units and the LTO link keeps the first copy it
reads. A companion copy built without exceptions would have no unwind table, so
a module that catches around the same function (BLE scans, ARP parsing) would
terminate instead of catching. The program emitter therefore links the companion
objects after every other object: any copy that another object also defines comes
from that object, built with exceptions.

Because that safety rests on link order, tools/eh_catch_guard.py runs as a post
action on firmware.elf (also for a plain `pio run`): every source try/catch whose
code is linked must still own a catch handler in .gcc_except_table, otherwise the
ELF is deleted and the build fails. The guard needs the toolchain readelf next to
$CC; without it companion sources keep exceptions (never flags without the guard).

Runs as a PlatformIO pre-script listed after patch_library_conflicts.py: its
middleware returns object nodes, so it must be the last one registered. The
helpers are plain Python so host tests can drive them.
"""
import os
import sys
import time

try:
    Import("env")
except NameError:
    env = None  # standalone tests

COMPANION_DIR = "companion"
NO_EXCEPTION_FLAGS = ("-fno-exceptions", "-fno-asynchronous-unwind-tables", "-fno-unwind-tables")
# Companion sources (paths relative to src/companion) that must keep exceptions. None today.
KEEP_EXCEPTIONS = frozenset()


def is_companion_source(source, project_src_dir):
    """True for a source under <project_src_dir>/companion/ not listed in KEEP_EXCEPTIONS."""
    root = os.path.join(os.path.realpath(project_src_dir), COMPANION_DIR)
    path = os.path.realpath(source)
    if os.path.commonpath([root, path]) != root or path == root:
        return False
    return os.path.relpath(path, root).replace(os.sep, "/") not in KEEP_EXCEPTIONS


def link_last(sources, last_paths):
    """Keep the source order but move the objects in last_paths after all the others."""
    first = [node for node in sources if node.get_abspath() not in last_paths]
    return first + [node for node in sources if node.get_abspath() in last_paths]


def guard_action(project_dir, resolve_readelf, log=print):
    """SCons post action: verify the linked ELF, delete it and fail on any problem."""
    sys.path.insert(0, os.path.join(project_dir, "tools"))
    import eh_catch_guard  # noqa: E402 (project tool, path set above)

    def run_guard(target, source, env):
        readelf = resolve_readelf(env)
        if not readelf:
            log("[eh-catch-guard] skipped: companion flags were not applied (no readelf)")
            return 0
        elf = target[0].get_abspath()
        started = time.monotonic()
        try:
            problems, summary = eh_catch_guard.check(elf, readelf, project_dir)
        except eh_catch_guard.GuardError as error:
            problems, summary = [f"guard could not run: {error}"], "unavailable"
        for problem in problems:
            log(f"[eh-catch-guard] FAIL {problem}")
        log(f"[eh-catch-guard] {'FAIL' if problems else 'OK'}: {summary} "
            f"({time.monotonic() - started:.1f} s)")
        if problems:
            log("[eh-catch-guard] a try/catch lost its handler, likely because a no-exception "
                "src/companion copy of an inline function won the LTO link; see "
                "pre_build_companion_flags.py")
            if os.path.exists(elf):
                os.remove(elf)
            return 1
        return 0

    return run_guard


def register(build_env, log=print):
    project_src_dir = build_env.subst("$PROJECT_SRC_DIR")
    project_dir = build_env.subst("$PROJECT_DIR")
    sys.path.insert(0, os.path.join(project_dir, "tools"))
    import eh_catch_guard  # noqa: E402 (project tool, path set above)
    companion_objects, state = set(), {}

    def resolve_readelf(any_env):
        # $CC names the cross compiler only once the platform builder ran, after the
        # pre-scripts, so resolve on first use (first source compiled), then cache.
        if "readelf" not in state:
            compiler = any_env.WhereIs(any_env.subst("$CC")) or any_env.subst("$CC")
            state["readelf"] = eh_catch_guard.find_readelf(compiler)
            if not state["readelf"]:
                log("[companion-flags] toolchain readelf not found next to %s: companion sources "
                    "keep C++ exceptions (the catch guard cannot run)" % compiler)
        return state["readelf"]

    def companion_without_exceptions(node_env, node):
        if not is_companion_source(node.srcnode().get_abspath(), project_src_dir):
            return node
        if not resolve_readelf(node_env):
            return node  # never the flags without the guard
        objects = node_env.Object(node, CCFLAGS=node_env["CCFLAGS"] + list(NO_EXCEPTION_FLAGS))
        companion_objects.update(obj.get_abspath() for obj in objects)
        return objects

    def companion_objects_last(target, source, _env):
        return target, link_last(source, companion_objects)

    build_env.AddBuildMiddleware(companion_without_exceptions)
    build_env.AddPostAction("$BUILD_DIR/${PROGNAME}.elf", build_env.VerboseAction(
        guard_action(project_dir, resolve_readelf, log), "Checking try/catch handlers in $TARGET"))
    emitters = build_env.get("PROGEMITTER") or []
    emitters = list(emitters) if isinstance(emitters, (list, tuple)) else [emitters]
    build_env["PROGEMITTER"] = emitters + [companion_objects_last]
    return companion_without_exceptions, companion_objects_last


if env is not None:
    register(env)
