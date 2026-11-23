package com.codio.tv.ui;

import android.content.SharedPreferences;
import android.util.Log;

import com.codio.tv.music.MusicNet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Watching a download counts toward the account like streaming does. A download plays straight from the phone, so the
 * core (which saves progress for streams) never hears about it. Instead this keeps a small queue on the phone
 * (prefs offline_progress_v1: profile -> library id -> where you stopped), and as soon as there is internet and a
 * signed-in account it writes that into the account's library item (Continue Watching, resume point, watched time),
 * then asks the core to pull the library so every screen and device shows it. Nothing is lost while offline.
 */
public final class OfflineProgress {
    private OfflineProgress() {}

    private static final String TAG = "CodioOfflineProgress";
    private static final String KEY = "offline_progress_v1";
    private static final String API = "https://api.strem.io/api/";

    /** Called on the main thread after the account was updated (the phone asks the core to sync its library). */
    public static Runnable onPushed;

    private static volatile boolean busy;

    // ---- Counting what really plays (forward steps of up to 3 s; pauses and seeks add nothing)

    private static String playing;
    private static double lastPos = -1;
    private static long playedMs;

    public static synchronized void tick(String videoId, double pos) {
        if (videoId == null) return;
        if (!videoId.equals(playing)) {
            playing = videoId;
            playedMs = 0;
            lastPos = pos;
            return;
        }
        double d = pos - lastPos;
        lastPos = pos;
        if (d > 0 && d <= 3) playedMs += Math.round(d * 1000);
    }

    private static synchronized long takePlayed(String videoId) {
        if (!videoId.equals(playing)) return 0;
        long ms = playedMs;
        playedMs = 0;
        return ms;
    }

    // ---- The queue

    private static SharedPreferences prefs() {
        return MusicNet.prefs();
    }

