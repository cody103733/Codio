package com.codio.tv.ui;

import android.graphics.Canvas;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/** Movies / Series: pick a catalog and a genre, browse a poster grid. */
final class DiscoverScreen extends Screen {
    private static final int NAV = 0, CATS = 1, GENRES = 2, GRID = 3;
    private static final float CHIPS_Y = 160, GENRES_Y = 236, GRID_Y = 340;

    private final String type;
    private final NavBar nav;
    private List<Store.Catalog> catalogs = new ArrayList<>();
    private int cat = 0, catFocus = 0;
    private final List<String> genres = new ArrayList<>(); // first entry null = all
    private int genre = 0, genreFocus = 0;
    private final Grid grid = new Grid(7);
    private int zone = GRID;
    private boolean loading, done, failed;
    private int request = 0;
    private int builtFor = -1;
    private final Smooth scroll = new Smooth(0.1f, 0.4f);
    private final Smooth genreScroll = new Smooth(0.09f, 0.4f);

    DiscoverScreen(TvView tv, String type) {
        super(tv);
        this.type = type;
        this.nav = new NavBar(tv, "movie".equals(type) ? NavBar.MOVIES : NavBar.SERIES);
    }

    @Override
    void onShow() {
        nav.focus = nav.current;
        if (builtFor != tv.store.generation()) {
            builtFor = tv.store.generation();
            catalogs = tv.store.discoverCatalogs(type);
            cat = catFocus = 0;
            selectCatalog();
        }
    }

    @Override
    void dataChanged(boolean addonsChanged) {
        if (addonsChanged) onShow();
    }

    private void selectCatalog() {
        genres.clear();
        if (!catalogs.isEmpty()) {
            Store.Catalog c = catalogs.get(cat);
            if (!c.genreRequired) genres.add(null);
            genres.addAll(c.genres);
        }
        genre = genreFocus = 0;
        genreScroll.snap(0);
        reload();
    }

    private void reload() {
        grid.reset();
        done = failed = false;
        loading = false;
        scroll.snap(0);
        grid.focusIn();
        loadMore();
    }

    private void loadMore() {
        if (catalogs.isEmpty() || loading || done) return;
        final Store.Catalog c = catalogs.get(cat);
        final int id = ++request;
        loading = true;
        tv.store.loadCatalog(c, genres.isEmpty() ? null : genres.get(genre), grid.items.size(), null, new Store.Callback<List<Store.Meta>>() {
            @Override
            public void done(List<Store.Meta> result, Exception error) {
                if (id != request) return;
                loading = false;
                if (error != null && grid.items.isEmpty()) failed = true;
                if (result == null || result.isEmpty() || !c.skip) done = true;
                if (result != null) {
                    java.util.HashSet<String> seen = new java.util.HashSet<>();
                    for (Store.Meta m : grid.items) seen.add(m.id);
                    for (Store.Meta m : result) if (seen.add(m.id)) grid.items.add(m);
                }
                tv.kick();
            }
        });
    }

    private String catLabel(Store.Catalog c) {
        // Two addons can both have a "Popular" catalog: name the addon when it's not the first one.
        boolean dup = false;
        for (Store.Catalog o : catalogs) if (o != c && o.name.equals(c.name)) dup = true;
        return dup ? c.name + " · " + c.addon.name : c.name;
    }

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(0xFF0B0B0B);
        int r = grid.row();
        scroll.to(zone == GRID && r > 0 ? r * Grid.STEP_Y - 150 : 0);
        float sy = step(scroll, dt);

