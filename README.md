# Codio

**A personal, unofficial fork of [Stremio Web](https://github.com/Stremio/stremio-web), provided as is, with no warranty and no support. It is not intended for general use.**

Codio is not affiliated with, endorsed by or supported by Stremio. If you are looking for Stremio, use the official app at https://www.stremio.com.

It is public only so its owner can install updates on their own devices, and so its source is available as the license requires. Issues and pull requests aren't monitored.

## License

GPL-2.0, the same as Stremio Web. See [LICENSE.md](LICENSE.md). The original copyright notices are kept, and [NOTICE.md](NOTICE.md) lists what was changed and the third-party parts included.

Codio ships no content and no add-ons. It plays only http(s) sources from add-ons the user installs, never torrents, and has no DRM or decryption code.

## What's here

- `web/`: the modified Stremio Web source. It runs hidden as the engine of the apps (account, add-ons, library, finding sources, auto-play).
- `ios/`: the iPhone app (native SwiftUI screens, VLCKit player). See [ios/README.md](ios/README.md).
- `android/`: the Android TV and phone app. See [android/README.md](android/README.md).
- `builds/` and `updates.json`: the Android builds and the list the apps check for updates.
- `apps.json` and `icon.png`: the update list the iPhone app checks.

iPhone builds are on the Releases page. The source of any build is this repository at the commit or tag that published it.
