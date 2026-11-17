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

/** An artist: photo, popular songs and their albums, singles and EPs. */
final class PhoneArtist extends PhoneUi.Page {
    private final Artist preview;
    private LinearLayout body;
    private String name, detail, photo;
    private List<Track> top = new ArrayList<>();
    private final List<String> groupTitles = new ArrayList<>();
    private final List<List<Album>> groups = new ArrayList<>();
    private boolean loaded, failed;
    private String errorText;
    private TextView likeButton, hideButton;
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

    PhoneArtist(PhoneUi ui, Artist preview) {
        super(ui);
        this.preview = preview;
        name = preview.name;
        detail = preview.detail;
        photo = preview.artworkURL;
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ui.largeTitle("Artist", true));
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
                    Addons.ArtistDetail d = Addons.artistDetail(addon, preview.id);
                    if (d == null) throw new Exception("The addon has no page for this artist");
                    return d;
                }
                return MusicBrainz.getArtist(preview.id, preview.name);
            }
        }, new MusicNet.Done<Object>() {
            @Override
            public void done(Object v, Exception e) {
                if (body == null) return;
                if (v instanceof Addons.ArtistDetail) {
                    Addons.ArtistDetail d = (Addons.ArtistDetail) v;
                    name = d.name.length() > 0 ? d.name : name;
                    detail = d.bio;
                    photo = d.artworkURL != null ? d.artworkURL : photo;
                    top = d.topTracks;
                    add("Albums", d.albums);
                } else if (v instanceof MusicBrainz.ArtistPage) {
                    MusicBrainz.ArtistPage p = (MusicBrainz.ArtistPage) v;
                    name = p.name;
                    detail = p.detail;
                    add("Albums", p.albums);
                    add("Singles and EPs", p.singles);
                    add("Live, compilations and more", p.other);
                    loadTop();
                } else {
                    failed = true;
                    errorText = e != null && e.getMessage() != null ? e.getMessage() : "Couldn't load this artist";
                }
                loaded = true;
                draw();
            }
        });
        return col;
    }

    private void add(String title, List<Album> list) {
        if (list == null || list.isEmpty()) return;
        groupTitles.add(title);
        groups.add(list);
    }

    private void loadTop() {
        MusicNet.async(new MusicNet.Job<List<Track>>() {
            @Override
            public List<Track> run() {
                return MusicBrainz.getTopTracks(preview.id, name);
            }
        }, new MusicNet.Done<List<Track>>() {
            @Override
            public void done(List<Track> v, Exception e) {
                if (body == null || v == null || v.isEmpty()) return;
                top = v;
                draw();
            }
        });
    }

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

    private Artist artist() {
        Artist a = new Artist();
        a.id = preview.id;
        a.name = name;
        a.addon = preview.addon;
        return a;
    }

    private void styleToggles() {
        boolean liked = Taste.isLikedArtist(artist());
        likeButton.setText(liked ? "♥ Liked" : "♡ Like");
        PhoneKit.styleChip(likeButton, liked);
        hideButton.setText(Taste.isHiddenArtist(artist()) ? "Hidden: undo" : "Not interested");
    }

    private void draw() {
        Context c = ctx();
        body.removeAllViews();
        LinearLayout hero = new LinearLayout(c);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(16), dp(4), dp(16), dp(8));
        MusicKit.Cover cover = new MusicKit.Cover(c, 104, true, name);
        cover.setArtist(preview.addon == null ? preview.id : null, name, photo);
        hero.addView(cover);
        LinearLayout side = new LinearLayout(c);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(14), 0, 0, 0);
        TextView t = PhoneKit.text(c, name, 24, Color.WHITE, PhoneKit.BOLD);
        t.setMaxLines(2);
        side.addView(t);
        if (detail != null) {
            TextView m = PhoneKit.text(c, detail, 13, Theme.TEXT_DIM, PhoneKit.REGULAR);
            m.setMaxLines(3);
            side.addView(m);
        }
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
        if (!top.isEmpty()) {
            TextView play = PhoneKit.button(c, "▶  Play", true);
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    MusicPlayer.playQueue(top, 0, false);
                }
            });
            TextView shuffle = PhoneKit.button(c, "Shuffle", false);
            shuffle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    MusicPlayer.playQueue(top, 0, true);
                }
            });
            actions.addView(play);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.leftMargin = dp(8);
            actions.addView(shuffle, sl);
            body.addView(actions);
        }
        LinearLayout toggles = new LinearLayout(c);
        toggles.setPadding(dp(16), 0, dp(16), dp(8));
        likeButton = PhoneKit.chip(c, "", false);
        hideButton = PhoneKit.chip(c, "", false);
        likeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Taste.toggleLikeArtist(artist());
            }
        });
        hideButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Taste.isHiddenArtist(artist())) Taste.unhide("artist", preview.id);
                else Taste.hideArtist(artist());
            }
        });
        toggles.addView(likeButton);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.leftMargin = dp(8);
        toggles.addView(hideButton, hl);
        body.addView(toggles);
        styleToggles();
        if (!top.isEmpty()) {
            body.addView(PhoneUi.sectionTitle(c, "Popular songs"));
            MusicKit.trackList(ui, body, top, true);
        }
        for (int i = 0; i < groups.size(); i++) {
            body.addView(MusicKit.row(c, groupTitles.get(i), MusicKit.albumTiles(ui, groups.get(i))));
        }
        if (groups.isEmpty() && top.isEmpty()) body.addView(MusicKit.note(c, "No albums found for this artist."));
    }
}
