package com.codio.tv.ui;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the native screens show: the viewer's addons (read from the web UI's saved profile), their catalogs, item
 * details and Continue Watching (from the saved library). Everything here is read-only; the web UI stays the owner.
 */
public final class Store {
    private static final String TAG = "CodioStore";
    public static final String CINEMETA = "https://v3-cinemeta.strem.io/manifest.json";

    public interface Callback<T> {
        void done(T result, Exception error);
    }

    // ------------------------------------------------------------------ models

    public static final class Addon {
        public final String transportUrl, base, name;
        final JSONObject manifest;

        Addon(String transportUrl, JSONObject manifest) {
            this.transportUrl = transportUrl;
            this.base = transportUrl.replaceFirst("/manifest\\.json$", "");
            this.manifest = manifest;
            this.name = manifest.optString("name", "Addon");
        }

        /** Does this addon answer resource (catalog/meta/stream) for this type and id? */
        boolean supports(String resource, String type, String id) {
            JSONArray res = manifest.optJSONArray("resources");
            if (res == null) return false;
            for (int i = 0; i < res.length(); i++) {
                Object r = res.opt(i);
                JSONArray types = manifest.optJSONArray("types"), prefixes = manifest.optJSONArray("idPrefixes");
                if (r instanceof String) {
                    if (!resource.equals(r)) continue;
                } else if (r instanceof JSONObject) {
                    JSONObject o = (JSONObject) r;
                    if (!resource.equals(o.optString("name"))) continue;
                    if (o.has("types")) types = o.optJSONArray("types");
                    if (o.has("idPrefixes")) prefixes = o.optJSONArray("idPrefixes");
                } else continue;
                if (types != null && type != null && !contains(types, type)) continue;
                if (prefixes != null && id != null && prefixes.length() > 0) {
                    boolean ok = false;
                    for (int j = 0; j < prefixes.length() && !ok; j++) ok = id.startsWith(prefixes.optString(j));
                    if (!ok) continue;
                }
                return true;
            }
            return false;
        }
    }

    public static final class Catalog {
        public final Addon addon;
        public final String type, id, name;
        public final boolean search, skip, genreRequired, otherRequired;
        public final List<String> genres = new ArrayList<>();

        Catalog(Addon addon, JSONObject c) {
            this.addon = addon;
            this.type = c.optString("type");
            this.id = c.optString("id");
            String n0 = str(c, "name");
            this.name = n0 != null ? n0 : addon.name;
            boolean s = false, k = false, gReq = false, oReq = false;
            JSONArray extra = c.optJSONArray("extra");
            if (extra != null) {
                for (int i = 0; i < extra.length(); i++) {
                    JSONObject e = extra.optJSONObject(i);
                    if (e == null) continue;
                    String n = e.optString("name");
                    boolean req = e.optBoolean("isRequired", false);
                    if ("search".equals(n)) s = true;
                    else if ("skip".equals(n)) k = true;
                    else if ("genre".equals(n)) {
                        gReq |= req;
                        JSONArray opts = e.optJSONArray("options");
                        if (opts != null) for (int j = 0; j < opts.length(); j++) genres.add(opts.optString(j));
                    }
                    if (req && !"genre".equals(n)) oReq = true;
                }
            }
            JSONArray sup = c.optJSONArray("extraSupported"), reqd = c.optJSONArray("extraRequired");
            if (sup != null) {
                s |= contains(sup, "search");
                k |= contains(sup, "skip");
            }
            if (reqd != null) {
                for (int i = 0; i < reqd.length(); i++) {
                    if ("genre".equals(reqd.optString(i))) gReq = true;
                    else oReq = true;
                }
            }
            search = s;
            skip = k;
            genreRequired = gReq;
            otherRequired = oReq;
        }

        /** Browsable without typing anything (home rows, Discover). */
        public boolean browsable() {
            return !otherRequired && (!genreRequired || !genres.isEmpty());
        }

