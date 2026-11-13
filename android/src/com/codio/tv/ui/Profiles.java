package com.codio.tv.ui;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Who's watching, shared by the TV and phone screens: the profiles made on the iPhone (each its own account, synced
 * through KeySync), the one in use, a round avatar for it, and switching with a profile's sign-in token.
 */
final class Profiles {
    /** The iPhone's profile colours, in the same order. */
    static final int[] COLORS = {0xFFE60A14, 0xFF2178F2, 0xFFFABA1A, 0xFF29AD61, 0xFF8C4DD9, 0xFFF27326};

    /** What switching needs from the screen it's started on. */
    interface Ui {
        void ctx(String name, Object args);

        void toast(String text);
    }

    private Profiles() {}

    /** The signed-in account's email, or null. */
    static String email(Store store) {
        JSONObject auth = store.profileJson != null ? store.profileJson.optJSONObject("auth") : null;
        JSONObject user = auth != null ? auth.optJSONObject("user") : null;
        String e = user != null ? user.optString("email", null) : null;
        return e != null && !e.isEmpty() ? e : null;
    }

    static JSONArray list(SharedPreferences prefs) {
        return KeySync.profiles(prefs);
    }

    static int currentIndex(JSONArray l, String email) {
        if (email == null) return -1;
        for (int i = 0; i < l.length(); i++) {
            JSONObject p = l.optJSONObject(i);
            if (p != null && email.equalsIgnoreCase(p.optString("email"))) return i;
        }
        return -1;
    }

    /** The profile being watched; when the account isn't in the list yet, one made from its email. Null when signed out. */
    static JSONObject current(Store store, SharedPreferences prefs) {
        String e = email(store);
        if (e == null) return null;
        JSONArray l = list(prefs);
        int i = currentIndex(l, e);
        if (i >= 0) return l.optJSONObject(i);
        try {
            String name = e.contains("@") ? e.substring(0, e.indexOf('@')) : e;
            return new JSONObject().put("email", e).put("name", name).put("color", 0);
        } catch (Exception x) {
            return null;
        }
    }

    static int color(JSONObject p) {
        if (p == null) return 0xFF333333;
        int c = p.optInt("color", 0);
        return COLORS[((c % COLORS.length) + COLORS.length) % COLORS.length];
    }

    static String initial(JSONObject p) {
        String n = p != null ? p.optString("name", "").trim() : "";
        return n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase();
    }

    static String name(JSONObject p) {
        return p != null ? p.optString("name", "?") : "?";
    }

    /** A round avatar: the profile's colour with its first letter (grey "?" when signed out). */
    static void drawAvatar(Canvas c, Paint fill, Paint text, float cx, float cy, float r, JSONObject p) {
        fill.setColor(color(p));
        c.drawCircle(cx, cy, r, fill);
        float was = text.getTextSize();
        int wasColor = text.getColor();
        Paint.Align wasAlign = text.getTextAlign();
        text.setTextSize(r * 0.95f);
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(initial(p), cx, cy - (fm.ascent + fm.descent) / 2, text);
        text.setTextSize(was);
        text.setColor(wasColor);
        text.setTextAlign(wasAlign);
    }

    /** Switches to a profile with its saved sign-in token; a toast says if it didn't work. */
    static void switchTo(final Store store, final JSONObject p, final Ui ui) {
        if (p == null) return;
        final String name = name(p);
        String now = email(store);
        if (now != null && now.equalsIgnoreCase(p.optString("email"))) return;
        String token = p.optString("token", "");
        if (token.isEmpty()) {
            ui.toast("Open " + name + " once on the iPhone, then try again.");
            return;
        }
        try {
            ui.ctx("Authenticate", new JSONObject().put("type", "LoginWithToken").put("token", token));
        } catch (Exception ignored) {
        }
        ui.toast("Switching to " + name + "…");
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                String e = email(store);
                if (e == null || !e.equalsIgnoreCase(p.optString("email"))) {
                    ui.toast("Couldn't switch to " + name + ". Open " + name + " once on the iPhone, then try again.");
                }
            }
        }, 15000);
    }
}
