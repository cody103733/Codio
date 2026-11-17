package com.codio.tv.ui;

import android.graphics.Canvas;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * A Music page made of a top part (an optional picture and name, then a line of buttons) and rows of tiles under it.
 * The Music home, the search results and an artist's page are all this. OK opens or plays, holding OK shows the options.
 */
abstract class MusicPage extends Screen implements TvMusicKit.Listening {
    static final int NAV = -2, HEAD = -1;

    final NavBar nav;
    final List<MusicStrip> strips = new ArrayList<MusicStrip>();
    int zone = HEAD, headFocus;
    private final Smooth scroll = new Smooth(0.1f, 0.4f);
    boolean busy;

    MusicPage(TvView tv, boolean withNav) {
        super(tv);
        TvMusicKit.start(tv);
        nav = withNav ? new NavBar(tv, NavBar.MUSIC) : null;
    }

    /** Height of the picture-and-name part above the buttons (0 for none). */
    float heroHeight() {
        return 0;
    }

    void drawHero(Canvas c, float y, float dt) {}

    abstract List<String> buttons();

    abstract void button(int index);

    /** A line under the buttons (a hint, "Nothing found"), or null. */
    String note() {
        return null;
    }

    /** Puts the rows together again from what is known now. */
    abstract void rebuild();

    @Override
    public void musicChanged() {
        rebuild();
    }

    /** Swaps in new rows, keeping the place in rows that are still there (by title). */
    final void setStrips(List<MusicStrip> next) {
        MusicStrip focused = zone >= 0 && zone < strips.size() ? strips.get(zone) : null;
        for (MusicStrip n : next) {
            for (MusicStrip o : strips) if (o.title.equals(n.title)) n.keep(o);
        }
        strips.clear();
        strips.addAll(next);
        if (zone >= 0) {
            int at = -1;
            for (int i = 0; focused != null && i < strips.size(); i++) if (strips.get(i).title.equals(focused.title)) at = i;
            zone = at >= 0 ? at : strips.isEmpty() ? HEAD : Math.min(zone, strips.size() - 1);
        }
        headFocus = Math.max(0, Math.min(headFocus, buttons().size() - 1));
        tv.kick();
    }

    private float top() {
        return nav != null ? NavBar.HEIGHT + 20 : 70;
    }

    private float rowsTop() {
        return top() + heroHeight() + 86 + (note() != null ? 56 : 0);
    }

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(Theme.BG);
        scroll.to(zone > 0 || (zone == 0 && heroHeight() > 0) ? rowsTop() + zone * MusicStrip.HEIGHT - (nav != null ? 150 : 90) : 0);
        float sy = step(scroll, dt);
        float y = top() - sy;
        if (heroHeight() > 0 && y + heroHeight() > 0) drawHero(c, y, dt);
        y += heroHeight();
        List<String> b = buttons();
        float x = TvView.MARGIN;
        for (int i = 0; i < b.size(); i++) {
            if (y > -80) tv.drawChip(c, x, y, b.get(i), zone == HEAD && i == headFocus, false);
            x += tv.chipWidth(b.get(i)) + 14;
        }
        if (busy) tv.spinner(c, x + 40, y + 28, 18, dt);
        if (note() != null && y > -140) tv.drawWrapped(c, note(), tv.tBody, TvView.MARGIN, y + 78, TvView.W - 2 * TvView.MARGIN, 1);
        float ry = rowsTop() - sy;
        float bottom = TvView.H - (com.codio.tv.music.MusicPlayer.hasQueue() ? TvMusicKit.BAR_H : 0);
        for (int i = 0; i < strips.size(); i++) {
            float sy2 = ry + i * MusicStrip.HEIGHT;
            if (sy2 > bottom || sy2 + MusicStrip.HEIGHT < 0) continue;
            strips.get(i).draw(this, c, sy2, zone == i, dt);
        }
        if (nav != null) nav.draw(c, zone == NAV, sy > 0);
        TvMusicKit.nowBar(tv, c, dt);
    }

    @Override
    boolean holdsOk() {
        return zone >= 0;
    }

    @Override
    void longOk() {
        if (zone < 0 || zone >= strips.size()) return;
        MusicStrip s = strips.get(zone);
        if (s.focused() != null) TvMusicKit.options(tv, s.focused(), s.items);
    }

    @Override
    boolean key(int k, KeyEvent e) {
        if (TvMusicKit.mediaKey(k)) return true;
        if (zone == NAV) {
            if (k == TvView.DOWN) {
                zone = HEAD;
                return true;
            }
            return nav.key(k);
        }
        if (zone == HEAD) {
            int n = buttons().size();
            if (k == TvView.LEFT && headFocus > 0) headFocus--;
            else if (k == TvView.RIGHT && headFocus < n - 1) headFocus++;
            else if (k == TvView.UP && nav != null) {
                zone = NAV;
                nav.focus = nav.current;
            } else if (k == TvView.DOWN && !strips.isEmpty()) {
                zone = 0;
                strips.get(0).focusIn();
            } else if (k == TvView.OK && headFocus < n) button(headFocus);
            return true;
        }
        if (zone >= strips.size()) {
            zone = HEAD;
            return true;
        }
        MusicStrip s = strips.get(zone);
        switch (k) {
            case TvView.UP:
                if (zone > 0) strips.get(--zone).focusIn();
                else zone = HEAD;
                return true;
            case TvView.DOWN:
                if (zone < strips.size() - 1) strips.get(++zone).focusIn();
                return true;
            case TvView.LEFT:
                s.move(-1);
                return true;
            case TvView.RIGHT:
                s.move(1);
                return true;
            case TvView.OK:
                if (s.focused() != null) TvMusicKit.open(tv, s.focused(), s.items);
                return true;
            default:
                return false;
        }
    }

    @Override
    boolean back() {
        if (zone >= 0) {
            zone = HEAD;
            return true;
        }
        if (zone == HEAD && nav != null) {
            zone = NAV;
            nav.focus = nav.current;
            return true;
        }
        return false;
    }
}
