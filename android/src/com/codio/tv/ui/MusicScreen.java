package com.codio.tv.ui;

import com.codio.tv.music.Addons;
import com.codio.tv.music.DailyMix;
import com.codio.tv.music.Discovery;
import com.codio.tv.music.ListenBrainz;
import com.codio.tv.music.Model;
import com.codio.tv.music.MusicBrainz;
import com.codio.tv.music.MusicNet;
import com.codio.tv.music.MusicPlayer;
import com.codio.tv.music.Taste;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Music home: first the rows made from you (what you play, heart and hide), then what is popular and new, then the
 * rows your music addons offer. The same rows, in the same order, as Music on Codio for Windows.
 */
final class MusicScreen extends MusicPage {
    // What was loaded is kept while the app runs, so coming back to Music is instant.
    private static List<Model.Album> popular, fresh, more;
    private static List<Model.Track> songs, mixRaw;
    private static List<Model.Artist> artists;
    private static List<Addons.CatalogRow> catalogs;
    private static final List<List<Model.Artist>> sims = new ArrayList<List<Model.Artist>>();
    private static String seedKey = "", mixKey = "", catalogKey = null;
    private static long loadedAt;

    private List<Taste.Seed> seeds = new ArrayList<Taste.Seed>();
    private List<Model.Artist> merged = new ArrayList<Model.Artist>();
    private int pending;

    MusicScreen(TvView tv) {
        super(tv, true);
        load();
        rebuild();
    }

    @Override
    void onShow() {
        if (nav != null) nav.focus = nav.current;
        rebuild();
    }

    @Override
    List<String> buttons() {
        List<String> b = new ArrayList<String>();
        b.add("Search");
        if (MusicPlayer.hasQueue()) b.add("Now playing");
        b.add("Addons");
        return b;
    }

    @Override
    void button(int index) {
        String label = buttons().get(index);
        if ("Search".equals(label)) tv.push(new MusicSearchScreen(tv));
        else if ("Now playing".equals(label)) tv.push(new MusicNowScreen(tv));
        else tv.push(new MusicSettingsScreen(tv));
    }

    @Override
    String note() {
        if (Addons.load().isEmpty()) return "Search works now. To play songs, add a music addon first, in Addons.";
        if (pending == 0 && strips.isEmpty()) return "Couldn't load ideas right now (no connection?). Search for an artist, an album or a song to start.";
        if (pending == 0 && seeds.isEmpty()) return "Play and heart songs, and this page starts to fit you: daily mixes, artists like the ones you play, new releases from them.";
        return null;
    }

    private interface Got<T> {
        void got(T value);
    }

    private <T> void ask(MusicNet.Job<T> job, final Got<T> got) {
        pending++;
        busy = true;
        MusicNet.async(job, new MusicNet.Done<T>() {
            @Override
            public void done(T value, Exception error) {
                pending--;
                busy = pending > 0;
                got.got(value);
                rebuild();
            }
        });
    }

    private void load() {
        boolean stale = System.currentTimeMillis() - loadedAt > 3 * 3600 * 1000L;
        if (popular == null || stale) {
            loadedAt = System.currentTimeMillis();
            ask(new MusicNet.Job<List<Model.Album>>() {
                @Override
                public List<Model.Album> run() {
                    return Discovery.popularAlbums();
                }
            }, new Got<List<Model.Album>>() {
                @Override
                public void got(List<Model.Album> v) {
                    popular = Model.list(v);
                }
            });
            ask(new MusicNet.Job<List<Model.Album>>() {
                @Override
                public List<Model.Album> run() {
                    return Discovery.newReleases();
                }
            }, new Got<List<Model.Album>>() {
                @Override
                public void got(List<Model.Album> v) {
                    fresh = Model.list(v);
                }
            });
            ask(new MusicNet.Job<List<Model.Track>>() {
                @Override
                public List<Model.Track> run() {
                    return Discovery.popularSongs();
                }
            }, new Got<List<Model.Track>>() {
                @Override
                public void got(List<Model.Track> v) {
                    songs = Model.list(v);
                }
            });
            ask(new MusicNet.Job<List<Model.Artist>>() {
                @Override
                public List<Model.Artist> run() {
                    return Discovery.popularArtists();
                }
            }, new Got<List<Model.Artist>>() {
                @Override
                public void got(List<Model.Artist> v) {
                    artists = Model.list(v);
                }
            });
        }
        loadCatalogs();
        loadForYou();
    }

