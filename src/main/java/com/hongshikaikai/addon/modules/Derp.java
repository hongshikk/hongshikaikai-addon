package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.FreeView;
import com.hongshikaikai.addon.utils.VersionCompat;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

/**
 * Derp: turns the body to a silly angle every tick, so everyone else sees the player derping, while
 * the player's own view carries on pointing where the mouse does.
 *
 * <p>This is a port of LiquidBounce's {@code ModuleDerp} (Kotlin, GPL-3.0). The decisions are the
 * LiquidBounce ones: the same two mode lists, the same defaults, the same {@code safe-pitch} and
 * {@code not-during-sprint} guards, and the same split between the rotation that is sent and the
 * rotation the player looks with. Where the port departs from the source, the method that departs
 * says so.
 *
 * <h2>Body and camera</h2>
 * The module writes the derped rotation to the player entity once a tick. The entity rotation is
 * what the client physics read, what the vanilla movement packet carries and what the server
 * predicts with, so all three agree on the derp - and it is also what the player model is drawn
 * from, so in third person the player can watch their own body spinning around.
 *
 * <p>The camera is a separate matter, and it does not follow. The module registers as a
 * {@link FreeView}, which is the same mechanism ElytraFly+, KillAura+ and NoFall+ use: the player
 * entity keeps the working rotation and the camera is rendered from a view the module owns, moved
 * only by the mouse. So the derp is visible from outside and invisible from the player's own eyes,
 * which is the shape LiquidBounce's Derp has as well - it takes the rotations group's default,
 * {@code MovementCorrection.SILENT}, and that correction only ever writes the managed rotation into
 * the client's look for {@code CHANGE_LOOK}.
 *
 * <h2>Why the rotation is written to the body and not only to the packet</h2>
 * A "silent" rotation - one swapped into the entity for the length of the movement packet and taken
 * back out afterwards - looks like the tidier way to do this, and it is what this addon's first
 * attempt at the module did. It is wrong here for two reasons.
 *
 * <p>The first is the addon's own rule: whatever the server is told, the client must actually have
 * done. The price of breaking it is the second reason, which is concrete. From 1.21.2 the client
 * sends {@code ServerboundPlayerInputPacket} with the keys it is actually holding, and Grim's
 * prediction engine builds the expected movement from those keys and the rotation it was last sent.
 * A silent rotation therefore makes the server predict a movement the client never made, on every
 * tick the player moves. Writing the rotation to the body instead means the physics turn with it,
 * and the prediction agrees - which is the same reason LiquidBounce bends the client's movement onto
 * the managed yaw whenever its correction is not {@code OFF}.
 *
 * <h2>The consequence, which is the source's behaviour too</h2>
 * Because the body really turns, the keys are read against it: holding forward walks the player the
 * way the derp is pointing, which with a fresh random angle every tick is barely anywhere at all.
 * That is what LiquidBounce's Derp does - {@code MovementCorrection.SILENT} feeds the managed yaw to
 * {@code Entity.moveRelative} - and it is left in rather than softened into a module that no longer
 * derps. {@code not-during-sprint} is the escape hatch the source provides, and it is on by default.
 *
 * <h2>Why the other modules win</h2>
 * LiquidBounce files the rotation at {@code Priority.NOT_IMPORTANT}, so every other request - an
 * aim, a flight, a save - outranks it. This module is the weakest holder in {@link FreeView}: while
 * a save, a flight or a combat aim holds the view, the derp releases it and does nothing, and a
 * module that outranks a derp can take the body back from it mid-fight.
 *
 * <p>The handler is filed at {@link EventPriority#LOW} so that it runs after those modules have had
 * their say for the tick, and the ranking is therefore read for the current tick rather than for the
 * last one.
 *
 * <h2>The modes read the view, not the body</h2>
 * {@code Offset} adds its setting to the view yaw (or pitch) and {@code Jitter} reads the view yaw
 * for its forward phase - the view, not {@code player.getYaw()}, because the entity rotation is the
 * module's own output from the previous tick. Reading the view is what the source does, where
 * {@code player.yRot} is the real look under a silent correction, and it is why the two modes do not
 * accumulate: an {@code Offset} of 30 degrees is a fixed 30 degrees from wherever the player is
 * looking, and {@code Jitter} flips the body between the view and the view plus 180. {@code Spin} is
 * the one mode with state of its own, and it starts at 0, as the source's does.
 *
 * <h2>Safe pitch</h2>
 * {@code safe-pitch} clamps the pitch to -90..90, which is also what vanilla's
 * {@code Entity.setPitch} does on its own, so with the option on the clamp is redundant and with it
 * off the pitch is clamped by vanilla anyway a line later. What the option really controls is the
 * range the {@code Random} pitch mode draws from - which matches what the source's option ends up
 * controlling.
 *
 * <h2>Versions</h2>
 * Everything this file names was read off the named jars for each supported version with
 * {@code javap} rather than assumed: {@code Entity.getYaw/setYaw/getPitch/setPitch} and
 * {@code GameOptions.sprintKey.isPressed()} are unchanged on all seven Minecraft versions
 * (1.21.4 - 1.21.11). The only version-shaped read is the previous-rotation write, which already
 * lives in {@link VersionCompat#setPreviousRotation}.
 */
