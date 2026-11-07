#!/usr/bin/env python3
"""F35: App Store installer trust and complete-file install.

Compiles the real src/core/app_store_install.cpp against fake download/storage faults
(interrupted, empty, oversized, unsized, HTML, full storage, failed flush/rename), checks the
device adapter source (pinned CA, never setInsecure) and the pinned roots themselves.

Limits: the TLS checks use Python's ssl with the pinned roots, not mbedtls on the device; the
live check against the real store host runs only with BRUCIA_NET_TESTS=1.
"""
import hashlib
import os
import re
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "src/core"
ROOTS_H = CORE / "app_store_roots.h"
SETTINGS = CORE / "settings.cpp"

HARNESS = r'''
#include "app_store_install.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <map>
#include <set>
#include <string>
using namespace app_store;

struct FakeBody : Body {
    long len; std::string data; long cutAt; long errAt;  // cutAt: end of stream early; errAt: timeout
    size_t pos = 0;
    FakeBody(std::string d, long l, long cut = -1, long err = -1) : len(l), data(d), cutAt(cut), errAt(err) {}
    long length() override { return len; }
    long read(uint8_t *b, size_t cap) override {
        if (errAt >= 0 && (long)pos >= errAt) return -1;
        if (cutAt >= 0 && (long)pos >= cutAt) return 0;
        size_t n = std::min(cap, data.size() - pos);
        if (cutAt >= 0) n = std::min(n, (size_t)(cutAt - pos));
        if (errAt >= 0) n = std::min(n, (size_t)(errAt - pos));
        if (n == 0) return 0;
        memcpy(b, data.data() + pos, n); pos += n; return (long)n;
    }
};

struct FakeFs : Files {
    std::map<std::string, std::string> files;
    std::string open; bool isOpen = false;
    long capacity = 1 << 30;         // bytes the storage accepts in total
    bool flushFails = false, sizeLies = false;
    std::set<std::pair<std::string, std::string>> failRename;
    long used() const { long n = 0; for (auto &f : files) n += (long)f.second.size(); return n; }
    bool exists(const std::string &p) override { return files.count(p) > 0; }
    bool remove(const std::string &p) override { return files.erase(p) > 0; }
    bool rename(const std::string &a, const std::string &b) override {
        if (failRename.count({a, b}) || !files.count(a) || files.count(b)) return false;  // FAT: no overwrite
        files[b] = files[a]; files.erase(a); return true;
    }
    bool beginWrite(const std::string &p) override { assert(!isOpen); files[p] = ""; open = p; isOpen = true; return true; }
    size_t write(const uint8_t *d, size_t n) override {
        assert(isOpen);
        long room = capacity - used(); if (room < 0) room = 0;
        size_t w = std::min(n, (size_t)room);
        files[open].append((const char *)d, w); return w;
    }
    bool endWrite() override { assert(isOpen); isOpen = false; return !flushFails; }
    long size(const std::string &p) override { if (!files.count(p)) return -1; return (long)files[p].size() + (sizeLies ? 1 : 0); }
};

static const std::string DEST = "/BruceJS/Tools/App Store.js";
static const std::string PART = DEST + ".part", BAK = DEST + ".bak";
static std::string script(size_t n) { std::string s = "function e(){}"; while (s.size() < n) s += "x"; s.resize(n); return s; }

#define CHECK(c) do { if (!(c)) { printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)
#define CASE(name) printf("case %s\n", name)

// After any failure: no staging file, previous install (if any) untouched, never left open.
static bool clean(FakeFs &fs, const char *old) {
    if (fs.exists(PART) || fs.isOpen) return false;
    if (old) return fs.exists(DEST) && fs.files[DEST] == old && !fs.exists(BAK);
    return !fs.exists(DEST);
}

int main() {
    const std::string s = script(10821);
    { CASE("fresh install");
      FakeFs fs; FakeBody b(s, (long)s.size());
      CHECK(install(b, fs, DEST) == ""); CHECK(fs.files[DEST] == s); CHECK(!fs.exists(PART) && !fs.exists(BAK)); }
    { CASE("update replaces previous, backup removed");
      FakeFs fs; fs.files[DEST] = "old"; FakeBody b(s, (long)s.size());
      CHECK(install(b, fs, DEST) == ""); CHECK(fs.files[DEST] == s); CHECK(!fs.exists(BAK)); }
    for (const char *old : {(const char *)nullptr, "old"}) {
      { CASE("interrupted: stream ends early"); FakeFs fs; if (old) fs.files[DEST] = old; FakeBody b(s, (long)s.size(), 4000);
        CHECK(install(b, fs, DEST) == "download interrupted"); CHECK(clean(fs, old)); }
      { CASE("interrupted: read timeout"); FakeFs fs; if (old) fs.files[DEST] = old; FakeBody b(s, (long)s.size(), -1, 512);
        CHECK(install(b, fs, DEST) == "download interrupted"); CHECK(clean(fs, old)); }
      { CASE("no content-length"); FakeFs fs; if (old) fs.files[DEST] = old; FakeBody b(s, -1);
        CHECK(install(b, fs, DEST) != ""); CHECK(clean(fs, old)); }
      { CASE("empty"); FakeFs fs; if (old) fs.files[DEST] = old; FakeBody b("", 0);
        CHECK(install(b, fs, DEST) == "empty download"); CHECK(clean(fs, old)); }
      { CASE("blank body"); FakeFs fs; if (old) fs.files[DEST] = old; FakeBody b("   \n", 4);
        CHECK(install(b, fs, DEST) == "download is blank"); CHECK(clean(fs, old)); }
      { CASE("oversized refused before writing"); FakeFs fs; if (old) fs.files[DEST] = old;
        std::string big = script(MAX_SCRIPT_BYTES + 1); FakeBody b(big, (long)big.size());
        CHECK(install(b, fs, DEST) == "download too large"); CHECK(clean(fs, old)); CHECK(b.pos == 0); }
      { CASE("html error page"); FakeFs fs; if (old) fs.files[DEST] = old; std::string h = "\n <html>nope</html>"; FakeBody b(h, (long)h.size());
        CHECK(install(b, fs, DEST) == "server sent a web page, not a script"); CHECK(clean(fs, old)); }
      { CASE("storage full mid-write"); FakeFs fs; if (old) fs.files[DEST] = old; fs.capacity = fs.used() + 3000; FakeBody b(s, (long)s.size());
        CHECK(install(b, fs, DEST) == "storage write failed (full?)"); CHECK(clean(fs, old)); }
      { CASE("storage full: zero bytes accepted"); FakeFs fs; if (old) fs.files[DEST] = old; fs.capacity = fs.used(); FakeBody b(s, (long)s.size());
        CHECK(install(b, fs, DEST) == "storage write failed (full?)"); CHECK(clean(fs, old)); }
      { CASE("flush fails"); FakeFs fs; if (old) fs.files[DEST] = old; fs.flushFails = true; FakeBody b(s, (long)s.size());
        CHECK(install(b, fs, DEST) != ""); CHECK(clean(fs, old)); }
      { CASE("size re-read mismatch"); FakeFs fs; if (old) fs.files[DEST] = old; fs.sizeLies = true; FakeBody b(s, (long)s.size());
        CHECK(install(b, fs, DEST) == "written size does not match download"); CHECK(clean(fs, old)); }
      { CASE("final rename fails"); FakeFs fs; if (old) fs.files[DEST] = old; fs.failRename.insert({PART, DEST}); FakeBody b(s, (long)s.size());
        CHECK(install(b, fs, DEST) == "cannot install downloaded script"); CHECK(clean(fs, old)); }
    }
    { CASE("previous cannot be moved aside"); FakeFs fs; fs.files[DEST] = "old"; fs.failRename.insert({DEST, BAK}); FakeBody b(s, (long)s.size());
      CHECK(install(b, fs, DEST) == "cannot move previous install aside"); CHECK(clean(fs, "old")); }
    { CASE("stale staging file is replaced"); FakeFs fs; fs.files[PART] = "junk"; FakeBody b(s, (long)s.size());
      CHECK(install(b, fs, DEST) == ""); CHECK(fs.files[DEST] == s && !fs.exists(PART)); }
    { CASE("crash between renames: backup restored, then a failed retry keeps it");
      FakeFs fs; fs.files[BAK] = "old"; FakeBody b(s, (long)s.size(), 100);
      CHECK(install(b, fs, DEST) == "download interrupted"); CHECK(clean(fs, "old")); }
    puts("ALL OK");
    return 0;
}
'''


