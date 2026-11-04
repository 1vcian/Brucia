"""Host tests for tools/check_apk_identity.py (no SDK, Gradle or APK required)."""
import contextlib
import io
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_apk_identity as identity  # noqa: E402

BADGING = """package: name='app.brucia' versionCode='1000103' versionName='0.1.3' platformBuildVersionName='14' platformBuildVersionCode='34' compileSdkVersion='34' compileSdkVersionCodename='14'
sdkVersion:'26'
targetSdkVersion:'34'
uses-permission: name='android.permission.INTERNET'
application-label:'Brucia'
application: label='Brucia' icon='res/mipmap-anydpi-v26/ic_launcher.xml'
application-debuggable
launchable-activity: name='app.brucia.MainActivity'  label='' icon=''
"""
EXPECTED = {"package": "app.brucia", "versionName": "0.1.3", "versionCode": "1000103",
            "minSdk": "26", "targetSdk": "34"}


class VersionCodeTest(unittest.TestCase):
    def test_matches_gradle_formula(self):
        self.assertEqual(identity.version_code("0.1.3"), 1000103)
        self.assertEqual(identity.version_code("1.2.3\n"), 1010203)
        self.assertEqual(identity.version_code("0.1"), 1000100)
        self.assertEqual(identity.version_code("0.x.7"), 1000007)
        self.assertEqual(identity.version_code("1_0.0.0"), 1000000)

    def test_ordering_is_preserved_across_minor_and_patch(self):
        self.assertLess(identity.version_code("0.1.99"), identity.version_code("0.2.0"))
        self.assertLess(identity.version_code("0.99.99"), identity.version_code("1.0.0"))


class BadgingTest(unittest.TestCase):
    def test_parses_identity_fields(self):
        found = identity.parse_badging(BADGING)
        self.assertEqual({k: found[k] for k in EXPECTED}, EXPECTED)
        self.assertTrue(found["debuggable"])

    def test_release_without_debuggable(self):
        found = identity.parse_badging(BADGING.replace("application-debuggable\n", ""))
        self.assertFalse(found["debuggable"])
        self.assertEqual(identity.compare(found, EXPECTED, "release"), [])
        self.assertTrue(identity.compare(found, EXPECTED, "debug"))

    def test_reports_each_mismatch(self):
        found = identity.parse_badging(BADGING.replace("1000103", "103").replace("'34'\nuses", "'35'\nuses"))
        errors = identity.compare(found, EXPECTED, "release")
        self.assertEqual(len(errors), 3, errors)
        self.assertTrue(any(e.startswith("versionCode") for e in errors))
        self.assertTrue(any(e.startswith("targetSdk") for e in errors))
        self.assertTrue(any(e.startswith("debuggable") for e in errors))

    def test_missing_fields_fail(self):
        self.assertEqual(len(identity.compare(identity.parse_badging(""), EXPECTED)), 5)


class RepositoryExpectationTest(unittest.TestCase):
    def test_reads_real_repository(self):
        expected = identity.expected_identity()
        version = (identity.ROOT / "VERSION").read_text().strip()
        self.assertEqual(expected["package"], "app.brucia")
        self.assertEqual(expected["versionName"], version)
        self.assertEqual(expected["versionCode"], str(identity.version_code(version)))
        self.assertRegex(expected["minSdk"], r"^\d+$")
        self.assertRegex(expected["targetSdk"], r"^\d+$")

    def test_rejects_changed_application_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "android/app").mkdir(parents=True)
            (root / "VERSION").write_text("0.1.3\n")
            gradle = (identity.ROOT / "android/app/build.gradle.kts").read_text()
            (root / "android/app/build.gradle.kts").write_text(
                re.sub(r'applicationId = "[^"]+"', 'applicationId = "app.other"', gradle))
            with self.assertRaises(ValueError):
                identity.expected_identity(root)


class Aapt2DiscoveryTest(unittest.TestCase):
    def test_prefers_env_then_newest_build_tools(self):
        with tempfile.TemporaryDirectory() as tmp:
            sdk = Path(tmp)
            for version in ("9.0.0", "34.0.0", "35.0.0-rc1", "35.0.0"):
                (sdk / "build-tools" / version).mkdir(parents=True)
                (sdk / "build-tools" / version / "aapt2").write_text("")
            self.assertEqual(identity.find_aapt2(sdk, {"ANDROID_HOME": str(sdk)}),
                             sdk / "build-tools/35.0.0/aapt2")
            self.assertEqual(identity.find_aapt2(sdk, {"AAPT2": "/x/aapt2", "ANDROID_HOME": str(sdk)}),
                             Path("/x/aapt2"))

    def test_falls_back_to_local_properties(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sdk = root / "sdk"
            (sdk / "build-tools/34.0.0").mkdir(parents=True)
            (sdk / "build-tools/34.0.0/aapt2").write_text("")
            (root / "android").mkdir()
            (root / "android/local.properties").write_text(f"sdk.dir={sdk}\n")
            self.assertEqual(identity.find_aapt2(root, {}), sdk / "build-tools/34.0.0/aapt2")
            with self.assertRaises(SystemExit):
                identity.find_aapt2(Path(tmp) / "missing", {})


@unittest.skipIf(os.name == "nt", "shell shim")
class MainTest(unittest.TestCase):
    def run_main(self, badging, *args):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "badging.txt").write_text(badging)
            fake = tmp / "aapt2"
            fake.write_text(f"#!/bin/sh\ncat '{tmp / 'badging.txt'}'\n")
            fake.chmod(fake.stat().st_mode | stat.S_IXUSR)
            apk = tmp / "app.apk"
            apk.write_bytes(b"PK")
            out, err = io.StringIO(), io.StringIO()
            with mock.patch.object(identity, "expected_identity", return_value=EXPECTED), \
                    contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = identity.main(["--aapt2", str(fake), *args, str(apk)])
            return code, out.getvalue(), err.getvalue()

    def test_debug_apk_passes(self):
        code, out, _ = self.run_main(BADGING, "--build-type", "debug")
        self.assertEqual(code, 0)
        self.assertIn("app.brucia 0.1.3/1000103 minSdk 26 targetSdk 34 debuggable true", out)

    def test_debuggable_release_fails(self):
        code, _, err = self.run_main(BADGING, "--build-type", "release")
        self.assertEqual(code, 1)
        self.assertIn("release APK is marked application-debuggable", err)

    def test_missing_apk_fails(self):
        with mock.patch.object(identity, "expected_identity", return_value=EXPECTED), \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(identity.main(["--aapt2", "/bin/true", "/nonexistent/app.apk"]), 1)


if __name__ == "__main__":
    unittest.main()
