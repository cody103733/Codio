# Codio for Windows

The Windows version of Codio. It is a separate copy of the modified Stremio Web source, with Codio's own screens (`src/routes/Codio/`) instead of running the engine hidden like the phone and TV apps.

- `src/`, `assets/` and the other top-level files: the web interface, built with `pnpm build`.
- `desktop/codio-web.js` and `desktop/codio-downloads.js`: the small local server that serves the interface on 127.0.0.1:11471 and saves downloads. It runs on an unmodified Node.js `node.exe`.
- `desktop/shell/`: Codio.exe, the desktop window (WebView2 for the interface, libmpv for video). See [desktop/shell/README.md](desktop/shell/README.md).
- `desktop/installer/codio.nsi` and `desktop/package.ps1`: packaging and the NSIS installer script.

There is no streaming server. Only http(s) sources (debrid and HTTP add-ons) are played, and they go straight to the player.
