package com.codio.tv.ui;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Calendar (Library > Calendar): when the next episodes and movies of what you follow come out.
 *
 * Follows the movies and shows in the Library plus the shows watched in the last six months (at most 60, the most
 * recent first). Dates come from each title's page at the catalog addon (Cinemeta lists every episode, also the ones
 * still to come). Shared by the TV (CalendarScreen) and the phone (PhoneCalendar); the iPhone's
 * ios/Codio/Store/ReleaseCalendar.swift follows the same rules. See DESIGN.md "Calendar".
 */
public final class ReleaseCalendar {
    private ReleaseCalendar() {}

    /** One row: a movie, an episode, or several episodes of a show that come out the same day. */
    public static final class Item {
        public String showId, type, name, poster, videoId, title;
        public int season, episode, lastEpisode;
        /** Episodes of the show out that day (1 for a movie). */
        public int count = 1;
        /** The local day it comes out, "yyyy-MM-dd". */
        public String day;
        public long released;

        /** "S2 E5 · Title", "S2 E1–E8 · 8 episodes" or "Movie". */
        public String line() {
            if ("movie".equals(type)) return "Movie";
            if (count > 1) return "S" + season + " E" + episode + "–E" + lastEpisode + " · " + count + " episodes";
            return "S" + season + " E" + episode + (title == null || title.isEmpty() ? "" : " · " + title);
        }

        public Store.Meta meta(Store store) {
            Store.LibraryEntry e = store.libraryEntry(showId);
            if (e != null && e.meta != null) return e.meta;
            Store.Meta m = new Store.Meta();
            m.id = showId;
            m.type = type;
            m.name = name;
            m.poster = poster;
            return m;
        }
    }

    /** A day heading and its rows. */
    public static final class Section {
        public String day, heading;
        public final List<Item> items = new ArrayList<>();
    }

