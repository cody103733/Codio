package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/** A short list of things to do with a song, an album, an artist or an addon. OK picks one, Back leaves. */
final class MusicMenu extends Screen {
    private final String title, sub;
    private final List<String> labels = new ArrayList<String>();
    private final List<Runnable> actions = new ArrayList<Runnable>();
    private int focus;
    private final RectF r = new RectF();

    MusicMenu(TvView tv, String title, String sub) {
        super(tv);
        this.title = title;
        this.sub = sub;
    }

    MusicMenu add(String label, Runnable action) {
        labels.add(label);
        actions.add(action);
        return this;
    }

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(Theme.BG);
        float w = 760, x = (TvView.W - w) / 2, rowH = 84;
        float y = Math.max(120, (TvView.H - (150 + labels.size() * rowH)) / 2);
        c.drawText(tv.fit(title, tv.tTitle, w), x, y + 40, tv.tTitle);
        if (sub != null) c.drawText(tv.fit(sub, tv.tBody, w), x, y + 84, tv.tBody);
        y += 130;
        for (int i = 0; i < labels.size(); i++) {
            r.set(x, y + i * rowH, x + w, y + i * rowH + rowH - 10);
            boolean f = i == focus;
            tv.fill.setColor(f ? Theme.FOCUS : Theme.CARD);
            c.drawRoundRect(r, 12, 12, tv.fill);
            tv.tLabel.setColor(f ? Theme.ON_FOCUS : Color.WHITE);
            c.drawText(labels.get(i), x + 30, r.centerY() + 10, tv.tLabel);
        }
        tv.tLabel.setColor(Color.WHITE);
    }

    @Override
    boolean key(int k, KeyEvent e) {
        if (k == TvView.UP && focus > 0) focus--;
        else if (k == TvView.DOWN && focus < labels.size() - 1) focus++;
        else if (k == TvView.OK) {
            Runnable a = actions.get(focus);
            tv.pop();
            a.run();
        } else if (TvMusicKit.mediaKey(k)) return true;
        return true;
    }
}
