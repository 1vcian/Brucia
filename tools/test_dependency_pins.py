"""Guard dependency resolution policy without installing packages."""
import configparser
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class DependencyPinsTest(unittest.TestCase):
    def setUp(self):
        self.config = configparser.ConfigParser(interpolation=None, inline_comment_prefixes=(';',))
        self.config.read(ROOT / 'platformio.ini')

    def test_git_libraries_use_immutable_commits(self):
        checked = 0
        for section in ('env', 'env_light'):
            for line in self.config.get(section, 'lib_deps', fallback='').splitlines():
                if 'https://github.com/' in line:
                    self.assertRegex(line.strip(), r'#[a-f0-9]{40}$')
                    checked += 1
        self.assertGreater(checked, 15)

    def test_main_registry_dependencies_are_exact_except_builtin_framework_fs(self):
        for line in self.config.get('env', 'lib_deps').splitlines():
            line = line.strip()
            if not line or 'https://' in line or line == 'FFat':
                continue
            self.assertRegex(line, r'@\s*\d+\.\d+\.\d+$')

    def test_library_ignore_uses_names_not_version_specs(self):
        for section in self.config.sections():
            for line in self.config.get(section, 'lib_ignore', fallback='').splitlines():
                self.assertNotIn('@', line, 'Version specs do not belong in lib_ignore')


if __name__ == '__main__':
    unittest.main()
