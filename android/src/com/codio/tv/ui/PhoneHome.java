package com.codio.tv.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Home: a featured title, Continue Watching, then a row per addon catalog. */
final class PhoneHome extends PhoneUi.Page {
    private ScrollView scroll;
    private LinearLayout list;
    private FrameLayout hero;
    private ImageView heroImg, heroLogo;
    private TextView heroTitle, heroMeta;
    private Store.Meta heroMeta0;
    private LinearLayout cwBox;
    private final List<RowView> rows = new ArrayList<>();
    private int builtFor = -1;
    private TextView empty;

    PhoneHome(PhoneUi ui) {
        super(ui);
    }

    @Override
    View build() {
        scroll = new ScrollView(ctx());
        scroll.setFillViewport(true);
        list = new LinearLayout(ctx());
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(16));
        scroll.addView(list);

        // Hero with the top bar over it.
        hero = new FrameLayout(ctx());
        heroImg = new ImageView(ctx());
        heroImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(heroImg, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(ctx());
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xAA000000, 0x00000000, 0x00000000, 0xFF000000}));
        hero.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout info = new LinearLayout(ctx());
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        info.setPadding(dp(16), 0, dp(16), dp(4));
        heroLogo = new ImageView(ctx());
        heroLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        info.addView(heroLogo, new LinearLayout.LayoutParams(dp(240), dp(80)));
        heroTitle = PhoneKit.text(ctx(), "", 28, Color.WHITE, true);
        heroTitle.setGravity(Gravity.CENTER);
        heroTitle.setMaxLines(2);
        info.addView(heroTitle, new LinearLayout.LayoutParams(-2, -2));
        heroMeta = PhoneKit.text(ctx(), "", 13, 0xFFDDDDDD, false);
        heroMeta.setGravity(Gravity.CENTER);
        heroMeta.setMaxLines(1);
        heroMeta.setPadding(0, dp(6), 0, dp(10));
        info.addView(heroMeta);
        LinearLayout btns = new LinearLayout(ctx());
        btns.setGravity(Gravity.CENTER);
        TextView play = PhoneKit.button(ctx(), "▶  Play", true);
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (heroMeta0 == null) return;
                if ("movie".equals(heroMeta0.type)) ui.play(heroMeta0, null);
                else ui.openDetail(heroMeta0);
            }
        });
        TextView more = PhoneKit.button(ctx(), "ⓘ  Info", false);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (heroMeta0 != null) ui.openDetail(heroMeta0);
            }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1);
        bl.setMargins(dp(6), 0, dp(6), 0);
        btns.addView(play, bl);
        btns.addView(more, new LinearLayout.LayoutParams(bl));
        info.addView(btns, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        hero.addView(info, il);

        LinearLayout bar = new LinearLayout(ctx());
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(12), dp(8), dp(12));
        bar.addView(new PhoneKit.Logo(ctx()), new LinearLayout.LayoutParams(-2, dp(26)));
        View gap = new View(ctx());
        bar.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        bar.addView(topLink("Movies", NavBar.MOVIES));
        bar.addView(topLink("Series", NavBar.SERIES));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
        al.setMargins(dp(8), 0, dp(6), 0);
        bar.addView(ui.profileButton(), al);
        hero.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        int w = ctx().getResources().getDisplayMetrics().widthPixels;
        list.addView(hero, new LinearLayout.LayoutParams(-1, Math.round(Math.min(w, dp(600)) * 1.2f)));

        cwBox = new LinearLayout(ctx());
        cwBox.setOrientation(LinearLayout.VERTICAL);
        list.addView(cwBox);

        empty = PhoneKit.text(ctx(), "", 14, PhoneKit.TEXT2, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), dp(24), dp(24), dp(24));
        list.addView(empty);

        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int ox, int oy) {
                loadVisible();
            }
        });
        rebuild();
        return scroll;
    }

    private TextView topLink(String s, final int nav) {
        TextView t = PhoneKit.text(ctx(), s, 15, Color.WHITE, true);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setShadowLayer(dp(4), 0, 0, Color.BLACK);
        t.setBackground(PhoneKit.pressable(null, dp(16)));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.navigate(nav);
            }
        });
        return t;
    }

    void scrollTop() {
        if (scroll != null) scroll.smoothScrollTo(0, 0);
    }

    @Override
    void refresh() {
        rebuild();
    }

    @Override
    void downloadsChanged() {
        for (RowView r : rows) r.updateBadges();
        if (cwRow != null) cwRow.updateBadges();
    }

    private RowView cwRow;
    private String cwSig = null;

    @Override
    void onShow() {
        rebuild();
    }

    private void rebuild() {
        if (list == null) return;
        if (builtFor != ui.store.generation()) {
            builtFor = ui.store.generation();
            for (RowView r : rows) list.removeView(r.box);
            rows.clear();
            int at = list.indexOfChild(empty);
            for (Store.Catalog c : ui.store.homeCatalogs()) {
                String label = c.name + (c.typeLabel().isEmpty() || c.name.toLowerCase().contains(c.typeLabel().toLowerCase()) ? "" : " " + c.typeLabel());
                RowView r = new RowView(label, c);
                rows.add(r);
                list.addView(r.box, at++);
            }
            heroMeta0 = null;
        }
        // Continue Watching.
        List<Store.Meta> cw = ui.store.continueWatching();
        StringBuilder sig = new StringBuilder();
        for (Store.Meta m : cw) sig.append(m.id).append(':').append(Math.round(m.progress * 100)).append(',');
        if (!sig.toString().equals(cwSig)) {
            cwSig = sig.toString();
            cwBox.removeAllViews();
            cwRow = null;
            if (!cw.isEmpty()) {
                RowView r = new RowView("Continue Watching", null);
                r.items.addAll(cw);
                r.done = true;
                r.fill();
                cwBox.addView(r.box);
                cwRow = r;
            }
        } else if (cwRow != null) {
            cwRow.updateBadges();
        }
        for (RowView r : rows) r.updateBadges();
        boolean none = rows.isEmpty() && cw.isEmpty();
        empty.setText(none ? (ui.store.hasAddons() ? "Loading…" : "No addons yet. Add some under Settings → Addons.") : "");
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        pickHero();
        list.post(new Runnable() {
            @Override
            public void run() {
                loadVisible();
            }
        });
    }

    private void pickHero() {
        if (heroMeta0 != null) return;
        for (RowView r : rows) {
            for (Store.Meta m : r.items) {
                if (m.background != null || (m.id != null && m.id.startsWith("tt"))) {
                    setHero(m);
                    return;
                }
            }
            if (!r.items.isEmpty()) return;
        }
    }

    private void setHero(Store.Meta m) {
        heroMeta0 = m;
        int w = ctx().getResources().getDisplayMetrics().widthPixels;
        // Portrait phones: the poster fills the banner better than the wide background.
        PhoneKit.image(heroImg, m.poster != null ? m.poster : HomeScreen.backgroundOf(m), w);
        String logo = HomeScreen.logoOf(m);
        heroTitle.setText(m.name);
        heroTitle.setVisibility(View.GONE);
        heroLogo.setVisibility(View.GONE);
        if (logo != null) {
            heroLogo.setVisibility(View.VISIBLE);
            PhoneKit.image(heroLogo, logo, dp(240));
        } else {
            heroTitle.setVisibility(View.VISIBLE);
        }
        String g = m.genreLine();
        heroMeta.setText(!g.isEmpty() ? g : m.metaLine());
    }

    /** Load the rows on screen and just below. */
    private void loadVisible() {
        if (scroll == null) return;
        int bottom = scroll.getScrollY() + scroll.getHeight() + dp(600);
        for (RowView r : rows) {
            if (r.requested) continue;
            if (r.box.getTop() < bottom) r.load();
        }
    }

    /** One horizontal row of posters. */
    final class RowView {
        final String title;
        final Store.Catalog catalog;
        final List<Store.Meta> items = new ArrayList<>();
        final LinearLayout box, strip;
        final HorizontalScrollView hs;
        boolean requested, loading, done;
        final ProgressBar spin;

        RowView(String title, Store.Catalog catalog) {
            this.title = title;
            this.catalog = catalog;
            box = new LinearLayout(ctx());
            box.setOrientation(LinearLayout.VERTICAL);
            box.addView(PhoneUi.sectionTitle(ctx(), title));
            hs = new HorizontalScrollView(ctx());
            hs.setHorizontalScrollBarEnabled(false);
            strip = new LinearLayout(ctx());
            strip.setPadding(dp(10), 0, dp(10), 0);
            hs.addView(strip);
            box.addView(hs, new LinearLayout.LayoutParams(-1, dp(165)));
            spin = new ProgressBar(ctx());
            strip.addView(spin, new LinearLayout.LayoutParams(dp(110), dp(40)));
            spin.setVisibility(catalog != null ? View.VISIBLE : View.GONE);
            hs.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                @Override
                public void onScrollChange(View v, int x, int y, int ox, int oy) {
                    if (!done && !loading && catalog != null && catalog.skip && x + hs.getWidth() > strip.getWidth() - dp(500)) load();
                }
            });
        }

        void load() {
            if (catalog == null || loading || done) return;
            requested = true;
            loading = true;
            final int skip = items.size();
            ui.store.loadCatalog(catalog, null, skip, null, new Store.Callback<List<Store.Meta>>() {
                @Override
                public void done(List<Store.Meta> result, Exception error) {
                    loading = false;
                    if (result == null || result.isEmpty() || !catalog.skip) done = true;
                    if (result != null) {
                        HashSet<String> seen = new HashSet<>();
                        for (Store.Meta m : items) seen.add(m.id);
                        for (Store.Meta m : result) if (seen.add(m.id)) items.add(m);
                    }
                    if (items.isEmpty()) {
                        box.setVisibility(View.GONE);
                        return;
                    }
                    fill();
                    pickHero();
                }
            });
        }

        void fill() {
            strip.removeView(spin);
            for (int i = strip.getChildCount(); i < items.size(); i++) {
                final Store.Meta m = items.get(i);
                PhonePoster p = new PhonePoster(ctx(), dp(110) * 2);
                p.bind(m, ui.downloads);
                p.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ui.openDetail(m);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(110), dp(165));
                lp.setMargins(dp(4), 0, dp(4), 0);
                strip.addView(p, lp);
            }
            if (!done && catalog != null) strip.addView(spin);
            spin.setVisibility(!done && catalog != null ? View.VISIBLE : View.GONE);
        }

        void updateBadges() {
            for (int i = 0; i < strip.getChildCount(); i++) {
                View v = strip.getChildAt(i);
                if (v instanceof PhonePoster) ((PhonePoster) v).updateBadge(ui.downloads);
            }
        }
    }

    @SuppressWarnings("unused")
    private static ViewGroup.LayoutParams wrap() {
        return new ViewGroup.LayoutParams(-2, -2);
    }
}
