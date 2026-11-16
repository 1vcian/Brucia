"""CI selection must retain every real board, including newly configured ones."""
import unittest

from firmware_matrix import supported_environments
from test_dependency_pins import load_config


class FirmwareMatrixTest(unittest.TestCase):
    def test_only_the_exact_template_is_excluded_and_new_boards_are_kept(self):
        self.assertEqual(
            ['NewDeviceModel-v2', 'future-board', 'lilygo-t-embed-cc1101'],
            supported_environments(['future-board', 'NewDeviceModel',
                                    'lilygo-t-embed-cc1101', 'NewDeviceModel-v2', 'future-board']))

    def test_empty_selection_and_unsafe_names_fail_instead_of_running_a_bad_matrix(self):
        for names in ([], ['NewDeviceModel'], ['board;echo'], ['board with spaces']):
            with self.subTest(names=names), self.assertRaises(ValueError):
                supported_environments(names)

    def test_repository_selection_preserves_every_configured_non_template_env(self):
        configured = {s.removeprefix('env:') for s in load_config().sections() if s.startswith('env:')}
        self.assertIn('NewDeviceModel', configured)
        selected = set(supported_environments(configured))
        self.assertEqual({'NewDeviceModel'}, configured - selected)
        self.assertTrue({'lilygo-t-embed-cc1101', 'm5stack-cardputer', 'm5stack-core4mb'} <= selected)


if __name__ == '__main__':
    unittest.main()
