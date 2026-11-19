package com.codio.tv.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.codio.tv.music.Lyrics;
import com.codio.tv.music.Model;
import com.codio.tv.music.MusicNet;
import com.codio.tv.music.Model.Track;
import com.codio.tv.music.MusicPlayer;
import com.codio.tv.music.Taste;

import java.util.List;

/** The music player full screen: cover, seek bar, controls, volume and the queue. */
final class PhoneNowPlaying extends PhoneUi.Page {
    private MusicKit.Cover cover;
    private String coverFor;
    private TextView title, artist, info, elapsed, total, message;
    private SeekBar seek, volume;
    private MusicIcons play, shuffle, repeat, heart, speaker;
    private LinearLayout queueBox, lyricsCard;
    private TextView lyricsNow, lyricsNext;
    private String lyricsFor;
    private Lyrics.Result lyrics;
    private int lyricsLine = -2;
    private boolean dragging;
    private int queueSig = -1;

    private final MusicPlayer.Listener listener = new MusicPlayer.Listener() {
        @Override
        public void changed() {
            update();
        }

        @Override
        public void tick() {
            progress();
        }
    };
    private final Runnable tasteChanged = new Runnable() {
        @Override
        public void run() {
            update();
        }
    };

    PhoneNowPlaying(PhoneUi ui) {
        super(ui);
    }

