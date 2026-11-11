#!/bin/bash
# Builds the hidden web engine that ships inside the iPhone app (ios/web) from this folder's source.
# Needs Node 22+, pnpm 11, python3 with Pillow.
# Usage (from the repo root): bash web/codio-build/build-ios.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT/web"
pnpm install
SERVICE_WORKER_DISABLED=true pnpm build
OUT="$ROOT/ios/web"
rm -rf "$OUT"; mkdir -p "$OUT"
rsync -a --exclude '*.map' --exclude screenshots --exclude service-worker.js --exclude 'workbox-*' \
  --exclude 'images/splash' build/ "$OUT/"
python3 codio-build/patch-http-only.py "$OUT"            # auto-play never picks torrent sources
python3 codio-build/patch-play-after-prefetch.py "$OUT"  # Play starts even after sources were prefetched
python3 codio-build/neutral-images.py "$OUT"             # no Stremio artwork
# The web build names its folder after the git commit; drop the PWA screenshots entry from the manifest.
python3 - "$OUT/manifest.json" <<'PY'
import json, sys
m = json.load(open(sys.argv[1])); m.pop('screenshots', None)
json.dump(m, open(sys.argv[1], 'w'), indent=4)
PY
echo "ios/web rebuilt"
