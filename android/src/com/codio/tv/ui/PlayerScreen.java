package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Codio's own player controls over the video: seek bar, play/pause, audio and subtitle menus, next episode. */
final class PlayerScreen extends Screen {
    private static final int BAR = 0, BUTTONS = 1;
    private static final int B_PLAY = 0, B_AUDIO = 1, B_SUBS = 2, B_CAST = 3, B_NEXT = 4;
    private static final long HIDE_AFTER = 5000, SEEK_COMMIT = 850;
    private static final float BAR_X = TvView.MARGIN, BAR_Y = 900, BAR_W = TvView.W - TvView.MARGIN * 2 - 190;

    final Store.Meta meta;
    String videoId;
    private Store.Video next;
    private SkipTimes.Times skip;
    private String skipFor;
    private boolean skipInZone = false;
    private final PlayerState st;

    private final Smooth shown = new Smooth(0.12f, 0.4f);
    private boolean controls = true;
    private long lastKey = SystemClock.uptimeMillis();
    private int zone = BAR, button = B_PLAY;

    // Scrubbing: a target position that's committed after a short pause in key presses.
    private double seekTarget = -1;
    private long seekAt = 0;

    // Track menu (audio or subtitles).
    private int menu = -1, menuFocus = 0;
    private final Smooth menuScroll = new Smooth(0.09f, 0.4f);

    // Actors panel: null until it has loaded.
    private List<CastInfo.Actor> cast;
    private static final float CAST_ROW = 132, CAST_TOP = 170, CAST_BOTTOM = TvView.H - 120;

    private final RectF r = new RectF();
    private final Paint bottomShade = new Paint(), topShadeP = new Paint();

    PlayerScreen(TvView tv, Store.Meta meta, String videoId) {
        super(tv);
        this.meta = meta;
        this.st = tv.playerState;
        this.videoId = videoId;
        bottomShade.setShader(new LinearGradient(0, 640, 0, TvView.H, new int[]{0x00000000, 0xE6000000}, null, Shader.TileMode.CLAMP));
        topShadeP.setShader(new LinearGradient(0, 0, 0, 260, new int[]{0xB3000000, 0x00000000}, null, Shader.TileMode.CLAMP));
        shown.snap(1);
        findNext();
        if (!meta.full || meta.videos.isEmpty()) {
            tv.store.loadMeta(meta, new Store.Callback<Store.Meta>() {
                @Override
                public void done(Store.Meta m, Exception error) {
                    findNext();
                    PlayerScreen.this.tv.kick();
                }
            });
        }
    }

    /** The player moved on to another episode (binge watching). */
    void setVideo(String vid) {
        if (vid == null || vid.equals(videoId)) return;
        videoId = vid;
        findNext();
        showControls();
    }

    private boolean skipEnabled() {
        return !"off".equals(tv.host.prefs().getString("skip_buttons", "on"));
    }

    private void loadSkip() {
        if (videoId == null || videoId.equals(skipFor) || !skipEnabled()) return;
        int[] se = SkipTimes.episodeOf(meta, videoId);
        String imdb = SkipTimes.imdbOf(meta, videoId);
        if (se == null || imdb == null) {
            android.util.Log.i(TvView.TAG, "skip times: no IMDb id or episode for " + meta.id + " / " + videoId);
            return;
        }
        skipFor = videoId;
        skip = null;
        autoSkipped = 0;
        upNextDismissed = false;
        upNextFired = false;
        final String forVideo = videoId;
        SkipTimes.load(imdb, se[0], se[1], new SkipTimes.Done() {
            @Override
            public void done(SkipTimes.Times t) {
                if (!forVideo.equals(videoId)) return;
                skip = t;
                tv.kick();
            }
        });
    }

    private static final int SKIP_NONE = 0, SKIP_INTRO = 1, SKIP_RECAP = 2, SKIP_CREDITS = 3, SKIP_NEXT = 4;

