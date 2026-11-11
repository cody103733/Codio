package com.codio.tv.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
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
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared look for the phone screens: colours, text, buttons, pictures. */
final class PhoneKit {
    static final int RED = 0xFFE50914;
    static final int BG = 0xFF000000;
    static final int CARD = 0xFF1A1A1A;
    static final int TEXT2 = 0xFFB3B3B3;

    private PhoneKit() {}

    private static Typeface regular, bold;

    static void init(Context ctx) {
        if (regular != null) return;
        try {
            regular = Typeface.createFromAsset(ctx.getAssets(), "web/fonts/PlusJakartaSans.ttf");
        } catch (Throwable t) {
            regular = Typeface.SANS_SERIF;
        }
        bold = Typeface.create(regular, Typeface.BOLD);
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
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), bg, mask);
    }

    /** Pill button: primary = white with black text (Play), otherwise translucent grey. */
    static TextView button(Context c, String label, boolean primary) {
        TextView t = text(c, label, 15, primary ? Color.BLACK : Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        int ph = dp(c, 18), pv = dp(c, 11);
        t.setPadding(ph, pv, ph, pv);
        float r = dp(c, 6);
        t.setBackground(pressable(round(primary ? Color.WHITE : 0x33FFFFFF, r), r));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** Filter chip. */
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
        GradientDrawable g = round(selected ? Color.WHITE : 0x00000000, r);
        if (!selected) g.setStroke(dp(t.getContext(), 1), 0x80FFFFFF);
        t.setBackground(pressable(g, r));
        t.setTextColor(selected ? Color.BLACK : Color.WHITE);
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

    // ------------------------------------------------------------------ the Codio wordmark (the red C is the C of "codio")

    static final class Logo extends View {
        private final Paint red = new Paint(Paint.ANTI_ALIAS_FLAG), white = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tri = new Path();
        private final RectF arc = new RectF(17.0f - 14.25f, 19.25f - 14.25f, 17.0f + 14.25f, 19.25f + 14.25f);

        Logo(Context c) {
            super(c);
            red.setShader(new LinearGradient(0, 0, 129, 40, 0xFFFF4B2B, 0xFFC3001D, Shader.TileMode.CLAMP));
            red.setStyle(Paint.Style.STROKE);
            red.setStrokeWidth(4.6f);
            red.setStrokeCap(Paint.Cap.ROUND);
            white.setColor(Color.WHITE);
            white.setStyle(Paint.Style.STROKE);
            white.setStrokeWidth(4.6f);
            white.setStrokeCap(Paint.Cap.ROUND);
            white.setStrokeJoin(Paint.Join.ROUND);
            tri.moveTo(12.5f, 12.8f);
            tri.lineTo(24f, 19.25f);
            tri.lineTo(12.5f, 25.7f);
            tri.close();
        }

        @Override
        protected void onMeasure(int w, int h) {
            int height = MeasureSpec.getMode(h) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(h) : dp(getContext(), 28);
            setMeasuredDimension(Math.round(height * 129f / 40f), height);
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = getHeight() / 40f;
            c.save();
            c.scale(k, k);
            c.drawArc(arc, 46f, 268f, false, red);
            white.setStyle(Paint.Style.FILL_AND_STROKE);
            white.setStrokeWidth(2f);
            c.drawPath(tri, white);
            white.setStyle(Paint.Style.STROKE);
            white.setStrokeWidth(4.6f);
            c.drawCircle(45, 24, 9.5f, white);
            c.drawCircle(70, 24, 9.5f, white);
            c.drawLine(79.5f, 5, 79.5f, 33.5f, white);
            c.drawLine(93, 16, 93, 33.5f, white);
            c.drawCircle(114, 24, 9.5f, white);
            red.setStyle(Paint.Style.FILL);
            c.drawCircle(93, 7.5f, 3, red);
            red.setStyle(Paint.Style.STROKE);
            c.restore();
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
        final WeakReference<ImageView> ref = new WeakReference<>(v);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                ImageView iv0 = ref.get();
                if (iv0 == null || !u.equals(iv0.getTag())) return;
                Bitmap bm = null;
                try {
                    bm = decode(u, widthPx);
                } catch (Throwable ignored) {
                }
                final Bitmap res = bm;
                if (res == null) return;
                CACHE.put(key, res);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        ImageView iv = ref.get();
                        if (iv != null && u.equals(iv.getTag())) iv.setImageBitmap(res);
                    }
                });
            }
        });
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
