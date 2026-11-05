#!/usr/bin/env python3
"""Build firmware with a dependency bootstrap before PlatformIO's recursive resolver.

Some Arduino library.properties files use owner-qualified registry strings
as library names. PlatformIO then requests them again instead of matching the
installed library's canonical name (NimBLE-Arduino / ESP PN532 UART).
Install direct dependencies first, normalize those manifests, let the LDF install
owner-less registry libraries (so pre-build library patches see them on a first
build), then build normally. After a successful build, report each env's
firmware.bin against its app partition (tools/check_flash_budget.py) and fail only
above the env's fail threshold.
"""
import argparse
import json
import os
from pathlib import Path
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


def reachable_packages(listing: str, packages: list[Path]) -> set[Path]:
    """Installed packages that `pio pkg list -e <env> --only-libraries -v` prints.

    PlatformIO prints a package when it matches a declared lib_deps spec or a
    dependency declared by such a package (library.json / library.properties /
    module.json), recursively, and the verbose form ends each line with the
    package path: "Name @ version (required: <spec>, <path>)".
    """
    endings = {}
    for package in packages:
        for form in {str(package), os.path.realpath(package)}:
            endings[f', {form})'] = package
    found = set()
    for line in listing.splitlines():
        line = line.rstrip()
        for ending, package in endings.items():
            if line.endswith(ending):
                found.add(package)
    return found


def quarantine_orphans(project: Path, libdeps: Path, environment: str, pio: str) -> list[str]:
    """Move packages no declared dependency accounts for out of the LDF's search path."""
    env_libdeps = libdeps / environment
    packages = installed_packages(env_libdeps)
    if not packages:
        return []
    listing = subprocess.run([pio, 'pkg', 'list', '-e', environment, '--only-libraries', '-v'],
                             cwd=project, check=True, capture_output=True, text=True).stdout
    expected = reachable_packages(listing, packages)
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
    # `pio pkg install` skips owner-less registry specs (for example ESP8266Audio@2.4.1) as
    # possible built-ins; the LDF would install them only during `pio run`, after the pre:
    # scripts, so patch_library_conflicts.py would miss them on a fresh first build.
    subprocess.run([pio, 'project', 'metadata', *selection, '--json-output'], cwd=project,
                   check=True, stdout=subprocess.DEVNULL)
    changed += normalize_manifests(libdeps, environments)
    if changed:
        print(f'Normalized {changed} Arduino dependency manifests', flush=True)
    # After every declared dependency is installed and normalized (so the dependency
    # graph is complete) and before `pio run`, whose pre: script patch_library_conflicts.py
    # then patches exactly the libraries that remain.
    for environment in environments:
        quarantine_orphans(project, libdeps, environment, pio)
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
