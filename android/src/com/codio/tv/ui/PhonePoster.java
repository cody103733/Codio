package com.codio.tv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** A poster with the title under it while the picture loads, a watch-progress bar, and the download badge. */
final class PhonePoster extends FrameLayout {
    private final ImageView img;
    private final TextView name;
    private final Badge badge;
    private final TextView fresh;
    private final View bar, barFill;
    private final int widthPx;
    Store.Meta meta;

    PhonePoster(Context c, int widthPx) {
        super(c);
        this.widthPx = widthPx;
        final float r = PhoneKit.dp(c, 6);
        setBackground(PhoneKit.round(Theme.CARD, r));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        setClipToOutline(true);
        name = PhoneKit.text(c, "", 12, Theme.TEXT_BODY, false);
        int p = PhoneKit.dp(c, 8);
        name.setPadding(p, p, p, p);
        name.setMaxLines(4);
        addView(name, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER_VERTICAL));
        img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(img, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        bar = new View(c);
        bar.setBackgroundColor(Theme.SCRIM);
        LayoutParams bl = new LayoutParams(LayoutParams.MATCH_PARENT, PhoneKit.dp(c, 3), android.view.Gravity.BOTTOM);
        addView(bar, bl);
        barFill = new View(c);
        barFill.setBackgroundColor(Theme.RED);
        addView(barFill, new LayoutParams(0, PhoneKit.dp(c, 3), android.view.Gravity.BOTTOM));
        badge = new Badge(c);
        int bs = PhoneKit.dp(c, 24);
        LayoutParams lp = new LayoutParams(bs, bs, android.view.Gravity.TOP | android.view.Gravity.END);
        lp.setMargins(0, PhoneKit.dp(c, 5), PhoneKit.dp(c, 5), 0);
        addView(badge, lp);
        // "New episode", across the top (new episode alerts).
        fresh = PhoneKit.text(c, "New episode", 10, Color.WHITE, true);
        fresh.setSingleLine(true);
        fresh.setPadding(PhoneKit.dp(c, 7), PhoneKit.dp(c, 2), PhoneKit.dp(c, 7), PhoneKit.dp(c, 3));
        fresh.setBackground(PhoneKit.round(Theme.RED, PhoneKit.dp(c, 10)));
        fresh.setVisibility(GONE);
        LayoutParams fl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.START);
        fl.setMargins(PhoneKit.dp(c, 5), PhoneKit.dp(c, 5), 0, 0);
        addView(fresh, fl);
        setClickable(true);
        setForeground(PhoneKit.pressable(null, r));
    }

    void bind(Store.Meta m, PhoneDownloads d) {
        meta = m;
        name.setText(m.name);
        PhoneKit.image(img, m.poster, widthPx);
        NewEpisodes.Alert alert = widthPx >= PhoneKit.dp(getContext(), 90) ? NewEpisodes.of(m.id) : null;
        fresh.setVisibility(alert != null ? VISIBLE : GONE);
        setContentDescription(alert != null ? m.name + ", new episode" : m.name);
        updateBadge(d);
        final float progress = m.progress;
        if (progress > 0) {
            bar.setVisibility(VISIBLE);
            barFill.setVisibility(VISIBLE);
            post(new Runnable() {
                @Override
                public void run() {
                    LayoutParams lp = (LayoutParams) barFill.getLayoutParams();
                    lp.width = Math.max(PhoneKit.dp(getContext(), 4), Math.round(getWidth() * Math.min(1, progress)));
                    barFill.setLayoutParams(lp);
                }
            });
        } else {
            bar.setVisibility(GONE);
            barFill.setVisibility(GONE);
        }
    }

    void updateBadge(PhoneDownloads d) {
        String st = meta != null ? d.metaState(meta.id) : null;
        badge.set(st, st != null ? d.metaFraction(meta.id) : 0);
    }

    /** ✓ in a red circle when downloaded, a progress ring while downloading. */
    static final class Badge extends View {
        private String state;
        private float fraction;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tick = new Path();
        private final RectF r = new RectF();

        Badge(Context c) {
            super(c);
            setVisibility(GONE);
        }

        void set(String state, float fraction) {
            this.state = state;
            this.fraction = fraction;
            setVisibility(state == null ? GONE : VISIBLE);
            setContentDescription(PhoneDownloads.DONE.equals(state) ? "Downloaded" : "Downloading");
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (state == null) return;
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, rad = Math.min(w, h) / 2 - 1;
            p.setStyle(Paint.Style.FILL);
            if (PhoneDownloads.DONE.equals(state)) {
                p.setColor(Theme.RED);
                c.drawCircle(cx, cy, rad, p);
                p.setColor(Color.WHITE);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(w * 0.11f);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                tick.reset();
                tick.moveTo(w * 0.28f, h * 0.52f);
                tick.lineTo(w * 0.44f, h * 0.67f);
                tick.lineTo(w * 0.73f, h * 0.36f);
                c.drawPath(tick, p);
            } else {
                p.setColor(Theme.SCRIM_STRONG);
                c.drawCircle(cx, cy, rad, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(w * 0.12f);
                p.setColor(0x55FFFFFF);
                float in = w * 0.16f;
                r.set(in, in, w - in, h - in);
                c.drawArc(r, 0, 360, false, p);
                p.setColor(PhoneDownloads.FAILED.equals(state) ? Theme.RED_SOFT : Color.WHITE);
                c.drawArc(r, -90, Math.max(12, 360 * fraction), false, p);
            }
        }
    }
}