    private static JSONObject read() {
        SharedPreferences p = prefs();
        try {
            return new JSONObject(p != null ? p.getString(KEY, "{}") : "{}");
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void write(JSONObject all) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString(KEY, all.toString()).apply();
    }

    /**
     * Where a download stopped (seconds). Called every 15 s while it plays, when it's closed and when it ends.
     * The latest one for a title wins; the played time adds up until it reaches the account.
     */
    public static void record(Store.Meta m, String videoId, double pos, double dur, boolean ended) {
        if (m == null || m.id == null || videoId == null || prefs() == null) return;
        String profile = Store.profileId();
        long add = takePlayed(videoId);
        synchronized (OfflineProgress.class) {
            try {
                JSONObject all = read();
                JSONObject mine = all.optJSONObject(profile);
                if (mine == null) all.put(profile, mine = new JSONObject());
                JSONObject q = mine.optJSONObject(m.id);
                long before = 0;
                if (q != null && videoId.equals(q.optString("video"))) before = q.optLong("add");
                else if (q != null) mine.put(m.id + "#" + q.optString("video"), q); // an earlier episode: still sent, first
                boolean done = ended || (dur > 0 && pos / dur > 0.9);
                if (q != null && videoId.equals(q.optString("video")) && q.optBoolean("done")) done = true;
                q = new JSONObject()
                        .put("type", m.type).put("name", m.name != null ? m.name : "")
                        .put("poster", m.poster != null ? m.poster : JSONObject.NULL)
                        .put("video", videoId)
                        .put("offset", done ? 0 : Math.round(Math.max(0, pos) * 1000))
                        .put("dur", Math.round(Math.max(0, dur) * 1000))
                        .put("add", before + add)
                        .put("done", done)
                        .put("at", System.currentTimeMillis());
                mine.put(m.id, q);
                write(all);
            } catch (Exception e) {
                Log.w(TAG, "couldn't save progress", e);
            }
        }
    }

    /** True while something is waiting to reach the account. */
    public static boolean pending() {
        JSONObject mine = read().optJSONObject(Store.profileId());
        return mine != null && mine.length() > 0;
    }

    // ---- Into the account

    /** Puts everything waiting into the account. Quietly keeps it for later when there is no internet. */
    public static void flush(final String authKey) {
        if (authKey == null || prefs() == null || busy || !pending()) return;
        busy = true;
        final String profile = Store.profileId();
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                boolean pushed = false;
                try {
                    final JSONObject mine;
                    synchronized (OfflineProgress.class) {
                        mine = read().optJSONObject(profile);
                    }
                    if (mine == null) return;
                    List<String> keys = new ArrayList<>();
                    for (Iterator<String> it = mine.keys(); it.hasNext(); ) keys.add(it.next());
                    JSONArray ids = new JSONArray();
                    for (String k : keys) ids.put(itemId(k));
                    JSONObject r = call("datastoreGet", new JSONObject().put("authKey", authKey).put("collection", "libraryItem")
                            .put("ids", ids).put("all", false));
                    JSONObject remote = new JSONObject();
                    JSONArray items = r.optJSONArray("result");
                    for (int i = 0; items != null && i < items.length(); i++) {
                        JSONObject it = items.optJSONObject(i);
                        if (it != null) remote.put(it.optString("_id"), it);
                    }
                    // Older finished episodes first, so the title's latest one is what the item ends on.
                    java.util.Collections.sort(keys, new java.util.Comparator<String>() {
                        @Override
                        public int compare(String a, String b) {
                            return Long.compare(mine.optJSONObject(a).optLong("at"), mine.optJSONObject(b).optLong("at"));
                        }
                    });
                    JSONArray changes = new JSONArray();
                    for (String k : keys) {
                        String id = itemId(k);
                        JSONObject item = apply(remote.optJSONObject(id), id, mine.optJSONObject(k));
                        if (item != null) remote.put(id, item);
                    }
                    for (Iterator<String> it = remote.keys(); it.hasNext(); ) {
                        JSONObject item = remote.optJSONObject(it.next());
                        if (item != null && item.optBoolean("__changed")) {
                            item.remove("__changed");
                            changes.put(item);
                        }
                    }
                    if (changes.length() > 0) {
                        call("datastorePut", new JSONObject().put("authKey", authKey).put("collection", "libraryItem")
                                .put("changes", changes));
                        pushed = true;
                    }
                    // Done: drop what was sent, unless it was watched further meanwhile.
                    synchronized (OfflineProgress.class) {
                        JSONObject all = read();
                        JSONObject now = all.optJSONObject(profile);
                        for (String k : keys) {
                            JSONObject sent = mine.optJSONObject(k), cur = now != null ? now.optJSONObject(k) : null;
                            if (cur != null && sent != null && cur.optLong("at") == sent.optLong("at")) now.remove(k);
                        }
                        write(all);
                    }
                    Log.i(TAG, "downloads' progress is in the account (" + changes.length() + ")");
                } catch (Exception e) {
                    Log.i(TAG, "progress waits for internet: " + e.getMessage());
                } finally {
                    busy = false;
                    if (pushed && onPushed != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(onPushed);
                }
            }
        });
    }

    private static String itemId(String key) {
        int i = key.indexOf('#');
        return i < 0 ? key : key.substring(0, i);
    }

    /** The library item with this progress in it, or null to leave the account's as it is. */
    static JSONObject apply(JSONObject item, String id, JSONObject q) throws Exception {
        if (q == null) return null;
        long at = q.optLong("at");
        String now = iso(System.currentTimeMillis());
        if (item == null) {
            JSONObject hints = new JSONObject().put("defaultVideoId", JSONObject.NULL)
                    .put("featuredVideoId", JSONObject.NULL).put("hasScheduledVideos", false);
            JSONObject st = new JSONObject()
                    .put("lastWatched", JSONObject.NULL).put("timeWatched", 0).put("timeOffset", 0)
                    .put("overallTimeWatched", 0).put("timesWatched", 0).put("flaggedWatched", 0)
                    .put("duration", 0).put("video_id", JSONObject.NULL).put("watched", JSONObject.NULL)
                    .put("noNotif", false);
            // Like playing something that isn't in the library: it shows in Continue Watching only.
            item = new JSONObject().put("_id", id).put("name", q.optString("name")).put("type", q.optString("type"))
                    .put("poster", q.opt("poster")).put("posterShape", "poster")
                    .put("removed", true).put("temp", true).put("_ctime", now).put("_mtime", now)
                    .put("state", st).put("behaviorHints", hints);
        }
        JSONObject st = item.optJSONObject("state");
        if (st == null) item.put("state", st = new JSONObject());
        long add = q.optLong("add");
        st.put("overallTimeWatched", st.optLong("overallTimeWatched") + add);
        // Watched somewhere else after this: keep that place, only add the time.
        long remoteAt = parse(st.optString("lastWatched", ""));
        boolean newer = remoteAt <= at;
        if (newer) {
            String video = q.optString("video");
            boolean same = video.equals(st.optString("video_id", ""));
            long watched = (same ? st.optLong("timeWatched") : 0) + add;
            long dur = q.optLong("dur");
            if (dur > 0) st.put("duration", dur);
            if (q.optBoolean("done")) {
                if (!same || st.optLong("timeOffset") > 0) st.put("timesWatched", st.optLong("timesWatched") + 1);
                if ("movie".equals(item.optString("type"))) st.put("flaggedWatched", 1);
                watched = 0;
            }
            st.put("video_id", video);
            st.put("timeOffset", q.optLong("offset"));
            st.put("timeWatched", watched);
            st.put("lastWatched", iso(at));
            if (item.optBoolean("removed") && !item.optBoolean("temp")) item.put("temp", true);
        } else if (add <= 0) {
            return null;
        }
        item.put("_mtime", now);
        item.put("__changed", true);
        return item;
    }

    private static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static long parse(String s) {
        if (s == null || s.isEmpty() || "null".equals(s)) return 0;
        String[] forms = {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for (String form : forms) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(form, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                return f.parse(s.length() > 24 ? s.substring(0, 23) + "Z" : s).getTime();
            } catch (ParseException ignored) {
            }
        }
        return 0;
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
}
