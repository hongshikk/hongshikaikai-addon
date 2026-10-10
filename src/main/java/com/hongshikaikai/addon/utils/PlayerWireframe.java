package com.hongshikaikai.addon.utils;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.math.Vec3d;

/**
 * A player-shaped wireframe drawn at a position of the caller's choosing, turned to a yaw: the six
 * boxes a player model is made of, filled faintly and outlined.
 *
 * <p>It lives here rather than in one module because two of them answer the same question - "where
 * does the server believe I am" - and draw the same shape to answer it. TpAura+ puts it at its
 * desync position, FakeLag puts it at the position in the oldest packet it is still holding.
 *
 * <p>The corners are turned by hand rather than through the matrix stack, because Meteor renders
 * the whole frame's vertices in one batch under a single matrix: a transform pushed around a box
 * would be gone by the time the batch is drawn.
 */
public final class PlayerWireframe {
    /** Scratch colour for the fill. The render thread is the only caller, so one is enough. */
    private static final Color FILL_SCRATCH = new Color();

    /** How much of the outline's alpha the fill keeps. */
    private static final double FILL_ALPHA = 0.34;

    private PlayerWireframe() {
    }

    /**
     * Draws the wireframe standing at {@code origin} and facing {@code yaw}, in Minecraft degrees,
     * with {@code outline} as both the edges and - dimmed - the faces.
     */
    public static void draw(Render3DEvent event, Vec3d origin, float yaw, Color outline) {
        // Minecraft yaw 0 looks along +Z, so the model's own forward has to be turned by -yaw.
        double radians = -Math.toRadians(yaw);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        Color fill = tint(outline);

        // Legs, torso, arms, head - the same six parts a player model has.
        box(event, origin, cos, sin, -0.20, 0.00, -0.10, 0.00, 0.75, 0.10, fill, outline);
        box(event, origin, cos, sin, 0.00, 0.00, -0.10, 0.20, 0.75, 0.10, fill, outline);
        box(event, origin, cos, sin, -0.20, 0.75, -0.10, 0.20, 1.40, 0.10, fill, outline);
        box(event, origin, cos, sin, -0.35, 0.75, -0.10, -0.20, 1.40, 0.10, fill, outline);
        box(event, origin, cos, sin, 0.20, 0.75, -0.10, 0.35, 1.40, 0.10, fill, outline);
        box(event, origin, cos, sin, -0.20, 1.40, -0.20, 0.20, 1.80, 0.20, fill, outline);
    }

    /**
     * One turned box: its eight corners are rotated around the origin and drawn as six translucent
     * faces and twelve edges.
     */
    private static void box(Render3DEvent event, Vec3d origin, double cos, double sin,
                            double x1, double y1, double z1, double x2, double y2, double z2,
                            Color fill, Color outline) {
        double[] xs = {x1, x2, x2, x1, x1, x2, x2, x1};
        double[] ys = {y1, y1, y2, y2, y1, y1, y2, y2};
        double[] zs = {z1, z2, z2, z1, z1, z2, z2, z1};

        double[] wx = new double[8];
        double[] wy = new double[8];
        double[] wz = new double[8];

        for (int i = 0; i < 8; i++) {
            wx[i] = origin.x + xs[i] * cos - zs[i] * sin;
            wy[i] = origin.y + ys[i];
            wz[i] = origin.z + xs[i] * sin + zs[i] * cos;
        }

        // Sides. Bottom, top, then the four walls in the corner order above.
        quad(event, wx, wy, wz, 0, 1, 2, 3, fill);
        quad(event, wx, wy, wz, 4, 5, 6, 7, fill);
        quad(event, wx, wy, wz, 0, 1, 5, 4, fill);
        quad(event, wx, wy, wz, 3, 2, 6, 7, fill);
        quad(event, wx, wy, wz, 0, 3, 7, 4, fill);
        quad(event, wx, wy, wz, 1, 2, 6, 5, fill);

        // Edges.
        line(event, wx, wy, wz, 0, 1, outline);
        line(event, wx, wy, wz, 1, 2, outline);
        line(event, wx, wy, wz, 2, 3, outline);
        line(event, wx, wy, wz, 3, 0, outline);
        line(event, wx, wy, wz, 4, 5, outline);
        line(event, wx, wy, wz, 5, 6, outline);
        line(event, wx, wy, wz, 6, 7, outline);
        line(event, wx, wy, wz, 7, 4, outline);
        line(event, wx, wy, wz, 0, 4, outline);
        line(event, wx, wy, wz, 1, 5, outline);
        line(event, wx, wy, wz, 2, 6, outline);
        line(event, wx, wy, wz, 3, 7, outline);
    }

    private static void quad(Render3DEvent event, double[] wx, double[] wy, double[] wz,
                             int a, int b, int c, int d, Color color) {
        event.renderer.quad(
            wx[a], wy[a], wz[a],
            wx[b], wy[b], wz[b],
            wx[c], wy[c], wz[c],
            wx[d], wy[d], wz[d],
            color
        );
    }

    private static void line(Render3DEvent event, double[] wx, double[] wy, double[] wz, int a, int b, Color color) {
        event.renderer.line(wx[a], wy[a], wz[a], wx[b], wy[b], wz[b], color);
    }

    /** A scratch copy of {@code source} with its alpha scaled, so the fill and the edges match. */
    private static Color tint(Color source) {
        FILL_SCRATCH.set(source);
        FILL_SCRATCH.a = (int) (source.a * FILL_ALPHA);
        return FILL_SCRATCH;
    }
}
