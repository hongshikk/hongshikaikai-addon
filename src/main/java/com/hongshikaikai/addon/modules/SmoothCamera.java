package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Port of LiquidBounce's SmoothCamera: the camera trails behind the player instead of being pinned
 * to the eye, so the view glides over small movements instead of copying every one of them.
 *
 * <h2>What it does</h2>
 * Vanilla puts the camera exactly where the player is, every frame. This module keeps its own
 * position and, once per frame, moves it a fraction of the way towards the position vanilla asked
 * for. With the default factors that is a tenth of the remaining distance per frame for the
 * horizontal axes and a little less vertically, so a step sideways drags the view along behind it
 * for a handful of frames, while standing still converges back onto the player and stops.
 *
 * <p>Each axis is filtered on its own, which is why there are two factors rather than one: the
 * horizontal factor is the stronger of the two, so the camera hangs back on a strafe but still
 * follows the player down a fall instead of floating above it. Both are plain settings - 0 leaves
 * the camera where it was, 1 pins it to the player exactly.
 *
 * <h2>What it does not do</h2>
 * This is a render-only effect. The camera position is not the player position and never reaches
 * the server: movement packets carry the entity, and this module does not touch the entity. The
 * view can therefore trail behind while the physics, the packet and the server prediction all
 * agree on where the player actually is.
 *
 * <p>The position is not the rotation either - the camera still points where the player looks, so
 * aiming, attacking and every rotation module are unaffected.
 *
 * <h2>First person</h2>
 * Smoothing only makes sense while the camera is standing back looking at the player. In first
 * person the camera sits in the player's head, so a trailing camera detaches the view from the
 * player and the world drifts past the crosshair; {@code enable-first-person} turns that on anyway
 * for anyone who wants it, and it is off by default.
 *
 * <h2>Perspective changes</h2>
 * Switching between first and third person teleports the camera several blocks in one frame - from
 * the eye to behind the back. Filtering across that would sweep the camera out through the wall
 * the player was facing, so the filter is restarted from the new perspective's position instead of
 * gliding to it. That is what {@code reset-on-perspective-change} controls, on by default.
 *
 * <h2>Where the hook lives</h2>
 * {@code Camera.update} is where the frame's position is worked out and stored, and
 * {@link com.hongshikaikai.addon.mixin.SmoothCameraMixin} filters it there, at the end of the
 * method. That is the one point that sees the finished position on every path and every version
 * this addon builds for; it is also the value the renderer actually uses, so the filter acts on
 * what is on screen rather than on an intermediate. Nothing else about the camera is touched.
 */
public class SmoothCamera extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> enableFirstPerson = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-first-person")
        .description("Also smooth the camera in first person. Off by default, because there the lag reads as the view coming loose from the player.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> resetOnPerspectiveChange = sgGeneral.add(new BoolSetting.Builder()
        .name("reset-on-perspective-change")
        .description("Snap the camera back to the player when the perspective is switched, instead of gliding across the blocks between the two positions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> horizontalFactor = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-factor")
        .description("How much of the camera's previous horizontal position is kept each frame. Higher is smoother and further behind the player.")
        .defaultValue(0.9)
        .range(0.0, 1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    private final Setting<Double> verticalFactor = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-factor")
        .description("The same for the vertical axis. A little higher by default, so the camera does not sink on the way down.")
        .defaultValue(0.93)
        .range(0.0, 1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    /**
     * The position the camera was last rendered at. {@link Vec3d#ZERO} is not a position a camera
     * ever holds - the eye height alone keeps it off the origin - so it doubles as "no frame has
     * been filtered yet".
     */
    private Vec3d smoothPos = Vec3d.ZERO;

    /** The perspective the last filtered frame was rendered with, for the change-over test. */
    private Perspective lastPerspective;

    public SmoothCamera() {
        super(HongShiKaiKai.CATEGORY_TOOLS, "SmoothCamera", "Lets the camera trail behind the player instead of moving with it, so the view glides over small movements.");
    }

    @Override
    public void onActivate() {
        // Restart the filter, but from wherever the camera is now - the perspective the player is
        // in at the moment the module is switched on is the one the first frame should not treat as
        // a change.
        smoothPos = Vec3d.ZERO;
        lastPerspective = mc.options.getPerspective();
    }

    @Override
    public void onDeactivate() {
        // The camera is back in vanilla's hands; a stale position would otherwise be the starting
        // point of the glide the next time the module is switched on.
        smoothPos = Vec3d.ZERO;
    }

    /**
     * Filters one frame of camera movement. Called from the camera hook with the position vanilla
     * has just computed for the frame, and returns the position the camera should be rendered at
     * instead.
     *
     * <p>Every branch has to write {@link #smoothPos}, including the ones that return the real
     * position unchanged: those are exactly the frames the filter is being held at or restarted
     * from, and leaving the stored value behind would make the next frame glide in from wherever
     * the camera was before.
     */
    public Vec3d cameraUpdate(Vec3d pos) {
        Perspective perspective = mc.options.getPerspective();

        // First and third person are blocks apart, so crossing between them is not a movement to
        // smooth - it is a jump to be taken, and then filtered from the far side.
        if (resetOnPerspectiveChange.get() && lastPerspective != perspective) {
            lastPerspective = perspective;
            smoothPos = pos;
            return smoothPos;
        }
        lastPerspective = perspective;

        // In first person the camera is inside the player's head; lagging it there drags the view
        // off the crosshair, so unless it was asked for, it follows exactly.
        if (!enableFirstPerson.get() && perspective.isFirstPerson()) {
            smoothPos = pos;
            return smoothPos;
        }

        // First frame after switching on: start from the real position rather than sliding in from
        // the origin. Same check LiquidBounce makes on its own stored position.
        if (MathHelper.approximatelyEquals(smoothPos.lengthSquared(), 0.0)) {
            smoothPos = pos;
            return smoothPos;
        }

        double h = horizontalFactor.get();
        double v = verticalFactor.get();

        smoothPos = new Vec3d(
            smoothPos.x * h + pos.x * (1.0 - h),
            smoothPos.y * v + pos.y * (1.0 - v),
            smoothPos.z * h + pos.z * (1.0 - h)
        );

        return smoothPos;
    }
}
