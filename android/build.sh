#!/usr/bin/env bash
# Builds Codio for Android TV without Gradle (only Debian's Android tools + a JDK).
#   WEB_BUILD   = built Codio web UI (run `SERVICE_WORKER_DISABLED=true pnpm build` in the web source)
#   LIBMPV_AAR  = dev.jdtech.mpv:libmpv AAR from Maven Central
#   LIBVLC_AAR  = org.videolan.android:libvlc-all AAR from Maven Central (the default player)
#   VLC_SRC     = Java sources of that libVLC version (its -sources.jar, unpacked and patched: see vlc-src/README)
#   KEYSTORE    = signing key; keep it, Android only installs updates signed with the same key
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
VERSION="${VERSION:-1.0.0}"
VERSION_CODE="${VERSION_CODE:-1}"
ANDROID_JAR="${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
WEB_BUILD="${WEB_BUILD:?set WEB_BUILD to the web UI build folder}"
LIBMPV_AAR="${LIBMPV_AAR:?set LIBMPV_AAR to libmpv-<ver>.aar}"
LIBVLC_AAR="${LIBVLC_AAR:?set LIBVLC_AAR to libvlc-all-<ver>.aar}"
VLC_SRC="${VLC_SRC:?set VLC_SRC to the patched libVLC Java sources}"
KEYSTORE="${KEYSTORE:-$HERE/codio.keystore}"
KS_PASS="${KS_PASS:?set KS_PASS to the keystore password}"
ABIS="${ABIS:-armeabi-v7a arm64-v8a x86_64}"
OUT="${OUT:-$HERE/out}"
B="$HERE/.build"
# PHONE=1 builds Codio for phones (package com.codio.phone, touch UI, downloads) from the same source.
PHONE="${PHONE:-0}"
# The phone app needs API 26+ framework classes (mini player): pass ANDROID_JAR=<a full API 34 android.jar>, e.g. Robolectric's
# org.robolectric:android-all:14-robolectric-10818077 from Maven Central (it includes resources.arsc for aapt2).
if [ "$PHONE" = "1" ]; then PHONE_BOOL=true; APK_NAME="Codio-Phone"; else PHONE_BOOL=false; APK_NAME="Codio-AndroidTV"; fi

rm -rf "$B" && mkdir -p "$B"/{gen,obj,dex,assets/web,lib,res}
mkdir -p "$OUT"

# 1. BuildConfig
mkdir -p "$B/gen/com/codio/tv"
cat > "$B/gen/com/codio/tv/BuildConfig.java" <<JAVA
package com.codio.tv;
public final class BuildConfig {
    public static final String VERSION = "$VERSION";
    /** The phone app (touch screens, downloads) instead of Android TV. */
    public static final boolean PHONE = ${PHONE_BOOL};
    private BuildConfig() {}
}
JAVA

# 2. Assets: web UI (no source maps / service worker), shim, CA bundle, subtitle font
rsync -a --exclude '*.map' --exclude 'service-worker.js' --exclude 'workbox-*.js' --exclude 'screenshots' \
  --exclude 'images/splash' "$WEB_BUILD"/ "$B/assets/web/"
cp "$HERE/assets/"* "$B/assets/"
# No Stremio artwork in Codio: plain images instead (same script as the iPhone build).
python3 "$HERE/../web/codio-build/neutral-images.py" "$B/assets/web"
# Auto-play never tries torrents (no streaming server on Android). Same change as web-changes/quality.ts, applied to the
# built bundle so it holds even with an older web build.
python3 - "$B/assets/web" <<'PY2'
import sys, glob
old = 'return"string"==typeof(null===(t=e.deepLinks)||void 0===t?void 0:t.player)}).map(function(e,E){var a=(0,t.streamText)(e)'
new = 'return"string"==typeof(null===(t=e.deepLinks)||void 0===t?void 0:t.player)&&"string"==typeof e.url&&/^https?:\\/\\//i.test(e.url)}).map(function(e,E){var a=(0,t.streamText)(e)'
hits = 0
for f in glob.glob(sys.argv[1] + '/*/scripts/main.js'):
    s = open(f, encoding='utf-8').read()
    if old in s:
        open(f, 'w', encoding='utf-8').write(s.replace(old, new, 1)); hits += 1
    elif 'typeof e.url&&/^https?:' in s:
        hits += 1