    /** Which skip button shows right now (Netflix-style, bottom right). */
    private int skipKind() {
        if (!st.started || st.error != null || menu >= 0 || seekTarget >= 0) return SKIP_NONE;
        double t = st.position();
        if (skip != null && skipEnabled()) {
            if (skip.intro != null && skip.intro.contains(t)) return SKIP_INTRO;
            if (skip.recap != null && skip.recap.contains(t)) return SKIP_RECAP;
            if (skip.outro != null && t >= skip.outro.start && (st.duration <= 0 || t < st.duration - 1)) {
                if (next != null) return upNext() != null ? SKIP_NEXT : SKIP_NONE;
                if (skip.outro.contains(t) && (st.duration <= 0 || skip.outro.end < st.duration - 5)) return SKIP_CREDITS;
                return SKIP_NONE;
            }
        }
        return upNext() != null ? SKIP_NEXT : SKIP_NONE;
    }

    /** The Up Next card was sent away with Back (for this episode), or its countdown already ran out. */
    private boolean upNextDismissed, upNextFired;

    /** The Up Next card for right now, or null. */
    private UpNext.Card upNext() {
        if (next == null || upNextDismissed || !st.started || st.error != null) return null;
        return UpNext.at(skipEnabled() ? skip : null, st.position(), st.duration, UpNext.beforeEndMs(tv.store), UpNext.playsByItself(tv.store));
    }

    private int autoSkipped;

    private void autoSkip() {
        if (!st.started || st.error != null || st.paused || menu >= 0 || seekTarget >= 0 || !skipEnabled()) return;
        int kind = SkipTimes.auto(skip, tv.host.prefs().getInt("auto_skip", 0), st.position(), next != null, autoSkipped);
        if (kind == 0) return;
        autoSkipped |= kind;
        tv.toast(SkipTimes.autoToast(kind));
        doSkip(kind == SkipTimes.INTRO ? SKIP_INTRO : kind == SkipTimes.RECAP ? SKIP_RECAP : SKIP_NEXT);
    }

    private String skipLabel(int kind) {
        switch (kind) {
            case SKIP_INTRO: return "Skip Intro";
            case SKIP_RECAP: return "Skip Recap";
            case SKIP_CREDITS: return "Skip Credits";
            case SKIP_NEXT: return "Next Episode";
            default: return null;
        }
    }

    private void doSkip(int kind) {
        SkipTimes.Segment seg = kind == SKIP_INTRO ? skip.intro : kind == SKIP_RECAP ? skip.recap : kind == SKIP_CREDITS ? skip.outro : null;
        if (kind == SKIP_NEXT) {
            playNext();
            return;
        }
        if (seg == null) return;
        seekTarget = seg.end;
        commitSeek();
        skipInZone = false;
        tv.kick();
    }

    private void findNext() {
        loadSkip();
        next = null;
        if (videoId == null || meta.videos.isEmpty()) return;
        List<Store.Video> v = meta.videos;
        for (int i = 0; i < v.size() - 1; i++) {
            if (videoId.equals(v.get(i).id)) {
                for (int j = i + 1; j < v.size(); j++) {
                    if (v.get(j).season > 0 || v.get(i).season == 0) {
                        next = v.get(j);
                        return;
                    }
                }
            }
        }
    }

    private Store.Video current() {
        if (videoId == null) return null;
        for (Store.Video v : meta.videos) if (videoId.equals(v.id)) return v;
        return null;
    }

    private String subtitle() {
        Store.Video v = current();
        if (v == null || v.season == 0 && v.episode == 0) return null;
        return "S" + v.season + " E" + v.episode + (v.title == null || v.title.isEmpty() ? "" : "  ·  " + v.title);
    }

    private void showControls() {
        controls = true;
        lastKey = SystemClock.uptimeMillis();
        shown.to(1);
        tv.kick();
    }

    private void hideControls() {
        controls = false;
        menu = -1;
        shown.to(0);
        tv.kick();
    }

    void onPlayerChanged() {
        tv.kick();
    }

