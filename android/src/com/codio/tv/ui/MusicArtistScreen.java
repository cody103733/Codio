package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.Color;

import com.codio.tv.music.Addons;
import com.codio.tv.music.Model;
import com.codio.tv.music.MusicBrainz;
import com.codio.tv.music.MusicNet;
import com.codio.tv.music.MusicPlayer;
import com.codio.tv.music.Taste;

import java.util.ArrayList;
import java.util.List;

/** An artist: their most played songs, then albums, singles and the rest. From MusicBrainz, or from the addon that offered them. */
final class MusicArtistScreen extends MusicPage {
    private final String id, addonUrl;
    private String name, detail, art, error;
    private List<Model.Track> top = new ArrayList<Model.Track>();
    private final List<String> groupTitles = new ArrayList<String>();
    private final List<List<Model.Album>> groups = new ArrayList<List<Model.Album>>();
    private boolean loaded;

    MusicArtistScreen(TvView tv, String id, String knownName, String addonUrl) {
        super(tv, false);
        this.id = id;
        this.name = knownName == null ? "" : knownName;
        this.addonUrl = addonUrl;
        busy = true;
        final Model.Addon addon = addonUrl == null ? null : Addons.byUrl(addonUrl);
        if (addon != null) {
            MusicNet.async(new MusicNet.Job<Addons.ArtistDetail>() {
                @Override
                public Addons.ArtistDetail run() throws Exception {
                    return Addons.artistDetail(addon, MusicArtistScreen.this.id);
                }
            }, new MusicNet.Done<Addons.ArtistDetail>() {
                @Override
                public void done(Addons.ArtistDetail d, Exception e) {
                    busy = false;
                    loaded = true;
                    if (d == null) {
                        error = e != null ? "Couldn't load this artist" : "The addon has no page for this artist";
                    } else {
                        name = d.name;
                        detail = d.bio;
                        art = d.artworkURL;
                        top = d.topTracks;
                        groupTitles.add("Albums");
                        groups.add(d.albums);
                    }
                    rebuild();
                }
            });
            return;
        }
        MusicNet.async(new MusicNet.Job<MusicBrainz.ArtistPage>() {
            @Override
            public MusicBrainz.ArtistPage run() throws Exception {
                return MusicBrainz.getArtist(MusicArtistScreen.this.id, name.length() > 0 ? name : null);
            }
        }, new MusicNet.Done<MusicBrainz.ArtistPage>() {
            @Override
            public void done(final MusicBrainz.ArtistPage a, Exception e) {
                busy = false;
                loaded = true;
                if (a == null) {
                    error = "Couldn't load this artist";
                    rebuild();
                    return;
                }
                name = a.name;
                detail = a.detail;
                add("Albums", a.albums);
                add("Singles and EPs", a.singles);
                add("Live, compilations and more", a.other);
                rebuild();
                MusicNet.async(new MusicNet.Job<List<Model.Track>>() {
                    @Override
                    public List<Model.Track> run() {
                        return MusicBrainz.getTopTracks(MusicArtistScreen.this.id, a.name);
                    }
                }, new MusicNet.Done<List<Model.Track>>() {
                    @Override
                    public void done(List<Model.Track> value, Exception error) {
                        if (value != null && !value.isEmpty()) {
                            top = value;
                            rebuild();
                        }
                    }
                });
            }
        });
    }

    private void add(String title, List<Model.Album> albums) {
        if (albums == null || albums.isEmpty()) return;
        groupTitles.add(title);
        groups.add(albums);
    }

    private Model.Artist self() {
        Model.Artist a = new Model.Artist();
        a.id = id;
        a.name = name;
        a.addon = addonUrl;
        a.artworkURL = art;
        return a;
    }

    @Override
    float heroHeight() {
        return 250;
    }

    @Override
    void drawHero(Canvas c, float y, float dt) {
        String picture = TvMusicKit.artistArt(tv, id, name, art);
        if (picture == null && !groups.isEmpty() && !groups.get(0).isEmpty()) picture = groups.get(0).get(0).artworkURL;
        TvMusicKit.cover(tv, c, picture, name, TvView.MARGIN, y, 210, true, Math.round(210 * tv.scale()));
        float x = TvView.MARGIN + 250;
        tv.tSmall.setColor(Theme.TEXT_FAINT);
        c.drawText("Artist", x, y + 50, tv.tSmall);
        tv.tSmall.setColor(Theme.TEXT_DIM);
        tv.tHero.setColor(Color.WHITE);
        c.drawText(tv.fit(name, tv.tHero, TvView.W - x - TvView.MARGIN), x, y + 122, tv.tHero);
        String line = error != null ? error : Taste.isHiddenArtist(self()) ? "You said Not interested. " + (detail == null ? "" : detail) : detail;
        if (line != null) tv.drawWrapped(c, line, tv.tBody, x, y + 146, 1200, 2);
    }

    @Override
    List<String> buttons() {
        List<String> b = new ArrayList<String>();
        if (!top.isEmpty()) {
            b.add("Play");
            b.add("Shuffle");
        }
        b.add(Taste.isLikedArtist(id) ? "Hearted" : "Heart");
        b.add(Taste.isHiddenArtist(self()) ? "Show again" : "Not interested");
        return b;
    }

    @Override
    void button(int index) {
        String label = buttons().get(index);
        if ("Play".equals(label)) TvMusicKit.play(tv, top, 0, false);
        else if ("Shuffle".equals(label)) TvMusicKit.play(tv, top, (int) (Math.random() * top.size()), true);
        else if ("Show again".equals(label)) Taste.unhide("artist", id);
        else if ("Not interested".equals(label)) Taste.hideArtist(id, name);
        else Taste.toggleLikeArtist(self());
    }

    @Override
    String note() {
        return loaded && error == null && top.isEmpty() && groups.isEmpty() ? "Nothing listed for this artist yet." : null;
    }

    @Override
    void rebuild() {
        List<MusicStrip> out = new ArrayList<MusicStrip>();
        if (!top.isEmpty()) out.add(new MusicStrip("Top songs", top));
        for (int i = 0; i < groups.size(); i++) out.add(new MusicStrip(groupTitles.get(i), groups.get(i)));
        setStrips(out);
    }
}