public class Derp extends Module implements FreeView {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgYaw = settings.createGroup("Yaw");
    private final SettingGroup sgPitch = settings.createGroup("Pitch");

    private final Setting<YawMode> yawMode = sgGeneral.add(new EnumSetting.Builder<YawMode>()
        .name("yaw-mode")
        .description("How the body's yaw is chosen each tick. Static pins it, Offset pushes it away from where you are looking, Random picks a fresh angle, Jitter flips it around, Spin turns it at a fixed speed.")
        .defaultValue(YawMode.Random)
        .build()
    );

    private final Setting<PitchMode> pitchMode = sgGeneral.add(new EnumSetting.Builder<PitchMode>()
        .name("pitch-mode")
        .description("How the body's pitch is chosen each tick. Static pins it, Offset pushes it away from where you are looking, Random picks a fresh angle.")
        .defaultValue(PitchMode.Random)
        .build()
    );

    private final Setting<Boolean> safePitch = sgGeneral.add(new BoolSetting.Builder()
        .name("safe-pitch")
        .description("Keep the pitch inside -90..90, the range vanilla clamps to anyway. Off lets the choices be drawn from the full -180..180 circle.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notDuringSprint = sgGeneral.add(new BoolSetting.Builder()
        .name("not-during-sprint")
        .description("Stop derping and hand the body back while the sprint key is held or the player is sprinting, so a sprint is not steered by the derp.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> yawStatic = sgYaw.add(new DoubleSetting.Builder()
        .name("yaw")
        .description("The yaw the Static mode turns the body to.")
        .defaultValue(0.0)
        .min(-180.0)
        .max(180.0)
        .sliderRange(-180.0, 180.0)
        .decimalPlaces(1)
        .visible(() -> yawMode.get() == YawMode.Static)
        .build()
    );

    private final Setting<Double> yawOffset = sgYaw.add(new DoubleSetting.Builder()
        .name("offset")
        .description("How far the Offset mode turns the body away from where you are looking. Your look is not changed, so this is a fixed offset and not a drift.")
        .defaultValue(0.0)
        .min(-180.0)
        .max(180.0)
        .sliderRange(-180.0, 180.0)
        .decimalPlaces(1)
        .visible(() -> yawMode.get() == YawMode.Offset)
        .build()
    );

    private final Setting<Integer> yawForwardTicks = sgYaw.add(new IntSetting.Builder()
        .name("forward-ticks")
        .description("How many ticks the Jitter mode keeps the body on the yaw you are looking with, before the backward phase.")
        .defaultValue(2)
        .min(0)
        .max(100)
        .sliderRange(0, 40)
        .visible(() -> yawMode.get() == YawMode.Jitter)
        .build()
    );

    private final Setting<Integer> yawBackwardTicks = sgYaw.add(new IntSetting.Builder()
        .name("backward-ticks")
        .description("How many ticks the Jitter mode turns the body to the yaw you are looking with plus 180, so everyone else sees the player facing backwards.")
        .defaultValue(2)
        .min(0)
        .max(100)
        .sliderRange(0, 40)
        .visible(() -> yawMode.get() == YawMode.Jitter)
        .build()
    );

    private final Setting<Integer> yawSpinSpeed = sgYaw.add(new IntSetting.Builder()
        .name("speed")
        .description("How many degrees per tick the Spin mode turns the body. Positive is clockwise on the compass, negative the other way.")
        .defaultValue(50)
        .min(-70)
        .max(70)
        .sliderRange(-70, 70)
        .visible(() -> yawMode.get() == YawMode.Spin)
        .build()
    );

    private final Setting<Double> pitchStatic = sgPitch.add(new DoubleSetting.Builder()
        .name("pitch")
        .description("The pitch the Static mode turns the body to. -90 is straight down, 90 straight up.")
        .defaultValue(-90.0)
        .min(-180.0)
        .max(180.0)
        .sliderRange(-180.0, 180.0)
        .decimalPlaces(1)
        .visible(() -> pitchMode.get() == PitchMode.Static)
        .build()
    );

    private final Setting<Double> pitchOffset = sgPitch.add(new DoubleSetting.Builder()
        .name("offset")
        .description("How far the Offset mode turns the body's pitch away from the pitch you are looking with.")
        .defaultValue(0.0)
        .min(-180.0)
        .max(180.0)
        .sliderRange(-180.0, 180.0)
        .decimalPlaces(1)
        .visible(() -> pitchMode.get() == PitchMode.Offset)
        .build()
    );

    /** Source of the random angles. LiquidBounce draws from Kotlin's default {@code Random}. */
    private final Random random = new Random();

    /** Position inside the Jitter cycle, in ticks. */
    private int jitterTick;

    /**
     * The Spin mode's accumulated yaw. It is kept across ticks - and across mode changes, like the
     * source's - so that the spin resumes where it left off; it starts at 0 because the source's
     * {@code yaw} does too.
     */
    private float spinYaw;

    // ---- The view the camera renders, and the body rotation it is split from ----

    /** True while the module owns the body rotation and the camera renders its view. */
    private boolean viewLockActive;

    /** The view the player is looking with. Only the mouse moves it. */
    private float viewYaw;
    private float viewPitch;
    private float prevViewYaw;
    private float prevViewPitch;

    public Derp() {
        super(HongShiKaiKai.CATEGORY, "Derp", "Turns your body to a silly angle every tick - everyone else sees you derping, your own view stays where you point it.");
    }

    @Override
    public void onActivate() {
        jitterTick = 0;
        spinYaw = 0.0f;
        viewLockActive = false;
    }

    @Override
    public void onDeactivate() {
        releaseViewLock();
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) {
            releaseViewLock();
            return;
        }

        // The derp steers the body, so a sprint would be steered by it. Hand the body back instead
        // of freezing it at whatever angle the last tick happened to pick.
        if (notDuringSprint.get() && (mc.options.sprintKey.isPressed() || mc.player.isSprinting())) {
            releaseViewLock();
            return;
        }

        // A save, a flight or an aim outranks a derp. The body is handed back without restoring the
        // view, because the module that outranks this one has already written its own rotation for
        // this tick and the view here is not what should be in the packet.
        if (FreeView.outranking(this) != null) {
            releaseViewLock(false);
            return;
        }

        // Take the view from wherever the player was looking when the derp started, so the camera
        // carries on from there instead of jumping.
        if (!viewLockActive) beginViewLock();

        prevViewYaw = viewYaw;
        prevViewPitch = viewPitch;

        float yaw = currentYaw();
        float pitch = currentPitch();

        if (safePitch.get()) {
            pitch = MathHelper.clamp(pitch, -90.0f, 90.0f);
        }

        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);

        // The model is drawn from the interpolated rotation, so the previous values have to move
        // with the current ones: without this the body would smear round to each new angle over a
        // tick instead of snapping to it, and the spin would read as a stutter.
        VersionCompat.setPreviousRotation(mc.player, yaw, pitch);
    }

