package com.codio.tv.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * For You on the TV (a section in the top bar, after Home): Home's layout, with the focused pick's picture and info
 * on top, a red line saying why it's there, and the rows below. Picks come from ForYou (DESIGN.md "For You").
 */
final class ForYouScreen extends Screen {
    private static final int NAV = -2, HEAD = -1;
    private static final float ROWS_Y = 548;

    private final NavBar nav;
    private final Backdrop backdrop;
    private final List<Row> rows = new ArrayList<>();
    private ForYou.State state = ForYou.State.LOADING;
    private int zone = HEAD;
    private int focusRow = 0;
    private final Smooth rowsY = new Smooth(0.1f, 0.4f);

    ForYouScreen(TvView tv) {
        super(tv);
        nav = new NavBar(tv, NavBar.FOR_YOU);
        backdrop = new Backdrop(tv);
        rowsY.snap(ROWS_Y);
        if (CastInfo.key() == null) state = ForYou.State.NO_KEY;
        else {
            List<ForYou.PickRow> saved = ForYou.cached(tv.host.prefs());
            if (saved != null && !saved.isEmpty()) setRows(saved);
        }
        refresh(false);
    }

    @Override
    void onShow() {
        nav.focus = nav.current;
    }

    private void refresh(boolean force) {
        if (CastInfo.key() == null) {
            state = ForYou.State.NO_KEY;
            return;
        }
        if (!force && !ForYou.needsBuild(tv.host.prefs(), tv.store)) return;
        if (force) tv.toast("Finding new picks…");
        if (rows.isEmpty()) state = ForYou.State.LOADING;
        ForYou.build(tv.host.prefs(), tv.store, new ForYou.Done() {
            @Override
            public void done(ForYou.State s, List<ForYou.PickRow> got) {
                if (got != null && !got.isEmpty()) setRows(got);
                else if (rows.isEmpty()) state = s;
                tv.dataChanged(false);
            }
        });
    }

    private void setRows(List<ForYou.PickRow> list) {
        rows.clear();
        for (ForYou.PickRow p : list) {
            Row r = new Row(p.title, null);
            r.items.addAll(p.items);
            r.done = true;
            r.requested = true;
            rows.add(r);
        }
        state = ForYou.State.OK;
        focusRow = 0;
        if (zone >= 0) zone = 0;
    }

    private Store.Meta current() {
        if (rows.isEmpty()) return null;
        Row r = rows.get(Math.max(0, Math.min(focusRow, rows.size() - 1)));
        return zone >= 0 ? r.focused() : rows.get(0).focused();
    }

    @Override
    void draw(Canvas c, float dt) {
        Store.Meta m = current();
        backdrop.show(HomeScreen.backgroundOf(m), zone >= 0 ? 380 : 0);
        backdrop.draw(c, dt, true);
        if (rows.isEmpty()) {
            drawMessage(c, dt);
        } else {
            if (m != null) drawInfo(c, m, 150);
            rowsY.to(ROWS_Y - (zone >= 0 ? focusRow : 0) * Row.HEIGHT);
            float y0 = step(rowsY, dt);
            for (int i = 0; i < rows.size(); i++) {
                float y = y0 + i * Row.HEIGHT;
                if (y > TvView.H || y + Row.HEIGHT < ROWS_Y - 20) continue;
                rows.get(i).draw(this, c, y, zone >= 0 && i == focusRow, dt);
            }
        }
        nav.draw(c, zone == NAV);
    }

    private void drawMessage(Canvas c, float dt) {
        float cx = TvView.W / 2f;
        tv.tHero.setTextSize(56);
        float tw = tv.tHero.measureText("For You");
        c.drawText("For You", cx - tw / 2, 420, tv.tHero);
        tv.tHero.setTextSize(64);
        String text;
        switch (state) {
            case NO_KEY:
                text = "For You uses TMDB to find titles like the ones you watch. Add your free TMDB key in Settings (Cast photos) to turn it on.";
                break;
            case NO_HISTORY:
                text = "Watch a few movies or shows and your picks show up here.";
                break;
            case FAILED:
                text = "Couldn't reach TMDB for your picks. Check the connection and try again.";
                break;
            default:
                text = "Finding titles that fit what you watch…";
                tv.spinner(c, cx, 640, 34, dt);
                break;
        }
        tv.drawWrapped(c, text, tv.tBody, cx - 450, 480, 900, 3);
        if (state == ForYou.State.NO_KEY || state == ForYou.State.FAILED) {
            String label = state == ForYou.State.NO_KEY ? "Open Settings" : "Try again";
            tv.drawButton(c, cx - 130, 640, label, false, zone == HEAD, true);
        }
    }

