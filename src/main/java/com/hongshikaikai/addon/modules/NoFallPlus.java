package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.FreeView;
import com.hongshikaikai.addon.utils.VersionCompat;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Survives a fall by starting to glide just before the ground, instead of lying about being on it.
 *
 * <h2>Why this is not a packet spoof</h2>
 * Every "onGround" route is closed on Grim, and one of them is closed twice on the vanilla side:
 *
 * <ul>
 *     <li>A claimed onGround on a packet with a position is replayed through the movement
 *         prediction, so {@code GroundSpoof} flags it whenever the prediction does not land on the
 *         same tick. A claimed onGround on a packet without a position is the one packet case
 *         {@code NoFall} exists for, and {@code CheckManagerListener} vets those claims first and
 *         force-resyncs the ones that are not near the ground.</li>
 *     <li>Spending the claim does not even help: in 1.21.4 {@code Entity#fall} applies the whole
 *         stored fall distance the moment a packet claims ground, so a single spoof at altitude
 *         <em>is</em> the fall damage, not a way around it. The only damage-free state is claiming
 *         ground on <em>every</em> packet, which is exactly what {@code GroundSpoof} flags.</li>
 *     <li>The 1.17 duplicate movement packet that used to beat this is rewritten by
 *         {@code CheckManagerListener#isMojangStupid} with the comment "Prevents position-related
 *         bypasses like nofall".</li>
 * </ul>
 *
 * <h2>What actually works</h2>
 * {@code LivingEntity#tickGliding} calls {@code Entity#limitFallDistance} every tick while
 * gliding, and that caps the stored fall distance at 1.0 - but only while the vertical speed is
 * above -0.5. A glide that is already slow when the landing packet arrives therefore takes the
 * damage to zero, with no packet lying about anything.
 *
 * <p>That condition is the whole reason this module has two shapes. A fall it reaches before the
 * ground is a <em>deploy</em>: swap an elytra in, press jump, hold the level pitch, land. A glide
 * that is already running and pointed at the ground - ElytraFly+ with sneak held writes exactly
 * that with {@code down-pitch}, and a vanilla dive or a held-down mouse is the same fall - is an
 * <em>arrest</em>: the module takes the body rotation over and holds the same level pitch until
 * the local vertical speed has bled back under the cap. Without the arrest a dive accumulates the
 * whole fall, because {@code limitFallDistance} never runs during it.
 *
 * <p>Starting a glide is left to the vanilla client: this module only puts an elytra in the
 * chest slot and presses the jump key, and {@code ClientPlayerEntity#tickMovement} sends
 * {@code START_FALL_FLYING} itself, in the packet order the server and Grim both expect. Driving
 * {@code startGliding()} directly, or sending the command by hand, flips the client's glide flag
 * without the packets that explain it, which is the shape of thing Grim's simulation looks for.
 *
 * <h2>The three timing rules</h2>
 * <ul>
 *     <li>The press has to be a <em>fresh</em> press. Grim's {@code ElytraB} looks at the input
 *         that precedes the command (it must read {@code jump = false}) and the input that follows
 *         it (it must read {@code jump = true}); a key that is already down leaves the last
 *         reported input at {@code true}, so it is released for a tick first. The same rule is
 *         what vanilla enforces, so the press is exactly the one a player makes.</li>
 *     <li>The elytra is swapped in as soon as the fall is dangerous, not at the last moment. The
 *         inventory click has to round-trip before the client sees the elytra on its own chest, and
 *         only then can the client glide at all - so the earlier the swap, the more fall is left
 *         to work with.</li>
 *     <li>The glide starts a few ticks above the ground ({@code deploy-ticks}). The server caps the
 *         stored fall distance on the tick it processes the command, so the command has to arrive
 *         before the landing packet, not with it.</li>
 * </ul>
 *
 * <p>The remaining guards mirror the rest of Grim's elytra checks: {@code ElytraD} (no elytra),
 * {@code ElytraF} (started on the ground, which the minimum fall distance covers), {@code G}
 * (levitation), {@code H} (vehicle), {@code I} (water) and {@code C} (started too often, which the
 * cooldown covers). The mace pause is there because gliding cancels a smash, and the horizontal
 * speed cap keeps 1.21.2+'s kinetic damage - a glide into a wall or slope at speed - away from
 * deployments that are not a plain vertical drop.
 *
 * <h2>Where the fall lands: the in-place drop</h2>
 * A level glide converts the whole fall into forward speed, so the save above lands the player
 * wherever the glide carried them - a level glide keeps descending at about 0.17 blocks a tick and
 * travels at roughly ten times that horizontally. {@code in-place} attacks both ends of that. It
 * starts the glide at the physical minimum ({@link #bleedClearance()} plus {@link #pressLead()})
 * instead of the tick gate, which for a fast fall presses about twice as early as the bleed needs -
 * and every one of those extra blocks is glided level, which is where most of the drift comes from.
 * Then, once {@code limitFallDistance} has actually capped the fall distance, it stops holding the
 * level pitch, takes the body rotation over and aims it at the landing point - the ground the
 * module's own vertical raycast sees straight down - so the rest of the fall is a descent onto
 * that point instead of a level glide past it.
 *
 * <p>The drop cannot start earlier than that cap, and this is the whole reason the two settings
 * exist together. The cap leaves the server's fall distance at {@link #CAPPED_FALL} and vanilla
 * starts charging at {@link #SAFE_FALL}, so exactly two blocks of descent can be spent outside the
 * cap without a scratch. {@link #predictedFallDistance(double, double)} replays the two vanilla
 * lines that decide that - the packet's dy is added, and the cap puts the fall distance back on
 * every tick the descent is still above it - so a glide that is already slow may drop from higher
 * up than one levelled out late, and {@code in-place-margin} is what is held back.
 *
 * <p>The camera is not part of any of this. In-place mode runs through {@link FreeView}: the mouse
 * keeps the camera, the module writes the rotation the server sees, and the view is handed back to
 * the body when the fall ends - which is the packet that finally tells the server where the player
 * was looking. Without the setting the module writes the pitch straight to the entity the way it
 * always has, and the camera goes with it.
 */
public class NoFallPlus extends Module implements FreeView {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgInPlace = settings.createGroup("In Place");
    private final SettingGroup sgGuards = settings.createGroup("Guards");

    private final Setting<Double> minFallDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-fall-distance")
        .description("Blocks of fall before the module does anything, measured by the module from y (the client's own fallDistance field is not maintained in 1.21.4). 3.0 is where vanilla starts dealing damage, so below that there is nothing to save.")
        .defaultValue(3.0)
        .min(3.0)
        .sliderRange(3.0, 40.0)
        .build()
    );

    private final Setting<Double> deployTicks = sgGeneral.add(new DoubleSetting.Builder()
        .name("deploy-ticks")
        .description("Start gliding when the ground is this many ticks away. The glide bleeds vertical speed by about 12 percent per tick, and Entity#limitFallDistance only caps the fall distance once it is above -0.5, so a level glide needs roughly seven ticks to become a save at all. Raise it for faster falls; the cost is a longer glide.")
        .defaultValue(8.0)
        .min(1.0)
        .max(40.0)
        .sliderRange(2.0, 20.0)
        .build()
    );

    private final Setting<Integer> equipLeadTicks = sgGeneral.add(new IntSetting.Builder()
        .name("equip-lead-ticks")
        .description("Ticks the elytra has to have been on the chest before the glide command may be sent. Grim reads the chest plate off the platform player, so a command that arrives with the inventory click is cancelled as \"no elytra\" and the save silently fails. Raise it if a save still hurts; 0 sends the command as soon as the swap is out.")
        .defaultValue(2)
        .min(0)
        .max(20)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Boolean> levelPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("level-pitch")
        .description("Look level while the glide runs. This is not cosmetic: Entity#limitFallDistance only caps the fall distance while the vertical speed is above -0.5, so a glide aimed at the ground dives straight through the cap and the landing is charged in full.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> deployPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("deploy-pitch")
        .description("Pitch written when the glide starts. 0 is level flight, which is where the glide descends slowest (about 0.17 blocks per tick) and the cap holds with room to spare. Negative looks up.")
        .defaultValue(0.0)
        .min(-60.0)
        .max(40.0)
        .sliderRange(-45.0, 30.0)
        .visible(levelPitch::get)
        .build()
    );

    private final Setting<Boolean> restorePitch = sgGeneral.add(new BoolSetting.Builder()
        .name("restore-pitch")
        .description("Put the view back where the player left it once the fall is over. Off leaves it level, which is one camera snap instead of two.")
        .defaultValue(false)
        .visible(levelPitch::get)
        .build()
    );

    private final Setting<Boolean> arrestDive = sgGeneral.add(new BoolSetting.Builder()
        .name("arrest-dive")
        .description("Take over a glide that is diving at the ground - the ElytraFly+ sneak/down-pitch case, or any vanilla dive - and hold the level pitch until the vertical speed is back under the cap. A glide on its own saves nothing here: Entity#limitFallDistance only caps while velocity.y > -0.5, so a dive accumulates the whole fall and the landing is charged in full. Off leaves every dive to whoever is flying it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> arrestMargin = sgGeneral.add(new DoubleSetting.Builder()
        .name("arrest-margin")
        .description("Extra blocks of ground clearance on top of the distance a level glide needs to bleed the current vertical speed back to the cap before a dive is arrested. Raise it when a save still lands hard; 0 starts the arrest exactly at the physical minimum, which leaves no room for the tick the press is processed on.")
        .defaultValue(4.0)
        .min(0.0)
        .max(32.0)
        .sliderRange(0.0, 16.0)
        .visible(arrestDive::get)
        .build()
    );

    private final Setting<Double> scanRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("scan-range")
        .description("How far down the ground is looked for. A fall longer than this is treated as \"no ground\" and the glide waits for a later tick.")
        .defaultValue(32.0)
        .min(4.0)
        .max(128.0)
        .sliderRange(8.0, 64.0)
        .build()
    );

    private final Setting<Double> maxHorizontalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-horizontal-speed")
        .description("Do not deploy above this horizontal speed (blocks per tick). 1.21.2+ charges kinetic damage for gliding into a wall or slope at speed, which a vertical drop does not.")
        .defaultValue(1.0)
        .min(0.0)
        .max(4.0)
        .sliderRange(0.0, 2.0)
        .build()
    );

    private final Setting<Boolean> restoreChestplate = sgGeneral.add(new BoolSetting.Builder()
        .name("restore-chestplate")
        .description("Swap the chest item back once the player is down. Off leaves the elytra on, the way ElytraFly+ does.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Say in chat what the module is doing: the fall it armed on, the swap, every press and why one was skipped. Turn it off once it works.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> inPlace = sgInPlace.add(new BoolSetting.Builder()
        .name("in-place")
        .description("Start the glide at the physical minimum instead of the deploy-ticks run-up, end the level glide once the server's fall distance has actually been capped, then take the body rotation over and aim it at the landing point - the ground straight below, which is where the drop lands - so the tail of the fall is a descent onto that point instead of a level glide past it. The mouse keeps the camera: the module writes the rotation the server sees, and the view goes back to the body when the fall ends. Needs level-pitch; deploy-ticks is not consulted in this mode.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> inPlaceMargin = sgInPlace.add(new DoubleSetting.Builder()
        .name("in-place-margin")
        .description("Blocks of fall distance held back when deciding whether the drop onto the landing point is still free. Entity#limitFallDistance leaves the server's fall distance at 1.0 and vanilla starts charging at 3.0, so 2.0 blocks of descent can be spent outside the cap; this is how much of that is kept in reserve. Raise it if a landing still hurts; 0 spends the two blocks to the last inch.")
        .defaultValue(0.5)
        .min(0.0)
        .max(2.0)
        .sliderRange(0.0, 2.0)
        .visible(inPlace::get)
        .build()
    );

    private final Setting<Double> inPlaceLead = sgInPlace.add(new DoubleSetting.Builder()
        .name("in-place-lead")
        .description("Extra blocks of ground kept on top of the physical minimum before in-place mode starts the glide. The level glide eats (speed - 0.45) / 0.11 blocks while it bleeds the descent back down to the cap, and the room for the press itself - three ticks of it, scaled by the speed - is already folded in; this is the slack on top. Raise it if a save still lands hard; 0 spends the physical minimum plus the press room and nothing else.")
        .defaultValue(1.0)
        .min(0.0)
        .max(16.0)
        .sliderRange(0.0, 8.0)
        .visible(inPlace::get)
        .build()
    );

    private final Setting<Boolean> pauseOnMace = sgGuards.add(new BoolSetting.Builder()
        .name("pause-on-mace")
        .description("Do not glide while a mace is held: a glide cancels the smash the mace is for. This also keeps a mace dive out of the dive arrest.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> levitationGuard = sgGuards.add(new BoolSetting.Builder()
        .name("levitation-guard")
        .description("Do not start gliding under levitation (Grim ElytraG).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> cooldownTicks = sgGuards.add(new IntSetting.Builder()
        .name("cooldown-ticks")
        .description("Minimum ticks between two glide starts. Grim's ElytraC flags starts that land within two ticks of each other. Measured from a start that actually happened, not from a press, so a rejected press is retried on the next tick.")
        .defaultValue(4)
        .min(2)
        .max(100)
        .sliderRange(2, 40)
        .build()
    );

    /**
     * Vertical speed at or below which {@code Entity#limitFallDistance} stops capping the fall
     * ({@code velocity.y > -0.5} is the vanilla condition).
     */
    private static final double CAP_SPEED = 0.45;
    /** Factor a level glide bleeds the vertical speed by each tick: the lift term against drag. */
    private static final double GLIDE_BLEED = 0.89;
    /**
     * How far above the held pitch the mouse has to look before it counts as "take me up" while
     * nothing is steering the body with the keys. Small nudges are not a request to end a save.
     */
    private static final double RECOVERY_PITCH = 12.0;
    /** Fall distance vanilla leaves alone: {@code SAFE_FALL_DISTANCE} is 3.0 for a player. */
    private static final double SAFE_FALL = 3.0;
    /** Fall distance {@code Entity#limitFallDistance} leaves behind every tick it caps. */
    private static final double CAPPED_FALL = 1.0;
    /** Downward acceleration a player falls with, per tick. Part of the drop prediction. */
    private static final double GRAVITY = 0.08;
    /** Vertical drag a straight-down glide keeps, per tick. The other part of the prediction. */
    private static final double DRAG = 0.98;
    /**
     * How far the landing point may be off straight down before the aim takes the body yaw from
     * it. A point directly below has no yaw, and inventing one would spin the body for nothing.
     */
    private static final double AIM_DEAD_ZONE = 1.0e-4;
    /**
     * Ticks of room folded into the in-place glide start for the press itself, on top of the
     * configured lead. A press can take two ticks - a jump key that is already down has to come up
     * before it can be a fresh press, and the tick after that is the press - and the check is made
     * at the head of the tick, so three ticks is the latency the start is measured with.
     */
    private static final double PRESS_LEAD_TICKS = 3.0;

    /** Inventory index the elytra came out of, or -1 when this module has not swapped one in. */
    private int elytraOrigin = -1;
    /** True while the chest slot holds an elytra this module put there. */
    private boolean swapped;
    /** The swap click is in flight; a second one would swap the elytra straight back. */
    private boolean swapSent;
    /** Player age when the elytra went into the chest, -1 when this module did not swap one. */
    private int swappedAtAge = -1;
    /** The jump key is down because of this module and has to be lifted again. */
    private boolean liftKeyAtHead;
    /** One tick has to pass after a lift before the next press can be a fresh press. */
    private boolean waitOneTick;
    /** The jump key state was overridden (or is being held) by this module and has to be dropped. */
    private boolean jumpKeyOverridden;
    /** Whether the missing-elytra error was already shown for this fall. */
    private boolean warnedNoElytra;
    /** True once the arming message was said for this fall, so debug does not repeat it. */
    private boolean announced;
    /** Glide presses made in this fall; the cap keeps a rejected command from looping. */
    private int attempts;
    /** Wall clock of the last start that actually happened, for the ElytraC cooldown. */
    private long lastGlideStart;
    /** True while the player is gliding, so the start and end can be told apart. */
    private boolean wasGliding;
    /** One message per fall for the standing-still reasons. */
    private boolean saidOnce;
    /** Airborne ticks since the last heartbeat, so debug says something even when nothing else does. */
    private int heartbeatTicks;
    /** The player's pitch before the module levelled it, or null when it has not touched the view. */
    private Float savedPitch;
    /** True while the view is being held level for a glide. */
    private boolean pitchLeveled;
    /**
     * The point the in-place drop is aimed at - the ground the module's vertical raycast saw when
     * the drop took over - or null while the module is not dropping.
     */
    private Vec3d landingPoint;
    /** True while the camera is decoupled from the body rotation, in in-place mode. */
    private boolean freeView;
    /** The camera view, which is what the player sees and steers while the module owns the body. */
    private float viewYaw;
    private float viewPitch;
    /** The view at the end of the previous tick, for the camera's interpolation over a frame. */
    private float prevViewYaw;
    private float prevViewPitch;
    /** True once the glide has been slow enough for Entity#limitFallDistance to cap the fall. */
    private boolean capWindow;
    /** True when the held level pitch came from taking over a dive, not from a deploy press. */
    private boolean arrested;
    /** The input was dropped for a tick to make the inventory click legal; click on the next one. */
    private boolean swapArmed;
    /** Same one-tick dance for the click that puts the chest item back, which lands mid-stride. */
    private boolean restoreArmed;
    /** Key states taken over for that tick, restored right after the click. */
    private boolean[] savedKeys;
    /** Last health seen, so damage on the landing can be reported with its size. */
    private float lastHealth = Float.NaN;
    /**
     * Highest y seen since the player last left the ground. The client's own {@code fallDistance}
     * field is not what the fall is measured with here: in 1.21.4 it stays at 0.00 for a falling
     * client player (confirmed in game), because {@code Entity#fall} is only reached from the
     * server's movement handling. Y is synced and reliable, so the fall is measured from it.
     */
    private double fallStartY = Double.NaN;

    public NoFallPlus() {
        super(HongShiKaiKai.CATEGORY, "nofall+", "Elytra MLG: an elytra is put on and vanilla starts the glide just before the ground, which caps the server's fall distance at one block. In-place mode ends the glide once the cap has run and drops onto the landing point instead of gliding past it.");
    }

    @Override
    public void onActivate() {
        clearState();
    }

    @Override
    public void onDeactivate() {
        releaseJumpKey();
        releasePitch();
        restoreMovementInput();

        // Taking the elytra off mid-glide would drop the player, so a disable in the air leaves it
        // on and only forgets the bookkeeping. On the ground the item goes back, but only when the
        // input is clean: deactivation has no tick to drop the input in, and a click with a key
        // down is exactly what Grim's MultiActionsC flags.
        if (mc.player != null && !mc.player.isGliding()) {
            if (inputIsDirty()) info("Chest item left on: a key is held, so the swap back would be a flagged click.");
            else restoreChestNow();
        }

        clearState();
    }

    /**
     * Head of the tick, before the player tick reads the key bindings - the same place a real key
     * press would already be visible. See {@link SprintPlus} for the same reasoning on the sprint
     * key.
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate()) {
            releaseJumpKey();
            releasePitch();
            clearState();
            return;
        }

        // Before any gate, so a fall that never reaches the module's own checks still says what the
        // player state looks like. Once a second while airborne, and never on the ground.
        heartbeat();
        reportDamage();

        // The restore click is the one that used to be flagged: it goes out when the player is back
        // on the ground, which is exactly when they are walking. It gets the same clean-input tick
        // as the swap.
        if (restoreArmed) {
            restoreArmed = false;
            clickRestoreChest();
            restoreMovementInput();
            finishFall();
            return;
        }

        // Hand the key back up before vanilla reads it. Grim's ElytraB wants the input that
        // precedes the command to read jump = false and the one that follows it to read
        // jump = true, so the press has to be exactly one tick wide - and the tick right after a
        // press can never be the next press, because the previous input still says true.
        if (liftKeyAtHead) {
            liftKeyAtHead = false;
            mc.options.jumpKey.setPressed(false);
            waitOneTick = true;
        }

        // Being in a glide is not the same as being saved. Entity#limitFallDistance only caps the
        // fall while the vertical speed is above -0.5, so a glide that is pointed at the ground -
        // ElytraFly+ with sneak held writes exactly that - accumulates the whole fall and the
        // landing is charged in full. This is the branch that catches it.
        if (mc.player.isGliding()) {
            if (!wasGliding) {
                wasGliding = true;
                lastGlideStart = System.currentTimeMillis();
                say("gliding, %s tick(s) to the ground, vy=%.2f", ticksText(), mc.player.getVelocity().y);
            }

            // Entity#limitFallDistance caps the fall distance on any tick the vertical speed is
            // above -0.5, and one such tick is enough to wipe everything accumulated so far. This
            // is the line that says whether the glide got slow in time - and it comes before the
            // rotation is decided, because it is also what makes the in-place drop affordable.
            if (!capWindow && mc.player.getVelocity().y > -0.5) {
                capWindow = true;
                say("slow enough for the cap, vy=%.2f", mc.player.getVelocity().y);
            }

            // The in-place drop comes first: once it is running it is a descent onto the landing
            // point, and the arrest below would take the pitch back to level and glide straight
            // past that point - it triggers exactly when the drop makes the descent fast again.
            if (pitchLeveled && inPlaceDrop()) {
                aimAtLandingPoint();
                return;
            }

            // A dive that is close enough that the bleed can no longer finish is taken over: this
            // module holds the level pitch, which is where the bleed is strongest (the lift term is
            // proportional to cos^2 of the pitch, so only level flight gets the full 11 percent per
            // tick). ElytraFly+ stops writing the body rotation while isSaving() is true, so there
            // is one writer, not a fight over the pitch.
            //
            // The hold is deliberately not permanent. Pitching up cannot recover a dive - the climb
            // term needs horizontal speed and cancels at the same cos^2 the lift does, and at
            // exactly -90 both are gone - so the level pitch stays until the descent is back under
            // the cap. After that the fall distance is already wiped and the rotation goes back to
            // whoever is flying the moment they ask for height; holding it until the landing is what
            // made "dive, then pull up" impossible to get out of.
            if (arrestingDive()) {
                if (!pitchLeveled) {
                    arrested = true;
                    say("dive arrest: vy=%.2f with %s blocks of ground - holding level so the cap can run",
                        mc.player.getVelocity().y, groundText());
                }
                levelPitchForGlide();
            } else if (pitchLeveled && recovering()) {
                say("descent is under the cap and the player is asking for height - rotation handed back");
                arrested = false;
                giveUpPitch(false);
            } else if (pitchLeveled) {
                // A glide this module already owns has to stay level until it is safe: the mouse, or
                // ElytraFly+'s keys, would dive it straight back through the cap.
                levelPitchForGlide();
            }
            return;
        }

        if (wasGliding) {
            wasGliding = false;
            say("glide ended");
        }

        // Every way a fall can end without a glide. Water and vehicles are also where Grim's
        // ElytraI and ElytraH would fire, so they end the attempt instead of being skipped.
        if (mc.player.isOnGround() || mc.player.getAbilities().flying || mc.player.isClimbing()
            || mc.player.isTouchingWater() || mc.player.hasVehicle()) {
            if (needsRestoreClick()) {
                clearMovementInput();
                restoreArmed = true;
                say("dropping the input for a tick so the chest swap back is a legal click");
                return;
            }

            endFall();
            return;
        }

        // Arm on the fall itself, and arm early. The elytra has to be on the chest for a while
        // before the command is safe to send: the click is applied by the server's main thread
        // while Grim reads the chest plate straight off the platform player, so a command that
        // arrives in the same tick as the click looks like "no elytra" to Grim, which cancels it
        // and resyncs - the client still glides, the server never caps the fall, and the landing
        // hurts exactly as much as it did without the module.
        // The elytra goes on while the ground below is still far enough to matter, not once the fall
        // is already three blocks deep: the swap itself costs two ticks (drop the input, click) and
        // the lead costs more, and all of that has to be over before the glide can start. A fall
        // that cannot hurt (a one block hop, the ground right under the feet) never gets this far.
        double ground = groundDistance();
        if (!Double.isFinite(ground) || ground < minFallDistance.get()) return;

        if (!announced) {
            announced = true;
            say("falling: %.2f blocks down, ground %s blocks away (%s ticks), velocity %.2f",
                fallDistance(), groundText(), ticksText(), mc.player.getVelocity().y);
        }

        if (paused()) {
            sayOnce("paused (mace)");
            return;
        }

        if (guarded()) {
            sayOnce("guarded (levitation)");
            return;
        }

        if (!equipElytra()) return;

        if (swappedAtAge >= 0 && mc.player.age - swappedAtAge < equipLeadTicks.get()) {
            sayOnce("waiting: the elytra has been on for %d of %d ticks", mc.player.age - swappedAtAge, equipLeadTicks.get());
            return;
        }

        if (fallDistance() < minFallDistance.get()) {
            sayOnce("waiting: only %.2f blocks fallen so far", fallDistance());
            return;
        }

        if (!glideStartIsDue()) {
            if (inPlace.get()) {
                sayOnce("waiting: %.2f blocks of ground left, the bleed needs %.2f and the press lead is %.2f",
                    ground, bleedClearance(), pressLead());
            }
            return;
        }

        // A press that is still settling cannot be followed by a fresh one in the same tick.
        if (waitOneTick) {
            waitOneTick = false;
            return;
        }

        if (attempts >= 4) return;

        if (horizontalSpeed() > maxHorizontalSpeed.get()) {
            sayOnce("waiting: horizontal speed %.2f is above the cap", horizontalSpeed());
            return;
        }

        if (System.currentTimeMillis() - lastGlideStart < cooldownTicks.get() * 50L) {
            sayOnce("waiting: %.1f ticks of cooldown left",
                (cooldownTicks.get() * 50L - (System.currentTimeMillis() - lastGlideStart)) / 50.0);
            return;
        }

        pressJump();
    }

    /**
     * Starts the glide the way a player does: press the jump key and let
     * {@code ClientPlayerEntity#tickMovement} send {@code START_FALL_FLYING}. Vanilla flips its own
     * glide flag in that same tick, so client physics, the command and the following input packet
     * all describe one state.
     */
    private void pressJump() {
        if (mc.options.jumpKey.isPressed()) {
            // A key that is already down leaves the last reported input at jump = true, which
            // ElytraB reads as "started gliding without releasing jump". Drop it for this tick; the
            // press follows on the next one, after that release has been reported.
            mc.options.jumpKey.setPressed(false);
            jumpKeyOverridden = true;
            say("jump key was already down, released it for a tick before pressing");
            return;
        }

        levelPitchForGlide();

        mc.options.jumpKey.setPressed(true);
        jumpKeyOverridden = true;
        liftKeyAtHead = true;
        attempts++;
        say("pressed jump (attempt %d), %s ticks to the ground, elytra on for %s tick(s)",
            attempts, ticksText(), swappedAtAge < 0 ? "many" : String.valueOf(mc.player.age - swappedAtAge));
    }

    /**
     * Writes the deploy pitch and snaps "the previous pitch" with it, so the camera does not sweep
     * down from the old angle. This is the difference between a glide that the server caps at one
     * block of fall distance and a dive that it charges in full: {@code limitFallDistance} only
     * runs while the vertical speed is above -0.5.
     *
     * <p>With {@code in-place} on this is also where the camera is handed over to the module's own
     * view, once per fall. The body rotation written from then on is the module's - level here,
     * aimed at the landing point during the drop - and the mouse goes to the view instead of the
     * entity, so the rotation the server receives and the rotation the player is looking with stop
     * being the same value. That is the point: the player can look wherever they like while the
     * glide is flown by the module, and the view is what goes back to the body when the fall ends.
     */
    private void levelPitchForGlide() {
        if (!levelPitch.get()) return;

        if (!pitchLeveled) {
            pitchLeveled = true;
            landingPoint = null;

            if (inPlace.get()) beginFreeView();
            else savedPitch = mc.player.getPitch();
        }

        if (freeView) {
            writeBodyRotation(mc.player.getYaw(), (float) (double) deployPitch.get());
            return;
        }

        float pitch = (float) (double) deployPitch.get();
        mc.player.setPitch(pitch);
        VersionCompat.setPreviousRotation(mc.player, mc.player.getYaw(), pitch);
    }

    /**
     * Gives the rotation back at the end of a fall. The body has been carrying the module's
     * rotation, so the player's own view goes back onto it - which is also the rotation the server
     * receives on the next movement packet, the one that says where the player was looking all
     * along. With the view held ({@code in-place}) that happens on every hand-back, whatever
     * {@code restore-pitch} says: the camera has been free for the whole fall, and handing the body
     * back the aim instead would snap the view to a pitch the player never chose.
     */
    private void releasePitch() {
        giveUpPitch(true);
    }

    /**
     * Drops the hold on the body rotation. {@code restoreSaved} is for the paths where putting the
     * pre-glide pitch back is wanted; the pull-up hand-back passes false, because the pitch the
     * module saved is the one that was diving at the ground, and the player has just asked to
     * climb - putting that back would be a request to keep diving.
     */
    private void giveUpPitch(boolean restoreSaved) {
        boolean hadIt = pitchLeveled;
        boolean hadView = freeView;
        Float pitch = savedPitch;

        pitchLeveled = false;
        freeView = false;
        savedPitch = null;
        landingPoint = null;

        if (!hadIt || mc.player == null) return;

        if (hadView) {
            writeBodyRotation(viewYaw, viewPitch);
            return;
        }

        if (!restoreSaved || !restorePitch.get() || pitch == null) return;

        mc.player.setPitch(pitch);
        VersionCompat.setPreviousRotation(mc.player, mc.player.getYaw(), pitch);
    }

    /** Writes the module's rotation to the entity, the rotation the movement packet carries. */
    private void writeBodyRotation(float yaw, float pitch) {
        float clamped = MathHelper.clamp(pitch, -90.0f, 90.0f);

        mc.player.setYaw(yaw);
        mc.player.setPitch(clamped);

        // The entity's own interpolation fields move with the value just written, so the rendered
        // model and getRotationVec see the same direction the physics and the packet use.
        VersionCompat.setPreviousRotation(mc.player, yaw, clamped);
    }

    /**
     * True while the rest of the fall is being spent as a drop onto the landing point.
     *
     * <p>The free view is part of the mode, so it is part of the guard: a fall that started with
     * {@code in-place} off has the camera on the body, and dropping the aim into that fall would
     * drag the view through the aim. Toggling the setting on takes effect on the next fall.
     */
    private boolean inPlaceDrop() {
        if (!inPlace.get() || !levelPitch.get() || !freeView || !capWindow) {
            landingPoint = null;
            return false;
        }

        double ground = groundDistance();
        double predicted = predictedFallDistance(ground, Math.max(0.0, -mc.player.getVelocity().y));

        // The point is abandoned the moment the drop would no longer be free - which is what
        // happens when the drift carries the player out over ground the raycast no longer sees -
        // and the level pitch comes back, so the fall is still saved even when the drop is not.
        if (ground > scanRange.get() || predicted > SAFE_FALL - inPlaceMargin.get()) {
            landingPoint = null;
            return false;
        }

        if (landingPoint == null) {
            landingPoint = groundHit();
            if (landingPoint == null) return false;

            say("in-place: %s blocks of ground left and the drop adds %.2f of fall distance - aiming at the landing point",
                groundText(), predicted);
        }

        return true;
    }

    /**
     * Writes the aim at the landing point to the body. The point is the ground the module's own
     * raycast saw when the drop took over, so this is the ground the player is landing on, and it
     * does not move as the player drifts - the aim keeps pointing back at it, which is what turns
     * the glide's forward push around instead of letting it carry the player past the point.
     *
     * <p>The pitch is measured from the horizon and positive looks down, the same value
     * {@code Entity#getRotationVector} turns into a direction, so the body looks at the point from
     * the eyes: {@code -atan2(dy, horizontal)}. The body yaw is only taken from the point when the
     * point is off to a side; a point straight below has no yaw to give, and inventing one would
     * spin the body for nothing. It is then expressed as the shortest turn from the yaw the body
     * already has, because {@code atan2} answers in -180..180 and a target dead astern would flip
     * the body between 179 and -179 - which is exactly the modulo-360 signature Grim's
     * {@code AimModulo360} looks for.
     */
    private void aimAtLandingPoint() {
        Vec3d target = landingPoint;
        if (target == null) return;

        Vec3d eyes = mc.player.getEyePos();
        double dx = target.x - eyes.x;
        double dy = target.y - eyes.y;
        double dz = target.z - eyes.z;
        double horizontal = Math.hypot(dx, dz);

        float yaw = mc.player.getYaw();
        if (horizontal > AIM_DEAD_ZONE) {
            float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            yaw += MathHelper.wrapDegrees(targetYaw - yaw);
        }

        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));

        writeBodyRotation(yaw, pitch);
    }

    /**
     * The fall distance the server will have accumulated when a straight-down drop of {@code drop}
     * blocks lands, starting from the descent the glide has now.
     *
     * <p>This replays the two vanilla lines that decide it. The movement packet's dy is added to
     * the fall distance, and {@code Entity#limitFallDistance} puts it back to one block on every
     * tick the descent is still above {@link #CAP_SPEED} - so the first blocks of the drop are
     * free, and a glide that is already slow can drop from further up than one that levelled out
     * late. A straight-down pitch is the fastest the descent can grow, which makes this the worst
     * case: any shallower aim spends longer under the cap and lands cheaper.
     */
    private double predictedFallDistance(double drop, double speed) {
        // groundDistance() answers with MAX_VALUE when the raycast found no ground inside
        // scan-range; there is nothing to predict a landing on.
        if (drop > scanRange.get()) return Double.MAX_VALUE;

        double fall = CAPPED_FALL;
        double left = drop;
        double vy = speed;

        for (int tick = 0; tick < 64 && left > 0.0; tick++) {
            vy = (vy + GRAVITY) * DRAG;

            double step = Math.min(vy, left);
            left -= step;
            fall += step;

            if (vy < CAP_SPEED) fall = Math.min(fall, CAPPED_FALL);
        }

        return fall;
    }


    /** Puts an elytra in the chest slot, or reports that there is nothing to put there. */
    private boolean equipElytra() {
        if (isElytraOn()) return true;

        // One click per fall. The chest slot only changes on this client when the server answers,
        // and a second click sent before that would swap the elytra straight back out.
        if (swapSent) {
            sayOnce("waiting for the elytra swap to come back from the server");
            return false;
        }
        if (warnedNoElytra) {
            restoreMovementInput();
            return false;
        }

        // A nearly broken elytra cannot glide, and Grim reads the same damage value.
        FindItemResult elytra = InvUtils.find(stack -> stack.isOf(Items.ELYTRA)
            && stack.getDamage() < stack.getMaxDamage() - 1);

        if (!elytra.found()) {
            restoreMovementInput();
            error("No usable elytra in the inventory.");
            warnedNoElytra = true;
            return false;
        }

        // Grim's MultiActionsC flags an inventory click that arrives while the last input packet
        // still reports a key down (KnownInput#moving counts jump as well as the four directions).
        // A vanilla client cannot produce that: the GUI that allows the click is also what stops
        // the input. So the input is dropped for one tick, and the click goes out on the next one,
        // when the server has already seen a clean input packet. The keys are handed straight back
        // after the click, so the player loses one tick of steering and nothing else.
        if (!swapArmed) {
            clearMovementInput();
            swapArmed = true;
            say("dropping the input for a tick so the swap is a legal inventory click");
            return false;
        }

        elytraOrigin = elytra.slot();
        // Chest is armor index 37 in Meteor's slot layout, which is the toArmor(2) call Meteor's
        // own ChestSwap and ElytraFlightMode use.
        InvUtils.move().from(elytraOrigin).toArmor(2);
        swapped = true;
        swapSent = true;
        swappedAtAge = mc.player.age;
        swapArmed = false;
        restoreMovementInput();
        say("swapped an elytra from slot %d into the chest slot", elytraOrigin);
        return isElytraOn();
    }

    /** Drops every key that KnownInput#moving and the sprint flag look at, remembering them. */
    private void clearMovementInput() {
        savedKeys = new boolean[]{
            mc.options.forwardKey.isPressed(),
            mc.options.backKey.isPressed(),
            mc.options.leftKey.isPressed(),
            mc.options.rightKey.isPressed(),
            mc.options.jumpKey.isPressed(),
            mc.options.sprintKey.isPressed()
        };

        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
    }

    /** Hands the keys back exactly as they were before the swap. */
    private void restoreMovementInput() {
        boolean[] saved = savedKeys;
        savedKeys = null;
        swapArmed = false;

        if (saved == null) return;

        mc.options.forwardKey.setPressed(saved[0]);
        mc.options.backKey.setPressed(saved[1]);
        mc.options.leftKey.setPressed(saved[2]);
        mc.options.rightKey.setPressed(saved[3]);
        mc.options.jumpKey.setPressed(saved[4]);
        mc.options.sprintKey.setPressed(saved[5]);
    }

    /** True when the chest item has to go back and the click has not been sent yet. */
    private boolean needsRestoreClick() {
        return swapped && restoreChestplate.get() && elytraOrigin >= 0 && isElytraOn();
    }

    /**
     * Sends the click that puts the chest item back. The elytra displaced it into the slot
     * the elytra came from, so swapping the two slots again restores both. Never called without the
     * clean-input tick that {@code restoreArmed} arranges.
     */
    private void clickRestoreChest() {
        int origin = elytraOrigin;

        swapped = false;
        swapSent = false;
        swappedAtAge = -1;
        elytraOrigin = -1;

        if (origin < 0 || mc.player == null) return;

        InvUtils.move().fromArmor(2).to(origin);
    }

    /** Puts the chest item back right now, for the paths where a deferred click is not possible. */
    private void restoreChestNow() {
        if (!needsRestoreClick()) {
            swapped = false;
            swapSent = false;
            swappedAtAge = -1;
            elytraOrigin = -1;
            return;
        }

        clickRestoreChest();
    }

    /** True while any key KnownInput#moving or the sprint flag looks at is down. */
    private boolean inputIsDirty() {
        return mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
            || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed()
            || mc.options.jumpKey.isPressed() || mc.options.sprintKey.isPressed();
    }

    /** The tail of a finished fall: state and keys, no inventory clicks. */
    private void finishFall() {
        boolean missedCap = wasGliding && !capWindow;
        boolean wasArrest = arrested;

        releaseJumpKey();
        releasePitch();
        restoreMovementInput();

        if (missedCap && mc.player != null) {
            if (wasArrest) {
                say("the dive never got under the cap (vy=%.2f) - raise scan-range or arrest-margin",
                    mc.player.getVelocity().y);
            } else {
                say("landed before the glide got under the cap (vy=%.2f) - raise %s",
                    mc.player.getVelocity().y, inPlace.get() ? "in-place-lead" : "deploy-ticks");
            }
        }

        attempts = 0;
        warnedNoElytra = false;
        announced = false;
        saidOnce = false;
        waitOneTick = false;
        wasGliding = false;
        fallStartY = Double.NaN;
        capWindow = false;
        arrested = false;
    }

    /** Everything a finished fall has to undo. */
    private void endFall() {
        if (needsRestoreClick() && inputIsDirty()) {
            // No tick left to drop the input in (a disable, a world change): leave the elytra on
            // rather than send a click that Grim's MultiActionsC would flag.
            sayOnce("chest item left on: a key is held, so the swap back would be a flagged click");
        } else {
            clickRestoreChest();
        }

        finishFall();
    }

    /**
     * Drops the jump key if this module pressed it. A key the player is holding is deliberately not
     * restored: telling the player's own press apart from the module's needs a second look at the
     * physical keyboard state, and every version past 1.21.8 changed that call's signature. The
     * only cost is that a held jump key reads as up until it is pressed again.
     */
    private void releaseJumpKey() {
        if (!jumpKeyOverridden && !liftKeyAtHead) return;

        liftKeyAtHead = false;
        jumpKeyOverridden = false;
        mc.options.jumpKey.setPressed(false);
    }

    private void clearState() {
        elytraOrigin = -1;
        swapped = false;
        swapSent = false;
        swappedAtAge = -1;
        liftKeyAtHead = false;
        waitOneTick = false;
        jumpKeyOverridden = false;
        warnedNoElytra = false;
        announced = false;
        attempts = 0;
        wasGliding = false;
        saidOnce = false;
        heartbeatTicks = 0;
        fallStartY = Double.NaN;
        pitchLeveled = false;
        savedPitch = null;
        landingPoint = null;
        freeView = false;
        prevViewYaw = 0.0f;
        prevViewPitch = 0.0f;
        viewYaw = 0.0f;
        viewPitch = 0.0f;
        capWindow = false;
        arrested = false;
        swapArmed = false;
        restoreArmed = false;
        savedKeys = null;
        lastHealth = Float.NaN;
    }

    /**
     * True while the held item makes a glide counterproductive.
     *
     * <p>Nothing pauses this module for another flight module being enabled. ElytraFly+ is a flight
     * assistant, not a fall-saver - it only deploys an elytra when {@code auto-takeoff} fires on a
     * fresh jump - and standing down for it is how a fall became a death. It gets out of the way in
     * the one place it has to, the body rotation, through {@link #isSaving()}.
     */
    private boolean paused() {
        return pauseOnMace.get()
            && (mc.player.getMainHandStack().isOf(Items.MACE) || mc.player.getOffHandStack().isOf(Items.MACE));
    }

    /**
     * True while this module owns the glide: either it deployed the glide itself, or it has taken
     * over a dive that was about to be charged in full. Both are the level-pitch posture.
     *
     * <p>ElytraFly+ asks this before it takes the body rotation back over. Two writers of one pitch
     * on a single tick is a dive: the cap only runs while the look is level, so a flight rotation
     * landing on top of the held level pitch charges the whole fall.
     */
    public boolean isSaving() {
        return pitchLeveled && mc.player != null && mc.player.isGliding();
    }

    // ---- FreeView ----

    /**
     * Whether the camera renders the module's view instead of the body rotation.
     *
     * <p>Only the in-place mode takes the camera. It does that for the whole time it owns the body
     * rotation - the level glide included, not just the drop - because the body rotation is the
     * module's from the first tick it writes it, and a camera that kept following the body would be
     * dragged through the deploy pitch and then the aim.
     */
    @Override
    public boolean isViewLockActive() {
        return freeView && pitchLeveled;
    }

    /**
     * Mouse look, while the module owns the body rotation. The delta is applied to the view only;
     * the body rotation is written once per tick, so the value that goes into the movement packet
     * is never a half-updated one - and the mouse never reaches it at all.
     */
    @Override
    public void applyLookDelta(double deltaYaw, double deltaPitch) {
        if (!isViewLockActive()) return;

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

    /**
     * Takes the camera over, starting from wherever the player is looking at that moment.
     *
     * <p>Another module may still be holding the view on this tick - ElytraFly+, whose own tick
     * handler stands down only once {@link #isSaving()} is true, which is this tick, or a derp - and
     * while it holds it the mouse has been going to its view rather than to the entity. Taking the
     * handover from there is what makes the camera carry on from the same angle instead of jumping
     * back to the body rotation the other module was writing.
     */
    private void beginFreeView() {
        // Read the handover before this module counts itself as holding the view, so that the answer
        // is the other module rather than this one.
        FreeView held = FreeView.heldByOther(this);
        freeView = true;

        if (held != null) {
            viewYaw = held.getCameraYaw();
            viewPitch = held.getCameraPitch();
            prevViewYaw = held.getPrevCameraYaw();
            prevViewPitch = held.getPrevCameraPitch();
            return;
        }

        viewYaw = mc.player.getYaw();
        viewPitch = mc.player.getPitch();
        prevViewYaw = viewYaw;
        prevViewPitch = viewPitch;
    }

    /** Conditions that make the server reject the glide command (Grim's other elytra checks). */
    private boolean guarded() {
        return levitationGuard.get() && mc.player.hasStatusEffect(StatusEffects.LEVITATION);
    }

    private boolean isElytraOn() {
        return mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
    }

    /**
     * Blocks fallen since the player last left the ground, measured from y. The client's own
     * {@code fallDistance} field is not used: in 1.21.4 it is never updated on the client (it stayed
     * at 0.00 through an in-game fall), so the module would never arm. Y is synced every tick and
     * the highest point reached is the same quantity vanilla accumulates.
     */
    private double fallDistance() {
        double y = mc.player.getY();

        // Anything that ends a fall in vanilla also ends it here.
        if (mc.player.isOnGround() || mc.player.isGliding() || mc.player.isTouchingWater()
            || mc.player.isClimbing() || mc.player.getAbilities().flying || mc.player.hasVehicle()) {
            fallStartY = y;
            return 0.0;
        }

        if (Double.isNaN(fallStartY) || y > fallStartY) fallStartY = y;
        return Math.max(0.0, fallStartY - y);
    }

    /**
     * How many ticks above the ground the glide has to start. A level glide bleeds vertical speed by
     * {@link #GLIDE_BLEED} per tick (the lift term against drag), and {@code limitFallDistance}
     * only caps the fall once that speed is above {@link #CAP_SPEED}, so a faster fall needs a longer
     * run-up than the configured minimum. This is that run-up, never less than {@code deploy-ticks}.
     */
    private double deployTicks() {
        double speed = -mc.player.getVelocity().y;
        double bleed = speed > CAP_SPEED ? Math.log(CAP_SPEED / speed) / Math.log(GLIDE_BLEED) : 0.0;
        return Math.max(deployTicks.get(), bleed + 1.0);
    }

    /**
     * True when the glide has to start now.
     *
     * <p>Without {@code in-place} this is the tick gate: the configured run-up, extended for the
     * current speed. It is generous by design - {@code ticks-to-ground at the current speed} is
     * compared against {@code ticks of bleed}, and the glide decelerates while it bleeds, so the
     * press lands at roughly twice the ground the bleed actually needs. Every one of those extra
     * blocks is then glided level at about 0.17 blocks a tick, which is where the forward drift of
     * a save comes from.
     *
     * <p>{@code in-place} gates on the distance instead, because that is the quantity the bleed is
     * measured in: {@link #bleedClearance()} blocks of ground are what the level glide eats before
     * the cap can run, and {@link #pressLead()} is the room kept on top of it for the press itself
     * and for the configurable slack. Every block the gate keeps beyond the physical minimum is
     * glided level at 0.17 blocks a tick before the cap can run, so this is also where the forward
     * drift of a save is decided.
     */
    private boolean glideStartIsDue() {
        if (!inPlace.get()) return ticksToGround() <= deployTicks();

        double ground = groundDistance();
        return ground <= scanRange.get() && ground <= bleedClearance() + pressLead();
    }

    /**
     * Blocks of ground a level glide eats while it bleeds the current descent back down to
     * {@link #CAP_SPEED}: the geometric sum {@code (speed - CAP_SPEED) / (1 - GLIDE_BLEED)}. This is
     * the physical minimum a fall needs in order to be capped at all, and the reason both the dive
     * arrest and the in-place glide start are measured in distance rather than in ticks.
     */
    private double bleedClearance() {
        double speed = Math.max(0.0, -mc.player.getVelocity().y);
        return speed > CAP_SPEED ? (speed - CAP_SPEED) / (1.0 - GLIDE_BLEED) : 0.0;
    }

    /**
     * The room the in-place glide start keeps beyond {@link #bleedClearance()}: the configured
     * lead, plus {@link #PRESS_LEAD_TICKS} ticks of the fall as it is right now.
     *
     * <p>The per-tick term is the speed - the player keeps free-falling while the press is being
     * arranged - plus the rate the bleed itself grows at, {@code GRAVITY / (1 - GLIDE_BLEED)}, which
     * is how much longer the bleed gets for every tick the press slips. Delaying the start by one
     * tick therefore costs both, and this is the room that pays for it.
     */
    private double pressLead() {
        double speed = Math.max(0.0, -mc.player.getVelocity().y);
        return inPlaceLead.get() + (speed + GRAVITY / (1.0 - GLIDE_BLEED)) * PRESS_LEAD_TICKS;
    }

    /**
     * True when a glide that is already running is diving at the ground too fast and too low for the
     * cap to start by itself.
     *
     * <p>This is the case a glide alone does not save. ElytraFly+ steers the body with the keys, so
     * sneak held writes {@code down-pitch} and drives the body at the floor; the fall distance then
     * accumulates for the whole dive, because {@code Entity#limitFallDistance} only runs while the
     * vertical speed is above {@link #CAP_SPEED}. A vanilla dive, or the mouse held down, is the
     * same fall.
     *
     * <p>The distance, not a tick count, is the trigger. A level glide eats the geometric sum
     * {@code (speed - CAP_SPEED) / (1 - GLIDE_BLEED)} of ground while it slows from {@code speed}
     * down to the cap, and starting any later than that is a partial save: the vertical speed is
     * still high, so measuring "ticks at the current speed" would fire far too early - a terminal
     * dive would be arrested at well over a hundred blocks and every intentional dive would be
     * neutered. {@code arrest-margin} is the room left on top of the physical minimum.
     *
     * <p>{@code scan-range} has to cover the arrest distance for the ground to be seen at all; a
     * terminal dive needs roughly thirty blocks of it.
     */
    private boolean arrestingDive() {
        if (!arrestDive.get() || !levelPitch.get()) return false;

        // A mace dive is the point of the mace, which cancels the landing damage itself when the
        // smash lands.
        if (paused()) return false;

        double speed = -mc.player.getVelocity().y;
        if (speed <= CAP_SPEED) return false;

        double ground = groundDistance();
        if (!Double.isFinite(ground)) return false;

        return ground <= bleedClearance() + arrestMargin.get();
    }

    /**
     * True when the player is asking to climb or level off, which is the signal to hand the body
     * rotation back once the descent is under the cap.
     *
     * <p>Two control schemes reach the body rotation, and they need different signals. With
     * ElytraFly+ {@code lock-view} the keys own it - space climbs, a direction key alone levels off,
     * and anything held together with sneak is still a dive - and the mouse never touches the body.
     * With the mouse on the body, a look clearly above the held pitch <em>is</em> the request.
     *
     * <p>Space only counts as "up" under the key scheme: a vanilla glide ignores the jump key, so
     * reading it there would end a save for a key that does nothing.
     */
    private boolean recovering() {
        if (elytraFlySteersByKeys()) {
            boolean up = mc.options.jumpKey.isPressed();
            boolean down = mc.options.sneakKey.isPressed();
            boolean direction = mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
                || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed();

            return up || (direction && !down);
        }

        return mc.player.getPitch() < deployPitch.get() - RECOVERY_PITCH;
    }

    /** True while ElytraFly+ is configured to steer the body rotation with the keys. */
    private boolean elytraFlySteersByKeys() {
        ElytraFlyPlus elytraFly = Modules.get().get(ElytraFlyPlus.class);
        return elytraFly != null && elytraFly.isActive() && elytraFly.steersByKeys();
    }

    /** Ticks until the feet reach the first block below, at the current vertical speed. */
    private double ticksToGround() {
        double speed = -mc.player.getVelocity().y;
        if (speed <= 0.01) return Double.MAX_VALUE;
        return groundDistance() / speed;
    }

    /**
     * Distance from the feet straight down to the first block with collision, or {@link
     * Double#MAX_VALUE} when {@code scan-range} does not reach one - which is why the callers that
     * care about "no ground at all" compare against the scan range rather than trusting a finite
     * sentinel to fail an {@code isFinite} test.
     */
    private double groundDistance() {
        Vec3d hit = groundHit();
        return hit == null ? Double.MAX_VALUE : Math.max(0.0, mc.player.getY() - hit.y);
    }

    /**
     * The point on the first block below the feet, or null when {@code scan-range} does not reach
     * one. This is the module's own vertical raycast: the same ray says how far the drop is and,
     * in in-place mode, where the landing point is.
     */
    private Vec3d groundHit() {
        if (mc.world == null || mc.player == null) return null;

        Vec3d start = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d end = start.add(0.0, -scanRange.get(), 0.0);

        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));

        if (hit.getType() == HitResult.Type.MISS) return null;
        return hit.getPos();
    }

    private double horizontalSpeed() {
        Vec3d velocity = mc.player.getVelocity();
        return Math.hypot(velocity.x, velocity.z);
    }

    // ---- Debug chat ----

    /**
     * One line a second while the player is off the ground, whatever the module decides to do with
     * the fall. This is the line that says "the module is running and sees fallDistance 4.2" when
     * every other message is silent.
     */
    private void heartbeat() {
        if (!debug.get()) return;

        if (mc.player.isOnGround()) {
            heartbeatTicks = 0;
            return;
        }

        if (heartbeatTicks++ % 20 != 0) return;

        info("air: fd=%.2f y=%.2f vy=%.2f pitch=%.0f gliding=%s flying=%s climbing=%s water=%s vehicle=%s ground=%s window=%s",
            fallDistance(), mc.player.getY(), mc.player.getVelocity().y, mc.player.getPitch(),
            mc.player.isGliding(), mc.player.getAbilities().flying, mc.player.isClimbing(),
            mc.player.isTouchingWater(), mc.player.hasVehicle(), groundText(),
            Double.isFinite(groundDistance()) && groundDistance() >= minFallDistance.get());
    }

    /**
     * Names the damage the moment it lands, with the amount, whenever this module had something to
     * do with the fall. That is what separates "the save did not work" from "the save worked and
     * something else charged the player".
     */
    private void reportDamage() {
        float health = mc.player.getHealth();
        float last = lastHealth;
        lastHealth = health;

        if (Float.isNaN(last) || health >= last) return;
        if (!debug.get() || !(swapped || pitchLeveled || capWindow || wasGliding)) return;

        info("took %.1f damage (health %.1f -> %.1f), capWindow=%s gliding=%s",
            last - health, last, health, capWindow, mc.player.isGliding());
    }

    private void say(String format, Object... args) {
        if (!debug.get()) return;
        info(format, args);
    }

    private void sayOnce(String format, Object... args) {
        if (!debug.get() || saidOnce) return;
        saidOnce = true;
        info(format, args);
    }

    /** Ticks to the ground as text, because {@link #ticksToGround()} uses MAX_VALUE for "no idea". */
    private String ticksText() {
        double ticks = ticksToGround();
        return Double.isFinite(ticks) && ticks < 1.0e6 ? String.format("%.1f", ticks) : "n/a";
    }

    /** Ground distance as text, for the same reason. */
    private String groundText() {
        double distance = groundDistance();
        return Double.isFinite(distance) && distance < 1.0e6 ? String.format("%.1f", distance) : "n/a";
    }
}
