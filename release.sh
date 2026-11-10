#!/usr/bin/env bash
# One-command release: bumps the single VERSION file, rebuilds firmware + app from it, and publishes
# a release with the APK + firmware bin to the repository named in brand.json (1vcian/Brucia, pushed
# through the git remote brand.json names as release_remote).
#
# Usage: ./release.sh [major|minor|patch] ["changelog notes"]
#        ./release.sh resume
#   The optional second argument is the release changelog. It becomes the GitHub release BODY (which
#   the app shows in-app as "What's new" on the Updates screen) and is appended to the commit message.
#   For a bullet list, pass real newlines or "- " markers, e.g.:
#     ./release.sh patch "Added central logging; fixed the wake-lock bug; DTMF audible on the ESP"
#   With no notes, a generic body is used.
#
# Staging and recovery: every release builds into .release-staging/v<version>/ (gitignored): the
# signed APK, the firmware bin, SHA256SUMS, the release title/notes, the release commit and the test
# reports copied before `:app:clean`. A failure before the release commit restores VERSION/manifest/
# firmware and renames the folder to failed-v<version>-<time> (reports kept for diagnosis). A failure
# at or after the commit (tag, push, release create, asset upload, publish) keeps the bundle; fix the
# cause and run `./release.sh resume`: it republishes the SAME version without a new bump or rebuild.
# Resume checks the bundle hashes, that the local and remote tags point at the release commit and
# that every asset already on the release matches the bundle; anything that does not match aborts
# (nothing is overwritten). The release is created as a draft and published only after every asset
# was downloaded back and verified. A completed bundle gets a `published` marker and is kept as the
# local record of the shipped files.
set -euo pipefail
cd "$(dirname "$0")"
# Target repository and git remote come from brand.json (single source; see docs/LAUNCH.md).
REMOTE="$(python3 tools/brand.py get release_remote)"
REPO="$(python3 tools/brand.py get repo_slug)"
if [ -z "$REMOTE" ] || [ -z "$REPO" ]; then
  echo "Cannot read the release target from brand.json." >&2
  exit 1
fi
STAGING_ROOT=".release-staging"
FIRMWARE_PATH="docs/firmware/brucia-t-embed-cc1101.bin"
FIRMWARE_ASSET="brucia-t-embed-cc1101.bin"

die() { echo "$*" >&2; exit 1; }

require_clean_main() {
  if [ -n "$(git status --porcelain)" ]; then
    die "Release requires a clean working tree; commit reviewed changes first."
  fi
  if [ "$(git branch --show-current)" != "main" ]; then
    die "Release must run from main."
  fi
  if ! git check-ignore -q "$STAGING_ROOT/probe"; then
    die "$STAGING_ROOT/ must be gitignored before releasing."
  fi
}

# Release bundles that were committed or started but never marked published.
pending_bundles() {
  local dir
  for dir in "$STAGING_ROOT"/v*; do
    [ -d "$dir" ] || continue
    [ -f "$dir/published" ] && continue
    echo "$dir"
  done
}

# Commit the remote tag points at (peeled for annotated tags); empty when absent.
remote_tag_commit() {
  local tag="$1" listing
  if ! listing="$(git ls-remote --tags "$REMOTE")"; then
    die "Cannot list tags on remote '$REMOTE'; refusing to release blind."
  fi
  printf '%s\n' "$listing" | python3 -c '
import sys
tag = sys.argv[1]
plain = peeled = ""
for line in sys.stdin:
    parts = line.split()
    if len(parts) != 2:
        continue
    if parts[1] == "refs/tags/" + tag:
        plain = parts[0]
    elif parts[1] == "refs/tags/" + tag + "^{}":
        peeled = parts[0]
print(peeled or plain)
' "$tag"
}

# 0 when the release exists, 1 when GitHub reports it absent, exit on any other error.
release_exists() {
  local tag="$1" err
  if err="$(gh release view "$tag" -R "$REPO" --json tagName 2>&1 >/dev/null)"; then
    return 0
  fi
  case "$err" in
    *"release not found"*) return 1 ;;
  esac
  die "Cannot query GitHub release $tag on $REPO: $err"
}

