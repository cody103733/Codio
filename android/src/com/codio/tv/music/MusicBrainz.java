package com.codio.tv.music;

import com.codio.tv.music.Model.Album;
import com.codio.tv.music.Model.Artist;
import com.codio.tv.music.Model.Results;
import com.codio.tv.music.Model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * MusicBrainz and the Cover Art Archive: artists, albums, tracklists with ISRC codes, artwork
 * (windows/src/routes/Codio/music/musicbrainz.ts). All calls block: run them on MusicNet.POOL. The server address is a
 * setting (MusicNet.mbBase()), so a self-hosted mirror works.
 */
public final class MusicBrainz {
    private MusicBrainz() {}

    static final String DEFAULT = "https://musicbrainz.org";

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static Object mb(String path) throws Exception {
        return mb(path, false);
    }

    /** Matching chart albums and artists: in the background lane, behind anything you opened. */
    private static Object mbBg(String path) throws Exception {
        return mb(path, true);
    }

    private static Object mb(String path, boolean background) throws Exception {
        String base = MusicNet.mbBase();
        if (base.length() == 0) base = DEFAULT;
        base = base.replaceAll("/+$", "");
        if (!base.matches("(?i)^https?://.*")) base = "https://" + base;
        String p = path.contains("fmt=") ? path : path + (path.contains("?") ? "&" : "?") + "fmt=json";
        boolean search = path.matches("^/ws/2/[a-z-]+\\?(?:.*&)?query=.*");
        return MusicNet.metadata(base, p, search ? MusicNet.DAY : 7 * MusicNet.DAY, background);
    }

