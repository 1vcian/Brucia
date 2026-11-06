"""brand.json is the single source of Brucia's public links (see docs/LAUNCH.md).

Checks: derived values and validation, generated/managed files in sync, the BruciaDev -> Brucia
switch end to end on a copy, firmware brand-macro hygiene, and a scanner that fails when a
Brucia repo/site link or a Bruce upstream link appears outside the central config and the
reasoned allowlist below.
"""
import fnmatch
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

import brand

ROOT = brand.ROOT

# Files allowed to contain hardcoded links, by kind ("own" = Brucia repo/site, "upstream" = Bruce),
# with the reason. Managed files (brand.MANAGED_GLOBS) may contain own links only while in sync.
ALLOWLIST = [
    ("brand.json", {"own", "upstream"}, "the central config itself"),
    ("docs/LAUNCH.md", {"own", "upstream"}, "documents the BruciaDev -> Brucia switch, both names on purpose"),
    ("CHANGELOG.md", {"own", "upstream"}, "historical release records and published tag links; upstream credit"),
    ("TESTS.md", {"own"}, "evidence log quoting what devices displayed at the time"),
    ("skills/brucia-maintenance/references/*.md", {"own"}, "task history and release evidence"),
    ("NOTICE", {"upstream"}, "AGPL attribution to Bruce"),
    ("THIRD_PARTY.md", {"upstream"}, "third-party/upstream component credits (mquickjs, BruceHAL)"),
    ("README.md", {"upstream"}, "credit: Brucia started from Bruce; link to the official Bruce app"),
    ("CONTRIBUTING.md", {"upstream"}, "credit to Bruce"),
    ("SECURITY.md", {"upstream"}, "credit to Bruce"),
    ("ARCHITECTURE.md", {"upstream"}, "upstream JS interpreter reference (same JS API)"),
    ("wiki/*.md", {"upstream"}, "credit to Bruce and its official app"),
    ("docs/index.html", {"upstream"}, "footer credit to Bruce and its official app"),
    ("docs/wiki/index.html", {"upstream"}, "sidebar credit link to Bruce"),
    ("embedded_resources/web_interface/index.html", {"upstream"},
     "WebUI info credit and Bruce's WebUI customization guide (same /BruceWebUI mechanism)"),
    ("platformio.ini", {"upstream"}, "mquickjs library dependency hosted by the upstream organisation"),
    ("tools/test_dependency_pins.py", {"upstream"}, "fixture for the mquickjs dependency pin"),
    ("lib/HAL/library.json", {"upstream"}, "BruceHAL library metadata (upstream library identity)"),
    ("boards/_boards_json/*.json", {"upstream"}, "inherited PlatformIO board metadata (vendor url field)"),
    ("boards/lilygo-t-embed-cc1101/pins_arduino.h", {"upstream"}, "comment citing an upstream issue"),
    ("pre_build_current_year.py", {"upstream"}, "provenance comment for inherited code"),
    ("pcbs/*", {"upstream"}, "upstream PCB documentation with upstream-hosted images"),
    ("sd_files/*", {"upstream"}, "inherited SD-card content: Bruce theme builder, example JS bookmarks"),
]

SKIP_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".bin", ".pdf", ".zip", ".gz",
                 ".ttf", ".otf", ".woff", ".woff2", ".stl", ".step", ".kicad_pcb", ".jar", ".apk"}


def tracked_files(root):
    try:
        out = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                             check=True, capture_output=True).stdout
        names = sorted({n for n in out.decode("utf-8").split("\0") if n})
    except (OSError, subprocess.CalledProcessError):
        names = sorted(str(p.relative_to(root)) for p in root.rglob("*")
                       if p.is_file() and not any(part in {".git", "build", ".pio", ".gradle", "node_modules"}
                                                  for part in p.relative_to(root).parts))
    return [n for n in names if (root / n).is_file() and Path(n).suffix.lower() not in SKIP_SUFFIXES]


