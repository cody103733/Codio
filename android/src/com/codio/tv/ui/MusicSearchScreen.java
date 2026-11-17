package com.codio.tv.ui;

import com.codio.tv.music.Addons;
import com.codio.tv.music.Model;
import com.codio.tv.music.MusicBrainz;
import com.codio.tv.music.MusicNet;
import com.codio.tv.music.Taste;

import java.util.ArrayList;
import java.util.List;

/** Search for artists, albums and songs: MusicBrainz and every music addon that can search, shown as each one answers. */
final class MusicSearchScreen extends MusicPage {
    private static final String RECENT = "tvMusicSearches";

    private String query = "";
    private int request;
    private Model.Results mb = new Model.Results();
    private final List<Model.Results> fromAddons = new ArrayList<Model.Results>();
    private boolean searching, asked;

    MusicSearchScreen(TvView tv) {
        super(tv, false);
    }

    @Override
    void onShow() {
        // Straight to the keyboard the first time.
        if (!asked) {
            asked = true;
            MusicNet.MAIN.post(new Runnable() {
                @Override
                public void run() {
                    if (tv.top() == MusicSearchScreen.this) type();
                }
            });
        }
    }

    private List<String> recent() {
        List<String> out = new ArrayList<String>();
        String saved = MusicNet.prefs().getString(RECENT, "");
        for (String s : saved.split("\n")) if (s.trim().length() > 0) out.add(s.trim());
        return out;
    }

    private void remember(String q) {
        List<String> list = recent();
        list.remove(q);
        list.add(0, q);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < list.size() && i < 5; i++) b.append(list.get(i)).append('\n');
        MusicNet.prefs().edit().putString(RECENT, b.toString()).apply();
    }

    private void type() {
        tv.push(new TextEntryScreen(tv, "Search music", "Artists, albums and songs", query, false, new TextEntryScreen.Done() {
            @Override
            public void done(String text) {
                if (text.length() > 0) search(text);
            }
        }));
    }

    @Override
    List<String> buttons() {
        List<String> b = new ArrayList<String>();
        b.add(query.length() == 0 ? "Type a search" : "New search");
        for (String r : recent()) if (!r.equals(query)) b.add(r);
        return b;
    }

    @Override
    void button(int index) {
        if (index == 0) type();
        else search(buttons().get(index));
    }

    @Override
    String note() {
        if (query.length() == 0) return "Search for an artist, an album or a song.";
        if (searching && strips.isEmpty()) return "Searching for “" + query + "”…";
        if (!searching && strips.isEmpty()) return "Nothing found for “" + query + "”.";
        return "Results for “" + query + "”";
    }

    private void search(String q) {
        query = q;
        remember(q);
        headFocus = 0;
        final int id = ++request;
        mb = new Model.Results();
        fromAddons.clear();
        searching = busy = true;
        zone = HEAD;
        rebuild();
        final String text = q;
        final List<Model.Addon> addons = new ArrayList<Model.Addon>();
        for (Model.Addon a : Addons.load()) if (a.has("search")) addons.add(a);
        final int[] left = {1 + addons.size()};
        final Runnable one = new Runnable() {
            @Override
            public void run() {
                if (id != request) return;
                if (--left[0] <= 0) searching = busy = false;
                rebuild();
            }
        };
        MusicNet.POOL.execute(new Runnable() {
            @Override
            public void run() {
                Model.Results all = null;
                try {
                    all = MusicBrainz.search(text, new MusicBrainz.Partial() {
                        @Override
                        public void part(final Model.Results snapshot) {
                            MusicNet.MAIN.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (id != request) return;
                                    mb = snapshot;
                                    rebuild();
                                }
                            });
                        }
                    });
                } catch (Exception ignored) {
                }
                final Model.Results done = all;
                MusicNet.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (id == request && done != null) mb = done;
                        one.run();
                    }
                });
            }
        });
        for (final Model.Addon a : addons) {
            MusicNet.async(new MusicNet.Job<Model.Results>() {
                @Override
                public Model.Results run() throws Exception {
                    return Addons.search(a, text);
                }
            }, new MusicNet.Done<Model.Results>() {
                @Override
                public void done(Model.Results value, Exception error) {
                    if (id == request && value != null) fromAddons.add(value);
                    one.run();
                }
            });
        }
    }

    @Override
    void rebuild() {
        List<Model.Artist> artists = new ArrayList<Model.Artist>(mb.artists);
        List<Model.Album> albums = new ArrayList<Model.Album>(mb.albums);
        // Songs from MusicBrainz play through any addon; an addon's own songs play from that addon first.
        List<Model.Track> tracks = new ArrayList<Model.Track>(mb.tracks);
        for (Model.Results r : fromAddons) {
            artists.addAll(r.artists);
            albums.addAll(r.albums);
            tracks.addAll(r.tracks);
        }
        List<MusicStrip> out = new ArrayList<MusicStrip>();
        if (!artists.isEmpty()) out.add(new MusicStrip("Artists", artists));
        if (!albums.isEmpty()) out.add(new MusicStrip("Albums", albums));
        if (!tracks.isEmpty()) out.add(new MusicStrip("Songs", tracks));
        setStrips(out);
    }
}
