from contextlib import redirect_stderr, redirect_stdout
import io
from pathlib import Path
import tempfile
import textwrap
import unittest

from check_flash_budget import (APP_TYPE, DATA_TYPE, ProjectConfig, app_limit, check_env,
                                check_environments, classify, main, parse_int,
                                parse_partition_csv, resolve_partition_csv, thresholds_for)

REPO = Path(__file__).resolve().parents[1]

OTA_CSV = '''# Name, Type, SubType, Offset, Size, Flags
nvs,      data, nvs,     ,        20K,
otadata,  data, ota,     ,        8K,
app0,     app,  ota_0,   ,        0x1E0000,
app1,     app,  ota_1,   ,        1792K,
spiffs,   data, spiffs,  ,        1M,
'''

FIXTURE_INI = '''
[platformio]
boards_dir = boards_json
extra_configs = boards/*/*.ini

[env]
board_build.variants_dir = boards
board_build.partitions = root.csv

[base_a]
board = demo
board_build.partitions = base_a.csv

[base_b]
board_build.partitions = base_b.csv ; inline comment

[env:plain]
board = demo

[env:child]
extends = base_a

[env:grandchild]
extends = env:child

[env:override]
extends = env:grandchild
board_build.partitions = own.csv

[env:multi]
extends = base_a, base_b

[env:interp]
board = demo
board_build.partitions = ${base_b.board_build.partitions}
'''


def write(root: Path, rel: str, text: str) -> Path:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(text))
    return path


class ParseTest(unittest.TestCase):
    def test_integers_accept_hex_decimal_and_k_m_suffixes(self):
        self.assertEqual(0x270000, parse_int('0x270000'))
        self.assertEqual(4096, parse_int(' 4096 '))
        self.assertEqual(20 * 1024, parse_int('20K'))
        self.assertEqual(20 * 1024, parse_int('20k'))
        self.assertEqual(3 * 1024 * 1024, parse_int('3M'))
        self.assertEqual(0x10 * 1024, parse_int('0x10K'))
        with self.assertRaises(ValueError):
            parse_int('')

    def test_repo_tables_parse_with_explicit_offsets(self):
        expected = {'custom_4Mb.csv': 2555904, 'custom_4Mb_full.csv': 3932160,
                    'custom_8Mb.csv': 5111808, 'custom_16Mb.csv': 4653056,
                    'boards/ES3C28P/partitions_16Mb.csv': 4653056}
        for name, size in expected.items():
            partitions = parse_partition_csv((REPO / name).read_text())
            slot = app_limit(partitions)
            self.assertEqual((size, 0x10000), (slot.size, slot.offset), name)
        nvs = parse_partition_csv((REPO / 'custom_16Mb.csv').read_text())[0]
        self.assertEqual(('nvs', DATA_TYPE, 'nvs', 0x9000, 0x6000),
                         (nvs.name, nvs.type, nvs.subtype, nvs.offset, nvs.size))

    def test_empty_offsets_follow_gen_esp32part_alignment(self):
        parts = {p.name: p for p in parse_partition_csv(OTA_CSV)}
        self.assertEqual(0x9000, parts['nvs'].offset)
        self.assertEqual(0xE000, parts['otadata'].offset)
        self.assertEqual(0x10000, parts['app0'].offset)
        self.assertEqual(0x1F0000, parts['app1'].offset)
        self.assertEqual(0x3B0000, parts['spiffs'].offset)
        self.assertEqual(APP_TYPE, parts['app1'].type)
        self.assertEqual(0x11, parts['app1'].subtype)

    def test_overlap_and_short_lines_are_rejected(self):
        with self.assertRaises(ValueError):
            parse_partition_csv('a, data, nvs, 0x9000, 0x6000\nb, data, nvs, 0xA000, 0x1000\n')
        with self.assertRaises(ValueError):
            parse_partition_csv('a, data, nvs, 0x9000\n')


