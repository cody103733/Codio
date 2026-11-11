package com.codio.tv.ui;

import android.content.DialogInterface;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** My List (the Stremio library) plus everything downloaded; downloads carry a ✓ when they're done. */
final class PhoneLibrary extends PhoneUi.Page {
    private static final String[] TABS = {"All", "Movies", "Shows", "Downloads"};
    private static int tabSel = 0;
    private final TextView[] chips = new TextView[TABS.length];
    private PhoneGrid grid;
    private ScrollView dlScroll;
    private LinearLayout dlList;
    private TextView empty;

    PhoneLibrary(PhoneUi ui) {
        super(ui);
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = PhoneKit.text(ctx(), "Library", 24, Color.WHITE, true);
        title.setPadding(dp(16), dp(14), dp(16), dp(6));
        col.addView(title);
        HorizontalScrollView hs = new HorizontalScrollView(ctx());
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(ctx());
        strip.setPadding(dp(12), dp(4), dp(12), dp(10));
        for (int i = 0; i < TABS.length; i++) {
            final int t = i;
            chips[i] = PhoneKit.chip(ctx(), TABS[i], i == tabSel);
            chips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tabSel = t;
                    fill();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(4), 0, dp(4), 0);
            strip.addView(chips[i], lp);
        }
        hs.addView(strip);
        col.addView(hs);
        FrameLayout area = new FrameLayout(ctx());
        grid = new PhoneGrid(ui);
        area.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        dlScroll = new ScrollView(ctx());
        dlList = new LinearLayout(ctx());
        dlList.setOrientation(LinearLayout.VERTICAL);
        dlScroll.addView(dlList);
        area.addView(dlScroll, new FrameLayout.LayoutParams(-1, -1));
        empty = PhoneKit.text(ctx(), "", 14, PhoneKit.TEXT2, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), dp(48), dp(24), 0);
        area.addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        col.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
        fill();
        return col;
    }

    @Override
    void refresh() {
        if (grid == null) return;
        if (tabSel == 3) fillDownloads();
        else fill();
    }

    @Override
    void downloadsChanged() {
        if (grid == null) return;
        if (tabSel == 3) fillDownloads();
        else if (grid.items.size() != countFor()) fill();
        else grid.changed();
    }

    private int countFor() {
        String type = tabSel == 1 ? "movie" : tabSel == 2 ? "series" : null;
        Set<String> seen = new HashSet<>();
        for (Store.Meta m : ui.downloads.metas()) if (type == null || type.equals(m.type)) seen.add(m.id);
        for (Store.Meta m : ui.store.libraryItems(type)) seen.add(m.id);
        return seen.size();
    }

    private void fill() {
        for (int i = 0; i < chips.length; i++) PhoneKit.styleChip(chips[i], i == tabSel);
        boolean dl = tabSel == 3;
        grid.setVisibility(dl ? View.GONE : View.VISIBLE);
        dlScroll.setVisibility(dl ? View.VISIBLE : View.GONE);
        if (dl) {
            fillDownloads();
            return;
        }
        String type = tabSel == 1 ? "movie" : tabSel == 2 ? "series" : null;
        grid.items.clear();
        Set<String> seen = new HashSet<>();
        // Downloads first: they're what works without internet.
        for (Store.Meta m : ui.downloads.metas()) {
            if (type != null && !type.equals(m.type)) continue;
            if (seen.add(m.id)) grid.items.add(m);
        }
        for (Store.Meta m : ui.store.libraryItems(type)) if (seen.add(m.id)) grid.items.add(m);
        grid.changed();
        empty.setText(grid.items.isEmpty() ? (ui.store.profileLoaded || ui.downloads.any()
                ? "Nothing here yet. Tap “+ My List” or “Download” on a movie or show."
                : "Loading your library…") : "");
    }

    private void fillDownloads() {
        dlList.removeAllViews();
        List<Store.Meta> metas = ui.downloads.metas();
        long used = 0;
        for (PhoneDownloads.Entry e : ui.downloads.all()) used += PhoneDownloads.DONE.equals(e.state) ? e.bytes : e.done;
        TextView space = PhoneKit.text(ctx(), "Using " + (used > 0 ? PhoneKit.size(used) : "0 GB") + "  ·  " + PhoneKit.size(ui.downloads.freeBytes()) + " free"
                + (ui.downloads.wifiOnly() ? "  ·  Wi-Fi only" : ""), 12, PhoneKit.TEXT2, false);
        space.setPadding(dp(16), 0, dp(16), dp(8));
        dlList.addView(space);
        for (final Store.Meta m : metas) dlList.addView(row(m));
        empty.setText(metas.isEmpty() ? "No downloads yet. Open a movie or show and tap Download to watch it without internet." : "");
    }

    private View row(final Store.Meta m) {
        LinearLayout r = new LinearLayout(ctx());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(6), dp(4), dp(6));
        r.setBackground(PhoneKit.pressable(null, 0));
        r.setClickable(true);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.openDetail(m);
            }
        });
        PhonePoster p = new PhonePoster(ctx(), dp(64));
        p.bind(m, ui.downloads);
        p.setClickable(false);
        r.addView(p, new LinearLayout.LayoutParams(dp(64), dp(96)));
        LinearLayout t = new LinearLayout(ctx());
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(12), 0, dp(4), 0);
        TextView name = PhoneKit.text(ctx(), m.name, 15, Color.WHITE, true);
        name.setMaxLines(2);
        t.addView(name);
        List<PhoneDownloads.Entry> es = ui.downloads.entriesFor(m.id);
        String sub;
        if (!"movie".equals(m.type) && (es.size() > 1 || (es.size() == 1 && es.get(0).isEpisode()))) {
            long bytes = 0;
            for (PhoneDownloads.Entry e : es) bytes += e.bytes;
            int done = ui.downloads.doneCount(m.id);
            sub = done + " of " + es.size() + " episode" + (es.size() == 1 ? "" : "s") + " downloaded" + (bytes > 0 ? " · " + PhoneKit.size(bytes) : "");
            for (PhoneDownloads.Entry e : es) {
                if (!PhoneDownloads.DONE.equals(e.state)) {
                    sub += "\nS" + e.season + " E" + e.episode + ": " + PhoneDetail.describe(e);
                    break;
                }
            }
        } else {
            sub = es.isEmpty() ? "" : PhoneDetail.describe(es.get(0));
        }
        TextView s = PhoneKit.text(ctx(), sub, 12, PhoneKit.TEXT2, false);
        s.setMaxLines(3);
        t.addView(s);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        final PhoneDownloads.Entry playable = playable(es);
        if (playable != null) {
            TextView play = PhoneKit.text(ctx(), "▶", 18, android.graphics.Color.BLACK, true);
            play.setGravity(Gravity.CENTER);
            play.setContentDescription("Play");
            play.setBackground(PhoneKit.pressable(PhoneKit.round(android.graphics.Color.WHITE, dp(20)), dp(20)));
            play.setClickable(true);
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.play(m, playable.videoId);
                }
            });
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(40), dp(40));
            pl.setMargins(dp(4), 0, dp(4), 0);
            r.addView(play, pl);
        }
        TextView del = PhoneKit.text(ctx(), "✕", 18, PhoneKit.TEXT2, false);
        del.setGravity(Gravity.CENTER);
        del.setContentDescription("Delete download");
        del.setBackground(PhoneKit.pressable(null, dp(22)));
        del.setClickable(true);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.dialog().setTitle("Delete download?")
                        .setMessage("Remove " + m.name + " from this phone? You can download it again later.")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                ui.downloads.removeAll(m.id);
                                fillDownloads();
                            }
                        })
                        .setNegativeButton("Keep", null).show();
            }
        });
        r.addView(del, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return r;
    }

    /** What ▶ plays: the episode you were in the middle of, else the first finished one (or the movie). */
    static PhoneDownloads.Entry playable(List<PhoneDownloads.Entry> es) {
        PhoneDownloads.Entry first = null, started = null;
        for (PhoneDownloads.Entry e : es) {
            if (!PhoneDownloads.DONE.equals(e.state)) continue;
            if (first == null || e.season * 1000 + e.episode < first.season * 1000 + first.episode) first = e;
            if (e.position > 30 && (started == null || e.season * 1000 + e.episode < started.season * 1000 + started.episode)) started = e;
        }
        return started != null ? started : first;
    }

    @SuppressWarnings("unused")
    private static ImageView unused(ImageView v) {
        return v;
    }
}
