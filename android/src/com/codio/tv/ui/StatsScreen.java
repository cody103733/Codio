package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.KeyEvent;

import com.codio.tv.music.Model;

import java.util.ArrayList;
import java.util.List;

/**
 * Your stats on the TV (DESIGN.md "Your stats"), opened from Settings. Left: the four numbers, Top shows and Top movies;
 * right: Top songs and Top artists. Up/Down move between the year chips, the poster rows and the songs; OK opens a title
 * or plays a song.
 */
final class StatsScreen extends Screen {
    private static final float X = TvView.MARGIN, TOP = 150, RIGHT_X = 1230;
    private static final float PW = 140, PH = 210;
    private static final int CHIPS = 0, SHOWS = 1, MOVIES = 2, SONGS = 3;

    private int sel = 0, zone = CHIPS;
    private final int[] at = new int[4];
    private Stats.Watch watch;
    private Stats.Listen listen;
    private final RectF r = new RectF();
    private final Smooth[] grow = new Smooth[16];

    StatsScreen(TvView tv) {
        super(tv);
        for (int i = 0; i < grow.length; i++) grow[i] = new Smooth(0.07f, 0.01f);
        load();
    }

    private int year() {
        return sel == 0 ? Stats.thisYear() : 0;
    }

    private void load() {
        watch = Stats.watch(tv.store.libraryEntries(), Store.profileId(), year());
        listen = Stats.listen(year());
    }

    @Override
    void dataChanged(boolean addonsChanged) {
        load();
    }

    private int size(int z) {
        switch (z) {
            case CHIPS:
                return 2;
            case SHOWS:
                return Math.min(5, watch.shows.size());
            case MOVIES:
                return Math.min(5, watch.movies.size());
            default:
                return Math.min(5, listen.songs.size());
        }
    }

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(Theme.BG);
        c.drawText("Your stats", X, TOP - 40, tv.tTitle);
        float cx = X + tv.tTitle.measureText("Your stats") + 40;
        String[] labels = {String.valueOf(Stats.thisYear()), "All time"};
        for (int i = 0; i < 2; i++) cx += tv.drawChip(c, cx, TOP - 84, labels[i], zone == CHIPS && at[CHIPS] == i, sel == i) + 16;

        // The four numbers.
        float tw = 250, th = 150, gap = 18, ty = TOP;
        String[][] tiles = {
                {Stats.hours(watch.ms), "Hours watched", null},
                {String.valueOf(watch.shows.size() + watch.movies.size()), "Titles watched",
                        Stats.count(watch.movies.size(), "movie") + " · " + Stats.count(watch.shows.size(), "show")},
                {String.valueOf(Math.round(listen.seconds / 60)), "Minutes listened", null},
                {String.valueOf(listen.plays), "Songs played", null},
        };
        for (int i = 0; i < 4; i++) {
            float x = X + i * (tw + gap);
            r.set(x, ty, x + tw, ty + th);
            tv.fill.setColor(PhoneKit.GROUPED);
            c.drawRoundRect(r, 16, 16, tv.fill);
            float was = tv.tHero.getTextSize();
            tv.tHero.setTextSize(54);
            c.drawText(tv.fit(tiles[i][0], tv.tHero, tw - 40), x + 22, ty + 66, tv.tHero);
            tv.tHero.setTextSize(was);
            tv.tBody.setColor(Theme.TEXT_BODY);
            c.drawText(tiles[i][1], x + 22, ty + 104, tv.tBody);
            if (tiles[i][2] != null) {
                tv.tSmall.setColor(Theme.TEXT_FAINT);
                c.drawText(tv.fit(tiles[i][2], tv.tSmall, tw - 40), x + 22, ty + 134, tv.tSmall);
                tv.tSmall.setColor(Theme.TEXT_DIM);
            }
        }

        posters(c, dt, "Top shows", watch.shows, SHOWS, 380, 0);
        posters(c, dt, "Top movies", watch.movies, MOVIES, 720, 5);

        // Right column: songs and artists.
        heading(c, "Top songs", RIGHT_X, TOP + 10);
        float sy = TOP + 34;
        if (listen.songs.isEmpty()) empty(c, "Nothing played yet", RIGHT_X, sy + 40);
        for (int i = 0; i < Math.min(5, listen.songs.size()); i++) {
            Stats.Song s = listen.songs.get(i);
            boolean f = zone == SONGS && at[SONGS] == i;
            float y = sy + i * 84;
            if (f) {
                r.set(RIGHT_X - 14, y, TvView.W - TvView.MARGIN + 14, y + 78);
                tv.fill.setColor(Theme.FOCUS);
                c.drawRoundRect(r, 14, 14, tv.fill);
            }
            TvMusicKit.cover(tv, c, s.track.artworkURL, s.track.title, RIGHT_X, y + 7, 64, false, Math.round(64 * tv.scale()));
            String plays = Stats.count(s.count, "play");
            tv.tSmall.setColor(f ? Theme.ON_FOCUS_DIM : Theme.RED);
            float pw = tv.tSmall.measureText(plays);
            c.drawText(plays, TvView.W - TvView.MARGIN - pw, y + 48, tv.tSmall);
            float textW = TvView.W - TvView.MARGIN - pw - 30 - (RIGHT_X + 84);
            tv.tLabel.setColor(f ? Theme.ON_FOCUS : Color.WHITE);
            c.drawText(tv.fit(s.track.title, tv.tLabel, textW), RIGHT_X + 84, y + 36, tv.tLabel);
            tv.tSmall.setColor(f ? Theme.ON_FOCUS_DIM : Theme.TEXT_DIM);
            c.drawText(tv.fit(s.track.artist, tv.tSmall, textW), RIGHT_X + 84, y + 66, tv.tSmall);
        }
        tv.tLabel.setColor(Color.WHITE);
        tv.tSmall.setColor(Theme.TEXT_DIM);

