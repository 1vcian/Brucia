#!/usr/bin/env python3
"""Check a built Brucia APK's identity with aapt2 from the Android SDK.

Expected values come from the repository, not from the APK:
- applicationId: APPLICATION_ID below, also required in android/app/build.gradle.kts;
- versionName/versionCode: repo-root VERSION, using the same 1,000,000-epoch
  formula as android/app/build.gradle.kts;
- minSdk/targetSdk: android/app/build.gradle.kts.
`--build-type` also checks the debuggable flag (debug APKs must be debuggable,
release APKs must not). Signing is not checked here: CI release APKs are unsigned
and signing stays local (release.sh).

Usage: tools/check_apk_identity.py --build-type release path/to/app-release-unsigned.apk
aapt2 is taken from --aapt2, $AAPT2, or the newest build-tools/*/aapt2 under
$ANDROID_HOME, $ANDROID_SDK_ROOT or android/local.properties sdk.dir.
"""
import argparse
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
APPLICATION_ID = "app.brucia"
VERSION_CODE_EPOCH = 1_000_000


def version_code(version):
    """Mirror build.gradle.kts: missing or non-numeric parts (Kotlin toIntOrNull) count as 0."""
    parts = [int(p) if re.fullmatch(r"[+-]?\d+", p) else 0 for p in version.strip().split(".")]
    parts += [0] * (3 - len(parts))
    return VERSION_CODE_EPOCH + parts[0] * 10000 + parts[1] * 100 + parts[2]


def gradle_int(text, name):
    match = re.search(rf"^\s*{name}\s*=\s*(\d+)\s*$", text, re.MULTILINE)
    if not match:
        raise ValueError(f"{name} not found as a literal in android/app/build.gradle.kts")
    return int(match.group(1))


def expected_identity(root=ROOT):
    version = (root / "VERSION").read_text().strip()
    gradle = (root / "android/app/build.gradle.kts").read_text()
    match = re.search(r'^\s*applicationId\s*=\s*"([^"]+)"\s*$', gradle, re.MULTILINE)
    if not match or match.group(1) != APPLICATION_ID:
        found = match.group(1) if match else "none"
        raise ValueError(f"build.gradle.kts applicationId is {found}, expected {APPLICATION_ID}")
    return {
        "package": APPLICATION_ID,
        "versionName": version,
        "versionCode": str(version_code(version)),
        "minSdk": str(gradle_int(gradle, "minSdk")),
        "targetSdk": str(gradle_int(gradle, "targetSdk")),
    }


def _attrs(line):
    return dict(re.findall(r"(\w+)='([^']*)'", line))


def parse_badging(output):
    """Extract identity fields from `aapt2 dump badging` output."""
    found = {"debuggable": False}
    for line in output.splitlines():
        key, _, rest = line.partition(":")
        if key == "package":
            attrs = _attrs(rest)
            found["package"] = attrs.get("name")
            found["versionCode"] = attrs.get("versionCode")
            found["versionName"] = attrs.get("versionName")
        elif key in ("sdkVersion", "minSdkVersion"):
            found["minSdk"] = rest.strip().strip("'")
        elif key == "targetSdkVersion":
            found["targetSdk"] = rest.strip().strip("'")
        elif line.strip() == "application-debuggable":
            found["debuggable"] = True
    return found


def compare(found, expected, build_type=None):
    errors = [f"{field}: APK has {found.get(field)!r}, expected {value!r}"
              for field, value in expected.items() if found.get(field) != value]
    if build_type == "debug" and not found.get("debuggable"):
        errors.append("debuggable: debug APK is not marked application-debuggable")
    if build_type == "release" and found.get("debuggable"):
        errors.append("debuggable: release APK is marked application-debuggable")
    return errors


def _sdk_dirs(root, environ):
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if environ.get(name):
            yield Path(environ[name])
    props = root / "android/local.properties"
    if props.exists():
        for line in props.read_text().splitlines():
            if line.startswith("sdk.dir="):
                yield Path(line.split("=", 1)[1].strip())


def _version_key(path):
    """build-tools folder ordering: numeric release first, final release after its -rcN."""
    release, _, suffix = path.parent.name.partition("-")
    return [int(p) if p.isdigit() else -1 for p in release.split(".")], not suffix


def find_aapt2(root=ROOT, environ=os.environ):
    if environ.get("AAPT2"):
        return Path(environ["AAPT2"])
    for sdk in _sdk_dirs(root, environ):
        candidates = sorted((sdk / "build-tools").glob("*/aapt2"), key=_version_key)
        if candidates:
            return candidates[-1]
    raise SystemExit("aapt2 not found: pass --aapt2, set AAPT2, or set ANDROID_HOME/ANDROID_SDK_ROOT")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("apk", nargs="+", type=Path)
    parser.add_argument("--build-type", choices=("debug", "release"), help="Also check the debuggable flag")
    parser.add_argument("--aapt2", type=Path, help="aapt2 binary (default: newest SDK build-tools)")
    args = parser.parse_args(argv)
    expected = expected_identity()
    aapt2 = args.aapt2 or find_aapt2()
    failed = False
    for apk in args.apk:
        if not apk.is_file():
            print(f"FAIL {apk}: file not found", file=sys.stderr)
            failed = True
            continue
        command = [str(aapt2), "dump", "badging", str(apk)]
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode != 0:
            print(f"FAIL {apk}: {shlex.join(command)} exited {result.returncode}\n{result.stderr}", file=sys.stderr)
            failed = True
            continue
        found = parse_badging(result.stdout)
        errors = compare(found, expected, args.build_type)
        summary = (f"{found.get('package')} {found.get('versionName')}/{found.get('versionCode')} "
                   f"minSdk {found.get('minSdk')} targetSdk {found.get('targetSdk')} "
                   f"debuggable {str(found['debuggable']).lower()}")
        if errors:
            failed = True
            print(f"FAIL {apk}: {summary}", file=sys.stderr)
            for error in errors:
                print(f"  {error}", file=sys.stderr)
        else:
            print(f"OK {apk}: {summary}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
