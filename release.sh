#!/usr/bin/env bash
# One-command release: bumps the single VERSION file, rebuilds firmware + app from it, and publishes
# a release with the APK + firmware bin to the PRIVATE BruciaDev repo (while the project is private).
# The public repo (origin) is intentionally NOT touched here - do not publish publicly until tested.
#
# Usage: ./release.sh [major|minor|patch] ["changelog notes"]
#   The optional second argument is the release changelog. It becomes the GitHub release BODY (which
#   the app shows in-app as "What's new" on the Updates screen) and is appended to the commit message.
#   For a bullet list, pass real newlines or "- " markers, e.g.:
#     ./release.sh patch "Added central logging; fixed the wake-lock bug; DTMF audible on the ESP"
#   With no notes, a generic body is used.
set -euo pipefail
cd "$(dirname "$0")"
# Private dev target. Flip to origin / add a public mirror only at the public launch.
REMOTE="brucia-dev"
REPO="1vcian/BruciaDev"
part="${1:-patch}"
notes="${2:-}"
if [ -n "$(git status --porcelain)" ]; then
  echo "Release requires a clean working tree; commit reviewed changes first." >&2
  exit 1
fi
case "$part" in major|minor|patch) ;; *) echo "Expected major, minor or patch" >&2; exit 1 ;; esac
if [ "$(git branch --show-current)" != "main" ]; then
  echo "Release must run from main." >&2
  exit 1
fi
cur="$(cat VERSION)"
if [[ ! "$cur" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(\.(0|[1-9][0-9]*))?$ ]]; then
  echo "Invalid VERSION: expected major.minor[.patch]" >&2
  exit 1
fi
IFS=. read -r maj min pat <<< "$cur"
pat="${pat:-0}"
case "$part" in
  major) maj=$((maj+1)); min=0; pat=0;;
  minor) min=$((min+1)); pat=0;;
  *)     pat=$((pat+1));;
esac
if [ "$min" -ge 100 ] || [ "$pat" -ge 100 ]; then
  echo "Minor/patch must be below 100 for Android versionCode; bump the next component." >&2
  exit 1
fi
if [ ! -f android/keystore.properties ]; then
  echo "Release signing requires android/keystore.properties; refusing an unsigned/stale APK." >&2
  exit 1
fi
new="$maj.$min.$pat"
if git rev-parse --verify --quiet "refs/tags/v$new" >/dev/null; then
  echo "Tag v$new already exists." >&2
  exit 1
fi
release_backup="$(mktemp -d "${TMPDIR:-/tmp}/brucia-release.XXXXXX")"
cp VERSION "$release_backup/VERSION"
cp docs/manifest.json "$release_backup/manifest.json"
if [ -f docs/firmware/brucia-t-embed-cc1101.bin ]; then
  cp docs/firmware/brucia-t-embed-cc1101.bin "$release_backup/firmware.bin"
fi
release_committed=0
cleanup_release() {
  release_status=$?
  trap - EXIT
  if [ "$release_status" -ne 0 ] && [ "$release_committed" -eq 0 ]; then
    cp "$release_backup/VERSION" VERSION
    cp "$release_backup/manifest.json" docs/manifest.json
    git restore --staged -- VERSION docs/manifest.json docs/firmware/brucia-t-embed-cc1101.bin || true
    if [ -f "$release_backup/firmware.bin" ]; then
      cp "$release_backup/firmware.bin" docs/firmware/brucia-t-embed-cc1101.bin
    fi
  fi
  python3 - "$release_backup" <<'PYCLEAN'
from pathlib import Path
import shutil, sys
path = Path(sys.argv[1])
if path.name.startswith("brucia-release."):
    shutil.rmtree(path)
PYCLEAN
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

python3 -m unittest discover -s tools -p 'test_*.py'
node --test tools/test_site_release.cjs
python3 tools/test_firmware_codec.py
python3 tools/test_firmware_storage.py
python3 tools/test_firmware_framing.py
python3 tools/test_protocol_e2e.py
./android/gradlew -p android :app:testDebugUnitTest :app:lintDebug
pio_bin="${PIO_BIN:-pio}"
if ! command -v "$pio_bin" >/dev/null 2>&1 && [ -x "$HOME/.pio-venv/bin/pio" ]; then
  pio_bin="$HOME/.pio-venv/bin/pio"
fi
PIO_BIN="$pio_bin" python3 tools/build_firmware.py -e lilygo-t-embed-cc1101
python3 tools/test_audio_noise_bounds.py -e lilygo-t-embed-cc1101
cp .pio/build/lilygo-t-embed-cc1101/firmware.factory.bin docs/firmware/brucia-t-embed-cc1101.bin
./android/gradlew -p android :app:clean :app:assembleRelease
cp android/app/build/outputs/apk/release/app-release.apk "$release_backup/Brucia-$new.apk"
git add -- VERSION docs/manifest.json docs/firmware/brucia-t-embed-cc1101.bin
git commit -m "$commit_msg"
release_committed=1
git tag "v$new"
git push --atomic "$REMOTE" main "v$new"
gh release create "v$new" -R "$REPO" "$release_backup/Brucia-$new.apk" docs/firmware/brucia-t-embed-cc1101.bin \
  --title "Brucia $new" --notes "$body"
echo ">> Done: v$new (repository $REPO)"
