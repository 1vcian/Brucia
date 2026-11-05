#!/usr/bin/env python3
"""Check a built firmware.bin against its app partition, not the whole chip.

PlatformIO's "Flash: x%" line divides by upload.maximum_size, which for
factory-only partition tables stays at the board's full flash size. The real
limit is the app slot in the env's partition table. This tool resolves that
table the way PlatformIO + the Arduino builder do (board_build.partitions
through `extends` chains and the implicit [env] section, then the board JSON
default), takes the smallest factory/ota_N slot and compares it with the size
of .pio/build/<env>/firmware.bin.

Exit status: 0 ok or warn, 1 above the fail threshold, 2 budget unavailable.
"""
import argparse
import configparser
from dataclasses import dataclass
import fnmatch
import glob
import json
import os
from pathlib import Path
import re
import sys

DEFAULT_WARN = 92.0
DEFAULT_FAIL = 98.0

# Per-env (fnmatch pattern) overrides: pattern -> (warn %, fail %). First match wins.
# Keep this table short and justify every entry; flags --warn/--fail override it.
ENV_THRESHOLDS = {
    # LAUNCHER_* share custom_4Mb.csv (2,496 KiB app slot so M5Launcher can coexist).
    # Batch 7 LITE compile-outs: LAUNCHER_CYD-2432S028 98.17% -> 97.38% (67,056 B free), so
    # the fail ceiling drops from 99% to the default 98% (~15.8 KB of growth left on CYD).
    # Kept as an override, warn 95%, because CYD is still above the 92% default warn.
    # Batch 12 measured all 17 LAUNCHER_* envs (table in the maintenance backlog): 13 at
    # 96.69-97.74%, the 4 elecrow envs at 98.54-98.66% (FAIL). Not tightened: the fail line
    # would fail CYD-3248S035C (97.74%) too, and every env is above 95%.
    # Elecrow (must precede LAUNCHER_*, first match wins): the 4 elecrow LAUNCHER envs measured
    # 98.54-98.66% in batch 12 (largest 2,521,536 B, 34,368 B free); the owner chose to accept
    # that at a 99% fail line instead of investigating. Not investigated; see the backlog item.
    'LAUNCHER_elecrow-*': (95.0, 99.0),
    'LAUNCHER_*': (95.0, 98.0),
}

APP_TYPE = 0x00
DATA_TYPE = 0x01
TYPE_NAMES = {'app': APP_TYPE, 'data': DATA_TYPE}
APP_SUBTYPES = {'factory': 0x00, 'test': 0x20}
APP_SUBTYPES.update({f'ota_{n}': 0x10 + n for n in range(16)})
PARTITION_TABLE_OFFSET = 0x8000
PARTITION_TABLE_SIZE = 0x1000
ALIGNMENT = {APP_TYPE: 0x10000}
DATA_ALIGNMENT = 0x1000


@dataclass(frozen=True)
class Partition:
    name: str
    type: int
    subtype: object  # int for app partitions, raw text otherwise
    offset: int
    size: int

    @property
    def is_app_slot(self) -> bool:
        return self.type == APP_TYPE and isinstance(self.subtype, int) and (
            self.subtype == 0x00 or 0x10 <= self.subtype <= 0x1F)


@dataclass(frozen=True)
class Budget:
    env: str
    table: str
    slot: str
    used: int
    size: int
    warn: float
    fail: float

    @property
    def free(self) -> int:
        return self.size - self.used

    @property
    def percent(self) -> float:
        return 100.0 * self.used / self.size

    @property
    def status(self) -> str:
        return classify(self.used, self.size, self.warn, self.fail)

    def report(self) -> str:
        return (f'[flash-budget] {self.env}: {self.status.upper()} {self.used:,} / {self.size:,} B '
                f'({self.percent:.2f}%), free {self.free:,} B; app slot {self.slot!r} in '
                f'{self.table} [warn > {self.warn:g}%, fail > {self.fail:g}%]')


def parse_int(text: str) -> int:
    """gen_esp32part rules: int(x, 0) with k/K (1024) and m/M (1024*1024) suffixes."""
    value = text.strip()
    if not value:
        raise ValueError('empty integer field')
    multiplier = 1
    if value[-1] in 'kK':
        value, multiplier = value[:-1], 1024
    elif value[-1] in 'mM':
        value, multiplier = value[:-1], 1024 * 1024
    return int(value, 0) * multiplier


def _parse_type(text: str) -> int:
    key = text.strip().lower()
    return TYPE_NAMES[key] if key in TYPE_NAMES else parse_int(key)


def _parse_subtype(ptype: int, text: str):
    key = text.strip().lower()
    if ptype == APP_TYPE:
        return APP_SUBTYPES[key] if key in APP_SUBTYPES else parse_int(key)
    return key


