package com.codio.tv;

import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.media.TimedText;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Real Dolby Vision: plays a Dolby Vision source with Android's own MediaPlayer, which hands the stream to the TV's
 * Dolby Vision decoder (VLC only decodes the HEVC base layer). Speaks the same mpv protocol as VlcShell.
 * Only used through HybridShell, and only after probe() said this TV can decode this exact file.
 */
final class DvShell {
    private static final String TAG = "CodioDV";

    /** What probe() found in a file. */
    static final class Probe {
        final List<MediaFormat> tracks = new ArrayList<>();
        double fps;
    }

    interface Listener {
        /** The file couldn't start: play it with VLC instead. */
        void startFailed(String[] cmd);
    }

    private final MpvShell.Sink sink;
    private Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler worker;
    private final SurfaceView view;
    private final TextView subs;
    private final View vlcView, vlcSubs;
    private final File cacheDir;

    private final Set<String> observed = new HashSet<>();
    private MediaPlayer mp;
    private Probe probe;
    private String[] cmd;
    private int loadId = 1000000; // apart from VlcShell's ids
    private volatile boolean active;
    private boolean prepared, started, wantPause, surfaceReady;
    private double startAt;
    private int aid = -1, sid = -1;
    private long lastTime = -1, lastMove;
    private boolean cacheShown = true;
    private final List<String> pendingSubs = new ArrayList<>();

