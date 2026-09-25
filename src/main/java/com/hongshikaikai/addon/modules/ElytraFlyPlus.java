package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.FreeView;
import com.hongshikaikai.addon.utils.VersionCompat;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Elytra assistant with a server-side flight rotation.
 *
 * <p>While gliding, the keys choose where the body points:
 * <ul>
 *     <li>space alone - straight up, at {@code up-pitch}</li>
 *     <li>sneak (shift) alone - straight down, at {@code down-pitch}; the elytra dives and trades
 *         height for speed the way a vanilla dive does</li>
 *     <li>W / A / S / D - the body points at the current camera view, or 90 degrees left / right,
 *         or 180 degrees behind it</li>
 *     <li>space + a direction - diagonally up in that direction</li>
 *     <li>sneak + a direction - down in that direction</li>
 *     <li>space + sneak - the two cancel out to level flight</li>
 *     <li>nothing held - the last rotation is held (locked)</li>
 * </ul>
 *
 * <p>Space and sneak together are read as "no vertical input", so adding sneak to a climb brings
 * the body back to level rather than fighting it.
 *
 * <p>The camera is NOT moved by any of this. The mouse keeps control of the view, and the view
 * is what the keys are measured against, so "forward" always means "the way I am looking".
 *
 * <h2>Why the body rotation is written on the client</h2>
 * A movement packet carries the player's rotation, and the server predicts the resulting
 * velocity with it. The client's own elytra physics ({@code LivingEntity#travelElytra}) also
 * reads the player's rotation. So if only the packet carried the flight rotation while the
 * body kept the mouse rotation, the client would fly one rotation and the server would predict
 * another - and the mismatch is exactly what a Grim Simulation check reports. This module
 * therefore writes the flight rotation to the player entity, once per tick, before the player
 * tick and before movement packets are built: physics, packet and server prediction then all use
 * one and the same rotation. Only the rendered camera is different, and rendering is local - it
 * is never sent anywhere.
 */
public class ElytraFlyPlus extends Module implements FreeView {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMovement = settings.createGroup("Movement");
    private final SettingGroup sgTakeoff = settings.createGroup("Takeoff");

    // ---- General ----

    private final Setting<Boolean> lockView = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-view")
        .description("Keys steer the server-side rotation while gliding. The camera stays free; only the rotation that is sent is locked.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> levelPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("level-pitch")
        .description("Flight pitch used when only a direction key is held. 0 is level flight.")
        .defaultValue(0.0)
        .min(-90.0)
        .max(90.0)
        .sliderRange(-90.0, 90.0)
        .visible(lockView::get)
        .build()
    );

