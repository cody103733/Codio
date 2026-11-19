package com.codio.tv.music;

import com.codio.tv.music.Model.Addon;
import com.codio.tv.music.Model.Album;
import com.codio.tv.music.Model.Artist;
import com.codio.tv.music.Model.Results;
import com.codio.tv.music.Model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Music addons: plain HTTP servers that follow Eclipse Music's addon protocol (https://eclipsemusic.app/docs), as in
 * windows/src/routes/Codio/music/addons.ts. The list lives on this device (pref codioMusicAddons), and goes locked to your other devices with the sync passcode (ui/Vault). Addon links, which
 * can hold a personal token, go to nobody but the addon itself.
 */
public final class Addons {
    private Addons() {}

    private static final String KEY = "codioMusicAddons";

    public interface Changed {
        void changed();
    }

    private static final List<Changed> LISTENERS = new CopyOnWriteArrayList<>();

    public static void listen(Changed c) {
        if (!LISTENERS.contains(c)) LISTENERS.add(c);
    }

    public static void unlisten(Changed c) {
        LISTENERS.remove(c);
    }

    public static List<Addon> load() {
        List<Addon> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(MusicNet.prefs().getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || Model.str(o, "url") == null || o.optJSONObject("manifest") == null) continue;
                Addon ad = new Addon();
                ad.url = o.getString("url");
                ad.manifest = o.getJSONObject("manifest");
                out.add(ad);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void save(List<Addon> list) {
        JSONArray a = new JSONArray();
        try {
            for (Addon ad : list) a.put(new JSONObject().put("url", ad.url).put("manifest", ad.manifest));
        } catch (Exception ignored) {
        }
        MusicNet.prefs().edit().putString(KEY, a.toString()).apply();
        for (Changed c : LISTENERS) c.changed();
    }

    /** What was typed or pasted, as the addon's base link (no manifest.json). */
    public static String baseLink(String text) {
        String u = text.trim();
        if (u.startsWith("stremio://") || u.startsWith("eclipse://")) u = "https://" + u.replaceFirst("^[a-z]+://", "");
        if (!u.toLowerCase(Locale.US).matches("^https?://.*")) u = "https://" + u;
        return u.replaceAll("(?i)/manifest\\.json$", "").replaceAll("/+$", "");
    }

    /** A call to an addon. query is key, value, key, value... (null or empty values are left out). */
    static MusicNet.Reply call(Addon addon, String path, String... query) throws java.io.IOException {
        return call(addon.url, path, query);
    }

    static MusicNet.Reply call(String base, String path, String... query) throws java.io.IOException {
        StringBuilder sb = new StringBuilder(base).append(path);
        boolean first = !path.contains("?");
        try {
            for (int i = 0; i + 1 < query.length; i += 2) {
                if (query[i + 1] == null || query[i + 1].length() == 0) continue;
                sb.append(first ? '?' : '&').append(URLEncoder.encode(query[i], "UTF-8")).append('=').append(URLEncoder.encode(query[i + 1], "UTF-8"));
                first = false;
            }
        } catch (java.io.UnsupportedEncodingException ignored) {
        }
        return MusicNet.get(sb.toString(), 12000);
    }

    /** The values the addon's own settings default to; Eclipse sends them with every call. */
    private static String[] withSettings(Addon addon, String... query) {
        List<String> q = new ArrayList<>();
        for (String s : query) q.add(s);
        JSONArray s = addon.manifest.optJSONArray("settings");
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                JSONObject o = s.optJSONObject(i);
                if (o == null || Model.str(o, "key") == null || o.isNull("default")) continue;
                q.add(o.optString("key"));
                q.add(o.optString("default"));
            }
        }
        return q.toArray(new String[0]);
    }

    public static Addon fetchManifest(String text) throws Exception {
        String url = baseLink(text);
        MusicNet.Reply r;
        try {
            r = call(url, "/manifest.json");
        } catch (Exception e) {
            throw new Exception("Couldn't reach that link");
        }
        JSONObject m = r.object();
        if (r.status != 200 || m == null || Model.str(m, "id") == null || Model.str(m, "name") == null || m.optJSONArray("resources") == null) {
            throw new Exception("That link isn't a music addon (no manifest found)");
        }
        Addon a = new Addon();
        a.url = url;
        a.manifest = m;
        if (!a.has("search") && !a.has("stream") && !a.has("catalog")) throw new Exception("That addon doesn't search, stream or list anything");
        return a;
    }

    // Notices an addon sends along (shown as a short message).
    public interface Notice {
        void notice(String message, String level);
    }

    private static final List<Notice> NOTICES = new CopyOnWriteArrayList<>();

    public static void onNotice(Notice n) {
        NOTICES.add(n);
    }

    private static void notice(Addon addon, JSONObject json) {
        JSONObject n = json == null ? null : json.optJSONObject("notice");
        if (n == null || Model.str(n, "message") == null) return;
        String title = Model.str(n, "title");
        String text = addon.name() + ": " + (title != null ? title + ". " : "") + n.optString("message");
        if (text.length() > 200) text = text.substring(0, 200);
        String level = n.optString("level", "info");
        for (Notice l : NOTICES) l.notice(text, level);
    }

    public static Track toTrack(Addon addon, JSONObject t) {
        if (t == null || t.isNull("id") || Model.str(t, "title") == null) return null;
        Track k = new Track();
        k.id = t.optString("id");
        k.title = t.optString("title");
        k.artist = t.optString("artist", "");
        k.album = Model.str(t, "album");
        k.artworkURL = Model.str(t, "artworkURL");
        if (t.optLong("durationMs", 0) > 0) k.durationMs = t.optLong("durationMs");
        else if (t.optDouble("duration", 0) > 0) k.durationMs = Math.round(t.optDouble("duration") * 1000);
        k.isrc = Model.str(t, "isrc");
        k.addon = addon.url;
        k.streamURL = Model.str(t, "streamURL");
        return k;
    }

    static Album toAlbum(Addon addon, JSONObject a) {
        if (a == null || a.isNull("id") || Model.str(a, "title") == null) return null;
        Album k = new Album();
        k.id = a.optString("id");
        k.title = a.optString("title");
        k.artist = a.optString("artist", "");
        k.year = a.isNull("year") || a.optString("year").length() == 0 ? null : a.optString("year");
        k.artworkURL = Model.str(a, "artworkURL");
        k.addon = addon.url;
        return k;
    }

    static Artist toArtist(Addon addon, JSONObject a) {
        if (a == null || a.isNull("id") || Model.str(a, "name") == null) return null;
        Artist k = new Artist();
        k.id = a.optString("id");
        k.name = a.optString("name");
        JSONArray g = a.optJSONArray("genres");
        if (g != null && g.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, g.length()); i++) sb.append(i > 0 ? ", " : "").append(g.optString(i));
            k.detail = sb.toString();
        }
        k.artworkURL = Model.str(a, "artworkURL");
        k.addon = addon.url;
        return k;
    }

    public static Results search(Addon addon, String q) throws Exception {
        MusicNet.Reply r = call(addon, "/search", withSettings(addon, "q", q));
        JSONObject j = r.object();
        notice(addon, j);
        Results out = new Results();
        if (r.status != 200 || j == null) return out;
        JSONArray t = j.optJSONArray("tracks"), al = j.optJSONArray("albums"), ar = j.optJSONArray("artists");
        for (int i = 0; t != null && i < t.length(); i++) add(out.tracks, toTrack(addon, t.optJSONObject(i)));
        for (int i = 0; al != null && i < al.length(); i++) add(out.albums, toAlbum(addon, al.optJSONObject(i)));
        for (int i = 0; ar != null && i < ar.length(); i++) add(out.artists, toArtist(addon, ar.optJSONObject(i)));
        return out;
    }

    private static <T> void add(List<T> list, T x) {
        if (x != null) list.add(x);
    }

    public static final class Stream {
        public final String url, quality, format;

        Stream(String url, String quality, String format) {
            this.url = url;
            this.quality = quality;
            this.format = format;
        }
    }

    /** How a caller follows a "still preparing" wait (e.g. TorBox is caching the song). */
    public interface Waiting {
        /** True once the caller has moved on (the song was skipped): the wait stops. */
        boolean cancelled();

        /** The text to show while waiting, or null when the wait is over. Called on the background thread. */
        void preparing(String text);
    }

    /** How long Codio keeps asking an addon that says a song is being prepared, before it counts as no source. */
    public static final long PREPARE_LIMIT_MS = 3 * 60 * 1000L;

    /** Does this reply say "not ready yet, ask again"? Only an explicit sign counts (503/202/425, or a status word like
     *  "downloading"); a 404, an error notice or a reply with no link is a real "no source" and ends the wait at once. */
    public static boolean isPreparing(int status, JSONObject j) {
        JSONObject n = j == null ? null : j.optJSONObject("notice");
        if (n != null && "error".equals(n.optString("level"))) return false;
        if (status == 503 || status == 202 || status == 425) return true;
        if (status == 200 && j != null && Model.str(j, "url") == null) {
            String word = Model.str(j, "status") != null ? Model.str(j, "status") : Model.str(j, "state");
            return word != null && word.trim().replaceAll("[\\s-]+", "_").matches("(?i)preparing|pending|queued|queue|downloading|caching|processing|in_?progress|waiting|not_?ready");
        }
        return false;
    }

    public static Stream streamFor(Addon addon, String id) throws Exception {
        return streamFor(addon, id, null);
    }

    /** The audio link for an addon's own track id. While the addon says it is still preparing the song (TorBox downloading
     *  it), wait as long as it asks (2 to 30 s) and ask again, for up to PREPARE_LIMIT_MS. A real "no source" returns null at once. */
    public static Stream streamFor(Addon addon, String id, Waiting waiting) throws Exception {
        long started = System.currentTimeMillis();
        boolean waited = false;
        int misses = 0;
        try {
            while (true) {
                MusicNet.Reply r;
                try {
                    r = call(addon, "/stream/" + URLEncoder.encode(id, "UTF-8").replace("+", "%20"), withSettings(addon));
                } catch (java.io.IOException e) {
                    r = null;
                }
                JSONObject j = r == null ? null : r.object();
                notice(addon, j);
                if (waiting != null && waiting.cancelled()) return null;
                int status = r == null ? 0 : r.status;
                // The addon couldn't be reached or timed out (some hold the call while they fetch the song): two more tries.
                boolean lost = status == 0 || status == 502 || status == 504;
                if (lost && ++misses > 2) return null;
                if (lost || isPreparing(status, j)) {
                    int wait = Math.min(30, Math.max(2, r == null || r.retryAfter < 0 ? 5 : r.retryAfter));
                    if (System.currentTimeMillis() - started + wait * 1000L > PREPARE_LIMIT_MS) return null;
                    waited = true;
                    if (waiting != null) waiting.preparing(preparingText(addon, j));
                    Thread.sleep(wait * 1000L);
                    if (waiting != null && waiting.cancelled()) return null;
                    continue;
                }
                if (status != 200 || j == null || Model.str(j, "url") == null) return null;
                return new Stream(j.optString("url"), Model.str(j, "quality"), Model.str(j, "format"));
            }
        } finally {
            if (waited && waiting != null && !waiting.cancelled()) waiting.preparing(null);
        }
    }

    private static String preparingText(Addon addon, JSONObject j) {
        JSONObject n = j == null ? null : j.optJSONObject("notice");
        if (n == null || Model.str(n, "message") == null) return "Preparing song on " + addon.name() + "\u2026";
        String title = Model.str(n, "title");
        double p = n.optDouble("progress", -1);
        String text = (title != null ? title + ": " : "") + n.optString("message") + (p > 0 && p < 1 ? " " + Math.round(p * 100) + "%" : "");
        return text.length() > 120 ? text.substring(0, 120) : text;
    }

    public static String resolveIsrc(Addon addon, String isrc) throws Exception {
        MusicNet.Reply r = call(addon, "/resolve-isrc", withSettings(addon, "isrc", isrc));
        JSONObject j = r.object();
        notice(addon, j);
        if (r.status != 200 || j == null) return null;
        Object id = j.opt("trackId") != null && !j.isNull("trackId") ? j.opt("trackId") : j.opt("id");
        return id == null || JSONObject.NULL.equals(id) ? null : String.valueOf(id);
    }

    public static Track resolveTrack(Addon addon, Track t) throws Exception {
        MusicNet.Reply r = call(addon, "/resolve", withSettings(addon, "isrc", t.isrc, "title", t.title, "artist", t.artist,
                "durationMs", t.durationMs > 0 ? String.valueOf(t.durationMs) : null));
        JSONObject j = r.object();
        notice(addon, j);
        if (r.status != 200 || j == null || j.optJSONObject("item") == null) return null;
        return toTrack(addon, j.optJSONObject("item"));
    }

    /** A row an addon offers for the Music home (one of its catalogs). */
    public static final class CatalogRow {
        public Addon addon;
        public String id, name, type;
        public List<Object> items = new ArrayList<>();
    }

    public static List<CatalogRow> loadCatalogs(List<Addon> addons) {
        final List<CatalogRow> rows = new ArrayList<>();
        for (Addon addon : addons) {
            if (!addon.has("catalog")) continue;
            JSONArray cs = addon.manifest.optJSONArray("catalogs");
            for (int i = 0; cs != null && i < cs.length(); i++) {
                JSONObject c = cs.optJSONObject(i);
                if (c == null || Model.str(c, "id") == null) continue;
                try {
                    MusicNet.Reply r = call(addon, "/catalog/" + URLEncoder.encode(c.optString("id"), "UTF-8"), withSettings(addon, "skip", "0"));
                    JSONObject j = r.object();
                    notice(addon, j);
                    JSONArray items = j == null ? null : j.optJSONArray("items");
                    String type = c.optString("type", "track");
                    CatalogRow row = new CatalogRow();
                    row.addon = addon;
                    row.id = c.optString("id");
                    row.name = c.optString("name", row.id);
                    row.type = type;
                    for (int k = 0; items != null && k < items.length(); k++) {
                        JSONObject it = items.optJSONObject(k);
                        if (it == null) continue;
                        Object m;
                        if ("album".equals(type)) m = toAlbum(addon, it);
                        else if ("artist".equals(type)) {
                            if (it.isNull("name") && !it.isNull("title")) it.put("name", it.optString("title"));
                            m = toArtist(addon, it);
                        } else m = toTrack(addon, it);
                        if (m != null) row.items.add(m);
                    }
                    if (!row.items.isEmpty()) rows.add(row);
                } catch (Exception ignored) {
                    // a row that fails is left out
                }
            }
        }
        return rows;
    }

    public static final class AlbumDetail {
        public String title, artist, artworkURL, year;
        public List<Track> tracks = new ArrayList<>();
    }

    public static AlbumDetail albumDetail(Addon addon, String id) throws Exception {
        MusicNet.Reply r = call(addon, "/album/" + URLEncoder.encode(id, "UTF-8").replace("+", "%20"), withSettings(addon));
        JSONObject j = r.object();
        notice(addon, j);
        if (r.status != 200 || j == null) return null;
        AlbumDetail d = new AlbumDetail();
        d.title = j.optString("title", "");
        d.artist = j.optString("artist", "");
        d.artworkURL = Model.str(j, "artworkURL");
        d.year = j.isNull("year") || j.optString("year").length() == 0 ? null : j.optString("year");
        JSONArray ts = j.optJSONArray("tracks");
        for (int i = 0; ts != null && i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t == null) continue;
            if (t.isNull("artist")) t.put("artist", d.artist);
            if (t.isNull("album")) t.put("album", d.title);
            if (t.isNull("artworkURL") && d.artworkURL != null) t.put("artworkURL", d.artworkURL);
            add(d.tracks, toTrack(addon, t));
        }
        return d;
    }

    public static final class ArtistDetail {
        public String name, artworkURL, bio;
        public List<Track> topTracks = new ArrayList<>();
        public List<Album> albums = new ArrayList<>();
    }

    public static ArtistDetail artistDetail(Addon addon, String id) throws Exception {
        MusicNet.Reply r = call(addon, "/artist/" + URLEncoder.encode(id, "UTF-8").replace("+", "%20"), withSettings(addon));
        JSONObject j = r.object();
        notice(addon, j);
        if (r.status != 200 || j == null) return null;
        ArtistDetail d = new ArtistDetail();
        d.name = j.optString("name", "");
        d.artworkURL = Model.str(j, "artworkURL");
        d.bio = Model.str(j, "bio");
        JSONArray ts = j.optJSONArray("topTracks"), al = j.optJSONArray("albums");
        for (int i = 0; ts != null && i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t == null) continue;
            if (t.isNull("artist")) t.put("artist", d.name);
            add(d.topTracks, toTrack(addon, t));
        }
        for (int i = 0; al != null && i < al.length(); i++) {
            JSONObject a = al.optJSONObject(i);
            if (a == null) continue;
            if (a.isNull("artist")) a.put("artist", d.name);
            add(d.albums, toAlbum(addon, a));
        }
        return d;
    }

    /** The installed addon whose manifest id is id, or null. */
    public static Addon byUrl(String url) {
        if (url == null) return null;
        for (Addon a : load()) if (a.url.equals(url)) return a;
        return null;
    }
}
