package com.codio.tv.music;

import com.codio.tv.music.Model.Album;
import com.codio.tv.music.Model.Artist;
import com.codio.tv.music.Model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What to listen to, without any account: what is popular right now, new releases, and artists like the ones you played
 * (windows/src/routes/Codio/music/discovery.ts). Blocking calls, answers cached on disk for hours or days.
 */
public final class Discovery {
    private Discovery() {}

    static final String LB = "https://api.listenbrainz.org";
    static final String LABS = "https://labs.api.listenbrainz.org";
    private static final long HOURS6 = 6 * 3600 * 1000L;

    private static Object lb(String path, boolean labs) {
        try {
            long ttl = labs ? 7 * MusicNet.DAY : path.startsWith("/1/popularity/") ? MusicNet.DAY : HOURS6;
            return MusicNet.metadata(labs ? LABS : LB, path, ttl);
        } catch (Exception e) {
            return null;
        }
    }

    private static String first(JSONArray a) {
        return a != null && a.length() > 0 && !a.isNull(0) ? a.optString(0) : null;
    }

    private static JSONArray list(Object j, String... path) {
        Object o = j;
        for (String k : path) o = o instanceof JSONObject ? ((JSONObject) o).opt(k) : null;
        return o instanceof JSONArray ? (JSONArray) o : new JSONArray();
    }

    /** Albums people are playing this week. */
    public static List<Album> popularAlbums() {
        JSONArray l = list(lb("/1/stats/sitewide/release-groups?range=week&count=25", false), "payload", "release_groups");
        List<Album> out = new ArrayList<>();
        for (int i = 0; i < l.length(); i++) {
            JSONObject g = l.optJSONObject(i);
            if (g == null || Model.str(g, "release_group_mbid") == null) continue;
            Album a = new Album();
            a.id = g.optString("release_group_mbid");
            a.title = g.optString("release_group_name", "");
            a.artist = g.optString("artist_name", "");
            a.artworkURL = MusicBrainz.coverUrl(a.id);
            a.artistMbid = first(g.optJSONArray("artist_mbids"));
            out.add(a);
        }
        return out;
    }