    // ---- FreeView ----

    @Override
    public boolean isViewLockActive() {
        return viewLockActive;
    }

    @Override
    public void applyLookDelta(double deltaYaw, double deltaPitch) {
        if (!viewLockActive) return;

        // Vanilla shifts prevYaw/prevPitch by the same delta, so the view answers the mouse
        // immediately instead of easing toward it. Mirror that here.
        viewYaw += (float) deltaYaw;
        prevViewYaw += (float) deltaYaw;

        viewPitch = MathHelper.clamp(viewPitch + (float) deltaPitch, -90.0f, 90.0f);
        prevViewPitch = MathHelper.clamp(prevViewPitch + (float) deltaPitch, -90.0f, 90.0f);
    }

    @Override
    public float getCameraYaw() {
        return viewYaw;
    }

    @Override
    public float getCameraPitch() {
        return viewPitch;
    }

    @Override
    public float getPrevCameraYaw() {
        return prevViewYaw;
    }

    @Override
    public float getPrevCameraPitch() {
        return prevViewPitch;
    }

    private void beginViewLock() {
        viewLockActive = true;

        viewYaw = mc.player.getYaw();
        viewPitch = mc.player.getPitch();
        prevViewYaw = viewYaw;
        prevViewPitch = viewPitch;
    }

