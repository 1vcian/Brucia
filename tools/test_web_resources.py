import gzip
from pathlib import Path
import re
import tempfile
import unittest
from web_resources import embed_web


class WebResourcesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / "web"
        self.source.mkdir()
        self.header = self.root / "include" / "webFiles.h"
        self.checksum = self.source / "checksum.sha256"
        self.raw = b'<pre> keep  spaces\n</pre><script>const x = `a  b\nc`;</script>'
        (self.source / "index.html").write_bytes(self.raw)

    def embed(self):
        return embed_web(self.source, self.header, self.checksum)

    def test_preserves_exact_source_bytes(self):
        self.assertTrue(self.embed())
        body = self.header.read_text().split("PROGMEM = {", 1)[1].split("};", 1)[0]
        compressed = bytes(int(x, 16) for x in re.findall(r"0x([0-9A-F]{2})", body))
        self.assertEqual(self.raw, gzip.decompress(compressed))
        self.assertEqual(b"\0\0\0\0", compressed[4:8])

    def test_missing_header_rebuilds_even_with_valid_checksum(self):
        self.embed()
        original = self.header.read_bytes()
        self.header.unlink()
        self.assertTrue(self.embed())
        self.assertEqual(original, self.header.read_bytes())
        self.assertFalse(self.embed())

    def test_changed_source_and_added_resource_invalidate_cache(self):
        self.embed()
        original = self.header.read_bytes()
        (self.source / "index.html").write_bytes(self.raw + b"new")
        self.assertTrue(self.embed())
        self.assertNotEqual(original, self.header.read_bytes())
        (self.source / "index.js").write_text("const a = 1;")
        self.assertTrue(self.embed())
        self.assertIn("index_js[]", self.header.read_text())

    def test_source_order_is_deterministic(self):
        (self.source / "z.js").write_text("const z = 1;")
        (self.source / "a.css").write_text("body { color: red; }")
        self.embed()
        original = self.header.read_bytes()
        original_checksum = self.checksum.read_bytes()
        self.header.unlink()
        self.checksum.unlink()
        self.embed()
        self.assertEqual(original, self.header.read_bytes())
        self.assertEqual(original_checksum, self.checksum.read_bytes())


if __name__ == "__main__":
    unittest.main()