    /** Albums out in the last two weeks. */
    public static List<Album> newReleases() {
        Object j = lb("/1/explore/fresh-releases/?days=14&past=true&future=false&sort=release_date", false);
        JSONArray items = j instanceof JSONArray ? (JSONArray) j : list(j, "payload", "releases");
        if (items.length() == 0 && j instanceof JSONObject) items = list(j, "releases");
        final List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r == null || Model.str(r, "release_group_mbid") == null) continue;
            String type = Model.str(r, "release_group_primary_type");
            if (type == null || "Album".equals(type) || "EP".equals(type)) rows.add(r);
        }
        Collections.sort(rows, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return b.optString("release_date", "").compareTo(a.optString("release_date", ""));
            }
        });
        Set<String> seen = new HashSet<>();
        List<Album> out = new ArrayList<>();
        for (JSONObject r : rows) {
            if (!seen.add(r.optString("release_group_mbid")) || out.size() >= 300) continue;
            Album a = new Album();
            a.id = r.optString("release_group_mbid");
            a.title = r.optString("release_name", "");
            a.artist = r.optString("artist_credit_name", "");
            a.artistMbid = first(r.optJSONArray("artist_mbids"));
            String date = Model.str(r, "release_date");
            a.year = date != null && date.length() >= 4 ? date.substring(0, 4) : null;
            a.kind = Model.str(r, "release_group_primary_type");
            a.artworkURL = MusicBrainz.coverUrl(a.id);
            out.add(a);
        }
        return out;
    }

    /** Songs people are playing this week. */
    public static List<Track> popularSongs() {
        JSONArray l = list(lb("/1/stats/sitewide/recordings?range=week&count=25", false), "payload", "recordings");
        List<Track> out = new ArrayList<>();
        for (int i = 0; i < l.length(); i++) {
            JSONObject r = l.optJSONObject(i);
            if (r == null || Model.str(r, "recording_mbid") == null || Model.str(r, "track_name") == null) continue;
            Track t = new Track();
            t.mbid = r.optString("recording_mbid");
            t.id = "mbid:" + t.mbid;
            t.title = r.optString("track_name");
            t.artist = r.optString("artist_name", "");
            t.album = Model.str(r, "release_name");
            t.artworkURL = Model.str(r, "release_mbid") != null ? MusicBrainz.releaseCoverUrl(r.optString("release_mbid")) : null;
            t.artistMbid = first(r.optJSONArray("artist_mbids"));
            out.add(t);
        }
        return out;
    }

    /** Artists people are playing this month. */
    public static List<Artist> popularArtists() {
        JSONArray l = list(lb("/1/stats/sitewide/artists?range=month&count=30", false), "payload", "artists");
        List<Artist> out = new ArrayList<>();
        for (int i = 0; i < l.length() && out.size() < 20; i++) {
            JSONObject a = l.optJSONObject(i);
            if (a == null || Model.str(a, "artist_mbid") == null || Model.str(a, "artist_name") == null) continue;
            Artist x = new Artist();
            x.id = a.optString("artist_mbid");
            x.name = a.optString("artist_name");
            out.add(x);
        }
        return out;
    }

    private static final String SIMILAR = "session_based_days_9000_session_300_contribution_5_threshold_15_limit_50_skip_30";

    private static void walk(Object x, List<JSONObject> flat) {
        if (x instanceof JSONArray) {
            JSONArray a = (JSONArray) x;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), flat);
        } else if (x instanceof JSONObject) {
            JSONObject o = (JSONObject) x;
            if (Model.str(o, "artist_mbid") != null && Model.str(o, "name") != null) {
                flat.add(o);
                return;
            }
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                Object v = o.opt(keys.next());
                if (v instanceof JSONArray) walk(v, flat);
            }
        }
    }

    /** Artists like this one (ListenBrainz Labs). Empty if the service doesn't answer. */
    public static List<Artist> similarArtists(String artistMbid) {
        Object j = lb("/similar-artists/json?artist_mbids=" + artistMbid + "&algorithm=" + SIMILAR, true);
        List<JSONObject> flat = new ArrayList<>();
        walk(j, flat);
        List<Artist> out = new ArrayList<>();
        for (JSONObject a : flat) {
            if (a.optString("artist_mbid").equals(artistMbid) || out.size() >= 20) continue;
            Artist x = new Artist();
            x.id = a.optString("artist_mbid");
            x.name = a.optString("name");
            String type = Model.str(a, "type"), comment = Model.str(a, "comment");
            x.detail = type != null && comment != null ? type + " · " + comment : type != null ? type : comment;
            out.add(x);
        }
        return out;
    }

    /** More albums by an artist, most played first. */
    public static List<Album> moreFrom(String artistMbid) {
        Object j = lb("/1/popularity/top-release-groups-for-artist/" + artistMbid, false);
        JSONArray l = j instanceof JSONArray ? (JSONArray) j : new JSONArray();
        List<Album> out = new ArrayList<>();
        for (int i = 0; i < l.length() && out.size() < 20; i++) {
            JSONObject g = l.optJSONObject(i);
            if (g == null || Model.str(g, "release_group_mbid") == null) continue;
            JSONObject rg = g.optJSONObject("release_group"), ar = g.optJSONObject("artist");
            Album a = new Album();
            a.id = g.optString("release_group_mbid");
            a.title = rg != null ? rg.optString("name", "") : "";
            a.artist = ar != null ? ar.optString("name", "") : "";
            String date = rg != null ? Model.str(rg, "date") : null;
            a.year = date != null && date.length() >= 4 ? date.substring(0, 4) : null;
            a.artworkURL = rg != null && !rg.isNull("caa_id") && Model.str(rg, "caa_release_mbid") != null
                    ? "https://coverartarchive.org/release/" + rg.optString("caa_release_mbid") + "/front-250" : MusicBrainz.coverUrl(a.id);
            out.add(a);
        }
        return out;
    }
}
