package com.codio.tv.music;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Daily mix: 50 to 100 songs made for you, new every day and steady within the day.
 *
 * It blends your ListenBrainz Daily Jams (when signed in), a different few songs each day from the artists you play and
 * heart, songs you hearted, two or three songs each from artists like yours, and a handful from your ListenBrainz Weekly
 * Exploration. No song twice (the same title by the same artist counts as the same song), at most two per artist (three
 * when the mix would be short otherwise), nothing you marked Not interested. Songs found later in the day are added at
 * the end, so the mix you're in the middle of doesn't reshuffle.
 *
 * The same rules as windows/src/routes/Codio/music/dailymix.ts and ios/Codio/Music/DailyMix.swift.
 */
public final class DailyMix {
    private DailyMix() {}

    private static final int MAX = 100, ENOUGH = 50;
    private static final String STORE = "codioDailyMix";
    private static final Pattern MBID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final String TRACK_EXT = "https://musicbrainz.org/doc/jspf#track";
    private static final String PLAYLIST_EXT = "https://musicbrainz.org/doc/jspf#playlist";
    /** Daily Jams, artists like yours, your artists, Daily Jams, artists like yours, hearted songs, Weekly Exploration. */
    private static final int[] ORDER = {0, 3, 1, 0, 3, 2, 4};

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** What is hidden (Not interested), copied on the main thread so the mix can be built on another. */
    public static final class Hidden {
        final Set<String> tracks = new HashSet<String>(), artists = new HashSet<String>();

        public static Hidden now() {
            Taste.load();
            Hidden h = new Hidden();
            h.tracks.addAll(Taste.hiddenTracks.keySet());
            h.artists.addAll(Taste.hiddenArtists.keySet());
            return h;
        }

        boolean hides(Model.Track t) {
            return tracks.contains(t.key()) || (t.artistMbid != null && artists.contains(t.artistMbid));
        }
    }

    private static String mbidIn(Object o) {
        if (o == null) return null;
        Matcher m = MBID.matcher(String.valueOf(o));
        return m.find() ? m.group() : null;
    }

    private static Object lb(String path, long ttl) {
        try {
            return MusicNet.metadata(Discovery.LB, path, ttl);
        } catch (Exception e) {
            return null;
        }
    }

    private static Model.Track playlistTrack(JSONObject t) {
        if (t == null) return null;
        Object idf = t.opt("identifier");
        String mbid = null;
        if (idf instanceof JSONArray) {
            for (int i = 0; i < ((JSONArray) idf).length() && mbid == null; i++) mbid = mbidIn(((JSONArray) idf).opt(i));
        } else {
            mbid = mbidIn(idf);
        }
        String title = Model.str(t, "title");
        if (mbid == null || title == null) return null;
        JSONObject meta = MusicNet.obj(MusicNet.obj(MusicNet.obj(t.optJSONObject("extension")).optJSONObject(TRACK_EXT)).optJSONObject("additional_metadata"));
        JSONArray artists = meta.optJSONArray("artists");
        JSONObject artist = artists != null ? artists.optJSONObject(0) : null;
        Model.Track x = new Model.Track();
        x.id = "mbid:" + mbid;
        x.mbid = mbid;
        x.title = title;
        String creator = Model.str(t, "creator");
        x.artist = creator != null ? creator : artist != null && Model.str(artist, "artist_credit_name") != null ? artist.optString("artist_credit_name") : "";
        x.album = Model.str(t, "album");
        x.durationMs = t.optLong("duration", 0);
        String caa = Model.str(meta, "caa_release_mbid");
        x.artworkURL = caa != null ? MusicBrainz.releaseCoverUrl(caa) : null;
        x.artistMbid = artist != null ? Model.str(artist, "artist_mbid") : null;
        return x;
    }

    private static List<Model.Track> playlist(JSONObject p) {
        List<Model.Track> out = new ArrayList<Model.Track>();
        String id = p == null ? null : mbidIn(p.optString("identifier"));
        if (id == null) return out;
        JSONArray tracks = MusicNet.obj(MusicNet.obj(lb("/1/playlist/" + id, 7 * MusicNet.DAY)).optJSONObject("playlist")).optJSONArray("track");
        for (int i = 0; tracks != null && i < tracks.length(); i++) {
            Model.Track t = playlistTrack(tracks.optJSONObject(i));
            if (t != null) out.add(t);
        }
        return out;
    }

