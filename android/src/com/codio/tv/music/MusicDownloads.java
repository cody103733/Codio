package com.codio.tv.music;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Environment;

import com.codio.tv.music.Model.Addon;
import com.codio.tv.music.Model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Songs kept on the phone to play offline (DESIGN.md "Music downloads", same rules as ios/Codio/Music/MusicDownloads.swift).
 * One song downloads at a time: its audio (found like playing finds it), its cover and its lyrics, into the app's own
 * Music folder. The list lives in files/music-downloads.json. Downloads follow the "Download over" setting (Wi-Fi only by
 * default) and pick up again the next time Codio opens. MusicPlayer plays a downloaded song from the file.
 */
public final class MusicDownloads {
    private MusicDownloads() {}

    public static final String QUEUED = "queued", WAITING = "waiting", DOWNLOADING = "downloading", DONE = "done", FAILED = "failed";

    public static final class Entry {
        public Track track;
        public String key, state = QUEUED, file, cover, lyrics, error;
        public long bytes, total, added;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("track", track.toJson()).put("key", key).put("state", state).put("bytes", bytes).put("total", total).put("added", added);
                if (file != null) o.put("file", file);
                if (cover != null) o.put("cover", cover);
                if (lyrics != null) o.put("lyrics", lyrics);
                if (error != null) o.put("error", error);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Entry fromJson(JSONObject o) {
            Track t = Track.fromJson(o == null ? null : o.optJSONObject("track"));
            if (t == null) return null;
            Entry e = new Entry();
            e.track = t;
            e.key = o.optString("key", t.key());
            e.state = o.optString("state", QUEUED);
            e.file = Model.str(o, "file");
            e.cover = Model.str(o, "cover");
            e.lyrics = Model.str(o, "lyrics");
            e.error = Model.str(o, "error");
            e.bytes = o.optLong("bytes", 0);
            e.total = o.optLong("total", 0);
            e.added = o.optLong("added", 0);
            return e;
        }

        public boolean done() {
            return DONE.equals(state) && file != null && new File(file).exists();
        }

        /** The track to play or show: with the saved cover when there is one. */
        public Track playable() {
            Track t = Track.fromJson(track.toJson());
            if (cover != null && new File(cover).exists()) t.artworkURL = cover;
            return t;
        }
    }

    private static final Object LOCK = new Object();
    private static final List<Entry> ENTRIES = new ArrayList<>();
    public static final List<Runnable> listeners = new ArrayList<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static Context app;
    private static File index, dir;
    private static boolean running;
    private static String wifiPrefsName = "codio";

    public static void init(Context c) {
        synchronized (LOCK) {
            if (app != null) return;
            app = c.getApplicationContext();
            MusicNet.init(app);
            index = new File(app.getFilesDir(), "music-downloads.json");
            File ext = app.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            dir = new File(ext != null ? ext : new File(app.getFilesDir(), "music"), "Codio");
            dir.mkdirs();
            try {
                if (index.exists()) {
                    JSONArray a = new JSONArray(read(index));
                    for (int i = 0; i < a.length(); i++) {
                        Entry e = Entry.fromJson(a.optJSONObject(i));
                        if (e == null) continue;
                        // a download cut off when Codio closed starts again
                        if (DOWNLOADING.equals(e.state) || WAITING.equals(e.state)) e.state = QUEUED;
                        if (DONE.equals(e.state) && (e.file == null || !new File(e.file).exists())) e.state = QUEUED;
                        ENTRIES.add(e);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        kick();
    }

    private static String read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int n = 0;
        while (n < b.length) {
            int k = in.read(b, n, b.length - n);
            if (k < 0) break;
            n += k;
        }
        in.close();
        return new String(b, 0, n, StandardCharsets.UTF_8);
    }

    private static void save() {
        JSONArray a = new JSONArray();
        synchronized (LOCK) {
            for (Entry e : ENTRIES) a.put(e.toJson());
        }
        try {
            File tmp = new File(index.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
            tmp.renameTo(index);
        } catch (Exception ignored) {
        }
    }

    private static void changed() {
        MusicNet.MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Runnable r : new ArrayList<>(listeners)) r.run();
            }
        });
    }

    // ---- what the screens and the player ask

