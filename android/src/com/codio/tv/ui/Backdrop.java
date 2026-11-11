package com.codio.tv.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;

/** The big picture behind a screen, cross-fading when it changes, with the dark fades that keep text readable. */
final class Backdrop {
    private final TvView tv;
    private String want, shown, previous;
    private float mix = 1;
    private long wantSince;
    private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF full = new RectF(0, 0, TvView.W, TvView.H);
    /** Decoded at 1280x720 and stretched: a quarter of the memory, and soft anyway under the fades. */
    static final int BW = 1280, BH = 720;

    Backdrop(TvView tv) {
        this.tv = tv;
    }

    /** Change picture after the viewer has rested on something for delayMs (avoids decoding every poster passed). */
    void show(String url, long delayMs) {
        if (url == null || url.equals(want)) return;
        want = url;
        wantSince = SystemClock.uptimeMillis() + delayMs;
        if (delayMs > 0) tv.postDelayed(new Runnable() {
            @Override
            public void run() {
                tv.kick();
            }
        }, delayMs + 10);
    }

    private boolean fresh = true;

    void draw(Canvas c, float dt, boolean shades) {
        if (want != null && !want.equals(shown) && SystemClock.uptimeMillis() >= wantSince) {
            Bitmap next = tv.images.backdrop(want, BW, BH);
            if (next != null || tv.images.isFailed(want)) {
                previous = shown;
                shown = next != null ? want : null;
                // A picture already in memory on the screen's first frame shows at once (no fade up from black).
                mix = fresh && next != null ? 1 : 0;
                if (next == null) want = null;
            }
        }
        fresh = false;
        // (Asked again if it was dropped from memory meanwhile.)
        Bitmap cur = shown != null ? tv.images.backdrop(shown, BW, BH) : null;
        Bitmap prev = previous != null && mix < 1 ? tv.images.cached(previous, BW, BH, false) : null;
        if (mix < 1) {
            mix = Math.min(1, mix + dt / 0.35f);
            tv.moving();
        }
        if (prev != null) {
            p.setAlpha(255);
            c.drawBitmap(prev, null, full, p);
        }
        if (cur != null) {
            p.setAlpha((int) (255 * (prev != null ? mix : mix)));
            c.drawBitmap(cur, null, full, p);
        }
        if (shades) {
            c.drawRect(full, tv.leftShade);
            c.drawRect(full, tv.bottomShade);
            c.drawRect(0, 0, TvView.W, 180, tv.topShade);
        }
    }
}
