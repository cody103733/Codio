package com.codio.tv.ui;

import android.graphics.Canvas;

import java.util.ArrayList;
import java.util.List;

/** One horizontal row of posters; the focused poster stays at the left edge and the row slides under it. */
final class Row {
    static final float PW = 200, PH = 300, STEP = 228, TITLE_H = 72;
    static final float HEIGHT = TITLE_H + PH + 72;

    final String title;
    final Store.Catalog catalog;
    final List<Store.Meta> items = new ArrayList<>();
    int focus = 0, prevFocus = -1;
    boolean loading, done, failed, requested;
    final Smooth scroll = new Smooth(0.085f, 0.4f);
    final Smooth grow = new Smooth(0.07f, 0.01f);

    Row(String title, Store.Catalog catalog) {
        this.title = title;
        this.catalog = catalog;
    }

    boolean visible() {
        return !items.isEmpty() || (loading && !failed) || !requested;
    }

    Store.Meta focused() {
        return items.isEmpty() ? null : items.get(Math.min(focus, items.size() - 1));
    }

    boolean move(int d) {
        int n = Math.max(0, Math.min(items.size() - 1, focus + d));
        if (n == focus) return false;
        prevFocus = focus;
        focus = n;
        grow.v = 0;
        grow.to(1);
        scroll.to(focus * STEP);
        return true;
    }

    void load(final TvView tv) {
        if (catalog == null || loading || done) return;
        requested = true;
        loading = true;
        final int skip = items.size();
        tv.store.loadCatalog(catalog, null, skip, null, new Store.Callback<List<Store.Meta>>() {
            @Override
            public void done(List<Store.Meta> result, Exception error) {
                loading = false;
                if (error != null && items.isEmpty()) failed = true;
                if (result == null || result.isEmpty() || !catalog.skip) done = true;
                if (result != null) {
                    // Skip duplicates some addons repeat across pages.
                    java.util.HashSet<String> seen = new java.util.HashSet<>();
                    for (Store.Meta m : items) seen.add(m.id);
                    for (Store.Meta m : result) if (seen.add(m.id)) items.add(m);
                }
                tv.dataChanged(false);
            }
        });
    }

    /** Ask for the next page when the viewer nears the end. */
    void maybeMore(TvView tv) {
        if (!done && !loading && catalog != null && catalog.skip && focus >= items.size() - 8) load(tv);
    }

    void draw(Screen s, Canvas c, float y, boolean focusedRow, float dt) {
        TvView tv = s.tv;
        c.drawText(title, TvView.MARGIN, y + 38, tv.tTitle);
        float sx = s.step(scroll, dt);
        float g = s.step(grow, dt);
        float py = y + TITLE_H;
        if (items.isEmpty()) {
            if (loading || !requested) {
                tv.fill.setColor(0xFF1C1C1C);
                for (int i = 0; i < 8; i++) {
                    float x = TvView.MARGIN + i * STEP;
                    c.drawRoundRect(x, py, x + PW, py + PH, 12, 12, tv.fill);
                }
            }
            return;
        }
        int first = Math.max(0, (int) (sx / STEP) - 1);
        int last = Math.min(items.size() - 1, (int) ((sx + TvView.W) / STEP) + 1);
        // Warm the cache just past the right edge (asked first: the newest request loads first).
        for (int i = last + 1; i <= Math.min(items.size() - 1, last + 3); i++) {
            tv.images.get(items.get(i).poster, Math.round(PW * 1.09f * tv.scale()), Math.round(PH * 1.09f * tv.scale()), 12 * tv.scale());
        }
        // Draw the focused poster last, so its growth overlaps its neighbours.
        for (int i = first; i <= last; i++) {
            if (focusedRow && i == focus) continue;
            float x = TvView.MARGIN + i * STEP - sx;
            float f = focusedRow && i == prevFocus ? 1 - g : 0;
            tv.drawPoster(c, items.get(i), x, py, PW, PH, f);
        }
        if (focusedRow && focus >= first && focus <= last) {
            float x = TvView.MARGIN + focus * STEP - sx;
            tv.drawPoster(c, items.get(focus), x, py, PW, PH, g);
        }
    }
}
