package com.codio.tv.ui;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Movies or Shows: pick a catalog (and genre), browse the grid. */
final class PhoneDiscover extends PhoneUi.Page {
    private final String type;
    private List<Store.Catalog> catalogs = new ArrayList<>();
    private int cat = 0, genre = -1;
    private PhoneGrid grid;
    private LinearLayout catStrip, genreStrip;
    private TextView empty;
    private boolean loading, done;
    private int request = 0;

    PhoneDiscover(PhoneUi ui, String type) {
        super(ui);
        this.type = type;
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(ctx());
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = PhoneKit.text(ctx(), "‹", 30, Color.WHITE, true);
        back.setPadding(dp(14), 0, dp(10), dp(4));
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.back();
            }
        });
        head.addView(back);
        head.addView(PhoneKit.text(ctx(), "movie".equals(type) ? "Movies" : "Shows", 22, Color.WHITE, true));
        head.setPadding(0, dp(8), 0, dp(4));
        col.addView(head);
        catStrip = strip(col);
        genreStrip = strip(col);
        FrameLayout area = new FrameLayout(ctx());
        grid = new PhoneGrid(ui);
        grid.onNearEnd = new Runnable() {
            @Override
            public void run() {
                more();
            }
        };
        area.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        empty = PhoneKit.text(ctx(), "", 14, PhoneKit.TEXT2, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), dp(48), dp(24), 0);
        area.addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        col.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
        catalogs = ui.store.discoverCatalogs(type);
        for (int i = 0; i < catalogs.size(); i++) {
            final int idx = i;
            Store.Catalog c = catalogs.get(i);
            TextView chip = PhoneKit.chip(ctx(), c.name, i == 0);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cat = idx;
                    genre = -1;
                    reload();
                }
            });
            catStrip.addView(chip, chipLp());
        }
        reload();
        return col;
    }

    private LinearLayout strip(LinearLayout col) {
        HorizontalScrollView hs = new HorizontalScrollView(ctx());
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout s = new LinearLayout(ctx());
        s.setPadding(dp(12), dp(4), dp(12), dp(6));
        hs.addView(s);
        col.addView(hs);
        return s;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private void reload() {
        for (int i = 0; i < catStrip.getChildCount(); i++) PhoneKit.styleChip((TextView) catStrip.getChildAt(i), i == cat);
        genreStrip.removeAllViews();
        if (catalogs.isEmpty()) {
            empty.setText("No addon has a catalog for this.");
            return;
        }
        final Store.Catalog c = catalogs.get(cat);
        if (c.genreRequired && genre < 0 && !c.genres.isEmpty()) genre = 0;
        if (!c.genres.isEmpty()) {
            if (!c.genreRequired) {
                TextView all = PhoneKit.chip(ctx(), "All genres", genre < 0);
                all.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        genre = -1;
                        reload();
                    }
                });
                genreStrip.addView(all, chipLp());
            }
            for (int i = 0; i < c.genres.size(); i++) {
                final int g = i;
                TextView chip = PhoneKit.chip(ctx(), c.genres.get(i), i == genre);
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        genre = g;
                        reload();
                    }
                });
                genreStrip.addView(chip, chipLp());
            }
        }
        ((View) genreStrip.getParent()).setVisibility(c.genres.isEmpty() ? View.GONE : View.VISIBLE);
        grid.items.clear();
        grid.changed();
        done = false;
        loading = false;
        request++;
        more();
    }

    private void more() {
        if (loading || done || catalogs.isEmpty()) return;
        final Store.Catalog c = catalogs.get(cat);
        final int id = request;
        loading = true;
        empty.setText(grid.items.isEmpty() ? "Loading…" : "");
        ui.store.loadCatalog(c, genre >= 0 && genre < c.genres.size() ? c.genres.get(genre) : null, grid.items.size(), null, new Store.Callback<List<Store.Meta>>() {
            @Override
            public void done(List<Store.Meta> result, Exception error) {
                if (id != request) return;
                loading = false;
                if (result == null || result.isEmpty() || !c.skip) done = true;
                if (result != null) {
                    HashSet<String> seen = new HashSet<>();
                    for (Store.Meta m : grid.items) seen.add(m.id);
                    for (Store.Meta m : result) if (seen.add(m.id)) grid.items.add(m);
                }
                grid.changed();
                empty.setText(grid.items.isEmpty() ? (error != null ? "Couldn't load this catalog." : "Nothing here.") : "");
            }
        });
    }

    @Override
    void downloadsChanged() {
        if (grid != null) grid.changed();
    }
}
