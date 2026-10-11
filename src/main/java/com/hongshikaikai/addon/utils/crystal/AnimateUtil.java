package com.hongshikaikai.addon.utils.crystal;

/** Linear-towards-target animation. */
public final class AnimateUtil {
    private AnimateUtil() {
    }

    /**
     * Moves {@code current} towards {@code endPoint} by {@code speed} of the remaining distance.
     *
     * <p>The step is proportional to the gap, so the motion slows as it arrives instead of
     * overshooting; {@code speed >= 1} snaps and {@code speed == 0} freezes.
     */
    public static double animate(double current, double endPoint, double speed) {
        if (speed >= 1.0) {
            return endPoint;
        } else if (speed == 0.0) {
            return current;
        } else {
            boolean shouldContinueAnimation = endPoint > current;
            double dif = Math.max(endPoint, current) - Math.min(endPoint, current);
            if (Math.abs(dif) <= 0.001) {
                return endPoint;
            } else {
                double factor = dif * speed;
                return current + (shouldContinueAnimation ? factor : -factor);
            }
        }
    }
}
