package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.crystal.BlockUtil;
import com.hongshikaikai.addon.utils.crystal.CombatUtil;
import com.hongshikaikai.addon.utils.crystal.CrystalConfig;
import com.hongshikaikai.addon.utils.crystal.EntityUtil;
import com.hongshikaikai.addon.utils.crystal.InventoryUtil;
import com.hongshikaikai.addon.utils.crystal.MovementUtil;
import com.hongshikaikai.addon.utils.crystal.PlayerEntityPredict;
import com.hongshikaikai.addon.utils.crystal.RotationHelper;
import com.hongshikaikai.addon.utils.crystal.Timer;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.FacingBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

/**
 * Builds a piston crystal trap on the nearest enemy and fires it.
 *
 * <h2>What it does</h2>
 * For the nearest enemy it builds a piston crystal trap: a piston three blocks out facing the target,
 * a redstone block to fire it, a crystal in the cell the piston head will occupy, and optionally fire
 * on the ground the trap covers. The whole thing is a fixed cycle - the "stage" counter walks through
 * the piston, redstone, crystal and fire steps at their configured stages - so the parts land in the
 * order the server will accept them rather than all in one tick.
 *
 * <h2>How it works here</h2>
 * <p>The piston is aimed with a look packet before it is placed
 * ({@link RotationHelper}), which makes the server compute the facing the trap needs. The "is that
 * cell already being mined" tests read Meteor's mining list. The 1.12 compatibility branch is driven
 * by {@link com.hongshikaikai.addon.utils.crystal.CrystalConfig#lowVersion}, which no module
 * exposes, so it stays off.
 */
