package com.codio.tv.ui;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One setting with a fixed list of values, and the list of them that the TV and phone Settings screens both show
 * (the wording is the same on every Codio app: see DESIGN.md).
 */
final class Setting {
    /**
     * Where a value lives: with Smart auto-play (the engine's storage), on this device only, in the synced profile, or
     * on this device for each profile (falls back to the old device-wide value until the profile changes it).
     */
    static final int AUTOPLAY = 0, DEVICE = 1, PROFILE = 2, DEVICE_PROFILE = 3;

    static final String PLAYBACK = "Playback", PICTURE_SOUND = "Picture & sound", LANGUAGES = "Languages & subtitles",
            DOWNLOADS = "Downloads", ACCOUNT = "Account", ABOUT = "About";

    static final String[] LANG_CODES = {"eng", "spa", "fre", "ger", "ita", "por", "pob", "nld", "swe", "pol", "rus", "tur", "ara", "hin", "jpn", "kor", "zho"};
    static final String[] LANG_NAMES = {"English", "Spanish", "French", "German", "Italian", "Portuguese", "Portuguese (Brazil)", "Dutch", "Swedish", "Polish", "Russian", "Turkish", "Arabic", "Hindi", "Japanese", "Korean", "Chinese"};

    // Subtitle look, in Stremio's "#RRGGBBAA" (the same values on every Codio app, see DESIGN.md).
    static final Object[] SUB_COLORS = {"#FFFFFFFF", "#FFFF00FF", "#00FFFFFF", "#00FF00FF"};
    static final String[] SUB_COLOR_NAMES = {"White", "Yellow", "Cyan", "Green"};
    static final Object[] SUB_BACKS = {"#00000000", "#00000080", "#000000FF"};
    static final String[] SUB_BACK_NAMES = {"None", "Shaded box", "Black box"};

    final String section, label, help, key;
    final int kind;
    final Object dflt;
    final Object[] values;
    final String[] labels;
    /** Left/Right step through the values without wrapping around (a slider, e.g. Audio sync). */
    final boolean slider;

    private Setting(String section, int kind, String label, String help, String key, Object dflt, Object[] values, String[] labels, boolean slider) {
        this.section = section;
        this.kind = kind;
        this.label = label;
        this.help = help;
        this.key = key;
        this.dflt = dflt;
        this.values = values;
        this.labels = labels;
        this.slider = slider;
    }

    Object current(Store store, SharedPreferences prefs) {
        if (kind == AUTOPLAY) return store.autoPlay.has(key) ? store.autoPlay.opt(key) : dflt;
        if (kind == PROFILE) {
            JSONObject st = store.profileJson.optJSONObject("settings");
            return st != null && st.has(key) ? st.opt(key) : dflt;
        }
        if (kind == DEVICE_PROFILE) return prefs.getString(Store.perProfile(key), prefs.getString(key, String.valueOf(dflt)));
        if (dflt instanceof Boolean) return prefs.getBoolean(key, (Boolean) dflt);
        if (dflt instanceof Integer) return prefs.getInt(key, (Integer) dflt);
        return prefs.getString(key, String.valueOf(dflt));
    }