    @Override
    View build() {
        final Context c = ctx();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ui.largeTitle("Now playing", true));
        ScrollView sv = new ScrollView(c);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(30));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        int screen = c.getResources().getDisplayMetrics().widthPixels;
        int size = Math.min(screen - dp(64), dp(340));
        cover = new MusicKit.Cover(c, 100, false, "");
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(size, size);
        cl.gravity = Gravity.CENTER_HORIZONTAL;
        cl.topMargin = dp(8);
        cover.setLayoutParams(cl);
        body.addView(cover);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(24), dp(18), dp(12), 0);
        LinearLayout names = new LinearLayout(c);
        names.setOrientation(LinearLayout.VERTICAL);
        title = PhoneKit.text(c, "", 22, Color.WHITE, PhoneKit.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        artist = PhoneKit.text(c, "", 17, Theme.TEXT_DIM, PhoneKit.REGULAR);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(title);
        names.addView(artist);
        head.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        heart = new MusicIcons(c, MusicIcons.HEART, Color.WHITE);
        heart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Track t = MusicPlayer.current();
                if (t != null) Taste.toggleLikeTrack(t);
            }
        });
        head.addView(heart, new LinearLayout.LayoutParams(dp(44), dp(44)));
        MusicIcons more = new MusicIcons(c, MusicIcons.MORE, Color.WHITE);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Track t = MusicPlayer.current();
                if (t != null) MusicKit.trackMenu(ui, t);
            }
        });
        head.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
        body.addView(head);
        info = PhoneKit.text(c, "", 12, Theme.TEXT_FAINT, PhoneKit.REGULAR);
        info.setPadding(dp(24), dp(2), dp(24), 0);
        body.addView(info);

        seek = new SeekBar(c);
        seek.setMax(1000);
        tint(seek, Color.WHITE);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && MusicPlayer.duration() > 0) elapsed.setText(Model.formatTime(MusicPlayer.duration() * p / 1000.0));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (MusicPlayer.duration() > 0) MusicPlayer.seek(MusicPlayer.duration() * s.getProgress() / 1000.0);
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(36));
        sl.setMargins(dp(12), dp(14), dp(12), 0);
        body.addView(seek, sl);
        LinearLayout times = new LinearLayout(c);
        times.setPadding(dp(24), 0, dp(24), 0);
        elapsed = PhoneKit.text(c, "0:00", 12, Theme.TEXT_DIM, PhoneKit.REGULAR);
        total = PhoneKit.text(c, "0:00", 12, Theme.TEXT_DIM, PhoneKit.REGULAR);
        total.setGravity(Gravity.END);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(total, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(times);

        LinearLayout controls = new LinearLayout(c);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(12), dp(10), dp(12), dp(6));
        shuffle = control(controls, MusicIcons.SHUFFLE, 44, 22, new Runnable() {
            @Override
            public void run() {
                MusicPlayer.toggleShuffle();
            }
        });
        control(controls, MusicIcons.PREV, 56, 30, new Runnable() {
            @Override
            public void run() {
                MusicPlayer.previous();
            }
        });
        play = control(controls, MusicIcons.PLAY, 72, 40, new Runnable() {
            @Override
            public void run() {
                MusicPlayer.toggle();
            }
        });
        control(controls, MusicIcons.NEXT, 56, 30, new Runnable() {
            @Override
            public void run() {
                MusicPlayer.next();
            }
        });
        repeat = control(controls, MusicIcons.REPEAT, 44, 22, new Runnable() {
            @Override
            public void run() {
                MusicPlayer.cycleRepeat();
            }
        });
        body.addView(controls);

        LinearLayout vol = new LinearLayout(c);
        vol.setGravity(Gravity.CENTER_VERTICAL);
        vol.setPadding(dp(24), 0, dp(24), 0);
        speaker = new MusicIcons(c, MusicIcons.VOLUME, Theme.TEXT_DIM);
        speaker.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MusicPlayer.toggleMute();
            }
        });
        vol.addView(speaker, new LinearLayout.LayoutParams(dp(28), dp(28)));
        volume = new SeekBar(c);
        volume.setMax(100);
        tint(volume, Theme.TEXT_DIM);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) MusicPlayer.setVolume(p / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        vol.addView(volume, new LinearLayout.LayoutParams(0, dp(36), 1));
        body.addView(vol);

        // The lyrics card: the line being sung and the next one; tap for all of them.
        lyricsCard = new LinearLayout(c);
        lyricsCard.setOrientation(LinearLayout.VERTICAL);
        lyricsCard.setPadding(dp(16), dp(12), dp(16), dp(14));
        lyricsCard.setBackground(PhoneKit.round(Theme.RAISED, dp(12)));
        lyricsCard.addView(PhoneKit.text(c, "LYRICS", 12, Theme.TEXT_DIM, PhoneKit.SEMIBOLD));
        lyricsNow = PhoneKit.text(c, "", 19, Color.WHITE, PhoneKit.BOLD);
        lyricsNow.setPadding(0, dp(6), 0, 0);
        lyricsNow.setMaxLines(3);
        lyricsNext = PhoneKit.text(c, "", 17, Theme.TEXT_FAINT, PhoneKit.BOLD);
        lyricsNext.setPadding(0, dp(4), 0, 0);
        lyricsNext.setMaxLines(4);
        lyricsCard.addView(lyricsNow);
        lyricsCard.addView(lyricsNext);
        lyricsCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.push(new PhoneLyrics(ui));
            }
        });
        lyricsCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.setMargins(dp(16), dp(14), dp(16), 0);
        body.addView(lyricsCard, ll);

        message = PhoneKit.text(c, "", 14, Theme.RED_SOFT, PhoneKit.REGULAR);
        message.setPadding(dp(24), dp(10), dp(24), 0);
        body.addView(message);

        LinearLayout qhead = new LinearLayout(c);
        qhead.setGravity(Gravity.CENTER_VERTICAL);
        TextView qt = PhoneUi.sectionTitle(c, "Up next");
        qhead.addView(qt, new LinearLayout.LayoutParams(0, -2, 1));
        TextView stop = PhoneKit.link(c, "Stop and close", PhoneKit.DESTRUCTIVE);
        stop.setTextSize(15);
        stop.setPadding(dp(8), dp(18), dp(16), dp(8));
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MusicPlayer.stop();
                ui.back();
            }
        });
        qhead.addView(stop);
        body.addView(qhead);
        queueBox = new LinearLayout(c);
        queueBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(queueBox);
        update();
        return col;
    }

    private void tint(SeekBar s, int color) {
        s.setProgressTintList(ColorStateList.valueOf(color));
        s.setThumbTintList(ColorStateList.valueOf(color));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF48484A));
    }

    private MusicIcons control(LinearLayout row, int kind, int boxDp, int iconDp, final Runnable action) {
        Context c = ctx();
        MusicIcons i = new MusicIcons(c, kind, Color.WHITE);
        i.setPadding((dp(boxDp) - dp(iconDp)) / 2, (dp(boxDp) - dp(iconDp)) / 2, (dp(boxDp) - dp(iconDp)) / 2, (dp(boxDp) - dp(iconDp)) / 2);
        i.setBackground(PhoneKit.pressable(null, dp(boxDp) / 2f));
        i.setClickable(true);
        i.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(boxDp), dp(boxDp));
        lp.setMargins(dp(6), 0, dp(6), 0);
        row.addView(i, lp);
        return i;
    }

    @Override
    void onShow() {
        MusicPlayer.listen(listener);
        Taste.listeners.add(tasteChanged);
        update();
    }

    @Override
    void onHide() {
        MusicPlayer.unlisten(listener);
        Taste.listeners.remove(tasteChanged);
    }

    private void progress() {
        if (seek == null) return;
        double d = MusicPlayer.duration(), p = MusicPlayer.position();
        if (!dragging) {
            seek.setProgress(d > 0 ? (int) Math.min(1000, p / d * 1000) : 0);
            elapsed.setText(Model.formatTime(p));
        }
        total.setText(Model.formatTime(d));
        lyricsLine();
    }

    private void loadLyrics(final Track t) {
        if (t.key().equals(lyricsFor)) return;
        lyricsFor = t.key();
        lyrics = null;
        lyricsLine = -2;
        lyricsCard.setVisibility(View.GONE);
        final String mine = lyricsFor;
        Lyrics.get(t, (long) (MusicPlayer.duration() * 1000), new MusicNet.Done<Lyrics.Result>() {
            @Override
            public void done(Lyrics.Result r, Exception e) {
                if (lyricsCard == null || !mine.equals(lyricsFor)) return;
                if (e != null) lyricsFor = null; // try again on the next change
                lyrics = r;
                if (r == null) return;
                lyricsCard.setVisibility(View.VISIBLE);
                if (!r.synced()) {
                    lyricsNow.setText(r.instrumental ? "♪  Instrumental" : firstLines(r.plain, 3));
                    lyricsNext.setText(r.instrumental ? "" : "Tap for all the words");
                    lyricsNext.setVisibility(r.instrumental ? View.GONE : View.VISIBLE);
                }
                lyricsLine();
            }
        });
    }

    private static String firstLines(String text, int n) {
        StringBuilder sb = new StringBuilder();
        int k = 0;
        for (String l : text.split("\\r?\\n")) {
            if (l.trim().length() == 0) continue;
            if (k++ == n) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(l.trim());
        }
        return sb.toString();
    }

    private void lyricsLine() {
        if (lyrics == null || !lyrics.synced() || lyricsNow == null) return;
        int at = lyrics.lineAt(MusicPlayer.position());
        if (at == lyricsLine) return;
        lyricsLine = at;
        String now = at >= 0 ? lyrics.lines.get(at).text : "♪";
        String next = at + 1 < lyrics.lines.size() ? lyrics.lines.get(at + 1).text : "";
        lyricsNow.setText(now.length() == 0 ? "♪" : now);
        lyricsNext.setText(next);
        lyricsNext.setVisibility(next.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void update() {
        if (title == null) return;
        Track t = MusicPlayer.current();
        if (t == null) {
            if (ui.top() == this) ui.back();
            return;
        }
        title.setText(t.title);
        artist.setText(t.artist);
        if (!MusicPlayer.loading()) loadLyrics(t);
        if (t.artworkURL == null ? coverFor != null : !t.artworkURL.equals(coverFor)) {
            coverFor = t.artworkURL;
            cover.set(coverFor);
        }
        String src = MusicPlayer.loading() ? MusicPlayer.loadingText("Finding a source…") : join(MusicPlayer.source(), MusicPlayer.quality());
        info.setText(src);
        message.setText(MusicPlayer.message() == null ? "" : MusicPlayer.message());
        message.setVisibility(MusicPlayer.message() == null ? View.GONE : View.VISIBLE);
        boolean liked = Taste.isLikedTrack(t);
        heart.set(liked ? MusicIcons.HEART_FILL : MusicIcons.HEART, liked ? Theme.RED : Color.WHITE);
        play.set(MusicPlayer.playing() ? MusicIcons.PAUSE : MusicIcons.PLAY, Color.WHITE);
        shuffle.set(MusicIcons.SHUFFLE, MusicPlayer.shuffle() ? Theme.RED : PhoneKit.GRAY);
        repeat.set(MusicPlayer.repeat() == MusicPlayer.REPEAT_ONE ? MusicIcons.REPEAT_ONE : MusicIcons.REPEAT,
                MusicPlayer.repeat() == MusicPlayer.REPEAT_OFF ? PhoneKit.GRAY : Theme.RED);
        float v = MusicPlayer.volume();
        volume.setProgress(Math.round(v * 100));
        speaker.set(v == 0 ? MusicIcons.MUTE : MusicIcons.VOLUME, Theme.TEXT_DIM);
        progress();
        drawQueue();
    }

    private static String join(String a, String b) {
        if (a == null) return b == null ? "" : b;
        return b == null ? a : a + " · " + b;
    }

    private void drawQueue() {
        final List<Track> q = MusicPlayer.queue();
        int sig = q.size() * 1000 + MusicPlayer.index();
        for (Track t : q) sig = sig * 31 + t.key().hashCode();
        if (sig == queueSig) return;
        queueSig = sig;
        Context c = ctx();
        queueBox.removeAllViews();
        for (int i = 0; i < q.size(); i++) {
            final int at = i;
            final Track t = q.get(i);
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(6), dp(4), dp(6));
            row.setBackground(PhoneKit.pressable(null, 0));
            boolean now = i == MusicPlayer.index();
            TextView n = PhoneKit.text(c, now ? "▶" : String.valueOf(i + 1), 13, now ? Theme.RED_SOFT : Theme.TEXT_DIM, PhoneKit.REGULAR);
            n.setGravity(Gravity.CENTER);
            row.addView(n, new LinearLayout.LayoutParams(dp(28), -2));
            LinearLayout text = new LinearLayout(c);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView tt = PhoneKit.text(c, t.title, 16, now ? Theme.RED_SOFT : Color.WHITE, PhoneKit.REGULAR);
            tt.setSingleLine(true);
            tt.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(tt);
            text.addView(MusicKit.sub(c, t.artist));
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            if (!now) {
                MusicIcons x = new MusicIcons(c, MusicIcons.CLOSE, PhoneKit.GRAY);
                x.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        MusicPlayer.removeFromQueue(at);
                    }
                });
                row.addView(x, new LinearLayout.LayoutParams(dp(40), dp(40)));
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    MusicPlayer.playIndex(at);
                }
            });
            queueBox.addView(row);
        }
    }
}
