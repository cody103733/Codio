package com.codio.tv.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/** A movie or series: artwork, description, Play / Choose a source, and for series the seasons and episodes. */
final class DetailScreen extends Screen {
    private static final int BUTTONS = 0, SEASONS = 1, EPISODES = 2;
    private static final float EW = 352, EH = 198, ESTEP = 376;

    private final Store.Meta meta;
    private final Backdrop backdrop;
    private int zone = BUTTONS, button = 0;
    private boolean loading = true, failed = false;
    private final List<Integer> seasons = new ArrayList<>();
    private int season = 0; // index into seasons
    private int seasonFocus = 0;
    private final List<Store.Video> episodes = new ArrayList<>();
    private int episode = 0, prevEpisode = -1;
    private final Smooth escroll = new Smooth(0.085f, 0.4f);
    private final Smooth sscroll = new Smooth(0.085f, 0.4f);
    private final Smooth grow = new Smooth(0.07f, 0.01f);
    private final Smooth lift = new Smooth(0.1f, 0.4f);

    private void toggleLibrary() {
        boolean in = tv.store.inLibrary(meta.id);
        if (in) tv.ctx("RemoveFromLibrary", meta.id);
        else tv.ctx("AddToLibrary", Store.preview(meta));
        tv.store.markInLibrary(meta, !in);
        tv.toast(in ? "Removed from your library" : "Added to your library");
    }

    DetailScreen(TvView tv, Store.Meta meta) {
        super(tv);
        this.meta = meta;
        this.backdrop = new Backdrop(tv);
        load();
    }

    String id() {
        return meta.id;
    }

    private void load() {
        loading = true;
        tv.store.loadMeta(meta, new Store.Callback<Store.Meta>() {
            @Override
            public void done(Store.Meta m, Exception error) {
                loading = false;
                failed = m == null;
                buildSeasons();
                prefetchNow();
                tv.kick();
            }
        });
    }

    private boolean isSeries() {
        return !"movie".equals(meta.type) && !meta.videos.isEmpty();
    }

    private void buildSeasons() {
        seasons.clear();
        for (Store.Video v : meta.videos) if (!seasons.contains(v.season)) seasons.add(v.season);
        // Specials (season 0) last.
        if (seasons.size() > 1 && seasons.get(0) == 0) seasons.add(seasons.remove(0));
        Store.Video resume = resumeVideo();
        season = 0;
        if (resume != null) season = Math.max(0, seasons.indexOf(resume.season));
        seasonFocus = season;
        fillEpisodes();
        if (resume != null) {
            int i = episodes.indexOf(resume);
            if (i >= 0) {
                episode = i;
                escroll.snap(episodeScroll());
            }
        }
        sscroll.snap(0);
    }

    private void fillEpisodes() {
        episodes.clear();
        if (seasons.isEmpty()) return;
        int s = seasons.get(season);
        for (Store.Video v : meta.videos) if (v.season == s) episodes.add(v);
        episode = 0;
        prevEpisode = -1;
        escroll.snap(0);
    }

    private float episodeScroll() {
        return Math.max(0, (episode - 1) * ESTEP);
    }

    /** The episode Play starts: the one being watched, or the first of the first season. */
    private Store.Video resumeVideo() {
        if (meta.videos.isEmpty()) return null;
        Store.LibraryEntry e = tv.store.libraryEntry(meta.id);
        String vid = e != null ? e.videoId : meta.lastVideoId;
        if (vid != null) {
            for (int i = 0; i < meta.videos.size(); i++) {
                Store.Video v = meta.videos.get(i);
                if (!vid.equals(v.id)) continue;
                // Finished it: go on with the next one.
                if (e != null && e.duration > 0 && e.offset / e.duration > 0.92 && i + 1 < meta.videos.size()) return meta.videos.get(i + 1);
                return v;
            }
        }
        for (Store.Video v : meta.videos) if (v.season > 0) return v;
        return meta.videos.get(0);
    }