print('auto-play torrent filter:', 'applied' if hits else 'NOT FOUND (check web build)')
PY2
# A Play press must start even when the page was first opened only to load sources (?prefetch=1 = 'manual'):
# that earlier 'handled' decision used to stick, so Play showed the source list. Same change as web-changes/useAutoPlay.ts.
python3 - "$B/assets/web" <<'PY3'
import sys, glob
old = 'null!==D&&D.key===t||((0,_.wasHandled)(t)||"manual"===I?C({key:t,state:"handled",candidates:u,startedAt:Date.now()}):L&&0!==u.length&&C({key:t,state:"countdown",candidates:u,startedAt:Date.now()}))'
new = '(function(){var pp="immediate"===I&&null!==D&&D.key===t&&D.mode!==I&&"handled"===D.state;null!==D&&D.key===t&&!pp||("manual"===I||!pp&&(0,_.wasHandled)(t)?C({key:t,mode:I,state:"handled",candidates:u,startedAt:Date.now()}):L&&0!==u.length&&C({key:t,mode:I,state:"countdown",candidates:u,startedAt:Date.now()}))})()'
hits = 0
for f in glob.glob(sys.argv[1] + '/*/scripts/main.js'):
    s = open(f, encoding='utf-8').read()
    if s.count(old) == 1:
        open(f, 'w', encoding='utf-8').write(s.replace(old, new, 1)); hits += 1
    elif 'D.mode!==I&&"handled"===D.state' in s:
        hits += 1
print('auto-play Play-after-prefetch fix:', 'applied' if hits else 'NOT FOUND (check web build)')
if not hits: sys.exit(1)
PY3
# Faster starts: a Play press waits at most 3 s for slow addons, and a dead source costs 20 s. Same change as
# web-changes/useAutoPlay.ts and settings.ts, applied to the built bundle.
python3 - "$B/assets/web" <<'PY4'
import sys, glob
pairs = [
  ('1e3*Math.max(0,A.addonWaitSeconds));return function(){return clearTimeout(e)}}},[O,c,N,A.addonWaitSeconds,t])',
   '1e3*Math.max(0,"immediate"===I?Math.min(A.addonWaitSeconds,3):A.addonWaitSeconds));return function(){return clearTimeout(e)}}},[O,c,N,A.addonWaitSeconds,t,I])'),
  ('countdownSeconds:3,addonWaitSeconds:8,skipOnError:!0,startupTimeoutSeconds:30,',
   'countdownSeconds:3,addonWaitSeconds:8,skipOnError:!0,startupTimeoutSeconds:20,'),
]
hits = 0
for f in glob.glob(sys.argv[1] + '/*/scripts/main.js'):
    s = open(f, encoding='utf-8').read()
    for old, new in pairs:
        if s.count(old) == 1:
            s = s.replace(old, new, 1); hits += 1
        elif new in s:
            hits += 1
    open(f, 'w', encoding='utf-8').write(s)
print('faster-start patches:', hits, 'of', len(pairs))
if hits != len(pairs): sys.exit(1)
PY4
# Dolby Vision first (within the same quality and cache status) when the TV plays real Dolby Vision: the shim's
# __codioDvRank says 0 for a Dolby Vision source this TV can show (not a Blu-ray remux), else 1.
python3 - "$B/assets/web" <<'PY5'
import sys, glob
old = 'return l(e)-l(t)||I(e)-I(t)||e.index-t.index'
new = 'return l(e)-l(t)||I(e)-I(t)||(window.__codioDvRank?window.__codioDvRank(e.text)-window.__codioDvRank(t.text):0)||e.index-t.index'
hits = 0
for f in glob.glob(sys.argv[1] + '/*/scripts/main.js'):
    s = open(f, encoding='utf-8').read()
    if s.count(old) == 1:
        open(f, 'w', encoding='utf-8').write(s.replace(old, new, 1)); hits += 1
    elif new in s:
        hits += 1
