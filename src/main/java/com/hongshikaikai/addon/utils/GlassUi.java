package com.hongshikaikai.addon.utils;

import com.hongshikaikai.addon.modules.HskkHud;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * The painter behind HSKK-HUD: it turns the flat rectangles Meteor draws its screens and HUD with
 * into frosted-glass panels.
 *
 * <p>Meteor's whole interface - the ClickGUI windows, the module list, the settings page, the HUD
 * editor and every HUD element - is drawn with one primitive: an axis-aligned rectangle with up to
 * four corner colours ({@code GuiRenderer.quad} for screens, {@code HudRenderer.quad} for the HUD).
 * That is the single choke point this class works on, which is why two small mixins are enough to
 * reskin all of it: every rectangle that is big enough to be a panel is replaced by
 *
 * <ol>
 *   <li>a soft drop shadow (large panels only),</li>
 *   <li>the same rectangle again, square, at part of its opacity, so that the corners of the rounded
 *       shape that follows can never look bitten into,</li>
 *   <li>a rounded rectangle in the panel's own colour, made translucent and pulled towards the
 *       tint,</li>
 *   <li>a highlight that fades out over the top of the panel, and</li>
 *   <li>a one-pixel light edge (large panels only).</li>
 * </ol>
 *
 * <p>The rounded rectangle is real geometry, not a texture: a straight band across the top, one
 * across the bottom, one down the middle, and a triangle fan for each corner. Using geometry keeps
 * the corner radius a live setting (Meteor's texture atlas is packed once at startup, so a
 * generated rounded-corner texture could not be added later) and keeps the corners free of the
 * stretching a nine-slice would need.
 *
 * <p>The colour transform is deliberately conservative: it keeps the theme's own colours - the
 * purple accent header stays a purple accent header - and only scales their alpha down and pushes
 * them a little towards {@code tint}. Nothing here needs a Minecraft or Meteor version check, which
 * is what lets one implementation cover 1.21.4 through 1.21.11.
 *
 * <p>Everything is written on the render thread into the renderer Meteor already has open, so the
 * panels stay in the same batch - and therefore in the same scissor box - as the widget that asked
 * for them.
 */
public final class GlassUi {
    private GlassUi() {
    }

    // ------------------------------------------------------------------------------------------
    // Re-entrancy
    // ------------------------------------------------------------------------------------------

    /**
     * How deep inside a paint we are. The mixins sit on {@code quad}, and painting a panel means
     * calling {@code quad} again, so without this the first panel would recurse until the stack ran
     * out. While this is non-zero the mixins step aside and let the original rectangle through.
     */
    private static int depth;

    public static boolean painting() {
        return depth > 0;
    }

    // ------------------------------------------------------------------------------------------
    // Access to the module
    // ------------------------------------------------------------------------------------------

    private static HskkHud module() {
        Modules modules = Modules.get();
        return modules == null ? null : modules.get(HskkHud.class);
    }

    /**
     * Whether a rectangle of this size should become a panel. Small ones are left exactly as Meteor
     * drew them: separators, scrollbars, the two-pixel accent bar of an active module and the
     * blinking cursor all live in that size range, and rounding them would smear the interface
     * rather than beautify it.
     */
    public static boolean shouldPaint(double width, double height, boolean hud) {
        if (painting() || width <= 0 || height <= 0) return false;

        HskkHud module = module();
        if (module == null || !module.isActive()) return false;
        if (hud ? !module.hud.get() : !module.screens.get()) return false;

        return Math.min(width, height) >= module.minPanelSize.get();
    }

    // ------------------------------------------------------------------------------------------
    // Scratch colours
    // ------------------------------------------------------------------------------------------
    // The render thread is single-threaded and Renderer2D copies the colours into the vertex buffer
    // as it is called, so a handful of colours can be shared by every panel instead of allocated per
    // call.

    private static final Color TOP_LEFT = new Color();
    private static final Color TOP_RIGHT = new Color();
    private static final Color BOTTOM_RIGHT = new Color();
    private static final Color BOTTOM_LEFT = new Color();
    private static final Color SCRATCH_A = new Color();
    private static final Color SCRATCH_B = new Color();
    private static final Color SCRATCH_C = new Color();
    private static final Color SCRATCH_D = new Color();
    private static final Color CLEAR = new Color(0, 0, 0, 0);

    /**
     * How much of a panel's opacity goes into the square underlay. The rounded pass takes the rest,
     * scaled so that the two composite back to exactly the opacity the setting asked for in the
     * middle of the panel (1 - (1 - p)(1 - q) = opacity), while the corners - where only the
     * underlay reaches - keep {@code UNDERLAY} of it. 1 would make every corner square, 0 would cut
     * them out; half is enough for the rounding to read while nothing can look bitten into.
     */
    private static final double UNDERLAY = 0.5;