# The bundle files still match SHA256SUMS, and the release commit carries this version and firmware.
verify_bundle() {
  local dir="$1" version="$2" commit="$3"
  python3 - "$dir" <<'PYSUMS'
import hashlib, sys
from pathlib import Path
folder = Path(sys.argv[1])
sums = folder / "SHA256SUMS"
if not sums.is_file():
    sys.exit(f"Release bundle {folder} has no SHA256SUMS.")
entries = [line.split(None, 1) for line in sums.read_text().splitlines() if line.strip()]
if not entries:
    sys.exit(f"Release bundle {folder} lists no files.")
for digest, name in entries:
    path = folder / name.strip()
    if not path.is_file():
        sys.exit(f"Release bundle file missing: {path}")
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != digest:
        sys.exit(f"Release bundle file changed since staging: {path} ({actual} != {digest}).")
PYSUMS
  if ! git cat-file -e "$commit^{commit}" 2>/dev/null; then
    die "Release commit $commit recorded in $dir does not exist in this repository."
  fi
  if [ "$(git show "$commit:VERSION" | tr -d '[:space:]')" != "$version" ]; then
    die "Release commit $commit does not carry VERSION $version."
  fi
  if ! git show "$commit:$FIRMWARE_PATH" | cmp -s - "$dir/$FIRMWARE_ASSET"; then
    die "Firmware committed in $commit differs from the staged $FIRMWARE_ASSET."
  fi
}

# Idempotent publication of one staged bundle: tag, push, draft release, upload, verify, publish.
publish_bundle() {
  local dir="$1" version tag commit local_commit remote_commit
  version="$(cat "$dir/version")"
  tag="v$version"
  [ -f "$dir/commit" ] || die "Release bundle $dir records no release commit."
  commit="$(cat "$dir/commit")"
  verify_bundle "$dir" "$version" "$commit"

  if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
    local_commit="$(git rev-parse "$tag^{commit}")"
    if [ "$local_commit" != "$commit" ]; then
      die "Local tag $tag points at $local_commit, expected release commit $commit; not moving it."
    fi
  else
    git tag "$tag" "$commit"
  fi

  remote_commit="$(remote_tag_commit "$tag")"
  if [ -z "$remote_commit" ]; then
    if [ "$(git rev-parse HEAD)" != "$commit" ]; then
      die "HEAD is no longer the release commit $commit; push it explicitly or restore main first."
    fi
    git push --atomic "$REMOTE" main "$tag"
  elif [ "$remote_commit" != "$commit" ]; then
    die "Remote tag $tag points at $remote_commit, expected release commit $commit; not overwriting it."
  else
    echo ">> Remote tag $tag already at the release commit"
  fi
  git fetch -q "$REMOTE" main
  if ! git merge-base --is-ancestor "$commit" FETCH_HEAD; then
    die "Remote main does not contain release commit $commit."
  fi

  python3 - "$dir" "$tag" "$REPO" <<'PYPUBLISH'
import hashlib, json, shutil, subprocess, sys
from pathlib import Path

folder, tag, repo = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
expected = {}
for line in (folder / "SHA256SUMS").read_text().splitlines():
    if line.strip():
        digest, name = line.split(None, 1)
        expected[name.strip()] = digest


def gh(*args):
    result = subprocess.run(["gh", *args], text=True, capture_output=True)
    if result.returncode != 0:
        sys.exit(f"gh {' '.join(args[:2])} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout


def view():
    result = subprocess.run(["gh", "release", "view", tag, "-R", repo, "--json", "tagName,isDraft,assets"],
                            text=True, capture_output=True)
    if result.returncode != 0:
        if "release not found" in result.stderr:
            return None
        sys.exit(f"Cannot query GitHub release {tag}: {result.stderr.strip()}")
    return json.loads(result.stdout)


def check_existing(release):
    """Names on the release verified against the bundle; any mismatch aborts without touching it."""
    verified = set()
    scratch = folder / "verify"
    for asset in release.get("assets") or []:
        name = asset["name"]
        if name not in expected:
            sys.exit(f"Release {tag} has asset {name} that is not in the bundle; inspect it manually.")
        if asset.get("state", "uploaded") != "uploaded":
            sys.exit(f"Release {tag} asset {name} is incomplete (state {asset['state']}); "
                     f"inspect and delete it with `gh release delete-asset {tag} {name} -R {repo}`, then resume.")
        local = folder / name
        if asset.get("size") is not None and int(asset["size"]) != local.stat().st_size:
            sys.exit(f"Release {tag} asset {name} has size {asset['size']}, bundle has "
                     f"{local.stat().st_size}; refusing to overwrite it.")
        digest = asset.get("digest") or ""
        if digest.startswith("sha256:") and digest[7:] != expected[name]:
            sys.exit(f"Release {tag} asset {name} digest {digest} does not match the bundle; "
                     "refusing to overwrite it.")
        shutil.rmtree(scratch, ignore_errors=True)
        scratch.mkdir()
        gh("release", "download", tag, "-R", repo, "-p", name, "-D", str(scratch))
        actual = hashlib.sha256((scratch / name).read_bytes()).hexdigest()
        shutil.rmtree(scratch, ignore_errors=True)
        if actual != expected[name]:
            sys.exit(f"Release {tag} asset {name} does not match the bundle ({actual} != "
                     f"{expected[name]}); refusing to overwrite it.")
        verified.add(name)
    return verified


release = view()
if release is None:
    gh("release", "create", tag, "-R", repo, "--title", (folder / "title").read_text().strip(),
       "--notes-file", str(folder / "notes.md"), "--draft", "--verify-tag")
    print(f">> Created draft release {tag}")
    release = view()
    if release is None:
        sys.exit(f"Release {tag} was not found after creation.")
else:
    print(f">> Release {tag} already exists ({'draft' if release.get('isDraft') else 'published'}); verifying assets")
missing = [name for name in expected if name not in check_existing(release)]
if missing:
    gh("release", "upload", tag, "-R", repo, *[str(folder / name) for name in missing])
    print(f">> Uploaded {', '.join(missing)}")
release = view()
if release is None or check_existing(release) != set(expected):
    sys.exit(f"Release {tag} assets do not match the bundle after upload.")
if release.get("isDraft"):
    gh("release", "edit", tag, "-R", repo, "--draft=false")
    release = view()
    if release is None or release.get("isDraft"):
        sys.exit(f"Release {tag} is still a draft after publishing.")
print(f">> Release {tag} published; assets verified against SHA256SUMS")
PYPUBLISH
  date -u +%Y-%m-%dT%H:%M:%SZ > "$dir/published"
  echo ">> Done: $tag (repository $REPO); bundle kept in $dir"
}

part="${1:-patch}"
notes="${2:-}"

if [ "$part" = "resume" ]; then
  require_clean_main
  pending="$(pending_bundles)"
  if [ -z "$pending" ]; then
    die "No unpublished release bundle in $STAGING_ROOT/ to resume."
  fi
  if [ "$(printf '%s\n' "$pending" | wc -l | tr -d ' ')" != "1" ]; then
    die "Several unpublished release bundles; resolve all but one manually: $(echo $pending)"
  fi
  echo ">> Resuming $(cat "$pending/version") from $pending (no bump, no rebuild)"
  publish_bundle "$pending"
  exit 0
fi

require_clean_main
case "$part" in major|minor|patch) ;; *) die "Expected major, minor, patch or resume" ;; esac
pending="$(pending_bundles)"
if [ -n "$pending" ]; then
  die "Unpublished release bundle in $(echo $pending); run ./release.sh resume instead of a new bump."