    private void releaseViewLock() {
        releaseViewLock(true);
    }

    /**
     * Drops the view lock. {@code restoreBodyView} puts the body back on the view - what the module
     * wants whenever the derp stops for its own reasons, because the body would otherwise be left
     * frozen at the last silly angle. It is turned off when the view is being handed to a module
     * that outranks this one, which has already written the rotation for this tick.
     */
    private void releaseViewLock(boolean restoreBodyView) {
        if (!viewLockActive) return;
        viewLockActive = false;

        if (!restoreBodyView || mc.player == null) return;

        float yaw = viewYaw;
        float pitch = MathHelper.clamp(viewPitch, -90.0f, 90.0f);

        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);

        // The camera renders the interpolated rotation, so without this the frame after the derp
        // stops would still be drawn at the last silly angle and only then slide across.
        VersionCompat.setPreviousRotation(mc.player, yaw, pitch);
    }

    // ---- Modes ----

    /** The yaw the active mode asks the body to take this tick. */
    private float currentYaw() {
        return switch (yawMode.get()) {
            case Static -> yawStatic.get().floatValue();
            case Offset -> viewYaw + yawOffset.get().floatValue();
            case Random -> randomBetween(-180.0f, 180.0f);
            case Jitter -> jitterYaw();
            case Spin -> spinYaw += yawSpinSpeed.get();
        };
    }

    /** The pitch the active mode asks the body to take this tick. */
    private float currentPitch() {
        return switch (pitchMode.get()) {
            case Static -> pitchStatic.get().floatValue();
            case Offset -> viewPitch + pitchOffset.get().floatValue();
            case Random -> safePitch.get() ? randomBetween(-90.0f, 90.0f) : randomBetween(-180.0f, 180.0f);
        };
    }

    /**
     * The Jitter yaw: the view yaw for the first {@code forward-ticks} ticks of the cycle, and the
     * view yaw plus 180 for the next {@code backward-ticks}. LiquidBounce writes this on a generator
     * that sets {@code yaw} from {@code player.yRot} and then waits a tick, so the value is re-read
     * every tick - which is what makes the backward phase a flip back and forth rather than an
     * ever-turning spin.
     *
     * <p>A cycle of zero ticks never advances; the phase is then pinned to "forward" and the body
     * follows the view, which is what the source's empty repeat does.
     */
    private float jitterYaw() {
        int forward = yawForwardTicks.get();
        int backward = yawBackwardTicks.get();
        int cycle = forward + backward;

        if (cycle <= 0) return viewYaw;

        boolean facingBackwards = jitterTick >= forward;
        jitterTick = (jitterTick + 1) % cycle;

        return facingBackwards ? viewYaw + 180.0f : viewYaw;
    }

    /** A uniform angle in {@code min..max}, the way Kotlin's {@code ClosedFloatingPointRange.random()} draws one. */
    private float randomBetween(float min, float max) {
        return (float) (min + random.nextDouble() * (max - min));
    }

    @Override
    public String getInfoString() {
        return yawMode.get() + "/" + pitchMode.get();
    }

    /** How the yaw is picked each tick. The names are LiquidBounce's mode names. */
    public enum YawMode {
        /** One fixed angle. */
        Static,
        /** The view, pushed away by a fixed amount. */
        Offset,
        /** A fresh uniform angle in -180..180. */
        Random,
        /** The view for a while, then the view plus 180 for a while. */
        Jitter,
        /** The angle from the previous tick, turned by a fixed number of degrees. */
        Spin
    }

    /** How the pitch is picked each tick. LiquidBounce has no jitter or spin pitch. */
    public enum PitchMode {
        /** One fixed angle. */
        Static,
        /** The view, pushed away by a fixed amount. */
        Offset,
        /** A fresh uniform angle, drawn from -90..90 while {@code safe-pitch} is on. */
        Random
    }
}
