package com.codio.tv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import com.codio.tv.music.ListenBrainz;
import com.codio.tv.music.MusicPlayer;
import com.codio.tv.music.Taste;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Codio for phones: touch screens built from ordinary Android views (portrait browsing, a bottom tab bar, the player
 * in landscape), on the same engine as the TV app (Store, the hidden web page for sources and playback, VLC).
 */
public final class PhoneUi extends FrameLayout implements NativeUi, PhoneDownloads.Listener, Profiles.Ui {
    static final int TAB_HOME = 0, TAB_SEARCH = 1, TAB_MUSIC = 2, TAB_LIBRARY = 3, TAB_SETTINGS = 4;

    /** The activity: keeps Android's mini player (picture-in-picture) settings up to date. */
    public interface MiniPlayerHost {
        void updatePipParams();
    }

    /** One screen in the stack. */
    abstract static class Page {
        final PhoneUi ui;
        View view;
        int tab = TAB_HOME;

        Page(PhoneUi ui) {
            this.ui = ui;
        }

        Context ctx() {
            return ui.getContext();
        }

        int dp(float v) {
            return PhoneKit.dp(ui.getContext(), v);
        }

        abstract View build();

        void onShow() {}

        void onHide() {}

        /** Library, addons or downloads changed. */
        void refresh() {}

        /** A download moved on (progress, finished). */
        void downloadsChanged() {
            refresh();
        }

        boolean back() {
            return false;
        }

        void coreEvent(String name) {}
    }

    final Activity activity;
    final Store store;
    final TvView.Host host;
    final PhoneDownloads downloads;
    private final FrameLayout content;
    private final LinearLayout tabs;
    private final ArrayList<Page> stack = new ArrayList<>();
    private final TabButton[] tabButtons = new TabButton[5];
    private final MiniBar mini;
    private PhoneLoading loading;
    private PhonePlayer player;
    final PlayerState playerState = new PlayerState();
    /** The player is showing a downloaded file (not a stream). */
    boolean playingOffline = false;

