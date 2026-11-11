package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.Color;

/** The top bar: logo and sections. */
final class NavBar {
    static final int HOME = 0, MOVIES = 1, SERIES = 2, SEARCH = 3, LIBRARY = 4, ADDONS = 5, SETTINGS = 6;
    static final String[] ITEMS = {"Home", "Movies", "Series", "Search", "Library", "Addons", "Settings"};
    static final float Y = 44, HEIGHT = 120;

    private final TvView tv;
    final int current;
    int focus;

    NavBar(TvView tv, int current) {
        this.tv = tv;
        this.current = current;
        this.focus = current;
    }

    void draw(Canvas c, boolean focused) {
        tv.drawLogo(c, TvView.MARGIN, Y + 2);
        float x = TvView.MARGIN + TvView.LOGO_W + 60;
        for (int i = 0; i < ITEMS.length; i++) {
            String s = ITEMS[i];
            float w = tv.chipWidth(s);
            if (focused && i == focus) {
                tv.drawChip(c, x, Y, s, true, false);
            } else {
                tv.tNav.setColor(i == current ? Color.WHITE : 0xFFB3B3B3);
                c.drawText(s, x + 26, Y + 37, tv.tNav);
                if (i == current) {
                    tv.fill.setColor(TvView.RED);
                    c.drawRect(x + 26, Y + 50, x + w - 26, Y + 54, tv.fill);
                }
                tv.tNav.setColor(0xFFB3B3B3);
            }
            x += w + 8;
        }
    }

    /** LEFT/RIGHT/OK while the bar has focus. */
    boolean key(int k) {
        if (k == TvView.LEFT && focus > 0) {
            focus--;
            return true;
        }
        if (k == TvView.RIGHT && focus < ITEMS.length - 1) {
            focus++;
            return true;
        }
        if (k == TvView.OK) {
            if (focus != current) tv.navigate(focus);
            return true;
        }
        return k == TvView.LEFT || k == TvView.RIGHT;
    }
}
