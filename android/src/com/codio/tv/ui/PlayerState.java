package com.codio.tv.ui;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** What the video player reports (time, duration, pause, tracks), mirrored from its property changes. Main thread. */
public final class PlayerState {
    public static final class Track {
        public int id;
        public String type, lang, title, codec;
        public int channels;
        public boolean selected;
    }

    public double time, duration;
    long timeAt;
    public boolean paused, buffering = true, started;
    public final List<Track> audio = new ArrayList<>(), subs = new ArrayList<>();
    public int aid = -1, sid = -1;
    /** The address the player was last told to open (any thread); lets the controls tell which source is on. */
    public static volatile String playingUrl;
    /** Set when playback ended with an error. */
    public String error;

    void reset() {
        time = duration = 0;
        paused = false;
        buffering = true;
        started = false;
        audio.clear();
        subs.clear();
        aid = sid = -1;
        error = null;
    }

    /** Playback position now, moving smoothly between the player's updates. */
    double position() {
        if (paused || buffering || !started) return time;
        double extra = (SystemClock.uptimeMillis() - timeAt) / 1000.0;
        return time + Math.min(extra, 1.0);
    }

    /** One mpv-prop-change; true when something visible changed. */
    boolean prop(String name, Object data) {
        switch (name) {
            case "time-pos":
                if (data instanceof Number) {
                    time = ((Number) data).doubleValue();
                    timeAt = SystemClock.uptimeMillis();
                    if (time > 0) started = true;
                }
                return true;
            case "duration":
                if (data instanceof Number) duration = ((Number) data).doubleValue();
                return true;
            case "pause":
                paused = Boolean.TRUE.equals(data);
                timeAt = SystemClock.uptimeMillis();
                return true;
            case "paused-for-cache":
                buffering = Boolean.TRUE.equals(data);
                timeAt = SystemClock.uptimeMillis();
                return true;
            case "aid":
                aid = data instanceof Number ? ((Number) data).intValue() : -1;
                return true;
            case "sid":
                sid = data instanceof Number ? ((Number) data).intValue() : -1;
                return true;
            case "track-list":
                if (data instanceof JSONArray) tracks((JSONArray) data);
                return true;
            default:
                return false;
        }
    }

    private void tracks(JSONArray list) {
        audio.clear();
        subs.clear();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            Track t = new Track();
            t.id = o.optInt("id", -1);
            t.type = o.optString("type");
            t.lang = o.isNull("lang") ? null : o.optString("lang", null);
            t.title = o.isNull("title") ? null : o.optString("title", null);
            t.codec = o.isNull("codec") ? null : o.optString("codec", null);
            t.channels = o.optInt("demux-channel-count", 0);
            t.selected = o.optBoolean("selected");
            if ("audio".equals(t.type)) audio.add(t);
            else if ("sub".equals(t.type)) subs.add(t);
        }
    }

    static String language(String code) {
        if (code == null) return null;
        switch (code.toLowerCase()) {
            case "en": case "eng": return "English";
            case "es": case "spa": return "Spanish";
            case "fr": case "fre": case "fra": return "French";
            case "de": case "ger": case "deu": return "German";
            case "it": case "ita": return "Italian";
            case "pt": case "por": return "Portuguese";
            case "nl": case "nld": case "dut": return "Dutch";
            case "pl": case "pol": return "Polish";
            case "ru": case "rus": return "Russian";
            case "tr": case "tur": return "Turkish";
            case "ar": case "ara": return "Arabic";
            case "hi": case "hin": return "Hindi";
            case "ja": case "jpn": return "Japanese";
            case "ko": case "kor": return "Korean";
            case "zh": case "zho": case "chi": return "Chinese";
            case "sv": case "swe": return "Swedish";
            case "da": case "dan": return "Danish";
            case "no": case "nor": return "Norwegian";
            case "fi": case "fin": return "Finnish";
            case "cs": case "cze": case "ces": return "Czech";
            case "hu": case "hun": return "Hungarian";
            case "el": case "gre": case "ell": return "Greek";
            case "he": case "heb": return "Hebrew";
            case "th": case "tha": return "Thai";
            case "und": return null;
            default: return code;
        }
    }

    static String label(Track t, int n) {
        StringBuilder b = new StringBuilder();
        String lang = language(t.lang);
        if (lang != null) b.append(lang);
        if (t.title != null && !t.title.equalsIgnoreCase(lang)) b.append(b.length() > 0 ? "  ·  " : "").append(t.title);
        if ("audio".equals(t.type)) {
            String codec = codecLabel(t.codec);
            String ch = t.channels == 1 ? "Mono" : t.channels == 2 ? "Stereo" : t.channels == 6 ? "5.1" : t.channels == 8 ? "7.1" : t.channels > 0 ? t.channels + " ch" : null;
            String tech = (codec != null ? codec : "") + (ch != null ? (codec != null ? " " : "") + ch : "");
            if (!tech.isEmpty()) b.append(b.length() > 0 ? "  ·  " : "").append(tech);
        }
        if (b.length() == 0) b.append("Track ").append(n);
        return b.toString();
    }

    private static String codecLabel(String c) {
        if (c == null) return null;
        switch (c) {
            case "truehd": return "Dolby TrueHD";
            case "eac3": return "Dolby Digital+";
            case "ac3": return "Dolby Digital";
            case "dts": return "DTS";
            case "aac": return "AAC";
            case "opus": return "Opus";
            case "mp3": case "mpga": return "MP3";
            case "flac": return "FLAC";
            default: return c.toUpperCase();
        }
    }
}
