package com.codio.tv.ui;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Movies and episodes saved to the phone. Android's own DownloadManager does the transfer (it keeps going when Codio
 * is closed, resumes after a dropped connection and shows progress in the notification shade); this class remembers
 * what each file is, finds a source for it first, and keeps the title's details so it opens and plays offline.
 */
final class PhoneDownloads {
    private static final String TAG = "CodioDownloads";

    static final String WAITING = "waiting", FINDING = "finding", DOWNLOADING = "downloading", DONE = "done", FAILED = "failed";

    static final class Entry {
        String key, metaId, type, name, poster, posterFile, videoId, episodeTitle, thumb, thumbFile;
        int season, episode;
        String quality, wantQuality, state = WAITING, error, file, status;
        long bytes, done, dmId = -1, addedAt;
        double position, duration;

        boolean isEpisode() {
            return videoId != null && !videoId.equals(metaId);
        }

        float fraction() {
            if (DONE.equals(state)) return 1;
            return bytes > 0 ? Math.min(1f, (float) done / bytes) : 0;
        }

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("key", key).put("metaId", metaId).put("type", type).put("name", name).put("poster", poster)
                    .put("posterFile", posterFile).put("videoId", videoId).put("episodeTitle", episodeTitle)
                    .put("thumb", thumb).put("thumbFile", thumbFile).put("season", season).put("episode", episode)
                    .put("quality", quality).put("wantQuality", wantQuality).put("state", state).put("error", error)
                    .put("file", file).put("bytes", bytes).put("done", done).put("dmId", dmId).put("addedAt", addedAt)
                    .put("position", position).put("duration", duration);
            return o;
        }

