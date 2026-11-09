# Codio

Codio is a personal streaming app built on the Stremio web UI (GPL-2.0). It has Smart auto-play: it picks your quality, starts the best source and moves to the next source when one fails. It also has Skip Intro and a cast button. It plays only http and debrid sources, never torrents.

This repository holds the **iPhone** app. The Windows, Android TV and Android phone builds live in the project folder.

## iPhone app (`ios/`)

Since 0.2.0 every screen is native SwiftUI (`ios/Codio/UI/`): Home, Detail (episodes, My List, sources), Search, My List, Settings, the loading screen and the player controls with Skip Intro / Next Episode. It works the same way as the Android phone app:

- `ios/web/` is the Codio web UI's Android build (1.4.0, with the native-UI hooks and Codio's patches for no torrents and Play-after-prefetch). It runs hidden in a WKWebView as the engine: account, addons, library, finding sources, and Smart auto-play with source fallback.
- `ios/Codio/codio-engine.js` is the Android shim (`codio-android.js`) unchanged. `Engine.swift` adds a `window.CodioNative` bridge in front of it, so the native screens drive the engine through the same hooks (`__codioNativeGo`, `__codioDispatch`, `__codioStreams`, ...).
- `ios/Codio/LocalServer.swift` serves the engine on `http://127.0.0.1:11471/` and puts the shim into index.html.
- `ios/Codio/Player/VlcShell.swift` plays video with VLC (MobileVLCKit) and speaks the protocol of Stremio's Windows shell, so the hidden web player drives it and keeps saving progress.
- `ios/Codio/Store/` reads addons and the library from the engine's storage and loads catalogs and details straight from the addons.

### Building

There's no Mac involved. Every push to `main` runs `.github/workflows/ios.yml` on a GitHub macOS runner, which does:

1. `xcodegen generate` and `pod install` (MobileVLCKit)
2. An unsigned `xcodebuild` for iphoneos
3. Zips `Payload/Codio.app` into `Codio-<version>.ipa`
4. Publishes the ipa as a GitHub Release (`ios-v<version>-build<n>`) and as a workflow artifact

To bump the version, edit `MARKETING_VERSION` in `ios/project.yml`.

### Installing on the iPhone (SideStore, free Apple ID)

1. On the iPhone, open this repo's **Releases** page in Safari while signed in to GitHub. Download the newest `Codio-x.y.z.ipa`; it goes to Files > Downloads.
2. Open SideStore > My Apps > **+**, pick the ipa, and sign in with the spare Apple ID if asked.
3. Free Apple ID apps expire after 7 days. Refresh in SideStore (LocalDevVPN on) before then.
4. To update, install the newer ipa the same way. Your sign-in and addons stay, because they are kept per app.
