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
# Access lock (lock/README.md): only when lock/access-lock.json exists.
[ -f "$HERE/../lock/access-lock.json" ] && cp "$HERE/../lock/access-lock.json" "$B/assets/"
# No Stremio artwork in Codio: plain images instead (same script as the iPhone build).
python3 "$HERE/../web/codio-build/neutral-images.py" "$B/assets/web"
# Codio's changes to the built engine (no torrents, Play after prefetch, faster start, ranking, chosen episode).
python3 "$HERE/../web/codio-build/patch-bundle.py" "$B/assets/web" android
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
