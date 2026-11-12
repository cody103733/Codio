package com.codio.tv.ui;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Codio's own settings: Smart auto-play, the bitrate limit and audio. Everything else opens the full web settings. */
final class SettingsScreen extends Screen {
    private static final float LIST_X = TvView.MARGIN, LIST_Y = 190, ROW_H = 78, LIST_W = 1060;

    private abstract class Item {
        final String label, help;

        Item(String label, String help) {
            this.label = label;
            this.help = help;
        }

        abstract String value();

        void change(int dir) {}

        void ok() {
            change(1);
        }

        boolean cycles() {
            return true;
        }
    }

    /** One of a fixed list of values, stored in the auto-play settings (web storage) or the app's preferences. */
    private class Choice extends Item {
        final String key;
        final Object[] values;
        final String[] labels;
        final boolean autoPlayKey;
        final Object dflt;

        Choice(String label, String help, String key, boolean autoPlayKey, Object dflt, Object[] values, String[] labels) {
            super(label, help);
            this.key = key;
            this.values = values;
            this.labels = labels;
            this.autoPlayKey = autoPlayKey;
            this.dflt = dflt;
        }

        Object current() {
            if (autoPlayKey) return tv.store.autoPlay.has(key) ? tv.store.autoPlay.opt(key) : dflt;
            SharedPreferences p = tv.host.prefs();
            if (dflt instanceof Integer) return p.getInt(key, (Integer) dflt);
            return p.getString(key, String.valueOf(dflt));
        }

