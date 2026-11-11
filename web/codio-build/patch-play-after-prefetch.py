import sys, glob
old = 'null!==D&&D.key===t||((0,_.wasHandled)(t)||"manual"===I?C({key:t,state:"handled",candidates:u,startedAt:Date.now()}):L&&0!==u.length&&C({key:t,state:"countdown",candidates:u,startedAt:Date.now()}))'
new = '(function(){var pp="immediate"===I&&null!==D&&D.key===t&&D.mode!==I&&"handled"===D.state;null!==D&&D.key===t&&!pp||("manual"===I||!pp&&(0,_.wasHandled)(t)?C({key:t,mode:I,state:"handled",candidates:u,startedAt:Date.now()}):L&&0!==u.length&&C({key:t,mode:I,state:"countdown",candidates:u,startedAt:Date.now()}))})()'
hits = 0
for f in glob.glob(sys.argv[1] + '/*/scripts/main.js'):
    s = open(f, encoding='utf-8').read()
    if s.count(old) == 1:
        open(f, 'w', encoding='utf-8').write(s.replace(old, new, 1)); hits += 1
    elif 'D.mode!==I&&"handled"===D.state' in s:
        hits += 1
print('auto-play Play-after-prefetch fix:', 'applied' if hits else 'NOT FOUND (check web build)')
if not hits: sys.exit(1)
