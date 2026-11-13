package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Search with an on-screen keyboard (D-pad friendly) or a real keyboard; results from every searchable catalog. */
final class SearchScreen extends Screen {
    private static final int NAV = 0, KEYS = 1, RESULTS = 2;
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyz1234567890";
    private static final int KCOLS = 6;
    private static final float KX = TvView.MARGIN, KY = 290, KW = 76, KH = 70, KSX = 84, KSY = 78;
    private static final float RX = 700, RY = 200;
    private static final String[] SPECIAL = {"Space", "Delete", "Clear"};
    /** Movies / Series / Both filter, the row under Space/Delete/Clear. Remembered while the app runs. */
    private static final String[] KIND_LABEL = {"Both", "Movies", "Series"};
    private static final String[] KIND_TYPE = {null, "movie", "series"};
    private static int kind = 0;

    private final NavBar nav;
    private int zone = KEYS;
    private int keyFocus = 0;
    private final StringBuilder query = new StringBuilder();
    private final Grid grid = new Grid(5);
    private int request = 0, pending = 0;
    private long dueAt = 0;
    private final Smooth scroll = new Smooth(0.1f, 0.4f);

    SearchScreen(TvView tv) {
        super(tv);
        nav = new NavBar(tv, NavBar.SEARCH);
    }

    private int keyCount() {
        return CHARS.length() + SPECIAL.length + KIND_LABEL.length;
    }

    private static boolean wanted(String type) {
        String t = KIND_TYPE[kind];
        return t == null || t.equals(type);
    }

    private final Runnable fire = new Runnable() {
        @Override
        public void run() {
            if (SystemClock.uptimeMillis() >= dueAt) search();
        }
    };

    private void changed() {
        dueAt = SystemClock.uptimeMillis() + 450;
        tv.removeCallbacks(fire);
        tv.postDelayed(fire, 460);
    }

    private void search() {
        final String q = query.toString().trim();
        final int id = ++request;
        grid.reset();
        scroll.snap(0);
        if (q.isEmpty()) {
            pending = 0;
            tv.kick();
            return;
        }
        final List<Store.Catalog> cats = new ArrayList<>();
        for (Store.Catalog cat : tv.store.searchCatalogs()) if (wanted(cat.type)) cats.add(cat);
        pending = cats.size();
        final List<List<Store.Meta>> parts = new ArrayList<>();
        for (int i = 0; i < cats.size(); i++) parts.add(null);
        for (int i = 0; i < cats.size(); i++) {
            final int slot = i;
            tv.store.loadCatalog(cats.get(i), null, 0, q, new Store.Callback<List<Store.Meta>>() {
                @Override
                public void done(List<Store.Meta> result, Exception error) {
                    if (id != request) return;
                    pending--;
                    parts.set(slot, result != null ? result : new ArrayList<Store.Meta>());
                    // Keep catalog order (movies first) and drop repeats.
                    Store.Meta keep = grid.focused();
                    grid.items.clear();
                    Set<String> seen = new HashSet<>();
                    for (List<Store.Meta> p : parts) {
                        if (p == null) continue;
                        for (Store.Meta m : p) if (wanted(m.type) && seen.add(m.type + m.id)) grid.items.add(m);
                    }
                    if (keep != null && zone == RESULTS) {
                        int at = grid.items.indexOf(keep);
                        grid.focus = at >= 0 ? at : Math.min(grid.focus, Math.max(0, grid.items.size() - 1));
                    }
                    tv.kick();
                }
            });
        }
    }

    private final RectF r = new RectF();

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(0xFF0B0B0B);
        // Query line.
        String q = query.toString();
        tv.tTitle.setTextSize(44);
        if (q.isEmpty()) {
            tv.tTitle.setColor(0xFF777777);
            c.drawText("Search movies & series", KX, 232, tv.tTitle);
            tv.tTitle.setColor(Color.WHITE);
        } else {
            c.drawText(tv.fit(q, tv.tTitle, 560), KX, 232, tv.tTitle);
        }
        tv.tTitle.setTextSize(34);
        tv.fill.setColor(TvView.RED);
        c.drawRect(KX, 250, KX + KSX * KCOLS - 8, 253, tv.fill);

        // Keyboard.
        for (int i = 0; i < keyCount(); i++) {
            boolean focused = zone == KEYS && i == keyFocus;
            boolean selected = false;
            float x, y, w;
            String label;
            int typeBase = CHARS.length() + SPECIAL.length;
            if (i >= typeBase) {
                int s = i - typeBase;
                x = KX + s * 2 * KSX;
                y = KY + (CHARS.length() / KCOLS + 1) * KSY;
                w = KSX * 2 - 8;
                label = KIND_LABEL[s];
                selected = s == kind;
            } else if (i < CHARS.length()) {
                x = KX + (i % KCOLS) * KSX;
                y = KY + (i / KCOLS) * KSY;
                w = KW;
                label = String.valueOf(CHARS.charAt(i)).toUpperCase();
            } else {
                int s = i - CHARS.length();
                x = KX + s * 2 * KSX;
                y = KY + (CHARS.length() / KCOLS) * KSY;
                w = KSX * 2 - 8;
                label = SPECIAL[s];
            }
            r.set(x, y, x + w, y + KH);
            tv.fill.setColor(focused ? Color.WHITE : selected ? TvView.RED : 0xFF1F1F1F);
            c.drawRoundRect(r, 8, 8, tv.fill);
            tv.tLabel.setColor(focused ? Color.BLACK : 0xFFE0E0E0);
            if (i >= CHARS.length()) tv.tLabel.setTextSize(24);
            float tw = tv.tLabel.measureText(label);
            c.drawText(label, x + (w - tw) / 2, y + KH / 2 + 10, tv.tLabel);
            tv.tLabel.setTextSize(28);
            tv.tLabel.setColor(Color.WHITE);
        }

