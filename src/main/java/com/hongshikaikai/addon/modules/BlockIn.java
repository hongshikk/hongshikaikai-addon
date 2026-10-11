package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.VersionCompat;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
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
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Blocks yourself in: it fills the ring of blocks around your feet and head - floor, the four
 * sides at both heights, and the ceiling - and then, by default, turns itself off, which is what
 * makes it a "press it and be safe" module rather than a surround that keeps running.
 *
 * <h2>What it does</h2>
 * <ul>
 *     <li>The position order. The ring is built from the block the player stood on when the module
 *         was switched on, starting at the direction the player is facing and walking either
 *         clockwise or counter-clockwise, the side being picked at random on every activation. The
 *         four {@code place-order} modes - {@code normal}, {@code random}, {@code bottom-top},
 *         {@code top-bottom} - reorder exactly that list.</li>
 *     <li>{@code avoid-broken-blocks}: a position the player has just mined is left alone for
 *         {@code broken-block-window} milliseconds, so the module does not immediately put back the
 *         block you are breaking out of your own box.</li>
 *     <li>The block choice. The slot finder takes the <em>hardest</em> block it has while
 *         it is working through the ring and the softest otherwise, so the wall you are left with is
 *         the strongest thing in your hotbar rather than whatever you happened to be holding.</li>
 *     <li>{@code disable-on-move}: moving out of the block you started in - the one above it does not
 *         count, that is just standing up - means the ring no longer describes where you are, so the
 *         module stops instead of walling in the wrong place.</li>
 * </ul>
 *
 * <h2>Placement details</h2>
 * <ul>
 *     <li>Placement is written directly against Meteor's {@link Rotations} and {@code InvUtils},
 *         carrying only what this module needs.</li>
 *     <li>A placement is <em>ray traced</em> from the eye with the exact {@code (yaw, pitch)} that
 *         goes into the movement packet, and only accepted when the ray lands on the face that was
 *         aimed at and inside {@code range}. A click the server would refuse is never sent.</li>
 *     <li>The slot swap back is deferred by one tick. Grim's {@code PacketOrderE} flags an
 *         {@code UpdateSelectedSlot} that shares a tick with a right click, which is what swapping
 *         straight back after placing looks like; the swap <em>to</em> the block stays in the tick of
 *         the placement, where a vanilla hotbar press would be.</li>
 *     <li>Placement pauses while a screen is open and while the camera is another entity.</li>
 *     <li>{@code rotate} is on by default. With it off the verification ray is
 *         traced with <em>your</em> rotation instead of an aim, so nothing is placed unless you are
 *         already looking at the face yourself.</li>
 * </ul>
 *
 * <h2>Grim</h2>
 * Unlike {@link ScaffoldPlus}, this module aims the full {@code (yaw, pitch)} and spoofs them to the
 * server. Grim's movement prediction reads the yaw of the movement packet, so a spoofed yaw is a
 * {@code Simulation} offset - but only while a movement key is held. BlockIn is built to be used
 * standing still: {@code disable-on-move} turns it off the moment you step out of the block, so the
 * spoof never lives long enough to be walked with.
 */
public class BlockIn extends Module {
    /** Rotation priority handed to Meteor's {@link Rotations}. Same as {@link ScaffoldPlus}. */
    private static final int ROTATION_PRIORITY = 50;

    /** The six faces, in the order the ring's neighbours are tried. */
    private static final Direction[] FACES = Direction.values();

