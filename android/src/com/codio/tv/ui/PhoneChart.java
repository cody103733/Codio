package com.codio.tv.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.codio.tv.music.Discovery;
import com.codio.tv.music.Model;
import com.codio.tv.music.Model.Track;
import com.codio.tv.music.MusicNet;
import com.codio.tv.music.MusicPlayer;
import com.codio.tv.music.Taste;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A whole chart as a numbered list (the Top 100 or a genre's top 100), opened from its row on the Music page. */
final class PhoneChart extends PhoneUi.Page {
    private final String id;
    private LinearLayout body;
    private List<Track> tracks = new ArrayList<>();
    private boolean loaded;
    private final Runnable tasteChanged = new Runnable() {
        @Override
        public void run() {
            if (body != null) MusicKit.refreshRows(body);
        }
    };
    private final MusicPlayer.Listener playerListener = new MusicPlayer.Listener() {
        @Override
        public void changed() {
            if (body != null) MusicKit.refreshRows(body);
        }
    };

    PhoneChart(PhoneUi ui, String chartId) {
        super(ui);
        id = chartId;
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ui.largeTitle(Discovery.chartTitle(id), true));
        ScrollView sv = new ScrollView(ctx());
        body = new LinearLayout(ctx());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(24));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        draw();
        MusicNet.async(new MusicNet.Job<List<Track>>() {
            @Override
            public List<Track> run() {
                return Discovery.chartSongs(id);
            }
        }, new MusicNet.Done<List<Track>>() {
            @Override
            public void done(List<Track> v, Exception e) {
                if (body == null) return;
                tracks = Taste.visibleTracks(Model.list(v));
                loaded = true;
                draw();
            }
        });
        return col;
    }

    @Override
    void onShow() {
        Taste.listeners.add(tasteChanged);
        MusicPlayer.listen(playerListener);
    }

    @Override
    void onHide() {
        Taste.listeners.remove(tasteChanged);
        MusicPlayer.unlisten(playerListener);
    }

    private void draw() {
        Context c = ctx();
        body.removeAllViews();
        if (!loaded) {
            LinearLayout box = new LinearLayout(c);
            box.setGravity(Gravity.CENTER);
            box.setPadding(0, dp(30), 0, 0);
            ProgressBar p = new ProgressBar(c);
            p.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Theme.RED));
            box.addView(p, new LinearLayout.LayoutParams(dp(40), dp(40)));
            body.addView(box);
            return;
        }
        if (tracks.isEmpty()) {
            body.addView(MusicKit.note(c, "Couldn't load this chart right now (no connection?)."));
            return;
        }
        if (Discovery.appleMusicUrl(tracks.get(0)) != null) body.addView(MusicKit.note(c, "Chart from Apple Music. Tap ⋯ on a song to listen there."));
        else if ("top".equals(id) && "lastfm".equals(Discovery.chartSource())) body.addView(MusicKit.note(c, "Chart data from Last.fm"));
        LinearLayout actions = new LinearLayout(c);
        actions.setPadding(dp(16), dp(6), dp(16), dp(8));
        TextView play = PhoneKit.button(c, "▶  Play", true);
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MusicPlayer.playQueue(tracks, 0, false);
            }
        });
        TextView shuffle = PhoneKit.button(c, "Shuffle", false);
        shuffle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MusicPlayer.playQueue(tracks, new Random().nextInt(tracks.size()), true);
            }
        });
        actions.addView(play);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.leftMargin = dp(8);
        actions.addView(shuffle, sl);
        body.addView(actions);
        MusicKit.trackList(ui, body, tracks, true);
    }
}