    private void loadCatalogs() {
        final List<Model.Addon> addons = Addons.load();
        StringBuilder k = new StringBuilder();
        for (Model.Addon a : addons) k.append(a.id()).append(',');
        if (k.toString().equals(catalogKey)) return;
        catalogKey = k.toString();
        catalogs = null;
        if (addons.isEmpty()) return;
        ask(new MusicNet.Job<List<Addons.CatalogRow>>() {
            @Override
            public List<Addons.CatalogRow> run() {
                return Addons.loadCatalogs(addons);
            }
        }, new Got<List<Addons.CatalogRow>>() {
            @Override
            public void got(List<Addons.CatalogRow> v) {
                catalogs = v;
            }
        });
    }

    /** Artists like each of your favorites, more from the first one, then the daily mix from all of them. */
    private void loadForYou() {
        final List<Taste.Seed> now = Taste.topSeeds(5);
        StringBuilder k = new StringBuilder();
        for (Taste.Seed s : now) k.append(s.id).append(',');
        if (k.toString().equals(seedKey)) return;
        seedKey = k.toString();
        final String mine = seedKey;
        sims.clear();
        more = null;
        if (now.isEmpty()) return;
        ask(new MusicNet.Job<List<List<Model.Artist>>>() {
            @Override
            public List<List<Model.Artist>> run() {
                List<List<Model.Artist>> out = new ArrayList<List<Model.Artist>>();
                for (Taste.Seed s : now) {
                    List<Model.Artist> l;
                    try {
                        l = Model.list(Discovery.similarArtists(s.id));
                    } catch (Exception e) {
                        l = new ArrayList<Model.Artist>();
                    }
                    out.add(l);
                }
                return out;
            }
        }, new Got<List<List<Model.Artist>>>() {
            @Override
            public void got(List<List<Model.Artist>> v) {
                if (!mine.equals(seedKey) || v == null) return;
                sims.clear();
                sims.addAll(v);
            }
        });
        ask(new MusicNet.Job<List<Model.Album>>() {
            @Override
            public List<Model.Album> run() {
                return Discovery.moreFrom(now.get(0).id);
            }
        }, new Got<List<Model.Album>>() {
            @Override
            public void got(List<Model.Album> v) {
                if (mine.equals(seedKey)) more = Model.list(v);
            }
        });
    }

    /**
     * Daily mix: 50 to 100 songs from your ListenBrainz Daily Jams, your artists, artists like them and your hearts (see
     * DailyMix). Built again when the day, your favorites or your ListenBrainz account change; today's order is kept.
     */
    private void loadMix() {
        final List<Taste.Seed> mine = Taste.topSeeds(8);
        ListenBrainz.Account account = ListenBrainz.account();
        final String user = account != null ? account.user : null;
        if (mine.isEmpty() && user == null) return;
        // Wait for the artists like your favorites first.
        if (!mine.isEmpty() && sims.isEmpty()) return;
        final List<Model.Artist> like = Taste.mergeSimilar(Taste.topSeeds(5), sims);
        final List<Model.Track> liked = new ArrayList<Model.Track>(Taste.likedTracks());
        StringBuilder k = new StringBuilder(DailyMix.today());
        for (Taste.Seed s : mine) k.append(',').append(s.id);
        for (Model.Artist x : like) k.append(',').append(x.id);
        k.append('|').append(user).append('|').append(account != null ? account.lastSync / (3 * 3600 * 1000L) : 0).append('|').append(liked.size());
        if (k.toString().equals(mixKey)) return;
        mixKey = k.toString();
        final DailyMix.Hidden hidden = DailyMix.Hidden.now();
        ask(new MusicNet.Job<List<Model.Track>>() {
            @Override
            public List<Model.Track> run() {
                return DailyMix.build(mine, like, liked, user, hidden);
            }
        }, new Got<List<Model.Track>>() {
            @Override
            public void got(List<Model.Track> v) {
                if (v != null && !v.isEmpty()) mixRaw = v;
            }
        });
    }