class SlotTest(unittest.TestCase):
    def test_ota_layout_uses_the_smallest_app_slot(self):
        slot = app_limit(parse_partition_csv(OTA_CSV))
        self.assertEqual(('app1', 1792 * 1024), (slot.name, slot.size))

    def test_factory_plus_ota_and_numeric_types(self):
        csv = ('nvs, 1, 0x02, 0x9000, 0x5000\n'
               'factory, 0, 0x00, 0x10000, 0x100000\n'
               'ota_0, app, ota_0, 0x110000, 0x200000\n'
               'test, app, test, 0x310000, 0x10000\n')
        self.assertEqual('factory', app_limit(parse_partition_csv(csv)).name)

    def test_named_app_partition_wins_and_missing_app_is_an_error(self):
        partitions = parse_partition_csv(OTA_CSV)
        self.assertEqual('app0', app_limit(partitions, 'app0').name)
        self.assertEqual('app1', app_limit(partitions, 'missing').name)
        with self.assertRaises(ValueError):
            app_limit(parse_partition_csv('nvs, data, nvs, 0x9000, 0x6000\n'))


class ThresholdTest(unittest.TestCase):
    def test_outcomes_are_strictly_above_each_threshold(self):
        self.assertEqual('ok', classify(92, 100, 92, 98))
        self.assertEqual('warn', classify(93, 100, 92, 98))
        self.assertEqual('warn', classify(98, 100, 92, 98))
        self.assertEqual('fail', classify(99, 100, 92, 98))
        self.assertEqual('fail', classify(2555905, 2555904, 92, 98))

    def test_table_patterns_and_flag_overrides(self):
        table = {'LAUNCHER_*': (95.0, 99.0), 'exact': (50.0, 60.0)}
        self.assertEqual((92.0, 98.0), thresholds_for('lilygo-t-embed-cc1101', table))
        self.assertEqual((95.0, 99.0), thresholds_for('LAUNCHER_CYD-2432S028', table))
        self.assertEqual((50.0, 60.0), thresholds_for('exact', table))
        self.assertEqual((95.0, 99.5), thresholds_for('LAUNCHER_x', table, fail=99.5))
        self.assertEqual((80.0, 98.0), thresholds_for('other', table, warn=80))
        with self.assertRaises(ValueError):
            thresholds_for('other', table, warn=99)


class ConfigResolutionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        write(self.root, 'platformio.ini', FIXTURE_INI)
        write(self.root, 'boards/demo/demo.ini', '[env:from_extra]\nextends = base_b\n')
        write(self.root, 'boards_json/demo.json', '{"build": {"variant": "demo"}}')
        write(self.root, 'boards_json/bare.json', '{"build": {"partitions": "bare.csv"}}')
        self.config = ProjectConfig(self.root)

    def tearDown(self):
        self.tmp.cleanup()

    def partitions_option(self, env):
        return self.config.get('env:' + env, 'board_build.partitions')

    def test_extends_chain_env_section_and_extra_configs(self):
        self.assertEqual('root.csv', self.partitions_option('plain'))
        self.assertEqual('base_a.csv', self.partitions_option('child'))
        self.assertEqual('base_a.csv', self.partitions_option('grandchild'))
        self.assertEqual('own.csv', self.partitions_option('override'))
        self.assertEqual('base_b.csv', self.partitions_option('from_extra'))
        self.assertEqual('base_b.csv', self.partitions_option('interp'))
        # PlatformIO pops extends as a stack: the last listed section is searched first.
        self.assertEqual('base_b.csv', self.partitions_option('multi'))
        self.assertEqual('demo', self.config.get('env:grandchild', 'board'))

    def test_csv_lookup_order_variant_framework_project(self):
        framework = self.root / 'fw'
        write(self.root, 'own.csv', 'factory, app, factory, 0x10000, 1M\n')
        self.assertEqual(self.root / 'own.csv',
                         resolve_partition_csv(self.config, 'override', framework))
        write(self.root, 'fw/tools/partitions/own.csv', 'factory, app, factory, 0x10000, 2M\n')
        self.assertEqual(framework / 'tools/partitions/own.csv',
                         resolve_partition_csv(self.config, 'override', framework))
        write(self.root, 'boards/demo/own.csv', 'factory, app, factory, 0x10000, 3M\n')
        self.assertEqual(self.root / 'boards/demo/own.csv',
                         resolve_partition_csv(self.config, 'override', framework))

    def test_board_json_default_when_no_env_sets_partitions(self):
        write(self.root, 'platformio.ini', '[env:bare]\nboard = bare\n'
                         '[platformio]\nboards_dir = boards_json\n')
        write(self.root, 'fw/tools/partitions/bare.csv', 'factory, app, factory, 0x10000, 1M\n')
        write(self.root, 'fw/tools/partitions/default.csv', 'factory, app, factory, 0x10000, 2M\n')
        config = ProjectConfig(self.root)
        self.assertEqual(self.root / 'fw/tools/partitions/bare.csv',
                         resolve_partition_csv(config, 'bare', self.root / 'fw'))
        write(self.root, 'boards_json/bare.json', '{"build": {}}')
        self.assertEqual(self.root / 'fw/tools/partitions/default.csv',
                         resolve_partition_csv(config, 'bare', self.root / 'fw'))

    def test_unknown_env_and_missing_table_are_errors(self):
        with self.assertRaises(ValueError):
            resolve_partition_csv(self.config, 'missing', self.root / 'fw')
        with self.assertRaises(FileNotFoundError):
            resolve_partition_csv(self.config, 'child', self.root / 'fw')

    def test_check_env_measures_firmware_bin_and_reports(self):
        write(self.root, 'own.csv', 'factory, app, factory, 0x10000, 0x1000\n')
        firmware = self.root / '.pio/build/override/firmware.bin'
        firmware.parent.mkdir(parents=True)
        firmware.write_bytes(b'\0' * 3900)
        budget = check_env(self.root, 'override', framework_dir=self.root / 'fw')
        self.assertEqual((3900, 4096, 196, 'warn'),
                         (budget.used, budget.size, budget.free, budget.status))
        self.assertIn('own.csv', budget.report())
        firmware.write_bytes(b'\0' * 4050)
        out = io.StringIO()
        self.assertEqual(['override'], check_environments(self.root, ['override', 'child'], out))
        self.assertIn('override: FAIL 4,050 / 4,096 B', out.getvalue())
        self.assertIn('child: UNAVAILABLE', out.getvalue())
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(0, main(['-e', 'override', '--project-dir', str(self.root),
                                      '--fail', '99.5', '--warn', '99']))
            self.assertEqual(1, main(['-e', 'override', '--project-dir', str(self.root)]))
            self.assertEqual(2, main(['-e', 'missing', '--project-dir', str(self.root)]))


class RepositoryConfigTest(unittest.TestCase):
    """The CI and MATRIX envs must resolve to project tables without a build or toolchain."""

    def test_known_envs_resolve_to_their_app_slots(self):
        config = ProjectConfig(REPO)
        expected = {
            'lilygo-t-embed-cc1101': ('custom_16Mb.csv', 4653056),
            'm5stack-core4mb': ('custom_4Mb_full.csv', 3932160),
            'm5stack-cardputer': ('custom_8Mb.csv', 5111808),
            'CYD-2432S028': ('custom_4Mb_full.csv', 3932160),
            'LAUNCHER_CYD-2432S028': ('custom_4Mb.csv', 2555904),
            'LAUNCHER_m5stack-cplus1_1': ('custom_4Mb.csv', 2555904),
            'm5stack-core16mb': ('custom_16Mb.csv', 4653056),
            'ES3C28P': ('partitions_16Mb.csv', 4653056),
        }
        for env, (name, size) in expected.items():
            table = resolve_partition_csv(config, env, REPO / 'missing-framework')
            self.assertEqual(name, table.name, env)
            self.assertEqual(size, app_limit(parse_partition_csv(table.read_text())).size, env)


if __name__ == '__main__':
    unittest.main()
