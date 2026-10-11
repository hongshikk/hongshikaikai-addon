package com.hongshikaikai.addon.utils.crystal;

import com.hongshikaikai.addon.modules.CrystalAuraPlus;
import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.AnvilBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CartographyTableBlock;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.GrindstoneBlock;
import net.minecraft.block.LoomBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.StonecutterBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.thrown.ExperienceBottleEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Block placement and crystal placement helpers.
 *
 * <h2>Design notes</h2>
 * <ul>
 *     <li>Block reads go through {@link CombatUtil#getBlockState}, so the module's own world override
 *         (the "treat this cell as obsidian" trick) reaches every check: the override is applied
 *         locally by that accessor instead of through a world mixin.</li>
 *     <li>Placement rotation is handled by {@link RotationHelper}, which sends a look packet ahead of
 *         the interaction rather than steering the player's actual camera.</li>
 *     <li>Air placement is driven by {@code CrystalAura+}'s own setting, and the click side always
 *         uses the vanilla "any face of a solid neighbour" rule.</li>
 * </ul>
 *
 * <p>{@link #placedPos} is shared across the tick: a freshly placed cell is remembered for the rest
 * of the tick so two placements cannot target the same cell. The modules clear it at the top of each
 * tick.
 */
public class BlockUtil implements Wrapper {
    public static final List<BlockPos> placedPos = new ArrayList<>();

    private BlockUtil() {
    }

    public static boolean canPlace(BlockPos pos) {
        return canPlace(pos, 1000.0);
    }

    public static boolean canPlace(BlockPos pos, double distance) {
        if (getPlaceSide(pos, distance) == null) {
            return false;
        }

        return canReplace(pos) && !hasEntity(pos, false);
    }

    public static boolean canPlace(BlockPos pos, double distance, boolean ignoreCrystal) {
        if (getPlaceSide(pos, distance) == null) {
            return false;
        }

        return canReplace(pos) && !hasEntity(pos, ignoreCrystal);
    }

    public static boolean clientCanPlace(BlockPos pos, boolean ignoreCrystal) {
        return canReplace(pos) && !hasEntity(pos, ignoreCrystal);
    }

    /** Every entity whose hitbox intersects {@code box}, skipping armour stands when configured to. */
    public static List<Entity> getEntities(Box box) {
        List<Entity> list = new ArrayList<>();

        for (Entity entity : mc.world.getEntities()) {
            if (entity != null
                && (!(entity instanceof ArmorStandEntity) || !CrystalConfig.ignoreArmorStand)
                && entity.getBoundingBox().intersects(box)) {
                list.add(entity);
            }
        }

        return list;
    }

    public static List<EndCrystalEntity> getEndCrystals(Box box) {
        List<EndCrystalEntity> list = new ArrayList<>();

        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof EndCrystalEntity crystal && crystal.getBoundingBox().intersects(box)) {
                list.add(crystal);
            }
        }

        return list;
    }

    public static boolean hasEntity(BlockPos pos, boolean ignoreCrystal) {
        return hasEntity(new Box(pos), ignoreCrystal);
    }

    /**
     * Whether anything that would block a placement stands in the cell.
     *
     * <p>Items, orbs, bottles and arrows are ignored - they are not solid and the server would let
     * the placement through. A crystal is only ignored when it is close enough to be attacked out of
     * the way, which is what makes "break the crystal and place in the same tick" a legal sequence
     * rather than a placement into an occupied cell.
     */
    public static boolean hasEntity(Box box, boolean ignoreCrystal) {
        for (Entity entity : getEntities(box)) {
            if (entity.isAlive()
                && !(entity instanceof ItemEntity)
                && !(entity instanceof ExperienceOrbEntity)
                && !(entity instanceof ExperienceBottleEntity)
                && !(entity instanceof ArrowEntity)
                && (!ignoreCrystal
                    || !(entity instanceof EndCrystalEntity)
                    || !(mc.player.getEyePos().distanceTo(MathUtil.getClosestPoint(entity)) <= CrystalConfig.ieRange))) {
                return true;
            }
        }

        return false;
    }

    public static boolean hasCrystal(BlockPos pos) {
        for (EndCrystalEntity entity : getEndCrystals(new Box(pos))) {
            if (entity.isAlive()) {
                return true;
            }
        }

        return false;
    }

    public static boolean noEntityBlockCrystal(BlockPos pos, boolean ignoreCrystal) {
        return noEntityBlockCrystal(pos, ignoreCrystal, false);
    }

    public static boolean noEntityBlockCrystal(BlockPos pos, boolean ignoreCrystal, boolean ignoreItem) {
        for (Entity entity : getEntities(new Box(pos))) {
            if (entity.isAlive()
                && (!ignoreItem || !(entity instanceof ItemEntity))
                && (!ignoreCrystal
                    || !(entity instanceof EndCrystalEntity)
                    || !(mc.player.getEyePos().distanceTo(MathUtil.getClosestPoint(entity)) <= CrystalConfig.ieRange))) {
                return false;
            }
        }

        return true;
    }

    /**
     * Whether a crystal can be placed on top of {@code pos}.
     *
     * <p>Vanilla's rules: obsidian or bedrock below, a clickable face on it, and both cells above
     * free of entities. The lower cell has to be air (that is where the crystal's base sits); the
     * upper cell only has to be free of entities, because a crystal is two blocks tall.
     */
    public static boolean canPlaceCrystal(BlockPos pos) {
        BlockPos obsPos = pos.down();
        BlockPos boost = obsPos.up();
        return (getBlock(obsPos) == Blocks.BEDROCK || getBlock(obsPos) == Blocks.OBSIDIAN)
            && getClickSideStrict(obsPos) != null
            && mc.world.isAir(boost)
            && noEntityBlockCrystal(boost, false)
            && noEntityBlockCrystal(boost.up(), false)
            && (!CrystalConfig.lowVersion || mc.world.isAir(boost.up()));
    }

    /** Places a crystal on {@code pos}, optionally aiming at the click point first. */
    public static void placeCrystal(BlockPos pos, boolean rotate) {
        boolean offhand = mc.player.getOffHandStack().getItem() == Items.END_CRYSTAL;
        BlockPos obsPos = pos.down();
        Direction facing = getClickSide(obsPos);
        Vec3d vec = obsPos.toCenterPos().add(facing.getVector().getX() * 0.5, facing.getVector().getY() * 0.5, facing.getVector().getZ() * 0.5);
        if (rotate) {
            RotationHelper.lookAt(vec);
        }

        clickBlock(obsPos, facing, false, offhand ? Hand.OFF_HAND : Hand.MAIN_HAND);
    }

    public static void placeBlock(BlockPos pos, boolean rotate) {
        placeBlock(pos, rotate, CrystalConfig.packetPlace);
    }

    public static void placeBlock(BlockPos pos, boolean rotate, boolean packet) {
        if (allowAirPlace()) {
            placedPos.add(pos);
            airPlace(pos, rotate, Hand.MAIN_HAND, packet);
        } else {
            Direction side = getPlaceSide(pos);
            if (side != null) {
                placedPos.add(pos);
                clickBlock(pos.offset(side), side.getOpposite(), rotate, Hand.MAIN_HAND, packet);
            }
        }
    }

    public static void clickBlock(BlockPos pos, Direction side, boolean rotate) {
        clickBlock(pos, side, rotate, Hand.MAIN_HAND);
    }

    public static void clickBlock(BlockPos pos, Direction side, boolean rotate, Hand hand) {
        clickBlock(pos, side, rotate, hand, CrystalConfig.packetPlace);
    }

    public static void clickBlock(BlockPos pos, Direction side, boolean rotate, boolean packet) {
        clickBlock(pos, side, rotate, Hand.MAIN_HAND, packet);
    }

    public static void clickBlock(BlockPos pos, Direction side, boolean rotate, Hand hand, boolean packet) {
        Vec3d directionVec = new Vec3d(
            pos.getX() + 0.5 + side.getVector().getX() * 0.5,
            pos.getY() + 0.5 + side.getVector().getY() * 0.5,
            pos.getZ() + 0.5 + side.getVector().getZ() * 0.5
        );
        if (rotate) {
            RotationHelper.lookAt(directionVec);
        }

        EntityUtil.swingHand(hand, CrystalConfig.interactSwing);

        BlockHitResult result = new BlockHitResult(directionVec, side, pos, false);
        if (packet) {
            EntityUtil.interactBlock(hand, result);
        } else {
            mc.interactionManager.interactBlock(mc.player, hand, result);
        }
    }

    public static void clickBlock(BlockPos pos, Direction side, boolean rotate, Hand hand, SwingSide swingSide) {
        Vec3d directionVec = new Vec3d(
            pos.getX() + 0.5 + side.getVector().getX() * 0.5,
            pos.getY() + 0.5 + side.getVector().getY() * 0.5,
            pos.getZ() + 0.5 + side.getVector().getZ() * 0.5
        );
        if (rotate) {
            RotationHelper.lookAt(directionVec);
        }

        EntityUtil.swingHand(hand, swingSide);
        EntityUtil.interactBlock(hand, new BlockHitResult(directionVec, side, pos, false));
    }

    public static void airPlace(BlockPos pos, boolean rotate) {
        airPlace(pos, rotate, Hand.MAIN_HAND, CrystalConfig.packetPlace);
    }

    public static void airPlace(BlockPos pos, boolean rotate, Hand hand, boolean packet) {
        Direction side = getClickSide(pos);
        Vec3d directionVec = new Vec3d(
            pos.getX() + 0.5 + side.getVector().getX() * 0.5,
            pos.getY() + 0.5 + side.getVector().getY() * 0.5,
            pos.getZ() + 0.5 + side.getVector().getZ() * 0.5
        );
        if (rotate) {
            RotationHelper.lookAt(directionVec);
        }

        EntityUtil.swingHand(hand, CrystalConfig.interactSwing);
        BlockHitResult result = new BlockHitResult(directionVec, side, pos, false);
        if (packet) {
            EntityUtil.interactBlock(hand, result);
        } else {
            mc.interactionManager.interactBlock(mc.player, hand, result);
        }
    }

    public static double distanceToXZ(double x, double z, double x2, double z2) {
        double dx = x2 - x;
        double dz = z2 - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static double distanceToXZ(double x, double z) {
        return distanceToXZ(x, z, mc.player.getX(), mc.player.getZ());
    }

    /** The neighbour face of a solid block that {@code pos} can be placed against, nearest first. */
    public static Direction getPlaceSide(BlockPos pos) {
        if (allowAirPlace()) {
            return getClickSide(pos);
        }

        double minDistance = Double.MAX_VALUE;
        Direction side = null;

        for (Direction i : Direction.values()) {
            if (canClick(pos.offset(i)) && !canReplace(pos.offset(i)) && isStrictDirection(pos.offset(i), i.getOpposite())) {
                double vecDis = mc.player
                    .getEyePos()
                    .squaredDistanceTo(pos.toCenterPos().add(i.getVector().getX() * 0.5, i.getVector().getY() * 0.5, i.getVector().getZ() * 0.5));
                if (!(vecDis > minDistance)) {
                    side = i;
                    minDistance = vecDis;
                }
            }
        }

        return side;
    }

    /** A neighbouring face to put the redstone on that is not the piston's own face. */
    public static Direction getBestNeighboring(BlockPos pos, Direction facing) {
        Direction bestFacing = null;
        double distance = 0.0;

        for (Direction i : Direction.values()) {
            if ((facing == null || !pos.offset(i).equals(pos.offset(facing, -1)))
                && i != Direction.DOWN
                && getPlaceSide(pos) != null
                && (bestFacing == null || mc.player.getEyePos().squaredDistanceTo(pos.offset(i).toCenterPos()) < distance)) {
                bestFacing = i;
                distance = mc.player.getEyePos().squaredDistanceTo(pos.offset(i).toCenterPos());
            }
        }

        return bestFacing;
    }

    public static Direction getPlaceSide(BlockPos pos, double reachDistance) {
        if (allowAirPlace()) {
            Direction i = getClickSide(pos);
            double vecDis = mc.player
                .getEyePos()
                .squaredDistanceTo(pos.toCenterPos().add(i.getVector().getX() * 0.5, i.getVector().getY() * 0.5, i.getVector().getZ() * 0.5));
            return Math.sqrt(vecDis) > reachDistance ? null : Direction.DOWN;
        }

        double minDistance = Double.MAX_VALUE;
        Direction side = null;

        for (Direction i : Direction.values()) {
            if (canClick(pos.offset(i)) && !canReplace(pos.offset(i)) && isStrictDirection(pos.offset(i), i.getOpposite())) {
                double vecDis = mc.player
                    .getEyePos()
                    .squaredDistanceTo(pos.toCenterPos().add(i.getVector().getX() * 0.5, i.getVector().getY() * 0.5, i.getVector().getZ() * 0.5));
                if (!(Math.sqrt(vecDis) > reachDistance) && !(vecDis > minDistance)) {
                    side = i;
                    minDistance = vecDis;
                }
            }
        }

        return side;
    }

    /** Nearest face of the cell, defaulting to {@code UP} when nothing is closer. */
    public static Direction getClickSide(BlockPos pos) {
        Direction side = Direction.UP;
        double minDistance = Double.MAX_VALUE;

        for (Direction i : Direction.values()) {
            if (isStrictDirection(pos, i)) {
                double disSq = mc.player.getEyePos().squaredDistanceTo(pos.offset(i).toCenterPos());
                if (!(disSq > minDistance)) {
                    side = i;
                    minDistance = disSq;
                }
            }
        }

        return side;
    }

    /** Nearest face of the cell, or null when no face is clickable from here. */
    public static Direction getClickSideStrict(BlockPos pos) {
        Direction side = null;
        double minDistance = Double.MAX_VALUE;

        for (Direction i : Direction.values()) {
            if (isStrictDirection(pos, i)) {
                double disSq = mc.player.getEyePos().squaredDistanceTo(pos.offset(i).toCenterPos());
                if (!(disSq > minDistance)) {
                    side = i;
                    minDistance = disSq;
                }
            }
        }

        return side;
    }

    public static boolean isStrictDirection(BlockPos pos, Direction side, double reachDistance) {
        double vecDis = mc.player
            .getEyePos()
            .squaredDistanceTo(pos.toCenterPos().add(side.getVector().getX() * 0.5, side.getVector().getY() * 0.5, side.getVector().getZ() * 0.5));
        return Math.sqrt(vecDis) > reachDistance ? false : isStrictDirection(pos, side);
    }

    /**
     * Whether a face may be clicked.
     *
     * <p>This is the vanilla placement mode: every face is reachable and the server does the
     * line-of-sight work.
     */
    public static boolean isStrictDirection(BlockPos pos, Direction side) {
        return true;
    }

    public static ArrayList<BlockPos> getSphere(float range) {
        return getSphere(range, mc.player.getEyePos());
    }

    public static BlockPos getBlock(Block block, float range) {
        for (BlockPos pos : getSphere(range)) {
            if (getBlock(pos) == block) {
                return pos;
            }
        }

        return null;
    }

    public static BlockPos getBlock(Class<?> block, float range) {
        for (BlockPos pos : getSphere(range)) {
            if (block.isInstance(getBlock(pos))) {
                return pos;
            }
        }

        return null;
    }

    public static ArrayList<BlockPos> getSphere(float range, Vec3d pos) {
        ArrayList<BlockPos> list = new ArrayList<>();

        for (double y = pos.getY() + range; y > pos.getY() - range; y--) {
            if (y < mc.world.getBottomY()) continue;

            for (double x = pos.getX() - range; x < pos.getX() + range; x++) {
                for (double z = pos.getZ() - range; z < pos.getZ() + range; z++) {
                    BlockPos curPos = new BlockPosX(x, y, z);
                    if (!(curPos.toCenterPos().distanceTo(pos) > range)) {
                        list.add(curPos);
                    }
                }
            }
        }

        return list;
    }

    public static Block getBlock(BlockPos pos) {
        return CombatUtil.getBlockState(pos).getBlock();
    }

    public static BlockState getState(BlockPos pos) {
        return CombatUtil.getBlockState(pos);
    }

    /**
     * Whether a cell may be built into.
     *
     * <p>{@code placedPos} covers the same-tick case and the module's {@code replace} setting covers
     * cobwebs, which are not replaceable in vanilla but which this code treats as replaceable so a
     * crystal can be placed into them.
     */
    public static boolean canReplace(BlockPos pos) {
        // A fixed build limit of 320 expressed against the world's own height, so a datapack that
        // changes it still means the same thing.
        if (pos.getY() >= mc.world.getTopYInclusive() + 1) {
            return false;
        }

        if (CrystalConfig.multiPlace && placedPos.contains(pos)) {
            return false;
        }

        BlockState state = CombatUtil.getBlockState(pos);
        return (state.getBlock() == Blocks.COBWEB && CrystalAuraPlus.replaceCobweb()) || state.isReplaceable();
    }

    public static boolean canClick(BlockPos pos) {
        if (CrystalConfig.multiPlace && placedPos.contains(pos)) {
            return true;
        }

        BlockState state = CombatUtil.getBlockState(pos);
        Block block = state.getBlock();
        if (block == Blocks.COBWEB) {
            return CrystalAuraPlus.airPlaceEnabled();
        }

        return mc.player.isSneaking() || !isClickable(block);
    }

    /** Blocks that open a screen or otherwise swallow a right-click, so they cannot be placed against. */
    public static boolean isClickable(Block block) {
        return block instanceof CraftingTableBlock
            || block instanceof AnvilBlock
            || block instanceof LoomBlock
            || block instanceof CartographyTableBlock
            || block instanceof GrindstoneBlock
            || block instanceof StonecutterBlock
            || block instanceof ButtonBlock
            || block instanceof AbstractPressurePlateBlock
            || block instanceof BlockWithEntity
            || block instanceof BedBlock
            || block instanceof FenceGateBlock
            || block instanceof DoorBlock
            || block instanceof NoteBlock
            || block instanceof TrapdoorBlock;
    }

    public static boolean canCollide(Box box) {
        return canCollide(mc.player, box);
    }

    public static boolean canCollide(Entity entity, Box box) {
        return mc.world.canCollide(entity, box);
    }

    /**
     * Whether a block may be placed against nothing at all.
     *
     * <p>The switch itself lives on {@code CrystalAura+} ({@code air-place}).
     */
    public static boolean allowAirPlace() {
        return CrystalAuraPlus.airPlaceEnabled();
    }

    /** Convenience for the modules: whether {@code entity} is a player that could be a crystal target. */
    public static boolean isEnemy(Entity entity) {
        return entity instanceof PlayerEntity;
    }
}
