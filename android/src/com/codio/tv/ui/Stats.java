package com.codio.tv.ui;

import android.content.SharedPreferences;

import com.codio.tv.music.Model;
import com.codio.tv.music.MusicNet;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Your stats (TV and phone): watching from the profile's library, listening from a play log on this device. Same rules
 * as ios/Codio/Store/Stats.swift and windows/.../Codio/stats.ts (DESIGN.md "Your stats"). Nothing is sent anywhere.
 */
public final class Stats {
    private static final String WATCH = "stats_watch_v1", LISTEN = "stats_listen_v1";
    public static final int MAX_SONGS = 2000;
    private static final Map<String, Integer> lastYear = new HashMap<>();

    private Stats() {
    }

    public static int thisYear() {
        return Calendar.getInstance().get(Calendar.YEAR);
    }

    private static SharedPreferences prefs() {
        return MusicNet.prefs();
    }

    private static JSONObject read(String key) {
        SharedPreferences p = prefs();
        if (p == null) return new JSONObject();
        try {
            return new JSONObject(p.getString(key, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void write(String key, JSONObject o) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString(key, o.toString()).apply();
    }

    // ---- Watching ----

    /** Called whenever the library is read: the first time in a new year, every title's time is saved as that year's start. */
    public static void observe(Collection<Store.LibraryEntry> lib, String profile) {
        if (lib.isEmpty() || prefs() == null) return;
        int year = thisYear();
        String key = WATCH + "|" + profile;
        Integer done = lastYear.get(key);
        if (done != null && done == year) return;
        lastYear.put(key, year);
        JSONObject o = read(key);
        int seen = o.optInt("seen", 0);
        if (seen >= year) return;
        try {
            if (seen > 0) {
                JSONObject start = new JSONObject();
                for (Store.LibraryEntry e : lib) if (e.overall > 0) start.put(e.id, e.overall);
                JSONObject base = o.optJSONObject("base");
                if (base == null) base = new JSONObject();
                base.put(String.valueOf(year), start);
                List<String> old = new ArrayList<>();
                for (Iterator<String> it = base.keys(); it.hasNext(); ) {
                    String k = it.next();
                    try {
                        if (Integer.parseInt(k) < year - 4) old.add(k);
                    } catch (NumberFormatException ex) {
                        old.add(k);
                    }
                }
                for (String k : old) base.remove(k);
                o.put("base", base);
            }
            o.put("seen", year);
        } catch (Exception ignored) {
        }
        write(key, o);
    }

    public static final class Title {
        public Store.LibraryEntry entry;
        public double ms;
    }

    public static final class Watch {
        public double ms;
        public final List<Title> shows = new ArrayList<>(), movies = new ArrayList<>();
    }

    /** year 0 = all time. */
    public static Watch watch(List<Store.LibraryEntry> lib, String profile, int year) {
        JSONObject start = null;
        if (year > 0) {
            JSONObject base = read(WATCH + "|" + profile).optJSONObject("base");
            if (base != null) start = base.optJSONObject(String.valueOf(year));
        }
        Watch out = new Watch();
        String y = String.valueOf(year);
        for (Store.LibraryEntry e : lib) {
            if (!("movie".equals(e.type) || "series".equals(e.type)) || e.overall <= 0 || e.meta == null) continue;
            double ms = e.overall;
            if (year > 0) {
                if (e.lastWatched == null || !e.lastWatched.startsWith(y)) continue;
                if (start != null) ms -= start.optDouble(e.id, 0);
            }
            if (ms < 60000) continue;
            out.ms += ms;
            Title t = new Title();
            t.entry = e;
            t.ms = ms;
            ("series".equals(e.type) ? out.shows : out.movies).add(t);
        }
        Comparator<Title> byTime = new Comparator<Title>() {
            @Override
            public int compare(Title a, Title b) {
                return Double.compare(b.ms, a.ms);
            }
        };
        Collections.sort(out.shows, byTime);
        Collections.sort(out.movies, byTime);
        return out;
    }

    /** Hours with one decimal under 10 ("2.5", "14"). */
    public static String hours(double ms) {
        double h = ms / 3600000.0;
        if (h < 10) return String.format(Locale.US, "%.1f", h);
        return String.valueOf(Math.round(h));
    }

    public static String count(int n, String word) {
        return n == 1 ? "1 " + word : n + " " + word + "s";
    }

    // ---- Listening ----

    public static final class Song {
        public Model.Track track;
        public int count;
    }

    public static final class Artist {
        public String name;
        public int count;
    }

    public static final class Listen {
        public double seconds;
        public int plays;
        public final List<Song> songs = new ArrayList<>();
        public final List<Artist> artists = new ArrayList<>();
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /** A song started playing (any source, downloads too). */
    public static synchronized void record(Model.Track t) {
        if (t == null || prefs() == null) return;
        try {
            JSONObject all = read(LISTEN);
            String profile = Store.profileId();
            JSONObject mine = all.optJSONObject(profile);
            if (mine == null) all.put(profile, mine = new JSONObject());
            String year = String.valueOf(thisYear());
            JSONObject y = mine.optJSONObject(year);
            if (y == null) mine.put(year, y = new JSONObject());
            y.put("plays", y.optInt("plays") + 1);
            y.put("seconds", y.optDouble("seconds", 0) + Math.max(0, t.durationMs) / 1000.0);
            JSONObject songs = y.optJSONObject("songs");
            if (songs == null) y.put("songs", songs = new JSONObject());
            String sk = norm(t.artist) + "|" + norm(t.title);
            JSONObject song = songs.optJSONObject(sk);
            if (song == null) song = new JSONObject().put("track", t.toJson()).put("count", 0);
            song.put("count", song.optInt("count") + 1);
            if (t.artworkURL != null) song.put("track", t.toJson());
            songs.put(sk, song);
            if (songs.length() > MAX_SONGS) {
                List<String> keys = new ArrayList<>();
                for (Iterator<String> it = songs.keys(); it.hasNext(); ) keys.add(it.next());
                final JSONObject ss = songs;
                Collections.sort(keys, new Comparator<String>() {
                    @Override
                    public int compare(String a, String b) {
                        return ss.optJSONObject(b).optInt("count") - ss.optJSONObject(a).optInt("count");
                    }
                });
                for (int i = MAX_SONGS; i < keys.size(); i++) songs.remove(keys.get(i));
            }
            String ak = norm(t.artist);
            if (!ak.isEmpty()) {
                JSONObject artists = y.optJSONObject("artists");
                if (artists == null) y.put("artists", artists = new JSONObject());
                JSONObject a = artists.optJSONObject(ak);
                artists.put(ak, new JSONObject().put("name", t.artist).put("count", (a == null ? 0 : a.optInt("count")) + 1));
            }
            write(LISTEN, all);
        } catch (Exception ignored) {
        }
    }

    /** year 0 = all years added together. */
    public static Listen listen(int year) {
        Listen out = new Listen();
        JSONObject mine = read(LISTEN).optJSONObject(Store.profileId());
        if (mine == null) return out;
        Map<String, Song> songs = new HashMap<>();
        Map<String, Artist> artists = new HashMap<>();
        for (Iterator<String> it = mine.keys(); it.hasNext(); ) {
            String k = it.next();
            if (year > 0 && !k.equals(String.valueOf(year))) continue;
            JSONObject y = mine.optJSONObject(k);
            if (y == null) continue;
            out.plays += y.optInt("plays");
            out.seconds += y.optDouble("seconds", 0);
            JSONObject ss = y.optJSONObject("songs");
            if (ss != null) for (Iterator<String> i = ss.keys(); i.hasNext(); ) {
                String sk = i.next();
                JSONObject v = ss.optJSONObject(sk);
                if (v == null) continue;
                Song s = songs.get(sk);
                if (s == null) {
                    Model.Track t = Model.Track.fromJson(v.optJSONObject("track"));
                    if (t == null) continue;
                    s = new Song();
                    s.track = t;
                    songs.put(sk, s);
                }
                s.count += v.optInt("count");
            }
            JSONObject as = y.optJSONObject("artists");
            if (as != null) for (Iterator<String> i = as.keys(); i.hasNext(); ) {
                String ak = i.next();
                JSONObject v = as.optJSONObject(ak);
                if (v == null) continue;
                Artist a = artists.get(ak);
                if (a == null) {
                    a = new Artist();
                    a.name = v.optString("name", ak);
                    artists.put(ak, a);
                }
                a.count += v.optInt("count");
            }
        }
        out.songs.addAll(songs.values());
        out.artists.addAll(artists.values());
        Collections.sort(out.songs, new Comparator<Song>() {
            @Override
            public int compare(Song a, Song b) {
                return b.count - a.count;
            }
        });
        Collections.sort(out.artists, new Comparator<Artist>() {
            @Override
            public int compare(Artist a, Artist b) {
                return b.count - a.count;
            }
        });
        return out;
    }
}
