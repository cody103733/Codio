package com.codio.tv.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.KeyEvent;

/**
 * Covers the web movie page while Smart auto-play looks for a source and the player starts, so the viewer stays in
 * Codio's own screens until the picture appears.
 */
final class LoadingScreen extends Screen {
    private final Store.Meta meta;
    private final String videoId;
    private final Backdrop backdrop;
    private String status = "";
    private boolean failed = false;
    private final RectF lr = new RectF();

    LoadingScreen(TvView tv, Store.Meta meta, String videoId, String quality) {
        super(tv);
        this.meta = meta;
        this.videoId = videoId;
        this.backdrop = new Backdrop(tv);
        status = "Finding the best " + (quality != null ? quality + " " : "") + "version…";
    }

    void setStatus(String kind, String text) {
        failed = "failed".equals(kind);
        if ("starting".equals(kind)) status = text != null && !text.isEmpty() ? text : "Starting…";
        else if ("searching".equals(kind) && text != null && !text.isEmpty()) status = text;
        else if (failed) status = text != null && !text.isEmpty() ? text : "Couldn't find a source.";
        else if ("player".equals(kind)) status = "Starting playback…";
        tv.kick();
    }

    @Override
    void draw(Canvas c, float dt) {
        backdrop.show(HomeScreen.backgroundOf(meta), 0);
        backdrop.draw(c, dt, true);
        tv.fill.setColor(0x80000000);
        c.drawRect(0, 0, TvView.W, TvView.H, tv.fill);
        float x = TvView.MARGIN, y = 300;
        String logoUrl = HomeScreen.logoOf(meta);
        Bitmap logo = logoUrl != null ? tv.images.logo(logoUrl, Math.round(640 * tv.scale()), Math.round(200 * tv.scale())) : null;
        if (logo != null) {
            float w = logo.getWidth() / tv.scale(), h = logo.getHeight() / tv.scale();
            lr.set(x, y + 200 - h, x + w, y + 200);
            c.drawBitmap(logo, null, lr, tv.bmp);
        } else if (logoUrl == null || tv.images.isFailed(logoUrl)) {
            tv.tHero.setTextSize(72);
            tv.drawWrapped(c, meta.name, tv.tHero, x, y + 30, 1100, 2);
            tv.tHero.setTextSize(64);
        }
        String sub = episodeLabel();
        if (sub != null) c.drawText(tv.fit(sub, tv.tMeta, 1100), x, y + 250, tv.tMeta);
        float sy = y + 330;
        if (!failed) {
            tv.spinner(c, x + 26, sy, 24, dt);
            c.drawText(tv.fit(status, tv.tLabel, 1200), x + 74, sy + 10, tv.tLabel);
            c.drawText("Back to cancel", x, sy + 80, tv.tSmall);
        } else {
            c.drawText(tv.fit(status, tv.tLabel, 1400), x, sy + 10, tv.tLabel);
            float w = tv.drawButton(c, x, sy + 50, "Choose a source", false, true, false);
            c.drawText("Back to return", x + w + 30, sy + 96, tv.tSmall);
        }
        tv.tLabel.setColor(Color.WHITE);
    }

    private String episodeLabel() {
        if (videoId == null || videoId.equals(meta.id)) return null;
        for (Store.Video v : meta.videos) {
            if (videoId.equals(v.id)) return "S" + v.season + " E" + v.episode + (v.title.isEmpty() ? "" : "  ·  " + v.title);
        }
        return null;
    }

    @Override
    boolean key(int k, KeyEvent e) {
        if (failed && k == TvView.OK) {
            tv.host.cancelPlay();
            tv.openSources(meta, videoId != null ? videoId : meta.id);
        }
        return true; // nothing else reaches the page underneath
    }

    @Override
    boolean back() {
        tv.host.cancelPlay(); // also takes this screen away
        return true;
    }
}