fi
cur="$(cat VERSION)"
if [[ ! "$cur" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(\.(0|[1-9][0-9]*))?$ ]]; then
  die "Invalid VERSION: expected major.minor[.patch]"
fi
IFS=. read -r maj min pat <<< "$cur"
pat="${pat:-0}"
case "$part" in
  major) maj=$((maj+1)); min=0; pat=0;;
  minor) min=$((min+1)); pat=0;;
  *)     pat=$((pat+1));;
esac
if [ "$min" -ge 100 ] || [ "$pat" -ge 100 ]; then
  die "Minor/patch must be below 100 for Android versionCode; bump the next component."
fi
if [ ! -f android/keystore.properties ]; then
  die "Release signing requires android/keystore.properties; refusing an unsigned/stale APK."
fi
new="$maj.$min.$pat"
tag="v$new"
# Preflight before any change: the tag and release must not exist anywhere, and main must push.
if git rev-parse --verify --quiet "refs/tags/$tag" >/dev/null; then
  die "Tag $tag already exists."
fi
if [ -n "$(remote_tag_commit "$tag")" ]; then
  die "Tag $tag already exists on remote '$REMOTE'."
fi
if release_exists "$tag"; then
  die "GitHub release $tag already exists on $REPO."
fi
git fetch -q "$REMOTE" main
if ! git merge-base --is-ancestor FETCH_HEAD HEAD; then
  die "Local main does not contain remote main; pull before releasing."
fi

bundle="$STAGING_ROOT/$tag"
if [ -e "$bundle" ]; then
  die "$bundle already exists and is marked published; refusing to reuse version $new."
fi
mkdir -p "$bundle/rollback"
cp VERSION "$bundle/rollback/VERSION"
cp docs/manifest.json "$bundle/rollback/manifest.json"
if [ -f "$FIRMWARE_PATH" ]; then
  cp "$FIRMWARE_PATH" "$bundle/rollback/firmware.bin"
