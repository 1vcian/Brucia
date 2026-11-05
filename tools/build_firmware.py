#!/usr/bin/env python3
"""Build firmware with a dependency bootstrap before PlatformIO's recursive resolver.

Some Arduino library.properties files use owner-qualified registry strings
as library names. PlatformIO then requests them again instead of matching the
installed library's canonical name (NimBLE-Arduino / ESP PN532 UART).
Install direct dependencies first, normalize those manifests, let the LDF install
owner-less registry libraries (so pre-build library patches see them on a first
build), then quarantine what the env's dependencies do not reach (or reach at another release
than an exact lib_deps pin), then build normally. After a successful build, report each env's
firmware.bin against its app partition (tools/check_flash_budget.py) and fail only
above the env's fail threshold.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

from check_flash_budget import check_environments


def normalize_manifests(libdeps: Path, environments: list[str]) -> int:
    changed = 0
    for environment in environments:
        for manifest in (libdeps / environment).glob('*/library.properties'):
            original = manifest.read_text()
            lines = original.splitlines(keepends=True)
            replacements = {
                'depends=h2zero/NimBLE-Arduino': 'depends=NimBLE-Arduino\n',
                'depends=whywilson/ESP-PN532-UART': 'depends=ESP PN532 UART\n',
            }
            updated = ''.join(replacements.get(line.strip(), line) for line in lines)
            if updated != original:
                manifest.write_text(updated)
                changed += 1
    return changed


QUARANTINE_DIR = 'libdeps-orphans'


def installed_packages(env_libdeps: Path) -> list[Path]:
    """Package directories in one env's libdeps (files such as integrity.dat are kept)."""
    if not env_libdeps.is_dir():
        return []
    return sorted(item for item in env_libdeps.iterdir()
                  if item.is_dir() and not item.name.startswith('.'))


EXACT_SPEC = re.compile(r'(?:^git\+\S+#[0-9a-f]{40}|@\s*\d+\.\d+\.\d+)$')
REGISTRY_PIN = re.compile(r'^(?:[^/@=\s]+/)?(?P<name>[^/@=]+?)\s*@\s*(?P<version>\d+\.\d+\.\d+)$')


def is_exact_spec(spec: str) -> bool:
    """A spec that names one release: `owner/name @ x.y.z`, a Git URL at a full commit, or a
    plain download URL. A range (`^`, `~`, `>=`), a bare name or a Git branch is not exact."""
    spec = spec.strip()
    if EXACT_SPEC.search(spec):
        return True
    return '://' in spec and not spec.startswith('git+')


def registry_pins(lib_deps: list[str]) -> dict[str, str]:
    """{library name (lower case): version} for each exact registry spec in lib_deps."""
    pins = {}
    for spec in lib_deps:
        match = REGISTRY_PIN.match(spec.split(' ;', 1)[0].strip())
        if match:
            pins[match['name'].strip().lower()] = match['version']
    return pins


def listed_packages(listing: str, packages: list[Path]) -> list[tuple[str, str, str, Path]]:
    """(name, version, required spec, package) for each listing line naming an installed package.

    The verbose form of `pio pkg list -e <env> --only-libraries` ends each line with the
    package path: "Name @ version (required: <spec>, <path>)". Lines are matched on that
    whole ending, never on a parsed path, because a spec can itself contain ", ".
    PlatformIO prints each package directory once, under the first spec (in its walk of
    lib_deps sorted by name, depth first) that resolves to it, so a package pinned in
    lib_deps can appear only under a parent's range or bare name.
    """
    endings = {}
    for package in packages:
        for form in {str(package), os.path.realpath(package)}:
            endings[f', {form})'] = package
    found = []
    for line in listing.splitlines():
        line = line.rstrip()
        for ending, package in endings.items():
            if not line.endswith(ending):
                continue
            head = line[:-len(ending)].lstrip(' \t\u2502\u251c\u2514\u2500|`-')
            name, _, rest = head.partition(' @ ')
            version, _, spec = rest.partition(' (required: ')
            found.append((name.strip(), version.strip(), spec, package))
    return found


def reachable_packages(listing: str, packages: list[Path], pins: dict[str, str] | None = None) -> set[Path]:
    """Installed packages that `pio pkg list -e <env> --only-libraries -v` resolves.

    PlatformIO prints a package when it matches a declared lib_deps spec or a
    dependency declared by such a package (library.json / library.properties /
    module.json), recursively. For a library that lib_deps pins exactly, only the
    installed package at the pinned release stays reachable (`pins`: registry name ->
    version; otherwise the package(s) printed for an exact spec). Another installed
    version that only a range in some package's manifest reaches (for example
    M5Unified's `M5GFX >=0.2.35` matching a newer M5GFX left over from earlier) is
    quarantined instead of sitting next to the pinned one where the LDF could pick it.
    """
    pins = pins or {}
    entries = listed_packages(listing, packages)
    by_version, by_spec = {}, {}
    for name, version, spec, package in entries:
        key = name.lower()
        if pins.get(key) == version:
            by_version.setdefault(key, set()).add(package)
        if is_exact_spec(spec):
            by_spec.setdefault(key, set()).add(package)
    found = set()
    for name, _, _, package in entries:
        # A pin that matches no listed package never removes anything (format or
        # resolution surprise): the package stays, as without pins.
        allowed = by_version.get(name.lower()) or by_spec.get(name.lower())
        if allowed is None or package in allowed:
            found.add(package)
    return found