    // ------------------------------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------------------------------

    public static void paint(GuiRenderer renderer, double x, double y, double width, double height,
                             Color cTopLeft, Color cTopRight, Color cBottomRight, Color cBottomLeft) {
        HskkHud module = module();
        if (module == null) return;

        GUI.target = renderer;
        paint(GUI, module, x, y, width, height, cTopLeft, cTopRight, cBottomRight, cBottomLeft);
    }

    public static void paint(HudRenderer renderer, double x, double y, double width, double height,
                             Color cTopLeft, Color cTopRight, Color cBottomRight, Color cBottomLeft) {
        HskkHud module = module();
        if (module == null) return;

        HUD.target = renderer;
        paint(HUD, module, x, y, width, height, cTopLeft, cTopRight, cBottomRight, cBottomLeft);
    }

    private static void paint(Surface surface, HskkHud module, double x, double y, double width, double height,
                              Color cTopLeft, Color cTopRight, Color cBottomRight, Color cBottomLeft) {
        depth++;

        try {
            double radius = Math.min(module.cornerRadius.get(), Math.min(width, height) / 2);

            // A panel is only worth a frame and a highlight once it is big enough for them to read as
            // a frame and a highlight rather than as noise: a module row is ~17px tall, a window body
            // and a settings button are much bigger, and everything below 20px is left glossy but
            // unframed and unshadowed.
            boolean framed = module.border.get() && radius >= 1 && width >= 60 && height >= 40;
            boolean glossy = module.sheen.get() && radius >= 1 && width >= 40 && height >= 20;
            boolean lifted = module.shadow.get() && width >= 80 && height >= 60;

            if (lifted) paintShadow(surface, module, x, y, width, height, radius);

            frost(module, cTopLeft, TOP_LEFT);
            frost(module, cTopRight, TOP_RIGHT);
            frost(module, cBottomRight, BOTTOM_RIGHT);
            frost(module, cBottomLeft, BOTTOM_LEFT);

            // Almost every widget smaller than a window draws a two-pixel frame behind the rectangle
            // it hands us - that is how Meteor outlines a button, a checkbox and a text box - and the
            // window body is drawn directly under its own header. Rounding such a rectangle on its own
            // would leave a notch in each corner where what is behind it shows through, so every panel
            // is laid down twice: once square, at most of the opacity, and then rounded on top at the
            // rest. The corners come out lighter than the middle instead of cut out, which is what a
            // soft corner looks like anyway, and the two alphas are chosen so that they composite back
            // to exactly the opacity the glass-opacity setting asked for.
            if (radius >= 1) {
                copy(TOP_LEFT, UNDERLAY, SCRATCH_A);
                copy(TOP_RIGHT, UNDERLAY, SCRATCH_B);
                copy(BOTTOM_RIGHT, UNDERLAY, SCRATCH_C);
                copy(BOTTOM_LEFT, UNDERLAY, SCRATCH_D);
                surface.quad(x, y, width, height, SCRATCH_A, SCRATCH_B, SCRATCH_C, SCRATCH_D);

                double opacity = TOP_LEFT.a / 255.0;
                double rest = (1 - UNDERLAY) / (1 - UNDERLAY * opacity);

                damp(TOP_LEFT, rest);
                damp(TOP_RIGHT, rest);
                damp(BOTTOM_RIGHT, rest);
                damp(BOTTOM_LEFT, rest);
            }

            rounded(surface, x, y, width, height, radius, TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT);

            if (glossy) {
                // The highlight is the thing that reads as "glass": a light wash off the top edge
                // that fades to nothing before it reaches the middle of the panel.
                double sheenHeight = Math.max(6, Math.min(height * 0.42, 18));
                Color sheen = module.sheenColor.get();

                rounded(surface, x + 1, y + 1, width - 2, sheenHeight, Math.max(0, radius - 1),
                    sheen, sheen, CLEAR, CLEAR);
            }

            if (framed) ring(surface, x, y, width, height, radius, 1, module.borderColor.get());
        } finally {
            depth--;
        }
    }

    /**
     * A two-step drop shadow under the big panels. Two nested rounded rectangles, the outer one
     * fainter, read as one soft edge without a blur pass - and a real blur of the panel itself is
     * not an option here, because the panel is translucent and the thing it would have to blur is
     * the world behind it, which Meteor's own Blur module already does.
     */
    private static void paintShadow(Surface surface, HskkHud module, double x, double y, double width, double height, double radius) {
        Color drop = module.shadowColor.get();

        shadow(drop, 0.35, SCRATCH_A);
        rounded(surface, x - 3, y - 1, width + 6, height + 6, radius + 3, SCRATCH_A, SCRATCH_A, SCRATCH_A, SCRATCH_A);

        shadow(drop, 0.65, SCRATCH_B);
        rounded(surface, x - 1.5, y, width + 3, height + 4, radius + 1.5, SCRATCH_B, SCRATCH_B, SCRATCH_B, SCRATCH_B);
    }

