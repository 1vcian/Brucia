import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from build_firmware import build, normalize_manifests


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
                if args[1] == 'run':
                    self.assertIn('depends=NimBLE-Arduino\n', manifest.read_text())
                return subprocess.CompletedProcess(args, 0)

            with patch('build_firmware.subprocess.run', side_effect=run):
                build(root, ['board'], 'pio')
            self.assertEqual(['project', 'pkg', 'run'], [c[1] for c in calls])
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


if __name__ == '__main__':
    unittest.main()
