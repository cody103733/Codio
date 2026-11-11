import sys, glob
old = 'return"string"==typeof(null===(t=e.deepLinks)||void 0===t?void 0:t.player)}).map(function(e,E){var a=(0,t.streamText)(e)'
new = 'return"string"==typeof(null===(t=e.deepLinks)||void 0===t?void 0:t.player)&&"string"==typeof e.url&&/^https?:\\/\\//i.test(e.url)}).map(function(e,E){var a=(0,t.streamText)(e)'
hits = 0
for f in glob.glob(sys.argv[1] + '/*/scripts/main.js'):
    s = open(f, encoding='utf-8').read()
    if old in s:
        open(f, 'w', encoding='utf-8').write(s.replace(old, new, 1)); hits += 1
    elif 'typeof e.url&&/^https?:' in s:
        hits += 1
print('auto-play torrent filter:', 'applied' if hits else 'NOT FOUND (check web build)')
