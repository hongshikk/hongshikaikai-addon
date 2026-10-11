package com.hongshikaikai.addon.utils.crystal;

/**
 * A value that eases from wherever it was to a new target.
 *
 * <p>Read every frame with {@link #get}: the first frame a new target is seen the previous motion is
 * committed to {@code from} and the fade restarts, so the value never jumps.
 */
public class Animation {
    private final FadeUtils fadeUtils = new FadeUtils(0L);
    public double from = 0.0;
    public double to = 0.0;

    public double get(double target, long length, Easing ease) {
        if (target != to) {
            from = from + (to - from) * fadeUtils.ease(ease);
            to = target;
            fadeUtils.reset();
        }

        fadeUtils.setLength(length);
        return from + (to - from) * fadeUtils.ease(ease);
    }
}
