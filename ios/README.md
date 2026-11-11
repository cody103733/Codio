# Codio for iPhone (`ios/`)

Since 0.2.0 every screen is native SwiftUI (`ios/Codio/UI/`): Home, Detail (episodes, My List, sources), Search, My List, Settings, the loading screen and the player controls with Skip Intro / Next Episode. It works the same way as the Android phone app:

- `ios/web/` is the Codio web UI's Android build (1.4.0, with the native-UI hooks and Codio's patches for no torrents and Play-after-prefetch). It runs hidden in a WKWebView as the engine: account, addons, library, finding sources, and Smart auto-play with source fallback.
- `ios/Codio/codio-engine.js` is the Android shim (`codio-android.js`) unchanged. `Engine.swift` adds a `window.CodioNative` bridge in front of it, so the native screens drive the engine through the same hooks (`__codioNativeGo`, `__codioDispatch`, `__codioStreams`, ...).
- `ios/Codio/LocalServer.swift` serves the engine on `http://127.0.0.1:11471/` and puts the shim into index.html.
- `ios/Codio/Player/VlcShell.swift` plays video with VLC (MobileVLCKit) and speaks the protocol of Stremio's Windows shell, so the hidden web player drives it and keeps saving progress.
- `ios/Codio/Store/` reads addons and the library from the engine's storage and loads catalogs and details straight from the addons.

## Building

There's no Mac involved. Every push to `main` runs `.github/workflows/ios.yml` on a GitHub macOS runner, which does:

1. `xcodegen generate` and `pod install` (MobileVLCKit)
2. An unsigned `xcodebuild` for iphoneos
3. Zips `Payload/Codio.app` into `Codio-<version>.ipa`
4. Publishes the ipa as a GitHub Release of this repo (`ios-v<version>-build<n>`) and adds it to the SideStore source (`apps.json` at the repo root)

To bump the version, edit `MARKETING_VERSION` in `ios/project.yml`. To rebuild `ios/web` from source, run `bash web/codio-build/build-ios.sh`.

## Installing on the iPhone (SideStore, free Apple ID)

1. In SideStore, open Sources, tap **+**, and add `https://raw.githubusercontent.com/cody103733/Codio/main/apps.json`. New builds then show up with an Update button, and Codio shows an "Update available" banner on Home.
2. Or download the newest `Codio-x.y.z.ipa` from the Releases page and add it in SideStore > My Apps > **+**.
3. Free Apple ID apps expire after 7 days. Refresh in SideStore (LocalDevVPN on) before then.
4. To update, install the newer ipa the same way. Your sign-in and addons stay, because they are kept per app.
