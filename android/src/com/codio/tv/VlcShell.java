package com.codio.tv;

import android.content.Context;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * libVLC behind the same shell IPC as MpvShell, so the web UI's ShellVideo player (and Codio's auto-play)
 * drives VLC without knowing it. VLC hands hardware-decoded frames straight to the video surface
 * (MediaCodec direct rendering), with no GPU copy or shader pass, which keeps 4K smooth on weak TV boxes.
 * Embedded subtitles are drawn by VLC on a second, transparent surface above the video.
 */
final class VlcShell implements PlayerShell, MediaPlayer.EventListener, IVLCVout.OnNewVideoLayoutListener {
    private static final String TAG = "CodioVlc";

    private final LibVLC vlc;
    private final MediaPlayer player;
    private final MpvShell.Sink sink;
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SurfaceView videoView;
    private final SurfaceView subsView;
    private final boolean hardware;

    /** Frame rate of the current video (MainActivity matches the TV's refresh rate to it). */
    static volatile double videoFps = 0;

    private String mediaOpts = "";
    private boolean directHttps = false;
    private String audioPref = "auto";
    private android.content.SharedPreferences prefs;
    private volatile long openedAt = 0;
    private volatile long timingStart = 0;
    /** Set by MainActivity when Play is pressed, to time the search for a source. */
    static volatile long playPressedAt = 0;
    private boolean firstBufferLogged = false;
    private boolean bufferFullLogged = false;
    private int sinkChannels = 0; // most channels the TV/receiver takes over HDMI (0 = unknown)

    private final Set<String> observedByUi = new HashSet<>();
    private final Map<String, Object> lastValues = new HashMap<>();

    // Playback state, guarded by "this".
    private int loadId = 0;
    private int pendingReadyLoadId = -1;
    private boolean active = false;       // a file is loaded or loading
    private boolean started = false;      // it reached Playing at least once
    private boolean playing = false;
    private boolean wantPause = false;
    private int wantSid = -2;             // -2: no preference, -1: off
    private int wantAid = -2;
    private long timeMs = 0, lengthMs = 0;
    private long lastTimeSent = 0;
    private boolean lengthLogged = false;
    private int videoW = 0, videoH = 0;
    private int volume = 100;
    private boolean muted = false;

    // Layout, main thread only.
    private int layoutW, layoutH, layoutVisW, layoutVisH, layoutSarNum = 1, layoutSarDen = 1;
    // The decoder's buffer can be padded (4096x2160 for a 1920x816 movie), so the shape comes from the track itself.
    private int trackDw, trackH;
    private boolean keepAspect = true;
    private double panscan = 0;

    private VlcShell(LibVLC vlc, MediaPlayer player, MpvShell.Sink sink, Handler worker, SurfaceView videoView,
                     SurfaceView subsView, boolean hardware) {
        this.vlc = vlc;
        this.player = player;
        this.sink = sink;
        this.worker = worker;
        this.videoView = videoView;
        this.subsView = subsView;
        this.hardware = hardware;
    }

    /**
     * Must be called on the main thread. subsView is placed above videoView (media overlay, transparent).
     * Returns null if libVLC can't start on this device.
     */
    static VlcShell create(Context context, MpvShell.Sink sink, SurfaceView videoView, SurfaceView subsView, boolean hardware,
                           android.content.SharedPreferences prefs) {
        try {
            ArrayList<String> o = new ArrayList<>();
            o.add("--verbose=1");
            o.add("--no-sub-autodetect-file");
            o.add("--http-reconnect");
            o.add("--audio-time-stretch");
            o.add("--avcodec-skiploopfilter=1");      // only affects software decoding
            o.add("--android-display-chroma");
            o.add("RV32");
            o.add("--freetype-rel-fontsize=16");
            o.add("--freetype-bold");
            o.add("--freetype-outline-thickness=4");
            o.add("--freetype-background-opacity=0");
            o.add("--sub-language=en,eng");
            o.add("--audio-language=en,eng");
            o.add("--preferred-resolution=-1");
            // Measured on a Fire TV: OpenSL ES output plus an 8s network buffer lost the fewest frames on 4K remuxes.
            // Audio goes out through Android's AudioTrack (this libVLC has no OpenSL ES output; asking for it only
            // logged "failed to create audio output" before falling back). On TV speakers or a stereo soundbar, VLC
            // mixes surround tracks down to stereo itself: otherwise a 7.1 stream goes to Android, whose own downmix
            // path on this TV adds delay that the playback clock doesn't see (sound slightly behind the picture).
            int outChannels = outputChannels(context);
            String audioPrefNow = prefs.getString("vlc_audio", "auto");
            if (outChannels > 0 && outChannels <= 2 && !"full".equals(audioPrefNow)) {
                o.add("--stereo-mode=1");
            }
            // Test switches (set with adb, see MainActivity.applyTestSwitches): extra libVLC options, e.g. "--aout=opensles".
            String extra = prefs.getString("vlc_opts", "").trim();
            if (!extra.isEmpty()) for (String x : extra.split("\\s+")) o.add(x);
            LibVLC lib = new LibVLC(context, o);
            MediaPlayer mp = new MediaPlayer(lib);
            HandlerThread thread = new HandlerThread("codio-vlc");
            thread.start();
            subsView.setZOrderMediaOverlay(true);
            subsView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
            VlcShell shell = new VlcShell(lib, mp, sink, new Handler(thread.getLooper()), videoView, subsView, hardware);
            shell.mediaOpts = prefs.getString("vlc_media_opts", "").trim();
            shell.directHttps = "true".equals(prefs.getString("vlc_direct", "false"));
            shell.audioPref = prefs.getString("vlc_audio", "auto");
            shell.prefs = prefs;
            shell.sinkChannels = outputChannels(context);
            measureOutputLatency(prefs);
            Log.i(TAG, "options: " + extra + " | media: " + shell.mediaOpts + " | direct https: " + shell.directHttps
                    + " | audio: " + shell.audioPref + " | output channels: " + shell.sinkChannels);
            mp.setEventListener(shell); // delivered on the main thread
            shell.attachViews();
            shell.installSurfaceWatch();
            Log.i(TAG, "libVLC " + LibVLC.version() + " ready");
            return shell;
        } catch (Throwable t) {
            Log.e(TAG, "libVLC is not available on this device", t);
            return null;
        }
    }