    // ------------------------------------------------------------------------------------------
    // Colour
    // ------------------------------------------------------------------------------------------

    /**
     * The panel colour: the theme's own colour, pushed a touch towards white so it reads as glass
     * rather than as paint, pulled towards the tint, and made translucent.
     */
    private static void frost(HskkHud module, Color in, Color out) {
        double frost = module.frost.get();
        double tintStrength = module.tintStrength.get();
        Color tint = module.tintColor.get();

        double r = in.r + (255 - in.r) * frost;
        double g = in.g + (255 - in.g) * frost;
        double b = in.b + (255 - in.b) * frost;

        r += (tint.r - r) * tintStrength;
        g += (tint.g - g) * tintStrength;
        b += (tint.b - b) * tintStrength;

        out.r = clamp(r);
        out.g = clamp(g);
        out.b = clamp(b);
        out.a = clamp(in.a * module.glassOpacity.get());
    }

    private static void shadow(Color from, double factor, Color out) {
        out.r = from.r;
        out.g = from.g;
        out.b = from.b;
        out.a = clamp(from.a * factor);
    }

    /** Scales a colour's alpha in place; the underlay passes use it on the colours {@link #frost} made. */
    private static void damp(Color color, double factor) {
        color.a = clamp(color.a * factor);
    }

    /** A copy of a colour at a fraction of its alpha, for the underlay pass. */
    private static void copy(Color from, double alphaFactor, Color out) {
        out.r = from.r;
        out.g = from.g;
        out.b = from.b;
        out.a = clamp(from.a * alphaFactor);
    }

    private static int clamp(double value) {
        if (value <= 0) return 0;
        if (value >= 255) return 255;

        return (int) Math.round(value);
    }

    private static void lerp(Color from, Color to, double t, Color out) {
        out.r = clamp(from.r + (to.r - from.r) * t);
        out.g = clamp(from.g + (to.g - from.g) * t);
        out.b = clamp(from.b + (to.b - from.b) * t);
        out.a = clamp(from.a + (to.a - from.a) * t);
    }

    // ------------------------------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------------------------------

    /** A rectangle of the given size with rounded corners, as three bands and four fans. */
    private static void rounded(Surface surface, double x, double y, double width, double height, double radius,
                                Color cTopLeft, Color cTopRight, Color cBottomRight, Color cBottomLeft) {
        if (width <= 0 || height <= 0) return;

        if (radius < 1 || width < radius * 2 + 1 || height < radius * 2 + 1) {
            surface.quad(x, y, width, height, cTopLeft, cTopRight, cBottomRight, cBottomLeft);
            return;
        }

        // The colours where each straight band meets the corner arcs, so the vertical gradient of
        // the panel runs through the bands unbroken.
        lerp(cTopLeft, cBottomLeft, radius / height, SCRATCH_A);
        lerp(cTopRight, cBottomRight, radius / height, SCRATCH_B);
        lerp(cTopRight, cBottomRight, (height - radius) / height, SCRATCH_C);
        lerp(cTopLeft, cBottomLeft, (height - radius) / height, SCRATCH_D);

        surface.quad(x + radius, y, width - radius * 2, radius, cTopLeft, cTopRight, SCRATCH_B, SCRATCH_A);
        surface.quad(x, y + radius, width, height - radius * 2, SCRATCH_A, SCRATCH_B, SCRATCH_C, SCRATCH_D);
        surface.quad(x + radius, y + height - radius, width - radius * 2, radius, SCRATCH_D, SCRATCH_C, cBottomRight, cBottomLeft);

        lerp(cTopLeft, cBottomLeft, radius / (height * 2), SCRATCH_A);
        fan(surface, x + radius, y + radius, radius, Math.PI, Math.PI * 1.5, SCRATCH_A);

        lerp(cTopRight, cBottomRight, radius / (height * 2), SCRATCH_B);
        fan(surface, x + width - radius, y + radius, radius, Math.PI * 1.5, Math.PI * 2, SCRATCH_B);

        lerp(cTopRight, cBottomRight, 1 - radius / (height * 2), SCRATCH_C);
        fan(surface, x + width - radius, y + height - radius, radius, 0, Math.PI * 0.5, SCRATCH_C);

        lerp(cTopLeft, cBottomLeft, 1 - radius / (height * 2), SCRATCH_D);
        fan(surface, x + radius, y + height - radius, radius, Math.PI * 0.5, Math.PI, SCRATCH_D);
    }

