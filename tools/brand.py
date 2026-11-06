#!/usr/bin/env python3
"""Brucia brand links: one source (brand.json at the repo root), several consumers.

brand.json holds the project name, the GitHub owner/repository, the website URL and the
upstream (Bruce) credit links. Everything else is derived here, in one place:

- generated files (committed, checked by tools/test_brand.py):
    include/brucia_brand.h   firmware macros (boot/about/companion screens, QR codes, NFC defaults)
    docs/brand.js            website/web-flasher constants (release catalog, wiki renderer)
- managed text files: README/wiki/site pages whose Brucia links are rewritten in place;
- android/app/build.gradle.kts reads brand.json directly into BuildConfig (app/brucia/BrandLinks.kt);
- release.sh reads `python3 tools/brand.py get repo_slug` and `get release_remote`.

Commands:
    python3 tools/brand.py write     regenerate generated files and rewrite managed files
    python3 tools/brand.py check     exit 1 if anything is out of date (no writes)
    python3 tools/brand.py get KEY   print one derived value (see derive())
    python3 tools/brand.py launch    apply brand.json "launch" values, then write (docs/LAUNCH.md)
"""
from __future__ import annotations

import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
BRAND_FILE = "brand.json"

# Text files whose Brucia links are kept in sync by rewriting (not generated wholesale).
MANAGED_GLOBS = (
    "README.md",
    "SECURITY.md",
    "docs/USAGE.md",
    "docs/index.html",
    "docs/wiki/index.html",
    "wiki/*.md",
    "embedded_resources/web_interface/index.html",
)

_SLUG = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
_SITE = re.compile(r"https://[A-Za-z0-9.-]+(/[A-Za-z0-9._~-]+)*/")
_UPSTREAM = re.compile(r"https://[A-Za-z0-9.-]+(/[A-Za-z0-9._~-]+)*")


def load(root: Path = ROOT) -> dict:
    return json.loads((root / BRAND_FILE).read_text(encoding="utf-8"))


def validate(raw: dict) -> None:
    """Values flow into C string literals, Kotlin BuildConfig and shell: keep them plain."""
    if raw.get("name") != "Brucia":
        raise ValueError("brand.json: name must stay 'Brucia' (product names are literals in firmware)")
    for key in ("github_owner", "github_repo", "release_remote"):
        if not _SLUG.fullmatch(str(raw.get(key, ""))):
            raise ValueError(f"brand.json: invalid {key}")
    if not _SITE.fullmatch(str(raw.get("site_url", ""))):
        raise ValueError("brand.json: site_url must be https://host/path/ with a trailing slash")
    repos = raw.get("accepted_release_repos")
    if not isinstance(repos, list) or not repos or not all(_SLUG.fullmatch(str(r)) for r in repos):
        raise ValueError("brand.json: accepted_release_repos must be a non-empty list of repo names")
    launch = raw.get("launch") or {}
    if not _SLUG.fullmatch(str(launch.get("github_repo", ""))) or not _SITE.fullmatch(str(launch.get("site_url", ""))):
        raise ValueError("brand.json: launch needs github_repo and site_url")
    for repo in (raw["github_repo"], launch["github_repo"]):
        if repo not in repos:
            raise ValueError(f"brand.json: accepted_release_repos must include {repo} (updater migration)")
    up = raw.get("upstream") or {}
    if not up.get("name") or not all(_UPSTREAM.fullmatch(str(up.get(k, "")))
                                     for k in ("repo_url", "site_url", "legacy_repo_url")):
        raise ValueError("brand.json: upstream needs name, repo_url, site_url, legacy_repo_url")
    # Installer endpoint of the Bruce community App Store (third-party host); it needs its trailing slash.
    if not _UPSTREAM.fullmatch(str(up.get("app_store_url", "")).removesuffix("/")):
        raise ValueError("brand.json: upstream needs app_store_url")


def _hostpath(url: str) -> str:
    return url.split("://", 1)[1]