def link_patterns(raw):
    d = brand.derive(raw)
    owner = re.escape(d["github_owner"])
    hosts = {s.split("://", 1)[1].split("/", 1)[0] for s in (raw["site_url"], raw["launch"]["site_url"])}
    own = [rf"github\.com/{owner}/", rf"githubusercontent\.com/{owner}/", rf"api\.github\.com/repos/{owner}/",
           rf"{owner}\.github\.io"] + [rf"(?<![A-Za-z0-9-]){re.escape(h)}/" for h in hosts]
    up = raw["upstream"]
    upstream = [re.escape(up["site_url"].split("://", 1)[1].split("/", 1)[0]),
                re.escape(up["repo_url"].split("github.com/", 1)[1].split("/", 1)[0]),
                re.escape(up["legacy_repo_url"].split("github.com/", 1)[1])]
    return re.compile("|".join(own)), re.compile("|".join(upstream))


def scan(root, raw):
    own, upstream = link_patterns(raw)
    managed = {str(p.relative_to(root)) for p in brand.managed_files(root)}
    generated = set(brand.GENERATED)
    problems = []
    for rel in tracked_files(root):
        try:
            text = (root / rel).read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        kinds = set()
        for kind, pattern in (("own", own), ("upstream", upstream)):
            if pattern.search(text):
                kinds.add(kind)
        if not kinds:
            continue
        allowed = set()
        for pattern, ok, _reason in ALLOWLIST:
            if fnmatch.fnmatchcase(rel, pattern):
                allowed |= ok
        if rel in generated:
            allowed |= {"own", "upstream"}  # content checked byte-for-byte against brand.json
        if rel in managed:
            allowed.add("own")  # rewritten by tools/brand.py; sync checked separately
        for kind in sorted(kinds - allowed):
            pattern = own if kind == "own" else upstream
            lines = [i + 1 for i, line in enumerate(text.splitlines()) if pattern.search(line)]
            problems.append(f"{rel}:{lines[:5]} hardcoded {kind} link outside brand.json/allowlist")
    return problems


class DeriveTest(unittest.TestCase):
    def setUp(self):
        self.raw = brand.load()

    def test_current_values_stay_on_bruciadev_until_launch(self):
        d = brand.derive(self.raw)
        self.assertEqual("Brucia", d["name"])
        self.assertEqual(f"https://github.com/{d['repo_slug']}", d["repo_url"])
        self.assertEqual(d["repo_url"] + "/releases/download/", d["download_prefix"])
        self.assertEqual(f"https://api.github.com/repos/{d['repo_slug']}/releases/latest", d["releases_api"])
        self.assertEqual(d["site_url"] + "wiki/", d["wiki_url"])
        self.assertIn(d["download_prefix"], d["accepted_download_prefixes"])

    def test_launch_switch_changes_repo_site_and_remote_only(self):
        before, after = brand.derive(self.raw), brand.derive(brand.apply_launch(self.raw))
        self.assertEqual(self.raw["launch"]["github_repo"], after["github_repo"])
        self.assertEqual(self.raw["launch"]["site_url"], after["site_url"])
        self.assertEqual(self.raw["launch"]["release_remote"], after["release_remote"])
        self.assertEqual(before["accepted_download_prefixes"], after["accepted_download_prefixes"])
        self.assertEqual((before["name"], before["github_owner"], before["upstream_repo_url"]),
                         (after["name"], after["github_owner"], after["upstream_repo_url"]))

    def test_validation_rejects_values_unsafe_for_c_kotlin_or_shell(self):
        for key, value in (("github_repo", 'Bad"Repo'), ("github_owner", "a b"), ("site_url", "http://x/"),
                           ("site_url", "https://x/no-slash"), ("release_remote", "$(rm)"), ("name", "Other")):
            raw = json.loads(json.dumps(self.raw))
            raw[key] = value
            with self.assertRaises(ValueError, msg=f"{key}={value!r}"):
                brand.derive(raw)
        raw = json.loads(json.dumps(self.raw))
        raw["accepted_release_repos"] = [self.raw["github_repo"]]
        with self.assertRaises(ValueError, msg="launch repo must stay accepted for the updater migration"):
            brand.derive(raw)


