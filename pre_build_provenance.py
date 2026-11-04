# Release provenance for the companion CAPS `build` object: Git commit (+ dirty flag), a
# deterministic build id/time (SOURCE_DATE_EPOCH, else commit time; never the wall clock) and the
# PlatformIO env. Writes include/brucia_build_info.h only when its content changes; included by
# src/companion/capabilities.cpp and the CLI `info` command (core/serial_commands/util_commands.cpp).
# A tree without .git builds with "unknown" values.
#
# Reproducible compiler inputs (see tools/firmware_provenance.py):
# - SOURCE_DATE_EPOCH is exported from the HEAD commit time when not already set, after the
#   provenance above was computed (so time_src keeps saying "commit"), pinning GCC's
#   __DATE__/__TIME__. Without Git nothing is exported (wall-clock __DATE__, as before).
# - -ffile-prefix-map strips the checkout and PlatformIO core/packages/libdeps locations from
#   __FILE__ and debug info, if the compiler accepts the option.
import os
import sys

Import("env")
root = env.get("PROJECT_DIR")
sys.path.insert(0, os.path.join(root, "tools"))
try:
    import firmware_provenance as fp
finally:
    sys.path.pop(0)

info = fp.collect(root, fp.read_version(root), env.get("PIOENV", fp.UNKNOWN))
fp.write_if_changed(os.path.join(root, "include", fp.HEADER_NAME), fp.render_header(info))
print("Brucia build provenance:", info["id"], "commit", info["commit"],
      "dirty" if info["dirty"] else ("clean" if info["dirty"] is False else "dirty-unknown"),
      "time", info["time"], "(" + info["time_src"] + ")")

sde = fp.source_date_epoch_export(os.environ, fp.head_commit_epoch(root))
if sde is not None:
    # SCons runs the compiler with env["ENV"] (PlatformIO seeds it from os.environ); set both.
    os.environ["SOURCE_DATE_EPOCH"] = sde
    env["ENV"]["SOURCE_DATE_EPOCH"] = sde
print("Brucia SOURCE_DATE_EPOCH:", env["ENV"].get("SOURCE_DATE_EPOCH", "unset (wall-clock __DATE__)"),
      "(exported from commit)" if sde is not None else "")

_PREFIX_MAP_FLAGS = fp.prefix_map_flags([
    (env.subst("$PROJECT_CORE_DIR"), "/pio/core"),
    (env.subst("$PROJECT_PACKAGES_DIR"), "/pio/packages"),
    (root, "."),
    (env.subst("$PROJECT_LIBDEPS_DIR"), "./.pio/libdeps"),
])
_accepted = {}


def _prefix_map(target, source, env, for_signature):
    # $CC is only defined by the platform builder that runs after pre-scripts: probe lazily, once.
    cc = env.subst("$CC")
    if cc not in _accepted:
        _accepted[cc] = fp.compiler_accepts(env.WhereIs(cc) or cc, _PREFIX_MAP_FLAGS, environ=dict(env["ENV"]))
        print("Brucia path prefix map:", "enabled" if _accepted[cc] else "unsupported by", cc)
    return _PREFIX_MAP_FLAGS if _accepted[cc] else []


env["BRUCIA_PREFIX_MAP"] = _prefix_map
env.Append(CCFLAGS=["$BRUCIA_PREFIX_MAP"], ASPPFLAGS=["$BRUCIA_PREFIX_MAP"], LINKFLAGS=["$BRUCIA_PREFIX_MAP"])
