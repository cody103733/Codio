# Building Codio for Android TV

No Gradle. `build.sh` uses aapt2, javac, dx (d8 works too), zipalign and apksigner.

1. Build the Codio web UI: `SERVICE_WORKER_DISABLED=true pnpm build` in the web source (needs web-changes/ applied).
2. Get libmpv-android 1.0.0 (`dev.jdtech.mpv:libmpv:1.0.0` AAR from Maven Central).
3. Run:
   WEB_BUILD=/path/to/web/build LIBMPV_AAR=/path/to/libmpv-1.0.0.aar KEYSTORE=/path/to/codio-android.keystore ./build.sh
   The APK lands in out/. Keep using the same keystore (pass its password as KS_PASS) so updates install over the old app. The keystore is not in this repository.

The layout is:
- src/com/codio/tv/MainActivity.java: a WebView over a video surface, plus remote keys and 4K display mode.
- src/com/codio/tv/MpvShell.java: mpv speaks the same protocol as Stremio's Windows shell.
- src/com/codio/tv/LocalServer.java: serves the web UI on 127.0.0.1.
- assets/codio-android.js: remote/D-pad navigation and the IPC bridge, injected into the page.