def parse_partition_csv(text: str, table_offset: int = PARTITION_TABLE_OFFSET) -> list:
    """Parse an ESP-IDF partition CSV, filling empty offsets as gen_esp32part does."""
    partitions = []
    last_end = table_offset + PARTITION_TABLE_SIZE
    for line_no, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        fields = [field.strip() for field in line.split(',')]
        if len(fields) < 5:
            raise ValueError(f'partition CSV line {line_no}: expected at least 5 fields')
        ptype = _parse_type(fields[1])
        if fields[3]:
            offset = parse_int(fields[3])
        else:
            align = ALIGNMENT.get(ptype, DATA_ALIGNMENT)
            offset = (last_end + align - 1) & ~(align - 1)
        size = parse_int(fields[4])
        if offset < last_end:
            raise ValueError(f'partition CSV line {line_no}: {fields[0]} overlaps 0x{last_end:x}')
        partitions.append(Partition(fields[0], ptype, _parse_subtype(ptype, fields[2]), offset, size))
        last_end = offset + size
    return partitions


def app_limit(partitions: list, app_partition_name: str = '') -> Partition:
    """The slot the image must fit: a named partition, else the smallest factory/ota_N slot."""
    if app_partition_name:
        for partition in partitions:
            if partition.name == app_partition_name:
                return partition
    slots = [partition for partition in partitions if partition.is_app_slot]
    if not slots:
        raise ValueError('partition table has no factory/ota_N app partition')
    return min(slots, key=lambda partition: partition.size)


def classify(used: int, size: int, warn: float, fail: float) -> str:
    percent = 100.0 * used / size
    if percent > fail:
        return 'fail'
    if percent > warn:
        return 'warn'
    return 'ok'


def thresholds_for(env: str, table: dict = None, warn: float = None, fail: float = None) -> tuple:
    table = ENV_THRESHOLDS if table is None else table
    base = next((value for pattern, value in table.items() if fnmatch.fnmatchcase(env, pattern)),
                (DEFAULT_WARN, DEFAULT_FAIL))
    result = (base[0] if warn is None else warn, base[1] if fail is None else fail)
    if result[0] > result[1]:
        raise ValueError(f'warn threshold {result[0]}% exceeds fail threshold {result[1]}%')
    return result


class ProjectConfig:
    """Enough of PlatformIO's ProjectConfig to resolve one option for one env."""

    VAR_RE = re.compile(r'\$\{(?:([^.}()]+)\.)?([^}]+)\}')

    def __init__(self, project_dir: Path):
        self.project_dir = Path(project_dir)
        self.parser = configparser.ConfigParser(
            inline_comment_prefixes=('#', ';'), interpolation=None, strict=False)
        self._read(self.project_dir / 'platformio.ini')

    def _read(self, path: Path) -> None:
        self.parser.read(path, encoding='utf-8')
        patterns = self.parser.get('platformio', 'extra_configs', fallback='')
        for pattern in self._multi(patterns):
            for item in glob.glob(str(self.project_dir / os.path.expanduser(pattern)), recursive=True):
                self.parser.read(item, encoding='utf-8')

    @staticmethod
    def _multi(value: str) -> list:
        return [item.strip() for line in value.splitlines() for item in line.split(',')
                if item.strip()]

    def walk(self, section: str):
        """Sections in PlatformIO lookup order: the env, its extends (stack order), then [env]."""
        queue = ['env', section] if section.startswith('env:') else [section]
        while queue:
            current = queue.pop()
            if not self.parser.has_section(current):
                continue
            yield current
            if self.parser.has_option(current, 'extends'):
                queue.extend(self._multi(self.parser.get(current, 'extends')))

    def get(self, section: str, option: str, default=None, _depth: int = 0):
        if _depth > 32:
            raise ValueError(f'interpolation recursion resolving {section}.{option}')
        for current in self.walk(section):
            if self.parser.has_option(current, option):
                value = self.parser.get(current, option)
                return self.VAR_RE.sub(lambda m: self._expand(section, m, _depth), value)
        return default

    def _expand(self, section: str, match, depth: int) -> str:
        scope, option = match.group(1), match.group(2)
        if scope is None:
            return str(self.project_dir) if option == 'PROJECT_DIR' else match.group(0)
        if scope == 'sysenv':
            return os.environ.get(option, '')
        if scope == 'this':
            scope = section
        return self.get(scope, option, '', depth + 1) or ''

    def has_env(self, env: str) -> bool:
        return self.parser.has_section('env:' + env)


def core_dir(config: ProjectConfig) -> Path:
    configured = config.get('platformio', 'core_dir') or os.environ.get('PLATFORMIO_CORE_DIR')
    return Path(os.path.expanduser(configured or '~/.platformio'))


