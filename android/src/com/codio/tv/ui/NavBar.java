package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.Color;

/** The top bar: logo and sections, with the round Who's watching avatar on the right. */
final class NavBar {
    static final int HOME = 0, MOVIES = 1, SERIES = 2, SEARCH = 3, LIBRARY = 4, ADDONS = 5, SETTINGS = 6;
    static final String[] ITEMS = {"Home", "Movies", "Series", "Search", "Library", "Addons", "Settings"};
    /** Focus index of the profile avatar (right of the last section). */
    static final int PROFILE = ITEMS.length;
    static final float Y = 44, HEIGHT = 120, AVATAR_R = 30;

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
        float cx = TvView.W - TvView.MARGIN - AVATAR_R, cy = Y + 28;
        boolean f = focused && focus == PROFILE;
        float r = f ? AVATAR_R + 4 : AVATAR_R;
        if (f) {
            tv.fill.setColor(Color.WHITE);
            c.drawCircle(cx, cy, r + 5, tv.fill);
        }
        Profiles.drawAvatar(c, tv.fill, tv.tLabel, cx, cy, r, Profiles.current(tv.store, tv.host.prefs()));
    }

    /** LEFT/RIGHT/OK while the bar has focus. */
    boolean key(int k) {
        if (k == TvView.LEFT && focus > 0) {
            focus--;
            return true;
        }
        if (k == TvView.RIGHT && focus < PROFILE) {
            focus++;
            return true;
        }
        if (k == TvView.OK) {
            if (focus == PROFILE) tv.push(new ProfilesScreen(tv));
            else if (focus != current) tv.navigate(focus);
            return true;
        }
        return k == TvView.LEFT || k == TvView.RIGHT;
    }
}
