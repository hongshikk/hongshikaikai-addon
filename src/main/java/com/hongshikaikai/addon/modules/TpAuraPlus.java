package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.AStarPathFinder;
import com.hongshikaikai.addon.utils.PlayerWireframe;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Attacks from somewhere else: the client is teleported on the server's side only, the hits go out
 * from the teleported position, and the client itself never moves.
 *
 * <h2>The desync is the whole trick</h2>
 * A movement packet is the only thing that tells the server where the player is. So the module does
 * not move the player at all - it leaves the client exactly where it is and keeps a second position,
 * {@link #desyncPos}, which is what every movement packet is rewritten to carry. The server moves
 * the player it is tracking to that position; the client keeps walking, rendering and colliding
 * where it was. An attack sent while the desync is up is resolved by the server at the desynced
 * position, which is why the range check against a target is written against {@code desyncPos} and
 * not against the client's own feet.
 *
 * <h2>Rewriting the packet</h2>
 * The movement packet's position fields are {@code protected final} in Yarn, so the packet is
 * cancelled at {@link PacketEvent.Send} and a replacement is sent built with the desync position
 * instead - the same packet type, the same on-ground flag, the same rotation. The replacement is
 * sent with {@link #selfSend} set, so the handler lets it straight through: without that guard the
 * module's own teleports would be rewritten to the position they are meant to replace, and the two
 * would chase each other.
 *
 * <p>Every movement packet is rewritten, not only the ones that already carried a position. A
 * look-only packet turned loose would reach the server without one, and the server would keep the
 * last position it was told - which after the first rewrite is already the desync position, so it
 * would be harmless, but a packet that carries it explicitly leaves nothing to infer.
 *
 * <h2>Two ways to cover the distance</h2>
 * <ul>
 *     <li><b>Immediate</b> blinks straight at the target: enough filler packets at the current
 *         position to burn the server's "moved too quickly" allowance, then one at the target, a
 *         short stay, and one back.</li>
 *     <li><b>AStar</b> asks {@link AStarPathFinder} for a route through the terrain and walks the
 *         desync along it in chunks, each chunk either a single packet to its end when the straight
 *         line is clear or one packet per block when it is not. It can be told to make the return
 *         trip, or to leave the server-side player where it arrived.</li>
 * </ul>
 *
 * <h2>When the server refuses</h2>
 * A {@link PlayerPositionLookS2CPacket} is the server moving the player by itself - a setback, a
 * teleport, a pearl. Either way the desync is over: the packet handler reports it and drops the
 * position, the path and the in-flight mode state, so the module starts from wherever the server
 * put the player rather than from a position that no longer exists.
 *
 * <h2>Implementation notes</h2>
 * <ul>
 *     <li>Attack timing reads a CPS range turned into one tick per click on average, plus the
 *         vanilla attack cooldown, which is what gates the rate in practice.</li>
 *     <li>The attack is the vanilla attack, the same call {@code KillAura+} makes, so a hit resets
 *         your sprint exactly as a normal click does.</li>
 *     <li>The player model is built from the same six boxes with the yaw applied to each corner by
 *         hand, drawn as a wireframe with translucent sides: Meteor's 3D renderer batches every
 *         vertex and applies the matrix stack once at the end, so a per-model transform cannot be
 *         pushed around the vertices.</li>
 *     <li>The path search runs on the tick thread, bounded by {@code maximum-cost} and a fixed
 *         500-node cap - the search is skipped entirely when no target is inside
 *         {@code maximum-distance}, which is the common case.</li>
 * </ul>
 */
public class TpAuraPlus extends Module {
    /** The node cap the search runs under. */
    private static final int MAX_ITERATIONS = 500;
    /** How close to the goal block the path is allowed to stop, in blocks. */
    private static final double STOP_RANGE = 2.0;
    /** How many ticks Immediate stays on the far side. */
    private static final int BLINK_TICKS = 20;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargets = settings.createGroup("Targets");
    private final SettingGroup sgAStar = settings.createGroup("AStar");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    private final Setting<Double> attackRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("attack-range")
        .description("How far from the desynced position a target may be to be attacked. The server resolves the hit at that position, so this is the reach that matters.")
        .defaultValue(4.2)
        .min(3.0)
        .max(5.0)
        .sliderRange(3.0, 5.0)
        .build()
    );

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Immediate blinks straight at the target and back. AStar pathfinds through the terrain and walks the desync along the route.")
        .defaultValue(Mode.AStar)
        .build()
    );

    private final Setting<Integer> minCps = sgGeneral.add(new IntSetting.Builder()
        .name("min-cps")
        .description("Lowest clicks per second the clicker plans. The planned rate is only what it asks for - the vanilla attack cooldown still gates every swing.")
        .defaultValue(11)
        .min(1)
        .max(20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> maxCps = sgGeneral.add(new IntSetting.Builder()
        .name("max-cps")
        .description("Highest clicks per second the clicker plans.")
        .defaultValue(14)
        .min(1)
        .max(20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> cooldown = sgGeneral.add(new BoolSetting.Builder()
        .name("cooldown")
        .description("Only swing when the 1.9 attack cooldown is charged. Off attacks at the planned CPS instead, which mostly lands weaker hits.")
        .defaultValue(true)
        .build()
    );

    // Targets

    private final Setting<Boolean> players = sgTargets.add(new BoolSetting.Builder()
        .name("players")
        .description("Target players. Friends are always skipped.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hostile = sgTargets.add(new BoolSetting.Builder()
        .name("hostile")
        .description("Target hostile mobs.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> passive = sgTargets.add(new BoolSetting.Builder()
        .name("passive")
        .description("Target passive mobs - animals, villagers and the like.")
        .defaultValue(false)
        .build()
    );

    private final Setting<TargetPriority> priority = sgTargets.add(new EnumSetting.Builder<TargetPriority>()
        .name("priority")
        .description("Which target to pick when several are in reach. Hurt-time finishes what is already being hit.")
        .defaultValue(TargetPriority.HurtTime)
        .build()
    );

    private final Setting<Double> targetRange = sgTargets.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("How far away Immediate may look for a target. The cap keeps a blink from crossing the whole render distance.")
        .defaultValue(50.0)
        .min(5.0)
        .max(250.0)
        .sliderRange(5.0, 250.0)
        .build()
    );

    // AStar

    private final Setting<Integer> maximumDistance = sgAStar.add(new IntSetting.Builder()
        .name("maximum-distance")
        .description("How far from the player a target may be for AStar to consider it. Nothing is searched when no target is inside this, which is what keeps the search off the tick in a quiet fight.")
        .defaultValue(95)
        .min(20)
        .max(250)
        .sliderRange(20, 250)
        .visible(() -> mode.get() == Mode.AStar)
        .build()
    );

    private final Setting<Integer> maximumCost = sgAStar.add(new IntSetting.Builder()
        .name("maximum-cost")
        .description("Longest route AStar may return. Edges cost their squared length, so one nine-block climb spends 81 of the budget on its own.")
        .defaultValue(250)
        .min(50)
        .max(500)
        .sliderRange(50, 500)
        .visible(() -> mode.get() == Mode.AStar)
        .build()
    );

    private final Setting<Integer> tickDistance = sgAStar.add(new IntSetting.Builder()
        .name("tick-distance")
        .description("How many path blocks one teleport may cover when the straight line between them is clear. Lower sends more packets.")
        .defaultValue(3)
        .min(1)
        .max(7)
        .sliderRange(1, 7)
        .visible(() -> mode.get() == Mode.AStar)
        .build()
    );

    private final Setting<Boolean> allowDiagonal = sgAStar.add(new BoolSetting.Builder()
        .name("allow-diagonal")
        .description("Let the path cut corners. A diagonal is only taken when both of the straight sides it crosses are open too.")
        .defaultValue(false)
        .visible(() -> mode.get() == Mode.AStar)
        .build()
    );

    private final Setting<Boolean> tpBack = sgAStar.add(new BoolSetting.Builder()
        .name("tp-back")
        .description("Travel the route in reverse after the stay. Off leaves the server-side player where it arrived and lets it be corrected on the next ordinary movement packet.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.AStar)
        .build()
    );

    private final Setting<Integer> stick = sgAStar.add(new IntSetting.Builder()
        .name("stick")
        .description("How many ticks to stay at the far end of the route before coming back.")
        .defaultValue(5)
        .min(1)
        .max(10)
        .sliderRange(1, 10)
        .visible(() -> mode.get() == Mode.AStar)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Master switch for everything below.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderPlayer = sgRender.add(new BoolSetting.Builder()
        .name("render-player")
        .description("Draw a player-shaped wireframe where the server thinks you are.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> playerColor = sgRender.add(new ColorSetting.Builder()
        .name("player-color")
        .description("Colour of the wireframe player.")
        .defaultValue(new SettingColor(36, 32, 147, 255))
        .visible(() -> render.get() && renderPlayer.get())
        .build()
    );

    private final Setting<Boolean> renderPath = sgRender.add(new BoolSetting.Builder()
        .name("render-path")
        .description("Draw the AStar route as a line through the block centres it runs over.")
        .defaultValue(true)
        .visible(() -> render.get() && mode.get() == Mode.AStar)
        .build()
    );

    private final Setting<SettingColor> pathColor = sgRender.add(new ColorSetting.Builder()
        .name("path-color")
        .description("Colour of the route line.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(() -> render.get() && mode.get() == Mode.AStar && renderPath.get())
        .build()
    );

    private final Setting<Boolean> renderLine = sgRender.add(new BoolSetting.Builder()
        .name("render-line")
        .description("Draw a line from you to the desynced position while Immediate is out.")
        .defaultValue(true)
        .visible(() -> render.get() && mode.get() == Mode.Immediate)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Colour of the Immediate line.")
        .defaultValue(new SettingColor(36, 32, 147, 255))
        .visible(() -> render.get() && mode.get() == Mode.Immediate && renderLine.get())
        .build()
    );

    /** Where the server thinks the player is, or null when no desync is up. */
    private Vec3d desyncPos;

    /**
     * True while the module is itself sending a movement packet. Everything the module sends would
     * otherwise come back through {@link #onSendPacket} and be rewritten to the position it was
     * trying to set.
     */
    private boolean selfSend;

    /** Ticks owed to the click plan; a click happens on the tick this reaches zero. */
    private double clickDebt;
    /** Whether this tick is a click tick, decided once so every reader sees the same answer. */
    private boolean clickReady;

    /**
     * Set from the network thread when the server moves the player, drained on the game thread on
     * the next tick. Volatile because the two are not the same thread.
     */
    private volatile boolean setback;

    private final Random random = new Random();

    // AStar state

    /** The route currently being travelled or drawn. */
    private List<BlockPos> path = Collections.emptyList();
    /** Where the next route should start, or null to start from the player's own position. */
    private BlockPos pathStart;
    /** True while the stay at the far end is counting down. */
    private boolean returning;
    private int stickTimer;

    // Immediate state

    /** Ticks left on the far side; zero means Immediate is idle. */
    private int blinkTimer;
    /** Where Immediate was when it blinked out, so it can blink back. */
    private Vec3d blinkOrigin;

    public TpAuraPlus() {
        super(HongShiKaiKai.CATEGORY, "tpaura+", "Attacks from a server-side position the client never moves to, by blinking or by pathfinding, and only while the desync is up.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    /** Drops every piece of state, so nothing survives a toggle or a server-side setback. */
    private void reset() {
        desyncPos = null;
        selfSend = false;
        setback = false;
        clickDebt = 0.0;
        clickReady = false;
        path = Collections.emptyList();
        pathStart = null;
        returning = false;
        stickTimer = 0;
        blinkTimer = 0;
        blinkOrigin = null;
    }

    // ---- Tick ----

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (setback) {
            setback = false;
            reportSetback();
        }

        tickClicker();

        // Travel and the swing stand down together: a teleport that cannot be followed by an attack
        // is a trip taken for nothing, and a screen is the one state where an attack would be built
        // and then thrown away. Timers freeze rather than being skipped, so a pause mid-route
        // resumes where it left off instead of firing the return leg the moment the screen closes.
        if (canAct()) {
            if (mode.get() == Mode.AStar) {
                tickAStar();
            } else {
                tickImmediate();
            }

            attackFromDesync();
        }
    }

    /** Whether the module may act at all this tick. */
    private boolean canAct() {
        return mc.currentScreen == null && !mc.player.isDead() && !mc.player.isSpectator();
    }

    /**
     * Turns the CPS range into click ticks.
     *
     * <p>The debt is carried in ticks rather than rounded per click, so a rate that does not divide
     * 20 evenly - 14 clicks a second is one every 1.43 ticks - still averages out over time instead
     * of collapsing to the nearest whole tick.
     */
    private void tickClicker() {
        clickReady = false;
        clickDebt -= 1.0;
        if (clickDebt > 0.0) return;

        clickReady = true;

        int low = Math.min(minCps.get(), maxCps.get());
        int high = Math.max(minCps.get(), maxCps.get());
        int cps = low + (high > low ? random.nextInt(high - low + 1) : 0);

        clickDebt += 20.0 / cps;
    }

    // ---- Attacking ----

    /**
     * Swings at the best target in reach of the desynced position.
     *
     * <p>The range is measured against {@code desyncPos} because that is the position the server
     * will resolve the hit from. When no desync is up the player's own position is used, which is
     * what makes the module behave as a plain aura between teleports rather than going silent.
     */
    private void attackFromDesync() {
        if (!clickReady) return;
        if (cooldown.get() && mc.player.getAttackCooldownProgress(0.5f) < 1.0f) return;

        Vec3d origin = desyncPos != null ? desyncPos : entityPos(mc.player);
        LivingEntity target = selectTarget(origin, attackRange.get());
        if (target == null) return;

        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    // ---- Targeting ----

    /**
     * The best target within {@code range} of {@code origin}, by the setting's priority.
     *
     * <p>Distance is boxed - measured to the target's hitbox rather than to its feet - so a target
     * whose box overlaps the range counts.
     */
    private LivingEntity selectTarget(Vec3d origin, double range) {
        double maximumSquared = range * range;

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!isCandidate(living)) continue;

            double squared = living.getBoundingBox().squaredMagnitude(origin);
            if (squared > maximumSquared) continue;

            double score = priority.get().score(living, squared);
            if (score >= bestScore) continue;

            bestScore = score;
            best = living;
        }

        return best;
    }

    /** The nearest target within {@code range}, which is the only ordering Immediate uses. */
    private LivingEntity selectNearest(Vec3d origin, double range) {
        double maximumSquared = range * range;

        LivingEntity best = null;
        double bestSquared = Double.MAX_VALUE;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!isCandidate(living)) continue;

            double squared = living.getBoundingBox().squaredMagnitude(origin);
            if (squared > maximumSquared || squared >= bestSquared) continue;

            bestSquared = squared;
            best = living;
        }

        return best;
    }

    /** Whether this entity is something the module would attack at all - the filters, not a range. */
    private boolean isCandidate(LivingEntity living) {
        if (living == mc.player || !living.isAlive() || living.isDead() || living.isSpectator()) return false;

        if (living instanceof PlayerEntity player) {
            return players.get() && !Friends.get().isFriend(player);
        }

        if (living instanceof Monster) return hostile.get();
        if (living instanceof PassiveEntity) return passive.get();

        // Anything else living - armour stands, and the like - is left alone rather than guessed at.
        return false;
    }

    // ---- Immediate ----

    /**
     * Blinks at the nearest target, holds for {@link #BLINK_TICKS}, then blinks back.
     *
     * <p>The origin is captured on the tick of the blink rather than read on the way back, because
     * the client keeps walking while the server-side player is away and the return has to land where
     * the client actually is.
     */
    private void tickImmediate() {
        if (blinkTimer > 0) {
            blinkTimer--;
            if (blinkTimer > 0) return;

            travelImmediate(blinkOrigin);
            desyncPos = null;
            blinkOrigin = null;
            return;
        }

        if (!clickReady) return;

        Vec3d origin = entityPos(mc.player);
        LivingEntity target = selectNearest(origin, targetRange.get());
        if (target == null) return;

        travelImmediate(entityPos(target));
        blinkOrigin = origin;
        blinkTimer = BLINK_TICKS;
    }

    /**
     * Sends the filler packets that make a long jump look like movement, then the packet that lands.
     *
     * <p>A server refuses a position further than a few blocks from the last one to avoid a
     * different thread's "moved too quickly" check - the fillers move the reported position in
     * steps the server will accept, so the final packet is only ever a short hop from the one before
     * it. The count is one step per ten blocks of Manhattan distance, less the packet that lands.
     */
    private void travelImmediate(Vec3d position) {
        if (position == null) return;

        double deltaX = position.x - mc.player.getX();
        double deltaY = position.y - mc.player.getY();
        double deltaZ = position.z - mc.player.getZ();

        int fillers = (int) Math.floor((Math.abs(deltaX) + Math.abs(deltaY) + Math.abs(deltaZ)) / 10.0) - 1;

        for (int i = 0; i < fillers; i++) {
            sendFull(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        }

        sendFull(position.x, position.y, position.z);
        desyncPos = position;
    }

    /** One teleport packet at {@code (x, y, z)}, carrying the player's own rotation and flags. */
    private void sendFull(double x, double y, double z) {
        sendMovePacket(new PlayerMoveC2SPacket.Full(
            x, y, z,
            mc.player.getYaw(), mc.player.getPitch(),
            mc.player.isOnGround(), mc.player.horizontalCollision
        ));
    }

    // ---- AStar ----

    /**
     * One tick of the AStar mode: search while idle, travel on a click tick, hold, come back.
     *
     * <p>The search runs every idle tick, but the result is only acted on when
     * a click is planned - the teleport exists to carry an attack, and arriving on a tick with no
     * swing wastes the trip and the packets with it.
     *
     * <p>{@code tp-back} off keeps the desync at the far end of the route rather than clearing it,
     * and records that far end as the start of the next search: the next route is then computed
     * from where the server-side player actually is, and its first teleport is a short hop instead
     * of a cross-map jump that would get set back.
     */
    private void tickAStar() {
        if (returning) {
            if (stickTimer > 0) {
                stickTimer--;
                return;
            }

            if (tpBack.get()) {
                travel(reversed(path));
                desyncPos = null;
                pathStart = null;
            } else if (desyncPos != null) {
                // The server-side player stays where it arrived, and the next route starts there.
                pathStart = BlockPos.ofFloored(desyncPos);
            }
            path = Collections.emptyList();
            returning = false;
            return;
        }

        List<BlockPos> found = findPath();
        path = found;
        if (found.isEmpty() || !clickReady) return;

        travel(found);
        returning = true;
        stickTimer = stick.get();
    }

    /**
     * The first route that reaches a target, or an empty list.
     *
     * <p>Targets are tried nearest-first and only those inside {@code maximum-distance} of the eye
     * are considered at all, so a fight that is too far away costs nothing - no target means no
     * search, which is what keeps the tick-thread search affordable.
     */
    private List<BlockPos> findPath() {
        BlockPos start = pathStart != null ? pathStart : mc.player.getBlockPos();
        Vec3d eye = mc.player.getEyePos();
        double maximumSquared = (double) maximumDistance.get() * maximumDistance.get();

        List<LivingEntity> candidates = new ArrayList<>();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!isCandidate(living)) continue;
            if (living.getBoundingBox().squaredMagnitude(eye) > maximumSquared) continue;

            candidates.add(living);
        }

        candidates.sort(Comparator.comparingDouble(living -> living.getBoundingBox().squaredMagnitude(eye)));

        for (LivingEntity candidate : candidates) {
            List<BlockPos> found = AStarPathFinder.findPath(
                mc.world, mc.player, start, candidate.getBlockPos(),
                allowDiagonal.get(), maximumCost.get(), MAX_ITERATIONS, STOP_RANGE
            );

            if (!found.isEmpty()) return found;
        }

        return Collections.emptyList();
    }

    /**
     * Sends the route as teleports.
     *
     * <p>The path is cut into chunks of {@code tick-distance}. A chunk whose straight line is clear
     * goes out as one packet to its far end; one that is not goes out block by block, because a
     * single packet from the start to the end of a chunk that has a wall in it would put the
     * server-side player inside the wall. A clear chunk targets the centre of its last block, a
     * stepped one targets the floor of each block.
     */
    private void travel(List<BlockPos> blocks) {
        if (blocks.isEmpty()) return;

        int chunkSize = tickDistance.get();

        for (int i = 0; i < blocks.size(); i += chunkSize) {
            List<BlockPos> chunk = blocks.subList(i, Math.min(i + chunkSize, blocks.size()));
            Vec3d start = center(chunk.get(0));
            Vec3d end = center(chunk.get(chunk.size() - 1));

            if (hasBlockCollision(start, end)) {
                for (BlockPos position : chunk) {
                    sendTeleport(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
                    desyncPos = bottomCenter(position);
                }
            } else {
                sendTeleport(end.x, end.y, end.z);
                desyncPos = end;
            }
        }
    }

    private void sendTeleport(double x, double y, double z) {
        sendMovePacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, false));
    }

    private boolean hasBlockCollision(Vec3d from, Vec3d to) {
        for (VoxelShape shape : mc.world.getBlockCollisions(mc.player, new Box(from, to))) {
            if (!shape.isEmpty()) return true;
        }

        return false;
    }

    /** The centre of a block: what a clear chunk aims at. */
    private static Vec3d center(BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** The floor centre of a block: where a body standing on it has its feet. */
    private static Vec3d bottomCenter(BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    /**
     * An entity's position, built from its coordinates.
     *
     * <p>1.21.10 renamed {@code Entity.getPos()} to {@code getEntityPos()} while both still read the
     * same field, so the coordinates are read directly - the same way {@code KillAura+} does it, and
     * the reason no third source family had to be added for it.
     */
    private static Vec3d entityPos(Entity entity) {
        return new Vec3d(entity.getX(), entity.getY(), entity.getZ());
    }

    // ---- Packets ----

    /**
     * Keeps every movement packet the client sends on the desynced position.
     *
     * <p>The packet is cancelled and rebuilt rather than written into: Yarn declares its position
     * fields {@code protected final}, so there is nothing to write. The replacement keeps the
     * packet's own rotation and takes the on-ground flag and the collision flag from the player, the
     * same values the packet was built from a moment earlier.
     */
    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (selfSend) return;
        if (event.isCancelled()) return;
        if (desyncPos == null) return;
        if (!(event.packet instanceof PlayerMoveC2SPacket packet)) return;

        event.cancel();

        if (packet instanceof PlayerMoveC2SPacket.Full || packet instanceof PlayerMoveC2SPacket.LookAndOnGround) {
            sendMovePacket(new PlayerMoveC2SPacket.Full(
                desyncPos.x, desyncPos.y, desyncPos.z,
                packet.getYaw(0.0f), packet.getPitch(0.0f),
                mc.player.isOnGround(), mc.player.horizontalCollision
            ));
        } else {
            sendMovePacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                desyncPos.x, desyncPos.y, desyncPos.z,
                mc.player.isOnGround(), mc.player.horizontalCollision
            ));
        }
    }

    /** Sends a packet the module built itself, exempt from the rewrite above. */
    private void sendMovePacket(PlayerMoveC2SPacket packet) {
        if (mc.getNetworkHandler() == null) return;

        selfSend = true;
        try {
            mc.getNetworkHandler().getConnection().send(packet);
        } finally {
            selfSend = false;
        }
    }

    /**
     * A server-side teleport means the desync is over, whichever module asked for it: a setback, a
     * portal, a pearl. Everything in flight is dropped so the next tick starts from the player's
     * real position rather than from one the server has already overwritten.
     *
     * <p>A play packet is handed to the client on netty's event loop, not on the game thread, so the
     * work is only done here when this really is the game thread. Off it - which is the normal case
     * - the flag is set and the tick handler picks it up, because reporting the message and clearing
     * the position both touch state the game thread is using.
     *
     * <p>The message carries no coordinates on purpose. Reading them means naming the packet's
     * position record, which 1.21.10 renamed from {@code PlayerPosition} to {@code EntityPosition} -
     * a version split that would need a third source family for one line of chat.
     */
    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof PlayerPositionLookS2CPacket)) return;

        if (mc.isOnThread()) {
            reportSetback();
        } else {
            setback = true;
        }
    }

    /** Reports a setback and drops everything the module had in flight. */
    private void reportSetback() {
        error("Server setback detected - teleport failed!");
        reset();
    }

    // ---- Render ----

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || mc.player == null) return;

        if (mode.get() == Mode.AStar && renderPath.get() && !path.isEmpty()) drawPath(event);
        if (renderPlayer.get() && desyncPos != null) drawWirePlayer(event);

        if (mode.get() == Mode.Immediate && renderLine.get() && desyncPos != null) {
            event.renderer.line(
                mc.player.getX(), mc.player.getY() + 1.0, mc.player.getZ(),
                desyncPos.x, desyncPos.y + 1.0, desyncPos.z,
                lineColor.get()
            );
        }
    }

    /** The route as a line through the centre of every block it runs over. */
    private void drawPath(Render3DEvent event) {
        for (int i = 1; i < path.size(); i++) {
            Vec3d from = center(path.get(i - 1));
            Vec3d to = center(path.get(i));

            event.renderer.line(from.x, from.y, from.z, to.x, to.y, to.z, pathColor.get());
        }
    }

    /**
     * A player-shaped wireframe where the server thinks the player is, turned to the yaw the player
     * is looking with. The shape itself lives in {@link PlayerWireframe}, which FakeLag draws too.
     */
    private void drawWirePlayer(Render3DEvent event) {
        PlayerWireframe.draw(event, desyncPos, mc.player.getYaw(), playerColor.get());
    }

    private static List<BlockPos> reversed(List<BlockPos> blocks) {
        List<BlockPos> reversed = new ArrayList<>(blocks);
        Collections.reverse(reversed);
        return reversed;
    }

    // ---- Enums ----

    public enum Mode {
        AStar,
        Immediate
    }

    /**
     * How targets are ordered when more than one is in reach.
     *
     * <p>{@code HurtTime} finishes the entity that has been hit most recently first, which is what
     * stops a fight from spreading damage across several targets that all heal back up.
     */
    public enum TargetPriority {
        HurtTime {
            @Override
            double score(LivingEntity target, double squaredDistance) {
                // Hurt-time counts down, so a lower value is a fresher hit; distance breaks ties.
                return target.hurtTime * 1.0E6 + squaredDistance;
            }
        },
        LowestDistance {
            @Override
            double score(LivingEntity target, double squaredDistance) {
                return squaredDistance;
            }
        },
        LowestHealth {
            @Override
            double score(LivingEntity target, double squaredDistance) {
                return target.getHealth() * 1.0E6 + squaredDistance;
            }
        };

        /** Lower is better. */
        abstract double score(LivingEntity target, double squaredDistance);
    }
}
