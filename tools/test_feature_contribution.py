import json
from pathlib import Path
import unittest

from new_feature import generate
from pcap_pack import MAX_PACK, export_pack, import_pack

FIXTURE = Path(__file__).resolve().parents[1] / "examples/feature-v1/synthetic.json"


class ContributionTest(unittest.TestCase):
    def test_fixture_round_trip(self):
        raw = FIXTURE.read_bytes()
        data = import_pack(raw)
        self.assertEqual(len(data), 64)
        meta = json.loads(raw)
        self.assertEqual(export_pack(data, meta["source"], meta["license"]), raw)

    def test_invalid_packs(self):
        meta = json.loads(FIXTURE.read_bytes())
        for patch in [dict(version=True), dict(version=2), dict(capture="!"), dict(source=""),
                      dict(sha256="0"*64), dict(script="unused")]:
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                import_pack(json.dumps(dict(meta, **patch)).encode())
        with self.assertRaises(ValueError):
            import_pack(b" " * (MAX_PACK + 1))
        with self.assertRaises(ValueError):
            import_pack(FIXTURE.read_bytes().replace(b'"version":', b'"version":1,"version":'))
        with self.assertRaises(ValueError):
            export_pack(import_pack(FIXTURE.read_bytes())[:-1], "fixture", "CC0")

    def test_generator(self):
        self.assertIn('"org.example.fixture", 101', generate("org.example.fixture", 101))
        for ident, tab in [('invalid id', 101), ('org.example.fixture', -1)]:
            with self.assertRaises(ValueError):
                generate(ident, tab)


if __name__ == "__main__":
    unittest.main()
