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
cur="$(cat VERSION)"; IFS=. read -r maj min pat <<< "$cur"
case "$part" in
  major) maj=$((maj+1)); min=0; pat=0;;
  minor) min=$((min+1)); pat=0;;
  *)     pat=$((pat+1));;
esac
new="$maj.$min.$pat"; echo "$new" > VERSION
echo ">> Releasing $cur -> $new"

footer="APK + T-Embed CC1101 firmware. Flash the firmware from Device > Updates; the device boot screen shows $new when it took."
if [ -n "$notes" ]; then
  body="$notes"$'\n\n'"$footer"
  commit_msg="release $new"$'\n\n'"$notes"
else
  body="Release $new. $footer"
  commit_msg="release $new"
fi

~/.pio-venv/bin/pio run -e lilygo-t-embed-cc1101
cp .pio/build/lilygo-t-embed-cc1101/firmware.factory.bin docs/firmware/brucia-t-embed-cc1101.bin
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-23.jdk/Contents/Home ./android/gradlew -p android :app:assembleRelease
cp android/app/build/outputs/apk/release/app-release.apk "/tmp/Brucia-$new.apk"
git add -A && git commit -m "$commit_msg"
git tag "v$new" && git push "$REMOTE" main && git push "$REMOTE" "v$new"
gh release create "v$new" -R "$REPO" "/tmp/Brucia-$new.apk" docs/firmware/brucia-t-embed-cc1101.bin \
  --title "Brucia $new" --notes "$body"
echo ">> Done: v$new (private repo $REPO)"
