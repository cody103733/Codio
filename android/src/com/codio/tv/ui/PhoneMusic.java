package com.codio.tv.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.codio.tv.music.Addons;
import com.codio.tv.music.DailyMix;
import com.codio.tv.music.ListenBrainz;
import com.codio.tv.music.Discovery;
import com.codio.tv.music.Model.Addon;
import com.codio.tv.music.Model.Album;
import com.codio.tv.music.Model.Artist;
import com.codio.tv.music.Model.Results;
import com.codio.tv.music.Model.Track;
import com.codio.tv.music.MusicBrainz;
import com.codio.tv.music.MusicNet;
import com.codio.tv.music.MusicPlayer;
import com.codio.tv.music.Taste;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Music tab: a search field, then what you played lately and rows made from your taste (Daily mix, Because you
 * played..., New from artists you like), what is popular and new, and the rows your music addons offer. Searching shows
 * artists, albums and songs as each source answers. The same rows as Codio for Windows.
 */
final class PhoneMusic extends PhoneUi.Page {
    private final Handler h = new Handler(Looper.getMainLooper());
    private EditText box;
    private LinearLayout content;
    private ScrollView scroll;
    private ProgressBar spin;
    private int request = 0;
    private static String lastQuery = "";

    // What the home rows are made from (filled in as each call answers).
    private int homeGen = 0;
    private List<Album> albums, freshAll;
    private List<Track> songs, mixRaw;
    private List<Artist> artists;
    private List<Album> more;
    private List<List<Artist>> sims = new ArrayList<>();
    private List<Taste.Seed> seeds = new ArrayList<>();
    private List<Addons.CatalogRow> catalogs = new ArrayList<>();
    private Results results;
    private boolean searching;