        public String url(String genre, int skipCount, String query) {
            StringBuilder extra = new StringBuilder();
            if (query != null) appendExtra(extra, "search", query);
            if (genre == null && genreRequired && !genres.isEmpty()) genre = genres.get(0);
            if (genre != null) appendExtra(extra, "genre", genre);
            if (skipCount > 0) appendExtra(extra, "skip", String.valueOf(skipCount));
            return addon.base + "/catalog/" + enc(type) + "/" + enc(id) + (extra.length() > 0 ? "/" + extra : "") + ".json";
        }

        public String typeLabel() {
            return Store.typeLabel(type);
        }
    }

    public static final class Video {
        public String id, title, thumbnail, overview, released;
        public int season, episode;
    }

    public static final class Meta {
        public String id, type, name, poster, background, logo, description, releaseInfo, runtime, imdbRating;
        public String posterShape;
        public final List<String> genres = new ArrayList<>();
        public final List<String> cast = new ArrayList<>();
        public final List<Video> videos = new ArrayList<>();
        public boolean full;
        /** Continue Watching. */
        public float progress;
        public String lastVideoId;
        /** The addon's own JSON for this item (for Add to library). */
        public JSONObject raw;

        static Meta parse(JSONObject o) {
            Meta m = new Meta();
            m.raw = o;
            m.id = o.optString("id", o.optString("_id", ""));
            m.type = o.optString("type", "movie");
            m.name = o.optString("name", "");
            m.poster = str(o, "poster");
            m.background = str(o, "background");
            m.logo = str(o, "logo");
            m.description = str(o, "description");
            m.releaseInfo = str(o, "releaseInfo");
            if (m.releaseInfo == null) m.releaseInfo = str(o, "year");
            m.runtime = str(o, "runtime");
            m.imdbRating = str(o, "imdbRating");
            m.posterShape = o.optString("posterShape", "poster");
            JSONArray g = o.optJSONArray("genres");
            if (g == null) g = o.optJSONArray("genre");
            if (g != null) for (int i = 0; i < g.length() && i < 4; i++) m.genres.add(g.optString(i));
            JSONArray c = o.optJSONArray("cast");
            if (c != null) for (int i = 0; i < c.length() && i < 5; i++) m.cast.add(c.optString(i));
            JSONArray v = o.optJSONArray("videos");
            if (v != null) {
                for (int i = 0; i < v.length(); i++) {
                    JSONObject e = v.optJSONObject(i);
                    if (e == null) continue;
                    Video x = new Video();
                    x.id = e.optString("id");
                    x.title = e.optString("title", e.optString("name", ""));
                    x.thumbnail = str(e, "thumbnail");
                    x.overview = str(e, "overview");
                    if (x.overview == null) x.overview = str(e, "description");
                    x.released = str(e, "released");
                    x.season = e.optInt("season", 0);
                    x.episode = e.optInt("episode", e.optInt("number", 0));
                    m.videos.add(x);
                }
                Collections.sort(m.videos, new Comparator<Video>() {
                    @Override
                    public int compare(Video a, Video b) {
                        if (a.season != b.season) return a.season - b.season;
                        return a.episode - b.episode;
                    }
                });
            }
            return m;
        }

        /** "2003 · 3h 21min · ★ 8.9" */
        public String metaLine() {
            StringBuilder sb = new StringBuilder();
            if (releaseInfo != null) sb.append(releaseInfo);
            if (runtime != null) sb.append(sb.length() > 0 ? "  ·  " : "").append(runtime);
            if (imdbRating != null && !imdbRating.isEmpty()) sb.append(sb.length() > 0 ? "  ·  " : "").append("★ ").append(imdbRating);
            return sb.toString();
        }

        public String genreLine() {
            StringBuilder sb = new StringBuilder();
            for (String g : genres) sb.append(sb.length() > 0 ? "  ·  " : "").append(g);
            return sb.toString();
        }

