// Codio for Android TV: glue between the Codio web UI and the native app.
// Injected at the top of index.html by MainActivity before any other script runs.
(function () {
    'use strict';
    var native = window.CodioNative;
    if (!native) return;
    window.__codioAndroid = true;
    // First run on a TV: Smart auto-play with sources hidden (a remote can't wade through hundreds of sources).
    // On a TV that can show 4K, Smart auto-play aims for 4K (it still falls back to 1080p and below).
    try {
        var ap = JSON.parse(window.localStorage.getItem('autoPlaySettings') || 'null');
        if (!ap) ap = { enabled: true, hideSources: true };
        if (!ap.preferredQuality && window.__codioTv4k) ap.preferredQuality = '2160p';
        // Home internet rarely sustains the biggest 4K remuxes (100+ Mbit/s); prefer sources that play without stalls.
        if (typeof ap.maxBitrateMbps !== 'number') ap.maxBitrateMbps = 70;
        window.localStorage.setItem('autoPlaySettings', JSON.stringify(ap));
    } catch (e) { /* storage unavailable */ }
    // Set on any key press; cleared when a new page opens (see the address watcher below).
    var userMoved = false;
    window.addEventListener('keydown', function (e) {
        // Keys that only pick a starting point don't count as moving.
        if (!nothingFocusedEarly()) userMoved = true;
    }, true);
    var nothingFocusedEarly = function () {
        var a = document.activeElement;
        return !a || a === document.body || a.id === 'app';
    };

    // ---- Shell IPC: the same interface Stremio's Windows shell (WebView2) exposes, so the
    // web UI's ShellVideo player drives the native mpv player.
    var listeners = [];
    if (window.__codioHasMpv) {
        var ipc = {
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
        window.chrome = window.chrome || {};
        window.chrome.webview = ipc;
    }
    window.__codioShellMessage = function (data) {
        listeners.slice().forEach(function (listener) {
            try {
                listener({ data: data });
            } catch (e) {
                console.error('Codio shell listener failed', e);
            }
        });
    };
    var signal = function (name, arg) {
        window.__codioShellMessage(JSON.stringify({ id: 1, object: 'transport', type: 1, args: [name, arg] }));
    };

    // ---- Styling for a TV: video shows through the page, clear focus ring for the remote.
    var style = document.createElement('style');
    style.textContent = [
        'html { background-color: transparent !important; }',
        'body { background-color: #0e0e10; }',
        ':focus-visible { outline: 3px solid #E50914 !important; outline-offset: 2px; }',
        '::-webkit-scrollbar { width: 0 !important; height: 0 !important; }',
        '[title="Enter fullscreen mode"], [title="Exit fullscreen mode"] { display: none !important; }',
        // Posters grow when focused and would cover an outline on the link, so ring the poster itself.
        '[class*="meta-item-link"]:focus-visible { outline: none !important; }',
        '[class*="logo-container"]:focus-visible { outline-offset: -4px !important; }',
        '[class*="meta-item-link"]:focus-visible [class*="poster-container"] { box-shadow: 0 0 0 4px #E50914 !important; }',
        // Rows: show every title and slide sideways (Netflix style) instead of cutting off after a screenful.
        '[class*="meta-row-container"] [class*="meta-items-container"] { overflow-x: auto !important; overflow-y: hidden !important; scroll-behavior: auto; padding: 1.2rem 2.5rem 1.2rem 0; margin: -1.2rem 0; }',
        '[class*="meta-row-container"] [class*="meta-items-container"] > [class*="meta-item"] { display: block !important; flex: 0 0 15% !important; max-width: 15%; }',
        '[class*="meta-row-container"] [class*="meta-items-container"] > [class*="poster-shape-landscape"] { flex-basis: 26% !important; max-width: 26%; }',
        '[class*="meta-row-container"] [class*="meta-items-container"] > div:empty { display: none !important; }',
        // ---- Speed on TV hardware, the way Netflix/YouTube TV UIs are built: only transform and opacity ever animate,
        // nothing is blurred (a blur re-renders a whole region on the GPU each frame), shadows are solid, and rows
        // that are off screen aren't drawn at all.
        '*, *::before, *::after { backdrop-filter: none !important; -webkit-backdrop-filter: none !important; }',
        '[class*="background-image"], [class*="blurred"], [class*="backdrop"] { filter: none !important; -webkit-filter: none !important; }',
        '[class*="hero"] [class*="backdrop"][class*="poster-only"] { filter: brightness(0.45) !important; transform: none !important; }',
        '[class*="menu-layer"], [class*="auto-play-indicator"], [class*="side-drawer"] { background-color: rgba(18, 18, 22, 0.96) !important; }',
        '[class*="streams-list"], [class*="videos-list"] { background-color: rgba(12, 12, 14, 0.88) !important; }',
        '[class*="link-container"], [class*="icon-container"] { background-color: rgba(40, 40, 46, 0.9); }',
        '[class*="meta-item-container"], [class*="meta-item-container"] * { transition-property: transform, opacity !important; transition-duration: 120ms !important; }',
        '[class*="meta-item-container"] [class*="poster-container"] { box-shadow: none !important; }',
        '[class*="horizontal-nav-bar"] { box-shadow: none !important; }',
        '[class*="meta-row-container"] { content-visibility: auto; contain-intrinsic-size: auto 22rem; }'
    ].join('\n');
    document.head.appendChild(style);

    // ---- Keyboard helpers
    var key = function (name, code, keyCode) {
        var target = document.activeElement || document.body;
        var init = { key: name, code: code, keyCode: keyCode, which: keyCode, bubbles: true, cancelable: true };
        var down = new KeyboardEvent('keydown', init);
        var handled = !target.dispatchEvent(down);
        target.dispatchEvent(new KeyboardEvent('keyup', init));
        return handled;
    };
    var INTERACTIVE = 'a[href], button, input, textarea, select, [tabindex]:not([tabindex="-1"])';
    // True when nothing the remote can act on has focus (body, or a page container like #app).
    var nothingFocused = function () {
        var a = document.activeElement;
        return !a || a === document.body || a === document.documentElement || !a.matches || !a.matches(INTERACTIVE) ||
            (a.tagName === 'INPUT' && (a.type === 'file' || a.type === 'hidden'));
    };
    var onPlayer = function () {
        return /^#\/player\//.test(window.location.hash);
    };
    var onHome = function () {
        var h = window.location.hash;
        return h === '' || h === '#' || h === '#/' || /^#\/\?/.test(h);
    };

    // Remote media buttons (play/pause, fast-forward, ...)
    window.__codioMediaKey = function (action) {
        switch (action) {
            case 'seek-forward':
                if (onPlayer()) key('ArrowRight', 'ArrowRight', 39);
                break;
            case 'seek-backward':
                if (onPlayer()) key('ArrowLeft', 'ArrowLeft', 37);
                break;
            case 'stop':
                if (onPlayer()) window.history.back();
                break;
            default:
                if (window.__codioHasMpv) {
                    signal('media-key', action);
                } else if (onPlayer() && (action === 'play-pause' || action === 'play' || action === 'pause')) {
                    key(' ', 'Space', 32);
                }
                break;
        }
    };

    // ---- Player remote control: OK = play/pause, Left/Right = seek, Up/Down = into the controls.
    var inside = function (el, selector) {
        return !!(el && el.closest && el.closest(selector));
    };
    // Cheap on purpose (it runs on timers during playback): find the few layer elements, then look at their children.
    var menuOpen = function () {
        var layers = document.getElementsByClassName ? document.querySelectorAll('[class*="menu-layer"], [class*="side-drawer-layer"]') : [];
        for (var i = 0; i < layers.length; i++) if (layers[i].firstElementChild) return true;
        return false;
    };
    var playerFocusIdle = function () {
        var a = document.activeElement;
        if (nothingFocused() || isRange(a)) return true; // the seek/volume sliders don't count as "in the controls"
        return !inside(a, '[class*="control-bar-layer"], [class*="menu-layer"], [class*="side-drawer"], [class*="nav-bar-layer"], [class*="indicator-layer"]');
    };
    var wakeControls = function () {
        var target = document.querySelector('[class*="player-container"]') || document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
        if (target) target.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: window.innerWidth / 2, clientY: window.innerHeight / 2 }));
    };
    // The player's control bar is built for a mouse (tabindex -1); make its buttons reachable by the remote.
    var focusControls = function () {
        var bar = document.querySelector('[class*="control-bar-layer"]');
        if (!bar) return false;
        var buttons = bar.querySelectorAll('[class*="control-bar-button"], [class*="control-bar-buttons-menu-button"]');
        Array.prototype.forEach.call(buttons, function (b) {
            if (b.getAttribute('tabindex') === '-1') b.setAttribute('tabindex', '0');
        });
        var target = bar.querySelector('[title="Pause"], [title="Play"]') || buttons[0];
        if (target) {
            target.focus();
            return true;
        }
        return false;
    };
    var isRange = function (el) {
        return !!(el && (el.getAttribute && (el.getAttribute('role') === 'slider') || (el.tagName === 'INPUT' && el.type === 'range')));
    };
    setInterval(function () {
        if (!onPlayer()) return;
        var a = document.activeElement;
        if (!a || nothingFocused()) return;
        var layer = a.closest && a.closest('[class*="control-bar-layer"]');
        if (!layer) return;
        var st = window.getComputedStyle(layer);
        if (st.opacity === '0' || st.visibility === 'hidden' || /(^|\s)[^\s]*hidden/i.test(String(layer.className))) a.blur();
    }, 300);
    // A player menu (subtitles, audio, speed...) opened: its items are mouse-only, so make them reachable and
    // put the remote inside the menu, on the selected item.
    var MENU_ITEMS = '[class*="menu-layer"] [class*="option"], [class*="menu-layer"] [class*="button"], [class*="menu-layer"] [role="button"], [class*="menu-layer"] a[href]';
    var prepareMenu = function () {
        Array.prototype.forEach.call(document.querySelectorAll(MENU_ITEMS), function (el) {
            if (el.getAttribute('tabindex') !== '0' && !isRange(el)) el.setAttribute('tabindex', '0');
        });
    };
    var focusMenu = function () {
        prepareMenu();
        var layers = document.querySelectorAll('[class*="menu-layer"]');
        var layer = layers.length ? layers[layers.length - 1] : null; // the newest menu
        if (!layer) return false;
        var items = Array.prototype.filter.call(layer.querySelectorAll('[tabindex="0"]'), function (el) {
            var r = el.getBoundingClientRect();
            return r.width > 0 && r.height > 0;
        });
        var target = items.filter(function (el) { return /(^|\s)selected(\s|$)/.test(String(el.className)); })[0] || items[0];
        if (target) {
            target.focus({ preventScroll: false });
            return true;
        }
        return false;
    };
    var menuWasOpen = false;
    setInterval(function () {
        var open = onPlayer() && menuOpen();
        if (!open && !menuWasOpen) return;
        if (open) {
            prepareMenu();
            var a = document.activeElement;
            if (!menuWasOpen || !inside(a, '[class*="menu-layer"]')) {
                if (!menuWasOpen || nothingFocused() || inside(a, '[class*="control-bar-layer"]')) focusMenu();
            }
        }
        menuWasOpen = open;
    }, 250);
    var lastControl = null;
    document.addEventListener('focusin', function (e) {
        if (inside(e.target, '[class*="control-bar-layer"]')) lastControl = e.target;
    });
    window.addEventListener('keydown', function (e) {
        if (!onPlayer() || e.ctrlKey || e.altKey || e.metaKey) return;
        var k = e.keyCode;
        var idle = playerFocusIdle() && !menuOpen();
        if (k === 13 && idle) { // OK / Enter: play-pause
            e.preventDefault();
            e.stopImmediatePropagation();
            if (!e.repeat) {
                wakeControls();
                var init = { key: ' ', code: 'Space', keyCode: 32, which: 32, bubbles: true, cancelable: true };
                window.dispatchEvent(new KeyboardEvent('keydown', init));
                window.dispatchEvent(new KeyboardEvent('keyup', init));
            }
            return;
        }
        if (k === 37 || k === 39) { // Left / Right
            if (idle) {
                // Seek (the player's own shortcut, on document) and never move focus: preventDefault stops the
                // page's spatial navigation, which otherwise walks onto the seek and volume sliders.
                e.preventDefault();
                e.spatialNavigationPrevented = true;
                wakeControls();
                setTimeout(function () { if (isRange(document.activeElement)) document.activeElement.blur(); }, 0);
            } else if (!isRange(document.activeElement)) {
                e.stopImmediatePropagation(); // move between buttons, don't seek
                e.preventDefault();
                moveFocus(k === 37 ? 'left' : 'right');
            }
            return;
        }
        if (k === 38 || k === 40) { // Up / Down: never change volume (the TV remote does that)
            e.stopImmediatePropagation();
            e.preventDefault();
            wakeControls();
            if (idle) {
                focusControls();
            } else {
                moveFocus(k === 38 ? 'up' : 'down');
            }
        }
    }, true);

    // ---- The top/side navigation bars are mouse-only (tabindex -1) in the desktop UI; let the remote reach them.
    var NAV_BARS = '[class*="horizontal-nav-bar"], [class*="vertical-nav-bar"]';
    var NAV_SELECTOR = '[class*="horizontal-nav-bar"] [tabindex="-1"], [class*="vertical-nav-bar"] [tabindex="-1"]';
    var fixNavTabIndex = function () {
        Array.prototype.forEach.call(document.querySelectorAll(NAV_SELECTOR), function (el) {
            if (el.tagName === 'INPUT' && el.type === 'file') return;
            // The player's top bar holds only a back arrow; Back on the remote does that, and focusing it would
            // turn the next OK (meant as pause) into "leave the movie".
            if (inside(el, '[class*="player-container"], [class*="nav-bar-layer"]')) return;
            el.setAttribute('tabindex', '0');
        });
        Array.prototype.forEach.call(document.querySelectorAll('[class*="nav-bar-layer"] [tabindex="0"], [class*="nav-bar-layer"] a[href]:not([tabindex])'), function (el) {
            el.setAttribute('tabindex', '-1');
            if (document.activeElement === el) el.blur();
        });
        // The invisible full-page file-drop box must never take the remote's focus.
        Array.prototype.forEach.call(document.querySelectorAll('input[type="file"]:not([tabindex="-1"])'), function (el) {
            el.setAttribute('tabindex', '-1');
        });
        // "See All" sits in each row's header, between rows; the remote skips it (rows scroll sideways instead).
        Array.prototype.forEach.call(document.querySelectorAll('[class*="see-all-container"]:not([tabindex="-1"])'), function (el) {
            el.setAttribute('tabindex', '-1');
        });
        // The "search videos" box on a series page sits between the season picker and the episodes; the remote skips it.
        Array.prototype.forEach.call(document.querySelectorAll('[class*="videos-list"] input:not([tabindex="-1"])'), function (el) {
            el.setAttribute('tabindex', '-1');
        });
        var a = document.activeElement;
        if (a && a.tagName === 'INPUT' && a.type === 'file') a.blur();
    };
    var navFixScheduled = false;
    new MutationObserver(function () {
        if (navFixScheduled) return;
        navFixScheduled = true;
        setTimeout(function () { navFixScheduled = false; fixNavTabIndex(); }, 200);
    }).observe(document.documentElement, { childList: true, subtree: true });

    // ---- Directional focus for the remote.
    // The web UI's spatial-navigation polyfill only considers what's on screen and otherwise scrolls 40px per press,
    // so the remote takes all arrow keys here: pick the nearest control in that direction anywhere on the page,
    // focus it, and scroll it into a comfortable spot (below the top bar, rows slid sideways).
    // Open popups/dialogs: while focus is inside one, the remote stays inside it. Matched on the start of a class
    // name so that e.g. the player's "control-bar-buttons-menu-container" doesn't count as a popup.
    var LAYER_CLASS = /^(modal-dialog-container|modal-container|menu-container|dropdown|portal-menu|bottom-sheet|context-menu|menu-layer|side-drawer-layer)(-|$)/;
    var layerOf = function (el) {
        for (var p = el; p && p !== document.body; p = p.parentElement) {
            var cl = p.classList;
            if (!cl) continue;
            for (var i = 0; i < cl.length; i++) if (LAYER_CLASS.test(cl[i])) return p;
        }
        return null;
    };
    var isDisabled = function (el) {
        if (el.disabled || el.getAttribute('aria-disabled') === 'true') return true;
        var c = String(el.className || '');
        return /(^|[\s_-])disabled/i.test(c);
    };
    var isShown = function (el) {
        if (!el.getClientRects().length) return false;
        var r = el.getBoundingClientRect();
        if (r.width < 2 || r.height < 2) return false;
        var st = window.getComputedStyle(el);
        return st.visibility !== 'hidden' && st.display !== 'none' && st.opacity !== '0';
    };
    var focusable = function (scope) {
        var list = (scope || document).querySelectorAll('a[href], button, input, textarea, select, [tabindex]');
        return Array.prototype.filter.call(list, function (el) {
            if (el.getAttribute('tabindex') === '-1' || el.type === 'file' || el.type === 'hidden') return false;
            if (isDisabled(el) || el.closest('[inert], [aria-hidden="true"]')) return false;
            return isShown(el);
        });
    };
    // Cheap first pass for directional moves: geometry only, so the costly style checks run on a handful of elements.
    var rawFocusable = function (scope) {
        var list = (scope || document).querySelectorAll('a[href], button, input, textarea, select, [tabindex]');
        return Array.prototype.filter.call(list, function (el) {
            return el.getAttribute('tabindex') !== '-1' && el.type !== 'file' && el.type !== 'hidden';
        });
    };
    var usable = function (el) {
        return !isDisabled(el) && !el.closest('[inert], [aria-hidden="true"]') && isShown(el);
    };
    // Left/Right inside a row of posters: just the neighbouring poster (no page-wide search).
    var rowNeighbour = function (from, dir) {
        var item = from.closest('[class*="meta-items-container"] > *');
        if (!item) return null;
        for (var sib = dir === 'right' ? item.nextElementSibling : item.previousElementSibling; sib;
             sib = dir === 'right' ? sib.nextElementSibling : sib.previousElementSibling) {
            var target = sib.matches('a[href], [tabindex="0"]') ? sib : sib.querySelector('a[href], [tabindex="0"]');
            if (target && usable(target)) return target;
        }
        return 'end';
    };
    var navBottom = function () {
        if (onPlayer()) return 0;
        // Earlier pages stay mounted (hidden), each with its own top bar: use the one on screen.
        var bars = document.querySelectorAll('[class*="horizontal-nav-bar"]');
        for (var i = 0; i < bars.length; i++) {
            var r = bars[i].getBoundingClientRect();
            if (r.height > 0 && r.bottom > 0 && r.top < 10 && isShown(bars[i])) return r.bottom;
        }
        return 0;
    };
    var scrollParent = function (el, axis) {
        for (var p = el.parentElement; p && p !== document.body; p = p.parentElement) {
            var st = window.getComputedStyle(p);
            var o = axis === 'x' ? st.overflowX : st.overflowY;
            if ((o === 'auto' || o === 'scroll') &&
                (axis === 'x' ? p.scrollWidth > p.clientWidth + 1 : p.scrollHeight > p.clientHeight + 1)) return p;
        }
        return null;
    };
    // Keep the focused control fully visible: sideways inside its row, vertically below the top bar.
    var ensureVisible = function (el) {
        if (!el || !el.getBoundingClientRect || onPlayer()) return;
        var r, sx = scrollParent(el, 'x');
        if (sx) {
            r = el.getBoundingClientRect();
            var c = sx.getBoundingClientRect();
            var pad = Math.min(c.width * 0.08, 120);
            if (r.left < c.left + pad) sx.scrollLeft -= (c.left + pad - r.left);
            else if (r.right > c.right - pad) sx.scrollLeft += (r.right - (c.right - pad));
        }
        var sy = scrollParent(el, 'y');
        var target = el.closest('[class*="meta-row-container"]') || el;
        r = target.getBoundingClientRect();
        var top = navBottom(), bottom = window.innerHeight;
        if (sy) {
            var cr = sy.getBoundingClientRect();
            top = Math.max(top, cr.top);
            bottom = Math.min(bottom, cr.bottom);
        }
        var margin = Math.min(48, (bottom - top) * 0.08);
        var delta = 0;
        if (r.top < top + margin || r.bottom > bottom - margin) {
            if (r.height > bottom - top - 2 * margin) delta = r.top - (top + margin);
            else if (r.top < top + margin) delta = r.top - (top + margin);
            else delta = r.bottom - (bottom - margin);
        }
        // The home banner is at the very top of the page: show all of it, not just enough to see the button.
        if (sy && el.closest('[class*="hero"]')) {
            if (sy.scrollTop !== 0) sy.scrollTop = 0;
            return;
        }
        if (!delta) return;
        if (sy) {
            // Near the start of the page, show the very top (the banner) instead of half of it.
            var contentTop = sy.scrollTop + delta;
            sy.scrollTop = contentTop < (bottom - top) * 0.35 ? 0 : contentTop;
        } else {
            window.scrollBy(0, delta);
        }
        // If that didn't do it (a scroll area that wasn't recognised), let the browser bring it into the middle.
        r = el.getBoundingClientRect();
        if (r.bottom > window.innerHeight || r.top < navBottom()) {
            try { el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (e) { el.scrollIntoView(false); }
        }
    };
    var focusEl = function (el) {
        if (!el || typeof el.focus !== 'function') return false;
        el.focus({ preventScroll: true });
        ensureVisible(el);
        return document.activeElement === el;
    };
    var geometricMove = function (dir) {
        var from = document.activeElement;
        if (nothingFocused()) return false;
        var layer = layerOf(from);
        var vertical = dir === 'up' || dir === 'down';
        if (!vertical && !layer) {
            var n = rowNeighbour(from, dir);
            if (n === 'end') return false;
            if (n) return focusEl(n);
        }
        var a = from.getBoundingClientRect();
        var cands = [];
        var reach = window.innerHeight * 2;
        rawFocusable(layer || document).forEach(function (el) {
            if (el === from || from.contains(el) || el.contains(from)) return;
            var b = el.getBoundingClientRect();
            if (b.width < 2 || b.height < 2) return;
            // Far away vertically: never the answer (keeps whole-page searches cheap).
            if (b.bottom < a.top - reach || b.top > a.bottom + reach) return;
            var main, overlap;
            if (dir === 'down') { main = b.top - a.bottom; if (b.top < a.top + a.height * 0.5) return; }
            else if (dir === 'up') { main = a.top - b.bottom; if (b.bottom > a.bottom - a.height * 0.5) return; }
            else if (dir === 'right') { main = b.left - a.right; if (b.left < a.left + a.width * 0.5) return; }
            else { main = a.left - b.right; if (b.right > a.right - a.width * 0.5) return; }
            main = Math.max(0, main);
            if (vertical) {
                overlap = Math.min(a.right, b.right) - Math.max(a.left, b.left);
            } else {
                overlap = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
                // Sideways moves stay on the same line (a row of posters, the top bar, a list of buttons).
                if (overlap < Math.min(a.height, b.height) * 0.3) return;
            }
            var centre = vertical ? Math.abs((b.left + b.right) / 2 - (a.left + a.right) / 2) : Math.abs((b.top + b.bottom) / 2 - (a.top + a.bottom) / 2);
            cands.push({ el: el, main: main, gap: overlap > 0 ? 0 : -overlap, centre: centre });
        });
        // Now the style checks, only for what's left.
        cands = cands.filter(function (c) { return (layer || !layerOf(c.el)) && usable(c.el); });
        if (!cands.length && !vertical && !inside(from, '[class*="meta-row-container"]') && !inside(from, NAV_BARS)) {
            // Nothing on the same line (e.g. Left from a series' episode list): take the nearest item that way.
            focusable(layer || document).forEach(function (el) {
                if (el === from || from.contains(el) || el.contains(from) || inside(el, NAV_BARS)) return;
                if (!layer && layerOf(el)) return;
                var b = el.getBoundingClientRect();
                var main = dir === 'right' ? b.left - a.right : a.left - b.right;
                if (dir === 'right' ? b.left < a.left + a.width * 0.5 : b.right > a.right - a.width * 0.5) return;
                var dy = Math.abs((b.top + b.bottom) / 2 - (a.top + a.bottom) / 2);
                if (dy > window.innerHeight * 0.6) return;
                cands.push({ el: el, main: Math.max(0, main) + dy, gap: 0, centre: dy });
            });
        }
        if (!cands.length) return false;
        // Stay inside the scrolling area you're in (e.g. a settings page's content column) while it has somewhere to go.
        var area = scrollParent(from, 'y');
        if (area && vertical) {
            var inArea = cands.filter(function (c) { return area.contains(c.el); });
            if (inArea.length) cands = inArea;
        }
        // The nearest line in that direction wins (e.g. Up from a row reaches the banner's buttons, not the top bar);
        // an item lined up with the current one is preferred when it's about as close.
        var nearest = Math.min.apply(null, cands.map(function (c) { return c.main; }));
        var span = vertical ? a.height : a.width;
        var aligned = cands.filter(function (c) { return c.gap === 0 && c.main <= nearest + Math.max(span * 1.5, window.innerHeight * 0.15); });
        var pool = aligned.length ? aligned : cands.filter(function (c) { return c.main <= nearest + Math.max(span * 0.5, 24); });
        pool.sort(function (x, y) { return (x.main + x.gap * 2 + x.centre * 0.1) - (y.main + y.gap * 2 + y.centre * 0.1); });
        var best = pool[0].el;
        // Coming back into a row (Netflix style) returns to the poster you were on in that row.
        var row = vertical && best.closest('[class*="meta-row-container"]');
        if (row && !row.contains(from)) {
            var last = rowMemory.get(row);
            if (last && last !== best && document.contains(last) && isShown(last)) best = last;
        }
        return best ? focusEl(best) : false;
    };
    window.__codioFocusable = function () { return focusable().map(function (e) { return e.className + '|' + (e.getAttribute('title') || ''); }); };
    var moveFocus = function (dir) {
        geometricMove(dir);
    };
    // Watchdog: things load and shift under the focus (posters, banners), so keep it on screen.
    var lastKeyAt = 0;
    window.addEventListener('keydown', function () { lastKeyAt = Date.now(); }, true);
    setInterval(function () {
        if (onPlayer() || nothingFocused() || Date.now() - lastKeyAt < 800) return;
        var a = document.activeElement, r = a.getBoundingClientRect();
        if (r.height && (r.bottom > window.innerHeight + 2 || r.top < navBottom() - 2)) ensureVisible(a);
    }, 700);
    var rowMemory = new WeakMap();
    document.addEventListener('focusin', function (e) {
        var row = e.target.closest && e.target.closest('[class*="meta-row-container"]');
        if (row) rowMemory.set(row, e.target);
    }, true);
    // Anything else that moves focus (the app itself, a page opening) still gets scrolled into a good spot.
    document.addEventListener('focusin', function (e) {
        if (onPlayer()) return;
        var el = e.target;
        requestAnimationFrame(function () { if (document.activeElement === el) ensureVisible(el); });
    }, true);
    var isTextInput = function (el) {
        return !!el && (el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' &&
            ['range', 'checkbox', 'radio', 'file', 'hidden', 'button', 'submit'].indexOf(el.type) === -1));
    };
    window.addEventListener('keydown', function (e) {
        var k = e.keyCode;
        if (k < 37 || k > 40 || e.ctrlKey || e.altKey || e.metaKey || onPlayer()) return;
        var a = document.activeElement;
        if (isTextInput(a)) return; // handled below
        if ((k === 37 || k === 39) && isRange(a)) return; // sliders take Left/Right
        e.preventDefault();
        e.stopImmediatePropagation();
        if (nothingFocused()) {
            autoFocus(true);
            return;
        }
        moveFocus(k === 37 ? 'left' : k === 38 ? 'up' : k === 39 ? 'right' : 'down');
    }, true);

    // ---- Text boxes (search, login): the remote's Up/Down always leaves the box, Left/Right leaves at the ends.
    window.addEventListener('keydown', function (e) {
        var a = document.activeElement;
        if (!a || (a.tagName !== 'INPUT' && a.tagName !== 'TEXTAREA')) return;
        if (a.type === 'range' || a.type === 'checkbox' || a.type === 'radio' || a.type === 'file' || a.type === 'hidden') return;
        var k = e.keyCode;
        var dir = null;
        if (k === 38) dir = 'up';
        else if (k === 40) dir = 'down';
        else if (k === 37 && a.selectionStart === 0 && a.selectionEnd === 0) dir = 'left';
        else if (k === 39 && a.selectionStart === (a.value || '').length) dir = 'right';
        if (!dir) return;
        e.preventDefault();
        e.stopImmediatePropagation();
        moveFocus(dir);
    }, true);

    // ---- On-screen keyboard: OK on a search/login box opens it straight away (one press, not two).
    var lastOkAt = 0;
    window.addEventListener('keydown', function (e) {
        if (e.keyCode !== 13) return;
        lastOkAt = Date.now();
        if (isTextInput(document.activeElement) && native.showKeyboard) native.showKeyboard();
    }, true);
    document.addEventListener('focusin', function (e) {
        if (isTextInput(e.target) && Date.now() - lastOkAt < 2000 && native.showKeyboard) native.showKeyboard();
    }, true);

    // ---- Put the remote's focus somewhere useful when a page opens, and remember where it was when leaving,
    // so Back returns to the same poster.
    // The whole address counts: a movie's page and its source list differ only after the "?".
    var routeKey = function () { return window.location.hash || '#/'; };
    var describe = function (el) {
        if (!el || nothingFocused()) return null;
        return { href: el.getAttribute('href'), title: el.getAttribute('title'), text: (el.textContent || '').trim().slice(0, 80), row: rowIndex(el) };
    };
    // The same movie can be in several rows (Continue watching and Popular); remember which one.
    var rowIndex = function (el) {
        var row = el.closest && el.closest('[class*="meta-row-container"]');
        if (!row) return -1;
        return Array.prototype.indexOf.call(document.querySelectorAll('[class*="meta-row-container"]'), row);
    };
    var findDescribed = function (d) {
        if (!d) return null;
        var list = focusable();
        if (d.href && d.row >= 0) {
            for (var k = 0; k < list.length; k++) {
                if (list[k].getAttribute('href') === d.href && rowIndex(list[k]) === d.row) return list[k];
            }
        }
        for (var i = 0; i < list.length; i++) {
            var el = list[i];
            if (d.href && el.getAttribute('href') === d.href) return el;
        }
        for (var j = 0; j < list.length; j++) {
            var e2 = list[j];
            if (d.title && e2.getAttribute('title') === d.title) return e2;
        }
        return null;
    };
    var saved = {};
    var lastFocusByRoute = {};
    document.addEventListener('focusin', function (e) {
        var d = describe(e.target);
        if (d) lastFocusByRoute[routeKey()] = d;
    }, true);
    var firstOf = function (selector) {
        var list = focusable();
        for (var i = 0; i < list.length; i++) if (list[i].matches(selector)) return list[i];
        return null;
    };
    var contentFocusables = function () {
        return focusable().filter(function (el) { return !inside(el, NAV_BARS); });
    };
    var pickStart = function () {
        var h = window.location.hash;
        if (/^#\/detail\//.test(h)) {
            if (/\?/.test(h) && firstOf('[class*="streams-container"] a[href], [class*="streams-container"] [tabindex="0"]')) {
                return firstOf('[class*="streams-container"] a[href], [class*="streams-container"] [tabindex="0"]');
            }
            return firstOf('[class*="play-button"]') ||
                firstOf('[class*="videos-list"] [class*="video-container"], [class*="videos-list"] a[href]') ||
                firstOf('[class*="streams-list"] a[href], [class*="streams-list"] [tabindex="0"]');
        }
        if (onHome()) {
            return firstOf('[class*="hero"] [class*="play"]') || firstOf('[class*="meta-item-link"]');
        }
        if (/^#\/(discover|library|search)/.test(h)) {
            var item = firstOf('[class*="meta-item-link"]');
            if (item) return item;
        }
        var content = contentFocusables();
        return content[0] || null;
    };
    var autoFocus = function (force) {
        var h = window.location.hash;
        var early = Date.now() - arrivedAt < 3500;
        var moviePage = /^#\/detail\/[^?]*$/.test(h);
        // In the play panel but not on Play: "Choose a source", or the status line while it searches.
        var onSourcesLink = inside(document.activeElement, '[class*="play-panel"]') && !inside(document.activeElement, '[class*="play-button"]');
        if (!userMoved && (early || onSourcesLink || nothingFocused()) && moviePage) {
            // The page's own early focus (e.g. "Choose a source" before Play renders) gives way to Play.
            var play = firstOf('[class*="play-button"]');
            if (play && document.activeElement !== play) return focusEl(play);
            // Still looking for the best version: rest on the status line, so a quick OK doesn't open the source list.
            var status = !play && document.querySelector('[class*="play-panel"] [class*="spinner"]');
            if (status && (onSourcesLink || nothingFocused())) {
                var holder = status.parentNode;
                holder.setAttribute('tabindex', '-1');
                holder.focus({ preventScroll: true });
                return true;
            }
        }
        if (!userMoved && !saved[routeKey()] && onHome() && Date.now() - arrivedAt < 30000) {
            // Home starts on the banner's Play, even if a poster row rendered first.
            var heroPlay = firstOf('[class*="hero"] [class*="play"]');
            if (heroPlay && document.activeElement !== heroPlay && inside(document.activeElement, '[class*="meta-row-container"]')) return focusEl(heroPlay);
        }
        // Until the viewer moves, a spot in the top bar (picked before the page had loaded) can still be replaced.
        var replaceable = nothingFocused() || (!userMoved && inside(document.activeElement, NAV_BARS));
        if (!replaceable) return false;
        var target = (moviePage ? null : findDescribed(saved[routeKey()])) || pickStart();
        if (!target && force && nothingFocused()) target = focusable()[0] || null;
        if (!target || target === document.activeElement) return false;
        return focusEl(target);
    };
    // An OK press while nothing is focused just picks a starting point.
    window.addEventListener('keydown', function (e) {
        if (e.keyCode !== 13 || onPlayer() || !nothingFocused()) return;
        if (autoFocus(true)) {
            e.preventDefault();
            e.stopImmediatePropagation();
        }
    }, true);
    var autoFocusTimers = [];
    var scheduleAutoFocus = function () {
        autoFocusTimers.forEach(clearTimeout);
        autoFocusTimers = [150, 400, 800, 1200, 1600, 2000, 2500, 3000, 4500, 6500, 9000, 12000, 16000, 20000, 25000, 30000].map(function (ms) {
            return setTimeout(function () { if (!onPlayer() && (nothingFocused() || !userMoved)) autoFocus(false); }, ms);
        });
    };
    // The router changes pages with pushState (no hashchange event), so watch the address.
    var lastHash = null;
    var arrivedAt = Date.now();
    setInterval(function () {
        var h = routeKey();
        if (h !== lastHash) {
            // Native browse screens: tell the app where the web UI is, so it can take over again after playback.
            if (window.__codioNativeUi && native.route) native.route(h);
            arrivedAt = Date.now();
            if (lastHash !== null && lastFocusByRoute[lastHash]) saved[lastHash] = lastFocusByRoute[lastHash];
            lastHash = h;
            userMoved = false;
            // A saved spot is restored even if the page focused something itself.
            var want = /^#\/detail\/[^?]*$/.test(h) ? null : saved[h]; // a movie page always starts on Play
            if (want) {
                [100, 400, 900, 1600, 2500].forEach(function (ms) {
                    setTimeout(function () {
                        if (userMoved || routeKey() !== h) return;
                        var el = findDescribed(want);
                        if (el && document.activeElement !== el) focusEl(el);
                    }, ms);
                });
            }
            scheduleAutoFocus();
        }
    }, 200);

    // A pick in a player menu (subtitles, audio): the app only lets the viewer switch subtitles on.
    window.addEventListener('keydown', function (e) {
        if ((e.key === 'Enter' || e.keyCode === 13) && onPlayer() && native.userPick) native.userPick();
    }, true);
    window.addEventListener('click', function () {
        if (onPlayer() && native.userPick) native.userPick();
    }, true);

    // ---- Native browse screens (Home, Discover, Search, movie pages) hand pages to the web UI and take them back.
    var PARK = '#/codio-native';
    window.__codioNativeGo = function (route) {
        // Play pressed on purpose: auto-play runs again even for a movie it played earlier this session (Resume).
        if (/[?&]autoplay=1/.test(route)) {
            try { sessionStorage.removeItem('autoPlayHandled'); } catch (e) { /* storage unavailable */ }
        }
        // From the parked page, push: Back in the web UI then returns to the parked page (and so to the native screens).
        if (routeKey() === PARK) window.location.hash = route;
        else window.location.replace(route);
    };
    window.__codioNativePark = function () {
        // An unknown page renders almost nothing, and unmounts the movie page (no more source loading).
        if (routeKey() !== PARK) window.location.replace(PARK);
    };
    window.__codioNativeStorage = function () {
        try {
            var live = window.__codioProfile ? JSON.stringify(window.__codioProfile) : null;
            return JSON.stringify({ profile: live || localStorage.getItem('profile'), recent: localStorage.getItem('library_recent'), library: localStorage.getItem('library'), autoplay: localStorage.getItem('autoPlaySettings') });
        } catch (e) { return null; }
    };
    // Actions from the native screens (install/remove addons, settings, library, sign in/out) go to the core.
    var coreHooked = false;
    function hookCore() {
        var core = window.__codioCore;
        if (coreHooked || !core || typeof core.on !== 'function') return;
        coreHooked = true;
        core.on('event', function (name) {
            if (native.coreEvent && typeof name === 'string') native.coreEvent(name, '');
        });
        core.on('error', function (source, error) {
            if (!native.coreEvent) return;
            var ev = source && source.event ? source.event : 'Unknown';
            native.coreEvent('Error:' + ev, error && error.message ? String(error.message) : '');
        });
    }
    window.__codioDispatch = function (json) {
        try {
            if (!window.__codioCore) return 'no-core';
            hookCore();
            window.__codioCore.transport.dispatch(JSON.parse(json));
            return 'ok';
        } catch (e) { return 'error: ' + e.message; }
    };
    var profileTimer = null;
    window.__codioProfileChanged = function () {
        hookCore();
        clearTimeout(profileTimer);
        profileTimer = setTimeout(function () { if (native.profileChanged) native.profileChanged(); }, 300);
    };
    window.__codioNativeSetAutoPlay = function (json) {
        try { localStorage.setItem('autoPlaySettings', json); } catch (e) { /* storage unavailable */ }
    };
    // The movie page runs hidden behind the native screens (loading sources early): pass its source list along.
    var lastStreams = null;
    setInterval(function () {
        if (!window.__codioNativeUi || !native.streams) return;
        var st = window.__codioStreams;
        if (!st || !/^#\/detail\//.test(window.location.hash)) return;
        var sig = st.key + '|' + st.loading + '|' + st.streams.length + '|' + st.ranked.length + '|' + (st.ranked[0] ? st.ranked[0].link : '');
        if (sig === lastStreams) return;
        lastStreams = sig;
        try { native.streams(JSON.stringify(st)); } catch (e) { /* too big? */ }
    }, 400);

    // While the native "finding your stream" screen covers the movie page, tell it what auto-play is doing.
    var lastStatus = null;
    setInterval(function () {
        if (!window.__codioNativeUi || !native.playStatus || !/^#\/detail\/[^?]*\?(.*&)?autoplay=1/.test(window.location.hash)) {
            lastStatus = null;
            return;
        }
        var panel = document.querySelector('[class*="play-panel"]');
        var kind = 'none', text = '';
        if (panel) {
            var label = panel.querySelector('[class*="status"] [class*="label"]');
            text = label ? label.textContent : '';
            if (panel.querySelector('[class*="spinner"]')) kind = /^Starting/.test(text) ? 'starting' : 'searching';
            else if (panel.querySelector('[class*="play-button"]')) kind = 'manual';
            else if (label) kind = 'failed';
        } else if (document.querySelector('[class*="streams-list-container"]')) {
            kind = 'list';
        }
        var status = kind + '|' + text;
        if (status !== lastStatus) {
            lastStatus = status;
            native.playStatus(kind, text);
        }
    }, 300);

    // ---- Back button on the remote.
    // Closes whatever is open (menus, dialogs, player controls); otherwise goes back a page;
    // on the home screen asks the app to exit (it wants a second press).
    window.__codioBack = function () {
        var a = document.activeElement;
        if (onPlayer() && menuOpen()) {
            // Close the player menu (subtitles, audio, ...) the way a click outside it would, and stay in the player.
            var container = document.querySelector('[class*="player-container"]');
            if (container) {
                var opts = { bubbles: true, button: 0, clientX: 5, clientY: 5 };
                container.dispatchEvent(new MouseEvent('mousedown', opts));
                container.dispatchEvent(new MouseEvent('mouseup', opts));
            }
            setTimeout(function () {
                var last = lastControl && document.contains(lastControl) ? lastControl : null;
                if (last) last.focus(); else focusControls();
            }, 50);
            return;
        }
        if (onPlayer() && a && a !== document.body && inside(a, '[class*="control-bar-layer"], [class*="indicator-layer"], [class*="side-drawer-button-layer"]')) {
            a.blur(); // then leave the player below, in the same press
        }
        if (window.__codioNativeUi && !onPlayer() && !menuOpen() && /^#\/detail\//.test(window.location.hash)) {
            // Opened from the native screens (source list, auto-play search): Back returns there, nothing starts.
            if (window.__codioCancelAutoPlay) window.__codioCancelAutoPlay();
            window.__codioNativePark();
            if (native.requestExit) native.requestExit();
            return;
        }
        if (!onPlayer() && !menuOpen() && /^#\/detail\/[^?]*\?(.*&)?sources=1/.test(window.location.hash)) {
            // "Choose a source" replaces the movie page's history entry, so history.back() would skip the movie page.
            var url = window.location.href.replace(/([?&])sources=1(&|$)/, function (m, pre, post) { return post ? pre : ''; });
            window.location.replace(url.replace(/\?$/, ''));
            return;
        }
        var changed = 0;
        var hashBefore = window.location.hash;
        var observer = new MutationObserver(function (records) {
            records.forEach(function (r) {
                // Only a popup, menu or dialog closing counts (banners, toasts and trailers change on their own).
                for (var i = 0; i < r.removedNodes.length; i++) {
                    var n = r.removedNodes[i];
                    if (n.nodeType !== 1) continue;
                    var popup = /(modal|menu|popup|dropdown|sheet|drawer|context|dialog|portal)/i;
                    if (popup.test(String(n.className || '')) || (n.querySelector && n.querySelector('[class*="modal"], [class*="menu-container"], [class*="popup"], [class*="dropdown"], [class*="portal"]'))) changed++;
                }
            });
        });
        observer.observe(document.body, { childList: true, subtree: true });
        key('Escape', 'Escape', 27);
        setTimeout(function () {
            observer.disconnect();
            if (changed > 0 || window.location.hash !== hashBefore) return;
            if (onHome() || (window.__codioNativeUi && !onPlayer())) {
                if (native.requestExit) native.requestExit();
                return;
            }
            if (window.history.length > 1) {
                window.history.back();
            } else {
                window.location.hash = '#/';
            }
        }, 450);
    };
})();
