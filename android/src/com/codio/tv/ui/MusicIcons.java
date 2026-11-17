package com.codio.tv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** The small drawn icons of Music (play, skip, heart...), so they look the same on every phone (no emoji fonts). */
final class MusicIcons extends View {
    static final int PLAY = 0, PAUSE = 1, NEXT = 2, PREV = 3, SHUFFLE = 4, REPEAT = 5, REPEAT_ONE = 6, HEART = 7, HEART_FILL = 8,
            MORE = 9, VOLUME = 10, MUTE = 11, CLOSE = 12, QUEUE = 13, NOTE = 14;

    private int kind;
    private int color;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    MusicIcons(Context c, int kind, int color) {
        super(c);
        this.kind = kind;
        this.color = color;
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    void set(int kind, int color) {
        if (this.kind == kind && this.color == color) return;
        this.kind = kind;
        this.color = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        draw(c, kind, color, getWidth(), getHeight(), getResources().getDisplayMetrics().density);
    }

    /** Draws the icon in a w x h box (used by the tab bar too). */
    void draw(Canvas c, int kind, int color, float w, float h, float d) {
        float s = Math.min(w, h);
        float cx = w / 2, cy = h / 2;
        float u = s / 24f;
        p.setColor(color);
        path.reset();
        p.setStyle(Paint.Style.FILL);
        switch (kind) {
            case PLAY:
                path.moveTo(cx - 6 * u, cy - 9 * u);
                path.lineTo(cx + 9 * u, cy);
                path.lineTo(cx - 6 * u, cy + 9 * u);
                path.close();
                c.drawPath(path, p);
                break;
            case PAUSE:
                r.set(cx - 7 * u, cy - 8 * u, cx - 2 * u, cy + 8 * u);
                c.drawRoundRect(r, u, u, p);
                r.set(cx + 2 * u, cy - 8 * u, cx + 7 * u, cy + 8 * u);
                c.drawRoundRect(r, u, u, p);
                break;
            case NEXT:
                path.moveTo(cx - 9 * u, cy - 8 * u);
                path.lineTo(cx + 3 * u, cy);
                path.lineTo(cx - 9 * u, cy + 8 * u);
                path.close();
                c.drawPath(path, p);
                r.set(cx + 5 * u, cy - 8 * u, cx + 9 * u, cy + 8 * u);
                c.drawRoundRect(r, u, u, p);
                break;
            case PREV:
                path.moveTo(cx + 9 * u, cy - 8 * u);
                path.lineTo(cx - 3 * u, cy);
                path.lineTo(cx + 9 * u, cy + 8 * u);
                path.close();
                c.drawPath(path, p);
                r.set(cx - 9 * u, cy - 8 * u, cx - 5 * u, cy + 8 * u);
                c.drawRoundRect(r, u, u, p);
                break;
            case SHUFFLE:
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f * u);
                path.moveTo(cx - 10 * u, cy - 6 * u);
                path.cubicTo(cx - 2 * u, cy - 6 * u, cx - 2 * u, cy + 6 * u, cx + 6 * u, cy + 6 * u);
                path.moveTo(cx - 10 * u, cy + 6 * u);
                path.cubicTo(cx - 2 * u, cy + 6 * u, cx - 2 * u, cy - 6 * u, cx + 6 * u, cy - 6 * u);
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(cx + 5 * u, cy - 10 * u);
                path.lineTo(cx + 10 * u, cy - 6 * u);
                path.lineTo(cx + 5 * u, cy - 2 * u);
                path.close();
                c.drawPath(path, p);
                path.reset();
                path.moveTo(cx + 5 * u, cy + 2 * u);
                path.lineTo(cx + 10 * u, cy + 6 * u);
                path.lineTo(cx + 5 * u, cy + 10 * u);
                path.close();
                c.drawPath(path, p);
                break;
            case REPEAT:
            case REPEAT_ONE:
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f * u);
                r.set(cx - 9 * u, cy - 6 * u, cx + 9 * u, cy + 6 * u);
                c.drawRoundRect(r, 5 * u, 5 * u, p);
                p.setStyle(Paint.Style.FILL);
                path.moveTo(cx + 3 * u, cy - 10 * u);
                path.lineTo(cx + 8 * u, cy - 6 * u);
                path.lineTo(cx + 3 * u, cy - 2 * u);
                path.close();
                path.moveTo(cx - 3 * u, cy + 2 * u);
                path.lineTo(cx - 8 * u, cy + 6 * u);
                path.lineTo(cx - 3 * u, cy + 10 * u);
                path.close();
                c.drawPath(path, p);
                if (kind == REPEAT_ONE) {
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setTextSize(8 * u);
                    p.setFakeBoldText(true);
                    c.drawText("1", cx, cy + 3 * u, p);
                    p.setFakeBoldText(false);
                }
                break;
            case HEART:
            case HEART_FILL:
                p.setStyle(kind == HEART ? Paint.Style.STROKE : Paint.Style.FILL);
                p.setStrokeWidth(2f * u);
                path.moveTo(cx, cy + 8.5f * u);
                path.cubicTo(cx - 14 * u, cy - 1 * u, cx - 8 * u, cy - 11 * u, cx, cy - 4 * u);
                path.cubicTo(cx + 8 * u, cy - 11 * u, cx + 14 * u, cy - 1 * u, cx, cy + 8.5f * u);
                path.close();
                c.drawPath(path, p);
                break;
            case MORE:
                c.drawCircle(cx - 7 * u, cy, 2 * u, p);
                c.drawCircle(cx, cy, 2 * u, p);
                c.drawCircle(cx + 7 * u, cy, 2 * u, p);
                break;
            case VOLUME:
            case MUTE:
                path.moveTo(cx - 10 * u, cy - 3 * u);
                path.lineTo(cx - 5 * u, cy - 3 * u);
                path.lineTo(cx, cy - 8 * u);
                path.lineTo(cx, cy + 8 * u);
                path.lineTo(cx - 5 * u, cy + 3 * u);
                path.lineTo(cx - 10 * u, cy + 3 * u);
                path.close();
                c.drawPath(path, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f * u);
                if (kind == VOLUME) {
                    r.set(cx - 4 * u, cy - 5 * u, cx + 6 * u, cy + 5 * u);
                    c.drawArc(r, -50, 100, false, p);
                    r.set(cx - 5 * u, cy - 9 * u, cx + 10 * u, cy + 9 * u);
                    c.drawArc(r, -50, 100, false, p);
                } else {
                    c.drawLine(cx + 4 * u, cy - 4 * u, cx + 10 * u, cy + 4 * u, p);
                    c.drawLine(cx + 10 * u, cy - 4 * u, cx + 4 * u, cy + 4 * u, p);
                }
                break;
            case CLOSE:
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.2f * u);
                c.drawLine(cx - 6 * u, cy - 6 * u, cx + 6 * u, cy + 6 * u, p);
                c.drawLine(cx + 6 * u, cy - 6 * u, cx - 6 * u, cy + 6 * u, p);
                break;
            case QUEUE:
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.2f * u);
                c.drawLine(cx - 9 * u, cy - 6 * u, cx + 9 * u, cy - 6 * u, p);
                c.drawLine(cx - 9 * u, cy, cx + 9 * u, cy, p);
                c.drawLine(cx - 9 * u, cy + 6 * u, cx + 2 * u, cy + 6 * u, p);
                break;
            default:
                // NOTE: a beamed pair of eighth notes
                p.setStyle(Paint.Style.FILL);
                r.set(cx - 8 * u, cy + 3 * u, cx - 2.5f * u, cy + 8.5f * u);
                c.drawOval(r, p);
                r.set(cx + 2.5f * u, cy + 1 * u, cx + 8 * u, cy + 6.5f * u);
                c.drawOval(r, p);
                r.set(cx - 3.6f * u, cy - 9 * u, cx - 2 * u, cy + 6 * u);
                c.drawRect(r, p);
                r.set(cx + 6.4f * u, cy - 11 * u, cx + 8 * u, cy + 4 * u);
                c.drawRect(r, p);
                path.moveTo(cx - 3.6f * u, cy - 9 * u);
                path.lineTo(cx + 8 * u, cy - 11 * u);
                path.lineTo(cx + 8 * u, cy - 7 * u);
                path.lineTo(cx - 3.6f * u, cy - 5 * u);
                path.close();
                c.drawPath(path, p);
                break;
        }
    }
}
