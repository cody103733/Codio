# Codio for Windows

The Windows version of Codio, with Codio's own screens (`src/routes/Codio/`) instead of running the engine hidden like the phone and TV apps.

It is built on the web source in [`../web`](../web). This folder holds only the files Windows changes or adds; everything else comes from `../web`.

- `assemble.js`: lays `../web` and then this folder over each other in `.app/` (not in git). Build there: `node assemble.js`, then `pnpm install --frozen-lockfile` and `pnpm build` inside `.app/`. To change a file that is not in this folder yet, copy it here from `../web` at the same path and edit the copy.
- `src/`, `assets/`, `manifest.json`, `webpack.config.js`: the Windows versions of those files.
- `desktop/codio-web.js` and `desktop/codio-downloads.js`: the small local server that serves the interface on 127.0.0.1:11471 and saves downloads. It runs on an unmodified Node.js `node.exe`.
- `desktop/shell/`: Codio.exe, the desktop window (WebView2 for the interface, libmpv for video). See [desktop/shell/README.md](desktop/shell/README.md).
- `desktop/installer/codio.nsi` and `desktop/package.ps1`: packaging and the NSIS installer script.

There is no streaming server. Only http(s) sources (debrid and HTTP add-ons) are played, and they go straight to the player.
