package com.codio.tv;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import dev.jdtech.mpv.MPVLib;

/**
 * Speaks the same IPC protocol as Stremio's Windows shell (stremio-shell-ng), so the
 * Codio web UI's built-in "ShellVideo" player drives a real mpv on Android.
 *
 * Web UI -> shell:  {"id":0,"type":6,"args":["mpv-command",["loadfile",url]]} etc.
 * shell -> Web UI:  {"id":1,"object":"transport","type":1,"args":["mpv-prop-change",{"name":..,"data":..}]}
 */
final class MpvShell implements PlayerShell, MPVLib.EventObserver, MPVLib.LogObserver {
    private static final String TAG = "CodioMpv";

    interface Sink {
        void send(String message);
        void onPlayingChanged(boolean playing);
        /** Decoded video size, or 0x0 when no video is loaded (used to switch the TV to 4K output). */
        void onVideoSize(int width, int height);
    }

    // Observation formats, matching stremio-shell-ng.
    private static final int FMT_NONE = 0, FMT_STRING = 1, FMT_FLAG = 3, FMT_INT64 = 4, FMT_DOUBLE = 5;
    private static final Map<String, Integer> FORMATS = new HashMap<>();
    static {
        for (String p : new String[]{"pause", "paused-for-cache", "seeking", "eof-reached", "keepaspect", "mute", "idle-active"}) FORMATS.put(p, FMT_FLAG);
        for (String p : new String[]{"aid", "vid", "sid", "width", "height"}) FORMATS.put(p, FMT_INT64);
        for (String p : new String[]{"time-pos", "volume", "duration", "sub-delay", "sub-scale", "cache-buffering-state",
                "demuxer-cache-time", "sub-pos", "speed", "panscan"}) FORMATS.put(p, FMT_DOUBLE);
    }
    // String props whose value is JSON (mpv formats node properties as JSON).
    private static final Set<String> JSON_PROPS = new HashSet<>();
    static {
        JSON_PROPS.add("track-list");
        JSON_PROPS.add("video-params");
        JSON_PROPS.add("metadata");
    }
    // Props that the web UI sets for desktop mpv but that must stay as we configured them on Android.
    private static final Set<String> IGNORED_SET_PROPS = new HashSet<>();
    static {
        for (String p : new String[]{"vo", "osc", "input-default-bindings", "input-vo-keyboard",
                "gpu-context", "wid"}) IGNORED_SET_PROPS.add(p);
    }

    private final MPVLib mpv;
    private final Sink sink;
    private final Handler worker;
    private final Set<String> observedByUi = new HashSet<>();
    private final Set<String> observedNative = new HashSet<>();
    private final String hwdec;

    private Surface surface;
    private boolean surfaceAttached;

    // Playback state, guarded by "this".
    private int loadId = 0;
    private int pendingReadyLoadId = -1;
    private int expectedStops = 0;
    private boolean loadPending = false;
    private boolean fileActive = false;
    private boolean fileLoaded = false;
    private boolean eofReached = false;
    private boolean paused = false;
    private double timePos = 0, duration = 0;
    private int videoW = 0, videoH = 0;
    private long lastTimeSent = 0;
    private String lastError = null;

    private MpvShell(MPVLib mpv, Sink sink, Handler worker, String hwdec) {
        this.mpv = mpv;
        this.sink = sink;
        this.worker = worker;
        this.hwdec = hwdec;
    }

