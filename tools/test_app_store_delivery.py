#!/usr/bin/env python3
"""Store delivery core on the host; TLS/device evidence is recorded separately in TESTS.md."""
import shutil
import json
import os
import ssl
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_app_store_install import HARNESS, ROOT, CORE, pinned_pems

DELIVERY = r'''
#include "app_store_delivery.h"
int main() {
    const std::string origin = "https://store.example";
    for (const std::string scheme : {"http://", "https://", "HTTP://"}) {
        auto r = storeRoute(scheme + "STORE.EXAMPLE/service/main/catalog.json", origin);
        CHECK(r.handled && r.error.empty());
        CHECK(r.url == origin + "/service/main/catalog.json");
    }
    for (const std::string authority : {"store.example:80", "store.example"}) {
        CHECK(storeRoute("http://" + authority + "/service/manual/a.js", origin).url == origin + "/service/manual/a.js");
    }
    CHECK(storeRoute(origin + ":443/service/main/a.json", origin).url == origin + "/service/main/a.json");
    for (const std::string url : {"http://store.example:8080/service/a", "http://user@store.example/service/a",
            "ftp://store.example/service/a", "https://store.example/other", "https://store.example/service/a#fragment",
            "http://store.example/service/a\r\nInjected: yes"}) {
        auto r = storeRoute(url, origin); CHECK(r.handled && !r.error.empty() && r.url.empty());
    }
    for (const std::string url : {"http://lab.example/data", "http://store.example.evil/service/a",
            "http://store.example@evil.example/service/a"}) CHECK(!storeRoute(url, origin).handled);

    for (const std::string path : {"/BruceJS/Tools/App Store.js", "/BruceJS/Games/demo.bin", "/Themes/Blue/icon.bmp"}) CHECK(storeDestination(path));
    for (const std::string path : {"/bruce.conf", "/BruceJS/../bruce.conf", "/BruceJS/Tools/../../x", "/BruceJS//x",
            "/BruceJS/Tools/x.part", "/BruceJS/Tools/x.BAK", "/BruceJS/Tools/x.", "/Themes/Blue/", "/BruceJS/./a",
            "/BruceJS/a\\b", "/BruceJS/a\nb", "/BruceJS/a:b", "/BruceJS/a "}) CHECK(!storeDestination(path));
    CHECK(!storeDestination("/BruceJS/" + std::string(250, 'a')));
    const std::string catalog = "{\"apps\":[{\"name\":\"demo\"}]}";
    { FakeBody b(catalog, catalog.size()); b.chunk = 1; std::string out;
      CHECK(readCatalog(b, gClock, out).empty()); CHECK(out == catalog); }
    for (long length : {-1L, 0L, MAX_CATALOG_BYTES + 1}) {
        FakeBody b(catalog, length); std::string out = "previous";
        CHECK(!readCatalog(b, gClock, out).empty()); CHECK(out.empty() && b.pos == 0);
    }
    { FakeBody b(catalog, catalog.size(), 4); std::string out;
      CHECK(!readCatalog(b, gClock, out).empty()); CHECK(out.empty()); }
    { FakeBody b(catalog, catalog.size(), -1, 4); b.chunk = 1; std::string out;
      CHECK(!readCatalog(b, gClock, out).empty()); CHECK(out.empty()); }
    { FakeBody b(catalog, catalog.size()); b.chunk = 1; b.perReadMs = 10000; std::string out;
      const uint32_t start = gClock.t; CHECK(!readCatalog(b, gClock, out).empty());
      CHECK(out.empty() && gClock.t - start <= DOWNLOAD_BUDGET_MS); }
    { FakeBody b(catalog, catalog.size()); b.perReadMs = DOWNLOAD_BUDGET_MS; std::string out;
      CHECK(!readCatalog(b, gClock, out).empty()); CHECK(out.empty()); }
    { std::string large(MAX_CATALOG_BYTES, 'x'); FakeBody b(large, large.size()); std::string out;
      CHECK(readCatalog(b, gClock, out).empty()); CHECK(out == large); }

    // Same real installer as bootstrap, with the explicit downstream asset budget. Binary bytes
    // including NUL survive; every failure preserves an old file (and works without one too).
    const std::string asset = std::string("\0\1\2\xff", 4) + std::string(70000, 'x');
    for (const char *old : {(const char *)nullptr, "old"}) {
      for (int fault = 0; fault < 7; ++fault) {
        FakeFs fs; if (old) fs.files[DEST] = old;
        FakeBody b(asset, asset.size());
        if (fault == 1) b.cutAt = 60000;
        if (fault == 2) fs.capacity = 1024;
        if (fault == 3) fs.flushFails = true;
        if (fault == 4) fs.lostOnFlush = true;
        if (fault == 5) fs.failRename.insert({PART, DEST});
        if (fault == 6) b.len = MAX_ASSET_BYTES + 1;
        const auto error = app_store::install(b, fs, DEST, gClock, DOWNLOAD_BUDGET_MS, MAX_ASSET_BYTES);
        if (!fault) { CHECK(error.empty() && fs.files[DEST] == asset); }
        else { CHECK(!error.empty()); CHECK(clean(fs, old)); }
      }
    }
    // Bootstrap keeps its stricter limit, independently of the downstream path.
    { FakeFs fs; FakeBody b(asset, asset.size()); CHECK(!install(b, fs, DEST).empty()); CHECK(b.pos == 0); }
    puts("DELIVERY OK");
}
'''


class StoreDeliveryTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get("BRUCIA_NET_TESTS") == "1", "set BRUCIA_NET_TESTS=1 for live store data")
    def test_live_catalog_metadata_and_benign_asset_are_complete_over_https(self):
        # Read data only. No installation or execution of downloaded JavaScript.
        from urllib.request import HTTPSHandler, HTTPRedirectHandler, Request, build_opener
        from urllib.parse import quote

        class NoRedirect(HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs):
                return None

        origin = json.loads((ROOT / "brand.json").read_text())["upstream"]["app_store_origin"]
        opener = build_opener(HTTPSHandler(context=ssl.create_default_context(cadata=pinned_pems()[1])), NoRedirect())

        def get(path, limit):
            with opener.open(Request(origin + path, headers={"Accept-Encoding": "identity", "User-Agent": "ESP32HTTPClient"}), timeout=20) as reply:
                self.assertEqual(200, reply.status)
                size = int(reply.headers["Content-Length"])
                self.assertGreater(size, 0)
                self.assertLessEqual(size, limit)
                body = reply.read(limit + 1)
                self.assertEqual(size, len(body))
                return body

        catalog = json.loads(get("/service/main/releases/categories.json", 256 * 1024))
        self.assertIn("categories", catalog)
        metadata = json.loads(get("/service/main/repositories/emericklaw/Bruce-Device-Info-App/Device%20Info/metadata.json", 256 * 1024))
        file = metadata["files"][0]
        path = (metadata["path"] + file).lstrip("/")
        url = "/service/manual/{}/{}/{}/{}".format(metadata["owner"], metadata["repo"], metadata["commit"], quote(path))
        body = get(url, 2 * 1024 * 1024)
        self.assertFalse(body.lstrip().startswith(b"<"))

    def compile_run(self, directory, source):
        core = directory / "delivery.cpp"
        core.write_text(source)
        harness = directory / "harness.cpp"
        harness.write_text(HARNESS[:HARNESS.index("int main(")] + DELIVERY)
        binary = directory / "test"
        compile_result = subprocess.run([
            shutil.which("c++"), "-std=c++17", "-I", str(CORE), str(harness),
            str(core), str(CORE / "app_store_install.cpp"), "-o", str(binary),
        ], capture_output=True, text=True, timeout=60)
        self.assertEqual(0, compile_result.returncode, compile_result.stderr)
        return subprocess.run([str(binary)], capture_output=True, text=True, timeout=30)

    def test_routes_catalogs_and_transactional_assets(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self.compile_run(Path(tmp), (CORE / "app_store_delivery.cpp").read_text())
            self.assertEqual(0, run.returncode, run.stdout + run.stderr)
            self.assertIn("DELIVERY OK", run.stdout)

    def test_regressions_detect_http_downgrade_and_missing_read_budget(self):
        original = (CORE / "app_store_delivery.cpp").read_text()
        for before, after in [
            ("result.url = httpsOrigin + path;", "result.url = input;"),
            ("clock.nowMs() - start >= DOWNLOAD_BUDGET_MS", "false"),
        ]:
            self.assertIn(before, original)
            with self.subTest(mutation=before), tempfile.TemporaryDirectory() as tmp:
                run = self.compile_run(Path(tmp), original.replace(before, after))
                self.assertNotEqual(0, run.returncode, "mutation survived")

    def test_interpreter_routes_before_generic_network_and_checks_before_success(self):
        wifi = (ROOT / "src/modules/bjs_interpreter/wifi_js.cpp").read_text()
        adapter = (ROOT / "src/modules/bjs_interpreter/app_store_js.cpp").read_text()
        self.assertLess(wifi.index("if (store.handled) return storeHttpFetch"), wifi.index("http.begin(url)"))
        self.assertIn("tls.setCACert(BRUCIA_APP_STORE_ROOTS)", adapter)
        self.assertNotIn("setInsecure", adapter)
        self.assertIn("HTTPC_DISABLE_FOLLOW_REDIRECTS", adapter)
        self.assertLess(adapter.index("checkPeerCertificateDates(tls)"), adapter.index("app_store::HttpBody body(http)"))
        self.assertIn("app_store::install(body, files", adapter)
        self.assertIn("app_store::readCatalog(body, clock", adapter)
        self.assertLess(adapter.index("if (!error.empty()) return JS_ThrowInternalError"), adapter.index('"status", JS_NewInt32(ctx, 200)'))
        self.assertIn("JS_EVAL_JSON", adapter)
        self.assertNotIn("native_storageWrite", adapter)


if __name__ == "__main__":
    unittest.main()
