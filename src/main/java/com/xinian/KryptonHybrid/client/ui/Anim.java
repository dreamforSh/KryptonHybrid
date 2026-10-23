package com.xinian.KryptonHybrid.client.ui;

import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;

/**
 * Frame-rate independent easing of named UI values (hover fades, sliding tab
 * underline, toggle knobs). Call {@link #frame()} once per render, then
 * {@link #approach} for each animated value.
 */
public final class Anim {
    private final Object2FloatOpenHashMap<String> values = new Object2FloatOpenHashMap<>();
    private long lastNanos = System.nanoTime();
    private float dt;

    public Anim() {
        values.defaultReturnValue(Float.NaN);
    }

    public void frame() {
        long now = System.nanoTime();
        dt = Math.min(0.1F, (now - lastNanos) / 1_000_000_000F);
        lastNanos = now;
    }

    /**
     * Moves the value named {@code id} toward {@code target} and returns it. The first
     * call snaps to the target, so nothing animates in from zero when a screen opens.
     * {@code speed} is roughly how many times per second the remaining gap shrinks by e.
     */
    public float approach(String id, float target, float speed) {
        float current = values.getFloat(id);
        if (current == target) {
            return target;
        }
        float v = Float.isNaN(current) ? target : current + (target - current) * (1F - (float) Math.exp(-speed * dt));
        if (Math.abs(target - v) < 0.002F) {
            v = target;
        }
        values.put(id, v);
        return v;
    }

    /** 0..1 hover progress for {@code id}. */
    public float hover(String id, boolean hovered) {
        return approach(id, hovered ? 1F : 0F, 16F);
    }
}
