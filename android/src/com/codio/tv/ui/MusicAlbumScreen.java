package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.KeyEvent;

import com.codio.tv.music.Addons;
import com.codio.tv.music.Model;
import com.codio.tv.music.MusicBrainz;
import com.codio.tv.music.MusicNet;
import com.codio.tv.music.MusicPlayer;
import com.codio.tv.music.Taste;

import java.util.ArrayList;
import java.util.List;

/** An album: its cover and name on the left, its songs on the right. From MusicBrainz, or from the addon that offered it. */
final class MusicAlbumScreen extends Screen implements TvMusicKit.Listening {
    private static final int BUTTONS = 0, LIST = 1;
    private static final float LX = 640, ROW_H = 78, LIST_Y = 190;

    private final Model.Album album;
    private String artistId, error;
    private List<Model.Track> tracks = new ArrayList<Model.Track>();
    private boolean loading = true;
    private int zone = BUTTONS, button, focus;
    private final Smooth scroll = new Smooth(0.1f, 0.4f);
    private final RectF r = new RectF();

    MusicAlbumScreen(TvView tv, Model.Album preview) {
        super(tv);
        TvMusicKit.start(tv);
        album = preview;
        artistId = preview.artistMbid;
        final Model.Addon addon = preview.addon == null ? null : Addons.byUrl(preview.addon);
        if (addon != null) {
            MusicNet.async(new MusicNet.Job<Addons.AlbumDetail>() {
                @Override
                public Addons.AlbumDetail run() throws Exception {
                    return Addons.albumDetail(addon, album.id);
                }
            }, new MusicNet.Done<Addons.AlbumDetail>() {
                @Override
                public void done(Addons.AlbumDetail d, Exception e) {
                    loading = false;
                    if (d == null) {
                        error = e != null ? "Couldn't load this album" : "The addon has no page for this album";
                    } else {
                        if (d.title != null && d.title.length() > 0) album.title = d.title;
                        if (d.artist != null && d.artist.length() > 0) album.artist = d.artist;
                        if (d.artworkURL != null) album.artworkURL = d.artworkURL;
                        if (d.year != null) album.year = d.year;
                        tracks = d.tracks;
                        if (!tracks.isEmpty()) zone = LIST;
                    }
                    MusicAlbumScreen.this.tv.kick();
                }
            });
            return;
        }
        MusicNet.async(new MusicNet.Job<MusicBrainz.AlbumPage>() {
            @Override
            public MusicBrainz.AlbumPage run() throws Exception {
                return MusicBrainz.getAlbum(album.id);
            }
        }, new MusicNet.Done<MusicBrainz.AlbumPage>() {
            @Override
            public void done(MusicBrainz.AlbumPage a, Exception e) {
                loading = false;
                if (a == null) {
                    error = e != null && e.getMessage() != null && e.getMessage().startsWith("No release") ? e.getMessage() : "Couldn't load this album";
                } else {
                    album.title = a.title;
                    album.artist = a.artist;
                    if (a.year != null) album.year = a.year;
                    if (album.artworkURL == null) album.artworkURL = a.artworkURL;
                    if (a.artistId != null) {
                        artistId = a.artistId;
                        if (album.artistMbid == null) album.artistMbid = a.artistId;
                    }
                    tracks = a.tracks;
                    if (!tracks.isEmpty()) zone = LIST;
                }
                MusicAlbumScreen.this.tv.kick();
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
        b.add(Taste.isLikedAlbum(album) ? "Hearted" : "Heart");
        if (artistId != null) b.add("Artist");
        b.add(Taste.isHiddenAlbum(album) ? "Show again" : "Not interested");
        return b;
    }

    private float listBottom() {
        return TvView.H - (MusicPlayer.hasQueue() ? TvMusicKit.BAR_H : 0) - 20;
    }

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(Theme.BG);
        // Left: cover, name, artist.
        float x = TvView.MARGIN, cw = 440;
        String big = album.artworkURL != null && album.artworkURL.endsWith("/front-250") ? album.artworkURL.replace("/front-250", "/front-500") : album.artworkURL;
        TvMusicKit.cover(tv, c, big, album.title, x, 100, cw, false, Math.round(cw * tv.scale()));
        tv.tSmall.setColor(Theme.TEXT_FAINT);
        c.drawText(album.kind != null ? album.kind : "Album", x, 100 + cw + 50, tv.tSmall);
        tv.tSmall.setColor(Theme.TEXT_DIM);
        float h = tv.drawWrapped(c, album.title, tv.tTitle, x, 100 + cw + 64, cw + 40, 2);
        String line = album.artist + (album.year != null ? "  ·  " + album.year : "");
        c.drawText(tv.fit(line, tv.tBody, cw + 40), x, 100 + cw + 64 + h + 40, tv.tBody);
        if (!tracks.isEmpty()) {
            long total = 0;
            for (Model.Track t : tracks) total += t.durationMs;
            String count = tracks.size() + (tracks.size() == 1 ? " song" : " songs") + (total > 0 ? "  ·  " + Math.round(total / 60000f) + " min" : "");
            c.drawText(count, x, 100 + cw + 64 + h + 80, tv.tSmall);
        }

        // Right: buttons, then the songs.
        List<String> b = buttons();
        button = Math.min(button, b.size() - 1);
        float bx = LX;
        for (int i = 0; i < b.size(); i++) {
            tv.drawChip(c, bx, 100, b.get(i), zone == BUTTONS && i == button, false);
            bx += tv.chipWidth(b.get(i)) + 14;
        }
        float bottom = listBottom(), w = TvView.W - TvView.MARGIN - LX;
        if (loading) tv.spinner(c, LX + 30, LIST_Y + 60, 24, dt);
        if (error != null) c.drawText(error, LX, LIST_Y + 50, tv.tBody);
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
            tv.tLabel.setColor(main);
            boolean other = t.artist.length() > 0 && !t.artist.equals(album.artist);
            c.drawText(tv.fit(t.title, tv.tLabel, w - (other ? 620 : 300)), LX + 80, y + 46, tv.tLabel);
            if (other) c.drawText(tv.fit(t.artist, tv.tSmall, 300), LX + w - 520, y + 44, tv.tSmall);
            if (Taste.isLikedTrack(t)) TvMusicKit.heart(tv, c, LX + w - 170, y + 36, 22, f ? Theme.RED : Theme.RED_SOFT);
            if (t.durationMs > 0) {
                String d = Model.formatTime(t.durationMs / 1000.0);
                c.drawText(d, LX + w - 30 - tv.tSmall.measureText(d), y + 44, tv.tSmall);
            }
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
            else if (k == TvView.OK) {
                String label = b.get(button);
                if ("Play".equals(label)) TvMusicKit.play(tv, tracks, 0, false);
                else if ("Shuffle".equals(label)) TvMusicKit.play(tv, tracks, (int) (Math.random() * tracks.size()), true);
                else if ("Artist".equals(label)) tv.push(new MusicArtistScreen(tv, artistId, album.artist, null));
                else if ("Show again".equals(label)) Taste.unhide("album", album.id);
                else if ("Not interested".equals(label)) Taste.hideAlbum(album);
                else Taste.toggleLikeAlbum(album);
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
