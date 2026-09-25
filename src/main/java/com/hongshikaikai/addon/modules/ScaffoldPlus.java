package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.VersionCompat;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A scaffold that places blocks the way a vanilla client does, so that GrimAC's placement
 * checks have nothing to report.
 *
 * <h2>The one-tick trick</h2>
 * A block placement packet carries no rotation: the server validates the click with the
 * rotation and position of the <em>last movement packet it received</em>. In a vanilla tick the
 * order on the wire is
 *
 * <pre>
 *     [interact]  ...  [look]        <- handleInputEvents() runs before ClientWorld.tickEntities()
 * </pre>
 *
 * so when the server handles the interact the player is still standing at the previous tick's
 * position, looking in the previous tick's direction. Grim's {@code RotationPlace} and
 * {@code PositionPlace} reproduce that and ray trace from {@code player.x/y/z} with
 * {@code player.yaw/pitch} - the values of the last movement packet.
 *
 * <p>This module therefore never aims and places in the same tick. It aims while the movement
 * packet is being built ({@link SendMovementPacketsEvent.Pre}, where the entity position and
 * rotation are exactly the ones about to be serialised) and sends the interact one tick later,
 * from {@link TickEvent.Pre}. At that moment the server's copy of the player is positionally and
 * rotationally identical to the copy the aim was computed from, so the ray the server traces is
 * the ray this module traced. The placement packet also still goes out <em>before</em> this
 * tick's movement packet, which is what Grim's {@code Post} check wants.
 *
 * <h2>Aiming without desyncing the walk</h2>
 * Grim predicts the player's horizontal movement with
 * {@code getMovementResultFromInput(player, input, speed, player.yaw)}: the WASD input is rotated
 * by the <em>yaw of the movement packet</em>. The client's own physics ran earlier in the same
 * tick, with the yaw the mouse gave it. Spoofing the yaw in the packet alone therefore makes the
 * client walk one way and the server predict another - a Simulation offset that appears as soon as
 * a movement key is held.
 *
 * <p>So the default {@link RotationMode#Pitch} aims <em>only the pitch</em> and never touches the
 * yaw. The walking prediction reads the yaw and nothing else: a check of the whole prediction
 * engine shows the pitch is used only by {@code PredictionEngineWater}, {@code
 * PredictionEngineElytra} and the firework uncertainty box, all of which the module steps aside
 * for. Sub-degree pitch corrections are held rather than applied (see {@code aimAt}), so a pitch
 * change is either absent or a whole degree or more, which keeps clear of Grim's {@code Baritone}
 * check as well.
 *
 * <h2>Everything else is vanilla</h2>
 * <ul>
 *     <li>The hit is produced by {@code World.raycast} from the player's own eye along exactly the
 *         {@code (yaw, pitch)} that will be sent, so the clicked face is unoccluded
 *         ({@code PositionPlace}), the cursor lies inside the face ({@code FabricatedPlace},
 *         {@code InvalidPlaceA/B}) and the face id is real ({@code InvalidPlaceB}).</li>
 *     <li>The clicked block is a real, non-air, non-liquid neighbour ({@code AirLiquidPlace}).</li>
 *     <li>The cursor never exceeds the block interaction range ({@code FarPlace}) or the vanilla
 *         server's own reach test.</li>
 *     <li>At most one placement per tick ({@code MultiPlace}), through
 *         {@code ClientPlayerInteractionManager.interactBlock}, so the 1.19+ sequence id and the
 *         swing packet come from vanilla code ({@code BadPacketsH}).</li>
 *     <li>No velocity is ever written. Fast tower / no-slow style velocity injection is
 *         deliberately not offered.</li>
 * </ul>
 */
public class ScaffoldPlus extends Module {
    /** Rotation priority handed to Meteor's {@link Rotations}. Same as Meteor's own scaffold. */
    private static final int ROTATION_PRIORITY = 50;

    /** The six faces, in the order the aim points are generated. */
    private static final Direction[] FACES = Direction.values();

    /** No reachable pitch for a face. */
    private static final double[] EMPTY = new double[0];

    /** How many candidate blocks are allowed to be ray traced per tick before giving up. */
    private static final int MAX_RAYCAST_TARGETS = 12;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlacement = settings.createGroup("Placement");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // ---- General ----

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Which blocks may be used.")
        .build()
    );

    private final Setting<Filter> blocksFilter = sgGeneral.add(new EnumSetting.Builder<Filter>()
        .name("blocks-filter")
        .description("Whether the block list is a whitelist or a blacklist.")
        .defaultValue(Filter.Blacklist)
        .build()
    );

    private final Setting<Boolean> onlyOnClick = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-click")
        .description("Only work while the use key is held.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Swap to a block in the hotbar before placing. Off: only the held item is used.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Give the original hotbar slot back after each placement. Off: the module switches to whichever hotbar slot holds the blocks and leaves it there, so the block really is in your hand and you change back yourself - exactly like pressing a hotbar key.")
        .defaultValue(false)
        .visible(autoSwitch::get)
        .build()
    );


    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Render the client-side swing. Off: only the swing packet is sent.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks to wait between placements. 0 places once per tick, which is the most vanilla can do anyway.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Boolean> pauseInVehicle = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-in-vehicle")
        .description("Do nothing while riding an entity.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print a line to chat every time a block is placed.")
        .defaultValue(false)
        .build()
    );

    // ---- Placement ----

    private final Setting<RotationMode> rotation = sgPlacement.add(new EnumSetting.Builder<RotationMode>()
        .name("rotation")
        .description("Pitch: only the pitch is aimed, the player keeps their own yaw. Grim predicts walking with the yaw from the movement packet, so a spoofed yaw desyncs the movement prediction (Simulation) while a spoofed pitch does not. Full: aim the yaw too - the placement is easier, but Simulation will flag while a movement key is held.")
        .defaultValue(RotationMode.Pitch)
        .build()
    );

    private final Setting<Double> maxReach = sgPlacement.add(new DoubleSetting.Builder()
        .name("max-reach")
        .description("How far from the eye a placement may be. Vanilla's block interaction range is 4.5; Grim's FarPlace allows 4.5 plus the movement threshold, so 4.5 is the ceiling.")
        .defaultValue(4.5)
        .min(1.0)
        .max(4.5)
        .sliderRange(1.0, 4.5)
        .build()
    );

    private final Setting<Double> aheadDistance = sgPlacement.add(new DoubleSetting.Builder()
        .name("ahead-distance")
        .description("How many blocks in front of the player the block should go. 0 leaves it to the fallback search, which finds the nearest good spot on its own.")
        .defaultValue(0.0)
        .min(0.0)
        .max(3.0)
        .sliderRange(0.0, 3.0)
        .build()
    );

    private final Setting<Integer> searchRadius = sgPlacement.add(new IntSetting.Builder()
        .name("search-radius")
        .description("How far around the spot under the player the fallback search looks when that spot cannot be used.")
        .defaultValue(2)
        .min(0)
        .max(4)
        .sliderRange(0, 4)
        .build()
    );

    // ---- Render ----

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Master switch for everything below.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are drawn.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> renderTarget = sgRender.add(new BoolSetting.Builder()
        .name("target")
        .description("Highlight the block the next placement will fill, while it is being aimed at.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> renderFace = sgRender.add(new BoolSetting.Builder()
        .name("face")
        .description("Also highlight the face of the neighbour that will be clicked.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill colour of the target block.")
        .defaultValue(new SettingColor(197, 137, 232, 30))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline colour of the target block and of the face that will be clicked.")
        .defaultValue(new SettingColor(197, 137, 232))
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> pulse = sgRender.add(new BoolSetting.Builder()
        .name("pulse")
        .description("Breathe the target highlight - it shrinks and dims, then grows and brightens - so an armed placement reads as live rather than as a static box.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final Setting<Double> pulseSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("pulse-speed")
        .description("Pulse cycles per second.")
        .defaultValue(0.8)
        .min(0.1)
        .sliderRange(0.1, 3.0)
        .visible(() -> render.get() && pulse.get())
        .build()
    );

    private final Setting<Boolean> glow = sgRender.add(new BoolSetting.Builder()
        .name("glow")
        .description("Draw a wider, fainter outline around the target that breathes with the pulse.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> renderPlaced = sgRender.add(new BoolSetting.Builder()
        .name("placed")
        .description("Fade out the block that was actually placed.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final Setting<Integer> placedDuration = sgRender.add(new IntSetting.Builder()
        .name("placed-duration")
        .description("How many ticks the placed block stays highlighted.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 40)
        .visible(() -> render.get() && renderPlaced.get())
        .build()
    );

    private final Setting<SettingColor> placedSideColor = sgRender.add(new ColorSetting.Builder()
        .name("placed-side-color")
        .description("Fill colour of a block that was placed.")
        .defaultValue(new SettingColor(80, 220, 130, 40))
        .visible(() -> render.get() && renderPlaced.get())
        .build()
    );

    private final Setting<SettingColor> placedLineColor = sgRender.add(new ColorSetting.Builder()
        .name("placed-line-color")
        .description("Outline colour of a block that was placed.")
        .defaultValue(new SettingColor(80, 220, 130))
        .visible(() -> render.get() && renderPlaced.get())
        .build()
    );

    // ---- State ----

    /**
     * The placement aimed at during the tick that just ended. It is sent at the head of the next
     * client tick, which is exactly where a vanilla right click would have been sent.
     */
    private Placement pending;

    /** Client tick {@link #pending} was computed on, so an aim from an older tick is never sent. */
    private int pendingTick = -1;

    private int tick;
    private int placeTimer;

    /**
     * Scratch colours for the pulse, handed out in order by {@link #fade} so the box and its halo
     * in one frame do not fight over a single instance. Module fields because this runs per frame.
     */
    private final Color[] scratch = {new Color(), new Color(), new Color()};
    private int scratchIndex;


    /**
     * The pitch that actually went out last tick, or {@link Float#NaN} when nothing has been sent
     * yet. Sub-degree corrections are held instead of applied, so the pitch either does not move
     * at all or moves by a degree or more - see {@link #aimAt}.
     */
    private float lastPitch = Float.NaN;

    /**
     * Hotbar slot to put back after a block was placed from another slot, or -1.
     *
     * <p>Deliberately restored at the head of the <em>next</em> tick rather than straight after
     * the placement. Grim keeps a per-tick "right clicking" flag and its {@code PacketOrderE}
     * check flags an {@code UpdateSelectedSlot} that arrives in the same tick as a right click -
     * which is exactly what swapping back immediately after placing looks like. One tick later the
     * flag has been cleared by the tick's own movement packet.
     */
    private int restoreSlot = -1;

    public ScaffoldPlus() {
        super(HongShiKaiKai.CATEGORY, "scaffold+",
            "Vanilla-faithful scaffold: aims while the movement packet is built, places one tick later. Nothing for Grim's placement checks to see.");
    }

    @Override
    public void onActivate() {
        pending = null;
        pendingTick = -1;
        tick = 0;
        placeTimer = 0;
        lastPitch = Float.NaN;
        restoreSlot = -1;
    }

    @Override
    public void onDeactivate() {
        restoreHotbarSlot();
        pending = null;
        pendingTick = -1;
        placeTimer = 0;
        lastPitch = Float.NaN;
    }

    // ---- Aiming ----

    /**
     * Runs while {@code ClientPlayerEntity.sendMovementPackets} builds this tick's movement
     * packet. The entity has already moved, so its position and eye height here are the ones the
     * server is about to be told about. Queueing the rotation now means the look packet and the
     * placement packet - which is sent next tick - describe the same state.
     *
     * <p>Highest priority so the rotation is in Meteor's queue before {@link Rotations} drains it.
     *
     * <h2>Why a rotation is queued even when there is nothing to place</h2>
     * {@link Rotations} re-sends the last queued rotation for {@code Config.rotationHoldTicks}
     * (four by default) whenever its queue runs dry. That held rotation is the one the module aimed
     * with - and the moment the player turns a corner while walking over solid ground, the packet
     * would carry a yaw the client did not walk with, which is exactly the Simulation offset the
     * pitch-only mode exists to avoid. Keeping the queue fed with the player's own rotation pins
     * the hold to what the client is actually doing.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onSendMovementPacketsPre(SendMovementPacketsEvent.Pre event) {
        if (mc.player == null) return;

        if (!canRun()) {
            pending = null;
            holdPlayerRotation();
            return;
        }

        Placement placement = findPlacement();
        if (placement == null) {
            pending = null;
            holdPlayerRotation();
            return;
        }

        Rotations.rotate(placement.yaw(), placement.pitch(), ROTATION_PRIORITY);

        pending = placement;
        pendingTick = tick;
    }

    /**
     * Queues the rotation the client is already using. It changes nothing on the wire - the client
     * rotation is set to the value it already had, so vanilla sends no look packet unless the mouse
     * moved - but it stops {@link Rotations} from replaying an older, stale aim.
     */
    private void holdPlayerRotation() {
        // Rotations bails out when the camera is another entity, and a queued rotation would then
        // sit in its queue and be flushed later. Do not queue in that case.
        if (mc.getCameraEntity() != mc.player) return;

        Rotations.rotate(mc.player.getYaw(), mc.player.getPitch(), ROTATION_PRIORITY);
    }

    /**
     * Meteor's {@link Rotations} may have prioritised somebody else's rotation out of the queue,
     * in which case the rotation that actually went out does not look at the block. Placing now
     * would be validated against that foreign rotation, so drop the aim instead.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        if (pending == null) {
            // Nothing was aimed, so the player's own rotation is what is on the wire now.
            if (mc.player != null) lastPitch = mc.player.getPitch();
            return;
        }

        if (Math.abs(Rotations.serverYaw - pending.yaw()) > 0.01f
            || Math.abs(Rotations.serverPitch - pending.pitch()) > 0.01f) {
            pending = null;
            return;
        }

        // Remember the pitch the server now has, so the next aim can choose to keep it.
        lastPitch = pending.pitch();
    }

    // ---- Placing ----

    /**
     * Head of {@code MinecraftClient.tick()}, i.e. before {@code handleInputEvents} - the same
     * place in the packet stream a vanilla right click occupies. The placement aimed at last tick
     * is sent here.
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        tick++;

        restoreHotbarSlot();

        if (!canRun()) {
            pending = null;
            return;
        }

        if (placeTimer > 0) {
            placeTimer--;
            return;
        }

        Placement placement = pending;
        pending = null;

        // The aim has to be from the immediately preceding tick: that is the state the server
        // still has when this packet arrives.
        if (placement == null || pendingTick != tick - 1) return;
        if (!stillValid(placement)) return;

        if (place(placement)) {
            placeTimer = placeDelay.get();

            if (render.get() && renderPlaced.get()) {
                RenderUtils.renderTickingBlock(
                    placement.target(),
                    placedSideColor.get(), placedLineColor.get(), shapeMode.get(),
                    0, placedDuration.get(), true, true);
            }

            if (chatFeedback.get()) {
                info("Placed at %d, %d, %d on the %s face (%.2f blocks).",
                    placement.clicked().getX(), placement.clicked().getY(), placement.clicked().getZ(),
                    placement.face().asString(), placement.distance());
            }
        }
    }

    // ---- Rendering ----

    /**
     * Draws the placement that is currently armed, straight out of {@link #pending}. The aim was
     * resolved while the last movement packet was built and is sent at the head of this tick, so
     * the box is exactly the block the next interact will fill - not a guess at one.
     *
     * <p>Nothing is drawn when there is no reachable spot, which is itself useful: an empty screen
     * means the module has nowhere to put a block and the next step would be a fall.
     */
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || mc.player == null || mc.world == null) return;

        Placement placement = pending;
        if (placement == null) return;

        double beat = pulseFactor();

        if (renderTarget.get()) {
            BlockPos target = placement.target();

            // The box breathes: it pulls in and dims, then pushes out and brightens. The halo runs
            // on the same beat so the two read as one object rather than two boxes.
            double inset = beat * 0.06;
            double x1 = target.getX() + inset;
            double y1 = target.getY() + inset;
            double z1 = target.getZ() + inset;
            double x2 = target.getX() + 1.0 - inset;
            double y2 = target.getY() + 1.0 - inset;
            double z2 = target.getZ() + 1.0 - inset;

            event.renderer.box(x1, y1, z1, x2, y2, z2,
                fade(sideColor.get(), beat, 0.45, 0.55), fade(lineColor.get(), beat, 0.60, 0.40),
                shapeMode.get(), 0);

            if (glow.get()) {
                double grow = 0.02 + 0.05 * beat;
                Color halo = fade(lineColor.get(), beat, 0.08, 0.18);
                event.renderer.box(x1 - grow, y1 - grow, z1 - grow, x2 + grow, y2 + grow, z2 + grow,
                    halo, halo, ShapeMode.Lines, 0);
            }
        }

        if (renderFace.get()) {
            drawFace(event.renderer, placement.clicked(), placement.face(),
                0.004 + beat * 0.012,
                fade(sideColor.get(), beat, 0.45, 0.55),
                fade(lineColor.get(), beat, 0.60, 0.40));
        }
    }

    /** 0 at the bottom of the breath, 1 at the top. A flat 1 when pulsing is off. */
    private double pulseFactor() {
        if (!pulse.get()) return 1.0;

        double cycle = System.nanoTime() / 1.0E9 * pulseSpeed.get();
        return 0.5 + 0.5 * Math.sin(cycle * Math.PI * 2.0);
    }

    /**
     * Copies {@code source} into a scratch colour with its alpha scaled by
     * {@code min + span * beat}. The scratch colours are module fields because this runs every
     * frame and allocating two colours per frame is silly.
     */
    private Color fade(Color source, double beat, double min, double span) {
        Color out = scratch[scratchIndex++ % scratch.length];
        out.set(source);
        out.a = MathHelper.clamp((int) (source.a * (min + span * beat)), 0, 255);
        return out;
    }

    /**
     * Draws a single face of a block, pushed a hair outwards so the outline does not z-fight with
     * the block it belongs to.
     */
    private void drawFace(Renderer3D renderer, BlockPos clicked, Direction face, double o, Color side, Color line) {
        double x1 = clicked.getX();
        double y1 = clicked.getY();
        double z1 = clicked.getZ();
        double x2 = x1 + 1.0;
        double y2 = y1 + 1.0;
        double z2 = z1 + 1.0;
        ShapeMode mode = shapeMode.get();

        switch (face) {
            case UP -> renderer.side(x1, y2 + o, z1, x1, y2 + o, z2, x2, y2 + o, z2, x2, y2 + o, z1, side, line, mode);
            case DOWN -> renderer.side(x1, y1 - o, z1, x2, y1 - o, z1, x2, y1 - o, z2, x1, y1 - o, z2, side, line, mode);
            case NORTH -> renderer.side(x1, y1, z1 - o, x1, y2, z1 - o, x2, y2, z1 - o, x2, y1, z1 - o, side, line, mode);
            case SOUTH -> renderer.side(x1, y1, z2 + o, x2, y1, z2 + o, x2, y2, z2 + o, x1, y2, z2 + o, side, line, mode);
            case WEST -> renderer.side(x1 - o, y1, z1, x1 - o, y1, z2, x1 - o, y2, z2, x1 - o, y2, z1, side, line, mode);
            case EAST -> renderer.side(x2 + o, y1, z1, x2 + o, y2, z1, x2 + o, y2, z2, x2 + o, y1, z2, side, line, mode);
        }
    }

    private boolean place(Placement placement) {
        // Whatever is already in a hand costs nothing - and a placement from the hand the client
        // already has selected is the most vanilla-looking one there is.
        if (isValidItem(mc.player.getMainHandStack())) {
            return interact(Hand.MAIN_HAND, placement);
        }

        if (isValidItem(mc.player.getOffHandStack())) {
            return interact(Hand.OFF_HAND, placement);
        }

        if (!autoSwitch.get()) return false;

        FindItemResult item = InvUtils.findInHotbar(this::isValidItem);
        if (!item.found()) return false;

        int previous = VersionCompat.getSelectedSlot(mc.player.getInventory());
        if (!InvUtils.swap(item.slot(), false)) return false;

        boolean placed = interact(Hand.MAIN_HAND, placement);

        if (swapBack.get() && previous != item.slot()) {
            // Hand the HUD its selection back right away, but without the packet: an
            // UpdateSelectedSlot in the same tick as the placement is what Grim's PacketOrderE
            // flags. The packet goes out at the head of the next tick, where the tick's own
            // movement packet has already cleared that flag.
            VersionCompat.setSelectedSlot(mc.player.getInventory(), previous);
            restoreSlot = previous;
        }

        return placed;
    }


    /**
     * Puts the hotbar slot back after a block was placed. Runs at the head of the tick, before
     * anything else can send a packet, so the swap cannot land in the same tick as a placement.
     * The client-side selection itself was already put back when the block was placed.
     */
    private void restoreHotbarSlot() {
        int slot = restoreSlot;
        restoreSlot = -1;

        if (slot < 0 || mc.player == null) return;

        // InvUtils.swap writes the slot and syncs it, which sends the UpdateSelectedSlot packet.
        InvUtils.swap(slot, false);
    }

    /**
     * {@code interactBlock} is the vanilla right click path: it runs the item's own placement
     * logic on the client world, allocates the 1.19+ sequence id and sends the packet, so the
     * module never has to build a {@code PlayerInteractBlockC2SPacket} itself.
     */
    private boolean interact(Hand hand, Placement placement) {
        ActionResult result = mc.interactionManager.interactBlock(mc.player, hand, placement.hit());
        if (!result.isAccepted()) return false;

        if (swing.get()) mc.player.swingHand(hand);
        else mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));

        return true;
    }

    private boolean canRun() {
        if (mc.player == null || mc.world == null) return false;
        if (mc.interactionManager == null || mc.getNetworkHandler() == null) return false;
        if (mc.player.isDead() || mc.player.isSpectator()) return false;
        if (mc.currentScreen != null) return false;
        if (pauseInVehicle.get() && mc.player.hasVehicle()) return false;
        if (onlyOnClick.get() && !mc.options.useKey.isPressed()) return false;

        // Pitch feeds the prediction in these states (PredictionEngineWater uses the look vector,
        // PredictionEngineElytra and the firework uncertainty box use the whole look), so a silent
        // pitch would desync there. Walking prediction only ever reads the yaw.
        if (mc.player.isGliding() || mc.player.isUsingRiptide()) return false;
        if (mc.player.isSwimming() || mc.player.isTouchingWater()) return false;

        // Meteor's Rotations refuses to work when the camera is a different entity. Aiming
        // without a rotation going out is pointless, so do not even try.
        return mc.getCameraEntity() == mc.player;
    }

    private boolean isValidItem(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;

        Block block = blockItem.getBlock();

        if (blocksFilter.get() == Filter.Whitelist && !blocks.get().contains(block)) return false;
        if (blocksFilter.get() == Filter.Blacklist && blocks.get().contains(block)) return false;

        return true;
    }

    /**
     * How many blocks the module has left to place, counted with its own block list and filter so
     * the number is exactly what {@link #place} could actually use.
     *
     * @param includeMainInventory also count the 27 main inventory slots and the offhand, not just
     *                             the hotbar. Only the hotbar can be reached without moving items,
     *                             so that is the number that decides when the module runs dry.
     */
    public int countBlocks(boolean includeMainInventory) {
        if (mc.player == null) return 0;

        PlayerInventory inventory = mc.player.getInventory();
        int last = (includeMainInventory ? PlayerInventory.MAIN_SIZE : PlayerInventory.HOTBAR_SIZE) - 1;

        int total = 0;
        for (int slot = 0; slot <= last; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (isValidItem(stack)) total += stack.getCount();
        }

        if (includeMainInventory) {
            ItemStack offhand = mc.player.getOffHandStack();
            if (isValidItem(offhand)) total += offhand.getCount();
        }

        return total;
    }

    // ---- Target selection ----

    /**
     * The spot the player would pick: the block level they are standing on, at their feet,
     * optionally pushed forward along the keys that are held. It is only a preference - when it is
     * already solid the search around it finds the closest sensible alternative.
     */
    private BlockPos idealTarget() {
        double x = mc.player.getX();
        double z = mc.player.getZ();

        double ahead = aheadDistance.get();
        if (ahead > 0.0) {
            Vec3d direction = inputDirection();
            if (direction != null) {
                x += direction.x * ahead;
                z += direction.z * ahead;
            }
        }

        int feetY = MathHelper.floor(mc.player.getY());
        return new BlockPos(MathHelper.floor(x), feetY - 1, MathHelper.floor(z));
    }

    /** Unit horizontal direction of the WASD keys, measured against the current view. Null if idle. */
    private Vec3d inputDirection() {
        double forward = (mc.options.forwardKey.isPressed() ? 1 : 0) - (mc.options.backKey.isPressed() ? 1 : 0);
        double strafe = (mc.options.rightKey.isPressed() ? 1 : 0) - (mc.options.leftKey.isPressed() ? 1 : 0);
        if (forward == 0 && strafe == 0) return null;

        double yaw = Math.toRadians(mc.player.getYaw());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);

        // Look vector is (-sin, cos); its right hand normal is (-cos, -sin).
        double dx = -sin * forward - cos * strafe;
        double dz = cos * forward - sin * strafe;

        double length = Math.hypot(dx, dz);
        if (length < 1.0E-4) return null;

        return new Vec3d(dx / length, 0.0, dz / length);
    }

    private Placement findPlacement() {
        Vec3d eye = mc.player.getEyePos();
        double reach = maxReach.get();

        BlockPos ideal = idealTarget();

        // Fast path: the preferred spot is free and one of its faces is actually clickable.
        if (isCandidate(ideal)) {
            Placement placement = aimAt(ideal, eye, reach);
            if (placement != null) return placement;
        }

        int tried = 0;
        for (BlockPos target : collectCandidates(ideal)) {
            if (target.equals(ideal)) continue;
            if (tried++ >= MAX_RAYCAST_TARGETS) break;

            Placement placement = aimAt(target, eye, reach);
            if (placement != null) return placement;
        }

        return null;
    }

    private List<BlockPos> collectCandidates(BlockPos ideal) {
        List<BlockPos> candidates = new ArrayList<>();

        int radius = searchRadius.get();
        int maxY = ideal.getY();
        int minY = Math.max(mc.world.getBottomY(), maxY - radius - 1);
        if (maxY < minY) return candidates;

        for (int x = ideal.getX() - radius; x <= ideal.getX() + radius; x++) {
            for (int z = ideal.getZ() - radius; z <= ideal.getZ() + radius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isCandidate(pos)) candidates.add(pos);
                }
            }
        }

        // Prefer the level the player is on and the shortest horizontal step: dropping a level is
        // a fallback, not the first choice.
        candidates.sort(Comparator.comparingDouble(pos -> {
            double dy = pos.getY() - ideal.getY();
            double dx = pos.getX() - ideal.getX();
            double dz = pos.getZ() - ideal.getZ();
            return dy * dy * 4.0 + dx * dx + dz * dz;
        }));

        return candidates;
    }

    /** A block that is free to be filled and that has at least one neighbour worth clicking. */
    private boolean isCandidate(BlockPos pos) {
        if (!World.isValid(pos)) return false;
        if (!isLoaded(pos)) return false;

        if (!mc.world.getBlockState(pos).isReplaceable()) return false;

        // Never fill the space the player's own box occupies: the server would refuse it.
        if (mc.player.getBoundingBox().intersects(new Box(pos))) return false;

        for (Direction face : FACES) {
            if (isSupport(pos.offset(face))) return true;
        }

        return false;
    }

    private boolean isLoaded(BlockPos pos) {
        return mc.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * A neighbour that can be clicked to reach {@code pos}. Air, liquids, replaceable plants and
     * blocks with their own right-click behaviour are all out: Grim's {@code AirLiquidPlace} and
     * the vanilla server both want a real, full, non-interactive support.
     */
    private boolean isSupport(BlockPos pos) {
        if (!World.isValid(pos)) return false;
        if (!isLoaded(pos)) return false;

        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir()) return false;
        if (state.isReplaceable()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        if (BlockUtils.isClickable(state.getBlock())) return false;

        return !state.getCollisionShape(mc.world, pos).isEmpty();
    }

    /**
     * Ray traces from the eye with the rotation that will go into the movement packet and keeps
     * the hit only when the server would put the block where we want it.
     *
     * <p>Two kinds of aim point are tried: the middle of the target block (which usually lands on
     * the top face of the block underneath) and the middle of each face shared with a neighbour
     * (which is what you get when the target is bridged sideways).
     *
     * <h2>Why the yaw is not aimed</h2>
     * Grim predicts walking with {@code getMovementResultFromInput(..., player.yaw)} - the yaw of
     * the movement packet. The client's own physics ran earlier in the tick with the yaw the mouse
     * gave it. Changing the yaw in the packet alone therefore makes client and server predict two
     * different directions, which is a Simulation offset the moment a movement key is held. The
     * pitch is not read by the walking prediction at all, so aiming only the pitch stays in sync.
     *
     * <p>With {@link RotationMode#Full} the yaw is aimed as well. That is the old behaviour: it
     * places more easily and it does flag Simulation.
     */
    private Placement aimAt(BlockPos target, Vec3d eye, double reach) {
        return rotation.get() == RotationMode.Full
            ? aimFull(target, eye, reach)
            : aimPitch(target, eye, reach);
    }

    /**
     * Pitch mode. The yaw is the player's own, so the ray sweeps a vertical plane rather than
     * pointing at the block; a sampled pitch would simply miss faces that the yaw is not lined up
     * with. Instead the pitch is solved for: for every face that could be clicked, the ray's
     * horizontal line is intersected with that face and the pitch follows from the crossing point.
     */
    private Placement aimPitch(BlockPos target, Vec3d eye, double reach) {
        float yaw = mc.player.getYaw();

        // Keep the pitch the server already has when it still works. That makes the rotation
        // update either absent, or a whole degree or more: Grim's Baritone check is built around
        // "yaw delta of exactly zero plus a pitch delta under one degree".
        if (!Float.isNaN(lastPitch)) {
            Placement held = cast(target, yaw, lastPitch, eye, reach);
            if (held != null) return held;
        }

        Placement best = null;

        for (Direction face : FACES) {
            BlockPos support = target.offset(face.getOpposite());
            if (!isSupport(support)) continue;

            for (double pitch : pitchesFor(support, face, eye, yaw)) {
                Placement placement = cast(target, yaw, (float) pitch, eye, reach);
                if (placement == null) continue;
                if (best == null || placement.distance() < best.distance()) best = placement;
                break;
            }
        }

        // The target itself can be replaceable (grass, a snow layer); then the click lands on the
        // target rather than on a neighbour.
        if (mc.world.getBlockState(target).isReplaceable()) {
            Vec3d delta = Vec3d.ofCenter(target).subtract(eye);
            double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            Placement placement = cast(target, yaw, (float) pitchFor(delta.y, horizontal), eye, reach);
            if (placement != null && (best == null || placement.distance() < best.distance())) best = placement;
        }

        return best;
    }

    /**
     * Full mode. The yaw is aimed as well, so the ray can simply be pointed at the face centres;
     * the cast then verifies what will actually be sent.
     */
    private Placement aimFull(BlockPos target, Vec3d eye, double reach) {
        Vec3d center = Vec3d.ofCenter(target);
        Placement best = null;

        for (int i = -1; i < FACES.length; i++) {
            Vec3d aim = i < 0
                ? center
                : center.subtract(Vec3d.of(FACES[i].getVector()).multiply(0.5));

            Vec3d delta = aim.subtract(eye);
            double distance = delta.length();
            if (distance < 1.0E-3 || distance > reach + 1.0) continue;

            double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            float pitch = (float) pitchFor(delta.y, horizontal);
            float yaw = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(-delta.x, delta.z)));

            Placement placement = cast(target, yaw, pitch, eye, reach);
            if (placement == null) continue;

            if (best == null || placement.distance() < best.distance()) best = placement;
        }

        return best;
    }

    /**
     * The pitches that put a ray of this yaw onto {@code face} of {@code support}, best first.
     *
     * <p>With the yaw fixed, the ray's horizontal path is a fixed line. A horizontal face (UP /
     * DOWN) is reached wherever that line crosses the face rectangle, and the crossing distance
     * gives the pitch. A vertical face (the four sides) is reached where the line crosses the
     * face's plane inside the rectangle, at whatever height within the face is convenient.
     */
    private double[] pitchesFor(BlockPos support, Direction face, Vec3d eye, float yaw) {
        double yawRad = Math.toRadians(yaw);
        double dx = -Math.sin(yawRad);
        double dz = Math.cos(yawRad);

        double sx = support.getX();
        double sy = support.getY();
        double sz = support.getZ();

        switch (face) {
            case UP, DOWN -> {
                double planeY = face == Direction.UP ? sy + 1 : sy;
                double[] span = spanXZ(eye.x, eye.z, dx, dz, sx, sx + 1, sz, sz + 1);
                if (span == null) return EMPTY;

                double dy = planeY - eye.y;
                double near = span[0];
                double far = span[1];
                double length = far - near;

                // Sample just inside the reachable stretch: aiming exactly at one of its ends puts
                // the ray on the very edge of the face, where float rounding decides whether the
                // hit counts, and it is the difference between placing and falling.
                double inset = Math.min(0.05, length * 0.1);
                return new double[]{
                    pitchFor(dy, (near + far) * 0.5),
                    pitchFor(dy, near + inset),
                    pitchFor(dy, far - inset),
                    pitchFor(dy, near + length * 0.25),
                    pitchFor(dy, near + length * 0.75)
                };
            }
            case EAST, WEST -> {
                if (Math.abs(dx) < 1.0E-6) return EMPTY;

                double planeX = face == Direction.EAST ? sx + 1 : sx;
                double t = (planeX - eye.x) / dx;
                if (t <= 1.0E-4) return EMPTY;

                double z = eye.z + t * dz;
                if (z < sz || z > sz + 1) return EMPTY;

                return facePitches(sy, t, eye.y);
            }
            case NORTH, SOUTH -> {
                if (Math.abs(dz) < 1.0E-6) return EMPTY;

                double planeZ = face == Direction.SOUTH ? sz + 1 : sz;
                double t = (planeZ - eye.z) / dz;
                if (t <= 1.0E-4) return EMPTY;

                double x = eye.x + t * dx;
                if (x < sx || x > sx + 1) return EMPTY;

                return facePitches(sy, t, eye.y);
            }
            default -> {
                return EMPTY;
            }
        }
    }

    /**
     * A vertical face can be hit at any height along it, so the pitch is free; use the eye's own
     * height when it is on the face and the middle otherwise.
     */
    private static double[] facePitches(double sy, double horizontal, double eyeY) {
        return new double[]{
            pitchFor(MathHelper.clamp(eyeY, sy + 0.05, sy + 0.95) - eyeY, horizontal),
            pitchFor(sy + 0.5 - eyeY, horizontal)
        };
    }

    /**
     * How far along {@code (dx, dz)} the horizontal path stays inside the rectangle
     * {@code [minX, maxX] x [minZ, maxZ]}, as {@code {near, far}}, or null when it never does.
     */
    private static double[] spanXZ(double ox, double oz, double dx, double dz,
                                   double minX, double maxX, double minZ, double maxZ) {
        double near = 0.0;
        double far = Double.MAX_VALUE;

        double[] origins = {ox, oz};
        double[] steps = {dx, dz};
        double[] lower = {minX, minZ};
        double[] upper = {maxX, maxZ};

        for (int axis = 0; axis < 2; axis++) {
            double origin = origins[axis];
            double step = steps[axis];

            if (Math.abs(step) < 1.0E-6) {
                if (origin < lower[axis] || origin > upper[axis]) return null;
                continue;
            }

            double t1 = (lower[axis] - origin) / step;
            double t2 = (upper[axis] - origin) / step;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }

            near = Math.max(near, t1);
            far = Math.min(far, t2);
            if (near > far) return null;
        }

        if (far <= 0.0) return null;
        return new double[]{Math.max(near, 1.0E-3), far};
    }

    /** Pitch that looks along a drop of {@code dy} over a horizontal run of {@code horizontal}. */
    private static double pitchFor(double dy, double horizontal) {
        return MathHelper.clamp(-Math.toDegrees(Math.atan2(dy, Math.max(horizontal, 1.0E-4))), -90.0, 90.0);
    }

    /**
     * Cast straight along {@code (yaw, pitch)} - the exact vector the server rebuilds from the
     * packet - and accept the hit only if it fills the target.
     */
    private Placement cast(BlockPos target, float yaw, float pitch, Vec3d eye, double reach) {
        Vec3d direction = Vec3d.fromPolar(pitch, yaw);
        Vec3d end = eye.add(direction.multiply(reach + 1.0));

        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));

        if (hit.getType() != HitResult.Type.BLOCK) return null;

        return accept(target, hit, yaw, pitch, eye, reach);
    }

    private Placement accept(BlockPos target, BlockHitResult hit, float yaw, float pitch, Vec3d eye, double reach) {
        BlockPos clicked = hit.getBlockPos();
        Direction face = hit.getSide();
        Vec3d cursor = hit.getPos();

        // The click has to end up filling the target, either by clicking a neighbour's face or by
        // replacing whatever replaceable thing already sits in the target (grass, snow layer...).
        boolean replacing = clicked.equals(target);
        if (replacing) {
            if (!mc.world.getBlockState(target).isReplaceable()) return null;
        } else if (!clicked.offset(face).equals(target)) {
            return null;
        }

        // Grim's RotationPlace traces the ray out to exactly the block interaction range, and the
        // server re-derives the direction with float trig, so leave a sliver of room. The cursor
        // is a point on the clicked box, so this also keeps FarPlace's box distance in range.
        double distance = cursor.distanceTo(eye);
        if (distance > reach - 1.0E-3) return null;

        return new Placement(target, clicked, face, hit, cursor, yaw, pitch, distance);
    }

    /**
     * The aim was computed from the state of the last movement packet, which is also the state the
     * server will validate against - so this can only really change if the world did. Re-check it
     * anyway: a placement that no longer makes sense must not go out.
     */
    private boolean stillValid(Placement placement) {
        if (!mc.world.getBlockState(placement.target()).isReplaceable()) return false;

        BlockState clicked = mc.world.getBlockState(placement.clicked());
        if (clicked.isAir()) return false;
        if (!clicked.getFluidState().isEmpty()) return false;
        if (clicked.getCollisionShape(mc.world, placement.clicked()).isEmpty()) return false;

        if (placement.clicked().equals(placement.target())) {
            return clicked.isReplaceable();
        }

        return placement.clicked().offset(placement.face()).equals(placement.target());
    }

    /** A resolved placement: the block to fill, the block that was clicked and the aim that hits it. */
    private record Placement(
        BlockPos target,
        BlockPos clicked,
        Direction face,
        BlockHitResult hit,
        Vec3d cursor,
        float yaw,
        float pitch,
        double distance
    ) {
    }

    public enum Filter {
        Whitelist,
        Blacklist
    }

    /** How much of the rotation the module is allowed to aim. */
    public enum RotationMode {
        /** Pitch only. The walking prediction stays in sync with the client's own physics. */
        Pitch,
        /** Yaw and pitch. Places more easily; desyncs the walking prediction (Grim Simulation). */
        Full
    }
}
