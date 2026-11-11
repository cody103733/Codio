package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.KeyEvent;

/** Type a line of text with the remote (an addon link, an email, a password). A real keyboard types directly. */
final class TextEntryScreen extends Screen {
    interface Done {
        void done(String text);
    }

    private static final String[] ROWS = {"1234567890", "qwertyuiop", "asdfghjkl@", "zxcvbnm.-_", "/:?=&+!#~%"};
    private static final String[] SPECIAL = {"Caps", "Space", "Delete", ".com", "Done"};
    private static final int COLS = 10;
    private static final float KW = 104, KH = 76, KSX = 114, KSY = 86;
    private static final float KX = (TvView.W - KSX * COLS + 10) / 2, KY = 420;

    private final String title, hint;
    private final boolean secret;
    private final Done done;
    private final StringBuilder text = new StringBuilder();
    private int row = 1, col = 0;
    private boolean caps = false;
    private final RectF r = new RectF();

    TextEntryScreen(TvView tv, String title, String hint, String initial, boolean secret, Done done) {
        super(tv);
        this.title = title;
        this.hint = hint;
        this.secret = secret;
        this.done = done;
        if (initial != null) text.append(initial);
    }

    private int rowLength(int rw) {
        return rw < ROWS.length ? COLS : SPECIAL.length;
    }

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(0xFF0B0B0B);
        c.drawText(title, KX, 170, tv.tTitle);
        if (hint != null) tv.drawWrapped(c, hint, tv.tSmall, KX, 196, KSX * COLS, 2);
        // The text field.
        r.set(KX, 270, KX + KSX * COLS - 10, 360);
        tv.fill.setColor(0xFF1F1F1F);
        c.drawRoundRect(r, 10, 10, tv.fill);
        String shown = text.toString();
        if (secret) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < shown.length(); i++) b.append('•');
            shown = b.toString();
        }
        float maxW = KSX * COLS - 60;
        String visible = shown;
        while (visible.length() > 0 && tv.tLabel.measureText(visible) > maxW) visible = visible.substring(1);
        c.drawText(visible, KX + 24, 326, tv.tLabel);
        tv.fill.setColor(TvView.RED);
        float cx = KX + 26 + tv.tLabel.measureText(visible);
        c.drawRect(cx, 292, cx + 3, 338, tv.fill);

        for (int rw = 0; rw <= ROWS.length; rw++) {
            int n = rowLength(rw);
            for (int i = 0; i < n; i++) {
                float x, w;
                String label;
                if (rw < ROWS.length) {
                    x = KX + i * KSX;
                    w = KW;
                    char ch = ROWS[rw].charAt(i);
                    label = String.valueOf(caps ? Character.toUpperCase(ch) : ch);
                } else {
                    w = KSX * 2 - 10;
                    x = KX + i * KSX * 2;
                    label = SPECIAL[i];
                }
                float y = KY + rw * KSY;
                boolean f = rw == row && i == col;
                boolean on = rw == ROWS.length && i == 0 && caps;
                r.set(x, y, x + w, y + KH);
                tv.fill.setColor(f ? Color.WHITE : on ? TvView.RED : rw == ROWS.length && i == 4 ? 0xFF2E2E2E : 0xFF1F1F1F);
                c.drawRoundRect(r, 8, 8, tv.fill);
                tv.tLabel.setColor(f ? Color.BLACK : 0xFFE0E0E0);
                float tw = tv.tLabel.measureText(label);
                c.drawText(label, x + (w - tw) / 2, y + KH / 2 + 10, tv.tLabel);
            }
        }
        tv.tLabel.setColor(Color.WHITE);
        c.drawText("Back cancels", KX, KY + (ROWS.length + 1) * KSY + 40, tv.tSmall);
    }

    private void press() {
        if (row < ROWS.length) {
            char ch = ROWS[row].charAt(col);
            text.append(caps ? Character.toUpperCase(ch) : ch);
            return;
        }
        switch (col) {
            case 0:
                caps = !caps;
                break;
            case 1:
                text.append(' ');
                break;
            case 2:
                if (text.length() > 0) text.setLength(text.length() - 1);
                break;
            case 3:
                text.append(".com");
                break;
            default:
                finish();
                break;
        }
    }

    private void finish() {
        tv.pop();
        done.done(text.toString().trim());
    }

    @Override
    boolean key(int k, KeyEvent e) {
        if (k == TvView.OTHER && e != null) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_DEL) {
                if (text.length() > 0) text.setLength(text.length() - 1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                finish();
                return true;
            }
            int ch = e.getUnicodeChar();
            if (ch >= 32 && text.length() < 400) {
                text.append((char) ch);
                return true;
            }
            return false;
        }
        switch (k) {
            case TvView.UP:
                if (row > 0) {
                    if (row == ROWS.length) col = col * 2;
                    row--;
                }
                return true;
            case TvView.DOWN:
                if (row < ROWS.length) {
                    row++;
                    if (row == ROWS.length) col = Math.min(SPECIAL.length - 1, col / 2);
                }
                return true;
            case TvView.LEFT:
                if (col > 0) col--;
                return true;
            case TvView.RIGHT:
                if (col < rowLength(row) - 1) col++;
                return true;
            case TvView.OK:
                press();
                return true;
            case TvView.PLAY:
                finish();
                return true;
            default:
                return false;
        }
    }
}
