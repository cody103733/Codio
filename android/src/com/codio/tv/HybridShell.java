package com.codio.tv;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * VLC for everything, except Dolby Vision sources on a TV that can show them: those go to DvShell (Android's own
 * player and the TV's Dolby Vision decoder). If DvShell can't open a file, it plays with VLC as before.
 */
final class HybridShell implements PlayerShell, DvShell.Listener {
    private static final String TAG = "CodioDV";

    interface Policy {
        /** The viewer wants Dolby Vision, the TV can do it, and this address is a source tagged Dolby Vision. */
        boolean wantsDv(String url);
    }

    private final PlayerShell vlc;
    private final DvShell dv;
    private final Policy policy;
    private final Handler decider;

    // Guarded by "this". While a Dolby Vision file is being checked, the UI's next messages wait for the verdict.
    private boolean deciding = false;
    private boolean dvActive = false;
    private final List<Object[]> queued = new ArrayList<>();

    HybridShell(PlayerShell vlc, DvShell dv, Policy policy) {
        this.vlc = vlc;
        this.dv = dv;
        this.policy = policy;
        HandlerThread t = new HandlerThread("codio-dv-probe");
        t.start();
        decider = new Handler(t.getLooper());
    }

    @Override public boolean handle(String method, Object params) {
        if ("mpv-observe-prop".equals(method) && params instanceof String) {
            dv.observe((String) params);
            return vlc.handle(method, params);
        }
        if (!method.startsWith("mpv-")) return vlc.handle(method, params);
        String[] cmd = null;
        if ("mpv-command".equals(method) && params instanceof JSONArray) {
            JSONArray a = (JSONArray) params;
            cmd = new String[a.length()];
            for (int i = 0; i < cmd.length; i++) cmd[i] = String.valueOf(a.opt(i));
        }
        synchronized (this) {
            if (deciding) {
                queued.add(new Object[]{method, params});
                return true;
            }
        }
        if (cmd != null && cmd.length > 1 && "loadfile".equals(cmd[0])) {
            load(cmd, method, params);
            return true;
        }
        // Not dv.isActive(): right after the switch DvShell's own flag isn't set yet, and checking it here sent every
        // later seek and pause to VLC (rewinding did nothing).
        boolean toDv;
        synchronized (this) {
            toDv = dvActive;
        }
        if (!toDv) return vlc.handle(method, params);
        if (cmd != null && cmd.length > 0) {
            dv.command(cmd);
            if ("stop".equals(cmd[0])) {
                synchronized (this) {
                    dvActive = false;
                }
            }
        } else if ("mpv-set-prop".equals(method) && params instanceof JSONArray && ((JSONArray) params).length() >= 2) {
            dv.setProp(((JSONArray) params).optString(0), ((JSONArray) params).opt(1));
        }
        return true;
    }

    private void load(final String[] cmd, final String method, final Object params) {
        boolean wasDv;
        synchronized (this) {
            wasDv = dvActive;
            dvActive = false;
        }
        if (wasDv) dv.stop();
        if (!policy.wantsDv(cmd[1])) {
            vlc.handle(method, params);
            return;
        }
        synchronized (this) {
            deciding = true;
        }
        Log.e(TAG, "Dolby Vision source: checking the file");
        decider.post(new Runnable() {
            @Override public void run() {
                DvShell.Probe p = DvShell.probe(cmd[1]);
                if (p != null) {
                    // VLC may still hold the previous file.
                    vlc.handle("mpv-command", new JSONArray().put("stop"));
                    synchronized (HybridShell.this) {
                        dvActive = true;
                    }
                    dv.load(cmd, p);
                } else {
                    Log.e(TAG, "playing with VLC instead");
                    vlc.handle(method, params);
                }
                flush();
            }
        });
    }

    private void flush() {
        List<Object[]> q;
        synchronized (this) {
            deciding = false;
            q = new ArrayList<>(queued);
            queued.clear();
        }
        for (Object[] m : q) handle((String) m[0], m[1]);
    }

    /** The viewer left the player: make sure the Dolby Vision player stops even if the page's stop never comes. */
    void leftPlayer() {
        boolean d;
        synchronized (this) {
            d = dvActive;
            dvActive = false;
        }
        if (d || dv.isActive()) {
            Log.e(TAG, "left the player: stopping the Dolby Vision player");
            dv.stop();
        }
    }

    /** DvShell couldn't start the file: VLC plays it (the UI never notices). */
    @Override public void startFailed(String[] cmd) {
        synchronized (this) {
            dvActive = false;
        }
        Log.e(TAG, "Dolby Vision start failed; playing with VLC");
        JSONArray a = new JSONArray();
        for (String s : cmd) a.put(s);
        vlc.handle("mpv-command", a);
    }

    @Override public void attachSurface(android.view.Surface surface) {
        vlc.attachSurface(surface);
    }

    @Override public void surfaceSize(int width, int height) {
        vlc.surfaceSize(width, height);
    }

    @Override public void detachSurface() {
        vlc.detachSurface();
    }

    @Override public void pause() {
        boolean d;
        synchronized (this) {
            d = dvActive;
        }
        if (d) dv.pause();
        else vlc.pause();
    }

    @Override public void destroy() {
        dv.destroy();
        vlc.destroy();
        decider.getLooper().quitSafely();
    }

    @Override public String hwdec() {
        return vlc.hwdec();
    }

    @Override public String engine() {
        return vlc.engine();
    }
}
