package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.FreeView;
import com.hongshikaikai.addon.utils.VersionCompat;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Attacks whatever it is aiming at, one target per tick, and only when the attack would survive the
 * checks Grim actually runs on it.
 *
 * <h2>The attack is one tick behind the aim, on purpose</h2>
 * An attack packet carries no rotation. The server resolves the hit with the rotation and position
 * of the <em>last movement packet it received</em>, so aiming and attacking in the same tick means
 * the server checks the hit against the aim from <em>before</em> the aim existed.
 *
 * <p>So the module splits it: it writes the aim to the player entity at {@link TickEvent.Pre} - so
 * this tick's physics and this tick's movement packet carry the same rotation - and attacks on the
 * <em>next</em> tick with the rotation that packet carried. Grim's reach check then sees exactly the
 * aim the module aimed with.
 *
 * <h2>What is verified before anything is sent</h2>
 * The rotation a movement packet carried is captured at {@link SendMovementPacketsEvent.Post}, where
 * it is exactly what went out. Before attacking, the module casts that rotation from the eye against
 * the target's hitbox and requires it to land inside {@code range} - which is the same test Grim's
 * {@code Reach} runs, at the same distance, from the same position. A target that has moved out of
 * the way simply is not attacked that tick instead of being attacked and flagged.
 *
 * <p>Because the aim is written to the entity rather than spoofed into the packet, the walking
 * prediction sees it too and nothing desyncs; {@link #aim} can be turned off entirely, which turns
 * the module into a plain triggerbot that only swings when you are already looking at the target.
 *
 * <h2>The rules it follows</h2>
 * <ul>
 *     <li>{@code Reach} and the hitbox half of it - the ray from the eye must land on the target's
 *         box, and inside the entity interaction range. {@code range} is capped at the vanilla 3.0
 *         for that reason.</li>
 *     <li>{@code PacketOrderB} - one attack per tick, always followed by a swing in the same tick,
 *         in the vanilla attack-then-swing order.</li>
 *     <li>{@code MultiInteractA}/{@code B} - never two entities in one tick, so the "multiple
 *         entities" and "multiple target positions" checks have nothing to see.</li>
 *     <li>Attack cooldown - the 1.9 swing timer is respected, which is also what makes the damage
 *         real.</li>
 * </ul>
 *
 * <h2>Critting by jumping, through the jump key</h2>
 * A critical hit is decided by the server from the state of the <em>last movement packet</em>: the
 * player must be falling, off the ground and not sprinting. So a crit is not something this module
 * can decide - it has to make the state true and then swing, which is what {@code crit} does. The
 * jump is a real press of {@code jumpKey}, so the jump flag travels in the vanilla input packet and
 * Grim's prediction simulates the same arc; the sprint is stopped with the sprint flag, which is the
 * one thing letting go of the key cannot do. Both are things a player does by hand to set up a crit
 * (a "w-tap"), and neither is something Grim checks: the sprint checks are all about <em>starting</em>
 * a sprint, and there is no crit check at all.
 *
 * <h2>Circling the target with the strafe keys</h2>
 * {@code orbit} holds a strafe key and lets vanilla do the walking. The strafe direction is read off
 * the body yaw, the body yaw is the aim, and the aim is what the movement packet carries - so the
 * circle the server sees is the circle the client walked, and Grim's prediction reproduces it from
 * the input packet instead of having to take the module's word for it. Writing the velocity directly
 * would be a movement the server never predicted, which is the whole thing this addon avoids.
 *
 * <p>{@code orbit-circle} draws the distance the keys are holding as a ring around the target. It is
 * a read-out of the movement and never an input to it: nothing in {@link #driveOrbit} consults it.
 *
 * <h2>The hit effect</h2>
 * {@code attack-effect} draws a shockwave ring, a flash on the target's hitbox and a burst of
 * particles on every hit. It is decoration and nothing else - a render plus a few particles in this
 * client's own particle manager - so no part of it is sent anywhere and Grim has nothing to look at.
 * The crit colour is not a guess either: a crit is decided from the state of the last movement
 * packet, which is exactly what {@link #critConditions()} reads when the swing goes out.
 */
public class KillAuraPlus extends Module implements FreeView {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargets = settings.createGroup("Targets");
    private final SettingGroup sgCrit = settings.createGroup("Crit");
    private final SettingGroup sgOrbit = settings.createGroup("Orbit");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> aim = sgGeneral.add(new BoolSetting.Builder()
        .name("aim")
        .description("Aim at the target. The aim only takes the body on the ticks that feed a swing - the rest of the time your own view is written back, so walking stays yours. Off: the module never touches your rotation and only swings when you are already looking at the target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> rotationStep = sgGeneral.add(new DoubleSetting.Builder()
        .name("rotation-step")
        .description("Max degrees per tick the aim may turn toward a target. 0 snaps instantly, which lands the most hits; a small value looks smoother but lags a moving target and will simply skip more attacks.")
        .defaultValue(0.0)
        .min(0.0)
        .sliderRange(0.0, 90.0)
        .visible(aim::get)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far the hitbox may be from the eye. Vanilla's entity interaction range is 3.0 and Grim's Reach measures against exactly that, so this is capped there.")
        .defaultValue(3.0)
        .min(1.0)
        .max(3.0)
        .sliderRange(1.0, 3.0)
        .build()
    );

    private final Setting<Boolean> cooldown = sgGeneral.add(new BoolSetting.Builder()
        .name("cooldown")
        .description("Only attack when the 1.9 attack cooldown is charged. Off spams attacks that do less damage, which is neither useful nor subtle.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-use")
        .description("Do not attack while using an item.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> restoreView = sgGeneral.add(new BoolSetting.Builder()
        .name("restore-view")
        .description("Hand the view back to the body when there is nothing left to aim at, so the camera does not jump.")
        .defaultValue(true)
        .visible(aim::get)
        .build()
    );

    private final Setting<Boolean> crit = sgCrit.add(new BoolSetting.Builder()
        .name("crit")
        .description("Jump before swinging, so the hit lands as a critical. The swing is held until the movement packet the server resolves it against says the player is falling.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> critTimeout = sgCrit.add(new IntSetting.Builder()
        .name("crit-timeout")
        .description("How many ticks a swing may be held waiting for the fall before it goes out anyway. Covers a jump that a low ceiling or water will not allow.")
        .defaultValue(20)
        .min(2)
        .max(60)
        .sliderRange(2, 60)
        .visible(crit::get)
        .build()
    );

    private final Setting<Boolean> orbit = sgOrbit.add(new BoolSetting.Builder()
        .name("orbit")
        .description("Strafe around the target instead of standing in front of it. Drives the strafe keys and keeps the aim on the target, so the circle is the one the server predicts from the input packet.")
        .defaultValue(false)
        .build()
    );

    private final Setting<OrbitDirection> orbitDirection = sgOrbit.add(new EnumSetting.Builder<OrbitDirection>()
        .name("orbit-direction")
        .description("Which way to circle. Random picks a side each time a new target is picked up.")
        .defaultValue(OrbitDirection.Clockwise)
        .visible(orbit::get)
        .build()
    );

    private final Setting<Double> orbitDistance = sgOrbit.add(new DoubleSetting.Builder()
        .name("orbit-distance")
        .description("Distance from the target's centre to hold while circling, in blocks. Keep it inside range, or the swing that comes with the circle will not reach. The low end is for hugging: below about 0.6 the two hitboxes are already touching, so the circle becomes a slide around the target's face.")
        .defaultValue(2.5)
        .min(0.1)
        .max(3.0)
        .sliderRange(0.1, 3.0)
        .visible(orbit::get)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Master switch for everything below.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> circle = sgRender.add(new BoolSetting.Builder()
        .name("circle")
        .description("Draw the attack range as a ring around the player, so the reach is something you can see rather than guess at.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> outline = sgRender.add(new BoolSetting.Builder()
        .name("outline")
        .description("Outline every entity inside that range that the module would actually consider a target, using the same filters the attacks use. The one it is currently working on is drawn filled and brighter.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> circleColor = sgRender.add(new ColorSetting.Builder()
        .name("circle-color")
        .description("Colour of the range ring.")
        .defaultValue(new SettingColor(255, 90, 90, 130))
        .visible(() -> render.get() && circle.get())
        .build()
    );

    private final Setting<Integer> circleSegments = sgRender.add(new IntSetting.Builder()
        .name("circle-segments")
        .description("Segments the ring is built from. Higher is rounder and costs one band each.")
        .defaultValue(90)
        .min(12)
        .max(180)
        .sliderRange(12, 180)
        .visible(() -> render.get() && circle.get())
        .build()
    );

    private final Setting<Boolean> circleAtEyes = sgRender.add(new BoolSetting.Builder()
        .name("circle-at-eyes")
        .description("Draw the ring at eye height instead of at your feet. The range is measured from the eye, so this is the ring that matches the check; the feet one is just easier to read while walking.")
        .defaultValue(false)
        .visible(() -> render.get() && circle.get())
        .build()
    );

    private final Setting<Double> circleWidth = sgRender.add(new DoubleSetting.Builder()
        .name("circle-width")
        .description("Radial thickness of the ring, in blocks. This is what makes it a band instead of a one-pixel line.")
        .defaultValue(0.08)
        .min(0.01)
        .max(0.5)
        .sliderRange(0.01, 0.5)
        .visible(() -> render.get() && circle.get())
        .build()
    );

    private final Setting<Double> circleHeight = sgRender.add(new DoubleSetting.Builder()
        .name("circle-height")
        .description("Height of the ring's outer wall, in blocks. A ring lying flat on the ground is almost edge-on when you are looking along it, so this is the part that keeps it readable from eye level. 0 makes it flat.")
        .defaultValue(0.3)
        .min(0.0)
        .max(1.0)
        .sliderRange(0.0, 1.0)
        .visible(() -> render.get() && circle.get())
        .build()
    );

    private final Setting<SettingColor> outlineColor = sgRender.add(new ColorSetting.Builder()
        .name("outline-color")
        .description("Outline colour for the entities in range.")
        .defaultValue(new SettingColor(255, 220, 120, 200))
        .visible(() -> render.get() && outline.get())
        .build()
    );

    private final Setting<SettingColor> targetColor = sgRender.add(new ColorSetting.Builder()
        .name("target-color")
        .description("Colour for the entity the module is currently working on. Outlined with this, filled with a quarter of its alpha.")
        .defaultValue(new SettingColor(255, 90, 90, 220))
        .visible(() -> render.get() && outline.get())
        .build()
    );

    private final Setting<Boolean> orbitCircle = sgRender.add(new BoolSetting.Builder()
        .name("orbit-circle")
        .description("Draw the orbit distance as a ring around the target being circled, so the circle the strafe keys are trying to walk is something you can see instead of guess at.")
        .defaultValue(true)
        .visible(() -> render.get() && orbit.get())
        .build()
    );

    private final Setting<SettingColor> orbitCircleColor = sgRender.add(new ColorSetting.Builder()
        .name("orbit-circle-color")
        .description("Colour of the orbit ring.")
        .defaultValue(new SettingColor(90, 200, 255, 150))
        .visible(() -> render.get() && orbit.get() && orbitCircle.get())
        .build()
    );

    private final Setting<Integer> orbitCircleSegments = sgRender.add(new IntSetting.Builder()
        .name("orbit-circle-segments")
        .description("Segments the orbit ring is built from. The radius is smaller than the range ring's, so fewer of them read as round.")
        .defaultValue(60)
        .min(12)
        .max(180)
        .sliderRange(12, 180)
        .visible(() -> render.get() && orbit.get() && orbitCircle.get())
        .build()
    );

    private final Setting<Double> orbitCircleWidth = sgRender.add(new DoubleSetting.Builder()
        .name("orbit-circle-width")
        .description("Radial thickness of the orbit ring, in blocks.")
        .defaultValue(0.08)
        .min(0.01)
        .max(0.5)
        .sliderRange(0.01, 0.5)
        .visible(() -> render.get() && orbit.get() && orbitCircle.get())
        .build()
    );

    private final Setting<Double> orbitCircleHeight = sgRender.add(new DoubleSetting.Builder()
        .name("orbit-circle-height")
        .description("Height of the orbit ring's outer wall, in blocks. 0 makes it flat.")
        .defaultValue(0.3)
        .min(0.0)
        .max(1.0)
        .sliderRange(0.0, 1.0)
        .visible(() -> render.get() && orbit.get() && orbitCircle.get())
        .build()
    );

    private final Setting<Boolean> attackEffect = sgRender.add(new BoolSetting.Builder()
        .name("attack-effect")
        .description("Draw an effect on every hit: a ring that expands from the target's feet and a flash around its hitbox. Every part of it is client-side, so nothing about it goes anywhere.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> attackEffectColor = sgRender.add(new ColorSetting.Builder()
        .name("attack-effect-color")
        .description("Colour of the effect for a normal hit.")
        .defaultValue(new SettingColor(255, 200, 90, 200))
        .visible(() -> render.get() && attackEffect.get())
        .build()
    );

    private final Setting<SettingColor> critEffectColor = sgRender.add(new ColorSetting.Builder()
        .name("crit-effect-color")
        .description("Colour of the effect when the hit is a critical. The module knows, because a crit is decided from exactly the state it swings on.")
        .defaultValue(new SettingColor(255, 80, 80, 220))
        .visible(() -> render.get() && attackEffect.get())
        .build()
    );

    private final Setting<Double> attackEffectDuration = sgRender.add(new DoubleSetting.Builder()
        .name("attack-effect-duration")
        .description("Seconds the effect lasts.")
        .defaultValue(0.4)
        .min(0.1)
        .max(2.0)
        .sliderRange(0.1, 2.0)
        .visible(() -> render.get() && attackEffect.get())
        .build()
    );

    private final Setting<Double> attackEffectRadius = sgRender.add(new DoubleSetting.Builder()
        .name("attack-effect-radius")
        .description("Radius in blocks the ring expands to before it fades out. Below the target's own hitbox it reads as a flash rather than a shockwave.")
        .defaultValue(1.1)
        .min(0.3)
        .max(3.0)
        .sliderRange(0.3, 3.0)
        .visible(() -> render.get() && attackEffect.get())
        .build()
    );

    private final Setting<Boolean> attackEffectBox = sgRender.add(new BoolSetting.Builder()
        .name("attack-effect-box")
        .description("Flash the target's hitbox along with the ring.")
        .defaultValue(true)
        .visible(() -> render.get() && attackEffect.get())
        .build()
    );

    private final Setting<Boolean> attackParticles = sgRender.add(new BoolSetting.Builder()
        .name("attack-particles")
        .description("Throw vanilla crit particles at the hit. They are spawned on this client only - no packet is sent for them.")
        .defaultValue(true)
        .visible(() -> render.get() && attackEffect.get())
        .build()
    );

    private final Setting<Boolean> players = sgTargets.add(new BoolSetting.Builder()
        .name("players")
        .description("Attack players. Friends are always skipped.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hostile = sgTargets.add(new BoolSetting.Builder()
        .name("hostile")
        .description("Attack hostile mobs.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> passive = sgTargets.add(new BoolSetting.Builder()
        .name("passive")
        .description("Attack passive mobs - animals, villagers and the like.")
        .defaultValue(false)
        .build()
    );

    /** The entity the current aim was written for, or null when there was nothing to aim at. */
    private LivingEntity aimedAt;

    /** True while the module owns the rotation, so the first aim of a fight is not eased. */
    private boolean aiming;

    /**
     * Ticks the swing that is ready to go has been held waiting for a crit fall. Zero whenever no
     * swing is being held, which is what makes the timeout count from the first held tick.
     */
    private int critHold;

    /** True while the sprint key is down because the module let go of it for a crit. */
    private boolean sprintReleasedByUs;

    /** True while the module has the jump key down for a crit jump, so a refused one is noticed. */
    private boolean critJumpPressed;

    /**
     * The keys the module is currently holding. Only these are ever released, so a key the player
     * pressed is left alone even when the module wants it up.
     */
    private final Set<KeyBinding> heldKeys = new HashSet<>();

    /** The target the current orbit side was picked for, so a new target can pick a new side. */
    private LivingEntity orbitTarget;

    /**
     * The side the orbit travels on: 1 for the left strafe key, which circles clockwise seen from
     * above, -1 for the right.
     */
    private int orbitSide = 1;

    /**
     * How far the orbit may be off its distance before it taps a key, in blocks. At an
     * {@code orbit-distance} below this every error is larger than the deadband, so the forward key
     * is simply held - which is what makes the in-your-face end of the slider press into the target
     * and slide around it instead of oscillating.
     */
    private static final double ORBIT_DEADBAND = 0.35;

    /** How many hit effects may be alive at once. */
    private static final int MAX_HIT_EFFECTS = 16;

    /** Radius the shockwave ring starts at, in blocks - just inside a player's own hitbox. */
    private static final double EFFECT_START_RADIUS = 0.15;

    /** The ring's radial thickness, its outer wall height, how round it is and how many sparks. */
    private static final double EFFECT_RING_WIDTH = 0.09;
    private static final double EFFECT_RING_HEIGHT = 0.35;
    private static final int EFFECT_SEGMENTS = 48;
    private static final int EFFECT_PARTICLES = 12;

    /** The entity the module is working on right now, tracked every tick for the outline. */
    private LivingEntity currentTarget;

    /** Scratch colour so the target's fill can be a dimmed copy of its outline without allocating. */
    private final Color scratch = new Color();

    /**
     * Hit effects still being drawn: one per attack, dropped when the effect ages out. Bounded by
     * {@link #MAX_HIT_EFFECTS}, so a long fight cannot pile them up.
     */
    private final List<HitEffect> hitEffects = new ArrayList<>();

    /**
     * Scratch colours for the effect's fade, handed out in order so the several colours one frame
     * needs do not share an instance. Module fields because this runs every frame.
     */
    private final Color[] effectScratch = {new Color(), new Color(), new Color(), new Color()};
    private int effectScratchIndex;

    /**
     * The rotation the last movement packet carried - the one the server will test the attack
     * against. Captured where the packet is built rather than where it is aimed, so a mouse move
     * that lands in between cannot make this disagree with what was actually sent.
     */
    private float sentYaw;
    private float sentPitch;

    // ---- Free view ----
    //
    // While a target is being aimed at, the body carries the aim (what the physics, the movement
    // packet and the server all use) and the camera renders the player's own view. Nothing about
    // the rotation that goes out changes; only what is drawn does.

    private boolean viewLockActive;
    private float viewYaw;
    private float viewPitch;
    private float prevViewYaw;
    private float prevViewPitch;

    public KillAuraPlus() {
        super(HongShiKaiKai.CATEGORY, "killaura+", "Attacks what it is aiming at, one target per tick, jumping for crits and circling the target on request, and only when the hit survives Grim's reach and hitbox checks.");
    }

    @Override
    public void onActivate() {
        aimedAt = null;
        currentTarget = null;
        aiming = false;
        viewLockActive = false;
        critHold = 0;
        critJumpPressed = false;
        orbitTarget = null;
        hitEffects.clear();
        releaseKeys();

        if (mc.player != null) {
            sentYaw = mc.player.getYaw();
            sentPitch = mc.player.getPitch();
        }
    }

    @Override
    public void onDeactivate() {
        aimedAt = null;
        currentTarget = null;
        aiming = false;
        critHold = 0;
        critJumpPressed = false;
        orbitTarget = null;
        hitEffects.clear();
        releaseKeys();
        releaseViewLock();
    }

    /**
     * Captures the rotation this movement packet carried.
     *
     * <p>Read at {@link EventPriority#HIGHEST} so it happens before anybody else's post-handler can
     * write a different rotation back - Meteor's own {@code Rotations} resets the entity rotation to
     * the pre-rotation at this same event, and it subscribes at start-up, ahead of any addon. What
     * is wanted here is the value that went out, not the value that was left behind.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        if (mc.player == null) return;

        sentYaw = mc.player.getYaw();
        sentPitch = mc.player.getPitch();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Somebody else owns the body rotation - a flight in progress, say. Fighting over it would
        // only make both of them wrong, so stand down.
        FreeView holder = FreeView.active();
        if (holder != null && holder != this) {
            aimedAt = null;
            currentTarget = null;
            aiming = false;
            critHold = 0;
            orbitTarget = null;
            releaseKeys();
            releaseViewLock();
            return;
        }

        // The crit jump is a one-tick tap of the jump key, the way a player taps it. Hand it back
        // before this tick builds the input it is about to send, so the module never holds a jump
        // down and never bunny-hops on the player's behalf.
        //
        // Whether last tick's tap was refused is read before the key goes back up: a jump that
        // never left the ground - a low ceiling, a slab, a ladder - is never going to turn into a
        // fall, so the swing it was holding up goes out now instead of waiting for the timeout.
        boolean jumpRefused = critJumpPressed && mc.player.isOnGround();
        drive(mc.options.jumpKey, false);
        critJumpPressed = false;

        // Spend the aim from last tick first, while the server still has it, and only if it still
        // lands on the target: anything else is an attack the server would resolve as a miss.
        LivingEntity previous = aimedAt;
        aimedAt = null;

        boolean swingReady = previous != null && canAttack() && reaches(previous, sentYaw, sentPitch);

        // The server decides a crit from the state the last movement packet carried, so an attack
        // sent now is resolved against the player as of the previous tick. Waiting for
        // critConditions() here is therefore waiting for the swing that will actually crit.
        boolean holdingForCrit = swingReady && crit.get() && critReachable() && !critConditions()
            && !jumpRefused && critHold < critTimeout.get();

        if (swingReady && !holdingForCrit) {
            attack(previous);
            critHold = 0;
            releaseSprint();
        } else if (holdingForCrit) {
            critHold++;

            if (canJumpForCrit()) {
                drive(mc.options.jumpKey, true);
                critJumpPressed = mc.options.jumpKey.isPressed();
            }

            stopSprintForCrit();
        } else {
            critHold = 0;
            releaseSprint();
        }

        LivingEntity target = findTarget();
        currentTarget = target;

        if (target == null) {
            critHold = 0;
            orbitTarget = null;
            releaseSprint();
            releaseOrbitKeys();
            aiming = false;
            releaseViewLock();
            return;
        }

        boolean orbiting = orbit.get() && canMove();
        if (orbiting) {
            driveOrbit(target);
        } else {
            orbitTarget = null;
            releaseOrbitKeys();
        }

        if (!aim.get()) {
            releaseViewLock();
            aimedAt = target;
            return;
        }

        // Only take the body when a swing is actually coming up. The aim has to be in a movement
        // packet one tick before the attack, and the attack can only happen once the 1.9 cooldown
        // is charged - so on every other tick there is nothing to aim for and the body is given
        // back to the player's own view. Walking follows the yaw that goes to the server, so this
        // is what keeps the aura from steering the player around for the whole fight.
        //
        // The orbit is the exception: a circle is drawn by the yaw the strafe keys are read against,
        // so while it is on the aim is what keeps the circle a circle and has to stay on the target.
        if (canAttack() || orbiting) {
            aimAt(target);
        } else {
            followView();
            aimedAt = null;
        }
    }

    /**
     * Puts the player's own view back on the body while the free view is held but nothing is being
     * aimed at.
     *
     * <p>While the view lock is active the mouse no longer writes the entity rotation - that is the
     * whole point of it - so unless something writes it, it would sit on the last aim. Writing the
     * view keeps the body, the movement packet and the client physics on the direction the player
     * is actually looking, which is what makes walking behave normally between swings.
     */
    private void followView() {
        if (!viewLockActive || mc.player == null) return;

        float yaw = viewYaw;
        float pitch = MathHelper.clamp(viewPitch, -90.0f, 90.0f);

        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
        VersionCompat.setPreviousRotation(mc.player, yaw, pitch);
    }

    private boolean canAttack() {
        if (mc.currentScreen != null) return false;
        if (mc.player.isDead() || mc.player.isSpectator()) return false;
        if (pauseOnUse.get() && mc.player.isUsingItem()) return false;

        return cooldownReady();
    }

    /**
     * Whether the module may drive movement at all. A screen is the one case where the keys it
     * holds would go nowhere but would still be reported as held, so the orbit stands down.
     */
    private boolean canMove() {
        return mc.currentScreen == null && !mc.player.isDead() && !mc.player.isSpectator();
    }

    private boolean cooldownReady() {
        return !cooldown.get() || mc.player.getAttackCooldownProgress(0.5f) >= 1.0f;
    }

    /**
     * Grim's own reach test, run locally: cast the rotation the server has from the eye and require
     * it to land on the target's hitbox inside {@code range}.
     *
     * <p>The box is used exactly as the client sees it - not expanded. Grim may add a small
     * uncertainty margin of its own, so being stricter than it can only cost attacks, never flags.
     */
    private boolean reaches(Entity target, float yaw, float pitch) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = Vec3d.fromPolar(pitch, yaw);
        Vec3d end = eye.add(look.multiply(range.get()));

        return target.getBoundingBox().raycast(eye, end).isPresent();
    }

    private void attack(LivingEntity target) {
        // Vanilla order, and both in the same tick: Grim's PacketOrderB wants a swing since the
        // last attack before this one, and flags an attack with no swing before the tick ends.
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);

        // Whether this hit is a critical is decided by the state of the last movement packet, which
        // is exactly the state critConditions() reads here - the same one the server judges the
        // swing by. So the effect can colour a crit correctly without guessing.
        addHitEffect(target, critConditions());
    }

    // ---- Attack effects ----

    /**
     * Starts the effect for one hit: a shockwave ring anchored where the target was standing, a
     * flash on its hitbox that follows it, and a burst of crit particles.
     *
     * <p>All of it is client-side - a render, a particle in this client's own particle manager -
     * so nothing here can become something the server or Grim has an opinion about.
     */
    private void addHitEffect(LivingEntity target, boolean critical) {
        if (!render.get() || !attackEffect.get()) return;

        if (hitEffects.size() >= MAX_HIT_EFFECTS) hitEffects.remove(0);

        Vec3d centre = target.getBoundingBox().getCenter();
        hitEffects.add(new HitEffect(target, centre.x, target.getY(), centre.z, System.nanoTime(), critical));

        if (!attackParticles.get()) return;

        for (int i = 0; i < EFFECT_PARTICLES; i++) {
            double angle = Math.PI * 2.0 * i / EFFECT_PARTICLES;
            double speed = 0.25 + Math.random() * 0.15;

            mc.particleManager.addParticle(ParticleTypes.CRIT,
                centre.x, centre.y, centre.z,
                Math.cos(angle) * speed, 0.05 + Math.random() * 0.15, Math.sin(angle) * speed);
        }
    }

    /**
     * Draws the hit effects that are still alive and drops the ones that have aged out.
     *
     * <p>The age is real time rather than ticks, so the expansion is smooth at any frame rate - the
     * same reason Scaffold+ breathes its target on a wall clock.
     */
    private void drawAttackEffects(Render3DEvent event) {
        if (hitEffects.isEmpty()) return;

        long now = System.nanoTime();
        double lifespan = Math.max(0.05, attackEffectDuration.get()) * 1.0E9;

        Iterator<HitEffect> iterator = hitEffects.iterator();
        while (iterator.hasNext()) {
            HitEffect effect = iterator.next();

            double age = (now - effect.startNanos()) / lifespan;
            if (age >= 1.0) {
                iterator.remove();
                continue;
            }

            double fade = 1.0 - age;
            Color colour = effect.crit() ? critEffectColor.get() : attackEffectColor.get();

            // Fast at first and slowing as it goes, so it reads as a shockwave rather than a
            // balloon being inflated.
            double grow = 1.0 - (1.0 - age) * (1.0 - age);
            double radius = EFFECT_START_RADIUS + (attackEffectRadius.get() - EFFECT_START_RADIUS) * grow;

            drawBand(event, effect.x(), effect.z(), effect.y() + 0.02, radius,
                EFFECT_RING_WIDTH * (1.0 - 0.4 * age), EFFECT_RING_HEIGHT * fade,
                EFFECT_SEGMENTS, tint(colour, fade));

            if (attackEffectBox.get() && effect.target().isAlive()) {
                Box box = effect.target().getBoundingBox().expand(0.04 + 0.2 * grow);
                event.renderer.box(box, tint(colour, fade * 0.25), tint(colour, fade), ShapeMode.Both, 0);
            }
        }
    }

    /** A scratch copy of {@code source} with its alpha scaled by {@code scale}. */
    private Color tint(Color source, double scale) {
        Color out = effectScratch[effectScratchIndex++ % effectScratch.length];
        out.set(source);
        out.a = MathHelper.clamp((int) (source.a * scale), 0, 255);
        return out;
    }

    /**
     * One attack flash: where the hit landed, when, whether it was a critical, and the entity whose
     * hitbox flashes with it. The position is a snapshot, so the ring stays where the hit happened
     * even if the target is knocked away or killed.
     */
    private record HitEffect(LivingEntity target, double x, double y, double z, long startNanos, boolean crit) {}

    // ---- Critting ----

    /**
     * Vanilla's own critical-hit test, read from the player as the last movement packet left it -
     * which is the state the server resolves the attack against, because the attack goes out before
     * this tick's movement packet and after the previous one.
     *
     * <p>The target half of the test (`target instanceof LivingEntity`) is implied: only living
     * entities are ever picked as targets.
     */
    private boolean critConditions() {
        return mc.player.fallDistance > 0.0
            && !mc.player.isOnGround()
            && !mc.player.isClimbing()
            && !mc.player.isTouchingWater()
            && !mc.player.hasStatusEffect(StatusEffects.BLINDNESS)
            && !mc.player.hasVehicle()
            && !mc.player.isSprinting();
    }

    /** Whether a jump from here would actually leave the ground. */
    private boolean canJumpForCrit() {
        return mc.player.isOnGround()
            && !mc.player.isTouchingWater()
            && !mc.player.isClimbing()
            && !mc.player.hasVehicle()
            && !mc.player.isGliding()
            && !mc.player.getAbilities().flying;
    }

    /**
     * Whether a fall that counts as a crit is still possible. Holding a swing back only makes sense
     * when it is: riding, swimming, climbing, gliding, flying and blindness all rule a crit out
     * completely, and waiting through any of them would just delay every hit by the timeout.
     */
    private boolean critReachable() {
        return !mc.player.hasVehicle()
            && !mc.player.isClimbing()
            && !mc.player.isGliding()
            && !mc.player.isTouchingWater()
            && !mc.player.getAbilities().flying
            && !mc.player.hasStatusEffect(StatusEffects.BLINDNESS);
    }

    /**
     * Gets the sprint flag off a swing that is being held for a crit.
     *
     * <p>Releasing the sprint key does not stop a sprint that is already running - vanilla only
     * gives it up when the forward input, a hard collision, low hunger or water takes it away - so
     * the flag goes off as well. Nothing in Grim reacts to a sprint <em>ending</em>: the sprint
     * checks all act on a sprint that is running, or on the packet that starts one. The client
     * reports the stop the way it reports a wall - a STOP_SPRINTING action before this tick's
     * movement packet - so both sides predict the same tick.
     *
     * <p>One path this cannot close: a player who double-taps W rather than holding the sprint key
     * leaves vanilla's double-tap window open, and vanilla will start the sprint again from it for
     * the few ticks that window lasts. It clears itself and the crit then lands, so the cost is a
     * delayed swing, not a missed one.
     */
    private void stopSprintForCrit() {
        if (mc.player.isSprinting()) mc.player.setSprinting(false);

        if (mc.options.sprintKey.isPressed() || isPhysicallyDown(mc.options.sprintKey)) {
            mc.options.sprintKey.setPressed(false);
            sprintReleasedByUs = true;
        }
    }

    /**
     * Gives the sprint key back once there is no crit left to set up.
     *
     * <p>Only if the player is still holding it. A physical release during the few ticks a hold
     * lasts must not be undone: a key that is down with nothing behind it would be a sprint the
     * player never asked for, and the `START_SPRINTING` that follows it is a packet no player would
     * have sent. Leaving the key up instead would take their sprint away until they pressed it
     * again, so the flag is simply dropped and the key left as the player left it.
     */
    private void releaseSprint() {
        if (!sprintReleasedByUs) return;

        sprintReleasedByUs = false;
        if (isPhysicallyDown(mc.options.sprintKey)) mc.options.sprintKey.setPressed(true);
    }

    // ---- Orbiting ----

    /**
     * Circles the target by holding a strafe key.
     *
     * <p>Nothing here writes a velocity: the module holds a key and vanilla turns it into movement,
     * so the input packet describes the circle and Grim's prediction reproduces it. The direction
     * the strafe actually goes in is the body yaw - which is the aim, which is the yaw the movement
     * packet carries - so the circle closes around the target rather than around a yaw only one of
     * the two sides can see.
     *
     * <p>Distance is corrected with the forward and back keys, with a deadband so they are not
     * tapped every tick. A key the player is physically holding is never fought with: if a strafe
     * key is down the player is steering the circle, and if a walk key is down the module leaves the
     * distance alone rather than pressing its opposite and cancelling the player's input.
     */
    private void driveOrbit(LivingEntity target) {
        if (orbitTarget != target) {
            orbitTarget = target;

            orbitSide = switch (orbitDirection.get()) {
                case Clockwise -> 1;
                case CounterClockwise -> -1;
                case Random -> Math.random() < 0.5 ? 1 : -1;
            };
        }

        boolean playerSteering = isPhysicallyDown(mc.options.leftKey) || isPhysicallyDown(mc.options.rightKey);
        drive(mc.options.leftKey, !playerSteering && orbitSide > 0);
        drive(mc.options.rightKey, !playerSteering && orbitSide < 0);

        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        double error = Math.sqrt(dx * dx + dz * dz) - orbitDistance.get();

        boolean playerWalking = isPhysicallyDown(mc.options.forwardKey) || isPhysicallyDown(mc.options.backKey);
        drive(mc.options.forwardKey, !playerWalking && error > ORBIT_DEADBAND);
        drive(mc.options.backKey, !playerWalking && error < -ORBIT_DEADBAND);
    }

    private void releaseOrbitKeys() {
        drive(mc.options.leftKey, false);
        drive(mc.options.rightKey, false);
        drive(mc.options.forwardKey, false);
        drive(mc.options.backKey, false);
    }

    // ---- Keys ----

    /**
     * Whether the physical key behind a binding is down, rather than the flag that may have been set
     * on it. {@code KeyBinding.isPressed} cannot tell the two apart, and the difference decides
     * whether a key may be pressed or lifted at all.
     *
     * <p>GLFW is asked directly instead of through {@code InputUtil.isKeyPressed}: that helper takes
     * a window object as of 1.21.11 and a raw handle before it, while these calls have been the same
     * everywhere, so one implementation covers every version this addon builds for. Mouse bindings
     * are asked about as mouse buttons, or a jump bound to a side button would read as released and
     * could be pressed and lifted underneath the player.
     */
    private boolean isPhysicallyDown(KeyBinding key) {
        InputUtil.Key bound = InputUtil.fromTranslationKey(key.getBoundKeyTranslationKey());
        if (bound == InputUtil.UNKNOWN_KEY) return false;

        long window = mc.getWindow().getHandle();
        int code = bound.getCode();

        return switch (bound.getCategory()) {
            case KEYSYM -> GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS;
            case MOUSE -> GLFW.glfwGetMouseButton(window, code) == GLFW.GLFW_PRESS;
            // Nothing the options screen produces is bound by scancode, and GLFW has no scancode
            // lookup to ask about one with. Reported as not held, which leaves it to the flag.
            case SCANCODE -> false;
        };
    }

    /**
     * Presses or releases one key on the module's behalf.
     *
     * <p>A key the player is holding is never pressed by the module and never lifted by it. If the
     * module was holding it, the press is handed over by dropping it from {@link #heldKeys} and
     * leaving it down - their press is the one that survives. A key that is down without a physical
     * press behind it belongs to somebody else, and is left alone for the same reason.
     */
    private void drive(KeyBinding key, boolean want) {
        if (isPhysicallyDown(key)) {
            heldKeys.remove(key);
            return;
        }

        if (want) {
            if (key.isPressed()) return;

            key.setPressed(true);
            heldKeys.add(key);
        } else if (heldKeys.remove(key)) {
            key.setPressed(false);
        }
    }

    /**
     * Hands back every key the module is holding, and the sprint key it talked the player out of.
     *
     * <p>These five are the only keys the module ever drives, so this is complete by construction;
     * {@code drive} refuses to lift anything the player has taken over.
     */
    private void releaseKeys() {
        drive(mc.options.leftKey, false);
        drive(mc.options.rightKey, false);
        drive(mc.options.forwardKey, false);
        drive(mc.options.backKey, false);
        drive(mc.options.jumpKey, false);

        heldKeys.clear();
        releaseSprint();
    }

    /**
     * Whether this entity is something the module would consider, at this distance from the eye.
     * Shared by targeting and by the outline render, so what is drawn is exactly what is attackable.
     */
    private boolean isCandidate(LivingEntity living, Vec3d eye, double maxSquared) {
        if (living == mc.player || !living.isAlive() || living.isDead() || living.isSpectator()) return false;
        if (!isTarget(living)) return false;

        return living.getBoundingBox().squaredMagnitude(eye) <= maxSquared;
    }

    private LivingEntity findTarget() {
        Vec3d eye = mc.player.getEyePos();
        double maxSquared = range.get() * range.get();

        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || !isCandidate(living, eye, maxSquared)) continue;

            double distance = living.getBoundingBox().squaredMagnitude(eye);
            if (distance >= bestDistance) continue;

            bestDistance = distance;
            best = living;
        }

        return best;
    }

    private boolean isTarget(LivingEntity entity) {
        if (entity instanceof PlayerEntity player) {
            return players.get() && !Friends.get().isFriend(player);
        }

        if (entity instanceof Monster) return hostile.get();
        if (entity instanceof PassiveEntity) return passive.get();

        // Anything else living (armour stands and the like) is left alone rather than guessed at.
        return false;
    }

    /**
     * Writes the aim straight to the player entity. That is the whole reason this module does not
     * need a silent rotation: the entity rotation is what the physics read, what the movement packet
     * carries and what the server predicts walking with, so aiming through it introduces no
     * disagreement anywhere.
     */
    private void aimAt(LivingEntity target) {
        // Grab the view before the body rotation is overwritten, so the camera carries on from
        // wherever the player was looking when the fight started.
        if (!viewLockActive) beginViewLock();

        prevViewYaw = viewYaw;
        prevViewPitch = viewPitch;

        Vec3d centre = target.getBoundingBox().getCenter();

        float yaw = (float) Rotations.getYaw(centre);
        float pitch = (float) MathHelper.clamp(Rotations.getPitch(centre), -90.0, 90.0);

        double step = rotationStep.get();
        if (step > 0.0 && aiming) {
            yaw = (float) (mc.player.getYaw() + MathHelper.clamp(MathHelper.wrapDegrees(yaw - mc.player.getYaw()), -step, step));
            pitch = (float) (mc.player.getPitch() + MathHelper.clamp(pitch - mc.player.getPitch(), -step, step));
        }

        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
        VersionCompat.setPreviousRotation(mc.player, yaw, pitch);

        aimedAt = target;
        aiming = true;
    }

    // ---- Rendering ----

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || mc.player == null || mc.world == null) return;

        if (circle.get()) drawCircle(event);
        if (orbit.get() && orbitCircle.get()) drawOrbitCircle(event);
        if (outline.get()) drawOutlines(event);
        drawAttackEffects(event);
    }

    /**
     * The attack range as a ring around the player.
     *
     * <p>Drawn as a band of quads with both a width and a height rather than as a line loop. A line
     * on the ground is one pixel thick and almost edge-on when you are standing, which is why a flat
     * ring always looks too thin; the top face reads from above and the outer wall reads from eye
     * level, so it works from both.
     */
    private void drawCircle(Render3DEvent event) {
        double y = (circleAtEyes.get() ? mc.player.getEyeY() : mc.player.getY()) + 0.02;

        drawBand(event, mc.player.getX(), mc.player.getZ(), y, range.get(), circleWidth.get(),
            circleHeight.get(), circleSegments.get(), circleColor.get());
    }

    /**
     * The orbit distance as a ring around the target being circled.
     *
     * <p>It is the same band the range ring is drawn with, only centred on the entity the strafe
     * keys are walking around instead of on the player - so the circle those keys are trying to hold
     * is something you can see rather than guess at, and the module's own deadband is legible: the
     * player is on the ring when it is doing its job.
     *
     * <p>The ring only exists while the orbit is actually driving, which is what {@link #orbitTarget}
     * being set means. It follows the rendered position rather than the tick position, or it would
     * trail the target by the part of a tick the render interpolates.
     */
    private void drawOrbitCircle(Render3DEvent event) {
        LivingEntity target = orbitTarget;
        if (target == null) return;

        // 1.21.10 把 getPos() 改名成 getEntityPos(),getLerpedPos 两版都在,所以这里直接用它。
        Vec3d pos = target.getLerpedPos(event.tickDelta);

        drawBand(event, pos.x, pos.z, pos.y + 0.02, orbitDistance.get(), orbitCircleWidth.get(),
            orbitCircleHeight.get(), orbitCircleSegments.get(), orbitCircleColor.get());
    }

    /**
     * One flat band of quads: a ring of the given radius, radial width and outer wall height, lying
     * at {@code y}. Both rings are built from this, so the two can differ only where the settings
     * say they do.
     */
    private void drawBand(Render3DEvent event, double centreX, double centreZ, double y, double radius,
                          double width, double height, int segments, Color color) {
        double step = Math.PI * 2.0 / segments;

        double half = width / 2.0;
        double inner = Math.max(0.0, radius - half);
        double outer = radius + half;

        for (int i = 0; i < segments; i++) {
            double a1 = step * i;
            double a2 = step * (i + 1);

            // Shared corners between neighbouring segments, so the band has no seams to double-blend
            // along.
            double cos1 = Math.cos(a1);
            double sin1 = Math.sin(a1);
            double cos2 = Math.cos(a2);
            double sin2 = Math.sin(a2);

            double innerX1 = centreX + cos1 * inner;
            double innerZ1 = centreZ + sin1 * inner;
            double outerX1 = centreX + cos1 * outer;
            double outerZ1 = centreZ + sin1 * outer;
            double innerX2 = centreX + cos2 * inner;
            double innerZ2 = centreZ + sin2 * inner;
            double outerX2 = centreX + cos2 * outer;
            double outerZ2 = centreZ + sin2 * outer;

            // Top of the band.
            event.renderer.quad(innerX1, y, innerZ1, outerX1, y, outerZ1, outerX2, y, outerZ2, innerX2, y, innerZ2, color);

            // Outer wall, so the ring still reads as a ring when it is seen along the ground rather
            // than from above.
            if (height > 0.0) {
                event.renderer.quad(outerX1, y, outerZ1, outerX2, y, outerZ2, outerX2, y + height, outerZ2, outerX1, y + height, outerZ1, color);
            }
        }
    }

    /**
     * Outlines everything inside the range that the module would consider, using the same filters
     * the attacks use - so the drawing and the decision can never disagree.
     *
     * <p>Boxes follow the rendered position rather than the tick position, or they would stutter
     * against the entities they belong to.
     */
    private void drawOutlines(Render3DEvent event) {
        Vec3d eye = mc.player.getEyePos();
        double maxSquared = range.get() * range.get();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || !isCandidate(living, eye, maxSquared)) continue;

            // getPos() 在 1.21.10 改名成 getEntityPos();getX/Y/Z 读的是同一个 pos 字段
            // (javap 核过字节码),所以这里用后者,不必再开一个版本维度。
            Vec3d tickPos = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
            Box box = entity.getBoundingBox().offset(entity.getLerpedPos(event.tickDelta).subtract(tickPos));

            if (living == currentTarget) {
                Color colour = targetColor.get();
                scratch.set(colour);
                scratch.a = colour.a / 4;

                event.renderer.box(box, scratch, colour, ShapeMode.Both, 0);
            } else {
                event.renderer.box(box, outlineColor.get(), outlineColor.get(), ShapeMode.Lines, 0);
            }
        }
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
        if (!viewLockActive) return;
        viewLockActive = false;

        if (restoreView.get() && mc.player != null) {
            float yaw = viewYaw;
            float pitch = MathHelper.clamp(viewPitch, -90.0f, 90.0f);

            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);

            // The camera renders the interpolated rotation, so the previous values have to move
            // with the current ones or the first tick after the lock renders at the aim.
            VersionCompat.setPreviousRotation(mc.player, yaw, pitch);
        }
    }

    /** Which way the orbit travels around the target. */
    public enum OrbitDirection {
        /** The way the left strafe key goes while the body is aimed at the target. */
        Clockwise,
        /** The way the right strafe key goes. */
        CounterClockwise,
        /** Picked per target, so every fight does not circle the same way. */
        Random
    }
}
