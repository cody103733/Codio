# Notices

Codio is a modified version of **Stremio Web**, https://github.com/Stremio/stremio-web
Copyright © 2017-2026 Smart Code OOD and contributors. Licensed under the GNU General Public License, version 2 (see [LICENSE.md](LICENSE.md)).

Codio is not made, endorsed or supported by Stremio or Smart Code OOD. "Stremio" is their name and trademark; it is used here only to say where the code comes from. Codio does not use Stremio's logos. See [DISCLAIMER.md](DISCLAIMER.md) for the full disclaimer and trademark notes, and [PRIVACY.md](PRIVACY.md) for what the apps send where.

## What was changed

Changes by Cody, November 2026. The per-file history is in this repository's commits.

- `web/`: the Stremio Web source, forked at upstream commit `55d8b33` (2026-11-03). Added Smart auto-play (preferred quality, next source on failure), the Codio look and logo, hooks that let Codio's native screens drive the engine, a patch to `@stremio/stremio-video` (see `web/patches/`), and a filter so only http(s) sources are used, never torrents. Stremio's artwork (logo, mascot and placeholder pictures, splash screens) was replaced with plain images. The original copyright headers in the source files are unchanged.
- `web/codio-build/`: the script that turns `web/` into the engine that ships in the iPhone app (`ios/web`), including two small patches applied to the built `main.js`.
- `ios/`: Codio's own iPhone app (SwiftUI), which runs the engine hidden in a WKWebView. Same license as the rest (GPL-2.0).
- `android/`: Codio's own Android TV and phone app, which runs the same engine hidden in a WebView. Same license (GPL-2.0). `android/vlc-src/` holds libVLC's Java sources with Codio's changes (see its README).
- `windows/`: the Windows interface, a separate copy of the Stremio Web source with Codio's own screens, the same auto-play and artwork changes, and no streaming server; plus `windows/desktop/`: Codio's own desktop window (`desktop/shell`, written for Codio, not based on Stremio's shell), the local interface server and the installer script. Same license (GPL-2.0).

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
| libmpv (`libmpv-2.dll`, from the `shinchiro/mpv-winbuild-cmake` dev builds; the exact build is in each release's CI log), unmodified | Windows build | GPL (mpv is GPL-2.0-or-later; FFmpeg and the other libraries built into the DLL keep their own GPL/LGPL licenses). Sources: https://github.com/mpv-player/mpv and the build recipe at https://github.com/shinchiro/mpv-winbuild-cmake |
| FFmpeg 4.4.1 (Jellyfin build: `ffmpeg.exe`, `ffprobe.exe` and their DLLs), unmodified | Windows installers before 1.6.1 only (removed with the streaming server) | GPL-3.0-or-later (built with --enable-gpl --enable-version3). Source: https://github.com/jellyfin/jellyfin-ffmpeg |
| Node.js 22.x `node.exe` (official build), unmodified, shipped as `codio-runtime.exe` | Windows build | MIT and the licenses of its bundled parts. Source: https://github.com/nodejs/node |
| Microsoft WebView2 loader (`WebView2Loader.dll`) | Windows build | Microsoft WebView2 SDK license (redistributable), via the MIT `webview2-com` crate |
| Rust crates (`webview2-com`, `windows`, `serde_json`) | inside `Codio.exe`, listed in `windows/desktop/shell/Cargo.lock` | MIT / Apache-2.0 |
| NSIS installer stub | `CodioSetup-<version>.exe` | zlib/libpng license |

VLCKit is linked as a separate dynamic framework, so it can be swapped for another build of VLCKit 4.0.0a26 by replacing `Frameworks/VLCKit.framework` in the ipa and re-signing it.

## Data and online services

Codio ships none of the data below; the apps fetch it while you use them. Each service has its own terms, and Codio is not endorsed by any of them.

| Service | Used for | Attribution and terms |
| --- | --- | --- |
| [TMDB](https://www.themoviedb.org) | Cast photos, recommendations, trailer ids (with the user's own API key) | This product uses the TMDB API but is not endorsed or certified by TMDB. The apps show this notice next to the TMDB key setting and in About. [TMDB API terms](https://www.themoviedb.org/api-terms-of-use) |
| Stremio and its default addons (Cinemeta, `images.metahub.space`) | Account, library, catalogs and artwork, through Stremio's engine | [Stremio terms](https://www.stremio.com/tos) |
| [YouTube](https://www.youtube.com) | Trailers, played in the YouTube app or YouTube's own embedded player, never downloaded | [YouTube Terms of Service](https://www.youtube.com/t/terms) |
| [IntroDB](https://introdb.app) | Intro, recap and credit times for Skip Intro | Community data from introdb.app |
| [MusicBrainz](https://musicbrainz.org) | Artists, albums and track lists | Core data CC0; supplementary data CC BY-NC-SA 3.0. Requests carry Codio's User-Agent and are rate-limited as MusicBrainz asks. |
| [Cover Art Archive](https://coverartarchive.org) | Album covers | Images belong to their copyright holders |
| [ListenBrainz](https://listenbrainz.org) | Optional scrobbling and recommendations (Daily mix), with the user's own token | Data CC0 |
| Apple (iTunes Search API, Apple's public RSS chart feeds) | Default music charts, artist and album lookups; chart rows link back to Apple Music | [Apple Media Services terms](https://www.apple.com/legal/internet-services/itunes/) |
| [Last.fm](https://www.last.fm) | Optional Popular rows and charts, with the user's own API key | [Last.fm API terms](https://www.last.fm/api/tos) |
| [Deezer API](https://developers.deezer.com) | Artist photos and song matching | [Deezer API terms](https://developers.deezer.com/termsofuse) |
| [LRCLIB](https://lrclib.net) | Synced lyrics | Lyrics belong to their copyright holders |
| Music addons (Eclipse Music addon protocol) | Audio, from addons the user adds | Codio only implements the public protocol at https://eclipsemusic.app/docs; it ships no addon |

## Getting the source of a build

Every iPhone build is a GitHub Release of this repository, tagged `ios-v<version>-build<n>`. The source of that build is this repository at that tag: `ios/` plus `web/` (see `web/codio-build/build-ios.sh`).

The Android TV and phone builds are in `builds/`. Their source is this repository at the commit that added that build: `android/` plus `web/` (see `android/BUILDING.md`).

Every Windows installer is a GitHub Release tagged `windows-v<version>`. Its source is this repository at that tag: `windows/` (an overlay on `web/`, see `windows/assemble.js`) plus `windows/desktop/`.
