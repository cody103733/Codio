package com.codio.tv.ui;

import android.graphics.Color;
import android.graphics.Outline;
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

import java.util.List;

/**
 * For You on phones (the "For You" pill on Home, like the iPhone's ForYouView): a featured pick with why it's there,
 * then rows (Top picks, Because you watched …, Something different, More you might like). DESIGN.md "For You".
 */
final class PhoneForYou extends PhoneUi.Page {
    private LinearLayout list;
    private LinearLayout content;
    private List<ForYou.PickRow> rows;

    PhoneForYou(PhoneUi ui) {
        super(ui);
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.BLACK);
        col.addView(ui.largeTitle("For You", true));
        ScrollView scroll = new ScrollView(ctx());
        list = new LinearLayout(ctx());
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(30));
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = new LinearLayout(ctx());
        content.setOrientation(LinearLayout.VERTICAL);
        list.addView(content);

        rows = CastInfo.key() != null ? ForYou.cached(ui.host.prefs()) : null;
        if (CastInfo.key() == null) message(ForYou.State.NO_KEY);
        else if (rows != null && !rows.isEmpty()) show();
        else message(ForYou.State.LOADING);
        refresh(false);
        return col;
    }

    private void refresh(boolean force) {
        if (CastInfo.key() == null) return;
        if (!force && !ForYou.needsBuild(ui.host.prefs(), ui.store)) return;
        if (force) ui.toast("Finding new picks…");
        ForYou.build(ui.host.prefs(), ui.store, new ForYou.Done() {
            @Override
            public void done(ForYou.State state, List<ForYou.PickRow> got) {
                if (got != null && !got.isEmpty()) {
                    rows = got;
                    show();
                } else if (rows == null || rows.isEmpty()) {
                    message(state);
                }
            }
        });
    }

    private void message(ForYou.State s) {
        content.removeAllViews();
        LinearLayout box = new LinearLayout(ctx());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(60), dp(24), dp(24));
        if (s == ForYou.State.LOADING) {
            ProgressBar p = new ProgressBar(ctx());
            p.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Theme.RED));
            box.addView(p, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        String text = s == ForYou.State.NO_KEY ? "For You uses TMDB to find titles like the ones you watch. Add your free TMDB key in Settings to turn it on."
                : s == ForYou.State.NO_HISTORY ? "Watch a few movies or shows and your picks show up here."
                : s == ForYou.State.FAILED ? "Couldn't reach TMDB for your picks. Check the connection and try again."
                : "Finding titles that fit what you watch…";
        TextView t = PhoneKit.text(ctx(), text, 15, Theme.TEXT_DIM, PhoneKit.REGULAR);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = dp(16);
        box.addView(t, tl);
        if (s == ForYou.State.NO_KEY || s == ForYou.State.FAILED) {
            TextView b = PhoneKit.button(ctx(), s == ForYou.State.NO_KEY ? "Open Settings" : "Try again", true);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (s == ForYou.State.NO_KEY) ui.navigate(NavBar.SETTINGS);
                    else {
                        message(ForYou.State.LOADING);
                        refresh(true);
                    }
                }
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
            bl.topMargin = dp(20);
            box.addView(b, bl);
        }
        content.addView(box);
    }

    private void show() {
        content.removeAllViews();
        if (rows == null || rows.isEmpty() || rows.get(0).items.isEmpty()) return;
        content.addView(featured(rows.get(0).items.get(0)));
        for (ForYou.PickRow r : rows) content.addView(row(r));
        TextView again = PhoneKit.link(ctx(), "New picks", Theme.RED);
        again.setGravity(Gravity.CENTER);
        again.setPadding(dp(16), dp(26), dp(16), dp(8));
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh(true);
            }
        });
        content.addView(again, new LinearLayout.LayoutParams(-1, -2));
        TextView credit = PhoneKit.text(ctx(), CastInfo.CREDIT, 11, PhoneKit.GRAY, PhoneKit.REGULAR);
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(dp(24), dp(10), dp(24), 0);
        content.addView(credit);
    }

    /** The day's first pick: its wide picture, why it's here, Play and More info. */
    private View featured(final Store.Meta m) {
        LinearLayout col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(6), dp(16), 0);
        FrameLayout pic = new FrameLayout(ctx());
        final float r = dp(10);
        pic.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        pic.setClipToOutline(true);
        pic.setBackground(PhoneKit.round(Theme.CARD, r));
        ImageView img = new ImageView(ctx());
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int w = ctx().getResources().getDisplayMetrics().widthPixels - dp(32);
        PhoneKit.image(img, m.background != null ? m.background : m.poster, w);
        pic.addView(img, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(ctx());
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x00000000, 0xD9000000}));
        pic.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        TextView name = PhoneKit.text(ctx(), m.name, 22, Color.WHITE, PhoneKit.BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        nl.setMargins(dp(14), 0, dp(14), dp(12));
        pic.addView(name, nl);
        pic.setClickable(true);
        pic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.openDetail(m);
            }
        });
        col.addView(pic, new LinearLayout.LayoutParams(-1, Math.round(w * 9f / 16f)));
        if (m.reason != null && !m.reason.isEmpty()) {
            TextView why = PhoneKit.text(ctx(), m.reason, 14, Theme.RED, PhoneKit.SEMIBOLD);
            why.setPadding(0, dp(12), 0, 0);
            col.addView(why);
        }
        String line = m.metaLine();
        String g = m.genreLine();
        if (!g.isEmpty()) line = line.isEmpty() ? g : line + "  ·  " + g;
        if (!line.isEmpty()) {
            TextView meta = PhoneKit.text(ctx(), line, 13, Theme.TEXT_DIM, PhoneKit.REGULAR);
            meta.setPadding(0, dp(4), 0, 0);
            col.addView(meta);
        }
        if (m.description != null) {
            TextView d = PhoneKit.text(ctx(), m.description, 14, Theme.TEXT_DIM, PhoneKit.REGULAR);
            d.setMaxLines(3);
            d.setEllipsize(TextUtils.TruncateAt.END);
            d.setPadding(0, dp(8), 0, 0);
            col.addView(d);
        }
        LinearLayout btns = new LinearLayout(ctx());
        TextView play = PhoneKit.button(ctx(), "▶  Play", true);
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("movie".equals(m.type)) ui.play(m, null);
                else ui.openDetail(m);
            }
        });
        TextView info = PhoneKit.button(ctx(), "More info", false);
        info.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.openDetail(m);
            }
        });
        btns.addView(play, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, -2, 1);
        il.leftMargin = dp(10);
        btns.addView(info, il);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(14);
        col.addView(btns, bl);
        return col;
    }

    private View row(ForYou.PickRow r) {
        LinearLayout box = new LinearLayout(ctx());
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = PhoneKit.text(ctx(), r.title, 17, Color.WHITE, PhoneKit.BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(16), dp(26), dp(16), dp(8));
        box.addView(t);
        HorizontalScrollView hs = new HorizontalScrollView(ctx());
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout inner = new LinearLayout(ctx());
        inner.setPadding(dp(16), 0, dp(8), 0);
        hs.addView(inner);
        for (final Store.Meta m : r.items) {
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
            inner.addView(p, lp);
        }
        box.addView(hs, new LinearLayout.LayoutParams(-1, dp(168)));
        return box;
    }
}
