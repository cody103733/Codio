package com.codio.tv.ui;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The ambient screen: when Codio sits untouched, a slow slideshow of big pictures from the Library (Continue Watching
 * first, else the Home rows) instead of the TV going black. Any button brings back the screen underneath.
 *
 * Kept light for the Fire TV: two 1280x720 pictures in memory at most, frames only while a picture fades (and once a
 * minute for the clock), and after an hour the screen goes dark and lets the TV sleep as usual.
 */
final class AmbientScreen extends Screen {
    static final String PREF = "ambient", DELAY_PREF = "ambient_delay_ms";
    static final int DEFAULT_DELAY = 120000;
    /** How long each picture stays, how long the fade takes, and when the slideshow gives the TV back to its own sleep. */
    private static final long SLIDE_MS = 12000, FADE_MS = 1500, RUN_MS = 60 * 60 * 1000L;

    static boolean enabled(SharedPreferences prefs) {
        return !"off".equals(prefs.getString(PREF, "on"));
    }

    static long delay(SharedPreferences prefs) {
        return Math.max(30000, prefs.getInt(DELAY_PREF, DEFAULT_DELAY));
    }

    /** Items to show, already shuffled; empty when there's nothing with a picture. */
    static List<Store.Meta> pick(TvView tv) {
        List<Store.Meta> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Store.Meta> lib = new ArrayList<>(tv.store.continueWatching());
        lib.addAll(tv.store.libraryItems(null));
        for (Store.Meta m : lib) {
            if (out.size() >= 40) break;
            if (m.id != null && seen.add(m.id) && picture(m) != null) out.add(m);
        }
        if (out.size() < 6 && !tv.stack.isEmpty() && tv.root() instanceof HomeScreen) {
            for (Store.Meta m : ((HomeScreen) tv.root()).pictureItems(30)) if (m.id != null && seen.add(m.id)) out.add(m);
        }
        Collections.shuffle(out);
        return out;
    }

    /** The wide picture: the title's own backdrop, else the one Cinemeta's image service has for IMDb ids. */
    static String picture(Store.Meta m) {
        if (m.background != null) return m.background;
        if (m.id != null && m.id.startsWith("tt")) return "https://images.metahub.space/background/medium/" + m.id + "/img";
        return null;
    }

    private final List<Store.Meta> items;
    private int index = 0, prevIndex = -1;
    private long shownAt, started;
    private float mix = 1;
    private boolean asleep;
    private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF full = new RectF(0, 0, TvView.W, TvView.H);
    private final DateFormat clock;

    AmbientScreen(TvView tv, List<Store.Meta> items) {
        super(tv);
        this.items = items;
        clock = android.text.format.DateFormat.getTimeFormat(tv.getContext());
    }

    @Override
    void onShow() {
        started = SystemClock.uptimeMillis();
        shownAt = 0;
        asleep = false;
        tv.setKeepScreenOn(true);
    }

    @Override
    void onHide() {
        tv.setKeepScreenOn(false);
    }

    @Override
    boolean key(int k, KeyEvent e) {
        return true;
    }

    private Bitmap bitmap(int i, boolean load) {
        if (i < 0 || items.isEmpty()) return null;
        String url = picture(items.get(i % items.size()));
        if (load) return tv.images.backdrop(url, Backdrop.BW, Backdrop.BH);
        return tv.images.cached(url, Backdrop.BW, Backdrop.BH, false);
    }

    @Override
    void draw(Canvas c, float dt) {
        long now = SystemClock.uptimeMillis();
        c.drawColor(Color.BLACK);
        if (asleep || items.isEmpty()) return;
        if (now - started > RUN_MS) {
            // An hour is enough: go dark and let the TV's own screen saver / sleep take over.
            asleep = true;
            tv.setKeepScreenOn(false);
            tv.images.trim();
            return;
        }
        Bitmap cur = bitmap(index, true);
        if (cur == null && tv.images.isFailed(picture(items.get(index)))) {
            // No picture for this one: try the next (and stop trying once none of them load).
            items.remove(index);
            if (items.isEmpty()) return;
            index %= items.size();
            prevIndex = -1;
            tv.moving();
            return;
        }
        if (cur != null && shownAt == 0) {
            shownAt = now;
            mix = 0;
        }
        if (cur != null && mix < 1) {
            mix = Math.min(1, mix + dt * 1000 / FADE_MS);
            tv.moving();
        }
        Bitmap prev = mix < 1 ? bitmap(prevIndex, false) : null;
        if (prev != null) {
            p.setAlpha(255);
            c.drawBitmap(prev, null, full, p);
        }
        if (cur != null) {
            p.setAlpha((int) (255 * ease(mix)));
            c.drawBitmap(cur, null, full, p);
        }
        c.drawRect(full, tv.bottomShade);
        c.drawRect(0, 0, TvView.W, 180, tv.topShade);

        // Title and clock move a little with each picture, so nothing sits in one place on the panel for an hour.
        float nudge = (index % 3) * 18;
        Store.Meta m = items.get(index);
        if (cur != null) {
            int a = (int) (255 * ease(mix));
            int save = a < 255 ? c.saveLayerAlpha(0, TvView.H - 420, TvView.W, TvView.H, a) : c.save();
            tv.drawTitleArt(c, m, TvView.MARGIN + nudge, TvView.H - 340 - nudge);
            tv.tMeta.setColor(Theme.TEXT_DIM);
            String line = m.releaseInfo != null ? m.releaseInfo : "";
            if (tv.store.inLibrary(m.id)) line = line.isEmpty() ? "In your Library" : line + "  ·  In your Library";
            c.drawText(tv.fit(line, tv.tMeta, 1100), TvView.MARGIN + nudge, TvView.H - 100 - nudge, tv.tMeta);
            c.restoreToCount(save);
        }
        String time = clock.format(new Date());
        tv.tHero.setTextSize(56);
        float tw = tv.tHero.measureText(time);
        tv.tHero.setColor(0xE6FFFFFF);
        c.drawText(time, TvView.W - TvView.MARGIN - tw - nudge, 110 + nudge, tv.tHero);
        tv.tHero.setColor(Color.WHITE);
        tv.tHero.setTextSize(64);

        // Next picture: ask for it a few seconds early so it's decoded when its turn comes.
        if (shownAt > 0 && items.size() > 1) {
            int next = (index + 1) % items.size();
            if (now - shownAt > SLIDE_MS - 4000) bitmap(next, true);
            if (now - shownAt >= SLIDE_MS && bitmap(next, false) != null) {
                prevIndex = index;
                index = next;
                shownAt = now;
                mix = 0;
                tv.moving();
                return;
            }
        }
        if (mix >= 1) {
            // Nothing moves until the next picture is due or the minute changes: draw again only then.
            long wait = 60000 - System.currentTimeMillis() % 60000 + 50;
            if (shownAt > 0 && items.size() > 1) {
                long since = now - shownAt;
                wait = Math.min(wait, since < SLIDE_MS - 4000 ? SLIDE_MS - 4000 - since : Math.max(250, SLIDE_MS - since));
            } else if (shownAt == 0) {
                wait = 500;
            }
            tv.soon(Math.max(100, wait));
        }
    }

    private static float ease(float t) {
        return t * t * (3 - 2 * t);
    }
}
