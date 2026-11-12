package com.codio.tv.ui;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Keeps the TMDB key with the Stremio account, so every device signed in to it gets the key.
 * Stremio has no place for app settings, so the key rides in a hidden library entry
 * ("codio:tmdb", type other, removed + temp), which Stremio apps never show.
 * The iPhone app uses the same entry (ios/Codio/Store/KeySync.swift).
 */
public final class KeySync {
    private static final String TAG = "CodioKeySync";
    static final String ITEM_ID = "codio:tmdb";
    private static final String API = "https://api.strem.io/api/";
    private static final Set<String> tried = new HashSet<>();

    private KeySync() {
    }

    /** The signed-in account's auth key from the engine profile, or null. */
    public static String authKey(JSONObject profile) {
        JSONObject auth = profile != null ? profile.optJSONObject("auth") : null;
        String k = auth != null ? auth.optString("key", "") : "";
        return k.isEmpty() ? null : k;
    }

    /** After sign-in (and at every start): this device's key goes up, or with none here the account's key comes down. */
    public static void sync(final SharedPreferences prefs, final String authKey, final Runnable onPulled) {
        if (prefs == null || authKey == null) return;
        final String local = prefs.getString("tmdb_key", "").trim();
        synchronized (tried) {
            if (!tried.add(authKey + "|" + local)) return;
        }
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                pullProfiles(prefs, authKey);
                if (CastInfo.looksLikeKey(local)) {
                    pushNow(prefs, local, authKey);
                    return;
                }
                String k = pull(authKey);
                if (k != null && prefs.getString("tmdb_key", "").trim().isEmpty()) {
                    prefs.edit().putString("tmdb_key", k).apply();
                    Log.i(TAG, "TMDB key loaded from the Stremio account");
                    if (onPulled != null) onPulled.run();
                }
            }
        });
    }

    /** Saves the key with the account (in the background). */
    public static void push(final SharedPreferences prefs, final String key, final String authKey) {
        if (prefs == null || authKey == null || !CastInfo.looksLikeKey(key)) return;
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                pushNow(prefs, key, authKey);
            }
        });
    }

    private static void pushNow(SharedPreferences prefs, String key, String authKey) {
        String mark = authKey + "|" + key;
        if (mark.equals(prefs.getString("tmdb_key_pushed", ""))) return;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            String now = f.format(new Date());
            JSONObject state = new JSONObject()
                    .put("lastWatched", JSONObject.NULL).put("timeWatched", 0).put("timeOffset", 0)
                    .put("overallTimeWatched", 0).put("timesWatched", 0).put("flaggedWatched", 0)
                    .put("duration", 0).put("video_id", JSONObject.NULL).put("watched", JSONObject.NULL)
                    .put("noNotif", true);
            JSONObject hints = new JSONObject().put("defaultVideoId", JSONObject.NULL)
                    .put("featuredVideoId", JSONObject.NULL).put("hasScheduledVideos", false);
            JSONObject item = new JSONObject()
                    .put("_id", ITEM_ID).put("name", key).put("type", "other")
                    .put("poster", JSONObject.NULL).put("posterShape", "poster")
                    .put("removed", true).put("temp", true).put("_ctime", now).put("_mtime", now)
                    .put("state", state).put("behaviorHints", hints);
            JSONObject body = new JSONObject().put("authKey", authKey).put("collection", "libraryItem")
                    .put("changes", new JSONArray().put(item));
            JSONObject r = call("datastorePut", body);
            if (r != null && r.has("result")) prefs.edit().putString("tmdb_key_pushed", mark).apply();
        } catch (Exception e) {
            Log.w(TAG, "push failed", e);
        }
    }

    /** The key saved with the account, if any. */
    static String pull(String authKey) {
        String k = itemName(authKey, ITEM_ID);
        k = k != null ? k.trim() : null;
        return CastInfo.looksLikeKey(k) ? k : null;
    }

    // ---- Profiles (made on the iPhone; each is its own Stremio account) ----

    static final String PROFILES_ID = "codio:profiles";

    /** The iPhone's profile list, saved with the account: [{email, name, color, token}], newest copy kept in prefs. */
    private static void pullProfiles(SharedPreferences prefs, String authKey) {
        String s = itemName(authKey, PROFILES_ID);
        if (s == null) return;
        try {
            JSONArray list = new JSONObject(s).optJSONArray("profiles");
            if (list != null && list.length() > 0) prefs.edit().putString("profiles_synced", list.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "profiles unreadable", e);
        }
    }

    /** The synced profiles (may be empty). */
    public static JSONArray profiles(SharedPreferences prefs) {
        try {
            return new JSONArray(prefs.getString("profiles_synced", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** The name field of one hidden entry, or null. */
    private static String itemName(String authKey, String id) {
        try {
            JSONObject body = new JSONObject().put("authKey", authKey).put("collection", "libraryItem")
                    .put("ids", new JSONArray().put(id)).put("all", false);
            JSONObject r = call("datastoreGet", body);
            JSONArray items = r != null ? r.optJSONArray("result") : null;
            if (items == null) return null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null && id.equals(it.optString("_id"))) return it.optString("name", null);
            }
        } catch (Exception e) {
            Log.w(TAG, "read " + id + " failed", e);
        }
        return null;
    }

    private static JSONObject call(String method, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API + method).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream out = c.getOutputStream();
            out.write(body.toString().getBytes("UTF-8"));
            out.close();
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            in.close();
            return new JSONObject(buf.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }
}
