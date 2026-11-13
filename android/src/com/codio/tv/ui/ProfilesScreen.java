package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

/** "Who's watching?": round avatars in a row, opened from the avatar in the top right. Left/Right pick, OK switches. */
final class ProfilesScreen extends Screen {
    private static final float R = 92, GAP = 70;
    private int focus = -1;

    ProfilesScreen(TvView tv) {
        super(tv);
    }

    private JSONArray profiles() {
        JSONArray l = Profiles.list(tv.host.prefs());
        if (l.length() > 0) return l;
        JSONArray one = new JSONArray();
        JSONObject me = Profiles.current(tv.store, tv.host.prefs());
        if (me != null) one.put(me);
        return one;
    }

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(0xFF0B0B0B);
        tv.drawLogo(c, TvView.MARGIN, NavBar.Y + 2);
        JSONArray l = profiles();
        String email = Profiles.email(tv.store);
        int cur = Profiles.currentIndex(l, email);
        if (l.length() == 1 && email != null) cur = 0;
        if (focus < 0 || focus >= l.length()) focus = Math.max(0, cur);

        tv.tHero.setTextAlign(Paint.Align.CENTER);
        c.drawText("Who's watching?", TvView.W / 2, 330, tv.tHero);
        tv.tHero.setTextAlign(Paint.Align.LEFT);

        if (l.length() == 0) {
            tv.tBody.setTextAlign(Paint.Align.CENTER);
            c.drawText("Sign in first, in Settings.", TvView.W / 2, 560, tv.tBody);
            tv.tBody.setTextAlign(Paint.Align.LEFT);
            return;
        }
        float total = l.length() * R * 2 + (l.length() - 1) * GAP;
        float x = (TvView.W - total) / 2 + R, cy = 560;
        for (int i = 0; i < l.length(); i++) {
            JSONObject p = l.optJSONObject(i);
            boolean f = i == focus;
            float r = f ? R * 1.08f : R;
            if (f) {
                tv.fill.setColor(Color.WHITE);
                c.drawCircle(x, cy, r + 8, tv.fill);
            }
            Profiles.drawAvatar(c, tv.fill, tv.tLabel, x, cy, r, p);
            TextPaintSwap name = new TextPaintSwap(f || i == cur ? Color.WHITE : 0xFF8C8C8C);
            c.drawText(tv.fit(Profiles.name(p), tv.tLabel, R * 2 + GAP - 10), x, cy + R + 70, tv.tLabel);
            if (i == cur) {
                tv.tSmall.setTextAlign(Paint.Align.CENTER);
                c.drawText("Watching", x, cy + R + 110, tv.tSmall);
                tv.tSmall.setTextAlign(Paint.Align.LEFT);
            }
            name.restore();
            x += R * 2 + GAP;
        }
        if (Profiles.list(tv.host.prefs()).length() == 0) {
            tv.tSmall.setTextAlign(Paint.Align.CENTER);
            c.drawText("Make more profiles on the iPhone. They show up here on their own.", TvView.W / 2, TvView.H - 120, tv.tSmall);
            tv.tSmall.setTextAlign(Paint.Align.LEFT);
        }
    }

    /** Centres tLabel in a colour for one name, then puts it back. */
    private final class TextPaintSwap {
        private final int color;

        TextPaintSwap(int c) {
            color = tv.tLabel.getColor();
            tv.tLabel.setColor(c);
            tv.tLabel.setTextAlign(Paint.Align.CENTER);
        }

        void restore() {
            tv.tLabel.setColor(color);
            tv.tLabel.setTextAlign(Paint.Align.LEFT);
        }
    }

    @Override
    boolean key(int k, KeyEvent e) {
        JSONArray l = profiles();
        switch (k) {
            case TvView.LEFT:
                if (focus > 0) focus--;
                return true;
            case TvView.RIGHT:
                if (focus < l.length() - 1) focus++;
                return true;
            case TvView.OK:
                if (focus >= 0 && focus < l.length()) {
                    final TvView v = tv;
                    Profiles.switchTo(tv.store, l.optJSONObject(focus), new Profiles.Ui() {
                        @Override
                        public void ctx(String name, Object args) {
                            v.ctx(name, args);
                        }

                        @Override
                        public void toast(String text) {
                            v.toast(text);
                        }
                    });
                }
                tv.pop();
                return true;
            case TvView.UP:
            case TvView.DOWN:
                return true;
            default:
                return false;
        }
    }
}