    private static String plain(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT)
                .replace("&", "and").replaceAll("[^a-z0-9]+", "");
    }

    private static String quoted(String s) {
        return "\"" + s.replace("\"", "").replace("\\", "") + "\"";
    }

    private static final java.util.regex.Pattern LEAD = java.util.regex.Pattern.compile("\\s+(?:&|feat\\.?|ft\\.?|featuring|with|x)\\s+|,\\s+(?!the\\s)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Artists by exact name (or alias), all in one request (a OR b OR ...; MusicBrainz asks for one request a second, so
     * a chart costs one instead of twenty). Same order as names, null where none.
     */
    public static List<String> artistIdsByNames(List<String> names) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) out.add(null);
        if (names.isEmpty()) return out;
        try {
            StringBuilder q = new StringBuilder();
            for (String n : names) q.append(q.length() > 0 ? " OR " : "").append("artist:").append(quoted(n));
            JSONArray list = MusicNet.obj(mbBg("/ws/2/artist?query=" + enc(q.toString()) + "&limit=100")).optJSONArray("artists");
            for (int k = 0; k < names.size(); k++) {
                String wanted = plain(names.get(k));
                String best = null;
                int bestScore = -1;
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject a = list.optJSONObject(i);
                    if (a == null || !isMbid(a.optString("id"))) continue;
                    boolean same = plain(a.optString("name")).equals(wanted);
                    JSONArray al = a.optJSONArray("aliases");
                    for (int j = 0; !same && al != null && j < al.length(); j++) same = al.optJSONObject(j) != null && plain(al.optJSONObject(j).optString("name")).equals(wanted);
                    if (same && a.optInt("score", 0) > bestScore) {
                        best = a.optString("id");
                        bestScore = a.optInt("score", 0);
                    }
                }
                out.set(k, best);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Albums by {title, artist}, the whole chart in one request (Apple adds " - Single" / " - EP"). Same order, or null. */
    public static List<Album> albumsByNames(List<String[]> wanted) {
        List<Album> out = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) out.add(null);
        if (wanted.isEmpty()) return out;
        try {
            StringBuilder q = new StringBuilder();
            for (String[] w : wanted) {
                String title = w[0].replaceAll("(?i)\\s+-\\s+(single|ep)$", "");
                q.append(q.length() > 0 ? " OR " : "").append("(releasegroup:").append(quoted(title)).append(" AND artist:").append(quoted(LEAD.split(w[1])[0].trim())).append(")");
            }
            JSONArray list = MusicNet.obj(mbBg("/ws/2/release-group?query=" + enc(q.toString()) + "&limit=100")).optJSONArray("release-groups");
            for (int k = 0; k < wanted.size(); k++) {
                String title = plain(wanted.get(k)[0].replaceAll("(?i)\\s+-\\s+(single|ep)$", ""));
                String who = plain(LEAD.split(wanted.get(k)[1])[0]);
                JSONObject best = null;
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject g = list.optJSONObject(i);
                    if (g == null || !isMbid(g.optString("id")) || !plain(g.optString("title")).equals(title)) continue;
                    StringBuilder names = new StringBuilder();
                    JSONArray ac = g.optJSONArray("artist-credit");
                    for (int j = 0; ac != null && j < ac.length(); j++) if (ac.optJSONObject(j) != null) names.append(ac.optJSONObject(j).optString("name")).append(' ');
                    String credit = plain(names.toString());
                    if (!credit.contains(who) && !who.contains(credit)) continue;
                    if (best == null || g.optInt("score", 0) > best.optInt("score", 0)) best = g;
                }
                if (best != null) {
                    Album a = toAlbum(best);
                    if (a.artist == null || a.artist.isEmpty()) a.artist = wanted.get(k)[1];
                    out.set(k, a);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** An artist's id by exact name (or alias), best score first; null if none. Cached a day like every search. */
    public static String artistIdByName(String name) {
        try {
            JSONObject j = MusicNet.obj(mbBg("/ws/2/artist?query=" + enc("artist:\"" + name.replace("\"", "") + "\"") + "&limit=5"));
            JSONArray list = j.optJSONArray("artists");
            String wanted = plain(name);
            String best = null;
            int bestScore = -1;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject a = list.optJSONObject(i);
                if (a == null || !isMbid(a.optString("id"))) continue;
                boolean same = plain(a.optString("name")).equals(wanted);
                JSONArray al = a.optJSONArray("aliases");
                for (int k = 0; !same && al != null && k < al.length(); k++) same = al.optJSONObject(k) != null && plain(al.optJSONObject(k).optString("name")).equals(wanted);
                if (same && a.optInt("score", 0) > bestScore) {
                    best = a.optString("id");
                    bestScore = a.optInt("score", 0);
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    /** An album (release group) by title and artist, for chart albums (Apple adds " - Single" / " - EP"); null if none. */
    public static Album albumByName(String title, String artist) {
        try {
            String clean = title.replaceAll("(?i)\\s+-\\s+(single|ep)$", "").replace("\"", "");
            String q = "releasegroup:\"" + clean + "\" AND artist:\"" + artist.replace("\"", "") + "\"";
            JSONObject j = MusicNet.obj(mbBg("/ws/2/release-group?query=" + enc(q) + "&limit=5"));
            JSONArray list = j.optJSONArray("release-groups");
            String wanted = plain(clean);
            JSONObject best = null;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject g = list.optJSONObject(i);
                if (g == null || !isMbid(g.optString("id")) || !plain(g.optString("title")).equals(wanted)) continue;
                if (best == null || g.optInt("score", 0) > best.optInt("score", 0)) best = g;
            }
            if (best == null) return null;
            Album a = toAlbum(best);
            if (a.artist == null || a.artist.isEmpty()) a.artist = artist;
            return a;
        } catch (Exception e) {
            return null;
        }
    }

    /** Lucene special characters in a search must be escaped. */
    static String lucene(String text) {
        return text.replaceAll("([+\\-&|!(){}\\[\\]^\"~*?:\\\\/])", "\\\\$1").trim();
    }

    static final class Credit {
        String name = "", id;
    }

    static Credit credit(JSONArray list) {
        Credit c = new Credit();
        if (list == null || list.length() == 0) return c;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            String n = o.optString("name", "");
            if (n.length() == 0 && o.optJSONObject("artist") != null) n = o.optJSONObject("artist").optString("name", "");
            sb.append(n).append(o.optString("joinphrase", ""));
        }
        c.name = sb.toString().trim();
        JSONObject first = list.optJSONObject(0);
        if (first != null && first.optJSONObject("artist") != null) c.id = Model.str(first.optJSONObject("artist"), "id");
        return c;
    }

    private static String year(String date) {
        return date != null && date.matches("^\\d{4}.*") ? date.substring(0, 4) : null;
    }

    public static String coverUrl(String releaseGroupId, int size) {
        return "https://coverartarchive.org/release-group/" + releaseGroupId + "/front-" + size;
    }

    public static String coverUrl(String releaseGroupId) {
        return coverUrl(releaseGroupId, 250);
    }

    public static String releaseCoverUrl(String releaseId) {
        return "https://coverartarchive.org/release/" + releaseId + "/front-250";
    }

    public static boolean isMbid(String s) {
        return s != null && s.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    static Album toAlbum(JSONObject g) {
        Album a = new Album();
        a.id = g.optString("id");
        a.title = g.optString("title", "");
        Credit c = credit(g.optJSONArray("artist-credit"));
        a.artist = c.name;
        a.artistMbid = c.id;
        a.year = year(g.optString("first-release-date", null));
        StringBuilder kind = new StringBuilder();
        String primary = Model.str(g, "primary-type");
        if (primary != null) kind.append(primary);
        JSONArray sec = g.optJSONArray("secondary-types");
        for (int i = 0; sec != null && i < sec.length(); i++) kind.append(kind.length() > 0 ? " · " : "").append(sec.optString(i));
        a.kind = kind.length() > 0 ? kind.toString() : null;
        a.artworkURL = coverUrl(a.id);
        return a;
    }

    static Track toTrack(JSONObject recording, String albumTitle, String artwork, String artistFallback) {
        if (recording == null || Model.str(recording, "id") == null) return null;
        Track t = new Track();
        t.mbid = recording.optString("id");
        t.id = "mbid:" + t.mbid;
        t.title = recording.optString("title", "");
        Credit c = credit(recording.optJSONArray("artist-credit"));
        t.artist = c.name.length() > 0 ? c.name : artistFallback;
        t.artistMbid = c.id;
        t.album = albumTitle;
        t.artworkURL = artwork;
        t.durationMs = recording.optLong("length", 0);
        JSONArray isrcs = recording.optJSONArray("isrcs");
        if (isrcs != null && isrcs.length() > 0) t.isrc = isrcs.optString(0);
        return t;
    }

    /** Hands over partial results as each part arrives (one call a second each), and returns when all three are in. */
    public interface Partial {
        void part(Results snapshot);
    }

    public static Results search(String query, final Partial onPart) {
        final String q = lucene(query);
        final Results out = new Results();
        final CountDownLatch latch = new CountDownLatch(3);
        Runnable artists = new Runnable() {
            @Override
            public void run() {
                try {
                    JSONArray list = MusicNet.obj(mb("/ws/2/artist?query=" + enc(q) + "&limit=8")).optJSONArray("artists");
                    List<Artist> res = new ArrayList<>();
                    for (int i = 0; list != null && i < list.length(); i++) {
                        JSONObject a = list.getJSONObject(i);
                        Artist x = new Artist();
                        x.id = a.optString("id");
                        x.name = a.optString("name", "");
                        String dis = Model.str(a, "disambiguation"), type = Model.str(a, "type");
                        x.detail = dis != null && type != null ? dis + " · " + type : dis != null ? dis : type;
                        res.add(x);
                    }
                    publish(out, res, null, null, onPart);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            }
        };
        Runnable albums = new Runnable() {
            @Override
            public void run() {
                try {
                    JSONArray list = MusicNet.obj(mb("/ws/2/release-group?query=" + enc(q) + "&limit=12")).optJSONArray("release-groups");
                    List<Album> res = new ArrayList<>();
                    for (int i = 0; list != null && i < list.length(); i++) res.add(toAlbum(list.getJSONObject(i)));
                    publish(out, null, res, null, onPart);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            }
        };
        Runnable tracks = new Runnable() {
            @Override
            public void run() {
                try {
                    JSONArray list = MusicNet.obj(mb("/ws/2/recording?query=" + enc(q) + "&limit=20")).optJSONArray("recordings");
                    List<Track> res = new ArrayList<>();
                    for (int i = 0; list != null && i < list.length(); i++) {
                        JSONObject r = list.getJSONObject(i);
                        JSONArray rel = r.optJSONArray("releases");
                        JSONObject release = rel != null && rel.length() > 0 ? rel.optJSONObject(0) : null;
                        String groupId = release != null && release.optJSONObject("release-group") != null ? Model.str(release.optJSONObject("release-group"), "id") : null;
                        Track t = toTrack(r, release != null ? release.optString("title", "") : null, groupId != null ? coverUrl(groupId) : null, "");
                        if (t != null) res.add(t);
                    }
                    publish(out, null, null, res, onPart);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            }
        };
        MusicNet.POOL.execute(artists);
        MusicNet.POOL.execute(albums);
        MusicNet.POOL.execute(tracks);
        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }
        return out;
    }

    private static void publish(Results out, List<Artist> a, List<Album> al, List<Track> t, Partial onPart) {
        Results snap = new Results();
        synchronized (out) {
            if (a != null) out.artists = a;
            if (al != null) out.albums = al;
            if (t != null) out.tracks = t;
            snap.artists = out.artists;
            snap.albums = out.albums;
            snap.tracks = out.tracks;
        }
        if (onPart != null) onPart.part(snap);
    }

    public static final class ArtistPage {
        public String id, name, detail;
        public List<Album> albums = new ArrayList<>(), singles = new ArrayList<>(), other = new ArrayList<>();
    }

    private static final Comparator<Album> NEWEST = new Comparator<Album>() {
        @Override
        public int compare(Album a, Album b) {
            return (b.year == null ? "0" : b.year).compareTo(a.year == null ? "0" : a.year);
        }
    };

    public static ArtistPage getArtist(String id, String knownName) throws Exception {
        JSONObject artist = knownName != null ? new JSONObject().put("name", knownName) : MusicNet.obj(mb("/ws/2/artist/" + id));
        JSONObject groups;
        try {
            groups = MusicNet.obj(mb("/ws/2/release-group?artist=" + id + "&type=album%7Cep%7Csingle&limit=100"));
        } catch (Exception e) {
            groups = new JSONObject();
        }
        ArtistPage p = new ArtistPage();
        p.id = id;
        p.name = artist.optString("name", "");
        StringBuilder d = new StringBuilder();
        for (String k : new String[] {"disambiguation", "type", "country"}) {
            String v = Model.str(artist, k);
            if (v != null) d.append(d.length() > 0 ? " · " : "").append(v);
        }
        p.detail = d.length() > 0 ? d.toString() : null;
        JSONArray list = groups.optJSONArray("release-groups");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject g = list.getJSONObject(i);
            Album a = toAlbum(g);
            JSONArray sec = g.optJSONArray("secondary-types");
            if (sec != null && sec.length() > 0) p.other.add(a);
            else if ("Album".equals(g.optString("primary-type"))) p.albums.add(a);
            else p.singles.add(a);
        }
        Collections.sort(p.albums, NEWEST);
        Collections.sort(p.singles, NEWEST);
        Collections.sort(p.other, NEWEST);
        return p;
    }

    public static final class AlbumPage {
        public String id, title, artist, artistId, year, artworkURL;
        public List<Track> tracks = new ArrayList<>();
    }

    /** Official before anything else, then the earliest date; with withTracks, only releases whose tracks came along. */
    private static JSONObject earliest(JSONArray rel, boolean withTracks) {
        JSONObject best = null;
        String bestKey = null;
        for (int i = 0; rel != null && i < rel.length(); i++) {
            JSONObject r = rel.optJSONObject(i);
            if (r == null) continue;
            if (withTracks) {
                JSONArray media = r.optJSONArray("media");
                boolean has = false;
                for (int k = 0; media != null && k < media.length(); k++) {
                    JSONArray t = media.optJSONObject(k) == null ? null : media.optJSONObject(k).optJSONArray("tracks");
                    has |= t != null && t.length() > 0;
                }
                if (!has) continue;
            }
            String date = r.optString("date", "");
            String key = ("Official".equals(r.optString("status")) ? "0" : "1") + (date.isEmpty() ? "9999" : date);
            if (best == null || key.compareTo(bestKey) < 0) {
                best = r;
                bestKey = key;
            }
        }
        return best;
    }

    /** The earliest official release of the release group, with its tracks and their ISRC codes (one request). */
    public static AlbumPage getAlbum(String groupId) throws Exception {
        // One request: the album's releases with their tracks; the earliest official one is shown.
        JSONObject release = null;
        try {
            JSONArray all = MusicNet.obj(mb("/ws/2/release?release-group=" + groupId + "&inc=recordings+isrcs+artist-credits+release-groups&limit=25")).optJSONArray("releases");
            release = earliest(all, true);
        } catch (Exception ignored) {
            // the two-step way below
        }
        if (release == null) {
            JSONObject list = MusicNet.obj(mb("/ws/2/release?release-group=" + groupId + "&limit=100"));
            JSONObject best = earliest(list.optJSONArray("releases"), false);
            if (best == null) throw new Exception("No release found for this album");
            release = MusicNet.obj(mb("/ws/2/release/" + best.optString("id") + "?inc=recordings+isrcs+artist-credits+release-groups"));
        }
        JSONObject group = release.optJSONObject("release-group");
        if (group == null) group = new JSONObject();
        AlbumPage p = new AlbumPage();
        p.id = groupId;
        p.artworkURL = coverUrl(groupId);
        Credit c = credit(release.optJSONArray("artist-credit"));
        p.artist = c.name;
        p.artistId = c.id;
        p.title = group.optString("title", release.optString("title", ""));
        p.year = year(group.optString("first-release-date", release.optString("date", "")));
        JSONArray media = release.optJSONArray("media");
        for (int m = 0; media != null && m < media.length(); m++) {
            JSONArray ts = media.getJSONObject(m).optJSONArray("tracks");
            for (int i = 0; ts != null && i < ts.length(); i++) {
                JSONObject t = ts.getJSONObject(i);
                JSONObject rec = t.optJSONObject("recording");
                JSONObject merged = rec != null ? new JSONObject(rec.toString()) : new JSONObject();
                if (merged.isNull("title")) merged.put("title", t.optString("title", ""));
                if (merged.isNull("length") && !t.isNull("length")) merged.put("length", t.optLong("length"));
                Track tr = toTrack(merged, p.title, p.artworkURL, p.artist);
                if (tr != null) p.tracks.add(tr);
            }
        }
        return p;
    }

    /** A recording's ISRC code, asked when a track from a list without them is played. */
    public static String getRecordingIsrc(String mbid) throws Exception {
        JSONArray isrcs = MusicNet.obj(mb("/ws/2/recording/" + mbid + "?inc=isrcs")).optJSONArray("isrcs");
        return isrcs != null && isrcs.length() > 0 ? isrcs.optString(0) : null;
    }

    /** The artist's most listened-to tracks (ListenBrainz; MusicBrainz itself has no popularity). Empty if unavailable. */
    public static List<Track> getTopTracks(String artistId, String artistName) {
        List<Track> out = new ArrayList<>();
        try {
            JSONArray list = MusicNet.arr(MusicNet.metadata(Discovery.LB, "/1/popularity/top-recordings-for-artist/" + artistId, MusicNet.DAY));
            for (int i = 0; i < Math.min(10, list.length()); i++) {
                JSONObject r = list.optJSONObject(i);
                if (r == null || Model.str(r, "recording_mbid") == null) continue;
                Track t = new Track();
                t.mbid = r.optString("recording_mbid");
                t.id = "mbid:" + t.mbid;
                t.title = r.optString("recording_name", "");
                t.artist = r.optString("artist_name", artistName);
                t.album = Model.str(r, "release_name");
                t.artworkURL = Model.str(r, "release_mbid") != null ? releaseCoverUrl(r.optString("release_mbid")) : null;
                JSONArray am = r.optJSONArray("artist_mbids");
                t.artistMbid = am != null && am.length() > 0 ? am.optString(0) : artistId;
                t.durationMs = r.optLong("length", 0);
                out.add(t);
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