    /** Returns null when mpv can't be started on this device (the UI then falls back to its HTML5 player). */
    static MpvShell create(Context context, Sink sink, String configDir, String caFile, String fontsDir, boolean hardwareDecoding) {
        try {
            MPVLib lib = MPVLib.create(context);
            if (lib == null) return null;
            String hw = hardwareDecoding ? "mediacodec,mediacodec-copy" : "no";
            lib.setOptionString("config", "no");
            lib.setOptionString("config-dir", configDir);
            lib.setOptionString("vo", "null");
            lib.setOptionString("gpu-context", "android");
            lib.setOptionString("opengl-es", "yes");
            lib.setOptionString("hwdec", hw);
            lib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1");
            lib.setOptionString("ao", "audiotrack,opensles");
            lib.setOptionString("audio-set-media-role", "yes");
            lib.setOptionString("profile", "fast");
            lib.setOptionString("video-sync", "audio");
            lib.setOptionString("idle", "yes");
            lib.setOptionString("force-window", "no");
            lib.setOptionString("keep-open", "no");
            lib.setOptionString("input-default-bindings", "no");
            lib.setOptionString("input-vo-keyboard", "no");
            lib.setOptionString("osc", "no");
            lib.setOptionString("terminal", "no");
            lib.setOptionString("msg-level", "all=info");
            lib.setOptionString("cache", "yes");
            lib.setOptionString("demuxer-max-bytes", String.valueOf(128 * 1024 * 1024));
            lib.setOptionString("demuxer-max-back-bytes", String.valueOf(32 * 1024 * 1024));
            lib.setOptionString("network-timeout", "30");
            // If a connection drops mid-stream (e.g. while the cache was full and nothing was read), pick up where it left off.
            lib.setOptionString("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=5");
            lib.setOptionString("tls-verify", caFile != null ? "yes" : "no");
            if (caFile != null) lib.setOptionString("tls-ca-file", caFile);
            if (fontsDir != null) {
                lib.setOptionString("sub-fonts-dir", fontsDir);
                lib.setOptionString("osd-fonts-dir", fontsDir);
                lib.setOptionString("sub-font", "DejaVu Sans");
            }
            lib.setOptionString("sub-scale-with-window", "yes");
            lib.setOptionString("sub-use-margins", "no");
            lib.setOptionString("slang", "en,eng");
            lib.setOptionString("user-agent", "Codio/1.0 (Android TV)");
            lib.init();

            HandlerThread thread = new HandlerThread("codio-mpv");
            thread.start();
            MpvShell shell = new MpvShell(lib, sink, new Handler(thread.getLooper()), hw);
            lib.addObserver(shell);
            lib.addLogObserver(shell);
            for (String p : new String[]{"time-pos", "duration", "eof-reached", "pause", "width", "height"}) {
                shell.observe(p, false);
            }
            return shell;
        } catch (Throwable t) {
            Log.e(TAG, "mpv is not available on this device", t);
            return null;
        }
    }

    // ---------------------------------------------------------------- surface

    @Override public void attachSurface(final Surface s) {
        worker.post(new Runnable() {
            @Override public void run() {
                try {
                    surface = s;
                    mpv.attachSurface(s);
                    mpv.setOptionString("force-window", "yes");
                    mpv.setPropertyString("vo", "gpu-next,gpu");
                    surfaceAttached = true;
                } catch (Throwable t) {
                    Log.e(TAG, "attachSurface failed", t);
                }
            }
        });
    }

    @Override public void surfaceSize(final int width, final int height) {
        worker.post(new Runnable() {
            @Override public void run() {
                try {
                    mpv.setPropertyString("android-surface-size", width + "x" + height);
                } catch (Throwable t) {
                    Log.e(TAG, "surface size failed", t);
                }
            }
        });
    }