    private final RectF lr = new RectF();

    /** Why it's here (red), title or logo, year/rating/genres, description. */
    private void drawInfo(Canvas c, Store.Meta m, float y) {
        float x = TvView.MARGIN;
        if (m.reason != null && !m.reason.isEmpty()) {
            tv.tMeta.setColor(Theme.RED);
            c.drawText(tv.fit(m.reason, tv.tMeta, 1100), x, y, tv.tMeta);
            tv.tMeta.setColor(Theme.TEXT_DIM);
        }
        y += 16;
        int lw = 560, lh = 150;
        String logoUrl = HomeScreen.logoOf(m);
        Bitmap logo = logoUrl != null ? tv.images.logo(logoUrl, Math.round(lw * tv.scale()), Math.round(lh * tv.scale())) : null;
        if (logo != null) {
            float w = logo.getWidth() / tv.scale(), h = logo.getHeight() / tv.scale();
            lr.set(x, y + lh - h, x + w, y + lh);
            c.drawBitmap(logo, null, lr, tv.bmp);
        } else if (logoUrl == null || tv.images.isFailed(logoUrl)) {
            tv.tHero.setTextSize(60);
            tv.drawWrapped(c, m.name, tv.tHero, x, y + lh - 140, 1000, 2);
            tv.tHero.setTextSize(64);
        }
        float ty = y + lh + 48;
        String meta = m.metaLine();
        String genres = m.genreLine();
        if (!genres.isEmpty()) meta = meta.isEmpty() ? genres : meta + "  ·  " + genres;
        c.drawText(tv.fit(meta, tv.tMeta, 1100), x, ty, tv.tMeta);
        tv.drawWrapped(c, m.description, tv.tBody, x, ty + 22, 900, 2);
    }

    @Override
    boolean key(int k, KeyEvent e) {
        if (zone == NAV) {
            if (k == TvView.DOWN) {
                zone = rows.isEmpty() ? HEAD : 0;
                return true;
            }
            return nav.key(k);
        }
        if (rows.isEmpty()) {
            if (k == TvView.UP) {
                zone = NAV;
                nav.focus = nav.current;
                return true;
            }
            if (k == TvView.OK) {
                if (state == ForYou.State.NO_KEY) tv.navigate(NavBar.SETTINGS);
                else if (state == ForYou.State.FAILED) refresh(true);
                return true;
            }
            return false;
        }
        if (zone < 0) zone = 0;
        Row r = rows.get(focusRow);
        switch (k) {
            case TvView.UP:
                if (focusRow > 0) setRow(focusRow - 1);
                else {
                    zone = NAV;
                    nav.focus = nav.current;
                }
                return true;
            case TvView.DOWN:
                if (focusRow < rows.size() - 1) setRow(focusRow + 1);
                return true;
            case TvView.LEFT:
                r.move(-1);
                return true;
            case TvView.RIGHT:
                r.move(1);
                return true;
            case TvView.OK: {
                Store.Meta m = r.focused();
                if (m != null) tv.openDetail(m);
                return true;
            }
            case TvView.PLAY: {
                Store.Meta m = r.focused();
                if (m == null) return true;
                if ("movie".equals(m.type)) tv.host.play(m, null);
                else tv.openDetail(m);
                return true;
            }
            default:
                return false;
        }
    }

    private void setRow(int i) {
        focusRow = i;
        Row r = rows.get(i);
        r.prevFocus = -1;
        r.grow.snap(0);
        r.grow.to(1);
    }

    @Override
    boolean back() {
        if (zone >= 0 && focusRow > 0) {
            focusRow = 0;
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
