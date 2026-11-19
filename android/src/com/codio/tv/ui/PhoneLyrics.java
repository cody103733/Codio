package com.codio.tv.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.codio.tv.music.Lyrics;
import com.codio.tv.music.Model.Track;
import com.codio.tv.music.MusicNet;
import com.codio.tv.music.MusicPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * The words of the song that is playing. Timed lyrics follow the song: the line being sung is white, the rest grey, and
 * the page keeps it in view (unless you just scrolled). Tap a line to jump there. DESIGN.md "Lyrics".
 */
final class PhoneLyrics extends PhoneUi.Page {
    private TextView heading;
    private ScrollView scroll;
    private LinearLayout body;
    private final List<TextView> lineViews = new ArrayList<>();
    private Lyrics.Result lyrics;
    private String shownFor;
    private int current = -2;
    private long touchedAt;

    private final MusicPlayer.Listener listener = new MusicPlayer.Listener() {
        @Override
        public void changed() {
            load();
        }

        @Override
        public void tick() {
            follow(false);
        }
    };

    PhoneLyrics(PhoneUi ui) {
        super(ui);
    }

    @Override
    View build() {
        Context c = ctx();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ui.largeTitle("Lyrics", true));
        heading = PhoneKit.text(c, "", 15, Theme.TEXT_DIM, PhoneKit.REGULAR);
        heading.setPadding(dp(20), 0, dp(20), dp(8));
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(heading);
        scroll = new ScrollView(c);
        scroll.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                touchedAt = System.currentTimeMillis();
                return false;
            }
        });
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(200));
        scroll.addView(body);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        load();
        return col;
    }

    @Override
    void onShow() {
        MusicPlayer.listen(listener);
        load();
    }

    @Override
    void onHide() {
        MusicPlayer.unlisten(listener);
    }

    private void load() {
        if (body == null) return;
        final Track t = MusicPlayer.current();
        if (t == null) {
            if (ui.top() == this) ui.back();
            return;
        }
        if (t.key().equals(shownFor)) return;
        shownFor = t.key();
        heading.setText(t.title + " · " + t.artist);
        lyrics = null;
        current = -2;
        lineViews.clear();
        body.removeAllViews();
        ProgressBar p = new ProgressBar(ctx());
        p.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Theme.RED));
        LinearLayout box = new LinearLayout(ctx());
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, dp(40), 0, 0);
        box.addView(p, new LinearLayout.LayoutParams(dp(40), dp(40)));
        body.addView(box);
        final String mine = shownFor;
        Lyrics.get(t, (long) (MusicPlayer.duration() * 1000), new MusicNet.Done<Lyrics.Result>() {
            @Override
            public void done(Lyrics.Result r, Exception e) {
                if (body == null || !mine.equals(shownFor)) return;
                lyrics = r;
                draw(e);
            }
        });
    }

    private void draw(Exception error) {
        Context c = ctx();
        body.removeAllViews();
        lineViews.clear();
        if (lyrics == null) {
            body.addView(MusicKit.note(c, error != null ? "Couldn't reach the lyrics. Check your connection." : "No lyrics found for this song."));
            return;
        }
        if (lyrics.instrumental && !lyrics.synced()) {
            body.addView(PhoneKit.text(c, "♪  Instrumental", 24, Color.WHITE, PhoneKit.BOLD));
        } else if (lyrics.synced()) {
            for (int i = 0; i < lyrics.lines.size(); i++) {
                final Lyrics.Line l = lyrics.lines.get(i);
                TextView tv = PhoneKit.text(c, l.text.length() == 0 ? "♪" : l.text, 24, Theme.TEXT_FAINT, PhoneKit.BOLD);
                tv.setPadding(0, dp(7), 0, dp(7));
                tv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        MusicPlayer.seek(l.ms / 1000.0);
                        touchedAt = 0;
                    }
                });
                lineViews.add(tv);
                body.addView(tv);
            }
        } else {
            TextView tv = PhoneKit.text(c, lyrics.plain, 20, Color.WHITE, PhoneKit.SEMIBOLD);
            tv.setLineSpacing(dp(6), 1f);
            body.addView(tv);
            body.addView(MusicKit.note(c, "These lyrics aren't timed to the song."));
        }
        TextView credit = PhoneKit.text(c, Lyrics.CREDIT, 12, Theme.TEXT_FAINT, PhoneKit.REGULAR);
        credit.setPadding(0, dp(24), 0, 0);
        body.addView(credit);
        follow(true);
    }

    private void follow(boolean jump) {
        if (lyrics == null || !lyrics.synced() || lineViews.isEmpty()) return;
        int at = lyrics.lineAt(MusicPlayer.position());
        if (at == current && !jump) return;
        if (current >= 0 && current < lineViews.size()) lineViews.get(current).setTextColor(Theme.TEXT_FAINT);
        current = at;
        if (at >= 0) lineViews.get(at).setTextColor(Color.WHITE);
        // after you scroll by hand the page stays put for a few seconds
        if (System.currentTimeMillis() - touchedAt < 4000) return;
        final View target = at >= 0 ? lineViews.get(at) : lineViews.get(0);
        scroll.post(new Runnable() {
            @Override
            public void run() {
                int y = Math.max(0, target.getTop() - scroll.getHeight() / 3);
                scroll.smoothScrollTo(0, y);
            }
        });
    }
}