    private static final long DAY = 86400_000L;
    /** How far back ("Out this past week") and ahead the page looks. */
    private static final long BACK = 7 * DAY, AHEAD = 365 * DAY;
    private static final long WATCHING = 180 * DAY;
    private static final int MAX_TITLES = 60;
    private static final long REFRESH_EVERY = 6 * 3600_000L;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, Net.named("codio-calendar", Thread.NORM_PRIORITY - 1));
    private static volatile List<Item> items = new ArrayList<>();
    private static volatile boolean loading, loaded;
    private static String sig = "";
    private static long checked;
    private static final List<Runnable> listeners = new ArrayList<>();

    public static boolean loading() {
        return loading;
    }

    public static boolean loaded() {
        return loaded;
    }

    /** Main thread: run r whenever the rows changed. */
    public static void listen(Runnable r) {
        if (!listeners.contains(r)) listeners.add(r);
    }

    public static void unlisten(Runnable r) {
        listeners.remove(r);
    }

    /**
     * Main thread. Reads the titles again when what's followed changed or the last read is older than six hours
     * (force: always).
     */
    public static void refresh(Store store, boolean force) {
        if (!store.profileLoaded || loading) return;
        final List<Store.LibraryEntry> titles = store.calendarTitles(System.currentTimeMillis() - WATCHING, MAX_TITLES);
        final List<Store.Addon> addons = store.addons();
        StringBuilder sb = new StringBuilder();
        for (Store.LibraryEntry e : titles) sb.append(e.type).append('/').append(e.id).append(',');
        sb.append('|');
        for (Store.Addon a : addons) sb.append(a.base).append(',');
        String s = sb.toString();
        if (!force && s.equals(sig) && System.currentTimeMillis() - checked < REFRESH_EVERY) return;
        sig = s;
        loading = true;
        if (titles.isEmpty()) {
            finish(new ArrayList<Item>());
            return;
        }
        final List<Item> found = Collections.synchronizedList(new ArrayList<Item>());
        final AtomicInteger left = new AtomicInteger(titles.size());
        for (final Store.LibraryEntry e : titles) {
            final String name = e.meta != null ? e.meta.name : "", poster = e.meta != null ? e.meta.poster : null;
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        found.addAll(itemsFor(e.id, e.type, name, poster, addons));
                    } catch (Throwable ignored) {
                    }
                    if (left.decrementAndGet() == 0) {
                        final List<Item> all;
                        synchronized (found) {
                            all = new ArrayList<>(found);
                        }
                        Collections.sort(all, new Comparator<Item>() {
                            @Override
                            public int compare(Item a, Item b) {
                                if (a.released != b.released) return Long.compare(a.released, b.released);
                                return String.valueOf(a.name).compareTo(String.valueOf(b.name));
                            }
                        });
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                finish(all);
                            }
                        });
                    }
                }
            });
        }
    }

    private static void finish(List<Item> all) {
        items = all;
        checked = System.currentTimeMillis();
        loading = false;
        loaded = true;
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    /** The days to show: today and later first, then what came out in the past week (newest first). */
    public static List<Section> sections() {
        String today = dayKey(System.currentTimeMillis());
        Map<String, Section> ahead = new LinkedHashMap<>();
        Section past = null;
        List<Item> list = items;
        for (Item i : list) {
            if (i.day.compareTo(today) >= 0) {
                Section s = ahead.get(i.day);
                if (s == null) {
                    s = new Section();
                    s.day = i.day;
                    s.heading = heading(i.day, today);
                    ahead.put(i.day, s);
                }
                s.items.add(i);
            }
        }
        for (int k = list.size() - 1; k >= 0; k--) {
            Item i = list.get(k);
            if (i.day.compareTo(today) >= 0) continue;
            if (past == null) {
                past = new Section();
                past.day = "past";
                past.heading = "Out this past week";
            }
            past.items.add(i);
        }
        List<Section> out = new ArrayList<>(ahead.values());
        if (past != null) out.add(past);
        return out;
    }

    /** The first thing still to come, for the Calendar row in the Library; null when there is none. */
    public static Item next() {
        String today = dayKey(System.currentTimeMillis());
        for (Item i : items) if (i.day.compareTo(today) >= 0) return i;
        return null;
    }

    /** "Next: Show S2 E5 · Friday", or what the row says while there is nothing yet. */
    public static String nextLine() {
        Item n = next();
        if (n != null) return "Next: " + n.name + ("movie".equals(n.type) ? "" : " S" + n.season + " E" + n.episode) + " · " + shortDay(n.day);
        return loaded ? "Upcoming episodes and movies" : "Looking for release dates…";
    }

    // ------------------------------------------------------------------ reading

    private static List<Item> itemsFor(String id, String type, String name, String poster, List<Store.Addon> addons) {
        for (Store.Addon a : addons) {
            if (!a.supports("meta", type, id)) continue;
            try {
                JSONObject r = new JSONObject(Net.get(a.base + "/meta/" + Store.enc(type) + "/" + Store.enc(id) + ".json"));
                JSONObject m = r.optJSONObject("meta");
                if (m != null) return parse(m, id, type, name, poster);
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<>();
    }

    private static List<Item> parse(JSONObject m, String id, String type, String name, String poster) {
        long now = System.currentTimeMillis();
        String n = Store.str(m, "name");
        if (n != null) name = n;
        String p = Store.str(m, "poster");
        if (p != null) poster = p;
        List<Item> out = new ArrayList<>();
        if ("movie".equals(type)) {
            String rel = Store.str(m, "released");
            long r = NewEpisodes.parseIso(rel);
            if (r <= 0 || r < now - BACK || r > now + AHEAD) return out;
            Item i = new Item();
            i.showId = id;
            i.type = "movie";
            i.name = name;
            i.poster = poster;
            i.videoId = id;
            i.title = "";
            i.day = localDay(rel);
            i.released = r;
            out.add(i);
            return out;
        }
        Map<String, Item> byDay = new HashMap<>();
        JSONArray videos = m.optJSONArray("videos");
        for (int k = 0; videos != null && k < videos.length(); k++) {
            JSONObject v = videos.optJSONObject(k);
            if (v == null) continue;
            int season = v.optInt("season", 0);
            String rel = Store.str(v, "released");
            long r = NewEpisodes.parseIso(rel);
            if (season <= 0 || r <= 0 || r < now - BACK || r > now + AHEAD) continue;
            int ep = v.has("episode") ? v.optInt("episode", 0) : v.optInt("number", 0);
            String title = Store.str(v, "title");
            if (title == null) title = Store.str(v, "name");
            String day = localDay(rel);
            Item have = byDay.get(day);
            if (have != null) {
                have.count++;
                if (season * 10000 + ep < have.season * 10000 + have.episode) {
                    have.season = season;
                    have.episode = ep;
                    have.videoId = v.optString("id", have.videoId);
                    have.title = title == null ? "" : title;
                }
                if (season == have.season) have.lastEpisode = Math.max(have.lastEpisode, ep);
                continue;
            }
            Item i = new Item();
            i.showId = id;
            i.type = "series";
            i.name = name;
            i.poster = poster;
            i.videoId = v.optString("id", "");
            i.title = title == null ? "" : title;
            i.season = season;
            i.episode = ep;
            i.lastEpisode = ep;
            i.day = day;
            i.released = r;
            byDay.put(day, i);
        }
        out.addAll(byDay.values());
        return out;
    }

    // ------------------------------------------------------------------ days

    /** The local day of an addon date: a date with a time is moved into this device's time zone, a bare date stays. */
    static String localDay(String s) {
        if (s == null || s.length() < 10) return "";
        if (s.length() >= 19 && s.charAt(10) == 'T') return dayKey(NewEpisodes.parseIso(s));
        return s.substring(0, 10);
    }

    static String dayKey(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return f.format(new Date(ms));
    }

    /** "Today", "Tomorrow", "Friday" (this week), else "Sat 13 Dec" ("Sat 13 Dec 2027" in another year). */
    public static String heading(String day, String today) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            long d = f.parse(day).getTime(), t = f.parse(today).getTime();
            int days = Math.round((d - t) / (float) DAY);
            if (days == 0) return "Today";
            if (days == 1) return "Tomorrow";
            SimpleDateFormat out = new SimpleDateFormat(days > 1 && days < 7 ? "EEEE"
                    : day.startsWith(today.substring(0, 4)) ? "EEE d MMM" : "EEE d MMM yyyy", Locale.US);
            out.setTimeZone(TimeZone.getTimeZone("UTC"));
            return out.format(new Date(d));
        } catch (Exception e) {
            return day;
        }
    }

    /** "Today", "Tomorrow", "Friday" or "13 Dec": short, for the Library row and the past week. */
    public static String shortDay(String day) {
        String h = heading(day, dayKey(System.currentTimeMillis()));
        String[] parts = h.split(" ");
        return parts.length >= 3 ? parts[1] + " " + parts[2] : h;
    }
}
