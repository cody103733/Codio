package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Settings, in sections: Up/Down move, Left/Right change a value, the panel on the right explains the focused one. */
final class SettingsScreen extends Screen {
    private static final float LIST_X = TvView.MARGIN, LIST_Y = 190, ROW_H = 78, HEAD_H = 84, LIST_W = 1060;

    private abstract class Item {
        final String label, help;

        Item(String label, String help) {
            this.label = label;
            this.help = help;
        }

        abstract String value();

        /** Left (-1) or Right (1) on a row that cycles through values. */
        void change(int dir) {}

        void ok() {
            change(1);
        }

        boolean cycles() {
            return false;
        }
    }

    /** One of the shared choice settings (see Setting). */
    private final class Choice extends Item {
        final Setting s;

        Choice(Setting s) {
            super(s.label, s.help);
            this.s = s;
        }

        @Override
        String value() {
            return s.value(tv.store, tv.host.prefs());
        }

        @Override
        void change(int dir) {
            if (!s.set(s.stepped(tv.store, tv.host.prefs(), dir), tv.store, tv.host)) tv.toast(Setting.LOADING);
        }

        @Override
        void ok() {
            if (!s.slider) change(1);
        }

        @Override
        boolean cycles() {
            return true;
        }
    }

    /** Sign in / sign out. */
    private final class Account extends Item {
        private long confirmUntil;

        Account() {
            super("Account", "Signed in, your library, addons and settings are kept in your account and synced to your other devices.");
        }

        @Override
        String value() {
            String e = Profiles.email(tv.store);
            if (e == null) return "Not signed in · Sign in";
            if (SystemClock.uptimeMillis() < confirmUntil) return "Press OK again to sign out";
            return e;
        }

        @Override
        void ok() {
            if (Profiles.email(tv.store) == null) {
                signIn();
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now < confirmUntil) {
                confirmUntil = 0;
                tv.ctx("Logout", null);
            } else {
                confirmUntil = now + 4000;
            }
        }
    }

    private void signIn() {
        tv.push(new TextEntryScreen(tv, "Sign in: email", null, null, false, new TextEntryScreen.Done() {
            @Override
            public void done(final String email) {
                if (email.isEmpty()) return;
                tv.push(new TextEntryScreen(tv, "Sign in: password", email, null, true, new TextEntryScreen.Done() {
                    @Override
                    public void done(String password) {
                        if (password.isEmpty()) return;
                        tv.ctx("Authenticate", Core.login(email, password));
                        tv.toast("Signing in…");
                    }
                }));
            }
        }));
    }

    private final NavBar nav;
    /** Section headings (String) and the items under them. */
    private final List<Object> rows = new ArrayList<>();
    private float[] tops;
    private float height;
    private int focus;
    private boolean navFocused = false;
    private final Smooth scroll = new Smooth(0.1f, 0.4f);
    private final RectF r = new RectF();

    SettingsScreen(TvView tv) {
        super(tv);
        nav = new NavBar(tv, NavBar.SETTINGS);
        String section = null;
        for (Setting s : Setting.all(false)) {
            if (!s.section.equals(section)) rows.add(section = s.section);
            rows.add(new Choice(s));
        }
        rows.add(Setting.ACCOUNT);
        rows.add(new Account());
        rows.add(new Item("Who's watching", "Each profile has its own library, Continue Watching and addons. Press OK to pick who's watching. Profiles are made on the iPhone and show up here on their own.") {
            @Override
            String value() {
                JSONObject p = Profiles.current(SettingsScreen.this.tv.store, SettingsScreen.this.tv.host.prefs());
                return p != null ? Profiles.name(p) + "  ›" : "Sign in first";
            }

            @Override
            void ok() {
                SettingsScreen.this.tv.push(new ProfilesScreen(SettingsScreen.this.tv));
            }
        });
        rows.add(new Item("Cast photos (TMDB key)", "Actors' photos and characters come from TMDB with your own free API key (themoviedb.org, Settings, API). Press OK to type it in. It's saved with your account, so your other devices get it. Without a key, the cast shows names only.") {
            @Override
            String value() {
                return CastInfo.key() != null ? "Key saved" : "Add key";
            }

            @Override
            void ok() {
                final TvView tv = SettingsScreen.this.tv;
                tv.push(new TextEntryScreen(tv, "TMDB API key", "32 letters and numbers", CastInfo.key(), false, new TextEntryScreen.Done() {
                    @Override
                    public void done(String text) {
                        String k = text.trim();
                        if (!k.isEmpty() && !CastInfo.looksLikeKey(k)) {
                            tv.toast(CastInfo.BAD_KEY);
                            return;
                        }
                        CastInfo.saveKey(tv.host.prefs(), tv.store, k);
                        tv.toast(k.isEmpty() ? "TMDB key removed" : "TMDB key saved");
                    }
                }));
            }
        });
        rows.add(Setting.ABOUT);
        rows.add(new Item("Updates", "Codio checks for a newer version when it starts and offers to install it. Press OK to check now.") {
            @Override
            String value() {
                return "Check now";
            }

            @Override
            void ok() {
                SettingsScreen.this.tv.host.checkUpdates();
            }
        });
        tops = new float[rows.size()];
        float y = 0;
        for (int i = 0; i < rows.size(); i++) {
            tops[i] = y;
            y += rows.get(i) instanceof Item ? ROW_H : HEAD_H;
        }
        height = y;
        focus = next(-1, 1);
    }

