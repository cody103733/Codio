package com.codio.tv.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/** Home: a featured banner, then Continue Watching and one row per addon catalog. */
final class HomeScreen extends Screen {
    private static final int NAV = -2, HERO = -1;
    private static final float HERO_ROWS_Y = 800, ROWS_Y = 548;

    private final NavBar nav;
    private final Backdrop backdrop;
    private final List<Row> rows = new ArrayList<>();
    private Row cw;
    private int zone = HERO;
    private Row focusRow = null;
    private int heroButton = 0;
    private Store.Meta hero;
    private int builtFor = -1;
    private final Smooth rowsY = new Smooth(0.1f, 0.4f);

    HomeScreen(TvView tv) {
        super(tv);
        nav = new NavBar(tv, NavBar.HOME);
        backdrop = new Backdrop(tv);
        rowsY.snap(HERO_ROWS_Y);
    }

    @Override
    void onShow() {
        nav.focus = nav.current;
        build();
    }

    @Override
    void dataChanged(boolean addonsChanged) {
        build();
    }

    private void build() {
        if (builtFor != tv.store.generation()) {
            builtFor = tv.store.generation();
            rows.clear();
            for (Store.Catalog c : tv.store.homeCatalogs()) {
                String label = c.name + (c.typeLabel().isEmpty() || c.name.toLowerCase().contains(c.typeLabel().toLowerCase()) ? "" : " " + c.typeLabel());
                rows.add(new Row(label, c));
            }
            focusRow = null;
            if (zone >= 0) zone = HERO;
        }
        // Continue Watching (refreshed after playback).
        List<Store.Meta> list = tv.store.continueWatching();
        if (cw == null) cw = new Row("Continue Watching", null);
        String keepId = cw.focused() != null ? cw.focused().id : null;
        String firstBefore = cw.items.isEmpty() ? null : cw.items.get(0).id;
        cw.items.clear();
        cw.items.addAll(list);
        // Something new was watched: it's first now, start the row there.
        if (!list.isEmpty() && !list.get(0).id.equals(firstBefore)) keepId = list.get(0).id;
        cw.done = true;
        cw.requested = true;
        if (keepId != null) {
            for (int i = 0; i < cw.items.size(); i++) if (cw.items.get(i).id.equals(keepId)) cw.focus = i;
        }
        cw.focus = Math.min(cw.focus, Math.max(0, cw.items.size() - 1));
        cw.scroll.snap(cw.focus * Row.STEP);
        // The banner shows the first catalog's first item that has artwork.
        if (hero == null) {
            for (Row r : rows) {
                for (Store.Meta m : r.items) {
                    if (m.background != null) {
                        hero = m;
                        break;
                    }
                }
                if (hero != null || !r.items.isEmpty()) break;
            }
        }
        loadNear();
    }

    private List<Row> shown() {
        List<Row> out = new ArrayList<>();
        if (cw != null && !cw.items.isEmpty()) out.add(cw);
        for (Row r : rows) if (r.visible()) out.add(r);
        return out;
    }

    private int focusIndex(List<Row> list) {
        int i = focusRow == null ? -1 : list.indexOf(focusRow);
        return i;
    }

    /** Load the rows on screen and the next few. */
    private void loadNear() {
        List<Row> list = shown();
        int f = Math.max(0, focusIndex(list));
        for (int i = 0; i < list.size(); i++) {
            Row r = list.get(i);
            if (!r.requested && i <= f + 3) r.load(tv);
        }
    }

    static String backgroundOf(Store.Meta m) {
        if (m == null) return null;
        if (m.background != null) return m.background;
        if (m.id != null && m.id.startsWith("tt")) return "https://images.metahub.space/background/medium/" + m.id + "/img";
        return m.poster;
    }

    static String logoOf(Store.Meta m) {
        if (m == null) return null;
        if (m.logo != null) return m.logo;
        if (m.id != null && m.id.startsWith("tt")) return "https://images.metahub.space/logo/medium/" + m.id + "/img";
        return null;
    }

    @Override
    void draw(Canvas c, float dt) {
        if (hero == null) build();
        List<Row> list = shown();
        int fi = focusIndex(list);
        if (zone >= 0 && fi < 0) {
            zone = list.isEmpty() ? HERO : 0;
            focusRow = list.isEmpty() ? null : list.get(0);
            fi = focusRow == null ? -1 : 0;
            if (focusRow == null) zone = HERO;
        }
        Store.Meta featured = zone >= 0 && focusRow != null ? focusRow.focused() : hero;
        backdrop.show(backgroundOf(featured), zone >= 0 ? 380 : 0);
        backdrop.draw(c, dt, true);

        if (zone >= 0 && featured != null) {
            drawInfo(c, featured, 150, false);
        } else if (hero != null) {
            drawInfo(c, hero, 200, true);
        } else if (list.isEmpty() || !tv.store.hasAddons()) {
            tv.drawLogo(c, (TvView.W - TvView.LOGO_W) / 2, 470);
            tv.spinner(c, TvView.W / 2, 600, 34, dt);
        }

        rowsY.to(zone >= 0 ? ROWS_Y - Math.max(0, fi) * Row.HEIGHT : HERO_ROWS_Y);
        float y0 = step(rowsY, dt);
        for (int i = 0; i < list.size(); i++) {
            float y = y0 + i * Row.HEIGHT;
            if (y > TvView.H || y + Row.HEIGHT < 0) continue;
            // Rows above the focused one slide up behind the info and fade out.
            if (zone >= 0 && y + Row.HEIGHT < ROWS_Y) continue;
            // (A clip, not a translucent layer: an offscreen layer per frame is costly on TV GPUs.)
            boolean clip = zone >= 0 && y < ROWS_Y - 20;
            if (clip) {
                c.save();
                c.clipRect(0, ROWS_Y - 34, TvView.W, TvView.H);
            }
            list.get(i).draw(this, c, y, zone >= 0 && i == fi, dt);
            if (clip) c.restore();
        }
        // Keep the top bar readable over the picture.
        nav.draw(c, zone == NAV);
    }