    /** The newest Daily Jams [0] and Weekly Exploration [1] ListenBrainz made for this user (they're public). Blocking. */
    @SuppressWarnings("unchecked")
    public static List<Model.Track>[] madeForYou(String user) {
        List<Model.Track>[] out = new List[]{new ArrayList<Model.Track>(), new ArrayList<Model.Track>()};
        if (user == null) return out;
        String name;
        try {
            name = URLEncoder.encode(user, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return out;
        }
        JSONArray list = MusicNet.obj(lb("/1/user/" + name + "/playlists/createdfor?count=25", 2 * 3600 * 1000L)).optJSONArray("playlists");
        String[] patches = {"daily-jams", "weekly-exploration"};
        for (int k = 0; k < 2; k++) {
            JSONObject best = null;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject p = MusicNet.obj(list.optJSONObject(i)).optJSONObject("playlist");
                if (p == null) continue;
                String patch = MusicNet.obj(MusicNet.obj(MusicNet.obj(MusicNet.obj(p.optJSONObject("extension")).optJSONObject(PLAYLIST_EXT))
                        .optJSONObject("additional_metadata")).optJSONObject("algorithm_metadata")).optString("source_patch");
                if (!patches[k].equals(patch)) continue;
                if (best == null || p.optString("date").compareTo(best.optString("date")) > 0) best = p;
            }
            out[k] = playlist(best);
        }
        return out;
    }

    private static final Pattern BRACKETS = Pattern.compile("\\s*[(\\[].*?[)\\]]\\s*");
    private static final Pattern DASH_TAIL = Pattern.compile("\\s+-\\s+.*$");
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    static String norm(String s) {
        if (s == null) return "";
        String x = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
        x = BRACKETS.matcher(x).replaceAll(" ");
        x = DASH_TAIL.matcher(x).replaceAll("");
        return NOT_WORD.matcher(x).replaceAll(" ").trim();
    }

    private static String songKey(Model.Track t) {
        return norm(t.artist) + "|" + norm(t.title);
    }

    private static String artistKey(Model.Track t) {
        return t.artistMbid != null && t.artistMbid.length() > 0 ? t.artistMbid : norm(t.artist);
    }

    /** A different few of an artist's best-known songs each day (each song once, live and remastered versions folded in). */
    static List<Model.Track> pickTop(List<Model.Track> top, int count, String salt) {
        Set<String> seen = new HashSet<String>();
        List<Model.Track> unique = new ArrayList<Model.Track>();
        for (Model.Track t : top) if (seen.add(norm(t.title)) && unique.size() < 8) unique.add(t);
        List<Model.Track> shuffled = Taste.dayShuffle(unique, salt);
        return shuffled.size() > count ? new ArrayList<Model.Track>(shuffled.subList(0, count)) : shuffled;
    }

    /** Put the sources together: a song from each in turn, no repeats, a cap per artist, nothing hidden, no artist twice in a row. */
    static List<Model.Track> blend(List<List<Model.Track>> groups, Hidden hidden) {
        List<Model.Track> out = new ArrayList<Model.Track>();
        Set<String> songs = new HashSet<String>();
        Map<String, Integer> perArtist = new HashMap<String, Integer>();
        for (int cap = 2; cap <= 3; cap++) {
            if (cap == 3 && out.size() >= ENOUGH) break;
            int[] at = new int[groups.size()];
            boolean left = true;
            while (left && out.size() < MAX) {
                left = false;
                for (int i : ORDER) {
                    if (i >= groups.size()) continue;
                    List<Model.Track> g = groups.get(i);
                    while (at[i] < g.size()) {
                        Model.Track t = g.get(at[i]++);
                        String a = artistKey(t);
                        Integer n = perArtist.get(a);
                        if (songs.contains(songKey(t)) || (t.mbid != null && songs.contains(t.mbid)) || hidden.hides(t) || (n != null && n >= cap)) continue;
                        songs.add(songKey(t));
                        if (t.mbid != null) songs.add(t.mbid);
                        perArtist.put(a, n == null ? 1 : n + 1);
                        out.add(t);
                        break;
                    }
                    if (at[i] < g.size()) left = true;
                    if (out.size() >= MAX) break;
                }
            }
        }
        // Spread out the same artist.
        for (int i = 1; i < out.size(); i++) {
            if (!artistKey(out.get(i)).equals(artistKey(out.get(i - 1)))) continue;
            for (int j = i + 1; j < out.size(); j++) {
                if (!artistKey(out.get(j)).equals(artistKey(out.get(i)))) {
                    Model.Track x = out.get(i);
                    out.set(i, out.get(j));
                    out.set(j, x);
                    break;
                }
            }
        }
        return out;
    }

