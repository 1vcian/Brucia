from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from build_firmware import (build, installed_packages, is_exact_spec, main, normalize_manifests,
                            quarantine_orphans, reachable_packages, registry_pins,
                            unpinned_libraries)


def listing(*packages, required=None):
    """Fake `pio pkg list -e <env> --only-libraries -v` output for the given paths."""
    lines = ['Resolving board dependencies...', 'Libraries']
    for index, package in enumerate(packages):
        branch = '└──' if index == len(packages) - 1 else '├──'
        spec = required or f'owner/{Path(package).name} @ 1.0.0'
        lines.append(f'{branch} {Path(package).name} @ 1.0.0 (required: {spec}, {package})')
    return '\n'.join(lines) + '\n'


class BuildFirmwareTest(unittest.TestCase):
    def test_bootstrap_precedes_normalization_and_recursive_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = root / '.pio/libdeps/board/reader/library.properties'
            calls = []

            def run(args, **kwargs):
                calls.append(args)
                if args[1:3] == ['project', 'config']:
                    return subprocess.CompletedProcess(args, 0, json.dumps([
                        ['platformio', []], ['env:board', []]]))
                if args[1:3] == ['pkg', 'install']:
                    self.assertIn('--skip-dependencies', args)
                    manifest.parent.mkdir(parents=True)
                    manifest.write_text('name=Reader\ndepends=h2zero/NimBLE-Arduino\n')
                if args[1:3] == ['pkg', 'list']:
                    board = root / '.pio/libdeps/board'
                    return subprocess.CompletedProcess(args, 0, listing(board / 'reader', board / 'late'))
                if args[1:3] == ['project', 'metadata']:
                    self.assertIn('depends=NimBLE-Arduino\n', manifest.read_text())
                    late = root / '.pio/libdeps/board/late/library.properties'
                    late.parent.mkdir(parents=True)
                    late.write_text('name=Late\ndepends=h2zero/NimBLE-Arduino\n')
                if args[1] == 'run':
                    self.assertIn('depends=NimBLE-Arduino\n', (root / '.pio/libdeps/board/late/library.properties').read_text())
                    self.assertIn('depends=NimBLE-Arduino\n', manifest.read_text())
                return subprocess.CompletedProcess(args, 0)

            with patch('build_firmware.subprocess.run', side_effect=run):
                build(root, ['board'], 'pio')
            self.assertEqual(['config', 'install', 'metadata', 'list', 'run'],
                             [c[2] if c[1] != 'run' else 'run' for c in calls])
            self.assertEqual(0, normalize_manifests(root / '.pio/libdeps', ['board']))

    def test_uart_dependency_uses_its_installed_canonical_name(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = root / 'board/ESP PN532Killer/library.properties'
            manifest.parent.mkdir(parents=True)
            manifest.write_text('name=ESP PN532Killer\ndepends=whywilson/ESP-PN532-UART')
            self.assertEqual(1, normalize_manifests(root, ['board']))
            self.assertIn('depends=ESP PN532 UART\n', manifest.read_text())
            self.assertEqual(0, normalize_manifests(root, ['board']))

    def test_unknown_board_cannot_start_installation(self):
        with patch('build_firmware.subprocess.run', return_value=subprocess.CompletedProcess(
                [], 0, json.dumps([['platformio', []]]))) as run:
            with self.assertRaises(ValueError):
                build(Path('/tmp'), ['missing'], 'pio')
            self.assertEqual(1, run.call_count)

    def test_failed_bootstrap_cannot_start_a_build(self):
        responses = [subprocess.CompletedProcess([], 0, json.dumps([
            ['platformio', []], ['env:board', []]])), subprocess.CalledProcessError(1, ['pio'])]
        with patch('build_firmware.subprocess.run', side_effect=responses) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                build(Path('/tmp'), ['board'], 'pio')
            self.assertEqual(2, run.call_count)

    def test_budget_is_checked_after_build_and_fails_only_when_over(self):
        with patch('build_firmware.build') as build_mock, \
                patch('build_firmware.check_environments', return_value=[]) as check, \
                patch('sys.argv', ['build_firmware.py', '-e', 'board']):
            main()
        build_mock.assert_called_once()
        self.assertEqual(['board'], check.call_args.args[1])
        with patch('build_firmware.build'), \
                patch('build_firmware.check_environments', return_value=['board']), \
                patch('sys.argv', ['build_firmware.py', '-e', 'board']):
            with self.assertRaises(SystemExit) as raised:
                main()
        self.assertIn('board', str(raised.exception.code))

    def test_failed_build_skips_the_budget_check(self):
        with patch('build_firmware.build', side_effect=subprocess.CalledProcessError(3, ['pio'])), \
                patch('build_firmware.check_environments') as check, \
                patch('sys.argv', ['build_firmware.py', '-e', 'board']):
            with self.assertRaises(SystemExit) as raised:
                main()
        self.assertEqual(3, raised.exception.code)
        check.assert_not_called()



class OrphanLibraryTest(unittest.TestCase):
    """A stale libdeps tree must not change what the LDF can link."""

    def fake_tree(self, root):
        board = root / '.pio/libdeps/board'
        for name, manifest in [('Adafruit TCA8418', 'library.properties'),
                               ('Adafruit SH110X', 'library.properties'),
                               ('ESP8266Audio', 'library.json'),
                               ('M5Unified', 'library.json'),
                               ('M5GFX', 'library.json')]:
            (board / name).mkdir(parents=True)
            (board / name / manifest).write_text('name=' + name + '\n')
        (board / 'integrity.dat').write_text('adafruit/Adafruit TCA8418 @ ^1.0.2\nESP8266Audio@2.4.1\n')
        return board

    def test_undeclared_packages_are_quarantined_and_declared_ones_kept(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            board = self.fake_tree(root)
            # Declared (TCA8418, ESP8266Audio) and transitive (SH110X) packages are listed;
            # M5Unified/M5GFX are installed but nothing declared requires them.
            output = listing(board / 'Adafruit TCA8418', board / 'Adafruit SH110X', board / 'ESP8266Audio')
            with patch('build_firmware.subprocess.run',
                       return_value=subprocess.CompletedProcess([], 0, output)) as run:
                removed = quarantine_orphans(root, root / '.pio/libdeps', 'board', 'pio')
            self.assertEqual(['pio', 'pkg', 'list', '-e', 'board', '--only-libraries', '-v'],
                             run.call_args.args[0])
            self.assertEqual(['M5GFX', 'M5Unified'], removed)
            self.assertEqual(['Adafruit SH110X', 'Adafruit TCA8418', 'ESP8266Audio'],
                             [p.name for p in installed_packages(board)])
            self.assertTrue((board / 'integrity.dat').is_file())
            quarantine = root / '.pio/libdeps-orphans/board'
            self.assertTrue((quarantine / 'M5Unified/library.json').is_file())
            self.assertTrue((quarantine / 'M5GFX/library.json').is_file())

    def test_requarantine_replaces_an_older_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            board = self.fake_tree(root)
            old = root / '.pio/libdeps-orphans/board/M5Unified'
            old.mkdir(parents=True)
            (old / 'stale').write_text('old')
            output = listing(board / 'Adafruit TCA8418', board / 'Adafruit SH110X', board / 'ESP8266Audio')
            with patch('build_firmware.subprocess.run',
                       return_value=subprocess.CompletedProcess([], 0, output)):
                quarantine_orphans(root, root / '.pio/libdeps', 'board', 'pio')
            self.assertFalse((old / 'stale').exists())
            self.assertTrue((old / 'library.json').is_file())

    def test_clean_tree_is_left_alone(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            board = self.fake_tree(root)
            output = listing(*installed_packages(board))
            with patch('build_firmware.subprocess.run',
                       return_value=subprocess.CompletedProcess([], 0, output)):
                self.assertEqual([], quarantine_orphans(root, root / '.pio/libdeps', 'board', 'pio'))
            self.assertEqual(5, len(installed_packages(board)))
            self.assertFalse((root / '.pio/libdeps-orphans').exists())

    def test_unmatched_listing_never_empties_libdeps(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            board = self.fake_tree(root)
            with patch('build_firmware.subprocess.run',
                       return_value=subprocess.CompletedProcess([], 0, 'Resolving board dependencies...\nNo packages\n')):
                with self.assertRaises(RuntimeError):
                    quarantine_orphans(root, root / '.pio/libdeps', 'board', 'pio')
            self.assertEqual(5, len(installed_packages(board)))

    def test_missing_libdeps_needs_no_listing(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch('build_firmware.subprocess.run') as run:
                self.assertEqual([], quarantine_orphans(Path(tmp), Path(tmp) / '.pio/libdeps', 'board', 'pio'))
            run.assert_not_called()

    def test_reachability_matches_whole_paths_only(self):
        board = Path('/p/.pio/libdeps/board')
        packages = [board / 'M5GFX', board / 'M5Unified', board / 'Time']
        # A spec or a longer path that merely contains a package name does not count.
        output = listing(board / 'Time', required='M5Unified') + \
            f'├── X @ 1 (required: X, {board}/M5GFX-extra)\n'
        self.assertEqual({board / 'Time'}, reachable_packages(output, packages))

    def test_quarantine_precedes_the_build_and_its_patch_step(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            board = self.fake_tree(root)
            calls = []

            def run(args, **kwargs):
                calls.append(args[1] if args[1] == 'run' else args[2])
                if args[1:3] == ['project', 'config']:
                    return subprocess.CompletedProcess(args, 0, json.dumps([
                        ['platformio', []], ['env:board', []]]))
                if args[1:3] == ['pkg', 'list']:
                    return subprocess.CompletedProcess(args, 0, listing(
                        board / 'Adafruit TCA8418', board / 'Adafruit SH110X', board / 'ESP8266Audio'))
                if args[1] == 'run':
                    # pio run executes pre:patch_library_conflicts.py over what is left.
                    self.assertFalse((board / 'M5Unified').exists())
                    self.assertFalse((board / 'M5GFX').exists())
                return subprocess.CompletedProcess(args, 0)

            with patch('build_firmware.subprocess.run', side_effect=run):
                build(root, ['board'], 'pio')
            self.assertEqual(['config', 'install', 'metadata', 'list', 'run'], calls)

    def test_refused_quarantine_is_a_clean_exit(self):
        with patch('build_firmware.build', side_effect=RuntimeError('refusing')), \
                patch('build_firmware.check_environments') as check, \
                patch('sys.argv', ['build_firmware.py', '-e', 'board']):
            with self.assertRaises(SystemExit) as raised:
                main()
        self.assertEqual('refusing', raised.exception.code)
        check.assert_not_called()


class PinnedVersionTest(unittest.TestCase):
    """An exact lib_deps pin decides which installed version of a library stays linkable."""

    def tree(self, root, names):
        board = root / '.pio/libdeps/board'
        for name in names:
            (board / name).mkdir(parents=True)
            (board / name / 'library.json').write_text('{}')
        return board

    def test_exact_specs(self):
        for spec in ('m5stack/M5GFX @ 0.2.36', 'LibSSH-ESP32 @ 5.10.0',
                     'git+https://github.com/a/b#' + 'c' * 40,
                     'https://github.com/a/b/archive/' + 'c' * 40 + '.zip'):
            with self.subTest(spec=spec):
                self.assertTrue(is_exact_spec(spec))
        for spec in ('M5GFX @ >=0.2.35', 'ESP32Async/AsyncTCP @ ^3.5.0', 'x @ ~1.2.3',
                     'NimBLE-Arduino', 'git+https://github.com/a/b', 'git+https://github.com/a/b#main',
                     'Foo @ >=1.0.0, <2.0.0'):
            with self.subTest(spec=spec):
                self.assertFalse(is_exact_spec(spec))

    def test_other_version_reached_only_through_a_range_is_quarantined(self):
        # `pio pkg install` of the pin m5stack/M5GFX @ 0.2.36 over a newer leftover 0.2.37
        # keeps the old one in M5GFX/ and puts the pinned one in M5GFX@0.2.36/ (PlatformIO
        # "detach-new"); M5Unified's own `M5GFX >=0.2.35` still prints the newer one.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            board = self.tree(root, ['M5Unified', 'M5GFX', 'M5GFX@0.2.36'])
            output = '\n'.join([
                'Libraries',
                f'├── M5GFX @ 0.2.36 (required: m5stack/M5GFX @ 0.2.36, {board}/M5GFX@0.2.36)',
                f'└── M5Unified @ 0.2.29 (required: m5stack/M5Unified @ 0.2.29, {board}/M5Unified)',
                f'    └── M5GFX @ 0.2.37 (required: M5GFX @ >=0.2.35, {board}/M5GFX)', ''])
            with patch('build_firmware.subprocess.run',
                       return_value=subprocess.CompletedProcess([], 0, output)):
                removed = quarantine_orphans(root, root / '.pio/libdeps', 'board', 'pio')
            self.assertEqual(['M5GFX'], removed)
            self.assertEqual(['M5GFX@0.2.36', 'M5Unified'], [p.name for p in installed_packages(board)])

    def test_same_package_reached_by_pin_and_range_is_kept(self):
        board = Path('/p/.pio/libdeps/board')
        packages = [board / 'ESPAsyncWebServer', board / 'AsyncTCP']
        output = '\n'.join([
            f'├── ESPAsyncWebServer @ 3.12.1 (required: ESP32Async/ESPAsyncWebServer @ 3.12.1, {board}/ESPAsyncWebServer)',
            f'│   └── AsyncTCP @ 3.5.0 (required: ESP32Async/AsyncTCP @ ^3.5.0, {board}/AsyncTCP)',
            f'└── AsyncTCP @ 3.5.0 (required: ESP32Async/AsyncTCP @ 3.5.0, {board}/AsyncTCP)'])
        self.assertEqual(set(packages), reachable_packages(output, packages))
        self.assertEqual([], unpinned_libraries(output, packages))

    def test_library_reached_only_through_a_range_is_kept_and_reported(self):
        board = Path('/p/.pio/libdeps/board')
        packages = [board / 'Parent', board / 'Child']
        output = '\n'.join([
            f'├── Parent @ 1.0.0 (required: owner/Parent @ 1.0.0, {board}/Parent)',
            f'│   └── Child @ 2.1.0 (required: Child @ >=2.0.0, <3.0.0, {board}/Child)'])
        self.assertEqual(set(packages), reachable_packages(output, packages))
        self.assertEqual(['Child (required: Child @ >=2.0.0, <3.0.0)'],
                         unpinned_libraries(output, packages))

    def test_range_only_library_is_reported_before_the_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            board = self.tree(root, ['Parent', 'Child'])
            output = '\n'.join([
                f'├── Parent @ 1.0.0 (required: owner/Parent @ 1.0.0, {board}/Parent)',
                f'│   └── Child @ 2.1.0 (required: Child @ ^2.0.0, {board}/Child)'])
            printed = io.StringIO()
            with patch('build_firmware.subprocess.run',
                       return_value=subprocess.CompletedProcess([], 0, output)), redirect_stdout(printed):
                self.assertEqual([], quarantine_orphans(root, root / '.pio/libdeps', 'board', 'pio'))
            self.assertIn('Warning: board resolves Child (required: Child @ ^2.0.0) through a range',
                          printed.getvalue())

    def test_registry_pins_come_from_exact_lib_deps_entries(self):
        self.assertEqual({'m5gfx': '0.2.36', 'nimble-arduino': '2.5.2', 'libssh-esp32': '5.10.0',
                          'adafruit gfx library': '1.13.0'},
                         registry_pins(['m5stack/M5GFX @ 0.2.36 ; why', 'h2zero/NimBLE-Arduino@2.5.2',
                                        'LibSSH-ESP32@5.10.0', 'adafruit/Adafruit GFX Library @ 1.13.0',
                                        'm5stack/M5Unified @ ^0.2.11', 'FFat',
                                        'Adafruit BusIO=https://github.com/a/b#' + 'c' * 40]))

    def test_pinned_release_decides_even_when_listed_only_under_ranges(self):
        # PlatformIO prints each package once: when a parent sorting before the pinned
        # library reaches it first, the exact top-level line is never printed. Here two
        # parents' ranges reach two installed releases; the pin keeps 2.5.2 only.
        board = Path('/p/.pio/libdeps/board')
        packages = [board / 'A', board / 'B', board / 'NimBLE-Arduino', board / 'NimBLE-Arduino@2.6.0']
        output = '\n'.join([
            f'├── A @ 1.0.0 (required: git+https://github.com/x/a#{"a" * 40}, {board}/A)',
            f'│   └── NimBLE-Arduino @ 2.6.0 (required: NimBLE-Arduino, {board}/NimBLE-Arduino@2.6.0)',
            f'└── B @ 1.0.0 (required: git+https://github.com/x/b#{"b" * 40}, {board}/B)',
            f'    └── NimBLE-Arduino @ 2.5.2 (required: NimBLE-Arduino @ ^2.5.0, {board}/NimBLE-Arduino)'])
        pins = {'nimble-arduino': '2.5.2'}
        self.assertEqual({board / 'A', board / 'B', board / 'NimBLE-Arduino'},
                         reachable_packages(output, packages, pins))
        self.assertEqual([], unpinned_libraries(output, packages, pins))
        # Without the declared pin both stay and both are reported as unpinned.
        self.assertEqual(set(packages), reachable_packages(output, packages))
        self.assertEqual(2, len(unpinned_libraries(output, packages)))

    def test_pin_matching_no_listed_release_removes_nothing(self):
        board = Path('/p/.pio/libdeps/board')
        packages = [board / 'Time']
        output = f'└── Time @ 1.6.1 (required: Time, {board}/Time)'
        self.assertEqual({board / 'Time'}, reachable_packages(output, packages, {'time': '9.9.9'}))

    def test_build_passes_each_env_lib_deps_to_the_quarantine(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            board = self.tree(root, ['M5GFX', 'M5GFX@0.2.36'])
            listed = '\n'.join([
                f'├── M5GFX @ 0.2.37 (required: M5GFX @ >=0.2.35, {board}/M5GFX)',
                f'├── M5GFX @ 0.2.36 (required: M5GFX @ >=0.2.0, {board}/M5GFX@0.2.36)'])

            def run(args, **kwargs):
                if args[1:3] == ['project', 'config']:
                    return subprocess.CompletedProcess(args, 0, json.dumps([
                        ['platformio', []], ['env:board', [['lib_deps', ['m5stack/M5GFX @ 0.2.36']]]]]))
                if args[1:3] == ['pkg', 'list']:
                    return subprocess.CompletedProcess(args, 0, listed)
                return subprocess.CompletedProcess(args, 0)

            with patch('build_firmware.subprocess.run', side_effect=run), redirect_stdout(io.StringIO()):
                build(root, ['board'], 'pio')
            self.assertEqual(['M5GFX@0.2.36'], [p.name for p in installed_packages(board)])


if __name__ == '__main__':
    unittest.main()