    /** A filled quarter disc: the corner piece {@link #rounded} leaves out. */
    private static void fan(Surface surface, double cx, double cy, double radius, double from, double to, Color color) {
        int segments = Math.max(3, (int) Math.ceil(radius * 1.5));
        double step = (to - from) / segments;

        for (int i = 0; i < segments; i++) {
            double a0 = from + step * i;
            double a1 = from + step * (i + 1);

            surface.triangle(
                cx, cy,
                cx + Math.cos(a0) * radius, cy + Math.sin(a0) * radius,
                cx + Math.cos(a1) * radius, cy + Math.sin(a1) * radius,
                color
            );
        }
    }

    /** A one-pixel (or thicker) outline that follows the rounded edge, rather than a bigger panel. */
    private static void ring(Surface surface, double x, double y, double width, double height, double radius,
                             double thickness, Color color) {
        if (width <= 0 || height <= 0) return;

        double t = Math.min(thickness, Math.min(width, height) / 2);

        if (radius < 1 || width < radius * 2 + 1 || height < radius * 2 + 1) {
            surface.quad(x, y, width, t, color, color, color, color);
            surface.quad(x, y + height - t, width, t, color, color, color, color);
            surface.quad(x, y + t, t, height - t * 2, color, color, color, color);
            surface.quad(x + width - t, y + t, t, height - t * 2, color, color, color, color);
            return;
        }

        // The straight edges stop where the arcs begin, and the arcs pick up from exactly there.
        surface.quad(x + radius, y, width - radius * 2, t, color, color, color, color);
        surface.quad(x + radius, y + height - t, width - radius * 2, t, color, color, color, color);
        surface.quad(x, y + radius, t, height - radius * 2, color, color, color, color);
        surface.quad(x + width - t, y + radius, t, height - radius * 2, color, color, color, color);

        double inner = Math.max(0, radius - t);

        arc(surface, x + radius, y + radius, inner, radius, Math.PI, Math.PI * 1.5, color);
        arc(surface, x + width - radius, y + radius, inner, radius, Math.PI * 1.5, Math.PI * 2, color);
        arc(surface, x + width - radius, y + height - radius, inner, radius, 0, Math.PI * 0.5, color);
        arc(surface, x + radius, y + height - radius, inner, radius, Math.PI * 0.5, Math.PI, color);
    }

    /** The band between two radii over a quarter turn: the outline's corner. */
    private static void arc(Surface surface, double cx, double cy, double inner, double outer,
                            double from, double to, Color color) {
        int segments = Math.max(3, (int) Math.ceil(outer * 1.5));
        double step = (to - from) / segments;

        for (int i = 0; i < segments; i++) {
            double a0 = from + step * i;
            double a1 = from + step * (i + 1);

            double inX0 = cx + Math.cos(a0) * inner, inY0 = cy + Math.sin(a0) * inner;
            double inX1 = cx + Math.cos(a1) * inner, inY1 = cy + Math.sin(a1) * inner;
            double outX0 = cx + Math.cos(a0) * outer, outY0 = cy + Math.sin(a0) * outer;
            double outX1 = cx + Math.cos(a1) * outer, outY1 = cy + Math.sin(a1) * outer;

            surface.triangle(inX0, inY0, outX0, outY0, outX1, outY1, color);
            surface.triangle(inX0, inY0, outX1, outY1, inX1, inY1, color);
        }
    }

    // ------------------------------------------------------------------------------------------
    // The two renderers, behind one interface
    // ------------------------------------------------------------------------------------------

    private interface Surface {
        void quad(double x, double y, double width, double height, Color cTopLeft, Color cTopRight, Color cBottomRight, Color cBottomLeft);

        void triangle(double x1, double y1, double x2, double y2, double x3, double y3, Color color);
    }

    private static final GuiSurface GUI = new GuiSurface();
    private static final HudSurface HUD = new HudSurface();

    private static final class GuiSurface implements Surface {
        private GuiRenderer target;

        @Override
        public void quad(double x, double y, double width, double height, Color cTopLeft, Color cTopRight, Color cBottomRight, Color cBottomLeft) {
            target.quad(x, y, width, height, cTopLeft, cTopRight, cBottomRight, cBottomLeft);
        }

        @Override
        public void triangle(double x1, double y1, double x2, double y2, double x3, double y3, Color color) {
            target.triangle(x1, y1, x2, y2, x3, y3, color);
        }
    }

    private static final class HudSurface implements Surface {
        private HudRenderer target;

        @Override
        public void quad(double x, double y, double width, double height, Color cTopLeft, Color cTopRight, Color cBottomRight, Color cBottomLeft) {
            target.quad(x, y, width, height, cTopLeft, cTopRight, cBottomRight, cBottomLeft);
        }

        @Override
        public void triangle(double x1, double y1, double x2, double y2, double x3, double y3, Color color) {
            target.triangle(x1, y1, x2, y2, x3, y3, color);
        }
    }
}
