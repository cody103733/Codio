package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/** "Choose a source": every source the addons found, Smart auto-play's ranking first. */
final class SourcesScreen extends Screen {
    private static final float LIST_X = TvView.MARGIN, LIST_Y = 250, ROW_H = 156, LIST_W = 1728;

    private final Store.Meta meta;
    private final String videoId;
    private final Backdrop backdrop;
    private final List<Store.Source> list = new ArrayList<>();
    private boolean loading = true;
    private int focus = 0;
    private final Smooth scroll = new Smooth(0.09f, 0.4f);
    private final RectF r = new RectF();

    SourcesScreen(TvView tv, Store.Meta meta, String videoId) {
        super(tv);
        this.meta = meta;
        this.videoId = videoId;
        this.backdrop = new Backdrop(tv);
        refresh();
    }

    private void refresh() {
        Store.Sources s = tv.store.sourcesFor(meta.type, meta.id, videoId);
        String keep = focus < list.size() ? list.get(focus).href : null;
        list.clear();
        if (s != null) {
            list.addAll(s.list);
            loading = s.loading;
        }
        focus = 0;
        if (keep != null) {
            for (int i = 0; i < list.size(); i++) if (list.get(i).href.equals(keep)) focus = i;
        }
    }

    @Override
    void dataChanged(boolean addonsChanged) {
        refresh();
    }

    @Override
    void onShow() {
        tv.host.prefetch(meta, videoId);
        refresh();
    }

    private String title() {
        if (videoId != null && !videoId.equals(meta.id)) {
            for (Store.Video v : meta.videos) {
                if (videoId.equals(v.id)) return meta.name + "  ·  S" + v.season + " E" + v.episode + (v.title.isEmpty() ? "" : "  " + v.title);
            }
        }
        return meta.name;
    }

    static String qualityLabel(String q) {
        if (q == null) return null;
        return "2160p".equals(q) ? "4K" : q;
    }

    @Override
    void draw(Canvas c, float dt) {
        backdrop.show(HomeScreen.backgroundOf(meta), 0);
        backdrop.draw(c, dt, true);
        tv.fill.setColor(0xCC000000);
        c.drawRect(0, 0, TvView.W, TvView.H, tv.fill);

        c.drawText(tv.fit(title(), tv.tTitle, 1500), LIST_X, 130, tv.tTitle);
        String sub = list.isEmpty() ? (loading ? "Looking for sources…" : "No sources found.")
                : list.size() + " source" + (list.size() == 1 ? "" : "s") + (loading ? "  ·  still looking…" : "");
        c.drawText(sub, LIST_X, 180, tv.tMeta);
        if (loading) tv.spinner(c, TvView.W - TvView.MARGIN - 24, 150, 22, dt);

        scroll.to(Math.max(0, (focus - 3) * ROW_H));
        float sy = step(scroll, dt);
        c.save();
        c.clipRect(0, LIST_Y - 16, TvView.W, TvView.H);
        int first = Math.max(0, (int) (sy / ROW_H) - 1);
        int last = Math.min(list.size() - 1, first + 10);
        for (int i = first; i <= last; i++) {
            float y = LIST_Y + i * ROW_H - sy;
            if (y > TvView.H) break;
            drawRow(c, list.get(i), y, i == focus);
        }
        c.restore();
    }

    private void drawRow(Canvas c, Store.Source s, float y, boolean focused) {
        // The card and everything in it share one height, and both columns are centred in it, so the white
        // highlight sits exactly around the source and nothing spills past its bottom edge.
        float boxH = ROW_H - 14;
        r.set(LIST_X, y, LIST_X + LIST_W, y + boxH);
        tv.fill.setColor(focused ? Color.WHITE : 0xB3161616);
        c.drawRoundRect(r, 12, 12, tv.fill);
        float x = LIST_X + 28;
        int dim = focused ? 0xFF444444 : 0xFFAAAAAA;

        // Left column: quality badge, with "Best match" / "Instant" under it.
        String q = qualityLabel(s.quality);
        String tag = s.rank == 0 ? "Best match" : s.cached ? "Instant" : null;
        float badgeH = 48, smallCap = tv.tSmall.getTextSize() * 0.72f;
        float colH = (q != null ? badgeH : 0) + (tag != null ? (q != null ? 14 : 0) + smallCap : 0);
        float cy = y + (boxH - colH) / 2;
        if (q != null) {
            float w = Math.max(96, tv.tLabel.measureText(q) + 32);
            r.set(x, cy, x + w, cy + badgeH);
            tv.fill.setColor(focused ? 0xFF111111 : 0x33FFFFFF);
            c.drawRoundRect(r, 8, 8, tv.fill);
            tv.tLabel.setColor(Color.WHITE);
            float capL = tv.tLabel.getTextSize() * 0.72f;
            c.drawText(q, x + (w - tv.tLabel.measureText(q)) / 2, cy + (badgeH + capL) / 2, tv.tLabel);
            cy += badgeH + 14;
        }
        if (tag != null) {
            tv.tSmall.setColor(s.rank == 0 ? (focused ? TvView.RED : 0xFFFF5A5F) : dim);
            c.drawText(tag, x, cy + smallCap, tv.tSmall);
        }

        // Right column: addon and name, then up to two lines of details.
        float tx = x + 140, tw = LIST_W - 196;
        String head = (s.addon != null ? s.addon : "") + (s.name != null ? (s.addon != null ? "  ·  " : "") + s.name.replace('\n', ' ') : "");
        String d = null;
        if (s.description != null) {
            d = s.description.replace("\r", "").replaceAll("\n+", "  ·  ");
            if (s.mbps > 0) d = Math.round(s.mbps) + " Mbit/s  ·  " + d;
        }
        tv.tSmall.setColor(dim);
        float capH = tv.tLabel.getTextSize() * 0.72f;
        float descH = d != null ? tv.wrappedHeight(d, tv.tSmall, tw, 2) : 0;
        // The text layout carries a little air under its last line; leave it out when centring.
        Paint.FontMetrics fm = tv.tSmall.getFontMetrics();
        float above = -fm.ascent - tv.tSmall.getTextSize() * 0.72f;
        float descVis = descH > 0 ? descH - above - tv.tSmall.getTextSize() * 0.3f : 0;
        float block = capH + (descH > 0 ? 10 + descVis : 0);
        float top = y + (boxH - block) / 2;
        tv.tLabel.setColor(focused ? Color.BLACK : Color.WHITE);
        c.drawText(tv.fit(head, tv.tLabel, tw), tx, top + capH, tv.tLabel);
        tv.tLabel.setColor(Color.WHITE);
        if (d != null) {
            // StaticLayout's first line has the font's ascent above the capitals.
            tv.drawWrapped(c, d, tv.tSmall, tx, top + capH + 10 - above, tw, 2);
        }
        tv.tSmall.setColor(0xFFAAAAAA);
    }

    @Override
    boolean key(int k, KeyEvent e) {
        switch (k) {
            case TvView.UP:
                if (focus > 0) focus--;
                return true;
            case TvView.DOWN:
                if (focus < list.size() - 1) focus++;
                return true;
            case TvView.OK:
            case TvView.PLAY:
                if (focus < list.size()) tv.host.playSource(meta, videoId, list.get(focus));
                return true;
            default:
                return k == TvView.LEFT || k == TvView.RIGHT;
        }
    }
}
