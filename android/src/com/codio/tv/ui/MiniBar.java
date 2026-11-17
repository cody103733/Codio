package com.codio.tv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.codio.tv.music.MusicPlayer;
import com.codio.tv.music.Model.Track;
import com.codio.tv.music.Taste;

/** The music mini player: sits above the tab bar while a song is queued. Tap it for the full player and the queue. */
final class MiniBar extends LinearLayout {
    private final PhoneUi ui;
    private final MusicKit.Cover cover;
    private final TextView title, sub;
    private final MusicIcons heart, play, next;
    private final Line line;
    private String coverFor;

    private static final class Line extends View {
        float fraction;
        private final Paint p = new Paint();

        Line(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            p.setColor(0xFF48484A);
            c.drawRect(0, 0, getWidth(), getHeight(), p);
            p.setColor(Theme.RED);
            c.drawRect(0, 0, getWidth() * fraction, getHeight(), p);
        }
    }

    MiniBar(final PhoneUi ui) {
        super(ui.getContext());
        this.ui = ui;
        Context c = getContext();
        setOrientation(VERTICAL);
        setBackgroundColor(PhoneKit.GROUPED);
        line = new Line(c);
        addView(line, new LayoutParams(-1, PhoneKit.dp(c, 2)));
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(PhoneKit.dp(c, 10), 0, PhoneKit.dp(c, 4), 0);
        cover = new MusicKit.Cover(c, 40, false, "");
        LayoutParams cl = new LayoutParams(PhoneKit.dp(c, 40), PhoneKit.dp(c, 40));
        cl.rightMargin = PhoneKit.dp(c, 10);
        row.addView(cover, cl);
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(VERTICAL);
        title = PhoneKit.text(c, "", 15, Color.WHITE, PhoneKit.SEMIBOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        sub = MusicKit.sub(c, "");
        text.addView(title);
        text.addView(sub);
        row.addView(text, new LayoutParams(0, -2, 1));
        heart = icon(row, MusicIcons.HEART, 22, new Runnable() {
            @Override
            public void run() {
                Track t = MusicPlayer.current();
                if (t != null) Taste.toggleLikeTrack(t);
            }
        });
        play = icon(row, MusicIcons.PLAY, 26, new Runnable() {
            @Override
            public void run() {
                MusicPlayer.toggle();
            }
        });
        next = icon(row, MusicIcons.NEXT, 22, new Runnable() {
            @Override
            public void run() {
                MusicPlayer.next();
            }
        });
        row.setBackground(PhoneKit.pressable(null, 0));
        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.openNowPlaying();
            }
        });
        addView(row, new LayoutParams(-1, PhoneKit.dp(c, 54)));
        setVisibility(GONE);
        Taste.listeners.add(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        });
        MusicPlayer.listen(new MusicPlayer.Listener() {
            @Override
            public void changed() {
                refresh();
            }

            @Override
            public void tick() {
                double d = MusicPlayer.duration();
                line.fraction = d > 0 ? (float) Math.min(1, MusicPlayer.position() / d) : 0;
                line.invalidate();
            }
        });
    }

    private MusicIcons icon(LinearLayout row, int kind, int sizeDp, final Runnable action) {
        Context c = getContext();
        MusicIcons i = new MusicIcons(c, kind, Color.WHITE);
        int box = PhoneKit.dp(c, 46), pad = (box - PhoneKit.dp(c, sizeDp)) / 2;
        i.setPadding(pad, pad, pad, pad);
        i.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        row.addView(i, new LayoutParams(box, box));
        return i;
    }

    void refresh() {
        Track t = MusicPlayer.current();
        boolean show = MusicPlayer.hasQueue() && t != null && !(ui.top() instanceof PhoneNowPlaying);
        setVisibility(show ? VISIBLE : GONE);
        if (t == null) return;
        title.setText(t.title);
        String s = MusicPlayer.loading() ? "Finding a source…" : MusicPlayer.message() != null ? MusicPlayer.message() : t.artist;
        sub.setText(s);
        if (t.artworkURL == null ? coverFor != null : !t.artworkURL.equals(coverFor)) {
            coverFor = t.artworkURL;
            cover.set(coverFor);
        }
        boolean liked = Taste.isLikedTrack(t);
        heart.set(liked ? MusicIcons.HEART_FILL : MusicIcons.HEART, liked ? Theme.RED : Color.WHITE);
        play.set(MusicPlayer.playing() ? MusicIcons.PAUSE : MusicIcons.PLAY, Color.WHITE);
    }
}
