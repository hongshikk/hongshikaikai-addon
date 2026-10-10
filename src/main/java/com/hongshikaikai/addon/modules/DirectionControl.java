package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.FreeView;
import com.hongshikaikai.addon.utils.VersionCompat;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/**
 * Snaps the body to a chosen compass direction on a key press, then hands the view straight back to
 * the mouse.
 *
 * <h2>What it does</h2>
 * Four keys, the arrow keys by default, each turn the player to one exact yaw - up to 0, right to 90,
 * down to 180 and left to 270 degrees - and to the configured pitch, which is level (0) out of the
 * box. Every one of those five angles is a setting, so the four directions can be pointed anywhere
 * and the pitch can be raised or lowered; the keys are settings too, so the whole thing can be moved
 * off the arrow keys without touching the code.
 *
 * <p>The turn is a real rotation, not a rendered one: it is written to the player entity, which is
 * the value the client physics use, the vanilla movement packet carries and the server predicts
 * with. The camera follows it, because the camera renders that same entity rotation. Nothing is
 * spoofed and nothing needs a second view, which is why this module does not use the
 * {@code FreeView} machinery the flight and combat modules need - it only borrows the interface to
 * know when one of them is already steering the body.
 *
 * <h2>Why the turn is computed as a delta</h2>
 * The new yaw is {@code current + wrapDegrees(target - current)}, never {@code target} itself.
 * Writing the target directly looks equivalent, because the two are the same angle modulo 360, but
 * it is the difference between turning and teleporting: a player sitting at yaw 350 who asks for 0
 * would jump -350 degrees in one tick. Vanilla's own mouse turning never does that - a mouse turn
 * moves by the delta - and Grim's {@code AimModulo360} is written to catch clients that do, since a
 * yaw delta over 320 degrees is not something a mouse produces. Taking the delta keeps every turn at
 * most 180 degrees, and it is also why the stored yaw is never normalised back into -180..180: an
 * accumulated yaw is what a vanilla client sends, and snapping it back is the same signature.
 *
 * <p>The pitch does not need the same treatment, because vanilla clamps it rather than accumulating
 * it, so it is clamped to the same -90..90.
 *
 * <h2>Why the other modules win</h2>
 * ElytraFly+, KillAura+ and NoFall+ each take over the body rotation for as long as they need it -
 * steering a flight, aiming an attack, flying a save - and write it again every tick. Turning from
 * here while one of them is doing that would be overwritten on the next tick at best, and at worst
 * would be the rotation the camera shows for one frame. So a press is dropped, silently, while
 * {@link FreeView#active()} reports one of them in charge. The module stays on; it just does not
 * fight over the same tick.
 *
 * <h2>Why the key press is applied on the tick</h2>
 * The key is read by Meteor's keybind event, which fires from the GLFW callback wherever the input
 * thread happens to be, and the rotation is written from {@code TickEvent.Pre} instead - the head of
 * the client tick, before the player tick reads the rotation and before the movement packet for that
 * tick is built. That is the same place a mouse turn lands, so the entity, the packet and the server
 * prediction all see the turn on the same tick. A press that arrives between ticks is remembered
 * until the next one, and the button being nudged twice inside one tick keeps only the last
 * direction, which is what the player asked for.
 */
