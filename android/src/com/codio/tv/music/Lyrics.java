package com.codio.tv.music;

import com.codio.tv.music.Model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Song lyrics from LRCLIB (lrclib.net, free, no key), timed line by line when LRCLIB has them. The same rules as
 * windows/src/routes/Codio/music/lyrics.ts and ios/Codio/Music/Lyrics.swift (DESIGN.md "Lyrics"):
 * exact lookup by artist, title, album and length first, then a search whose answer must be the same recording.
 * Found lyrics are kept 30 days, "none found" 1 day. Downloaded songs keep theirs next to the song (MusicDownloads).
 */
public final class Lyrics {
    public static final String CREDIT = "Lyrics from LRCLIB";
    private static final String BASE = "https://lrclib.net";
    private static final Pattern TAG = Pattern.compile("^\\[(\\d{1,3}):(\\d{1,2}(?:[.:]\\d{1,3})?)\\]");
    private static final Pattern OFFSET = Pattern.compile("^\\[offset:\\s*([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE);

    public static final class Line {
        public final long ms;
        public final String text;

        Line(long ms, String text) {
            this.ms = ms;
            this.text = text;
        }
    }

    public static final class Result {
        /** Timed lines, oldest first; empty when only plain lyrics exist. */
        public final List<Line> lines = new ArrayList<>();
        public String plain;
        public boolean instrumental;

        public boolean synced() {
            return !lines.isEmpty();
        }

        public boolean empty() {
            return !instrumental && lines.isEmpty() && (plain == null || plain.trim().length() == 0);
        }

        /** The line playing at this point of the song, or -1 before the first one. */
        public int lineAt(double seconds) {
            long ms = (long) (seconds * 1000) + 250;
            int found = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).ms <= ms) found = i;
                else break;
            }
            return found;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                if (instrumental) o.put("instrumental", true);
                if (plain != null) o.put("plainLyrics", plain);
                if (!lines.isEmpty()) o.put("syncedLyrics", toLrc());
            } catch (Exception ignored) {
            }
            return o;
        }

        private String toLrc() {
            StringBuilder sb = new StringBuilder();
            for (Line l : lines) {
                long cs = l.ms / 10;
                sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]", cs / 6000, (cs / 100) % 60, cs % 100)).append(l.text).append('\n');
            }
            return sb.toString();
        }
    }

    /** Turns LRC text ("[01:02.50]words") into timed lines. Lines with several times appear once per time. */
    public static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        if (lrc == null) return out;
        long offset = 0;
        for (String raw : lrc.split("\r?\n")) {
            String line = raw.trim();
            java.util.regex.Matcher off = OFFSET.matcher(line);
            if (off.find()) {
                try {
                    offset = Long.parseLong(off.group(1));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            List<Long> times = new ArrayList<>();
            java.util.regex.Matcher m = TAG.matcher(line);
            while (m.find()) {
                long min = Long.parseLong(m.group(1));
                String sec = m.group(2).replace(':', '.');
                double s = Double.parseDouble(sec);
                times.add(Math.round((min * 60 + s) * 1000));
                line = line.substring(m.end());
                m = TAG.matcher(line);
            }
            if (times.isEmpty()) continue;
            String text = line.trim();
            for (long t : times) out.add(new Line(Math.max(0, t - offset), text));
        }
        Collections.sort(out, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return Long.compare(a.ms, b.ms);
            }
        });
        return out;
    }

    static Result fromJson(JSONObject o) {
        if (o == null) return null;
        Result r = new Result();
        r.instrumental = o.optBoolean("instrumental", false);
        r.plain = Model.str(o, "plainLyrics");
        r.lines.addAll(parse(Model.str(o, "syncedLyrics")));
        return r;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String key(Track t, long durationMs) {
        return "lyrics|" + Matcher.normalise(t.artist) + "|" + Matcher.normalise(t.title) + "|" + Math.round(durationMs / 1000.0);
    }

    private static final java.util.LinkedHashMap<String, Result> MEMO = new java.util.LinkedHashMap<String, Result>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Result> e) {
            return size() > 30;
        }
    };
    private static final Result NONE = new Result();

    /** Already looked up this run? NONE-free: null means not known yet or none. */
    public static boolean known(Track t) {
        synchronized (MEMO) {
            return t != null && MEMO.containsKey(t.key());
        }
    }

    /** The lyrics, from memory when this song was looked up already; done gets null when there are none. Main thread. */
    public static void get(final Track t, final long durationMs, final MusicNet.Done<Result> done) {
        if (t == null) {
            done.done(null, null);
            return;
        }
        synchronized (MEMO) {
            if (MEMO.containsKey(t.key())) {
                Result r = MEMO.get(t.key());
                done.done(r == NONE ? null : r, null);
                return;
            }
        }
        MusicNet.async(new MusicNet.Job<Result>() {
            @Override
            public Result run() throws Exception {
                return find(t, durationMs);
            }
        }, new MusicNet.Done<Result>() {
            @Override
            public void done(Result r, Exception e) {
                // a network failure is not remembered, so the next look tries again
                if (e == null) {
                    synchronized (MEMO) {
                        MEMO.put(t.key(), r == null ? NONE : r);
                    }
                }
                done.done(r, e);
            }
        });
    }

    /** The lyrics for a song, or null when LRCLIB has none. Network and disk: call off the main thread. */
    public static Result find(Track t, long durationMs) throws Exception {
        if (t == null || t.title.length() == 0) return null;
        Result saved = MusicDownloads.savedLyrics(t);
        if (saved != null) return saved.empty() ? null : saved;
        if (durationMs <= 0) durationMs = t.durationMs;
        String k = key(t, durationMs);
        byte[] cached = MusicNet.readCache(k, 30 * MusicNet.DAY);
        if (cached != null) {
            JSONObject o = new JSONObject(new String(cached, StandardCharsets.UTF_8));
            if (o.optBoolean("none", false)) {
                // "none found" is only trusted for a day
                if (MusicNet.readCache(k, MusicNet.DAY) != null) return null;
            } else {
                return fromJson(o);
            }
        }
        JSONObject found = lookup(t, durationMs);
        Result r = found == null ? null : fromJson(found);
        if (r != null && r.empty()) r = null;
        MusicNet.writeCache(k, (r == null ? "{\"none\":true}" : r.toJson().toString()).getBytes(StandardCharsets.UTF_8));
        return r;
    }

    private static JSONObject lookup(Track t, long durationMs) throws Exception {
        if (durationMs > 0) {
            String url = BASE + "/api/get?artist_name=" + enc(t.artist) + "&track_name=" + enc(t.title)
                    + (t.album != null ? "&album_name=" + enc(t.album) : "") + "&duration=" + Math.round(durationMs / 1000.0);
            MusicNet.Reply r = MusicNet.get(url, 12000);
            if (r.status == 200 && r.body != null) {
                JSONObject o = new JSONObject(new String(r.body, StandardCharsets.UTF_8));
                if (o.has("syncedLyrics") || o.has("plainLyrics") || o.optBoolean("instrumental")) return o;
            } else if (r.status != 404) {
                throw new java.io.IOException("LRCLIB answered " + r.status);
            }
        }
        MusicNet.Reply r = MusicNet.get(BASE + "/api/search?artist_name=" + enc(t.artist) + "&track_name=" + enc(t.title), 12000);
        if (r.status != 200 || r.body == null) {
            if (r.status == 404) return null;
            throw new java.io.IOException("LRCLIB answered " + r.status);
        }
        JSONArray a = new JSONArray(new String(r.body, StandardCharsets.UTF_8));
        JSONObject plainOnly = null;
        Track wanted = new Track();
        wanted.title = t.title;
        wanted.artist = t.artist;
        wanted.durationMs = durationMs;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Track c = new Track();
            c.title = o.optString("trackName", "");
            c.artist = o.optString("artistName", "");
            c.durationMs = Math.round(o.optDouble("duration", 0) * 1000);
            if (!Matcher.isSameRecording(wanted, c)) continue;
            if (Model.str(o, "syncedLyrics") != null) return o;
            if (plainOnly == null && (Model.str(o, "plainLyrics") != null || o.optBoolean("instrumental"))) plainOnly = o;
        }
        return plainOnly;
    }

    /** Saves lyrics for a downloaded song into file (an empty object when there are none, so offline knows). */
    static void save(Result r, File file) {
        try {
            FileOutputStream out = new FileOutputStream(file);
            out.write((r == null ? "{}" : r.toJson().toString()).getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
        }
    }

    static Result load(File file) {
        try {
            if (!file.exists()) return null;
            FileInputStream in = new FileInputStream(file);
            byte[] b = new byte[(int) file.length()];
            int n = 0;
            while (n < b.length) {
                int k = in.read(b, n, b.length - n);
                if (k < 0) break;
                n += k;
            }
            in.close();
            return fromJson(new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }
}
