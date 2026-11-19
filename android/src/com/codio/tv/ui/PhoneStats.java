package com.codio.tv.ui;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.codio.tv.music.MusicPlayer;

import java.util.Collections;
import java.util.List;

/** Your stats on the phone, laid out like the iPhone's StatsView (DESIGN.md "Your stats"). */
final class PhoneStats extends PhoneUi.Page {
    private LinearLayout body;
    private int sel = 0;

    PhoneStats(PhoneUi ui) {
        super(ui);
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.BLACK);
        col.addView(ui.largeTitle("Your stats", true));
        PhoneKit.Segmented seg = new PhoneKit.Segmented(ctx(), new String[]{String.valueOf(Stats.thisYear()), "All time"}, sel);
        seg.setListener(new PhoneKit.Segmented.Listener() {
            @Override
            public void picked(int index) {
                sel = index;
                fill();
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.setMargins(dp(16), dp(4), dp(16), dp(14));
        col.addView(seg, sl);
        ScrollView sv = new ScrollView(ctx());
        body = new LinearLayout(ctx());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(80));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        fill();
        return col;
    }

    @Override
    void refresh() {
        fill();
    }

    @Override
    void downloadsChanged() {
    }

    private int year() {
        return sel == 0 ? Stats.thisYear() : 0;
    }

    private void fill() {
        if (body == null) return;
        Context c = ctx();
        body.removeAllViews();
        Stats.Watch w = Stats.watch(ui.store.libraryEntries(), Store.profileId(), year());
        Stats.Listen l = Stats.listen(year());

        LinearLayout r1 = new LinearLayout(c), r2 = new LinearLayout(c);
        tile(r1, Stats.hours(w.ms), "Hours watched", null, false);
        tile(r1, String.valueOf(w.shows.size() + w.movies.size()), "Titles watched",
                Stats.count(w.movies.size(), "movie") + " · " + Stats.count(w.shows.size(), "show"), true);
        tile(r2, String.valueOf(Math.round(l.seconds / 60)), "Minutes listened", null, false);
        tile(r2, String.valueOf(l.plays), "Songs played", null, true);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.setMargins(dp(16), 0, dp(16), dp(10));
        body.addView(r1, rl);
        body.addView(r2, new LinearLayout.LayoutParams(rl));

        titles("Top shows", w.shows);
        titles("Top movies", w.movies);

        body.addView(PhoneUi.sectionTitle(c, "Top songs"));
        if (l.songs.isEmpty()) body.addView(empty("Nothing played yet"));
        for (int i = 0; i < Math.min(5, l.songs.size()); i++) body.addView(songRow(l.songs.get(i)));

        body.addView(PhoneUi.sectionTitle(c, "Top artists"));
        if (l.artists.isEmpty()) {
            body.addView(empty("Nothing played yet"));
        } else {
            LinearLayout g = new LinearLayout(c);
            g.setOrientation(LinearLayout.VERTICAL);
            g.setBackground(PhoneKit.round(PhoneKit.GROUPED, dp(10)));
            for (int i = 0; i < Math.min(5, l.artists.size()); i++) {
                if (i > 0) {
                    View line = new View(c);
                    line.setBackgroundColor(PhoneKit.SEPARATOR);
                    LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
                    ll.leftMargin = dp(54);
                    g.addView(line, ll);
                }
                Stats.Artist a = l.artists.get(i);
                LinearLayout row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(10), dp(16), dp(10));
                TextView n = PhoneKit.text(c, String.valueOf(i + 1), 15, Theme.TEXT_DIM, PhoneKit.REGULAR);
                row.addView(n, new LinearLayout.LayoutParams(dp(22), -2));
                TextView name = PhoneKit.text(c, a.name, 17, Color.WHITE, PhoneKit.REGULAR);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(PhoneKit.text(c, Stats.count(a.count, "play"), 12, Theme.RED, PhoneKit.REGULAR));
                g.addView(row);
            }
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
            gl.setMargins(dp(16), 0, dp(16), 0);
            body.addView(g, gl);
        }
        TextView foot = PhoneKit.text(c, "Worked out on this phone from your library and the music you play here.", 13, PhoneKit.SECONDARY, PhoneKit.REGULAR);
        foot.setPadding(dp(16), dp(18), dp(16), 0);
        body.addView(foot);
    }

    private void tile(LinearLayout row, String value, String label, String detail, boolean second) {
        Context c = ctx();
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(14), dp(14), dp(14), dp(14));
        t.setBackground(PhoneKit.round(PhoneKit.GROUPED, dp(12)));
        TextView v = PhoneKit.text(c, value, 30, Color.WHITE, PhoneKit.BOLD);
        v.setSingleLine(true);
        t.addView(v);
        t.addView(PhoneKit.text(c, label, 15, Theme.TEXT_BODY, PhoneKit.SEMIBOLD));
        TextView d = PhoneKit.text(c, detail == null ? " " : detail, 12, Theme.TEXT_FAINT, PhoneKit.REGULAR);
        d.setSingleLine(true);
        d.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (second) lp.leftMargin = dp(10);
        row.addView(t, lp);
    }

    private TextView empty(String what) {
        TextView t = PhoneKit.text(ctx(), what + (sel == 0 ? " this year." : "."), 15, Theme.TEXT_FAINT, PhoneKit.REGULAR);
        t.setPadding(dp(16), 0, dp(16), dp(4));
        return t;
    }

    private void titles(String head, List<Stats.Title> list) {
        Context c = ctx();
        body.addView(PhoneUi.sectionTitle(c, head));
        if (list.isEmpty()) {
            body.addView(empty("Nothing watched yet"));
            return;
        }
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(c);
        row.setPadding(dp(16), 0, dp(6), 0);
        int w = dp(108);
        for (int i = 0; i < Math.min(5, list.size()); i++) {
            final Stats.Title t = list.get(i);
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            PhonePoster p = new PhonePoster(c, w);
            p.bind(t.entry.meta, ui.downloads);
            p.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.openDetail(t.entry.meta);
                }
            });
            cell.addView(p, new LinearLayout.LayoutParams(w, w * 3 / 2));
            TextView n = PhoneKit.text(c, t.entry.meta.name, 12, Color.WHITE, PhoneKit.REGULAR);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            n.setPadding(0, dp(4), 0, 0);
            cell.addView(n, new LinearLayout.LayoutParams(w, -2));
            cell.addView(PhoneKit.text(c, Stats.hours(t.ms) + " h", 11, Theme.RED, PhoneKit.REGULAR));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(w, -2);
            cl.rightMargin = dp(10);
            row.addView(cell, cl);
        }
        hs.addView(row);
        body.addView(hs);
    }

    private View songRow(final Stats.Song s) {
        Context c = ctx();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(3), dp(16), dp(3));
        row.setBackground(PhoneKit.pressable(null, 0));
        MusicKit.Cover cover = new MusicKit.Cover(c, 46, false, s.track.title);
        cover.set(s.track.artworkURL);
        row.addView(cover);
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, dp(4), 0);
        TextView t = PhoneKit.text(c, s.track.title, 17, Color.WHITE, PhoneKit.REGULAR);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(t);
        TextView a = PhoneKit.text(c, s.track.artist, 12, Theme.TEXT_DIM, PhoneKit.REGULAR);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(a);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(PhoneKit.text(c, Stats.count(s.count, "play"), 12, Theme.RED, PhoneKit.REGULAR));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MusicPlayer.playQueue(Collections.singletonList(s.track), 0, false);
            }
        });
        return row;
    }
}
