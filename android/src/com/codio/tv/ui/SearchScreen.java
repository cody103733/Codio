package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Search with an on-screen keyboard (D-pad friendly), a real keyboard, or voice (the mic key at the end of the query
 * line, or the remote's search/mic button); results from every searchable catalog.
 */
final class SearchScreen extends Screen {
    private static final int NAV = 0, KEYS = 1, RESULTS = 2, RECENT = 3, MIC = 4;
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyz1234567890";
    private static final int KCOLS = 6;
    private static final float KX = TvView.MARGIN, KY = 290, KW = 76, KH = 70, KSX = 84, KSY = 78;
    private static final float RX = 700, RY = 200;
    private static final String[] SPECIAL = {"Space", "Delete", "Clear"};

    private final NavBar nav;
    private int zone = KEYS;
    private int keyFocus = 0;
    private final StringBuilder query = new StringBuilder();
    private final Grid grid = new Grid(5);
    private final Search search = new Search();
    private boolean searched = false;
    /** Recent searches, listed where the results go while nothing is typed. The last row clears them. */
    private List<String> recent = new ArrayList<>();
    private int recentFocus = 0;
    private long dueAt = 0;
    private final Smooth scroll = new Smooth(0.1f, 0.4f);
    /** This TV can do voice search: the mic key shows. */
    private final boolean voice;
    /** A spoken search moves the focus to its first results. */
    private boolean focusResults = false;
    private static final float MIC_R = 32, MIC_X = KX + KSX * KCOLS - 8 - MIC_R, MIC_Y = 214;

    SearchScreen(TvView tv) {
        super(tv);
        nav = new NavBar(tv, NavBar.SEARCH);
        voice = Voice.available(tv.getContext());
        Voice.attach(tv);
    }

    /** Listen for a spoken search (the mic key, or the remote's search/mic button). */
    void listen() {
        if (Voice.listening) return;
        Voice.listen(tv.getContext(), new Voice.Listener() {
            @Override
            public void heard(String text) {
                setQuery(text);
            }

            @Override
            public void failed(String message) {
                tv.toast(message);
            }
        });
    }

    /** Search for this text now (voice, or a search the TV's assistant sent to Codio). */
    void setQuery(String text) {
        query.setLength(0);
        query.append(text.length() > 60 ? text.substring(0, 60) : text);
        zone = KEYS;
        focusResults = true;
        tv.removeCallbacks(fire);
        dueAt = 0;
        search();
    }

    private int keyCount() {
        return CHARS.length() + SPECIAL.length + Search.KIND_LABEL.length;
    }

    private final Runnable fire = new Runnable() {
        @Override
        public void run() {
            if (SystemClock.uptimeMillis() >= dueAt) search();
        }
    };

    private void changed() {
        dueAt = SystemClock.uptimeMillis() + 450;
        if (query.length() == 0 && zone == RESULTS) zone = KEYS;
        tv.removeCallbacks(fire);
        tv.postDelayed(fire, 460);
    }

    private void search() {
        final String q = query.toString().trim();
        search.cancel();
        grid.reset();
        scroll.snap(0);
        searched = !q.isEmpty();
        if (!q.isEmpty()) search.run(tv.store, q, new Search.Results() {
            @Override
            public void results(List<Store.Meta> items) {
                // The focused title keeps the focus while more results arrive around it.
                Store.Meta keep = grid.focused();
                grid.items.clear();
                grid.items.addAll(items);
                if (focusResults && !items.isEmpty() && (zone == KEYS || zone == MIC)) {
                    focusResults = false;
                    zone = RESULTS;
                    grid.focusIn();
                    Search.remember(tv.host.prefs(), query.toString());
                } else if (keep != null && zone == RESULTS) {
                    int at = grid.items.indexOf(keep);
                    grid.focus = at >= 0 ? at : Math.min(grid.focus, Math.max(0, grid.items.size() - 1));
                }
                tv.kick();
            }
        });
        tv.kick();
    }

    private final RectF r = new RectF();

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(Theme.BG);
        // Query line.
        String q = query.toString();
        tv.tTitle.setTextSize(44);
        if (q.isEmpty()) {
            tv.tTitle.setColor(Theme.TEXT_FAINT);
            c.drawText("Search movies and shows", KX, 232, tv.tTitle);
            tv.tTitle.setColor(Color.WHITE);
        } else {
            c.drawText(tv.fit(q, tv.tTitle, voice ? 410 : 560), KX, 232, tv.tTitle);
        }
        if (voice) drawMic(c, zone == MIC);
        tv.tTitle.setTextSize(34);
        tv.fill.setColor(Theme.RED);
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
                label = Search.KIND_LABEL[s];
                selected = s == Search.kind;
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
            tv.fill.setColor(focused ? Color.WHITE : selected ? Theme.RED : Theme.CARD);
            c.drawRoundRect(r, 8, 8, tv.fill);
            tv.tLabel.setColor(focused ? Color.BLACK : Theme.TEXT_BODY);
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
        if (query.length() == 0) drawRecent(c);
        else {
            c.save();
            c.clipRect(RX - 30, 0, TvView.W, TvView.H);
            grid.draw(this, c, RX, RY - sy, zone == RESULTS, dt);
            c.restore();
        }
        if (query.length() > 0 && grid.items.isEmpty()) {
            if (search.pending > 0) tv.spinner(c, RX + 560, 520, 34, dt);
            else if (searched && SystemClock.uptimeMillis() >= dueAt) {
                c.drawText("No results for “" + tv.fit(q, tv.tBody, 600) + "”", RX, 400, tv.tBody);
            }
        }
        nav.draw(c, zone == NAV, sy > 0);
        if (Voice.listening) drawListening(c, dt);
    }

    private final Path micPath = new Path();

    /** A round mic key at the end of the query line. */
    private void drawMic(Canvas c, boolean focused) {
        tv.fill.setColor(focused ? Color.WHITE : Theme.CARD);
        c.drawCircle(MIC_X, MIC_Y, MIC_R, tv.fill);
        drawMicIcon(c, MIC_X, MIC_Y, 1, focused ? Color.BLACK : Theme.TEXT_BODY);
    }

    private void drawMicIcon(Canvas c, float cx, float cy, float s, int color) {
        tv.fill.setColor(color);
        r.set(cx - 7 * s, cy - 18 * s, cx + 7 * s, cy + 5 * s);
        c.drawRoundRect(r, 7 * s, 7 * s, tv.fill);
        Paint st = tv.stroke;
        float was = st.getStrokeWidth();
        st.setColor(color);
        st.setStrokeWidth(3.5f * s);
        st.setStrokeCap(Paint.Cap.ROUND);
        micPath.reset();
        r.set(cx - 12 * s, cy - 10 * s, cx + 12 * s, cy + 11 * s);
        micPath.arcTo(r, 0, 180, true);
        c.drawPath(micPath, st);
        c.drawLine(cx, cy + 11 * s, cx, cy + 18 * s, st);
        c.drawLine(cx - 7 * s, cy + 18 * s, cx + 7 * s, cy + 18 * s, st);
        st.setStrokeWidth(was);
        st.setStrokeCap(Paint.Cap.BUTT);
    }

    private float pulse = 0;

    /** Codio's own Listening card (only on TVs whose speech service has no prompt of its own). */
    private void drawListening(Canvas c, float dt) {
        tv.moving();
        tv.fill.setColor(Theme.SCRIM_STRONG);
        c.drawRect(0, 0, TvView.W, TvView.H, tv.fill);
        float cx = TvView.W / 2, cy = 440;
        pulse += (Voice.level - pulse) * Math.min(1, dt * 10);
        tv.fill.setColor(0x55E50914);
        c.drawCircle(cx, cy, 90 + 50 * pulse, tv.fill);
        tv.fill.setColor(Theme.RED);
        c.drawCircle(cx, cy, 80, tv.fill);
        drawMicIcon(c, cx, cy, 2.2f, Color.WHITE);
        String p = Voice.partial;
        String line = p == null || p.isEmpty() ? "Listening…" : "“" + p + "”";
        tv.tTitle.setTextSize(44);
        line = tv.fit(line, tv.tTitle, 1400);
        c.drawText(line, cx - tv.tTitle.measureText(line) / 2, cy + 190, tv.tTitle);
        tv.tTitle.setTextSize(34);
        tv.tBody.setColor(Theme.TEXT_DIM);
        String hint = "Say a movie or show. Back to cancel.";
        c.drawText(hint, cx - tv.tBody.measureText(hint) / 2, cy + 250, tv.tBody);
        tv.tBody.setColor(Theme.TEXT_BODY);
    }

    private static final float RECENT_ROW = 76, RECENT_W = 760;

    private void drawRecent(Canvas c) {
        if (recent.isEmpty()) return;
        c.drawText("Recent searches", RX, RY + 30, tv.tTitle);
        for (int i = 0; i <= recent.size(); i++) {
            boolean f = zone == RECENT && i == recentFocus;
            float y = RY + 70 + i * RECENT_ROW;
            r.set(RX, y, RX + RECENT_W, y + RECENT_ROW - 10);
            tv.fill.setColor(f ? Theme.FOCUS : i < recent.size() ? Theme.CARD : Theme.BG);
            c.drawRoundRect(r, 10, 10, tv.fill);
            tv.tBody.setColor(f ? Theme.ON_FOCUS : i < recent.size() ? Theme.TEXT : Theme.TEXT_DIM);
            c.drawText(i < recent.size() ? tv.fit(recent.get(i), tv.tBody, RECENT_W - 60) : "Clear recent searches", RX + 30, y + 43, tv.tBody);
        }
        tv.tBody.setColor(Theme.TEXT_BODY);
    }

    private void press() {
        int typeBase = CHARS.length() + SPECIAL.length;
        if (keyFocus >= typeBase) {
            int k = keyFocus - typeBase;
            if (k != Search.kind) {
                Search.kind = k;
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
        if (Voice.listening) return true;
        switch (zone) {
            case NAV:
                if (k == TvView.DOWN) {
                    zone = voice ? MIC : KEYS;
                    return true;
                }
                return nav.key(k);
            case MIC:
                if (k == TvView.OK) listen();
                else if (k == TvView.UP) {
                    zone = NAV;
                    nav.focus = nav.current;
                } else if (k == TvView.DOWN) zone = KEYS;
                else if (k == TvView.LEFT) zone = KEYS;
                else if (k == TvView.RIGHT) {
                    if (query.length() == 0 && !recent.isEmpty()) {
                        zone = RECENT;
                        recentFocus = 0;
                    } else if (!grid.items.isEmpty()) {
                        zone = RESULTS;
                        grid.focusIn();
                    }
                }
                return true;
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
                        if (voice) zone = MIC;
                        else {
                            zone = NAV;
                            nav.focus = nav.current;
                        }
                    } else if (type) keyFocus = chars + (keyFocus - types);
                    else if (special) keyFocus = (rows - 1) * KCOLS + col;
                    else keyFocus -= KCOLS;
                } else if (k == TvView.DOWN) {
                    if (row == rows - 1) keyFocus = chars + Math.min(SPECIAL.length - 1, col / 2);
                    else if (special) keyFocus = types + Math.min(Search.KIND_LABEL.length - 1, keyFocus - chars);
                    else if (!type) keyFocus += KCOLS;
                } else if (k == TvView.LEFT) {
                    if (wide ? keyFocus > base : col > 0) keyFocus--;
                } else if (k == TvView.RIGHT) {
                    boolean lastCol = wide ? keyFocus == base + 2 : col == KCOLS - 1;
                    if (!lastCol) keyFocus++;
                    else if (query.length() == 0 && !recent.isEmpty()) {
                        zone = RECENT;
                        recentFocus = 0;
                    } else if (!grid.items.isEmpty()) {
                        zone = RESULTS;
                        grid.focusIn();
                    }
                }
                return true;
            }
            case RECENT:
                if (k == TvView.UP && recentFocus > 0) recentFocus--;
                else if (k == TvView.DOWN && recentFocus < recent.size()) recentFocus++;
                else if (k == TvView.LEFT) zone = KEYS;
                else if (k == TvView.OK) {
                    zone = KEYS;
                    if (recentFocus < recent.size()) {
                        query.append(recent.get(recentFocus));
                        tv.removeCallbacks(fire);
                        dueAt = 0;
                        search();
                    } else {
                        Search.clearRecent(tv.host.prefs());
                        recent.clear();
                    }
                }
                return true;
            case RESULTS:
            default:
                if (grid.open(tv, k)) {
                    if (grid.focused() != null) Search.remember(tv.host.prefs(), query.toString());
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
        recent = Search.recent(tv.host.prefs());
    }

    @Override
    void onHide() {
        Voice.cancel();
    }

    @Override
    boolean back() {
        if (Voice.listening) {
            Voice.cancel();
            return true;
        }
        if (zone == RESULTS || zone == RECENT || zone == MIC) {
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