    private static <T> List<T> first(List<T> list, int n) {
        return list.size() > n ? new ArrayList<T>(list.subList(0, n)) : list;
    }

    private void row(List<MusicStrip> out, String title, List<?> items) {
        if (items != null && !items.isEmpty()) out.add(new MusicStrip(title, items));
    }

    @Override
    void rebuild() {
        loadCatalogs();
        loadForYou();
        loadMix();
        if (mixRaw == null) mixRaw = DailyMix.saved();
        seeds = Taste.topSeeds(5);
        merged = Taste.mergeSimilar(seeds, sims);
        // Artists you like and ones like them: popular and new rows lead with their music.
        Set<String> liked = new HashSet<String>();
        for (Taste.Seed s : seeds) liked.add(s.id);
        for (Model.Artist a : Taste.likedArtists()) liked.add(a.id);
        for (Model.Artist a : merged) liked.add(a.id);

        List<MusicStrip> out = new ArrayList<MusicStrip>();
        if (mixRaw != null) row(out, "Daily mix", Taste.visibleTracks(mixRaw));
        row(out, "Recently played", Taste.visibleTracks(MusicPlayer.recent()));
        if (seeds.size() > 0 && sims.size() > 0) row(out, "Because you played " + seeds.get(0).name, Taste.visibleArtists(sims.get(0)));
        if (seeds.size() > 0 && more != null) row(out, "More from " + seeds.get(0).name, Taste.visibleAlbums(more));
        row(out, "Similar to your favorites", merged);
        List<Model.Album> freshShown = fresh == null ? new ArrayList<Model.Album>() : Taste.visibleAlbums(fresh);
        List<Model.Album> forYou = new ArrayList<Model.Album>();
        for (Model.Album a : freshShown) if (a.artistMbid != null && liked.contains(a.artistMbid)) forYou.add(a);
        row(out, "New from artists you like", first(forYou, 24));
        if (seeds.size() > 1 && sims.size() > 1) row(out, "Because you played " + seeds.get(1).name, Taste.visibleArtists(sims.get(1)));
        row(out, "Liked songs", new ArrayList<Model.Track>(Taste.likedTracks()));
        row(out, "Liked albums", new ArrayList<Model.Album>(Taste.likedAlbums()));
        row(out, "Liked artists", new ArrayList<Model.Artist>(Taste.likedArtists()));
        if (popular != null) row(out, "Popular right now", favor(Taste.visibleAlbums(popular), liked));
        row(out, "New releases", first(favor(freshShown, liked), 30));
        if (songs != null) row(out, "Songs people are playing", Taste.visibleTracks(songs));
        if (artists != null) row(out, "Popular artists", Taste.visibleArtists(artists));
        if (catalogs != null) {
            for (Addons.CatalogRow r : catalogs) {
                List<Object> shown = new ArrayList<Object>();
                for (Object o : r.items) {
                    boolean hidden = o instanceof Model.Track ? Taste.isHiddenTrack((Model.Track) o) : o instanceof Model.Album ? Taste.isHiddenAlbum((Model.Album) o) : Taste.isHiddenArtist((Model.Artist) o);
                    if (!hidden) shown.add(o);
                }
                row(out, r.name, shown);
            }
        }
        setStrips(out);
    }

    private static List<Model.Album> favor(List<Model.Album> visible, Set<String> liked) {
        List<Model.Album> out = new ArrayList<Model.Album>();
        for (Model.Album a : visible) if (a.artistMbid != null && liked.contains(a.artistMbid)) out.add(a);
        for (Model.Album a : visible) if (!(a.artistMbid != null && liked.contains(a.artistMbid))) out.add(a);
        return out;
    }
}
