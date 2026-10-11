package com.hongshikaikai.addon.utils.crystal;

import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Colour maths for the crystal rendering.
 *
 * <p>Each helper returns a fresh {@link Color} instance rather than mutating the colours passed in.
 */
public final class ColorUtil {
    private ColorUtil() {
    }

    /** Linear blend from {@code startColor} to {@code endColor}; {@code progress} is clamped to 0..1. */
    public static Color fadeColor(Color startColor, Color endColor, double progress) {
        progress = Math.min(Math.max(progress, 0.0), 1.0);
        int sR = startColor.r;
        int sG = startColor.g;
        int sB = startColor.b;
        int sA = startColor.a;
        int eR = endColor.r;
        int eG = endColor.g;
        int eB = endColor.b;
        int eA = endColor.a;
        return new Color(
            Math.min((int) (sR + (eR - sR) * progress), 255),
            Math.min((int) (sG + (eG - sG) * progress), 255),
            Math.min((int) (sB + (eB - sB) * progress), 255),
            Math.min((int) (sA + (eA - sA) * progress), 255)
        );
    }

    /** Same colour with the alpha replaced; the alpha is clamped to 0..255. */
    public static Color injectAlpha(Color color, int alpha) {
        alpha = Math.max(Math.min(255, alpha), 0);
        return new Color(color.r, color.g, color.b, alpha);
    }

    /** Same colour with its alpha scaled by {@code scale} (0..1), clamped to 0..255. */
    public static Color scaleAlpha(Color color, double scale) {
        return injectAlpha(color, (int) (color.a * scale));
    }
}
