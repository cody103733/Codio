// Codio for iPhone: glue between the Codio web UI and the native app.
// Injected before any page script runs (WKUserScript, document start).
(function () {
    'use strict';
    var native = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.codio;
    if (!native) return;
    window.__codioIos = true;
    window.__codioAndroid = true; // only hides the "streaming server not found" banner (no server on a phone)

    // First run: Smart auto-play on, sources hidden (Codio's Netflix-style default), bitrate cap 70 Mbit/s
    // (changeable in Settings). Torrents are never used: Codio only plays http/debrid sources.
    try {
        var ap = JSON.parse(window.localStorage.getItem('autoPlaySettings') || 'null');
        if (!ap) ap = { enabled: true, hideSources: true };
        if (typeof ap.maxBitrateMbps !== 'number') ap.maxBitrateMbps = 70;
        window.localStorage.setItem('autoPlaySettings', JSON.stringify(ap));
    } catch (e) { /* storage unavailable */ }

    // ---- Shell IPC: the interface Stremio's Windows shell (WebView2) exposes, so the web UI's
    // ShellVideo player drives the native VLC player.
    var listeners = [];
    window.chrome = window.chrome || {};
    window.chrome.webview = {
        postMessage: function (message) {
            native.postMessage(typeof message === 'string' ? message : JSON.stringify(message));
        },
        addEventListener: function (type, listener) {
            if (type === 'message' && listeners.indexOf(listener) === -1) listeners.push(listener);
        },
        removeEventListener: function (type, listener) {
            var i = listeners.indexOf(listener);
            if (i !== -1) listeners.splice(i, 1);
        }
    };
    window.__codioShellMessage = function (data) {
        listeners.slice().forEach(function (listener) {
            try {
                listener({ data: data });
            } catch (e) {
                console.error('Codio shell listener failed', e);
            }
        });
    };

    // ---- Phone styling: video shows through the page, no long-press menus or text selection on posters.
    var addStyle = function () {
        var style = document.createElement('style');
        style.textContent = [
            'html { background-color: transparent !important; }',
            'body { background-color: #0e0e10; -webkit-touch-callout: none; -webkit-user-select: none; user-select: none; -webkit-tap-highlight-color: transparent; }',
            'input, textarea { -webkit-user-select: text; user-select: text; }',
            '::-webkit-scrollbar { width: 0 !important; height: 0 !important; }',
            '[title="Enter fullscreen mode"], [title="Exit fullscreen mode"] { display: none !important; }'
        ].join('\n');
        (document.head || document.documentElement).appendChild(style);
    };
    if (document.head) addStyle();
    else document.addEventListener('DOMContentLoaded', addStyle);

    // The player page must be see-through so the VLC picture underneath shows.
    var syncPlayer = function () {
        var onPlayer = /^#\/player\//.test(window.location.hash);
        document.documentElement.classList.toggle('codio-ios-player', onPlayer);
        if (document.body) document.body.style.backgroundColor = onPlayer ? 'transparent' : '';
    };
    window.addEventListener('hashchange', syncPlayer);
    document.addEventListener('DOMContentLoaded', syncPlayer);
})();
