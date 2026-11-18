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
    // ---- the country's album chart (windows/desktop/codio-music.js buildAlbums): Apple's most played albums, matched to
    // MusicBrainz release groups by title and artist. Last.fm has no albums chart per country, so this is Apple's always.
    private static final Object ALBUM_LOCK = new Object();
    private static List<Album> albumPopular, albumRecent;
    private static long albumAt;

    private static void loadAlbums() {
        synchronized (ALBUM_LOCK) {
            if (albumPopular != null && System.currentTimeMillis() - albumAt < 30 * 60 * 1000L) return;
            List<Album> popular = new ArrayList<>(), recent = new ArrayList<>();
            try {
                String cc = country();
                String url = CHART + "/api/v2/" + cc + "/music/most-played/100/albums.json";
                byte[] body = MusicNet.readCache(url, HOURS6);
                if (body == null) {
                    MusicNet.Reply r = MusicNet.get(url, 15000);
                    if (r.status != 200 && !cc.equals("us")) {
                        url = CHART + "/api/v2/us/music/most-played/100/albums.json";
                        r = MusicNet.get(url, 15000);
                    }
                    if (r.status == 200) {
                        body = r.body;
                        MusicNet.writeCache(url, body);
                    }
                }
                JSONArray items = body == null ? new JSONArray() : list(new JSONObject(new String(body, "UTF-8")), "feed", "results");
                long cutoff = System.currentTimeMillis() - 56 * MusicNet.DAY;
                java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
                List<Album> recentFound = new ArrayList<>();
                List<String> recentDates = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject r = items.optJSONObject(i);
                    if (r == null) continue;
                    String date = r.optString("releaseDate", "");
                    boolean isRecent = false;
                    try {
                        isRecent = date.length() >= 10 && day.parse(date.substring(0, 10)).getTime() >= cutoff;
                    } catch (Exception ignored) {
                    }
                    if (i >= 30 && !isRecent) continue;
                    Album a = MusicBrainz.albumByName(r.optString("name", ""), r.optString("artistName", ""));
                    if (a == null) continue;
                    a.year = date.length() >= 4 ? date.substring(0, 4) : null;
                    String art = r.optString("artworkUrl100", "");
                    if (art.matches("^https://is\\d+-ssl\\.mzstatic\\.com/.*")) a.artworkURL = art.replaceAll("/\\d+x\\d+bb\\.", "/400x400bb.");
                    if (i < 30) popular.add(a);
                    if (isRecent) {
                        int at = 0;
                        while (at < recentDates.size() && recentDates.get(at).compareTo(date) >= 0) at++;
                        recentDates.add(at, date);
                        recentFound.add(at, a);
                    }
                }
                recent = recentFound;
            } catch (Exception ignored) {
                // the rows stay empty
            }
            albumPopular = popular;
            albumRecent = recent;
            albumAt = popular.isEmpty() ? 0 : System.currentTimeMillis();
        }
    }

    /** Albums people are playing now in your country. */
    public static List<Album> popularAlbums() {
        loadAlbums();
        return new ArrayList<>(albumPopular);
    }

    /** Popular albums out in the last 8 weeks, newest first. */
    public static List<Album> chartNewReleases() {
        loadAlbums();
        return new ArrayList<>(albumRecent);
    }

    /** Every album out in the last two weeks (ListenBrainz, worldwide): only used to find new music from artists you like. */
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

    /** Is this Last.fm key accepted? 1 yes, 0 no (Last.fm said so), -1 couldn't ask. Blocking. */
    public static int checkLastfm(String key) {
        if (key == null || !key.trim().matches("[A-Za-z0-9]{16,64}")) return 0;
        try {
            MusicNet.Reply r = MusicNet.get(LASTFM + "/2.0/?method=geo.gettoptracks&country=United%20States&limit=1&format=json&api_key=" + key.trim(), 15000);
            JSONObject j = null;
            try {
                j = new JSONObject(r.text());
            } catch (Exception ignored) {
            }
            if (r.status == 200 && j != null && j.optJSONObject("tracks") != null) return 1;
            if (j != null && (j.optInt("error") == 10 || j.optInt("error") == 26 || j.optInt("error") == 4)) return 0;
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // Last.fm's chart for the country (your own key), as Apple-shaped items. Cached under an address without the key.
    private static JSONArray lastfmFeed(String cc, String key) throws Exception {
        String name = new Locale("", cc.toUpperCase(Locale.ROOT)).getDisplayCountry(Locale.ENGLISH);
        if (name == null || name.isEmpty() || name.equalsIgnoreCase(cc)) name = "United States";
        String p = "/2.0/?method=geo.gettoptracks&country=" + java.net.URLEncoder.encode(name, "UTF-8") + "&limit=100&format=json";
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
        return t == null || t.id == null ? null : APPLE.get(t.id);
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

    private static String plainName(String s) {
        return java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace("&", "and").replaceAll("[^a-z0-9]+", "");
    }

    /** A song's cover from Deezer, or null. Kept a month. */
    static String deezerCover(String artist, String title) {
        try {
            String q = "artist:\"" + artist.replace("\"", "") + "\" track:\"" + title.replace("\"", "") + "\"";
            String url = "https://api.deezer.com/search/track?q=" + java.net.URLEncoder.encode(q, "UTF-8") + "&limit=5";
            byte[] body = MusicNet.readCache(url, 30 * MusicNet.DAY);
            if (body == null) {
                MusicNet.spaced("https://api.deezer.com", 120);
                MusicNet.Reply r = MusicNet.get(url, 15000);
                if (r.status != 200) return null;
                body = r.body;
                MusicNet.writeCache(url, body);
            }
            JSONArray list = new JSONObject(new String(body, "UTF-8")).optJSONArray("data");
            if (list == null || list.length() == 0) return null;
            String lead = plainName(LEAD.split(artist)[0]);
            JSONObject hit = list.optJSONObject(0);
            for (int i = 0; i < list.length(); i++) {
                JSONObject t = list.optJSONObject(i);
                JSONObject a = t == null ? null : t.optJSONObject("artist");
                if (a != null && plainName(a.optString("name")).equals(lead)) {
                    hit = t;
                    break;
                }
            }
            JSONObject album = hit == null ? null : hit.optJSONObject("album");
            String cover = album == null ? "" : album.optString("cover_big", album.optString("cover_medium", ""));
            return cover.startsWith("https://") && !cover.contains("/cover//") ? cover : null;
        } catch (Exception e) {
            return null;
        }
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
        String url = CHART + "/api/v2/" + cc + "/music/most-played/100/songs.json";
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
                int n = Math.min(items.length(), 100);
                List<Future<JSONObject>> jobs = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    final JSONObject r = items.optJSONObject(i);
                    jobs.add(LOOKUPS.submit(() -> r == null ? null : lookupSong(r.optString("artistName", ""), r.optString("name", ""))));
                }
                // Every chart song is shown (an addon finds it by title and artist); the MusicBrainz match only adds ids
                // and covers. The artists row needs ids: from the match, else an exact-name MusicBrainz search.
                List<String> names = new ArrayList<>();
                List<String> nameIds = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    JSONObject m;
                    try {
                        m = jobs.get(i).get();
                    } catch (Exception e) {
                        m = null;
                    }
                    JSONObject r = items.optJSONObject(i);
                    if (r == null) continue;
                    JSONArray idsRaw = m == null ? null : m.optJSONArray("artist_mbids");
                    List<String> ids = new ArrayList<>();
                    for (int k = 0; idsRaw != null && k < idsRaw.length(); k++) if (MBID.matcher(idsRaw.optString(k)).matches()) ids.add(idsRaw.optString(k));
                    String credit = m != null && Model.str(m, "artist_credit_name") != null ? m.optString("artist_credit_name") : r.optString("artistName", "");
                    String title = m != null && Model.str(m, "recording_name") != null ? m.optString("recording_name") : r.optString("name", "");
                    if (credit.isEmpty() || title.isEmpty()) continue;
                    Track t = new Track();
                    t.mbid = m == null ? null : Model.str(m, "recording_mbid");
                    t.id = t.mbid != null ? "mbid:" + t.mbid : "chart:" + credit.toLowerCase(Locale.ROOT) + "|" + title.toLowerCase(Locale.ROOT);
                    t.title = title;
                    t.artist = credit;
                    t.album = m == null ? null : Model.str(m, "release_name");
                    String rel = m == null ? null : Model.str(m, "release_mbid");
                    String art = r.optString("artworkUrl100", "");
                    t.artworkURL = rel != null ? MusicBrainz.releaseCoverUrl(rel) : art.matches("^https://is\\d+-ssl\\.mzstatic\\.com/.*") ? art.replaceAll("/\\d+x\\d+bb\\.", "/400x400bb.") : null;
                    t.artistMbid = ids.isEmpty() ? null : ids.get(0);
                    songs.add(t);
                    String link = r.optString("url", "");
                    if (link.startsWith("https://music.apple.com/")) APPLE.put(t.id, link);
                    String name = ids.size() <= 1 ? credit : LEAD.split(credit)[0].trim();
                    if (ids.isEmpty()) name = LEAD.split(credit)[0].trim();
                    boolean known = false;
                    for (String x : names) known |= x.equalsIgnoreCase(name);
                    if (!known) {
                        names.add(name);
                        nameIds.add(ids.isEmpty() ? null : ids.get(0));
                    }
                }
                // Songs with no cover yet (Last.fm sends no real pictures): Deezer's cover of that song.
                List<Future<String>> covers = new ArrayList<>();
                for (final Track t : songs) {
                    covers.add(t.artworkURL != null ? null : LOOKUPS.submit(() -> deezerCover(t.artist, t.title)));
                }
                for (int i = 0; i < songs.size(); i++) {
                    try {
                        if (covers.get(i) != null) songs.get(i).artworkURL = covers.get(i).get();
                    } catch (Exception ignored) {
                    }
                }
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < names.size() && i < 30; i++) {
                    String id = nameIds.get(i) != null ? nameIds.get(i) : MusicBrainz.artistIdByName(names.get(i));
                    if (id == null || !seen.add(id)) continue;
                    Artist a = new Artist();
                    a.id = id;
                    a.name = names.get(i);
                    artists.add(a);
                }
            } catch (Exception ignored) {
                // nothing from the chart: the rows stay empty
            }
            chartSongs = songs;
            chartArtists = artists;
            chartAt = songs.isEmpty() ? 0 : System.currentTimeMillis();
        }
    }

    /** Songs people are playing this week: the country's chart, else ListenBrainz's. */
    public static List<Track> popularSongs() {
        loadChart();
        return new ArrayList<>(chartSongs.subList(0, Math.min(100, chartSongs.size())));
    }

    /** Artists people are playing now: the ones in the country's chart, else ListenBrainz's. */
    public static List<Artist> popularArtists() {
        loadChart();
        return new ArrayList<>(chartArtists.subList(0, Math.min(20, chartArtists.size())));
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
