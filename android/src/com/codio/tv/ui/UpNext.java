package com.codio.tv.ui;

import org.json.JSONObject;

/**
 * The Up Next card: near the end of an episode it shows the next one with a countdown, and starts it when the countdown
 * runs out. The same rules on every Codio app (see DESIGN.md):
 *
 * - Credits timed (IntroDB): the card comes up as the credits start and counts down 10 seconds.
 * - Credits not timed: the card comes up "Up Next" seconds before the end (Settings) and counts down to the end.
 * - "Next episode" set to Off: the card still offers the next episode, but nothing starts by itself.
 * - "Up Next" set to Don't show: no card.
 *
 * The countdown follows the video's position, so it stops while paused and goes away after seeking back.
 */
final class UpNext {
    private UpNext() {}

    static final double COUNTDOWN = 10;

    static final class Card {
        /** A countdown is running: `left` seconds to go, `fraction` 0..1 of it done. */
        boolean counting;
        double left;
        float fraction;
        /** The countdown ran out: start the next episode now. */
        boolean fire;

        String heading() {
            return counting ? "Up Next in " + (int) Math.max(1, Math.ceil(left)) : "Up Next";
        }
    }

    /** The card for position t (seconds), or null when it isn't time yet (or never is). */
    static Card at(SkipTimes.Times skip, double t, double duration, int beforeEndMs, boolean playsByItself) {
        if (beforeEndMs <= 0) return null;
        if (duration > 0 && t >= duration - 1) return null;
        Card c = new Card();
        if (skip != null && skip.outro != null) {
            double start = skip.outro.start;
            if (t < start) return null;
            double end = start + COUNTDOWN;
            if (duration > 0) end = Math.min(end, duration - 1);
            c.left = end - t;
            c.counting = playsByItself && c.left > 0;
            c.fraction = end > start ? (float) Math.max(0, Math.min(1, (t - start) / (end - start))) : 1;
            c.fire = playsByItself && c.left <= 0;
            return c;
        }
        if (duration <= 0) return null;
        double left = duration - t, window = beforeEndMs / 1000.0;
        if (left <= 0 || left > window) return null;
        // The end of the video starts the next episode (the player does that itself), so nothing fires here.
        c.left = left;
        c.counting = playsByItself;
        c.fraction = (float) Math.max(0, Math.min(1, 1 - left / window));
        return c;
    }

    /** Settings, Playback, Up Next: how long before the end the card appears when the credits aren't timed (0 = never). */
    static int beforeEndMs(Store store) {
        JSONObject s = store.profileJson.optJSONObject("settings");
        return s != null ? s.optInt("nextVideoNotificationDuration", 35000) : 35000;
    }

    /** Settings, Playback, Next episode: Plays automatically. */
    static boolean playsByItself(Store store) {
        JSONObject s = store.profileJson.optJSONObject("settings");
        return s == null || s.optBoolean("bingeWatching", true);
    }

    static String episodeLine(Store.Video v) {
        return "S" + v.season + " E" + v.episode + (v.title == null || v.title.isEmpty() ? "" : "  ·  " + v.title);
    }

    /** The picture for the card: the episode's own, else the show's artwork. */
    static String picture(Store.Meta meta, Store.Video v) {
        if (v.thumbnail != null) return v.thumbnail;
        String b = HomeScreen.backgroundOf(meta);
        return b != null ? b : meta.poster;
    }
}