    @Override public void detachSurface() {
        // Must finish before the surface is gone, so run synchronously on the worker.
        final Object lock = new Object();
        final boolean[] done = {false};
        worker.post(new Runnable() {
            @Override public void run() {
                try {
                    if (surfaceAttached) {
                        mpv.setPropertyString("vo", "null");
                        mpv.setPropertyString("force-window", "no");
                        mpv.detachSurface();
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "detachSurface failed", t);
                }
                surfaceAttached = false;
                surface = null;
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            long until = System.currentTimeMillis() + 2000;
            while (!done[0] && System.currentTimeMillis() < until) {
                try {
                    lock.wait(200);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
    }

    @Override public void pause() {
        worker.post(new Runnable() {
            @Override public void run() {
                try {
                    mpv.setPropertyString("pause", "yes");
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @Override public void destroy() {
        worker.post(new Runnable() {
            @Override public void run() {
                try {
                    mpv.removeObserver(MpvShell.this);
                    mpv.removeLogObserver(MpvShell.this);
                    mpv.destroy();
                } catch (Throwable t) {
                    Log.e(TAG, "destroy failed", t);
                }
                worker.getLooper().quitSafely();
            }
        });
    }

    @Override public String hwdec() {
        return hwdec;
    }

    @Override public String engine() {
        return "mpv";
    }

    // ---------------------------------------------------------------- web UI -> mpv

    /** Handles one invoke from the web UI. Returns true if it was an mpv message. */
    @Override public boolean handle(final String method, final Object params) {
        switch (method) {
            case "mpv-observe-prop":
                if (params instanceof String) {
                    final String name = (String) params;
                    worker.post(new Runnable() {
                        @Override public void run() {
                            observe(name, true);
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

    private void observe(String name, boolean fromUi) {
        if (fromUi) {
            synchronized (this) {
                observedByUi.add(name);
            }
        }
        synchronized (this) {
            if (observedNative.contains(name)) {
                if (fromUi) replayValue(name);
                return;
            }
            observedNative.add(name);
        }
        Integer format = FORMATS.get(name);
        try {
            mpv.observeProperty(name, format != null ? format : FMT_STRING);
        } catch (Throwable t) {
            Log.w(TAG, "observe " + name + " failed", t);
        }
    }

    /** A late observer (from the UI) still needs the current value. */
    private void replayValue(String name) {
        try {
            Integer format = FORMATS.get(name);
            if (format == null || format == FMT_STRING) {
                String v = mpv.getPropertyString(name);
                if (v != null) eventProperty(name, v);
            } else if (format == FMT_FLAG) {
                Boolean v = mpv.getPropertyBoolean(name);
                if (v != null) eventProperty(name, v.booleanValue());
            } else if (format == FMT_DOUBLE) {
                Double v = mpv.getPropertyDouble(name);
                if (v != null) eventProperty(name, v.doubleValue());
            } else if (format == FMT_INT64) {
                Integer v = mpv.getPropertyInt(name);
                if (v != null) eventProperty(name, (long) v.intValue());
            }
        } catch (Throwable ignored) {
        }
    }

    private void setProp(String name, Object value) {
        if (IGNORED_SET_PROPS.contains(name)) return;
        if ("hwdec".equals(name)) {
            // Settings > Player > Hardware decoding: "no" turns it off, anything else uses Android's MediaCodec.
            value = "no".equals(value) ? "no" : hwdec;
        }
        String v;
        if (value instanceof Boolean) {
            v = ((Boolean) value) ? "yes" : "no";
        } else if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            v = (d == Math.rint(d) && Math.abs(d) < 1e15) ? String.valueOf((long) d) : String.valueOf(d);
        } else if (value == null || value == JSONObject.NULL) {
            return;
        } else {
            v = String.valueOf(value);
        }
        try {
            mpv.setPropertyString(name, v);
        } catch (Throwable t) {
            Log.w(TAG, "set " + name + "=" + v + " failed", t);
        }
    }

    /** Origin of the app's local server; https media goes through its /stream/ proxy (see LocalServer). */
    static volatile String streamProxy = null;

    private static String viaProxy(String url) {
        String origin = streamProxy;
        if (origin == null || url == null || !url.regionMatches(true, 0, "https://", 0, 8)) return url;
        return origin + LocalServer.streamPath(url);
    }

    private void command(String[] cmd) {
        if (cmd.length == 0) return;
        String name = cmd[0];
        if (cmd.length > 1 && ("loadfile".equals(name) || "sub-add".equals(name) || "audio-add".equals(name))) {
            // mpv's own TLS (mbedtls) fails against some debrid/CDN hosts; Android's TLS stack doesn't.
            cmd[1] = viaProxy(cmd[1]);
        }
        if ("loadfile".equals(name) || "stop".equals(name)) {
            int id;
            synchronized (this) {
                if (fileActive || loadPending) {
                    expectedStops++;
                } else {
                    expectedStops = 0;
                }
                loadId++;
                id = loadId;
                if ("loadfile".equals(name)) {
                    loadPending = true;
                    pendingReadyLoadId = id;
                    lastError = null;
                } else {
                    loadPending = false;
                    pendingReadyLoadId = -1;
                }
            }
            sendVideoReady(id, false);
        }
        try {
            mpv.command(cmd);
        } catch (Throwable t) {
            Log.w(TAG, "command " + name + " failed", t);
        }
    }

    // ---------------------------------------------------------------- mpv -> web UI

    @Override public void eventProperty(String name) {
        track(name, null);
        forward(name, JSONObject.NULL);
    }

    @Override public void eventProperty(String name, long value) {
        track(name, value);
        forward(name, value);
    }

    @Override public void eventProperty(String name, double value) {
        track(name, value);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            forward(name, JSONObject.NULL);
            return;
        }
        if ("time-pos".equals(name)) {
            long now = System.currentTimeMillis();
            if (now - lastTimeSent < 250) return;
            lastTimeSent = now;
        }
        forward(name, value);
    }

    @Override public void eventProperty(String name, boolean value) {
        track(name, value);
        forward(name, value);
    }

    @Override public void eventProperty(String name, String value) {
        Object data = value;
        if (JSON_PROPS.contains(name)) {
            try {
                data = new JSONTokener(value).nextValue();
            } catch (Throwable t) {
                data = JSONObject.NULL;
            }
        }
        forward(name, data);
    }

    private void track(String name, Object value) {
        boolean playingChanged = false;
        boolean playing = false;
        boolean sizeChanged = false;
        int w = 0, h = 0;
        synchronized (this) {
            switch (name) {
                case "width":
                case "height": {
                    int v = value instanceof Long ? (int) (long) (Long) value : 0;
                    if ("width".equals(name)) { sizeChanged = v != videoW; videoW = v; }
                    else { sizeChanged = v != videoH; videoH = v; }
                    w = videoW;
                    h = videoH;
                    break;
                }
                case "time-pos":
                    // These become unavailable as the file closes, just before END_FILE; keep the last real value.
                    if (value instanceof Double) timePos = (Double) value;
                    break;
                case "duration":
                    if (value instanceof Double) duration = (Double) value;
                    break;
                case "eof-reached":
                    if (Boolean.TRUE.equals(value)) eofReached = true;
                    break;
                case "pause":
                    boolean before = !paused && fileActive;
                    paused = Boolean.TRUE.equals(value);
                    playing = !paused && fileActive;
                    playingChanged = before != playing;
                    break;
                default:
                    break;
            }
        }
        if (playingChanged) sink.onPlayingChanged(playing);
        if (sizeChanged && (w > 0) == (h > 0)) sink.onVideoSize(w, h);
    }

    private void forward(String name, Object data) {
        boolean wanted;
        synchronized (this) {
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

    @Override public void event(int eventId) {
        switch (eventId) {
            case 6: { // MPV_EVENT_START_FILE
                synchronized (this) {
                    fileActive = true;
                    loadPending = false;
                    fileLoaded = false;
                    eofReached = false;
                    timePos = 0;
                    duration = 0;
                }
                break;
            }
            case 8: { // MPV_EVENT_FILE_LOADED
                synchronized (this) {
                    fileLoaded = true;
                }
                sink.onPlayingChanged(!paused);
                break;
            }
            case 21: { // MPV_EVENT_PLAYBACK_RESTART (first frame shown / seek done)
                int ready = -1;
                synchronized (this) {
                    if (fileLoaded && pendingReadyLoadId != -1) {
                        ready = pendingReadyLoadId;
                        pendingReadyLoadId = -1;
                    }
                }
                if (ready != -1) {
                    sendVideoReady(ready, true);
                    logDecoder();
                }
                break;
            }
            case 7: { // MPV_EVENT_END_FILE
                String reason;
                boolean critical = false;
                String message = null;
                synchronized (this) {
                    fileActive = false;
                    if (expectedStops > 0) {
                        expectedStops--;
                        reason = "stop";
                    } else if (!fileLoaded) {
                        reason = "error";
                        critical = true;
                        message = lastError != null ? lastError : "This stream could not be opened.";
                    } else if (eofReached || (duration > 0 && timePos >= duration - 15)) {
                        reason = "eof";
                    } else {
                        reason = "error";
                        message = lastError != null ? lastError : "Playback stopped unexpectedly.";
                    }
                    Log.i(TAG, "end-file: " + reason + " at " + timePos + "/" + duration + " eof=" + eofReached
                            + (message != null ? " (" + message + ")" : ""));
                    fileLoaded = false;
                    pendingReadyLoadId = -1;
                }
                sink.onPlayingChanged(false);
                try {
                    JSONObject ended = new JSONObject();
                    ended.put("reason", reason);
                    if (message != null) {
                        JSONObject error = new JSONObject();
                        error.put("message", message);
                        error.put("critical", critical);
                        // Same codes the HTML5 player uses: 83 "not supported" (never started), 81 network.
                        error.put("code", critical ? 83 : 81);
                        ended.put("error", error);
                    }
                    signal("mpv-event-ended", ended);
                } catch (Throwable t) {
                    Log.w(TAG, "end-file signal failed", t);
                }
                break;
            }
            default:
                break;
        }
    }

    @Override public void logMessage(String prefix, int level, String text) {
        if (level <= 20) { // error or fatal
            String line = prefix + ": " + (text != null ? text.trim() : "");
            Log.w(TAG, line);
            synchronized (this) {
                if (lastError == null && (fileActive || loadPending) && explainsFailure(prefix, text)) lastError = line;
            }
        } else if (level <= 30) {
            Log.i(TAG, prefix + ": " + (text != null ? text.trim() : ""));
        }
    }

    /** One line per started video, e.g. "playing hevc 3840x2160 decoder=mediacodec", for adb logcat troubleshooting. */
    private void logDecoder() {
        try {
            Log.i(TAG, "playing " + mpv.getPropertyString("video-codec") + " " + mpv.getPropertyString("width") + "x"
                    + mpv.getPropertyString("height") + " decoder=" + mpv.getPropertyString("hwdec-current")
                    + " audio=" + mpv.getPropertyString("audio-codec-name") + " out=" + mpv.getPropertyString("current-ao"));
        } catch (Throwable ignored) {
        }
    }

    /** Decoder/output probing errors are recovered from (mpv falls back), so they don't explain a failed stream. */
    private static boolean explainsFailure(String prefix, String text) {
        String p = prefix != null ? prefix : "";
        String[] noise = {"vd", "ad", "vo", "ao", "ffmpeg/video", "ffmpeg/audio", "sub", "osd", "lua", "ytdl", "auto_profiles"};
        for (String n : noise) {
            if (p.equals(n) || p.startsWith(n + "/")) return false;
        }
        String t = text != null ? text : "";
        return !t.contains("audio device") && !t.contains("audio output");
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