    /** The next item from index i in direction dir, skipping headings; i itself when there is none. */
    private int next(int i, int dir) {
        for (int n = i + dir; n >= 0 && n < rows.size(); n += dir) if (rows.get(n) instanceof Item) return n;
        return i;
    }

    private Item focused() {
        return (Item) rows.get(focus);
    }

    @Override
    void onShow() {
        nav.focus = nav.current;
    }

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(Theme.BG);
        float visible = TvView.H - LIST_Y;
        // The focused row stays about two thirds of the way down, the section heading above the first row in view.
        scroll.to(Math.max(0, Math.min(height - visible + 40, tops[focus] - visible * 0.62f)));
        float sy = step(scroll, dt);
        c.save();
        c.clipRect(0, LIST_Y - 20, TvView.W, TvView.H);
        for (int i = 0; i < rows.size(); i++) {
            float y = LIST_Y + tops[i] - sy;
            if (y > TvView.H || y + HEAD_H < LIST_Y - 20) continue;
            Object o = rows.get(i);
            if (!(o instanceof Item)) {
                c.drawText((String) o, LIST_X, y + 58, tv.tTitle);
                continue;
            }
            Item it = (Item) o;
            boolean f = !navFocused && i == focus;
            r.set(LIST_X, y, LIST_X + LIST_W, y + ROW_H - 10);
            tv.fill.setColor(f ? Theme.FOCUS : Theme.CARD);
            c.drawRoundRect(r, 10, 10, tv.fill);
            tv.tLabel.setColor(f ? Theme.ON_FOCUS : Theme.TEXT);
            c.drawText(it.label, LIST_X + 30, y + 44, tv.tLabel);
            String v = it.value();
            tv.tNav.setColor(f ? Theme.ON_FOCUS_DIM : Theme.TEXT_DIM);
            float vw = tv.tNav.measureText(v);
            float vx = LIST_X + LIST_W - 30 - vw - (f && it.cycles() ? 30 : 0);
            c.drawText(v, vx, y + 44, tv.tNav);
            if (f && it.cycles()) {
                c.drawText("‹", vx - 30, y + 44, tv.tNav);
                c.drawText("›", LIST_X + LIST_W - 40, y + 44, tv.tNav);
            }
        }
        c.restore();
        tv.tLabel.setColor(Theme.TEXT);
        tv.tNav.setColor(Theme.TEXT_DIM);
        float hx = LIST_X + LIST_W + 70;
        if (!navFocused) {
            Item it = focused();
            c.drawText(it.label, hx, LIST_Y + 44, tv.tLabel);
            tv.drawWrapped(c, it.help, tv.tBody, hx, LIST_Y + 70, TvView.W - hx - TvView.MARGIN, 8);
        }
        c.drawText("Codio " + tv.host.version() + " for Android TV", hx, TvView.H - 60, tv.tSmall);
        nav.draw(c, navFocused);
    }

    @Override
    boolean key(int k, KeyEvent e) {
        if (navFocused) {
            if (k == TvView.DOWN) {
                navFocused = false;
                return true;
            }
            return nav.key(k);
        }
        Item it = focused();
        switch (k) {
            case TvView.UP: {
                int n = next(focus, -1);
                if (n != focus) focus = n;
                else {
                    navFocused = true;
                    nav.focus = nav.current;
                }
                return true;
            }
            case TvView.DOWN:
                focus = next(focus, 1);
                return true;
            case TvView.LEFT:
                if (it.cycles()) it.change(-1);
                return true;
            case TvView.RIGHT:
                if (it.cycles()) it.change(1);
                return true;
            case TvView.OK:
                it.ok();
                return true;
            default:
                return false;
        }
    }

    @Override
    boolean back() {
        if (!navFocused) {
            navFocused = true;
            nav.focus = nav.current;
            return true;
        }
        return false;
    }
}