    int index(Store store, SharedPreferences prefs) {
        Object v = current(store, prefs);
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof Number && v instanceof Number) {
                if (((Number) values[i]).doubleValue() == ((Number) v).doubleValue()) return i;
            } else if (String.valueOf(values[i]).equals(String.valueOf(v))) {
                return i;
            }
        }
        return -1;
    }

    String value(Store store, SharedPreferences prefs) {
        int i = index(store, prefs);
        return i >= 0 ? labels[i] : String.valueOf(current(store, prefs));
    }

    /** The value one step along (dir -1 or 1): wraps around, or stops at the ends for a slider. */
    int stepped(Store store, SharedPreferences prefs, int dir) {
        int i = index(store, prefs);
        Object v = current(store, prefs);
        if (i < 0 && slider && v instanceof Number) {
            // A value between two steps: carry on from the nearest one.
            for (int k = 0; k < values.length; k++) {
                if (i < 0 || Math.abs(((Number) values[k]).doubleValue() - ((Number) v).doubleValue())
                        < Math.abs(((Number) values[i]).doubleValue() - ((Number) v).doubleValue())) i = k;
            }
        }
        if (i < 0) return 0;
        if (slider) return Math.max(0, Math.min(values.length - 1, i + dir));
        return (i + dir + values.length) % values.length;
    }

    /** Saves value n. False when the synced profile hasn't loaded yet (nothing was saved). */
    boolean set(int n, Store store, TvView.Host host) {
        Object v = values[n];
        try {
            if (kind == AUTOPLAY) {
                store.autoPlay.put(key, v);
                host.saveAutoPlay(store.autoPlay.toString());
            } else if (kind == PROFILE) {
                JSONObject st = store.profileJson.optJSONObject("settings");
                if (st == null) return false;
                JSONObject all = store.settingsCopy();
                all.put(key, v);
                st.put(key, v);
                host.dispatch(Core.ctx("UpdateSettings", all));
            } else if (kind == DEVICE_PROFILE) {
                host.prefs().edit().putString(Store.perProfile(key), String.valueOf(v)).apply();
            } else {
                SharedPreferences.Editor e = host.prefs().edit();
                if (v instanceof Boolean) e.putBoolean(key, (Boolean) v);
                else if (v instanceof Integer) e.putInt(key, (Integer) v);
                else e.putString(key, String.valueOf(v));
                e.apply();
                if ("audio_delay_ms".equals(key)) host.audioSyncChanged();
                if (NewEpisodes.PREF.equals(key)) host.newEpisodeAlertsChanged();
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    static final String LOADING = "Still loading your settings, try again in a moment";

    // ------------------------------------------------------------------ the list

    private static final Object[] ON_OFF = {true, false}, ON_OFF_TEXT = {"on", "off"};
    private static final String[] ON_OFF_LABELS = {"On", "Off"};

    private static String[] labels(Object[] values, String zero, String unit) {
        String[] l = new String[values.length];
        for (int i = 0; i < values.length; i++) l[i] = (Integer) values[i] == 0 && zero != null ? zero : values[i] + unit;
        return l;
    }

    private static String[] seconds(Object[] ms, String zero, String after) {
        String[] l = new String[ms.length];
        for (int i = 0; i < ms.length; i++) l[i] = (Integer) ms[i] == 0 && zero != null ? zero : ((Integer) ms[i] / 1000) + " seconds" + after;
        return l;
    }

    /** Phones (Playback, as on the iPhone): open in the last profile, or ask who's watching each time. */
    static Setting askProfile() {
        return new Setting(PLAYBACK, DEVICE, "Who's watching?", "Codio opens in the profile you used last. Turn this on to be asked who's watching every time it opens.",
                "ask_profile", "off", new Object[]{"on", "off"}, new String[]{"Ask when Codio opens", "Open the last profile"}, false);
    }

    /** Every choice setting, in the order shown. phone = Codio for phones, otherwise Android TV. */
    static List<Setting> all(boolean phone) {
        List<Setting> l = new ArrayList<>();
        l.add(new Setting(PLAYBACK, AUTOPLAY, "Smart auto-play", "Play picks the best source for you, and moves on to the next one if a source fails.",
                "enabled", true, ON_OFF, ON_OFF_LABELS, false));
        l.add(new Setting(PLAYBACK, AUTOPLAY, "Preferred quality", "The quality auto-play aims for.",
                "preferredQuality", "1080p", new Object[]{"2160p", "1440p", "1080p", "720p", "480p"}, new String[]{"4K (2160p)", "1440p", "1080p", "720p", "480p"}, false));
        l.add(new Setting(PLAYBACK, AUTOPLAY, "If that quality isn't available", "What auto-play falls back to.",
                "fallbackMode", "closest", new Object[]{"closest", "lower", "exact"}, new String[]{"Closest quality", "Lower quality only", "Don't play"}, false));
        Object[] rates = phone ? new Object[]{0, 10, 15, 25, 40, 60, 70, 80, 100, 150} : new Object[]{0, 25, 40, 60, 70, 80, 100, 150};
        l.add(new Setting(PLAYBACK, AUTOPLAY, "Skip sources above", "Sources above this bitrate are tried last, so playback doesn't stall. Pick what your connection can carry"
                + (phone ? "; lower it on mobile data." : "."),
                "maxBitrateMbps", 70, rates, labels(rates, "No limit", " Mbit/s"), false));
        l.add(new Setting(PLAYBACK, AUTOPLAY, "Prefer instant sources", "Among sources of the same quality, start with ones your debrid service already has ready.",
                "preferCached", true, ON_OFF, ON_OFF_LABELS, false));
        if (!phone) {
            l.add(new Setting(PLAYBACK, AUTOPLAY, "Source list", "Hidden: movies just get a Play button. Shown: you see every source and pick one.",
                    "hideSources", true, ON_OFF, new String[]{"Hidden", "Shown"}, false));
        }
        l.add(new Setting(PLAYBACK, PROFILE, "Next episode", "At the end of an episode, play the next one automatically.",
                "bingeWatching", true, ON_OFF, new String[]{"Plays automatically", "Off"}, false));
        l.add(new Setting(PLAYBACK, DEVICE, "Skip Intro button", "Shows Skip Intro, Skip Recap and Skip Credits in the player. Times come from IntroDB, a community database, so not every show has them.",
                "skip_buttons", "on", ON_OFF_TEXT, ON_OFF_LABELS, false));
        l.add(new Setting(PLAYBACK, DEVICE, "Skip automatically", "Jumps past intros and recaps by itself when their times are known. With credits too, the next episode starts as the credits begin.",
                "auto_skip", 0, new Object[]{0, 1, 2}, new String[]{"Off", "Intros and recaps", "Intros, recaps and credits"}, false));
        Object[] popup = {0, 10000, 15000, 20000, 30000, 35000, 45000, 60000, 90000};
        l.add(new Setting(PLAYBACK, PROFILE, "Up Next", "Shows the next episode with a countdown as the credits start. On episodes whose credits aren't timed, it appears this long before the end.",
                "nextVideoNotificationDuration", 35000, popup, seconds(popup, "Don't show", " before the end"), false));
        l.add(new Setting(PLAYBACK, DEVICE, "New episode alerts", phone
                ? "Marks shows you're watching when a new episode is out, and sends a notification."
                : "Marks shows you're watching when a new episode is out, and puts them first in Continue Watching.",
                NewEpisodes.PREF, "on", ON_OFF_TEXT, ON_OFF_LABELS, false));
        if (phone) {
            l.add(askProfile());
            l.add(new Setting(PLAYBACK, DEVICE, "Mini player", "When you leave Codio during a video, it keeps playing in a small window over your other apps.",
                    "pip_auto", "on", ON_OFF_TEXT, ON_OFF_LABELS, false));
        }
        if (!phone) {
            Object[] seeks = {5000, 10000, 15000, 20000, 30000};
            l.add(new Setting(PLAYBACK, PROFILE, "Skip forward and back", "How far Left and Right jump in the player.",
                    "seekTimeDuration", 10000, seeks, seconds(seeks, null, ""), false));

            l.add(new Setting(PICTURE_SOUND, DEVICE, "Dolby Vision", "On: auto-play picks Dolby Vision sources first, and they play with this TV's own Dolby Vision decoder. Big Blu-ray remuxes still play as HDR10. Off: always play as HDR10.",
                    "dolby_vision", "on", ON_OFF_TEXT, ON_OFF_LABELS, false));
            l.add(new Setting(PICTURE_SOUND, DEVICE, "Start-up buffer", "How much video Codio loads before the picture starts. Shorter starts faster; longer rides out a shaky connection better. Applies to the next thing you play.",
                    "start_buffer_ms", 4000, new Object[]{2000, 4000, 8000}, new String[]{"2 seconds (fastest)", "4 seconds (balanced)", "8 seconds (smoothest)"}, false));
            l.add(new Setting(PICTURE_SOUND, DEVICE, "Audio format", "Auto: on TV speakers or a stereo soundbar, Codio picks a light audio track and mixes surround down to stereo itself. Best: always the full surround track (for a surround receiver).",
                    "vlc_audio", "auto", new Object[]{"auto", "light", "full"}, new String[]{"Auto", "Stereo-friendly", "Best (surround)"}, false));
            Object[] delays = new Object[41];
            String[] delayLabels = new String[41];
            for (int i = 0; i < 41; i++) {
                int ms = (i - 20) * 50;
                delays[i] = ms;
                delayLabels[i] = ms == 0 ? "In sync (0 ms)" : (ms > 0 ? "Sound " + ms + " ms later" : "Sound " + (-ms) + " ms earlier");
            }
            l.add(new Setting(PICTURE_SOUND, DEVICE, "Audio sync", "Codio measures how long this TV's speakers take and corrects for it automatically. If voices still don't match lips, fine-tune here: \"earlier\" when the sound lags, \"later\" when it comes first. Applies to the next thing you play.",
                    "audio_delay_ms", 0, delays, delayLabels, true));
        } else {
            l.add(new Setting(DOWNLOADS, DEVICE, "Download over", "Big video files can use a lot of mobile data. With Wi-Fi only, downloads wait until you're on Wi-Fi.",
                    "dl_wifi_only", true, ON_OFF, new String[]{"Wi-Fi only", "Wi-Fi and mobile data"}, false));
        }

        l.add(new Setting(LANGUAGES, DEVICE_PROFILE, "Subtitles", "Off: subtitles only appear when you turn them on in the player. Automatic: picked by your subtitle language.",
                "subs_auto", "off", new Object[]{"off", "on"}, new String[]{"Off until I turn them on", "Automatic"}, false));
        l.add(new Setting(LANGUAGES, PROFILE, "Audio language", "Auto-play and the player prefer an audio track in this language.",
                "audioLanguage", "eng", LANG_CODES, LANG_NAMES, false));
        Object[] subCodes = new Object[LANG_CODES.length + 1];
        String[] subNames = new String[LANG_CODES.length + 1];
        subCodes[0] = JSONObject.NULL;
        subNames[0] = "None";
        System.arraycopy(LANG_CODES, 0, subCodes, 1, LANG_CODES.length);
        System.arraycopy(LANG_NAMES, 0, subNames, 1, LANG_NAMES.length);
        l.add(new Setting(LANGUAGES, PROFILE, "Subtitle language", "The language picked when you turn subtitles on.",
                "subtitlesLanguage", JSONObject.NULL, subCodes, subNames, false));
        Object[] sizes = {75, 100, 125, 150, 175, 200, 250};
        l.add(new Setting(LANGUAGES, PROFILE, "Subtitle size", "How big subtitles are drawn.",
                "subtitlesSize", 100, sizes, labels(sizes, null, "%"), false));
        l.add(new Setting(LANGUAGES, PROFILE, "Subtitle colour", "The colour of the words.",
                "subtitlesTextColor", "#FFFFFFFF", SUB_COLORS, SUB_COLOR_NAMES, false));
        l.add(new Setting(LANGUAGES, PROFILE, "Subtitle background", "A box behind the words makes them easier to read on bright scenes.",
                "subtitlesBackgroundColor", "#00000000", SUB_BACKS, SUB_BACK_NAMES, false));
        return l;
    }
}
