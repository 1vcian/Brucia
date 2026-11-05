import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from build_firmware import build, main, normalize_manifests


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
            self.assertEqual(['config', 'install', 'metadata', 'run'],
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


if __name__ == '__main__':
    unittest.main()