    static String time(double s) {
        if (s < 0 || Double.isNaN(s)) s = 0;
        long t = (long) s;
        long h = t / 3600, m = (t / 60) % 60, sec = t % 60;
        return h > 0 ? h + ":" + (m < 10 ? "0" : "") + m + ":" + (sec < 10 ? "0" : "") + sec : m + ":" + (sec < 10 ? "0" : "") + sec;
    }

    private int seekStep() {
        JSONObject s = tv.store.profileJson.optJSONObject("settings");
        int ms = s != null ? s.optInt("seekTimeDuration", 10000) : 10000;
        return Math.max(3, ms / 1000);
    }

    private void scrub(int dir, int repeat) {
        double base = seekTarget >= 0 ? seekTarget : st.position();
        double step = seekStep() * Math.min(6, 1 + repeat / 6);
        double max = st.duration > 0 ? st.duration - 2 : base + step;
        seekTarget = Math.max(0, Math.min(max, base + dir * step));
        seekAt = SystemClock.uptimeMillis();
        showControls();
    }

    private void commitSeek() {
        if (seekTarget < 0) return;
        double t = seekTarget;
        seekTarget = -1;
        st.time = t;
        st.timeAt = SystemClock.uptimeMillis();
        try {
            tv.host.player("mpv-set-prop", new JSONArray().put("time-pos").put(t));
        } catch (Exception ignored) {
        }
    }

    private void togglePause() {
        tv.host.player("mpv-set-prop", new JSONArray().put("pause").put(!st.paused));
        st.paused = !st.paused;
        st.timeAt = SystemClock.uptimeMillis();
        showControls();
    }

