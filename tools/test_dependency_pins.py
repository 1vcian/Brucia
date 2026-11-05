"""Guard dependency resolution policy without installing packages.

Every library a firmware env resolves must be named exactly: a registry
library as `owner/name @ x.y.z`, a Git library at a full commit. A range
(`^`, `~`, `>=`), a bare name or a branch lets a fresh install pick a newer
release than an existing `.pio/libdeps`, so two checkouts of one commit build
different firmware (batch 13: core4mb differed by 608 B through M5Unified).
"""
import configparser
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]

# Environments built by .github/workflows/build.yml.
CI_ENVS = ('lilygo-t-embed-cc1101', 'm5stack-cardputer', 'm5stack-core4mb')

# Names that are not registry or Git specs, with the reason they need no version.
NOT_PINNABLE = {
    # Arduino-ESP32 built-in library (framework-arduinoespressif32/libraries/FFat):
    # its version is the framework's, fixed by `platform` and `platform_packages` in [env].
    'FFat': 'framework built-in',
}

# Registry libraries that another pinned library pulls in through a range in its own
# manifest. A range is satisfied by whatever version is already installed, so the
# dependency is pinned directly next to its parent (keep the two in step when bumping).
TRANSITIVE_PINS = {
    'm5stack/M5Unified': ('m5stack/M5GFX',),                    # library.json: M5GFX >=0.2.35
    'ESP32Async/ESPAsyncWebServer': ('ESP32Async/AsyncTCP',),   # library.json: AsyncTCP ^3.5.0
}

# Manifest dependencies that are installed but never compiled, so their release cannot change
# the firmware: Adafruit TCA8418 1.0.2 declares `depends=Adafruit SH110X, Adafruit BusIO` and
# SH110X declares `Adafruit GFX Library`, but no source includes them and they are absent from
# the LDF dependency graph (Cardputer, T-Lora Pager). Listing them in lib_deps would force them
# into the build: tried in batch 14, the Cardputer firmware grew by 32 B. build_firmware.py
# prints them as reached through a range; Adafruit BusIO is pinned (Git) in [env].
UNBUILT_DEPENDENCIES = {
    'adafruit/Adafruit TCA8418': ('Adafruit SH110X', 'Adafruit GFX Library'),
}

REGISTRY_EXACT = re.compile(r'^(?P<owner>[^/@=\s]+)/(?P<name>[^@=]+?)\s*@\s*\d+\.\d+\.\d+$')
GIT_COMMIT = re.compile(r'^(?:[^=]+=)?(?:git\+)?https://\S+#[0-9a-f]{40}$')
ARCHIVE_COMMIT = re.compile(r'^(?:[^=]+=)?https://github\.com/[^/\s]+/[^/\s]+/archive/[0-9a-f]{40}\.zip$')
REFERENCE = re.compile(r'\$\{([^.}]+)\.([^}]+)\}')


def load_config() -> configparser.ConfigParser:
    """platformio.ini plus its extra_configs (boards/*.ini, boards/*/*.ini), as PlatformIO merges them."""
    config = configparser.ConfigParser(interpolation=None, inline_comment_prefixes=(';',),
                                       strict=False)
    config.optionxform = str
    files = [ROOT / 'platformio.ini', *sorted(ROOT.glob('boards/*.ini')),
             *sorted(ROOT.glob('boards/*/*.ini'))]
    config.read(files, encoding='utf-8')
    return config


def raw_option(config, section: str, option: str, seen=()) -> str | None:
    """An option as PlatformIO looks it up: the section, its `extends` chain, then [env]."""
    if section in seen or not config.has_section(section):
        return None
    if config.has_option(section, option):
        return config.get(section, option)
    for parent in config.get(section, 'extends', fallback='').split(','):
        parent = parent.strip()
        if parent:
            value = raw_option(config, parent, option, (*seen, section))
            if value is not None:
                return value
    if section.startswith('env:'):
        return raw_option(config, 'env', option, (*seen, section))
    return None


def expand(config, value: str, depth=0) -> str:
    if depth > 10:
        raise AssertionError(f'Reference loop in {value!r}')
    return REFERENCE.sub(lambda match: expand(config, raw_option(config, match[1], match[2]) or '',
                                              depth + 1), value)


def lib_deps(config, section: str) -> list[str]:
    value = expand(config, raw_option(config, section, 'lib_deps') or '')
    entries = []
    for line in value.splitlines():
        line = line.split(' ;', 1)[0].strip()
        if line and not line.startswith((';', '#')):
            entries.extend(item.strip() for item in line.split(',') if item.strip())
    return entries


def is_exact(entry: str) -> bool:
    return bool(REGISTRY_EXACT.match(entry) or GIT_COMMIT.match(entry)
                or ARCHIVE_COMMIT.match(entry)) or entry in NOT_PINNABLE