    DvShell(MpvShell.Sink sink, Listener listener, SurfaceView view, TextView subs, View vlcView, View vlcSubs, File cacheDir) {
        this.sink = sink;
        this.listener = listener;
        this.view = view;
        this.subs = subs;
        this.vlcView = vlcView;
        this.vlcSubs = vlcSubs;
        this.cacheDir = cacheDir;
        HandlerThread t = new HandlerThread("codio-dv");
        t.start();
        worker = new Handler(t.getLooper());
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                worker.post(new Runnable() {
                    @Override public void run() {
                        surfaceReady = true;
                        if (mp != null) {
                            try {
                                mp.setDisplay(view.getHolder());
                            } catch (Throwable ignored) {
                            }
                        }
                        if (active && mp == null) open();
                    }
                });
            }

            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
            }

            @Override public void surfaceDestroyed(SurfaceHolder h) {
                // Called on the main thread; the player must let go of the surface before this returns.
                surfaceReady = false;
                MediaPlayer p = mp;
                if (p != null) {
                    try {
                        p.setDisplay(null);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    // ---------------------------------------------------------------- can this TV do it?

    private static Boolean supported;


    /** The screen takes Dolby Vision and there's a Dolby Vision decoder apps can use. */
    static synchronized boolean deviceSupports(android.content.Context c) {
        if (supported != null) return supported;
        boolean screen = false, decoder = false;
        try {
            android.view.Display d = ((android.view.WindowManager) c.getSystemService(android.content.Context.WINDOW_SERVICE)).getDefaultDisplay();
            android.view.Display.HdrCapabilities caps = d.getHdrCapabilities();
            if (caps != null) for (int t : caps.getSupportedHdrTypes()) if (t == android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) screen = true;
            for (android.media.MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (i.isEncoder()) continue;
                for (String type : i.getSupportedTypes()) if (MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION.equalsIgnoreCase(type)) decoder = true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "capability check failed", t);
        }
        supported = screen && decoder;
        Log.e(TAG, "Dolby Vision: screen " + screen + ", decoder " + decoder);
        return supported;
    }

    /**
     * Opens the file's header and checks that its video really is Dolby Vision, that this TV has a decoder for that
     * profile (no Profile 7 here), and that at least one audio track can be decoded. Null = use VLC. Blocks.
     */
    static Probe probe(String url) {
        MediaExtractor x = new MediaExtractor();
        long t0 = SystemClock.uptimeMillis();
        try {
            x.setDataSource(viaProxy(url, false), new HashMap<String, String>());
            MediaCodecList codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            Probe p = new Probe();
            boolean dv = false, audio = false;
            for (int i = 0; i < x.getTrackCount(); i++) {
                MediaFormat f = x.getTrackFormat(i);
                p.tracks.add(f);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/")) {
                    if (MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION.equals(mime) && decodable(codecs, f)) dv = true;
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        try {
                            p.fps = f.getInteger(MediaFormat.KEY_FRAME_RATE);
                        } catch (ClassCastException e) {
                            p.fps = f.getFloat(MediaFormat.KEY_FRAME_RATE);
                        }
                    }
                    Log.e(TAG, "video track " + i + ": " + mime + " profile " + (f.containsKey(MediaFormat.KEY_PROFILE) ? f.getInteger(MediaFormat.KEY_PROFILE) : -1) + " -> " + (dv ? "Dolby Vision decoder" : "no"));
                } else if (mime.startsWith("audio/")) {
                    boolean ok = playable(codecs, f);
                    audio |= ok;
                    Log.e(TAG, "audio track " + i + ": " + mime + (ok ? "" : " (can't decode)"));
                }
            }
            Log.e(TAG, "probe " + (SystemClock.uptimeMillis() - t0) + " ms: dv=" + dv + " audio=" + audio);
            return dv && audio ? p : null;
        } catch (Throwable t) {
            Log.e(TAG, "probe failed after " + (SystemClock.uptimeMillis() - t0) + " ms: " + t);
            return null;
        } finally {
            x.release();
        }
    }

    /** An audio track the TV can decode, or send as it is to its own Dolby/DTS audio (TV speakers, ARC). */
    private static boolean playable(MediaCodecList codecs, MediaFormat f) {
        if (decodable(codecs, f)) return true;
        String mime = f.getString(MediaFormat.KEY_MIME);
        int enc;
        if ("audio/ac3".equals(mime)) enc = android.media.AudioFormat.ENCODING_AC3;
        else if ("audio/eac3".equals(mime)) enc = android.media.AudioFormat.ENCODING_E_AC3;
        else if ("audio/eac3-joc".equals(mime)) enc = android.media.AudioFormat.ENCODING_E_AC3_JOC;
        else if ("audio/ac4".equals(mime)) enc = android.media.AudioFormat.ENCODING_AC4;
        else if ("audio/true-hd".equals(mime)) enc = android.media.AudioFormat.ENCODING_DOLBY_TRUEHD;
        else if ("audio/vnd.dts".equals(mime)) enc = android.media.AudioFormat.ENCODING_DTS;
        else if ("audio/vnd.dts.hd".equals(mime)) enc = android.media.AudioFormat.ENCODING_DTS_HD;
        else return false;
        if (android.os.Build.VERSION.SDK_INT < 29) return false;
        android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build();
        int[] masks = {android.media.AudioFormat.CHANNEL_OUT_5POINT1, android.media.AudioFormat.CHANNEL_OUT_STEREO,
                android.media.AudioFormat.CHANNEL_OUT_7POINT1_SURROUND};
        for (int mask : masks) {
            try {
                android.media.AudioFormat af = new android.media.AudioFormat.Builder().setEncoding(enc)
                        .setSampleRate(48000).setChannelMask(mask).build();
                if (android.media.AudioTrack.isDirectPlaybackSupported(af, attrs)) {
                    Log.e(TAG, mime + ": the TV plays it directly");
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        // JOC (Atmos) is plain E-AC3 underneath.
        if (enc == android.media.AudioFormat.ENCODING_E_AC3_JOC) {
            MediaFormat plain = new MediaFormat(f);
            plain.setString(MediaFormat.KEY_MIME, "audio/eac3");
            return playable(codecs, plain);
        }
        return false;
    }

    /** Through Codio's local proxy: it remembers the debrid redirect, and (buffered) reads far ahead of the player. */
    private static String viaProxy(String url, boolean buffered) {
        String origin = MpvShell.streamProxy;
        if (origin == null || url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return url;
        return origin + (buffered ? LocalServer.bufferedStreamPath(url) : LocalServer.streamPath(url));
    }

    private static boolean decodable(MediaCodecList codecs, MediaFormat f) {
        try {
            // findDecoderForFormat refuses formats that carry a frame rate on some Android versions.
            MediaFormat c = new MediaFormat(f);
            if (android.os.Build.VERSION.SDK_INT >= 29) c.removeKey(MediaFormat.KEY_FRAME_RATE);
            return codecs.findDecoderForFormat(c) != null;
        } catch (Throwable t) {
            try {
                return codecs.findDecoderForFormat(f) != null;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    // ---------------------------------------------------------------- protocol (any thread)

    void observe(String name) {
        synchronized (observed) {
            observed.add(name);
        }
    }

    boolean isActive() {
        return active;
    }

    void load(final String[] cmd, final Probe p) {
        worker.post(new Runnable() {
            @Override public void run() {
                release();
                DvShell.this.cmd = cmd;
                probe = p;
                startAt = 0;
                for (int i = 2; i < cmd.length; i++) {
                    int idx = cmd[i].indexOf("start=");
                    if (idx >= 0) startAt = num(cmd[i].substring(idx + 6).replace("+", ""), 0);
                }
                active = true;
                prepared = started = wantPause = false;
                aid = sid = -1;
                lastTime = -1;
                pendingSubs.clear();
                loadId++;
                videoReady(false);
                forward("paused-for-cache", true);
                forward("pause", false);
                main.post(new Runnable() {
                    @Override public void run() {
                        vlcView.setVisibility(View.INVISIBLE);
                        vlcSubs.setVisibility(View.INVISIBLE);
                        view.setVisibility(View.VISIBLE);
                        subs.setText("");
                        subs.setVisibility(View.VISIBLE);
                    }
                });
                if (surfaceReady) open();
            }
        });
    }

    /** Worker. */
    private void open() {
        final MediaPlayer p = new MediaPlayer();
        mp = p;
        try {
            p.setDisplay(view.getHolder());
            p.setScreenOnWhilePlaying(true);
            p.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build());
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) {
                    if (m != mp) return;
                    prepared = true;
                    Log.e(TAG, "prepared, " + m.getDuration() + " ms");
                    forward("duration", m.getDuration() / 1000.0);
                    pickAudio();
                    for (String s : pendingSubs) addSub(s);
                    pendingSubs.clear();
                    if (startAt > 0) m.seekTo((long) (startAt * 1000), MediaPlayer.SEEK_CLOSEST_SYNC);
                    if (!wantPause) m.start();
                    onStarted();
                }
            });
            p.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override public void onVideoSizeChanged(MediaPlayer m, int w, int h) {
                    if (m != mp || w <= 0 || h <= 0) return;
                    videoSize(w, h);
                }
            });
            p.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override public boolean onInfo(MediaPlayer m, int what, int extra) {
                    if (m != mp) return false;
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START || what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                        Log.e(TAG, what == MediaPlayer.MEDIA_INFO_BUFFERING_START ? "player buffering" : "player buffering done");
                    } else if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        Log.e(TAG, "picture running");
                        videoReady(true);
                    }
                    return false;
                }
            });
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) {
                    if (m == mp) ended(null);
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    if (m != mp) return true;
                    Log.e(TAG, "error " + what + "/" + extra + (started ? " while playing" : " before the start"));
                    if (!started) {
                        final String[] c = cmd;
                        stopQuietly();
                        listener.startFailed(c);
                    } else {
                        ended("Playback stopped unexpectedly.");
                    }
                    return true;
                }
            });
            p.setOnTimedTextListener(new MediaPlayer.OnTimedTextListener() {
                @Override public void onTimedText(MediaPlayer m, final TimedText text) {
                    final String s = text != null && text.getText() != null ? text.getText() : "";
                    main.post(new Runnable() {
                        @Override public void run() {
                            subs.setText(s.trim());
                        }
                    });
                }
            });
            p.setDataSource(viaProxy(cmd[1], true));
            p.prepareAsync();
            worker.removeCallbacks(prepareTimeout);
            worker.postDelayed(prepareTimeout, 25000);
            Log.e(TAG, "opening with the TV's Dolby Vision decoder");
        } catch (Throwable t) {
            Log.e(TAG, "open failed", t);
            final String[] c = cmd;
            stopQuietly();
            listener.startFailed(c);
        }
    }

    /** A file that never gets ready goes to VLC rather than leaving the viewer on the loading screen. */
    private final Runnable prepareTimeout = new Runnable() {
        @Override public void run() {
            if (!active || prepared) return;
            Log.e(TAG, "not ready after 25 s");
            final String[] c = cmd;
            stopQuietly();
            listener.startFailed(c);
        }
    };

    private void onStarted() {
        started = true;
        lastMove = SystemClock.uptimeMillis();
        cacheShown = false;
        forward("pause", wantPause);
        forward("paused-for-cache", false);
        sink.onPlayingChanged(!wantPause);
        sendTracks();
        worker.removeCallbacks(ticker);
        worker.post(ticker);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            MediaPlayer p = mp;
            if (p == null || !active) return;
            try {
                long t = p.getCurrentPosition();
                long now = SystemClock.uptimeMillis();
                if (t != lastTime) {
                    lastTime = t;
                    lastMove = now;
                    forward("time-pos", t / 1000.0);
                }
                // The loading spinner follows the clock: this TV's player doesn't always say when buffering ends.
                boolean stalled = !wantPause && prepared && now - lastMove > 1200;
                if (stalled != cacheShown) {
                    cacheShown = stalled;
                    forward("paused-for-cache", stalled);
                    if (stalled) Log.e(TAG, "picture waiting for data at " + t / 1000 + " s");
                }
            } catch (Throwable ignored) {
            }
            worker.postDelayed(this, 250);
        }
    };

    void setProp(final String name, final Object value) {
        worker.post(new Runnable() {
            @Override public void run() {
                MediaPlayer p = mp;
                try {
                    switch (name) {
                        case "pause": {
                            boolean want = truthy(value);
                            wantPause = want;
                            lastMove = SystemClock.uptimeMillis();
                            if (p != null && prepared) {
                                if (want && p.isPlaying()) p.pause();
                                else if (!want && !p.isPlaying()) p.start();
                                sink.onPlayingChanged(!want);
                            }
                            forward("pause", want);
                            break;
                        }
                        case "time-pos": {
                            double s = num(value, -1);
                            if (s >= 0 && p != null && prepared) p.seekTo((long) (s * 1000), MediaPlayer.SEEK_CLOSEST_SYNC);
                            lastMove = SystemClock.uptimeMillis();
                            if (s >= 0) forward("time-pos", s);
                            forward("seeking", false);
                            break;
                        }
                        case "volume": {
                            float v = (float) Math.max(0, Math.min(1, num(value, 100) / 100));
                            if (p != null) p.setVolume(v, v);
                            forward("volume", num(value, 100));
                            break;
                        }
                        case "aid": {
                            int id = trackId(value);
                            if (id >= 0 && p != null && prepared) {
                                p.selectTrack(id);
                                aid = id;
                                sendTracks();
                            }
                            break;
                        }
                        case "sid": {
                            int id = trackId(value);
                            boolean auto = SystemClock.uptimeMillis() - VlcShell.userPickAt > 4000;
                            if (id >= 0 && auto) break; // like VlcShell: subtitles stay off until the viewer picks one
                            if (p != null && prepared) {
                                if (sid >= 0) {
                                    try {
                                        p.deselectTrack(sid);
                                    } catch (Throwable ignored) {
                                    }
                                }
                                if (id >= 0) p.selectTrack(id);
                            }
                            sid = id;
                            if (id < 0) {
                                main.post(new Runnable() {
                                    @Override public void run() {
                                        subs.setText("");
                                    }
                                });
                            }
                            sendTracks();
                            break;
                        }
                        default:
                            break;
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "set " + name + " failed", t);
                }
            }
        });
    }

    void command(final String[] c) {
        worker.post(new Runnable() {
            @Override public void run() {
                try {
                    switch (c[0]) {
                        case "stop":
                            Log.e(TAG, "stop from the page");
                            stop();
                            break;
                        case "seek":
                            if (c.length > 1 && mp != null && prepared) {
                                double s = num(c[1], 0);
                                boolean abs = c.length > 2 && c[2].startsWith("absolute");
                                long target = abs ? (long) (s * 1000) : mp.getCurrentPosition() + (long) (s * 1000);
                                mp.seekTo(Math.max(0, target), MediaPlayer.SEEK_CLOSEST_SYNC);
                            }
                            break;
                        case "sub-add":
                            if (c.length > 1) {
                                if (prepared) addSub(c[1]);
                                else pendingSubs.add(c[1]);
                            }
                            break;
                        default:
                            break;
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "command " + c[0] + " failed", t);
                }
            }
        });
    }

    void pause() {
        setProp("pause", true);
    }

    /** Stop and hand the screen back to VLC, telling the UI the file is gone. */
    void stop() {
        worker.post(new Runnable() {
            @Override public void run() {
                if (!active) return;
                stopQuietly();
                videoReady(false);
                sink.onPlayingChanged(false);
            }
        });
    }

    /** Worker. Releases the player and gives the screen back to VLC, without telling the UI. */
    private void stopQuietly() {
        active = false;
        com.codio.tv.ui.PlayerState.playingDv = false;
        release();
        VlcShell.videoFps = 0;
        sink.onVideoSize(0, 0);
        main.post(new Runnable() {
            @Override public void run() {
                if (active) return;
                view.setVisibility(View.GONE);
                subs.setText("");
                subs.setVisibility(View.GONE);
                vlcView.setVisibility(View.VISIBLE);
                vlcSubs.setVisibility(View.VISIBLE);
            }
        });
    }

    private void release() {
        worker.removeCallbacks(ticker);
        worker.removeCallbacks(prepareTimeout);
        MediaPlayer p = mp;
        mp = null;
        prepared = started = false;
        if (p != null) {
            try {
                p.reset();
            } catch (Throwable ignored) {
            }
            p.release();
        }
    }

    void destroy() {
        worker.post(new Runnable() {
            @Override public void run() {
                release();
                worker.getLooper().quitSafely();
            }
        });
    }

    // ---------------------------------------------------------------- player -> UI (worker)

    private void ended(String error) {
        if (!active) return;
        long t = mp != null ? safePos() : 0;
        Log.e(TAG, "ended " + (error != null ? error : "eof") + " at " + t);
        stopQuietly();
        sink.onPlayingChanged(false);
        forward("eof-reached", error == null);
        try {
            JSONObject o = new JSONObject();
            o.put("reason", error == null ? "eof" : "error");
            if (error != null) {
                JSONObject err = new JSONObject();
                err.put("message", error);
                err.put("critical", false);
                err.put("code", 81);
                o.put("error", err);
            }
            sink.send(ShellProtocol.signal("mpv-event-ended", o));
        } catch (Throwable ignored) {
        }
    }

    private long safePos() {
        try {
            return mp.getCurrentPosition();
        } catch (Throwable t) {
            return 0;
        }
    }

    private void videoSize(final int w, final int h) {
        try {
            JSONObject p = new JSONObject();
            p.put("w", w);
            p.put("h", h);
            p.put("dw", w);
            p.put("dh", h);
            p.put("aspect", (double) w / h);
            if (probe != null && probe.fps > 0) p.put("fps", probe.fps);
            forward("video-params", p);
        } catch (Throwable ignored) {
        }
        VlcShell.videoFps = probe != null ? probe.fps : 0;
        sink.onVideoSize(w, h);
        com.codio.tv.ui.PlayerState.playingDv = true;
        Log.e(TAG, "playing Dolby Vision " + w + "x" + h);
        main.post(new Runnable() {
            @Override public void run() {
                View parent = (View) view.getParent();
                int pw = parent.getWidth(), ph = parent.getHeight();
                if (pw <= 0 || ph <= 0) return;
                int vw = pw, vh = (int) Math.round((double) pw * h / w);
                if (vh > ph) {
                    vh = ph;
                    vw = (int) Math.round((double) ph * w / h);
                }
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(vw, vh, Gravity.CENTER);
                view.setLayoutParams(lp);
            }
        });
    }

    private void pickAudio() {
        try {
            MediaPlayer.TrackInfo[] info = mp.getTrackInfo();
            MediaCodecList codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            int best = -1, bestScore = -1;
            for (int pass = 0; pass < 2 && best < 0; pass++) {
                for (int i = 0; i < info.length; i++) {
                    if (info[i].getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) continue;
                    String lang = info[i].getLanguage() != null ? info[i].getLanguage().toLowerCase() : "";
                    if (pass == 0 && !lang.startsWith("en")) continue;
                    MediaFormat f = i < probe.tracks.size() ? probe.tracks.get(i) : null;
                    if (f != null && !playable(codecs, f)) continue;
                    int ch = f != null && f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
                    if (ch > bestScore) {
                        bestScore = ch;
                        best = i;
                    }
                }
            }
            if (best >= 0) {
                mp.selectTrack(best);
                aid = best;
                Log.e(TAG, "audio track " + best);
            }
        } catch (Throwable t) {
            Log.e(TAG, "audio pick failed", t);
        }
    }

    private void sendTracks() {
        MediaPlayer p = mp;
        if (p == null) return;
        try {
            MediaPlayer.TrackInfo[] info = p.getTrackInfo();
            int selAudio = p.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO);
            int selText = p.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
            if (selAudio >= 0) aid = selAudio;
            JSONArray out = new JSONArray();
            for (int i = 0; i < info.length; i++) {
                int type = info[i].getTrackType();
                String t = type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO ? "audio"
                        : type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT ? "sub"
                        : type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO ? "video" : null;
                if (t == null) continue;
                JSONObject o = new JSONObject();
                o.put("id", i);
                o.put("type", t);
                String lang = info[i].getLanguage();
                if (lang != null && !lang.isEmpty() && !"und".equals(lang)) o.put("lang", lang);
                MediaFormat f = probe != null && i < probe.tracks.size() ? probe.tracks.get(i) : null;
                if (f != null) {
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    o.put("codec", codecName(mime));
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) o.put("demux-channel-count", f.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                } else if ("sub".equals(t)) {
                    o.put("codec", "subrip");
                }
                o.put("external", false);
                o.put("selected", "audio".equals(t) ? i == aid : "sub".equals(t) ? i == selText && sid >= 0 : true);
                out.put(o);
            }
            forward("track-list", out);
            forward("aid", aid >= 0 ? (Object) (long) aid : "no");
            forward("sid", sid >= 0 ? (Object) (long) sid : "no");
        } catch (Throwable t) {
            Log.e(TAG, "track list failed", t);
        }
    }

    private static String codecName(String mime) {
        if (mime == null) return null;
        switch (mime) {
            case "audio/ac3": return "ac3";
            case "audio/eac3": case "audio/eac3-joc": return "eac3";
            case "audio/true-hd": return "truehd";
            case "audio/mp4a-latm": return "aac";
            case "audio/vnd.dts": case "audio/vnd.dts.hd": return "dts";
            case "audio/opus": return "opus";
            case "audio/flac": return "flac";
            case "audio/mpeg": return "mp3";
            case "video/dolby-vision": return "dvhe";
            case "video/hevc": return "hevc";
            default: return mime.substring(mime.indexOf('/') + 1);
        }
    }

    /** External subtitles: MediaPlayer only reads SubRip files from disk, so fetch (and convert WebVTT). */
    private void addSub(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            String s = b.toString("UTF-8");
            if (s.startsWith("﻿")) s = s.substring(1);
            if (s.startsWith("WEBVTT")) s = vttToSrt(s);
            File f = new File(cacheDir, "dv-sub-" + (url.hashCode() & 0x7fffffff) + ".srt");
            FileOutputStream o = new FileOutputStream(f);
            o.write(s.getBytes("UTF-8"));
            o.close();
            mp.addTimedTextSource(f.getAbsolutePath(), MediaPlayer.MEDIA_MIMETYPE_TEXT_SUBRIP);
            sendTracks();
        } catch (Throwable t) {
            Log.e(TAG, "subtitle failed: " + t);
        }
    }

    private static String vttToSrt(String v) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (String block : v.replace("\r", "").split("\n\n+")) {
            String[] lines = block.split("\n");
            int at = -1;
            for (int i = 0; i < lines.length; i++) if (lines[i].contains("-->")) { at = i; break; }
            if (at < 0) continue;
            String time = lines[at].replaceAll("(\\d)\\.(\\d{3})", "$1,$2").replaceAll(" (align|line|position|size|vertical):\\S+", "");
            String[] parts = time.split("-->");
            out.append(++n).append('\n').append(hms(parts[0].trim())).append(" --> ").append(hms(parts[1].trim())).append('\n');
            for (int i = at + 1; i < lines.length; i++) out.append(lines[i]).append('\n');
            out.append('\n');
        }
        return out.toString();
    }

    private static String hms(String t) {
        return t.split(":").length == 2 ? "00:" + t : t;
    }

    private void videoReady(boolean ready) {
        try {
            JSONObject o = new JSONObject();
            o.put("loadId", loadId);
            o.put("ready", ready);
            sink.send(ShellProtocol.signal("mpv-event-video-ready", o));
        } catch (Throwable ignored) {
        }
    }

    private final Map<String, Object> last = new HashMap<>();

    private void forward(String name, Object data) {
        synchronized (observed) {
            if (!observed.contains(name)) return;
            last.put(name, data);
        }
        try {
            JSONObject change = new JSONObject();
            change.put("name", name);
            change.put("data", data);
            sink.send(ShellProtocol.signal("mpv-prop-change", change));
        } catch (Throwable ignored) {
        }
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
}
