package com.codio.tv.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.codio.tv.music.Addons;
import com.codio.tv.music.ListenBrainz;
import com.codio.tv.music.Model;
import com.codio.tv.music.MusicNet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sync passcode: the private settings (TMDB key, music addon links, ListenBrainz sign-in, MusicBrainz server) follow
 * you to every device signed in to the same Stremio account, locked with a passcode only you know. They are encrypted
 * here (PBKDF2-SHA256 600,000 rounds, then AES-256-GCM) and kept in a hidden library entry, "codio:vault", which
 * Stremio stores but can't open. Same format and rules as Windows (vault.ts) and iPhone (Vault.swift): see SYNC.md.
 */
public final class Vault {
    private static final String TAG = "CodioVault";
    private static final String API = "https://api.strem.io/api/";
    static final String VAULT_ID = "codio:vault";
    public static final int ITERATIONS = 600000;
    public static final int MIN_PASSCODE = 6;
    private static final long PULL_EVERY_MS = 10 * 60 * 1000L;

    public static final int OK = 0, LOCKED = 1, OFFLINE = 2;

    private static SharedPreferences state, codio;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Told on the main thread when the sync state changes. */
    public static final Set<Runnable> listeners = new HashSet<>();
    private static volatile String authKey, accountId;
    private static volatile boolean busy;

    private Vault() {
    }

    public static synchronized void init(Context c) {
        if (state != null) return;
        MusicNet.init(c);
        state = c.getApplicationContext().getSharedPreferences("codio_sync", Context.MODE_PRIVATE);
        codio = c.getApplicationContext().getSharedPreferences("codio", Context.MODE_PRIVATE);
        Addons.listen(new Addons.Changed() {
            @Override
            public void changed() {
                soon();
            }
        });
        ListenBrainz.listeners.add(new Runnable() {
            @Override
            public void run() {
                soon();
            }
        });
    }

    // ------------------------------------------------------------------ status

    public static boolean on() {
        return state != null && state.getString("key", null) != null;
    }

    public static boolean busy() {
        return busy;
    }

    public static int problem() {
        return state == null ? OK : state.getInt("problem", OK);
    }

    public static long lastSync() {
        return state == null ? 0 : state.getLong("lastSync", 0);
    }

    public static boolean signedIn() {
        return authKey != null;
    }

    /** The short value for the Settings row. */
    public static String label() {
        if (authKey == null) return "Sign in first";
        if (!on()) return "Off · Set passcode";
        if (problem() == LOCKED) return "Enter the new passcode";
        if (busy) return "Syncing…";
        if (problem() == OFFLINE) return "On · Offline";
        return "On";
    }

    public static String detail() {
        if (authKey == null) return "Sign in to your Stremio account first. The locked settings are kept with it.";
        if (!on()) return "Off. Enter the passcode you set on another device, or pick a new one.";
        if (problem() == LOCKED) return "The sync passcode was changed on another device. Enter the new one.";
        if (problem() == OFFLINE) return "Couldn't reach your Stremio account. Codio tries again on its own.";
        long t = lastSync();
        if (t == 0) return "On.";
        long m = Math.round((System.currentTimeMillis() - t) / 60000.0);
        return "On. Last synced " + (m < 1 ? "just now" : m < 60 ? m + " min ago" : Math.round(m / 60.0) + " h ago") + ".";
    }

    public static final String HELP = "One passcode unlocks your private settings on every device: the TMDB key, music addon links, "
            + "your ListenBrainz sign-in and the MusicBrainz server. They're locked with the passcode before they go to your "
            + "Stremio account, so nobody without it (Stremio included) can read them. Enter the same passcode once on each device.";

