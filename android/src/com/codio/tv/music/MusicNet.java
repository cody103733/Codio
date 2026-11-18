package com.codio.tv.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The network part of Music: calls to MusicBrainz, ListenBrainz and music addons, with Codio's User-Agent, one call a
 * second to each metadata server (MusicBrainz asks for that) and a small disk cache. The same rules as
 * windows/desktop/codio-music.js. Addon links (they can hold a personal token) are never logged or cached.
 */
public final class MusicNet {
    private MusicNet() {}

    static final String USER_AGENT = "Codio/1.9 ( https://github.com/cody103733/Codio )";
    static final long DAY = 24L * 3600 * 1000;

    private static Context app;
    private static File cacheDir;
    private static SharedPreferences prefs;
    public static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Background work for Music (searches, rows, addon calls). */
    public static final ExecutorService POOL = Executors.newFixedThreadPool(8, new ThreadFactory() {
        int n = 0;

        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r, "codio-music-" + (n++));
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    });

    public static synchronized void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        cacheDir = new File(app.getCacheDir(), "music-cache");
        cacheDir.mkdirs();
        prefs = app.getSharedPreferences("codio_music", Context.MODE_PRIVATE);
    }

    public static Context context() {
        return app;
    }

    public static SharedPreferences prefs() {
        return prefs;
    }

    public static final class Reply {
        public int status;
        public int retryAfter = -1;
        public byte[] body = new byte[0];

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /** The JSON body (a JSONObject or JSONArray), or null. */
        public Object json() {
            try {
                String t = text().trim();
                return t.length() == 0 ? null : new JSONTokener(t).nextValue();
            } catch (Exception e) {
                return null;
            }
        }

        public JSONObject object() {
            Object o = json();
            return o instanceof JSONObject ? (JSONObject) o : null;
        }
    }

    /** One request; follows up to 3 redirects. headers may be null. */
    public static Reply fetch(String url, String method, Map<String, String> headers, byte[] body, int timeoutMs) throws IOException {
        for (int hop = 0; ; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                c.getOutputStream().write(body);
            }
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null && hop < 3) {
                    url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                    method = "GET";
                    body = null;
                    continue;
                }
                Reply r = new Reply();
                r.status = code;
                String ra = c.getHeaderField("Retry-After");
                if (ra != null) {
                    try {
                        r.retryAfter = Integer.parseInt(ra.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                InputStream in = code / 100 == 2 ? c.getInputStream() : c.getErrorStream();
                if (in != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > 8 * 1024 * 1024) throw new IOException("Answer too large");
                    }
                    in.close();
                    r.body = out.toByteArray();
                }
                return r;
            } finally {
                c.disconnect();
            }
        }
    }

    public static Reply get(String url, int timeoutMs) throws IOException {
        return fetch(url, "GET", null, null, timeoutMs);
    }

    // Calls to one server start at least gap ms apart (MusicBrainz asks for no more than one a second); they may overlap.
    private static final Map<String, Long> NEXT = new HashMap<>();

    public static void spaced(String origin, long gapMs) {
        long wait;
        synchronized (NEXT) {
            long now = System.currentTimeMillis();
            Long next = NEXT.get(origin);
            long at = Math.max(now, next == null ? 0 : next);
            NEXT.put(origin, at + gapMs);
            wait = at - now;
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static final Object BG = new Object();

    /**
     * Background work (matching chart albums and artists): one at a time, and only once nothing else is waiting for that
     * server, so a page you open goes first.
     */
    public static void spacedBackground(String origin, long gapMs) {
        synchronized (BG) {
            for (;;) {
                long wait;
                synchronized (NEXT) {
                    Long next = NEXT.get(origin);
                    wait = next == null ? 0 : next - System.currentTimeMillis();
                }
                if (wait <= 0) break;
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    break;
                }
            }
            spaced(origin, gapMs);
        }
    }

    // ---- disk cache

    private static File cacheFile(String key, String ext) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return new File(cacheDir, sb + ext);
        } catch (Exception e) {
            return new File(cacheDir, Integer.toHexString(key.hashCode()) + ext);
        }
    }

    public static byte[] readCache(String key, long ttl) {
        try {
            File f = cacheFile(key, ".json");
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > ttl) return null;
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    public static void writeCache(String key, byte[] body) {
        try {
            FileOutputStream out = new FileOutputStream(cacheFile(key, ".json"));
            out.write(body);
            out.close();
        } catch (Exception ignored) {
            // the cache is a bonus
        }
    }

    /** Thrown when a metadata server is busy (503/429) or says no. */
    public static final class Busy extends IOException {
        public final int status;

        Busy(int status, String m) {
            super(m);
            this.status = status;
        }
    }

    /**
     * MusicBrainz or ListenBrainz: queued (one call a second per server), cached, JSON only. Returns the parsed body
     * (a JSONObject or JSONArray), or null for an empty answer (204).
     */
    public static Object metadata(String origin, String path, long ttl) throws IOException {
        return metadata(origin, path, ttl, false);
    }

    public static Object metadata(String origin, String path, long ttl, boolean background) throws IOException {
        String url = origin + path;
        byte[] cached = readCache(url, ttl);
        if (cached != null) {
            try {
                return new JSONTokener(new String(cached, StandardCharsets.UTF_8)).nextValue();
            } catch (Exception ignored) {
            }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            if (background) spacedBackground(origin, 1000);
            else spaced(origin, 1000);
            Reply r = get(url, 15000);
            if (r.status == 200) {
                writeCache(url, r.body);
                return r.json();
            }
            if (r.status == 204) return null;
            if ((r.status == 503 || r.status == 429) && attempt < 2) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                }
                continue;
            }
            throw new Busy(r.status, "The music database answered " + r.status);
        }
        throw new Busy(503, "The music database is busy");
    }

    public static JSONArray arr(Object o) {
        return o instanceof JSONArray ? (JSONArray) o : new JSONArray();
    }

    public static JSONObject obj(Object o) {
        return o instanceof JSONObject ? (JSONObject) o : new JSONObject();
    }

    // ---- settings

    public static String mbBase() {
        String s = prefs.getString("mbBase", "");
        return s == null ? "" : s.trim();
    }

    public static void setMbBase(String s) {
        prefs.edit().putString("mbBase", s == null ? "" : s.trim()).apply();
    }

    /** Your own Last.fm API key; with one, the Popular rows use Last.fm's chart instead of Apple's. */
    public static String lastfmKey() {
        String s = prefs.getString("lastfmKey", "");
        return s == null ? "" : s.trim();
    }

    public static void setLastfmKey(String s) {
        prefs.edit().putString("lastfmKey", s == null ? "" : s.trim()).apply();
    }

    /** True when you picked Last.fm's chart (Apple's, the closest to Billboard, is the default). */
    public static boolean chartLastfm() {
        return prefs.getBoolean("chartLastfm", false);
    }

    public static void setChartLastfm(boolean on) {
        prefs.edit().putBoolean("chartLastfm", on).apply();
    }

    /** The Last.fm key in use for the charts: only when you picked Last.fm. */
    public static String activeLastfmKey() {
        return chartLastfm() ? lastfmKey() : "";
    }

    /** Result callback, always delivered on the main thread. */
    public interface Done<T> {
        void done(T value, Exception error);
    }

    public interface Job<T> {
        T run() throws Exception;
    }

    public static <T> void async(final Job<T> job, final Done<T> done) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                T v = null;
                Exception err = null;
                try {
                    v = job.run();
                } catch (Exception e) {
                    err = e;
                }
                final T fv = v;
                final Exception fe = err;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        done.done(fv, fe);
                    }
                });
            }
        });
    }
}
