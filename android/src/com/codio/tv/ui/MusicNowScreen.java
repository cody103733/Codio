package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.KeyEvent;

import com.codio.tv.music.Model;
import com.codio.tv.music.MusicPlayer;
import com.codio.tv.music.Taste;

import java.util.List;

/**
 * What is playing: the cover, how far along the song is, the buttons, and the queue on the right. Left and Right on the
 * line of time jump ten seconds; Fast forward and Rewind are the next and the previous song.
 */
final class MusicNowScreen extends Screen implements TvMusicKit.Listening {
    private static final int BAR = 0, CONTROLS = 1, QUEUE = 2;
    private static final int SHUFFLE = 0, PREVIOUS = 1, PLAY = 2, NEXT = 3, REPEAT = 4, HEART = 5;
    private static final float QX = 1000, ROW_H = 84, LIST_Y = 180;

    private int zone = CONTROLS, control = PLAY, focus;
    private final Smooth scroll = new Smooth(0.1f, 0.4f);
    private final RectF r = new RectF();

    MusicNowScreen(TvView tv) {
        super(tv);
        TvMusicKit.start(tv);
        focus = Math.max(0, MusicPlayer.index());
    }

    @Override
    public void musicChanged() {}

    private String label(int i) {
        switch (i) {
            case SHUFFLE:
                return MusicPlayer.shuffle() ? "Shuffle: On" : "Shuffle: Off";
            case PREVIOUS:
                return "Previous";
            case PLAY:
                return MusicPlayer.playing() ? "Pause" : "Play";
            case NEXT:
                return "Next";
            case REPEAT:
                return MusicPlayer.repeat() == MusicPlayer.REPEAT_ALL ? "Repeat: All" : MusicPlayer.repeat() == MusicPlayer.REPEAT_ONE ? "Repeat: One" : "Repeat: Off";
            default: {
                Model.Track t = MusicPlayer.current();
                return t != null && Taste.isLikedTrack(t) ? "Hearted" : "Heart";
            }
        }
    }

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(Theme.BG);
        Model.Track t = MusicPlayer.current();
        List<Model.Track> queue = MusicPlayer.queue();
        float x = TvView.MARGIN;
        if (t == null) {
            c.drawText("Nothing is playing", x, 200, tv.tTitle);
            c.drawText("Pick a song in Music to start.", x, 250, tv.tBody);
            return;
        }
        float cw = 430;
        String big = t.artworkURL != null && t.artworkURL.endsWith("/front-250") ? t.artworkURL.replace("/front-250", "/front-500") : t.artworkURL;
        TvMusicKit.cover(tv, c, big, t.title, x, 90, cw, false, Math.round(cw * tv.scale()));
        float ty = 90 + cw + 40;
        float h = tv.drawWrapped(c, t.title, tv.tTitle, x, ty, 820, 2);
        String line = t.artist + (t.album != null ? "  ·  " + t.album : "");
        c.drawText(tv.fit(line, tv.tBody, 820), x, ty + h + 38, tv.tBody);
        String state = MusicPlayer.loading() ? "Finding the song…" : null;
        String from = MusicPlayer.source() != null ? "From " + MusicPlayer.source() + (MusicPlayer.quality() != null ? "  ·  " + MusicPlayer.quality() : "") : null;
        String info = state != null ? state : from;
        if (info != null) {
            tv.tSmall.setColor(Theme.TEXT_FAINT);
            c.drawText(tv.fit(info, tv.tSmall, 820), x, ty + h + 76, tv.tSmall);
            tv.tSmall.setColor(Theme.TEXT_DIM);
        }
        if (MusicPlayer.loading()) tv.moving();

        // The line of time.
        float by = 800, bw = 820;
        double d = MusicPlayer.duration(), p = MusicPlayer.position();
        float frac = d > 0 ? (float) Math.max(0, Math.min(1, p / d)) : 0;
        boolean bf = zone == BAR;
        tv.fill.setColor(Theme.LINE);
        c.drawRoundRect(x, by, x + bw, by + (bf ? 12 : 8), 6, 6, tv.fill);
        tv.fill.setColor(Theme.RED);
        c.drawRoundRect(x, by, x + bw * frac, by + (bf ? 12 : 8), 6, 6, tv.fill);
        if (bf) {
            tv.fill.setColor(Color.WHITE);
            c.drawCircle(x + bw * frac, by + 6, 16, tv.fill);
        }
        c.drawText(Model.formatTime(p), x, by + 50, tv.tSmall);
        String total = Model.formatTime(d);
        c.drawText(total, x + bw - tv.tSmall.measureText(total), by + 50, tv.tSmall);

        float bx = x;
        for (int i = 0; i <= HEART; i++) {
            String l = label(i);
            tv.drawChip(c, bx, 890, l, zone == CONTROLS && i == control, false);
            bx += tv.chipWidth(l) + 10;
        }

