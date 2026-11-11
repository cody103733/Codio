package com.codio.tv.ui;

import android.graphics.Canvas;

import java.util.ArrayList;
import java.util.List;

/** A poster grid (Discover, Search) that scrolls to keep the focused row in view. */
final class Grid {
    static final float PW = 200, PH = 300, STEP_X = 236, STEP_Y = 384;

    final List<Store.Meta> items = new ArrayList<>();
    final int cols;
    int focus = 0, prev = -1;
    final Smooth grow = new Smooth(0.07f, 0.01f);

    Grid(int cols) {
        this.cols = cols;
    }

    int row() {
        return focus / cols;
    }

    int rows() {
        return (items.size() + cols - 1) / cols;
    }

    void reset() {
        items.clear();
        focus = 0;
        prev = -1;
    }

    Store.Meta focused() {
        return items.isEmpty() ? null : items.get(Math.min(focus, items.size() - 1));
    }

    /** Moves focus; false at an edge (the caller may move to another part of the screen). */
    boolean move(int k) {
        int n = focus;
        if (k == TvView.LEFT) {
            if (focus % cols == 0) return false;
            n--;
        } else if (k == TvView.RIGHT) {
            if (focus % cols == cols - 1 || focus + 1 >= items.size()) return false;
            n++;
        } else if (k == TvView.UP) {
            if (focus < cols) return false;
            n -= cols;
        } else if (k == TvView.DOWN) {
            if (row() >= rows() - 1) return false;
            n = Math.min(items.size() - 1, focus + cols);
        }
        if (n == focus) return false;
        prev = focus;
        focus = n;
        grow.snap(0);
        grow.to(1);
        return true;
    }

    void focusIn() {
        prev = -1;
        grow.snap(0);
        grow.to(1);
    }

    /** Draws rows that are on screen; y0 is the top of the first row after scrolling. */
    void draw(Screen s, Canvas c, float x0, float y0, boolean focused, float dt) {
        TvView tv = s.tv;
        float g = s.step(grow, dt);
        int firstRow = Math.max(0, (int) Math.floor((-y0 - PH) / STEP_Y));
        int lastRow = Math.min(rows() - 1, (int) ((TvView.H - y0) / STEP_Y));
        // Next row first, so it loads after what's on screen.
        int pre = Math.min(items.size(), (lastRow + 2) * cols);
        for (int i = (lastRow + 1) * cols; i < pre; i++) {
            tv.images.get(items.get(i).poster, Math.round(PW * 1.09f * tv.scale()), Math.round(PH * 1.09f * tv.scale()), 12 * tv.scale());
        }
        for (int r = firstRow; r <= lastRow; r++) {
            for (int col = 0; col < cols; col++) {
                int i = r * cols + col;
                if (i >= items.size()) break;
                if (focused && i == focus) continue;
                float f = focused && i == prev ? 1 - g : 0;
                tv.drawPoster(c, items.get(i), x0 + col * STEP_X, y0 + r * STEP_Y, PW, PH, f);
            }
        }
        if (focused && focus < items.size()) {
            int r = focus / cols, col = focus % cols;
            float x = x0 + col * STEP_X, y = y0 + r * STEP_Y;
            tv.drawPoster(c, items.get(focus), x, y, PW, PH, g);
            Store.Meta m = items.get(focus);
            String label = m.name + (m.releaseInfo != null ? "  (" + m.releaseInfo + ")" : "");
            float w = tv.tSmall.measureText(label);
            float lx = Math.max(x0, Math.min(x + PW / 2 - Math.min(w, STEP_X * 2) / 2, x0 + cols * STEP_X - STEP_X * 2));
            tv.tSmall.setColor(0xFFFFFFFF);
            c.drawText(tv.fit(label, tv.tSmall, STEP_X * 2), lx, y + PH + 50, tv.tSmall);
            tv.tSmall.setColor(0xFFAAAAAA);
        }
    }
}
