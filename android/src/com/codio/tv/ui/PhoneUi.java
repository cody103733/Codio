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

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Codio for phones: touch screens built from ordinary Android views (portrait browsing, a bottom tab bar, the player
 * in landscape), on the same engine as the TV app (Store, the hidden web page for sources and playback, VLC).
 */
public final class PhoneUi extends FrameLayout implements NativeUi, PhoneDownloads.Listener {
    static final int TAB_HOME = 0, TAB_SEARCH = 1, TAB_LIBRARY = 2, TAB_SETTINGS = 3;

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
    private final TabButton[] tabButtons = new TabButton[4];
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
        setBackgroundColor(PhoneKit.BG);
        downloads = new PhoneDownloads(act, store, host, this);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        content = new FrameLayout(act);
        col.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        tabs = new LinearLayout(act);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(0xFF121212);
        String[] labels = {"Home", "Search", "Library", "Settings"};
        for (int i = 0; i < 4; i++) {
            final int t = i;
            TabButton b = new TabButton(act, i, labels[i]);
            b.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    openTab(t);
                }
            });
            tabButtons[i] = b;
            tabs.addView(b, new LinearLayout.LayoutParams(0, PhoneKit.dp(act, 58), 1));
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

    private void updateTabs() {
        Page t = top();
        int sel = t != null ? t.tab : 0;
        for (int i = 0; i < tabButtons.length; i++) tabButtons[i].setSelected(i == sel);
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
        if (player == null) return;
        player.closing();
        removeView(player);
        player = null;
        setBackgroundColor(PhoneKit.BG);
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
        if (name.startsWith("Error:")) {
            String ev = name.substring(6);
            if (ev.equals("UserPulledFromAPI") || ev.equals("LibrarySyncWithAPIPlanned") || ev.equals("UserAddonsLocked")) return;
            toast(message != null && !message.isEmpty() ? message : "Something went wrong");
        } else if (name.equals("AddonInstalled")) toast("Addon installed");
        else if (name.equals("AddonUninstalled")) toast("Addon removed");
        else if (name.equals("UserAuthenticated")) toast("Signed in");
        else if (name.equals("UserLoggedOut")) toast("Signed out");
    }

    // ------------------------------------------------------------------ who's watching (round avatar, top right)

    /** A round avatar for the profile being watched; tap it to pick who's watching. Sits in the top right of each tab. */
    View profileButton() {
        final Avatar a = new Avatar(getContext(), null, 34);
        a.setContentDescription("Who's watching");
        a.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                pickProfile();
            }
        });
        avatars.add(new java.lang.ref.WeakReference<>(a));
        return a;
    }

    private final ArrayList<java.lang.ref.WeakReference<Avatar>> avatars = new ArrayList<>();

    /** The account or profile list changed: every top-right avatar redraws. */
    private void refreshAvatars() {
        for (int i = avatars.size() - 1; i >= 0; i--) {
            Avatar a = avatars.get(i).get();
            if (a == null) avatars.remove(i);
            else a.invalidate();
        }
    }

    /** A page title with the round avatar on the right. */
    static View titleRow(PhoneUi ui, TextView title) {
        LinearLayout row = new LinearLayout(ui.getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.setMargins(0, PhoneKit.dp(ui.getContext(), 6), PhoneKit.dp(ui.getContext(), 14), 0);
        row.addView(ui.profileButton(), al);
        return row;
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
            TextView name = PhoneKit.text(c, Profiles.name(p) + (cur ? "  ·  watching" : ""), 17, cur ? Color.WHITE : 0xFFCCCCCC, cur);
            name.setPadding(PhoneKit.dp(c, 16), 0, 0, 0);
            row.addView(name);
            row.setBackground(PhoneKit.pressable(null, 0));
            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (shown[0] != null) shown[0].dismiss();
                    Profiles.switchTo(store, p, new Profiles.Ui() {
                        @Override
                        public void ctx(String n, Object args) {
                            PhoneUi.this.ctx(n, args);
                        }

                        @Override
                        public void toast(String text) {
                            PhoneUi.this.toast(text);
                        }
                    });
                }
            });
            box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (l.length() == 0) {
            TextView hint = PhoneKit.text(c, "Make more profiles on the iPhone. They show up here on their own.", 13, PhoneKit.TEXT2, false);
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
            ring.setColor(0x40FFFFFF);
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

    void toast(String s) {
        Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
    }

    AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    /** Send an action to Codio's core, e.g. AddToLibrary. */
    void ctx(String name, Object args) {
        try {
            JSONObject inner = new JSONObject();
            inner.put("action", name);
            if (args != null) inner.put("args", args);
            JSONObject a = new JSONObject();
            a.put("action", "Ctx");
            a.put("args", inner);
            host.dispatch(a);
        } catch (Exception ignored) {
        }
    }

    /** Play: a finished download plays from the phone, anything else streams with Smart auto-play. */
    void play(Store.Meta m, String videoId) {
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

    private static final class TabButton extends View {
        private final int icon;
        private final String label;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        TabButton(Context c, int icon, String label) {
            super(c);
            this.icon = icon;
            this.label = label;
            t.setTextSize(PhoneKit.dp(c, 11));
            t.setTextAlign(Paint.Align.CENTER);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(PhoneKit.dp(c, 2));
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
            int col = isSelected() ? Color.WHITE : 0xFF8C8C8C;
            p.setColor(col);
            t.setColor(col);
            float cx = getWidth() / 2f, cy = getHeight() * 0.38f, s = PhoneKit.dp(getContext(), 10);
            path.reset();
            switch (icon) {
                case TAB_HOME:
                    path.moveTo(cx - s, cy);
                    path.lineTo(cx, cy - s);
                    path.lineTo(cx + s, cy);
                    path.moveTo(cx - s * 0.75f, cy - s * 0.2f);
                    path.lineTo(cx - s * 0.75f, cy + s);
                    path.lineTo(cx + s * 0.75f, cy + s);
                    path.lineTo(cx + s * 0.75f, cy - s * 0.2f);
                    c.drawPath(path, p);
                    break;
                case TAB_SEARCH:
                    c.drawCircle(cx - s * 0.2f, cy - s * 0.2f, s * 0.65f, p);
                    c.drawLine(cx + s * 0.28f, cy + s * 0.28f, cx + s * 0.9f, cy + s * 0.9f, p);
                    break;
                case TAB_LIBRARY:
                    r.set(cx - s, cy - s * 0.55f, cx + s * 0.55f, cy + s);
                    c.drawRoundRect(r, s * 0.2f, s * 0.2f, p);
                    c.drawLine(cx - s * 0.55f, cy - s, cx + s, cy - s, p);
                    c.drawLine(cx + s, cy - s, cx + s, cy + s * 0.55f, p);
                    break;
                default:
                    c.drawCircle(cx, cy, s * 0.35f, p);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI * i / 4;
                        c.drawLine(cx + (float) Math.cos(a) * s * 0.65f, cy + (float) Math.sin(a) * s * 0.65f,
                                cx + (float) Math.cos(a) * s, cy + (float) Math.sin(a) * s, p);
                    }
                    break;
            }
            c.drawText(label, cx, getHeight() * 0.86f, t);
        }
    }

    /** Small "tap a row" helper for the pages. */
    static TextView sectionTitle(Context c, String s) {
        TextView t = PhoneKit.text(c, s, 17, Color.WHITE, true);
        int p = PhoneKit.dp(c, 14);
        t.setPadding(p, PhoneKit.dp(c, 18), p, PhoneKit.dp(c, 8));
        t.setGravity(Gravity.START);
        return t;
    }
}