        static Entry parse(JSONObject o) {
            Entry e = new Entry();
            e.key = o.optString("key");
            e.metaId = o.optString("metaId");
            e.type = o.optString("type", "movie");
            e.name = Store.str(o, "name");
            e.poster = Store.str(o, "poster");
            e.posterFile = Store.str(o, "posterFile");
            e.videoId = Store.str(o, "videoId");
            e.episodeTitle = Store.str(o, "episodeTitle");
            e.thumb = Store.str(o, "thumb");
            e.thumbFile = Store.str(o, "thumbFile");
            e.season = o.optInt("season");
            e.episode = o.optInt("episode");
            e.quality = Store.str(o, "quality");
            e.wantQuality = Store.str(o, "wantQuality");
            e.state = o.optString("state", WAITING);
            e.error = Store.str(o, "error");
            e.file = Store.str(o, "file");
            e.bytes = o.optLong("bytes");
            e.done = o.optLong("done");
            e.dmId = o.optLong("dmId", -1);
            e.addedAt = o.optLong("addedAt");
            e.position = o.optDouble("position", 0);
            e.duration = o.optDouble("duration", 0);
            return e;
        }
    }

    interface Listener {
        void downloadsChanged();
    }

    private final Context ctx;
    private final Store store;
    private final TvView.Host host;
    private final Listener listener;
    private final DownloadManager dm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File listFile, metaFile, picDir;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, JSONObject> metas = new LinkedHashMap<>();

    PhoneDownloads(Context ctx, Store store, TvView.Host host, Listener listener) {
        this.ctx = ctx;
        this.store = store;
        this.host = host;
        this.listener = listener;
        this.dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        listFile = new File(ctx.getFilesDir(), "downloads.json");
        metaFile = new File(ctx.getFilesDir(), "downloads-meta.json");
        picDir = new File(ctx.getFilesDir(), "download-pictures");
        load();
        refresh();
        // Episodes still waiting for a source (Codio was closed meanwhile) carry on.
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                pumpFinder();
            }
        }, 6000);
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        try {
            if (listFile.exists()) {
                JSONArray a = new JSONArray(new String(PhoneKit.readAll(new java.io.FileInputStream(listFile)), "UTF-8"));
                for (int i = 0; i < a.length(); i++) {
                    Entry e = Entry.parse(a.getJSONObject(i));
                    // A lookup cut short when Codio closed starts again.
                    if (FINDING.equals(e.state)) e.state = WAITING;
                    entries.put(e.key, e);
                }
            }
            if (metaFile.exists()) {
                JSONObject o = new JSONObject(new String(PhoneKit.readAll(new java.io.FileInputStream(metaFile)), "UTF-8"));
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    metas.put(k, o.getJSONObject(k));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "downloads list unreadable", e);
        }
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Entry e : entries.values()) a.put(e.json());
            write(listFile, a.toString());
            JSONObject m = new JSONObject();
            for (Map.Entry<String, JSONObject> x : metas.entrySet()) {
                if (!entriesFor(x.getKey()).isEmpty()) m.put(x.getKey(), x.getValue());
            }
            write(metaFile, m.toString());
        } catch (Exception e) {
            Log.w(TAG, "downloads list not saved", e);
        }
    }

    private static void write(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        out.write(s.getBytes("UTF-8"));
        out.getFD().sync();
        out.close();
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename failed");
    }

    // ------------------------------------------------------------------ queries

    static String key(String type, String id, String videoId) {
        return type + "/" + id + "/" + (videoId != null ? videoId : id);
    }

    Entry entry(String type, String id, String videoId) {
        return entries.get(key(type, id, videoId));
    }

    List<Entry> entriesFor(String metaId) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) if (e.metaId.equals(metaId)) out.add(e);
        return out;
    }

    List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    boolean any() {
        return !entries.isEmpty();
    }

    /** DONE when everything saved for this title has finished, DOWNLOADING while any is still on its way, else null. */
    String metaState(String metaId) {
        List<Entry> list = entriesFor(metaId);
        if (list.isEmpty()) return null;
        boolean active = false, failed = false;
        for (Entry e : list) {
            if (DONE.equals(e.state)) continue;
            if (FAILED.equals(e.state)) failed = true;
            else active = true;
        }
        if (active) return DOWNLOADING;
        if (failed) {
            for (Entry e : list) if (DONE.equals(e.state)) return DONE;
            return FAILED;
        }
        return DONE;
    }

    float metaFraction(String metaId) {
        long total = 0, got = 0;
        int n = 0;
        float sum = 0;
        for (Entry e : entriesFor(metaId)) {
            n++;
            sum += e.fraction();
            total += e.bytes;
            got += DONE.equals(e.state) ? e.bytes : e.done;
        }
        if (n == 0) return 0;
        return sum / n;
    }

    int doneCount(String metaId) {
        int n = 0;
        for (Entry e : entriesFor(metaId)) if (DONE.equals(e.state)) n++;
        return n;
    }

    /** Titles with something saved, newest first (for the Library), with the saved details when offline. */
    List<Store.Meta> metas() {
        List<Entry> list = new ArrayList<>(entries.values());
        Collections.sort(list, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(b.addedAt, a.addedAt);
            }
        });
        List<Store.Meta> out = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (Entry e : list) {
            if (!seen.add(e.metaId)) continue;
            out.add(savedMeta(e.metaId, e));
        }
        return out;
    }

    /** The title as saved with its download (works without internet). */
    Store.Meta savedMeta(String metaId, Entry hint) {
        JSONObject raw = metas.get(metaId);
        Store.Meta m = raw != null ? Store.Meta.parse(raw) : new Store.Meta();
        if (raw == null && hint != null) {
            m.id = hint.metaId;
            m.type = hint.type;
            m.name = hint.name != null ? hint.name : "";
            m.poster = hint.poster;
        }
        if (hint == null) {
            List<Entry> l = entriesFor(metaId);
            if (!l.isEmpty()) hint = l.get(0);
        }
        if (hint != null && hint.posterFile != null && new File(hint.posterFile).exists()) m.poster = hint.posterFile;
        return m;
    }

    boolean hasSavedMeta(String metaId) {
        return metas.containsKey(metaId);
    }

    long freeBytes() {
        File d = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        return d != null ? d.getUsableSpace() : 0;
    }

    boolean wifiOnly() {
        return host.prefs().getBoolean("dl_wifi_only", true);
    }

    // ------------------------------------------------------------------ adding

    private Entry create(Store.Meta m, Store.Video v) {
        Entry e = new Entry();
        e.metaId = m.id;
        e.type = m.type;
        e.name = m.name;
        e.poster = m.poster;
        e.videoId = v != null ? v.id : m.id;
        e.key = key(m.type, m.id, e.videoId);
        if (v != null) {
            e.season = v.season;
            e.episode = v.episode;
            e.episodeTitle = v.title;
            e.thumb = v.thumbnail;
        }
        e.addedAt = System.currentTimeMillis();
        if (m.raw != null) metas.put(m.id, m.raw);
        savePictures(e);
        return e;
    }

    /** Start downloading this source now (the viewer picked it). */
    Entry start(Store.Meta m, Store.Video v, PhoneSources.Option o) {
        Entry e = entries.get(key(m.type, m.id, v != null ? v.id : m.id));
        if (e == null) e = create(m, v);
        entries.put(e.key, e);
        enqueue(e, o);
        save();
        changed();
        return e;
    }

    /** A whole season (or any list of episodes) in one quality: each episode finds its source in turn. */
    int startMany(Store.Meta m, List<Store.Video> videos, String quality) {
        int n = 0;
        for (Store.Video v : videos) {
            String k = key(m.type, m.id, v.id);
            Entry old = entries.get(k);
            if (old != null && !FAILED.equals(old.state)) continue;
            Entry e = old != null ? old : create(m, v);
            e.state = WAITING;
            e.error = null;
            e.wantQuality = quality;
            entries.put(k, e);
            n++;
        }
        save();
        changed();
        pumpFinder();
        return n;
    }

    private void enqueue(Entry e, PhoneSources.Option o) {
        try {
            File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Codio");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String base = safe(e.name != null ? e.name : e.metaId) + (e.isEpisode() ? String.format(java.util.Locale.US, " S%02dE%02d", e.season, e.episode) : "");
            String name = base + " " + PhoneKit.qualityLabel(o.quality).replace(" ", "") + "." + PhoneSources.extension(o.source);
            File f = new File(dir, name);
            if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(o.source.url));
            r.setTitle(base + (o.quality != null ? " (" + PhoneKit.qualityLabel(o.quality) + ")" : ""));
            r.setDescription("Codio");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationUri(Uri.fromFile(f));
            r.setAllowedOverMetered(!wifiOnly());
            r.setAllowedOverRoaming(false);
            r.setVisibleInDownloadsUi(false);
            r.addRequestHeader("User-Agent", "Codio");
            e.dmId = dm.enqueue(r);
            e.file = f.getAbsolutePath();
            e.quality = o.quality;
            e.bytes = Math.max(0, o.bytes);
            e.done = 0;
            e.state = DOWNLOADING;
            e.error = null;
            e.status = null;
            Log.i(TAG, "download " + e.key + " " + o.quality + " queued");
        } catch (Exception ex) {
            Log.w(TAG, "download not started", ex);
            e.state = FAILED;
            e.error = "Couldn't start the download";
        }
        scheduleRefresh(500);
    }

    private static String safe(String s) {
        String t = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > 80) t = t.substring(0, 80).trim();
        return t.isEmpty() ? "Video" : t;
    }

    /** Posters and episode stills saved next to the list, so the Library looks right offline. */
    private void savePictures(final Entry e) {
        //noinspection ResultOfMethodCallIgnored
        picDir.mkdirs();
        final String poster = e.poster, thumb = e.thumb;
        final File pf = new File(picDir, "p-" + Integer.toHexString(e.metaId.hashCode()) + ".jpg");
        final File tf = new File(picDir, "t-" + Integer.toHexString(e.key.hashCode()) + ".jpg");
        e.posterFile = poster != null ? pf.getAbsolutePath() : null;
        e.thumbFile = thumb != null ? tf.getAbsolutePath() : null;
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                fetch(poster, pf);
                fetch(thumb, tf);
            }
        });
    }

    private static void fetch(String url, File f) {
        if (url == null || f.exists()) return;
        try {
            if (url.startsWith("//")) url = "https:" + url;
            byte[] b = Net.getBytes(url);
            FileOutputStream out = new FileOutputStream(f);
            out.write(b);
            out.close();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ removing

    void remove(Entry e) {
        if (e.dmId >= 0) {
            try {
                dm.remove(e.dmId);
            } catch (Exception ignored) {
            }
        }
        if (e.file != null) //noinspection ResultOfMethodCallIgnored
            new File(e.file).delete();
        if (e.thumbFile != null) //noinspection ResultOfMethodCallIgnored
            new File(e.thumbFile).delete();
        entries.remove(e.key);
        if (entriesFor(e.metaId).isEmpty()) {
            if (e.posterFile != null) //noinspection ResultOfMethodCallIgnored
                new File(e.posterFile).delete();
            metas.remove(e.metaId);
        }
        if (finding != null && finding.key.equals(e.key)) finding = null;
        save();
        changed();
    }

    void removeAll(String metaId) {
        for (Entry e : entriesFor(metaId)) remove(e);
    }

    void retry(Entry e) {
        if (e.wantQuality == null) e.wantQuality = e.quality != null ? e.quality : "1080p";
        if (e.dmId >= 0) {
            try {
                dm.remove(e.dmId);
            } catch (Exception ignored) {
            }
            e.dmId = -1;
        }
        e.state = WAITING;
        e.error = null;
        save();
        changed();
        pumpFinder();
    }

    void savePosition(String type, String id, String videoId, double pos, double dur) {
        Entry e = entry(type, id, videoId);
        if (e == null) return;
        e.position = dur > 0 && pos / dur > 0.95 ? 0 : pos;
        if (dur > 0) e.duration = dur;
        save();
    }

    // ------------------------------------------------------------------ progress (DownloadManager)

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };

    private void scheduleRefresh(long ms) {
        main.removeCallbacks(refresher);
        main.postDelayed(refresher, ms);
    }

    /** Reads every transfer's state; keeps checking while any is running. */
    void refresh() {
        List<Long> ids = new ArrayList<>();
        Map<Long, Entry> byId = new LinkedHashMap<>();
        for (Entry e : entries.values()) {
            if (DOWNLOADING.equals(e.state) && e.dmId >= 0) {
                ids.add(e.dmId);
                byId.put(e.dmId, e);
            }
        }
        if (ids.isEmpty()) return;
        long[] arr = new long[ids.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = ids.get(i);
        boolean changed = false, active = false;
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        Cursor c = null;
        try {
            c = dm.query(new DownloadManager.Query().setFilterById(arr));
            while (c != null && c.moveToNext()) {
                long id = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_ID));
                Entry e = byId.get(id);
                if (e == null) continue;
                seen.add(id);
                int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                int reason = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON));
                long got = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                String before = e.state + e.done + e.status;
                if (total > 0) e.bytes = total;
                e.done = got;
                e.status = null;
                switch (status) {
                    case DownloadManager.STATUS_SUCCESSFUL:
                        e.state = DONE;
                        if (e.bytes <= 0) e.bytes = got;
                        Log.i(TAG, "download " + e.key + " finished");
                        break;
                    case DownloadManager.STATUS_FAILED:
                        e.state = FAILED;
                        e.error = failure(reason);
                        Log.w(TAG, "download " + e.key + " failed: " + reason);
                        break;
                    case DownloadManager.STATUS_PAUSED:
                        active = true;
                        e.status = reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI ? "Waiting for Wi-Fi"
                                : reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK ? "Waiting for internet"
                                : reason == DownloadManager.PAUSED_WAITING_TO_RETRY ? "Retrying…" : "Paused";
                        break;
                    case DownloadManager.STATUS_PENDING:
                        active = true;
                        e.status = "Queued";
                        break;
                    default:
                        active = true;
                        break;
                }
                if (!before.equals(e.state + e.done + e.status)) changed = true;
            }
        } catch (Exception ex) {
            Log.w(TAG, "download status unreadable", ex);
            active = true;
        } finally {
            if (c != null) c.close();
        }
        // Cancelled from the notification shade (or cleared by the system).
        for (Entry e : byId.values()) {
            if (!seen.contains(e.dmId) && DOWNLOADING.equals(e.state)) {
                if (e.file != null && new File(e.file).length() > 0 && e.done > 0 && e.done >= e.bytes) {
                    e.state = DONE;
                } else {
                    e.state = FAILED;
                    e.error = "Download cancelled";
                }
                changed = true;
            }
        }
        if (changed) {
            save();
            changed();
        }
        if (active) scheduleRefresh(1500);
    }

    private static String failure(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "Not enough space on the phone";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "Storage not available";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "The source stopped working";
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "The source can't resume the download";
            default:
                if (reason >= 400 && reason < 600) return "The source refused the download (" + reason + ")";
                return "Download failed";
        }
    }

    private void changed() {
        listener.downloadsChanged();
    }

    // ------------------------------------------------------------------ finding sources (one title at a time)

    interface SourcesCallback {
        /** sources, or null when none came (offline, no addon has it). */
        void done(Store.Sources s);
    }

    private static final class Lookup {
        Store.Meta meta;
        String videoId;
        SourcesCallback cb;
        long startedAt, firstAt;
    }

    private final ArrayDeque<Lookup> lookups = new ArrayDeque<>();
    private Lookup current;
    private Entry finding;

    /** Loads the source list for one movie/episode in the hidden web page (as Play does), then calls back. */
    void findSources(Store.Meta m, String videoId, SourcesCallback cb) {
        Lookup l = new Lookup();
        l.meta = m;
        l.videoId = videoId != null ? videoId : m.id;
        l.cb = cb;
        lookups.addLast(l);
        pumpLookups();
    }

    boolean lookupActive() {
        return current != null || !lookups.isEmpty();
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            pumpLookups();
        }
    };

    private void pumpLookups() {
        main.removeCallbacks(poll);
        if (current == null) {
            if (lookups.isEmpty()) return;
            if (host.webBusy()) {
                // Something is playing: the page is in use. Try again in a while.
                main.postDelayed(poll, 3000);
                return;
            }
            current = lookups.pollFirst();
            current.startedAt = SystemClock.uptimeMillis();
            host.prefetch(current.meta, current.videoId);
        }
        Lookup l = current;
        long now = SystemClock.uptimeMillis();
        Store.Sources s = store.sourcesFor(l.meta.type, l.meta.id, l.videoId);
        if (s != null && l.firstAt == 0) l.firstAt = now;
        boolean finished = s != null && !s.loading;
        // Slow addons: after 25 s with some sources, go with what there is; give up after 45 s.
        boolean enough = s != null && !PhoneSources.options(s).isEmpty() && now - l.firstAt > 25000;
        boolean timeout = now - l.startedAt > 45000;
        if (host.webBusy() && !finished) {
            // Play took over the page meanwhile: start this one again later.
            lookups.addFirst(l);
            current = null;
            main.postDelayed(poll, 3000);
            return;
        }
        if (finished || enough || timeout) {
            current = null;
            if (lookups.isEmpty()) host.prefetchEnd();
            l.cb.done(s);
            if (!lookups.isEmpty()) main.post(poll);
            return;
        }
        main.postDelayed(poll, 400);
    }

    /** Episodes added with "Download season" get their sources one after another. */
    private void pumpFinder() {
        if (finding != null) return;
        Entry next = null;
        for (Entry e : entries.values()) {
            if (WAITING.equals(e.state)) {
                next = e;
                break;
            }
        }
        if (next == null) return;
        final Entry e = next;
        finding = e;
        e.state = FINDING;
        changed();
        Store.Meta m = savedMeta(e.metaId, e);
        if (m.id == null) {
            m.id = e.metaId;
            m.type = e.type;
        }
        findSources(m, e.videoId, new SourcesCallback() {
            @Override
            public void done(Store.Sources s) {
                if (finding != e || !entries.containsKey(e.key)) {
                    finding = null;
                    pumpFinder();
                    return;
                }
                finding = null;
                PhoneSources.Option o = s != null ? PhoneSources.pick(s, e.wantQuality) : null;
                if (o == null) {
                    e.state = FAILED;
                    e.error = s == null ? "Couldn't load sources (no internet?)" : "No downloadable source found";
                } else {
                    enqueue(e, o);
                }
                save();
                changed();
                pumpFinder();
            }
        });
    }
}
