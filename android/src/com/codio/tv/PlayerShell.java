package com.codio.tv;

/** A native player that speaks Stremio's shell (mpv) IPC to the web UI: VlcShell, or MpvShell as the fallback. */
interface PlayerShell {
    /** Handles one invoke from the web UI. Returns true if it was a player message. */
    boolean handle(String method, Object params);
    void attachSurface(android.view.Surface surface);
    void surfaceSize(int width, int height);
    void detachSurface();
    void pause();
    void destroy();
    String hwdec();
    /** "vlc" or "mpv", for logs and the debug info. */
    String engine();
}