    private final Setting<Double> upPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("up-pitch")
        .description("Flight pitch used when space is held with no direction. Vanilla elytra climbs best around -60 to -85; exactly -90 stalls and drops, because the climb term is proportional to the horizontal part of the look vector.")
        .defaultValue(-70.0)
        .min(-90.0)
        .max(90.0)
        .sliderRange(-90.0, 90.0)
        .visible(lockView::get)
        .build()
    );

    private final Setting<Double> diagonalPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("diagonal-pitch")
        .description("Flight pitch used when space and a direction are held together.")
        .defaultValue(-45.0)
        .min(-90.0)
        .max(90.0)
        .sliderRange(-90.0, 90.0)
        .visible(lockView::get)
        .build()
    );

    private final Setting<Double> downPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("down-pitch")
        .description("Flight pitch used when sneak (shift) is held, with or without a direction. Positive looks down, so the elytra dives. Diving also trades height for speed, the same way a vanilla elytra does. Holding space and sneak together cancels out to level flight.")
        .defaultValue(45.0)
        .min(-90.0)
        .max(90.0)
        .sliderRange(-90.0, 90.0)
        .visible(lockView::get)
        .build()
    );

    private final Setting<Double> rotationStep = sgGeneral.add(new DoubleSetting.Builder()
        .name("rotation-step")
        .description("How many degrees per tick the locked rotation may move toward its target. 0 turns instantly; raise it if a server dislikes large one-tick turns.")
        .defaultValue(0.0)
        .min(0.0)
        .sliderRange(0.0, 90.0)
        .visible(lockView::get)
        .build()
    );

    private final Setting<Boolean> restoreView = sgGeneral.add(new BoolSetting.Builder()
        .name("restore-view")
        .description("When the lock ends, hand the camera view back to the body so the view does not jump.")
        .defaultValue(true)
        .visible(lockView::get)
        .build()
    );

    private final Setting<Boolean> fakeFirework = sgGeneral.add(new BoolSetting.Builder()
        .name("fake-firework")
        .description("Do not consume fireworks, fake the boost effect.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> fireworkLevel = sgGeneral.add(new IntSetting.Builder()
        .name("firework-level")
        .description("Firework level (1-3).")
        .defaultValue(1)
        .range(1, 3)
        .sliderRange(1, 3)
        .build()
    );

    private final Setting<Double> boostCooldown = sgGeneral.add(new DoubleSetting.Builder()
        .name("boost-cooldown")
        .description("Seconds to wait after a boost before boosting again.")
        .defaultValue(1.0)
        .min(0.0)
        .sliderRange(0.0, 5.0)
        .build()
    );

    private final Setting<Boolean> holdToBoost = sgGeneral.add(new BoolSetting.Builder()
        .name("hold-to-boost")
        .description("Hold a trigger key to keep re-firing rockets on the cooldown. Off: the chain runs on its own for the rocket duration and then the flight state resets.")
        .defaultValue(true)
        .build()
    );

    private final Setting<BoostTrigger> boostTrigger = sgGeneral.add(new EnumSetting.Builder<BoostTrigger>()
        .name("boost-trigger")
        .description("Which keys count as 'keep boosting'. Jump is the vanilla habit; Direction lets WASD hold the rocket chain while steering.")
        .defaultValue(BoostTrigger.Both)
        .visible(holdToBoost::get)
        .build()
    );

    private final Setting<Boolean> vanillaOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("vanilla-only")
        .description("Master kill switch: forces every synthetic velocity path off regardless of the individual switches. Keep on for Grim/simulation servers.")
        .defaultValue(true)
        .build()
    );

    // ---- Movement ----

    private final Setting<Boolean> horizontalMove = sgMovement.add(new BoolSetting.Builder()
        .name("horizontal-move")
        .description("WASD horizontal control. NOT vanilla - injects velocity every tick and WILL flag Grim Simulation. Leave off unless the server has no prediction check.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> horizontalSpeed = sgMovement.add(new DoubleSetting.Builder()
        .name("horizontal-speed")
        .description("Horizontal movement speed added per tick.")
        .defaultValue(0.08)
        .min(0.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    private final Setting<Boolean> diagonalMove = sgMovement.add(new BoolSetting.Builder()
        .name("diagonal-move")
        .description("Normalize diagonal WASD input. Only meaningful when horizontal-move is on, which itself flags Grim Simulation.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> verticalMove = sgMovement.add(new BoolSetting.Builder()
        .name("vertical-move")
        .description("Jump ascends, sneak descends as an injected velocity. NOT vanilla - injects velocity every tick and WILL flag Grim Simulation. Use lock-view instead: space climbs, sneak dives, and both are real rotation the physics and the server agree on.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> verticalSpeed = sgMovement.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Vertical movement speed added per tick.")
        .defaultValue(0.5)
        .min(0.0)
        .sliderRange(0.0, 2.0)
        .visible(verticalMove::get)
        .build()
    );

    // ---- Takeoff ----

    private final Setting<Boolean> autoTakeoff = sgTakeoff.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Swap a hotbar elytra into the chest slot when you press jump in the air. Vanilla already deploys an equipped elytra by itself, so leave this off unless the elytra lives in the hotbar.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> swapElytra = sgTakeoff.add(new BoolSetting.Builder()
        .name("swap-elytra")
        .description("If the elytra is in the hotbar, swap it into the chest slot. If already equipped, nothing is swapped.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> restoreChest = sgTakeoff.add(new BoolSetting.Builder()
        .name("restore-chest")
        .description("Swap the original chest item back when flight ends. Disabled: the elytra stays equipped.")
        .defaultValue(false)
        .build()
    );

    private static final long TAKEOFF_GRACE_MS = 500;

    private boolean isFlying = false;
    private long flightStartTime = 0;
    private long lastBoostTime = 0;
    private int savedElytraSlot = -1;
    private boolean didSwap = false;

    /**
     * Hotbar slot to put back after a rocket was fired, or -1.
     *
     * <p>Deliberately restored at the head of the <em>next</em> tick rather than straight after
     * the use-item packet. Grim keeps a per-tick "right clicking" flag and its {@code PacketOrderE}
     * check flags an {@code UpdateSelectedSlot} that arrives in the same tick as a right click -
     * which is exactly what swapping back immediately after firing looks like. One tick later the
     * flag has been cleared by the tick's own movement packet.
     */
    private int restoreSlot = -1;

    /** Player tick on which we last injected synthetic velocity, so we never do it twice in one tick. */
    private int lastInjectTick = -1;

    /** Jump key state last tick, so auto-takeoff can require a fresh press rather than a held key. */
    private boolean lastJumpPressed;

    // ---- Server-side rotation state ----

    /** True while the module owns the body rotation and the camera renders the free view. */
    private boolean viewLockActive = false;

    /** The camera view - what the player sees and what the keys are measured against. */
    private float viewYaw;
    private float viewPitch;

    /** The view at the end of the previous tick, used to interpolate the camera over a frame. */
    private float prevViewYaw;
    private float prevViewPitch;

    /** The rotation that is actually written to the player and sent to the server. */
    private float lockedYaw;
    private float lockedPitch;

    public ElytraFlyPlus() {
        super(HongShiKaiKai.CATEGORY, "elytrafly+", "Server-side locked elytra flight rotation, steered with jump and WASD, with a free client camera.");
    }

    @Override
    public void onActivate() {
        isFlying = false;
        savedElytraSlot = -1;
        didSwap = false;
        lastBoostTime = 0;
        lastInjectTick = -1;
        viewLockActive = false;
        restoreSlot = -1;
        lastJumpPressed = false;
    }

    @Override
    public void onDeactivate() {
        restoreHotbarSlot();
        releaseViewLock();
        if (isFlying) endFlight();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        restoreHotbarSlot();

        if (mc.player == null || mc.world == null) return;

        boolean gliding = mc.player.isGliding();
        boolean jumpPressed = mc.options.jumpKey.isPressed();
        boolean jumpEdge = jumpPressed && !lastJumpPressed;
        lastJumpPressed = jumpPressed;

        if (!isFlying && gliding) {
            isFlying = true;
            flightStartTime = System.currentTimeMillis();
            lastBoostTime = 0;
        }

        if (isFlying) {
            long elapsed = System.currentTimeMillis() - flightStartTime;

            // Short grace window after takeoff: the client flag may not have flipped yet.
            if (elapsed > TAKEOFF_GRACE_MS && (!gliding || mc.player.isOnGround())) {
                endFlight();
            } else if (!holdToBoost.get() && elapsed > getFireworkDuration()) {
                endFlight();
            }
        } else if (autoTakeoff.get() && jumpEdge && !gliding && !mc.player.isOnGround()) {
            // Vanilla already deploys an equipped elytra on its own. Only step in when the elytra
            // still sits in the hotbar, and only under the same precondition - never on the ground,
            // where the server rejects the command and the glide state desyncs.
            //
            // The jump key has to be a fresh press, not a key that is still being held from the
            // ground jump. The client only sends PlayerInput when the input changes, so a held key
            // leaves Grim's knownInput.jump() true when the command arrives - and ElytraB reads
            // exactly that as "started gliding without releasing jump" and sets back.
            if (tryStartFlight()) {
                isFlying = true;
                flightStartTime = System.currentTimeMillis();
                lastBoostTime = 0;
            }
        }

        updateViewLock();
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null) return;
        if (!mc.player.isGliding()) return;
        if (!isFlying && !autoTakeoff.get()) return;

        if (shouldBoost()) {
            applyFireworkBoost(event);
            lastBoostTime = System.currentTimeMillis();
        }

        // vanilla-only hard-disables every synthetic velocity path, no matter what the
        // individual switches say. Meteor persists only settings that differ from their
        // default, so a switch toggled once in the GUI survives a default change in code -
        // this master switch is the one thing a stale config cannot re-enable.
        if (vanillaOnly.get()) return;

        // PlayerMoveEvent is posted from the HEAD of Entity.move(), and move() runs more
        // than once per tick (SELF / PLAYER / PISTON). Injecting on every post multiplies
        // the same delta 2-3x inside a single tick; inject at most once per player tick.
        if (mc.player.age == lastInjectTick) return;
        lastInjectTick = mc.player.age;

        if (horizontalMove.get()) applyDirectionControl(event);
        if (verticalMove.get()) applyVerticalControl(event);
    }

    /**
     * Build the locked flight rotation from the current view and the keys, then write it to the
     * player entity.
     *
     * Runs from TickEvent.Pre, which is posted at the head of MinecraftClient.tick() - before
     * the player tick that computes movement and before sendMovementPackets() builds the packet.
     * The rotation written here is therefore the rotation the elytra physics use for this tick and
     * the rotation the server receives for this tick: one value, no desync.
     */
    private void updateViewLock() {
        if (!lockView.get() || mc.player == null || !mc.player.isGliding()) {
            releaseViewLock();
            return;
        }

        if (!viewLockActive) beginViewLock();

        prevViewYaw = viewYaw;
        prevViewPitch = viewPitch;

        double forwardInput = (mc.options.forwardKey.isPressed() ? 1 : 0) - (mc.options.backKey.isPressed() ? 1 : 0);
        double strafeInput = (mc.options.rightKey.isPressed() ? 1 : 0) - (mc.options.leftKey.isPressed() ? 1 : 0);
        boolean up = mc.options.jumpKey.isPressed();
        boolean down = mc.options.sneakKey.isPressed();
        boolean hasDirection = forwardInput != 0 || strafeInput != 0;

        float targetYaw = lockedYaw;
        float targetPitch = lockedPitch;
        boolean hasTarget = false;

        if (hasDirection) {
            // Measure the key direction against the view: forward is where the camera looks,
            // left/right are 90 degrees off it, back is 180. A diagonal lands between the two.
            targetYaw = viewYaw + (float) Math.toDegrees(Math.atan2(strafeInput, forwardInput));
            targetPitch = verticalPitch(up, down, diagonalPitch.get());
            hasTarget = true;
        } else if (up || down) {
            targetYaw = viewYaw;
            targetPitch = verticalPitch(up, down, upPitch.get());
            hasTarget = true;
        }
        // No key held: hold the last rotation. That is the lock.

        if (hasTarget) {
            double step = rotationStep.get();
            if (step <= 0.0) {
                lockedYaw = targetYaw;
                lockedPitch = targetPitch;
            } else {
                lockedYaw = (float) (lockedYaw + MathHelper.clamp(MathHelper.wrapDegrees(targetYaw - lockedYaw), -step, step));
                lockedPitch = (float) (lockedPitch + MathHelper.clamp(targetPitch - lockedPitch, -step, step));
            }
        }

        // The yaw is deliberately NOT normalised into -180..180. Vanilla's Entity.setYaw stores
        // whatever it is given (only setPitch wraps), so a vanilla client that keeps turning sends
        // 179, 181, 183 and so on. Snapping it back to -179 instead is exactly the modulo-360
        // signature Grim's AimModulo360 looks for: a yaw delta over 320 degrees followed by a
        // normal one. The pitch does have to stay clamped, because vanilla clamps it too.
        lockedPitch = MathHelper.clamp(lockedPitch, -90.0f, 90.0f);

        mc.player.setYaw(lockedYaw);
        mc.player.setPitch(lockedPitch);

        // Keep the entity's own interpolation fields in step with the value we just wrote, so
        // anything that reads the interpolated rotation (the third-person model, getRotationVec)
        // sees the same direction the physics and the packet use.
        VersionCompat.setPreviousRotation(mc.player, lockedYaw, lockedPitch);
    }

    /**
     * The flight pitch the vertical keys ask for.
     *
     * <p>Space alone climbs and sneak alone dives; holding both cancels out to level flight. The
     * {@code climbPitch} argument is whichever of {@code up-pitch} / {@code diagonal-pitch} fits the
     * direction keys that are held. This is a rotation, not an injected velocity: the client really
     * points the body that way, so the elytra physics and the server prediction agree.
     */
    private float verticalPitch(boolean up, boolean down, double climbPitch) {
        if (up == down) return levelPitch.get().floatValue();
        return (float) (up ? climbPitch : downPitch.get().doubleValue());
    }

    private void beginViewLock() {
        viewLockActive = true;

        viewYaw = mc.player.getYaw();
        viewPitch = mc.player.getPitch();
        prevViewYaw = viewYaw;
        prevViewPitch = viewPitch;
        lockedYaw = viewYaw;
        lockedPitch = viewPitch;
    }

    private void releaseViewLock() {
        if (!viewLockActive) return;
        viewLockActive = false;

        // Hand the view back to the body so the camera does not jump when the lock ends. The yaw
        // goes back raw - see the note in updateViewLock about not normalising it.
        if (restoreView.get() && mc.player != null) {
            float yaw = viewYaw;
            float pitch = MathHelper.clamp(viewPitch, -90.0f, 90.0f);

            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);

            // The camera does not render the plain rotation: it renders Entity.getYaw(tickDelta)
            // and getPitch(tickDelta), which lerp from prevYaw/prevPitch. Those two still hold the
            // flight rotation this module has been writing every tick, so without this the first
            // tick after the lock ends renders at the flight pitch and only then slides across -
            // turning the module off mid-dive snaps the view down for a tick.
            VersionCompat.setPreviousRotation(mc.player, yaw, pitch);
        }
    }

    // ---- Called from the mixins ----

    /** Whether the camera should render the free view instead of the body rotation. */
    public boolean isViewLockActive() {
        return viewLockActive;
    }

    /**
     * Mouse look, while the lock owns the body rotation. The delta is applied to the view only;
     * the body rotation is written once per tick, so the value that goes into the movement packet
     * is never a half-updated one.
     */
    public void applyLookDelta(double deltaYaw, double deltaPitch) {
        if (!viewLockActive) return;

        // Vanilla shifts prevYaw/prevPitch by the same delta, so the view answers the mouse
        // immediately instead of easing toward it. Mirror that here.
        viewYaw += (float) deltaYaw;
        prevViewYaw += (float) deltaYaw;

        viewPitch = MathHelper.clamp(viewPitch + (float) deltaPitch, -90.0f, 90.0f);
        prevViewPitch = MathHelper.clamp(prevViewPitch + (float) deltaPitch, -90.0f, 90.0f);
    }

    public float getCameraYaw() {
        return viewYaw;
    }

    public float getCameraPitch() {
        return viewPitch;
    }

    public float getPrevCameraYaw() {
        return prevViewYaw;
    }

    public float getPrevCameraPitch() {
        return prevViewPitch;
    }

    // ---- Flight ----

    private boolean shouldBoost() {
        if (!isFlying) return false;
        if (holdToBoost.get() && !isBoostKeyHeld()) return false;
        return System.currentTimeMillis() - lastBoostTime >= getBoostCooldownMs();
    }

    /**
     * Whether one of the keys selected by boost-trigger is currently held.
     *
     * The old code only ever looked at the jump key, so holding WASD to steer never re-fired a
     * rocket - the module would glide until it stalled, which is exactly the "WASD does not
     * trigger fireworks" report.
     */
    private boolean isBoostKeyHeld() {
        boolean jump = mc.options.jumpKey.isPressed();
        boolean direction = mc.options.forwardKey.isPressed()
            || mc.options.backKey.isPressed()
            || mc.options.leftKey.isPressed()
            || mc.options.rightKey.isPressed();

        return switch (boostTrigger.get()) {
            case Jump -> jump;
            case Direction -> direction;
            case Both -> jump || direction;
        };
    }

    /** Which key set keeps the firework chain running. */
    public enum BoostTrigger {
        /** Only the jump key re-boosts, like vanilla rocket flight. */
        Jump,
        /** Only WASD re-boosts, so steering alone sustains the chain. */
        Direction,
        /** Either one re-boosts. */
        Both
    }

    private boolean tryStartFlight() {
        // Already wearing an elytra: do nothing. Vanilla's own ClientPlayerEntity
        // .tickMovement() already sends START_FALL_FLYING when jump is pressed in the air,
        // so a second packet from here is a duplicate the server never expects.
        if (isElytraEquipped()) return false;

        if (!swapElytra.get()) {
            error("No elytra equipped and swap-elytra is off.");
            return false;
        }

        int elytraSlot = findElytraInHotbar();
        if (elytraSlot == -1) {
            error("No elytra in hotbar or chest slot.");
            return false;
        }

        if (!fakeFirework.get() && !hasFireworkInHotbar()) {
            error("No fireworks in hotbar.");
            return false;
        }

        savedElytraSlot = elytraSlot;

        // Move the elytra into the chest slot. The old chest item lands in elytraSlot.
        InvUtils.move().from(elytraSlot).toArmor(2);
        didSwap = true;

        deployElytra();
        return true;
    }

    private void deployElytra() {
        mc.getNetworkHandler().sendPacket(
            new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING)
        );
    }

    /**
     * Puts the hotbar slot back after a rocket was fired. Runs at the head of the tick, before
     * anything else can send a packet, so the swap cannot land in the same tick as a right click.
     * The client-side selection itself was already put back when the rocket was used.
     */
    private void restoreHotbarSlot() {
        int slot = restoreSlot;
        restoreSlot = -1;

        if (slot < 0 || mc.player == null) return;

        // InvUtils.swap writes the slot and syncs it, which sends the UpdateSelectedSlot packet.
        InvUtils.swap(slot, false);
    }

    private void endFlight() {
        isFlying = false;
        releaseViewLock();

        // Default (restore-chest = false): intentionally keep the elytra on.
        if (restoreChest.get() && didSwap && savedElytraSlot != -1) {
            InvUtils.move().fromArmor(2).toHotbar(savedElytraSlot);
        }

        savedElytraSlot = -1;
        didSwap = false;
    }

    private boolean isElytraEquipped() {
        return mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
    }

    /**
     * Jump ascends, sneak descends, as a synthetic velocity injection. Only reachable with
     * vanilla-only off; the rotation lock is the vanilla-safe way to climb.
     */
    private void applyVerticalControl(PlayerMoveEvent event) {
        double speed = verticalSpeed.get();

        boolean up = mc.options.jumpKey.isPressed();
        boolean down = mc.options.sneakKey.isPressed();

        double dy = (up ? speed : 0.0) - (down ? speed : 0.0);
        if (dy == 0.0) return;

        setMovement(event, event.movement.x, event.movement.y + dy, event.movement.z);
    }

    private void applyFireworkBoost(PlayerMoveEvent event) {
        double boost = switch (fireworkLevel.get()) {
            case 1 -> 0.7;
            case 2 -> 0.9;
            case 3 -> 1.1;
            default -> 0.7;
        };

        if (!fakeFirework.get()) {
            // Real rocket: the server applies the boost itself from the use-item packet.
            // Adding synthetic velocity on top of a real rocket is precisely the delta
            // Grim's Simulation predicts against, so do not touch movement here.
            int fireworkSlot = findFireworkInHotbar();
            if (fireworkSlot != -1) {
                // InvUtils.swap sends the matching UpdateSelectedSlot packet. Assigning
                // selectedSlot directly only changes the client, so the server would see
                // a use-item packet for an item it does not think you are holding.
                int previous = VersionCompat.getSelectedSlot(mc.player.getInventory());
                InvUtils.swap(fireworkSlot, false);
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                mc.player.swingHand(Hand.MAIN_HAND);

                if (previous != fireworkSlot) {
                    // Hand the HUD its selection back right away, but without the packet: an
                    // UpdateSelectedSlot in the same tick as the use-item packet is exactly what
                    // Grim's PacketOrderE flags. The packet goes out at the head of the next tick,
                    // where the tick's own movement packet has already cleared that flag.
                    VersionCompat.setSelectedSlot(mc.player.getInventory(), previous);
                    restoreSlot = previous;
                }
            }
            return;
        }

        // fake-firework: nothing is consumed, so the boost has to be synthesised locally.
        // This is a non-vanilla velocity injection and will flag Grim Simulation.
        if (vanillaOnly.get()) return;

        Vec3d look = mc.player.getRotationVec(1.0f);
        setMovement(
            event,
            event.movement.x + look.x * boost,
            event.movement.y + look.y * boost,
            event.movement.z + look.z * boost
        );
    }

    private void applyDirectionControl(PlayerMoveEvent event) {
        double speed = horizontalSpeed.get();

        boolean forward = mc.options.forwardKey.isPressed();
        boolean back = mc.options.backKey.isPressed();
        boolean left = mc.options.leftKey.isPressed();
        boolean right = mc.options.rightKey.isPressed();

        double forwardInput = (forward ? 1 : 0) - (back ? 1 : 0);
        double strafeInput = (right ? 1 : 0) - (left ? 1 : 0);

        if (forwardInput == 0 && strafeInput == 0) return;

        // Normalize the diagonal so W+A is not faster than W.
        if (diagonalMove.get() && forwardInput != 0 && strafeInput != 0) {
            double inv = 1.0 / Math.sqrt(2.0);
            forwardInput *= inv;
            strafeInput *= inv;
        }

        double yaw = Math.toRadians(mc.player.getYaw());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);

        double dx = (-sin * forwardInput + cos * strafeInput) * speed;
        double dz = (cos * forwardInput + sin * strafeInput) * speed;

        setMovement(event, event.movement.x + dx, event.movement.y, event.movement.z + dz);
    }

    /**
     * Mutate the movement vector in place.
     *
     * Meteor posts PlayerMoveEvent at the HEAD of Entity.move() and never reads the field
     * back, so assigning `event.movement = new Vec3d(...)` is a silent no-op. The Vec3d
     * instance handed to the event is the one the movement code uses, so it has to be
     * modified through IVec3d - the same approach Meteor's own ElytraFly takes.
     */
    private void setMovement(PlayerMoveEvent event, double x, double y, double z) {
        ((IVec3d) event.movement).meteor$set(x, y, z);
    }

    private long getBoostCooldownMs() {
        return (long) (boostCooldown.get() * 1000.0);
    }

    private long getFireworkDuration() {
        return switch (fireworkLevel.get()) {
            case 1 -> 1500;
            case 2 -> 2000;
            case 3 -> 2500;
            default -> 1500;
        };
    }

    private int findElytraInHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(Items.ELYTRA)) return i;
        }
        return -1;
    }

    private boolean hasFireworkInHotbar() {
        return findFireworkInHotbar() != -1;
    }

    private int findFireworkInHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(Items.FIREWORK_ROCKET)) return i;
        }
        return -1;
    }
}
