"""Hermetic release-script tests: commands are fakes; nothing is pushed/published."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

import brand

ROOT = Path(__file__).resolve().parents[1]
SHIM = r'''
import json, os, sys
from pathlib import Path
name = Path(sys.argv[0]).name
args = sys.argv[1:]
with open('calls.jsonl', 'a') as log:
    log.write(json.dumps([name, *args]) + '\n')
if name == 'git':
    if args[:2] == ['status', '--porcelain'] and os.environ.get('RELEASE_TEST_DIRTY'):
        print(' M user.txt')
    if args[:2] == ['branch', '--show-current']: print('main')
    if args[:1] == ['rev-parse']: sys.exit(1)
    if args[:1] == ['add'] and os.environ.get('RELEASE_TEST_FAIL_ADD'): sys.exit(1)
elif name == 'pio':
    if args[:2] == ['project', 'config']:
        print(json.dumps([['platformio', []], ['env:lilygo-t-embed-cc1101', []]]))
        sys.exit(0)
    path = Path('.pio/build/lilygo-t-embed-cc1101/firmware.factory.bin')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b'new firmware')
elif name == 'gradlew':
    folder = Path('android/app/build/outputs/apk/release')
    folder.mkdir(parents=True, exist_ok=True)
    if ':app:clean' in args: (folder / 'app-release.apk').unlink(missing_ok=True)
    if ':app:assembleRelease' in args:
        name = 'app-release-unsigned.apk' if os.environ.get('RELEASE_TEST_UNSIGNED') else 'app-release.apk'
        (folder / name).write_bytes(b'new apk')
'''


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        shutil.copy2(ROOT / 'release.sh', self.repo / 'release.sh')
        for directory in ('bin', 'android', 'tools', 'docs/firmware'):
            (self.repo / directory).mkdir(parents=True, exist_ok=True)
        (self.repo / 'VERSION').write_text('0.1\n')
        (self.repo / 'docs/manifest.json').write_text('{"version":"0.6.3"}\n')
        (self.repo / 'docs/firmware/brucia-t-embed-cc1101.bin').write_bytes(b'old firmware')
        for tool in ('git', 'pio', 'gh'):
            self.executable(self.repo / 'bin' / tool)
        self.executable(self.repo / 'android/gradlew')
        shutil.copy2(ROOT / 'brand.json', self.repo / 'brand.json')
        for tool in ('build_firmware.py', 'check_flash_budget.py', 'brand.py'):
            shutil.copy2(ROOT / 'tools' / tool, self.repo / 'tools' / tool)
        (self.repo / 'tools/test_site_release.cjs').write_text('// standalone website tests run separately\n')
        for script in ('test_firmware_codec.py', 'test_firmware_storage.py', 'test_firmware_framing.py', 'test_protocol_e2e.py', 'test_audio_noise_bounds.py'):
            (self.repo / 'tools' / script).write_text('# stand-in for independently tested gates\n')
        self.env = dict(os.environ, PATH=str(self.repo / 'bin') + os.pathsep + os.environ['PATH'], PIO_BIN='pio')

    def executable(self, path):
        path.write_text('#!' + sys.executable + '\n' + SHIM)
        path.chmod(0o755)

    def run_release(self):
        return subprocess.run(['bash', 'release.sh', 'patch', 'Fix one\nFix two'], cwd=self.repo,
                              env=self.env, capture_output=True, text=True, timeout=30)

    def calls(self):
        return [json.loads(x) for x in (self.repo / 'calls.jsonl').read_text().splitlines()]

    def test_missing_signing_refuses_before_changing_version(self):
        result = self.run_release()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('signing requires', result.stderr)
        self.assertEqual('0.1\n', (self.repo / 'VERSION').read_text())
        self.assertFalse(any(c[0] == 'gh' for c in self.calls()))

    def test_dirty_tree_refuses_before_changing_version(self):
        self.env['RELEASE_TEST_DIRTY'] = '1'
        result = self.run_release()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('clean working tree', result.stderr)
        self.assertEqual('0.1\n', (self.repo / 'VERSION').read_text())

    def test_unsigned_build_cannot_publish_a_stale_signed_apk(self):
        (self.repo / 'android/keystore.properties').write_text('# simulated signing config')
        stale = self.repo / 'android/app/build/outputs/apk/release/app-release.apk'
        stale.parent.mkdir(parents=True)
        stale.write_bytes(b'stale signed apk')
        self.env['RELEASE_TEST_UNSIGNED'] = '1'
        result = self.run_release()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(stale.exists())
        self.assertFalse(any(c[0] == 'gh' or c[:2] == ['git', 'commit'] for c in self.calls()))
        self.assertEqual('0.1\n', (self.repo / 'VERSION').read_text())
        self.assertEqual('0.6.3', json.loads((self.repo / 'docs/manifest.json').read_text())['version'])
        self.assertEqual(b'old firmware', (self.repo / 'docs/firmware/brucia-t-embed-cc1101.bin').read_bytes())

    def test_failed_staging_cannot_tag_or_publish(self):
        (self.repo / 'android/keystore.properties').write_text('# simulated signing config')
        self.env['RELEASE_TEST_FAIL_ADD'] = '1'
        result = self.run_release()
        self.assertNotEqual(0, result.returncode)
        forbidden = {'commit', 'tag', 'push'}
        self.assertFalse(any(c[0] == 'gh' or (c[0] == 'git' and c[1] in forbidden) for c in self.calls()))
        self.assertEqual('0.1\n', (self.repo / 'VERSION').read_text())

    def test_success_stages_only_release_files_and_pushes_refs_atomically(self):
        (self.repo / 'android/keystore.properties').write_text('# simulated signing config')
        result = self.run_release()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('0.1.1\n', (self.repo / 'VERSION').read_text())
        self.assertEqual('0.1.1', json.loads((self.repo / 'docs/manifest.json').read_text())['version'])
        calls = self.calls()
        add = next(c for c in calls if c[:2] == ['git', 'add'])
        self.assertEqual(['git', 'add', '--', 'VERSION', 'docs/manifest.json',
                          'docs/firmware/brucia-t-embed-cc1101.bin'], add)
        target = brand.derive(brand.load(ROOT))
        self.assertIn(['git', 'push', '--atomic', target['release_remote'], 'main', 'v0.1.1'], calls)
        release = next(c for c in calls if c[:3] == ['gh', 'release', 'create'])
        self.assertEqual(['-R', target['repo_slug']], release[4:6])
        self.assertIn('Fix one\nFix two', release[-1])

    def test_release_target_follows_brand_json_after_launch_switch(self):
        (self.repo / 'android/keystore.properties').write_text('# simulated signing config')
        launched = brand.apply_launch(brand.load(ROOT))
        (self.repo / 'brand.json').write_text(json.dumps(launched))
        result = self.run_release()
        self.assertEqual(0, result.returncode, result.stderr)
        target = brand.derive(launched)
        calls = self.calls()
        self.assertIn(['git', 'push', '--atomic', target['release_remote'], 'main', 'v0.1.1'], calls)
        release = next(c for c in calls if c[:3] == ['gh', 'release', 'create'])
        self.assertEqual(['-R', target['repo_slug']], release[4:6])
        self.assertIn(target['repo_slug'], result.stdout)


if __name__ == '__main__':
    unittest.main()
