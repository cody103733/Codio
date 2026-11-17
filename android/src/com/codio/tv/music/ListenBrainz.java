package com.codio.tv.music;

import com.codio.tv.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Your ListenBrainz account (listenbrainz.org, free and open): sign in once with your user token on each device, and what
 * you play and the songs you heart follow you. Codio sends each song you listen to (a "listen") and each heart (a "love"),
 * and reads back your loved songs and the listens from your other devices, so Music's rows fit you everywhere. Nothing is
 * sent while signed out, and "Not interested" is never sent. A port of the Windows app's listenbrainz.ts (same saved JSON,
 * keys "codioListenBrainz" and "codioListenBrainzQueue"). Call from the main thread; the network runs on its own thread.
 */
public final class ListenBrainz {
    private ListenBrainz() {}

    private static final String KEY = "codioListenBrainz";
    private static final String QUEUE_KEY = "codioListenBrainzQueue";
    private static final String API = "https://api.listenbrainz.org";
    private static final Pattern MBID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern TOKEN = Pattern.compile("^[\\w-]{8,100}$");
    private static final long EVERY = 10 * 60 * 1000;

    public static final class Account {
        public String token;
        public String user;
        /** This device, so its own listens aren't counted twice when they come back. */
        public String device;
        /** Newest listen already read back (seconds). */
        public long listensUntil;
        /** 0 = never. */
        public long lastSync;
        public String syncError;

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("token", token).put("user", user).put("device", device).put("listensUntil", listensUntil)
                    .put("lastSync", lastSync > 0 ? (Object) lastSync : JSONObject.NULL).put("syncError", syncError == null ? JSONObject.NULL : syncError);
        }
    }

    /** Told (on the main thread) when the account or its sync state changes. */
    public static final Set<Runnable> listeners = new HashSet<Runnable>();

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Object LOCK = new Object();
    private static Account account;
    private static JSONArray queuedListens = new JSONArray();
    /** recording mbid -> {score: 1 (love) or 0 (no longer loved), at: when it was changed here}. */
    private static JSONObject queuedFeedback = new JSONObject();
    private static boolean loaded, started, syncing;

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            String saved = MusicNet.prefs().getString(KEY, null);
            if (saved != null) {
                JSONObject o = new JSONObject(saved);
                if (Model.str(o, "token") != null && Model.str(o, "user") != null) {
                    Account a = new Account();
                    a.token = o.getString("token");
                    a.user = o.getString("user");
                    a.device = o.optString("device", UUID.randomUUID().toString());
                    a.listensUntil = o.optLong("listensUntil", 0);
                    a.lastSync = o.optLong("lastSync", 0);
                    a.syncError = Model.str(o, "syncError");
                    account = a;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            JSONObject q = new JSONObject(MusicNet.prefs().getString(QUEUE_KEY, "{}"));
            if (q.optJSONArray("listens") != null) queuedListens = q.optJSONArray("listens");
            if (q.optJSONObject("feedback") != null) queuedFeedback = q.optJSONObject("feedback");
        } catch (Exception ignored) {
        }
        Taste.likeHooks.add(new Taste.LikeHook() {
            @Override
            public void liked(Model.Track t, boolean liked) {
                Account a = account();
                if (a == null || t.mbid == null || !MBID.matcher(t.mbid).matches()) return;
                synchronized (LOCK) {
                    try {
                        queuedFeedback.put(t.mbid, new JSONObject().put("score", liked ? 1 : 0).put("at", System.currentTimeMillis()));
                    } catch (JSONException ignored) {
                    }
                    saveQueue();
                }
                sendSoon(a);
            }
        });
    }

    /** The signed-in account, or null. */
    public static Account account() {
        synchronized (LOCK) {
            load();
            return account;
        }
    }

    private static void setAccount(Account next) {
        synchronized (LOCK) {
            account = next;
            try {
                if (next == null) MusicNet.prefs().edit().remove(KEY).apply();
                else MusicNet.prefs().edit().putString(KEY, next.toJson().toString()).apply();
            } catch (JSONException ignored) {
            }
        }
        MusicNet.MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Runnable l : new ArrayList<Runnable>(listeners)) l.run();
            }
        });
    }

    /** Hold LOCK. */
    private static void saveQueue() {
        try {
            MusicNet.prefs().edit().putString(QUEUE_KEY, new JSONObject().put("listens", queuedListens).put("feedback", queuedFeedback).toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    private static final class Refused extends IOException {
        final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static Object call(String token, String path, JSONObject body) throws IOException {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Authorization", "Token " + token);
        if (body != null) headers.put("Content-Type", "application/json");
        MusicNet.Reply r = MusicNet.fetch(API + path, body == null ? "GET" : "POST", headers, body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8), 15000);
        Object json = r.json();
        if (r.status / 100 != 2) {
            String error = json instanceof JSONObject ? Model.str((JSONObject) json, "error") : null;
            throw new Refused(r.status, error != null ? error : "ListenBrainz answered " + r.status);
        }
        return json;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static JSONObject metadata(Model.Track t, String device) throws JSONException {
        JSONObject info = new JSONObject();
        if (t.mbid != null && MBID.matcher(t.mbid).matches()) info.put("recording_mbid", t.mbid);
        if (t.artistMbid != null && MBID.matcher(t.artistMbid).matches()) info.put("artist_mbids", new JSONArray().put(t.artistMbid));
        if (t.durationMs > 0) info.put("duration_ms", t.durationMs);
        if (t.isrc != null) info.put("isrc", t.isrc);
        info.put("media_player", "Codio");
        info.put("submission_client", BuildConfig.PHONE ? "Codio for Android" : "Codio for Android TV");
        info.put("codio_device", device);
        JSONObject m = new JSONObject();
        m.put("artist_name", t.artist == null || t.artist.length() == 0 ? "Unknown artist" : t.artist);
        m.put("track_name", t.title == null || t.title.length() == 0 ? "Unknown song" : t.title);
        if (t.album != null) m.put("release_name", t.album);
        m.put("additional_info", info);
        return m;
    }

    // ---- Sign in and out

    /** Checks the token with ListenBrainz and signs in. done gets the user name, or an error with a message to show. */
    public static void signIn(String tokenText, final MusicNet.Done<String> done) {
        final String token = tokenText == null ? "" : tokenText.trim();
        if (!TOKEN.matcher(token).matches()) {
            done.done(null, new IOException("That doesn't look like a ListenBrainz user token"));
            return;
        }
        final Account before = account();
        // Songs hearted here before signing in go to the account first, so the first sync keeps them.
        final List<Model.Track> liked = new ArrayList<Model.Track>(Taste.likedTracks());
        WORK.execute(new Runnable() {
            @Override
            public void run() {
                String user = null;
                Exception error = null;
                try {
                    Object answer = call(token, "/1/validate-token", null);
                    JSONObject o = MusicNet.obj(answer);
                    if (!o.optBoolean("valid") || Model.str(o, "user_name") == null) throw new IOException("ListenBrainz didn't accept that token");
                    user = o.getString("user_name");
                    Account a = new Account();
                    a.token = token;
                    a.user = user;
                    a.device = before != null ? before.device : UUID.randomUUID().toString();
                    a.listensUntil = System.currentTimeMillis() / 1000 - 30L * 24 * 3600;
                    synchronized (LOCK) {
                        for (Model.Track t : liked) {
                            if (t.mbid != null && MBID.matcher(t.mbid).matches() && !queuedFeedback.has(t.mbid)) {
                                queuedFeedback.put(t.mbid, new JSONObject().put("score", 1).put("at", System.currentTimeMillis()));
                            }
                        }
                        saveQueue();
                    }
                    setAccount(a);
                } catch (Refused e) {
                    error = new IOException(e.status == 401 ? "ListenBrainz didn't accept that token" : e.getMessage());
                } catch (IOException e) {
                    error = e.getMessage() != null && e.getMessage().startsWith("ListenBrainz") ? e : new IOException("Couldn't reach ListenBrainz");
                } catch (Exception e) {
                    error = new IOException("Couldn't reach ListenBrainz");
                }
                final String u = user;
                final Exception err = error;
                MusicNet.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        done.done(u, err);
                        if (err == null) syncNow();
                    }
                });
            }
        });
    }

    public static void signOut() {
        synchronized (LOCK) {
            load();
            queuedListens = new JSONArray();
            queuedFeedback = new JSONObject();
            saveQueue();
        }
        setAccount(null);
    }

    // ---- What you play and heart, sent as it happens (kept and sent later when offline)

    /** On the work thread. */
    private static void sendQueued(Account a) throws IOException, JSONException {
        JSONArray batch = new JSONArray();
        synchronized (LOCK) {
            for (int i = 0; i < queuedListens.length() && i < 100; i++) batch.put(queuedListens.get(i));
        }
        if (batch.length() > 0) {
            call(a.token, "/1/submit-listens", new JSONObject().put("listen_type", batch.length() == 1 ? "single" : "import").put("payload", batch));
            synchronized (LOCK) {
                JSONArray rest = new JSONArray();
                for (int i = batch.length(); i < queuedListens.length(); i++) rest.put(queuedListens.get(i));
                queuedListens = rest;
                saveQueue();
            }
        }
        List<String> ids = new ArrayList<String>();
        synchronized (LOCK) {
            for (Iterator<String> it = queuedFeedback.keys(); it.hasNext(); ) ids.add(it.next());
        }
        for (String mbid : ids) {
            JSONObject f;
            synchronized (LOCK) {
                f = queuedFeedback.optJSONObject(mbid);
            }
            if (f == null) continue;
            call(a.token, "/1/feedback/recording-feedback", new JSONObject().put("recording_mbid", mbid).put("score", f.optInt("score")));
            synchronized (LOCK) {
                JSONObject now = queuedFeedback.optJSONObject(mbid);
                if (now != null && now.optLong("at") == f.optLong("at")) queuedFeedback.remove(mbid);
                saveQueue();
            }
        }
    }

    private static void sendSoon(final Account a) {
        WORK.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    sendQueued(a);
                } catch (Exception ignored) {
                    // sent at the next sync
                }
            }
        });
    }

    /** A song started (shown as "listening now" on your ListenBrainz page). */
    public static void playingNow(final Model.Track t) {
        final Account a = account();
        if (a == null || t == null) return;
        WORK.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    call(a.token, "/1/submit-listens", new JSONObject().put("listen_type", "playing_now")
                            .put("payload", new JSONArray().put(new JSONObject().put("track_metadata", metadata(t, a.device)))));
                } catch (Exception ignored) {
                    // only a hint
                }
            }
        });
    }

    /**
     * A song counts as listened to after half of it, or four minutes, whichever comes first (ListenBrainz's rule); the
     * player calls this once that point is reached.
     */
    public static void listened(Model.Track t, long startedAtMs) {
        Account a = account();
        if (a == null || t == null) return;
        synchronized (LOCK) {
            try {
                queuedListens.put(new JSONObject().put("listened_at", startedAtMs / 1000).put("track_metadata", metadata(t, a.device)));
                if (queuedListens.length() > 500) {
                    JSONArray kept = new JSONArray();
                    for (int i = queuedListens.length() - 500; i < queuedListens.length(); i++) kept.put(queuedListens.get(i));
                    queuedListens = kept;
                }
            } catch (JSONException ignored) {
            }
            saveQueue();
        }
        sendSoon(a);
    }

    // ---- Reading back: loved songs and listens from your other devices

    private static Model.Track lovedTrack(JSONObject f) {
        JSONObject meta = f.optJSONObject("track_metadata");
        String mbid = f.optString("recording_mbid", "");
        if (!MBID.matcher(mbid).matches() || meta == null || Model.str(meta, "track_name") == null) return null;
        JSONObject map = MusicNet.obj(meta.optJSONObject("mbid_mapping"));
        String release = Model.str(map, "caa_release_mbid");
        if (release == null) release = Model.str(map, "release_mbid");
        Model.Track t = new Model.Track();
        t.id = "mbid:" + mbid;
        t.mbid = mbid;
        t.title = meta.optString("track_name");
        t.artist = meta.isNull("artist_name") ? "" : meta.optString("artist_name", "");
        t.album = Model.str(meta, "release_name");
        t.artworkURL = release != null ? "https://coverartarchive.org/release/" + release + "/front-250" : null;
        JSONArray artists = map.optJSONArray("artist_mbids");
        t.artistMbid = artists != null && artists.length() > 0 && artists.optString(0).length() > 0 ? artists.optString(0) : null;
        return t;
    }

    private static void pullLoved(Account a) throws IOException {
        final Set<String> ids = new HashSet<String>();
        final List<Model.Track> tracks = new ArrayList<Model.Track>();
        for (int offset = 0; offset < 2000; offset += 100) {
            JSONObject page = MusicNet.obj(call(a.token, "/1/feedback/user/" + enc(a.user) + "/get-feedback?score=1&count=100&offset=" + offset + "&metadata=true", null));
            JSONArray list = MusicNet.arr(page.optJSONArray("feedback"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject f = list.optJSONObject(i);
                if (f == null) continue;
                if (Model.str(f, "recording_mbid") != null) ids.add(f.optString("recording_mbid"));
                Model.Track t = lovedTrack(f);
                if (t != null) tracks.add(t);
            }
            if (list.length() < 100 || offset + 100 >= page.optInt("total_count", 0)) break;
        }
        // Hearts changed here that haven't reached the account yet win over what it says.
        final Set<String> pending = new HashSet<String>();
        synchronized (LOCK) {
            for (Iterator<String> it = queuedFeedback.keys(); it.hasNext(); ) pending.add(it.next());
        }
        MusicNet.MAIN.post(new Runnable() {
            @Override
            public void run() {
                Taste.applyLovedFromAccount(ids, tracks, new Taste.Keep() {
                    @Override
                    public boolean keep(Model.Track t) {
                        return t.mbid != null && pending.contains(t.mbid);
                    }
                });
            }
        });
    }

    private static long pullListens(Account a) throws IOException {
        JSONObject answer = MusicNet.obj(call(a.token, "/1/user/" + enc(a.user) + "/listens?count=100&min_ts=" + a.listensUntil, null));
        JSONArray listens = MusicNet.arr(MusicNet.obj(answer.optJSONObject("payload")).optJSONArray("listens"));
        long newest = a.listensUntil;
        final List<Taste.AccountPlay> plays = new ArrayList<Taste.AccountPlay>();
        for (int i = 0; i < listens.length(); i++) {
            JSONObject l = listens.optJSONObject(i);
            if (l == null) continue;
            long at = l.optLong("listened_at", 0);
            newest = Math.max(newest, at);
            JSONObject meta = MusicNet.obj(l.optJSONObject("track_metadata"));
            JSONObject info = MusicNet.obj(meta.optJSONObject("additional_info"));
            if (a.device.equals(info.optString("codio_device"))) continue;
            JSONArray artists = MusicNet.obj(meta.optJSONObject("mbid_mapping")).optJSONArray("artist_mbids");
            if (artists == null) artists = info.optJSONArray("artist_mbids");
            String artistMbid = artists != null && artists.length() > 0 ? artists.optString(0) : "";
            if (!MBID.matcher(artistMbid).matches()) continue;
            Taste.AccountPlay p = new Taste.AccountPlay();
            p.artistMbid = artistMbid;
            p.name = meta.isNull("artist_name") ? "" : meta.optString("artist_name", "");
            p.at = at * 1000;
            plays.add(p);
        }
        if (!plays.isEmpty()) MusicNet.MAIN.post(new Runnable() {
            @Override
            public void run() {
                Taste.addPlaysFromAccount(plays);
            }
        });
        return newest;
    }

    private static Account changed(Account from, long listensUntil, long lastSync, String syncError) {
        Account a = new Account();
        a.token = from.token;
        a.user = from.user;
        a.device = from.device;
        a.listensUntil = listensUntil;
        a.lastSync = lastSync;
        a.syncError = syncError;
        return a;
    }

    /** Sends what is waiting and reads the account back. Does nothing when signed out or already at it. */
    public static void syncNow() {
        final Account a = account();
        synchronized (LOCK) {
            if (a == null || syncing) return;
            syncing = true;
        }
        WORK.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    sendQueued(a);
                    pullLoved(a);
                    long newest = pullListens(a);
                    Account now = account();
                    if (now != null && now.token.equals(a.token)) setAccount(changed(now, newest, System.currentTimeMillis(), null));
                } catch (Exception e) {
                    boolean refused = e instanceof Refused && ((Refused) e).status == 401;
                    Account now = account();
                    if (now != null && now.token.equals(a.token)) {
                        setAccount(changed(now, now.listensUntil, now.lastSync, refused ? "ListenBrainz no longer accepts your token. Sign in again." : "Couldn't reach ListenBrainz. Trying again later."));
                    }
                } finally {
                    synchronized (LOCK) {
                        syncing = false;
                    }
                }
            }
        });
    }

    /** Started once by the app: syncs now and every ten minutes. Call syncNow() too when the app comes back to the front. */
    public static void start() {
        if (started) return;
        started = true;
        syncNow();
        MusicNet.MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                syncNow();
                MusicNet.MAIN.postDelayed(this, EVERY);
            }
        }, EVERY);
    }
}
