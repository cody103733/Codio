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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

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

    // ---- the country's chart (windows/desktop/codio-music.js buildChart)
    // Apple Music's public "most played" chart for the device's country (no account or key), each song matched to
    // MusicBrainz ids with ListenBrainz's name lookup. ListenBrainz's own sitewide charts only reflect its few thousand
    // listeners, which leans heavily towards a handful of very active fans (mostly J-pop and K-pop).
    static final String CHART = "https://rss.applemarketingtools.com";
    static final String LASTFM = "https://ws.audioscrobbler.com";
    private static String chartKey = "";

    /** "lastfm" when your Last.fm key is set (its chart replaces Apple's), else "apple". */
    public static String chartSource() {
        return MusicNet.lastfmKey().isEmpty() ? "apple" : "lastfm";
    }

    // Last.fm's chart for the country (your own key), as Apple-shaped items. Cached under an address without the key.
    private static JSONArray lastfmFeed(String cc, String key) throws Exception {
        String name = new Locale("", cc.toUpperCase(Locale.ROOT)).getDisplayCountry(Locale.ENGLISH);
        if (name == null || name.isEmpty() || name.equalsIgnoreCase(cc)) name = "United States";
        String p = "/2.0/?method=geo.gettoptracks&country=" + java.net.URLEncoder.encode(name, "UTF-8") + "&limit=50&format=json";
        byte[] body = MusicNet.readCache(LASTFM + p, HOURS6);
        if (body == null) {
            MusicNet.Reply r = MusicNet.get(LASTFM + p + "&api_key=" + java.net.URLEncoder.encode(key, "UTF-8"), 15000);
            if (r.status != 200) return new JSONArray();
            body = r.body;
            if (new JSONObject(new String(body, "UTF-8")).optJSONObject("tracks") == null) return new JSONArray();
            MusicNet.writeCache(LASTFM + p, body);
        }
        JSONArray l = list(new JSONObject(new String(body, "UTF-8")), "tracks", "track");
        JSONArray out = new JSONArray();
        for (int i = 0; i < l.length(); i++) {
            JSONObject t = l.optJSONObject(i);
            if (t == null) continue;
            JSONObject a = t.optJSONObject("artist");
            out.put(new JSONObject().put("name", t.optString("name", "")).put("artistName", a == null ? "" : a.optString("name", "")));
        }
        return out;
    }
    private static final ExecutorService LOOKUPS = Executors.newFixedThreadPool(6);
    private static final Pattern LEAD = Pattern.compile("\\s+(?:&|feat\\.?|ft\\.?|featuring|with|x)\\s+|,\\s+(?!the\\s)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MBID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Object CHART_LOCK = new Object();
    private static List<Track> chartSongs;
    private static List<Artist> chartArtists;
    private static long chartAt;
    private static final java.util.Map<String, String> APPLE = new java.util.concurrent.ConcurrentHashMap<>();

    /** The song's page on Apple Music, for songs from Apple's chart (the chart is meant to send people there). */
    public static String appleMusicUrl(Track t) {
        return t == null || t.mbid == null ? null : APPLE.get(t.mbid);
    }

    /** Opens the song on Apple Music (its app, else the browser). False if nothing on the device can open it. */
    public static boolean openAppleMusic(android.content.Context c, Track t) {
        String url = appleMusicUrl(t);
        if (url == null) return false;
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String country() {
        String c = Locale.getDefault().getCountry();
        return c != null && c.matches("[A-Za-z]{2}") ? c.toLowerCase(Locale.ROOT) : "us";
    }

    private static JSONObject lookupSong(String artist, String title) {
        try {
            String url = LB + "/1/metadata/lookup/?artist_name=" + java.net.URLEncoder.encode(artist, "UTF-8")
                    + "&recording_name=" + java.net.URLEncoder.encode(title, "UTF-8");
            byte[] body = MusicNet.readCache(url, 30 * MusicNet.DAY);
            if (body == null) {
                MusicNet.spaced(LB + "#lookup", 150);
                MusicNet.Reply r = MusicNet.get(url, 15000);
                if (r.status != 200) return null;
                body = r.body;
                MusicNet.writeCache(url, body);
            }
            JSONObject j = new JSONObject(new String(body, "UTF-8"));
            return Model.str(j, "recording_mbid") != null ? j : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONArray chartFeed(String cc) throws Exception {
        String url = CHART + "/api/v2/" + cc + "/music/most-played/50/songs.json";
        byte[] body = MusicNet.readCache(url, HOURS6);
        if (body == null) {
            MusicNet.Reply r = MusicNet.get(url, 15000);
            if (r.status != 200) {
                if (!cc.equals("us")) return chartFeed("us");
                return new JSONArray();
            }
            body = r.body;
            MusicNet.writeCache(url, body);
        }
        return list(new JSONObject(new String(body, "UTF-8")), "feed", "results");
    }

    private static void loadChart() {
        synchronized (CHART_LOCK) {
            String key = MusicNet.lastfmKey();
            if (!key.equals(chartKey)) {
                chartKey = key;
                chartSongs = null;
                APPLE.clear();
            }
            if (chartSongs != null && System.currentTimeMillis() - chartAt < 30 * 60 * 1000L) return;
            List<Track> songs = new ArrayList<>();
            List<Artist> artists = new ArrayList<>();
            try {
                JSONArray items = key.isEmpty() ? chartFeed(country()) : lastfmFeed(country(), key);
                int n = Math.min(items.length(), 40);
                List<Future<JSONObject>> jobs = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    final JSONObject r = items.optJSONObject(i);
                    jobs.add(LOOKUPS.submit(() -> r == null ? null : lookupSong(r.optString("artistName", ""), r.optString("name", ""))));
                }
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < n; i++) {
                    JSONObject m;
                    try {
                        m = jobs.get(i).get();
                    } catch (Exception e) {
                        m = null;
                    }
                    if (m == null) continue;
                    JSONObject r = items.optJSONObject(i);
                    JSONArray idsRaw = m.optJSONArray("artist_mbids");
                    List<String> ids = new ArrayList<>();
                    for (int k = 0; idsRaw != null && k < idsRaw.length(); k++) if (MBID.matcher(idsRaw.optString(k)).matches()) ids.add(idsRaw.optString(k));
                    String credit = m.optString("artist_credit_name", r != null ? r.optString("artistName", "") : "");
                    Track t = new Track();
                    t.mbid = m.optString("recording_mbid");
                    t.id = "mbid:" + t.mbid;
                    t.title = m.optString("recording_name", r != null ? r.optString("name", "") : "");
                    t.artist = credit;
                    t.album = Model.str(m, "release_name");
                    t.artworkURL = Model.str(m, "release_mbid") != null ? MusicBrainz.releaseCoverUrl(m.optString("release_mbid")) : null;
                    t.artistMbid = ids.isEmpty() ? null : ids.get(0);
                    songs.add(t);
                    String link = r != null ? r.optString("url", "") : "";
                    if (link.startsWith("https://music.apple.com/")) APPLE.put(t.mbid, link);
                    if (!ids.isEmpty() && seen.add(ids.get(0))) {
                        Artist a = new Artist();
                        a.id = ids.get(0);
                        a.name = ids.size() == 1 ? credit : LEAD.split(credit)[0].trim();
                        artists.add(a);
                    }
                }
            } catch (Exception ignored) {
                // falls back to ListenBrainz's charts
            }
            chartSongs = songs;
            chartArtists = artists;
            chartAt = songs.isEmpty() ? 0 : System.currentTimeMillis();
        }
    }

    /** Songs people are playing this week: the country's chart, else ListenBrainz's. */
    public static List<Track> popularSongs() {
        loadChart();
        if (chartSongs.size() >= 5) return new ArrayList<>(chartSongs.subList(0, Math.min(25, chartSongs.size())));
        return sitewideSongs();
    }

    /** Artists people are playing now: the ones in the country's chart, else ListenBrainz's. */
    public static List<Artist> popularArtists() {
        loadChart();
        if (chartArtists.size() >= 5) return new ArrayList<>(chartArtists.subList(0, Math.min(20, chartArtists.size())));
        return sitewideArtists();
    }

    /** Songs ListenBrainz's listeners played this week. */
    private static List<Track> sitewideSongs() {
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

    /** Artists ListenBrainz's listeners played this month. */
    private static List<Artist> sitewideArtists() {
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
