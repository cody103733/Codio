package com.codio.tv.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * New episode alerts (Settings, Playback): shows you're watching get a "New episode" mark on Home and in the Library
 * when an episode came out after you last watched them, and phones also get a notification.
 *
 * The shows come from the library (watched in the last six months, or added to the Library); the episode dates come
 * from the catalog addons that answer "lastVideosIds" (Cinemeta does). What was found is kept in a small file, so the
 * background check on phones works without the rest of the app.
 */
public final class NewEpisodes {
    private NewEpisodes() {}

    private static final String TAG = "CodioNewEpisodes";
    /** Pref (the app's "codio" preferences): "on" or "off". */
    public static final String PREF = "new_episode_alerts";
    private static final long HOUR = 3600_000L, DAY = 24 * HOUR;
    /** Ask the addons again after this long. */
    private static final long CHECK_EVERY = 3 * HOUR;
    /** An episode counts as new for this long after it came out. */
    private static final long FRESH = 30 * DAY;
    private static final long WATCHING = 180 * DAY;
    private static final int MAX_SHOWS = 50;

    /** The newest new episode of one show. */
    public static final class Alert {
        public String showId, type, name, poster, videoId, title;
        public int season, episode;
        /** How many new episodes the show has. */
        public int count;
        public long released;

        /** "S2 E5 · Title" */
        public String episodeLine() {
            return "S" + season + " E" + episode + (title == null || title.isEmpty() ? "" : " · " + title);
        }
    }

    private static final Object LOCK = new Object();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static JSONObject state;
    private static volatile Map<String, Alert> alerts = new HashMap<>();
    private static boolean checking;