    /** Today's mix as it was last built, shown right away while the new one is put together. */
    public static synchronized List<Model.Track> saved() {
        List<Model.Track> out = new ArrayList<Model.Track>();
        try {
            JSONObject o = new JSONObject(MusicNet.prefs().getString(STORE, "{}"));
            if (!today().equals(o.optString("day"))) return out;
            JSONArray a = MusicNet.arr(o.optJSONArray("tracks"));
            for (int i = 0; i < a.length(); i++) {
                Model.Track t = Model.Track.fromJson(a.optJSONObject(i));
                if (t != null) out.add(t);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Today's mix stays in the order it had; songs that are new since then go at the end. */
    static synchronized List<Model.Track> keepToday(List<Model.Track> fresh, Hidden hidden) {
        List<Model.Track> before = saved();
        Set<String> songs = new HashSet<String>();
        for (Model.Track t : before) songs.add(songKey(t));
        List<Model.Track> merged = new ArrayList<Model.Track>();
        for (Model.Track t : before) if (!hidden.hides(t) && merged.size() < MAX) merged.add(t);
        for (Model.Track t : fresh) if (!songs.contains(songKey(t)) && !hidden.hides(t) && merged.size() < MAX) merged.add(t);
        JSONArray a = new JSONArray();
        for (Model.Track t : merged) a.put(t.toJson());
        try {
            MusicNet.prefs().edit().putString(STORE, new JSONObject().put("day", today()).put("tracks", a).toString()).apply();
        } catch (Exception ignored) {
        }
        return merged;
    }

    private static List<Model.Track> top(String id, String name) {
        try {
            return Model.list(MusicBrainz.getTopTracks(id, name));
        } catch (Exception e) {
            return new ArrayList<Model.Track>();
        }
    }

    /**
     * Builds today's mix. Blocking (network), so call it off the main thread. seeds = your artists (Taste.topSeeds(8)),
     * similar = artists like them (Taste.mergeSimilar), liked = your hearted songs, user = your ListenBrainz name or null.
     */
    public static List<Model.Track> build(List<Taste.Seed> seeds, List<Model.Artist> similar, List<Model.Track> liked, String user, Hidden hidden) {
        String day = today();
        List<Taste.Seed> mine = seeds.size() > 8 ? seeds.subList(0, 8) : seeds;
        List<Model.Artist> like = new ArrayList<Model.Artist>(similar.subList(0, Math.min(8, similar.size())));
        if (similar.size() > 8) like.addAll(Taste.dayShuffle(similar.subList(8, similar.size()), "like" + day));
        if (like.size() > 16) like = new ArrayList<Model.Artist>(like.subList(0, 16));

        List<List<Model.Track>> ownLists = new ArrayList<List<Model.Track>>();
        for (Taste.Seed s : mine) ownLists.add(pickTop(top(s.id, s.name), 4, "own" + s.id));
        List<Model.Track> own = new ArrayList<Model.Track>();
        for (int r = 0; r < 4; r++) for (List<Model.Track> l : ownLists) if (r < l.size()) own.add(l.get(r));

        List<List<Model.Track>> likeLists = new ArrayList<List<Model.Track>>();
        for (Model.Artist a : like) likeLists.add(pickTop(top(a.id, a.name), 3, "like" + a.id));
        List<Model.Track> likeTracks = new ArrayList<Model.Track>();
        for (int r = 0; r < 3; r++) for (List<Model.Track> l : likeLists) if (r < l.size()) likeTracks.add(l.get(r));

        List<Model.Track> hearted = Taste.dayShuffle(liked, "liked");
        if (hearted.size() > 12) hearted = new ArrayList<Model.Track>(hearted.subList(0, 12));
        List<Model.Track>[] made = madeForYou(user);
        List<Model.Track> explore = Taste.dayShuffle(made[1], "explore");
        if (explore.size() > 15) explore = new ArrayList<Model.Track>(explore.subList(0, 15));

        List<List<Model.Track>> groups = new ArrayList<List<Model.Track>>();
        groups.add(made[0]);
        groups.add(own);
        groups.add(hearted);
        groups.add(likeTracks);
        groups.add(explore);
        return keepToday(blend(groups, hidden), hidden);
    }
}