    /** How far a sample point may sit from the face centre before it stops being "that face". */
    private static final double SAMPLE_INSET = 0.25;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlacement = settings.createGroup("Placement");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // ---- General ----

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Which blocks may be placed. Empty with the blacklist filter - the default - means every block is allowed.")
        .build()
    );

    private final Setting<Filter> blocksFilter = sgGeneral.add(new EnumSetting.Builder<Filter>()
        .name("blocks-filter")
        .description("Whether the block list is a whitelist or a blacklist.")
        .defaultValue(Filter.Blacklist)
        .build()
    );

    private final Setting<Order> placeOrder = sgGeneral.add(new EnumSetting.Builder<Order>()
        .name("place-order")
        .description("The order the ring is filled in. Normal starts at the direction you are facing, Random shuffles it, the two others sort it by height.")
        .defaultValue(Order.Normal)
        .build()
    );

    private final Setting<Boolean> disableOnFinish = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-finish")
        .description("Turn the module off once the ring is complete.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disableOnMove = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-move")
        .description("Turn the module off when you step out of the block you started in. Standing up - the block above - does not count.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> avoidBrokenBlocks = sgGeneral.add(new BoolSetting.Builder()
        .name("avoid-broken-blocks")
        .description("Leave a position alone for a while after you mine it, so the module does not put back the block you are breaking out of.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> brokenBlockWindow = sgGeneral.add(new IntSetting.Builder()
        .name("broken-block-window")
        .description("How long a position you mined stays out of the ring.")
        .defaultValue(1000)
        .min(0)
        .max(10000)
        .sliderRange(0, 5000)
        .visible(avoidBrokenBlocks::get)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print a line to chat every time a block is placed.")
        .defaultValue(false)
        .build()
    );

    // ---- Placement ----

    private final Setting<Double> range = sgPlacement.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far from the eye a placement may be. Vanilla's block interaction range is 4.5, and the server enforces it, so that is the ceiling.")
        .defaultValue(4.5)
        .min(1.0)
        .max(4.5)
        .sliderRange(1.0, 4.5)
        .build()
    );

    private final Setting<Boolean> rotate = sgPlacement.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Aim at the face being clicked. Off: only place what you are already looking at, since the ray is then traced with your own rotation.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgPlacement.add(new BoolSetting.Builder()
        .name("swing")
        .description("Render the client-side swing. Off: only the swing packet is sent.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> cooldown = sgPlacement.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Ticks to wait between placements. Vanilla places at most one block per tick, so 0 is the fastest anything can go.")
        .defaultValue(1)
        .min(0)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgPlacement.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Swap to a block in the hotbar before placing. Off: only the held item, the offhand and nothing else.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swapBack = sgPlacement.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Give the hotbar slot you had selected back after each placement. Off: the module switches to whichever hotbar slot holds the blocks and leaves it there, so the block really is in your hand and you change back yourself.")
        .defaultValue(true)
        .visible(autoSwitch::get)
        .build()
    );

    private final Setting<Boolean> checkEntities = sgPlacement.add(new BoolSetting.Builder()
        .name("check-entities")
        .description("Skip a position an entity is standing in. The server refuses those anyway.")
        .defaultValue(true)
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

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill colour of a position that is still waiting to be filled.")
        .defaultValue(new SettingColor(197, 137, 232, 25))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline colour of a position that is still waiting to be filled.")
        .defaultValue(new SettingColor(197, 137, 232))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> nextSideColor = sgRender.add(new ColorSetting.Builder()
        .name("next-side-color")
        .description("Fill colour of the position that is about to be filled.")
        .defaultValue(new SettingColor(80, 220, 130, 45))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> nextLineColor = sgRender.add(new ColorSetting.Builder()
        .name("next-line-color")
        .description("Outline colour of the position that is about to be filled.")
        .defaultValue(new SettingColor(80, 220, 130))
        .visible(render::get)
        .build()
    );

    // ---- State ----

    /** The block the player stood in when the module was switched on. The ring is built around it. */
    private BlockPos startPos = BlockPos.ORIGIN;

    /** Which way the ring is walked from the direction the player faces. Picked at random on enable. */
    private boolean rotateClockwise;

    /** The positions still waiting to be filled, in the order they will be filled. Recomputed per tick. */
    private List<BlockPos> targets = new ArrayList<>();

    /** Position (as a long) to the time it was mined, for {@code avoid-broken-blocks}. */
    private final Map<Long, Long> recentlyBroken = new HashMap<>();

    private int cooldownTimer;

    /**
     * Hotbar slot to put back after a block was placed from another slot, or -1.
     *
     * <p>Restored at the head of the <em>next</em> tick rather than straight after the placement: an
     * {@code UpdateSelectedSlot} in the same tick as a right click is what Grim's
     * {@code PacketOrderE} flags.
     */
    private int restoreSlot = -1;

    private final Random random = new Random();

    public BlockIn() {
        super(HongShiKaiKai.CATEGORY, "BlockIn",
            "Fills the ring of blocks around you - floor, four sides at both heights and ceiling - and then turns itself off.");
    }

    @Override
    public void onActivate() {
        startPos = mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
        rotateClockwise = random.nextBoolean();
        targets = new ArrayList<>();
        recentlyBroken.clear();
        cooldownTimer = 0;
        restoreSlot = -1;
    }

    @Override
    public void onDeactivate() {
        restoreHotbarSlot();
        targets = new ArrayList<>();
        recentlyBroken.clear();
        cooldownTimer = 0;
    }

    @Override
    public String getInfoString() {
        return targets.isEmpty() ? null : Integer.toString(targets.size());
    }

    // ---- Reading the world ----

    /**
     * Runs at the head of the tick, i.e. before anything in this tick can send a packet - which is
     * where the swap-back from the previous placement has to be. See {@link #restoreSlot}.
     */
    @EventHandler(priority = EventPriority.HIGH)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        restoreHotbarSlot();

        if (!canRun()) {
            targets = new ArrayList<>();
            return;
        }

        // disable-on-move. Standing on top of the block you started in - startPos.up() - is not a
        // move, it is just standing up.
        BlockPos current = mc.player.getBlockPos();
        if (!current.equals(startPos) && !current.equals(startPos.up())) {
            if (disableOnMove.get()) {
                error("Your position has changed!");
                toggle();
                return;
            }
        }

        targets = currentTargets();

        if (targets.isEmpty()) {
            if (disableOnFinish.get()) {
                info("Filled!");
                toggle();
            }
            return;
        }

        if (cooldownTimer > 0) {
            cooldownTimer--;
            return;
        }

        // The first position of the ring that can actually be clicked is the one that gets filled.
        for (BlockPos target : targets) {
            if (place(target)) {
                cooldownTimer = cooldown.get();
                return;
            }
        }
    }

    /**
     * Builds the ring, in placement order, keeping only what is still free to fill.
     *
     * <p>The shape: the block below the player, then the four cardinal neighbours -
     * starting at the direction the player faces and stepping either clockwise or counter-clockwise -
     * each at as many heights as the player is tall, and finally the block above the head.
     */
    private List<BlockPos> orderedRing() {
        int playerHeight = MathHelper.ceil(mc.player.getHeight());

        List<BlockPos> ring = new ArrayList<>(10);
        Set<Long> seen = new HashSet<>(16);

        add(ring, seen, startPos.down());

        Direction direction = mc.player.getHorizontalFacing();
        for (int side = 0; side < 4; side++) {
            BlockPos column = startPos.offset(direction);
            for (int y = 0; y < playerHeight; y++) {
                add(ring, seen, column.up(y));
            }

            direction = rotateClockwise ? direction.rotateYClockwise() : direction.rotateYCounterclockwise();
        }

        add(ring, seen, startPos.up(playerHeight));

        switch (placeOrder.get()) {
            case Random -> Collections.shuffle(ring, random);
            case BottomTop -> ring.sort(Comparator.comparingInt(BlockPos::getY));
            case TopBottom -> ring.sort(Comparator.comparingInt(BlockPos::getY).reversed());
            case Normal -> { }
        }

        return ring;
    }

    private static void add(List<BlockPos> ring, Set<Long> seen, BlockPos pos) {
        if (seen.add(pos.asLong())) ring.add(pos.toImmutable());
    }

    /**
     * The ring with everything that is already filled - or that the player just mined and asked to
     * be left alone - dropped out of it.
     */
    private List<BlockPos> currentTargets() {
        long now = System.currentTimeMillis();
        int window = brokenBlockWindow.get();

        if (avoidBrokenBlocks.get()) {
            recentlyBroken.entrySet().removeIf(entry -> entry.getValue() + window <= now);
        }

        List<BlockPos> result = new ArrayList<>(10);
        for (BlockPos pos : orderedRing()) {
            if (!mc.world.getBlockState(pos).isReplaceable()) continue;
            if (avoidBrokenBlocks.get() && recentlyBroken.containsKey(pos.asLong())) continue;
            result.add(pos);
        }

        return result;
    }

    /**
     * A mined block is remembered so {@code avoid-broken-blocks} can leave its position alone. The
     * packet event runs on the network thread, so the write is handed to the client thread.
     */
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!avoidBrokenBlocks.get()) return;
        if (!(event.packet instanceof PlayerActionC2SPacket packet)) return;
        if (packet.getAction() != PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK) return;

        long pos = packet.getPos().asLong();
        mc.execute(() -> recentlyBroken.put(pos, System.currentTimeMillis()));
    }

    private boolean canRun() {
        if (mc.player == null || mc.world == null) return false;
        if (mc.interactionManager == null || mc.getNetworkHandler() == null) return false;
        if (mc.player.isDead() || mc.player.isSpectator()) return false;
        if (mc.currentScreen != null) return false;

        // Rotations refuses to work when the camera is a different entity, and a queued rotation
        // would then sit in its queue and be flushed later. Aiming without a rotation going out is
        // pointless, so do not even try.
        return mc.getCameraEntity() == mc.player;
    }

    // ---- Placing ----

    /**
     * Resolves the click that fills {@code target} and arms it.
     *
     * <p>The rotation and the interaction happen inside the same rotation update: the
     * aim is queued with {@link Rotations} and the interaction is its callback, which Meteor runs in
     * the tick the rotation actually goes into the movement packet. That keeps the rotation the
     * server validates the click against identical to the one the click was traced with.
     *
     * <p>Meteor drops the losers of a rotation priority contest without running their callbacks, so
     * a placement can be armed and never clicked. Nothing has to be undone when that happens - no
     * packet was sent - and the position is simply retried on the next tick.
     */
    private boolean place(BlockPos target) {
        if (!mc.world.getBlockState(target).isReplaceable()) return false;

        Slot slot = chooseSlot(target);
        if (slot == null) return false;

        // The server refuses a placement an entity is standing in, so the space is checked against
        // the block that would actually go in - air has an empty shape and would pass every time.
        Block block = blockOf(slot.stack());
        if (checkEntities.get() && block != null
            && !mc.world.canPlace(block.getDefaultState(), target, ShapeContext.absent())) {
            return false;
        }

        Vec3d eye = mc.player.getEyePos();
        Aim aim = findAim(target, eye, range.get());
        if (aim == null) return false;

        Runnable action = () -> {
            if (!stillValid(aim)) return;
            if (!interact(slot, aim.hit())) return;

            if (chatFeedback.get()) {
                info("Placed at %d, %d, %d on the %s face (%.2f blocks).",
                    aim.clicked().getX(), aim.clicked().getY(), aim.clicked().getZ(),
                    aim.face().asString(), aim.distance());
            }
        };

        if (rotate.get()) {
            Rotations.rotate(aim.yaw(), aim.pitch(), ROTATION_PRIORITY, action);
        } else {
            // With rotate off the aim is already the player's own rotation - findAim traced the ray
            // with it - so there is nothing to wait for.
            action.run();
        }

        return true;
    }

    /**
     * The nearest click that fills {@code target}, or null when nothing about it can be reached.
     *
     * <p>Two kinds of click are tried. The usual one lands on a neighbour: the face the neighbour
     * shares with the target is aimed at and the target is filled on the far side of it. The other
     * one lands on the target itself, which is what happens when the target already holds something
     * replaceable - tall grass, a snow layer - and the block goes in over the top of it.
     */
    private Aim findAim(BlockPos target, Vec3d eye, double reach) {
        Aim best = null;

        for (Direction face : FACES) {
            BlockPos support = target.offset(face);
            if (!isSupport(support)) continue;

            Direction clickedFace = face.getOpposite();

            // The centre of the face is what a player would click, and it is enough whenever the face
            // is fully visible. The four quarter points are only worth tracing when it is not.
            List<Vec3d> samples = faceSamples(support, clickedFace);
            Aim centre = cast(target, support, clickedFace, samples.get(0), eye, reach);
            if (centre != null) {
                best = nearer(best, centre);
                continue;
            }

            for (int i = 1; i < samples.size(); i++) {
                Aim aim = cast(target, support, clickedFace, samples.get(i), eye, reach);
                if (aim != null) best = nearer(best, aim);
            }
        }

        if (mc.world.getBlockState(target).isReplaceable()) {
            Aim aim = cast(target, target, null, Vec3d.ofCenter(target), eye, reach);
            if (aim != null) best = nearer(best, aim);
        }

        return best;
    }

    private static Aim nearer(Aim current, Aim candidate) {
        return current == null || candidate.distance() < current.distance() ? candidate : current;
    }

    /**
     * Traces from the eye with exactly the {@code (yaw, pitch)} that will be sent and keeps the hit
     * only when the server would put the block where it is wanted.
     *
     * <p>With {@code rotate} off the ray uses the player's own rotation instead, which is what makes
     * the switch mean "only what you are already looking at".
     */
    private Aim cast(BlockPos target, BlockPos clicked, Direction face, Vec3d point, Vec3d eye, double reach) {
        float yaw = rotate.get() ? (float) Rotations.getYaw(point) : mc.player.getYaw();
        float pitch = rotate.get() ? (float) Rotations.getPitch(point) : mc.player.getPitch();

        // Rebuild the direction from the angles that are about to be sent - the ray the server will
        // trace is the one this produces, not the ideal line from the eye to the point.
        Vec3d direction = Vec3d.fromPolar(pitch, yaw);
        Vec3d end = eye.add(direction.multiply(reach + 1.0));

        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));

        if (hit.getType() != HitResult.Type.BLOCK) return null;
        if (!hit.getBlockPos().equals(clicked)) return null;
        if (face != null && hit.getSide() != face) return null;

        // Leave a sliver of room: the server re-derives the direction with float trig and traces out
        // to exactly the block interaction range.
        double distance = hit.getPos().distanceTo(eye);
        if (distance > reach - 1.0E-3) return null;

        return new Aim(target, clicked, hit.getSide(), hit, yaw, pitch, distance);
    }

    /** The centre of {@code face} of {@code pos}, then four points a quarter of the way in from it. */
    private static List<Vec3d> faceSamples(BlockPos pos, Direction face) {
        Vec3d centre = faceCentre(pos, face);

        Vec3d u;
        Vec3d v;
        switch (face) {
            case UP, DOWN -> {
                u = new Vec3d(1.0, 0.0, 0.0);
                v = new Vec3d(0.0, 0.0, 1.0);
            }
            case EAST, WEST -> {
                u = new Vec3d(0.0, 1.0, 0.0);
                v = new Vec3d(0.0, 0.0, 1.0);
            }
            default -> {
                u = new Vec3d(1.0, 0.0, 0.0);
                v = new Vec3d(0.0, 1.0, 0.0);
            }
        }

        List<Vec3d> points = new ArrayList<>(5);
        points.add(centre);
        points.add(centre.add(u.multiply(SAMPLE_INSET)));
        points.add(centre.subtract(u.multiply(SAMPLE_INSET)));
        points.add(centre.add(v.multiply(SAMPLE_INSET)));
        points.add(centre.subtract(v.multiply(SAMPLE_INSET)));
        return points;
    }

    private static Vec3d faceCentre(BlockPos pos, Direction face) {
        return new Vec3d(
            pos.getX() + 0.5 + face.getOffsetX() * 0.5,
            pos.getY() + 0.5 + face.getOffsetY() * 0.5,
            pos.getZ() + 0.5 + face.getOffsetZ() * 0.5
        );
    }

    /**
     * The state the aim was resolved from is the state the server will validate it against, so this
     * can only really change if the world did. Re-check it anyway: a click that no longer makes
     * sense must not go out.
     */
    private boolean stillValid(Aim aim) {
        if (!mc.world.getBlockState(aim.target()).isReplaceable()) return false;

        BlockState clicked = mc.world.getBlockState(aim.clicked());
        if (clicked.isAir()) return false;
        if (!clicked.getFluidState().isEmpty()) return false;
        if (clicked.getCollisionShape(mc.world, aim.clicked()).isEmpty()) return false;

        if (aim.clicked().equals(aim.target())) return clicked.isReplaceable();

        return aim.clicked().offset(aim.face()).equals(aim.target());
    }

    /**
     * A neighbour that can be clicked to reach the target. Air, liquids, replaceable plants and
     * blocks with their own right-click behaviour are all out: the vanilla server wants a real,
     * full, non-interactive support.
     */
    private boolean isSupport(BlockPos pos) {
        if (!World.isValid(pos)) return false;

        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir()) return false;
        if (state.isReplaceable()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        if (BlockUtils.isClickable(state.getBlock())) return false;

        return !state.getCollisionShape(mc.world, pos).isEmpty();
    }

    /**
     * The slot to place from. While it is filling the ring the finder takes the hardest block it
     * has, and the softest otherwise, so the wall ends up being the strongest thing in
     * the hotbar. The slot already in hand is looked at first, so an equally hard block never costs
     * a swap.
     */
    private Slot chooseSlot(BlockPos target) {
        int selected = VersionCompat.getSelectedSlot(mc.player.getInventory());
        boolean hardest = targets.contains(target);

        List<Slot> candidates = new ArrayList<>(10);
        if (autoSwitch.get()) {
            for (int i = 0; i < PlayerInventory.HOTBAR_SIZE; i++) {
                int hotbar = (selected + i) % PlayerInventory.HOTBAR_SIZE;
                candidates.add(new Slot(hotbar, false, mc.player.getInventory().getStack(hotbar)));
            }
        } else {
            candidates.add(new Slot(selected, false, mc.player.getInventory().getStack(selected)));
        }
        candidates.add(new Slot(-1, true, mc.player.getOffHandStack()));

        Slot best = null;
        float bestHardness = 0.0f;

        for (Slot candidate : candidates) {
            Block block = blockOf(candidate.stack());
            if (block == null || !allowed(block)) continue;

            float hardness = block.getHardness();
            if (best == null || (hardest ? hardness > bestHardness : hardness < bestHardness)) {
                best = candidate;
                bestHardness = hardness;
            }
        }

        return best;
    }

    private boolean allowed(Block block) {
        boolean listed = blocks.get().contains(block);
        return blocksFilter.get() == Filter.Whitelist ? listed : !listed;
    }

    private static Block blockOf(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : null;
    }

    /**
     * Swaps the slot in if it is not the one in hand, and right clicks.
     *
     * <p>{@code interactBlock} is the vanilla right click path: it runs the item's own placement
     * logic on the client world, allocates the 1.19+ sequence id and sends the packet, so the module
     * never has to build a {@code PlayerInteractBlockC2SPacket} itself.
     */
    private boolean interact(Slot slot, BlockHitResult hit) {
        if (slot.offhand()) return click(Hand.OFF_HAND, hit);

        int previous = VersionCompat.getSelectedSlot(mc.player.getInventory());
        if (slot.hotbar() != previous) {
            if (!InvUtils.swap(slot.hotbar(), false)) return false;

            if (swapBack.get()) {
                // Hand the HUD its selection back right away, but without the packet: the packet
                // goes out at the head of the next tick, where no right click shares it.
                VersionCompat.setSelectedSlot(mc.player.getInventory(), previous);
                restoreSlot = previous;
            }
        }

        return click(Hand.MAIN_HAND, hit);
    }

    private boolean click(Hand hand, BlockHitResult hit) {
        ActionResult result = mc.interactionManager.interactBlock(mc.player, hand, hit);
        if (!result.isAccepted()) return false;

        if (swing.get()) mc.player.swingHand(hand);
        else mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));

        return true;
    }

    /**
     * Puts the hotbar slot back after a block was placed. Runs at the head of the tick, before
     * anything else can send a packet, so the swap cannot land in the same tick as a placement. The
     * client-side selection itself was already put back when the block was placed.
     */
    private void restoreHotbarSlot() {
        int slot = restoreSlot;
        restoreSlot = -1;

        if (slot < 0 || mc.player == null) return;

        // InvUtils.swap writes the slot and syncs it, which sends the UpdateSelectedSlot packet.
        InvUtils.swap(slot, false);
    }

    // ---- Rendering ----

    /** Draws the ring that is still missing, the position about to be filled picked out. */
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || mc.player == null || mc.world == null) return;

        List<BlockPos> ring = targets;
        for (int i = 0; i < ring.size(); i++) {
            boolean next = i == 0;
            event.renderer.box(
                ring.get(i),
                next ? nextSideColor.get() : sideColor.get(),
                next ? nextLineColor.get() : lineColor.get(),
                shapeMode.get(), 0);
        }
    }

    // ---- Types ----

    /** A resolved placement: the block to fill, the click that fills it and the aim that makes it. */
    private record Aim(
        BlockPos target,
        BlockPos clicked,
        Direction face,
        BlockHitResult hit,
        float yaw,
        float pitch,
        double distance
    ) {
    }

    /** A slot a block can be placed from: a hotbar slot, or the offhand. */
    private record Slot(int hotbar, boolean offhand, ItemStack stack) {
    }

    /** Which blocks the block list allows. */
    public enum Filter {
        Whitelist,
        Blacklist
    }

    /** The order the ring is filled in. */
    public enum Order {
        Normal,
        Random,
        BottomTop,
        TopBottom
    }
}
