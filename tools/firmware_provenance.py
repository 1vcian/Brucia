"""Build-time firmware provenance for the companion CAPS `build` object.

Used by `pre_build_provenance.py` (PlatformIO pre-script) to generate
`include/brucia_build_info.h`; kept free of SCons so host tests can exercise it.

Reproducibility: no value comes from the wall clock. The timestamp is
SOURCE_DATE_EPOCH when set, otherwise the HEAD commit time, otherwise "unknown".
Identical sources at the same commit therefore produce an identical header.
The dirty flag reflects `git status` at build time; a dirty build's timestamp is
still the commit time, not the time of the uncommitted edits.

A source tree without its own Git metadata (release tarball, Git missing, or a
tree nested in an unrelated repository) reports commit "unknown" and no dirty
flag; the build never fails because provenance is unavailable.

`build_year` serves Bruce's `pre_build_current_year.py` (CURRENT_YEAR): the same
SOURCE_DATE_EPOCH / commit-time order; only when both are unavailable does it fall
back to the wall-clock year, which is the one non-reproducible case.

Compiler reproducibility (applied by `pre_build_provenance.py`):
- `source_date_epoch_export`: when SOURCE_DATE_EPOCH is unset and the checkout has a
  HEAD commit, the commit time is exported so GCC's own SOURCE_DATE_EPOCH support pins
  `__DATE__`/`__TIME__` (RadioLib's RADIOLIB_INFO, Arduino core chip-debug-report and
  firmware_msc_fat). Without Git nothing is exported: those stay wall-clock, as before.
  `__TIMESTAMP__` is the source file mtime and is not affected (no compiled user found).
- `prefix_map_flags`: `-ffile-prefix-map` (implies -fmacro/-fdebug/-fprofile-prefix-map)
  so `__FILE__`, assert strings and debug info do not embed the checkout or the
  PlatformIO core/packages/libdeps location. Applied only if the compiler accepts it.
"""
import datetime
import os
import re
import subprocess

UNKNOWN = "unknown"
MAX_FIELD = 64
_UNSAFE = re.compile(r"[^A-Za-z0-9._+:\-]")
_SHA = re.compile(r"[0-9a-f]{7,40}")
HEADER_NAME = "brucia_build_info.h"


def sanitize(value, fallback=UNKNOWN):
    """Bounded, printable, C-string-safe token (no quotes/backslashes)."""
    text = _UNSAFE.sub("-", str(value or "").strip())[:MAX_FIELD]
    return text or fallback


def read_version(root):
    try:
        with open(os.path.join(root, "VERSION")) as f:
            return f.read().strip() or "dev"
    except OSError:
        return "dev"


def _git(root, args, runner):
    result = runner(["git", "-C", root, "--no-optional-locks", *args],
                    capture_output=True, text=True, timeout=15, check=False)
    if result.returncode != 0:
        return None
    return (result.stdout or "").strip()


def _own_checkout(root, runner):
    top = _git(root, ["rev-parse", "--show-toplevel"], runner)
    # A tarball unpacked inside another checkout must not borrow that repo's identity.
    return bool(top) and os.path.realpath(top) == os.path.realpath(root)


def _head_epoch(root, runner):
    stamp = _git(root, ["log", "-1", "--format=%ct", "HEAD"], runner)
    return int(stamp) if stamp and stamp.isdigit() else None


def git_info(root, runner=subprocess.run):
    """Return (commit, dirty, commit_epoch). dirty/commit_epoch are None when unknown."""
    try:
        if not _own_checkout(root, runner):
            return UNKNOWN, None, None
        sha = _git(root, ["rev-parse", "--short=12", "HEAD"], runner)
        if not sha or not _SHA.fullmatch(sha):
            return UNKNOWN, None, None
        status = _git(root, ["status", "--porcelain"], runner)
        dirty = None if status is None else bool(status)
        return sha, dirty, _head_epoch(root, runner)
    except (OSError, subprocess.SubprocessError, ValueError):
        return UNKNOWN, None, None


def head_commit_epoch(root, runner=subprocess.run):
    """HEAD commit time of the tree's own checkout, or None (no `git status` scan)."""
    try:
        return _head_epoch(root, runner) if _own_checkout(root, runner) else None
    except (OSError, subprocess.SubprocessError, ValueError):
        return None