public class DirectionControl extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgKeys = settings.createGroup("Keys");
    private final SettingGroup sgAngles = settings.createGroup("Angles");

    // The keys. The arrow keys are up / right / down / left, which is the layout the four directions
    // are named after.
    private final Setting<Keybind> upKey = sgKeys.add(new KeybindSetting.Builder()
        .name("up-key")
        .description("Turns the player to the up direction's yaw (arrow up by default).")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_UP))
        .action(() -> aim(Direction.Up))
        .build()
    );

    private final Setting<Keybind> rightKey = sgKeys.add(new KeybindSetting.Builder()
        .name("right-key")
        .description("Turns the player to the right direction's yaw (arrow right by default).")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_RIGHT))
        .action(() -> aim(Direction.Right))
        .build()
    );

    private final Setting<Keybind> downKey = sgKeys.add(new KeybindSetting.Builder()
        .name("down-key")
        .description("Turns the player to the down direction's yaw (arrow down by default).")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_DOWN))
        .action(() -> aim(Direction.Down))
        .build()
    );

    private final Setting<Keybind> leftKey = sgKeys.add(new KeybindSetting.Builder()
        .name("left-key")
        .description("Turns the player to the left direction's yaw (arrow left by default).")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_LEFT))
        .action(() -> aim(Direction.Left))
        .build()
    );

    // The angles each key turns to. Vanilla yaw runs clockwise from south, so 0 is south, 90 west,
    // 180 north and 270 east - but these are plain numbers and can be set to any angle.
    private final Setting<Integer> upYaw = sgAngles.add(new IntSetting.Builder()
        .name("up-yaw")
        .description("The yaw the up key turns to.")
        .defaultValue(0)
        .min(-360)
        .max(360)
        .sliderRange(-360, 360)
        .build()
    );

    private final Setting<Integer> rightYaw = sgAngles.add(new IntSetting.Builder()
        .name("right-yaw")
        .description("The yaw the right key turns to.")
        .defaultValue(90)
        .min(-360)
        .max(360)
        .sliderRange(-360, 360)
        .build()
    );

    private final Setting<Integer> downYaw = sgAngles.add(new IntSetting.Builder()
        .name("down-yaw")
        .description("The yaw the down key turns to.")
        .defaultValue(180)
        .min(-360)
        .max(360)
        .sliderRange(-360, 360)
        .build()
    );

    private final Setting<Integer> leftYaw = sgAngles.add(new IntSetting.Builder()
        .name("left-yaw")
        .description("The yaw the left key turns to.")
        .defaultValue(270)
        .min(-360)
        .max(360)
        .sliderRange(-360, 360)
        .build()
    );

    private final Setting<Integer> pitch = sgAngles.add(new IntSetting.Builder()
        .name("pitch")
        .description("The pitch every direction key turns to at the same time. 0 is level, negative looks up and positive looks down.")
        .defaultValue(0)
        .min(-90)
        .max(90)
        .sliderRange(-90, 90)
        .build()
    );

    /** The direction the last key press asked for, applied on the next tick. */
    private Direction pending;
    private boolean hasPending;

    public DirectionControl() {
        super(HongShiKaiKai.CATEGORY_TOOLS, "direction-control", "Turns the player to an exact direction on a key press - arrow up/right/down/left to 0/90/180/270 degrees - and then gives the mouse the view back.");
    }

    @Override
    public void onActivate() {
        clearPending();
    }

    @Override
    public void onDeactivate() {
        clearPending();
    }

    /** A direction key was pressed. The turn itself waits for the tick, so it lands at the same point a mouse turn would. */
    private void aim(Direction direction) {
        pending = direction;
        hasPending = true;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!hasPending) return;

        Direction direction = pending;
        clearPending();

        if (mc.player == null || mc.world == null) return;

        // Another module is already writing the body rotation every tick; a turn from here would be
        // gone before it was ever rendered.
        if (FreeView.active() != null) return;

        turnTo(yawOf(direction), pitch.get());
    }

    /** The configured yaw for one of the four direction keys. */
    private double yawOf(Direction direction) {
        return switch (direction) {
            case Up -> upYaw.get();
            case Right -> rightYaw.get();
            case Down -> downYaw.get();
            case Left -> leftYaw.get();
        };
    }

    /**
     * Writes the rotation to the body: the shortest way around to the target yaw, and the target
     * pitch clamped the way vanilla clamps it.
     */
    private void turnTo(double targetYaw, double targetPitch) {
        float yaw = mc.player.getYaw();

        // The shortest way round, exactly as a mouse turn would take it. The result is still the
        // requested angle modulo 360 - it is just not forced back into -180..180, because a yaw that
        // snaps back is the AimModulo360 signature and a yaw that accumulates is what vanilla sends.
        float newYaw = yaw + MathHelper.wrapDegrees((float) targetYaw - yaw);
        float newPitch = MathHelper.clamp((float) targetPitch, -90.0f, 90.0f);

        mc.player.setYaw(newYaw);
        mc.player.setPitch(newPitch);

        // The camera renders Entity.getYaw(tickDelta) / getPitch(tickDelta), which interpolate from
        // the previous tick's rotation, so without this the view would sweep to the new direction
        // over a tick instead of arriving at it. Moving the previous values with the current ones is
        // what makes the turn instant.
        VersionCompat.setPreviousRotation(mc.player, newYaw, newPitch);
    }

    private void clearPending() {
        pending = null;
        hasPending = false;
    }

    /** Which of the four keys asked for a turn. */
    private enum Direction {
        Up,
        Right,
        Down,
        Left
    }
}
