package com.codio.tv.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared look for the phone screens: colours, text, buttons, pictures. */
final class PhoneKit {
    private PhoneKit() {}

    private static Typeface regular, bold, semibold, black;

    // The iPhone app is the standard for the phones: its system colours in dark mode, used by the phone screens.
    /** Rows of a grouped list (Settings), search fields, the segmented control. */
    static final int GROUPED = 0xFF1C1C1E;
    /** Lines between the rows of a grouped list. */
    static final int SEPARATOR = 0xFF38383A;
    /** Section headings and footers of a grouped list. */
    static final int SECONDARY = 0xFF8D8D93;
    /** Tabs that aren't chosen, the magnifier and placeholder in the search field. */
    static final int GRAY = 0xFF8E8E93;
    /** Remove and Sign out. */
    static final int DESTRUCTIVE = 0xFFFF453A;

    /** Phones use the system font, the way the iPhone app does. */
    static void init(Context ctx) {
        if (regular != null) return;
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        bold = Typeface.create("sans-serif", Typeface.BOLD);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            semibold = Typeface.create(Typeface.DEFAULT, 600, false);
            black = Typeface.create(Typeface.DEFAULT, 900, false);
        } else {
            semibold = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            black = Typeface.create("sans-serif-black", Typeface.NORMAL);
        }
    }

    static final int REGULAR = 400, SEMIBOLD = 600, BOLD = 700, BLACK = 900;

    static Typeface face(int weight) {
        return weight >= 900 ? black : weight >= 700 ? bold : weight >= 500 ? semibold : regular;
    }

    static TextView text(Context c, String s, float sp, int color, int weight) {
        TextView t = text(c, s, sp, color, false);
        t.setTypeface(face(weight));
        return t;
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, String s, float sp, int color, boolean b) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(b ? bold : regular);
        t.setIncludeFontPadding(true);
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static Drawable pressable(Drawable bg, float radiusPx) {
        GradientDrawable mask = round(Color.WHITE, radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(Theme.GLASS_STRONG), bg, mask);
    }

    /** Pill button: primary = white with black text (Play), otherwise translucent grey. */
    static TextView button(Context c, String label, boolean primary) {
        TextView t = text(c, label, 15, primary ? Color.BLACK : Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        int ph = dp(c, 18), pv = dp(c, 11);
        t.setPadding(ph, pv, ph, pv);
        float r = dp(c, 6);
        t.setBackground(pressable(round(primary ? Color.WHITE : Theme.GLASS, r), r));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** Filter chip: red when chosen, grey otherwise (seasons on a show's page). */
    static TextView chip(Context c, String label, boolean selected) {
        TextView t = text(c, label, 14, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        int ph = dp(c, 14), pv = dp(c, 7);
        t.setPadding(ph, pv, ph, pv);
        styleChip(t, selected);
        t.setClickable(true);
        return t;
    }

    static void styleChip(TextView t, boolean selected) {
        float r = dp(t.getContext(), 18);
        t.setBackground(pressable(round(selected ? Theme.RED : Theme.RAISED, r), r));
        t.setTextColor(Color.WHITE);
    }

    static String size(long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / 1e9;
        if (gb >= 1) return String.format(java.util.Locale.US, gb >= 10 ? "%.0f GB" : "%.1f GB", gb);
        return String.format(java.util.Locale.US, "%.0f MB", bytes / 1e6);
    }

    static String qualityLabel(String q) {
        if (q == null) return "Unknown quality";
        return "2160p".equals(q) ? "4K" : q;
    }

    /**
     * The iPhone's segmented control: a dark rounded track, the chosen segment in red, thin lines between the others.
     * Both / Movies / Shows on Search, All / Movies / Shows / Downloads in the Library.
     */
    static final class Segmented extends View {
        interface Listener {
            void picked(int index);
        }

        private final String[] labels;
        private int sel;
        private Listener listener;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();

        Segmented(Context c, String[] labels, int sel) {
            super(c);
            this.labels = labels;
            this.sel = sel;
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13, c.getResources().getDisplayMetrics()));
            setClickable(true);
        }

        void setListener(Listener l) {
            listener = l;
        }

        void select(int i) {
            sel = i;
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(getContext(), 32));
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            if (e.getAction() == android.view.MotionEvent.ACTION_UP && getWidth() > 0) {
                int i = Math.max(0, Math.min(labels.length - 1, (int) (e.getX() * labels.length / getWidth())));
                if (i != sel) {
                    sel = i;
                    invalidate();
                    if (listener != null) listener.picked(i);
                }
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            float seg = w / labels.length;
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(GROUPED);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, 9 * d, 9 * d, p);
            p.setColor(0xFF48484A);
            for (int i = 1; i < labels.length; i++) {
                if (i == sel || i == sel + 1) continue;
                c.drawRect(seg * i - 0.5f * d, h * 0.28f, seg * i + 0.5f * d, h * 0.72f, p);
            }
            p.setColor(Theme.RED);
            r.set(seg * sel + 2 * d, 2 * d, seg * (sel + 1) - 2 * d, h - 2 * d);
            c.drawRoundRect(r, 7 * d, 7 * d, p);
            for (int i = 0; i < labels.length; i++) {
                p.setColor(Color.WHITE);
                p.setTypeface(face(i == sel ? SEMIBOLD : REGULAR));
                c.drawText(labels[i], seg * i + seg / 2, h / 2 - (p.descent() + p.ascent()) / 2, p);
            }
        }
    }

    /** The iPhone's small up-and-down chevrons beside a choice's value (a menu opens). */
    static final class Chevrons extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();

        Chevrons(Context c, int color) {
            super(c);
            p.setColor(color);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(dp(c, 1.6f));
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), 10), dp(getContext(), 16));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            path.reset();
            path.moveTo(w * 0.15f, h * 0.36f);
            path.lineTo(w * 0.5f, h * 0.14f);
            path.lineTo(w * 0.85f, h * 0.36f);
            path.moveTo(w * 0.15f, h * 0.64f);
            path.lineTo(w * 0.5f, h * 0.86f);
            path.lineTo(w * 0.85f, h * 0.64f);
            c.drawPath(path, p);
        }
    }

    /** Netflix's little red TOP 10 square, as on the iPhone. */
    static View top10Badge(Context c, boolean small) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(Theme.RED);
        TextView top = text(c, "TOP", small ? 6 : 8, Color.WHITE, BLACK);
        top.setIncludeFontPadding(false);
        TextView ten = text(c, "10", small ? 11 : 15, Color.WHITE, BLACK);
        ten.setIncludeFontPadding(false);
        ten.setPadding(0, -dp(c, 1), 0, 0);
        b.addView(top);
        b.addView(ten);
        b.setLayoutParams(new android.view.ViewGroup.LayoutParams(dp(c, small ? 18 : 26), dp(c, small ? 22 : 30)));
        return b;
    }

    /** A red text button, like the iPhone's buttons in Settings. */
    static TextView link(Context c, String label, int color) {
        TextView t = text(c, label, 17, color, REGULAR);
        t.setBackground(pressable(null, dp(c, 6)));
        t.setClickable(true);
        return t;
    }

    // ------------------------------------------------------------------ the Codio wordmark (the red C is the C of "codio")

    static final class Logo extends View {
        private final Theme.Logo logo = new Theme.Logo();

        Logo(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int height = MeasureSpec.getMode(h) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(h) : dp(getContext(), 28);
            setMeasuredDimension(Math.round(height * Theme.Logo.ASPECT), height);
        }

        @Override
        protected void onDraw(Canvas c) {
            logo.draw(c, 0, 0, getHeight());
        }
    }

    // ------------------------------------------------------------------ pictures

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, Net.named("codio-pimg", Thread.MIN_PRIORITY + 1));
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void trim() {
        CACHE.trimToSize(CACHE.maxSize() / 3);
    }

    /** Loads url (http(s) or a local file path) into the view, about widthPx wide. */
    static void image(final ImageView v, String url, final int widthPx) {
        if (url != null && url.startsWith("//")) url = "https:" + url;
        final String u = url;
        v.setTag(u);
        if (u == null) {
            v.setImageDrawable(null);
            return;
        }
        final String key = widthPx + "|" + u;
        Bitmap b = CACHE.get(key);
        if (b != null) {
            v.setImageBitmap(b);
            return;
        }
        v.setImageDrawable(null);
        // The same picture already on its way (a page drawn again while it loads): wait for that one instead of
        // downloading and decoding it twice.
        synchronized (WAITING) {
            List<WeakReference<ImageView>> w = WAITING.get(key);
            if (w != null) {
                w.add(new WeakReference<>(v));
                return;
            }
            w = new ArrayList<>();
            w.add(new WeakReference<>(v));
            WAITING.put(key, w);
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                if (!wanted(key, u)) return;
                Bitmap bm = null;
                try {
                    bm = decode(u, widthPx);
                } catch (Throwable ignored) {
                }
                final Bitmap res = bm;
                if (res != null) CACHE.put(key, res);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        List<WeakReference<ImageView>> w;
                        synchronized (WAITING) {
                            w = WAITING.remove(key);
                        }
                        if (res == null || w == null) return;
                        for (WeakReference<ImageView> r : w) {
                            ImageView iv = r.get();
                            if (iv != null && u.equals(iv.getTag())) iv.setImageBitmap(res);
                        }
                    }
                });
            }
        });
    }

    /** Views waiting for each picture being loaded (key = width|url). */
    private static final Map<String, List<WeakReference<ImageView>>> WAITING = new HashMap<>();

    /** Whether any view still waits for this picture; if none does, the job is dropped. */
    private static boolean wanted(String key, String u) {
        synchronized (WAITING) {
            List<WeakReference<ImageView>> w = WAITING.get(key);
            if (w == null) return false;
            for (WeakReference<ImageView> r : w) {
                ImageView iv = r.get();
                if (iv != null && u.equals(iv.getTag())) return true;
            }
            WAITING.remove(key);
            return false;
        }
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(65536);
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        in.close();
        return out.toByteArray();
    }

    private static Bitmap decode(String u, int widthPx) throws Exception {
        byte[] data = u.startsWith("http") ? Net.getBytes(u) : readAll(new FileInputStream(new File(u.startsWith("file://") ? u.substring(7) : u)));
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= widthPx) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }
}
