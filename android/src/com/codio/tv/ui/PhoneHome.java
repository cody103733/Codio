package com.codio.tv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
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

/**
 * Home, drawn the way the iPhone app draws it (the iPhone is the standard for the phones): the wordmark with Shows /
 * Movies pills, a big rounded featured card, Continue Watching, a Top 10, then a row per addon catalog. The top of the
 * page is tinted by the featured poster, blurred.
 */
final class PhoneHome extends PhoneUi.Page {
    private ScrollView scroll;
    private LinearLayout list;
    private ImageView backdrop;
    private FrameLayout featuredBox;
    private Store.Meta featured;
    private LinearLayout cwBox;
    private final List<RowView> rows = new ArrayList<>();
    private RowView leader;
    private int builtFor = -1;
    private TextView empty;
    private ProgressBar spin;

    PhoneHome(PhoneUi ui) {
        super(ui);
    }

    @Override
    View build() {
        FrameLayout root = new FrameLayout(ctx());
        root.setBackgroundColor(Color.BLACK);
        // The featured poster, blurred, tints the top of Home.
        backdrop = new ImageView(ctx());
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0.75f);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            backdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(40), dp(40), android.graphics.Shader.TileMode.CLAMP));
        }
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, dp(620)));
        View fade = new View(ctx());
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xFF000000}));
        root.addView(fade, new FrameLayout.LayoutParams(-1, dp(620)));

        scroll = new ScrollView(ctx());
        scroll.setFillViewport(true);
        list = new LinearLayout(ctx());
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(30));
        scroll.addView(list);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        list.addView(header());

        featuredBox = new FrameLayout(ctx());
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, dp(420));
        fl.setMargins(dp(22), dp(26), dp(22), 0);
        list.addView(featuredBox, fl);

        cwBox = new LinearLayout(ctx());
        cwBox.setOrientation(LinearLayout.VERTICAL);
        list.addView(cwBox);

        spin = new ProgressBar(ctx());
        spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Theme.RED));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(40));
        sl.topMargin = dp(20);
        list.addView(spin, sl);

        empty = PhoneKit.text(ctx(), "", 15, Theme.TEXT_DIM, PhoneKit.REGULAR);
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
        return root;
    }

    /** The wordmark and the avatar, then the Shows and Movies pills. */
    private View header() {
        LinearLayout col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), 0);
        LinearLayout bar = new LinearLayout(ctx());
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(new PhoneKit.Logo(ctx()), new LinearLayout.LayoutParams(-2, dp(26)));
        bar.addView(new View(ctx()), new LinearLayout.LayoutParams(0, 1, 1));
        bar.addView(ui.profileButton(32));
        col.addView(bar, new LinearLayout.LayoutParams(-1, dp(34)));
        LinearLayout pills = new LinearLayout(ctx());
        pills.addView(pill("For You", NavBar.FOR_YOU));
        LinearLayout.LayoutParams pl0 = new LinearLayout.LayoutParams(-2, -2);
        pl0.leftMargin = dp(8);
        pills.addView(pill("Shows", NavBar.SERIES), pl0);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
        pl.leftMargin = dp(8);
        pills.addView(pill("Movies", NavBar.MOVIES), pl);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = dp(14);
        col.addView(pills, ll);
        return col;
    }

    private TextView pill(String s, final int nav) {
        TextView t = PhoneKit.text(ctx(), s, 15, Color.WHITE, PhoneKit.SEMIBOLD);
        t.setPadding(dp(16), dp(7), dp(16), dp(7));
        GradientDrawable g = PhoneKit.round(0, dp(20));
        g.setStroke(dp(1), 0x99FFFFFF);
        t.setBackground(PhoneKit.pressable(g, dp(20)));
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
    }

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
            leader = null;
            int at = list.indexOfChild(spin);
            for (Store.Catalog c : ui.store.homeCatalogs()) {
                RowView r = new RowView(c);
                rows.add(r);
                list.addView(r.box, at++);
            }
            setFeatured(null);
        }
        // Continue Watching (shows with a new episode out come first).
        List<Store.Meta> cw = ui.store.continueWatching();
        StringBuilder sig = new StringBuilder();
        for (Store.Meta m : cw) sig.append(m.id).append(':').append(Math.round(m.progress * 100)).append(NewEpisodes.of(m.id) != null ? "n" : "").append(',');
        if (!sig.toString().equals(cwSig)) {
            cwSig = sig.toString();
            cwBox.removeAllViews();
            if (!cw.isEmpty()) cwBox.addView(continueRow(cw));
        }
        for (RowView r : rows) r.updateBadges();
        updateLeader();
        list.post(new Runnable() {
            @Override
            public void run() {
                loadVisible();
            }
        });
    }

    /** The first catalog with titles gives the featured card and the Top 10; the others are plain rows. */
    private void updateLeader() {
        RowView lead = null;
        boolean waiting = false;
        for (RowView r : rows) {
            if (!r.items.isEmpty()) {
                lead = r;
                break;
            }
            if (!r.done) {
                waiting = true;
                break;
            }
        }
        if (lead != leader) {
            if (leader != null) leader.setTop10(false);
            leader = lead;
            if (lead != null) lead.setTop10(true);
        }
        setFeatured(lead != null ? lead.items.get(0) : null);
        boolean none = lead == null && !waiting && ui.store.continueWatching().isEmpty();
        spin.setVisibility(lead == null && (waiting || rows.isEmpty()) && ui.store.hasAddons() ? View.VISIBLE : View.GONE);
        empty.setText(none && !ui.store.hasAddons() ? "No addons yet. Add some under Settings → Addons." : "");
        empty.setVisibility(empty.getText().length() > 0 ? View.VISIBLE : View.GONE);
    }

    private void setFeatured(Store.Meta m) {
        if (m == featured && (m != null || featuredBox.getChildCount() == 0)) return;
        featured = m;
        featuredBox.removeAllViews();
        int w = ctx().getResources().getDisplayMetrics().widthPixels - dp(44);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) featuredBox.getLayoutParams();
        lp.height = m == null ? dp(420) : Math.round(w * 1.5f);
        featuredBox.setLayoutParams(lp);
        if (m == null) {
            backdrop.setImageDrawable(null);
            return;
        }
        // A tiny copy of the poster, stretched, is the blur on phones older than Android 12.
        PhoneKit.image(backdrop, m.poster != null ? m.poster : m.background, android.os.Build.VERSION.SDK_INT >= 31 ? dp(200) : 48);
        featuredBox.addView(new FeaturedCard(ctx(), m, w), new FrameLayout.LayoutParams(-1, -1));
    }

    /** "Popular" from a movie catalog reads "Popular Movies"; a catalog with its own name keeps it. */
    private static String rowTitle(Store.Catalog c) {
        String[] generic = {"Popular", "Top", "Featured", "New", "Trending"};
        for (String g : generic) if (c.name.startsWith(g)) return c.name + " " + c.typeLabel();
        return c.name;
    }

    private TextView rowHeading(String s) {
        TextView t = PhoneKit.text(ctx(), s, 17, Color.WHITE, PhoneKit.BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(16), dp(26), dp(16), dp(8));
        return t;
    }

    private HorizontalScrollView strip(LinearLayout inner, int spacingDp) {
        HorizontalScrollView hs = new HorizontalScrollView(ctx());
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        inner.setPadding(dp(16), 0, dp(16) - dp(spacingDp), 0);
        hs.addView(inner);
        return hs;
    }

    /** Load the rows on screen and just below. */
    private void loadVisible() {
        if (scroll == null) return;
        int bottom = scroll.getScrollY() + scroll.getHeight() + dp(600);
        boolean first = true;
        for (RowView r : rows) {
            if (r.requested) {
                first = false;
                continue;
            }
            // The first catalog always loads: it fills the featured card.
            if (first || r.box.getTop() < bottom) r.load();
            first = false;
        }
    }

    // ------------------------------------------------------------------ the featured card

    private final class FeaturedCard extends FrameLayout {
        private final Store.Meta m;
        private final TextView genres, libraryBtn;
        private final ImageView logo;

        FeaturedCard(Context c, final Store.Meta m, int w) {
            super(c);
            this.m = m;
            final float r = dp(10);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            setClipToOutline(true);
            setElevation(dp(12));
            setBackground(PhoneKit.round(Theme.CARD, r));
            GradientDrawable edge = PhoneKit.round(0, r);
            edge.setStroke(dp(1), 0x1FFFFFFF);
            setForeground(edge);
            ImageView poster = new ImageView(c);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            PhoneKit.image(poster, m.poster != null ? m.poster : m.background, w);
            poster.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.openDetail(m);
                }
            });
            addView(poster, new LayoutParams(-1, -1));
            View shade = new View(c);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x00000000, 0xD9000000}));
            addView(shade, new LayoutParams(-1, -1));

            LinearLayout info = new LinearLayout(c);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setGravity(Gravity.CENTER_HORIZONTAL);
            info.setPadding(dp(14), dp(14), dp(14), dp(14));
            logo = new ImageView(c);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            info.addView(logo, new LinearLayout.LayoutParams(dp(220), dp(70)));
            if (leader != null && leader.catalog != null) {
                LinearLayout rank = new LinearLayout(c);
                rank.setGravity(Gravity.CENTER_VERTICAL);
                rank.addView(PhoneKit.top10Badge(c, false));
                TextView rt = PhoneKit.text(c, "#1 in " + leader.catalog.typeLabel() + " Today", 15, Color.WHITE, PhoneKit.BOLD);
                rt.setPadding(dp(6), 0, 0, 0);
                rank.addView(rt);
                info.addView(rank, gap());
            }
            genres = PhoneKit.text(c, "", 13, Color.WHITE, PhoneKit.SEMIBOLD);
            genres.setSingleLine(true);
            info.addView(genres, gap());
            LinearLayout btns = new LinearLayout(c);
            TextView play = cardButton("▶  Play", true);
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if ("movie".equals(m.type)) ui.play(m, null);
                    else ui.openDetail(m);
                }
            });
            libraryBtn = cardButton("", false);
            libraryBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean in = ui.store.inLibrary(m.id);
                    if (in) ui.ctx("RemoveFromLibrary", m.id);
                    else ui.ctx("AddToLibrary", Store.preview(m));
                    ui.toast(in ? "Removed from Library" : "Added to Library");
                    libraryBtn.setText(!in ? "✓  Library" : "+  Library");
                }
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1);
            btns.addView(play, bl);
            LinearLayout.LayoutParams bl2 = new LinearLayout.LayoutParams(0, -2, 1);
            bl2.leftMargin = dp(10);
            btns.addView(libraryBtn, bl2);
            LinearLayout.LayoutParams btl = gap();
            btl.width = -1;
            info.addView(btns, btl);
            addView(info, new LayoutParams(-1, -2, Gravity.BOTTOM));
            show();
            if (!m.full) {
                ui.store.loadMeta(m, new Store.Callback<Store.Meta>() {
                    @Override
                    public void done(Store.Meta result, Exception error) {
                        show();
                    }
                });
            }
        }

        private LinearLayout.LayoutParams gap() {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(12);
            return lp;
        }

        private TextView cardButton(String label, boolean primary) {
            TextView t = PhoneKit.text(getContext(), label, 17, primary ? Color.BLACK : Color.WHITE, PhoneKit.SEMIBOLD);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(10), 0, dp(10));
            t.setBackground(PhoneKit.pressable(PhoneKit.round(primary ? Color.WHITE : 0xE6262626, dp(4)), dp(4)));
            t.setClickable(true);
            return t;
        }

        void show() {
            String l = HomeScreen.logoOf(m);
            logo.setVisibility(l != null ? View.VISIBLE : View.GONE);
            if (l != null) PhoneKit.image(logo, l, dp(220));
            StringBuilder g = new StringBuilder();
            for (int i = 0; i < Math.min(3, m.genres.size()); i++) g.append(i > 0 ? " • " : "").append(m.genres.get(i));
            genres.setText(g);
            genres.setVisibility(g.length() > 0 ? View.VISIBLE : View.GONE);
            libraryBtn.setText(ui.store.inLibrary(m.id) ? "✓  Library" : "+  Library");
        }
    }

    // ------------------------------------------------------------------ Continue Watching

    private View continueRow(List<Store.Meta> items) {
        LinearLayout box = new LinearLayout(ctx());
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(rowHeading("Continue Watching"));
        LinearLayout inner = new LinearLayout(ctx());
        for (Store.Meta m : items) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(110), -2);
            lp.rightMargin = dp(8);
            inner.addView(continueCard(m), lp);
        }
        box.addView(strip(inner, 8));
        return box;
    }

    private View continueCard(final Store.Meta m) {
        final boolean fresh = NewEpisodes.of(m.id) != null;
        LinearLayout card = new LinearLayout(ctx()) {
        };
        card.setOrientation(LinearLayout.VERTICAL);
        final float r = dp(4);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        card.setClipToOutline(true);
        FrameLayout top = new FrameLayout(ctx());
        top.setBackgroundColor(Theme.CARD);
        ImageView img = new ImageView(ctx());
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        PhoneKit.image(img, m.poster, dp(110) * 2);
        top.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (fresh) {
            TextView mark = PhoneKit.text(ctx(), "New episode", 10, Color.WHITE, PhoneKit.BOLD);
            mark.setPadding(dp(7), dp(3), dp(7), dp(3));
            mark.setBackground(PhoneKit.round(Theme.RED, dp(10)));
            FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            ml.setMargins(dp(5), dp(5), 0, 0);
            top.addView(mark, ml);
        } else {
            top.addView(new PlayCircle(ctx()), new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        }
        top.setForeground(PhoneKit.pressable(null, 0));
        top.setClickable(true);
        top.setContentDescription(fresh ? m.name + ", new episode" : "Resume " + m.name);
        top.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (fresh) ui.openDetail(m);
                else ui.play(m, m.lastVideoId);
            }
        });
        card.addView(top, new LinearLayout.LayoutParams(-1, dp(160)));
        FrameLayout track = new FrameLayout(ctx());
        track.setBackgroundColor(Theme.LINE);
        View fill = new View(ctx());
        fill.setBackgroundColor(Theme.RED);
        track.addView(fill, new FrameLayout.LayoutParams(Math.round(dp(110) * Math.max(0, Math.min(1, m.progress))), -1));
        card.addView(track, new LinearLayout.LayoutParams(-1, dp(3)));
        LinearLayout bar = new LinearLayout(ctx());
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Theme.CARD);
        bar.setPadding(dp(8), 0, dp(8), 0);
        InfoIcon info = new InfoIcon(ctx());
        info.setContentDescription("Details");
        info.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.openDetail(m);
            }
        });
        bar.addView(info, new LinearLayout.LayoutParams(dp(22), dp(30)));
        bar.addView(new View(ctx()), new LinearLayout.LayoutParams(0, 1, 1));
        bar.addView(PhoneKit.text(ctx(), episodeTag(m.lastVideoId), 11, Theme.TEXT_DIM, PhoneKit.REGULAR));
        card.addView(bar, new LinearLayout.LayoutParams(-1, dp(30)));
        return card;
    }

    private static String episodeTag(String vid) {
        if (vid == null) return "";
        String[] p = vid.split(":");
        if (p.length < 3) return "";
        try {
            return "S" + Integer.parseInt(p[p.length - 2]) + ":E" + Integer.parseInt(p[p.length - 1]);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** The thin play circle over a Continue Watching poster. */
    private static final class PlayCircle extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tri = new Path();

        PlayCircle(Context c) {
            super(c);
            p.setColor(Color.WHITE);
            p.setShadowLayer(PhoneKit.dp(c, 4), 0, 0, 0x99000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), cx = w / 2, cy = getHeight() / 2f, r = w * 0.44f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w * 0.035f);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.FILL);
            tri.reset();
            tri.moveTo(cx - r * 0.28f, cy - r * 0.4f);
            tri.lineTo(cx + r * 0.45f, cy);
            tri.lineTo(cx - r * 0.28f, cy + r * 0.4f);
            tri.close();
            c.drawPath(tri, p);
        }
    }

    /** ⓘ, drawn so it's the same on every phone. */
    private static final class InfoIcon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        InfoIcon(Context c) {
            super(c);
            p.setColor(Theme.TEXT_DIM);
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density, cx = 8 * d, cy = getHeight() / 2f, r = 7.5f * d;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.3f * d);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy - r * 0.45f, 1.1f * d, p);
            c.drawRect(cx - 0.7f * d, cy - r * 0.15f, cx + 0.7f * d, cy + r * 0.55f, p);
        }
    }

    // ------------------------------------------------------------------ catalog rows and the Top 10

    /** One catalog: a row of posters, or (the first one) the Top 10 with big numbers. */
    final class RowView {
        final Store.Catalog catalog;
        final List<Store.Meta> items = new ArrayList<>();
        final LinearLayout box, strip;
        final TextView heading;
        boolean requested, loading, done, top10;
        final ProgressBar rowSpin;

        RowView(Store.Catalog catalog) {
            this.catalog = catalog;
            box = new LinearLayout(ctx());
            box.setOrientation(LinearLayout.VERTICAL);
            heading = rowHeading(rowTitle(catalog));
            box.addView(heading);
            strip = new LinearLayout(ctx());
            final HorizontalScrollView hs = strip(strip, 8);
            box.addView(hs, new LinearLayout.LayoutParams(-1, dp(168)));
            rowSpin = new ProgressBar(ctx());
            rowSpin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Theme.RED));
            box.setVisibility(View.GONE);
            hs.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                @Override
                public void onScrollChange(View v, int x, int y, int ox, int oy) {
                    if (!top10 && !done && !loading && catalog.skip && x + hs.getWidth() > strip.getWidth() - dp(500)) load();
                }
            });
        }

        void setTop10(boolean on) {
            if (top10 == on) return;
            top10 = on;
            heading.setText(on ? "Top 10 " + catalog.typeLabel() + " Today" : rowTitle(catalog));
            strip.removeAllViews();
            ((LinearLayout.LayoutParams) ((View) strip.getParent()).getLayoutParams()).height = dp(on ? 150 : 168);
            fill();
            box.setVisibility(on && items.size() < 2 ? View.GONE : items.isEmpty() ? View.GONE : View.VISIBLE);
        }

        void load() {
            if (loading || done) return;
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
                    if (!top10) fill();
                    box.setVisibility(items.isEmpty() || (top10 && items.size() < 2) ? View.GONE : View.VISIBLE);
                    updateLeader();
                }
            });
        }

        void fill() {
            if (top10) {
                if (strip.getChildCount() > 0) return;
                for (int i = 1; i < Math.min(11, items.size()); i++) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(150));
                    lp.rightMargin = dp(4);
                    strip.addView(new TopTenItem(ctx(), i, items.get(i)), lp);
                }
                return;
            }
            strip.removeView(rowSpin);
            for (int i = strip.getChildCount(); i < items.size(); i++) {
                final Store.Meta m = items.get(i);
                PhonePoster p = new PhonePoster(ctx(), dp(112) * 2);
                p.bind(m, ui.downloads);
                p.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ui.openDetail(m);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(112), dp(168));
                lp.rightMargin = dp(8);
                strip.addView(p, lp);
            }
            if (!done) strip.addView(rowSpin, new LinearLayout.LayoutParams(dp(60), dp(168)));
        }

        void updateBadges() {
            for (int i = 0; i < strip.getChildCount(); i++) {
                View v = strip.getChildAt(i);
                if (v instanceof PhonePoster) ((PhonePoster) v).updateBadge(ui.downloads);
            }
        }
    }

    /** A Top 10 place: the big outlined number with the poster over its right edge. */
    private final class TopTenItem extends FrameLayout {
        private final String num;
        private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG), edgeP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int numW;

        TopTenItem(Context c, int rank, final Store.Meta m) {
            super(c);
            setWillNotDraw(false);
            setClipChildren(false);
            num = String.valueOf(rank);
            float size = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 120, c.getResources().getDisplayMetrics());
            fillP.setTypeface(PhoneKit.face(PhoneKit.BLACK));
            fillP.setTextSize(size);
            fillP.setColor(Color.BLACK);
            edgeP.set(fillP);
            edgeP.setStyle(Paint.Style.STROKE);
            edgeP.setStrokeWidth(dp(3));
            edgeP.setStrokeJoin(Paint.Join.ROUND);
            edgeP.setColor(0xFFBFBFBF);
            numW = Math.round(fillP.measureText(num)) + dp(2);
            PhonePoster p = new PhonePoster(c, dp(100) * 2);
            p.bind(m, ui.downloads);
            p.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.openDetail(m);
                }
            });
            LayoutParams pl = new LayoutParams(dp(100), dp(150));
            pl.leftMargin = numW - dp(14);
            addView(p, pl);
            View badge = PhoneKit.top10Badge(c, true);
            LayoutParams bl = new LayoutParams(dp(18), dp(22), Gravity.TOP | Gravity.END);
            addView(badge, bl);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(numW - dp(14) + dp(100), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(150), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas c) {
            float base = getHeight() - fillP.descent() + dp(22);
            c.drawText(num, dp(1), base, edgeP);
            c.drawText(num, dp(1), base, fillP);
        }
    }
}
