package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.Color;

/** The top bar: logo and sections, with the round Who's watching avatar on the right. */
final class NavBar {
    static final int HOME = 0, MOVIES = 1, SERIES = 2, SEARCH = 3, LIBRARY = 4, ADDONS = 5, SETTINGS = 6;
    static final String[] ITEMS = {"Home", "Movies", "Shows", "Search", "Library", "Addons", "Settings"};
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

    /** Draws the bar; scrolled = the page has moved under it, so it gets a solid band to stay readable. */
    void draw(Canvas c, boolean focused, boolean scrolled) {
        if (scrolled) {
            tv.fill.setColor(Theme.BG);
            c.drawRect(0, 0, TvView.W, HEIGHT, tv.fill);
            c.drawRect(0, HEIGHT, TvView.W, HEIGHT + 60, tv.topShade);
        }
        draw(c, focused);
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
                // Like the iPhone's tab bar: the current page in red, the rest grey.
                tv.tNav.setColor(i == current ? Theme.RED : PhoneKit.GRAY);
                c.drawText(s, x + 26, Y + 37, tv.tNav);
                tv.tNav.setColor(Theme.TEXT_DIM);
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
