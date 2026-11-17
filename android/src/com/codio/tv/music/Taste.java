package com.codio.tv.music;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What you like, kept on this device only: the artists you play, the songs, albums and artists you heart, and the ones you
 * said "Not interested" to. The Music home builds its rows from this. A port of the Windows app's taste.ts; the saved JSON
 * (key "codioMusicTaste") has the same shape. Use from the main thread.
 */
public final class Taste {
    private Taste() {}

    private static final String KEY = "codioMusicTaste";

    public static final class Play {
        public String name = "";
        public double count;
        public long last;
    }

    /** Artist (MusicBrainz id) -> how often you played them. */
    public static final Map<String, Play> plays = new LinkedHashMap<String, Play>();
    public static final List<Model.Track> likedTracks = new ArrayList<Model.Track>();
    public static final List<Model.Album> likedAlbums = new ArrayList<Model.Album>();
    public static final List<Model.Artist> likedArtists = new ArrayList<Model.Artist>();
    /** id -> name, for the lists where you can bring them back. */
    public static final Map<String, String> hiddenArtists = new LinkedHashMap<String, String>();
    public static final Map<String, String> hiddenAlbums = new LinkedHashMap<String, String>();
    public static final Map<String, String> hiddenTracks = new LinkedHashMap<String, String>();

    /** The hearted songs (a copy is not made: don't change it). */
    public static List<Model.Track> likedTracks() {
        load();
        return likedTracks;
    }

    public static List<Model.Album> likedAlbums() {
        load();
        return likedAlbums;
    }

    public static List<Model.Artist> likedArtists() {
        load();
        return likedArtists;
    }

    /** Told after every change (screens redraw). */
    public static final Set<Runnable> listeners = new HashSet<Runnable>();

    /** Told when you heart or un-heart a song (the ListenBrainz account, when signed in, keeps them in step). */
    public interface LikeHook {
        void liked(Model.Track track, boolean liked);
    }

    public static final Set<LikeHook> likeHooks = new HashSet<LikeHook>();

    private static boolean loaded;

    /** Reads what was saved; safe to call often. MusicNet.init must have run. */
    public static void load() {
        if (loaded) return;
        loaded = true;
        try {
            JSONObject t = new JSONObject(MusicNet.prefs().getString(KEY, "{}"));
            JSONObject p = t.optJSONObject("plays");
            if (p != null) for (Iterator<String> it = p.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject v = p.optJSONObject(id);
                if (v == null) continue;
                Play play = new Play();
                play.name = v.optString("name", "");
                play.count = v.optDouble("count", 0);
                play.last = v.optLong("last", 0);
                plays.put(id, play);
            }
            JSONArray a = t.optJSONArray("likedTracks");
            for (int i = 0; a != null && i < a.length(); i++) {
                Model.Track x = Model.Track.fromJson(a.optJSONObject(i));
                if (x != null) likedTracks.add(x);
            }
            a = t.optJSONArray("likedAlbums");
            for (int i = 0; a != null && i < a.length(); i++) {
                Model.Album x = Model.Album.fromJson(a.optJSONObject(i));
                if (x != null) likedAlbums.add(x);
            }
            a = t.optJSONArray("likedArtists");
            for (int i = 0; a != null && i < a.length(); i++) {
                Model.Artist x = Model.Artist.fromJson(a.optJSONObject(i));
                if (x != null) likedArtists.add(x);
            }
            readMap(t.optJSONObject("hiddenArtists"), hiddenArtists);
            readMap(t.optJSONObject("hiddenAlbums"), hiddenAlbums);
            readMap(t.optJSONObject("hiddenTracks"), hiddenTracks);
        } catch (Exception ignored) {
        }
    }

