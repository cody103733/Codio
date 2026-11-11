package com.codio.tv.ui;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The actors in a title, with their photos, from TMDB. Falls back to the names the addon gave when TMDB can't help. */
public final class CastInfo {
    static final class Actor {
        final String name, role, photo;

        Actor(String name, String role, String photo) {
            this.name = name;
            this.role = role;
            this.photo = photo;
        }
    }

    interface Done {
        void done(List<Actor> actors);
    }

    /** The notice TMDB requires wherever its data or images are shown. */
    static final String CREDIT = "This product uses the TMDB API but is not endorsed or certified by TMDB.";

    /** The viewer's own TMDB API key, from Settings (pref tmdb_key). Not built into the app. */
    private static android.content.SharedPreferences prefs;

    public static void init(android.content.SharedPreferences p) {
        prefs = p;
    }

    static String key() {
        String k = prefs != null ? prefs.getString("tmdb_key", "").trim() : "";
        return k.isEmpty() ? null : k;
    }

    /** A TMDB v3 API key is 32 letters and digits. */
    static boolean looksLikeKey(String k) {
        return k != null && k.trim().matches("[0-9a-fA-F]{32}");
    }
    private static final String API = "https://api.themoviedb.org/3/";
    private static final String PHOTOS = "https://image.tmdb.org/t/p/w185";
    private static final int MAX = 40;
    private static final Map<String, List<Actor>> cache = new HashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** done() runs on the main thread and always gets a list, empty when nothing is known. */
    static void load(final Store.Meta meta, String videoId, final Done done) {
        final String imdb = SkipTimes.imdbOf(meta, videoId);
        final String tmdb = meta.raw != null ? meta.raw.optString("moviedb_id", "") : "";
        final boolean series = !"movie".equals(meta.type);
        final String cacheKey = imdb != null ? imdb : meta.id;
        List<Actor> known = cache.get(cacheKey);
        if (known != null) {
            done.done(known);
            return;
        }
        final List<String> names = new ArrayList<>(meta.cast);
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                List<Actor> found = null;
                try {
                    found = fetch(imdb, tmdb, series);
                } catch (Exception e) {
                    Log.w(TvView.TAG, "cast: lookup failed (" + e.getClass().getSimpleName() + ")");
                }
                final List<Actor> fromTmdb = found;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fromTmdb != null && !fromTmdb.isEmpty()) {
                            cache.put(cacheKey, fromTmdb);
                            done.done(fromTmdb);
                            return;
                        }
                        // Not remembered, so a later try can still get photos.
                        List<Actor> plain = new ArrayList<>();
                        for (String n : names) if (n != null && !n.isEmpty()) plain.add(new Actor(n, null, null));
                        done.done(plain);
                    }
                });
            }
        });
    }

    private static List<Actor> fetch(String imdb, String tmdb, boolean series) throws Exception {
        if (key() == null) return null; // no key in Settings: the names the addon gave
        String path = null;
        if (imdb != null) {
            JSONObject f = new JSONObject(Net.get(API + "find/" + imdb + "?external_source=imdb_id&api_key=" + key()));
            JSONObject show = first(f.optJSONArray("tv_results")), movie = first(f.optJSONArray("movie_results"));
            JSONObject preferred = series ? show : movie, other = series ? movie : show;
            if (preferred != null) path = (series ? "tv/" : "movie/") + preferred.optInt("id");
            else if (other != null) path = (series ? "movie/" : "tv/") + other.optInt("id");
        }
        if (path == null && tmdb.matches("\\d+")) path = (series ? "tv/" : "movie/") + tmdb;
        if (path == null) return null;
        boolean tv = path.startsWith("tv/");
        JSONArray cast = new JSONObject(Net.get(API + path + (tv ? "/aggregate_credits" : "/credits") + "?api_key=" + key())).optJSONArray("cast");
        List<Actor> out = new ArrayList<>();
        if (cast == null) return out;
        for (int i = 0; i < cast.length() && out.size() < MAX; i++) {
            JSONObject a = cast.optJSONObject(i);
            if (a == null || a.optString("name", "").isEmpty()) continue;
            String role = a.optString("character", "");
            JSONArray roles = a.optJSONArray("roles");
            if (roles != null && roles.length() > 0 && roles.optJSONObject(0) != null) role = roles.optJSONObject(0).optString("character", "");
            String photo = a.optString("profile_path", "");
            out.add(new Actor(a.optString("name"), role.isEmpty() ? null : role, photo.startsWith("/") ? PHOTOS + photo : null));
        }
        return out;
    }

    private static JSONObject first(JSONArray a) {
        return a != null && a.length() > 0 ? a.optJSONObject(0) : null;
    }

    private CastInfo() {
    }
}
