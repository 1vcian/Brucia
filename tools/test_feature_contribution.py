import hashlib
import json
from pathlib import Path
import unittest

from fixture_recipe import run
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

    def test_catalog_downloads_and_recipe(self):
        catalog = FIXTURE.parents[2] / "docs/catalog"
        entries = json.loads((catalog / "catalog.json").read_bytes())["entries"]
        for entry in entries:
            data = (catalog / entry["file"]).read_bytes()
            self.assertEqual((len(data), hashlib.sha256(data).hexdigest()), (entry["bytes"], entry["sha256"]))
            self.assertIn(f'href="{entry["file"]}"', (catalog / "index.html").read_text())
            for key in ("format", "version"):
                self.assertEqual(json.loads(data)[key], entry[key])
        pack = (catalog / "synthetic.json").read_bytes()
        self.assertEqual(pack, FIXTURE.read_bytes())
        recipe = (catalog / "capture-summary.json").read_bytes()
        self.assertEqual(run(recipe, pack), {"packet-count": 1, "capture-bytes": 64,
                                             "capture-sha256": json.loads(pack)["sha256"]})
        for patch in [dict(steps=["shell"]), dict(steps=[]), dict(steps=["packet-count"]*2),
                      dict(input="org.brucia.fixture.pcap/2"), dict(version=True)]:
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                run(json.dumps(dict(json.loads(recipe), **patch)).encode(), pack)

    def test_generator(self):
        self.assertIn('"org.example.fixture", 101', generate("org.example.fixture", 101))
        for ident, tab in [('invalid id', 101), ('org.example.fixture', -1)]:
            with self.assertRaises(ValueError):
                generate(ident, tab)


if __name__ == "__main__":
    unittest.main()