    private static void readMap(JSONObject o, Map<String, String> into) {
        if (o == null) return;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            into.put(k, o.optString(k, ""));
        }
    }

    private static void commit() {
        try {
            JSONObject t = new JSONObject();
            JSONObject p = new JSONObject();
            for (Map.Entry<String, Play> e : plays.entrySet()) {
                p.put(e.getKey(), new JSONObject().put("name", e.getValue().name).put("count", e.getValue().count).put("last", e.getValue().last));
            }
            t.put("plays", p);
            JSONArray a = new JSONArray();
            for (Model.Track x : likedTracks) a.put(x.toJson());
            t.put("likedTracks", a);
            a = new JSONArray();
            for (Model.Album x : likedAlbums) a.put(x.toJson());
            t.put("likedAlbums", a);
            a = new JSONArray();
            for (Model.Artist x : likedArtists) a.put(x.toJson());
            t.put("likedArtists", a);
            t.put("hiddenArtists", new JSONObject(hiddenArtists));
            t.put("hiddenAlbums", new JSONObject(hiddenAlbums));
            t.put("hiddenTracks", new JSONObject(hiddenTracks));
            MusicNet.prefs().edit().putString(KEY, t.toString()).apply();
        } catch (JSONException ignored) {
        }
        for (Runnable l : new ArrayList<Runnable>(listeners)) l.run();
    }

    private static <T> void cap(List<T> list, int max) {
        while (list.size() > max) list.remove(list.size() - 1);
    }

    /** A track started playing. */
    public static void recordPlay(Model.Track t) {
        load();
        if (t == null || t.artistMbid == null) return;
        Play p = plays.get(t.artistMbid);
        if (p == null) plays.put(t.artistMbid, p = new Play());
        p.name = t.artist;
        p.count += 1;
        p.last = System.currentTimeMillis();
        commit();
    }

    public static boolean isLikedTrack(Model.Track t) {
        load();
        for (Model.Track x : likedTracks) if (x.same(t)) return true;
        return false;
    }

    public static boolean isLikedAlbum(Model.Album a) {
        load();
        for (Model.Album x : likedAlbums) if (x.id.equals(a.id)) return true;
        return false;
    }

    public static boolean isLikedArtist(Model.Artist a) {
        return isLikedArtist(a.id);
    }

    public static boolean isLikedArtist(String id) {
        load();
        for (Model.Artist x : likedArtists) if (x.id.equals(id)) return true;
        return false;
    }

    private static boolean removeTrack(Model.Track t) {
        boolean had = false;
        for (Iterator<Model.Track> it = likedTracks.iterator(); it.hasNext(); ) if (it.next().same(t)) {
            it.remove();
            had = true;
        }
        return had;
    }

    private static void hooks(Model.Track t, boolean liked) {
        for (LikeHook h : new ArrayList<LikeHook>(likeHooks)) h.liked(t, liked);
    }

    /** Hearts or un-hearts a song; returns whether it is hearted now. */
    public static boolean toggleLikeTrack(Model.Track track) {
        load();
        boolean liked = removeTrack(track);
        hiddenTracks.remove(track.key());
        if (!liked) {
            likedTracks.add(0, track);
            cap(likedTracks, 500);
        }
        commit();
        hooks(track, !liked);
        return !liked;
    }

    public static boolean toggleLikeAlbum(Model.Album album) {
        load();
        boolean liked = false;
        for (Iterator<Model.Album> it = likedAlbums.iterator(); it.hasNext(); ) if (it.next().id.equals(album.id)) {
            it.remove();
            liked = true;
        }
        hiddenAlbums.remove(album.id);
        if (!liked) {
            likedAlbums.add(0, album);
            cap(likedAlbums, 300);
        }
        commit();
        return !liked;
    }

    public static boolean toggleLikeArtist(Model.Artist artist) {
        load();
        boolean liked = false;
        for (Iterator<Model.Artist> it = likedArtists.iterator(); it.hasNext(); ) if (it.next().id.equals(artist.id)) {
            it.remove();
            liked = true;
        }
        hiddenArtists.remove(artist.id);
        if (!liked) {
            likedArtists.add(0, artist);
            cap(likedArtists, 300);
        }
        commit();
        return !liked;
    }

    /** Says whether a change made here just now should win over what the account says. */
    public interface Keep {
        boolean keep(Model.Track t);
    }

    /**
     * The songs loved on your ListenBrainz account (from any device). For songs with a MusicBrainz id the account is the
     * truth: loved ones are added here, ones no longer loved are dropped, except those keep() says changed here just now.
     */
    public static void applyLovedFromAccount(Set<String> lovedIds, List<Model.Track> loved, Keep keep) {
        load();
        List<Model.Track> kept = new ArrayList<Model.Track>();
        Set<String> have = new HashSet<String>();
        for (Model.Track t : likedTracks) {
            if (t.mbid == null || lovedIds.contains(t.mbid) || keep.keep(t)) {
                kept.add(t);
                if (t.mbid != null) have.add(t.mbid);
            }
        }
        List<Model.Track> added = new ArrayList<Model.Track>();
        for (Model.Track t : loved) {
            if (t.mbid != null && !have.contains(t.mbid) && !hiddenTracks.containsKey(t.key()) && !keep.keep(t)) {
                added.add(t);
                have.add(t.mbid);
            }
        }
        if (added.isEmpty() && kept.size() == likedTracks.size()) return;
        likedTracks.clear();
        likedTracks.addAll(kept);
        likedTracks.addAll(added);
        cap(likedTracks, 500);
        commit();
    }

    public static final class AccountPlay {
        public String artistMbid;
        public String name;
        public long at;
    }

    /** Plays from your other devices (ListenBrainz listens): they count towards your taste like the ones here. */
    public static void addPlaysFromAccount(List<AccountPlay> list) {
        load();
        if (list.isEmpty()) return;
        for (AccountPlay a : list) {
            if (hiddenArtists.containsKey(a.artistMbid)) continue;
            Play p = plays.get(a.artistMbid);
            if (p == null) {
                plays.put(a.artistMbid, p = new Play());
                p.name = a.name;
            }
            p.count += 1;
            p.last = Math.max(p.last, a.at);
        }
        commit();
    }

    /** "Not interested": gone from the rows, and no longer counts towards your taste. */
    public static void hideTrack(Model.Track track) {
        load();
        boolean wasLiked = removeTrack(track);
        hiddenTracks.put(track.key(), track.title + " · " + track.artist);
        commit();
        if (wasLiked) hooks(track, false);
    }

    public static void hideAlbum(Model.Album album) {
        load();
        for (Iterator<Model.Album> it = likedAlbums.iterator(); it.hasNext(); ) if (it.next().id.equals(album.id)) it.remove();
        hiddenAlbums.put(album.id, album.title + " · " + album.artist);
        commit();
    }

    public static void hideArtist(Model.Artist artist) {
        hideArtist(artist.id, artist.name);
    }

    public static void hideArtist(String id, String name) {
        load();
        plays.remove(id);
        for (Iterator<Model.Artist> it = likedArtists.iterator(); it.hasNext(); ) if (it.next().id.equals(id)) it.remove();
        hiddenArtists.put(id, name);
        commit();
    }

    /** kind: "artist", "album" or "track". */
    public static void unhide(String kind, String key) {
        load();
        ("artist".equals(kind) ? hiddenArtists : "album".equals(kind) ? hiddenAlbums : hiddenTracks).remove(key);
        commit();
    }

    public static void unhideAll() {
        load();
        hiddenArtists.clear();
        hiddenAlbums.clear();
        hiddenTracks.clear();
        commit();
    }

    public static void clearHistory() {
        load();
        plays.clear();
        commit();
    }

    public static void clearAll() {
        load();
        plays.clear();
        likedTracks.clear();
        likedAlbums.clear();
        likedArtists.clear();
        hiddenArtists.clear();
        hiddenAlbums.clear();
        hiddenTracks.clear();
        commit();
    }

    public static int hiddenCount() {
        load();
        return hiddenArtists.size() + hiddenAlbums.size() + hiddenTracks.size();
    }

    public static boolean isHiddenTrack(Model.Track t) {
        load();
        return hiddenTracks.containsKey(t.key()) || (t.artistMbid != null && hiddenArtists.containsKey(t.artistMbid));
    }

    public static boolean isHiddenAlbum(Model.Album a) {
        load();
        return hiddenAlbums.containsKey(a.id) || (a.artistMbid != null && hiddenArtists.containsKey(a.artistMbid));
    }

    public static boolean isHiddenArtist(Model.Artist a) {
        load();
        return hiddenArtists.containsKey(a.id);
    }

    public static List<Model.Track> visibleTracks(List<Model.Track> list) {
        List<Model.Track> out = new ArrayList<Model.Track>();
        for (Model.Track x : list) if (!isHiddenTrack(x)) out.add(x);
        return out;
    }

    public static List<Model.Album> visibleAlbums(List<Model.Album> list) {
        List<Model.Album> out = new ArrayList<Model.Album>();
        for (Model.Album x : list) if (!isHiddenAlbum(x)) out.add(x);
        return out;
    }

    public static List<Model.Artist> visibleArtists(List<Model.Artist> list) {
        List<Model.Artist> out = new ArrayList<Model.Artist>();
        for (Model.Artist x : list) if (!isHiddenArtist(x)) out.add(x);
        return out;
    }

    public static final class Seed {
        public String id;
        public String name;
        public double score;
    }

    /**
     * The artists you like most, best first. A play counts 1 (less as it gets old), a heart on the artist 6, a hearted song
     * or album by them 3.
     */
    public static List<Seed> topSeeds(int limit) {
        load();
        final Map<String, Seed> scores = new LinkedHashMap<String, Seed>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Play> e : plays.entrySet()) {
            Play p = e.getValue();
            add(scores, e.getKey(), p.name, p.count / (1 + (now - p.last) / (30.0 * 24 * 3600 * 1000)));
        }
        for (Model.Artist a : likedArtists) add(scores, a.id, a.name, 6);
        for (Model.Track x : likedTracks) add(scores, x.artistMbid, x.artist, 3);
        for (Model.Album x : likedAlbums) add(scores, x.artistMbid, x.artist, 3);
        List<Seed> out = new ArrayList<Seed>(scores.values());
        Collections.sort(out, new Comparator<Seed>() {
            @Override
            public int compare(Seed a, Seed b) {
                return Double.compare(b.score, a.score);
            }
        });
        return out.size() > limit ? new ArrayList<Seed>(out.subList(0, limit)) : out;
    }

    private static void add(Map<String, Seed> scores, String id, String name, double points) {
        if (id == null || hiddenArtists.containsKey(id)) return;
        Seed s = scores.get(id);
        if (s == null) {
            s = new Seed();
            s.id = id;
            s.name = name;
            scores.put(id, s);
        }
        s.score += points;
    }

    /** Artists similar to your favorites, put together: one that shows up for several favorites ranks higher. */
    public static List<Model.Artist> mergeSimilar(List<Seed> seeds, List<List<Model.Artist>> lists) {
        load();
        Set<String> known = new HashSet<String>();
        for (Seed s : seeds) known.add(s.id);
        final Map<String, Double> score = new LinkedHashMap<String, Double>();
        final Map<String, Model.Artist> byId = new LinkedHashMap<String, Model.Artist>();
        for (int i = 0; i < seeds.size() && i < lists.size(); i++) {
            List<Model.Artist> list = lists.get(i);
            for (int rank = 0; list != null && rank < list.size(); rank++) {
                Model.Artist a = list.get(rank);
                if (known.contains(a.id) || hiddenArtists.containsKey(a.id)) continue;
                Double had = score.get(a.id);
                score.put(a.id, (had == null ? 0 : had) + seeds.get(i).score / (1 + rank * 0.2));
                if (!byId.containsKey(a.id)) byId.put(a.id, a);
            }
        }
        List<Model.Artist> out = new ArrayList<Model.Artist>(byId.values());
        Collections.sort(out, new Comparator<Model.Artist>() {
            @Override
            public int compare(Model.Artist a, Model.Artist b) {
                return Double.compare(score.get(b.id), score.get(a.id));
            }
        });
        return out.size() > 24 ? new ArrayList<Model.Artist>(out.subList(0, 24)) : out;
    }

    /** The same shuffle all day, a different one tomorrow. */
    public static <T> List<T> dayShuffle(List<T> list, String salt) {
        Calendar day = Calendar.getInstance();
        String text = day.get(Calendar.YEAR) + "-" + day.get(Calendar.MONTH) + "-" + day.get(Calendar.DAY_OF_MONTH) + (salt == null ? "" : salt);
        long seed = 0;
        for (int i = 0; i < text.length(); i++) seed = (seed * 31 + text.charAt(i)) & 0xffffffffL;
        List<T> copy = new ArrayList<T>(list);
        for (int i = copy.size() - 1; i > 0; i--) {
            seed = (seed * 1664525L + 1013904223L) & 0xffffffffL;
            int j = (int) Math.floor(seed / 4294967296.0 * (i + 1));
            T x = copy.get(i);
            copy.set(i, copy.get(j));
            copy.set(j, x);
        }
        return copy;
    }
}
