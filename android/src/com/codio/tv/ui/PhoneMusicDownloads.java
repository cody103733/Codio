package com.codio.tv.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.codio.tv.music.Model.Track;
import com.codio.tv.music.MusicDownloads;
import com.codio.tv.music.MusicPlayer;
import com.codio.tv.music.Taste;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Songs kept on the phone: play them offline, see the ones on their way, remove them. */
final class PhoneMusicDownloads extends PhoneUi.Page {
    private LinearLayout body;
    private String sig = "";
    private final Runnable changed = new Runnable() {
        @Override
        public void run() {
            draw(false);
        }
    };
    private final MusicPlayer.Listener playerListener = new MusicPlayer.Listener() {
        @Override
        public void changed() {
            if (body != null) MusicKit.refreshRows(body);
        }
    };
    private final Runnable tasteChanged = new Runnable() {
        @Override
        public void run() {
            if (body != null) MusicKit.refreshRows(body);
        }
    };

    PhoneMusicDownloads(PhoneUi ui) {
        super(ui);
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ui.largeTitle("Downloaded", true));
        ScrollView sv = new ScrollView(ctx());
        body = new LinearLayout(ctx());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(24));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        draw(true);
        return col;
    }

    @Override
    void onShow() {
        MusicDownloads.listeners.add(changed);
        MusicPlayer.listen(playerListener);
        Taste.listeners.add(tasteChanged);
        draw(false);
    }

    @Override
    void onHide() {
        MusicDownloads.listeners.remove(changed);
        MusicPlayer.unlisten(playerListener);
        Taste.listeners.remove(tasteChanged);
    }

    private static String size(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
        return Math.max(1, Math.round(bytes / (1024.0 * 1024))) + " MB";
    }

    private void draw(boolean force) {
        if (body == null) return;
        final List<MusicDownloads.Entry> all = MusicDownloads.all();
        final List<Track> done = MusicDownloads.doneTracks();
        StringBuilder sb = new StringBuilder();
        for (MusicDownloads.Entry e : all) sb.append(e.key).append(e.state).append(e.total > 0 ? e.bytes * 20 / e.total : e.bytes >> 20).append('|');
        if (!force && sb.toString().equals(sig)) return;
        sig = sb.toString();
        Context c = ctx();
        body.removeAllViews();
        if (all.isEmpty()) {
            body.addView(MusicKit.note(c, "No songs downloaded yet. Pick Download in a song's menu (•••), or on an album."));
            return;
        }
        String info = done.size() + (done.size() == 1 ? " song" : " songs") + " · " + size(MusicDownloads.usedBytes());
        TextView head = PhoneKit.text(c, info, 14, Theme.TEXT_DIM, PhoneKit.REGULAR);
        head.setPadding(dp(16), 0, dp(16), dp(6));
        body.addView(head);
        if (!done.isEmpty()) {
            LinearLayout actions = new LinearLayout(c);
            actions.setPadding(dp(16), dp(4), dp(16), dp(8));
            TextView play = PhoneKit.button(c, "▶  Play", true);
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    MusicPlayer.playQueue(done, 0, false);
                }
            });
            TextView shuffle = PhoneKit.button(c, "Shuffle", false);
            shuffle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    MusicPlayer.playQueue(done, new Random().nextInt(done.size()), true);
                }
            });
            actions.addView(play);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.leftMargin = dp(8);
            actions.addView(shuffle, sl);
            body.addView(actions);
        }
        List<MusicDownloads.Entry> pending = new ArrayList<>();
        for (MusicDownloads.Entry e : all) if (!e.done()) pending.add(e);
        if (!pending.isEmpty()) {
            body.addView(PhoneUi.sectionTitle(c, "On the way"));
            for (MusicDownloads.Entry e : pending) body.addView(pendingRow(c, e));
        }
        if (!done.isEmpty()) {
            body.addView(PhoneUi.sectionTitle(c, "Songs"));
            MusicKit.trackList(ui, body, done, false);
        }
        TextView clear = PhoneKit.link(c, "Remove all downloads", PhoneKit.DESTRUCTIVE);
        clear.setPadding(dp(16), dp(22), dp(16), dp(8));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(ui.getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Remove all downloads?")
                        .setMessage("The songs leave this phone. You can download them again.")
                        .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                List<Track> ts = new ArrayList<>();
                                for (MusicDownloads.Entry e : MusicDownloads.all()) ts.add(e.track);
                                MusicDownloads.remove(ts);
                            }
                        })
                        .setNegativeButton("Cancel", null).show();
            }
        });
        body.addView(clear);
        TextView note = MusicKit.note(c, "Downloads follow the Download over setting in Settings (Wi-Fi only unless you change it).");
        note.setTextSize(12);
        body.addView(note);
    }

    private View pendingRow(Context c, final MusicDownloads.Entry e) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(6), dp(4), dp(6));
        MusicKit.Cover cv = new MusicKit.Cover(c, 44, false, e.track.title);
        cv.set(e.track.artworkURL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(44), dp(44));
        cl.rightMargin = dp(12);
        row.addView(cv, cl);
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = PhoneKit.text(c, e.track.title, 16, Color.WHITE, PhoneKit.REGULAR);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(t);
        String state;
        boolean failed = MusicDownloads.FAILED.equals(e.state);
        if (failed) state = (e.error != null ? e.error : "Failed") + " · Tap to try again";
        else if (MusicDownloads.WAITING.equals(e.state)) state = "Waiting for Wi-Fi";
        else if (MusicDownloads.DOWNLOADING.equals(e.state)) state = e.total > 0 ? "Downloading " + (e.bytes * 100 / e.total) + "%" : e.bytes > 0 ? "Downloading " + size(e.bytes) : "Finding the song…";
        else state = "Queued";
        TextView s = MusicKit.sub(c, e.track.artist + " · " + state);
        if (failed) s.setTextColor(Theme.RED_SOFT);
        text.addView(s);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        MusicIcons x = new MusicIcons(c, MusicIcons.CLOSE, PhoneKit.GRAY);
        x.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MusicDownloads.remove(e.track);
            }
        });
        row.addView(x, new LinearLayout.LayoutParams(dp(40), dp(40)));
        if (failed) {
            row.setBackground(PhoneKit.pressable(null, 0));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    MusicDownloads.add(e.track);
                }
            });
        }
        return row;
    }
}