    /** Every download, newest first. */
    public static List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        synchronized (LOCK) {
            for (int i = ENTRIES.size() - 1; i >= 0; i--) out.add(ENTRIES.get(i));
        }
        return out;
    }

    /** The finished downloads as tracks to play, newest first. */
    public static List<Track> doneTracks() {
        List<Track> out = new ArrayList<>();
        for (Entry e : all()) if (e.done()) out.add(e.playable());
        return out;
    }

    public static Entry find(Track t) {
        if (t == null || app == null) return null;
        String k = t.key();
        synchronized (LOCK) {
            for (Entry e : ENTRIES) if (e.key.equals(k)) return e;
            // the same song reached another way (an album page vs a search) counts too
            for (Entry e : ENTRIES) if (Matcher.isSameRecording(e.track, t) && Matcher.normalise(t.title).length() > 0) return e;
        }
        return null;
    }

    /** The audio file of a downloaded song, or null. */
    public static String fileFor(Track t) {
        Entry e = find(t);
        return e != null && e.done() ? e.file : null;
    }

    /** Lyrics saved with a downloaded song: null when not downloaded, an empty result when it has none. */
    static Lyrics.Result savedLyrics(Track t) {
        Entry e = find(t);
        if (e == null || !e.done() || e.lyrics == null) return null;
        Lyrics.Result r = Lyrics.load(new File(e.lyrics));
        return r != null ? r : new Lyrics.Result();
    }

    /** How many of these songs are downloaded, and how many are still on their way. */
    public static int[] count(List<Track> tracks) {
        int done = 0, pending = 0;
        for (Track t : tracks) {
            Entry e = find(t);
            if (e == null) continue;
            if (e.done()) done++;
            else if (!FAILED.equals(e.state)) pending++;
        }
        return new int[]{done, pending};
    }

    /** Queues these songs (ones already downloaded or on their way are skipped). */
    public static void add(List<Track> tracks) {
        boolean any = false;
        synchronized (LOCK) {
            for (Track t : tracks) {
                if (t == null) continue;
                Entry e = find(t);
                if (e != null) {
                    if (FAILED.equals(e.state)) {
                        e.state = QUEUED;
                        e.error = null;
                        any = true;
                    }
                    continue;
                }
                e = new Entry();
                e.track = Track.fromJson(t.toJson());
                e.key = t.key();
                e.added = System.currentTimeMillis();
                ENTRIES.add(e);
                any = true;
            }
        }
        if (any) {
            save();
            changed();
            kick();
        }
    }

    public static void add(Track t) {
        List<Track> l = new ArrayList<>();
        l.add(t);
        add(l);
    }

    public static void remove(Track t) {
        Entry e = find(t);
        if (e == null) return;
        synchronized (LOCK) {
            ENTRIES.remove(e);
        }
        deleteFiles(e);
        save();
        changed();
    }

    public static void remove(List<Track> tracks) {
        for (Track t : tracks) remove(t);
    }

    private static void deleteFiles(Entry e) {
        if (e.file != null) new File(e.file).delete();
        if (e.cover != null) new File(e.cover).delete();
        if (e.lyrics != null) new File(e.lyrics).delete();
        new File(dir, name(e.key) + ".part").delete();
    }

    /** Space the downloads take, in bytes. */
    public static long usedBytes() {
        long n = 0;
        for (Entry e : all()) if (e.done()) n += new File(e.file).length();
        return n;
    }

    // ---- the worker

    private static boolean wifiOnly() {
        try {
            return app.getSharedPreferences(wifiPrefsName, Context.MODE_PRIVATE).getBoolean("dl_wifi_only", true);
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean networkAllowed() {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || cm.getActiveNetworkInfo() == null || !cm.getActiveNetworkInfo().isConnected()) return false;
            return !wifiOnly() || !cm.isActiveNetworkMetered();
        } catch (Exception e) {
            return true;
        }
    }

    private static final Runnable RETRY = new Runnable() {
        @Override
        public void run() {
            kick();
        }
    };

    /** Starts the worker if there is something to download. */
    public static void kick() {
        synchronized (LOCK) {
            if (app == null || running) return;
            boolean work = false;
            for (Entry e : ENTRIES) if (QUEUED.equals(e.state) || WAITING.equals(e.state)) work = true;
            if (!work) return;
            running = true;
        }
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    work();
                } finally {
                    synchronized (LOCK) {
                        running = false;
                    }
                }
            }
        });
    }

    private static Entry nextQueued() {
        synchronized (LOCK) {
            for (Entry e : ENTRIES) if (QUEUED.equals(e.state) || WAITING.equals(e.state)) return e;
        }
        return null;
    }

    private static void work() {
        while (true) {
            Entry e = nextQueued();
            if (e == null) return;
            if (!networkAllowed()) {
                synchronized (LOCK) {
                    for (Entry x : ENTRIES) if (QUEUED.equals(x.state)) x.state = WAITING;
                }
                save();
                changed();
                MusicNet.MAIN.removeCallbacks(RETRY);
                MusicNet.MAIN.postDelayed(RETRY, 30000);
                return;
            }
            e.state = DOWNLOADING;
            e.bytes = 0;
            e.total = 0;
            e.error = null;
            changed();
            try {
                fetch(e);
                synchronized (LOCK) {
                    if (ENTRIES.contains(e)) e.state = DONE;
                }
            } catch (Exception ex) {
                synchronized (LOCK) {
                    if (ENTRIES.contains(e)) {
                        e.state = FAILED;
                        e.error = ex.getMessage() != null ? ex.getMessage() : "Download failed";
                    }
                }
                new File(dir, name(e.key) + ".part").delete();
            }
            synchronized (LOCK) {
                if (!ENTRIES.contains(e)) deleteFiles(e);
            }
            save();
            changed();
        }
    }

    private static String name(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception ex) {
            return Integer.toHexString(key.hashCode());
        }
    }

    private static String extension(String url, String contentType, Matcher.Playable p) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        if (ct.contains("flac")) return "flac";
        if (ct.contains("mpeg") || ct.contains("mp3")) return "mp3";
        if (ct.contains("mp4") || ct.contains("m4a") || ct.contains("aac")) return "m4a";
        if (ct.contains("ogg")) return "ogg";
        if (ct.contains("opus")) return "opus";
        if (ct.contains("webm")) return "webm";
        if (ct.contains("wav")) return "wav";
        String f = p.format == null ? "" : p.format.toLowerCase(Locale.US);
        if (f.matches("flac|mp3|m4a|aac|ogg|opus|webm|wav")) return f.equals("aac") ? "m4a" : f;
        String path = url.replaceAll("[?#].*$", "");
        int dot = path.lastIndexOf('.');
        if (dot > path.lastIndexOf('/') && path.length() - dot <= 5) return path.substring(dot + 1).toLowerCase(Locale.US);
        return "audio";
    }

    private static void fetch(final Entry e) throws Exception {
        final Track t = e.track;
        List<Addon> addons = Addons.load();
        if (addons.isEmpty()) throw new Exception("No music addon installed");
        Matcher.Playable p = Matcher.findAudio(t, addons, t.mbid != null ? new Matcher.IsrcLookup() {
            @Override
            public String find() throws Exception {
                return MusicBrainz.getRecordingIsrc(t.mbid);
            }
        } : null);
        if (p == null || p.url == null) throw new Exception("No source available");
        String base = name(e.key);
        File part = new File(dir, base + ".part");
        String url = p.url.trim().replace(" ", "%20");
        String contentType = null;
        for (int hop = 0; ; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", MusicNet.USER_AGENT);
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null && hop < 5) {
                    url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                    continue;
                }
                if (code / 100 != 2) throw new Exception("The source answered " + code);
                contentType = c.getContentType();
                e.total = c.getContentLength();
                InputStream in = c.getInputStream();
                OutputStream out = new FileOutputStream(part);
                byte[] buf = new byte[65536];
                int n;
                long lastNote = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    e.bytes += n;
                    synchronized (LOCK) {
                        if (!ENTRIES.contains(e)) break;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastNote > 700) {
                        lastNote = now;
                        changed();
                    }
                }
                out.close();
                in.close();
                break;
            } finally {
                c.disconnect();
            }
        }
        synchronized (LOCK) {
            if (!ENTRIES.contains(e)) return;
        }
        if (e.bytes < 16 * 1024) throw new Exception("The source sent no song");
        File audio = new File(dir, base + "." + extension(url, contentType, p));
        audio.delete();
        if (!part.renameTo(audio)) throw new Exception("Couldn't save the song");
        e.file = audio.getPath();
        // the cover, so it shows offline
        if (t.artworkURL != null && t.artworkURL.startsWith("http")) {
            try {
                MusicNet.Reply r = MusicNet.get(t.artworkURL, 15000);
                if (r.status == 200 && r.body != null && r.body.length > 0) {
                    File cover = new File(dir, base + ".jpg");
                    FileOutputStream out = new FileOutputStream(cover);
                    out.write(r.body);
                    out.close();
                    e.cover = cover.getPath();
                }
            } catch (Exception ignored) {
            }
        }
        // the lyrics, so they show offline too (empty when LRCLIB has none)
        try {
            Lyrics.Result l = Lyrics.find(t, t.durationMs);
            File lf = new File(dir, base + ".lyrics.json");
            Lyrics.save(l, lf);
            e.lyrics = lf.getPath();
        } catch (Exception ignored) {
            // no lyrics this time: they are looked up online when the song plays
        }
    }
}
