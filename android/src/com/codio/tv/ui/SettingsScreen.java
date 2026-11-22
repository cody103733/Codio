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

        String help() {
            return help;
        }

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

    // ------------------------------------------------------------------ sync passcode (ui/Vault)

    private final Runnable syncChanged = new Runnable() {
        @Override
        public void run() {
            tv.kick();
        }
    };

    private void addSyncRows() {
        rows.add(new Item("Sync passcode", Vault.HELP) {
            @Override
            String value() {
                return Vault.label();
            }

            @Override
            String help() {
                return Vault.detail() + "\n\n" + help + (Vault.on() && Vault.problem() != Vault.LOCKED ? "\n\nPress OK to sync now." : "");
            }

            @Override
            void ok() {
                if (!Vault.signedIn()) {
                    tv.toast("Sign in first");
                } else if (!Vault.on() || Vault.problem() == Vault.LOCKED) {
                    askPasscode(Vault.on() ? "New sync passcode" : "Sync passcode", Vault.on() ? "The one set on your other device" : "Same as on your other devices, or a new one", 0);
                } else {
                    Vault.maybeSync(true);
                    tv.toast("Syncing…");
                }
            }
        });
        rows.add(new Item("Change sync passcode", "Change: locks your synced settings with a new passcode, and your other devices ask for it. Start over: forgot the passcode? Replaces the synced settings with this TV's, under a new passcode. Turn off: this TV stops syncing and keeps what it has. Left and Right pick, OK does it.") {
            private int action;
            private long confirmUntil;
            private final String[] names = {"Change passcode", "Start over", "Turn off on this TV"};

            @Override
            String value() {
                if (!Vault.on()) return "Turn sync on first";
                if (action == 2 && SystemClock.uptimeMillis() < confirmUntil) return "Press OK again to turn off";
                return names[action];
            }

            @Override
            boolean cycles() {
                return Vault.on();
            }

            @Override
            void change(int dir) {
                action = (action + dir + names.length) % names.length;
                confirmUntil = 0;
            }

            @Override
            void ok() {
                if (!Vault.on()) {
                    tv.toast("Set a sync passcode first");
                    return;
                }
                if (action == 0) askPasscode("New sync passcode", "At least " + Vault.MIN_PASSCODE + " characters", 1);
                else if (action == 1) askPasscode("Start over: new sync passcode", "Replaces the synced settings with this TV's", 2);
                else {
                    long now = SystemClock.uptimeMillis();
                    if (now < confirmUntil) {
                        confirmUntil = 0;
                        Vault.turnOff();
                        tv.toast("Sync is off on this TV");
                    } else {
                        confirmUntil = now + 4000;
                    }
                }
            }
        });
    }

    /** kind 0 = turn on / unlock, 1 = change, 2 = start over. */
    private void askPasscode(String title, String hint, final int kind) {
        tv.push(new TextEntryScreen(tv, title, hint, null, true, new TextEntryScreen.Done() {
            @Override
            public void done(String text) {
                if (text.trim().isEmpty()) return;
                tv.toast(kind == 1 ? "Changing passcode…" : "Unlocking…");
                Vault.Done done = new Vault.Done() {
                    @Override
                    public void done(String error) {
                        if (error != null) tv.toast(error);
                        else tv.toast(kind == 0 ? "Sync is on" : kind == 1 ? "Passcode changed. Your other devices will ask for it." : "Started over with the new passcode");
                        tv.kick();
                    }
                };
                if (kind == 0) Vault.setPasscode(text, done);
                else if (kind == 1) Vault.changePasscode(text, done);
                else Vault.startOver(text, done);
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
        rows.add("You");
        rows.add(new Item("Your stats", "Hours watched, top shows and movies, and the songs and artists you played most, this year or all time. Codio counts what really plays (not paused time or skipping) on all your devices and keeps the counts with your account. Press OK to see them.") {
            private String cached;
            private long at;

            @Override
            String value() {
                long now = SystemClock.uptimeMillis();
                if (cached == null || now - at > 5000) {
                    int y = Stats.thisYear();
                    cached = Stats.hours(Stats.watch(SettingsScreen.this.tv.store.libraryEntries(), Store.profileId(), y).ms) + " h in " + y + "  ›";
                    at = now;
                }
                return cached;
            }

            @Override
            void ok() {
                SettingsScreen.this.tv.push(new StatsScreen(SettingsScreen.this.tv));
            }
        });
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
        rows.add(new Item("Cast photos (TMDB key)", "Actors' photos and characters come from TMDB with your own free API key (themoviedb.org, Settings, API). Press OK to type it in. With a sync passcode (below), your other devices get it too. Without a key, the cast shows names only.") {
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
        if (CastInfo.key() == null) rows.add(new Item("Get a TMDB key", "Opens themoviedb.org/settings/api, where you make your free key (sign up first if you have no account).") {
            @Override
            String value() {
                return CastInfo.key() != null ? "Key saved" : "Open";
            }

            @Override
            void ok() {
                if (CastInfo.key() != null) SettingsScreen.this.tv.toast("You already have a TMDB key");
                else WebLink.openOnTv(SettingsScreen.this.tv, WebLink.TMDB_KEY);
            }
        });
        addSyncRows();
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
        rows.add(new Item("Legal", Legal.TV_TEXT) {
            @Override
            String value() {
                return "Notices";
            }

            @Override
            void ok() {
                WebLink.openOnTv(SettingsScreen.this.tv, Legal.NOTICE_URL);
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
        Vault.listeners.add(syncChanged);
    }

    @Override
    void onHide() {
        Vault.listeners.remove(syncChanged);
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
        // Grouped like the iPhone's Settings: grey capitals over each section, its rows on one dark rounded card with
        // thin lines between them, the values in red. The focused row turns white.
        for (int i = 0; i < rows.size(); i++) {
            float y = LIST_Y + tops[i] - sy;
            Object o = rows.get(i);
            if (!(o instanceof Item)) {
                int j = i + 1;
                while (j < rows.size() && rows.get(j) instanceof Item) j++;
                float gy = LIST_Y + tops[Math.min(i + 1, rows.size() - 1)] - sy, gb = LIST_Y + tops[j - 1] - sy + ROW_H;
                if (gb < LIST_Y - 20 || y > TvView.H) continue;
                tv.tSmall.setColor(PhoneKit.SECONDARY);
                c.drawText(((String) o).toUpperCase(java.util.Locale.ROOT), LIST_X + 30, gy - 16, tv.tSmall);
                tv.tSmall.setColor(Theme.TEXT_DIM);
                if (j > i + 1) {
                    r.set(LIST_X, gy, LIST_X + LIST_W, gb);
                    tv.fill.setColor(PhoneKit.GROUPED);
                    c.drawRoundRect(r, 14, 14, tv.fill);
                }
                continue;
            }
            if (y > TvView.H || y + ROW_H < LIST_Y - 20) continue;
            Item it = (Item) o;
            boolean f = !navFocused && i == focus;
            if (i > 0 && rows.get(i - 1) instanceof Item) {
                tv.fill.setColor(PhoneKit.SEPARATOR);
                c.drawRect(LIST_X + 30, y, LIST_X + LIST_W, y + 1.5f, tv.fill);
            }
            if (f) {
                r.set(LIST_X, y, LIST_X + LIST_W, y + ROW_H);
                tv.fill.setColor(Theme.FOCUS);
                c.drawRoundRect(r, 14, 14, tv.fill);
            }
            tv.tLabel.setColor(f ? Theme.ON_FOCUS : Theme.TEXT);
            // Regular weight, like the iPhone's settings rows.
            tv.tLabel.setTypeface(tv.regular);
            tv.tNav.setTypeface(tv.regular);
            c.drawText(it.label, LIST_X + 30, y + 49, tv.tLabel);
            String v = it.value();
            tv.tNav.setColor(f ? Theme.ON_FOCUS_DIM : Theme.RED);
            float vw = tv.tNav.measureText(v);
            float vx = LIST_X + LIST_W - 30 - vw - (f && it.cycles() ? 30 : 0);
            c.drawText(v, vx, y + 49, tv.tNav);
            if (f && it.cycles()) {
                c.drawText("\u2039", vx - 30, y + 49, tv.tNav);
                c.drawText("\u203A", LIST_X + LIST_W - 40, y + 49, tv.tNav);
            }
            tv.tLabel.setTypeface(tv.bold);
            tv.tNav.setTypeface(tv.bold);
        }
        c.restore();
        tv.tLabel.setColor(Theme.TEXT);
        tv.tNav.setColor(Theme.TEXT_DIM);
        float hx = LIST_X + LIST_W + 70;
        if (!navFocused) {
            Item it = focused();
            c.drawText(it.label, hx, LIST_Y + 44, tv.tLabel);
            tv.drawWrapped(c, it.help(), tv.tBody, hx, LIST_Y + 70, TvView.W - hx - TvView.MARGIN, 8);
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
