package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.crystal.BlockUtil;
import com.hongshikaikai.addon.utils.crystal.CombatUtil;
import com.hongshikaikai.addon.utils.crystal.EntityUtil;
import com.hongshikaikai.addon.utils.crystal.InventoryUtil;
import com.hongshikaikai.addon.utils.crystal.RotationHelper;
import com.hongshikaikai.addon.utils.crystal.SwingSide;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.ScaffoldingBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Throws an ender pearl at a corrected, steep angle to carry you through the block in front of or
 * below you.
 *
 * <h2>What it does</h2>
 * It throws an ender pearl at a steep, deliberately corrected angle so the pearl carries you through
 * the block you are standing against or above, and then turns itself off. Anything in the way that
 * would throw the pearl back - scaffolding inside you, a crystal below your feet, an item frame or a
 * painting in your own cell - is attacked first.
 *
 * <p>The angle is the whole trick. {@code correct} aims from the neighbouring cell centre rather
 * than from the camera, and {@code dynamic-pitch} derives that angle from where the nearest
 * <em>hard</em> block actually is, so the pearl is aimed down the cell it has to fall through; with
 * it off a fixed {@code pitch} is used instead. {@code yaw-step} turns to that angle over several
 * ticks and only throws once the aim the server holds is inside {@code fov}.
 *
 * <h2>How it works here</h2>
 * <p>The aim is stepped and sent synchronously by {@link RotationHelper}, in the same call that
 * decides whether the server has caught up. Scaffolding is broken with the module's own
 * start/stop destroy packets rather than through a packet-mine module. The world is read through
 * {@code mc.world}, and {@link CombatUtil#isHard(BlockPos)} is the hard-block test, while
 * {@code EntityUtil.inInventory} and the inventory/hotbar search helpers find the pearl. The throw
 * settings ({@code InventorySwap}, {@code Swing}, {@code Rotation}) live in this module's own
 * {@code Pearl} group, and the aim is sent as a look packet immediately before the interaction,
 * exactly as {@link com.hongshikaikai.addon.utils.crystal.RotationHelper} describes.
 */
public class Phase extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBreak = settings.createGroup("Break");
    private final SettingGroup sgRotation = settings.createGroup("Rotation");
    private final SettingGroup sgPearl = settings.createGroup("Pearl");

    // ---- General ----

    private final Setting<Boolean> onEnable = sgGeneral.add(new BoolSetting.Builder()
        .name("on-enable")
        .description("Throw as soon as the module is turned on, instead of waiting for the next tick handler.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyGround = sgGeneral.add(new BoolSetting.Builder()
        .name("only-ground")
        .description("Only run while you are on the ground, or standing on a ladder.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Timing> timing = sgGeneral.add(new EnumSetting.Builder<Timing>()
        .name("timing")
        .description("Which part of the client tick the module acts on.")
        .defaultValue(Timing.All)
        .build()
    );

    private final Setting<Boolean> correct = sgGeneral.add(new BoolSetting.Builder()
        .name("correct")
        .description("Aim the pearl with the module's own corrected angle instead of your camera angle.")
        .defaultValue(true)
        .build()
    );

    // ---- Break ----

    private final Setting<Boolean> breakBlock = sgBreak.add(new BoolSetting.Builder()
        .name("break")
        .description("Deal with whatever would stop the pearl - scaffolding, crystals, item frames and paintings - before throwing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> scaffolding = sgBreak.add(new BoolSetting.Builder()
        .name("scaffolding")
        .description("Break scaffolding inside you or in the cell above your head.")
        .defaultValue(true)
        .visible(breakBlock::get)
        .build()
    );

    private final Setting<Boolean> crystal = sgBreak.add(new BoolSetting.Builder()
        .name("crystal")
        .description("Attack end crystals that are inside you and below your own height.")
        .defaultValue(true)
        .visible(breakBlock::get)
        .build()
    );

    private final Setting<Boolean> itemFrame = sgBreak.add(new BoolSetting.Builder()
        .name("item-frame")
        .description("Attack item frames inside your own cell.")
        .defaultValue(true)
        .visible(breakBlock::get)
        .build()
    );

    private final Setting<Boolean> paint = sgBreak.add(new BoolSetting.Builder()
        .name("paint")
        .description("Attack paintings inside your own cell.")
        .defaultValue(true)
        .visible(breakBlock::get)
        .build()
    );

    private final Setting<Boolean> rotate = sgBreak.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Aim at each obstruction before attacking it.")
        .defaultValue(true)
        .visible(breakBlock::get)
        .build()
    );

    // ---- Rotation ----

    private final Setting<Boolean> dynamicPitch = sgRotation.add(new BoolSetting.Builder()
        .name("dynamic-pitch")
        .description("Derive the aim from the neighbouring hard block instead of using a fixed pitch.")
        .defaultValue(true)
        .visible(correct::get)
        .build()
    );

    private final Setting<Double> factor = sgRotation.add(new DoubleSetting.Builder()
        .name("factor")
        .description("How far towards the neighbouring cell centre the aim point is moved, as a fraction of a block.")
        .defaultValue(0.5)
        .min(0.0)
        .sliderRange(0.0, 1.0)
        .visible(() -> correct.get() && dynamicPitch.get())
        .build()
    );

    private final Setting<Boolean> onlyHard = sgRotation.add(new BoolSetting.Builder()
        .name("only-hard")
        .description("Only aim at a neighbour that is a hard block, not at any solid one.")
        .defaultValue(false)
        .visible(() -> correct.get() && dynamicPitch.get())
        .build()
    );

    private final Setting<Double> pitch = sgRotation.add(new DoubleSetting.Builder()
        .name("pitch")
        .description("Pitch to throw at when the dynamic pitch is off.")
        .defaultValue(88.0)
        .min(0.0)
        .sliderRange(0.0, 90.0)
        .visible(() -> !correct.get() || !dynamicPitch.get())
        .build()
    );

    private final Setting<Boolean> yawStep = sgRotation.add(new BoolSetting.Builder()
        .name("yaw-step")
        .description("Turn to the aim over several ticks instead of snapping to it, and throw only once the server's aim has arrived.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> fallFlying = sgRotation.add(new BoolSetting.Builder()
        .name("fall-flying")
        .description("Keep yaw stepping while you are gliding.")
        .defaultValue(true)
        .visible(yawStep::get)
        .build()
    );

    private final Setting<Double> steps = sgRotation.add(new DoubleSetting.Builder()
        .name("steps")
        .description("Fraction of a half turn of yaw (and a quarter turn of pitch) allowed per tick.")
        .defaultValue(0.05)
        .min(0.0)
        .sliderRange(0.0, 1.0)
        .visible(yawStep::get)
        .build()
    );

    private final Setting<Double> fov = sgRotation.add(new DoubleSetting.Builder()
        .name("fov")
        .description("How close the aim the server holds has to be to the target before the pearl is thrown.")
        .defaultValue(20.0)
        .min(0.0)
        .sliderRange(0.0, 360.0)
        .visible(yawStep::get)
        .build()
    );

    private final Setting<Boolean> sync = sgRotation.add(new BoolSetting.Builder()
        .name("sync")
        .description("With yaw stepping on, throw with the module's corrected angle rather than the stepped one.")
        .defaultValue(true)
        .visible(yawStep::get)
        .build()
    );

    // ---- Pearl ----

    private final Setting<Boolean> inventory = sgPearl.add(new BoolSetting.Builder()
        .name("inventory-swap")
        .description("Take the pearl out of the inventory rather than the hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SwingSide> swing = sgPearl.add(new EnumSetting.Builder<SwingSide>()
        .name("swing")
        .description("How the swing that accompanies the throw is reported.")
        .defaultValue(SwingSide.All)
        .build()
    );

    private final Setting<Boolean> rotation = sgPearl.add(new BoolSetting.Builder()
        .name("rotation")
        .description("Send a look packet with the throw's aim immediately before the pearl is used.")
        .defaultValue(true)
        .build()
    );

    /** The corrected aim point, i.e. the cell centre the pearl should be lobbed at. */
    private Vec3d targetPos;

    public Phase() {
        super(
            HongShiKaiKai.CATEGORY,
            "Phase",
            "Throws an ender pearl at a corrected, steep angle to carry you through the block in front of or below you."
        );
    }

    @Override
    public void onActivate() {
        targetPos = null;
        RotationHelper.reset();

        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }

        if (findPearl() == -1) {
            warning("No ender pearl%s - disabling.", inventory.get() ? " in your inventory" : " in your hotbar");
            toggle();
            return;
        }

        if (!onEnable.get()) return;

        if (onlyGround.get() && !mc.player.isOnGround() && !(BlockUtil.getBlock(mc.player.getBlockPos()) instanceof LadderBlock)) return;

        if (breakBlock.get()) {
            if (breakScaffolding()) return;
            attackObstructions();
        }

        updatePos();

        // The inventory check only gates the throw itself; the
        // obstruction attacks above run either way.
        if (!shouldYawStep()) {
            if (inventory.get() && !EntityUtil.inInventory()) return;

            throwPearl(getYaw(), getPitch());
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        targetPos = null;
    }

    // ---- Tick ----

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (timing.get() == Timing.Post) return;
        runTick();
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (timing.get() != Timing.Post) return;
        runTick();
    }

    private void runTick() {
        if (mc.player == null || mc.world == null) return;
        if (inventory.get() && !EntityUtil.inInventory()) return;
        if (onlyGround.get() && !mc.player.isOnGround() && !(BlockUtil.getBlock(mc.player.getBlockPos()) instanceof LadderBlock)) return;

        if (breakBlock.get()) {
            if (breakScaffolding()) return;
            attackObstructions();
        }

        if (!shouldYawStep()) {
            throwPearl(getYaw(), getPitch());
            toggle();
            return;
        }

        updatePos();
        if (!faceVector(targetPos)) return;

        if (sync.get()) {
            throwPearl(getYaw(), getPitch());
        } else {
            throwPearlStepped();
        }

        toggle();
    }

    // ---- Obstructions ----

    /** Whatever is in the way, attacked before the pearl goes out. */
    private void attackObstructions() {
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof EndCrystalEntity && crystal.get()) {
                // Only crystals the pearl would actually be thrown through: at or below the feet,
                // and overlapping the player's own hitbox.
                if (!(entity.getY() >= mc.player.getY()) && entity.getBoundingBox().intersects(mc.player.getBoundingBox())) {
                    CombatUtil.attack(entity, rotate.get());
                }
            } else if (itemFrame.get() && entity instanceof ItemFrameEntity frame) {
                if (entity.getBoundingBox().intersects(new Box(mc.player.getBlockPos()))) {
                    if (!frame.getHeldItemStack().isEmpty()) {
                        CombatUtil.attack(entity, rotate.get());
                    }

                    CombatUtil.attack(entity, rotate.get());
                }
            } else if (paint.get() && entity instanceof PaintingEntity && entity.getBoundingBox().intersects(new Box(mc.player.getBlockPos()))) {
                CombatUtil.attack(entity, rotate.get());
            }
        }
    }

    /** Breaks scaffolding inside the player or just above their head; true when it did. */
    private boolean breakScaffolding() {
        if (!scaffolding.get()) return false;

        BlockPos self = EntityUtil.getPlayerPos(true);
        BlockPos selfFace = self.up();

        if (BlockUtil.getBlock(self) instanceof ScaffoldingBlock) {
            mineBlock(self);
            return true;
        }

        if (BlockUtil.getBlock(selfFace) instanceof ScaffoldingBlock) {
            mineBlock(selfFace);
            return true;
        }

        return false;
    }

    /**
     * Sends the start/stop pair that breaks {@code pos} at once.
     *
     * <p>Scaffolding is broken for real by both packets in the same tick, which is what lets the
     * player be inside it when the pearl is thrown.
     */
    private void mineBlock(BlockPos pos) {
        EntityUtil.sendSequencedPacket(id -> new PlayerActionC2SPacket(Action.START_DESTROY_BLOCK, pos, Direction.UP, id));
        EntityUtil.sendSequencedPacket(id -> new PlayerActionC2SPacket(Action.STOP_DESTROY_BLOCK, pos, Direction.UP, id));
    }

    // ---- Aim ----

    /**
     * Whether the aim should be turned to over several ticks.
     *
     * <p>Stepping is skipped while gliding, unless {@code fallFlying} keeps it on; Meteor's own
     * elytra fly shows up as {@code isGliding()} rather than as a module check.
     */
    private boolean shouldYawStep() {
        return (fallFlying.get() || !mc.player.isGliding()) && yawStep.get();
    }

    /** Sends this tick's step towards {@code vec} and reports whether the server's aim has arrived. */
    private boolean faceVector(Vec3d vec) {
        return RotationHelper.faceVector(vec, true, true, fov.get().floatValue(), steps.get().floatValue());
    }

    /**
     * The corrected aim point: the centre of the player's own cell, moved towards whichever
     * neighbouring cell has something worth aiming at.
     *
     * <p>With {@code only-hard} off, any non-air neighbour counts; with it on,
     * only a block the hole logic calls hard does. Either way {@code factor} decides how far the aim
     * point slides towards that neighbour, so the pearl is lobbed at the edge the block is on.
     */
    private Vec3d targetPos() {
        BlockPos bp = mc.player.getBlockPos();
        Vec3d playerPos = EntityUtil.pos(mc.player);
        Vec3d centerPos = mc.player.getBlockPos().toBottomCenterPos();
        double factorValue = factor.get();
        Vec3d closestPos = centerPos;
        double minDistance = Double.MAX_VALUE;
        int[] offsets = new int[]{1, -1};

        for (int x : offsets) {
            for (int z : offsets) {
                if (!onlyHard.get()
                        && (!mc.world.isAir(bp.add(x, 0, 0)) || !mc.world.isAir(bp.add(0, 0, z)) || !mc.world.isAir(bp.add(x, 0, z)))
                    || CombatUtil.isHard(bp.add(x, 0, 0))
                    || CombatUtil.isHard(bp.add(0, 0, z))
                    || CombatUtil.isHard(bp.add(x, 0, z))) {
                    Vec3d tempPos = centerPos.add(x * factorValue, 0.0, z * factorValue);
                    double distance = tempPos.distanceTo(playerPos);
                    if (distance < minDistance) {
                        minDistance = distance;
                        closestPos = tempPos;
                    }
                }
            }
        }

        return closestPos;
    }

    /** The aim point a throw should use, recomputed for this tick. */
    private void updatePos() {
        targetPos = new Vec3d(
            mc.player.getX()
                + MathHelper.clamp(
                    roundToClosest(mc.player.getX(), Math.floor(mc.player.getX()) + 0.241, Math.floor(mc.player.getX()) + 0.759)
                        - mc.player.getX(),
                    -0.2,
                    0.2
                ),
            mc.player.getY() - 0.5,
            mc.player.getZ()
                + MathHelper.clamp(
                    roundToClosest(mc.player.getZ(), Math.floor(mc.player.getZ()) + 0.241, Math.floor(mc.player.getZ()) + 0.759)
                        - mc.player.getZ(),
                    -0.2,
                    0.2
                )
        );
    }

    private float getYaw() {
        if (correct.get()) {
            if (dynamicPitch.get()) {
                Vec3d target = targetPos();
                if (target != null) {
                    return RotationHelper.getRotation(target)[0];
                }
            }

            return MathHelper.wrapDegrees(RotationHelper.getRotation(mc.player.getBlockPos().toCenterPos())[0] - 180.0f);
        }

        return mc.player.getYaw();
    }

    private float getPitch() {
        if (correct.get() && dynamicPitch.get()) {
            Vec3d target = targetPos();
            if (target != null) {
                return RotationHelper.getRotation(target)[1];
            }
        }

        return pitch.get().floatValue();
    }

    private double roundToClosest(double num, double low, double high) {
        double d1 = num - low;
        double d2 = high - num;
        return d2 > d1 ? low : high;
    }

    // ---- Throwing ----

    /** The pearl slot the configured swap mode should look in, or -1. */
    private int findPearl() {
        return inventory.get()
            ? InventoryUtil.findItemInventorySlotFromZero(Items.ENDER_PEARL)
            : InventoryUtil.findItem(Items.ENDER_PEARL);
    }

    /** Aim where the module wants, then throw with that same aim in the packet. */
    private void throwPearl(float yaw, float pitch) {
        if (rotation.get()) {
            RotationHelper.snapAt(yaw, pitch);
        }

        usePearl(yaw, pitch);
    }

    /**
     * Throw with the aim the yaw stepping last put on the server.
     *
     * <p>With {@code sync} off the packet carries the stepped aim - the last yaw and pitch
     * {@link RotationHelper} put on the server - rather than a fresh snap.
     */
    private void throwPearlStepped() {
        usePearl(RotationHelper.getLastYaw(), RotationHelper.getLastPitch());
    }

    private void usePearl(float yaw, float pitch) {
        if (mc.player.getMainHandStack().getItem() == Items.ENDER_PEARL) {
            sendUse(yaw, pitch);
            return;
        }

        int pearl;
        if (inventory.get() && (pearl = InventoryUtil.findItemInventorySlotFromZero(Items.ENDER_PEARL)) != -1) {
            InventoryUtil.inventorySwap(pearl, EntityUtil.selectedSlot());
            sendUse(yaw, pitch);
            InventoryUtil.inventorySwap(pearl, EntityUtil.selectedSlot());
            EntityUtil.syncInventory();
        } else if ((pearl = InventoryUtil.findItem(Items.ENDER_PEARL)) != -1) {
            int old = EntityUtil.selectedSlot();
            InventoryUtil.switchToSlot(pearl);
            sendUse(yaw, pitch);
            InventoryUtil.switchToSlot(old);
        }
    }

    private void sendUse(float yaw, float pitch) {
        EntityUtil.sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, id, yaw, pitch));
        EntityUtil.swingHand(Hand.MAIN_HAND, swing.get());
    }

    // ---- Enums ----

    public enum Timing {
        All,
        Pre,
        Post
    }
}
