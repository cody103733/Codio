package com.codio.tv.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Codio's browse screens, drawn directly on one hardware-accelerated canvas (no web page, no view tree): the same
 * approach TV apps like Netflix use to stay smooth on modest hardware. Screens draw in a 1920x1080 design space; the
 * view only draws while something is moving, so an idle screen costs nothing.
 */
public final class TvView extends View implements Images.Listener, NativeUi, Profiles.Ui {
    @Override
    public View view() {
        return this;
    }

    @Override
    public void trimImages() {
        images.trim();
    }

    static final String TAG = "CodioUi";
    public static final float W = 1920, H = 1080;
    public static final int MARGIN = 96;

    public interface Host {
        /** Play with Smart auto-play (the web player and VLC take over). */
        void play(Store.Meta meta, String videoId);

        /** The web UI's source list for this movie/episode. */
        void chooseSource(Store.Meta meta, String videoId);

        /** A web UI page (Settings, Addons, Library). */
        void openWeb(String route);

        void requestExit();

        /** Back on the "finding your stream" screen: stop the search. */
        void cancelPlay();

        android.content.SharedPreferences prefs();

        void saveAutoPlay(String json);

        void audioSyncChanged();

        /** Settings, Playback, New episode alerts was switched. */
        void newEpisodeAlertsChanged();

        String version();

        /** Settings > Check for updates: looks for a newer Codio in the repo and offers to install it. */
        void checkUpdates();

        /** Load the sources for this movie/episode in the background (the movie page runs hidden). */
        void prefetch(Store.Meta meta, String videoId);

        /** Left the movie page: stop loading sources. */
        void prefetchEnd();

        /** Play this source from the native source list. */
        void playSource(Store.Meta meta, String videoId, Store.Source source);

        /** A command for the video player (mpv-set-prop / mpv-command with their params). */
        void player(String method, org.json.JSONArray params);

        /** Leave the player (back to the movie page). */
        void stopPlayer();

        /** The viewer picked a subtitle track themselves. */
        void userPick();

        /** Send an action to Codio's core (the web UI's engine), e.g. {"action":"Ctx","args":{...}}. */
        void dispatch(org.json.JSONObject action);

        /** Phone: play a downloaded file directly (no addons, works offline), from startSec. */
        void playOffline(Store.Meta meta, String videoId, String path, double startSec);

        /** True while the web page is busy with playback (a hidden source lookup must wait). */
        boolean webBusy();
    }

    /** Sends an action to Codio's core, e.g. AddToLibrary. */
    @Override
    public void ctx(String name, Object args) {
        host.dispatch(Core.ctx(name, args));
    }

    public final Store store;
    public final Images images;
    public final Host host;
    final ArrayList<Screen> stack = new ArrayList<>();