        /** Copy in details fetched later, keeping what the catalog already had. */
        void merge(Meta full) {
            if (full.raw != null) raw = full.raw;
            if (full.name != null && !full.name.isEmpty()) name = full.name;
            if (full.poster != null) poster = full.poster;
            if (full.background != null) background = full.background;
            if (full.logo != null) logo = full.logo;
            if (full.description != null) description = full.description;
            if (full.releaseInfo != null) releaseInfo = full.releaseInfo;
            if (full.runtime != null) runtime = full.runtime;
            if (full.imdbRating != null) imdbRating = full.imdbRating;
            if (!full.genres.isEmpty()) {
                genres.clear();
                genres.addAll(full.genres);
            }
            if (!full.cast.isEmpty()) {
                cast.clear();
                cast.addAll(full.cast);
            }
            videos.clear();
            videos.addAll(full.videos);
            this.full = true;
        }
    }

    public static final class LibraryEntry {
        public String id, type, videoId;
        public double offset, duration;
        public String lastWatched, mtime;
        public boolean removed, temp;
        /** Name/poster as saved in the library. */
        public Meta meta;
    }

    /** One source (stream) from the web page's list. */
    public static final class Source {
        public String addon, name, description, href, url, quality;
        public boolean cached;
        public double mbps = -1;
        /** Position in Smart auto-play's ranking (0 = what Play would pick), -1 when it isn't a candidate. */
        public int rank = -1;
    }

    /** The sources for one movie/episode, auto-play's picks first. key = type/id/videoId. */
    public static final class Sources {
        public String key;
        public boolean loading;
        public final List<Source> list = new ArrayList<>();

        public Source best() {
            return !list.isEmpty() && list.get(0).rank == 0 ? list.get(0) : null;
        }

        static Sources parse(String json) throws Exception {
            JSONObject o = new JSONObject(json);
            Sources out = new Sources();
            out.key = o.optString("key");
            out.loading = o.optBoolean("loading");
            Map<String, Source> byHref = new HashMap<>();
            List<Source> rest = new ArrayList<>();
            JSONArray streams = o.optJSONArray("streams");
            if (streams != null) {
                for (int i = 0; i < streams.length(); i++) {
                    JSONObject x = streams.optJSONObject(i);
                    if (x == null || x.isNull("href")) continue;
                    Source src = new Source();
                    src.addon = str(x, "addon");
                    src.name = str(x, "name");
                    src.description = str(x, "description");
                    src.href = str(x, "href");
                    src.url = str(x, "url");
                    if (src.href == null) continue;
                    // Torrents need a streaming server, which Codio on Android doesn't have: never offered or played.
                    if (src.url == null || !(src.url.startsWith("http://") || src.url.startsWith("https://"))) continue;
                    byHref.put(src.href, src);
                    rest.add(src);
                }
            }
            List<Source> ranked = new ArrayList<>();
            JSONArray r = o.optJSONArray("ranked");
            if (r != null) {
                for (int i = 0; i < r.length(); i++) {
                    JSONObject c = r.optJSONObject(i);
                    if (c == null) continue;
                    Source src = byHref.get(c.optString("link"));
                    if (src == null || src.rank >= 0) continue;
                    src.rank = ranked.size();
                    src.quality = str(c, "quality");
                    src.cached = c.optBoolean("cached");
                    if (!c.isNull("mbps")) src.mbps = c.optDouble("mbps", -1);
                    ranked.add(src);
                }
            }
            out.list.addAll(ranked);
            for (Source src : rest) if (src.rank < 0) out.list.add(src);
            return out;
        }
    }

    public Sources sources;