print('Dolby Vision ranking:', 'applied' if hits else 'NOT FOUND (check web build)')
if not hits: sys.exit(1)
PY5
# The avatar images ship at 1400px but are shown at a few dozen pixels; shrink them to keep the APK small.
python3 - "$B/assets/web/images" <<'PY' || true
import sys, os
from PIL import Image
d = sys.argv[1]
for f in ("default_avatar.png", "anonymous.png"):
    p = os.path.join(d, f)
    if os.path.exists(p):
        im = Image.open(p)
        if max(im.size) > 384:
            im.thumbnail((384, 384), Image.LANCZOS)
            im.save(p, optimize=True)
PY

# 3. Resources + manifest (DEBUGGABLE=1 makes a test build that a debugger can attach to)
MANIFEST="$HERE/AndroidManifest.xml"
[ "$PHONE" = "1" ] && MANIFEST="$HERE/AndroidManifest-phone.xml"
if [ "${DEBUGGABLE:-0}" = "1" ]; then
  sed 's/android:allowBackup="false"/android:allowBackup="false"\n        android:debuggable="true"/' "$MANIFEST" > "$B/AndroidManifest.xml"
  MANIFEST="$B/AndroidManifest.xml"
fi
aapt2 compile --dir "$HERE/res" -o "$B/res/res.zip"
OVERLAY=()
if [ "$PHONE" = "1" ]; then
  aapt2 compile --dir "$HERE/res-phone" -o "$B/res/res-phone.zip"
  OVERLAY=(-R "$B/res/res-phone.zip" --rename-manifest-package com.codio.phone)
fi
aapt2 link -o "$B/base.apk" -I "$ANDROID_JAR" --manifest "$MANIFEST" \
  --min-sdk-version 26 --target-sdk-version 34 --version-code "$VERSION_CODE" --version-name "$VERSION" --replace-version \
  --java "$B/gen" -A "$B/assets" "$B/res/res.zip" "${OVERLAY[@]}"

# 4. Java -> dex
find "$HERE/src" "$B/gen" "$VLC_SRC" -name '*.java' > "$B/sources.txt"
javac -nowarn --release 8 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$B/obj" @"$B/sources.txt" 2>&1 | grep -v "^warning: \[options\]\|^1 warning\|^3 warnings" || true
test -f "$B/obj/com/codio/tv/MainActivity.class"
dalvik-exchange --dex --min-sdk-version=26 --output="$B/dex/classes.dex" "$B/obj"

# 5. Native libs: libVLC first, then libmpv (whose newer libc++_shared.so serves both)
unzip -q -o "$LIBVLC_AAR" 'jni/*' -d "$B/vlcaar"
unzip -q -o "$LIBMPV_AAR" 'jni/*' -d "$B/aar"
for abi in $ABIS; do
  mkdir -p "$B/lib/lib/$abi"
  cp "$B/vlcaar/jni/$abi/"*.so "$B/lib/lib/$abi/"
  cp "$B/aar/jni/$abi/"*.so "$B/lib/lib/$abi/"
done

# 6. Assemble, align, sign
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -X "$B/unsigned.apk" classes.dex)
(cd "$B/lib" && zip -q -X -r "$B/unsigned.apk" lib)
zipalign -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias codio \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Codio, O=Codio, C=US" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --ks-key-alias codio \
  --min-sdk-version 26 --out "$OUT/$APK_NAME-$VERSION${SUFFIX:-}.apk" "$B/aligned.apk"
apksigner verify --min-sdk-version 26 "$OUT/$APK_NAME-$VERSION${SUFFIX:-}.apk"
ls -la "$OUT/$APK_NAME-$VERSION${SUFFIX:-}.apk"
