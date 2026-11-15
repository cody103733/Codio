"""Codio's changes to the built engine bundle (main.js), shared by the Android and iPhone builds.

Usage: python3 patch-bundle.py <web build folder> [android]

Each change is one or more exact (old, new) text swaps. A change that is already in the bundle counts as applied, so
running this twice is harmless; one that fits neither way stops the build, because the web build has moved on.
"""
import glob
import sys

COMMON = [
    # Auto-play never tries torrents: only http(s) sources (there is no streaming server in these apps).
    ('no torrents', [
        ('return"string"==typeof(null===(t=e.deepLinks)||void 0===t?void 0:t.player)}).map(function(e,E){var a=(0,t.streamText)(e)',
         'return"string"==typeof(null===(t=e.deepLinks)||void 0===t?void 0:t.player)&&"string"==typeof e.url&&/^https?:\\/\\//i.test(e.url)}).map(function(e,E){var a=(0,t.streamText)(e)'),
    ]),
    # A Play press must start even when the page was first opened only to load sources (?prefetch=1 = 'manual'):
    # that earlier 'handled' decision used to stick, so Play showed the source list.
    ('Play after prefetch', [
        ('null!==D&&D.key===t||((0,_.wasHandled)(t)||"manual"===I?C({key:t,state:"handled",candidates:u,startedAt:Date.now()}):L&&0!==u.length&&C({key:t,state:"countdown",candidates:u,startedAt:Date.now()}))',
         '(function(){var pp="immediate"===I&&null!==D&&D.key===t&&D.mode!==I&&"handled"===D.state;null!==D&&D.key===t&&!pp||("manual"===I||!pp&&(0,_.wasHandled)(t)?C({key:t,mode:I,state:"handled",candidates:u,startedAt:Date.now()}):L&&0!==u.length&&C({key:t,mode:I,state:"countdown",candidates:u,startedAt:Date.now()}))})()'),
    ]),
    # Sources whose audio is only in another language (e.g. "KOREAN") go after every other source, whatever their
    # quality; the shim's __codioLangRank says 1 for those. Same as AutoPlay/language.ts on Windows.
    ('audio language ranking', [
        ('return O.push.apply(O,u),O.slice(0,Math.max(1,E.maxAttempts))',
         'return O.push.apply(O,u),window.__codioLangRank&&(function(){var m={};s.forEach(function(x){m[x.candidate.link]=window.__codioLangRank(x.text)});'
         'O=O.map(function(c,k){return{c:c,k:k}}).sort(function(a,b){return(m[a.c.link]||0)-(m[b.c.link]||0)||a.k-b.k}).map(function(x){return x.c})})(),'
         'O.slice(0,Math.max(1,E.maxAttempts))'),
    ]),
    # Opening another episode, the page shows the new route a moment before the core swaps the sources, so the last
    # episode's list (often the one Continue Watching loaded early) was still there and auto-play started that episode.
    ('chosen episode', [
        ('J=u(),ee=l.useMemo(function(){var e,t=J.type',
         'J=u(),__cs="string"==typeof J.videoId&&J.videoId!==J.id&&(null==n||n.id!==J.videoId),ee=l.useMemo(function(){var e,t=J.type'),
        ('te=l.useMemo(function(){return Object.values(Z).map(function(e){return e.streams}).flat(1)},[Z])',
         'te=l.useMemo(function(){return __cs?[]:Object.values(Z).map(function(e){return e.streams}).flat(1)},[Z,__cs])'),
        ('Ie=k({key:ee,streams:te,loading:q>0,', 'Ie=k({key:ee,streams:te,loading:q>0||__cs,'),
        ('window.__codioStreams={key:ee,loading:q>0,', 'window.__codioStreams={key:ee,loading:q>0||__cs,'),
    ]),
]

ANDROID = [
    # Faster starts: a Play press waits at most 3 s for slow addons, and a dead source costs 20 s.
    ('faster start', [
        ('1e3*Math.max(0,A.addonWaitSeconds));return function(){return clearTimeout(e)}}},[O,c,N,A.addonWaitSeconds,t])',
         '1e3*Math.max(0,"immediate"===I?Math.min(A.addonWaitSeconds,3):A.addonWaitSeconds));return function(){return clearTimeout(e)}}},[O,c,N,A.addonWaitSeconds,t,I])'),
        ('countdownSeconds:3,addonWaitSeconds:8,skipOnError:!0,startupTimeoutSeconds:30,',
         'countdownSeconds:3,addonWaitSeconds:8,skipOnError:!0,startupTimeoutSeconds:20,'),
    ]),
    # Dolby Vision first (within the same quality; ahead of "instant", behind only sources marked as not yet
    # downloaded) when the TV plays real Dolby Vision. The shim's __codioDvRank says 0 for a Dolby Vision source this
    # TV can show (not a Blu-ray remux), else 1.
    ('Dolby Vision ranking', [
        ('return l(e)-l(t)||I(e)-I(t)||e.index-t.index',
         'return l(e)-l(t)||(I(e)>1?1:0)-(I(t)>1?1:0)||(window.__codioDvRank?window.__codioDvRank(e.text)-window.__codioDvRank(t.text):0)||I(e)-I(t)||e.index-t.index'),
    ]),
]

files = glob.glob(sys.argv[1] + '/*/scripts/main.js')
if not files:
    sys.exit('no main.js under ' + sys.argv[1])
changes = COMMON + (ANDROID if 'android' in sys.argv[2:] else [])
for f in files:
    s = open(f, encoding='utf-8').read()
    for name, pairs in changes:
        for old, new in pairs:
            if s.count(old) == 1:
                s = s.replace(old, new, 1)
            elif new not in s:
                sys.exit('engine change "%s" does not fit this web build' % name)
        print('engine change:', name)
    open(f, 'w', encoding='utf-8').write(s)
