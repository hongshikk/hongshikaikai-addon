package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.PlayerWireframe;
import com.hongshikaikai.addon.utils.VelocityCompat;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.option.Perspective;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.ResourcePackStatusC2SPacket;
import net.minecraft.network.packet.c2s.handshake.HandshakeC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.SpectatorTeleportC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.c2s.query.QueryRequestC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * FakeLag: holds outgoing packets back while an enemy is near and releases them in one burst, so
 * the server - and therefore the enemy - keeps seeing a position the player has already left.
 *
 * <h2>The shape of the trick</h2>
 * Nothing here is spoofed - every packet that goes out is a packet the client really built. What
 * changes is <em>when</em> it goes out. While an enemy is near, packets are cancelled on their way
 * to the connection and kept in a queue, so the server's copy of the player stops advancing while
 * the client carries on simulating at full speed. When the hold ends - the random delay ran out, or
 * something happened that must not be delayed - the queue is written to the connection in its
 * original order and the server snaps forward.
 *
 * <p>For the enemy this looks like a player who is a few hundred milliseconds behind: their aim
 * leads a position that no longer exists. That is the whole module.
 *
 * <p>It is worth being exact about what that buys, because the usual claim - "the enemy cannot hit
 * you any more" - is not it. The server keeps broadcasting the held position, so the enemy's client
 * renders the player at that same point, and the server resolves the enemy's attack against that
 * same point: the aim and the hitbox agree, and a hit that was going to land still lands. What the
 * hold actually breaks is everything that needs <em>new</em> positions to work - aim that
 * extrapolates from velocity, projectile lead, a backtrack window, a read on where the player is
 * going - and it removes the player's own movement from the enemy's screen while it does so. It is
 * a disengaging tool, which is also why {@code Dynamic} holds only when the position the server
 * believes in is at least as far from the enemy as the real one.
 *
 * <h2>What it is not</h2>
 * Holding packets is a deliberate, visible desynchronisation, so unlike the rest of this addon this
 * module has no Grim story to tell. Grim sees it directly: movement arrives in bursts instead of
 * once a tick, transactions and keep-alives come back late, and every flush is a reconciliation
 * against a position the server has to accept or reject. Whether that is survivable is a property
 * of the server's lag compensation and its reach/backtrack window, not of anything this code can
 * control. It is also not free for the player: everything the server would have acknowledged during
 * the hold - block changes, item use, knockback - is delivered late with it.
 *
 * <p>The module therefore leaves its own safety valves in place. Lagging stops while the player is
 * dead or spectating, while in water, and whenever a screen is open; it stops while the player is
 * eating or drinking; and it releases the queue on a teleport, on taking damage, on being knocked
 * back and on an explosion, because those are exactly the moments when making the server wait
 * makes the next second worse rather than better.
 *
 * <h2>Where the queue lives</h2>
 * The queue is module-local: it is created empty on activation, flushed on deactivation, and
 * cleared when the world is left. Flush on teleport, on death and on disconnect is handled in
 * {@link #onReceive(PacketEvent.Receive)} and {@link #onGameLeft(GameLeftEvent)}.
 *
 * <h2>The render</h2>
 * Two drawings are fed by the position history the queue keeps, under {@code Render}, and they are
 * the reason the render needs no state of its own:
 *
 * <ul>
 *     <li>the ghost - a box or a skeleton - stands at the position in the <em>oldest</em> held
 *         movement packet, which is the position the server is working from, and it faces the yaw
 *         that packet carries instead of the camera's;</li>
 *     <li>the trail joins up every held position, oldest first: the path the server has not been
 *         told about yet;</li>
 *     <li>the ghost is hidden in first person while the trail is not - it is a path on the ground
 *         and reads as the way the player came.</li>
 * </ul>
 *
 * <p>Both are read off the queue the send path writes, so an empty screen means nothing is being
 * held. There is no mode that draws a second copy of the player through the entity renderer:
 * Meteor's renderer has no equivalent, and a wireframe is the honest neighbour of it rather than a
 * fake of it.
 *
 * <h2>Flush on hit</h2>
 * The flush-on-damage, flush-on-knockback and flush-on-explosion branches are checked where the
 * packets actually arrive, and they are the {@code On Hit} settings below.
 *
 * <h2>Versions</h2>
 * Every packet class and method named in this file was read off the yarn-named jar for each
 * supported version (1.21.4 - 1.21.11) with {@code javap}, not guessed. Two of them are not
 * obviously version-stable and are worth naming: {@code PlayerMoveC2SPacket.changesPosition()} is
 * the position-presence test used here, and attacking has no dedicated packet in yarn at all -
 * it is still an {@code InteractType} of {@code PlayerInteractEntityC2SPacket} on every
 * version this addon builds for, which is what the flush set matches on.
 *
 * <p>The third is the clientbound entity-motion packet, and it is the one difference that could not
 * be written around: up to 1.21.8 it exposes {@code getVelocityX/Y/Z()}, and from 1.21.10 those
 * three are one {@code getVelocity()} returning a {@code Vec3d}, with no accessor in common. That
 * read therefore lives in {@link com.hongshikaikai.addon.utils.VelocityCompat}, which exists in two
 * generations under {@code src/families/velocity-{legacy,modern}} - the same mechanism the build
 * already uses for the splash and compat differences.
 *
 * <p>The render's two reads were checked the same way on all seven jars rather than assumed from an
 * older version: {@code PlayerMoveC2SPacket.getYaw(float)} for the ghost's facing, and
 * {@code GameOptions.getPerspective()} against {@code Perspective.FIRST_PERSON} for the
 * first-person hide.
 */
public class FakeLag extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFlushOn = settings.createGroup("Flush On");
    private final SettingGroup sgOnHit = settings.createGroup("On Hit");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Constant holds for the whole hold time whenever the guards allow it. Dynamic holds only while an enemy is near and the held position is the more useful one to be seen at.")
        .defaultValue(Mode.Dynamic)
        .build()
    );

    private final Setting<Integer> minDelay = sgGeneral.add(new IntSetting.Builder()
        .name("min-delay")
        .description("Shortest hold time, in milliseconds. The hold time is picked fresh from min-delay..max-delay each time the queue is released.")
        .defaultValue(300)
        .min(0)
        .max(5000)
        .sliderRange(0, 1000)
        .build()
    );

    private final Setting<Integer> maxDelay = sgGeneral.add(new IntSetting.Builder()
        .name("max-delay")
        .description("Longest hold time, in milliseconds. Longer holds desynchronise harder and are more likely to be rejected outright.")
        .defaultValue(600)
        .min(0)
        .max(5000)
        .sliderRange(0, 1000)
        .build()
    );

    private final Setting<Integer> recoilTime = sgGeneral.add(new IntSetting.Builder()
        .name("recoil-time")
        .description("After a flush that was triggered rather than timed out - an interaction, damage, knockback - wait this long, in milliseconds, before holding anything again. 0 starts holding again on the very next packet.")
        .defaultValue(250)
        .min(0)
        .max(2000)
        .sliderRange(0, 1000)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Outer radius, in blocks, of the enemy search.")
        .defaultValue(5.0)
        .min(0.0)
        .max(10000.0)
        .sliderRange(0.0, 10.0)
        .build()
    );

    private final Setting<Double> minRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-range")
        .description("Inner radius, in blocks, of the enemy search: an enemy closer than this does not arm Dynamic mode. It gates the enemy search only - the positional test still looks inside the inner radius, because it has to see an entity the player is standing in.")
        .defaultValue(2.0)
        .min(0.0)
        .max(10.0)
        .sliderRange(0.0, 10.0)
        .build()
    );

    private final Setting<Boolean> players = sgGeneral.add(new BoolSetting.Builder()
        .name("players")
        .description("Count other players as enemies. Friends are never counted.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hostile = sgGeneral.add(new BoolSetting.Builder()
        .name("hostile")
        .description("Count hostile mobs as enemies.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> passive = sgGeneral.add(new BoolSetting.Builder()
        .name("passive")
        .description("Count passive mobs as enemies.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> flushOnEntityInteract = sgFlushOn.add(new BoolSetting.Builder()
        .name("entity-interact")
        .description("Release the queue when attacking or interacting with an entity, so the swing is not among the packets the server receives late.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> flushOnBlockInteract = sgFlushOn.add(new BoolSetting.Builder()
        .name("block-interact")
        .description("Release the queue when using an item on a block or editing a sign.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> flushOnAction = sgFlushOn.add(new BoolSetting.Builder()
        .name("action")
        .description("Release the queue on a player action: block breaking, item dropping, swapping hands, and the rest of the action packet's verbs.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> flushOnDamage = sgOnHit.add(new BoolSetting.Builder()
        .name("damage")
        .description("Release the queue when health drops. Being hit means the enemy already found the real position, so there is nothing left for the lag to hide.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> flushOnKnockback = sgOnHit.add(new BoolSetting.Builder()
        .name("knockback")
        .description("Release the queue when the server applies velocity to the player. The velocity is predicted from a position the server no longer has, so holding it back compounds the desync.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> flushOnExplosion = sgOnHit.add(new BoolSetting.Builder()
        .name("explosion")
        .description("Release the queue when an explosion knocks the player. Same reasoning as knockback.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Master switch for the render group: show where the server currently thinks you are, and the path it has not been told about yet.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Ghost> ghost = sgRender.add(new EnumSetting.Builder<Ghost>()
        .name("ghost")
        .description("What to draw at the position the server believes in. Box is a bounding box, Wireframe is a player-shaped skeleton.")
        .defaultValue(Ghost.Wireframe)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill colour of the ghost box.")
        .defaultValue(new SettingColor(36, 32, 147, 87))
        .visible(() -> render.get() && ghost.get() == Ghost.Box)
        .build()
    );

    private final Setting<SettingColor> ghostColor = sgRender.add(new ColorSetting.Builder()
        .name("ghost-color")
        .description("Edge colour of the ghost, and the colour of the whole wireframe.")
        .defaultValue(new SettingColor(36, 32, 147, 255))
        .visible(() -> render.get() && ghost.get() != Ghost.None)
        .build()
    );

    private final Setting<Boolean> trail = sgRender.add(new BoolSetting.Builder()
        .name("trail")
        .description("Draw the held positions as one line, oldest first: the path the server has not been told about yet.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> trailColor = sgRender.add(new ColorSetting.Builder()
        .name("trail-color")
        .description("Colour of the trail line.")
        .defaultValue(new SettingColor(0, 128, 255, 255))
        .visible(() -> render.get() && trail.get())
        .build()
    );

    /**
     * Hide the ghost in first person: you are inside the shape, so drawing it only blocks the view.
     * The trail has no such setting because it is a path on the ground and, in first person, reads
     * as the way you came.
     */
    private final Setting<Boolean> hideInFirstPerson = sgRender.add(new BoolSetting.Builder()
        .name("hide-in-first-person")
        .description("Do not draw the ghost while the camera is in first person.")
        .defaultValue(true)
        .visible(() -> render.get() && ghost.get() != Ghost.None)
        .build()
    );

    /** Packets waiting to be written to the connection, oldest first. Guarded by its own monitor. */
    private final Deque<Held> queue = new ArrayDeque<>();

    /** Set while the queue is being written out, so the re-sent packets do not get held again. */
    private volatile boolean flushing;

    /** Random source for the hold time. */
    private final Random random = new Random();

    /** Age the current hold must reach before it is released, in milliseconds. */
    private long nextDelay;

    /** When the last triggered flush happened, for {@code recoil-time}. 0 means "long ago". */
    private long lastTrigger;

    /** Health as of the last health packet, for the damage test. */
    private float lastHealth = Float.NaN;

    /** Whether an enemy was inside the search radii on the last tick. Refreshed once a tick. */
    private boolean enemyNearby;

    public FakeLag() {
        super(HongShiKaiKai.CATEGORY, "FakeLag", "Holds outgoing packets back while an enemy is near and releases them in one burst, so the server keeps seeing a position you have already left.");
    }

    @Override
    public void onActivate() {
        synchronized (queue) {
            queue.clear();
        }

        flushing = false;
        nextDelay = randomDelay();
        lastTrigger = 0L;
        lastHealth = mc.player == null ? Float.NaN : mc.player.getHealth();
        enemyNearby = false;
    }

    /**
     * Hands the queue back on the way out: a module-local queue that kept holding packets after the
     * module was switched off would be a trap, not a feature.
     */
    @Override
    public void onDeactivate() {
        flush();
        enemyNearby = false;
    }

    /**
     * Refreshes the "is an enemy near" flag. One scan per tick is plenty, because the answer only
     * changes as entities move.
     *
     * <p>The search is bounded on both sides: an enemy closer than {@code min-range} does not arm
     * Dynamic mode, which is why the two radii exist.
     */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!canRun()) {
            enemyNearby = false;
            return;
        }

        enemyNearby = !enemiesNear(positionOf(mc.player), minRange.get(), range.get()).isEmpty();
    }

    /**
     * The whole decision, run for every outgoing packet.
     *
     * <p>Priority {@link EventPriority#LOWEST} puts it at the end of the chain: every other module
     * has already had its say about the packet, so what is queued here is what would actually have
     * gone on the wire, and a packet somebody else already cancelled is left alone.
     *
     * <p>Every branch that does not end in a hold releases the queue: the default action is
     * "flush", and only an explicit decision to lag overrides it, which is what makes the queue
     * drain the instant lagging stops being useful.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    private void onSend(PacketEvent.Send event) {
        // Our own flush writes packets back through the same path; let them by.
        if (flushing) return;

        // Somebody else already dropped it, so there is nothing to hold: a cancelled packet neither
        // ends a hold nor starts one.
        if (event.isCancelled()) return;

        long now = System.currentTimeMillis();

        // No world, dead, in water or a screen is open: everything goes out.
        if (!canRun()) {
            flush();
            return;
        }

        // Still inside the recoil window that followed the last triggered flush.
        if (now - lastTrigger < recoilTime.get()) {
            flush();
            return;
        }

        // The hold has run its course. Pick a new one and release.
        if (!queue.isEmpty() && now - oldestHeldAt() >= nextDelay) {
            nextDelay = randomDelay();
            flush();
            return;
        }

        // A resource pack response is a handshake with the server, not gameplay traffic, so the
        // queue is released for it unconditionally.
        if (event.packet instanceof ResourcePackStatusC2SPacket || isFlushAction(event.packet)) {
            lastTrigger = now;
            flush();
            return;
        }

        // Eating and drinking take a fixed number of ticks on the server's side. Delaying the
        // packets that finish them delays the item, so they are not held.
        if (isConsuming()) {
            flush();
            return;
        }

        if (!shouldHold()) {
            flush();
            return;
        }

        // The decision is "hold", but some packets are never actually held. The test sits after the
        // decision: the release branches above run for every packet, while the exclusion only decides
        // whether a packet the module asked for really goes into the queue. Tested first, it would
        // also stop a chat message from releasing a queue the module had decided to release.
        if (!isHoldable(event.packet)) return;

        event.cancel();
        synchronized (queue) {
            queue.addLast(new Held(event.packet, now));
        }
    }

    /**
     * The clientbound half: the flush triggers that fire on packets coming in.
     *
     * <p>This runs before the packet is handled - Meteor posts the event at the head of the packet's
     * trip through the connection - so a teleport releases the held movement packets and <em>then</em>
     * the client moves, which is the order the two happen in.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    private void onReceive(PacketEvent.Receive event) {
        // A teleport overrides everything the client believes about its position. The held packets
        // describe positions that are about to be meaningless; send them, and let the server
        // correct us.
        if (event.packet instanceof PlayerPositionLookS2CPacket) {
            flush();
            return;
        }

        if (event.packet instanceof HealthUpdateS2CPacket health) {
            float value = health.getHealth();

            // Death releases everything with no recoil: there is nothing to lag afterwards.
            if (value <= 0.0f) {
                flush();
            }
            // A drop means damage. That is the module's own trigger, so it does start the recoil
            // window.
            else if (flushOnDamage.get() && !Float.isNaN(lastHealth) && value < lastHealth) {
                trigger(now());
            }

            lastHealth = value;
            return;
        }

        if (flushOnKnockback.get() && event.packet instanceof EntityVelocityUpdateS2CPacket velocity) {
            if (mc.player != null && velocity.getEntityId() == mc.player.getId()
                && !VelocityCompat.velocityOf(velocity).equals(Vec3d.ZERO)) {
                trigger(now());
            }
            return;
        }

        if (flushOnExplosion.get() && event.packet instanceof ExplosionS2CPacket explosion) {
            explosion.playerKnockback().ifPresent(knockback -> {
                if (!knockback.equals(Vec3d.ZERO)) trigger(now());
            });
        }
    }

    /**
     * Drops the queue when the world is left. The packets described a world that no longer exists,
     * and there is no connection left to send them to.
     */
    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        synchronized (queue) {
            queue.clear();
        }

        enemyNearby = false;
        lastHealth = Float.NaN;
    }

    // ---- Render ----

    /**
     * Draws the desync: the ghost at the position the server believes in, and - if asked for - the
     * trail of held positions behind it.
     *
     * <p>What is drawn is read off the same queue the send path writes, so an empty screen means
     * nothing is being held. The queue is touched under its own monitor for the length of a copy,
     * and the send path holds that monitor for the same short copy before writing to the connection
     * outside it, so a frame can never be blocked by a packet write.
     */
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || mc.player == null) return;

        PlayerMoveC2SPacket heldMove = oldestHeldMove();
        if (heldMove == null) return;

        if (trail.get()) drawTrail(event);

        if (ghost.get() == Ghost.None) return;
        if (hideInFirstPerson.get() && mc.options.getPerspective() == Perspective.FIRST_PERSON) return;

        Vec3d serverPos = movePosition(heldMove);

        if (ghost.get() == Ghost.Box) {
            drawGhostBox(event, serverPos);
        } else {
            // The yaw comes out of the held packet, so the ghost faces the way the server last saw
            // the player look.
            PlayerWireframe.draw(event, serverPos, heldMove.getYaw(mc.player.getYaw()), ghostColor.get());
        }
    }

    /**
     * The ghost as a box: the player's own bounding box moved to the held position. That is exactly
     * the box {@link #dynamicShouldHold()} reasons about, so the render shows the box the decision
     * was made on rather than a second guess at it.
     */
    private void drawGhostBox(Render3DEvent event, Vec3d serverPos) {
        Box box = mc.player.getBoundingBox().offset(serverPos.subtract(positionOf(mc.player)));

        event.renderer.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
            sideColor.get(), ghostColor.get(), ShapeMode.Both, 0);
    }

    /** The held positions, oldest first, joined up. An empty or single-point path draws nothing. */
    private void drawTrail(Render3DEvent event) {
        List<Vec3d> positions = heldPositions();

        for (int i = 1; i < positions.size(); i++) {
            Vec3d from = positions.get(i - 1);
            Vec3d to = positions.get(i);

            event.renderer.line(from.x, from.y, from.z, to.x, to.y, to.z, trailColor.get());
        }
    }

    /**
     * Whether to hold the packet in the current mode. Constant mode has already passed every guard
     * by the time it gets here, so it always holds.
     */
    private boolean shouldHold() {
        return mode.get() == Mode.Constant || dynamicShouldHold();
    }

    /**
     * The positional test. The question it answers is not "is an enemy near" but "is the position
     * the server currently believes in a better place to be seen than the real one".
     *
     * <p>The server's position is the one in the oldest held movement packet - the first position
     * the server has not been told about yet. Enemies are then found around <em>that</em> point, and
     * the player's bounding box is moved there too.
     *
     * <ul>
     *     <li>Nothing suitable is in range of that point: there is nothing to dodge, so release.</li>
     *     <li>The box at the server position overlaps an enemy: holding would park the player inside
     *         an entity as far as the server is concerned, which pushes and flags, so release.</li>
     *     <li>The server position is <em>closer</em> to an enemy than the real one: lagging would
     *         move the player towards the enemy's aim rather than away from it, so release.</li>
     *     <li>Otherwise the server position is farther away than the player really is, and holding
     *         keeps the enemy aiming at the wrong place. Hold.</li>
     * </ul>
     *
     * <p>With no position held yet there is nothing to compare against, so the packet is held: that
     * is what puts the first position into the queue for the next call to read, and on a normal
     * client a movement packet arrives often enough that the window is one tick wide.
     */
    private boolean dynamicShouldHold() {
        if (!enemyNearby) return false;

        Vec3d serverPos = oldestHeldPosition();
        if (serverPos == null) return true;

        Vec3d clientPos = positionOf(mc.player);
        Box boxAtServer = mc.player.getBoundingBox().offset(serverPos.subtract(clientPos));

        // The inner radius is deliberately not applied here: this list also has to contain an entity
        // the player is standing inside, or the overlap test below could never fire.
        List<LivingEntity> enemies = enemiesNear(serverPos, 0.0, range.get());
        if (enemies.isEmpty()) return false;

        for (LivingEntity enemy : enemies) {
            if (enemy.getBoundingBox().intersects(boxAtServer)) return false;
        }

        return nearestDistanceSquared(enemies, serverPos) >= nearestDistanceSquared(enemies, clientPos);
    }

    /**
     * Whether the packet is of a kind the module holds at all: the connection handshake and the
     * server-list ping are excluded because they never belong to a world, and chat because it is
     * signed, acknowledged by the server and read by the player.
     */
    private boolean isHoldable(Packet<?> packet) {
        return !(packet instanceof HandshakeC2SPacket
            || packet instanceof QueryRequestC2SPacket
            || packet instanceof QueryPingC2SPacket
            || packet instanceof ChatMessageC2SPacket
            || packet instanceof ChatCommandSignedC2SPacket);
    }

    /**
     * Whether the packet is one of the interaction categories the player asked to flush on.
     *
     * <p>The Mojang-mapped names {@code ServerboundAttackPacket}, {@code ServerboundPunchPacket}
     * and {@code ServerboundSpectatorActionPacket} have no one-to-one counterpart in yarn: there is
     * no separate attack packet on any supported version, so they collapse into
     * {@code PlayerInteractEntityC2SPacket} (whose {@code InteractType} is what distinguishes
     * attacking from interacting) plus {@code SpectatorTeleportC2SPacket}. Matching the packet type
     * therefore covers both verbs.
     */
    private boolean isFlushAction(Packet<?> packet) {
        if (flushOnEntityInteract.get()
            && (packet instanceof PlayerInteractEntityC2SPacket || packet instanceof SpectatorTeleportC2SPacket)) {
            return true;
        }

        if (flushOnBlockInteract.get()
            && (packet instanceof PlayerInteractBlockC2SPacket || packet instanceof UpdateSignC2SPacket)) {
            return true;
        }

        return flushOnAction.get() && packet instanceof PlayerActionC2SPacket;
    }

    /** Whether the player is dead or dying, spectating, in water, or has a screen open. */
    private boolean canRun() {
        if (mc.player == null || mc.world == null) return false;
        if (mc.player.isDead() || mc.player.getHealth() <= 0.0f || mc.player.isSpectator()) return false;
        if (mc.player.isTouchingWater()) return false;

        return mc.currentScreen == null;
    }

    /**
     * Whether the player is using a consumable. The item is read from the component that makes it
     * consumable in the first place, so food, potions and milk buckets all match without a list.
     */
    private boolean isConsuming() {
        if (mc.player == null || !mc.player.isUsingItem()) return false;

        return mc.player.getActiveItem().contains(DataComponentTypes.CONSUMABLE);
    }

    /** Starts the recoil window and releases the queue. */
    private void trigger(long now) {
        lastTrigger = now;
        flush();
    }

    /**
     * Writes the queue to the connection, oldest first, and empties it.
     *
     * <p>The flag is what stops the re-send from being intercepted by {@link #onSend} all over
     * again. It is not a lock: the packets are sent outside the queue's monitor, so a packet
     * produced while the flush is running is not queued behind it - it passes straight through,
     * which is the right answer, because the hold ended when the flush started.
     */
    private void flush() {
        List<Packet<?>> outgoing;
        synchronized (queue) {
            if (queue.isEmpty()) return;

            outgoing = new ArrayList<>(queue.size());
            for (Held held : queue) outgoing.add(held.packet());
            queue.clear();
        }

        if (mc.getNetworkHandler() == null) return;

        flushing = true;
        try {
            for (Packet<?> packet : outgoing) mc.getNetworkHandler().sendPacket(packet);
        } finally {
            flushing = false;
        }
    }

    /** The position in the oldest held movement packet, or null if none carries one yet. */
    private Vec3d oldestHeldPosition() {
        PlayerMoveC2SPacket move = oldestHeldMove();
        return move == null ? null : movePosition(move);
    }

    /**
     * The oldest held movement packet that carries a position, or null. This is the position the
     * server is currently working from, and the one the render stands the ghost on.
     */
    private PlayerMoveC2SPacket oldestHeldMove() {
        synchronized (queue) {
            for (Held held : queue) {
                // changesPosition() tells whether the packet carries a position at all.
                if (held.packet() instanceof PlayerMoveC2SPacket move && move.changesPosition()) {
                    return move;
                }
            }
        }

        return null;
    }

    /**
     * Every held position, oldest first: the path the server has not been told about yet, minus the
     * look-only packets, which carry no position to draw.
     */
    private List<Vec3d> heldPositions() {
        List<Vec3d> positions = new ArrayList<>();

        synchronized (queue) {
            for (Held held : queue) {
                if (held.packet() instanceof PlayerMoveC2SPacket move && move.changesPosition()) {
                    positions.add(movePosition(move));
                }
            }
        }

        return positions;
    }

    /**
     * The position a movement packet carries. The coordinate getters take a fallback, which is what
     * the packet itself uses for the look-only forms.
     */
    private static Vec3d movePosition(PlayerMoveC2SPacket move) {
        return new Vec3d(move.getX(0.0), move.getY(0.0), move.getZ(0.0));
    }

    /** Timestamp of the oldest held packet, or 0 when nothing is held. */
    private long oldestHeldAt() {
        synchronized (queue) {
            Held oldest = queue.peekFirst();
            return oldest == null ? 0L : oldest.at();
        }
    }

    /**
     * The enemies whose bounding box is within {@code max} of {@code centre} and no closer than
     * {@code min}. Distances are measured to the box, not to the entity's feet - the same test this
     * addon's KillAura+ uses.
     */
    private List<LivingEntity> enemiesNear(Vec3d centre, double min, double max) {
        double minSquared = min * min;
        double maxSquared = max * max;

        List<LivingEntity> found = new ArrayList<>();
        if (mc.world == null) return found;

        for (Entity entity : mc.world.getEntities()) {
            if (!isEnemy(entity)) continue;

            double distance = entity.getBoundingBox().squaredMagnitude(centre);
            if (distance < minSquared || distance > maxSquared) continue;

            found.add((LivingEntity) entity);
        }

        return found;
    }

    /**
     * What counts as an enemy: a living, non-spectating entity that is not the player, of a kind the
     * player asked for, and not a friend. Deliberately the same predicate KillAura+ targets with, so
     * the two modules agree on who is dangerous.
     */
    private boolean isEnemy(Entity entity) {
        if (!(entity instanceof LivingEntity living)) return false;
        if (living == mc.player || !living.isAlive() || living.isDead() || living.isSpectator()) return false;

        if (living instanceof PlayerEntity player) {
            return players.get() && Friends.get().shouldAttack(player);
        }

        if (living instanceof Monster) return hostile.get();
        if (living instanceof PassiveEntity) return passive.get();

        // Anything else living (armour stands and the like) is left alone rather than guessed at.
        return false;
    }

    private static double nearestDistanceSquared(List<LivingEntity> entities, Vec3d point) {
        double nearest = Double.MAX_VALUE;
        for (Entity entity : entities) {
            nearest = Math.min(nearest, entity.squaredDistanceTo(point));
        }

        return nearest;
    }

    /**
     * The entity's position as a vector. Built from the coordinates rather than read from a
     * position accessor: 1.21.10 renamed that accessor, and this route reads the same fields on
     * every supported version.
     */
    private static Vec3d positionOf(Entity entity) {
        return new Vec3d(entity.getX(), entity.getY(), entity.getZ());
    }

    /** A fresh hold time, uniform in {@code min-delay..max-delay}. */
    private long randomDelay() {
        int low = Math.min(minDelay.get(), maxDelay.get());
        int high = Math.max(minDelay.get(), maxDelay.get());

        return high > low ? low + random.nextInt(high - low + 1) : low;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    @Override
    public String getInfoString() {
        synchronized (queue) {
            return queue.size() + " held";
        }
    }

    /** How the module decides whether to lag at all. */
    public enum Mode {
        /** Hold whenever the guards allow it, enemy or not. */
        Constant,
        /** Hold only while an enemy is near and the held position is the better one to be seen at. */
        Dynamic
    }

    /**
     * What the render draws at the position the server believes in. There is no mode that draws a
     * second copy of the player through the entity renderer: Meteor's immediate-mode renderer has
     * no counterpart for it, and it is not faked with something that only looks similar.
     */
    public enum Ghost {
        /** Nothing. Only the trail is drawn. */
        None,
        /** The player's bounding box, moved to the held position. */
        Box,
        /** A player-shaped skeleton, turned to the yaw the held packet carries. */
        Wireframe
    }

    /** One held packet and the time it was taken off the wire, for the hold-time test. */
    private record Held(Packet<?> packet, long at) {
    }
}