    private void playNext() {
        if (next == null) return;
        tv.host.play(meta, next.id);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    void draw(Canvas c, float dt) {
        long now = SystemClock.uptimeMillis();
        if (seekTarget >= 0 && now - seekAt >= SEEK_COMMIT) commitSeek();
        if (controls && menu < 0 && seekTarget < 0 && !st.paused && st.started && st.error == null && now - lastKey > HIDE_AFTER) hideControls();
        float a = step(shown, dt);

        if (st.error != null) {
            c.drawColor(Theme.SCRIM_STRONG);
            c.drawText(st.error, (TvView.W - tv.tTitle.measureText(st.error)) / 2, 520, tv.tTitle);
            String hint = "Press Back to return and choose another source";
            c.drawText(hint, (TvView.W - tv.tBody.measureText(hint)) / 2, 580, tv.tBody);
            return;
        }
        if (st.buffering || !st.started) tv.spinner(c, TvView.W / 2, TvView.H / 2, 40, dt);

        if (a > 0.01f) {
            int alpha = (int) (255 * a);
            bottomShade.setAlpha(alpha);
            topShadeP.setAlpha(alpha);
            c.drawRect(0, 640, TvView.W, TvView.H, bottomShade);
            c.drawRect(0, 0, TvView.W, 260, topShadeP);
            c.saveLayerAlpha(0, 0, TvView.W, TvView.H, alpha);
            drawControls(c, now);
            c.restore();
        }
        autoSkip();
        int sk = skipKind();
        upNextCard = false;
        if (sk == SKIP_NONE) skipInZone = false;
        if (sk == SKIP_NEXT && menu < 0) {
            UpNext.Card card = upNext();
            upNextCard = card != null;
            if (card != null) {
                drawUpNext(c, card, a);
                if (card.fire && !upNextFired && !st.paused && seekTarget < 0) {
                    upNextFired = true;
                    playNext();
                }
            }
        } else if (sk != SKIP_NONE && menu < 0) drawSkip(c, sk, a);
        if (menu >= 0) drawMenu(c, dt);
        // Full frame rate only while something moves (seeking, the Up Next countdown, a menu). The clock, the seek
        // bar and the skip/Up Next timing only need a few frames a second: drawing the whole screen 60 times a
        // second over a playing 4K video costs low-memory TVs (Fire TV) smoothness.
        if (seekTarget >= 0 || upNextCard || (menu >= 0 && !st.paused)) tv.moving();
        else if (!st.paused && (a > 0.01f || sk != SKIP_NONE || next != null)) tv.soon(250);
    }

    /** The Up Next card was drawn this frame (its countdown moves). */
    private boolean upNextCard;

    private String tagFor;
    private String tag;
    private String baseTag;

    /** "Dolby Vision" / "HDR10" ... for the source that's playing, from its name in the sources list. */
    private String videoTag() {
        String url = PlayerState.playingUrl;
        if (url == null) return null;
        if (url.equals(tagFor)) return tag;
        Store.Sources s = tv.store.sourcesFor(meta.type, meta.id, videoId);
        if (s == null) return null; // not loaded yet: look again next frame
        tagFor = url;
        tag = null;
        baseTag = null;
        for (Store.Source src : s.list) {
            if (url.equals(src.url)) {
                tag = src.videoTag();
                baseTag = src.baseVideoTag();
                break;
            }
        }
        return tag;
    }

    private void drawControls(Canvas c, long now) {
        c.drawText(tv.fit(meta.name != null ? meta.name : "", tv.tTitle, 1500), TvView.MARGIN, 110, tv.tTitle);
        String vt = videoTag();
        // Say what the TV really gets: VLC drops the Dolby Vision layer and sends what's underneath (HDR10 or HDR10+).
        if ("Dolby Vision".equals(vt) && !PlayerState.playingDv) vt = baseTag != null ? baseTag : "HDR10";
        if (vt != null) {
            float w = tv.tSmall.measureText(vt) + 28;
            tv.videoTag(c, vt, TvView.W - TvView.MARGIN - w, 100, false);
        }
        String sub = subtitle();
        if (sub != null) c.drawText(tv.fit(sub, tv.tMeta, 1500), TvView.MARGIN, 156, tv.tMeta);

        double pos = seekTarget >= 0 ? seekTarget : st.position();
        float frac = st.duration > 0 ? (float) Math.max(0, Math.min(1, pos / st.duration)) : 0;
        boolean barFocus = zone == BAR && menu < 0;
        float h = barFocus ? 10 : 6;
        r.set(BAR_X, BAR_Y - h / 2, BAR_X + BAR_W, BAR_Y + h / 2);
        tv.fill.setColor(0x4DFFFFFF);
        c.drawRoundRect(r, h / 2, h / 2, tv.fill);
        if (seekTarget >= 0) {
            // Where it's playing now, under the target.
            float cur = st.duration > 0 ? (float) Math.max(0, Math.min(1, st.position() / st.duration)) : 0;
            r.set(BAR_X, BAR_Y - h / 2, BAR_X + BAR_W * cur, BAR_Y + h / 2);
            tv.fill.setColor(0x80FFFFFF);
            c.drawRoundRect(r, h / 2, h / 2, tv.fill);
        }
        r.set(BAR_X, BAR_Y - h / 2, BAR_X + BAR_W * frac, BAR_Y + h / 2);
        tv.fill.setColor(Theme.RED);
        c.drawRoundRect(r, h / 2, h / 2, tv.fill);
        float kx = BAR_X + BAR_W * frac;
        if (barFocus) {
            tv.fill.setColor(Theme.RED);
            c.drawCircle(kx, BAR_Y, 16, tv.fill);
            tv.fill.setColor(Color.WHITE);
            c.drawCircle(kx, BAR_Y, 7, tv.fill);
        }
        String left = st.duration > 0 ? "-" + time(st.duration - pos) : time(pos);
        tv.tLabel.setColor(Color.WHITE);
        c.drawText(left, BAR_X + BAR_W + 30, BAR_Y + 10, tv.tLabel);
        if (seekTarget >= 0 || barFocus) {
            String t = time(pos);
            float tw = tv.tLabel.measureText(t);
            float tx = Math.max(BAR_X, Math.min(BAR_X + BAR_W - tw, kx - tw / 2));
            c.drawText(t, tx, BAR_Y - 34, tv.tLabel);
        }

        float x = TvView.MARGIN, y = 950;
        boolean bf = zone == BUTTONS && menu < 0;
        x += 20 + tv.drawButton(c, x, y, st.paused ? "Play" : "Pause", st.paused, bf && button == B_PLAY, true);
        x += 20 + tv.drawButton(c, x, y, "Audio", false, bf && button == B_AUDIO, false);
        x += 20 + tv.drawButton(c, x, y, st.sid >= 0 ? "Subtitles: on" : "Subtitles", false, bf && button == B_SUBS, false);
        x += 20 + tv.drawButton(c, x, y, "Cast", false, bf && button == B_CAST, false);
        if (next != null) tv.drawButton(c, x, y, "Next episode", true, bf && button == B_NEXT, false);
    }

    private final RectF sr = new RectF();

    /** White-outlined button, bottom right; filled white when it has focus. */
    private void drawSkip(Canvas c, int kind, float controlsShown) {
        String label = skipLabel(kind);
        boolean focused = !controls || skipInZone;
        float h = 76, pad = 34;
        float w = pad * 2 + tv.tLabel.measureText(label);
        float x = TvView.W - TvView.MARGIN - w;
        float y = controlsShown > 0.01f ? BAR_Y - 150 : TvView.H - 190;
        sr.set(x, y, x + w, y + h);
        tv.fill.setColor(focused ? Color.WHITE : Theme.SCRIM);
        c.drawRoundRect(sr, 8, 8, tv.fill);
        if (!focused) {
            Paint p = tv.stroke;
            p.setColor(Color.WHITE);
            p.setStrokeWidth(3);
            c.drawRoundRect(sr, 8, 8, p);
        }
        int fg = focused ? Color.BLACK : Color.WHITE;
        tv.tLabel.setColor(fg);
        c.drawText(label, x + pad, y + h / 2 + 10, tv.tLabel);
        tv.tLabel.setColor(Color.WHITE);
    }

    private static final float UP_W = 800, UP_H = 184, UP_PIC_W = 270, UP_PIC_H = 152;

    /**
     * The Up Next card, bottom right: the next episode's picture, "Up Next in 8", its name, and Play now (which has the
     * focus while the controls are hidden: OK plays, Back keeps watching the credits). The red line fills as the
     * countdown runs.
     */
    private void drawUpNext(Canvas c, UpNext.Card card, float controlsShown) {
        boolean focused = !controls || skipInZone;
        float x = TvView.W - TvView.MARGIN - UP_W;
        float y = (controlsShown > 0.01f ? BAR_Y - 64 : TvView.H - 90) - UP_H;
        sr.set(x, y, x + UP_W, y + UP_H);
        tv.fill.setColor(0xF0161616);
        c.drawRoundRect(sr, 12, 12, tv.fill);
        float px = x + 16, py = y + 16;
        android.graphics.Bitmap pic = tv.images.get(UpNext.picture(meta, next), Math.round(UP_PIC_W * tv.scale()), Math.round(UP_PIC_H * tv.scale()), 8 * tv.scale());
        sr.set(px, py, px + UP_PIC_W, py + UP_PIC_H);
        if (pic != null) {
            c.drawBitmap(pic, null, sr, tv.bmp);
        } else {
            tv.fill.setColor(Theme.RAISED);
            c.drawRoundRect(sr, 8, 8, tv.fill);
        }
        float tx = px + UP_PIC_W + 28, tw = x + UP_W - 24 - tx;
        tv.tMeta.setColor(card.counting ? Color.WHITE : Theme.TEXT_DIM);
        c.drawText(card.heading(), tx, y + 46, tv.tMeta);
        tv.tMeta.setColor(Theme.TEXT_DIM);
        tv.tSmall.setColor(Theme.TEXT_BODY);
        c.drawText(tv.fit(UpNext.episodeLine(next), tv.tSmall, tw), tx, y + 84, tv.tSmall);
        tv.tSmall.setColor(Theme.TEXT_DIM);
        float bw = tv.drawButton(c, tx, y + 102, "Play now", true, focused, true);
        if (!controls) {
            String hint = card.counting ? "Back to keep watching" : "Back to hide";
            c.drawText(tv.fit(hint, tv.tSmall, tw - bw - 22), tx + bw + 22, y + 148, tv.tSmall);
        }
        if (card.counting || card.fraction >= 1) {
            // The countdown, along the bottom edge of the picture.
            tv.fill.setColor(Theme.SCRIM);
            c.drawRect(px, py + UP_PIC_H - 6, px + UP_PIC_W, py + UP_PIC_H, tv.fill);
            tv.fill.setColor(Theme.RED);
            c.drawRect(px, py + UP_PIC_H - 6, px + UP_PIC_W * card.fraction, py + UP_PIC_H, tv.fill);
        }
    }

    private List<PlayerState.Track> menuTracks() {
        return menu == B_AUDIO ? st.audio : st.subs;
    }

    private int menuRows() {
        return menuTracks().size() + (menu == B_SUBS ? 1 : 0);
    }

    private void drawMenu(Canvas c, float dt) {
        if (menu == B_CAST) {
            drawCast(c, dt);
            return;
        }
        float w = 720, x = TvView.W - w;
        tv.fill.setColor(0xF0141414);
        c.drawRect(x, 0, TvView.W, TvView.H, tv.fill);
        c.drawText(menu == B_AUDIO ? "Audio" : "Subtitles", x + 48, 120, tv.tTitle);
        List<PlayerState.Track> tracks = menuTracks();
        int rows = menuRows();
        float rowH = 84, top = 180;
        menuScroll.to(Math.max(0, (menuFocus - 8) * rowH));
        float sy = step(menuScroll, dt);
        c.save();
        c.clipRect(x, top - 10, TvView.W, TvView.H - 40);
        for (int i = 0; i < rows; i++) {
            float y = top + i * rowH - sy;
            if (y > TvView.H) break;
            String label;
            boolean selected;
            if (menu == B_SUBS && i == 0) {
                label = "Off";
                selected = st.sid < 0;
            } else {
                int idx = menu == B_SUBS ? i - 1 : i;
                PlayerState.Track t = tracks.get(idx);
                label = PlayerState.label(t, idx + 1);
                selected = menu == B_AUDIO ? t.id == st.aid : t.id == st.sid;
            }
            boolean f = i == menuFocus;
            r.set(x + 32, y, TvView.W - 32, y + rowH - 12);
            tv.fill.setColor(f ? Color.WHITE : 0x00000000);
            if (f) c.drawRoundRect(r, 10, 10, tv.fill);
            tv.tLabel.setColor(f ? Color.BLACK : Color.WHITE);
            c.drawText(selected ? "✓" : "", x + 56, y + 46, tv.tLabel);
            c.drawText(tv.fit(label, tv.tLabel, w - 170), x + 100, y + 46, tv.tLabel);
        }
        c.restore();
        tv.tLabel.setColor(Color.WHITE);
        if (rows == 0 || (menu == B_SUBS && rows == 1)) {
            tv.drawWrapped(c, menu == B_AUDIO ? "No other audio tracks in this video." : "This video has no built-in subtitles.",
                    tv.tSmall, x + 48, top + (menu == B_SUBS ? 110 : 30), w - 96, 3);
        }
    }

    private void openCast() {
        menu = B_CAST;
        menuFocus = 0;
        menuScroll.snap(0);
        if (cast != null && !cast.isEmpty()) return;
        cast = null;
        CastInfo.load(meta, videoId, new CastInfo.Done() {
            @Override
            public void done(List<CastInfo.Actor> actors) {
                cast = actors;
                tv.kick();
            }
        });
    }

    private void drawCast(Canvas c, float dt) {
        float w = 720, x = TvView.W - w;
        tv.fill.setColor(0xF0141414);
        c.drawRect(x, 0, TvView.W, TvView.H, tv.fill);
        c.drawText("Cast", x + 48, 120, tv.tTitle);
        tv.drawWrapped(c, CastInfo.CREDIT, tv.tSmall, x + 48, CAST_BOTTOM + 40, w - 96, 2);
        if (cast == null) {
            tv.spinner(c, x + w / 2, TvView.H / 2, 40, dt);
            return;
        }
        if (cast.isEmpty()) {
            tv.drawWrapped(c, "No cast information for this title.", tv.tBody, x + 48, CAST_TOP + 40, w - 96, 2);
            return;
        }
        menuScroll.to(Math.max(0, (menuFocus - 3) * CAST_ROW));
        float sy = step(menuScroll, dt);
        c.save();
        c.clipRect(x, CAST_TOP - 10, TvView.W, CAST_BOTTOM);
        float pw = 84, ph = 112;
        for (int i = 0; i < cast.size(); i++) {
            float y = CAST_TOP + i * CAST_ROW - sy;
            if (y > CAST_BOTTOM) break;
            if (y < CAST_TOP - CAST_ROW) continue;
            CastInfo.Actor a = cast.get(i);
            boolean f = i == menuFocus;
            r.set(x + 32, y, TvView.W - 32, y + CAST_ROW - 8);
            if (f) {
                tv.fill.setColor(Color.WHITE);
                c.drawRoundRect(r, 10, 10, tv.fill);
            }
            float px = x + 42, py = y + 6;
            android.graphics.Bitmap photo = tv.images.get(a.photo, (int) pw, (int) ph, 8);
            if (photo != null) {
                c.drawBitmap(photo, px, py, null);
            } else {
                r.set(px, py, px + pw, py + ph);
                tv.fill.setColor(Theme.RAISED);
                c.drawRoundRect(r, 8, 8, tv.fill);
                String initial = a.name.substring(0, 1);
                tv.tLabel.setColor(Color.WHITE);
                c.drawText(initial, px + (pw - tv.tLabel.measureText(initial)) / 2, py + ph / 2 + 10, tv.tLabel);
            }
            float tx = px + pw + 24, tw = TvView.W - 48 - tx;
            tv.tLabel.setColor(f ? Color.BLACK : Color.WHITE);
            c.drawText(tv.fit(a.name, tv.tLabel, tw), tx, y + (a.role != null ? 54 : 72), tv.tLabel);
            if (a.role != null) {
                tv.tSmall.setColor(f ? Theme.ON_FOCUS_DIM : Theme.TEXT_DIM);
                c.drawText(tv.fit(a.role, tv.tSmall, tw), tx, y + 92, tv.tSmall);
            }
        }
        c.restore();
        tv.tLabel.setColor(Color.WHITE);
        tv.tSmall.setColor(Theme.TEXT_DIM);
    }
    private void openMenu(int which) {
        menu = which;
        menuFocus = 0;
        List<PlayerState.Track> tracks = which == B_AUDIO ? st.audio : st.subs;
        for (int i = 0; i < tracks.size(); i++) {
            int id = tracks.get(i).id;
            if (which == B_AUDIO ? id == st.aid : id == st.sid) menuFocus = which == B_SUBS ? i + 1 : i;
        }
        menuScroll.snap(Math.max(0, (menuFocus - 8) * 84));
    }

    private void pickFromMenu() {
        if (menu == B_SUBS) {
            int id = menuFocus == 0 ? -1 : st.subs.get(menuFocus - 1).id;
            tv.host.userPick();
            tv.host.player("mpv-set-prop", new JSONArray().put("sid").put(id < 0 ? "no" : (Object) id));
            st.sid = id;
        } else if (menuFocus < st.audio.size()) {
            int id = st.audio.get(menuFocus).id;
            tv.host.userPick();
            tv.host.player("mpv-set-prop", new JSONArray().put("aid").put(id));
            st.aid = id;
        }
        menu = -1;
        showControls();
    }

    // ------------------------------------------------------------------ keys

    @Override
    boolean key(int k, KeyEvent e) {
        int repeat = e != null ? e.getRepeatCount() : 0;
        if (st.error != null) return true;
        if (menu >= 0) {
            lastKey = SystemClock.uptimeMillis();
            if (menu == B_CAST) {
                int actors = cast != null ? cast.size() : 0;
                if (k == TvView.UP && menuFocus > 0) menuFocus--;
                else if (k == TvView.DOWN && menuFocus < actors - 1) menuFocus++;
                else if (k == TvView.LEFT) menu = -1;
                return true;
            }
            int rows = menuRows();
            if (k == TvView.UP && menuFocus > 0) menuFocus--;
            else if (k == TvView.DOWN && menuFocus < rows - 1) menuFocus++;
            else if (k == TvView.OK && rows > 0) pickFromMenu();
            else if (k == TvView.LEFT) menu = -1;
            return true;
        }
        if (k == TvView.PLAY) {
            togglePause();
            return true;
        }
        if (k == TvView.FF || k == TvView.RW) {
            zone = BAR;
            scrub(k == TvView.FF ? 1 : -1, repeat + 6);
            return true;
        }
        if (!controls) {
            int sk = skipKind();
            if (k == TvView.OK && sk != SKIP_NONE) {
                doSkip(sk);
                return true;
            }
            if (k == TvView.LEFT || k == TvView.RIGHT) {
                zone = BAR;
                scrub(k == TvView.RIGHT ? 1 : -1, repeat);
                return true;
            }
            if (k == TvView.OK || k == TvView.UP || k == TvView.DOWN) {
                zone = k == TvView.OK ? BUTTONS : BAR;
                button = B_PLAY;
                showControls();
                return true;
            }
            return k != TvView.OTHER;
        }
        lastKey = SystemClock.uptimeMillis();
        if (skipInZone) {
            int sk = skipKind();
            if (sk == SKIP_NONE || k == TvView.DOWN) {
                skipInZone = false;
                if (sk != SKIP_NONE) return true;
            } else if (k == TvView.OK) {
                doSkip(sk);
                return true;
            } else {
                return true;
            }
        }
        if (zone == BAR && k == TvView.UP && seekTarget < 0 && skipKind() != SKIP_NONE) {
            skipInZone = true;
            return true;
        }
        if (zone == BAR) {
            switch (k) {
                case TvView.LEFT:
                case TvView.RIGHT:
                    scrub(k == TvView.RIGHT ? 1 : -1, repeat);
                    return true;
                case TvView.OK:
                    if (seekTarget >= 0) commitSeek();
                    else togglePause();
                    return true;
                case TvView.DOWN:
                    commitSeek();
                    zone = BUTTONS;
                    return true;
                case TvView.UP:
                    commitSeek();
                    hideControls();
                    return true;
                default:
                    return k != TvView.OTHER;
            }
        }
        int last = next != null ? B_NEXT : B_CAST;
        switch (k) {
            case TvView.LEFT:
                if (button > 0) button--;
                return true;
            case TvView.RIGHT:
                if (button < last) button++;
                return true;
            case TvView.UP:
                zone = BAR;
                return true;
            case TvView.DOWN:
                return true;
            case TvView.OK:
                if (button == B_PLAY) togglePause();
                else if (button == B_AUDIO || button == B_SUBS) openMenu(button);
                else if (button == B_CAST) openCast();
                else playNext();
                return true;
            default:
                return k != TvView.OTHER;
        }
    }

    @Override
    boolean back() {
        if (menu >= 0) {
            menu = -1;
            return true;
        }
        if (seekTarget >= 0) {
            seekTarget = -1;
            return true;
        }
        if (controls && st.error == null && !st.paused) {
            hideControls();
            return true;
        }
        if (st.error == null && skipKind() == SKIP_NEXT) {
            // Back on the Up Next card: keep watching (the credits), nothing starts early.
            upNextDismissed = true;
            skipInZone = false;
            tv.kick();
            return true;
        }
        tv.host.stopPlayer();
        return true;
    }
}