        float x = TvView.MARGIN;
        for (int i = 0; i < catalogs.size(); i++) {
            String label = catLabel(catalogs.get(i));
            if (CHIPS_Y - sy > -80) tv.drawChip(c, x, CHIPS_Y - sy, label, zone == CATS && i == catFocus, i == cat);
            x += tv.chipWidth(label) + 14;
        }
        float gs = step(genreScroll, dt);
        x = TvView.MARGIN - gs;
        for (int i = 0; i < genres.size(); i++) {
            String label = genres.get(i) == null ? "All genres" : genres.get(i);
            float w = tv.chipWidth(label);
            if (i == genreFocus && zone == GENRES) {
                if (x < TvView.MARGIN) genreScroll.to(genreScroll.target - (TvView.MARGIN - x));
                else if (x + w > TvView.W - TvView.MARGIN) genreScroll.to(genreScroll.target + (x + w - (TvView.W - TvView.MARGIN)));
            }
            if (x + w > 0 && x < TvView.W && GENRES_Y - sy > -80) tv.drawChip(c, x, GENRES_Y - sy, label, zone == GENRES && i == genreFocus, i == genre);
            x += w + 12;
        }

        grid.draw(this, c, TvView.MARGIN, GRID_Y - sy, zone == GRID, dt);
        if (grid.items.isEmpty()) {
            if (loading) tv.spinner(c, TvView.W / 2, 600, 34, dt);
            else if (failed) c.drawText("Couldn't load this catalog. Check the connection and try again.", TvView.MARGIN, 520, tv.tBody);
            else if (catalogs.isEmpty()) c.drawText("None of your addons has a " + Store.typeLabel(type).toLowerCase() + " catalog.", TvView.MARGIN, 520, tv.tBody);
        }
        // Top bar over a dark band.
        tv.fill.setColor(0xFF0B0B0B);
        if (sy > 0) {
            c.drawRect(0, 0, TvView.W, 120, tv.fill);
            c.drawRect(0, 120, TvView.W, 180, tv.topShade);
        }
        nav.draw(c, zone == NAV);
    }

    @Override
    boolean key(int k, KeyEvent e) {
        switch (zone) {
            case NAV:
                if (k == TvView.DOWN) {
                    zone = catalogs.size() > 1 ? CATS : genres.size() > 1 ? GENRES : GRID;
                    if (zone == GRID) grid.focusIn();
                    return true;
                }
                return nav.key(k);
            case CATS:
                if (k == TvView.LEFT && catFocus > 0) catFocus--;
                else if (k == TvView.RIGHT && catFocus < catalogs.size() - 1) catFocus++;
                else if (k == TvView.UP) {
                    zone = NAV;
                    nav.focus = nav.current;
                } else if (k == TvView.DOWN) {
                    zone = genres.size() > 1 ? GENRES : GRID;
                    if (zone == GRID) grid.focusIn();
                } else if (k == TvView.OK && catFocus != cat) {
                    cat = catFocus;
                    selectCatalog();
                }
                return true;
            case GENRES:
                if (k == TvView.LEFT && genreFocus > 0) genreFocus--;
                else if (k == TvView.RIGHT && genreFocus < genres.size() - 1) genreFocus++;
                else if (k == TvView.UP) zone = catalogs.size() > 1 ? CATS : NAV;
                else if (k == TvView.DOWN) {
                    zone = GRID;
                    grid.focusIn();
                } else if (k == TvView.OK) {
                    if (genreFocus != genre) {
                        genre = genreFocus;
                        reload();
                    }
                    zone = GRID;
                    grid.focusIn();
                }
                return true;
            case GRID:
            default:
                if (k == TvView.OK || k == TvView.PLAY) {
                    Store.Meta m = grid.focused();
                    if (m == null) return true;
                    if (k == TvView.PLAY && "movie".equals(m.type)) tv.host.play(m, null);
                    else tv.openDetail(m);
                    return true;
                }
                if (grid.move(k)) {
                    if (grid.focus >= grid.items.size() - grid.cols * 3) loadMore();
                    return true;
                }
                if (k == TvView.UP) {
                    zone = genres.size() > 1 ? GENRES : catalogs.size() > 1 ? CATS : NAV;
                    genreFocus = genre;
                    catFocus = cat;
                    if (zone == NAV) nav.focus = nav.current;
                }
                return true;
        }
    }

    @Override
    boolean back() {
        if (zone == GRID && grid.row() > 0) {
            grid.prev = -1;
            grid.focus = grid.focus % grid.cols;
            return true;
        }
        if (zone != NAV) {
            zone = NAV;
            nav.focus = nav.current;
            return true;
        }
        return false;
    }
}
