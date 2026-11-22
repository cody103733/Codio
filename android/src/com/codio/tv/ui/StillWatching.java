package com.codio.tv.ui;

import android.content.SharedPreferences;

/**
 * "Are you still watching?" (Settings > Playback, off unless turned on): after this many episodes in a row that
 * started by themselves with nobody pressing or touching anything, the next one waits for the viewer instead.
 * Same rule on every Codio app (DESIGN.md).
 */
public final class StillWatching {
    public static final String PREF = "still_watching";
    private static int run;

    private StillWatching() {
    }

    /** A key was pressed or the screen touched: somebody is there. */
    public static void touched() {
        run = 0;
    }

    /** The next episode is about to start by itself. True = go ahead (it's counted); false = ask first. */
    public static boolean mayContinue(SharedPreferences p) {
        int after = p.getInt(PREF, 0);
        if (after > 0 && run >= after) {
            run = 0;
            return false;
        }
        run++;
        return true;
    }
}
