# Codio.exe

Codio's desktop window for Windows, written for Codio (GPL-2.0-or-later).

- `src/main.rs`: the window. The Codio interface runs in WebView2 on top, with a transparent background, and the mpv player draws into a child window underneath. It starts `codio-web.js` on the bundled Node.js (`codio-runtime.exe`), which serves the interface on 127.0.0.1:11471. There is no streaming server: http(s) sources are handed straight to mpv.
- `src/mpv.rs`: a small binding to libmpv's client API. `libmpv-2.dll` is loaded at run time from the folder Codio.exe is in.
- `res/`: icon and application manifest. `build.rs` compiles them with `windres`.

The interface and the window exchange JSON messages over `chrome.webview` (described at the top of `src/main.rs`). These are the messages Stremio Web's shell transport and `@stremio/stremio-video`'s ShellVideo use.

## Building

The target is `x86_64-pc-windows-gnullvm`, with [llvm-mingw](https://github.com/mstorsjo/llvm-mingw) (ucrt, x86_64):

- `rustup target add x86_64-pc-windows-gnullvm`
- `CARGO_TARGET_X86_64_PC_WINDOWS_GNULLVM_LINKER`: llvm-mingw's `x86_64-w64-mingw32-clang.exe`
- `WINDRES`: llvm-mingw's `x86_64-w64-mingw32-windres.exe`
- `cargo build --release --target x86_64-pc-windows-gnullvm`

On a Windows host with the `-gnu` Rust toolchain, link the build scripts with Rust's own MinGW gcc (`CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER`), not llvm-mingw's.

At run time Codio.exe needs `WebView2Loader.dll` (shipped with the `webview2-com-sys` crate) and `libmpv-2.dll` next to it, plus the WebView2 Runtime that comes with Windows 10 and 11.
