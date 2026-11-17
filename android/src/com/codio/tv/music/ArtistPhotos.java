package com.codio.tv.music;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/**
 * An artist's picture. MusicBrainz has none, so: Deezer's photo of the artist with exactly that name, else the cover of
 * their most played album (ListenBrainz). What was found is kept for a month (a miss for a day)
 * (windows/desktop/codio-music.js artistPicture). Blocking: run it on MusicNet.POOL.
 */
public final class ArtistPhotos {
    private ArtistPhotos() {}

    private static final String DEEZER = "https://api.deezer.com";

    private static String plain(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.US)
                .replace("&", "and").replaceAll("[^a-z0-9]+", "");
    }

    private static String deezer(String name) throws Exception {
        MusicNet.spaced(DEEZER, 120);
        MusicNet.Reply r = MusicNet.get(DEEZER + "/search/artist?q=" + URLEncoder.encode(name, "UTF-8") + "&limit=10", 15000);
        JSONObject j = r.status == 200 ? r.object() : null;
        JSONArray list = j == null ? null : j.optJSONArray("data");
        if (list == null) return null;
        String wanted = plain(name);
        JSONObject best = null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject a = list.optJSONObject(i);
            if (a == null || !plain(a.optString("name")).equals(wanted)) continue;
            if (best == null || a.optLong("nb_fan") > best.optLong("nb_fan")) best = a;
        }
        if (best == null) return null;
        String url = Model.str(best, "picture_big");
        if (url == null) url = Model.str(best, "picture_medium");
        // Deezer's stand-in for "no photo" has an empty picture id (/images/artist//...).
        return url != null && url.startsWith("https://") && !url.contains("/artist//") ? url : null;
    }

    private static String albumCover(String id) throws Exception {
        MusicNet.spaced(Discovery.LB, 1000);
        MusicNet.Reply r = MusicNet.get(Discovery.LB + "/1/popularity/top-release-groups-for-artist/" + id, 15000);
        if (r.status != 200) return null;
        Object j = r.json();
        if (!(j instanceof JSONArray)) return null;
        JSONArray l = (JSONArray) j;
        for (int i = 0; i < l.length(); i++) {
            JSONObject g = l.optJSONObject(i);
            JSONObject rg = g == null ? null : g.optJSONObject("release_group");
            if (rg != null && Model.str(rg, "caa_release_mbid") != null) {
                return "https://coverartarchive.org/release/" + rg.optString("caa_release_mbid") + "/front-500";
            }
        }
        return null;
    }

    /** The picture's address, or null when there is none. */
    public static String find(String artistMbid, String name) {
        if (!MusicBrainz.isMbid(artistMbid)) return null;
        String key = "artist-picture:" + artistMbid;
        byte[] cached = MusicNet.readCache(key, 30 * MusicNet.DAY);
        if (cached != null) {
            try {
                JSONObject saved = new JSONObject(new String(cached, StandardCharsets.UTF_8));
                if (Model.str(saved, "url") != null) return saved.optString("url");
                if (System.currentTimeMillis() - saved.optLong("at", 0) < MusicNet.DAY) return null;
            } catch (Exception ignored) {
            }
        }
        String url = null;
        try {
            if (name != null && name.length() > 0) url = deezer(name);
        } catch (Exception ignored) {
        }
        try {
            if (url == null) url = albumCover(artistMbid);
        } catch (Exception ignored) {
        }
        try {
            JSONObject o = new JSONObject().put("at", System.currentTimeMillis());
            if (url != null) o.put("url", url);
            MusicNet.writeCache(key, o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        return url;
    }
}
