package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.KeyEvent;

import com.codio.tv.music.Discovery;
import com.codio.tv.music.Model;
import com.codio.tv.music.MusicNet;
import com.codio.tv.music.MusicPlayer;
import com.codio.tv.music.Taste;

import java.util.ArrayList;
import java.util.List;

/**
 * A whole chart as a list (the Top 100 or a genre's top 100), opened from "See all" at the end of its row on the Music
 * page. Same layout as an album page: the name on the left, the numbered songs on the right.
 */
final class MusicChartScreen extends Screen implements TvMusicKit.Listening {
    private static final int BUTTONS = 0, LIST = 1;
    private static final float LX = 640, ROW_H = 78, LIST_Y = 190;

    private final String id;
    private List<Model.Track> tracks = new ArrayList<Model.Track>();
    private boolean loading = true;
    private int zone = BUTTONS, button, focus;
    private final Smooth scroll = new Smooth(0.1f, 0.4f);
    private final RectF r = new RectF();

    MusicChartScreen(TvView tv, String chartId) {
        super(tv);
        TvMusicKit.start(tv);
        id = chartId;
        MusicNet.async(new MusicNet.Job<List<Model.Track>>() {
            @Override
            public List<Model.Track> run() {
                return Discovery.chartSongs(id);
            }
        }, new MusicNet.Done<List<Model.Track>>() {
            @Override
            public void done(List<Model.Track> v, Exception e) {
                loading = false;
                tracks = Taste.visibleTracks(Model.list(v));
                if (!tracks.isEmpty()) zone = LIST;
                MusicChartScreen.this.tv.kick();
            }
        });
    }

    @Override
    public void musicChanged() {}

    private List<String> buttons() {
        List<String> b = new ArrayList<String>();
        if (!tracks.isEmpty()) {
            b.add("Play");
            b.add("Shuffle");
        }
        return b;
    }

    private String credit() {
        if (!tracks.isEmpty() && Discovery.appleMusicUrl(tracks.get(0)) != null) return "Chart from Apple Music. Hold OK on a song to open it there.";
        if ("top".equals(id) && "lastfm".equals(Discovery.chartSource())) return "Chart data from Last.fm";
        return null;
    }

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(Theme.BG);
        float x = TvView.MARGIN, cw = 440;
        String art = tracks.isEmpty() ? null : tracks.get(0).artworkURL;
        TvMusicKit.cover(tv, c, art, Discovery.chartTitle(id), x, 100, cw, false, Math.round(cw * tv.scale()));
        float h = tv.drawWrapped(c, Discovery.chartTitle(id), tv.tTitle, x, 100 + cw + 30, cw + 40, 2);
        tv.tSmall.setColor(Theme.TEXT_DIM);
        if (!tracks.isEmpty()) c.drawText(tracks.size() + " songs", x, 100 + cw + 30 + h + 40, tv.tSmall);
        String credit = credit();
        if (credit != null) {
            tv.tSmall.setColor(Theme.TEXT_FAINT);
            tv.drawWrapped(c, credit, tv.tSmall, x, 100 + cw + 30 + h + 60, cw + 40, 2);
            tv.tSmall.setColor(Theme.TEXT_DIM);
        }

        List<String> b = buttons();
        button = Math.max(0, Math.min(button, b.size() - 1));
        float bx = LX;
        for (int i = 0; i < b.size(); i++) {
            tv.drawChip(c, bx, 100, b.get(i), zone == BUTTONS && i == button, false);
            bx += tv.chipWidth(b.get(i)) + 14;
        }
        float bottom = TvView.H - (MusicPlayer.hasQueue() ? TvMusicKit.BAR_H : 0) - 20, w = TvView.W - TvView.MARGIN - LX;
        if (loading) tv.spinner(c, LX + 30, LIST_Y + 60, 24, dt);
        if (!loading && tracks.isEmpty()) c.drawText("Couldn't load this chart right now (no connection?).", LX, LIST_Y + 50, tv.tBody);
        float visible = bottom - LIST_Y;
        scroll.to(Math.max(0, Math.min(tracks.size() * ROW_H - visible, (focus + 1) * ROW_H - visible / 2)));
        float sy = step(scroll, dt);
        c.save();
        c.clipRect(LX - 10, LIST_Y, TvView.W, bottom);
        Model.Track now = MusicPlayer.current();
        for (int i = 0; i < tracks.size(); i++) {
            float y = LIST_Y + i * ROW_H - sy;
            if (y + ROW_H < LIST_Y || y > bottom) continue;
            Model.Track t = tracks.get(i);
            boolean f = zone == LIST && i == focus, playing = now != null && now.same(t);
            r.set(LX, y, LX + w, y + ROW_H - 8);
            if (f) {
                tv.fill.setColor(Theme.FOCUS);
                c.drawRoundRect(r, 12, 12, tv.fill);
            }
            int main = f ? Theme.ON_FOCUS : playing ? Theme.RED_SOFT : Color.WHITE, dim = f ? Theme.ON_FOCUS_DIM : Theme.TEXT_FAINT;
            tv.tSmall.setColor(dim);
            String n = String.valueOf(i + 1);
            c.drawText(n, LX + 56 - tv.tSmall.measureText(n), y + 44, tv.tSmall);
            TvMusicKit.cover(tv, c, t.artworkURL, t.title, LX + 76, y + 6, ROW_H - 20, false, Math.round((ROW_H - 20) * tv.scale()));
            tv.tLabel.setColor(main);
            c.drawText(tv.fit(t.title, tv.tLabel, w - 700), LX + 76 + ROW_H, y + 46, tv.tLabel);
            tv.tSmall.setColor(dim);
            c.drawText(tv.fit(t.artist, tv.tSmall, 360), LX + w - 420, y + 44, tv.tSmall);
            if (Taste.isLikedTrack(t)) TvMusicKit.heart(tv, c, LX + w - 30, y + 36, 22, f ? Theme.RED : Theme.RED_SOFT);
        }
        tv.tLabel.setColor(Color.WHITE);
        tv.tSmall.setColor(Theme.TEXT_DIM);
        c.restore();
        TvMusicKit.nowBar(tv, c, dt);
    }

    @Override
    boolean holdsOk() {
        return zone == LIST;
    }

    @Override
    void longOk() {
        if (zone == LIST && focus < tracks.size()) TvMusicKit.options(tv, tracks.get(focus), tracks);
    }

    @Override
    boolean key(int k, KeyEvent e) {
        if (TvMusicKit.mediaKey(k)) return true;
        if (zone == BUTTONS) {
            List<String> b = buttons();
            if (k == TvView.LEFT && button > 0) button--;
            else if (k == TvView.RIGHT && button < b.size() - 1) button++;
            else if (k == TvView.DOWN && !tracks.isEmpty()) zone = LIST;
            else if (k == TvView.OK && !b.isEmpty()) {
                if ("Play".equals(b.get(button))) TvMusicKit.play(tv, tracks, 0, false);
                else TvMusicKit.play(tv, tracks, (int) (Math.random() * tracks.size()), true);
            }
            return true;
        }
        if (k == TvView.UP) {
            if (focus > 0) focus--;
            else zone = BUTTONS;
        } else if (k == TvView.DOWN && focus < tracks.size() - 1) focus++;
        else if (k == TvView.OK && focus < tracks.size()) TvMusicKit.play(tv, tracks, focus, false);
        return true;
    }
}
