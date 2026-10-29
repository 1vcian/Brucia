#!/usr/bin/env python3
"""Build firmware with a dependency bootstrap before PlatformIO's recursive resolver.

Some Arduino library.properties files use owner-qualified registry strings
as library names. PlatformIO then requests them again instead of matching the
installed library's canonical name (NimBLE-Arduino / ESP PN532 UART).
Install direct dependencies first, normalize those manifests, then build normally.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess


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
    if changed:
        print(f'Normalized {changed} Arduino dependency manifests', flush=True)
    subprocess.run([pio, 'run', *selection], cwd=project, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('-e', '--environment', action='append', required=True)
    parser.add_argument('--pio', default=os.environ.get('PIO_BIN', 'pio'))
    args = parser.parse_args()
    try:
        build(Path(__file__).resolve().parents[1], args.environment, args.pio)
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from error


if __name__ == '__main__':
    main()