    private String playLabel() {
        Store.LibraryEntry e = tv.store.libraryEntry(meta.id);
        if (isSeries()) {
            Store.Video v = resumeVideo();
            if (v == null) return "Play";
            boolean started = e != null && v.id.equals(e.videoId) && e.offset > 0;
            return (started ? "Resume" : "Play") + "  S" + v.season + " E" + v.episode;
        }
        if (e != null && e.offset > 60000 && e.duration > 0 && e.offset / e.duration < 0.92) return "Resume";
        return "Play";
    }

    private void play() {
        if (isSeries()) {
            Store.Video v = resumeVideo();
            if (v != null) tv.host.play(meta, v.id);
        } else if ("movie".equals(meta.type) || meta.videos.isEmpty()) {
            tv.host.play(meta, meta.id);
        }
    }

    private void chooseSource() {
        if (isSeries()) {
            Store.Video v = zone == EPISODES && !episodes.isEmpty() ? episodes.get(episode) : resumeVideo();
            if (v != null) tv.openSources(meta, v.id);
        } else {
            tv.openSources(meta, meta.id);
        }
    }

    private final RectF lr = new RectF();

    @Override
    void draw(Canvas c, float dt) {
        backdrop.show(HomeScreen.backgroundOf(meta), 0);
        backdrop.draw(c, dt, true);
        boolean series = isSeries();
        lift.to(zone == BUTTONS ? 0 : -150);
        float up = step(lift, dt);
        float x = TvView.MARGIN, y = 120 + up;

        String logoUrl = HomeScreen.logoOf(meta);
        Bitmap logo = logoUrl != null ? tv.images.logo(logoUrl, Math.round(640 * tv.scale()), Math.round(200 * tv.scale())) : null;
        if (logo != null) {
            float w = logo.getWidth() / tv.scale(), h = logo.getHeight() / tv.scale();
            lr.set(x, y + 200 - h, x + w, y + 200);
            c.drawBitmap(logo, null, lr, tv.bmp);
        } else if (logoUrl == null || tv.images.isFailed(logoUrl)) {
            tv.tHero.setTextSize(72);
            tv.drawWrapped(c, meta.name, tv.tHero, x, y + 30, 1100, 2);
            tv.tHero.setTextSize(64);
        }
        float ty = y + 262;
        String line = meta.metaLine();
        String g = meta.genreLine();
        if (!g.isEmpty()) line = line.isEmpty() ? g : line + "  ·  " + g;
        c.drawText(tv.fit(line, tv.tMeta, 1300), x, ty, tv.tMeta);
        ty += 24;
        if (zone == EPISODES && !episodes.isEmpty()) {
            Store.Video v = episodes.get(episode);
            String head = "S" + v.season + " E" + v.episode + (v.title.isEmpty() ? "" : "  ·  " + v.title);
            c.drawText(tv.fit(head, tv.tLabel, 1100), x, ty + 30, tv.tLabel);
            tv.drawWrapped(c, v.overview, tv.tBody, x, ty + 48, 920, 3);
        } else {
            float h = tv.drawWrapped(c, meta.description, tv.tBody, x, ty, 920, 4);
            if (!meta.cast.isEmpty() && zone == BUTTONS) {
                StringBuilder sb = new StringBuilder("Starring ");
                for (int i = 0; i < meta.cast.size() && i < 4; i++) sb.append(i > 0 ? ", " : "").append(meta.cast.get(i));
                c.drawText(tv.fit(sb.toString(), tv.tSmall, 920), x, ty + h + 36, tv.tSmall);
            }
        }

        float by = 650 + up;
        float w = tv.drawButton(c, x, by, playLabel(), true, zone == BUTTONS && button == 0, true);
        float w2 = tv.drawButton(c, x + w + 20, by, "Choose a source", false, zone == BUTTONS && button == 1, false);
        boolean saved = tv.store.inLibrary(meta.id);
        w2 += 20 + tv.drawButton(c, x + w + 20 + w2 + 20, by, saved ? "✓ In library" : "+ Library", false, zone == BUTTONS && button == 2, false);
        // What Play will pick, once the sources are in (they load as soon as this page opens).
        String pv = prefetchVideo();
        Store.Sources src = pv != null ? tv.store.sourcesFor(meta.type, meta.id, pv) : null;
        if (src != null) {
            Store.Source best = src.best();
            String hint = best != null ? (SourcesScreen.qualityLabel(best.quality) != null ? SourcesScreen.qualityLabel(best.quality) : "Ready")
                    + (best.cached ? "  ·  instant" : "") : src.loading ? "Finding sources…" : src.list.isEmpty() ? "No sources found" : "";
            c.drawText(hint, x + w + 20 + w2 + 34, by + 46, tv.tMeta);
        }
        if (loading && (series || !"movie".equals(meta.type))) tv.spinner(c, x + 30, by + 160, 22, dt);
        if (failed && !"movie".equals(meta.type) && meta.videos.isEmpty()) {
            c.drawText("Couldn't load the episodes.", x, by + 150, tv.tSmall);
        }

        if (series) {
            float sy = 770 + up;
            float sx = x - step(sscroll, dt);
            for (int i = 0; i < seasons.size(); i++) {
                int s = seasons.get(i);
                String label = s == 0 ? "Specials" : "Season " + s;
                float cw = tv.chipWidth(label);
                if (sx + cw > 0 && sx < TvView.W) tv.drawChip(c, sx, sy, label, zone == SEASONS && i == seasonFocus, i == season);
                if (i == seasonFocus) {
                    // Keep the focused chip on screen.
                    float right = sx + cw + step(sscroll, 0) - x;
                    if (sx < x) sscroll.to(sscroll.target - (x - sx));
                    else if (sx + cw > TvView.W - TvView.MARGIN) sscroll.to(sscroll.target + (sx + cw - (TvView.W - TvView.MARGIN)));
                }
                sx += cw + 14;
            }
            float ey = sy + 92;
            escroll.to(episodeScroll());
            float ex = step(escroll, dt);
            float gg = step(grow, dt);
            int first = Math.max(0, (int) (ex / ESTEP) - 1);
            int last = Math.min(episodes.size() - 1, (int) ((ex + TvView.W) / ESTEP) + 1);
            for (int i = first; i <= last; i++) {
                Store.Video v = episodes.get(i);
                float px = x + i * ESTEP - ex;
                float f = zone == EPISODES ? (i == episode ? gg : i == prevEpisode ? 1 - gg : 0) : 0;
                drawEpisode(c, v, px, ey, f);
            }
        }
    }

