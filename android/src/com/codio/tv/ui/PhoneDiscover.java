package com.codio.tv.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MenuItem;
import android.widget.PopupMenu;
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
    private LinearLayout chips;
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
        col.addView(ui.largeTitle("movie".equals(type) ? "Movies" : "Shows", true));
        chips = new LinearLayout(ctx());
        chips.setPadding(dp(16), 0, dp(16), dp(12));
        col.addView(chips, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout area = new FrameLayout(ctx());
        grid = new PhoneGrid(ui);
        grid.onNearEnd = new Runnable() {
            @Override
            public void run() {
                more();
            }
        };
        area.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        empty = PhoneKit.text(ctx(), "", 17, Theme.TEXT_DIM, PhoneKit.REGULAR);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), dp(40), dp(24), 0);
        area.addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        col.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
        catalogs = ui.store.discoverCatalogs(type);
        reload();
        return col;
    }

    /** The iPhone's menu chip: an outlined capsule with the choice and a small chevron; tap for the list. */
    private TextView menuChip(String label, final String[] options, final int current, final Picked picked) {
        TextView t = PhoneKit.text(ctx(), label + "  \u2304", 15, Color.WHITE, PhoneKit.SEMIBOLD);
        t.setSingleLine(true);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable g = PhoneKit.round(0, dp(20));
        g.setStroke(dp(1), 0x80FFFFFF);
        t.setBackground(PhoneKit.pressable(g, dp(20)));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PopupMenu pm = new PopupMenu(new ContextThemeWrapper(ctx(), android.R.style.Theme_DeviceDefault), v);
                for (int i = 0; i < options.length; i++) pm.getMenu().add(0, i, i, options[i]).setCheckable(true).setChecked(i == current);
                pm.getMenu().setGroupCheckable(0, true, true);
                pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        picked.picked(item.getItemId());
                        return true;
                    }
                });
                pm.show();
            }
        });
        return t;
    }

    private interface Picked {
        void picked(int index);
    }

    private void reload() {
        chips.removeAllViews();
        if (catalogs.isEmpty()) {
            empty.setText("No addon has a catalog for this.");
            return;
        }
        final Store.Catalog c = catalogs.get(cat);
        if (c.genreRequired && genre < 0 && !c.genres.isEmpty()) genre = 0;
        if (catalogs.size() > 1) {
            String[] names = new String[catalogs.size()];
            for (int i = 0; i < names.length; i++) names[i] = catalogs.get(i).name;
            chips.addView(menuChip(c.name, names, cat, new Picked() {
                @Override
                public void picked(int index) {
                    cat = index;
                    genre = -1;
                    reload();
                }
            }));
        }
        if (!c.genres.isEmpty()) {
            final int off = c.genreRequired ? 0 : 1;
            String[] names = new String[c.genres.size() + off];
            if (off == 1) names[0] = "All genres";
            for (int i = 0; i < c.genres.size(); i++) names[i + off] = c.genres.get(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (chips.getChildCount() > 0) lp.leftMargin = dp(8);
            chips.addView(menuChip(genre >= 0 ? c.genres.get(genre) : "All genres", names, genre + off, new Picked() {
                @Override
                public void picked(int index) {
                    genre = index - off;
                    reload();
                }
            }), lp);
        }
        chips.setVisibility(chips.getChildCount() > 0 ? View.VISIBLE : View.GONE);
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
        empty.setText("");
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