    // ---------------------------------------------------------------- surfaces (main thread)

    private void attachViews() {
        IVLCVout vout = player.getVLCVout();
        if (vout.areViewsAttached()) return;
        vout.setVideoView(videoView);
        vout.setSubtitlesView(subsView);
        vout.attachViews(this);
        View parent = (View) videoView.getParent();
        if (parent != null && parent.getWidth() > 0) vout.setWindowSize(parent.getWidth(), parent.getHeight());
        Log.i(TAG, "views attached");
    }

    /** libVLC detaches itself when a surface goes away (app in background); reattach once both are back. */
    private void installSurfaceWatch() {
        SurfaceHolder.Callback cb = new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                main.post(new Runnable() {
                    @Override public void run() {
                        if (player.getVLCVout().areViewsAttached()) return;
                        if (!videoView.getHolder().getSurface().isValid() || !subsView.getHolder().getSurface().isValid()) return;
                        attachViews();
                        // A running video needs a new video output on the new surface.
                        worker.post(new Runnable() {
                            @Override public void run() {
                                try {
                                    if (player.getVideoTracksCount() > 0) {
                                        player.setVideoTrackEnabled(false);
                                        player.setVideoTrackEnabled(true);
                                    }
                                } catch (Throwable t) {
                                    Log.w(TAG, "video restart failed", t);
                                }
                            }
                        });
                    }
                });
            }

            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override public void surfaceDestroyed(SurfaceHolder holder) {
            }
        };
        videoView.getHolder().addCallback(cb);
        subsView.getHolder().addCallback(cb);
        View parent = (View) videoView.getParent();
        if (parent != null) {
            parent.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    if (r - l != or - ol || b - t != ob - ot) {
                        player.getVLCVout().setWindowSize(r - l, b - t);
                        applyLayout();
                    }
                }
            });
        }
    }

    @Override public void onNewVideoLayout(IVLCVout vout, int width, int height, int visibleWidth, int visibleHeight,
                                           int sarNum, int sarDen) {
        layoutW = width;
        layoutH = height;
        layoutVisW = visibleWidth;
        layoutVisH = visibleHeight;
        layoutSarNum = sarNum > 0 ? sarNum : 1;
        layoutSarDen = sarDen > 0 ? sarDen : 1;
        applyLayout();
    }

    /** Sizes the video (and subtitle) surface to the video's shape, like VLC's own player does. */
    private void applyLayout() {
        View parent = (View) videoView.getParent();
        if (parent == null) return;
        int pw = parent.getWidth(), ph = parent.getHeight();
        int w = ViewGroup.LayoutParams.MATCH_PARENT, h = ViewGroup.LayoutParams.MATCH_PARENT;
        boolean known = trackDw > 0 && trackH > 0;
        if (pw > 0 && ph > 0 && (known || (layoutVisW > 0 && layoutVisH > 0)) && keepAspect) {
            double videoAr = known ? (double) trackDw / trackH : (double) layoutVisW * layoutSarNum / layoutSarDen / layoutVisH;
            double screenAr = (double) pw / ph;
            boolean fitWidth = videoAr >= screenAr;
            if (panscan >= 0.5) fitWidth = !fitWidth; // "cover": fill the screen, crop the rest
            if (fitWidth) {
                w = pw;
                h = (int) Math.round(pw / videoAr);
            } else {
                h = ph;
                w = (int) Math.round(ph * videoAr);
            }
        }
        setSize(videoView, w, h);
        setSize(subsView, w, h);
    }

    private static void setSize(SurfaceView v, int w, int h) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return;
        if (lp.width == w && lp.height == h && (!(lp instanceof FrameLayout.LayoutParams)
                || ((FrameLayout.LayoutParams) lp).gravity == Gravity.CENTER)) return;
        lp.width = w;
        lp.height = h;
        if (lp instanceof FrameLayout.LayoutParams) ((FrameLayout.LayoutParams) lp).gravity = Gravity.CENTER;
        v.setLayoutParams(lp);
    }

    // libVLC manages its surfaces itself (see attachViews), so MainActivity's surface calls are no-ops.
    @Override public void attachSurface(Surface surface) {
    }

    @Override public void surfaceSize(int width, int height) {
    }

    @Override public void detachSurface() {
    }

    @Override public void pause() {
        worker.post(new Runnable() {
            @Override public void run() {
                synchronized (VlcShell.this) {
                    wantPause = true;
                }
                try {
                    if (player.isPlaying()) player.pause();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @Override public void destroy() {
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    player.setEventListener(null);
                    player.getVLCVout().detachViews();
                } catch (Throwable ignored) {
                }
                worker.post(new Runnable() {
                    @Override public void run() {
                        try {
                            player.stop();
                            player.release();
                            vlc.release();
                        } catch (Throwable t) {
                            Log.e(TAG, "destroy failed", t);
                        }
                        worker.getLooper().quitSafely();
                    }
                });
            }
        });
    }

    @Override public String hwdec() {
        return hardware ? "mediacodec" : "no";
    }

    @Override public String engine() {
        return "vlc";
    }

    // ---------------------------------------------------------------- web UI -> VLC

    @Override public boolean handle(final String method, final Object params) {
        switch (method) {
            case "mpv-observe-prop":
                if (params instanceof String) {
                    final String name = (String) params;
                    worker.post(new Runnable() {
                        @Override public void run() {
                            observe(name);
                        }
                    });
                }
                return true;
            case "mpv-set-prop":
                if (params instanceof JSONArray && ((JSONArray) params).length() >= 2) {
                    final JSONArray a = (JSONArray) params;
                    final String name = a.optString(0);
                    final Object value = a.opt(1);
                    worker.post(new Runnable() {
                        @Override public void run() {
                            setProp(name, value);
                        }
                    });
                }
                return true;
            case "mpv-command":
                if (params instanceof JSONArray) {
                    final JSONArray a = (JSONArray) params;
                    final String[] cmd = new String[a.length()];
                    for (int i = 0; i < cmd.length; i++) cmd[i] = String.valueOf(a.opt(i));
                    worker.post(new Runnable() {
                        @Override public void run() {
                            command(cmd);
                        }
                    });
                }
                return true;
            case "mpv-set-gpu-video-processing":
                return true;
            default:
                return false;
        }
    }

    private void observe(String name) {
        Object replay;
        synchronized (this) {
            observedByUi.add(name);
            replay = lastValues.get(name);
        }
        switch (name) {
            // ShellVideo waits for mpv-version before it loads anything; a recent version gets the modern loadfile form.
            case "mpv-version":
                forward(name, "mpv 0.40.0 (libVLC " + LibVLC.version() + ")");
                return;
            case "ffmpeg-version":
                forward(name, "libVLC " + LibVLC.version());
                return;
            case "volume":
                forward(name, (double) volume);
                return;
            case "speed":
                forward(name, 1.0);
                return;
            case "sub-scale":
                forward(name, 1.0);
                return;
            case "sub-pos":
                forward(name, 100.0);
                return;
            case "sub-delay":
                forward(name, 0.0);
                return;
            default:
                break;
        }
        if (replay != null) forward(name, replay);
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        String s = String.valueOf(v);
        return "yes".equals(s) || "true".equals(s);
    }

    private static int trackId(Object v) {
        if (v == null || v == JSONObject.NULL) return -1;
        String s = String.valueOf(v);
        if ("no".equals(s) || "false".equals(s)) return -1;
        try {
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double num(Object v, double fallback) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void setProp(String name, Object value) {
        if (value == null || value == JSONObject.NULL) return;
        try {
            switch (name) {
                case "pause": {
                    boolean p = truthy(value);
                    synchronized (this) {
                        wantPause = p;
                    }
                    if (p && player.isPlaying()) player.pause();
                    else if (!p && !player.isPlaying() && started) player.play();
                    break;
                }
                case "time-pos": {
                    double s = num(value, -1);
                    if (s < 0) break;
                    long ms = (long) (s * 1000);
                    player.setTime(ms);
                    synchronized (this) {
                        timeMs = ms;
                    }
                    forward("seeking", false);
                    forward("time-pos", s);
                    break;
                }
                case "speed": {
                    double r = num(value, 1);
                    if (r > 0) player.setRate((float) r);
                    forward("speed", r);
                    break;
                }
                case "volume": {
                    int v = (int) Math.round(num(value, 100));
                    v = Math.max(0, Math.min(200, v));
                    synchronized (this) {
                        volume = v;
                    }
                    if (!muted) player.setVolume(v);
                    forward("volume", (double) v);
                    break;
                }
                case "mute": {
                    boolean m = truthy(value);
                    synchronized (this) {
                        muted = m;
                    }
                    player.setVolume(m ? 0 : volume);
                    break;
                }
                case "aid": {
                    int id = trackId(value);
                    // The web player re-sends the last video's track number when the next one loads (a source that
                    // failed, the next episode), which can be another language in this file. Only a pick in the
                    // menu may move away from the viewer's audio language.
                    boolean auto = android.os.SystemClock.uptimeMillis() - userPickAt > 4000;
                    if (id >= 0 && auto && !audioFits(id)) {
                        Log.i(TAG, "ignoring automatic audio pick " + id + " (not in " + AudioLang.preferred() + ")");
                        sendSelection();
                        break;
                    }
                    synchronized (this) {
                        wantAid = id;
                    }
                    if (id >= 0 && started) player.setAudioTrack(id);
                    break;
                }
                case "sid": {
                    int id = trackId(value);
                    // Subtitles stay off unless the viewer turns them on (Settings > Subtitles): the web player
                    // otherwise switches them on by itself, sometimes in the wrong language. A pick in the menu counts.
                    boolean auto = android.os.SystemClock.uptimeMillis() - userPickAt > 4000;
                    if (id >= 0 && auto && prefs != null && !"on".equals(prefs.getString("subs_auto", "off"))) {
                        Log.i(TAG, "ignoring automatic subtitle pick " + id);
                        sendSelection();
                        break;
                    }
                    synchronized (this) {
                        wantSid = id;
                    }
                    if (started) player.setSpuTrack(id);
                    sendSelection();
                    break;
                }
                case "sub-delay": {
                    double s = num(value, 0);
                    player.setSpuDelay((long) (s * 1000000));
                    forward("sub-delay", s);
                    break;
                }
                case "keepaspect":
                case "panscan": {
                    final boolean isKeep = "keepaspect".equals(name);
                    final boolean k = truthy(value);
                    final double ps = num(value, 0);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (isKeep) keepAspect = k;
                            else panscan = ps;
                            applyLayout();
                        }
                    });
                    break;
                }
                default:
                    break; // hwdec, vo, sub colours, ... : VLC is configured once at start-up
            }
        } catch (Throwable t) {
            Log.w(TAG, "set " + name + "=" + value + " failed", t);
        }
    }

    private int startBufferMs() {
        int fallback = BuildConfig.PHONE ? 3000 : 4000;
        if (prefs == null || BuildConfig.PHONE) return fallback;
        try {
            return prefs.getInt("start_buffer_ms", fallback);
        } catch (ClassCastException e) {
            return fallback;
        }
    }

    private String viaProxy(String url) {
        if (directHttps) return url;
        String origin = MpvShell.streamProxy;
        if (origin == null || url == null || !url.regionMatches(true, 0, "https://", 0, 8)) return url;
        return origin + LocalServer.streamPath(url);
    }

    private void command(String[] cmd) {
        if (cmd.length == 0) return;
        try {
            switch (cmd[0]) {
                case "loadfile":
                    openedAt = android.os.SystemClock.uptimeMillis();
                    timingStart = openedAt;
                    firstBufferLogged = false;
                    bufferFullLogged = false;
                    if (playPressedAt > 0) {
                        Log.e("CodioTiming", "Play press to first source open " + (openedAt - playPressedAt) + " ms");
                        playPressedAt = 0;
                    }
                    if (cmd.length > 1) load(cmd);
                    break;
                case "stop":
                    stopPlayback();
                    break;
                case "sub-add":
                    if (cmd.length > 1) player.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(viaProxy(cmd[1])), true);
                    break;
                case "audio-add":
                    if (cmd.length > 1) player.addSlave(IMedia.Slave.Type.Audio, Uri.parse(viaProxy(cmd[1])), false);
                    break;
                case "seek":
                    if (cmd.length > 1) {
                        double s = num(cmd[1], 0);
                        boolean absolute = cmd.length > 2 && cmd[2].startsWith("absolute");
                        long target = absolute ? (long) (s * 1000) : player.getTime() + (long) (s * 1000);
                        player.setTime(Math.max(0, target));
                    }
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "command " + cmd[0] + " failed", t);
        }
    }

    private void load(String[] cmd) {
        String url = viaProxy(cmd[1]);
        double startAt = 0;
        for (int i = 2; i < cmd.length; i++) {
            String a = cmd[i];
            int idx = a.indexOf("start=");
            if (idx >= 0) startAt = num(a.substring(idx + 6).replace("+", ""), 0);
        }
        int id;
        synchronized (this) {
            loadId++;
            id = loadId;
            pendingReadyLoadId = id;
            active = true;
            started = false;
            playing = false;
            wantPause = false;
            wantSid = -2;
            wantAid = -2;
            timeMs = 0;
            lengthMs = 0;
            lastTimeSent = 0;
            lengthLogged = false;
            lastValues.clear();
        }
        sendVideoReady(id, false);
        Media media = new Media(vlc, Uri.parse(url));
        media.setHWDecoderEnabled(hardware, hardware);
        // VLC fills this much buffer before the picture starts, so it is a direct trade between start time and
        // smoothness on a marginal connection. TV: Settings > Start-up buffer (a test switch in mediaOpts still wins).
        media.addOption(":network-caching=" + startBufferMs());
        media.addOption(":file-caching=3000");
        if (startAt > 0) media.addOption(":start-time=" + startAt);
        if (!mediaOpts.isEmpty()) for (String x : mediaOpts.split("\\s+")) media.addOption(x.startsWith(":") ? x : ":" + x);
        player.setMedia(media);
        media.release();
        player.play();
        Log.i(TAG, "loadfile #" + id + (startAt > 0 ? " from " + startAt + "s" : ""));
        worker.removeCallbacks(statsLogger);
        statsPrev = null;
        worker.postDelayed(statsLogger, 10000);
        forward("paused-for-cache", true);
        forward("pause", false);
    }

    private IMedia.Stats statsPrev = null;
    /** Every 10s while playing: shown/lost frames and bitrate, for judging smoothness from adb logcat. */
    private final Runnable statsLogger = new Runnable() {
        @Override public void run() {
            boolean on;
            synchronized (VlcShell.this) {
                on = active;
            }
            if (!on) return;
            IMedia m = null;
            try {
                m = player.getMedia();
                IMedia.Stats st = m != null ? m.getStats() : null;
                if (st != null && statsPrev != null && player.isPlaying()) {
                    Log.i(TAG, "stats 10s: shown=" + (st.displayedPictures - statsPrev.displayedPictures)
                            + " lost=" + (st.lostPictures - statsPrev.lostPictures)
                            + " decoded=" + (st.decodedVideo - statsPrev.decodedVideo)
                            + " audio-lost=" + (st.lostAbuffers - statsPrev.lostAbuffers)
                            + " input=" + Math.round(st.inputBitrate * 8000) + "kbit/s"
                            + " demux=" + Math.round(st.demuxBitrate * 8000) + "kbit/s");
                }
                if (st != null) statsPrev = st;
            } catch (Throwable ignored) {
            } finally {
                if (m != null) m.release();
            }
            worker.postDelayed(this, 10000);
        }
    };

    /**
     * Most channels the sound output takes: an HDMI/ARC receiver or soundbar if one is connected, otherwise the TV's
     * own speakers (2). 0 = unknown.
     */
    static int outputChannels(Context context) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int hdmi = -1;
            boolean speaker = false;
            StringBuilder seen = new StringBuilder();
            for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                int type = d.getType();
                seen.append(' ').append(type);
                if (type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) speaker = true;
                if (type != android.media.AudioDeviceInfo.TYPE_HDMI && type != android.media.AudioDeviceInfo.TYPE_HDMI_ARC) continue;
                int[] counts = d.getChannelCounts();
                int best = (counts == null || counts.length == 0) ? 8 : 0; // "any"
                if (counts != null) for (int c : counts) best = Math.max(best, c);
                hdmi = Math.max(hdmi, best);
            }
            Log.i(TAG, "audio outputs:" + seen + " -> hdmi " + hdmi + ", speaker " + speaker);
            // A TV with Fire OS built in lists HDMI only for ARC/eARC; a stick or box always plays through HDMI.
            if (hdmi > 0 && (!speaker || isArcConnected(am))) return hdmi;
            if (speaker) return 2;
            return Math.max(0, hdmi);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isArcConnected(android.media.AudioManager am) {
        for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == android.media.AudioDeviceInfo.TYPE_HDMI_ARC) return true;
        }
        return false;
    }

    /**
     * How long sound takes from Android's mixer to the speakers on this device, in ms. Plays half a second of
     * silence and compares the mixer's position (getPlaybackHeadPosition) with what the hardware reports as actually
     * presented (getTimestamp). That gap is what this libVLC's AudioTrack output leaves out of its clock.
     * Runs on its own thread; saves the result for the next playback.
     */
    static void measureOutputLatency(final android.content.SharedPreferences prefs) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                android.media.AudioTrack t = null;
                try {
                    int rate = 48000;
                    int min = android.media.AudioTrack.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_OUT_STEREO, android.media.AudioFormat.ENCODING_PCM_16BIT);
                    int buf = Math.max(min, rate / 5 * 4);
                    t = new android.media.AudioTrack(android.media.AudioManager.STREAM_MUSIC, rate, android.media.AudioFormat.CHANNEL_OUT_STEREO,
                            android.media.AudioFormat.ENCODING_PCM_16BIT, buf, android.media.AudioTrack.MODE_STREAM);
                    t.setVolume(0f);
                    byte[] silence = new byte[rate / 10 * 4];
                    t.play();
                    android.media.AudioTimestamp ts = new android.media.AudioTimestamp();
                    long sum = 0;
                    int n = 0;
                    long until = android.os.SystemClock.uptimeMillis() + 1500;
                    while (android.os.SystemClock.uptimeMillis() < until) {
                        t.write(silence, 0, silence.length);
                        if (t.getTimestamp(ts) && ts.framePosition > 0) {
                            long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                            double presented = ts.framePosition + (System.nanoTime() - ts.nanoTime) * rate / 1e9;
                            double ms = (head - presented) * 1000.0 / rate;
                            if (ms > -50 && ms < 1000 && android.os.SystemClock.uptimeMillis() > until - 900) {
                                sum += Math.round(ms);
                                n++;
                            }
                        }
                    }
                    int total = -1;
                    try {
                        total = (Integer) android.media.AudioTrack.class.getMethod("getLatency").invoke(t);
                    } catch (Throwable ignored) {
                    }
                    int hw = n > 0 ? (int) Math.max(0, Math.min(400, sum / n)) : 0;
                    Log.i(TAG, "audio output latency: " + hw + " ms after the mixer (" + n + " samples; getLatency " + total + " ms)");
                    prefs.edit().putInt("audio_hw_latency_ms", hw).apply();
                } catch (Throwable e) {
                    Log.w(TAG, "audio output latency unknown: " + e);
                } finally {
                    if (t != null) {
                        try {
                            t.stop();
                        } catch (Throwable ignored) {
                        }
                        t.release();
                    }
                }
            }
        }, "codio-audio-latency").start();
    }

    /** When the viewer last picked something in a player menu (the shim reports OK presses there). */
    static volatile long userPickAt = 0;

    private void stopPlayback() {
        int id;
        boolean wasActive;
        synchronized (this) {
            wasActive = active;
            active = false;
            started = false;
            loadId++;
            id = loadId;
            pendingReadyLoadId = -1;
        }
        sendVideoReady(id, false);
        if (wasActive) {
            player.stop();
            sink.onPlayingChanged(false);
        }
        setVideoSize(0, 0);
    }

    // ---------------------------------------------------------------- VLC -> web UI

    @Override public void onEvent(final MediaPlayer.Event e) {
        // Delivered on the main thread; anything that calls into libVLC goes to the worker.
        switch (e.type) {
            case MediaPlayer.Event.Opening:
                forward("paused-for-cache", true);
                break;
            case MediaPlayer.Event.Buffering: {
                if (!firstBufferLogged && openedAt > 0) {
                    firstBufferLogged = true;
                    Log.e("CodioTiming", "first data " + (android.os.SystemClock.uptimeMillis() - openedAt) + " ms after open");
                }
                if (e.getBuffering() >= 100f && timingStart > 0 && !bufferFullLogged) {
                    bufferFullLogged = true;
                    Log.e("CodioTiming", "buffer full " + (android.os.SystemClock.uptimeMillis() - timingStart) + " ms after open");
                }
                boolean buffering = e.getBuffering() < 100f;
                forward("paused-for-cache", buffering);
                forward("cache-buffering-state", (double) e.getBuffering());
                break;
            }
            case MediaPlayer.Event.Playing:
                onPlaying();
                break;
            case MediaPlayer.Event.Paused:
                synchronized (this) {
                    playing = false;
                }
                forward("pause", true);
                sink.onPlayingChanged(false);
                break;
            case MediaPlayer.Event.TimeChanged:
                if (timingStart > 0 && e.getTimeChanged() > 0) {
                    // Error level because some TVs (this Hisense) drop everything below it from logcat.
                    Log.e("CodioTiming", "picture running " + (android.os.SystemClock.uptimeMillis() - timingStart) + " ms after open");
                    timingStart = 0;
                }
                onTime(e.getTimeChanged());
                break;
            case MediaPlayer.Event.LengthChanged: {
                long len = e.getLengthChanged();
                synchronized (this) {
                    lengthMs = len;
                }
                if (len > 0) forward("duration", len / 1000.0);
                break;
            }
            case MediaPlayer.Event.Vout:
                if (e.getVoutCount() > 0 && openedAt > 0) {
                    Log.e("CodioTiming", "video output up " + (android.os.SystemClock.uptimeMillis() - openedAt) + " ms after open");
                    openedAt = 0;
                }
                if (e.getVoutCount() > 0) {
                    worker.post(new Runnable() {
                        @Override public void run() {
                            updateVideo();
                            markReady();
                        }
                    });
                }
                break;
            case MediaPlayer.Event.ESAdded:
            case MediaPlayer.Event.ESDeleted:
            case MediaPlayer.Event.ESSelected:
                worker.post(new Runnable() {
                    @Override public void run() {
                        if (e.type == MediaPlayer.Event.ESAdded) enforceSelection(e.getEsChangedType());
                        sendTracks();
                        sendSelection();
                        if (e.getEsChangedType() == IMedia.Track.Type.Video) updateVideo();
                    }
                });
                break;
            case MediaPlayer.Event.EndReached:
                ended(false);
                break;
            case MediaPlayer.Event.EncounteredError:
                ended(true);
                break;
            default:
                break;
        }
    }

    private void onPlaying() {
        final boolean firstStart;
        final boolean pauseNow;
        synchronized (this) {
            playing = true;
            firstStart = !started;
            started = true;
            pauseNow = wantPause;
        }
        forward("pause", false);
        forward("paused-for-cache", false);
        sink.onPlayingChanged(true);
        worker.post(new Runnable() {
            @Override public void run() {
                try {
                    // Audio sync from Settings (positive = sound later), applied live.
                    if (prefs != null) {
                        // This libVLC's AudioTrack output counts sound as played when it reaches the mixer; the TV's
                        // output path (speakers, sound processing) adds more on top. Correct for the measured part,
                        // plus the viewer's own fine-tuning from Settings.
                        long delayUs = (prefs.getInt("audio_delay_ms", 0) - prefs.getInt("audio_hw_latency_ms", 0)) * 1000L;
                        if (player.getAudioDelay() != delayUs) {
                            player.setAudioDelay(delayUs);
                            Log.i(TAG, "audio delay " + delayUs / 1000 + " ms");
                        }
                    }
                    if (firstStart) {
                        if (prefs != null) audioPref = prefs.getString("vlc_audio", "auto");
                        player.setVolume(muted ? 0 : volume);
                        pickDefaultAudio();
                        enforceSelection(IMedia.Track.Type.Audio);
                        enforceSelection(IMedia.Track.Type.Text);
                        sendTracks();
                        sendSelection();
                        updateVideo();
                    }
                    if (pauseNow && player.isPlaying()) player.pause();
                } catch (Throwable t) {
                    Log.w(TAG, "start-up settings failed", t);
                }
            }
        });
    }

    private void onTime(long ms) {
        boolean send;
        boolean ready;
        synchronized (this) {
            timeMs = ms;
            long now = System.currentTimeMillis();
            send = now - lastTimeSent >= 250;
            if (send) lastTimeSent = now;
            // Audio-only streams never get a video output; count them as ready once time moves.
            ready = pendingReadyLoadId != -1 && ms > 1500 && videoW == 0;
        }
        if (send) forward("time-pos", ms / 1000.0);
        boolean needLength;
        synchronized (this) {
            needLength = lengthMs <= 0 && send;
        }
        if (needLength) {
            // Not every stream gets a LengthChanged event; without a duration the UI can't seek.
            worker.post(new Runnable() {
                @Override public void run() {
                    try {
                        long len = player.getLength();
                        if (len <= 0) {
                            IMedia m = player.getMedia();
                            if (m != null) {
                                len = m.getDuration();
                                m.release();
                            }
                        }
                        if (len <= 0 && !lengthLogged && timeMs > 8000) {
                            lengthLogged = true;
                            Log.w(TAG, "no duration after " + timeMs + "ms; seekable=" + player.isSeekable());
                        }
                        boolean fresh;
                        synchronized (VlcShell.this) {
                            fresh = len > 0 && lengthMs <= 0;
                            if (fresh) lengthMs = len;
                        }
                        if (fresh) forward("duration", len / 1000.0);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
        if (ready) {
            worker.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (player.getVideoTracksCount() == 0) markReady();
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    }

    private void markReady() {
        int ready;
        synchronized (this) {
            ready = pendingReadyLoadId;
            pendingReadyLoadId = -1;
        }
        if (ready != -1) sendVideoReady(ready, true);
    }

    private void ended(boolean error) {
        String reason;
        boolean critical = false;
        String message = null;
        synchronized (this) {
            if (!active) return;
            active = false;
            if (error && !started) {
                reason = "error";
                critical = true;
                message = "This stream could not be opened.";
            } else if (!error && (lengthMs <= 0 || timeMs >= lengthMs - 15000)) {
                reason = "eof";
            } else if (!started) {
                reason = "error";
                critical = true;
                message = "This stream could not be opened.";
            } else {
                reason = "error";
                message = "Playback stopped unexpectedly.";
            }
            Log.i(TAG, "end-file: " + reason + " at " + timeMs + "/" + lengthMs + (message != null ? " (" + message + ")" : ""));
            started = false;
            playing = false;
            pendingReadyLoadId = -1;
        }
        sink.onPlayingChanged(false);
        forward("eof-reached", "eof".equals(reason));
        try {
            JSONObject o = new JSONObject();
            o.put("reason", reason);
            if (message != null) {
                JSONObject err = new JSONObject();
                err.put("message", message);
                err.put("critical", critical);
                err.put("code", critical ? 83 : 81);
                o.put("error", err);
            }
            signal("mpv-event-ended", o);
        } catch (Throwable t) {
            Log.w(TAG, "end-file signal failed", t);
        }
        worker.post(new Runnable() {
            @Override public void run() {
                setVideoSize(0, 0);
            }
        });
    }

    /**
     * VLC would take the first English audio track, which on remuxes is often a stereo or "compatibility" track.
     * Prefer the fullest one (most channels; TrueHD/Atmos plays smoothly too), in English if there is one.
     */
    private void pickDefaultAudio() {
        synchronized (this) {
            if (wantAid != -2) {
                Log.i(TAG, "audio track " + wantAid + " (the viewer's earlier choice)");
                return;
            }
        }
        IMedia media = null;
        try {
            media = player.getMedia();
            if (media == null) return;
            Map<Integer, String> names = trackNames();
            int best = -1, bestScore = -1;
            boolean anyPreferred = false;
            // Pass 0: main tracks in the viewer's audio language (wherever they sit in the file); pass 1: any main
            // track; pass 2: anything, commentary included.
            for (int pass = 0; pass < 3 && best < 0; pass++) {
                for (int i = 0; i < media.getTrackCount(); i++) {
                    IMedia.Track t = media.getTrack(i);
                    if (!(t instanceof IMedia.AudioTrack)) continue;
                    String name = t.description != null && !t.description.isEmpty() ? t.description : names.get(t.id);
                    boolean preferred = AudioLang.isPreferred(t.language, name);
                    anyPreferred |= preferred;
                    if (pass == 0 && !preferred) continue;
                    String d = name != null ? name.toLowerCase() : "";
                    if (pass < 2 && AudioLang.isExtra(name)) continue;
                    int ch = ((IMedia.AudioTrack) t).channels;
                    String codec = codecName(t.codec);
                    boolean lossless = "truehd".equals(codec) || "mlp".equals(codec) || (codec != null && codec.startsWith("dts"))
                            || "flac".equals(codec) || (codec != null && codec.startsWith("pcm"));
                    boolean light = "light".equals(audioPref) || ("auto".equals(audioPref) && sinkChannels > 0 && sinkChannels <= 2);
                    int score;
                    if (light) {
                        // The TV only takes stereo, so every track ends up as stereo: pick the one that's cheapest to
                        // decode (Dolby Digital / AAC rather than TrueHD), which leaves the CPU free for smooth 4K.
                        score = (lossless ? 0 : 1000) + Math.min(ch, 6) * 10 - (d.contains("compat") ? 0 : 1);
                    } else {
                        score = ch * 10 - (d.contains("compat") ? 5 : 0);
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        best = t.id;
                    }
                }
                if (pass == 0 && anyPreferred && best >= 0) break;
            }
            if (best >= 0 && player.getAudioTrack() != best) {
                player.setAudioTrack(best);
                Log.i(TAG, "audio track " + best);
            }
        } catch (Throwable t) {
            Log.w(TAG, "default audio failed", t);
        } finally {
            if (media != null) media.release();
        }
    }

    /** Applies the UI's (or our default) track choice when VLC picks its own. Worker thread. */
    private void enforceSelection(int type) {
        try {
            int sid, aid;
            synchronized (this) {
                sid = wantSid;
                aid = wantAid;
            }
            if (type == IMedia.Track.Type.Text) {
                // Like mpv after ShellVideo's "sid no": subtitles stay off until the UI picks a track.
                int target = sid == -2 ? -1 : sid;
                if (player.getSpuTrack() != target) player.setSpuTrack(target);
            } else if (type == IMedia.Track.Type.Audio && aid >= 0 && player.getAudioTrack() != aid) {
                player.setAudioTrack(aid);
            }
        } catch (Throwable t) {
            Log.w(TAG, "track selection failed", t);
        }
    }

    /** VLC's names for the audio and subtitle tracks, e.g. "English - DTS 5.1", by track id. */
    private Map<Integer, String> trackNames() {
        Map<Integer, String> names = new HashMap<>();
        try {
            MediaPlayer.TrackDescription[][] lists = {player.getAudioTracks(), player.getSpuTracks()};
            for (MediaPlayer.TrackDescription[] list : lists) {
                if (list == null) continue;
                for (MediaPlayer.TrackDescription d : list) if (d.id >= 0) names.put(d.id, d.name);
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    /** True if audio track {id} is in the viewer's language, or no track is (any pick is fine then). */
    private boolean audioFits(int id) {
        IMedia media = null;
        try {
            media = player.getMedia();
            if (media == null) return true;
            Map<Integer, String> names = trackNames();
            boolean any = false, known = false;
            for (int i = 0; i < media.getTrackCount(); i++) {
                IMedia.Track t = media.getTrack(i);
                if (!(t instanceof IMedia.AudioTrack)) continue;
                String name = t.description != null && !t.description.isEmpty() ? t.description : names.get(t.id);
                boolean pref = AudioLang.isPreferred(t.language, name);
                if (t.id == id) {
                    known = true;
                    if (pref) return true;
                }
                any |= pref;
            }
            // Before the tracks are known, wait: pickDefaultAudio decides once playback starts.
            return known ? !any : false;
        } catch (Throwable t) {
            return true;
        } finally {
            if (media != null) media.release();
        }
    }

    private void sendSelection() {
        try {
            int a = player.getAudioTrack();
            int s = player.getSpuTrack();
            forward("aid", a >= 0 ? (Object) (long) a : "no");
            forward("sid", s >= 0 ? (Object) (long) s : "no");
            forward("vid", player.getVideoTrack() >= 0 ? (Object) (long) player.getVideoTrack() : "no");
        } catch (Throwable ignored) {
        }
    }

    private static String codecName(String c) {
        if (c == null) return null;
        String k = c.trim().toLowerCase();
        switch (k) {
            case "ssa": return "ass";
            case "subt": return "subrip";
            case "tx3g": return "mov_text";
            case "pgs": return "hdmv_pgs_subtitle";
            case "dvbs": return "dvb_subtitle";
            case "spu": return "dvd_subtitle";
            case "a52": return "ac3";
            case "eac3": return "eac3";
            case "mlp": case "trhd": return "truehd";
            case "mp4a": return "aac";
            case "hevc": case "h265": return "hevc";
            case "h264": case "avc1": return "h264";
            default: return k;
        }
    }

    private void sendTracks() {
        IMedia media = null;
        try {
            media = player.getMedia();
            if (media == null) return;
            Map<Integer, String> names = new HashMap<>();
            MediaPlayer.TrackDescription[][] lists = {player.getAudioTracks(), player.getSpuTracks()};
            for (MediaPlayer.TrackDescription[] list : lists) {
                if (list == null) continue;
                for (MediaPlayer.TrackDescription d : list) if (d.id >= 0) names.put(d.id, d.name);
            }
            int audio = player.getAudioTrack(), spu = player.getSpuTrack(), video = player.getVideoTrack();
            JSONArray out = new JSONArray();
            List<Integer> seen = new ArrayList<>();
            for (int i = 0; i < media.getTrackCount(); i++) {
                IMedia.Track t = media.getTrack(i);
                if (t == null || seen.contains(t.id)) continue;
                String type = t.type == IMedia.Track.Type.Audio ? "audio" : t.type == IMedia.Track.Type.Text ? "sub"
                        : t.type == IMedia.Track.Type.Video ? "video" : null;
                if (type == null) continue;
                // A track VLC can't decode isn't offered for selection.
                if (!"video".equals(type) && !names.isEmpty() && !names.containsKey(t.id)) continue;
                seen.add(t.id);
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("type", type);
                String title = t.description != null && !t.description.isEmpty() ? t.description : null;
                // The player picks audio by language code, so send the code even when the file only names it.
                String code = AudioLang.trackCode(t.language, title != null ? title : names.get(t.id));
                if (code != null) o.put("lang", code);
                else if (t.language != null && !t.language.isEmpty()) o.put("lang", t.language);
                if (title == null) {
                    String n = names.get(t.id);
                    // VLC's own names look like "Track 1 - [English]"; only keep ones with real information.
                    if (n != null && !n.matches("(?i)^(track|piste|spur) \\d+( - \\[[^\\]]*\\])?$")) title = n;
                }
                if (title != null) o.put("title", title);
                o.put("codec", codecName(t.codec));
                o.put("external", false);
                o.put("selected", t.id == (t.type == IMedia.Track.Type.Audio ? audio : t.type == IMedia.Track.Type.Text ? spu : video));
                if (t instanceof IMedia.AudioTrack) {
                    o.put("demux-channel-count", ((IMedia.AudioTrack) t).channels);
                    o.put("demux-samplerate", ((IMedia.AudioTrack) t).rate);
                }
                out.put(o);
            }
            forward("track-list", out);
        } catch (Throwable t) {
            Log.w(TAG, "track list failed", t);
        } finally {
            if (media != null) media.release();
        }
    }

    /** Worker thread. */
    private void updateVideo() {
        try {
            IMedia.VideoTrack v = player.getCurrentVideoTrack();
            if (v == null || v.width <= 0 || v.height <= 0) return;
            int dw = v.width;
            if (v.sarNum > 0 && v.sarDen > 0 && v.sarNum != v.sarDen) dw = (int) Math.round((double) v.width * v.sarNum / v.sarDen);
            JSONObject p = new JSONObject();
            p.put("w", v.width);
            p.put("h", v.height);
            p.put("dw", dw);
            p.put("dh", v.height);
            p.put("aspect", (double) dw / v.height);
            if (v.frameRateNum > 0 && v.frameRateDen > 0) p.put("fps", (double) v.frameRateNum / v.frameRateDen);
            videoFps = p.optDouble("fps", 0);
            boolean changed;
            synchronized (this) {
                changed = v.width != videoW || v.height != videoH;
            }
            forward("video-params", p);
            final int fdw = dw, fh = v.height;
            main.post(new Runnable() {
                @Override public void run() {
                    if (trackDw != fdw || trackH != fh) {
                        trackDw = fdw;
                        trackH = fh;
                        Log.i(TAG, "layout " + fdw + "x" + fh + " (buffer " + layoutW + "x" + layoutH + ", visible " + layoutVisW + "x" + layoutVisH + ")");
                        applyLayout();
                    }
                }
            });
            if (changed) {
                setVideoSize(v.width, v.height);
                Log.i(TAG, "playing " + codecName(v.codec) + " " + v.width + "x" + v.height + " decoder="
                        + (hardware ? "mediacodec" : "software") + " fps=" + p.optDouble("fps", 0));
            }
        } catch (Throwable t) {
            Log.w(TAG, "video info failed", t);
        }
    }

    private void setVideoSize(int w, int h) {
        boolean changed;
        synchronized (this) {
            changed = w != videoW || h != videoH;
            videoW = w;
            videoH = h;
        }
        if (w == 0 && h == 0) videoFps = 0;
        if (changed) sink.onVideoSize(w, h);
        if (w == 0 && h == 0) {
            forward("video-params", JSONObject.NULL);
            main.post(new Runnable() {
                @Override public void run() {
                    layoutVisW = layoutVisH = 0;
                    trackDw = trackH = 0;
                    applyLayout();
                }
            });
        }
    }

    private void forward(String name, Object data) {
        boolean wanted;
        synchronized (this) {
            if (data != null && data != JSONObject.NULL) lastValues.put(name, data);
            else lastValues.remove(name);
            wanted = observedByUi.contains(name);
        }
        if (!wanted) return;
        try {
            JSONObject change = new JSONObject();
            change.put("name", name);
            change.put("data", data);
            signal("mpv-prop-change", change);
        } catch (Throwable t) {
            Log.w(TAG, "forward " + name + " failed", t);
        }
    }

    private void sendVideoReady(int id, boolean ready) {
        try {
            JSONObject o = new JSONObject();
            o.put("loadId", id);
            o.put("ready", ready);
            signal("mpv-event-video-ready", o);
        } catch (Throwable ignored) {
        }
    }

    private void signal(String name, Object arg) throws Exception {
        sink.send(ShellProtocol.signal(name, arg));
    }
}