    private final RectF er = new RectF();

    private void drawEpisode(Canvas c, Store.Video v, float x, float y, float focus) {
        float grow = 1 + 0.06f * focus;
        float dw = EW * grow, dh = EH * grow;
        float dx = x - (dw - EW) / 2, dy = y - (dh - EH) / 2;
        String url = v.thumbnail != null ? v.thumbnail : HomeScreen.backgroundOf(meta);
        Bitmap b = tv.images.get(url, Math.round(EW * 1.06f * tv.scale()), Math.round(EH * 1.06f * tv.scale()), 10 * tv.scale());
        er.set(dx, dy, dx + dw, dy + dh);
        if (b != null) {
            c.drawBitmap(b, null, er, tv.bmp);
        } else {
            tv.fill.setColor(0xFF262626);
            c.drawRoundRect(er, 10, 10, tv.fill);
        }
        Store.LibraryEntry e = tv.store.libraryEntry(meta.id);
        if (e != null && v.id.equals(e.videoId) && e.duration > 0) {
            tv.fill.setColor(0xB3000000);
            c.drawRect(dx + 10, dy + dh - 16, dx + dw - 10, dy + dh - 10, tv.fill);
            tv.fill.setColor(TvView.RED);
            c.drawRect(dx + 10, dy + dh - 16, dx + 10 + (dw - 20) * (float) Math.min(1, e.offset / e.duration), dy + dh - 10, tv.fill);
        }
        if (focus > 0.01f) tv.ring(c, er, 12, focus);
        String label = v.episode + ".  " + (v.title.isEmpty() ? "Episode " + v.episode : v.title);
        c.drawText(tv.fit(label, tv.tSmall, EW), x, y + EH + 40, tv.tSmall);
    }

