"""The Android CI and release gates that must not silently disappear (backlog F44).

Paparazzi 2.0.0-alpha05.1 compares screenshots with the committed baselines only when the verify
task sets paparazzi.test.verify; a plain testDebugUnitTest renders and passes whatever changed.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = (ROOT / '.github/workflows/build.yml').read_text()
RELEASE = (ROOT / 'release.sh').read_text()


def job(name: str) -> str:
    match = re.search(rf'^  {re.escape(name)}:\n(.*?)(?=^  \S|\Z)', WORKFLOW, re.M | re.S)
    if not match:
        raise AssertionError(f'job {name} missing from build.yml')
    return match.group(1)


class CiGatesTest(unittest.TestCase):
    def test_android_job_verifies_screenshots_against_baselines(self):
        self.assertRegex(job('android'), r'gradlew [^\n]*:app:verifyPaparazziDebug')

    def test_release_checks_verify_screenshots(self):
        self.assertRegex(RELEASE, r'gradlew [^\n]*:app:verifyPaparazziDebug')

    def test_instrumented_job_runs_connected_tests_on_an_emulator_and_keeps_reports(self):
        body = job('android-instrumented')
        self.assertIn('reactivecircus/android-emulator-runner@', body)
        self.assertIn(':app:connectedDebugAndroidTest', body)
        self.assertIn('android/app/build/reports/androidTests/', body)
        self.assertIn('logcat', body)
        self.assertRegex(body, r'if: always\(\)\n\s+uses: actions/upload-artifact@')

    def test_every_action_is_pinned_to_a_full_commit(self):
        for line in WORKFLOW.splitlines():
            match = re.search(r'uses:\s*(\S+)', line)
            if match and not match.group(1).startswith('./'):
                with self.subTest(action=match.group(1)):
                    self.assertRegex(match.group(1), r'@[0-9a-f]{40}$')


if __name__ == '__main__':
    unittest.main()
