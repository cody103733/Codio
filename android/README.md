# Codio for Android TV and phones (`android/`)

One source tree builds both apps: `./build.sh` for Android TV (`com.codio.tv`) and `PHONE=1 ./build.sh` for phones (`com.codio.phone`). No Gradle; see [BUILDING.md](BUILDING.md) and the top of `build.sh` for what it needs.

- Native screens (`src/com/codio/tv/ui/`) drawn on a Canvas for TV and with plain Android views for phones.
- The engine is the web UI from `../web`, built with `SERVICE_WORKER_DISABLED=true pnpm build` and passed as `WEB_BUILD`. `build.sh` applies the same two `main.js` patches and plain images as the iPhone build.
- Playback uses libVLC 3.7.7 (`org.videolan.android:libvlc-all`, LGPL-2.1+; its Java sources are in `vlc-src/` with Codio's changes listed in `vlc-src/README`) and libmpv 1.0.0 (`dev.jdtech.mpv:libmpv`, GPL/LGPL), both from Maven Central.
- Updates: `Updater.java` reads `updates.json` at the repo root, downloads the APK from `builds/` and hands it to Android's installer. Settings has "Check now".
- Cast photos use the viewer's own TMDB API key, typed into Settings. No key is built in.

Builds are signed with Codio's own key, which is not in this repository.