    private final RectF lr = new RectF();

    /** Title (logo picture when there is one), year/runtime/rating, description, and the banner buttons. */
    private void drawInfo(Canvas c, Store.Meta m, float y, boolean banner) {
        float x = TvView.MARGIN;
        int lw = banner ? 680 : 560, lh = banner ? 220 : 160;
        String logoUrl = logoOf(m);
        Bitmap logo = logoUrl != null ? tv.images.logo(logoUrl, Math.round(lw * tv.scale()), Math.round(lh * tv.scale())) : null;
        if (logo != null) {
            float w = logo.getWidth() / tv.scale(), h = logo.getHeight() / tv.scale();
            lr.set(x, y + lh - h, x + w, y + lh);
            c.drawBitmap(logo, null, lr, tv.bmp);
        } else if (logoUrl == null || tv.images.isFailed(logoUrl)) {
            tv.tHero.setTextSize(banner ? 76 : 60);
            tv.drawWrapped(c, m.name, tv.tHero, x, y + lh - (banner ? 180 : 150), 1000, 2);
            tv.tHero.setTextSize(64);
        }
        float ty = y + lh + 52;
        String meta = m.metaLine();
        String genres = m.genreLine();
        if (!genres.isEmpty()) meta = meta.isEmpty() ? genres : meta + "  ·  " + genres;
        c.drawText(tv.fit(meta, tv.tMeta, 1100), x, ty, tv.tMeta);
        ty += 22;
        tv.drawWrapped(c, m.description, tv.tBody, x, ty, banner ? 860 : 900, banner ? 3 : 2);
        if (banner) {
            float by = y + lh + 210;
            float w = tv.drawButton(c, x, by, "Play", true, zone == HERO && heroButton == 0, true);
            tv.drawButton(c, x + w + 20, by, "More info", false, zone == HERO && heroButton == 1, false);
        }
    }

    @Override
    boolean key(int k, KeyEvent e) {
        List<Row> list = shown();
        int fi = focusIndex(list);
        if (zone == NAV) {
            if (k == TvView.DOWN) {
                zone = hero != null ? HERO : (list.isEmpty() ? NAV : 0);
                if (zone == 0) focusRow = list.get(0);
                return true;
            }
            return nav.key(k);
        }
        if (zone == HERO) {
            switch (k) {
                case TvView.UP:
                    zone = NAV;
                    nav.focus = NavBar.HOME;
                    return true;
                case TvView.DOWN:
                    if (list.isEmpty()) return true;
                    zone = 0;
                    focusRow = list.get(0);
                    focusRow.grow.snap(0);
                    focusRow.grow.to(1);
                    loadNear();
                    return true;
                case TvView.LEFT:
                    heroButton = 0;
                    return true;
                case TvView.RIGHT:
                    heroButton = 1;
                    return true;
                case TvView.OK:
                case TvView.PLAY:
                    if (hero == null) return true;
                    if (heroButton == 0 && "movie".equals(hero.type) || k == TvView.PLAY && "movie".equals(hero.type)) tv.host.play(hero, null);
                    else tv.openDetail(hero);
                    return true;
                default:
                    return false;
            }
        }
        if (focusRow == null) return false;
        switch (k) {
            case TvView.UP:
                if (fi > 0) {
                    setRow(list.get(fi - 1));
                } else {
                    zone = hero != null ? HERO : NAV;
                    focusRow = null;
                }
                return true;
            case TvView.DOWN:
                if (fi < list.size() - 1) setRow(list.get(fi + 1));
                return true;
            case TvView.LEFT:
                focusRow.move(-1);
                return true;
            case TvView.RIGHT:
                if (focusRow.move(1)) focusRow.maybeMore(tv);
                return true;
            case TvView.OK: {
                Store.Meta m = focusRow.focused();
                if (m != null) tv.openDetail(m);
                return true;
            }
            case TvView.PLAY: {
                Store.Meta m = focusRow.focused();
                if (m == null) return true;
                if ("movie".equals(m.type)) tv.host.play(m, null);
                else tv.openDetail(m);
                return true;
            }
            default:
                return false;
        }
    }

    private void setRow(Row r) {
        focusRow = r;
        r.prevFocus = -1;
        r.grow.snap(0);
        r.grow.to(1);
        zone = 0;
        loadNear();
    }

    @Override
    boolean back() {
        if (zone >= 0) {
            // First Back returns to the top, like other TV apps; the next one asks to exit.
            zone = hero != null ? HERO : NAV;
            focusRow = null;
            return true;
        }
        if (zone == NAV && hero != null) {
            zone = HERO;
            return true;
        }
        return false;
    }
}
