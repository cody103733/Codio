package com.codio.tv.ui;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * For You: picks made on this device from what you watch (DESIGN.md "For You"). Same rules as
 * windows/.../Codio/foryou.ts and ios/Codio/Store/ForYou.swift:
 * a taste profile from your history (seeds), TMDB recommendations/similar for the seeds (candidates), a 512-number
 * feature-hashing embedding per title (genres, keywords, cast, director, language, decade, plot words, IDF weighted)
 * to re-rank candidates by closeness to your taste, and a stable daily shake plus a few wildcards for variety.
 * Nothing leaves the device except the TMDB lookups made with the viewer's own key.
 */
final class ForYou {
    static final class PickRow {
        final String id, title;
        final List<Store.Meta> items;

        PickRow(String id, String title, List<Store.Meta> items) {
            this.id = id;
            this.title = title;
            this.items = items;
        }
    }

    enum State { NO_KEY, NO_HISTORY, OK, FAILED, LOADING }

    interface Done {
        void done(State state, List<PickRow> rows);
    }

    private static final String API = "https://api.themoviedb.org/3";
    private static final String PREF = "foryou_v1";
    static final int DIM = 512;
    private static final int MAX_SEEDS = 12, POOL = 60;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = Executors.newFixedThreadPool(6, Net.named("codio-foryou", Thread.NORM_PRIORITY - 1));
    private static volatile boolean building;

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(("with that this from they their them there have when what where which while into about after "
            + "before over under again their than then these those will would could should being been were your yours more most "
            + "some such only also just very each other into onto upon must find finds found life lives world years year young "
            + "take takes back begin begins becomes become along new old one two three first last story film series show "
            + "season episode family man woman men women himself herself itself make makes made way ways time times get gets "
            + "help helps both even ever every between through during against among around because however while former").split(" ")));

    // ------------------------------------------------------------------ helpers (same in all three languages)

    /** FNV-1a, 32 bit, over the UTF-8 bytes, as an unsigned number. */
    static long fnv(String s) {
        int h = 0x811c9dc5;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x01000193;
        }
        return h & 0xffffffffL;
    }

    /** The same number in [0, 1) all day for a title, a new one tomorrow. */
    static double daily(String day, String id) {
        return fnv(day + "|" + id) / 4294967296.0;
    }

    static String today() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** Finished 1, started 0.45, only in the Library 0.3; up to +0.6 for 10 h of watching; faded by age (half after 60 days, never under 35 %). */
    static double seedWeight(boolean finished, boolean started, boolean inLibrary, double overallMs, long lastMs, long now) {
        double base = finished ? 1 : started ? 0.45 : inLibrary ? 0.3 : 0;
        if (base <= 0) return 0;
        base += Math.min(1, overallMs / 36e6) * 0.6;
        double days = lastMs > 0 ? Math.max(0, (now - lastMs) / 864e5) : 365;
        return base * (0.35 + 0.65 * Math.pow(0.5, days / 60));
    }

    // ------------------------------------------------------------------ history

    private static final class Seed {
        final String imdb, name;
        String kind;
        final double weight;

        Seed(String imdb, String kind, String name, double weight) {
            this.imdb = imdb;
            this.kind = kind;
            this.name = name;
            this.weight = weight;
        }
    }

    private static long parseTime(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(iso.length() > 19 ? iso.substring(0, 19) : iso).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static List<Seed> seeds(List<Store.LibraryEntry> list, Set<String> seen) {
        long now = System.currentTimeMillis();
        List<Seed> out = new ArrayList<>();
        for (Store.LibraryEntry e : list) {
            if (e.id == null || !e.id.startsWith("tt") || !("movie".equals(e.type) || "series".equals(e.type))) continue;
            JSONObject st = e.meta != null && e.meta.raw != null ? e.meta.raw.optJSONObject("state") : null;
            boolean finished = st != null && (st.optInt("timesWatched", 0) > 0 || st.optInt("flaggedWatched", 0) == 1)
                    || (e.duration > 0 && e.offset / e.duration > 0.9);
            boolean started = e.offset > 0 || e.overall > 0;
            boolean inLib = !e.removed && !e.temp;
            long last = parseTime(e.lastWatched);
            if (last > 0 || inLib) seen.add(e.id);
            double w = seedWeight(finished, started, inLib, e.overall, last, now);
            if (w > 0) out.add(new Seed(e.id, "movie".equals(e.type) ? "movie" : "tv", e.meta != null ? e.meta.name : "", w));
        }
        Collections.sort(out, (a, b) -> Double.compare(b.weight, a.weight));
        return out.size() > MAX_SEEDS ? new ArrayList<>(out.subList(0, MAX_SEEDS)) : out;
    }

    private static String signature(List<Seed> seeds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seeds.size() && i < 6; i++) sb.append(i > 0 ? "," : "").append(seeds.get(i).imdb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ TMDB

    private static final Map<String, JSONObject> memo = Collections.synchronizedMap(new LinkedHashMap<String, JSONObject>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JSONObject> e) {
            return size() > 200;
        }
    });

    private static JSONObject get(String path) {
        String key = CastInfo.key();
        if (key == null) return null;
        String url = API + path + (path.contains("?") ? "&" : "?") + "api_key=" + key;
        JSONObject hit = memo.get(url);
        if (hit != null) return hit;
        try {
            JSONObject o = new JSONObject(Net.get(url));
            memo.put(url, o);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** Runs fn over the list, six at a time, keeping the order. */
    private static <T> List<JSONObject> parallel(List<T> list, final Fn<T> fn) {
        List<Future<JSONObject>> fs = new ArrayList<>();
        for (final T t : list) fs.add(WORK.submit(() -> fn.run(t)));
        List<JSONObject> out = new ArrayList<>();
        for (Future<JSONObject> f : fs) {
            JSONObject r = null;
            try {
                r = f.get();
            } catch (Exception ignored) {
            }
            out.add(r);
        }
        return out;
    }

    private interface Fn<T> {
        JSONObject run(T t);
    }

    // ------------------------------------------------------------------ embeddings

    static Map<String, Double> features(JSONObject d, String kind) {
        Map<String, Double> f = new HashMap<>();
        JSONArray g = d.optJSONArray("genres");
        if (g != null) for (int i = 0; i < g.length(); i++) add(f, "g" + g.optJSONObject(i).optInt("id"), 1);
        JSONObject kw = d.optJSONObject("keywords");
        JSONArray k = kw == null ? null : kw.has("keywords") ? kw.optJSONArray("keywords") : kw.optJSONArray("results");
        if (k != null) for (int i = 0; i < k.length() && i < 20; i++) add(f, "k" + k.optJSONObject(i).optInt("id"), 1.2);
        JSONObject cr = d.optJSONObject("credits");
        JSONArray cast = cr != null ? cr.optJSONArray("cast") : null;
        if (cast != null) for (int i = 0; i < cast.length() && i < 6; i++) add(f, "c" + cast.optJSONObject(i).optInt("id"), 0.5);
        JSONArray crew = cr != null ? cr.optJSONArray("crew") : null;
        if (crew != null) {
            int n = 0;
            for (int i = 0; i < crew.length() && n < 2; i++) {
                JSONObject c = crew.optJSONObject(i);
                if (c != null && "Director".equals(c.optString("job"))) {
                    add(f, "d" + c.optInt("id"), 0.9);
                    n++;
                }
            }
        }
        JSONArray by = d.optJSONArray("created_by");
        if (by != null) for (int i = 0; i < by.length() && i < 2; i++) add(f, "d" + by.optJSONObject(i).optInt("id"), 0.9);
        String lang = d.optString("original_language", "");
        if (!lang.isEmpty()) add(f, "l" + lang, 0.4);
        String date = d.optString("release_date", "");
        if (date.isEmpty()) date = d.optString("first_air_date", "");
        try {
            int year = Integer.parseInt(date.substring(0, 4));
            if (year > 1800) add(f, "y" + (year / 10), 0.3);
        } catch (Exception ignored) {
        }
        add(f, "t" + kind, 0.3);
        int words = 0;
        for (String w : d.optString("overview", "").toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() < 4 || STOP.contains(w) || words >= 40 || f.containsKey("w" + w)) continue;
            add(f, "w" + w, 0.35);
            words++;
        }
        return f;
    }

    private static void add(Map<String, Double> f, String k, double w) {
        Double was = f.get(k);
        f.put(k, was == null ? w : Math.max(was, w));
    }

    static double[] embed(Map<String, Double> f, Map<String, Integer> df, int n) {
        double[] v = new double[DIM];
        for (Map.Entry<String, Double> e : f.entrySet()) {
            long h = fnv(e.getKey());
            Integer c = df.get(e.getKey());
            double idf = Math.log(1 + n / (double) (c == null ? 1 : c));
            v[(int) (h % DIM)] += ((h & 0x80000000L) != 0 ? -1 : 1) * e.getValue() * idf;
        }
        normalize(v);
        return v;
    }

    private static void normalize(double[] v) {
        double s = 0;
        for (double x : v) s += x * x;
        s = Math.sqrt(s);
        if (s > 0) for (int i = 0; i < v.length; i++) v[i] /= s;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < DIM; i++) s += a[i] * b[i];
        return s;
    }

    // ------------------------------------------------------------------ building

    private static final class Cand {
        final String key, kind;
        final int id;
        double cf, bestCf;
        Seed best;
        final Set<String> seeds = new HashSet<>();
        boolean trending;
        JSONObject d;
        String imdb;
        double sim, score;

        Cand(String key, String kind, int id) {
            this.key = key;
            this.kind = kind;
            this.id = id;
        }
    }

    static String keyFor() {
        return Store.perProfile(PREF);
    }

    /** The saved picks (instant), or null. */
    static List<PickRow> cached(SharedPreferences prefs) {
        try {
            JSONObject o = new JSONObject(prefs.getString(keyFor(), "{}"));
            JSONArray rows = o.optJSONArray("rows");
            if (rows == null) return null;
            List<PickRow> out = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                List<Store.Meta> items = new ArrayList<>();
                JSONArray a = r.optJSONArray("items");
                for (int j = 0; a != null && j < a.length(); j++) {
                    Store.Meta m = Store.Meta.parse(a.optJSONObject(j));
                    m.reason = a.optJSONObject(j).optString("reason", "");
                    items.add(m);
                }
                out.add(new PickRow(r.optString("id"), r.optString("title"), items));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** A new day, or something new watched (and the last build is over half an hour old). */
    static boolean needsBuild(SharedPreferences prefs, Store store) {
        try {
            JSONObject o = new JSONObject(prefs.getString(keyFor(), "{}"));
            if (!o.has("rows") || !today().equals(o.optString("day"))) return true;
            String sig = signature(seeds(store.libraryEntries(), new HashSet<String>()));
            return !sig.equals(o.optString("sig")) && System.currentTimeMillis() - o.optLong("builtAt") > 30 * 60_000L;
        } catch (Exception e) {
            return true;
        }
    }

    /** Builds new picks in the background; done() runs on the main thread. */
    static void build(final SharedPreferences prefs, final Store store, final Done done) {
        if (CastInfo.key() == null) {
            done.done(State.NO_KEY, null);
            return;
        }
        if (building) return;
        building = true;
        final List<Store.LibraryEntry> lib = store.libraryEntries();
        final String profileKey = keyFor();
        Net.IO.execute(() -> {
            State state;
            List<PickRow> rows = null;
            try {
                JSONObject saved = new JSONObject();
                rows = make(lib, saved);
                if (rows == null) state = State.NO_HISTORY;
                else if (rows.isEmpty()) state = State.FAILED;
                else {
                    state = State.OK;
                    prefs.edit().putString(profileKey, saved.toString()).apply();
                }
            } catch (Exception e) {
                Log.w(TvView.TAG, "for you: build failed (" + e.getClass().getSimpleName() + ")");
                state = State.FAILED;
            }
            final State s = state;
            final List<PickRow> r = rows;
            main.post(() -> {
                building = false;
                done.done(s, r);
            });
        });
    }

    /** null = nothing watched yet; empty = TMDB couldn't be reached. Fills `saved` with what to keep. */
    private static List<PickRow> make(List<Store.LibraryEntry> lib, JSONObject saved) throws Exception {
        final Set<String> seen = new HashSet<>();
        List<Seed> seeds = seeds(lib, seen);
        if (seeds.isEmpty()) return null;
        final String day = today();

        // Seeds: details with keywords, credits and both candidate lists in one call each.
        List<JSONObject> seedDetails = parallel(seeds, s -> {
            JSONObject f = get("/find/" + s.imdb + "?external_source=imdb_id");
            if (f == null) return null;
            JSONObject movie = first(f.optJSONArray("movie_results")), tv = first(f.optJSONArray("tv_results"));
            JSONObject pick = "tv".equals(s.kind) ? (tv != null ? tv : movie) : (movie != null ? movie : tv);
            if (pick == null) return null;
            String kind = pick == tv ? "tv" : "movie";
            JSONObject d = get("/" + kind + "/" + pick.optInt("id") + "?append_to_response=keywords,credits,recommendations,similar");
            if (d != null) {
                try {
                    d.put("_kind", kind);
                } catch (Exception ignored) {
                }
            }
            return d;
        });
        List<Seed> found = new ArrayList<>();
        List<JSONObject> foundD = new ArrayList<>();
        Set<String> seedKeys = new HashSet<>();
        for (int i = 0; i < seeds.size(); i++) {
            JSONObject d = seedDetails.get(i);
            if (d == null) continue;
            Seed s = seeds.get(i);
            s.kind = d.optString("_kind");
            found.add(s);
            foundD.add(d);
            seedKeys.add(s.kind + ":" + d.optInt("id"));
        }
        if (found.isEmpty()) return new ArrayList<>();

        Map<String, Cand> cands = new LinkedHashMap<>();
        for (int i = 0; i < found.size(); i++) {
            Seed s = found.get(i);
            JSONObject d = foundD.get(i);
            for (int l = 0; l < 2; l++) {
                JSONObject holder = d.optJSONObject(l == 0 ? "recommendations" : "similar");
                JSONArray list = holder != null ? holder.optJSONArray("results") : null;
                double factor = l == 0 ? 1 : 0.6;
                for (int rank = 0; list != null && rank < list.length() && rank < 20; rank++) {
                    JSONObject r = list.optJSONObject(rank);
                    if (r == null || !r.has("id")) continue;
                    String key = s.kind + ":" + r.optInt("id");
                    if (seedKeys.contains(key)) continue;
                    Cand c = cands.get(key);
                    if (c == null) cands.put(key, c = new Cand(key, s.kind, r.optInt("id")));
                    double add = s.weight * factor * (1 - rank / 25.0);
                    c.cf += add;
                    c.seeds.add(s.imdb);
                    if (add > c.bestCf) {
                        c.bestCf = add;
                        c.best = s;
                    }
                }
            }
        }
        JSONObject tr = get("/trending/all/week");
        JSONArray trending = tr != null ? tr.optJSONArray("results") : null;
        List<Cand> wildTrend = new ArrayList<>();
        for (int i = 0; trending != null && i < trending.length() && i < 20; i++) {
            JSONObject r = trending.optJSONObject(i);
            String mt = r != null ? r.optString("media_type") : "";
            if (!"tv".equals(mt) && !"movie".equals(mt)) continue;
            String key = mt + ":" + r.optInt("id");
            if (seedKeys.contains(key) || cands.containsKey(key)) continue;
            Cand c = new Cand(key, mt, r.optInt("id"));
            c.trending = true;
            cands.put(key, c);
            wildTrend.add(c);
        }
        List<Cand> ranked = new ArrayList<>();
        for (Cand c : cands.values()) if (c.cf > 0) ranked.add(c);
        Collections.sort(ranked, (a, b) -> Double.compare(b.cf, a.cf));
        if (ranked.size() > POOL) ranked = new ArrayList<>(ranked.subList(0, POOL));
        Collections.sort(wildTrend, (a, b) -> Double.compare(daily(day, a.key), daily(day, b.key)));
        if (wildTrend.size() > 6) wildTrend = new ArrayList<>(wildTrend.subList(0, 6));
        final List<Cand> toFetch = new ArrayList<>(ranked);
        toFetch.addAll(wildTrend);
        List<JSONObject> details = parallel(toFetch, c -> get("/" + c.kind + "/" + c.id + "?append_to_response=keywords,credits,external_ids"));

        // Embeddings.
        Map<String, Integer> df = new HashMap<>();
        List<Map<String, Double>> seedF = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < found.size(); i++) {
            Map<String, Double> f = features(foundD.get(i), found.get(i).kind);
            for (String k : f.keySet()) df.put(k, df.containsKey(k) ? df.get(k) + 1 : 1);
            seedF.add(f);
            n++;
        }
        List<Map<String, Double>> candF = new ArrayList<>();
        for (int i = 0; i < toFetch.size(); i++) {
            JSONObject d = details.get(i);
            Map<String, Double> f = d != null ? features(d, toFetch.get(i).kind) : null;
            if (f != null) {
                for (String k : f.keySet()) df.put(k, df.containsKey(k) ? df.get(k) + 1 : 1);
                n++;
            }
            candF.add(f);
        }
        double[] taste = new double[DIM];
        for (int i = 0; i < found.size(); i++) {
            double[] v = embed(seedF.get(i), df, n);
            for (int j = 0; j < DIM; j++) taste[j] += found.get(i).weight * v[j];
        }
        normalize(taste);

        List<Cand> scored = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < toFetch.size(); i++) {
            JSONObject d = details.get(i);
            if (d == null || d.optBoolean("adult")) continue;
            JSONObject ext = d.optJSONObject("external_ids");
            String imdb = ext != null ? ext.optString("imdb_id", "") : d.optString("imdb_id", "");
            if (!imdb.startsWith("tt") || seen.contains(imdb) || !used.add(imdb)) continue;
            Cand c = toFetch.get(i);
            c.d = d;
            c.imdb = imdb;
            c.sim = candF.get(i) != null ? dot(taste, embed(candF.get(i), df, n)) : 0;
            scored.add(c);
        }
        double maxCf = 1e-6, maxSim = 1e-6;
        for (Cand c : scored) {
            maxCf = Math.max(maxCf, c.cf);
            maxSim = Math.max(maxSim, c.sim);
        }
        for (Cand c : scored) {
            double quality = c.d.optInt("vote_count") >= 50 ? c.d.optDouble("vote_average", 5) / 10 : 0.5;
            double base = 0.45 * (c.cf / maxCf) + 0.45 * (Math.max(0, c.sim) / maxSim) + 0.1 * quality;
            c.score = base * (0.85 + 0.3 * daily(day, c.imdb));
        }

        List<Cand> recs = new ArrayList<>();
        for (Cand c : scored) if (!c.trending) recs.add(c);
        Collections.sort(recs, (a, b) -> Double.compare(b.score, a.score));

        // Wildcards: less like your usual (lower half by closeness) but still recommended, plus trending titles in your top genres.
        List<Double> sims = new ArrayList<>();
        for (Cand c : recs) sims.add(c.sim);
        Collections.sort(sims);
        double median = sims.isEmpty() ? 0 : sims.get(sims.size() / 2);
        Map<Integer, Double> gw = new HashMap<>();
        for (int i = 0; i < found.size(); i++) {
            JSONArray g = foundD.get(i).optJSONArray("genres");
            for (int j = 0; g != null && j < g.length(); j++) {
                int id = g.optJSONObject(j).optInt("id");
                gw.put(id, (gw.containsKey(id) ? gw.get(id) : 0) + found.get(i).weight);
            }
        }
        List<Map.Entry<Integer, Double>> ge = new ArrayList<>(gw.entrySet());
        Collections.sort(ge, (a, b) -> Double.compare(b.getValue(), a.getValue()));
        Set<Integer> topGenres = new HashSet<>();
        for (int i = 0; i < ge.size() && i < 6; i++) topGenres.add(ge.get(i).getKey());
        List<Cand> wildRec = new ArrayList<>();
        for (int i = 20; i < recs.size(); i++) if (recs.get(i).sim < median) wildRec.add(recs.get(i));
        List<Cand> wildTr = new ArrayList<>();
        for (Cand c : scored) {
            if (!c.trending) continue;
            JSONArray g = c.d.optJSONArray("genres");
            boolean hit = false;
            for (int j = 0; g != null && j < g.length(); j++) if (topGenres.contains(g.optJSONObject(j).optInt("id"))) hit = true;
            if (hit) wildTr.add(c);
        }
        Collections.sort(wildRec, (a, b) -> Double.compare(daily(day, "w" + a.imdb), daily(day, "w" + b.imdb)));
        Collections.sort(wildTr, (a, b) -> Double.compare(daily(day, "w" + a.imdb), daily(day, "w" + b.imdb)));
        List<Cand> wild = new ArrayList<>();
        for (int i = 0; wild.size() < 12 && (i < wildRec.size() || i < wildTr.size()); i++) {
            if (i < wildRec.size()) wild.add(wildRec.get(i));
            if (i < wildTr.size()) wild.add(wildTr.get(i));
        }

        Set<String> shown = new HashSet<>();
        JSONArray savedRows = new JSONArray();
        List<PickRow> rows = new ArrayList<>();

        // Top picks: the best 20 of the day, with two wildcards slipped in (6th and 13th).
        List<Cand> top = new ArrayList<>(recs.subList(0, Math.min(20, recs.size())));
        List<Cand> slip = wild.subList(0, Math.min(2, wild.size()));
        if (slip.size() > 0 && top.size() >= 6) top.add(5, slip.get(0));
        if (slip.size() > 1 && top.size() >= 13) top.add(12, slip.get(1));
        if (top.size() > 20) top = top.subList(0, 20);
        List<String> reasons = new ArrayList<>();
        for (Cand c : top) reasons.add(slip.contains(c) ? wildReason(c) : reasonOf(c));
        addRow(rows, savedRows, shown, "top", "Top picks for you", top, reasons);

        for (int i = 0; i < found.size() && i < 3; i++) {
            Seed s = found.get(i);
            List<Cand> items = new ArrayList<>();
            for (Cand c : recs) if (items.size() < 15 && !shown.contains(c.imdb) && c.seeds.contains(s.imdb)) items.add(c);
            List<String> rs = new ArrayList<>();
            for (int j = 0; j < items.size(); j++) rs.add("Because you watched " + s.name);
            addRow(rows, savedRows, shown, "because-" + s.imdb, "Because you watched " + s.name, items, rs);
        }

        List<Cand> different = new ArrayList<>();
        for (int i = 2; i < wild.size() && different.size() < 10; i++) if (!shown.contains(wild.get(i).imdb)) different.add(wild.get(i));
        List<String> dr = new ArrayList<>();
        for (Cand c : different) dr.add(wildReason(c));
        addRow(rows, savedRows, shown, "different", "Something different", different, dr);

        List<Cand> more = new ArrayList<>();
        for (Cand c : recs) if (more.size() < 20 && !shown.contains(c.imdb)) more.add(c);
        List<String> mr = new ArrayList<>();
        for (Cand c : more) mr.add(reasonOf(c));
        addRow(rows, savedRows, shown, "more", "More you might like", more, mr);

        saved.put("day", day);
        saved.put("sig", signature(seeds));
        saved.put("builtAt", System.currentTimeMillis());
        saved.put("rows", savedRows);
        return rows;
    }

    private static String reasonOf(Cand c) {
        return c.best != null ? "Because you watched " + c.best.name : wildReason(c);
    }

    private static String wildReason(Cand c) {
        return c.trending ? "Trending this week" : "Something a bit different";
    }

    private static void addRow(List<PickRow> rows, JSONArray saved, Set<String> shown, String id, String title, List<Cand> list, List<String> reasons) throws Exception {
        if (list.size() < 4) return;
        List<Store.Meta> items = new ArrayList<>();
        JSONArray a = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            Cand c = list.get(i);
            shown.add(c.imdb);
            JSONObject o = toMeta(c.d, c.kind, c.imdb, reasons.get(i));
            a.put(o);
            Store.Meta m = Store.Meta.parse(o);
            m.reason = reasons.get(i);
            items.add(m);
        }
        rows.add(new PickRow(id, title, items));
        saved.put(new JSONObject().put("id", id).put("title", title).put("items", a));
    }

    /** A TMDB title as the addon-style JSON Codio's pages read (IMDb id, so the addons know it). */
    private static JSONObject toMeta(JSONObject d, String kind, String imdb, String reason) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", imdb);
        o.put("type", "tv".equals(kind) ? "series" : "movie");
        o.put("name", d.optString("title", d.optString("name", "")));
        String p = d.optString("poster_path", "");
        if (p.startsWith("/")) o.put("poster", "https://image.tmdb.org/t/p/w342" + p);
        String b = d.optString("backdrop_path", "");
        if (b.startsWith("/")) o.put("background", "https://image.tmdb.org/t/p/w1280" + b);
        String ov = d.optString("overview", "");
        if (!ov.isEmpty()) o.put("description", ov);
        String date = d.optString("release_date", d.optString("first_air_date", ""));
        if (date.length() >= 4) o.put("releaseInfo", date.substring(0, 4));
        if (d.optInt("vote_count") >= 20) o.put("imdbRating", String.format(Locale.US, "%.1f", d.optDouble("vote_average", 0)));
        JSONArray g = d.optJSONArray("genres"), names = new JSONArray();
        for (int i = 0; g != null && i < g.length() && i < 3; i++) names.put(g.optJSONObject(i).optString("name"));
        o.put("genres", names);
        o.put("reason", reason);
        return o;
    }

    private static JSONObject first(JSONArray a) {
        return a != null && a.length() > 0 ? a.optJSONObject(0) : null;
    }

    private ForYou() {
    }
}