    public void updateSources(final String json, final Callback<Sources> done) {
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Sources s = Sources.parse(json);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            sources = s;
                            done.done(s, null);
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "source list unreadable: " + e);
                }
            }
        });
    }

    /** Sources for this movie/episode, if the hidden page has loaded them. */
    public Sources sourcesFor(String type, String id, String videoId) {
        Sources s = sources;
        return s != null && s.key.equals(type + "/" + id + "/" + videoId) ? s : null;
    }

    // ------------------------------------------------------------------ state

    private final Handler main = new Handler(Looper.getMainLooper());
    private final File cacheFile;
    private final List<Addon> addons = new ArrayList<>();
    private final Map<String, LibraryEntry> library = new HashMap<>();
    private final List<Meta> continueWatching = new ArrayList<>();
    private final Map<String, Meta> metaCache = new HashMap<>();
    private int generation = 0;
    public boolean profileLoaded = false;
    /** Smart auto-play settings (the web UI's autoPlaySettings), edited by the native Settings screen. */
    public JSONObject autoPlay = new JSONObject();

    public Store(File cacheDir) {
        cacheFile = new File(cacheDir, "native-profile.json");
        // Last run's addons, so the home screen can start loading before the web UI is up.
        try {
            if (cacheFile.exists()) {
                byte[] b = new byte[(int) cacheFile.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(cacheFile);
                int off = 0, r;
                while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
                in.close();
                parseAddons(new JSONArray(new String(b, StandardCharsets.UTF_8)), addons);
            }
        } catch (Exception e) {
            Log.w(TAG, "cached profile unreadable", e);
        }
    }

    public int generation() {
        return generation;
    }

    public synchronized List<Addon> addons() {
        return new ArrayList<>(addons);
    }

    public boolean hasAddons() {
        return !addons.isEmpty();
    }

    /**
     * The saved profile and library from the web UI's storage (JSON strings, null when missing). Parsed off the UI
     * thread (the library is easily 100 KB); done(true) when the addon list changed.
     */
    public void updateFromWeb(final String profile, final String libraryRecent, final String libraryAll, final Callback<Boolean> done) {
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                final List<Addon> newAddons = new ArrayList<>();
                JSONArray list = null;
                try {
                    if (profile != null) {
                        final JSONObject p = new JSONObject(profile);
                        list = p.optJSONArray("addons");
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                profileJson = p;
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.w(TAG, "profile unreadable", e);
                }
                if (list != null) parseAddons(list, newAddons);
                final Map<String, LibraryEntry> lib = new HashMap<>();
                final Map<String, Meta> cw = new HashMap<>();
                final Map<String, String> order = new HashMap<>();
                readLibrary(libraryAll, lib, cw, order);
                readLibrary(libraryRecent, lib, cw, order); // newer, wins
                final List<Meta> cwList = sortContinueWatching(cw, order);
                final JSONArray saved = list;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        boolean changed = false;
                        if (saved != null) {
                            String before = signature(addons);
                            changed = !before.equals(signature(newAddons));
                            if (changed) {
                                synchronized (Store.this) {
                                    addons.clear();
                                    addons.addAll(newAddons);
                                }
                                generation++;
                                writeCache(saved);
                            }
                        }
                        profileLoaded = true;
                        library.clear();
                        library.putAll(lib);
                        continueWatching.clear();
                        continueWatching.addAll(cwList);
                        done.done(changed, null);
                    }
                });
            }
        });
    }

    private void writeCache(final JSONArray list) {
        if (list.length() == 0) return;
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    FileOutputStream out = new FileOutputStream(cacheFile);
                    out.write(list.toString().getBytes(StandardCharsets.UTF_8));
                    out.close();
                } catch (Exception e) {
                    Log.w(TAG, "profile cache not saved", e);
                }
            }
        });
    }

    /** A fresh install has no saved profile yet: use Codio's default catalog addon (Cinemeta) until there is one. */
    public void ensureDefaults(final Runnable changed) {
        if (hasAddons()) return;
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject manifest = new JSONObject(Net.get(CINEMETA));
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (hasAddons()) return;
                            synchronized (Store.this) {
                                addons.add(new Addon(CINEMETA, manifest));
                            }
                            generation++;
                            changed.run();
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "default addon unavailable: " + e);
                }
            }
        });
    }

    private static String signature(List<Addon> list) {
        StringBuilder sb = new StringBuilder();
        for (Addon a : list) sb.append(a.transportUrl).append('|').append(a.manifest.optJSONArray("catalogs")).append('\n');
        return sb.toString();
    }

    private static void parseAddons(JSONArray list, List<Addon> addons) {
        for (int i = 0; i < list.length(); i++) {
            JSONObject d = list.optJSONObject(i);
            if (d == null) continue;
            String url = d.optString("transportUrl", "");
            JSONObject manifest = d.optJSONObject("manifest");
            if (url.isEmpty() || manifest == null) continue;
            addons.add(new Addon(url, manifest));
        }
    }

    private static void readLibrary(String json, Map<String, LibraryEntry> library, Map<String, Meta> pendingCw, Map<String, String> cwOrder) {
        if (json == null) return;
        try {
            JSONObject bucket = new JSONObject(json);
            JSONObject items = bucket.optJSONObject("items");
            if (items == null) return;
            JSONArray names = items.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                JSONObject it = items.optJSONObject(names.optString(i));
                if (it == null) continue;
                LibraryEntry e = new LibraryEntry();
                e.id = it.optString("_id");
                e.type = it.optString("type");
                e.removed = it.optBoolean("removed", false);
                JSONObject st = it.optJSONObject("state");
                if (st != null) {
                    e.offset = st.optDouble("timeOffset", 0);
                    e.duration = st.optDouble("duration", 0);
                    e.videoId = st.isNull("video_id") ? null : st.optString("video_id", null);
                    e.lastWatched = st.isNull("lastWatched") ? null : st.optString("lastWatched", null);
                }
                e.temp = it.optBoolean("temp", false);
                e.mtime = it.optString("_mtime", "");
                e.meta = Meta.parse(it);
                e.meta.id = e.id;
                library.put(e.id, e);
                // Continue Watching uses the library's own name/poster.
                if (e.offset > 0 && (!e.removed || it.optBoolean("temp", false)) && !"other".equals(e.type)) {
                    Meta m = Meta.parse(it);
                    m.id = e.id;
                    m.progress = e.duration > 0 ? (float) Math.min(1, e.offset / e.duration) : 0f;
                    m.lastVideoId = e.videoId;
                    pendingCw.put(e.id, m);
                    cwOrder.put(e.id, e.lastWatched != null ? e.lastWatched : it.optString("_mtime", ""));
                } else {
                    pendingCw.remove(e.id);
                }
            }
        } catch (Exception ex) {
            Log.w(TAG, "library unreadable", ex);
        }
    }

    private static List<Meta> sortContinueWatching(Map<String, Meta> pendingCw, final Map<String, String> cwOrder) {
        List<Meta> list = new ArrayList<>(pendingCw.values());
        Collections.sort(list, new Comparator<Meta>() {
            @Override
            public int compare(Meta a, Meta b) {
                String x = cwOrder.get(a.id), y = cwOrder.get(b.id);
                return (y == null ? "" : y).compareTo(x == null ? "" : x);
            }
        });
        return new ArrayList<>(list.subList(0, Math.min(30, list.size())));
    }

    public List<Meta> continueWatching() {
        return new ArrayList<>(continueWatching);
    }

    public LibraryEntry libraryEntry(String id) {
        return library.get(id);
    }

    /** The saved profile (auth, addons with their flags, settings), or an empty object. */
    public JSONObject profileJson = new JSONObject();

    public boolean inLibrary(String id) {
        LibraryEntry e = library.get(id);
        return e != null && !e.removed && !e.temp;
    }

    /** Library items of a type (null = all), most recently watched or added first. */
    public List<Meta> libraryItems(String type) {
        List<LibraryEntry> list = new ArrayList<>();
        for (LibraryEntry e : library.values()) {
            if (e.removed || e.temp || "other".equals(e.type)) continue;
            if (type != null && !type.equals(e.type)) continue;
            list.add(e);
        }
        Collections.sort(list, new Comparator<LibraryEntry>() {
            @Override
            public int compare(LibraryEntry a, LibraryEntry b) {
                String x = a.lastWatched != null && a.lastWatched.compareTo(a.mtime) > 0 ? a.lastWatched : a.mtime;
                String y = b.lastWatched != null && b.lastWatched.compareTo(b.mtime) > 0 ? b.lastWatched : b.mtime;
                return y.compareTo(x);
            }
        });
        List<Meta> out = new ArrayList<>();
        for (LibraryEntry e : list) {
            Meta m = e.meta;
            m.progress = e.duration > 0 && e.offset > 0 ? (float) Math.min(1, e.offset / e.duration) : 0f;
            out.add(m);
        }
        return out;
    }

    /** The library preview the core expects for AddToLibrary. */
    public static JSONObject preview(Meta m) {
        JSONObject p = new JSONObject();
        try {
            if (m.raw != null) {
                String[] keep = {"poster", "background", "logo", "description", "releaseInfo", "runtime", "released", "links", "trailerStreams", "behaviorHints"};
                for (String k : keep) if (m.raw.has(k) && !m.raw.isNull(k)) p.put(k, m.raw.get(k));
            }
            p.put("id", m.id);
            p.put("type", m.type);
            p.put("name", m.name);
            p.put("posterShape", m.posterShape != null ? m.posterShape : "poster");
            if (!p.has("poster") && m.poster != null) p.put("poster", m.poster);
            if (!p.has("background") && m.background != null) p.put("background", m.background);
            if (!p.has("logo") && m.logo != null) p.put("logo", m.logo);
            if (!p.has("description") && m.description != null) p.put("description", m.description);
            if (!p.has("releaseInfo") && m.releaseInfo != null) p.put("releaseInfo", m.releaseInfo);
            if (!p.has("links")) p.put("links", new JSONArray());
            if (!p.has("behaviorHints")) p.put("behaviorHints", new JSONObject());
        } catch (Exception ignored) {
        }
        return p;
    }

    /** Installed addon descriptors (transportUrl, manifest, flags) as saved in the profile. */
    public JSONArray addonDescriptors() {
        JSONArray a = profileJson.optJSONArray("addons");
        return a != null ? a : new JSONArray();
    }

    public boolean isInstalled(String transportUrl) {
        JSONArray a = addonDescriptors();
        for (int i = 0; i < a.length(); i++) {
            JSONObject d = a.optJSONObject(i);
            if (d != null && transportUrl.equals(d.optString("transportUrl"))) return true;
        }
        return false;
    }

    /** The profile's settings object (a copy, to change and send back whole with UpdateSettings). */
    public JSONObject settingsCopy() {
        JSONObject s = profileJson.optJSONObject("settings");
        try {
            return s != null ? new JSONObject(s.toString()) : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Optimistic local change after Add/Remove, until the web UI saves the library again. */
    public void markInLibrary(Meta m, boolean in) {
        LibraryEntry e = library.get(m.id);
        if (e == null) {
            e = new LibraryEntry();
            e.id = m.id;
            e.type = m.type;
            e.mtime = "9999";
            e.meta = m;
            library.put(m.id, e);
        }
        e.removed = !in;
        e.temp = false;
    }

    // ------------------------------------------------------------------ catalogs

    /** Catalogs for the home screen, in addon order (like the web UI's board). */
    public synchronized List<Catalog> homeCatalogs() {
        List<Catalog> out = new ArrayList<>();
        for (Addon a : addons) {
            JSONArray cats = a.manifest.optJSONArray("catalogs");
            if (cats == null) continue;
            for (int i = 0; i < cats.length(); i++) {
                JSONObject c = cats.optJSONObject(i);
                if (c == null) continue;
                Catalog cat = new Catalog(a, c);
                if (!cat.otherRequired && !cat.genreRequired) out.add(cat);
            }
        }
        return out;
    }

    public synchronized List<Catalog> discoverCatalogs(String type) {
        List<Catalog> out = new ArrayList<>();
        for (Addon a : addons) {
            JSONArray cats = a.manifest.optJSONArray("catalogs");
            if (cats == null) continue;
            for (int i = 0; i < cats.length(); i++) {
                JSONObject c = cats.optJSONObject(i);
                if (c == null || !type.equals(c.optString("type"))) continue;
                Catalog cat = new Catalog(a, c);
                if (cat.browsable()) out.add(cat);
            }
        }
        return out;
    }

    public synchronized List<Catalog> searchCatalogs() {
        List<Catalog> out = new ArrayList<>();
        for (Addon a : addons) {
            JSONArray cats = a.manifest.optJSONArray("catalogs");
            if (cats == null) continue;
            for (int i = 0; i < cats.length(); i++) {
                JSONObject c = cats.optJSONObject(i);
                if (c == null) continue;
                Catalog cat = new Catalog(a, c);
                if (cat.search && !cat.genreRequired) out.add(cat);
            }
        }
        // Movies before series, like the web UI.
        Collections.sort(out, new Comparator<Catalog>() {
            @Override
            public int compare(Catalog a, Catalog b) {
                return rank(a.type) - rank(b.type);
            }

            int rank(String t) {
                return "movie".equals(t) ? 0 : "series".equals(t) ? 1 : 2;
            }
        });
        return out;
    }

    public void loadCatalog(final Catalog cat, final String genre, final int skip, final String query, final Callback<List<Meta>> cb) {
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                List<Meta> out = new ArrayList<>();
                Exception err = null;
                try {
                    JSONObject r = new JSONObject(Net.get(cat.url(genre, skip, query)));
                    JSONArray metas = r.optJSONArray("metas");
                    if (metas != null) {
                        for (int i = 0; i < metas.length(); i++) {
                            JSONObject o = metas.optJSONObject(i);
                            if (o == null) continue;
                            Meta m = Meta.parse(o);
                            if (m.id.isEmpty()) continue;
                            if (o.optString("type", "").isEmpty()) m.type = cat.type;
                            out.add(m);
                        }
                    }
                } catch (Exception e) {
                    err = e;
                    Log.w(TAG, "catalog " + cat.addon.name + "/" + cat.id + " failed: " + e);
                }
                post(cb, out, err);
            }
        });
    }

    /** Full details (episodes, cast, runtime) from the first addon that has meta for this id. */
    public void loadMeta(final Meta m, final Callback<Meta> cb) {
        final String key = m.type + "/" + m.id;
        Meta cached = metaCache.get(key);
        if (cached != null) {
            m.merge(cached);
            cb.done(m, null);
            return;
        }
        final List<Addon> list = addons();
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                Exception err = null;
                Meta found = null;
                for (Addon a : list) {
                    if (!a.supports("meta", m.type, m.id)) continue;
                    try {
                        JSONObject r = new JSONObject(Net.get(a.base + "/meta/" + enc(m.type) + "/" + enc(m.id) + ".json"));
                        JSONObject o = r.optJSONObject("meta");
                        if (o != null) {
                            found = Meta.parse(o);
                            break;
                        }
                    } catch (Exception e) {
                        err = e;
                        Log.w(TAG, "meta from " + a.name + " failed: " + e);
                    }
                }
                final Meta f = found;
                final Exception e2 = found == null ? err : null;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (f != null) {
                            metaCache.put(key, f);
                            m.merge(f);
                        }
                        cb.done(f != null ? m : null, e2);
                    }
                });
            }
        });
    }

    private <T> void post(final Callback<T> cb, final T value, final Exception err) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.done(value, err);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    static boolean contains(JSONArray a, String v) {
        for (int i = 0; i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    static String str(JSONObject o, String k) {
        if (o.isNull(k)) return null;
        Object v = o.opt(k);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static void appendExtra(StringBuilder sb, String k, String v) {
        if (sb.length() > 0) sb.append('&');
        sb.append(enc(k)).append('=').append(enc(v));
    }

    public static String typeLabel(String type) {
        if ("movie".equals(type)) return "Movies";
        if ("series".equals(type)) return "Series";
        if ("channel".equals(type)) return "Channels";
        if ("tv".equals(type)) return "Live TV";
        return type.isEmpty() ? "" : Character.toUpperCase(type.charAt(0)) + type.substring(1);
    }
}