def _iso(epoch):
    try:
        return datetime.datetime.fromtimestamp(epoch, tz=datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    except (OverflowError, OSError, ValueError):
        return None


def build_time(environ, commit_epoch):
    """(iso8601 UTC, source) from SOURCE_DATE_EPOCH, else commit time; never wall clock."""
    sde = _epoch_env(environ)
    if sde is not None:
        iso = _iso(sde)
        if iso:
            return iso, "source_date_epoch"
    if commit_epoch is not None:
        iso = _iso(commit_epoch)
        if iso:
            return iso, "commit"
    return UNKNOWN, UNKNOWN


# Firmware uses CURRENT_YEAR as the default clock year and as the lower bound of the GPS-date
# sanity window (year .. year+4). A pre-2025 SOURCE_DATE_EPOCH (e.g. 0 or 315532800) would make
# real GPS dates fail that check, so derived years are clamped to this floor.
MIN_YEAR = 2025


def build_year(environ, commit_epoch, now=None):
    """(year, source): SOURCE_DATE_EPOCH, else commit time, else wall clock (non-reproducible)."""
    for epoch, source in ((_epoch_env(environ), "source_date_epoch"), (commit_epoch, "commit")):
        if epoch is None:
            continue
        try:
            year = datetime.datetime.fromtimestamp(epoch, tz=datetime.timezone.utc).year
        except (OverflowError, OSError, ValueError):
            continue
        return max(year, MIN_YEAR), source
    now = now or datetime.datetime.now(tz=datetime.timezone.utc)
    return max(now.year, MIN_YEAR), "wall_clock"


def _epoch_env(environ):
    sde = (environ.get("SOURCE_DATE_EPOCH") or "").strip()
    return int(sde) if sde.isdigit() else None


def source_date_epoch_export(environ, commit_epoch):
    """SOURCE_DATE_EPOCH value to export to the compiler, or None to leave the environment alone.

    A valid SOURCE_DATE_EPOCH is never replaced. A blank or malformed one is ignored by GCC
    (wall-clock dates) and by `build_time` (commit time), so it is replaced by the commit
    time to keep `__DATE__` consistent with the CAPS build time. Without a commit time (no
    own Git metadata) nothing is exported: the compiler keeps wall-clock dates, as before.
    """
    if _epoch_env(environ) is not None:
        return None
    if commit_epoch is None or int(commit_epoch) < 0:
        return None
    return str(int(commit_epoch))


def prefix_map_flags(mappings):
    """`-ffile-prefix-map=OLD=NEW` flags for (OLD, NEW) directory pairs.

    Empty or duplicate OLD values are dropped; both the given and the symlink-resolved
    spelling are mapped. GCC applies the last matching option, so flags are ordered by
    increasing OLD length: a nested directory (packages inside the core dir, libdeps
    inside the project) wins over its parent. OLD has no trailing separator so the
    directory itself (DW_AT_comp_dir) is matched too.
    """
    pairs = {}
    for old, new in mappings:
        if not old:
            continue
        for spelling in (os.path.abspath(old), os.path.realpath(old)):
            spelling = spelling.rstrip(os.sep) or os.sep
            if "=" in spelling:  # would split ambiguously in OLD=NEW
                continue
            pairs.setdefault(spelling, new)
    return [f"-ffile-prefix-map={old}={new}" for old, new in sorted(pairs.items(), key=lambda kv: (len(kv[0]), kv[0]))]


def compiler_accepts(cc, flags, runner=subprocess.run, environ=None):
    """True when `cc` preprocesses an empty C unit with `flags` (option support probe)."""
    if not cc:
        return False
    try:
        result = runner([cc, *flags, "-E", "-x", "c", os.devnull], capture_output=True, text=True,
                        timeout=30, check=False, env=environ)
    except (OSError, subprocess.SubprocessError, ValueError):
        return False
    return result.returncode == 0


def render_year_header(year):
    return f"#pragma once\n#define CURRENT_YEAR {int(year)}\n"


def collect(root, version, env_name, environ=None, runner=subprocess.run):
    environ = os.environ if environ is None else environ
    commit, dirty, epoch = git_info(root, runner)
    when, when_src = build_time(environ, epoch)
    env_name = sanitize(env_name)
    version = sanitize(version, "dev")
    build_id = sanitize(environ.get("BRUCIA_BUILD_ID"), "")
    if not build_id:
        build_id = sanitize(f"{version}+{env_name}.{commit}" + (".dirty" if dirty else ""))
    return {"commit": commit, "dirty": dirty, "time": when, "time_src": when_src,
            "env": env_name, "id": build_id}


def render_header(info):
    dirty = -1 if info["dirty"] is None else int(bool(info["dirty"]))
    lines = [
        "#pragma once",
        "// Generated by pre_build_provenance.py (tools/firmware_provenance.py). Do not edit.",
        f'#define BRUCIA_BUILD_COMMIT "{sanitize(info["commit"])}"',
        f"#define BRUCIA_BUILD_DIRTY {dirty} // -1 unknown, 0 clean, 1 dirty",
        f'#define BRUCIA_BUILD_ID "{sanitize(info["id"])}"',
        f'#define BRUCIA_BUILD_TIME "{sanitize(info["time"])}"',
        f'#define BRUCIA_BUILD_TIME_SOURCE "{sanitize(info["time_src"])}"',
        f'#define BRUCIA_BUILD_ENV "{sanitize(info["env"])}"',
    ]
    return "\n".join(lines) + "\n"


def write_if_changed(path, content):
    """Rewrite only on change so unchanged provenance does not force a recompile."""
    try:
        with open(path) as f:
            if f.read() == content:
                return False
    except OSError:
        pass
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(content)
    return True