        // The queue.
        float w = TvView.W - TvView.MARGIN - QX, bottom = TvView.H - 40;
        c.drawText("Queue", QX, 130, tv.tTitle);
        String hint = queue.size() + (queue.size() == 1 ? " song" : " songs") + "  ·  Hold OK for options";
        tv.tSmall.setColor(Theme.TEXT_FAINT);
        c.drawText(hint, QX + w - tv.tSmall.measureText(hint), 128, tv.tSmall);
        tv.tSmall.setColor(Theme.TEXT_DIM);
        focus = Math.max(0, Math.min(focus, queue.size() - 1));
        int centre = zone == QUEUE ? focus : Math.max(0, MusicPlayer.index());
        float visible = bottom - LIST_Y;
        scroll.to(Math.max(0, Math.min(queue.size() * ROW_H - visible, (centre + 1) * ROW_H - visible / 2)));
        float sy = step(scroll, dt);
        c.save();
        c.clipRect(QX - 10, LIST_Y, TvView.W, bottom);
        for (int i = 0; i < queue.size(); i++) {
            float y = LIST_Y + i * ROW_H - sy;
            if (y + ROW_H < LIST_Y || y > bottom) continue;
            Model.Track q = queue.get(i);
            boolean f = zone == QUEUE && i == focus, playing = i == MusicPlayer.index();
            r.set(QX, y, QX + w, y + ROW_H - 8);
            if (f) {
                tv.fill.setColor(Theme.FOCUS);
                c.drawRoundRect(r, 12, 12, tv.fill);
            }
            TvMusicKit.cover(tv, c, q.artworkURL, q.title, QX + 10, y + 8, 60, false, Math.round(60 * tv.scale()));
            tv.tLabel.setColor(f ? Theme.ON_FOCUS : playing ? Theme.RED_SOFT : Color.WHITE);
            c.drawText(tv.fit(q.title, tv.tLabel, w - 150), QX + 90, y + 36, tv.tLabel);
            tv.tSmall.setColor(f ? Theme.ON_FOCUS_DIM : Theme.TEXT_FAINT);
            c.drawText(tv.fit(q.artist, tv.tSmall, w - 150), QX + 90, y + 66, tv.tSmall);
            if (Taste.isLikedTrack(q)) TvMusicKit.heart(tv, c, QX + w - 36, y + 38, 22, f ? Theme.RED : Theme.RED_SOFT);
        }
        tv.tLabel.setColor(Color.WHITE);
        tv.tSmall.setColor(Theme.TEXT_DIM);
        c.restore();
    }

    @Override
    boolean holdsOk() {
        return zone == QUEUE;
    }

    @Override
    void longOk() {
        final List<Model.Track> queue = MusicPlayer.queue();
        if (zone != QUEUE || focus >= queue.size()) return;
        final int at = focus;
        final Model.Track t = queue.get(at);
        MusicMenu menu = new MusicMenu(tv, t.title, t.artist);
        menu.add("Play", new Runnable() {
            @Override
            public void run() {
                MusicPlayer.playIndex(at);
            }
        });
        if (at != MusicPlayer.index()) menu.add("Remove from queue", new Runnable() {
            @Override
            public void run() {
                MusicPlayer.removeFromQueue(at);
            }
        });
        menu.add(Taste.isLikedTrack(t) ? "Remove heart" : "Heart", new Runnable() {
            @Override
            public void run() {
                Taste.toggleLikeTrack(t);
            }
        });
        if (t.artistMbid != null) menu.add("Go to artist", new Runnable() {
            @Override
            public void run() {
                tv.push(new MusicArtistScreen(tv, t.artistMbid, t.artist, null));
            }
        });
        menu.add("Not interested", new Runnable() {
            @Override
            public void run() {
                Taste.hideTrack(t);
                if (at != MusicPlayer.index()) MusicPlayer.removeFromQueue(at);
                else MusicPlayer.next();
            }
        });
        tv.push(menu);
    }

    @Override
    boolean key(int k, KeyEvent e) {
        if (TvMusicKit.mediaKey(k)) return true;
        int n = MusicPlayer.queue().size();
        if (n == 0) return k != TvView.OTHER;
        switch (zone) {
            case BAR:
                if (k == TvView.LEFT) MusicPlayer.seek(Math.max(0, MusicPlayer.position() - 10));
                else if (k == TvView.RIGHT) MusicPlayer.seek(MusicPlayer.position() + 10);
                else if (k == TvView.DOWN) zone = CONTROLS;
                else if (k == TvView.OK) MusicPlayer.toggle();
                return true;
            case CONTROLS:
                if (k == TvView.LEFT && control > 0) control--;
                else if (k == TvView.RIGHT) {
                    if (control < HEART) control++;
                    else zone = QUEUE;
                } else if (k == TvView.UP) zone = BAR;
                else if (k == TvView.OK) press();
                return true;
            default:
                if (k == TvView.UP && focus > 0) focus--;
                else if (k == TvView.DOWN && focus < n - 1) focus++;
                else if (k == TvView.LEFT) zone = CONTROLS;
                else if (k == TvView.OK) MusicPlayer.playIndex(focus);
                return true;
        }
    }

    private void press() {
        switch (control) {
            case SHUFFLE:
                MusicPlayer.toggleShuffle();
                break;
            case PREVIOUS:
                MusicPlayer.previous();
                break;
            case PLAY:
                MusicPlayer.toggle();
                break;
            case NEXT:
                MusicPlayer.next();
                break;
            case REPEAT:
                MusicPlayer.cycleRepeat();
                break;
            default: {
                Model.Track t = MusicPlayer.current();
                if (t != null) Taste.toggleLikeTrack(t);
                break;
            }
        }
    }
}
