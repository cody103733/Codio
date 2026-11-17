package com.codio.tv.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.codio.tv.music.Addons;
import com.codio.tv.music.Model.Addon;
import com.codio.tv.music.Model.Album;
import com.codio.tv.music.Model.Artist;
import com.codio.tv.music.Model.Track;
import com.codio.tv.music.MusicBrainz;
import com.codio.tv.music.MusicNet;
import com.codio.tv.music.MusicPlayer;
import com.codio.tv.music.Taste;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** An album: its cover, Play and Shuffle, and the songs (from MusicBrainz, or from the addon the album came from). */
final class PhoneAlbum extends PhoneUi.Page {
    private final Album preview;
    private LinearLayout body;
    private String title, artistName, artistId, year, art;
    private List<Track> tracks = new ArrayList<>();
    private boolean failed;
    private final Runnable tasteChanged = new Runnable() {
        @Override
        public void run() {
            if (body != null) MusicKit.refreshRows(body);
            if (likeButton != null) styleToggles();
        }
    };
    private final MusicPlayer.Listener playerListener = new MusicPlayer.Listener() {
        @Override
        public void changed() {
            if (body != null) MusicKit.refreshRows(body);
        }
    };
    private TextView likeButton, hideButton;

    PhoneAlbum(PhoneUi ui, Album preview) {
        super(ui);
        this.preview = preview;
        title = preview.title;
        artistName = preview.artist;
        year = preview.year;
        art = preview.artworkURL;
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ui.largeTitle("Album", true));
        ScrollView sv = new ScrollView(ctx());
        body = new LinearLayout(ctx());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(24));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        draw();
        final Addon addon = Addons.byUrl(preview.addon);
        MusicNet.async(new MusicNet.Job<Object>() {
            @Override
            public Object run() throws Exception {
                if (addon != null) {
                    Addons.AlbumDetail d = Addons.albumDetail(addon, preview.id);
                    if (d == null) throw new Exception("The addon has no page for this album");
                    return d;
                }
                return MusicBrainz.getAlbum(preview.id);
            }
        }, new MusicNet.Done<Object>() {
            @Override
            public void done(Object v, Exception e) {
                if (body == null) return;
                if (v instanceof Addons.AlbumDetail) {
                    Addons.AlbumDetail d = (Addons.AlbumDetail) v;
                    title = d.title.length() > 0 ? d.title : title;
                    artistName = d.artist;
                    year = d.year;
                    art = d.artworkURL != null ? d.artworkURL : art;
                    tracks = d.tracks;
                } else if (v instanceof MusicBrainz.AlbumPage) {
                    MusicBrainz.AlbumPage p = (MusicBrainz.AlbumPage) v;
                    title = p.title;
                    artistName = p.artist;
                    artistId = p.artistId;
                    year = p.year;
                    art = p.artworkURL;
                    tracks = p.tracks;
                } else {
                    failed = true;
                    errorText = e != null && e.getMessage() != null ? e.getMessage() : "Couldn't load this album";
                }
                loaded = true;
                draw();
            }
        });
        return col;
    }

    private boolean loaded;
    private String errorText;

    @Override
    void onShow() {
        Taste.listeners.add(tasteChanged);
        MusicPlayer.listen(playerListener);
    }

    @Override
    void onHide() {
        Taste.listeners.remove(tasteChanged);
        MusicPlayer.unlisten(playerListener);
    }

    private Album album() {
        Album a = new Album();
        a.id = preview.id;
        a.title = title;
        a.artist = artistName;
        a.year = year;
        a.artworkURL = art;
        a.addon = preview.addon;
        a.artistMbid = artistId != null ? artistId : preview.artistMbid;
        return a;
    }

    private void styleToggles() {
        boolean liked = Taste.isLikedAlbum(album());
        likeButton.setText(liked ? "♥ Liked" : "♡ Like");
        PhoneKit.styleChip(likeButton, liked);
        boolean hidden = Taste.isHiddenAlbum(album());
        hideButton.setText(hidden ? "Hidden: undo" : "Not interested");
    }

    private void draw() {
        Context c = ctx();
        body.removeAllViews();
        LinearLayout hero = new LinearLayout(c);
        hero.setPadding(dp(16), dp(4), dp(16), dp(8));
        MusicKit.Cover cover = new MusicKit.Cover(c, 132, false, title);
        cover.set(art);
        hero.addView(cover);
        LinearLayout side = new LinearLayout(c);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(14), 0, 0, 0);
        TextView t = PhoneKit.text(c, title, 20, Color.WHITE, PhoneKit.BOLD);
        t.setMaxLines(3);
        side.addView(t);
        String meta = artistName == null ? "" : artistName;
        if (year != null) meta += (meta.length() > 0 ? " · " : "") + year;
        if (loaded && !tracks.isEmpty()) meta += (meta.length() > 0 ? " · " : "") + tracks.size() + " songs";
        TextView m = PhoneKit.text(c, meta, 14, Theme.TEXT_DIM, PhoneKit.REGULAR);
        m.setMaxLines(2);
        if (artistId != null) {
            m.setTextColor(Theme.RED_SOFT);
            m.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Artist a = new Artist();
                    a.id = artistId;
                    a.name = artistName;
                    ui.push(new PhoneArtist(ui, a));
                }
            });
        }
        side.addView(m);
        hero.addView(side, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(hero);
        if (!loaded) {
            LinearLayout box = new LinearLayout(c);
            box.setGravity(Gravity.CENTER);
            box.setPadding(0, dp(30), 0, 0);
            ProgressBar p = new ProgressBar(c);
            p.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Theme.RED));
            box.addView(p, new LinearLayout.LayoutParams(dp(40), dp(40)));
            body.addView(box);
            return;
        }
        if (failed) {
            body.addView(MusicKit.note(c, errorText));
            return;
        }
        LinearLayout actions = new LinearLayout(c);
        actions.setPadding(dp(16), dp(6), dp(16), dp(8));
        TextView play = PhoneKit.button(c, "▶  Play", true);
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MusicPlayer.playQueue(tracks, 0, false);
            }
        });
        TextView shuffle = PhoneKit.button(c, "Shuffle", false);
        shuffle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!tracks.isEmpty()) MusicPlayer.playQueue(tracks, new Random().nextInt(tracks.size()), true);
            }
        });
        actions.addView(play);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.leftMargin = dp(8);
        actions.addView(shuffle, sl);
        body.addView(actions);
        LinearLayout toggles = new LinearLayout(c);
        toggles.setPadding(dp(16), 0, dp(16), dp(8));
        likeButton = PhoneKit.chip(c, "", false);
        hideButton = PhoneKit.chip(c, "", false);
        PhoneKit.styleChip(hideButton, false);
        likeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Taste.toggleLikeAlbum(album());
            }
        });
        hideButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Taste.isHiddenAlbum(album())) Taste.unhide("album", preview.id);
                else Taste.hideAlbum(album());
            }
        });
        toggles.addView(likeButton);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.leftMargin = dp(8);
        toggles.addView(hideButton, hl);
        body.addView(toggles);
        styleToggles();
        if (tracks.isEmpty()) body.addView(MusicKit.note(c, "No songs found for this album."));
        MusicKit.trackList(ui, body, tracks, true);
    }
}
