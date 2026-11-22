package com.codio.tv.ui;

import android.content.SharedPreferences;

import com.codio.tv.BuildConfig;
import com.codio.tv.music.Model;
import com.codio.tv.music.MusicNet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Your stats (TV and phone). Codio counts it itself: the seconds a video really played (from the player's clock, so
 * pauses and jumps don't count) and the songs that started, on this device, for each profile. Every device keeps its
 * own counts in the account (hidden library entry codio:stats, one slot per device), so all of them show the same
 * totals. Same rules and format as ios/Codio/Store/Stats.swift and windows/.../Codio/stats.ts (DESIGN.md "Your stats").
 */
public final class Stats {
    private static final String WATCH = "stats_watch_v2", LISTEN = "stats_listen_v1", REMOTE = "stats_remote_v1", DEVICE = "stats_device_id";
    static final String ITEM_ID = "codio:stats";
    private static final String API = "https://api.strem.io/api/";
    public static final int MAX_SONGS = 2000;
    /** How much of each year one device puts in the account. */
    private static final int SYNC_SONGS = 150, SYNC_ARTISTS = 100, SYNC_TITLES = 400;

    private Stats() {
    }

    public static int thisYear() {
        return Calendar.getInstance().get(Calendar.YEAR);
    }

    private static SharedPreferences prefs() {
        return MusicNet.prefs();
    }

    private static JSONObject read(String key) {
        SharedPreferences p = prefs();
        if (p == null) return new JSONObject();
        try {
            return new JSONObject(p.getString(key, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void write(String key, JSONObject o) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString(key, o.toString()).apply();
    }

    private static JSONObject child(JSONObject o, String key) throws Exception {
        JSONObject c = o.optJSONObject(key);
        if (c == null) o.put(key, c = new JSONObject());
        return c;
    }

    private static String deviceId() {
        SharedPreferences p = prefs();
        if (p == null) return "unknown";
        String id = p.getString(DEVICE, null);
        if (id == null) {
            id = (BuildConfig.PHONE ? "android-" : "tv-") + UUID.randomUUID().toString().substring(0, 8);
            p.edit().putString(DEVICE, id).apply();
        }
        return id;
    }

    // ---- Counting what plays

    private static String playingId;
    private static double lastPos = -1;
    private static long pendingMs;
    private static Store.Meta pendingMeta;
    private static long lastFlush;

    /**
     * The player's clock moved to pos (seconds) while m plays. Only forward steps of up to 3 s count, so a pause, a
     * seek or a source switch adds nothing. Saved every 30 s of watching and when the video stops.
     */
    public static synchronized void tick(Store.Meta m, double pos) {
        if (m == null || m.id == null || m.id.isEmpty()) return;
        if (!m.id.equals(playingId)) {
            flush();
            playingId = m.id;
            lastPos = pos;
            return;
        }
        double d = pos - lastPos;
        lastPos = pos;
        if (d > 0 && d <= 3) {
            pendingMs += Math.round(d * 1000);
            pendingMeta = m;
        }
        long now = System.currentTimeMillis();
        if (pendingMs >= 30000 || (pendingMs > 0 && now - lastFlush > 60000)) flush();
    }

    /** The video stopped (player closed). */
    public static synchronized void stopped() {
        flush();
        playingId = null;
        lastPos = -1;
    }

    private static void flush() {
        lastFlush = System.currentTimeMillis();
        if (pendingMs <= 0 || pendingMeta == null || prefs() == null) {
            pendingMs = 0;
            return;
        }
        Store.Meta m = pendingMeta;
        long add = pendingMs;
        pendingMs = 0;
        try {
            JSONObject all = read(WATCH);
            JSONObject mine = child(all, Store.profileId());
            if (!mine.has("since")) mine.put("since", System.currentTimeMillis());
            JSONObject year = child(child(mine, "years"), String.valueOf(thisYear()));
            JSONArray e = year.optJSONArray(m.id);
            long ms = (e != null ? e.optLong(0) : 0) + add;
            year.put(m.id, new JSONArray().put(ms).put(m.type == null ? "" : m.type).put(m.name == null ? "" : m.name)
                    .put(m.poster == null ? JSONObject.NULL : m.poster));
            write(WATCH, all);
            dirty = true;
        } catch (Exception ignored) {
        }
    }

    // ---- Listening

    /** A song started playing (any source, downloads too). */
    public static synchronized void record(Model.Track t) {
        if (t == null || prefs() == null) return;
        try {
            JSONObject all = read(LISTEN);
            JSONObject mine = child(all, Store.profileId());
            JSONObject y = child(mine, String.valueOf(thisYear()));
            y.put("plays", y.optInt("plays") + 1);
            y.put("seconds", y.optDouble("seconds", 0) + Math.max(0, t.durationMs) / 1000.0);
            JSONObject songs = child(y, "songs");
            String sk = norm(t.artist) + "|" + norm(t.title);
            JSONObject song = songs.optJSONObject(sk);
            if (song == null) song = new JSONObject().put("track", t.toJson()).put("count", 0);
            song.put("count", song.optInt("count") + 1);
            if (t.artworkURL != null) song.put("track", t.toJson());
            songs.put(sk, song);
            if (songs.length() > MAX_SONGS) {
                List<String> keys = sortedKeys(songs, "count");
                for (int i = MAX_SONGS; i < keys.size(); i++) songs.remove(keys.get(i));
            }
            String ak = norm(t.artist);
            if (!ak.isEmpty()) {
                JSONObject artists = child(y, "artists");
                JSONObject a = artists.optJSONObject(ak);
                artists.put(ak, new JSONObject().put("name", t.artist).put("count", (a == null ? 0 : a.optInt("count")) + 1));
            }
            write(LISTEN, all);
            dirty = true;
        } catch (Exception ignored) {
        }
    }

    private static List<String> sortedKeys(final JSONObject o, final String field) {
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) keys.add(it.next());
        Collections.sort(keys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return o.optJSONObject(b).optInt(field) - o.optJSONObject(a).optInt(field);
            }
        });
        return keys;
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    // ---- This device's slot in the account, and everyone's together

    /** This device's counts for the profile, in the account's format. */
    private static JSONObject mySlot(String profile) throws Exception {
        JSONObject slot = new JSONObject().put("kind", BuildConfig.PHONE ? "android" : "tv").put("at", System.currentTimeMillis());
        JSONObject w = read(WATCH).optJSONObject(profile);
        if (w != null && w.has("since")) slot.put("since", w.optLong("since"));
        JSONObject years = w != null ? w.optJSONObject("years") : null;
        JSONObject watch = new JSONObject();
        if (years != null) for (Iterator<String> it = years.keys(); it.hasNext(); ) {
            String y = it.next();
            final JSONObject titles = years.optJSONObject(y);
            if (titles == null) continue;
            List<String> ids = new ArrayList<>();
            for (Iterator<String> i = titles.keys(); i.hasNext(); ) ids.add(i.next());
            Collections.sort(ids, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return Long.compare(titles.optJSONArray(b).optLong(0), titles.optJSONArray(a).optLong(0));
                }
            });
            JSONObject keep = new JSONObject();
            for (int i = 0; i < Math.min(SYNC_TITLES, ids.size()); i++) keep.put(ids.get(i), titles.optJSONArray(ids.get(i)));
            watch.put(y, keep);
        }
        slot.put("watch", watch);
        JSONObject l = read(LISTEN).optJSONObject(profile);
        JSONObject listen = new JSONObject();
        if (l != null) for (Iterator<String> it = l.keys(); it.hasNext(); ) {
            String y = it.next();
            JSONObject v = l.optJSONObject(y);
            if (v == null) continue;
            JSONObject out = new JSONObject().put("s", Math.round(v.optDouble("seconds", 0))).put("p", v.optInt("plays"));
            JSONObject songs = v.optJSONObject("songs"), s2 = new JSONObject();
            if (songs != null) {
                List<String> keys = sortedKeys(songs, "count");
                for (int i = 0; i < Math.min(SYNC_SONGS, keys.size()); i++) {
                    JSONObject s = songs.optJSONObject(keys.get(i));
                    s2.put(keys.get(i), new JSONArray().put(s.optInt("count")).put(s.optJSONObject("track")));
                }
            }
            JSONObject artists = v.optJSONObject("artists"), a2 = new JSONObject();
            if (artists != null) {
                List<String> keys = sortedKeys(artists, "count");
                for (int i = 0; i < Math.min(SYNC_ARTISTS, keys.size()); i++) {
                    JSONObject a = artists.optJSONObject(keys.get(i));
                    a2.put(keys.get(i), new JSONArray().put(a.optInt("count")).put(a.optString("name")));
                }
            }
            listen.put(y, out.put("songs", s2).put("artists", a2));
        }
        return slot.put("listen", listen);
    }

    /** Every device's slot for the profile: the other devices' as last read from the account, and this one's as it is now. */
    private static List<JSONObject> slots(String profile) {
        List<JSONObject> out = new ArrayList<>();
        JSONObject others = read(REMOTE).optJSONObject(profile);
        String me = deviceId();
        if (others != null) for (Iterator<String> it = others.keys(); it.hasNext(); ) {
            String id = it.next();
            if (!id.equals(me) && others.optJSONObject(id) != null) out.add(others.optJSONObject(id));
        }
        try {
            out.add(mySlot(profile));
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---- Account sync

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile boolean dirty = true, busy;
    private static volatile long lastSync;

    /** Puts this device's counts in the account and reads the others'. At most every 10 minutes unless forced. */
    public static void sync(final String authKey, boolean force, final Runnable done) {
        if (authKey == null || prefs() == null || busy) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastSync < 10 * 60000) return;
        busy = true;
        lastSync = now;
        final String profile = Store.profileId();
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (Stats.class) {
                        flush();
                    }
                    JSONObject r = call("datastoreGet", new JSONObject().put("authKey", authKey).put("collection", "libraryItem")
                            .put("ids", new JSONArray().put(ITEM_ID)).put("all", false));
                    JSONObject devices = new JSONObject();
                    JSONArray items = r.optJSONArray("result");
                    for (int i = 0; items != null && i < items.length(); i++) {
                        JSONObject it = items.optJSONObject(i);
                        if (it == null || !ITEM_ID.equals(it.optString("_id"))) continue;
                        try {
                            JSONObject box = new JSONObject(it.optString("name", "{}"));
                            if (box.optJSONObject("devices") != null) devices = box.optJSONObject("devices");
                        } catch (Exception ignored) {
                        }
                    }
                    JSONObject all = read(REMOTE);
                    all.put(profile, new JSONObject(devices.toString()));
                    write(REMOTE, all);
                    if (dirty || devices.optJSONObject(deviceId()) == null) {
                        devices.put(deviceId(), mySlot(profile));
                        JSONObject box = new JSONObject().put("v", 1).put("devices", devices);
                        call("datastorePut", new JSONObject().put("authKey", authKey).put("collection", "libraryItem")
                                .put("changes", new JSONArray().put(hiddenItem(box.toString()))));
                        dirty = false;
                    }
                } catch (Exception ignored) {
                    lastSync = 0;
                } finally {
                    busy = false;
                    if (done != null) MAIN.post(done);
                }
            }
        });
    }

    private static JSONObject call(String method, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API + method).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream out = c.getOutputStream();
            out.write(body.toString().getBytes("UTF-8"));
            out.close();
            if (c.getResponseCode() != 200) throw new Exception("Stremio answered " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            in.close();
            JSONObject r = new JSONObject(buf.toString("UTF-8"));
            if (r.has("error") && !r.isNull("error")) throw new Exception("Stremio said no");
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject hiddenItem(String name) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String now = f.format(new Date());
        JSONObject st = new JSONObject()
                .put("lastWatched", JSONObject.NULL).put("timeWatched", 0).put("timeOffset", 0)
                .put("overallTimeWatched", 0).put("timesWatched", 0).put("flaggedWatched", 0)
                .put("duration", 0).put("video_id", JSONObject.NULL).put("watched", JSONObject.NULL)
                .put("noNotif", true);
        JSONObject hints = new JSONObject().put("defaultVideoId", JSONObject.NULL)
                .put("featuredVideoId", JSONObject.NULL).put("hasScheduledVideos", false);
        return new JSONObject().put("_id", ITEM_ID).put("name", name).put("type", "other")
                .put("poster", JSONObject.NULL).put("posterShape", "poster")
                .put("removed", true).put("temp", true).put("_ctime", now).put("_mtime", now)
                .put("state", st).put("behaviorHints", hints);
    }

    // ---- What the stats page shows

    public static final class Title {
        public Store.LibraryEntry entry;
        public double ms;
    }

    public static final class Watch {
        public double ms;
        /** All time only: what the Stremio library had before Codio counted (its running totals, less Codio's own). */
        public double before;
        /** When Codio started counting on the first of the profile's devices (ms), 0 when nothing yet. */
        public long since;
        public final List<Title> shows = new ArrayList<>(), movies = new ArrayList<>();
    }

    /** year 0 = all time. lib is the profile's library (names, posters, and the older totals for "before Codio"). */
    public static Watch watch(List<Store.LibraryEntry> lib, String profile, int year) {
        Watch out = new Watch();
        Map<String, double[]> ms = new HashMap<>();
        Map<String, JSONArray> info = new HashMap<>();
        for (JSONObject slot : slots(profile)) {
            long since = slot.optLong("since", 0);
            if (since > 0 && (out.since == 0 || since < out.since)) out.since = since;
            JSONObject watch = slot.optJSONObject("watch");
            if (watch == null) continue;
            for (Iterator<String> it = watch.keys(); it.hasNext(); ) {
                String y = it.next();
                if (year > 0 && !y.equals(String.valueOf(year))) continue;
                JSONObject titles = watch.optJSONObject(y);
                if (titles == null) continue;
                for (Iterator<String> i = titles.keys(); i.hasNext(); ) {
                    String id = i.next();
                    JSONArray e = titles.optJSONArray(id);
                    if (e == null) continue;
                    double[] v = ms.get(id);
                    if (v == null) ms.put(id, v = new double[1]);
                    v[0] += e.optDouble(0, 0);
                    info.put(id, e);
                }
            }
        }
        Map<String, Store.LibraryEntry> byId = new HashMap<>();
        for (Store.LibraryEntry e : lib) byId.put(e.id, e);
        for (Map.Entry<String, double[]> x : ms.entrySet()) {
            double t = x.getValue()[0];
            if (t < 60000) continue;
            JSONArray e = info.get(x.getKey());
            Store.LibraryEntry le = byId.get(x.getKey());
            if (le == null || le.meta == null) {
                le = new Store.LibraryEntry();
                le.id = x.getKey();
                le.type = e.optString(1, "movie");
                try {
                    JSONObject m = new JSONObject().put("id", le.id).put("type", le.type).put("name", e.optString(2, ""));
                    if (!e.isNull(3)) m.put("poster", e.optString(3));
                    le.meta = Store.Meta.parse(m);
                    le.meta.id = le.id;
                } catch (Exception ex) {
                    continue;
                }
            }
            String type = le.type != null && !le.type.isEmpty() ? le.type : e.optString(1, "movie");
            if (!"movie".equals(type) && !"series".equals(type)) continue;
            out.ms += t;
            Title ti = new Title();
            ti.entry = le;
            ti.ms = t;
            ("series".equals(type) ? out.shows : out.movies).add(ti);
        }
        if (year == 0) {
            // Older viewing: the library's own running total for each title, less what Codio counted for it.
            for (Store.LibraryEntry e : lib) {
                if (!("movie".equals(e.type) || "series".equals(e.type)) || e.overall <= 0) continue;
                double[] v = ms.get(e.id);
                out.before += Math.max(0, e.overall - (v != null ? v[0] : 0));
            }
        }
        Comparator<Title> byTime = new Comparator<Title>() {
            @Override
            public int compare(Title a, Title b) {
                return Double.compare(b.ms, a.ms);
            }
        };
        Collections.sort(out.shows, byTime);
        Collections.sort(out.movies, byTime);
        return out;
    }

    /** Hours with one decimal under 10 ("2.5", "14"). */
    public static String hours(double ms) {
        double h = ms / 3600000.0;
        if (h < 10) return String.format(Locale.US, "%.1f", h);
        return String.valueOf(Math.round(h));
    }

    public static String count(int n, String word) {
        return n == 1 ? "1 " + word : n + " " + word + "s";
    }

    /** "Counted by Codio on all your devices since 19 Nov 2026." */
    public static String sinceLine(Watch w) {
        if (w.since <= 0) return "Codio counts what plays on all your devices, from now on.";
        return "Counted by Codio on all your devices since " + new SimpleDateFormat("d MMM yyyy", Locale.US).format(new Date(w.since)) + ".";
    }

    public static final class Song {
        public Model.Track track;
        public int count;
    }

    public static final class Artist {
        public String name;
        public int count;
    }

    public static final class Listen {
        public double seconds;
        public int plays;
        public final List<Song> songs = new ArrayList<>();
        public final List<Artist> artists = new ArrayList<>();
    }

    /** year 0 = all years added together; every device of the profile. */
    public static Listen listen(int year) {
        Listen out = new Listen();
        Map<String, Song> songs = new HashMap<>();
        Map<String, Artist> artists = new HashMap<>();
        for (JSONObject slot : slots(Store.profileId())) {
            JSONObject listen = slot.optJSONObject("listen");
            if (listen == null) continue;
            for (Iterator<String> it = listen.keys(); it.hasNext(); ) {
                String k = it.next();
                if (year > 0 && !k.equals(String.valueOf(year))) continue;
                JSONObject y = listen.optJSONObject(k);
                if (y == null) continue;
                out.plays += y.optInt("p");
                out.seconds += y.optDouble("s", 0);
                JSONObject ss = y.optJSONObject("songs");
                if (ss != null) for (Iterator<String> i = ss.keys(); i.hasNext(); ) {
                    String sk = i.next();
                    JSONArray v = ss.optJSONArray(sk);
                    if (v == null) continue;
                    Song s = songs.get(sk);
                    if (s == null) {
                        Model.Track t = Model.Track.fromJson(v.optJSONObject(1));
                        if (t == null) continue;
                        s = new Song();
                        s.track = t;
                        songs.put(sk, s);
                    }
                    s.count += v.optInt(0);
                }
                JSONObject as = y.optJSONObject("artists");
                if (as != null) for (Iterator<String> i = as.keys(); i.hasNext(); ) {
                    String ak = i.next();
                    JSONArray v = as.optJSONArray(ak);
                    if (v == null) continue;
                    Artist a = artists.get(ak);
                    if (a == null) {
                        a = new Artist();
                        a.name = v.optString(1, ak);
                        artists.put(ak, a);
                    }
                    a.count += v.optInt(0);
                }
            }
        }
        out.songs.addAll(songs.values());
        out.artists.addAll(artists.values());
        Collections.sort(out.songs, new Comparator<Song>() {
            @Override
            public int compare(Song a, Song b) {
                return b.count - a.count;
            }
        });
        Collections.sort(out.artists, new Comparator<Artist>() {
            @Override
            public int compare(Artist a, Artist b) {
                return b.count - a.count;
            }
        });
        return out;
    }
}
