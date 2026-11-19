package com.codio.tv.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/** Library > Calendar on the TV: the days ahead with the episodes and movies that come out, then the past week. */
final class CalendarScreen extends Screen {
    private static final float X = TvView.MARGIN, LIST_Y = 230, ROW_H = 140, HEAD_H = 84, W = TvView.W - 2 * TvView.MARGIN;
    private static final float POSTER_W = 80, POSTER_H = 120;

    /** One line of the list: a day heading (item == null) or a row. */
    private static final class Line {
        String heading;
        ReleaseCalendar.Item item;
        boolean past;
        float y;
    }

    private final List<Line> lines = new ArrayList<>();
    private final List<Integer> rows = new ArrayList<>();
    private int focus = 0;
    private final Smooth scroll = new Smooth(0.1f, 0.4f);
    private final RectF r = new RectF();
    private final Runnable changed = new Runnable() {
        @Override
        public void run() {
            fill();
            tv.kick();
        }
    };

    CalendarScreen(TvView tv) {
        super(tv);
        fill();
    }

    private void fill() {
        String keep = focusedId();
        lines.clear();
        rows.clear();
        float y = 0;
        for (ReleaseCalendar.Section s : ReleaseCalendar.sections()) {
            Line h = new Line();
            h.heading = s.heading;
            h.y = y;
            lines.add(h);
            y += HEAD_H;
            for (ReleaseCalendar.Item i : s.items) {
                Line l = new Line();
                l.item = i;
                l.past = "past".equals(s.day);
                l.y = y;
                rows.add(lines.size());
                lines.add(l);
                y += ROW_H;
            }
        }
        focus = 0;
        if (keep != null) {
            for (int k = 0; k < rows.size(); k++) if (keep.equals(id(lines.get(rows.get(k)).item))) focus = k;
        }
    }

    private static String id(ReleaseCalendar.Item i) {
        return i.showId + "|" + i.videoId;
    }

    private String focusedId() {
        return focus < rows.size() ? id(lines.get(rows.get(focus)).item) : null;
    }

    @Override
    void onShow() {
        ReleaseCalendar.listen(changed);
        ReleaseCalendar.refresh(tv.store, false);
        fill();
    }

    @Override
    void onHide() {
        ReleaseCalendar.unlisten(changed);
    }

    @Override
    void dataChanged(boolean addonsChanged) {
        ReleaseCalendar.refresh(tv.store, false);
    }

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(Theme.BG);
        c.drawText("Calendar", X, 120, tv.tTitle);
        int count = rows.size();
        c.drawText(count == 0 ? "" : "New episodes and movies for your Library and the shows you watch", X, 170, tv.tMeta);
        if (ReleaseCalendar.loading()) tv.spinner(c, TvView.W - TvView.MARGIN - 24, 110, 22, dt);
        if (lines.isEmpty()) {
            boolean busy = ReleaseCalendar.loading() || !ReleaseCalendar.loaded();
            c.drawText(busy ? "Looking for release dates…"
                    : "Nothing coming up. New episodes and movies for what's in your Library show up here.", X, LIST_Y + 60, tv.tBody);
            return;
        }
        float target = 0;
        if (focus < rows.size()) target = Math.max(0, lines.get(rows.get(focus)).y - 3 * ROW_H);
        // Keep the day heading over the first row in sight.
        if (focus == 0) target = 0;
        scroll.to(target);
        float sy = step(scroll, dt);
        c.save();
        c.clipRect(0, LIST_Y - 20, TvView.W, TvView.H);
        for (int k = 0; k < lines.size(); k++) {
            Line l = lines.get(k);
            float y = LIST_Y + l.y - sy;
            if (y + ROW_H < LIST_Y - 20) continue;
            if (y > TvView.H) break;
            if (l.item == null) {
                tv.tLabel.setColor(Color.WHITE);
                c.drawText(l.heading, X, y + 52, tv.tLabel);
            } else {
                drawRow(c, l, y, focus < rows.size() && rows.get(focus) == k);
            }
        }
        c.restore();
    }

    private void drawRow(Canvas c, Line l, float y, boolean focused) {
        ReleaseCalendar.Item i = l.item;
        float h = ROW_H - 12;
        r.set(X, y, X + W, y + h);
        tv.fill.setColor(focused ? Color.WHITE : Theme.CARD);
        c.drawRoundRect(r, 10, 10, tv.fill);
        float px = X + 14, py = y + (h - POSTER_H) / 2;
        Bitmap b = i.poster != null ? tv.images.get(i.poster, Math.round(POSTER_W * tv.scale()), Math.round(POSTER_H * tv.scale()), 8 * tv.scale()) : null;
        r.set(px, py, px + POSTER_W, py + POSTER_H);
        if (b != null) {
            c.drawBitmap(b, null, r, tv.bmp);
        } else {
            tv.fill.setColor(Theme.RAISED);
            c.drawRoundRect(r, 8, 8, tv.fill);
        }
        float tx = px + POSTER_W + 28, tw = W - (tx - X) - 300;
        tv.tLabel.setColor(focused ? Color.BLACK : Color.WHITE);
        c.drawText(tv.fit(i.name, tv.tLabel, tw), tx, y + h / 2 - 8, tv.tLabel);
        tv.tLabel.setColor(Color.WHITE);
        tv.tSmall.setColor(focused ? Theme.ON_FOCUS_DIM : Theme.TEXT_DIM);
        c.drawText(tv.fit(i.line(), tv.tSmall, tw), tx, y + h / 2 + 34, tv.tSmall);
        String right = l.past ? ReleaseCalendar.shortDay(i.day) : "movie".equals(i.type) ? "Movie" : null;
        if (right != null) {
            float rw = tv.tSmall.measureText(right);
            tv.tSmall.setColor(focused ? Theme.ON_FOCUS_DIM : Theme.TEXT_FAINT);
            c.drawText(right, X + W - 32 - rw, y + h / 2 + 10, tv.tSmall);
        }
        tv.tSmall.setColor(Theme.TEXT_DIM);
    }

    @Override
    boolean key(int k, KeyEvent e) {
        switch (k) {
            case TvView.UP:
                if (focus > 0) focus--;
                return true;
            case TvView.DOWN:
                if (focus < rows.size() - 1) focus++;
                return true;
            case TvView.OK:
                if (focus < rows.size()) tv.openDetail(lines.get(rows.get(focus)).item.meta(tv.store));
                return true;
            default:
                return k == TvView.LEFT || k == TvView.RIGHT;
        }
    }
}