class SyncTest(unittest.TestCase):
    def test_generated_and_managed_files_are_in_sync(self):
        self.assertEqual([], brand.stale(), "run: python3 tools/brand.py write")

    def test_rewrite_moves_every_brucia_link_and_nothing_else(self):
        raw = brand.apply_launch(brand.load())
        d, old = brand.derive(raw), brand.derive(brand.load())
        owner, site_host = d["github_owner"], old["site_display"].split("/", 1)[0]
        text = (f"{old['repo_url']} {old['repo_display']}/releases {old['raw_wiki_base']} {old['releases_api']} "
                f"{old['site_url']}wiki/ ({old['site_display']}) "
                f"https://github.com/sponsors/{owner} https://github.com/{owner}/{old['github_repo']}Extra "
                f"https://github.com/{owner}/OtherProject https://{site_host} {raw['upstream']['repo_url']}")
        out = brand.rewrite_links(text, raw)
        self.assertEqual(out, brand.rewrite_links(out, raw), "rewrite must be idempotent")
        self.assertIn(f"{d['repo_url']} {d['repo_display']}/releases {d['raw_wiki_base']} {d['releases_api']} ", out)
        self.assertIn(f"{d['site_url']}wiki/ ({d['site_display']})", out)
        for untouched in (f"https://github.com/sponsors/{owner}", f"{old['github_repo']}Extra",
                          f"{owner}/OtherProject", f"https://{site_host} ", raw["upstream"]["repo_url"]):
            self.assertIn(untouched, out)

    def test_launch_switch_end_to_end_on_a_copy(self):
        if brand.load()["github_repo"] == brand.load()["launch"]["github_repo"]:
            self.skipTest("brand.json already carries the launch values")
        with tempfile.TemporaryDirectory() as tmp:
            copy = Path(tmp)
            shutil.copy2(ROOT / "brand.json", copy / "brand.json")
            for path in [*brand.managed_files(ROOT), *(ROOT / rel for rel in brand.GENERATED)]:
                target = copy / path.relative_to(ROOT)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, target)
            launched = brand.apply_launch(brand.load(copy))
            (copy / "brand.json").write_text(json.dumps(launched, indent=2) + "\n")
            self.assertTrue(brand.write(copy))
            self.assertEqual([], brand.stale(copy))
            old_repo = brand.load()["github_repo"]
            old_site = brand.derive(brand.load())["site_display"]
            stale_link = re.compile(rf"/{re.escape(old_repo)}(?![A-Za-z0-9_-])|{re.escape(old_site)}(?![A-Za-z0-9_-])")
            for path in [*brand.managed_files(copy), *(copy / rel for rel in brand.GENERATED)]:
                # The accepted-prefix list keeps the old name on purpose (updater migration).
                text = re.sub(r'"accepted_download_prefixes": \[[^\]]*\]', "", path.read_text())
                self.assertIsNone(stale_link.search(text), path)
            header = (copy / "include/brucia_brand.h").read_text()
            self.assertIn(f'#define BRUCIA_REPO_URL "{brand.derive(launched)["repo_url"]}"', header)


