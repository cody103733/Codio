# Notices

Codio is a modified version of **Stremio Web**, https://github.com/Stremio/stremio-web
Copyright © 2017-2026 Smart Code OOD and contributors. Licensed under the GNU General Public License, version 2 (see [LICENSE.md](LICENSE.md)).

Codio is not made, endorsed or supported by Stremio or Smart Code OOD. "Stremio" is their name and trademark; it is used here only to say where the code comes from. Codio does not use Stremio's logos.

## What was changed

Changes by Cody, November 2026. The per-file history is in this repository's commits.

- `web/`: the Stremio Web source, forked at upstream commit `55d8b33` (2026-11-03). Added Smart auto-play (preferred quality, next source on failure), the Codio look and logo, hooks that let Codio's native screens drive the engine, a patch to `@stremio/stremio-video` (see `web/patches/`), and a filter so only http(s) sources are used, never torrents. Stremio's artwork (logo, mascot and placeholder pictures, splash screens) was replaced with plain images. The original copyright headers in the source files are unchanged.
- `web/codio-build/`: the script that turns `web/` into the engine that ships in the iPhone app (`ios/web`), including two small patches applied to the built `main.js`.
- `ios/`: Codio's own iPhone app (SwiftUI), which runs the engine hidden in a WKWebView. Same license as the rest (GPL-2.0).
- `android/`: Codio's own Android TV and phone app, which runs the same engine hidden in a WebView. Same license (GPL-2.0). `android/vlc-src/` holds libVLC's Java sources with Codio's changes (see its README).
- `windows/`: the Windows interface, a separate copy of the Stremio Web source with Codio's own screens, the same auto-play and artwork changes, and no streaming server; plus `windows/desktop/`, Codio's local interface server and installer script. Same license (GPL-2.0).

## Third-party parts in the builds

| Part | Where | License |
| --- | --- | --- |
| Stremio Web | `web/`, built into `ios/web` | GPL-2.0 |
| @stremio/stremio-core-web 0.66.0 (WebAssembly) | inside `ios/web` | MIT |
| @stremio/stremio-video 0.0.103 (patched) | inside `ios/web` | MIT |
| Other npm packages | inside `ios/web`, listed in `web/pnpm-lock.yaml` | their own licenses |
| VLCKit 4.0.0a26, unmodified, from CocoaPods | `Frameworks/VLCKit.framework` in the ipa | LGPL-2.1-or-later. Source: https://code.videolan.org/videolan/VLCKit |
| libVLC 3.7.7 (`org.videolan.android:libvlc-all`), Java part modified in `android/vlc-src`, native libraries unmodified | Android APKs | LGPL-2.1-or-later. Source: https://code.videolan.org/videolan/vlc-android |
| libmpv 1.0.0 (`dev.jdtech.mpv:libmpv`), unmodified | Android APKs | GPL-2.0-or-later / LGPL. Source: https://github.com/jarnedemeulemeester/libmpv-android |
| Mozilla CA certificate bundle (`android/assets/cacert.pem`) | Android APKs | MPL-2.0 |
| DejaVu Sans font (`android/assets/subfont.ttf`, subtitles) | Android APKs | Bitstream Vera / DejaVu font license (free to use and redistribute) |
| Plus Jakarta Sans font | `ios/web/fonts` | SIL Open Font License 1.1 |
| Twemoji flag glyphs | `ios/web/fonts/TwemojiFlags.woff2` | CC-BY 4.0, © Twitter, Inc. and other contributors |

VLCKit is linked as a separate dynamic framework, so it can be swapped for another build of VLCKit 4.0.0a26 by replacing `Frameworks/VLCKit.framework` in the ipa and re-signing it.

## Getting the source of a build

Every iPhone build is a GitHub Release of this repository, tagged `ios-v<version>-build<n>`. The source of that build is this repository at that tag: `ios/` plus `web/` (see `web/codio-build/build-ios.sh`).

The Android TV and phone builds are in `builds/`. Their source is this repository at the commit that added that build: `android/` plus `web/` (see `android/BUILDING.md`).