def compiler():
    return shutil.which("c++") or shutil.which("clang++") or shutil.which("g++")


class InstallLogicTest(unittest.TestCase):
    @unittest.skipUnless(compiler(), "no host C++ compiler")
    def test_faults_never_report_success_or_damage_previous_install(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "harness.cpp"
            exe = Path(tmp) / "harness"
            src.write_text(HARNESS)
            build = subprocess.run([compiler(), "-std=c++17", "-Wall", "-Werror", "-I", str(CORE), str(src),
                                    str(CORE / "app_store_install.cpp"), "-o", str(exe)], capture_output=True, text=True)
            self.assertEqual(0, build.returncode, build.stderr)
            run = subprocess.run([str(exe)], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, run.returncode, run.stdout + run.stderr)
            self.assertIn("ALL OK", run.stdout)


class AdapterSourceTest(unittest.TestCase):
    def setUp(self):
        text = SETTINGS.read_text()
        self.fn = text[text.index("void installAppStoreJS() {"):]
        self.fn = self.fn[:self.fn.index("\n}\n")]
        self.file = text

    def test_download_is_authenticated_with_the_pinned_roots(self):
        self.assertIn("tls.setCACert(BRUCIA_APP_STORE_ROOTS)", self.fn)
        self.assertIn("http.begin(tls, BRUCIA_UPSTREAM_APP_STORE_URL)", self.fn)
        self.assertIsNone(re.search(r"\.setInsecure\s*\(", self.file))
        # One-argument begin(url) is the path that ends in setInsecure() in the Arduino core.
        self.assertIsNone(re.search(r"http\.begin\(\s*BRUCIA_UPSTREAM_APP_STORE_URL\s*\)", self.fn))

    def test_install_goes_through_the_checked_path(self):
        self.assertIn("app_store::install(body, files, \"/BruceJS/Tools/App Store.js\")", self.fn)
        self.assertIn("http.useHTTP10(true)", self.fn)
        self.assertNotIn("getString()", self.fn)
        self.assertNotIn("file.print", self.fn)
        ok = self.fn.index("displaySuccess(\"Bruce App Store installed\"")
        self.assertLess(self.fn.index("if (!err.empty())"), ok)


def pinned_pems():
    text = ROOTS_H.read_text()
    body = text[text.index('R"PEM(') + 6:text.index(')PEM"')]
    return re.findall(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", body, re.S), body


class PinnedRootsTest(unittest.TestCase):
    def test_every_pinned_root_matches_its_documented_fingerprint(self):
        pems, _ = pinned_pems()
        documented = re.findall(r"^// (.+): ([0-9a-f]{64})$", ROOTS_H.read_text(), re.M)
        self.assertEqual(9, len(pems))
        self.assertEqual(len(documented), len(pems))
        for pem, (name, fp) in zip(pems, documented):
            self.assertEqual(fp, hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest(), name)

    @unittest.skipUnless(shutil.which("openssl"), "openssl not installed")
    def test_server_with_untrusted_certificate_is_rejected(self):
        _, bundle = pinned_pems()
        with tempfile.TemporaryDirectory() as tmp:
            key, crt, ca = (Path(tmp) / n for n in ("k.pem", "c.pem", "ca.pem"))
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                            "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
                            "-keyout", str(key), "-out", str(crt)], check=True, capture_output=True)
            ca.write_text(bundle)
            server_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            server_ctx.load_cert_chain(str(crt), str(key))
            listener = socket.socket()
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            port = listener.getsockname()[1]

            def serve():
                try:
                    conn, _ = listener.accept()
                    with server_ctx.wrap_socket(conn, server_side=True):
                        pass
                except (ssl.SSLError, OSError):
                    pass
            t = threading.Thread(target=serve, daemon=True)
            t.start()
            client_ctx = ssl.create_default_context(cafile=str(ca))
            with self.assertRaises(ssl.SSLCertVerificationError):
                with socket.create_connection(("127.0.0.1", port), timeout=5) as sock:
                    with client_ctx.wrap_socket(sock, server_hostname="localhost"):
                        pass
            listener.close()
            t.join(5)

    @unittest.skipUnless(os.environ.get("BRUCIA_NET_TESTS") == "1", "set BRUCIA_NET_TESTS=1 for live TLS checks")
    def test_live_store_host_validates_only_against_the_pinned_roots(self):
        _, bundle = pinned_pems()
        ctx = ssl.create_default_context(cadata=bundle)
        host = "ghp.iceis.co.uk"
        with socket.create_connection((host, 443), timeout=15) as sock:
            with ctx.wrap_socket(sock, server_hostname=host) as tls:
                self.assertTrue(tls.getpeercert())
        for bad in ("self-signed.badssl.com", "expired.badssl.com", "wrong.host.badssl.com", "untrusted-root.badssl.com"):
            with self.assertRaises(ssl.SSLError, msg=bad):
                with socket.create_connection((bad, 443), timeout=15) as sock:
                    with ctx.wrap_socket(sock, server_hostname=bad):
                        pass


if __name__ == "__main__":
    unittest.main()
