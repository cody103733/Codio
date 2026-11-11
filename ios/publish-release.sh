#!/bin/bash
# Publish an iPhone build as a Release of this repo and update the app's update list (apps.json in this repo).
# Usage: GH_TOKEN=... ios/publish-release.sh <ipa> <version> <build>
set -euo pipefail
IPA=$1; VER=$2; BUILD=$3
R=${GITHUB_REPOSITORY:-cody103733/Codio}
TAG="ios-v$VER-build$BUILD"
NAME="Codio-$VER.ipa"
cp "$IPA" "/tmp/$NAME"
gh release view "$TAG" -R $R >/dev/null 2>&1 || \
  gh release create "$TAG" -R $R --title "Codio for iPhone $VER (build $BUILD)" \
    --notes "Unofficial personal build, provided as is, not for general use. Unsigned. Source is this repository at this tag (GPL-2.0)." "/tmp/$NAME"
URL="https://github.com/$R/releases/download/$TAG/$NAME"
SIZE=$(wc -c < "/tmp/$NAME" | tr -d ' ')
SHA=$(gh api "repos/$R/contents/apps.json" --jq .sha 2>/dev/null || true)
gh api "repos/$R/contents/apps.json" -H "Accept: application/vnd.github.raw" > /tmp/apps.json 2>/dev/null || echo '{}' > /tmp/apps.json
python3 - "$VER" "$BUILD" "$URL" "$SIZE" <<'PY'
import json, sys, datetime
ver, build, url, size = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
import os
R = os.environ.get("GITHUB_REPOSITORY", "cody103733/Codio")
try: src = json.load(open("/tmp/apps.json"))
except Exception: src = {}
src.update({
  "name": "Codio", "identifier": "com.codio.source",
  "sourceURL": f"https://raw.githubusercontent.com/{R}/main/apps.json",
  "iconURL": f"https://raw.githubusercontent.com/{R}/main/icon.png",
})
apps = src.get("apps") or [{}]
app = apps[0]
app.update({
  "name": "Codio", "bundleIdentifier": "com.codio.ios", "developerName": "Cody",
  "subtitle": "Movies and shows, one tap to play.",
  "localizedDescription": "Codio picks the best source and plays it, with Skip Intro, downloads and your Stremio library.",
  "iconURL": f"https://raw.githubusercontent.com/{R}/main/icon.png",
  "tintColor": "#E50914", "appPermissions": {"entitlements": [], "privacy": {}},
})
versions = [v for v in app.get("versions", []) if not (v.get("version") == ver and v.get("buildVersion") == build)]
versions.insert(0, {"version": ver, "buildVersion": build, "date": datetime.date.today().isoformat(),
                    "size": size, "downloadURL": url, "minOSVersion": "16.0",
                    "localizedDescription": f"Codio {ver} (build {build})"})
app["versions"] = versions[:10]
src["apps"] = [app]
src.setdefault("news", [])
json.dump(src, open("/tmp/apps.json", "w"), indent=2)
PY
ARGS=(-X PUT "repos/$R/contents/apps.json" -f message="Update list: Codio $VER (build $BUILD) [skip ci]" -f content="$(base64 < /tmp/apps.json | tr -d '\n')")
[ -n "$SHA" ] && ARGS+=(-f sha="$SHA")
gh api "${ARGS[@]}" >/dev/null
echo "published $URL"
