package com.codio.tv.ui;

import android.graphics.Canvas;

import java.util.ArrayList;
import java.util.List;

/** One row of Music tiles (songs, albums or artists); the focused tile stays at the left and the row slides under it. */
final class MusicStrip {
    static final float TITLE_H = 66, HEIGHT = TITLE_H + TvMusicKit.TILE + 110;

    final String title;
    /** A chart row: its songs are numbered. */
    boolean numbered;
    final List<Object> items = new ArrayList<Object>();
    int focus, prevFocus = -1;
    final Smooth scroll = new Smooth(0.085f, 0.4f);
    final Smooth grow = new Smooth(0.07f, 0.01f);

    MusicStrip(String title, List<?> list) {
        this.title = title;
        items.addAll(list);
    }

    Object focused() {
        return items.isEmpty() ? null : items.get(Math.min(focus, items.size() - 1));
    }

    boolean move(int d) {
        int n = Math.max(0, Math.min(items.size() - 1, focus + d));
        if (n == focus) return false;
        prevFocus = focus;
        focus = n;
        grow.v = 0;
        grow.to(1);
        scroll.to(focus * TvMusicKit.STEP);
        return true;
    }

    void focusIn() {
        prevFocus = -1;
        grow.snap(0);
        grow.to(1);
    }

    /** Keeps the place when the row is rebuilt with (mostly) the same things. */
    void keep(MusicStrip old) {
        if (old == null) return;
        focus = Math.min(old.focus, Math.max(0, items.size() - 1));
        scroll.snap(Math.min(old.scroll.v, focus * TvMusicKit.STEP));
        scroll.to(focus * TvMusicKit.STEP);
        grow.snap(old.grow.v);
        grow.to(old.grow.target);
    }

    private String number(int i) {
        return numbered && items.get(i) instanceof com.codio.tv.music.Model.Track ? (i + 1) + ". " : null;
    }

    void draw(Screen s, Canvas c, float y, boolean focusedRow, float dt) {
        TvView tv = s.tv;
        c.drawText(title, TvView.MARGIN, y + 38, tv.tTitle);
        float sx = s.step(scroll, dt), g = s.step(grow, dt), ty = y + TITLE_H;
        int first = Math.max(0, (int) (sx / TvMusicKit.STEP) - 1);
        int last = Math.min(items.size() - 1, (int) ((sx + TvView.W) / TvMusicKit.STEP) + 1);
        for (int i = first; i <= last; i++) {
            if (focusedRow && i == focus) continue;
            TvMusicKit.tile(tv, c, items.get(i), TvView.MARGIN + i * TvMusicKit.STEP - sx, ty, focusedRow && i == prevFocus ? 1 - g : 0, number(i));
        }
        if (focusedRow && focus >= first && focus <= last) {
            TvMusicKit.tile(tv, c, items.get(focus), TvView.MARGIN + focus * TvMusicKit.STEP - sx, ty, g, number(focus));
        }
    }
}