def derive(raw: dict) -> dict:
    """Every public URL form used by the firmware, app, website and release script."""
    validate(raw)
    owner, repo, site = raw["github_owner"], raw["github_repo"], raw["site_url"]
    repo_url = f"https://github.com/{owner}/{repo}"
    up = raw["upstream"]
    return {
        "name": raw["name"],
        "github_owner": owner,
        "github_repo": repo,
        "repo_slug": f"{owner}/{repo}",
        "repo_url": repo_url,
        "repo_display": _hostpath(repo_url),
        "issues_url": f"{repo_url}/issues",
        "releases_url": f"{repo_url}/releases",
        "latest_release_url": f"{repo_url}/releases/latest",
        "download_prefix": f"{repo_url}/releases/download/",
        "releases_api": f"https://api.github.com/repos/{owner}/{repo}/releases/latest",
        "accepted_download_prefixes": [f"https://github.com/{owner}/{r}/releases/download/"
                                       for r in raw["accepted_release_repos"]],
        "raw_wiki_base": f"https://raw.githubusercontent.com/{owner}/{repo}/main/wiki/",
        "github_wiki_url": f"{repo_url}/wiki/",
        "site_url": site,
        "site_hostpath": _hostpath(site),
        "site_display": _hostpath(site).rstrip("/"),
        "wiki_url": f"{site}wiki/",
        "release_remote": raw["release_remote"],
        "upstream_name": up["name"],
        "upstream_repo_url": up["repo_url"],
        "upstream_repo_display": _hostpath(up["repo_url"]),
        "upstream_site_url": up["site_url"],
        "upstream_legacy_repo_url": up["legacy_repo_url"],
        "upstream_app_store_url": up["app_store_url"],
    }


def _c_string(value: str) -> str:
    return json.dumps(value)  # validated values contain no quotes/backslashes/non-ASCII


def render_header(d: dict) -> str:
    rows = [
        ("BRUCIA_NAME", "name"),
        ("BRUCIA_REPO_URL", "repo_url"),
        ("BRUCIA_REPO_DISPLAY", "repo_display"),
        ("BRUCIA_RELEASES_URL", "releases_url"),
        ("BRUCIA_LATEST_RELEASE_URL", "latest_release_url"),
        ("BRUCIA_DOWNLOAD_URL_PREFIX", "download_prefix"),
        ("BRUCIA_SITE_URL", "site_url"),
        ("BRUCIA_SITE_HOSTPATH", "site_hostpath"),
        ("BRUCIA_SITE_DISPLAY", "site_display"),
        ("BRUCIA_WIKI_URL", "wiki_url"),
        ("BRUCIA_UPSTREAM_NAME", "upstream_name"),
        ("BRUCIA_UPSTREAM_REPO_URL", "upstream_repo_url"),
        ("BRUCIA_UPSTREAM_REPO_DISPLAY", "upstream_repo_display"),
        ("BRUCIA_UPSTREAM_SITE_URL", "upstream_site_url"),
        ("BRUCIA_UPSTREAM_APP_STORE_URL", "upstream_app_store_url"),
    ]
    lines = [
        "#pragma once",
        "",
        "// Generated by tools/brand.py from brand.json. Do not edit by hand:",
        "// change brand.json (see docs/LAUNCH.md) and run `python3 tools/brand.py write`.",
        "",
    ]
    lines += [f"#define {macro} {_c_string(d[key])}" for macro, key in rows]
    return "\n".join(lines) + "\n"


def render_js(d: dict) -> str:
    keys = ("name", "repo_url", "repo_display", "releases_url", "latest_release_url", "download_prefix",
            "accepted_download_prefixes", "releases_api", "raw_wiki_base", "github_wiki_url",
            "site_url", "wiki_url", "upstream_name", "upstream_repo_url")
    body = json.dumps({k: d[k] for k in keys}, indent=2)
    return (
        "/* Generated by tools/brand.py from brand.json. Do not edit by hand:\n"
        "   change brand.json (see docs/LAUNCH.md) and run `python3 tools/brand.py write`. */\n"
        "(function (root) {\n"
        "  \"use strict\";\n"
        f"  var brand = Object.freeze({body.replace(chr(10), chr(10) + '  ')});\n"
        "  if (typeof module === \"object\" && module.exports) module.exports = brand;\n"
        "  else root.BruciaBrand = brand;\n"
        "})(typeof globalThis !== \"undefined\" ? globalThis : this);\n"
    )


