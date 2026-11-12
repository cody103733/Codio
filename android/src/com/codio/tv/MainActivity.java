package com.codio.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity implements SurfaceHolder.Callback, MpvShell.Sink, com.codio.tv.ui.PhoneUi.MiniPlayerHost {
    private static final String TAG = "Codio";
    private LocalServer server;
    private String origin;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private SurfaceView video;
    private SurfaceView subtitles;
    private SurfaceView dvVideo;
    private android.widget.TextView dvSubs;
    private PlayerShell mpv;
    private long lastBackAt = 0;
    private String shim;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.codio.tv.ui.CastInfo.init(getSharedPreferences("codio", MODE_PRIVATE));
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (!BuildConfig.PHONE) getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setBackgroundDrawableResource(android.R.color.black);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new SurfaceView(this);
        video.getHolder().addCallback(this);
        root.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // VLC draws embedded subtitles on their own transparent surface, just above the video.
        subtitles = new SurfaceView(this);
        root.addView(subtitles, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Dolby Vision sources play on their own surface with Android's player (DvShell); hidden otherwise.
        dvVideo = new SurfaceView(this);
        dvVideo.setVisibility(View.GONE);
        root.addView(dvVideo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER));
        dvSubs = new android.widget.TextView(this);
        dvSubs.setVisibility(View.GONE);
        dvSubs.setTextColor(Color.WHITE);
        dvSubs.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, getResources().getDisplayMetrics().heightPixels / 22f);
        dvSubs.setShadowLayer(6, 0, 2, Color.BLACK);
        dvSubs.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        dvSubs.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        FrameLayout.LayoutParams subLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM);
        subLp.bottomMargin = getResources().getDisplayMetrics().heightPixels / 12;
        root.addView(dvSubs, subLp);

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Native browse screens on top (Home, Discover, Search, movie pages); the web UI keeps the player and settings.
        android.content.SharedPreferences uiPrefs = getSharedPreferences("codio", MODE_PRIVATE);
        applyTestSwitches(uiPrefs);
        nativeUi = !"web".equals(uiPrefs.getString("ui", "native"));
        try {
            android.net.http.HttpResponseCache.install(new File(getCacheDir(), "http"), 150L * 1024 * 1024);
        } catch (IOException e) {
            Log.w(TAG, "no http cache", e);
        }
        if (nativeUi) {
            store = new com.codio.tv.ui.Store(getFilesDir());
            tvUi = BuildConfig.PHONE ? new com.codio.tv.ui.PhoneUi(this, store, new NativeHost())
                    : new com.codio.tv.ui.TvView(this, store, new NativeHost());
            tvUi.showHome();
            root.addView(tvUi.view(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            web.setVisibility(View.INVISIBLE);
            store.ensureDefaults(new Runnable() {
                @Override
                public void run() {
                    if (tvUi != null) tvUi.dataChanged(true);
                }
            });
        }
        setContentView(root);
        if (!BuildConfig.PHONE) hideSystemUi();

        WebView.setWebContentsDebuggingEnabled(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setSupportMultipleWindows(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        // Lay the page out as a 1280x720 screen whatever the TV's resolution and density: Fire TV reports a
        // 960x540 page (which gets the phone layout); this gives the full TV layout with text sized for the sofa.
        android.content.SharedPreferences prefs = getSharedPreferences("codio", MODE_PRIVATE);
        applyTestSwitches(prefs);
        String layout = prefs.getString("layout", "scale");
        // "viewport" falls back to 980px: the web UI blanks its viewport meta on desktop.
        scaleLayout = !"viewport".equals(layout);
        // "half": Fire TV's WebView rasterises at 4x density for a 1920x1080 window, i.e. 3840px wide for a 1080p
        // surface. Laying out at twice the width and zooming the page 2x gives the same look with a quarter of the pixels.
        halfRaster = "half".equals(layout);
        if (scaleLayout) {
            int widthPx = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
            web.setInitialScale(Math.max(1, Math.round(widthPx * 100f / LAYOUT_WIDTH / (halfRaster ? 2 : 1))));
            s.setLoadWithOverviewMode(false);
            s.setUseWideViewPort(false);
        } else {
            // A 1280px-wide viewport (set in index.html below) that the WebView fits to the screen.
            s.setLoadWithOverviewMode(true);
            s.setUseWideViewPort(true);
        }
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        s.setUserAgentString(tvUserAgent(s.getUserAgentString()));

        mpv = createPlayer();
        Log.i(TAG, "player " + (mpv != null ? mpv.engine() + " ready" : "unavailable, using the built-in HTML5 player"));

        shim = buildShim();
        web.addJavascriptInterface(new Bridge(), "CodioNative");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d("CodioWeb", m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
        startServerAndLoad();
        if (tvUi != null) tvUi.view().requestFocus();
        else web.requestFocus();
        checkWebViewVersion();
    }

    /**
     * VLC by default (smooth hardware playback); mpv if VLC can't start. For testing, the engine can be switched with
     * adb shell am start -n com.codio.tv/.MainActivity --es player mpv   (or vlc); the choice is remembered.
     */
    private PlayerShell createPlayer() {
        android.content.SharedPreferences prefs = getSharedPreferences("codio", MODE_PRIVATE);
        String asked = getIntent() != null ? getIntent().getStringExtra("player") : null;
        if ("vlc".equals(asked) || "mpv".equals(asked)) prefs.edit().putString("player", asked).apply();
        applyTestSwitches(prefs);
        String engine = prefs.getString("player", "vlc");
        PlayerShell p = null;
        if (!"mpv".equals(engine)) p = VlcShell.create(this, this, video, subtitles, true, prefs);
        if (p == null) {
            p = MpvShell.create(this, this, prepareDir("mpv"), copyAsset("cacert.pem", "cacert.pem"), prepareFonts(), true);
        }
        if (p == null || !"vlc".equals(p.engine())) {
            subtitles.setVisibility(View.GONE);
        }
        if (p != null && "vlc".equals(p.engine()) && !BuildConfig.PHONE) {
            final android.content.SharedPreferences dvPrefs = prefs;
            DvShell dv = new DvShell(this, null, dvVideo, dvSubs, video, subtitles, getCacheDir());
            final HybridShell h = new HybridShell(p, dv, new HybridShell.Policy() {
                @Override
                public boolean wantsDv(String url) {
                    if ("off".equals(dvPrefs.getString("dolby_vision", "on"))) return false;
                    if (!DvShell.deviceSupports(MainActivity.this)) return false;
                    com.codio.tv.ui.Store st = store;
                    com.codio.tv.ui.Store.Sources list = st != null ? st.sources : null;
                    if (list == null || url == null) return false;
                    for (com.codio.tv.ui.Store.Source src : list.list) {
                        if (url.equals(src.url)) return "Dolby Vision".equals(src.videoTag());
                    }
                    return false;
                }
            });
            dv.setListener(h);
            return h;
        }
        return p;
    }

    /**
     * Playback test switches, remembered until changed (force-stop first, the activity is singleTask):
     *   adb shell am start -n com.codio.tv/.MainActivity --es vlc_opts "--aout=opensles" --es vlc_media_opts ":network-caching=6000"
     *       --es vlc_direct true --es vlc_audio light|full|auto --es match_rate false     (--ez reset_tests true clears them)
     */
    private void applyTestSwitches(android.content.SharedPreferences prefs) {
        Intent in = getIntent();
        if (in == null) return;
        android.content.SharedPreferences.Editor e = prefs.edit();
        String[] keys = {"vlc_opts", "vlc_media_opts", "vlc_direct", "vlc_audio", "match_rate", "layout", "ui"};
        if (in.getBooleanExtra("reset_tests", false)) for (String k : keys) e.remove(k);
        for (String k : keys) {
            String v = in.getStringExtra(k);
            if (v != null) e.putString(k, v);
        }
        e.apply();
    }

    /** Codio's UI needs a reasonably recent Android System WebView (Chrome 80+); old TV boxes often ship an outdated one. */
    private static final int LAYOUT_WIDTH = 1280;
    private boolean scaleLayout = false;
    private boolean halfRaster = false;

    private void checkWebViewVersion() {
        int major = 0;
        try {
            Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(WebSettings.getDefaultUserAgent(this));
            if (m.find()) major = Integer.parseInt(m.group(1));
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "WebView Chrome version " + major);
        if (major == 0 || major >= 80) return;
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Update Android System WebView")
                .setMessage("This TV box has an old version of Android System WebView (" + major + "). Codio needs version 80 or newer, "
                        + "otherwise it may show a blank screen.\n\nUpdate \"Android System WebView\" (or \"Chrome\") from the Play Store, then reopen Codio.")
                .setPositiveButton("Open Play Store", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        openExternal(Uri.parse("market://details?id=com.google.android.webview"));
                    }
                })
                .setNegativeButton("Continue anyway", null)
                .show();
    }

    /** Desktop-style user agent: the Codio UI then uses its big-screen layout instead of the phone one. */
    private static String tvUserAgent(String original) {
        String chrome = "120.0.0.0";
        Matcher m = Pattern.compile("Chrome/([0-9.]+)").matcher(original != null ? original : "");
        if (m.find()) chrome = m.group(1);
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chrome
                + " Safari/537.36 CodioTV/" + BuildConfig.VERSION;
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && (!BuildConfig.PHONE || (tvUi instanceof com.codio.tv.ui.PhoneUi && ((com.codio.tv.ui.PhoneUi) tvUi).playerUp()))) hideSystemUi();
    }

    // ---------------------------------------------------------------- phone: mini player (picture-in-picture)

    private int videoW = 16, videoH = 9;

    private boolean phonePlaying() {
        return BuildConfig.PHONE && tvUi instanceof com.codio.tv.ui.PhoneUi && ((com.codio.tv.ui.PhoneUi) tvUi).playerUp();
    }

    private android.app.PictureInPictureParams pipParams() {
        float r = videoH > 0 ? (float) videoW / videoH : 16f / 9f;
        r = Math.max(1f / 2.3f, Math.min(2.3f, r));
        int w = Math.round(r * 1000);
        android.app.PictureInPictureParams.Builder b = new android.app.PictureInPictureParams.Builder()
                .setAspectRatio(new android.util.Rational(w, 1000));
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // Android 12+: swiping home while a video plays shrinks it into the mini player smoothly.
            b.setAutoEnterEnabled(phonePlaying());
            b.setSeamlessResizeEnabled(false);
        }
        return b.build();
    }

    /** Called by the phone UI when the player opens or closes, and when the video size is known. */
    @Override
    public void updatePipParams() {
        if (!BuildConfig.PHONE) return;
        try {
            setPictureInPictureParams(pipParams());
        } catch (Throwable t) {
            Log.w(TAG, "mini player unavailable", t);
        }
    }

    private boolean pipBlocked = false, pipAsked = false;

    /** Android lets people switch picture-in-picture off per app (Settings > Apps > Special access). */
    private boolean pipAllowed() {
        try {
            android.app.AppOpsManager ops = (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
            if (ops == null) return true;
            int mode = android.os.Build.VERSION.SDK_INT >= 29
                    ? ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), getPackageName())
                    : ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED || mode == android.app.AppOpsManager.MODE_DEFAULT;
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!phonePlaying() || isInPictureInPictureMode()) return;
        // Asked for on every Android version: some phones don't do the automatic switch on their own.
        if (!pipAllowed()) {
            pipBlocked = true;
            Log.w(TAG, "mini player is switched off for Codio in the phone's settings");
            return;
        }
        try {
            if (!enterPictureInPictureMode(pipParams())) pipBlocked = true;
        } catch (Throwable t) {
            pipBlocked = true;
            Log.w(TAG, "mini player unavailable", t);
        }
    }

    /** Back in Codio after the mini player couldn't open: offer the setting once. */
    private void explainPipBlocked() {
        if (!pipBlocked || pipAsked || !BuildConfig.PHONE) return;
        pipBlocked = false;
        if (pipAllowed()) return;
        pipAsked = true;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Mini player is off")
                .setMessage("Your phone has picture-in-picture switched off for Codio, so the video stops when you leave the app. Turn on \"Allow picture-in-picture\" for Codio to keep watching in a small window.")
                .setPositiveButton("Open settings", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        Uri pkg = Uri.parse("package:" + getPackageName());
                        try {
                            startActivity(new Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", pkg));
                        } catch (Throwable t) {
                            try {
                                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg));
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, android.content.res.Configuration c) {
        super.onPictureInPictureModeChanged(inPip, c);
        Log.i(TAG, "mini player " + (inPip ? "on" : "off"));
        if (tvUi instanceof com.codio.tv.ui.PhoneUi) ((com.codio.tv.ui.PhoneUi) tvUi).setMiniPlayer(inPip);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (main != null) main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) Updater.maybeCheck(MainActivity.this);
            }
        }, 4000);
        if (BuildConfig.PHONE && main != null) main.postDelayed(new Runnable() {
            @Override
            public void run() {
                explainPipBlocked();
            }
        }, 700);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving Codio (Home button, screensaver): pause the video so it doesn't keep playing unseen.
        if (mpv != null) mpv.pause();
        if (web != null) web.evaluateJavascript("window.__codioOnHidden && window.__codioOnHidden()", null);
        restoreDisplay.run();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Updater.handle(this, intent);
    }

    @Override
    protected void onDestroy() {
        if (mpv != null) {
            mpv.destroy();
            mpv = null;
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- remote control keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (nativeShown()) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) tvUi.back();
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN && !tvUi.view().hasFocus()) tvUi.view().requestFocus();
            return super.dispatchKeyEvent(event);
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) onBack();
            return true;
        }
        String media = mediaAction(code);
        if (media != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                js("window.__codioMediaKey && window.__codioMediaKey(" + JSONObject.quote(media) + ")");
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static String mediaAction(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return "play-pause";
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return "play";
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return "pause";
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return "stop";
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return "next-track";
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return "seek-forward";
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return "seek-backward";
            default:
                return null;
        }
    }

    private void onBack() {
        if (web == null) {
            finish();
            return;
        }
        // The page decides (close a menu, leave the player, go back); on the home screen it calls requestExit().
        web.evaluateJavascript("window.__codioBack ? window.__codioBack() : CodioNative.requestExit()", null);
    }

    private void requestExit() {
        if (tvUi != null && webShown) {
            // Back on a web page opened from the native screens (Settings, Library, ...) returns to them.
            showNative(null);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 2500) {
            finish();
        } else {
            lastBackAt = now;
            Toast.makeText(this, "Press Back again to exit Codio", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- video surface

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (mpv != null) mpv.attachSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mpv != null) mpv.surfaceSize(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mpv != null) mpv.detachSurface();
    }

    // ---------------------------------------------------------------- shell IPC

    @Override
    public void send(final String message) {
        // The video is running (its clock moves): take the loading screen away even if no size/playing signal came.
        if (playOverlay && message.contains("\"time-pos\"") && !message.contains("\"data\":0,") && !message.contains("\"data\":0}")) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (playOverlay) main.postDelayed(revealPlayer, 100);
                }
            });
        }
        // Codio's own player controls follow the player too.
        if (tvUi != null && (message.contains("mpv-prop-change") || message.contains("mpv-event-"))) {
            try {
                JSONArray a = new JSONObject(message).optJSONArray("args");
                if (a != null && a.length() > 1) {
                    final String sig = a.optString(0);
                    final Object arg = a.opt(1);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (tvUi != null) tvUi.playerEvent(sig, arg);
                        }
                    });
                }
            } catch (Exception ignored) {
            }
        }
        js("window.__codioShellMessage && window.__codioShellMessage(" + JSONObject.quote(message) + ")");
    }

    @Override
    public void onVideoSize(final int width, final int height) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (width > 0 && height > 0) {
                    videoW = width;
                    videoH = height;
                    updatePipParams();
                }
                applyVideoSize(width, height);
                if (width > 0 && height > 0 && playOverlay) main.postDelayed(revealPlayer, 300);
            }
        });
    }

    // ---------------------------------------------------------------- 4K output
    //
    // By default the video surface is only as big as the app's window, which Android TV boxes lay out at 1920x1080
    // even when the TV runs at 4K. For a 4K video, render at the TV's real 4K resolution (switching the HDMI
    // output to 4K if the TV supports it and the box is running at 1080p), and put things back afterwards.

    private boolean displaySwitched = false;
    private boolean surfaceUhd = false;

    private final Runnable restoreDisplay = new Runnable() {
        @Override
        public void run() {
            main.removeCallbacks(this);
            if (surfaceUhd && video != null) {
                video.getHolder().setSizeFromLayout();
                surfaceUhd = false;
            }
            if (displaySwitched) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.preferredDisplayModeId = 0; // the system's own choice again
                getWindow().setAttributes(lp);
                displaySwitched = false;
                Log.i(TAG, "display mode restored");
            }
        }
    };

    private boolean modesLogged = false;

    private void applyVideoSize(int w, int h) {
        main.removeCallbacks(restoreDisplay);
        if (w == 0 && h == 0) {
            // 0x0 happens between sources too (auto-play trying the next one), so wait before switching back.
            main.postDelayed(restoreDisplay, 5000);
            return;
        }
        boolean uhd = w > 2560 || h > 1440;
        double fps = VlcShell.videoFps;
        boolean matchRate = !"false".equals(getSharedPreferences("codio", MODE_PRIVATE).getString("match_rate", "true"));
        try {
            android.view.Display d = getWindowManager().getDefaultDisplay();
            android.view.Display.Mode cur = d.getMode();
            android.view.Display.Mode[] modes = d.getSupportedModes();
            if (!modesLogged) {
                modesLogged = true;
                StringBuilder sb = new StringBuilder("display modes:");
                for (android.view.Display.Mode m : modes) {
                    sb.append(' ').append(m.getPhysicalWidth()).append('x').append(m.getPhysicalHeight()).append('@').append(m.getRefreshRate());
                }
                Log.i(TAG, sb.toString());
            }
            // Resolution: 4K for a 4K video (if the TV has it), otherwise whatever the box is running at.
            int wantH = uhd && cur.getPhysicalHeight() < 2160 && tvSupports4k() ? 2160 : cur.getPhysicalHeight();
            android.view.Display.Mode target = null;
            // Refresh rate: a 23.976 fps movie on a 60 Hz screen can't show every frame for the same time, which
            // shows up as a small hitch every few seconds. Use a 24/48 Hz (etc.) mode when the TV offers one.
            if (matchRate && fps > 1) {
                int bestMult = Integer.MAX_VALUE;
                for (android.view.Display.Mode m : modes) {
                    if (m.getPhysicalHeight() != wantH && !(wantH >= 2160 && m.getPhysicalHeight() >= 2160)) continue;
                    double ratio = m.getRefreshRate() / fps;
                    long mult = Math.round(ratio);
                    if (mult < 1 || Math.abs(ratio - mult) > 0.004 * mult) continue;
                    if (mult < bestMult || (mult == bestMult && target != null && betterMode(m, target, cur.getRefreshRate()))) {
                        bestMult = (int) mult;
                        target = m;
                    }
                }
            }
            if (target == null && wantH != cur.getPhysicalHeight()) {
                for (android.view.Display.Mode m : modes) {
                    if (m.getPhysicalHeight() < 2160) continue;
                    if (target == null || betterMode(m, target, cur.getRefreshRate())) target = m;
                }
            }
            if (target == null) target = cur;
            if (target.getModeId() != cur.getModeId()) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.preferredDisplayModeId = target.getModeId();
                getWindow().setAttributes(lp);
                displaySwitched = true;
                Log.i(TAG, "switching display to " + target.getPhysicalWidth() + "x" + target.getPhysicalHeight()
                        + "@" + target.getRefreshRate() + " for " + w + "x" + h + "@" + fps + " video");
            } else {
                Log.i(TAG, "display stays " + cur.getPhysicalWidth() + "x" + cur.getPhysicalHeight() + "@" + cur.getRefreshRate()
                        + " for " + w + "x" + h + "@" + fps + " video");
            }
            // mpv draws into the surface with the GPU, so its buffer must be 4K; VLC's decoder frames go to the screen as they are.
            if (uhd && target.getPhysicalHeight() >= 2160 && video != null && mpv != null && "mpv".equals(mpv.engine())) {
                video.getHolder().setFixedSize(target.getPhysicalWidth(), target.getPhysicalHeight());
                surfaceUhd = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "display mode setup failed", t);
        }
    }

    private boolean tvSupports4k() {
        try {
            for (android.view.Display.Mode m : getWindowManager().getDefaultDisplay().getSupportedModes()) {
                if (m.getPhysicalHeight() >= 2160) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Prefer the current refresh rate (no judder change), then a 16:9 3840-wide mode. */
    private static boolean betterMode(android.view.Display.Mode a, android.view.Display.Mode b, float rate) {
        float da = Math.abs(a.getRefreshRate() - rate), db = Math.abs(b.getRefreshRate() - rate);
        if (Math.abs(da - db) > 0.5f) return da < db;
        boolean a16x9 = a.getPhysicalWidth() == 3840, b16x9 = b.getPhysicalWidth() == 3840;
        if (a16x9 != b16x9) return a16x9;
        return a.getPhysicalWidth() * a.getPhysicalHeight() > b.getPhysicalWidth() * b.getPhysicalHeight();
    }

    @Override
    public void onPlayingChanged(final boolean playing) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (playing) {
                    if (playOverlay) main.postDelayed(revealPlayer, 1200);
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            }
        });
    }

    private void js(final String script) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(script, null);
            }
        });
    }

    private void onShellMessage(String raw) {
        try {
            JSONObject msg = new JSONObject(raw);
            int type = msg.optInt("type");
            if (type == ShellProtocol.TYPE_INIT) {
                send(ShellProtocol.handshake("5.0.0-codio-android-" + BuildConfig.VERSION, mpv != null));
                sendVisibility();
                return;
            }
            if (type != ShellProtocol.TYPE_INVOKE) return;
            JSONArray args = msg.optJSONArray("args");
            if (args == null || args.length() == 0) return;
            String method = args.optString(0);
            Object params = args.length() > 1 ? args.opt(1) : null;
            if (tvUi != null && !webShown && "mpv-command".equals(method) && params instanceof JSONArray
                    && "loadfile".equals(((JSONArray) params).optString(0))) {
                // A stream starting while the native screens are up: the viewer backed out before it began.
                Log.i(TAG, "ignored a stream start behind the native screens");
                js("window.__codioNativePark && window.__codioNativePark()");
                return;
            }
            if ("mpv-command".equals(method) && params instanceof JSONArray && "loadfile".equals(((JSONArray) params).optString(0))) {
                com.codio.tv.ui.PlayerState.playingUrl = ((JSONArray) params).optString(1, null);
            }
            if (mpv != null && mpv.handle(method, params)) return;
            switch (method) {
                case "quit":
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            finish();
                        }
                    });
                    break;
                case "win-set-visibility":
                    sendVisibility();
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "bad shell message " + raw, t);
        }
    }

    private void sendVisibility() throws Exception {
        JSONObject v = new JSONObject();
        v.put("visible", true);
        v.put("visibility", 1);
        // Always on a TV; reported as "not fullscreen" so the web UI never spends a Back press leaving fullscreen.
        v.put("isFullscreen", false);
        send(ShellProtocol.signal("win-visibility-changed", v));
    }

    private final class Bridge {
        @JavascriptInterface
        public void postMessage(String message) {
            onShellMessage(message);
        }

        @JavascriptInterface
        public void streams(final String json) {
            if (store == null) return;
            store.updateSources(json, new com.codio.tv.ui.Store.Callback<com.codio.tv.ui.Store.Sources>() {
                @Override
                public void done(com.codio.tv.ui.Store.Sources s, Exception error) {
                    if (tvUi == null) return;
                    Log.i(TAG, "sources " + s.key + ": " + s.list.size() + (s.loading ? " (loading)" : "") + (s.best() != null ? ", best " + s.best().quality : ""));
                    tvUi.dataChanged(false);
                    if (!s.loading) warmTopSource(s);
                }
            });
        }

        @JavascriptInterface
        public void userPick() {
            VlcShell.userPickAt = android.os.SystemClock.uptimeMillis();
        }

        @JavascriptInterface
        public void coreEvent(final String name, final String message) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (tvUi != null && name != null) tvUi.coreEvent(name, message);
                    if (name != null && !name.startsWith("Error:")) {
                        main.removeCallbacks(readStorage);
                        main.postDelayed(readStorage, 800);
                    }
                }
            });
        }

        @JavascriptInterface
        public void playStatus(final String kind, final String text) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    onPlayStatus(kind, text);
                }
            });
        }

        @JavascriptInterface
        public void route(final String hash) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    onWebRoute(hash);
                }
            });
        }

        @JavascriptInterface
        public void requestExit() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.requestExit();
                }
            });
        }

        @JavascriptInterface
        public void exitApp() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }

        @JavascriptInterface
        public void showKeyboard() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (web == null) return;
                    web.requestFocus();
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(web, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }

        @JavascriptInterface
        public String info() {
            return "{\"version\":" + JSONObject.quote(BuildConfig.VERSION) + ",\"mpv\":" + (mpv != null)
                    + ",\"engine\":" + JSONObject.quote(mpv != null ? mpv.engine() : "html5")
                    + ",\"hwdec\":" + JSONObject.quote(mpv != null ? mpv.hwdec() : "") + "}";
        }
    }

    // ---------------------------------------------------------------- bundled web UI

    private void startServerAndLoad() {
        server = new LocalServer(getAssets(), new LocalServer.IndexFilter() {
            @Override
            public String filter(String html) {
                if (!scaleLayout) {
                    html = html.replaceFirst("<meta name=\"viewport\"[^>]*>",
                            "<meta name=\"viewport\" content=\"width=" + LAYOUT_WIDTH + ",user-scalable=no,viewport-fit=cover\">");
                }
                String extra = halfRaster ? "<style>html{zoom:2}</style>" : "";
                return html.replaceFirst("<head>", "<head>" + Matcher.quoteReplacement(extra + shim));
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String o = server.start();
                    MpvShell.streamProxy = o;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            origin = o;
                            if (web != null) web.loadUrl(o + (tvUi != null ? "/#/codio-native" : "/"));
                        }
                    });
                } catch (final IOException e) {
                    Log.e(TAG, "could not start the local server", e);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "Codio could not start: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "codio-start").start();
    }

    private boolean isLocal(Uri url) {
        return origin != null && "127.0.0.1".equals(url.getHost());
    }

    private final class Client extends WebViewClient {
        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String link) {
            Uri url = Uri.parse(link);
            if (isLocal(url)) return false;
            openExternal(url);
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (tvUi == null || webShown) view.requestFocus();
            if (tvUi != null) {
                readWebStorage();
                main.postDelayed(pauseWebIfHidden, 8000);
            }
        }
    }

    private void openExternal(Uri url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, url);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app on this TV can open " + url.getHost(), Toast.LENGTH_LONG).show();
        }
    }

    private String buildShim() {
        String js;
        try {
            js = new String(LocalServer.readAll(getAssets().open("codio-android.js")), StandardCharsets.UTF_8);
        } catch (IOException e) {
            js = "";
        }
        return "<script>window.__codioNativeUi=" + nativeUi + ";window.__codioHasMpv=" + (mpv != null) + ";window.__codioTv4k=" + tvSupports4k() + ";</script><script>" + js + "</script>";
    }

    // ---------------------------------------------------------------- files for mpv

    private String prepareDir(String name) {
        File dir = new File(getFilesDir(), name);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir.getAbsolutePath();
    }

    private String copyAsset(String asset, String target) {
        File out = new File(getFilesDir(), target);
        try {
            byte[] data = LocalServer.readAll(getAssets().open(asset));
            if (!out.exists() || out.length() != data.length) {
                OutputStream os = new FileOutputStream(out);
                os.write(data);
                os.close();
            }
            return out.getAbsolutePath();
        } catch (IOException e) {
            Log.w(TAG, "could not copy " + asset, e);
            return null;
        }
    }

    private String prepareFonts() {
        String dir = prepareDir("fonts");
        String font = copyAsset("subfont.ttf", "fonts/subfont.ttf");
        return font != null ? dir : null;
    }


    // ---------------------------------------------------------------- native browse screens <-> web UI

    private boolean nativeUi = false;
    private com.codio.tv.ui.Store store;
    private com.codio.tv.ui.NativeUi tvUi;
    /** A web page (player, source list, settings) is on screen instead of the native screens. */
    private boolean webShown = false;
    private boolean sawPlayer = false;
    private boolean webPaused = false;

    /** The native screens have the remote (also while the loading screen covers the web page). */
    private boolean nativeShown() {
        return tvUi != null && tvUi.view().getVisibility() == View.VISIBLE;
    }

    private final class NativeHost implements com.codio.tv.ui.TvView.Host {
        @Override
        public void play(com.codio.tv.ui.Store.Meta m, String videoId) {
            // Codio's own "finding your stream" screen stays up while the web page searches and the player starts.
            tvUi.showLoading(m, videoId != null ? videoId : m.id);
            playOverlay = true;
            playStartedAt = System.currentTimeMillis();
            VlcShell.playPressedAt = android.os.SystemClock.uptimeMillis();
            playMeta = m;
            playVideo = videoId != null ? videoId : m.id;
            showWeb(detailRoute(m, videoId) + "?autoplay=1", true);
        }

        @Override
        public void cancelPlay() {
            Log.i(TAG, "play cancelled on the loading screen");
            playOverlay = false;
            showNative(null);
        }

        @Override
        public android.content.SharedPreferences prefs() {
            return getSharedPreferences("codio", MODE_PRIVATE);
        }

        @Override
        public void saveAutoPlay(String json) {
            if (web != null) web.evaluateJavascript("window.__codioNativeSetAutoPlay && window.__codioNativeSetAutoPlay(" + JSONObject.quote(json) + ")", null);
        }

        @Override
        public void audioSyncChanged() {
        }

        @Override
        public String version() {
            return BuildConfig.VERSION;
        }

        @Override
        public void checkUpdates() {
            Updater.check(MainActivity.this, true);
        }

        @Override
        public void chooseSource(com.codio.tv.ui.Store.Meta m, String videoId) {
            tvUi.openSources(m, videoId);
        }

        @Override
        public void prefetch(com.codio.tv.ui.Store.Meta m, String videoId) {
            if (web == null || webShown) return;
            String route = detailRoute(m, videoId);
            if (route.equals(prefetchRoute)) return;
            prefetchRoute = route;
            Log.i(TAG, "loading sources early for " + route);
            main.removeCallbacks(pauseWebIfHidden);
            resumeWeb();
            // ?prefetch=1: the page only loads the sources, nothing starts.
            web.evaluateJavascript("window.__codioNativeGo && window.__codioNativeGo(" + JSONObject.quote(route + "?prefetch=1") + ")", null);
        }

        @Override
        public void prefetchEnd() {
            if (prefetchRoute == null || webShown) return;
            prefetchRoute = null;
            web.evaluateJavascript("window.__codioNativePark && window.__codioNativePark()", null);
            main.removeCallbacks(pauseWebIfHidden);
            main.postDelayed(pauseWebIfHidden, 8000);
        }

        @Override
        public void playSource(com.codio.tv.ui.Store.Meta m, String videoId, com.codio.tv.ui.Store.Source source) {
            tvUi.showLoading(m, videoId != null ? videoId : m.id);
            tvUi.loadingStatus("starting", "Starting" + (source.quality != null ? " in " + ("2160p".equals(source.quality) ? "4K" : source.quality) : "") + "…");
            playOverlay = true;
            playStartedAt = System.currentTimeMillis();
            VlcShell.playPressedAt = android.os.SystemClock.uptimeMillis();
            playMeta = m;
            playVideo = videoId != null ? videoId : m.id;
            showWeb(source.href, true);
        }

        @Override
        public void player(String method, JSONArray params) {
            if (mpv != null) mpv.handle(method, params);
        }

        @Override
        public void playOffline(com.codio.tv.ui.Store.Meta m, String videoId, String path, double startSec) {
            if (mpv == null) {
                Toast.makeText(MainActivity.this, "The video player isn't available on this device", Toast.LENGTH_LONG).show();
                return;
            }
            Log.i(TAG, "playing a download" + (startSec > 0 ? " from " + Math.round(startSec) + "s" : ""));
            // Nothing from the web page may play meanwhile.
            prefetchRoute = null;
            js("window.__codioNativePark && window.__codioNativePark()");
            offlinePlaying = true;
            playOverlay = false;
            playMeta = m;
            playVideo = videoId;
            String[] props = {"time-pos", "duration", "pause", "paused-for-cache", "aid", "sid", "track-list"};
            for (String p : props) mpv.handle("mpv-observe-prop", p);
            JSONArray cmd = new JSONArray().put("loadfile").put(Uri.fromFile(new File(path)).toString()).put("replace");
            if (startSec > 1) cmd.put("start=" + startSec);
            mpv.handle("mpv-command", cmd);
            tvUi.showPlayer(m, videoId);
            tvUi.view().setVisibility(View.VISIBLE);
        }

        @Override
        public boolean webBusy() {
            return webShown || playOverlay || offlinePlaying;
        }

        @Override
        public void stopPlayer() {
            if (offlinePlaying) {
                Log.i(TAG, "leaving a download's player");
                offlinePlaying = false;
                if (mpv != null) mpv.handle("mpv-command", new JSONArray().put("stop"));
                tvUi.hidePlayer();
                tvUi.resumed();
                return;
            }
            Log.i(TAG, "leaving the player");
            if (web != null) web.evaluateJavascript("window.__codioBack ? window.__codioBack() : history.back()", null);
        }

        @Override
        public void userPick() {
            VlcShell.userPickAt = android.os.SystemClock.uptimeMillis();
        }

        @Override
        public void dispatch(JSONObject action) {
            if (web == null) return;
            // The hidden page runs the core: wake it for the action and keep it up until it has saved.
            main.removeCallbacks(pauseWebIfHidden);
            resumeWeb();
            final String what = action.optJSONObject("args") != null ? action.optJSONObject("args").optString("action") : action.optString("action");
            web.evaluateJavascript("window.__codioDispatch ? window.__codioDispatch(" + JSONObject.quote(action.toString()) + ") : 'no-shim'", new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    Log.i(TAG, "action " + what + ": " + value);
                }
            });
            main.removeCallbacks(readStorage);
            main.postDelayed(readStorage, 1200);
            if (!webShown) main.postDelayed(pauseWebIfHidden, 15000);
        }

        @Override
        public void openWeb(String route) {
            showWeb(route);
        }

        @Override
        public void requestExit() {
            long now = System.currentTimeMillis();
            if (now - lastBackAt < 2500) {
                finish();
            } else {
                lastBackAt = now;
                Toast.makeText(MainActivity.this, "Press Back again to exit Codio", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static String detailRoute(com.codio.tv.ui.Store.Meta m, String videoId) {
        String vid = videoId != null ? videoId : m.id;
        return "#/detail/" + Uri.encode(m.type) + "/" + Uri.encode(m.id) + "/" + Uri.encode(vid);
    }

    private void showWeb(String route) {
        showWeb(route, false);
    }

    private void showWeb(String route, boolean behindNative) {
        if (web == null || tvUi == null) return;
        if (offlinePlaying && mpv != null) mpv.handle("mpv-command", new JSONArray().put("stop"));
        offlinePlaying = false;
        Log.i(TAG, "web page " + route.replaceAll("[?].*", ""));
        main.removeCallbacks(pauseWebIfHidden);
        resumeWeb();
        prefetchRoute = null;
        webShown = true;
        sawPlayer = false;
        web.setVisibility(View.VISIBLE);
        if (!behindNative) {
            web.setAlpha(1f);
            tvUi.hidePlayer();
            tvUi.view().setVisibility(View.GONE);
            web.requestFocus();
        }
        web.evaluateJavascript("window.__codioNativeGo && window.__codioNativeGo(" + JSONObject.quote(route) + ")", null);
    }

    /** Back to the native screens; hash says which page the web UI was going to (Home, a movie, ...), or null. */
    private void showNative(String hash) {
        if (tvUi == null) return;
        webShown = false;
        playOverlay = false;
        prefetchRoute = null;
        // Park first: the movie page underneath may then load its sources again (the screens' onShow).
        web.evaluateJavascript("window.__codioNativePark && window.__codioNativePark()", null);
        tvUi.hideLoading();
        tvUi.hidePlayer();
        tvUi.view().setVisibility(View.VISIBLE);
        web.setVisibility(View.INVISIBLE);
        web.setAlpha(1f);
        tvUi.view().requestFocus();
        if (hash != null && !sawPlayer) {
            if (hash.equals("#/") || hash.startsWith("#/?")) tvUi.navigate(0);
            else if (hash.startsWith("#/search")) tvUi.navigate(3);
            else if (hash.startsWith("#/discover")) tvUi.navigate(hash.contains("/series") ? 2 : 1);
            else if (hash.startsWith("#/detail/")) {
                String[] parts = hash.replaceAll("[?].*", "").split("/");
                if (parts.length >= 4 && !tvUi.showingDetail(Uri.decode(parts[3]))) {
                    com.codio.tv.ui.Store.Meta m = new com.codio.tv.ui.Store.Meta();
                    m.type = Uri.decode(parts[2]);
                    m.id = Uri.decode(parts[3]);
                    m.name = "";
                    tvUi.openDetail(m);
                }
            }
        }
        sawPlayer = false;
        tvUi.dataChanged(false);
        tvUi.resumed();
        // Progress is saved a moment after the player closes.
        main.postDelayed(readStorage, 1500);
        main.postDelayed(readStorage, 5000);
        main.removeCallbacks(pauseWebIfHidden);
        main.postDelayed(pauseWebIfHidden, 8000);
    }

    /** Where the web UI went: once playback has ended, or it reaches a page the native screens show, take over. */
    private void onWebRoute(String hash) {
        if (tvUi == null || hash == null) return;
        if (!webShown) {
            // Nothing may play behind the native screens (e.g. an auto-play the viewer backed out of).
            if (hash.startsWith("#/player") || (hash.startsWith("#/detail/") && prefetchRoute == null)) {
                Log.i(TAG, "web page " + hash.replaceAll("[?].*", "") + " behind the native screens: parking it");
                web.evaluateJavascript("window.__codioNativePark && window.__codioNativePark()", null);
            }
            return;
        }
        if (hash.startsWith("#/player")) {
            sawPlayer = true;
            String[] parts = hash.replaceAll("[?].*", "").split("/");
            if (parts.length >= 8) {
                String type = Uri.decode(parts[5]), id = Uri.decode(parts[6]), vid = Uri.decode(parts[7]);
                if (playMeta == null || !id.equals(playMeta.id)) {
                    playMeta = new com.codio.tv.ui.Store.Meta();
                    playMeta.type = type;
                    playMeta.id = id;
                    playMeta.name = "";
                }
                playVideo = vid;
            }
            // Already playing (the next episode started by itself): the controls stay.
            if (!playOverlay) showPlayerControls();
            if (playOverlay) {
                tvUi.loadingStatus("player", null);
                // The player shows its own messages if the video never starts.
                main.removeCallbacks(revealPlayer);
                main.postDelayed(revealPlayer, 25000);
            }
            return;
        }
        boolean nativePage = hash.startsWith("#/codio-native") || hash.equals("#/") || hash.startsWith("#/?")
                || hash.startsWith("#/discover") || hash.startsWith("#/search")
                || (hash.startsWith("#/detail/") && !hash.contains("autoplay=1") && !hash.contains("sources=1"));
        if (sawPlayer || nativePage) showNative(hash);
    }

    /** The movie page loading sources hidden behind the native screens (route without ?prefetch=1), or null. */
    private String prefetchRoute = null;
    private final java.util.Set<String> warmed = new java.util.HashSet<>();

    /**
     * With the sources in, ask the top pick's link once in the background when it's an instant (debrid-cached)
     * one: the addon then prepares the download link before Play is pressed, instead of after.
     */
    private void warmTopSource(com.codio.tv.ui.Store.Sources s) {
        final com.codio.tv.ui.Store.Source best = s.best();
        if (best == null || !best.cached || best.url == null || !best.url.startsWith("http") || !warmed.add(best.url)) return;
        com.codio.tv.ui.Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(best.url).openConnection();
                    c.setRequestProperty("Range", "bytes=0-1");
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    c.setInstanceFollowRedirects(true);
                    int code = c.getResponseCode();
                    try {
                        c.getInputStream().close();
                    } catch (Exception ignored) {
                    }
                    c.disconnect();
                    Log.i(TAG, "top source ready (" + code + ") in " + (System.currentTimeMillis() - t0) + " ms");
                } catch (Exception e) {
                    Log.i(TAG, "top source warm-up failed after " + (System.currentTimeMillis() - t0) + " ms: " + e.getClass().getSimpleName());
                }
            }
        });
    }

    /** The "finding your stream" screen is up over the web page. */
    private boolean playOverlay = false;
    /** Phone: a downloaded file is playing straight in VLC (the web page is parked). */
    private boolean offlinePlaying = false;
    private com.codio.tv.ui.Store.Meta playMeta;
    private String playVideo;
    private long playStartedAt = 0;

    /** The picture is there (or the web page needs the viewer): take the loading screen away. */
    private final Runnable revealPlayer = new Runnable() {
        @Override
        public void run() {
            main.removeCallbacks(this);
            if (!playOverlay || tvUi == null || !webShown) return;
            playOverlay = false;
            Log.i(TAG, "loading screen off after " + (System.currentTimeMillis() - playStartedAt) + " ms");
            showPlayerControls();
        }
    };

    /** The video is up: Codio's controls over it; the web player keeps running underneath, unseen. */
    private void showPlayerControls() {
        if (tvUi == null || web == null) return;
        if (playMeta == null) {
            playMeta = new com.codio.tv.ui.Store.Meta();
            playMeta.type = "movie";
            playMeta.id = "";
            playMeta.name = "";
        }
        tvUi.showPlayer(playMeta, playVideo);
        web.setAlpha(0f);
        tvUi.view().setVisibility(View.VISIBLE);
        tvUi.view().requestFocus();
    }

    private void onPlayStatus(String kind, String text) {
        if (!playOverlay || tvUi == null) return;
        if ("manual".equals(kind) || "list".equals(kind)) {
            // Auto-play is off (or the list is wanted): the viewer picks from Codio's own source list.
            final com.codio.tv.ui.Store.Meta m = playMeta;
            final String vid = playVideo;
            playOverlay = false;
            main.removeCallbacks(revealPlayer);
            showNative(null);
            if (m != null) tvUi.openSources(m, vid);
            return;
        }
        if (!"none".equals(kind)) tvUi.loadingStatus(kind, text);
    }

    private final Runnable readStorage = new Runnable() {
        @Override
        public void run() {
            readWebStorage();
        }
    };

    private void readWebStorage() {
        if (web == null || store == null) return;
        web.evaluateJavascript("window.__codioNativeStorage ? window.__codioNativeStorage() : null", new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(final String value) {
                if (value == null || value.equals("null") || tvUi == null) return;
                com.codio.tv.ui.Net.IO.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // The result is a JSON string holding our JSON object (parsed here, off the UI thread).
                            JSONObject o = new JSONObject(new JSONArray("[" + value + "]").getString(0));
                            String ap = opt(o, "autoplay");
                            final JSONObject autoPlay = ap != null ? new JSONObject(ap) : null;
                            store.updateFromWeb(opt(o, "profile"), opt(o, "recent"), opt(o, "library"), new com.codio.tv.ui.Store.Callback<Boolean>() {
                                @Override
                                public void done(Boolean changed, Exception error) {
                                    if (tvUi == null) return;
                                    if (autoPlay != null) store.autoPlay = autoPlay;
                                    // TMDB key: shared through the Stremio account (ui/KeySync).
                                    com.codio.tv.ui.KeySync.sync(getSharedPreferences("codio", MODE_PRIVATE),
                                            com.codio.tv.ui.KeySync.authKey(store.profileJson), null);
                                    store.ensureDefaults(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (tvUi != null) tvUi.dataChanged(true);
                                        }
                                    });
                                    tvUi.dataChanged(Boolean.TRUE.equals(changed));
                                }
                            });
                        } catch (Exception e) {
                            Log.w(TAG, "web storage unreadable", e);
                        }
                    }
                });
            }
        });
    }

    private static String opt(JSONObject o, String k) {
        return o.isNull(k) ? null : o.optString(k, null);
    }

    /** While the native screens are up, the hidden web page needn't run at all. */
    private final Runnable pauseWebIfHidden = new Runnable() {
        @Override
        public void run() {
            if (web == null || webShown || webPaused || prefetchRoute != null) return;
            web.onPause();
            web.pauseTimers();
            webPaused = true;
            Log.i(TAG, "web UI paused behind the native screens");
        }
    };

    private void resumeWeb() {
        if (!webPaused || web == null) return;
        web.resumeTimers();
        web.onResume();
        webPaused = false;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (tvUi != null && level >= TRIM_MEMORY_RUNNING_LOW) tvUi.trimImages();
    }

}