    final Typeface regular, bold;
    final TextPaint tTitle, tHero, tBody, tSmall, tLabel, tNav, tMeta;
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);

    private float scale = 1;
    private long lastFrame = 0;
    private boolean needFrame = false;
    /**
     * Screen changes cross-fade: the screen being left keeps drawing underneath while the new one fades in over it.
     * The top bar and the dark background sit in the same place on every section, so they stay still and only the
     * part that differs changes (no black flash between menus).
     */
    private float fadeIn = 0;
    private Screen leaving;
    private final Paint layer = new Paint();

    public TvView(Context ctx, Store store, Host host) {
        super(ctx);
        this.store = store;
        this.host = host;
        this.images = new Images(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.BLACK);
        // The system font, like the iPhone (the standard design).
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        bold = Typeface.create("sans-serif", Typeface.BOLD);
        tHero = text(64, Color.WHITE, true);
        tTitle = text(34, Color.WHITE, true);
        tBody = text(27, Theme.TEXT_BODY, false);
        tMeta = text(26, Theme.TEXT_DIM, true);
        tSmall = text(23, Theme.TEXT_DIM, false);
        tLabel = text(28, Color.WHITE, true);
        tNav = text(27, Theme.TEXT_DIM, true);
        stroke.setStyle(Paint.Style.STROKE);
    }

    private TextPaint text(float size, int color, boolean b) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(b ? bold : regular);
        p.setTextSize(size);
        p.setColor(color);
        if (b) p.setFakeBoldText(false);
        return p;
    }

    // ------------------------------------------------------------------ screens

    public void showHome() {
        setRoot(new HomeScreen(this));
    }

    void setRoot(Screen s) {
        Screen was = stack.isEmpty() ? null : top();
        for (Screen o : stack) o.onHide();
        stack.clear();
        stack.add(s);
        s.onShow();
        transition(was);
    }

    void push(Screen s) {
        Screen was = stack.isEmpty() ? null : top();
        if (was != null) was.onHide();
        stack.add(s);
        s.onShow();
        transition(was);
    }

    public void pop() {
        if (stack.size() <= 1) return;
        Screen was = stack.remove(stack.size() - 1);
        was.onHide();
        top().onShow();
        transition(was);
        checkPrefetch();
    }

    Screen top() {
        return stack.get(stack.size() - 1);
    }

    Screen root() {
        return stack.get(0);
    }

    /** Show a top-bar section: Home, Movies, Series, Search, or a web page. */
    public void navigate(int item) {
        switch (item) {
            case NavBar.HOME: {
                Screen was = top();
                while (stack.size() > 1) stack.remove(stack.size() - 1).onHide();
                top().onShow();
                transition(was);
                checkPrefetch();
                break;
            }
            case NavBar.MOVIES:
            case NavBar.SERIES:
                openSection(new DiscoverScreen(this, item == NavBar.MOVIES ? "movie" : "series"));
                break;
            case NavBar.MUSIC:
                openSection(new MusicScreen(this));
                break;
            case NavBar.SEARCH:
                openSection(new SearchScreen(this));
                break;
            case NavBar.LIBRARY:
                openSection(new LibraryScreen(this));
                break;
            case NavBar.ADDONS:
                openSection(new AddonsScreen(this));
                break;
            case NavBar.SETTINGS:
                openSection(new SettingsScreen(this));
                break;
            default:
                break;
        }
    }

    static boolean isVoiceKey(int code) {
        return code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST || code == KeyEvent.KEYCODE_ASSIST;
    }

    private SearchScreen openSearch() {
        if (stack.isEmpty()) return null;
        if (!(top() instanceof SearchScreen)) navigate(NavBar.SEARCH);
        return top() instanceof SearchScreen ? (SearchScreen) top() : null;
    }

    /** The remote's search button when the system asks Codio to search (onSearchRequested). */
    public void voiceSearch() {
        if (playerShown()) return;
        SearchScreen sc = openSearch();
        if (sc != null) sc.listen();
        kick();
    }

    /** A search the TV's assistant sent to Codio ("search for ... in Codio"). */
    public void searchFor(String text) {
        if (text == null || text.trim().isEmpty() || playerShown()) return;
        SearchScreen sc = openSearch();
        if (sc != null) sc.setQuery(text.trim());
        kick();
    }

    private void openSection(Screen s) {
        Screen was = top();
        while (stack.size() > 1) stack.remove(stack.size() - 1).onHide();
        stack.add(s);
        s.onShow();
        transition(was);
        checkPrefetch();
    }

    // ------------------------------------------------------------------ player

    /** The video player's state, mirrored from its property changes. */
    public final PlayerState playerState = new PlayerState();

    public boolean playerShown() {
        return !stack.isEmpty() && top() instanceof PlayerScreen;
    }

    /** The video is playing: Codio's controls over it (this view turns see-through). */
    public void showPlayer(Store.Meta m, String videoId) {
        pauseMusic();
        if (playerShown()) {
            ((PlayerScreen) top()).setVideo(videoId);
            return;
        }
        hideLoading();
        setBackgroundColor(Color.TRANSPARENT);
        push(new PlayerScreen(this, m, videoId));
        fadeIn = 0;
        leaving = null;
    }

    public void hidePlayer() {
        boolean had = false;
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i) instanceof PlayerScreen) {
                stack.remove(i).onHide();
                had = true;
            }
        }
        setBackgroundColor(Color.BLACK);
        if (had && !stack.isEmpty()) top().onShow();
        kick();
    }

    /** A message from the video player: mpv-prop-change {name, data}, mpv-event-ended, mpv-event-video-ready. */
    public void playerEvent(String signal, Object arg) {
        org.json.JSONObject o = arg instanceof org.json.JSONObject ? (org.json.JSONObject) arg : null;
        if (o == null) return;
        boolean changed = false;
        switch (signal) {
            case "mpv-prop-change":
                changed = playerState.prop(o.optString("name"), o.opt("data"));
                break;
            case "mpv-event-video-ready":
                if (!o.optBoolean("ready")) {
                    playerState.reset();
                    changed = true;
                }
                break;
            case "mpv-event-ended": {
                org.json.JSONObject err = o.optJSONObject("error");
                if (err != null && err.optBoolean("critical")) {
                    playerState.error = err.optString("message", "This stream could not be played.");
                    changed = true;
                }
                break;
            }
            default:
                break;
        }
        if (changed && playerShown()) kick();
    }

    /** The "finding your stream" screen over the web movie page while auto-play works. */
    public void showLoading(Store.Meta m, String videoId) {
        pauseMusic();
        hidePlayer();
        String q = store.autoPlay.optString("preferredQuality", null);
        if ("2160p".equals(q)) q = "4K";
        push(new LoadingScreen(this, m, videoId, q));
    }

    /** A video is starting: the music stops for it. */
    private void pauseMusic() {
        try {
            if (com.codio.tv.music.MusicPlayer.hasQueue() && com.codio.tv.music.MusicPlayer.playing()) com.codio.tv.music.MusicPlayer.pause();
        } catch (Throwable ignored) {
        }
    }

    public boolean loadingShown() {
        return !stack.isEmpty() && top() instanceof LoadingScreen;
    }

    public void loadingStatus(String kind, String text) {
        if (loadingShown()) ((LoadingScreen) top()).setStatus(kind, text);
    }

    public void hideLoading() {
        if (loadingShown()) {
            stack.remove(stack.size() - 1).onHide();
            top().onShow();
        }
    }

    /** Is the screen on top the page of this item already? */
    public boolean showingDetail(String id) {
        return !stack.isEmpty() && top() instanceof DetailScreen && ((DetailScreen) top()).id().equals(id);
    }

    /** The native source list ("Choose a source"). */
    public void openSources(Store.Meta m, String videoId) {
        host.prefetch(m, videoId);
        push(new SourcesScreen(this, m, videoId));
    }

    /** No movie page left on screen: the hidden one can stop. */
    private void checkPrefetch() {
        for (Screen s : stack) if (s instanceof DetailScreen || s instanceof SourcesScreen || s instanceof LoadingScreen) return;
        host.prefetchEnd();
    }

    /** Back from a web page: the screen on top gets onShow again (e.g. the movie page reloads its sources). */
    public void resumed() {
        if (!stack.isEmpty()) top().onShow();
        kick();
    }

    public void openDetail(Store.Meta m) {
        // Its "New episode" mark has done its job.
        NewEpisodes.seen(getContext(), m.id);
        push(new DetailScreen(this, m));
    }

    /** Back on the remote. */
    public void back() {
        if (top().back()) {
            kick();
            return;
        }
        if (stack.size() > 1) pop();
        else host.requestExit();
    }

    /** Addons or library changed (after the web UI has saved something). */
    public void dataChanged(boolean addonsChanged) {
        for (Screen s : stack) s.dataChanged(addonsChanged);
        kick();
    }

    /** An event from Codio's core: the screens may react, errors and installs show a short message. */
    public void coreEvent(String name, String message) {
        for (Screen s : new ArrayList<>(stack)) s.coreEvent(name, message);
        String text = Core.toastFor(name, message);
        if (text != null) toast(text);
        else kick();
    }

    private String toastText;
    private long toastUntil;

    @Override
    public void toast(String text) {
        toastText = text;
        toastUntil = SystemClock.uptimeMillis() + 3500;
        kick();
    }

    private void drawToast(Canvas c, long now) {
        if (toastText == null) return;
        long left = toastUntil - now;
        if (left <= 0) {
            toastText = null;
            return;
        }
        float a = Math.min(1, left / 300f);
        float w = Math.min(1400, tLabel.measureText(toastText)) + 80;
        r.set((W - w) / 2, H - 150, (W + w) / 2, H - 70);
        fill.setColor(Color.argb((int) (235 * a), 34, 34, 34));
        c.drawRoundRect(r, 40, 40, fill);
        tLabel.setAlpha((int) (255 * a));
        c.drawText(fit(toastText, tLabel, 1400), (W - w) / 2 + 40, H - 100, tLabel);
        tLabel.setAlpha(255);
        needFrame = true;
    }

    private void transition(Screen was) {
        // Over the video (player) there is nothing to fade from.
        leaving = was == null || was == top() || was instanceof PlayerScreen || top() instanceof PlayerScreen ? null : was;
        fadeIn = leaving != null ? 1 : 0;
        images.clearQueue();
        kick();
    }

    // ------------------------------------------------------------------ frame loop

    /** Draw a frame soon (something changed). */
    public void kick() {
        postInvalidateOnAnimation();
    }

    /** Called by screens while drawing: something is still moving, draw another frame. */
    void moving() {
        needFrame = true;
    }

    /** Called by screens while drawing: something changes slowly (a clock, a position), draw again in about ms. */
    void soon(long ms) {
        if (soonMs == 0 || ms < soonMs) soonMs = ms;
    }

    private long soonMs = 0;
    private boolean soonPosted = false;
    private final Runnable soonFrame = new Runnable() {
        @Override
        public void run() {
            soonPosted = false;
            invalidate();
        }
    };

    @Override
    public void imageReady() {
        kick();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        scale = Math.min(w / W, h / H);
    }

    private int frames = 0, slow = 0;
    private long statsAt = 0;

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
        if (lastFrame != 0 && now - lastFrame > 40 && now - lastFrame < 200) slow++;
        lastFrame = now;
        needFrame = false;
        c.save();
        c.scale(scale, scale);
        if (fadeIn > 0 && leaving != null && !stack.isEmpty()) {
            // Old screen underneath, new one on top at rising opacity (eased).
            leaving.draw(c, dt);
            float t = 1 - fadeIn;
            float a = t * t * (3 - 2 * t);
            layer.setAlpha(Math.max(1, (int) (255 * a)));
            int save = c.saveLayer(0, 0, W, H, layer);
            c.drawColor(Color.BLACK);
            top().draw(c, dt);
            c.restoreToCount(save);
            fadeIn = Math.max(0, fadeIn - dt / 0.2f);
            if (fadeIn == 0) leaving = null;
            needFrame = true;
        } else {
            fadeIn = 0;
            leaving = null;
            if (!stack.isEmpty()) top().draw(c, dt);
        }
        drawToast(c, now);
        c.restore();
        frames++;
        if (now - statsAt > 10000) {
            if (statsAt != 0 && frames > 30) Log.i(TAG, "frames " + frames + " slow " + slow);
            statsAt = now;
            frames = 0;
            slow = 0;
        }
        if (needFrame) postInvalidateOnAnimation();
        else {
            lastFrame = 0;
            if (soonMs > 0 && !soonPosted) {
                soonPosted = true;
                postDelayed(soonFrame, soonMs);
            }
        }
        soonMs = 0;
    }

    // ------------------------------------------------------------------ ambient screen

    private final Runnable idle = new Runnable() {
        @Override
        public void run() {
            startAmbient();
        }
    };

    /** Starts (or restarts) the wait for the ambient screen. */
    private void armIdle() {
        removeCallbacks(idle);
        postDelayed(idle, AmbientScreen.delay(host.prefs()));
    }

    public boolean ambientShown() {
        return !stack.isEmpty() && top() instanceof AmbientScreen;
    }

    private void startAmbient() {
        Screen t = stack.isEmpty() ? null : top();
        boolean busy = t == null || t instanceof AmbientScreen || t instanceof PlayerScreen || t instanceof LoadingScreen
                || t instanceof TextEntryScreen || t instanceof MusicNowScreen;
        // Hidden (web page, another app) or under a dialog (a trailer, an update prompt): not idle, just elsewhere.
        if (busy || !AmbientScreen.enabled(host.prefs()) || !isShown() || !hasWindowFocus() || host.webBusy()) {
            armIdle();
            return;
        }
        java.util.List<Store.Meta> items = AmbientScreen.pick(this);
        if (items.isEmpty()) {
            armIdle();
            return;
        }
        push(new AmbientScreen(this, items));
    }

    /**
     * A remote button was pressed (any key, from the activity): the idle wait starts over. True when it only woke the
     * ambient screen, so the key does nothing else.
     */
    public boolean userActive() {
        armIdle();
        if (!ambientShown()) return false;
        pop();
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        armIdle();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(idle);
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) armIdle();
    }

    // ------------------------------------------------------------------ keys

    public static final int UP = 1, DOWN = 2, LEFT = 3, RIGHT = 4, OK = 5, PLAY = 6, FF = 7, RW = 8, OTHER = 0;

    // OK on a screen that holdsOk() acts on release; held down, it asks for the options instead.
    private boolean okDown, okLong;

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (isVoiceKey(code)) return true;
        if (map(code) == OK && okDown) {
            okDown = false;
            if (!okLong && !stack.isEmpty() && top().key(OK, e)) kick();
            return true;
        }
        return super.onKeyUp(code, e);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (stack.isEmpty()) return false;
        if (isVoiceKey(code)) {
            // The remote's search/mic button: open Search and listen (not over the video).
            if (e.getRepeatCount() == 0 && !playerShown()) {
                SearchScreen sc = openSearch();
                if (sc != null) sc.listen();
            }
            return true;
        }
        int k = map(code);
        if (k == OK && (okDown || top().holdsOk())) {
            if (e.getRepeatCount() == 0) {
                okDown = true;
                okLong = false;
            } else if (okDown && !okLong) {
                okLong = true;
                top().longOk();
                kick();
            }
            return true;
        }
        okDown = false;
        boolean handled = top().key(k, e);
        if (handled) kick();
        return handled || k != OTHER || super.onKeyDown(code, e);
    }

    static int map(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                return OK;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return PLAY;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return FF;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return RW;
            default:
                return OTHER;
        }
    }

    // ------------------------------------------------------------------ drawing helpers

    private final RectF r = new RectF();

    /** Poster at x,y,w,h; focus 0..1 grows it and adds the ring. */
    void drawPoster(Canvas c, Store.Meta m, float x, float y, float w, float h, float focus) {
        float grow = 1 + 0.09f * focus;
        float fw = w * 1.09f, fh = h * 1.09f;
        float dw = w * grow, dh = h * grow;
        float dx = x - (dw - w) / 2, dy = y - (dh - h) / 2;
        Bitmap b = images.get(m.poster, Math.round(fw * scale), Math.round(fh * scale), 12 * scale);
        r.set(dx, dy, dx + dw, dy + dh);
        if (b != null) {
            c.drawBitmap(b, null, r, bmp);
        } else {
            fill.setColor(Theme.RAISED);
            c.drawRoundRect(r, 12, 12, fill);
            tSmall.setColor(Theme.TEXT_BODY);
            drawWrapped(c, m.name, tSmall, dx + 16, dy + dh / 2 - 30, dw - 32, 3);
            tSmall.setColor(Theme.TEXT_DIM);
        }
        if (m.progress > 0) {
            float bx = dx + 12, by = dy + dh - 18, bw = dw - 24;
            fill.setColor(Theme.SCRIM);
            c.drawRect(bx, by, bx + bw, by + 6, fill);
            fill.setColor(Theme.RED);
            c.drawRect(bx, by, bx + bw * Math.max(0.03f, m.progress), by + 6, fill);
        }
        NewEpisodes.Alert alert = NewEpisodes.of(m.id);
        if (alert != null) {
            // "New episode" across the top of the poster.
            String label = alert.count > 1 ? "New episodes" : "New episode";
            float tw = tSmall.measureText(label), bh = 38, bx = dx + 10, by = dy + 10;
            fill.setColor(Theme.RED);
            c.drawRoundRect(bx, by, bx + tw + 24, by + bh, bh / 2, bh / 2, fill);
            int was = tSmall.getColor();
            tSmall.setColor(Color.WHITE);
            tSmall.setFakeBoldText(true);
            c.drawText(label, bx + 12, by + bh / 2 + 8, tSmall);
            tSmall.setFakeBoldText(false);
            tSmall.setColor(was);
        }
        if (focus > 0.01f) ring(c, r, 14, focus);
    }

    /** A title's logo picture with its bottom left at x, y + 200; its name in big type until (or unless) there is one. */
    void drawTitleArt(Canvas c, Store.Meta meta, float x, float y) {
        String logoUrl = HomeScreen.logoOf(meta);
        Bitmap logo = logoUrl != null ? images.logo(logoUrl, Math.round(640 * scale), Math.round(200 * scale)) : null;
        if (logo != null) {
            float w = logo.getWidth() / scale, h = logo.getHeight() / scale;
            r.set(x, y + 200 - h, x + w, y + 200);
            c.drawBitmap(logo, null, r, bmp);
        } else if (logoUrl == null || images.isFailed(logoUrl)) {
            tHero.setTextSize(72);
            drawWrapped(c, meta.name, tHero, x, y + 30, 1100, 2);
            tHero.setTextSize(64);
        }
    }

    void ring(Canvas c, RectF rect, float radius, float alpha) {
        stroke.setColor(Color.argb((int) (255 * alpha), 255, 255, 255));
        stroke.setStrokeWidth(5);
        r2.set(rect.left - 5, rect.top - 5, rect.right + 5, rect.bottom + 5);
        c.drawRoundRect(r2, radius + 4, radius + 4, stroke);
    }

    private final RectF r2 = new RectF();
    private final Path tri = new Path();

    /** Pill button; returns its width. */
    float drawButton(Canvas c, float x, float y, String label, boolean playIcon, boolean focused, boolean primary) {
        float h = 72, pad = 38, icon = playIcon ? 34 : 0;
        float w = pad * 2 + icon + tLabel.measureText(label);
        r.set(x, y, x + w, y + h);
        fill.setColor(focused ? Color.WHITE : primary ? Theme.GLASS_STRONG : Theme.GLASS);
        c.drawRoundRect(r, 10, 10, fill);
        int fg = focused ? Color.BLACK : Color.WHITE;
        if (playIcon) {
            float ix = x + pad, iy = y + h / 2;
            tri.reset();
            tri.moveTo(ix, iy - 14);
            tri.lineTo(ix + 24, iy);
            tri.lineTo(ix, iy + 14);
            tri.close();
            fill.setColor(fg);
            c.drawPath(tri, fill);
        }
        tLabel.setColor(fg);
        c.drawText(label, x + pad + icon, y + h / 2 + 10, tLabel);
        tLabel.setColor(Color.WHITE);
        return w;
    }

    /** Filter chip; returns its width. */
    float drawChip(Canvas c, float x, float y, String label, boolean focused, boolean selected) {
        float h = 56, pad = 26;
        float w = pad * 2 + tNav.measureText(label);
        r.set(x, y, x + w, y + h);
        // Like the iPhone's pills: red when chosen, white when focused, otherwise a thin outline.
        if (focused || selected) {
            fill.setColor(focused ? Color.WHITE : Theme.RED);
            c.drawRoundRect(r, h / 2, h / 2, fill);
        } else {
            r.inset(1.5f, 1.5f);
            stroke.setColor(0x80FFFFFF);
            stroke.setStrokeWidth(3);
            c.drawRoundRect(r, h / 2, h / 2, stroke);
        }
        tNav.setColor(focused ? Color.BLACK : Color.WHITE);
        c.drawText(label, x + pad, y + h / 2 + 9, tNav);
        tNav.setColor(Theme.TEXT_DIM);
        return w;
    }

    float chipWidth(String label) {
        return 52 + tNav.measureText(label);
    }

    /** The Codio wordmark (see Theme.Logo). */
    static final float LOGO_H = 52, LOGO_W = LOGO_H * Theme.Logo.ASPECT;
    private final Theme.Logo logo = new Theme.Logo();

    void drawLogo(Canvas c, float x, float y) {
        logo.draw(c, x, y, LOGO_H);
    }

    private final Map<String, String> ellipsized = new LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > 400;
        }
    };

    String fit(String s, TextPaint p, float width) {
        if (s == null) return "";
        String k = p.getTextSize() + "|" + width + "|" + s;
        String v = ellipsized.get(k);
        if (v == null) {
            v = TextUtils.ellipsize(s, p, width, TextUtils.TruncateAt.END).toString();
            ellipsized.put(k, v);
        }
        return v;
    }

    private final Map<String, StaticLayout> layouts = new LinkedHashMap<String, StaticLayout>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, StaticLayout> e) {
            return size() > 60;
        }
    };

    /** Wrapped text, at most maxLines (ellipsized); returns its height. */
    /** Height drawWrapped would use, without drawing. */
    private final RectF tagR = new RectF();
    private final Paint tagLine = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * A small outlined tag such as "Dolby Vision" whose text sits on baseline y; returns its width.
     * dark = drawn on a white card.
     */
    float videoTag(Canvas c, String tag, float x, float y, boolean dark) {
        float cap = tSmall.getTextSize() * 0.72f, padX = 14, padY = 9;
        float w = tSmall.measureText(tag) + padX * 2;
        tagR.set(x, y - cap - padY, x + w, y + padY);
        tagLine.setStyle(Paint.Style.STROKE);
        tagLine.setStrokeWidth(2);
        tagLine.setColor(dark ? Theme.ON_FOCUS : 0xCCFFFFFF);
        c.drawRoundRect(tagR, 6, 6, tagLine);
        int was = tSmall.getColor();
        tSmall.setColor(dark ? Theme.ON_FOCUS : Color.WHITE);
        c.drawText(tag, x + padX, y, tSmall);
        tSmall.setColor(was);
        return w;
    }

    float wrappedHeight(String s, TextPaint p, float width, int maxLines) {
        if (s == null || s.isEmpty()) return 0;
        return layout(s, p, width, maxLines).getHeight();
    }

    float drawWrapped(Canvas c, String s, TextPaint p, float x, float y, float width, int maxLines) {
        if (s == null || s.isEmpty()) return 0;
        StaticLayout l = layout(s, p, width, maxLines);
        c.save();
        c.translate(x, y);
        l.draw(c);
        c.restore();
        return l.getHeight();
    }

    private StaticLayout layout(String s, TextPaint p, float width, int maxLines) {
        String k = p.getTextSize() + "|" + p.getColor() + "|" + width + "|" + maxLines + "|" + s;
        StaticLayout l = layouts.get(k);
        if (l == null) {
            l = StaticLayout.Builder.obtain(s, 0, s.length(), p, (int) width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0, 1.18f)
                    .setMaxLines(maxLines)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build();
            layouts.put(k, l);
        }
        return l;
    }

    private float spin = 0;

    void spinner(Canvas c, float cx, float cy, float radius, float dt) {
        spin = (spin + dt * 360) % 360;
        stroke.setColor(Theme.RED);
        stroke.setStrokeWidth(6);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        r.set(cx - radius, cy - radius, cx + radius, cy + radius);
        c.drawArc(r, spin, 270, false, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        moving();
    }

    // Shared shaders for the backdrop fades (design-space coordinates).
    final Paint leftShade = shade(new LinearGradient(0, 0, 1250, 0, new int[]{0xF2000000, 0xB3000000, 0x00000000}, new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
    final Paint bottomShade = shade(new LinearGradient(0, 0, 0, H, new int[]{0x00000000, 0x00000000, 0xE6000000, 0xFF000000}, new float[]{0, 0.35f, 0.72f, 1}, Shader.TileMode.CLAMP));
    final Paint topShade = shade(new LinearGradient(0, 0, 0, 180, new int[]{0xB3000000, 0x00000000}, null, Shader.TileMode.CLAMP));

    private static Paint shade(Shader s) {
        Paint p = new Paint();
        p.setShader(s);
        p.setDither(true);
        return p;
    }

    float scale() {
        return scale;
    }
}