        int index() {
            Object v = current();
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(v) || (values[i] == JSONObject.NULL && (v == null || v == JSONObject.NULL))) return i;
                if (values[i] instanceof Number && v instanceof Number && ((Number) values[i]).doubleValue() == ((Number) v).doubleValue()) return i;
            }
            return -1;
        }

        @Override
        String value() {
            int i = index();
            return i >= 0 ? labels[i] : String.valueOf(current());
        }

        @Override
        void change(int dir) {
            int i = index();
            int n = i < 0 ? 0 : (i + dir + values.length) % values.length;
            Object v = values[n];
            if (autoPlayKey) {
                try {
                    tv.store.autoPlay.put(key, v);
                } catch (Exception ignored) {
                }
                tv.host.saveAutoPlay(tv.store.autoPlay.toString());
            } else {
                SharedPreferences.Editor e = tv.host.prefs().edit();
                if (v instanceof Integer) e.putInt(key, (Integer) v);
                else e.putString(key, String.valueOf(v));
                e.apply();
            }
        }
    }

    /** A setting saved in the profile (synced with the account), changed through the core. */
    private class ProfileChoice extends Choice {
        ProfileChoice(String label, String help, String key, Object dflt, Object[] values, String[] labels) {
            super(label, help, key, false, dflt, values, labels);
        }

        @Override
        Object current() {
            JSONObject st = tv.store.profileJson.optJSONObject("settings");
            if (st == null || !st.has(key)) return dflt;
            return st.opt(key);
        }

        @Override
        void change(int dir) {
            JSONObject st = tv.store.profileJson.optJSONObject("settings");
            if (st == null) {
                tv.toast("Still loading your settings, try again in a moment");
                return;
            }
            int i = index();
            int n = i < 0 ? 0 : (i + dir + values.length) % values.length;
            JSONObject all = tv.store.settingsCopy();
            try {
                all.put(key, values[n]);
                st.put(key, values[n]);
            } catch (Exception ignored) {
            }
            tv.ctx("UpdateSettings", all);
        }
    }

    /** Sign in / sign out. */
    private final class Account extends Item {
        private long confirmUntil;

        Account() {
            super("Account", "Signed in, your library, addons and settings are kept in your Codio account and synced to your other devices.");
        }

        private String email() {
            JSONObject auth = tv.store.profileJson.optJSONObject("auth");
            JSONObject user = auth != null ? auth.optJSONObject("user") : null;
            return user != null ? user.optString("email", null) : null;
        }

        @Override
        String value() {
            String e = email();
            if (e == null) return "Not signed in · Sign in";
            if (android.os.SystemClock.uptimeMillis() < confirmUntil) return "Press OK again to sign out";
            return e;
        }

        @Override
        boolean cycles() {
            return false;
        }

        @Override
        void ok() {
            if (email() == null) {
                signIn();
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
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
                        try {
                            JSONObject a = new JSONObject();
                            a.put("type", "Login");
                            a.put("email", email);
                            a.put("password", password);
                            tv.ctx("Authenticate", a);
                            tv.toast("Signing in…");
                        } catch (Exception ignored) {
                        }
                    }
                }));
            }
        }));
    }

    private final class Link extends Item {
        final String route;

        Link(String label, String help, String route) {
            super(label, help);
            this.route = route;
        }

        @Override
        String value() {
            return "›";
        }

        @Override
        void ok() {
            tv.host.openWeb(route);
        }

        @Override
        boolean cycles() {
            return false;
        }
    }

    private final NavBar nav;
    private final List<Item> items = new ArrayList<>();
    private int focus = 0;
    private boolean navFocused = false;
    private final Smooth scroll = new Smooth(0.1f, 0.4f);
    private final RectF r = new RectF();

    SettingsScreen(TvView tv) {
        super(tv);
        nav = new NavBar(tv, NavBar.SETTINGS);
        items.add(new Choice("Smart auto-play", "Press Play and Codio picks the best source for you, and moves on to the next one if a source fails.",
                "enabled", true, Boolean.FALSE, new Object[]{true, false}, new String[]{"On", "Off"}));
        items.add(new Choice("Preferred quality", "The quality auto-play aims for.",
                "preferredQuality", true, "1080p", new Object[]{"2160p", "1440p", "1080p", "720p", "480p"}, new String[]{"4K (2160p)", "1440p", "1080p", "720p", "480p"}));
        items.add(new Choice("If that quality isn't available", "What auto-play falls back to.",
                "fallbackMode", true, "closest", new Object[]{"closest", "lower", "exact"}, new String[]{"Closest quality", "Lower quality only", "Don't play"}));
        Object[] rates = {0, 25, 40, 60, 70, 80, 100, 150};
        String[] rateLabels = new String[rates.length];
        for (int i = 0; i < rates.length; i++) rateLabels[i] = (Integer) rates[i] == 0 ? "No limit" : rates[i] + " Mbit/s";
        items.add(new Choice("Skip sources above", "Very large 4K files need a fast connection. Sources above this bitrate are tried last, so playback doesn't stall. Pick what your internet can carry.",
                "maxBitrateMbps", true, 0, rates, rateLabels));
        items.add(new Choice("Start-up buffer", "How much video Codio loads before the picture starts. Shorter starts faster; longer rides out a shaky connection better. Applies to the next thing you play.",
                "start_buffer_ms", false, 4000, new Object[]{2000, 4000, 8000}, new String[]{"2 seconds (fastest)", "4 seconds (balanced)", "8 seconds (smoothest)"}));
        items.add(new Choice("Source list", "Hidden: movies just get a Play button. Shown: you see every source and pick one.",
                "hideSources", true, Boolean.TRUE, new Object[]{true, false}, new String[]{"Hidden", "Shown"}));
        items.add(new Choice("Prefer instant sources", "Among sources of the same quality, start with ones your debrid service already has ready.",
                "preferCached", true, Boolean.TRUE, new Object[]{true, false}, new String[]{"On", "Off"}));
        Object[] delays = new Object[41];
        String[] delayLabels = new String[41];
        for (int i = 0; i < 41; i++) {
            int ms = (i - 20) * 50;
            delays[i] = ms;
            delayLabels[i] = ms == 0 ? "In sync (0 ms)" : (ms > 0 ? "Sound " + ms + " ms later" : "Sound " + (-ms) + " ms earlier");
        }
        items.add(new Choice("Skip intro & credits buttons", "Shows a \"Skip Intro\" button during an episode's opening (and recaps), and \"Next Episode\" once the credits start, like Netflix. Times come from IntroDB, a community database, so not every show has them.",
                "skip_buttons", false, "on", new Object[]{"on", "off"}, new String[]{"On", "Off"}));
        items.add(new Choice("Audio sync", "Codio measures how long this TV's speakers take and corrects for it automatically. If voices still don't match lips, fine-tune here: \"earlier\" when the sound lags, \"later\" when it comes first. Applies to the next thing you play.",
                "audio_delay_ms", false, 0, delays, delayLabels) {
            @Override
            void change(int dir) {
                Object v = current();
                int ms = v instanceof Integer ? (Integer) v : 0;
                ms = Math.max(-1000, Math.min(1000, ms + dir * 50));
                tv.host.prefs().edit().putInt(key, ms).apply();
                tv.host.audioSyncChanged();
            }

            @Override
            void ok() {
            }
        });
        items.add(new Choice("Audio format", "Auto: on TV speakers or a stereo soundbar, Codio picks a light audio track and mixes surround down to stereo itself. Best: always the full surround track (for a surround receiver).",
                "vlc_audio", false, "auto", new Object[]{"auto", "light", "full"}, new String[]{"Auto", "Stereo-friendly", "Best (surround)"}));
        items.add(new Choice("Subtitles", "Off: subtitles only appear when you turn them on in the player. On: the player picks them by your language setting.",
                "subs_auto", false, "off", new Object[]{"off", "on"}, new String[]{"Off until I turn them on", "Automatic"}));
        String[] langCodes = {"eng","spa","fre","ger","ita","por","nld","pol","rus","tur","ara","hin","jpn","kor","zho"};
        String[] langNames = {"English","Spanish","French","German","Italian","Portuguese","Dutch","Polish","Russian","Turkish","Arabic","Hindi","Japanese","Korean","Chinese"};
        items.add(new ProfileChoice("Audio language", "Auto-play and the player prefer an audio track in this language.", "audioLanguage", "eng", langCodes, langNames));
        Object[] subCodes = new Object[langCodes.length + 1];
        String[] subNames = new String[langCodes.length + 1];
        subCodes[0] = JSONObject.NULL;
        subNames[0] = "None";
        for (int i = 0; i < langCodes.length; i++) {
            subCodes[i + 1] = langCodes[i];
            subNames[i + 1] = langNames[i];
        }
        items.add(new ProfileChoice("Subtitle language", "The language picked when you turn subtitles on (or always, with Subtitles set to Automatic).", "subtitlesLanguage", JSONObject.NULL, subCodes, subNames));
        items.add(new ProfileChoice("Subtitle size", "How big subtitles are drawn.", "subtitlesSize", 100,
                new Object[]{75, 100, 125, 150, 175, 200, 250}, new String[]{"75%", "100%", "125%", "150%", "175%", "200%", "250%"}));
        items.add(new ProfileChoice("Next episode", "At the end of an episode, play the next one automatically.", "bingeWatching", Boolean.TRUE,
                new Object[]{true, false}, new String[]{"Plays automatically", "Off"}));
        Object[] popup = {0, 10000, 15000, 20000, 30000, 45000, 60000, 90000};
        String[] popupLabels = new String[popup.length];
        for (int i = 0; i < popup.length; i++) popupLabels[i] = (Integer) popup[i] == 0 ? "Don't show" : ((Integer) popup[i] / 1000) + " s before the end";
        items.add(new ProfileChoice("\"Up next\" card", "When the card for the next episode shows up.", "nextVideoNotificationDuration", 35000, popup, popupLabels));
        Object[] seeks = {5000, 10000, 15000, 20000, 30000};
        String[] seekLabels = {"5 seconds", "10 seconds", "15 seconds", "20 seconds", "30 seconds"};
        items.add(new ProfileChoice("Skip forward / back", "How far Left and Right jump in the player.", "seekTimeDuration", 10000, seeks, seekLabels));
        items.add(new Account());
        items.add(new Item("Cast photos (TMDB key)", "Actors' photos and characters come from TMDB with your own free API key (themoviedb.org, Settings, API). Press OK to type it in. It's saved with your Stremio account, so your other devices get it. Without a key, the cast shows names only.") {
            @Override
            String value() {
                return CastInfo.key() != null ? "Key saved" : "Add key";
            }

            @Override
            boolean cycles() {
                return false;
            }

            @Override
            void ok() {
                tv.push(new TextEntryScreen(tv, "TMDB API key", "32 letters and numbers", CastInfo.key(), false, new TextEntryScreen.Done() {
                    @Override
                    public void done(String text) {
                        String k = text.trim();
                        if (!k.isEmpty() && !CastInfo.looksLikeKey(k)) {
                            tv.toast("That doesn't look like a TMDB API key (32 letters and numbers)");
                            return;
                        }
                        tv.host.prefs().edit().putString("tmdb_key", k).apply();
                        KeySync.push(tv.host.prefs(), k, KeySync.authKey(tv.store.profileJson));
                        tv.toast(k.isEmpty() ? "TMDB key removed" : "TMDB key saved");
                    }
                }));
            }
        });
        items.add(new Item("Updates", "Codio checks for a newer version when it starts and offers to install it. Press OK to check now.") {
            @Override
            String value() {
                return "Check now";
            }

            @Override
            boolean cycles() {
                return false;
            }

            @Override
            void ok() {
                tv.host.checkUpdates();
            }
        });
    }

    @Override
    void onShow() {
        nav.focus = nav.current;
    }

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(0xFF0B0B0B);
        scroll.to(Math.max(0, (focus - 8) * ROW_H));
        float sy = step(scroll, dt);
        c.save();
        c.clipRect(0, LIST_Y - 20, TvView.W, TvView.H);
        for (int i = 0; i < items.size(); i++) {
            float y = LIST_Y + i * ROW_H - sy;
            if (y > TvView.H || y + ROW_H < LIST_Y - 20) continue;
            Item it = items.get(i);
            boolean f = !navFocused && i == focus;
            r.set(LIST_X, y, LIST_X + LIST_W, y + ROW_H - 10);
            tv.fill.setColor(f ? Color.WHITE : 0xFF161616);
            c.drawRoundRect(r, 10, 10, tv.fill);
            tv.tLabel.setColor(f ? Color.BLACK : Color.WHITE);
            c.drawText(it.label, LIST_X + 30, y + 44, tv.tLabel);
            String v = it.value();
            tv.tNav.setColor(f ? 0xFF333333 : 0xFFB3B3B3);
            float vw = tv.tNav.measureText(v);
            float vx = LIST_X + LIST_W - 30 - vw - (f && it.cycles() ? 30 : 0);
            c.drawText(v, vx, y + 44, tv.tNav);
            if (f && it.cycles()) {
                c.drawText("‹", vx - 30, y + 44, tv.tNav);
                c.drawText("›", LIST_X + LIST_W - 40, y + 44, tv.tNav);
            }
        }
        c.restore();
        tv.tLabel.setColor(Color.WHITE);
        tv.tNav.setColor(0xFFB3B3B3);
        if (!navFocused) {
            Item it = items.get(focus);
            float hx = LIST_X + LIST_W + 70;
            c.drawText(it.label, hx, LIST_Y + 44, tv.tLabel);
            tv.drawWrapped(c, it.help, tv.tBody, hx, LIST_Y + 70, TvView.W - hx - TvView.MARGIN, 8);
        }
        c.drawText("Codio " + tv.host.version(), LIST_X + LIST_W + 70, TvView.H - 60, tv.tSmall);
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
        Item it = items.get(focus);
        switch (k) {
            case TvView.UP:
                if (focus > 0) focus--;
                else {
                    navFocused = true;
                    nav.focus = nav.current;
                }
                return true;
            case TvView.DOWN:
                if (focus < items.size() - 1) focus++;
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
