package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Codio's look, shared by the TV and phone screens. The same names and values are used by the iPhone app
 * (ios/Codio/UI/Theme.swift) and by Codio for Windows (windows/src/routes/Codio/theme.less); DESIGN.md at the
 * top of the repository describes them.
 */
final class Theme {
    private Theme() {}

    // Brand
    static final int RED = 0xFFE50914;
    /** Red for text and small marks on a dark background (the brand red is too dark to read there). */
    static final int RED_SOFT = 0xFFFF5A5F;
    static final int LOGO_FROM = 0xFFFF4B2B, LOGO_TO = 0xFFC3001D;

    // Surfaces, darkest first
    static final int BLACK = 0xFF000000;
    /** A page without artwork behind it (Settings, Search, Library...). */
    static final int BG = 0xFF0B0B0B;
    /** A row, card or key on the page. */
    static final int CARD = 0xFF161616;
    /** Something raised on a card: a placeholder, a text field, a picture that hasn't loaded. */
    static final int RAISED = 0xFF262626;
    static final int LINE = 0xFF333333;

    // Text
    static final int TEXT = 0xFFFFFFFF;
    static final int TEXT_BODY = 0xFFD6D6D6;
    static final int TEXT_DIM = 0xFFB3B3B3;
    static final int TEXT_FAINT = 0xFF8C8C8C;

    /** The focused thing on TV is a white card: its text is near-black, its secondary text dark grey. */
    static final int FOCUS = 0xFFFFFFFF;
    static final int ON_FOCUS = 0xFF111111;
    static final int ON_FOCUS_DIM = 0xFF444444;

    // Over pictures and video
    static final int GLASS = 0x33FFFFFF;
    static final int GLASS_STRONG = 0x40FFFFFF;
    static final int SCRIM = 0xB3000000;
    static final int SCRIM_STRONG = 0xCC000000;

    /** Profile avatars, in the same order on every Codio app. */
    static final int[] PROFILE_COLORS = {0xFFE60A14, 0xFF2178F2, 0xFFFABA1A, 0xFF29AD61, 0xFF8C4DD9, 0xFFF27326};

    /**
     * The Codio wordmark, drawn in a 129x40 box: the red C around a play button IS the C of "codio" (one mark, never an
     * icon beside the word). Same drawing as BrandLogo on Windows and BrandWordmark on the iPhone.
     */
    static final class Logo {
        static final float ASPECT = 129f / 40f;
        private final Paint red = new Paint(Paint.ANTI_ALIAS_FLAG), white = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tri = new Path();
        private final RectF arc = new RectF(17.0f - 14.25f, 19.25f - 14.25f, 17.0f + 14.25f, 19.25f + 14.25f);

        Logo() {
            red.setShader(new LinearGradient(0, 0, 129, 40, LOGO_FROM, LOGO_TO, Shader.TileMode.CLAMP));
            red.setStrokeWidth(4.6f);
            red.setStrokeCap(Paint.Cap.ROUND);
            white.setColor(TEXT);
            white.setStrokeCap(Paint.Cap.ROUND);
            white.setStrokeJoin(Paint.Join.ROUND);
            tri.moveTo(12.5f, 12.8f);
            tri.lineTo(24f, 19.25f);
            tri.lineTo(12.5f, 25.7f);
            tri.close();
        }

        /** Draws the wordmark `height` tall with its top left corner at x, y. */
        void draw(Canvas c, float x, float y, float height) {
            c.save();
            c.translate(x, y);
            c.scale(height / 40f, height / 40f);
            red.setStyle(Paint.Style.STROKE);
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
            c.restore();
        }
    }
}