fi
release_committed=0
cleanup_release() {
  release_status=$?
  trap - EXIT
  if [ "$release_status" -eq 0 ]; then
    exit 0
  fi
  if [ "$release_committed" -eq 0 ]; then
    cp "$bundle/rollback/VERSION" VERSION
    cp "$bundle/rollback/manifest.json" docs/manifest.json
    git restore --staged -- VERSION docs/manifest.json "$FIRMWARE_PATH" || true
    if [ -f "$bundle/rollback/firmware.bin" ]; then
      cp "$bundle/rollback/firmware.bin" "$FIRMWARE_PATH"
    fi
    failed="$STAGING_ROOT/failed-$tag-$(date -u +%Y%m%dT%H%M%SZ)"
    mv "$bundle" "$failed"
    echo "Release $new aborted before its commit; VERSION restored. Reports kept in $failed." >&2
  else
    if [ ! -f "$bundle/commit" ]; then
      git rev-parse HEAD > "$bundle/commit" || true
    fi
    echo "Release $new is committed but not fully published. Bundle, version and hashes are kept in" >&2
    echo "$bundle. Fix the cause, then run ./release.sh resume (same version, no new bump)." >&2
  fi
  exit "$release_status"
}
trap cleanup_release EXIT
echo "$new" > VERSION
echo ">> Releasing $cur -> $new"
python3 - "$new" <<'PYMANIFEST'
import json, sys
from pathlib import Path
path = Path("docs/manifest.json")
manifest = json.loads(path.read_text())
manifest["version"] = sys.argv[1]
path.write_text(json.dumps(manifest, indent=2) + "\n")
PYMANIFEST

footer="APK + T-Embed CC1101 firmware. Flash the firmware from Device > Updates; the device boot screen shows $new when it took."
if [ -n "$notes" ]; then
  body="$notes"$'\n\n'"$footer"
  commit_msg="release $new"$'\n\n'"$notes"
else
  body="Release $new. $footer"
  commit_msg="release $new"
fi

# Copies Android test/lint reports into the bundle (they are deleted by :app:clean).
preserve_reports() {
  local item
  mkdir -p "$bundle/android-reports"
  for item in reports test-results outputs/paparazzi; do
    if [ -d "android/app/build/$item" ]; then
      mkdir -p "$bundle/android-reports/$(dirname "$item")"
      cp -R "android/app/build/$item" "$bundle/android-reports/$item"
    fi
  done
}

python3 -m unittest discover -s tools -p 'test_*.py'
node --test tools/test_site_release.cjs
python3 tools/test_firmware_codec.py
python3 tools/test_firmware_storage.py
python3 tools/test_firmware_framing.py
python3 tools/test_protocol_e2e.py
# verifyPaparazziDebug compares screenshots against the committed baselines; the plain unit task
# only records/renders them (Paparazzi 2.0 verifies only with paparazzi.test.verify set).
if ! ./android/gradlew -p android :app:testDebugUnitTest :app:verifyPaparazziDebug :app:lintDebug; then
  preserve_reports
  exit 1
fi
preserve_reports
# Prefer an explicit PIO_BIN, then the project venv (a Homebrew pio may run on a Python the
# toolchain rejects), then whatever pio is on PATH.
if [ -n "${PIO_BIN:-}" ]; then
  pio_bin="$PIO_BIN"
elif [ -x "$HOME/.pio-venv/bin/pio" ]; then
  pio_bin="$HOME/.pio-venv/bin/pio"
else
  pio_bin="pio"
fi
PIO_BIN="$pio_bin" python3 tools/build_firmware.py -e lilygo-t-embed-cc1101
python3 tools/test_audio_noise_bounds.py -e lilygo-t-embed-cc1101
cp .pio/build/lilygo-t-embed-cc1101/firmware.factory.bin "$FIRMWARE_PATH"
./android/gradlew -p android :app:clean :app:assembleRelease
cp android/app/build/outputs/apk/release/app-release.apk "$bundle/Brucia-$new.apk"
cp "$FIRMWARE_PATH" "$bundle/$FIRMWARE_ASSET"
echo "$new" > "$bundle/version"
echo "Brucia $new" > "$bundle/title"
printf '%s\n' "$body" > "$bundle/notes.md"
python3 - "$bundle" "Brucia-$new.apk" "$FIRMWARE_ASSET" <<'PYHASH'
import hashlib, sys
from pathlib import Path
folder = Path(sys.argv[1])
lines = [f"{hashlib.sha256((folder / name).read_bytes()).hexdigest()}  {name}" for name in sys.argv[2:]]
(folder / "SHA256SUMS").write_text("\n".join(lines) + "\n")
PYHASH
git add -- VERSION docs/manifest.json "$FIRMWARE_PATH"
git commit -m "$commit_msg"
release_committed=1
git rev-parse HEAD > "$bundle/commit"
publish_bundle "$bundle"