public class PistonCrystal extends Module {
    public static PistonCrystal INSTANCE;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStages = settings.createGroup("Stages");

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Aim at the blocks being placed and at the piston after it goes down.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pistonPacket = sgGeneral.add(new BoolSetting.Builder()
        .name("piston-packet")
        .description("Place the piston as a packet rather than letting the client predict it.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> noEating = sgGeneral.add(new BoolSetting.Builder()
        .name("no-eating")
        .description("Pause while you are using an item.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> eatingBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("eating-break")
        .description("Still break crystals while you are using an item.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("How far the trap may be built from the eye, in blocks.")
        .defaultValue(5.0)
        .min(1.0)
        .sliderRange(1.0, 8.0)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far away a player may be and still be trapped, in blocks.")
        .defaultValue(4.0)
        .min(1.0)
        .sliderRange(1.0, 8.0)
        .build()
    );

    private final Setting<Boolean> fire = sgGeneral.add(new BoolSetting.Builder()
        .name("fire")
        .description("Set the ground the trap covers alight.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> switchPos = sgGeneral.add(new BoolSetting.Builder()
        .name("switch")
        .description("Allow the trap to move to a better position while it is being built.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyGround = sgGeneral.add(new BoolSetting.Builder()
        .name("self-ground")
        .description("Only build while you are on the ground.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyStatic = sgGeneral.add(new BoolSetting.Builder()
        .name("moving-pause")
        .description("Pause while you are moving.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> updateDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Milliseconds between two stages of the trap, in milliseconds.")
        .defaultValue(100)
        .min(0)
        .sliderRange(0, 500)
        .build()
    );

    private final Setting<Integer> posUpdateDelay = sgGeneral.add(new IntSetting.Builder()
        .name("pos-update-delay")
        .description("Milliseconds between two searches for a trap position, in milliseconds.")
        .defaultValue(500)
        .min(0)
        .sliderRange(0, 1000)
        .build()
    );

    private final Setting<Integer> stageSetting = sgStages.add(new IntSetting.Builder()
        .name("stage")
        .description("How many stages the cycle has. The four stage settings below are clamped into it.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> pistonStage = sgStages.add(new IntSetting.Builder()
        .name("piston-stage")
        .description("Stage at which the piston is placed.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> pistonMaxStage = sgStages.add(new IntSetting.Builder()
        .name("piston-max-stage")
        .description("Last stage at which the piston may be placed.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> powerStage = sgStages.add(new IntSetting.Builder()
        .name("power-stage")
        .description("Stage at which the redstone is placed.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> powerMaxStage = sgStages.add(new IntSetting.Builder()
        .name("power-max-stage")
        .description("Last stage at which the redstone may be placed.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> crystalStage = sgStages.add(new IntSetting.Builder()
        .name("crystal-stage")
        .description("Stage at which the crystal is placed.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> crystalMaxStage = sgStages.add(new IntSetting.Builder()
        .name("crystal-max-stage")
        .description("Last stage at which the crystal may be placed.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> fireStage = sgStages.add(new IntSetting.Builder()
        .name("fire-stage")
        .description("Stage at which the fire is lit.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> fireMaxStage = sgStages.add(new IntSetting.Builder()
        .name("fire-max-stage")
        .description("Last stage at which the fire may be lit.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Boolean> inventory = sgGeneral.add(new BoolSetting.Builder()
        .name("inventory-swap")
        .description("Take the trap blocks from your inventory rather than the hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print the chosen trap position each time it is rebuilt.")
        .defaultValue(false)
        .build()
    );

    private final Timer timer = new Timer();
    private final Timer crystalTimer = new Timer();

    public BlockPos bestPos = null;
    public BlockPos bestOPos = null;
    public Direction bestFacing = null;
    public double distance = 100.0;
    public boolean getPos = false;
    public int stage = 1;

    private PlayerEntity target = null;
    private boolean isPiston = false;

    public PistonCrystal() {
        super(
            HongShiKaiKai.CATEGORY,
            "pistonCrystal",
            "Builds a piston crystal trap on the nearest enemy and fires it."
        );
        INSTANCE = this;
    }

    @Override
    public String getInfoString() {
        return target != null ? target.getName().getString() : null;
    }

    @Override
    public void onActivate() {
        INSTANCE = this;
        bestPos = null;
        bestOPos = null;
        bestFacing = null;
        getPos = false;
        distance = 100.0;
        target = null;
        stage = 1;
        RotationHelper.reset();
    }

    @Override
    public void onDeactivate() {
        CombatUtil.clearOverride();
    }

    // ---- Stages ----

    /** Keeps the four stage pairs inside the cycle length. */
    private void clampStages() {
        int max = stageSetting.get();

        clamp(pistonStage, max);
        clamp(fireStage, max);
        clamp(powerStage, max);
        clamp(crystalStage, max);
        clamp(pistonMaxStage, max);
        clamp(fireMaxStage, max);
        clamp(powerMaxStage, max);
        clamp(crystalMaxStage, max);

        if (crystalMaxStage.get() < crystalStage.get()) crystalStage.set(crystalMaxStage.get());
        if (powerMaxStage.get() < powerStage.get()) powerStage.set(powerMaxStage.get());
        if (pistonMaxStage.get() < pistonStage.get()) pistonStage.set(pistonMaxStage.get());
        if (fireMaxStage.get() < fireStage.get()) fireStage.set(fireMaxStage.get());
    }

    private static void clamp(Setting<Integer> setting, int max) {
        if (setting.get() > max) {
            setting.set(max);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        BlockUtil.placedPos.clear();
        clampStages();

        target = CombatUtil.getClosestEnemy(range.get());
        if (target == null) return;
        if (noEating.get() && mc.player.isUsingItem()) return;
        if (check(onlyStatic.get(), !mc.player.isOnGround(), onlyGround.get())) return;

        BlockPos pos = EntityUtil.getEntityPos(target, true);

        if (!mc.player.isUsingItem() || eatingBreak.get()) {
            if (checkCrystal(pos.up(0))) CombatUtil.attackCrystal(pos.up(0), rotate.get(), true);
            if (checkCrystal(pos.up(1))) CombatUtil.attackCrystal(pos.up(1), rotate.get(), true);
            if (checkCrystal(pos.up(2))) CombatUtil.attackCrystal(pos.up(2), rotate.get(), true);
        }

        if (bestPos != null && mc.world.getBlockState(bestPos).getBlock() instanceof PistonBlock) {
            isPiston = true;
        } else if (isPiston) {
            isPiston = false;
            crystalTimer.reset();
            bestPos = null;
        }

        if (crystalTimer.passedMs(posUpdateDelay.get())) {
            stage = 0;
            distance = 100.0;
            getPos = false;
            getBestPos(pos.up(2));
            getBestPos(pos.up());
        }

        if (timer.passedMs(updateDelay.get()) && getPos && bestPos != null) {
            timer.reset();

            if (debug.get()) {
                info("[Debug] PistonPos:" + bestPos + " Facing:" + bestFacing + " CrystalPos:" + bestOPos.offset(bestFacing));
            }

            doPistonAura(bestPos, bestFacing, bestOPos);
        }
    }

    /**
     * Whether to stand down this tick.
     *
     * <p>Returns true - do nothing - when the player is moving but told to keep still, when the
     * player is airborne but told to stay grounded, or when any of the three blocks the trap needs is
     * missing from the inventory.
     */
    public boolean check(boolean onlyStatic, boolean onGround, boolean onlyGround) {
        if (MovementUtil.isMoving() && onlyStatic) {
            return true;
        } else if (onGround && onlyGround) {
            return true;
        } else if (findBlock(Blocks.REDSTONE_BLOCK) == -1) {
            return true;
        } else {
            return findClass(PistonBlock.class) == -1 || findItem(Items.END_CRYSTAL) == -1;
        }
    }

    private boolean checkCrystal(BlockPos pos) {
        for (Entity entity : BlockUtil.getEntities(new Box(pos))) {
            if (entity instanceof EndCrystalEntity && damageOf(entity) > 7.0f) {
                return true;
            }
        }

        return false;
    }

    private boolean checkCrystal2(BlockPos pos) {
        for (Entity entity : BlockUtil.getEntities(new Box(pos))) {
            if (entity instanceof EndCrystalEntity && EntityUtil.getEntityPos(entity).equals(pos)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Damage a crystal would deal to the target, costed by {@code CrystalAura+} when it is loaded so
     * the two modules agree, and by the shared explosion maths otherwise.
     */
    private float damageOf(Entity crystal) {
        if (CrystalAuraPlus.INSTANCE != null) {
            return CrystalAuraPlus.INSTANCE.calculateDamage(EntityUtil.pos(crystal), target, (PlayerEntityPredict) null);
        }

        return com.hongshikaikai.addon.utils.crystal.ExplosionDamageUtils.calculateDamage(EntityUtil.pos(crystal), target, null, 6.0f);
    }

    // ---- Position search ----

    private void getBestPos(BlockPos pos) {
        for (Direction i : Direction.values()) {
            if (i != Direction.DOWN && i != Direction.UP) {
                getPos(pos, i);
            }
        }
    }

    /**
     * Tests the ring of cells a piston could push from, around one of the target's body cells.
     *
     * <p>For a side that has a crystal cell next to the target, it tries the piston at three, two and
     * one blocks out, on each of the three rows around the axis, plus the two diagonal offsets.
     * {@link #getPos(BlockPos, Direction, BlockPos)} keeps whichever candidate is closest to the eye.
     */
    private void getPos(BlockPos pos, Direction i) {
        if (BlockUtil.canPlaceCrystal(pos.offset(i)) || checkCrystal2(pos.offset(i))) {
            getPos(pos.offset(i, 3), i, pos);
            getPos(pos.offset(i, 3).up(), i, pos);
            int offsetX = pos.offset(i).getX() - pos.getX();
            int offsetZ = pos.offset(i).getZ() - pos.getZ();
            getPos(pos.offset(i, 3).add(offsetZ, 0, offsetX), i, pos);
            getPos(pos.offset(i, 3).add(-offsetZ, 0, -offsetX), i, pos);
            getPos(pos.offset(i, 3).add(offsetZ, 1, offsetX), i, pos);
            getPos(pos.offset(i, 3).add(-offsetZ, 1, -offsetX), i, pos);
            getPos(pos.offset(i, 2), i, pos);
            getPos(pos.offset(i, 2).up(), i, pos);
            getPos(pos.offset(i, 2).add(offsetZ, 0, offsetX), i, pos);
            getPos(pos.offset(i, 2).add(-offsetZ, 0, -offsetX), i, pos);
            getPos(pos.offset(i, 2).add(offsetZ, 1, offsetX), i, pos);
            getPos(pos.offset(i, 2).add(-offsetZ, 1, -offsetX), i, pos);
        }
    }

    private void getPos(BlockPos pos, Direction facing, BlockPos oPos) {
        if (switchPos.get() && bestPos != null && bestPos.equals(pos) && mc.world.isAir(bestPos)) {
            return;
        }

        if (!BlockUtil.canPlace(pos, placeRange.get()) && !(getBlock(pos) instanceof PistonBlock)) return;
        if (findClass(PistonBlock.class) == -1) return;

        if (CrystalConfig.lowVersion
            && !(getBlock(pos) instanceof PistonBlock)
            && (mc.player.getY() - pos.getY() <= -2.0 || mc.player.getY() - pos.getY() >= 3.0)
            && BlockUtil.distanceToXZ(pos.getX() + 0.5, pos.getZ() + 0.5) < 2.6) {
            return;
        }

        if (!mc.world.isAir(pos.offset(facing, -1))
            || getBlock(pos.offset(facing, -1)) == Blocks.FIRE
            || (getBlock(pos.offset(facing.getOpposite())) == Blocks.MOVING_PISTON && !checkCrystal2(pos.offset(facing.getOpposite())))) {
            return;
        }

        if (!BlockUtil.canPlace(pos, placeRange.get()) && !isPiston(pos, facing)) return;

        double eyeDistance = MathHelper.sqrt((float) mc.player.getEyePos().squaredDistanceTo(pos.toCenterPos()));
        if (eyeDistance < distance || bestPos == null) {
            bestPos = pos;
            bestOPos = oPos;
            bestFacing = facing;
            distance = eyeDistance;
            getPos = true;
            crystalTimer.reset();
        }
    }

    // ---- Building ----

    private void doPistonAura(BlockPos pos, Direction facing, BlockPos oPos) {
        if (stage >= stageSetting.get()) {
            stage = 0;
        }

        stage++;

        if (mc.world.isAir(pos)) {
            if (!BlockUtil.canPlace(pos)) {
                return;
            }

            if (stage >= pistonStage.get() && stage <= pistonMaxStage.get()) {
                Direction side = BlockUtil.getPlaceSide(pos);
                if (side == null) {
                    return;
                }

                int old = EntityUtil.selectedSlot();
                float cameraYaw = mc.player.getYaw();
                float cameraPitch = mc.player.getPitch();

                pistonFacing(facing);
                int piston = findClass(PistonBlock.class);
                doSwap(piston);
                BlockUtil.placeBlock(pos, false, pistonPacket.get());

                // Hand the camera back before the movement packet goes out, so the aim the server
                // keeps is the one the look packet above carried, not this temporary one.
                mc.player.setYaw(cameraYaw);
                mc.player.setPitch(cameraPitch);

                if (inventory.get()) {
                    doSwap(piston);
                    EntityUtil.syncInventory();
                } else {
                    doSwap(old);
                }

                BlockPos neighbour = pos.offset(side);
                Direction opposite = side.getOpposite();
                if (rotate.get()) {
                    RotationHelper.lookAt(neighbour, opposite);
                }
            }
        }

        if (stage >= powerStage.get() && stage <= powerMaxStage.get()) {
            doRedStone(pos, facing, oPos.offset(facing));
        }

        if (stage >= crystalStage.get() && stage <= crystalMaxStage.get()) {
            placeCrystal(oPos, facing);
        }

        if (stage >= fireStage.get() && stage <= fireMaxStage.get()) {
            doFire(oPos, facing);
        }
    }

    /**
     * Points the player at the yaw that makes the server face the piston the way the trap needs.
     *
     * <p>A piston takes its facing from the player's rotation at the moment it is placed, so the
     * look packet has to go out first.
     *
     * <p>The look packet alone is not enough. The server resolves the facing from the rotation
     * carried by that packet, but the client resolves its own prediction of the placement from the
     * player entity ({@code BlockItem.place} asks {@code ItemPlacementContext} for
     * {@code Direction.getEntityFacingOrder(player)[0]}), which is still the camera. Left at that,
     * the piston the client puts down - and so the one the player and the client's own
     * "is this cell a piston facing the right way" checks see until the server's block update
     * lands - faces wherever the camera happens to point instead of the way the trap needs. The
     * body is therefore aimed here too, and the caller puts the camera back straight after the
     * placement.
     */
    private void pistonFacing(Direction i) {
        float yaw;

        if (i == Direction.EAST) {
            yaw = -90.0f;
        } else if (i == Direction.WEST) {
            yaw = 90.0f;
        } else if (i == Direction.NORTH) {
            yaw = 180.0f;
        } else {
            yaw = 0.0f;
        }

        RotationHelper.snapAt(yaw, 5.0f);
        mc.player.setYaw(yaw);
        mc.player.setPitch(5.0f);
    }

    private void placeCrystal(BlockPos pos, Direction facing) {
        if (!BlockUtil.canPlaceCrystal(pos.offset(facing))) return;

        int crystal = findItem(Items.END_CRYSTAL);
        if (crystal == -1) return;

        int old = EntityUtil.selectedSlot();
        doSwap(crystal);
        BlockUtil.placeCrystal(pos.offset(facing), true);

        if (inventory.get()) {
            doSwap(crystal);
            EntityUtil.syncInventory();
        } else {
            doSwap(old);
        }
    }

    private boolean isPiston(BlockPos pos, Direction facing) {
        if (!(mc.world.getBlockState(pos).getBlock() instanceof PistonBlock)) {
            return false;
        }

        if (mc.world.getBlockState(pos).get(FacingBlock.FACING).getOpposite() != facing) {
            return false;
        }

        return mc.world.isAir(pos.offset(facing, -1))
            || getBlock(pos.offset(facing, -1)) == Blocks.FIRE
            || getBlock(pos.offset(facing.getOpposite())) == Blocks.MOVING_PISTON;
    }

    private void doFire(BlockPos pos, Direction facing) {
        if (!fire.get()) return;

        int fireSlot = findItem(Items.FLINT_AND_STEEL);
        if (fireSlot == -1) return;

        int old = EntityUtil.selectedSlot();
        int[] xOffset = new int[]{0, facing.getOffsetZ(), -facing.getOffsetZ()};
        int[] yOffset = new int[]{0, 1};
        int[] zOffset = new int[]{0, facing.getOffsetX(), -facing.getOffsetX()};

        for (int x : xOffset) {
            for (int y : yOffset) {
                for (int z : zOffset) {
                    if (getBlock(pos.add(x, y, z)) == Blocks.FIRE) {
                        return;
                    }
                }
            }
        }

        for (int x : xOffset) {
            for (int y : yOffset) {
                for (int z : zOffset) {
                    BlockPos firePos = pos.add(x, y, z);
                    if (!canFire(firePos)) continue;

                    doSwap(fireSlot);
                    placeFire(firePos);

                    if (inventory.get()) {
                        doSwap(fireSlot);
                        EntityUtil.syncInventory();
                    } else {
                        doSwap(old);
                    }

                    return;
                }
            }
        }
    }

    private boolean canFire(BlockPos pos) {
        if (BlockUtil.canReplace(pos.down())) {
            return false;
        } else if (!mc.world.isAir(pos)) {
            return false;
        } else {
            return BlockUtil.canClick(pos.offset(Direction.DOWN)) && BlockUtil.isStrictDirection(pos.down(), Direction.UP);
        }
    }

    public void placeFire(BlockPos pos) {
        BlockPos neighbour = pos.offset(Direction.DOWN);
        BlockUtil.clickBlock(neighbour, Direction.UP, rotate.get());
    }

    /**
     * Puts the redstone next to the piston, preferring a face that is not the piston's own.
     *
     * <p>Two passes: the best neighbouring face first, then any legal face
     * that is not the crystal cell and not the piston's back.
     */
    private void doRedStone(BlockPos pos, Direction facing, BlockPos crystalPos) {
        if (!mc.world.isAir(pos.offset(facing, -1))
            && getBlock(pos.offset(facing, -1)) != Blocks.FIRE
            && getBlock(pos.offset(facing.getOpposite())) != Blocks.MOVING_PISTON) {
            return;
        }

        for (Direction i : Direction.values()) {
            if (getBlock(pos.offset(i)) == Blocks.REDSTONE_BLOCK) {
                return;
            }
        }

        int power = findBlock(Blocks.REDSTONE_BLOCK);
        if (power == -1) return;

        int old = EntityUtil.selectedSlot();
        Direction bestNeighboring = BlockUtil.getBestNeighboring(pos, facing);

        if (bestNeighboring != null
            && bestNeighboring != facing.getOpposite()
            && BlockUtil.canPlace(pos.offset(bestNeighboring), placeRange.get())
            && !pos.offset(bestNeighboring).equals(crystalPos)) {
            doSwap(power);
            BlockUtil.placeBlock(pos.offset(bestNeighboring), rotate.get());
            restore(power, old);
            return;
        }

        for (Direction i : Direction.values()) {
            if (BlockUtil.canPlace(pos.offset(i), placeRange.get()) && !pos.offset(i).equals(crystalPos) && i != facing.getOpposite()) {
                doSwap(power);
                BlockUtil.placeBlock(pos.offset(i), rotate.get());
                restore(power, old);
                return;
            }
        }
    }

    private void restore(int usedSlot, int oldSlot) {
        if (inventory.get()) {
            doSwap(usedSlot);
            EntityUtil.syncInventory();
        } else {
            doSwap(oldSlot);
        }
    }

    private void doSwap(int slot) {
        if (inventory.get()) {
            InventoryUtil.inventorySwap(slot, EntityUtil.selectedSlot());
        } else {
            InventoryUtil.switchToSlot(slot);
        }
    }

    public int findItem(Item itemIn) {
        return inventory.get() ? InventoryUtil.findItemInventorySlot(itemIn) : InventoryUtil.findItem(itemIn);
    }

    public int findBlock(Block blockIn) {
        return inventory.get() ? InventoryUtil.findBlockInventorySlot(blockIn) : InventoryUtil.findBlock(blockIn);
    }

    public int findClass(Class<?> clazz) {
        return inventory.get() ? InventoryUtil.findClassInventorySlot(clazz) : InventoryUtil.findClass(clazz);
    }

    private Block getBlock(BlockPos pos) {
        return BlockUtil.getBlock(pos);
    }
}
