"""Hermetic release-script tests.

Each test builds a throwaway git repository whose release remote is a local bare repository. Real
git runs behind a logging wrapper that refuses network-capable commands unless every remote URL is
inside the sandbox. `gh`, Gradle and PlatformIO are stateful fakes: nothing reaches GitHub, and the
fake GitHub keeps releases/assets in a JSON file so partial publications can be inspected and
resumed. Set RELEASE_SCRIPT_UNDER_TEST to run the same cases against another release.sh.
"""
import hashlib
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
SCRIPT = Path(os.environ.get('RELEASE_SCRIPT_UNDER_TEST') or ROOT / 'release.sh')
REAL_GIT = shutil.which('git')
FIRMWARE = 'brucia-t-embed-cc1101.bin'

PRELUDE = r'''
import json, os, shutil, subprocess, sys
from pathlib import Path
name = Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['RELEASE_TEST_CALLS'], 'a') as log:
    log.write(json.dumps([name, *args]) + '\n')
'''

GIT_SHIM = PRELUDE + r'''
real = os.environ['RELEASE_TEST_REAL_GIT']
sandbox = os.environ['RELEASE_TEST_SANDBOX']
sub = args[0] if args else ''
if sub and sub == os.environ.get('RELEASE_TEST_FAIL_GIT'):
    print(f'injected failure: git {sub}', file=sys.stderr)
    sys.exit(1)
if sub in ('push', 'fetch', 'ls-remote', 'pull', 'clone', 'remote'):
    urls = subprocess.run([real, 'remote', '-v'], capture_output=True, text=True).stdout.split()
    for url in urls[1::3]:
        if not os.path.realpath(url).startswith(os.path.realpath(sandbox)):
            print(f'harness guard: remote {url} is outside the sandbox', file=sys.stderr)
            sys.exit(99)
os.execv(real, [real, *args])
'''

GH_SHIM = PRELUDE + r'''
state_path = Path(os.environ['RELEASE_TEST_GH_STATE'])
store = Path(os.environ['RELEASE_TEST_GH_STORE'])
state = json.loads(state_path.read_text()) if state_path.exists() else {}
fail = os.environ.get('RELEASE_TEST_FAIL_GH', '')

def save():
    state_path.write_text(json.dumps(state))

def die(message, code=1):
    print(message, file=sys.stderr)
    sys.exit(code)

if args[:1] != ['release']:
    die('fake gh supports only release commands')
sub, tag, rest = args[1], args[2], args[3:]
options, files, i = {}, [], 0
while i < len(rest):
    item = rest[i]
    if item in ('--draft', '--verify-tag', '--clobber'):
        options[item] = True
    elif item.startswith('--') and '=' in item:
        key, value = item.split('=', 1)
        options[key] = value
    elif item.startswith('-'):
        options[item] = rest[i + 1]
        i += 1
    else:
        files.append(item)
    i += 1
if options.get('-R') != os.environ['RELEASE_TEST_REPO']:
    die('fake gh: unexpected repository ' + str(options.get('-R')))
if fail == sub:
    die(f'injected failure: gh release {sub}')
release = state.get(tag)
if sub != 'create' and release is None:
    die('release not found')
if sub == 'view':
    assets = [{'name': n, 'size': a['size'], 'state': a['state']} for n, a in release['assets'].items()]
    print(json.dumps({'tagName': tag, 'isDraft': release['draft'], 'assets': assets}))
elif sub == 'create':
    if release is not None:
        die('a release with the same tag name already exists')
    bare = os.environ['RELEASE_TEST_BARE']
    if '--verify-tag' in options and subprocess.run(
            [os.environ['RELEASE_TEST_REAL_GIT'], '--git-dir', bare, 'rev-parse', '-q', '--verify',
             'refs/tags/' + tag], capture_output=True).returncode != 0:
        die('tag ' + tag + ' does not exist in the remote repository')
    if files:
        die('harness expects assets to be uploaded separately')
    state[tag] = {'draft': '--draft' in options, 'title': options['--title'],
                  'notes': Path(options['--notes-file']).read_text(), 'assets': {}}
    save()
elif sub == 'upload':
    for index, path in enumerate(files):
        if fail == 'upload-partial' and index == 1:
            die('injected failure: connection reset during second asset')
        asset = Path(path).name
        if asset in release['assets'] and '--clobber' not in options:
            die('asset under the same name already exists')
        blob = store / f'{tag}-{asset}'
        shutil.copyfile(path, blob)
        release['assets'][asset] = {'size': blob.stat().st_size, 'state': 'uploaded'}
        save()
elif sub == 'download':
    asset = options['-p']
    if asset not in release['assets']:
        die('no assets match the file pattern')
    target = Path(options['-D']) / asset
    if target.exists() and '--clobber' not in options:
        die('file exists')
    shutil.copyfile(store / f'{tag}-{asset}', target)
elif sub == 'edit':
    if options.get('--draft') == 'false':
        release['draft'] = False
    save()
else:
    die('fake gh: unsupported release ' + sub)
'''

