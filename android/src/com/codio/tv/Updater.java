package com.codio.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Checks the Codio repository for a newer build of this app, downloads it and hands it to Android's installer.
 * The list of builds is updates.json at the repo root: {"androidtv": {...}, "phone": {...}}, each with
 * version, versionCode, url and notes.
 *
 * GitHub's raw file server caches a file for up to 5 minutes (a ?query doesn't get past it), so the check first asks
 * GitHub's API for the newest commit and then reads updates.json and the APK at that exact commit. Those addresses
 * never change, so they are never stale. If the API can't be reached, it falls back to the main branch.
 */
public final class Updater {
    static final String REPO = "cody103733/Codio";
    static final String SOURCE = "https://raw.githubusercontent.com/" + REPO + "/main/updates.json";
    static final String ACTION_STATUS = "com.codio.tv.INSTALL_STATUS";
    private static final String TAG = "CodioUpdate";
    private static final long CHECK_EVERY_MS = 6 * 3600 * 1000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean busy;
    private static volatile boolean cancelled;

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
        if (manual) toast(a, "Checking for updates…");
        prefs(a).edit().putLong("update_checked_at", System.currentTimeMillis()).apply();
        IO.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject entry = null;
                try {
                    String sha = newestCommit();
                    String list = sha != null ? SOURCE.replace("/main/", "/" + sha + "/") : SOURCE + "?t=" + System.currentTimeMillis();
                    JSONObject all = new JSONObject(new String(get(list), "UTF-8"));
                    entry = all.optJSONObject(BuildConfig.PHONE ? "phone" : "androidtv");
                    // Download from the same commit, so the APK matches the list.
                    if (entry != null && sha != null) {
                        String url = entry.optString("url", "");
                        String pinned = url.replace("/" + REPO + "/main/", "/" + REPO + "/" + sha + "/");
                        entry.put("url", pinned);
                        if (!pinned.equals(url)) entry.put("fallback_url", url);
                    }
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

    /** The newest commit on main, from GitHub's API (not cached for long), or null. */
    private static String newestCommit() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/commits/main").openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/vnd.github.sha");
            c.setRequestProperty("User-Agent", "Codio-updater");
            if (c.getResponseCode() != 200) {
                Log.w(TAG, "commit lookup HTTP " + c.getResponseCode());
                return null;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 4096) b.write(buf, 0, n);
            in.close();
            String sha = b.toString("UTF-8").trim();
            return sha.matches("[0-9a-f]{40}") ? sha : null;
        } catch (Exception e) {
            Log.w(TAG, "commit lookup failed: " + e);
            return null;
        }
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
                        download(a, u, code);
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

    /** The progress window: a bar, "12.3 of 40.0 MB (30%)", and Cancel. */
    private static final class Progress {
        final AlertDialog dialog;
        final ProgressBar bar;
        final TextView text;

        Progress(Activity a, String version) {
            int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, a.getResources().getDisplayMetrics());
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(pad, pad / 2, pad, 0);
            bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setIndeterminate(true);
            text = new TextView(a);
            text.setText("Connecting…");
            text.setPadding(0, pad / 3, 0, 0);
            box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
            box.addView(text, new LinearLayout.LayoutParams(-1, -2));
            dialog = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Downloading Codio " + version)
                    .setView(box)
                    .setCancelable(false)
                    .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            cancelled = true;
                        }
                    })
                    .show();
        }

        void update(final long done, final long total) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!dialog.isShowing()) return;
                    if (total > 0) {
                        bar.setIndeterminate(false);
                        bar.setProgress((int) (done * 1000 / total));
                        text.setText(String.format(Locale.US, "%.1f of %.1f MB (%d%%)", done / 1048576.0, total / 1048576.0, (int) (done * 100 / total)));
                    } else {
                        text.setText(String.format(Locale.US, "%.1f MB", done / 1048576.0));
                    }
                }
            });
        }

        void status(final String s) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!dialog.isShowing()) return;
                    bar.setIndeterminate(true);
                    text.setText(s);
                }
            });
        }

        void close() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        dialog.dismiss();
                    } catch (Exception e) { /* window already gone */ }
                }
            });
        }
    }

    private static void download(final Activity a, final JSONObject u, final int code) {
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
        cancelled = false;
        final Progress p = new Progress(a, u.optString("version", ""));
        IO.execute(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    File apk = fetch(a, u.optString("url"), u.optString("fallback_url", ""), code, p);
                    p.status("Handing it to Android's installer…");
                    install(a, apk);
                } catch (Cancelled c) {
                    error = "Update cancelled.";
                } catch (Problem e) {
                    Log.w(TAG, "update failed: " + e.getMessage());
                    error = e.getMessage();
                } catch (Exception e) {
                    Log.w(TAG, "update failed: " + e);
                    error = "The update didn't download (" + e.getClass().getSimpleName() + "). Check your internet and try again.";
                }
                p.close();
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

    private static final class Cancelled extends Exception {
    }

    /** A failure with a message the viewer can act on. */
    private static final class Problem extends Exception {
        Problem(String m) {
            super(m);
        }
    }

    /** Downloads the APK to the cache and checks it is this app, at the offered version or newer. */
    private static File fetch(Activity a, String url, String fallback, int code, Progress p) throws Exception {
        File dir = a.getCacheDir();
        File apk = new File(dir, "update.apk");
        apk.delete();
        HttpURLConnection c;
        try {
            c = open(url);
        } catch (Exception e) {
            if (fallback.isEmpty()) throw e;
            Log.w(TAG, "pinned download failed, using main: " + e);
            c = open(fallback);
        }
        long total = c.getContentLengthLong();
        if (total > 0 && dir.getUsableSpace() < total + (50L << 20)) {
            c.disconnect();
            throw new Problem("Not enough free space for the update. Free up about " + (total >> 20) + " MB and try again.");
        }
        InputStream in = c.getInputStream();
        OutputStream out = new FileOutputStream(apk);
        try {
            byte[] buf = new byte[1 << 16];
            long done = 0, shown = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled) throw new Cancelled();
                out.write(buf, 0, n);
                done += n;
                if (done - shown > (256 << 10)) {
                    shown = done;
                    p.update(done, total);
                }
            }
            p.update(done, total);
            if (total > 0 && done != total) throw new Problem("The download was cut off. Try again.");
        } catch (Exception e) {
            apk.delete();
            throw e;
        } finally {
            out.close();
            in.close();
            c.disconnect();
        }
        PackageInfo info = a.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
        if (info == null) {
            apk.delete();
            throw new Problem("The downloaded file isn't a working app. Try again in a few minutes.");
        }
        if (!a.getPackageName().equals(info.packageName)) {
            apk.delete();
            throw new Problem("The download is a different app (" + info.packageName + "), so it wasn't installed.");
        }
        if (info.versionCode < code) {
            apk.delete();
            throw new Problem("GitHub is still handing out the previous version. Try again in about 5 minutes.");
        }
        return apk;
    }

    /** Copies the APK into a PackageInstaller session and commits it; Android then asks to confirm. */
    private static void install(Activity a, File apk) throws Exception {
        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(a.getPackageName());
        params.setSize(apk.length());
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            InputStream in = new FileInputStream(apk);
            OutputStream out = s.openWrite("codio.apk", 0, apk.length());
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
        } finally {
            apk.delete();
        }
    }

    /** MainActivity.onNewIntent: the installer's answer. It needs the viewer's OK, or it failed. */
    static boolean handle(Activity a, Intent i) {
        if (i == null || !ACTION_STATUS.equals(i.getAction())) return false;
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                a.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                toast(a, "Choose Update (or Install) to finish. Codio restarts on its own.");
            }
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Log.e(TAG, "install status " + st + ": " + msg);
            toast(a, installError(st, msg));
        }
        return true;
    }

    private static String installError(int st, String msg) {
        switch (st) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "The update was cancelled. Choose Update now again to install it.";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "Not enough free space to install the update.";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "Android refused the update (" + (msg != null ? msg : "conflict") + ").";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "The device blocked the update. Allow Codio to install apps and try again.";
            default:
                return "The update wasn't installed" + (msg != null ? " (" + msg + ")." : ".");
        }
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
            if (code != 200) throw new Problem("The update server answered HTTP " + code + ". Try again later.");
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
