package com.codio.tv.ui;

import android.graphics.Canvas;
import android.view.KeyEvent;

abstract class Screen {
    final TvView tv;

    Screen(TvView tv) {
        this.tv = tv;
    }

    abstract void draw(Canvas c, float dt);

    /** A mapped key (TvView.UP, OK, ...); true when used. */
    abstract boolean key(int k, KeyEvent e);

    /** Back: true when the screen used it (e.g. moved focus up), otherwise the screen closes. */
    boolean back() {
        return false;
    }

    /** True when OK should act on release, so that holding it can mean something else (longOk). */
    boolean holdsOk() {
        return false;
    }

    /** OK was held down (only asked when holdsOk()). */
    void longOk() {}

    void onShow() {}

    void onHide() {}

    void dataChanged(boolean addonsChanged) {}

    /** Codio's core reported something ("AddonInstalled", "UserAuthenticated", "Error:<event>"). */
    void coreEvent(String name, String message) {}

    /** Steps a Smooth and keeps frames coming while it moves. */
    final float step(Smooth s, float dt) {
        if (s.step(dt)) tv.moving();
        return s.v;
    }
}