class FirmwareMacroTest(unittest.TestCase):
    IDENTITY = ("include/brucia_brand.h", "include/brucia_identity.h")

    def defined(self):
        names = set()
        for rel in self.IDENTITY:
            names |= set(re.findall(r"^#define (BRUCIA_[A-Z0-9_]+)", (ROOT / rel).read_text(), re.M))
        return names

    def test_brand_macros_used_in_firmware_exist_and_are_included_directly(self):
        defined = self.defined()
        self.assertTrue({"BRUCIA_NAME", "BRUCIA_REPO_URL", "BRUCIA_SITE_URL", "BRUCIA_WIKI_URL",
                         "BRUCIA_LATEST_RELEASE_URL", "BRUCIA_DOWNLOAD_URL_PREFIX"} <= defined)
        prefixes = ("BRUCIA_NAME", "BRUCIA_REPO_", "BRUCIA_SITE_", "BRUCIA_WIKI_", "BRUCIA_RELEASES_",
                    "BRUCIA_LATEST_", "BRUCIA_DOWNLOAD_", "BRUCIA_UPSTREAM_", "BRUCIA_PRODUCT_", "BRUCIA_PROJECT_",
                    "BRUCIA_GUIDE_", "BRUCIA_AP_", "BRUCIA_MDNS_", "BRUCIA_MANUFACTURER")
        for path in sorted((ROOT / "src").rglob("*")):
            if path.suffix not in {".cpp", ".h", ".hpp", ".c"}:
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            used = {m for m in re.findall(r"\bBRUCIA_[A-Z0-9_]+\b", text) if m.startswith(prefixes)}
            if not used:
                continue
            rel = path.relative_to(ROOT)
            self.assertEqual(set(), used - defined, f"{rel} uses undefined brand macros")
            self.assertRegex(text, r'#include [<"]brucia_(identity|brand)\.h[>"]', f"{rel} must include brucia_identity.h")

    def test_visible_firmware_links_use_the_generated_header(self):
        for rel, needle in (("src/companion/companion.cpp", "BRUCIA_LATEST_RELEASE_URL"),
                            ("src/companion/companion.cpp", "BRUCIA_REPO_DISPLAY"),
                            ("src/core/config.h", "BRUCIA_GUIDE_URL"),
                            ("src/core/utils.cpp", "BRUCIA_UPSTREAM_CREDIT"),
                            ("src/core/serial_commands/util_commands.cpp", "BRUCIA_REPO_URL"),
                            ("src/main.cpp", "BRUCIA_REPO_URL")):
            self.assertIn(needle, (ROOT / rel).read_text(), rel)

    def test_app_store_endpoint_comes_only_from_brand_json(self):
        # The Bruce community store host is third-party; its URL lives in brand.json only.
        host = brand.load()["upstream"]["app_store_url"].split("/")[2]
        settings = (ROOT / "src/core/settings.cpp").read_text()
        self.assertIn("http.begin(BRUCIA_UPSTREAM_APP_STORE_URL)", settings)
        hits = [str(p.relative_to(ROOT)) for p in (ROOT / "src").rglob("*")
                if p.is_file() and host in p.read_text(errors="ignore")]
        self.assertEqual([], hits)
        self.assertIn("Install Bruce App Store", (ROOT / "src/core/menu_items/ConfigMenu.cpp").read_text())


class LinkScannerTest(unittest.TestCase):
    def test_no_hardcoded_links_outside_central_config_and_allowlist(self):
        self.assertEqual([], scan(ROOT, brand.load()))

    def test_scanner_flags_new_literals(self):
        raw = brand.load()
        d = brand.derive(raw)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "src").mkdir()
            (root / "src/a.cpp").write_text(f'const char *u = "{d["releases_url"]}";\n')
            (root / "src/b.kt").write_text(f'val w = "{d["wiki_url"]}"\n')
            (root / "src/c.cpp").write_text(f'const char *u = "{raw["upstream"]["site_url"]}";\n')
            (root / "src/d.kt").write_text(f'val ok = "https://github.com/sponsors/{d["github_owner"]}"\n')
            problems = scan(root, raw)
            self.assertEqual(["src/a.cpp", "src/b.kt", "src/c.cpp"], [p.split(":", 1)[0] for p in problems])

    def test_allowlist_entries_are_used(self):
        files = tracked_files(ROOT)
        for pattern, _kinds, reason in ALLOWLIST:
            self.assertTrue(reason)
            self.assertTrue(any(fnmatch.fnmatchcase(f, pattern) for f in files), f"stale allowlist entry {pattern}")


if __name__ == "__main__":
    unittest.main()