    public PhoneUi(Activity act, Store store, TvView.Host host) {
        super(act);
        this.activity = act;
        this.store = store;
        this.host = host;
        PhoneKit.init(act);
        setBackgroundColor(Theme.BLACK);
        downloads = new PhoneDownloads(act, store, host, this);
        MusicPlayer.init(act);
        com.codio.tv.music.MusicDownloads.init(act);
        Taste.load();
        ListenBrainz.start();

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        content = new FrameLayout(act);
        col.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        mini = new MiniBar(this);
        col.addView(mini, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tabs = new LinearLayout(act);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(Color.BLACK);
        String[] labels = {"Home", "Search", "Music", "Library", "Settings"};
        for (int i = 0; i < 5; i++) {
            final int t = i;
            TabButton b = new TabButton(act, i, labels[i]);
            b.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    openTab(t);
                }
            });
            tabButtons[i] = b;
            tabs.addView(b, new LinearLayout.LayoutParams(0, PhoneKit.dp(act, 50), 1));
        }
        col.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override
    public View view() {
        return this;
    }

    // ------------------------------------------------------------------ pages

    Page top() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    void push(Page p) {
        Page old = top();
        if (old != null) {
            old.onHide();
            old.view.setVisibility(View.GONE);
        }
        if (old != null && p.tab == TAB_HOME) p.tab = old.tab;
        stack.add(p);
        p.view = p.build();
        content.addView(p.view, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p.onShow();
        updateTabs();
    }

    void pop() {
        if (stack.size() <= 1) return;
        Page p = stack.remove(stack.size() - 1);
        p.onHide();
        content.removeView(p.view);
        Page t = top();
        t.view.setVisibility(View.VISIBLE);
        t.onShow();
        t.refresh();
        updateTabs();
        checkPrefetch();
    }

    private void setRoot(Page p) {
        for (Page o : stack) {
            o.onHide();
            content.removeView(o.view);
        }
        stack.clear();
        push(p);
        checkPrefetch();
    }

    void openTab(int t) {
        Page cur = top();
        if (cur != null && stack.size() == 1 && cur.tab == t) {
            // Tapping the current tab again scrolls it back to the top (rebuilds it).
            if (cur instanceof PhoneHome) ((PhoneHome) cur).scrollTop();
            return;
        }
        Page p;
        switch (t) {
            case TAB_SEARCH:
                p = new PhoneSearch(this);
                break;
            case TAB_MUSIC:
                p = new PhoneMusic(this);
                break;
            case TAB_LIBRARY:
                p = new PhoneLibrary(this);
                break;
            case TAB_SETTINGS:
                p = new PhoneSettings(this);
                break;
            default:
                p = new PhoneHome(this);
                break;
        }
        p.tab = t;
        setRoot(p);
    }

    /** Opens the full music player (from the mini player). */
    void openNowPlaying() {
        if (!(top() instanceof PhoneNowPlaying)) push(new PhoneNowPlaying(this));
    }

    private void updateTabs() {
        Page t = top();
        int sel = t != null ? t.tab : 0;
        for (int i = 0; i < tabButtons.length; i++) tabButtons[i].setSelected(i == sel);
        mini.refresh();
    }

    /** No movie page or source list open: the hidden web page can stop loading sources. */
    private void checkPrefetch() {
        for (Page p : stack) if (p instanceof PhoneSourcesPage || p instanceof PhoneDetail) return;
        if (!downloads.lookupActive()) host.prefetchEnd();
    }

    @Override
    public void showHome() {
        openTab(TAB_HOME);
    }

    @Override
    public void back() {
        if (player != null) {
            player.back();
            return;
        }
        if (loading != null) {
            host.cancelPlay();
            return;
        }
        Page t = top();
        if (t != null && t.back()) return;
        if (stack.size() > 1) pop();
        else if (t != null && t.tab != TAB_HOME) openTab(TAB_HOME);
        else host.requestExit();
    }

    @Override
    public void dataChanged(boolean addonsChanged) {
        for (Page p : stack) p.refresh();
        refreshAvatars();
        if (!askedWho && store.profileLoaded) {
            askedWho = true;
            if ("on".equals(host.prefs().getString("ask_profile", "off")) && Profiles.email(store) != null
                    && Profiles.list(host.prefs()).length() > 1) pickProfile();
        }
    }

    @Override
    public void downloadsChanged() {
        for (Page p : stack) p.downloadsChanged();
    }

    @Override
    public void navigate(int item) {
        switch (item) {
            case NavBar.HOME:
                while (stack.size() > 1) pop();
                if (top() == null || top().tab != TAB_HOME) openTab(TAB_HOME);
                break;
            case NavBar.MOVIES:
            case NavBar.SERIES:
                push(new PhoneDiscover(this, item == NavBar.MOVIES ? "movie" : "series"));
                break;
            case NavBar.SEARCH:
                openTab(TAB_SEARCH);
                break;
            case NavBar.LIBRARY:
                openTab(TAB_LIBRARY);
                break;
            case NavBar.SETTINGS:
            case NavBar.ADDONS:
                openTab(TAB_SETTINGS);
                break;
            default:
                break;
        }
    }

    @Override
    public void openDetail(Store.Meta m) {
        int depth = stack.size();
        // Its "New episode" mark has done its job.
        if (m != null) NewEpisodes.seen(getContext(), m.id);
        try {
            push(new PhoneDetail(this, m));
        } catch (Throwable t) {
            Log.e("Codio", "couldn't open " + (m != null ? m.type + "/" + m.id : null), t);
            // Take the half-built page off again and say what happened instead of doing nothing.
            while (stack.size() > depth) {
                Page p = stack.remove(stack.size() - 1);
                if (p.view != null) content.removeView(p.view);
            }
            Page t2 = top();
            if (t2 != null && t2.view != null) t2.view.setVisibility(View.VISIBLE);
            toast("Couldn't open this: " + t);
        }
    }

    @Override
    public boolean showingDetail(String id) {
        Page t = top();
        return t instanceof PhoneDetail && ((PhoneDetail) t).meta.id.equals(id);
    }

    @Override
    public void openSources(Store.Meta m, String videoId) {
        hideLoading();
        push(new PhoneSourcesPage(this, m, videoId));
    }

    // ------------------------------------------------------------------ "finding your stream"

    @Override
    public void showLoading(Store.Meta m, String videoId) {
        hidePlayer();
        hideLoading();
        playingOffline = false;
        String q = store.autoPlay.optString("preferredQuality", null);
        loading = new PhoneLoading(this, m, videoId, q);
        addView(loading, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void loadingStatus(String kind, String text) {
        if (loading != null) loading.setStatus(kind, text);
    }

    @Override
    public void hideLoading() {
        if (loading != null) {
            removeView(loading);
            loading = null;
        }
    }

    // ------------------------------------------------------------------ player

    public boolean playerUp() {
        return player != null;
    }

    /** The video shrank into Android's mini player (or came back): no controls in the small window. */
    public void setMiniPlayer(boolean on) {
        if (player != null) player.setMini(on);
    }

    private void pipChanged() {
        if (activity instanceof MiniPlayerHost) ((MiniPlayerHost) activity).updatePipParams();
    }

    @Override
    public void showPlayer(Store.Meta m, String videoId) {
        // A video starts: the music pauses by itself.
        MusicPlayer.pause();
        hideLoading();
        if (player != null) {
            player.setVideo(m, videoId);
            return;
        }
        playerState.reset();
        setBackgroundColor(Color.TRANSPARENT);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).setVisibility(View.GONE);
        player = new PhonePlayer(this, m, videoId);
        addView(player, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        immersive(true);
        setKeepScreenOn(true);
        pipChanged();
    }

    @Override
    public void hidePlayer() {
        Stats.stopped();
        if (player == null) return;
        player.closing();
        removeView(player);
        player = null;
        setBackgroundColor(Theme.BLACK);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).setVisibility(View.VISIBLE);
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        immersive(false);
        setKeepScreenOn(false);
        pipChanged();
        Page t = top();
        if (t != null) t.refresh();
    }

    @SuppressWarnings("deprecation")
    private void immersive(boolean on) {
        View d = activity.getWindow().getDecorView();
        if (on) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    public void playerEvent(String signal, Object arg) {
        JSONObject o = arg instanceof JSONObject ? (JSONObject) arg : null;
        if (o == null) return;
        switch (signal) {
            case "mpv-prop-change":
                playerState.prop(o.optString("name"), o.opt("data"));
                if (player != null && "time-pos".equals(o.optString("name")) && o.opt("data") instanceof Number) {
                    Stats.tick(player.meta(), ((Number) o.opt("data")).doubleValue());
                }
                break;
            case "mpv-event-video-ready":
                if (!o.optBoolean("ready")) playerState.reset();
                break;
            case "mpv-event-ended": {
                JSONObject err = o.optJSONObject("error");
                if (err != null && err.optBoolean("critical")) {
                    playerState.error = err.optString("message", "This video could not be played.");
                } else if (player != null) {
                    player.ended();
                }
                break;
            }
            default:
                break;
        }
        if (player != null) player.update();
    }

    @Override
    public void resumed() {
        Page t = top();
        if (t != null) {
            t.onShow();
            t.refresh();
        }
    }

    @Override
    public void trimImages() {
        PhoneKit.trim();
    }

    @Override
    public void coreEvent(String name, String message) {
        for (Page p : new ArrayList<>(stack)) p.coreEvent(name);
        refreshAvatars();
        String text = Core.toastFor(name, message);
        if (text != null) toast(text);
    }

    // ------------------------------------------------------------------ who's watching (round avatar, top right)

    /** A round avatar for the profile being watched; tap it to pick who's watching. Sits in the top right of each tab. */
    View profileButton() {
        return profileButton(34);
    }

    View profileButton(int size) {
        final Avatar a = new Avatar(getContext(), null, size);
        a.setContentDescription("Who's watching");
        a.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                pickProfile();
            }
        });
        avatars.add(new java.lang.ref.WeakReference<>(a));
        // As on the iPhone, there's no avatar until someone is signed in.
        a.setVisibility(Profiles.email(store) != null ? VISIBLE : GONE);
        return a;
    }

    private final ArrayList<java.lang.ref.WeakReference<Avatar>> avatars = new ArrayList<>();
    /** "When Codio opens: ask who's watching" has had its turn for this run. */
    private boolean askedWho = false;

    /** The account or profile list changed: every top-right avatar redraws. */
    private void refreshAvatars() {
        for (int i = avatars.size() - 1; i >= 0; i--) {
            Avatar a = avatars.get(i).get();
            if (a == null) avatars.remove(i);
            else {
                a.setVisibility(Profiles.email(store) != null ? VISIBLE : GONE);
                a.invalidate();
            }
        }
    }

    /** "Who's watching?": the profiles as round avatars with names; tap one to switch. */
    void pickProfile() {
        final org.json.JSONArray l = Profiles.list(host.prefs());
        final String email = Profiles.email(store);
        if (email == null) {
            toast("Sign in first, in Settings.");
            return;
        }
        Context c = getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, PhoneKit.dp(c, 8), 0, PhoneKit.dp(c, 8));
        final AlertDialog[] shown = new AlertDialog[1];
        int n = Math.max(1, l.length());
        for (int i = 0; i < n; i++) {
            final JSONObject p = l.length() > 0 ? l.optJSONObject(i) : Profiles.current(store, host.prefs());
            boolean cur = p != null && email.equalsIgnoreCase(p.optString("email"));
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(PhoneKit.dp(c, 20), PhoneKit.dp(c, 10), PhoneKit.dp(c, 20), PhoneKit.dp(c, 10));
            row.addView(new Avatar(c, p, 44));
            TextView name = PhoneKit.text(c, Profiles.name(p) + (cur ? "  ·  watching" : ""), 17, cur ? Color.WHITE : Theme.TEXT_BODY, cur);
            name.setPadding(PhoneKit.dp(c, 16), 0, 0, 0);
            row.addView(name);
            row.setBackground(PhoneKit.pressable(null, 0));
            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (shown[0] != null) shown[0].dismiss();
                    Profiles.switchTo(store, p, PhoneUi.this);
                }
            });
            box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (l.length() == 0) {
            TextView hint = PhoneKit.text(c, "Make more profiles on the iPhone. They show up here on their own.", 13, Theme.TEXT_DIM, false);
            hint.setPadding(PhoneKit.dp(c, 20), PhoneKit.dp(c, 8), PhoneKit.dp(c, 20), 0);
            box.addView(hint);
        }
        shown[0] = dialog().setTitle("Who's watching?").setView(box).setNegativeButton("Close", null).show();
    }

    /** A filled circle in the profile's colour with its first letter; null profile = the one being watched. */
    final class Avatar extends View {
        private final JSONObject fixed;
        private final int sizeDp;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), letter = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        Avatar(Context c, JSONObject profile, int sizeDp) {
            super(c);
            this.fixed = profile;
            this.sizeDp = sizeDp;
            letter.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(Theme.GLASS_STRONG);
            ring.setStrokeWidth(PhoneKit.dp(c, 1));
        }

        @Override
        protected void onMeasure(int w, int h) {
            int s = PhoneKit.dp(getContext(), sizeDp);
            setMeasuredDimension(s, s);
        }

        @Override
        protected void onDraw(Canvas c) {
            JSONObject p = fixed != null ? fixed : Profiles.current(store, host.prefs());
            float r = Math.min(getWidth(), getHeight()) / 2f;
            Profiles.drawAvatar(c, fill, letter, getWidth() / 2f, getHeight() / 2f, r - 1, p);
            if (fixed == null) c.drawCircle(getWidth() / 2f, getHeight() / 2f, r - 1, ring);
        }
    }

    // ------------------------------------------------------------------ helpers for the pages

    @Override
    public void toast(String s) {
        Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
    }

    AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    /** Send an action to Codio's core, e.g. AddToLibrary. */
    @Override
    public void ctx(String name, Object args) {
        host.dispatch(Core.ctx(name, args));
    }

    /** Play: a finished download plays from the phone, anything else streams with Smart auto-play. */
    public void play(Store.Meta m, String videoId) {
        String vid = videoId != null ? videoId : m.id;
        PhoneDownloads.Entry e = downloads.entry(m.type, m.id, vid);
        if (e != null && PhoneDownloads.DONE.equals(e.state) && e.file != null && new java.io.File(e.file).exists()) {
            playingOffline = true;
            host.playOffline(m, vid, e.file, e.position);
            return;
        }
        if (!online()) {
            toast("You're offline. Only downloaded videos can play.");
            return;
        }
        playingOffline = false;
        host.play(m, videoId);
    }

    boolean online() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo n = cm != null ? cm.getActiveNetworkInfo() : null;
            return n == null || n.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------------ bottom tab bar

    /** One tab of the bar along the bottom, drawn like the iPhone's: a filled icon over a small label, red when chosen. */
    private static final class TabButton extends View {
        private final int icon;
        private final String label;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();
        private final MusicIcons notes;

        TabButton(Context c, int icon, String label) {
            super(c);
            notes = new MusicIcons(c, MusicIcons.NOTE, 0);
            this.icon = icon;
            this.label = label;
            t.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 10, c.getResources().getDisplayMetrics()));
            t.setTextAlign(Paint.Align.CENTER);
            t.setTypeface(PhoneKit.face(PhoneKit.SEMIBOLD));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            setClickable(true);
            setContentDescription(label);
            setBackground(PhoneKit.pressable(PhoneKit.round(0, 0), PhoneKit.dp(c, 24)));
        }

        @Override
        public void setSelected(boolean s) {
            super.setSelected(s);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int col = isSelected() ? Theme.RED : PhoneKit.GRAY;
            p.setColor(col);
            t.setColor(col);
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f, cy = 18 * d, s = 11 * d;
            path.reset();
            p.setStyle(Paint.Style.FILL);
            switch (icon) {
                case TAB_HOME:
                    // house.fill: roof and walls, the door cut out.
                    path.moveTo(cx - s * 1.05f, cy - s * 0.02f);
                    path.lineTo(cx, cy - s);
                    path.lineTo(cx + s * 1.05f, cy - s * 0.02f);
                    path.close();
                    c.drawPath(path, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.8f * d);
                    c.drawPath(path, p);
                    p.setStyle(Paint.Style.FILL);
                    r.set(cx - s * 0.75f, cy - s * 0.35f, cx + s * 0.75f, cy + s);
                    c.drawRoundRect(r, s * 0.12f, s * 0.12f, p);
                    p.setColor(Color.BLACK);
                    r.set(cx - s * 0.22f, cy + s * 0.3f, cx + s * 0.22f, cy + s + 1);
                    c.drawRoundRect(r, s * 0.08f, s * 0.08f, p);
                    break;
                case TAB_SEARCH:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.3f * d);
                    c.drawCircle(cx - s * 0.18f, cy - s * 0.18f, s * 0.66f, p);
                    p.setStrokeWidth(2.8f * d);
                    c.drawLine(cx + s * 0.32f, cy + s * 0.32f, cx + s * 0.9f, cy + s * 0.9f, p);
                    break;
                case TAB_MUSIC:
                    c.save();
                    c.translate(cx - s, cy - s);
                    notes.draw(c, MusicIcons.NOTE, col, 2 * s, 2 * s, d);
                    c.restore();
                    break;
                case TAB_LIBRARY:
                    // rectangle.stack.fill
                    r.set(cx - s, cy - s * 0.32f, cx + s, cy + s);
                    c.drawRoundRect(r, s * 0.2f, s * 0.2f, p);
                    r.set(cx - s * 0.82f, cy - s * 0.62f, cx + s * 0.82f, cy - s * 0.47f);
                    c.drawRoundRect(r, s * 0.08f, s * 0.08f, p);
                    r.set(cx - s * 0.62f, cy - s * 0.9f, cx + s * 0.62f, cy - s * 0.76f);
                    c.drawRoundRect(r, s * 0.08f, s * 0.08f, p);
                    break;
                default:
                    // gearshape.fill
                    c.drawCircle(cx, cy, s * 0.74f, p);
                    for (int i = 0; i < 8; i++) {
                        c.save();
                        c.rotate(45 * i, cx, cy);
                        r.set(cx - s * 0.19f, cy - s, cx + s * 0.19f, cy - s * 0.5f);
                        c.drawRoundRect(r, s * 0.08f, s * 0.08f, p);
                        c.restore();
                    }
                    p.setColor(Color.BLACK);
                    c.drawCircle(cx, cy, s * 0.3f, p);
                    break;
            }
            c.drawText(label, cx, 42 * d, t);
        }
    }

    /**
     * The top of a page, the way the iPhone draws it: a bar with the round avatar on the right (and "‹ Back" on the
     * left on a pushed page), then the page's title in large bold type.
     */
    LinearLayout largeTitle(String title, boolean withBack) {
        Context c = getContext();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(PhoneKit.dp(c, 8), 0, PhoneKit.dp(c, 16), 0);
        if (withBack) {
            TextView back = PhoneKit.text(c, "\u2039 Back", 17, Theme.RED, PhoneKit.REGULAR);
            back.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17);
            back.setPadding(PhoneKit.dp(c, 6), PhoneKit.dp(c, 4), PhoneKit.dp(c, 10), PhoneKit.dp(c, 4));
            back.setBackground(PhoneKit.pressable(null, PhoneKit.dp(c, 8)));
            back.setContentDescription("Back");
            back.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    back();
                }
            });
            bar.addView(back);
        }
        bar.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        bar.addView(profileButton(28));
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PhoneKit.dp(c, 44)));
        TextView t = PhoneKit.text(c, title, 34, Color.WHITE, PhoneKit.BOLD);
        t.setSingleLine(true);
        t.setPadding(PhoneKit.dp(c, 16), 0, PhoneKit.dp(c, 16), PhoneKit.dp(c, 8));
        col.addView(t);
        return col;
    }

    /** The top of a pushed page: a back arrow and the page's title (which takes the spare width). */
    LinearLayout backHeader(String title, float sp) {
        Context c = getContext();
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, PhoneKit.dp(c, 8), PhoneKit.dp(c, 12), PhoneKit.dp(c, 4));
        TextView back = PhoneKit.text(c, "‹", 30, Color.WHITE, true);
        back.setPadding(PhoneKit.dp(c, 14), 0, PhoneKit.dp(c, 10), PhoneKit.dp(c, 4));
        back.setContentDescription("Back");
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                back();
            }
        });
        head.addView(back);
        head.addView(PhoneKit.text(c, title, sp, Color.WHITE, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return head;
    }

    /** A section heading inside a page. */
    static TextView sectionTitle(Context c, String s) {
        TextView t = PhoneKit.text(c, s, 17, Color.WHITE, true);
        int p = PhoneKit.dp(c, 14);
        t.setPadding(p, PhoneKit.dp(c, 18), p, PhoneKit.dp(c, 8));
        t.setGravity(Gravity.START);
        return t;
    }
}
