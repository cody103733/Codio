package com.codio.tv.ui;

import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** Touch controls over the video: tap to show, play/pause, ±10 s (or double-tap a side), seek bar, audio & subtitles. */
final class PhonePlayer extends FrameLayout {
    private final PhoneUi ui;
    private Store.Meta meta;
    private String videoId;
    private final PlayerState st;
    private final LinearLayout controls;
    private final TextView title, playPause, time, total, next, error;
    private final SeekBar seek;
    private final ProgressBar spin;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean dragging = false, shown = true, closed = false;
    private long hideAt;
    private final boolean offline;
    private double lastSaved = 0;
    /** Skip Intro / Recap / Credits (times from IntroDB) and Next Episode once the credits roll. */
    private final TextView skipButton;
    private SkipTimes.Times skip;
    private String skipFor;
    private static final int SKIP_NONE = 0, SKIP_INTRO = 1, SKIP_RECAP = 2, SKIP_CREDITS = 3, SKIP_NEXT = 4;
    private int skipShown = SKIP_NONE;

    PhonePlayer(final PhoneUi ui, Store.Meta m, String videoId) {
        super(ui.getContext());
        this.ui = ui;
        this.meta = m;
        this.videoId = videoId;
        this.st = ui.playerState;
        offline = ui.playingOffline;
        setBackgroundColor(Color.TRANSPARENT);

        spin = new ProgressBar(getContext());
        addView(spin, new LayoutParams(dp(56), dp(56), Gravity.CENTER));
        error = PhoneKit.text(getContext(), "", 15, Color.WHITE, true);
        error.setGravity(Gravity.CENTER);
        error.setPadding(dp(32), 0, dp(32), 0);
        addView(error, new LayoutParams(-1, -2, Gravity.CENTER));

        controls = new LinearLayout(getContext());
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x33000000, 0x33000000, 0xCC000000}));
        addView(controls, new LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(14), dp(16), 0);
        TextView back = icon("‹", 34);
        back.setContentDescription("Close the player");
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                back();
            }
        });
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        title = PhoneKit.text(getContext(), "", 16, Color.WHITE, true);
        title.setSingleLine(true);
        title.setPadding(dp(8), 0, dp(8), 0);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView castBtn = PhoneKit.text(getContext(), "Cast", 14, Color.WHITE, true);
        castBtn.setPadding(dp(12), dp(10), dp(12), dp(10));
        castBtn.setBackground(PhoneKit.pressable(null, dp(18)));
        castBtn.setClickable(true);
        castBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                castDialog();
            }
        });
        top.addView(castBtn);
        TextView tracks = PhoneKit.text(getContext(), "Audio & Subtitles", 14, Color.WHITE, true);
        tracks.setPadding(dp(12), dp(10), dp(12), dp(10));
        tracks.setBackground(PhoneKit.pressable(null, dp(18)));
        tracks.setClickable(true);
        tracks.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                tracksMenu();
            }
        });
        top.addView(tracks);
        controls.addView(top);

        LinearLayout mid = new LinearLayout(getContext());
        mid.setGravity(Gravity.CENTER);
        TextView rw = icon("↺10", 20);
        rw.setContentDescription("Back 10 seconds");
        rw.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                seekBy(-10);
            }
        });
        playPause = icon("❚❚", 30);
        playPause.setContentDescription("Pause");
        playPause.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePause();
            }
        });
        TextView ff = icon("10↻", 20);
        ff.setContentDescription("Forward 10 seconds");
        ff.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                seekBy(10);
            }
        });
        mid.addView(rw, new LinearLayout.LayoutParams(dp(72), dp(72)));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(84), dp(84));
        pl.setMargins(dp(40), 0, dp(40), 0);
        mid.addView(playPause, pl);
        mid.addView(ff, new LinearLayout.LayoutParams(dp(72), dp(72)));
        controls.addView(mid, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = new LinearLayout(getContext());
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        // Kept well above the bottom edge: a sideways swipe there is the phone's own "switch apps" gesture.
        bottom.setPadding(dp(24), 0, dp(24), dp(40));
        time = PhoneKit.text(getContext(), "0:00", 13, Color.WHITE, false);
        bottom.addView(time, new LinearLayout.LayoutParams(dp(64), -2));
        seek = new SeekBar(getContext());
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && st.duration > 0) time.setText(clock(st.duration * p / 1000.0));
                if (user) keepUp();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
                keepUp();
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (st.duration > 0) seekTo(st.duration * s.getProgress() / 1000.0);
                keepUp();
            }
        });
        seek.setPadding(dp(16), dp(14), dp(16), dp(14));
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(PhoneKit.RED));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(PhoneKit.RED));
        seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x80FFFFFF));
        bottom.addView(seek, new LinearLayout.LayoutParams(0, dp(52), 1));
        total = PhoneKit.text(getContext(), "", 13, Color.WHITE, false);
        total.setGravity(Gravity.END);
        bottom.addView(total, new LinearLayout.LayoutParams(dp(64), -2));
        next = PhoneKit.text(getContext(), "Next episode ›", 14, Color.WHITE, true);
        next.setPadding(dp(12), dp(8), dp(12), dp(8));
        next.setBackground(PhoneKit.pressable(PhoneKit.round(0x33FFFFFF, dp(6)), dp(6)));
        next.setClickable(true);
        next.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                playNext();
            }
        });
        bottom.addView(next);
        controls.addView(bottom);

        final GestureDetector g = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (shown) hideControls();
                else keepUp();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                seekBy(e.getX() < getWidth() / 2f ? -10 : 10);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                // Slide a finger sideways anywhere on the video to scrub (the whole width is about 3 minutes).
                if (st.duration <= 0 || a == null) return false;
                float moved = b.getX() - a.getX();
                if (scrubFrom < 0) {
                    if (Math.abs(moved) < dp(24) || Math.abs(b.getY() - a.getY()) > Math.abs(moved)) return false;
                    scrubFrom = shownPosition();
                }
                double t = scrubFrom + moved / Math.max(1f, getWidth()) * 180.0;
                scrubTo = Math.max(0, Math.min(st.duration - 1, t));
                dragging = true;
                keepUp();
                time.setText(clock(scrubTo));
                seek.setProgress((int) Math.round(1000 * scrubTo / st.duration));
                return true;
            }
        });
        setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                boolean r = g.onTouchEvent(e);
                int a = e.getActionMasked();
                if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && scrubFrom >= 0) {
                    scrubFrom = -1;
                    dragging = false;
                    if (a == MotionEvent.ACTION_UP && scrubTo >= 0) seekTo(scrubTo);
                    scrubTo = -1;
                    keepUp();
                }
                return r;
            }
        });
        skipButton = PhoneKit.text(getContext(), "Skip Intro", 15, Color.WHITE, true);
        skipButton.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable skipBg = PhoneKit.round(0x99000000, dp(6));
        skipBg.setStroke(dp(2), Color.WHITE);
        skipButton.setBackground(PhoneKit.pressable(skipBg, dp(6)));
        skipButton.setClickable(true);
        skipButton.setVisibility(GONE);
        skipButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                doSkip();
            }
        });
        LayoutParams slp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        slp.setMargins(0, 0, dp(24), dp(110));
        addView(skipButton, slp);

        setVideo(m, videoId);
        keepUp();
        h.post(tick);
    }

    private boolean skipEnabled() {
        return !"off".equals(ui.host.prefs().getString("skip_buttons", "on"));
    }

    private void loadSkip() {
        if (videoId == null || videoId.equals(skipFor) || !skipEnabled()) return;
        int[] se = SkipTimes.episodeOf(meta, videoId);
        String imdb = SkipTimes.imdbOf(meta, videoId);
        if (se == null || imdb == null) return;
        skipFor = videoId;
        skip = null;
        final String forVideo = videoId;
        SkipTimes.load(imdb, se[0], se[1], new SkipTimes.Done() {
            @Override
            public void done(SkipTimes.Times t) {
                if (forVideo.equals(videoId)) skip = t;
            }
        });
    }

    private int skipKind() {
        if (skip == null || !st.started || st.error != null || dragging || mini || !skipEnabled()) return SKIP_NONE;
        double t = shownPosition();
        if (skip.intro != null && skip.intro.contains(t)) return SKIP_INTRO;
        if (skip.recap != null && skip.recap.contains(t)) return SKIP_RECAP;
        if (skip.outro != null && t >= skip.outro.start && (st.duration <= 0 || t < st.duration - 1)) {
            if (nextVideo() != null) return SKIP_NEXT;
            if (skip.outro.contains(t) && st.duration > 0 && skip.outro.end < st.duration - 5) return SKIP_CREDITS;
        }
        return SKIP_NONE;
    }

    private void doSkip() {
        int kind = skipKind();
        if (kind == SKIP_NEXT) {
            playNext();
            return;
        }
        SkipTimes.Segment seg = kind == SKIP_INTRO ? skip.intro : kind == SKIP_RECAP ? skip.recap : kind == SKIP_CREDITS ? skip.outro : null;
        if (seg != null) seekTo(seg.end);
    }

    private int dp(float v) {
        return PhoneKit.dp(getContext(), v);
    }

    private TextView icon(String s, float sp) {
        TextView t = PhoneKit.text(getContext(), s, sp, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(PhoneKit.pressable(null, dp(40)));
        t.setClickable(true);
        return t;
    }

    void setVideo(Store.Meta m, String vid) {
        if (m != null && m.id != null && !m.id.isEmpty()) meta = m;
        videoId = vid;
        Store.Video v = current();
        title.setText((meta.name != null ? meta.name : "") + (v != null ? "  ·  S" + v.season + " E" + v.episode + (v.title != null && !v.title.isEmpty() ? " " + v.title : "") : ""));
        next.setVisibility(nextVideo() != null ? VISIBLE : GONE);
        loadSkip();
        keepUp();
    }

    private Store.Video current() {
        if (videoId == null) return null;
        for (Store.Video v : meta.videos) if (videoId.equals(v.id)) return v;
        return null;
    }

    private Store.Video nextVideo() {
        Store.Video c = current();
        if (c == null) return null;
        int i = meta.videos.indexOf(c);
        if (i < 0 || i + 1 >= meta.videos.size()) return null;
        Store.Video n = meta.videos.get(i + 1);
        if (offline) {
            PhoneDownloads.Entry e = ui.downloads.entry(meta.type, meta.id, n.id);
            if ((e == null || !PhoneDownloads.DONE.equals(e.state)) && !ui.online()) return null;
        }
        return n;
    }

    private void playNext() {
        Store.Video n = nextVideo();
        if (n == null) return;
        savePosition();
        if (offline) {
            closed = true;
            ui.host.stopPlayer();
        }
        ui.play(meta, n.id);
    }

    /** The video reached its end. */
    void ended() {
        if (!offline || closed) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (closed) return;
                Store.Video n = nextVideo();
                lastSaved = -1;
                ui.downloads.savePosition(meta.type, meta.id, videoId, st.duration, st.duration);
                boolean binge = true;
                org.json.JSONObject s = ui.store.profileJson.optJSONObject("settings");
                if (s != null && s.has("bingeWatching")) binge = s.optBoolean("bingeWatching", true);
                if (n != null && binge) {
                    PhoneDownloads.Entry e = ui.downloads.entry(meta.type, meta.id, n.id);
                    if (e != null && PhoneDownloads.DONE.equals(e.state)) {
                        closed = true;
                        ui.host.stopPlayer();
                        ui.play(meta, n.id);
                        return;
                    }
                }
                back();
            }
        });
    }

    private void togglePause() {
        ui.host.player("mpv-set-prop", new JSONArray().put("pause").put(!st.paused));
        st.paused = !st.paused;
        st.timeAt = SystemClock.uptimeMillis();
        update();
        keepUp();
    }

    private void seekBy(double s) {
        double t = Math.max(0, shownPosition() + s);
        if (st.duration > 0) t = Math.min(st.duration - 1, t);
        seekTo(t);
        keepUp();
    }

    private double scrubFrom = -1, scrubTo = -1;
    /** Where a seek was asked: shown until the player catches up (it keeps reporting the old time for a moment). */
    private double seekTarget = -1;
    private long seekAt = 0;

    private double shownPosition() {
        if (seekTarget >= 0) {
            boolean caughtUp = Math.abs(st.time - seekTarget) < 3;
            if (!caughtUp && SystemClock.uptimeMillis() - seekAt < 6000) return seekTarget;
            seekTarget = -1;
        }
        return st.position();
    }

    private void seekTo(double t) {
        seekTarget = t;
        seekAt = SystemClock.uptimeMillis();
        st.time = t;
        st.timeAt = SystemClock.uptimeMillis();
        try {
            ui.host.player("mpv-set-prop", new JSONArray().put("time-pos").put(t));
        } catch (Exception ignored) {
        }
        update();
    }

    private void tracksMenu() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> picks = new ArrayList<>();
        for (int i = 0; i < st.audio.size(); i++) {
            final PlayerState.Track t = st.audio.get(i);
            labels.add((t.id == st.aid ? "✓ " : "    ") + "Audio: " + PlayerState.label(t, i + 1));
            picks.add(new Runnable() {
                @Override
                public void run() {
                    ui.host.userPick();
                    ui.host.player("mpv-set-prop", new JSONArray().put("aid").put(t.id));
                    st.aid = t.id;
                }
            });
        }
        labels.add((st.sid < 0 ? "✓ " : "    ") + "Subtitles: Off");
        picks.add(new Runnable() {
            @Override
            public void run() {
                ui.host.userPick();
                ui.host.player("mpv-set-prop", new JSONArray().put("sid").put("no"));
                st.sid = -1;
            }
        });
        for (int i = 0; i < st.subs.size(); i++) {
            final PlayerState.Track t = st.subs.get(i);
            labels.add((t.id == st.sid ? "✓ " : "    ") + "Subtitles: " + PlayerState.label(t, i + 1));
            picks.add(new Runnable() {
                @Override
                public void run() {
                    ui.host.userPick();
                    ui.host.player("mpv-set-prop", new JSONArray().put("sid").put(t.id));
                    st.sid = t.id;
                }
            });
        }
        ui.dialog().setTitle("Audio & Subtitles")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        picks.get(which).run();
                    }
                })
                .setNegativeButton("Close", null).show();
    }

    /** The actors in this title, with their photos. The video keeps playing behind it. */
    private void castDialog() {
        keepUp();
        CastInfo.load(meta, videoId, new CastInfo.Done() {
            @Override
            public void done(List<CastInfo.Actor> actors) {
                if (closed) return;
                android.widget.ScrollView scroll = new android.widget.ScrollView(getContext());
                LinearLayout list = new LinearLayout(getContext());
                list.setOrientation(LinearLayout.VERTICAL);
                list.setPadding(dp(20), dp(8), dp(20), dp(8));
                scroll.addView(list);
                if (actors.isEmpty()) list.addView(PhoneKit.text(getContext(), "No cast information for this title.", 15, Color.WHITE, false));
                for (CastInfo.Actor a : actors) {
                    LinearLayout row = new LinearLayout(getContext());
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(6), 0, dp(6));
                    android.widget.ImageView photo = new android.widget.ImageView(getContext());
                    photo.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                    photo.setBackgroundColor(0xFF3A3A3A);
                    PhoneKit.image(photo, a.photo, dp(56));
                    row.addView(photo, new LinearLayout.LayoutParams(dp(56), dp(76)));
                    LinearLayout names = new LinearLayout(getContext());
                    names.setOrientation(LinearLayout.VERTICAL);
                    names.setPadding(dp(14), 0, 0, 0);
                    names.addView(PhoneKit.text(getContext(), a.name, 15, Color.WHITE, true));
                    if (a.role != null) names.addView(PhoneKit.text(getContext(), a.role, 13, 0xFFAAAAAA, false));
                    row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
                    list.addView(row);
                }
                TextView credit = PhoneKit.text(getContext(), CastInfo.CREDIT, 11, 0xFFAAAAAA, false);
                credit.setPadding(0, dp(12), 0, dp(4));
                list.addView(credit);
                ui.dialog().setTitle("Cast").setView(scroll).setNegativeButton("Close", null).show();
            }
        });
    }
    void back() {
        if (closed) return;
        closed = true;
        savePosition();
        ui.host.stopPlayer();
    }

    /** Taken off the screen (by the app): stop ticking. */
    void closing() {
        savePosition();
        closed = true;
        h.removeCallbacks(tick);
    }

    private void savePosition() {
        if (!offline || lastSaved < 0 || !st.started) return;
        ui.downloads.savePosition(meta.type, meta.id, videoId, st.position(), st.duration);
        lastSaved = st.position();
    }

    private boolean mini = false;

    void setMini(boolean on) {
        mini = on;
        if (on) {
            shown = false;
            controls.setVisibility(GONE);
        } else {
            keepUp();
        }
    }

    private void keepUp() {
        if (mini) return;
        shown = true;
        controls.setVisibility(VISIBLE);
        hideAt = SystemClock.uptimeMillis() + 4000;
    }

    private void hideControls() {
        if (st.paused || dragging) return;
        shown = false;
        controls.setVisibility(GONE);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (closed) return;
            update();
            if (shown && SystemClock.uptimeMillis() > hideAt && st.started) hideControls();
            // Downloads remember where you stopped every 15 s, in case the app is closed.
            if (offline && st.started && Math.abs(st.position() - lastSaved) > 15) savePosition();
            h.postDelayed(this, 500);
        }
    };

    void update() {
        double pos = shownPosition();
        boolean busy = st.error == null && (st.buffering || !st.started);
        spin.setVisibility(busy ? VISIBLE : GONE);
        error.setText(st.error != null ? st.error : "");
        error.setVisibility(st.error != null ? VISIBLE : GONE);
        playPause.setText(st.paused ? "▶" : "❚❚");
        playPause.setContentDescription(st.paused ? "Play" : "Pause");
        if (!dragging) {
            time.setText(clock(pos));
            seek.setProgress(st.duration > 0 ? (int) Math.round(1000 * pos / st.duration) : 0);
        }
        total.setText(st.duration > 0 ? clock(st.duration) : "");
        int sk = skipKind();
        if (sk != skipShown) {
            skipShown = sk;
            skipButton.setText(sk == SKIP_INTRO ? "Skip Intro" : sk == SKIP_RECAP ? "Skip Recap" : sk == SKIP_CREDITS ? "Skip Credits" : "Next Episode ›");
            skipButton.setVisibility(sk == SKIP_NONE ? GONE : VISIBLE);
        }
        // Sits above the control bar while that's shown, lower down when it's hidden.
        LayoutParams lp = (LayoutParams) skipButton.getLayoutParams();
        int want = shown ? dp(110) : dp(40);
        if (lp.bottomMargin != want) {
            lp.bottomMargin = want;
            skipButton.setLayoutParams(lp);
        }
        if (st.paused && !shown) keepUp();
    }

    static String clock(double s) {
        int t = (int) Math.max(0, s);
        int hh = t / 3600, mm = (t / 60) % 60, ss = t % 60;
        return hh > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", hh, mm, ss) : String.format(java.util.Locale.US, "%d:%02d", mm, ss);
    }
}