    private static void changed() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Runnable l : new ArrayList<>(listeners)) l.run();
            }
        });
    }

    // ------------------------------------------------------------------ crypto (VaultCrypto, checked against sync/vector.json)

    public static String normalise(String passcode) {
        return VaultCrypto.normalise(passcode);
    }

    static byte[] deriveKey(String passcode, byte[] salt, int iterations) throws Exception {
        return VaultCrypto.deriveKey(passcode, salt, iterations);
    }

    static JSONObject seal(byte[] key, String salt, int iter, JSONObject items) throws Exception {
        return VaultCrypto.seal(key, salt, iter, items);
    }

    static JSONObject open(byte[] key, JSONObject box) {
        return VaultCrypto.open(key, box);
    }

    static String b64(byte[] b) {
        return VaultCrypto.b64(b);
    }

    static byte[] unb64(String s) {
        return VaultCrypto.unb64(s);
    }

    // ------------------------------------------------------------------ the hidden entry

    private static JSONObject call(String method, JSONObject body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + method).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream out = c.getOutputStream();
            out.write(body.toString().getBytes("UTF-8"));
            out.close();
            if (c.getResponseCode() != 200) throw new IOException("Stremio answered " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            in.close();
            JSONObject r = new JSONObject(buf.toString("UTF-8"));
            if (r.has("error") && !r.isNull("error")) throw new IOException("Stremio said no");
            return r;
        } catch (org.json.JSONException e) {
            throw new IOException("Unreadable answer from Stremio");
        } finally {
            c.disconnect();
        }
    }

    /** The box saved with the account, or null when there is none yet. Throws when the account can't be reached. */
    private static JSONObject readBox(String auth) throws Exception {
        JSONObject r = call("datastoreGet", new JSONObject().put("authKey", auth).put("collection", "libraryItem")
                .put("ids", new JSONArray().put(VAULT_ID)).put("all", false));
        JSONArray items = r.optJSONArray("result");
        if (items == null) return null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null || !VAULT_ID.equals(it.optString("_id"))) continue;
            String name = it.optString("name", "");
            if (name.isEmpty()) return null;
            try {
                JSONObject box = new JSONObject(name);
                return box.has("salt") && box.has("iv") && box.has("data") ? box : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static JSONObject hiddenItem(String id, String name) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String now = f.format(new Date());
        JSONObject st = new JSONObject()
                .put("lastWatched", JSONObject.NULL).put("timeWatched", 0).put("timeOffset", 0)
                .put("overallTimeWatched", 0).put("timesWatched", 0).put("flaggedWatched", 0)
                .put("duration", 0).put("video_id", JSONObject.NULL).put("watched", JSONObject.NULL)
                .put("noNotif", true);
        JSONObject hints = new JSONObject().put("defaultVideoId", JSONObject.NULL)
                .put("featuredVideoId", JSONObject.NULL).put("hasScheduledVideos", false);
        return new JSONObject().put("_id", id).put("name", name).put("type", "other")
                .put("poster", JSONObject.NULL).put("posterShape", "poster")
                .put("removed", true).put("temp", true).put("_ctime", now).put("_mtime", now)
                .put("state", st).put("behaviorHints", hints);
    }

    /** Saves the box, and empties the old unlocked TMDB entry so the key can't be read there any more. */
    private static void writeBox(String auth, JSONObject box) throws Exception {
        call("datastorePut", new JSONObject().put("authKey", auth).put("collection", "libraryItem")
                .put("changes", new JSONArray().put(hiddenItem(VAULT_ID, box.toString())).put(hiddenItem(KeySync.ITEM_ID, ""))));
    }

    // ------------------------------------------------------------------ what is synced, as it is on this device

    static final String[] NAMES = {"tmdbKey", "musicAddons", "listenBrainz", "musicBrainzServer"};

    static Object read(String name) {
        try {
            switch (name) {
                case "tmdbKey":
                    return codio.getString("tmdb_key", "").trim();
                case "musicAddons": {
                    JSONArray a = new JSONArray();
                    for (Model.Addon ad : Addons.load()) a.put(new JSONObject().put("url", ad.url).put("manifest", ad.manifest));
                    return a;
                }
                case "listenBrainz": {
                    ListenBrainz.Account acc = ListenBrainz.account();
                    return acc == null ? JSONObject.NULL : new JSONObject().put("token", acc.token).put("user", acc.user);
                }
                case "musicBrainzServer":
                    return MusicNet.mbBase();
            }
        } catch (Exception ignored) {
        }
        return JSONObject.NULL;
    }

    private static JSONArray addonList(Object v) {
        JSONArray out = new JSONArray();
        if (!(v instanceof JSONArray)) return out;
        JSONArray a = (JSONArray) v;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optString("url", "").length() > 0 && o.optJSONObject("manifest") != null) out.put(o);
        }
        return out;
    }

    static String print(String name, Object v) {
        switch (name) {
            case "musicAddons": {
                JSONArray a = addonList(v);
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < a.length(); i++) b.append(i > 0 ? "\n" : "").append(a.optJSONObject(i).optString("url"));
                return b.toString();
            }
            case "listenBrainz":
                return v instanceof JSONObject ? ((JSONObject) v).optString("token", "") : "";
            default:
                return v instanceof String ? ((String) v).trim() : "";
        }
    }

    static boolean empty(String name, Object v) {
        return print(name, v).isEmpty();
    }

    static void write(String name, Object v) {
        switch (name) {
            case "tmdbKey":
                codio.edit().putString("tmdb_key", v instanceof String ? ((String) v).trim() : "").apply();
                break;
            case "musicAddons": {
                JSONArray a = addonList(v);
                final List<Model.Addon> list = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    Model.Addon ad = new Model.Addon();
                    ad.url = a.optJSONObject(i).optString("url");
                    ad.manifest = a.optJSONObject(i).optJSONObject("manifest");
                    list.add(ad);
                }
                // Listeners repaint screens: on the main thread.
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        Addons.save(list);
                    }
                });
                // The list is already saved for the fingerprint read right after.
                saveAddonsNow(list);
                break;
            }
            case "listenBrainz":
                if (v instanceof JSONObject && ((JSONObject) v).optString("token", "").length() > 0) {
                    ListenBrainz.adopt(((JSONObject) v).optString("token"), ((JSONObject) v).optString("user"));
                } else {
                    ListenBrainz.signOut();
                }
                break;
            case "musicBrainzServer":
                MusicNet.setMbBase(v instanceof String ? (String) v : "");
                break;
        }
    }

    private static void saveAddonsNow(List<Model.Addon> list) {
        JSONArray a = new JSONArray();
        try {
            for (Model.Addon ad : list) a.put(new JSONObject().put("url", ad.url).put("manifest", ad.manifest));
        } catch (Exception ignored) {
        }
        MusicNet.prefs().edit().putString("codioMusicAddons", a.toString()).apply();
    }

    /** Joining an account for the first time: keep both where we can. */
    static Object join(String name, Object remote, Object local) {
        if ("musicAddons".equals(name)) {
            JSONArray out = addonList(remote);
            JSONArray l = addonList(local);
            for (int i = 0; i < l.length(); i++) {
                JSONObject a = l.optJSONObject(i);
                boolean have = false;
                for (int j = 0; j < out.length(); j++) if (out.optJSONObject(j).optString("url").equals(a.optString("url"))) have = true;
                if (!have) out.put(a);
            }
            return out;
        }
        return empty(name, remote) ? local : remote;
    }

    // ------------------------------------------------------------------ sync

    /** Called with every engine profile update: notes the account and syncs when needed. */
    public static void account(JSONObject profile) {
        JSONObject auth = profile != null ? profile.optJSONObject("auth") : null;
        String k = auth != null ? auth.optString("key", "") : "";
        JSONObject user = auth != null ? auth.optJSONObject("user") : null;
        String id = user != null ? user.optString("_id", user.optString("email", "")) : "";
        String before = authKey;
        if (k.isEmpty() || id.isEmpty()) {
            authKey = null;
            accountId = null;
            if (before != null) changed();
            return;
        }
        authKey = k;
        accountId = id;
        boolean fresh = !k.equals(before);
        if (fresh) changed();
        maybeSync(fresh);
    }

    private static JSONObject synced() {
        try {
            JSONObject all = new JSONObject(state.getString("synced", "{}"));
            return accountId != null ? all.optJSONObject(accountId) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void soon() {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                maybeSync(false);
            }
        }, 1500);
    }

    /** Cheap check, safe to call often: syncs when something changed here, or now and then for other devices' changes. */
    public static void maybeSync(final boolean force) {
        if (state == null || !on() || authKey == null || busy) return;
        WORK.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject before = synced();
                boolean changedHere = before == null;
                if (before != null) for (String n : NAMES) if (!print(n, read(n)).equals(before.optString(n, null))) changedHere = true;
                long last = lastSync();
                boolean stale = last == 0 || System.currentTimeMillis() - last > PULL_EVERY_MS || problem() == OFFLINE;
                if (!force && !changedHere && !stale) return;
                String key = state.getString("key", null), salt = state.getString("salt", null);
                if (key == null || salt == null) return;
                setBusy(true);
                try {
                    syncWith(unb64(key), salt, state.getInt("iter", ITERATIONS), false);
                } catch (Exception e) {
                    Log.w(TAG, "sync failed", e);
                    if (problem() != LOCKED) state.edit().putInt("problem", OFFLINE).apply();
                } finally {
                    setBusy(false);
                }
            }
        });
    }

    private static void setBusy(boolean b) {
        busy = b;
        changed();
    }

    /** Merges this device's values with the account's box and saves both. fresh = start the box over from this device. Run on WORK. */
    private static void syncWith(byte[] key, String salt, int iter, boolean fresh) throws Exception {
        String auth = authKey, id = accountId;
        if (auth == null || id == null) throw new IOException("Sign in first");
        JSONObject box = fresh ? null : readBox(auth);
        JSONObject remote = new JSONObject();
        if (box != null) {
            JSONObject opened = salt.equals(box.optString("salt")) ? open(key, box) : null;
            if (opened == null) {
                state.edit().putInt("problem", LOCKED).apply();
                changed();
                return;
            }
            remote = opened;
        }
        JSONObject before = fresh ? new JSONObject() : synced();
        if (before == null) before = new JSONObject();
        long now = System.currentTimeMillis();
        JSONObject result = new JSONObject(remote.toString());
        JSONObject prints = new JSONObject();
        boolean changedRemote = box == null;
        for (String n : NAMES) {
            Object local = read(n);
            JSONObject r = remote.optJSONObject(n);
            Object value;
            long at;
            Object rv = r != null ? r.opt("value") : null;
            if (r == null) {
                value = local;
                at = now;
            } else if (!before.has(n)) {
                value = join(n, rv, local);
                at = print(n, value).equals(print(n, rv)) ? r.optLong("at", now) : now;
            } else if (!print(n, local).equals(before.optString(n))) {
                value = local;
                at = now;
            } else {
                value = rv;
                at = r.optLong("at", now);
            }
            if (!print(n, value).equals(print(n, local))) write(n, value);
            if (r == null || !print(n, value).equals(print(n, rv))) changedRemote = true;
            result.put(n, new JSONObject().put("value", value == null ? JSONObject.NULL : value).put("at", at));
            prints.put(n, print(n, read(n)));
        }
        if (changedRemote) writeBox(auth, seal(key, salt, iter, result));
        JSONObject all;
        try {
            all = new JSONObject(state.getString("synced", "{}"));
        } catch (Exception e) {
            all = new JSONObject();
        }
        all.put(id, prints);
        state.edit().putString("synced", all.toString()).putLong("lastSync", System.currentTimeMillis()).putInt("problem", OK).apply();
        changed();
    }

    /** Result callback on the main thread: null = done, else a message to show. */
    public interface Done {
        void done(String error);
    }

    private interface Job {
        void run(String passcode) throws Exception;
    }

    private static void runJob(final String passcode, final Done done, final Job job) {
        final String p = normalise(passcode);
        if (p.length() < MIN_PASSCODE) {
            done.done("Use at least " + MIN_PASSCODE + " characters");
            return;
        }
        if (authKey == null) {
            done.done("Sign in first");
            return;
        }
        WORK.execute(new Runnable() {
            @Override
            public void run() {
                String error = null;
                setBusy(true);
                try {
                    job.run(p);
                } catch (Message m) {
                    error = m.getMessage();
                } catch (IOException e) {
                    error = "Couldn't reach your Stremio account. Check the connection and try again.";
                } catch (Exception e) {
                    Log.w(TAG, "passcode job failed", e);
                    error = "Something went wrong. Try again.";
                } finally {
                    setBusy(false);
                }
                final String err = error;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        done.done(err);
                    }
                });
            }
        });
    }

    private static final class Message extends Exception {
        Message(String m) {
            super(m);
        }
    }

    private static void saveKey(byte[] key, String salt, int iter, boolean clearSynced) {
        SharedPreferences.Editor e = state.edit().putString("key", b64(key)).putString("salt", salt).putInt("iter", iter).putInt("problem", OK);
        if (clearSynced) e.putString("synced", "{}");
        e.commit();
    }

    /** Turns sync on with this passcode. With a box already saved, the passcode must open it. */
    public static void setPasscode(String passcode, Done done) {
        runJob(passcode, done, new Job() {
            @Override
            public void run(String p) throws Exception {
                JSONObject box = readBox(authKey);
                byte[] salt;
                int iter = ITERATIONS;
                if (box != null) {
                    salt = unb64(box.getString("salt"));
                    iter = box.optInt("iter", ITERATIONS);
                } else {
                    salt = new byte[16];
                    RANDOM.nextBytes(salt);
                }
                byte[] key = deriveKey(p, salt, iter);
                if (box != null && open(key, box) == null) throw new Message("That's not the sync passcode. Try again, or start over with a new one.");
                saveKey(key, b64(salt), iter, false);
                syncWith(key, b64(salt), iter, false);
            }
        });
    }

    /** Locks the box with a new passcode; the other devices then ask for it. */
    public static void changePasscode(String passcode, Done done) {
        runJob(passcode, done, new Job() {
            @Override
            public void run(String p) throws Exception {
                String k = state.getString("key", null), s = state.getString("salt", null);
                if (k == null || s == null) throw new Message("Turn sync on first");
                syncWith(unb64(k), s, state.getInt("iter", ITERATIONS), false);
                if (problem() != OK) throw new Message("Enter the current sync passcode first");
                byte[] salt = new byte[16];
                RANDOM.nextBytes(salt);
                byte[] key = deriveKey(p, salt, ITERATIONS);
                saveKey(key, b64(salt), ITERATIONS, false);
                syncWith(key, b64(salt), ITERATIONS, true);
            }
        });
    }

    /** Forgot the passcode: a new box with a new passcode, from what this device has. */
    public static void startOver(String passcode, Done done) {
        runJob(passcode, done, new Job() {
            @Override
            public void run(String p) throws Exception {
                byte[] salt = new byte[16];
                RANDOM.nextBytes(salt);
                byte[] key = deriveKey(p, salt, ITERATIONS);
                saveKey(key, b64(salt), ITERATIONS, true);
                syncWith(key, b64(salt), ITERATIONS, true);
            }
        });
    }

    /** Stops syncing on this device. What's here stays here. */
    public static void turnOff() {
        state.edit().clear().apply();
        changed();
    }

    /** True while the vault is on: then the TMDB key no longer goes up unlocked (KeySync). */
    static boolean replacesKeySync() {
        return on();
    }
}