def unpinned_libraries(listing: str, packages: list[Path], pins: dict[str, str] | None = None) -> list[str]:
    """Libraries reached only through a range or bare name: a fresh install may pick a
    different release than an existing libdeps, so the build is not reproducible."""
    pins = pins or {}
    entries = listed_packages(listing, packages)
    exact = {name.lower() for name, _, spec, _ in entries if is_exact_spec(spec)} | set(pins)
    return sorted({f'{name} (required: {spec})' for name, _, spec, _ in entries
                   if name.lower() not in exact})


def quarantine_orphans(project: Path, libdeps: Path, environment: str, pio: str,
                       lib_deps: list[str] | None = None) -> list[str]:
    """Move packages no declared dependency accounts for (or at another release than the
    exact pin in `lib_deps`) out of the LDF's search path."""
    env_libdeps = libdeps / environment
    packages = installed_packages(env_libdeps)
    if not packages:
        return []
    listing = subprocess.run([pio, 'pkg', 'list', '-e', environment, '--only-libraries', '-v'],
                             cwd=project, check=True, capture_output=True, text=True).stdout
    pins = registry_pins(lib_deps or [])
    for library in unpinned_libraries(listing, packages, pins):
        print(f'Warning: {environment} resolves {library} through a range; if the env compiles '
              'it (LDF "Dependency Graph"), pin it exactly in lib_deps (see CONTRIBUTING.md)',
              flush=True)
    expected = reachable_packages(listing, packages, pins)
    if not expected:
        # An empty match means the listing format changed or resolution failed; never
        # empty a whole libdeps directory on that basis.
        raise RuntimeError(f'pio pkg list matched no installed library for {environment}; '
                           'refusing to quarantine its libdeps')
    orphans = [package for package in packages if package not in expected]
    if not orphans:
        return []
    target_root = libdeps.parent / QUARANTINE_DIR / environment
    target_root.mkdir(parents=True, exist_ok=True)
    for package in orphans:
        target = target_root / package.name
        if target.exists():
            shutil.rmtree(target)
        package.rename(target)
        print(f'Quarantined undeclared library {environment}/{package.name} -> {target}', flush=True)
    return [package.name for package in orphans]


def build(project: Path, environments: list[str], pio: str) -> None:
    config_result = subprocess.run([pio, 'project', 'config', '--json-output'], cwd=project,
                                   check=True, capture_output=True, text=True)
    sections = dict(json.loads(config_result.stdout))
    for environment in environments:
        if 'env:' + environment not in sections:
            raise ValueError(f'Unknown PlatformIO environment: {environment}')
    options = dict(sections.get('platformio', []))
    libdeps = Path(options.get('libdeps_dir', project / '.pio/libdeps'))
    if not libdeps.is_absolute():
        libdeps = project / libdeps
    selection = [item for environment in environments for item in ('-e', environment)]
    subprocess.run([pio, 'pkg', 'install', '--skip-dependencies', *selection], cwd=project, check=True)
    changed = normalize_manifests(libdeps, environments)
    # `pio pkg install` skips owner-less registry specs as possible built-ins, and with
    # --skip-dependencies it installs nothing that a library's own manifest declares; the LDF
    # would install those only during `pio run`, after the pre: scripts, so
    # patch_library_conflicts.py would miss them on a fresh first build.
    subprocess.run([pio, 'project', 'metadata', *selection, '--json-output'], cwd=project,
                   check=True, stdout=subprocess.DEVNULL)
    changed += normalize_manifests(libdeps, environments)
    if changed:
        print(f'Normalized {changed} Arduino dependency manifests', flush=True)
    # After every declared dependency is installed and normalized (so the dependency
    # graph is complete) and before `pio run`, whose pre: script patch_library_conflicts.py
    # then patches exactly the libraries that remain.
    for environment in environments:
        lib_deps = dict(sections['env:' + environment]).get('lib_deps', [])
        if isinstance(lib_deps, str):
            lib_deps = lib_deps.splitlines()
        quarantine_orphans(project, libdeps, environment, pio, lib_deps)
    subprocess.run([pio, 'run', *selection], cwd=project, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('-e', '--environment', action='append', required=True)
    parser.add_argument('--pio', default=os.environ.get('PIO_BIN', 'pio'))
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    try:
        build(project, args.environment, args.pio)
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from error
    except RuntimeError as error:
        raise SystemExit(str(error)) from error
    over_budget = check_environments(project, args.environment)
    if over_budget:
        raise SystemExit(f'App partition budget exceeded: {", ".join(over_budget)}')


if __name__ == '__main__':
    main()
