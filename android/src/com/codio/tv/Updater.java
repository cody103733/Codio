package com.codio.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Checks the Codio repository for a newer build of this app, downloads it and hands it to Android's installer.
 * The list of builds is updates.json at the repo root: {"androidtv": {...}, "phone": {...}}, each with
 * version, versionCode, url and notes.
 */
public final class Updater {
    static final String SOURCE = "https://raw.githubusercontent.com/cody103733/Codio/main/updates.json";
    static final String ACTION_STATUS = "com.codio.tv.INSTALL_STATUS";
    private static final String TAG = "CodioUpdate";
    private static final long CHECK_EVERY_MS = 6 * 3600 * 1000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean busy;

    private Updater() {}

    private static SharedPreferences prefs(Activity a) {
        return a.getSharedPreferences("codio", Activity.MODE_PRIVATE);
    }

    /** At start-up and on return to the app: checks at most every few hours, quietly. */
    static void maybeCheck(Activity a) {
        long last = prefs(a).getLong("update_checked_at", 0);
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return;
        check(a, false);
    }

    /** "Check for updates" in Settings: always checks, and says so when Codio is up to date. */
    public static void check(final Activity a, final boolean manual) {
        if (busy) return;
        busy = true;
        prefs(a).edit().putLong("update_checked_at", System.currentTimeMillis()).apply();
        IO.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject entry = null;
                try {
                    JSONObject all = new JSONObject(new String(get(SOURCE + "?t=" + System.currentTimeMillis()), "UTF-8"));
                    entry = all.optJSONObject(BuildConfig.PHONE ? "phone" : "androidtv");
                } catch (Exception e) {
                    Log.w(TAG, "check failed: " + e);
                }
                final JSONObject found = entry;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        if (a.isFinishing()) return;
                        if (found == null) {
                            if (manual) toast(a, "Couldn't check for updates. Check your internet and try again.");
                            return;
                        }
                        int code = found.optInt("versionCode", 0);
                        if (code <= currentCode(a)) {
                            if (manual) toast(a, "Codio " + BuildConfig.VERSION + " is the newest version.");
                            return;
                        }
                        if (!manual && code == prefs(a).getInt("update_skipped", 0)) return;
                        offer(a, found, code);
                    }
                });
            }
        });
    }

    private static int currentCode(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void offer(final Activity a, final JSONObject u, final int code) {
        String ver = u.optString("version", "");
        String notes = u.optString("notes", "");
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Codio " + ver + " is available")
                .setMessage((notes.isEmpty() ? "" : notes + "\n\n") + "You have " + BuildConfig.VERSION + ". Your sign-in and settings stay.")
                .setPositiveButton("Update now", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        download(a, u.optString("url"));
                    }
                })
                .setNegativeButton("Later", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs(a).edit().putInt("update_skipped", code).apply();
                    }
                })
                .show();
    }

    private static void download(final Activity a, final String url) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            toast(a, "Allow Codio to install apps, then press Back and choose Update now again.");
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception e) {
                toast(a, "Turn on \"Install unknown apps\" for Codio in the device's settings, then try again.");
            }
            prefs(a).edit().remove("update_checked_at").apply();
            return;
        }
        if (busy) return;
        busy = true;
        toast(a, "Downloading the update…");
        IO.execute(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    install(a, url);
                } catch (Exception e) {
                    Log.w(TAG, "update failed: " + e);
                    error = "The update didn't download. Try again later.";
                }
                final String err = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        if (err != null) toast(a, err);
                    }
                });
            }
        });
    }

    /** Streams the APK into a PackageInstaller session and commits it; Android then asks to confirm. */
    private static void install(Activity a, String url) throws Exception {
        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(a.getPackageName());
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            HttpURLConnection c = open(url);
            InputStream in = c.getInputStream();
            OutputStream out = s.openWrite("codio.apk", 0, c.getContentLength());
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            s.fsync(out);
            out.close();
            in.close();
            Intent status = new Intent(a, a.getClass()).setAction(ACTION_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0);
            s.commit(PendingIntent.getActivity(a, id, status, flags).getIntentSender());
        } catch (Exception e) {
            s.abandon();
            throw e;
        }
    }

    /** MainActivity.onNewIntent: the installer's answer. It needs the viewer's OK, or it failed. */
    static boolean handle(Activity a, Intent i) {
        if (i == null || !ACTION_STATUS.equals(i.getAction())) return false;
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) a.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            Log.w(TAG, "install status " + st + ": " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            toast(a, "The update wasn't installed.");
        }
        return true;
    }

    private static HttpURLConnection open(String url) throws Exception {
        for (int hops = 0; hops < 5; hops++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                continue;
            }
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            return c;
        }
        throw new java.io.IOException("too many redirects");
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = open(url);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    private static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_LONG).show();
    }
}