PIO_SHIM = PRELUDE + r'''
if args[:2] == ['project', 'config']:
    print(json.dumps([['platformio', []], ['env:lilygo-t-embed-cc1101', []]]))
    sys.exit(0)
path = Path('.pio/build/lilygo-t-embed-cc1101/firmware.factory.bin')
path.parent.mkdir(parents=True, exist_ok=True)
path.write_bytes(b'new firmware')
'''

GRADLE_SHIM = PRELUDE + r'''
build = Path('android/app/build')
fail = os.environ.get('RELEASE_TEST_FAIL_GRADLE', '')
if ':app:testDebugUnitTest' in args:
    report = build / 'reports/tests/testDebugUnitTest/index.html'
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text('unit report')
if fail and fail in args:
    print(f'injected failure: {fail}', file=sys.stderr)
    sys.exit(1)
if ':app:clean' in args:
    shutil.rmtree(build / 'reports', ignore_errors=True)
    shutil.rmtree(build / 'test-results', ignore_errors=True)
    (build / 'outputs/apk/release/app-release.apk').unlink(missing_ok=True)
if ':app:assembleRelease' in args:
    folder = build / 'outputs/apk/release'
    folder.mkdir(parents=True, exist_ok=True)
    name = 'app-release-unsigned.apk' if os.environ.get('RELEASE_TEST_UNSIGNED') else 'app-release.apk'
    (folder / name).write_bytes(b'new signed apk')
'''


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.sandbox = Path(self.tmp.name)
        self.repo = self.sandbox / 'repo'
        self.bare = self.sandbox / 'remote.git'
        self.bin = self.sandbox / 'bin'
        self.calls_file = self.sandbox / 'calls.jsonl'
        self.gh_store = self.sandbox / 'gh-store'
        self.gh_state = self.sandbox / 'gh-state.json'
        for directory in (self.repo, self.bin, self.gh_store, self.sandbox / 'home'):
            directory.mkdir(parents=True)
        for directory in ('android', 'tools', 'docs/firmware'):
            (self.repo / directory).mkdir(parents=True, exist_ok=True)
        shutil.copy2(SCRIPT, self.repo / 'release.sh')
        (self.repo / 'VERSION').write_text('0.1\n')
        (self.repo / 'docs/manifest.json').write_text('{"version":"0.6.3"}\n')
        (self.repo / 'docs/firmware' / FIRMWARE).write_bytes(b'old firmware')
        (self.repo / '.gitignore').write_text(
            '.pio/\nandroid/app/build/\nandroid/keystore.properties\n.release-staging/\n__pycache__/\n')
        self.shim(self.bin / 'git', GIT_SHIM)
        self.shim(self.bin / 'gh', GH_SHIM)
        self.shim(self.bin / 'pio', PIO_SHIM)
        self.shim(self.repo / 'android/gradlew', GRADLE_SHIM)
        self.set_brand(brand.load(ROOT))
        for tool in ('build_firmware.py', 'check_flash_budget.py', 'brand.py'):
            shutil.copy2(ROOT / 'tools' / tool, self.repo / 'tools' / tool)
        (self.repo / 'tools/test_site_release.cjs').write_text('// standalone website tests run separately\n')
        for script in ('test_firmware_codec.py', 'test_firmware_storage.py', 'test_firmware_framing.py',
                       'test_protocol_e2e.py', 'test_audio_noise_bounds.py'):
            (self.repo / 'tools' / script).write_text('# stand-in for independently tested gates\n')
        env = {k: v for k, v in os.environ.items()
               if k not in ('GH_TOKEN', 'GITHUB_TOKEN', 'GH_ENTERPRISE_TOKEN', 'GH_HOST', 'GIT_DIR',
                            'GIT_WORK_TREE')
               and not k.startswith('RELEASE_TEST_')}
        env.update(
            PATH=str(self.bin) + os.pathsep + os.environ['PATH'], PIO_BIN='pio',
            HOME=str(self.sandbox / 'home'), GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1',
            GIT_TERMINAL_PROMPT='0', GIT_AUTHOR_NAME='Harness', GIT_AUTHOR_EMAIL='harness@invalid',
            GIT_COMMITTER_NAME='Harness', GIT_COMMITTER_EMAIL='harness@invalid',
            RELEASE_TEST_CALLS=str(self.calls_file), RELEASE_TEST_REAL_GIT=REAL_GIT,
            RELEASE_TEST_SANDBOX=str(self.sandbox), RELEASE_TEST_GH_STATE=str(self.gh_state),
            RELEASE_TEST_GH_STORE=str(self.gh_store), RELEASE_TEST_BARE=str(self.bare),
            RELEASE_TEST_REPO=self.target['repo_slug'])
        self.env = env
        self.git('init', '-q', '-b', 'main')
        self.git('add', '-A')
        self.git('commit', '-q', '-m', 'initial')
        subprocess.run([REAL_GIT, 'init', '-q', '--bare', str(self.bare)], check=True, env=self.env)
        self.git('remote', 'add', self.target['release_remote'], str(self.bare))
        self.git('push', '-q', self.target['release_remote'], 'main')
        self.calls_file.write_text('')

    # --- harness helpers -------------------------------------------------------------------
    def set_brand(self, raw):
        (self.repo / 'brand.json').write_text(json.dumps(raw, indent=2) + '\n')
        self.target = brand.derive(raw)

    def shim(self, path, body):
        path.write_text('#!' + sys.executable + '\n' + body)
        path.chmod(0o755)

    def git(self, *args, cwd=None):
        return subprocess.run([REAL_GIT, *args], cwd=cwd or self.repo, env=getattr(self, 'env', None),
                              check=True, capture_output=True, text=True).stdout.strip()

    def sign(self):
        (self.repo / 'android/keystore.properties').write_text('# simulated signing config')

    def run_release(self, *args, **faults):
        env = dict(self.env)
        for key, value in faults.items():
            env['RELEASE_TEST_' + key.upper()] = value
        self.calls_file.write_text('')
        return subprocess.run(['bash', 'release.sh', *(args or ('patch', 'Fix one\nFix two'))],
                              cwd=self.repo, env=env, capture_output=True, text=True, timeout=120)

    def calls(self):
        return [json.loads(x) for x in self.calls_file.read_text().splitlines()]

    def gh_releases(self):
        return json.loads(self.gh_state.read_text()) if self.gh_state.exists() else {}

    def bundle(self, version='0.1.1'):
        return self.repo / '.release-staging' / f'v{version}'

    def release_commits(self, version='0.1.1'):
        return self.git('log', '--format=%s', 'main').splitlines().count(f'release {version}')

    def remote_tag(self, tag='v0.1.1'):
        out = subprocess.run([REAL_GIT, '--git-dir', str(self.bare), 'rev-parse', '-q', '--verify',
                              f'refs/tags/{tag}^{{commit}}'], capture_output=True, text=True, env=self.env)
        return out.stdout.strip() or None

    def assert_published(self, version='0.1.1'):
        tag = f'v{version}'
        head = self.git('rev-parse', 'HEAD')
        self.assertEqual(f'{version}\n', (self.repo / 'VERSION').read_text())
        self.assertEqual(1, self.release_commits(version))
        self.assertEqual(head, self.remote_tag(tag))
        self.assertEqual(head, self.git('--git-dir', str(self.bare), 'rev-parse', 'main'))
        release = self.gh_releases()[tag]
        self.assertFalse(release['draft'])
        self.assertEqual(f'Brucia {version}', release['title'])
        self.assertEqual({f'Brucia-{version}.apk', FIRMWARE}, set(release['assets']))
        self.assertEqual(b'new signed apk', (self.gh_store / f'{tag}-Brucia-{version}.apk').read_bytes())
        self.assertEqual(b'new firmware', (self.gh_store / f'{tag}-{FIRMWARE}').read_bytes())
        self.assertTrue((self.bundle(version) / 'published').is_file())

    def assert_kept_for_resume(self, result):
        self.assertNotEqual(0, result.returncode)
        self.assertIn('./release.sh resume', result.stderr)
        bundle = self.bundle()
        self.assertEqual('0.1.1\n', (self.repo / 'VERSION').read_text())
        self.assertEqual(1, self.release_commits())
        self.assertEqual(self.git('rev-parse', 'HEAD'), (bundle / 'commit').read_text().strip())
        sums = dict(reversed(line.split(None, 1)) for line in (bundle / 'SHA256SUMS').read_text().splitlines())
        self.assertEqual(sha256(b'new signed apk'), sums['Brucia-0.1.1.apk'])
        self.assertEqual(sha256(b'new firmware'), sums[FIRMWARE])
        self.assertEqual(b'new signed apk', (bundle / 'Brucia-0.1.1.apk').read_bytes())
        self.assertFalse((bundle / 'published').exists())

    def assert_resume_publishes(self):
        result = self.run_release('resume')
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls()
        self.assertFalse(any(c[0] in ('gradlew', 'pio') for c in calls), 'resume must not rebuild')
        self.assertFalse(any(c[:2] == ['git', 'commit'] for c in calls), 'resume must not bump/commit')
        self.assertFalse(any('--clobber' in c for c in calls))
        self.assert_published()
        return calls

    # --- refusals before any change ----------------------------------------------------------
    def test_missing_signing_refuses_before_changing_version(self):
        result = self.run_release()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('signing requires', result.stderr)
        self.assertEqual('0.1\n', (self.repo / 'VERSION').read_text())
        self.assertFalse(any(c[0] == 'gh' for c in self.calls()))

    def test_dirty_tree_refuses_before_changing_version(self):
        self.sign()
        (self.repo / 'user.txt').write_text('uncommitted work')
        result = self.run_release()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('clean working tree', result.stderr)
        self.assertEqual('0.1\n', (self.repo / 'VERSION').read_text())

    def test_unsigned_build_cannot_publish_a_stale_signed_apk(self):
        self.sign()
        stale = self.repo / 'android/app/build/outputs/apk/release/app-release.apk'
        stale.parent.mkdir(parents=True)
        stale.write_bytes(b'stale signed apk')
        result = self.run_release(unsigned='1')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(stale.exists())
        self.assertFalse(any(c[:3] == ['gh', 'release', 'create'] or c[:2] == ['git', 'commit']
                             for c in self.calls()))
        self.assertEqual('0.1\n', (self.repo / 'VERSION').read_text())
        self.assertEqual('0.6.3', json.loads((self.repo / 'docs/manifest.json').read_text())['version'])
        self.assertEqual(b'old firmware', (self.repo / 'docs/firmware' / FIRMWARE).read_bytes())
        self.assertEqual('', self.git('status', '--porcelain'))

    def test_failed_staging_cannot_tag_or_publish(self):
        self.sign()
        result = self.run_release(fail_git='add')
        self.assertNotEqual(0, result.returncode)
        forbidden = {'commit', 'tag', 'push'}
        self.assertFalse(any(c[:3] == ['gh', 'release', 'create'] or (c[0] == 'git' and c[1] in forbidden)
                             for c in self.calls()))
        self.assertEqual('0.1\n', (self.repo / 'VERSION').read_text())
        self.assertFalse(self.bundle().exists(), 'a pre-commit failure must not leave a resumable bundle')

    def test_remote_tag_preflight_refuses_before_bump(self):
        self.sign()
        self.git('tag', 'v0.1.1')
        self.git('push', '-q', self.target['release_remote'], 'v0.1.1')
        self.git('tag', '-d', 'v0.1.1')
        result = self.run_release()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('already exists on remote', result.stderr)
        self.assertEqual('0.1\n', (self.repo / 'VERSION').read_text())
        self.assertFalse(any(c[0] in ('gradlew', 'pio') for c in self.calls()))

    def test_existing_github_release_preflight_refuses_before_bump(self):
        self.sign()
        self.gh_state.write_text(json.dumps({'v0.1.1': {'draft': True, 'title': 'x', 'notes': '', 'assets': {}}}))
        result = self.run_release()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('already exists', result.stderr)
        self.assertEqual('0.1\n', (self.repo / 'VERSION').read_text())
        self.assertFalse(any(c[0] in ('gradlew', 'pio') for c in self.calls()))

    def test_reports_survive_a_failure_after_clean(self):
        self.sign()
        result = self.run_release(fail_gradle=':app:assembleRelease')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('0.1\n', (self.repo / 'VERSION').read_text())
        failed = list((self.repo / '.release-staging').glob('failed-v0.1.1-*'))
        self.assertEqual(1, len(failed))
        self.assertEqual('unit report', (failed[0] / 'android-reports/reports/tests/testDebugUnitTest/index.html').read_text())
        # Not a pending bundle: the next release starts normally.
        self.assertEqual(0, self.run_release().returncode)

    # --- success ----------------------------------------------------------------------------------
    def test_success_stages_only_release_files_and_pushes_refs_atomically(self):
        self.sign()
        result = self.run_release()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('0.1.1', json.loads((self.repo / 'docs/manifest.json').read_text())['version'])
        calls = self.calls()
        add = next(c for c in calls if c[:2] == ['git', 'add'])
        self.assertEqual(['git', 'add', '--', 'VERSION', 'docs/manifest.json', 'docs/firmware/' + FIRMWARE], add)
        self.assertIn(['git', 'push', '--atomic', self.target['release_remote'], 'main', 'v0.1.1'], calls)
        gradle = next(c for c in calls if c[0] == 'gradlew' and ':app:testDebugUnitTest' in c)
        self.assertIn(':app:verifyPaparazziDebug', gradle)
        create = next(c for c in calls if c[:3] == ['gh', 'release', 'create'])
        self.assertEqual(['-R', self.target['repo_slug']], create[4:6])
        self.assertIn('--draft', create)
        self.assertIn('Fix one\nFix two', self.gh_releases()['v0.1.1']['notes'])
        self.assert_published()
        self.assertEqual('unit report',
                         (self.bundle() / 'android-reports/reports/tests/testDebugUnitTest/index.html').read_text())
        self.assertEqual('', self.git('status', '--porcelain'))

    def test_release_target_follows_brand_json_after_launch_switch(self):
        self.sign()
        launched = brand.apply_launch(brand.load(ROOT))
        if brand.derive(launched)['release_remote'] != self.target['release_remote']:
            self.git('remote', 'add', brand.derive(launched)['release_remote'], str(self.bare))
        self.set_brand(launched)
        self.env['RELEASE_TEST_REPO'] = self.target['repo_slug']
        self.git('commit', '-q', '--allow-empty', '-am', 'launch brand')
        self.git('push', '-q', self.target['release_remote'], 'main')
        result = self.run_release()
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls()
        self.assertIn(['git', 'push', '--atomic', self.target['release_remote'], 'main', 'v0.1.1'], calls)
        create = next(c for c in calls if c[:3] == ['gh', 'release', 'create'])
        self.assertEqual(['-R', self.target['repo_slug']], create[4:6])
        self.assertIn(self.target['repo_slug'], result.stdout)

    # --- failures after the commit and resume ------------------------------------------------------
    def test_failure_at_each_publication_step_resumes_same_version(self):
        for step, faults in (('tag', {'fail_git': 'tag'}), ('push', {'fail_git': 'push'}),
                             ('release create', {'fail_gh': 'create'}), ('asset upload', {'fail_gh': 'upload'}),
                             ('publish', {'fail_gh': 'edit'})):
            with self.subTest(step=step):
                if step != 'tag':
                    self.setUp()
                self.sign()
                first = self.run_release(**faults)
                self.assert_kept_for_resume(first)
                again = self.run_release()
                self.assertNotEqual(0, again.returncode)
                self.assertIn('resume', again.stderr)
                self.assertEqual('0.1.1\n', (self.repo / 'VERSION').read_text(), 'no double bump')
                self.assert_resume_publishes()
                self.assertEqual(1, len(list((self.repo / '.release-staging').glob('v*'))))

    def test_resume_after_partial_upload_uploads_only_the_missing_asset(self):
        self.sign()
        first = self.run_release(fail_gh='upload-partial')
        self.assert_kept_for_resume(first)
        release = self.gh_releases()['v0.1.1']
        self.assertTrue(release['draft'], 'a partially uploaded release must stay a draft')
        self.assertEqual(['Brucia-0.1.1.apk'], list(release['assets']))
        calls = self.assert_resume_publishes()
        uploads = [c for c in calls if c[:3] == ['gh', 'release', 'upload']]
        self.assertEqual(1, len(uploads))
        self.assertTrue(uploads[0][-1].endswith(FIRMWARE))
        self.assertFalse(any('Brucia-0.1.1.apk' in arg for arg in uploads[0]))
        self.assertTrue(any(c[:3] == ['gh', 'release', 'download'] and 'Brucia-0.1.1.apk' in c for c in calls))

    def test_resume_aborts_on_mismatching_existing_asset(self):
        for label, tampered in (('same size', b'evil signed ap'), ('other size', b'evil')):
            with self.subTest(label):
                if label != 'same size':
                    self.setUp()
                self.sign()
                self.assert_kept_for_resume(self.run_release(fail_gh='upload-partial'))
                blob = self.gh_store / 'v0.1.1-Brucia-0.1.1.apk'
                blob.write_bytes(tampered)
                state = self.gh_releases()
                state['v0.1.1']['assets']['Brucia-0.1.1.apk']['size'] = len(tampered)
                self.gh_state.write_text(json.dumps(state))
                result = self.run_release('resume')
                self.assertNotEqual(0, result.returncode)
                self.assertIn('refusing to overwrite', result.stderr)
                self.assertEqual(tampered, blob.read_bytes())
                calls = self.calls()
                self.assertFalse(any(c[:3] in (['gh', 'release', 'upload'], ['gh', 'release', 'edit'])
                                     for c in calls))
                self.assertTrue(self.gh_releases()['v0.1.1']['draft'])
                self.assertFalse((self.bundle() / 'published').exists())

    def test_resume_aborts_when_local_tag_moved(self):
        self.sign()
        self.assert_kept_for_resume(self.run_release(fail_git='push'))
        self.git('tag', '-f', 'v0.1.1', 'HEAD~1')
        result = self.run_release('resume')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Local tag v0.1.1 points at', result.stderr)
        self.assertIsNone(self.remote_tag())
        self.assertFalse(any(c[:2] == ['git', 'push'] or c[0] == 'gh' for c in self.calls()))

    def test_resume_aborts_when_remote_tag_differs(self):
        self.sign()
        self.assert_kept_for_resume(self.run_release(fail_git='push'))
        older = self.git('rev-parse', 'HEAD~1')
        subprocess.run([REAL_GIT, '--git-dir', str(self.bare), 'tag', 'v0.1.1', older], check=True, env=self.env)
        result = self.run_release('resume')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Remote tag v0.1.1 points at', result.stderr)
        self.assertEqual(older, self.remote_tag())
        self.assertFalse(any(c[:2] == ['git', 'push'] or c[0] == 'gh' for c in self.calls()))

    def test_resume_aborts_when_bundle_file_changed(self):
        self.sign()
        self.assert_kept_for_resume(self.run_release(fail_gh='create'))
        (self.bundle() / 'Brucia-0.1.1.apk').write_bytes(b'rebuilt apk')
        result = self.run_release('resume')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('changed since staging', result.stderr)
        self.assertNotIn('v0.1.1', self.gh_releases())

    def test_resume_without_pending_bundle_refuses(self):
        result = self.run_release('resume')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('No unpublished release bundle', result.stderr)


if __name__ == '__main__':
    unittest.main()