GENERATED = {
    "include/brucia_brand.h": render_header,
    "docs/brand.js": render_js,
}


def _known_repos(raw: dict) -> list[str]:
    repos = {raw["github_repo"], raw["launch"]["github_repo"], *raw["accepted_release_repos"]}
    return sorted(repos, key=lambda r: (-len(r), r))


def _known_sites(raw: dict) -> list[str]:
    """Current and launch site, plus <host>/<repo> for every known repo name (GitHub Pages project path)."""
    sites = {_hostpath(raw["site_url"]).rstrip("/"), _hostpath(raw["launch"]["site_url"]).rstrip("/")}
    hosts = {s.split("/", 1)[0] for s in sites}
    sites |= {f"{host}/{repo}" for host in hosts for repo in _known_repos(raw)}
    return sorted(sites, key=lambda s: (-len(s), s))


_END = r"(?![A-Za-z0-9_-])"


def rewrite_links(text: str, raw: dict) -> str:
    """Point every Brucia repo/site link (current, launch or legacy spelling) at the current values."""
    d = derive(raw)
    owner = re.escape(d["github_owner"])
    repos = "|".join(re.escape(r) for r in _known_repos(raw))
    for prefix in ("github.com/", "raw.githubusercontent.com/", "api.github.com/repos/"):
        text = re.sub(rf"(?<![A-Za-z0-9.-]){re.escape(prefix)}{owner}/(?:{repos}){_END}",
                      f"{prefix}{d['github_owner']}/{d['github_repo']}", text)
    sites = "|".join(re.escape(s) for s in _known_sites(raw))
    text = re.sub(rf"(?<![A-Za-z0-9.-])(?:{sites}){_END}", d["site_display"], text)
    return text


def managed_files(root: Path = ROOT) -> list[Path]:
    files: set[Path] = set()
    for pattern in MANAGED_GLOBS:
        files.update(p for p in root.glob(pattern) if p.is_file())
    return sorted(files)


def plan(root: Path = ROOT, raw: dict | None = None) -> dict[Path, str]:
    """Expected content of every generated and managed file."""
    raw = load(root) if raw is None else raw
    d = derive(raw)
    out = {root / rel: render(d) for rel, render in GENERATED.items()}
    for path in managed_files(root):
        out[path] = rewrite_links(path.read_text(encoding="utf-8"), raw)
    return out


def stale(root: Path = ROOT) -> list[str]:
    result = []
    for path, expected in plan(root).items():
        current = path.read_text(encoding="utf-8") if path.is_file() else None
        if current != expected:
            result.append(str(path.relative_to(root)))
    return result


def write(root: Path = ROOT) -> list[str]:
    changed = []
    for path, expected in plan(root).items():
        if not path.is_file() or path.read_text(encoding="utf-8") != expected:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(expected, encoding="utf-8")
            changed.append(str(path.relative_to(root)))
    return changed


def apply_launch(raw: dict) -> dict:
    """Return brand values after the BruciaDev -> Brucia switch (see docs/LAUNCH.md)."""
    out = json.loads(json.dumps(raw))
    for key in ("github_repo", "site_url", "release_remote"):
        out[key] = raw["launch"][key]
    validate(out)
    return out


def main(argv: list[str]) -> int:
    cmd = argv[1] if len(argv) > 1 else ""
    if cmd == "get" and len(argv) == 3:
        value = derive(load()).get(argv[2])
        if value is None:
            print(f"unknown key: {argv[2]}", file=sys.stderr)
            return 2
        print(" ".join(value) if isinstance(value, list) else value)
        return 0
    if cmd == "check":
        out = stale()
        for rel in out:
            print(f"out of date: {rel} (run python3 tools/brand.py write)", file=sys.stderr)
        return 1 if out else 0
    if cmd == "write":
        for rel in write():
            print(f"updated {rel}")
        return 0
    if cmd == "launch":
        raw = apply_launch(load())
        (ROOT / BRAND_FILE).write_text(json.dumps(raw, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
        for rel in write():
            print(f"updated {rel}")
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
