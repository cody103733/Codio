package com.codio.tv.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Library > Calendar on phones: the days ahead with the episodes and movies that come out, then the past week. */
final class PhoneCalendar extends PhoneUi.Page {
    private LinearLayout body;
    private final Runnable changed = new Runnable() {
        @Override
        public void run() {
            fill();
        }
    };

    PhoneCalendar(PhoneUi ui) {
        super(ui);
        tab = PhoneUi.TAB_LIBRARY;
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ui.largeTitle("Calendar", true));
        ScrollView sv = new ScrollView(ctx());
        body = new LinearLayout(ctx());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(24));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        fill();
        return col;
    }

    @Override
    void onShow() {
        ReleaseCalendar.listen(changed);
        ReleaseCalendar.refresh(ui.store, false);
        fill();
    }

    @Override
    void onHide() {
        ReleaseCalendar.unlisten(changed);
    }

    @Override
    void refresh() {
        ReleaseCalendar.refresh(ui.store, false);
    }

    private void fill() {
        if (body == null) return;
        body.removeAllViews();
        java.util.List<ReleaseCalendar.Section> sections = ReleaseCalendar.sections();
        if (sections.isEmpty()) {
            boolean busy = ReleaseCalendar.loading() || !ReleaseCalendar.loaded();
            TextView t = PhoneKit.text(ctx(), busy ? "Looking for release dates…"
                    : "Nothing coming up. New episodes and movies for what's in your Library show up here.", 17, Theme.TEXT_DIM, PhoneKit.REGULAR);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(24), dp(40), dp(24), 0);
            body.addView(t);
            return;
        }
        for (ReleaseCalendar.Section s : sections) {
            TextView h = PhoneKit.text(ctx(), s.heading, 17, Color.WHITE, PhoneKit.SEMIBOLD);
            h.setPadding(dp(16), dp(18), dp(16), dp(8));
            body.addView(h);
            for (ReleaseCalendar.Item i : s.items) body.addView(row(ctx(), ui, i, "past".equals(s.day)));
        }
    }

    static View row(Context c, final PhoneUi ui, final ReleaseCalendar.Item i, boolean showDay) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(PhoneKit.dp(c, 16), PhoneKit.dp(c, 6), PhoneKit.dp(c, 16), PhoneKit.dp(c, 6));
        r.setBackground(PhoneKit.pressable(null, 0));
        r.setClickable(true);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.openDetail(i.meta(ui.store));
            }
        });
        ImageView p = new ImageView(c);
        p.setScaleType(ImageView.ScaleType.CENTER_CROP);
        p.setBackground(PhoneKit.round(Theme.CARD, PhoneKit.dp(c, 6)));
        p.setClipToOutline(true);
        if (i.poster != null) PhoneKit.image(p, i.poster, PhoneKit.dp(c, 46));
        r.addView(p, new LinearLayout.LayoutParams(PhoneKit.dp(c, 46), PhoneKit.dp(c, 69)));
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(PhoneKit.dp(c, 12), 0, PhoneKit.dp(c, 8), 0);
        TextView name = PhoneKit.text(c, i.name, 15, Color.WHITE, PhoneKit.SEMIBOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(name);
        TextView line = PhoneKit.text(c, i.line(), 13, Theme.TEXT_DIM, PhoneKit.REGULAR);
        line.setMaxLines(2);
        line.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.setPadding(0, PhoneKit.dp(c, 3), 0, 0);
        t.addView(line);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (showDay) r.addView(PhoneKit.text(c, ReleaseCalendar.shortDay(i.day), 13, Theme.TEXT_FAINT, PhoneKit.REGULAR));
        return r;
    }

    /** The Calendar row at the top of the Library: what comes out next. */
    static final class Link extends LinearLayout {
        private final TextView sub;

        Link(Context c, final PhoneUi ui) {
            super(c);
            setGravity(Gravity.CENTER_VERTICAL);
            int p = PhoneKit.dp(c, 14);
            setPadding(p, PhoneKit.dp(c, 10), p, PhoneKit.dp(c, 10));
            float rad = PhoneKit.dp(c, 10);
            setBackground(PhoneKit.pressable(PhoneKit.round(PhoneKit.GROUPED, rad), rad));
            setClickable(true);
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.push(new PhoneCalendar(ui));
                }
            });
            ImageView icon = new ImageView(c);
            icon.setImageDrawable(new CalendarIcon(Theme.RED));
            LayoutParams il = new LayoutParams(PhoneKit.dp(c, 22), PhoneKit.dp(c, 22));
            il.setMargins(PhoneKit.dp(c, 3), 0, PhoneKit.dp(c, 15), 0);
            addView(icon, il);
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            col.addView(PhoneKit.text(c, "Calendar", 16, Color.WHITE, PhoneKit.SEMIBOLD));
            sub = PhoneKit.text(c, "", 13, Theme.TEXT_DIM, PhoneKit.REGULAR);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(sub);
            addView(col, new LayoutParams(0, -2, 1));
            addView(PhoneKit.text(c, "›", 22, PhoneKit.GRAY, PhoneKit.REGULAR));
            update();
        }

        void update() {
            sub.setText(ReleaseCalendar.nextLine());
        }
    }

    /** A small calendar outline (the iPhone's "calendar" symbol). */
    static final class CalendarIcon extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();

        CalendarIcon(int color) {
            p.setColor(color);
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float w = b.width(), h = b.height(), s = Math.min(w, h), x = b.left + (w - s) / 2, y = b.top + (h - s) / 2;
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.08f);
            r.set(x + s * 0.08f, y + s * 0.16f, x + s * 0.92f, y + s * 0.92f);
            c.drawRoundRect(r, s * 0.14f, s * 0.14f, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
            r.set(x + s * 0.08f, y + s * 0.16f, x + s * 0.92f, y + s * 0.36f);
            c.drawRoundRect(r, s * 0.1f, s * 0.1f, p);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            c.drawLine(x + s * 0.3f, y + s * 0.06f, x + s * 0.3f, y + s * 0.22f, p);
            c.drawLine(x + s * 0.7f, y + s * 0.06f, x + s * 0.7f, y + s * 0.22f, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
            float d = s * 0.06f;
            for (int row = 0; row < 2; row++)
                for (int col = 0; col < 3; col++)
                    c.drawCircle(x + s * (0.3f + col * 0.2f), y + s * (0.53f + row * 0.2f), d, p);
        }

        @Override
        public void setAlpha(int a) {
            p.setAlpha(a);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter f) {
            p.setColorFilter(f);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