def load_board(config: ProjectConfig, board: str) -> dict:
    boards_dir = config.get('platformio', 'boards_dir')
    candidates = []
    if boards_dir:
        candidates.append(config.project_dir / boards_dir / f'{board}.json')
    candidates.append(core_dir(config) / 'platforms' / 'espressif32' / 'boards' / f'{board}.json')
    for candidate in candidates:
        if candidate.is_file():
            return json.loads(candidate.read_text(encoding='utf-8'))
    return {}


def _board_value(board: dict, dotted: str):
    node = board
    for key in dotted.split('.'):
        if not isinstance(node, dict) or key not in node:
            return None
        node = node[key]
    return node


def resolve_partition_csv(config: ProjectConfig, env: str, framework_dir: Path = None) -> Path:
    """Locate the env's partition CSV following pioarduino-build.get_partition_table_csv()."""
    section = 'env:' + env
    if not config.has_env(env):
        raise ValueError(f'unknown PlatformIO environment: {env}')
    board_name = config.get(section, 'board')
    board = load_board(config, board_name) if board_name else {}
    name = config.get(section, 'board_build.partitions') or _board_value(
        board, 'build.partitions') or _board_value(board, 'build.arduino.partitions') or ''
    variant = config.get(section, 'board_build.variant') or _board_value(board, 'build.variant') or ''
    variants_dir = config.project_dir / (config.get(section, 'board_build.variants_dir') or 'variants')
    if framework_dir is None:
        framework_dir = core_dir(config) / 'packages' / 'framework-arduinoespressif32'
    framework_partitions = Path(framework_dir) / 'tools' / 'partitions'
    if not name:
        candidates = [variants_dir / variant / 'partitions.csv', framework_partitions / 'default.csv']
    else:
        candidates = [variants_dir / variant / name, framework_partitions / name,
                      config.project_dir / name]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise FileNotFoundError(f'{env}: partition table {name or "default.csv"} not found '
                            f'(looked in {", ".join(str(c) for c in candidates)})')


def build_dir(config: ProjectConfig, env: str) -> Path:
    configured = config.get('platformio', 'build_dir') or os.environ.get('PLATFORMIO_BUILD_DIR')
    base = Path(os.path.expanduser(configured)) if configured else Path('.pio/build')
    if not base.is_absolute():
        base = config.project_dir / base
    return base / env


def check_env(project_dir: Path, env: str, firmware: Path = None, warn: float = None,
              fail: float = None, framework_dir: Path = None) -> Budget:
    config = ProjectConfig(project_dir)
    table = resolve_partition_csv(config, env, framework_dir)
    partitions = parse_partition_csv(table.read_text(encoding='utf-8'))
    slot = app_limit(partitions, config.get('env:' + env, 'board_build.app_partition_name') or '')
    firmware = firmware or build_dir(config, env) / 'firmware.bin'
    if not firmware.is_file():
        raise FileNotFoundError(f'{env}: {firmware} not found (build the env first)')
    warn, fail = thresholds_for(env, warn=warn, fail=fail)
    try:
        table_label = str(table.relative_to(config.project_dir))
    except ValueError:
        table_label = str(table)
    return Budget(env, table_label, slot.name, firmware.stat().st_size, slot.size, warn, fail)


def check_environments(project_dir: Path, environments: list, out=None) -> list:
    """Report every env; return the envs above their fail threshold.

    A budget that cannot be computed is reported but does not fail the caller's build.
    """
    out = out or sys.stdout
    failed = []
    for env in environments:
        try:
            budget = check_env(project_dir, env)
        except (OSError, ValueError, KeyError) as error:
            print(f'[flash-budget] {env}: UNAVAILABLE ({error})', file=out, flush=True)
            continue
        print(budget.report(), file=out, flush=True)
        if budget.status == 'fail':
            failed.append(env)
    return failed


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('-e', '--environment', action='append', required=True)
    parser.add_argument('--project-dir', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--firmware', type=Path, help='firmware.bin to check (single env only)')
    parser.add_argument('--warn', type=float, help=f'warn above this %% (default {DEFAULT_WARN:g})')
    parser.add_argument('--fail', type=float, help=f'fail above this %% (default {DEFAULT_FAIL:g})')
    parser.add_argument('--framework-dir', type=Path, help='framework-arduinoespressif32 package')
    args = parser.parse_args(argv)
    if args.firmware and len(args.environment) != 1:
        parser.error('--firmware requires exactly one environment')
    status = 0
    for env in args.environment:
        try:
            budget = check_env(args.project_dir, env, args.firmware, args.warn, args.fail,
                               args.framework_dir)
        except (OSError, ValueError, KeyError) as error:
            print(f'[flash-budget] {env}: UNAVAILABLE ({error})', file=sys.stderr)
            status = max(status, 2)
            continue
        print(budget.report())
        if budget.status == 'fail':
            status = max(status, 1)
    return status


if __name__ == '__main__':
    sys.exit(main())
