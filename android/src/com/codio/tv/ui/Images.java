package com.codio.tv.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Downloads and decodes pictures off the UI thread, already cut to the exact size they are drawn at (so drawing is a
 * plain 1:1 copy), with rounded corners baked in. The newest request goes first, so what's on screen wins over
 * what the viewer has already scrolled past.
 */
public final class Images {
    private static final String TAG = "CodioImages";

    public interface Listener {
        void imageReady();
    }

    private final LruCache<String, Bitmap> cache;
    private final Set<String> failed = new HashSet<>();
    private final Map<String, Job> pending = new HashMap<>();
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private int busy = 0;
    private static final int WORKERS = 3;
    private static final int MAX_QUEUE = 60;

    private static final class Job {
        String key, url;
        int w, h;
        float radius;
        boolean opaque;
        /** "cover" crop, or "fit" inside w x h keeping the aspect (logos). */
        boolean fit;
    }

    public Images(Listener listener) {
        this.listener = listener;
        long max = Runtime.getRuntime().maxMemory();
        int bytes = (int) Math.min(96L * 1024 * 1024, max / 4);
        Log.i(TAG, "image cache " + (bytes >> 20) + " MB (heap " + (max >> 20) + " MB)");
        cache = new LruCache<String, Bitmap>(bytes) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    private static String key(String url, int w, int h, boolean fit) {
        return w + "x" + h + (fit ? "f" : "") + "|" + url;
    }

    /** Poster/thumbnail cut to w x h with rounded corners, or null while it loads. */
    public Bitmap get(String url, int w, int h, float radius) {
        return request(url, w, h, radius, radius <= 0, false);
    }

    /** Full-screen backdrop (opaque, no corners). */
    public Bitmap backdrop(String url, int w, int h) {
        return request(url, w, h, 0, true, false);
    }

    /** Title logo, fitted inside w x h with transparency kept. */
    public Bitmap logo(String url, int w, int h) {
        return request(url, w, h, 0, false, true);
    }

    public boolean isFailed(String url) {
        return url != null && failed.contains(url);
    }

    /** Peek without loading. */
    public Bitmap cached(String url, int w, int h, boolean fit) {
        return url == null ? null : cache.get(key(url, w, h, fit));
    }

    private Bitmap request(String url, int w, int h, float radius, boolean opaque, boolean fit) {
        if (url == null || w <= 0 || h <= 0) return null;
        if (url.startsWith("//")) url = "https:" + url;
        String k = key(url, w, h, fit);
        Bitmap b = cache.get(k);
        if (b != null) return b;
        if (failed.contains(url)) return null;
        Job j = pending.get(k);
        if (j != null) {
            // Asked again: it's still wanted, move it to the front.
            if (queue.remove(j)) queue.addLast(j);
            return null;
        }
        j = new Job();
        j.key = k;
        j.url = url;
        j.w = w;
        j.h = h;
        j.radius = radius;
        j.opaque = opaque;
        j.fit = fit;
        pending.put(k, j);
        queue.addLast(j);
        // Forget the oldest requests: the viewer has moved on from them.
        while (queue.size() > MAX_QUEUE) pending.remove(queue.pollFirst().key);
        pump();
        return null;
    }

    private void pump() {
        while (busy < WORKERS && !queue.isEmpty()) {
            final Job j = queue.pollLast();
            busy++;
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    Bitmap b = null;
                    try {
                        b = load(j);
                    } catch (Throwable t) {
                        Log.w(TAG, "image failed: " + t);
                    }
                    final Bitmap result = b;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            busy--;
                            pending.remove(j.key);
                            if (result != null) {
                                cache.put(j.key, result);
                                listener.imageReady();
                            } else {
                                failed.add(j.url);
                            }
                            pump();
                        }
                    });
                }
            });
        }
    }

    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newFixedThreadPool(WORKERS, Net.named("codio-img", Thread.MIN_PRIORITY + 1));

    private static Bitmap load(Job j) throws Exception {
        byte[] data = Net.getBytes(j.url);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sw = o.outWidth, sh = o.outHeight;
        // Scale factor so the decoded picture still covers (or fits) the target.
        float need = j.fit ? Math.min((float) j.w / sw, (float) j.h / sh) : Math.max((float) j.w / sw, (float) j.h / sh);
        int sample = 1;
        // Largest power-of-two shrink that still leaves at least the size it's drawn at.
        while (need < 1 && sw / (sample * 2) >= sw * need && sh / (sample * 2) >= sh * need) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = j.opaque ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
        o.inDither = false;
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (src == null) return null;
        int w = j.w, h = j.h;
        if (j.fit) {
            float s = Math.min((float) j.w / src.getWidth(), (float) j.h / src.getHeight());
            w = Math.max(1, Math.round(src.getWidth() * s));
            h = Math.max(1, Math.round(src.getHeight() * s));
        }
        Bitmap out = Bitmap.createBitmap(w, h, j.opaque ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Matrix m = new Matrix();
        float s = j.fit ? (float) w / src.getWidth() : Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        m.setScale(s, s);
        m.postTranslate((w - src.getWidth() * s) / 2f, (h - src.getHeight() * s) / 2f);
        if (j.radius > 0) {
            BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shader.setLocalMatrix(m);
            p.setShader(shader);
            c.drawRoundRect(new RectF(0, 0, w, h), j.radius, j.radius, p);
        } else {
            c.drawBitmap(src, m, p);
        }
        src.recycle();
        out.prepareToDraw();
        return out;
    }

    /** Drop queued work (e.g. when leaving a screen). */
    public void clearQueue() {
        for (Iterator<Job> it = queue.iterator(); it.hasNext(); ) pending.remove(it.next().key);
        queue.clear();
    }

    public void trim() {
        cache.trimToSize(cache.maxSize() / 3);
    }
}