    private final Runnable tasteChanged = new Runnable() {
        @Override
        public void run() {
            if (results == null) renderSoon();
            else if (content != null) MusicKit.refreshRows(content);
        }
    };
    private final Addons.Changed addonsChanged = new Addons.Changed() {
        @Override
        public void changed() {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (content != null && results == null) {
                        loadCatalogs();
                        renderSoon();
                    }
                }
            });
        }
    };
    private final MusicPlayer.Listener playerListener = new MusicPlayer.Listener() {
        @Override
        public void changed() {
            if (content != null) MusicKit.refreshRows(content);
        }
    };

    PhoneMusic(PhoneUi ui) {
        super(ui);
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ui.largeTitle("Music", false));

        LinearLayout bar = new LinearLayout(ctx());
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout field = new LinearLayout(ctx());
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(dp(8), 0, dp(8), 0);
        field.setBackground(PhoneKit.round(PhoneKit.GROUPED, dp(10)));
        field.addView(new PhoneSearch.Magnifier(ctx()), new LinearLayout.LayoutParams(dp(22), dp(22)));
        box = new EditText(ctx());
        box.setHint("Artists, albums and songs");
        box.setHintTextColor(PhoneKit.GRAY);
        box.setTextColor(Color.WHITE);
        box.setTextSize(17);
        box.setTypeface(PhoneKit.face(PhoneKit.REGULAR));
        box.setSingleLine(true);
        box.setBackground(null);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.setPadding(dp(6), 0, dp(4), 0);
        field.addView(box, new LinearLayout.LayoutParams(0, -1, 1));
        bar.addView(field, new LinearLayout.LayoutParams(0, dp(36), 1));
        TextView addons = PhoneKit.link(ctx(), "Addons", Theme.RED);
        addons.setPadding(dp(12), dp(6), dp(4), dp(6));
        addons.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.push(new PhoneMusicAddons(ui));
            }
        });
        bar.addView(addons);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.setMargins(dp(16), 0, dp(12), dp(6));
        col.addView(bar, bl);

        scroll = new ScrollView(ctx());
        scroll.setFillViewport(true);
        content = new LinearLayout(ctx());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        box.setText(lastQuery);
        box.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                h.removeCallbacks(run);
                h.postDelayed(run, 350);
            }
        });
        box.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                h.removeCallbacks(run);
                run.run();
                keyboard(false);
                return true;
            }
        });
        if (lastQuery.trim().length() == 0) startHome();
        else search(lastQuery);
        return col;
    }

    @Override
    void onShow() {
        Taste.listeners.add(tasteChanged);
        ListenBrainz.listeners.add(accountChanged);
        Addons.listen(addonsChanged);
        MusicPlayer.listen(playerListener);
    }

    @Override
    void onHide() {
        Taste.listeners.remove(tasteChanged);
        ListenBrainz.listeners.remove(accountChanged);
        Addons.unlisten(addonsChanged);
        MusicPlayer.unlisten(playerListener);
        keyboard(false);
    }

    private void keyboard(boolean show) {
        InputMethodManager imm = (InputMethodManager) ctx().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (show) imm.showSoftInput(box, InputMethodManager.SHOW_IMPLICIT);
        else imm.hideSoftInputFromWindow(box.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ search

    private final Runnable run = new Runnable() {
        @Override
        public void run() {
            search(box.getText().toString().trim());
        }
    };

    private void search(final String q) {
        lastQuery = q;
        final int id = ++request;
        if (q.length() == 0) {
            results = null;
            searching = false;
            startHome();
            return;
        }
        searching = true;
        results = new Results();
        render();
        final List<Results> fromAddons = new ArrayList<>();
        final Results[] mb = {new Results()};
        final Runnable show = new Runnable() {
            @Override
            public void run() {
                if (id != request) return;
                Results r = new Results();
                synchronized (fromAddons) {
                    r.artists.addAll(mb[0].artists);
                    r.albums.addAll(mb[0].albums);
                    r.tracks.addAll(mb[0].tracks);
                    for (Results a : fromAddons) {
                        r.artists.addAll(a.artists);
                        r.albums.addAll(a.albums);
                        r.tracks.addAll(a.tracks);
                    }
                }
                results = r;
                render();
            }
        };
        MusicNet.POOL.execute(new Runnable() {
            @Override
            public void run() {
                MusicBrainz.search(q, new MusicBrainz.Partial() {
                    @Override
                    public void part(Results snapshot) {
                        mb[0] = snapshot;
                        MusicNet.MAIN.post(show);
                    }
                });
                MusicNet.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (id == request) {
                            searching = false;
                            render();
                        }
                    }
                });
            }
        });
        for (final Addon a : Addons.load()) {
            if (!a.has("search")) continue;
            MusicNet.POOL.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Results r = Addons.search(a, q);
                        synchronized (fromAddons) {
                            fromAddons.add(r);
                        }
                        MusicNet.MAIN.post(show);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------ home data

    private void startHome() {
        final int gen = ++homeGen;
        albums = freshAll = more = null;
        songs = null;
        mixRaw = DailyMix.saved();
        artists = null;
        sims = new ArrayList<>();
        seeds = Taste.topSeeds(5);
        for (int i = 0; i < seeds.size(); i++) sims.add(null);
        render();
        fetch(gen, new MusicNet.Job<Object>() {
            @Override
            public Object run() {
                return Discovery.popularAlbums();
            }
        }, 0);
        fetch(gen, new MusicNet.Job<Object>() {
            @Override
            public Object run() {
                return Discovery.newReleases();
            }
        }, 1);
        fetch(gen, new MusicNet.Job<Object>() {
            @Override
            public Object run() {
                return Discovery.popularSongs();
            }
        }, 2);
        fetch(gen, new MusicNet.Job<Object>() {
            @Override
            public Object run() {
                return Discovery.popularArtists();
            }
        }, 3);
        if (!seeds.isEmpty()) {
            final String first = seeds.get(0).id;
            fetch(gen, new MusicNet.Job<Object>() {
                @Override
                public Object run() {
                    return Discovery.moreFrom(first);
                }
            }, 4);
        }
        for (int i = 0; i < seeds.size(); i++) {
            final String id = seeds.get(i).id;
            fetch(gen, new MusicNet.Job<Object>() {
                @Override
                public Object run() {
                    return Discovery.similarArtists(id);
                }
            }, 10 + i);
        }
        startMix(gen);
        loadCatalogs();
    }

    private void loadCatalogs() {
        final int gen = homeGen;
        final List<Addon> list = Addons.load();
        MusicNet.async(new MusicNet.Job<List<Addons.CatalogRow>>() {
            @Override
            public List<Addons.CatalogRow> run() {
                return Addons.loadCatalogs(list);
            }
        }, new MusicNet.Done<List<Addons.CatalogRow>>() {
            @Override
            public void done(List<Addons.CatalogRow> rows, Exception e) {
                if (gen != homeGen || rows == null) return;
                catalogs = rows;
                renderSoon();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void fetch(final int gen, final MusicNet.Job<Object> job, final int slot) {
        MusicNet.async(job, new MusicNet.Done<Object>() {
            @Override
            public void done(Object v, Exception e) {
                if (gen != homeGen) return;
                List<Object> list = v instanceof List ? (List<Object>) v : new ArrayList<Object>();
                switch (slot) {
                    case 0:
                        albums = (List<Album>) (List<?>) list;
                        break;
                    case 1:
                        freshAll = (List<Album>) (List<?>) list;
                        break;
                    case 2:
                        songs = (List<Track>) (List<?>) list;
                        break;
                    case 3:
                        artists = (List<Artist>) (List<?>) list;
                        break;
                    case 4:
                        more = (List<Album>) (List<?>) list;
                        break;
                    default:
                        if (slot - 10 < sims.size()) sims.set(slot - 10, (List<Artist>) (List<?>) list);
                        startMix(gen);
                        break;
                }
                renderSoon();
            }
        });
    }

    private String mixKey = "";

    /**
     * Daily mix: 50 to 100 songs from your ListenBrainz Daily Jams, your artists, artists like them and your hearts (see
     * DailyMix). Built again when the day, your favorites or your ListenBrainz account change; today's order is kept.
     */
    private void startMix(final int gen) {
        if (sims.contains(null)) return;
        List<List<Artist>> lists = new ArrayList<>();
        for (List<Artist> l : sims) lists.add(l == null ? new ArrayList<Artist>() : l);
        final List<Artist> like = Taste.mergeSimilar(seeds, lists);
        final List<Taste.Seed> mine = Taste.topSeeds(8);
        ListenBrainz.Account account = ListenBrainz.account();
        final String user = account != null ? account.user : null;
        if (mine.isEmpty() && user == null) return;
        final List<Track> liked = new ArrayList<>(Taste.likedTracks());
        StringBuilder k = new StringBuilder(DailyMix.today());
        for (Taste.Seed s : mine) k.append(',').append(s.id);
        for (Artist x : like) k.append(',').append(x.id);
        k.append('|').append(user).append('|').append(account != null ? account.lastSync / (3 * 3600 * 1000L) : 0).append('|').append(liked.size());
        if (k.toString().equals(mixKey)) return;
        mixKey = k.toString();
        final DailyMix.Hidden hidden = DailyMix.Hidden.now();
        MusicNet.async(new MusicNet.Job<List<Track>>() {
            @Override
            public List<Track> run() {
                return DailyMix.build(mine, like, liked, user, hidden);
            }
        }, new MusicNet.Done<List<Track>>() {
            @Override
            public void done(List<Track> v, Exception e) {
                if (v == null || v.isEmpty() || gen != homeGen) return;
                mixRaw = v;
                renderSoon();
            }
        });
    }

    private final Runnable accountChanged = new Runnable() {
        @Override
        public void run() {
            if (results == null) startMix(homeGen);
        }
    };

    // ------------------------------------------------------------------ drawing

    private final Runnable draw = new Runnable() {
        @Override
        public void run() {
            render();
        }
    };

    private void renderSoon() {
        h.removeCallbacks(draw);
        h.postDelayed(draw, 120);
    }

    private void render() {
        if (content == null) return;
        h.removeCallbacks(draw);
        Context c = ctx();
        content.removeAllViews();
        if (results != null) {
            renderResults(c);
            return;
        }
        if (Addons.load().isEmpty()) {
            TextView t = MusicKit.note(c, "Search works now. To play songs, add a music addon first.");
            content.addView(t);
            TextView add = PhoneKit.link(c, "Add one", Theme.RED);
            add.setPadding(dp(16), dp(2), dp(16), dp(10));
            add.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.push(new PhoneMusicAddons(ui));
                }
            });
            content.addView(add);
        }
        // Artists you like and ones like them: popular and new rows lead with their music.
        List<List<Artist>> simLists = new ArrayList<>();
        for (List<Artist> l : sims) simLists.add(l == null ? new ArrayList<Artist>() : l);
        List<Artist> merged = Taste.mergeSimilar(seeds, simLists);
        Set<String> liked = new HashSet<>();
        for (Taste.Seed s : seeds) liked.add(s.id);
        for (Artist a : Taste.likedArtists()) liked.add(a.id);
        for (Artist a : merged) liked.add(a.id);

        List<Track> mix = new ArrayList<>();
        if (mixRaw != null) {
            mix = Taste.visibleTracks(mixRaw);
        }
        addTracks(c, "Daily mix", mix);
        addTracks(c, "Recently played", Taste.visibleTracks(MusicPlayer.recent()));
        if (!seeds.isEmpty()) addArtists(c, "Because you played " + seeds.get(0).name, sims.get(0));
        if (!seeds.isEmpty() && more != null) addAlbums(c, "More from " + seeds.get(0).name, Taste.visibleAlbums(more));
        addArtists(c, "Similar to your favorites", merged);
        List<Album> forYou = new ArrayList<>();
        for (Album a : Taste.visibleAlbums(freshAll == null ? new ArrayList<Album>() : freshAll)) {
            if (a.artistMbid != null && liked.contains(a.artistMbid) && forYou.size() < 24) forYou.add(a);
        }
        addAlbums(c, "New from artists you like", forYou);
        if (seeds.size() > 1) addArtists(c, "Because you played " + seeds.get(1).name, sims.get(1));
        addTracks(c, "Liked songs", new ArrayList<>(Taste.likedTracks()));
        addAlbums(c, "Popular right now", favor(albums, liked, 100));
        addAlbums(c, "New releases", favor(freshAll, liked, 30));
        List<Track> top = Taste.visibleTracks(songs == null ? new ArrayList<Track>() : songs);
        if (!top.isEmpty()) {
            content.addView(PhoneUi.sectionTitle(c, "Popular songs"));
            List<Track> ten = new ArrayList<>(top.subList(0, Math.min(10, top.size())));
            MusicKit.trackList(ui, content, ten, true);
        }
        addArtists(c, "Popular artists", artists);
        for (Addons.CatalogRow row : catalogs) {
            List<View> tiles = new ArrayList<>();
            if ("album".equals(row.type)) {
                for (Object o : row.items) tiles.add(MusicKit.albumTile(ui, (Album) o));
            } else if ("artist".equals(row.type)) {
                for (Object o : row.items) tiles.add(MusicKit.artistTile(ui, (Artist) o));
            } else {
                List<Track> ts = new ArrayList<>();
                for (Object o : row.items) ts.add((Track) o);
                tiles = MusicKit.trackTiles(ui, ts);
            }
            if (!tiles.isEmpty()) content.addView(MusicKit.row(c, row.name, tiles));
        }
        if (albums == null && freshAll == null && songs == null && artists == null) content.addView(spinner(c));
        else if (content.getChildCount() == 0) content.addView(MusicKit.note(c, "Couldn't load music suggestions. Check your connection, or search above."));
    }

    private View spinner(Context c) {
        ProgressBar p = new ProgressBar(c);
        p.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Theme.RED));
        LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, dp(40), 0, 0);
        box.addView(p, new LinearLayout.LayoutParams(dp(40), dp(40)));
        return box;
    }

    private List<Album> favor(List<Album> list, Set<String> liked, int max) {
        List<Album> visible = Taste.visibleAlbums(list == null ? new ArrayList<Album>() : list);
        List<Album> out = new ArrayList<>();
        for (Album a : visible) if (a.artistMbid != null && liked.contains(a.artistMbid)) out.add(a);
        for (Album a : visible) if (!(a.artistMbid != null && liked.contains(a.artistMbid))) out.add(a);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private void addTracks(Context c, String heading, List<Track> list) {
        if (list == null || list.isEmpty()) return;
        content.addView(MusicKit.row(c, heading, MusicKit.trackTiles(ui, list)));
    }

    private void addAlbums(Context c, String heading, List<Album> list) {
        if (list == null || list.isEmpty()) return;
        content.addView(MusicKit.row(c, heading, MusicKit.albumTiles(ui, list)));
    }

    private void addArtists(Context c, String heading, List<Artist> list) {
        if (list == null) return;
        list = Taste.visibleArtists(list);
        if (list.isEmpty()) return;
        content.addView(MusicKit.row(c, heading, MusicKit.artistTiles(ui, list)));
    }

    private void renderResults(Context c) {
        content.removeAllViews();
        if (searching && results.size() == 0) {
            content.addView(spinner(c));
            return;
        }
        if (!searching && results.size() == 0) {
            content.addView(MusicKit.note(c, "Nothing found for “" + lastQuery + "”."));
            return;
        }
        if (Addons.load().isEmpty()) content.addView(MusicKit.note(c, "To play songs, add a music addon first (Addons, top right)."));
        List<Artist> ar = Taste.visibleArtists(results.artists);
        if (!ar.isEmpty()) content.addView(MusicKit.row(c, "Artists", MusicKit.artistTiles(ui, ar)));
        List<Album> al = Taste.visibleAlbums(results.albums);
        if (!al.isEmpty()) content.addView(MusicKit.row(c, "Albums", MusicKit.albumTiles(ui, al)));
        List<Track> ts = Taste.visibleTracks(results.tracks);
        if (!ts.isEmpty()) {
            content.addView(PhoneUi.sectionTitle(c, "Songs"));
            MusicKit.trackList(ui, content, ts, false);
        }
    }
}