    public static boolean enabled(Context c) {
        return !"off".equals(prefs(c).getString(PREF, "on"));
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("codio", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ what the screens ask

    /** The alert for a show, or null. Cheap: called while drawing. */
    public static Alert of(String showId) {
        return showId == null ? null : alerts.get(showId);
    }

    /** Shows with a new episode, the newest first. */
    public static List<Alert> all() {
        List<Alert> l = new ArrayList<>(alerts.values());
        Collections.sort(l, new Comparator<Alert>() {
            @Override
            public int compare(Alert a, Alert b) {
                return Long.compare(b.released, a.released);
            }
        });
        return l;
    }

    /** The viewer opened the show's page: its mark goes away until another episode comes out. */
    public static void seen(Context c, String showId) {
        if (showId == null || !alerts.containsKey(showId)) return;
        synchronized (LOCK) {
            try {
                JSONObject st = state(c);
                JSONObject seen = st.optJSONObject("seen");
                if (seen == null) st.put("seen", seen = new JSONObject());
                seen.put(showId, System.currentTimeMillis());
                save(c);
            } catch (Exception ignored) {
            }
            rebuild(c);
        }
    }

    /** The setting changed. */
    public static void settingChanged(Context c) {
        synchronized (LOCK) {
            rebuild(c);
        }
    }

    // ------------------------------------------------------------------ keeping it up to date

    /**
     * Called on the main thread whenever the library was read: remembers which shows to follow and where to ask, and
     * asks the addons when that changed or the last answer is old. changed runs on the main thread when the marks changed.
     */
    public static void sync(final Context ctx, Store store, final Runnable changed) {
        final Context c = ctx.getApplicationContext();
        final JSONArray watch = new JSONArray(), sources = new JSONArray();
        try {
            for (Store.LibraryEntry e : store.followedShows(System.currentTimeMillis() - WATCHING, MAX_SHOWS)) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("type", e.type);
                o.put("name", e.meta != null ? e.meta.name : "");
                if (e.meta != null && e.meta.poster != null) o.put("poster", e.meta.poster);
                // New means: out after you last watched the show (or, never watched, after it went into the Library).
                long watched = parseIso(e.lastWatched);
                o.put("since", watched > 0 ? watched : e.temp ? 0 : parseIso(e.mtime));
                watch.put(o);
            }
            for (Store.Addon a : store.addons()) {
                JSONArray cats = a.manifest.optJSONArray("catalogs");
                if (cats == null) continue;
                for (int i = 0; i < cats.length(); i++) {
                    JSONObject cat = cats.optJSONObject(i);
                    int limit = cat == null ? 0 : lastVideosLimit(cat);
                    if (limit <= 0) continue;
                    JSONObject o = new JSONObject();
                    o.put("base", a.base);
                    o.put("type", cat.optString("type"));
                    o.put("id", cat.optString("id"));
                    o.put("limit", limit);
                    JSONArray prefixes = a.manifest.optJSONArray("idPrefixes");
                    if (prefixes != null) o.put("prefixes", prefixes);
                    sources.put(o);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "library not read: " + e);
            return;
        }
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                boolean stale;
                String before = signature();
                synchronized (LOCK) {
                    try {
                        JSONObject st = state(c);
                        String sig = ids(watch) + "|" + sources;
                        stale = !sig.equals(st.optString("sig")) || System.currentTimeMillis() - st.optLong("checked", 0) > CHECK_EVERY;
                        st.put("watch", watch);
                        st.put("sources", sources);
                        st.put("sig", sig);
                        save(c);
                    } catch (Exception e) {
                        return;
                    }
                    rebuild(c);
                }
                if (stale && enabled(c)) check(c);
                if (!before.equals(signature()) && changed != null) main.post(changed);
            }
        });
    }

    private static String signature() {
        StringBuilder sb = new StringBuilder();
        for (Alert a : all()) sb.append(a.showId).append(':').append(a.videoId).append(':').append(a.count).append(' ');
        return sb.toString();
    }

    private static String ids(JSONArray watch) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < watch.length(); i++) sb.append(watch.optJSONObject(i).optString("id")).append(',');
        return sb.toString();
    }

    /** How many ids one request may carry, when the catalog answers lastVideosIds; 0 when it doesn't. */
    private static int lastVideosLimit(JSONObject cat) {
        JSONArray extra = cat.optJSONArray("extra");
        if (extra != null) {
            for (int i = 0; i < extra.length(); i++) {
                JSONObject e = extra.optJSONObject(i);
                if (e != null && "lastVideosIds".equals(e.optString("name"))) return Math.max(1, Math.min(100, e.optInt("optionsLimit", 1)));
            }
        }
        JSONArray supported = cat.optJSONArray("extraSupported");
        if (supported != null) {
            for (int i = 0; i < supported.length(); i++) if ("lastVideosIds".equals(supported.optString(i))) return 1;
        }
        return 0;
    }

    /**
     * Asks the addons for the followed shows' latest episodes (blocking: call off the main thread). Returns the
     * alerts nobody was told about yet, and remembers them as told.
     */
    public static List<Alert> check(Context ctx) {
        final Context c = ctx.getApplicationContext();
        List<Alert> fresh = new ArrayList<>();
        JSONArray watch, sources;
        synchronized (LOCK) {
            if (checking) return fresh;
            checking = true;
            JSONObject st = state(c);
            watch = st.optJSONArray("watch");
            sources = st.optJSONArray("sources");
        }
        try {
            if (watch == null || sources == null || watch.length() == 0 || sources.length() == 0) return fresh;
            JSONObject found = new JSONObject();
            boolean answered = false;
            for (int s = 0; s < sources.length(); s++) {
                JSONObject src = sources.optJSONObject(s);
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < watch.length(); i++) {
                    JSONObject w = watch.optJSONObject(i);
                    String id = w.optString("id");
                    if (found.has(id) || !src.optString("type").equals(w.optString("type")) || !fits(id, src.optJSONArray("prefixes"))) continue;
                    ids.add(id);
                }
                int limit = Math.max(1, src.optInt("limit", 1));
                // An addon that takes one id at a time is only asked about the ten most recent shows.
                if (limit < 5 && ids.size() > 10) ids = ids.subList(0, 10);
                for (int from = 0; from < ids.size(); from += limit) {
                    StringBuilder list = new StringBuilder();
                    for (String id : ids.subList(from, Math.min(ids.size(), from + limit))) list.append(list.length() > 0 ? "," : "").append(id);
                    String url = src.optString("base") + "/catalog/" + Store.enc(src.optString("type")) + "/" + Store.enc(src.optString("id"))
                            + "/lastVideosIds=" + Store.enc(list.toString()) + ".json";
                    try {
                        JSONObject r = new JSONObject(Net.get(url));
                        JSONArray metas = r.optJSONArray("metasDetailed");
                        if (metas == null) metas = r.optJSONArray("metas");
                        answered = true;
                        for (int i = 0; metas != null && i < metas.length(); i++) {
                            JSONObject m = metas.optJSONObject(i);
                            if (m == null) continue;
                            JSONArray keep = new JSONArray();
                            JSONArray videos = m.optJSONArray("videos");
                            long now = System.currentTimeMillis();
                            for (int v = 0; videos != null && v < videos.length(); v++) {
                                JSONObject video = videos.optJSONObject(v);
                                if (video == null) continue;
                                long released = parseIso(video.optString("released", null));
                                int season = video.optInt("season", 0);
                                // Only what could still be news: no specials, nothing older than a month, nothing unaired.
                                if (season <= 0 || released <= 0 || released > now || released < now - FRESH) continue;
                                JSONObject k = new JSONObject();
                                k.put("id", video.optString("id"));
                                k.put("season", season);
                                k.put("episode", video.optInt("episode", video.optInt("number", 0)));
                                k.put("title", video.optString("title", video.optString("name", "")));
                                k.put("released", released);
                                keep.put(k);
                            }
                            found.put(m.optString("id", m.optString("_id")), keep);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "no answer from " + Net.shortUrl(url) + ": " + e);
                    }
                }
            }
            if (!answered) return fresh;
            synchronized (LOCK) {
                JSONObject st = state(c);
                st.put("found", found);
                st.put("checked", System.currentTimeMillis());
                rebuild(c);
                JSONObject told = st.optJSONObject("told");
                if (told == null) st.put("told", told = new JSONObject());
                long now = System.currentTimeMillis();
                // Forget what was told long ago, so the file stays small.
                JSONArray names = told.names();
                for (int i = 0; names != null && i < names.length(); i++) {
                    if (now - told.optLong(names.optString(i)) > 3 * FRESH) told.remove(names.optString(i));
                }
                for (Alert a : all()) {
                    if (told.has(a.videoId)) continue;
                    told.put(a.videoId, now);
                    fresh.add(a);
                }
                save(c);
            }
            Log.i(TAG, "checked " + watch.length() + " shows: " + alerts.size() + " with a new episode, " + fresh.size() + " not told yet");
        } catch (Exception e) {
            Log.w(TAG, "check failed: " + e);
        } finally {
            synchronized (LOCK) {
                checking = false;
            }
        }
        return fresh;
    }

    private static boolean fits(String id, JSONArray prefixes) {
        if (prefixes == null || prefixes.length() == 0) return true;
        for (int i = 0; i < prefixes.length(); i++) if (id.startsWith(prefixes.optString(i))) return true;
        return false;
    }

    // ------------------------------------------------------------------ state

    /** Works the alerts out again from what was found, what was watched and what was seen. Hold LOCK. */
    private static void rebuild(Context c) {
        Map<String, Alert> out = new HashMap<>();
        JSONObject st = state(c);
        JSONArray watch = st.optJSONArray("watch");
        JSONObject found = st.optJSONObject("found"), seen = st.optJSONObject("seen");
        if (enabled(c) && watch != null && found != null) {
            long now = System.currentTimeMillis();
            for (int i = 0; i < watch.length(); i++) {
                JSONObject w = watch.optJSONObject(i);
                String id = w.optString("id");
                JSONArray videos = found.optJSONArray(id);
                if (videos == null) continue;
                long since = Math.max(w.optLong("since", 0), seen != null ? seen.optLong(id, 0) : 0);
                if (since <= 0) continue;
                Alert a = null;
                int count = 0;
                for (int v = 0; v < videos.length(); v++) {
                    JSONObject video = videos.optJSONObject(v);
                    long released = video.optLong("released", 0);
                    if (released <= since || released < now - FRESH || released > now) continue;
                    count++;
                    if (a != null && released < a.released) continue;
                    if (a == null) a = new Alert();
                    a.videoId = video.optString("id");
                    a.season = video.optInt("season");
                    a.episode = video.optInt("episode");
                    a.title = video.optString("title");
                    a.released = released;
                }
                if (a == null) continue;
                a.showId = id;
                a.type = w.optString("type", "series");
                a.name = w.optString("name");
                a.poster = w.has("poster") ? w.optString("poster") : null;
                a.count = count;
                out.put(id, a);
            }
        }
        alerts = out;
    }

    private static JSONObject state(Context c) {
        if (state != null) return state;
        try {
            File f = file(c);
            if (f.exists()) {
                byte[] b = new byte[(int) f.length()];
                FileInputStream in = new FileInputStream(f);
                int off = 0, r;
                while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
                in.close();
                state = new JSONObject(new String(b, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "saved alerts unreadable: " + e);
        }
        if (state == null) state = new JSONObject();
        rebuild(c);
        return state;
    }

    /** Reads last run's alerts, so the marks are there before the library has loaded. */
    public static void load(Context c) {
        synchronized (LOCK) {
            state(c.getApplicationContext());
        }
    }

    private static void save(Context c) {
        try {
            FileOutputStream out = new FileOutputStream(file(c));
            out.write(state.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception e) {
            Log.w(TAG, "alerts not saved: " + e);
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "new-episodes.json");
    }

    /** Milliseconds for "2026-11-10T03:12:45.123Z" (also without the time), 0 when it isn't a date. */
    static long parseIso(String s) {
        if (s == null || s.length() < 10) return 0;
        try {
            boolean time = s.length() >= 19 && s.charAt(10) == 'T';
            SimpleDateFormat f = new SimpleDateFormat(time ? "yyyy-MM-dd'T'HH:mm:ss" : "yyyy-MM-dd", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(s.substring(0, time ? 19 : 10)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }
}
