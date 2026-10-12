#!/usr/bin/env bash
# Bump the app version. Usage: scripts/bump_version.sh [patch|minor|major]   (default: patch)
set -euo pipefail
cd "$(dirname "$0")/.."
kind="${1:-patch}"
cur=$(grep -E 'versionName = "' app/build.gradle.kts | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
code=$(grep -E 'versionCode = ' app/build.gradle.kts | head -1 | sed -E 's/.*= *([0-9]+).*/\1/')
IFS=. read -r a b c <<<"$cur"
case "$kind" in
  major) a=$((a+1)); b=0; c=0;;
  minor) b=$((b+1)); c=0;;
  patch) c=$((c+1));;
  *) echo "unknown bump kind: $kind" >&2; exit 2;;
esac
new="$a.$b.$c"; newcode=$((code+1))
sed -i.bak -E "s/versionName = \"$cur\"/versionName = \"$new\"/; s/versionCode = $code/versionCode = $newcode/" app/build.gradle.kts && rm app/build.gradle.kts.bak
echo "$cur ($code) -> $new ($newcode)"
