#!/usr/bin/env python3
"""Android backups never carry the app's secrets.

android:allowBackup stays on (saved items, macros, settings survive a phone change), so the
manifest must point both backup schemes at rules that exclude:
- the MITM CA keystore directory (mitm/MitmCa.kt: File(filesDir, "$DIR/...")), whose private key
  could intercept any device that trusts the CA if a backup copy leaked;
- the WiGLE API credentials (getSharedPreferences("wigle") in WardriveViewModel.kt).
The names are read from the sources so a rename cannot silently drop an exclusion.
Run: python3 tools/test_android_backup_rules.py
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "android/app/src/main"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def secret_excludes():
    mitm = re.search(r'private const val DIR = "([^"]+)"', (APP / "java/app/brucia/mitm/MitmCa.kt").read_text()).group(1)
    vm = (APP / "java/app/brucia/wardrive/WardriveViewModel.kt").read_text()
    assert 'getSharedPreferences("wigle"' in vm
    assert 'wiglePrefs.getString("api_token"' in vm
    return {("file", mitm + "/"), ("sharedpref", "wigle.xml")}


def excludes(element):
    return {(e.get("domain"), e.get("path")) for e in element.iter("exclude")}


class BackupRulesTest(unittest.TestCase):
    def setUp(self):
        app = ET.parse(APP / "AndroidManifest.xml").getroot().find("application")
        self.attrs = {k.replace(ANDROID, ""): v for k, v in app.attrib.items()}

    def rules(self, attr):
        ref = self.attrs.get(attr)
        self.assertIsNotNone(ref, attr)
        self.assertTrue(ref.startswith("@xml/"), ref)
        return ET.parse(APP / "res/xml" / (ref[5:] + ".xml")).getroot()

    def test_legacy_full_backup_excludes_secrets(self):
        root = self.rules("fullBackupContent")
        self.assertEqual("full-backup-content", root.tag)
        self.assertTrue(secret_excludes() <= excludes(root))
        self.assertEqual([], [e for e in root.iter("include")], "an include list would drop everything else")

    def test_android12_cloud_backup_and_transfer_exclude_secrets(self):
        root = self.rules("dataExtractionRules")
        for section in ("cloud-backup", "device-transfer"):
            node = root.find(section)
            self.assertIsNotNone(node, section)
            self.assertTrue(secret_excludes() <= excludes(node), section)
            self.assertEqual([], [e for e in node.iter("include")], section)


if __name__ == "__main__":
    unittest.main(verbosity=2)