        float ay = 720;
        heading(c, "Top artists", RIGHT_X, ay);
        if (listen.artists.isEmpty()) {
            empty(c, "Nothing played yet", RIGHT_X, ay + 60);
        } else {
            int n = Math.min(5, listen.artists.size());
            float rh = 52, gy = ay + 24;
            r.set(RIGHT_X - 14, gy, TvView.W - TvView.MARGIN + 14, gy + n * rh);
            tv.fill.setColor(PhoneKit.GROUPED);
            c.drawRoundRect(r, 14, 14, tv.fill);
            for (int i = 0; i < n; i++) {
                Stats.Artist a = listen.artists.get(i);
                float y = gy + i * rh;
                if (i > 0) {
                    tv.fill.setColor(PhoneKit.SEPARATOR);
                    c.drawRect(RIGHT_X + 40, y, TvView.W - TvView.MARGIN + 14, y + 1.5f, tv.fill);
                }
                c.drawText(String.valueOf(i + 1), RIGHT_X + 6, y + 36, tv.tSmall);
                String plays = Stats.count(a.count, "play");
                tv.tSmall.setColor(Theme.RED);
                float pw = tv.tSmall.measureText(plays);
                c.drawText(plays, TvView.W - TvView.MARGIN - pw, y + 36, tv.tSmall);
                tv.tSmall.setColor(Theme.TEXT_DIM);
                tv.tBody.setColor(Color.WHITE);
                c.drawText(tv.fit(a.name, tv.tBody, TvView.W - TvView.MARGIN - pw - 30 - (RIGHT_X + 40)), RIGHT_X + 40, y + 36, tv.tBody);
                tv.tBody.setColor(Theme.TEXT_BODY);
            }
        }
    }

    private void heading(Canvas c, String s, float x, float y) {
        c.drawText(s, x, y, tv.tTitle);
    }

    private void empty(Canvas c, String what, float x, float y) {
        tv.tBody.setColor(Theme.TEXT_FAINT);
        c.drawText(what + (sel == 0 ? " this year." : "."), x, y, tv.tBody);
        tv.tBody.setColor(Theme.TEXT_BODY);
    }

    private void posters(Canvas c, float dt, String head, List<Stats.Title> list, int z, float y, int g) {
        heading(c, head, X, y);
        if (list.isEmpty()) {
            empty(c, "Nothing watched yet", X, y + 60);
            return;
        }
        float py = y + 24;
        for (int i = 0; i < Math.min(5, list.size()); i++) {
            Stats.Title t = list.get(i);
            float x = X + i * (PW + 70);
            boolean f = zone == z && at[z] == i;
            grow[g + i].to(f ? 1 : 0);
            tv.drawPoster(c, t.entry.meta, x, py, PW, PH, step(grow[g + i], dt));
            tv.tSmall.setColor(f ? Color.WHITE : Theme.TEXT_DIM);
            c.drawText(tv.fit(t.entry.meta.name, tv.tSmall, PW + 50), x, py + PH + 40, tv.tSmall);
            tv.tSmall.setColor(Theme.RED);
            c.drawText(Stats.hours(t.ms) + " h", x, py + PH + 70, tv.tSmall);
        }
        tv.tSmall.setColor(Theme.TEXT_DIM);
    }

    @Override
    boolean key(int k, KeyEvent e) {
        switch (k) {
            case TvView.UP:
            case TvView.DOWN: {
                int dir = k == TvView.UP ? -1 : 1;
                for (int z = zone + dir; z >= CHIPS && z <= SONGS; z += dir) {
                    if (size(z) > 0) {
                        zone = z;
                        at[z] = Math.min(at[z], size(z) - 1);
                        break;
                    }
                }
                return true;
            }
            case TvView.LEFT:
                if (at[zone] > 0) at[zone]--;
                return true;
            case TvView.RIGHT:
                if (at[zone] < size(zone) - 1) at[zone]++;
                return true;
            case TvView.OK:
                if (zone == CHIPS) {
                    if (sel != at[CHIPS]) {
                        sel = at[CHIPS];
                        load();
                    }
                } else if (zone == SHOWS) {
                    tv.openDetail(watch.shows.get(at[SHOWS]).entry.meta);
                } else if (zone == MOVIES) {
                    tv.openDetail(watch.movies.get(at[MOVIES]).entry.meta);
                } else {
                    List<Model.Track> one = new ArrayList<>();
                    one.add(listen.songs.get(at[SONGS]).track);
                    TvMusicKit.play(tv, one, 0, false);
                }
                return true;
            default:
                return false;
        }
    }
}