def registry_names(entries: list[str]) -> set[str]:
    names = set()
    for entry in entries:
        match = REGISTRY_EXACT.match(entry)
        if match:
            names.add(f"{match['owner']}/{match['name'].strip()}".lower())
    return names


class DependencyPinsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config = load_config()
        cls.envs = [section for section in cls.config.sections() if section.startswith('env:')]

    def test_ci_envs_resolve_only_exact_dependencies(self):
        for env in CI_ENVS:
            with self.subTest(env=env):
                entries = lib_deps(self.config, 'env:' + env)
                self.assertGreater(len(entries), 30)
                self.assertEqual([], [entry for entry in entries if not is_exact(entry)])

    def test_every_env_resolves_only_exact_dependencies(self):
        self.assertGreater(len(self.envs), 50)
        for env in self.envs:
            with self.subTest(env=env):
                self.assertEqual([], [e for e in lib_deps(self.config, env) if not is_exact(e)])

    def test_transitive_registry_dependencies_are_pinned_next_to_their_parent(self):
        for env in self.envs:
            names = registry_names(lib_deps(self.config, env))
            for parent, children in TRANSITIVE_PINS.items():
                if parent.lower() in names:
                    for child in children:
                        with self.subTest(env=env, parent=parent, child=child):
                            self.assertIn(child.lower(), names)

    def test_unbuilt_dependencies_stay_out_of_lib_deps(self):
        for env in self.envs:
            names = registry_names(lib_deps(self.config, env))
            for parent, children in UNBUILT_DEPENDENCIES.items():
                for child in children:
                    with self.subTest(env=env, child=child):
                        self.assertFalse(any(name.endswith('/' + child.lower()) for name in names))

    def test_one_version_per_library_in_an_env(self):
        for env in self.envs:
            entries = [REGISTRY_EXACT.match(e) for e in lib_deps(self.config, env)]
            names = [f"{m['owner']}/{m['name'].strip()}".lower() for m in entries if m]
            with self.subTest(env=env):
                self.assertEqual(len(names), len(set(names)))

    def test_not_pinnable_entries_are_still_used(self):
        used = {e for env in CI_ENVS for e in lib_deps(self.config, 'env:' + env)}
        self.assertEqual(set(NOT_PINNABLE), set(NOT_PINNABLE) & used)

    def test_policy_rejects_ranges_bare_names_and_branches(self):
        for loose in ('m5stack/M5Unified @ ^0.2.11', 'lewisxhe/XPowersLib @~0.3.3',
                      'm5stack/M5Unified', 'NTPClient@3.2.1', 'owner/name@>=1.0.0',
                      'https://github.com/mmMicky/TouchLib.git',
                      'https://github.com/bmorcelli/OneWire#patch-1',
                      'https://github.com/a/b#90aa6b4',
                      'https://github.com/pschatzmann/arduino-audio-driver/archive/v0.1.3.zip'):
            with self.subTest(entry=loose):
                self.assertFalse(is_exact(loose))
        for exact in ('nrf24/RF24 @ 1.4.11', 'Bodmer/JPEGDecoder@2.0.0',
                      'mquickjs=https://github.com/BruceDevices/mquickjs#' + 'a' * 40):
            with self.subTest(entry=exact):
                self.assertTrue(is_exact(exact))

    def test_extends_and_references_resolve_like_platformio(self):
        core = lib_deps(self.config, 'env:m5stack-core4mb')
        self.assertIn('bblanchon/ArduinoJson@7.4.3', core)    # ${env.lib_deps} via m5stack-core_base
        self.assertTrue(any(e.startswith('m5stack/M5Unified') for e in core))

    def test_upstream_workflows_cache_libraries_only_for_identical_pins(self):
        # buil_parallel.yml / manual_build_sel_env.yml build with plain `platformio run`,
        # which links whatever .pio/libdeps holds: the cache may only come back for exactly
        # the same pins and library patches, never through a prefix restore-key.
        for name in ('buil_parallel.yml', 'manual_build_sel_env.yml'):
            text = (ROOT / '.github/workflows' / name).read_text()
            start = text.index('name: PIO libraries cache')
            block = text[start:text.index('\n      - ', start)]
            with self.subTest(workflow=name):
                self.assertIn('.pio/libdeps', block)
                self.assertNotIn('restore-keys', block)
                key = next(line for line in block.splitlines() if line.strip().startswith('key:'))
                for source in ("'platformio.ini'", "'boards/**/*.ini'", "'patch_library_conflicts.py'"):
                    self.assertIn(source, key)

    def test_library_ignore_uses_names_not_version_specs(self):
        for section in self.config.sections():
            for line in self.config.get(section, 'lib_ignore', fallback='').splitlines():
                self.assertNotIn('@', line, 'Version specs do not belong in lib_ignore')


if __name__ == '__main__':
    unittest.main()