        // Results.
        int row = grid.row();
        scroll.to(zone == RESULTS && row > 0 ? row * Grid.STEP_Y - 120 : 0);
        float sy = step(scroll, dt);
        c.save();
        c.clipRect(RX - 30, 0, TvView.W, TvView.H);
        grid.draw(this, c, RX, RY - sy, zone == RESULTS, dt);
        c.restore();
        if (grid.items.isEmpty()) {
            if (pending > 0) tv.spinner(c, RX + 560, 520, 34, dt);
            else if (query.length() > 0 && SystemClock.uptimeMillis() >= dueAt && request > 0) {
                c.drawText("No results for “" + tv.fit(q, tv.tBody, 600) + "”", RX, 400, tv.tBody);
            }
        }
        tv.fill.setColor(0xFF0B0B0B);
        if (sy > 0) {
            c.drawRect(RX - 30, 0, TvView.W, 120, tv.fill);
            c.drawRect(RX - 30, 120, TvView.W, 180, tv.topShade);
        }
        nav.draw(c, zone == NAV);
    }

    private void press() {
        int typeBase = CHARS.length() + SPECIAL.length;
        if (keyFocus >= typeBase) {
            int k = keyFocus - typeBase;
            if (k != kind) {
                kind = k;
                tv.removeCallbacks(fire);
                dueAt = 0;
                search();
            }
            return;
        }
        if (keyFocus < CHARS.length()) {
            query.append(CHARS.charAt(keyFocus));
        } else {
            int s = keyFocus - CHARS.length();
            if (s == 0) {
                if (query.length() > 0 && query.charAt(query.length() - 1) != ' ') query.append(' ');
            } else if (s == 1) {
                if (query.length() > 0) query.setLength(query.length() - 1);
            } else {
                query.setLength(0);
            }
        }
        changed();
    }

    @Override
    boolean key(int k, KeyEvent e) {
        // A real keyboard (or the phone app's keyboard) types directly.
        if (k == TvView.OTHER && e != null) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_DEL) {
                if (query.length() > 0) query.setLength(query.length() - 1);
                changed();
                return true;
            }
            int ch = e.getUnicodeChar();
            if (ch >= 32 && query.length() < 60) {
                query.append(Character.toLowerCase((char) ch));
                changed();
                return true;
            }
            return false;
        }
        switch (zone) {
            case NAV:
                if (k == TvView.DOWN) {
                    zone = KEYS;
                    return true;
                }
                return nav.key(k);
            case KEYS: {
                // Rows: letters/digits, then Space/Delete/Clear, then Both/Movies/Series (wide keys, 2 columns each).
                int chars = CHARS.length(), rows = chars / KCOLS;
                int types = chars + SPECIAL.length;
                boolean special = keyFocus >= chars && keyFocus < types;
                boolean type = keyFocus >= types;
                boolean wide = special || type;
                int base = type ? types : chars;
                int col = wide ? (keyFocus - base) * 2 : keyFocus % KCOLS;
                int row = type ? rows + 1 : special ? rows : keyFocus / KCOLS;
                if (k == TvView.OK) {
                    press();
                } else if (k == TvView.UP) {
                    if (row == 0) {
                        zone = NAV;
                        nav.focus = nav.current;
                    } else if (type) keyFocus = chars + (keyFocus - types);
                    else if (special) keyFocus = (rows - 1) * KCOLS + col;
                    else keyFocus -= KCOLS;
                } else if (k == TvView.DOWN) {
                    if (row == rows - 1) keyFocus = chars + Math.min(SPECIAL.length - 1, col / 2);
                    else if (special) keyFocus = types + Math.min(KIND_LABEL.length - 1, keyFocus - chars);
                    else if (!type) keyFocus += KCOLS;
                } else if (k == TvView.LEFT) {
                    if (wide ? keyFocus > base : col > 0) keyFocus--;
                } else if (k == TvView.RIGHT) {
                    boolean lastCol = wide ? keyFocus == base + 2 : col == KCOLS - 1;
                    if (lastCol) {
                        if (!grid.items.isEmpty()) {
                            zone = RESULTS;
                            grid.focusIn();
                        }
                    } else keyFocus++;
                }
                return true;
            }
            case RESULTS:
            default:
                if (k == TvView.OK || k == TvView.PLAY) {
                    Store.Meta m = grid.focused();
                    if (m == null) return true;
                    if (k == TvView.PLAY && "movie".equals(m.type)) tv.host.play(m, null);
                    else tv.openDetail(m);
                    return true;
                }
                if (grid.move(k)) return true;
                if (k == TvView.LEFT) zone = KEYS;
                else if (k == TvView.UP) {
                    zone = NAV;
                    nav.focus = nav.current;
                }
                return true;
        }
    }

    @Override
    void onShow() {
        nav.focus = nav.current;
    }

    @Override
    boolean back() {
        if (zone == RESULTS) {
            zone = KEYS;
            return true;
        }
        if (zone == KEYS && query.length() > 0) {
            query.setLength(0);
            changed();
            return true;
        }
        return false;
    }
}