    @Override
    boolean key(int k, KeyEvent e) {
        boolean series = isSeries();
        if (k == TvView.PLAY) {
            if (zone == EPISODES && !episodes.isEmpty()) tv.host.play(meta, episodes.get(episode).id);
            else play();
            return true;
        }
        switch (zone) {
            case BUTTONS:
                if (k == TvView.LEFT && button > 0) button--;
                else if (k == TvView.RIGHT && button < 2) button++;
                else if (k == TvView.DOWN && series) {
                    zone = EPISODES;
                    grow.snap(0);
                    grow.to(1);
                    prevEpisode = -1;
                } else if (k == TvView.OK) {
                    if (button == 0) play();
                    else if (button == 1) chooseSource();
                    else toggleLibrary();
                }
                return true;
            case SEASONS:
                if (k == TvView.LEFT && seasonFocus > 0) seasonFocus--;
                else if (k == TvView.RIGHT && seasonFocus < seasons.size() - 1) seasonFocus++;
                else if (k == TvView.UP) zone = BUTTONS;
                else if (k == TvView.DOWN) {
                    zone = EPISODES;
                    grow.snap(0);
                    grow.to(1);
                } else if (k == TvView.OK && seasonFocus != season) {
                    season = seasonFocus;
                    fillEpisodes();
                    zone = EPISODES;
                    grow.snap(0);
                    grow.to(1);
                }
                if ((k == TvView.LEFT || k == TvView.RIGHT) && seasonFocus != season) {
                    // Moving along the seasons shows each one's episodes right away.
                    season = seasonFocus;
                    fillEpisodes();
                }
                return true;
            case EPISODES:
                if (k == TvView.LEFT && episode > 0) moveEpisode(-1);
                else if (k == TvView.RIGHT && episode < episodes.size() - 1) moveEpisode(1);
                else if (k == TvView.UP) {
                    zone = SEASONS;
                    seasonFocus = season;
                } else if (k == TvView.OK && !episodes.isEmpty()) tv.host.play(meta, episodes.get(episode).id);
                return true;
            default:
                return false;
        }
    }

    /** The movie, or the episode the sources are loading for. */
    private String prefetchVideo() {
        if ("movie".equals(meta.type) || meta.videos.isEmpty()) return meta.id;
        if (zone == EPISODES && !episodes.isEmpty()) return episodes.get(episode).id;
        Store.Video v = resumeVideo();
        return v != null ? v.id : null;
    }

    private void prefetchNow() {
        String v = prefetchVideo();
        if (v != null) tv.host.prefetch(meta, v);
    }

    private final Runnable prefetchLater = new Runnable() {
        @Override
        public void run() {
            if (tv.top() == DetailScreen.this) prefetchNow();
        }
    };

    private void moveEpisode(int d) {
        // Resting on an episode loads its sources (not every one passed on the way).
        tv.removeCallbacks(prefetchLater);
        tv.postDelayed(prefetchLater, 900);
        prevEpisode = episode;
        episode += d;
        grow.snap(0);
        grow.to(1);
    }

    @Override
    boolean back() {
        if (zone != BUTTONS) {
            zone = BUTTONS;
            return true;
        }
        return false;
    }

    @Override
    void onShow() {
        prefetchNow();
        // Back from playback: progress may have moved on.
        if (!loading && isSeries()) {
            Store.Video r = resumeVideo();
            if (r != null && zone == BUTTONS) {
                int si = seasons.indexOf(r.season);
                if (si >= 0 && si != season) {
                    season = si;
                    seasonFocus = si;
                    fillEpisodes();
                }
                int i = episodes.indexOf(r);
                if (i >= 0) episode = i;
            }
        }
    }
}
