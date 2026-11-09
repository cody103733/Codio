# Codio

Codio is a personal streaming app built on the Stremio web UI (GPL-2.0). It has Smart auto-play: it picks your quality, starts the best source and moves to the next source when one fails. It also has Skip Intro and a cast button. It plays only http and debrid sources, never torrents.

This repository holds the **iPhone** app. The Windows, Android TV and Android phone builds live in the project folder.

## iPhone app (`ios/`)

- `ios/web/` is the built Codio web UI, taken from Codio for Windows 1.5.2 with source maps removed.
- `ios/Codio/LocalServer.swift` serves that UI on `http://127.0.0.1:11471/`, the same address Windows uses, plus `/api/skip` for Skip Intro times from IntroDB.
- `ios/Codio/VlcShell.swift` plays video with VLC (MobileVLCKit) and speaks the protocol of Stremio's Windows shell. Because of that, the web player drives it unchanged, including auto-play, source fallback and progress saving.
- `ios/Codio/codio-ios.js` is injected into the page. It provides `window.chrome.webview` (the shell IPC) and sets the first-run auto-play defaults.

Next up: native SwiftUI screens replace the web screens. The web page then stays hidden and only handles addons and source picking, the same way the Android phone app works.

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
