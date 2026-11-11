package com.codio.tv.ui;

/** A value that eases toward its target (time-based, so it looks the same at any frame rate). */
final class Smooth {
    float v, target;
    private final float tau, eps;

    Smooth(float tau, float eps) {
        this.tau = tau;
        this.eps = eps;
    }

    void to(float t) {
        target = t;
    }

    void snap(float t) {
        v = target = t;
    }

    /** Advances one frame; true while still moving. */
    boolean step(float dt) {
        if (v == target) return false;
        float a = 1f - (float) Math.exp(-dt / tau);
        v += (target - v) * a;
        if (Math.abs(target - v) < eps) {
            v = target;
            return false;
        }
        return true;
    }
}
