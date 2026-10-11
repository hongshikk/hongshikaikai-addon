package com.hongshikaikai.addon.utils.crystal;

import com.hongshikaikai.addon.utils.CrystalCompat;
import meteordevelopment.meteorclient.systems.friends.Friends;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Targeting, crystal attacking and the world-override scratch state.
 *
 * <h2>The world override</h2>
 * The combat code can pretend a block is something else by writing {@link #modifyPos} /
 * {@link #modifyBlockState}: every block read the crystal code makes goes through
 * {@link #getBlockState}, which applies the substitution. That is what makes a "base place" costable
 * before the obsidian exists - the damage and placement checks run as if the block under the crystal
 * were already obsidian.
 *
 * <p>{@link #terrainIgnore} is the same idea applied to everything at once: every block that is not
 * on the "hard" list is read as air for the duration of one calculation.
 */
public class CombatUtil implements Wrapper {
    public static final Timer breakTimer = new Timer();

    /** While set, every block read through {@link #getBlockState} reports {@link #modifyBlockState}. */
    public static BlockPos modifyPos;
    public static BlockState modifyBlockState = Blocks.AIR.getDefaultState();

    /** While set, every non-hard block is read as air. */
    public static boolean terrainIgnore = false;

    /**
     * The blocks the hole logic treats as "hard": they are the ones a crystal cannot blow away,
     * so they are the only ones that survive a terrain-ignore read.
     */
    private static final List<Block> HARD = List.of(
        Blocks.OBSIDIAN,
        Blocks.ENDER_CHEST,
        Blocks.NETHERITE_BLOCK,
        Blocks.CRYING_OBSIDIAN,
        Blocks.RESPAWN_ANCHOR,
        Blocks.ANCIENT_DEBRIS,
        Blocks.ANVIL
    );

    public static boolean isHard(Block block) {
        return block == Blocks.BEDROCK || HARD.contains(block);
    }

    /**
     * Whether the block at {@code pos} is one the hole logic treats as hard.
     *
     * <p>The list lives here rather than in a separate hole module, so the "which neighbouring cell
     * has something worth aiming at" test does not need one.
     */
    public static boolean isHard(BlockPos pos) {
        return isHard(mc.world.getBlockState(pos).getBlock());
    }

    /** Block state as the crystal logic should see it, with the override and terrain-ignore applied. */
    public static BlockState getBlockState(BlockPos pos) {
        if (modifyPos != null && modifyBlockState != null && pos.equals(modifyPos)) {
            return modifyBlockState;
        }

        BlockState state = mc.world.getBlockState(pos);
        if (terrainIgnore && !isHard(state.getBlock())) {
            return Blocks.AIR.getDefaultState();
        }

        return state;
    }

    /** Clears both override fields. Every code path that sets them must call this. */
    public static void clearOverride() {
        modifyPos = null;
        modifyBlockState = Blocks.AIR.getDefaultState();
    }

    /** Every other player in range that is not a friend. */
    public static List<PlayerEntity> getEnemies(double range) {
        List<PlayerEntity> list = new ArrayList<>();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (isValid(player, range)) {
                list.add(player);
            }
        }

        return list;
    }

    public static void attackCrystal(BlockPos pos, boolean rotate, boolean eatingPause) {
        attackCrystal(new Box(pos), rotate, eatingPause);
    }

    public static void attackCrystal(Box box, boolean rotate, boolean eatingPause) {
        for (EndCrystalEntity entity : BlockUtil.getEndCrystals(box)) {
            attackWithDelay(entity, rotate, eatingPause);
        }
    }

    public static void attackWithDelay(Entity entity, boolean rotate, boolean usingPause) {
        if (breakTimer.passed((long) (CrystalConfig.attackDelay * 1000.0))) {
            if (!usingPause || !mc.player.isUsingItem()) {
                attack(entity, rotate);
            }
        }
    }

    /** Attacks an entity, refusing a hit the server would resolve as out of range. */
    public static void attack(Entity entity, boolean rotate) {
        if (entity == null) {
            return;
        }

        Vec3d attackVec = MathUtil.getClosestPointToBox(mc.player.getEyePos(), entity.getBoundingBox());
        if (mc.player.getEyePos().distanceTo(attackVec) > CrystalConfig.ieRange) {
            return;
        }

        breakTimer.reset();
        if (rotate && CrystalConfig.attackRotate) {
            RotationHelper.lookAt(attackVec);
        }

        mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(entity, mc.player.isSneaking()));
        CrystalCompat.resetAttackCooldown(mc.player);
        EntityUtil.swingHand(Hand.MAIN_HAND, CrystalConfig.attackSwing);
    }

    public static boolean isntValid(Entity entity, double range) {
        return !isValid(entity, range);
    }

    public static boolean isValid(Entity entity, double range) {
        boolean invalid = entity == null
            || !entity.isAlive()
            || entity.equals(mc.player)
            || (entity instanceof PlayerEntity player && Friends.get().isFriend(player))
            || EntityUtil.pos(mc.player).distanceTo(EntityUtil.pos(entity)) > range;
        return !invalid;
    }

    public static boolean isValid(Entity entity) {
        boolean invalid = entity == null
            || !entity.isAlive()
            || entity.equals(mc.player)
            || (entity instanceof PlayerEntity player && Friends.get().isFriend(player));
        return !invalid;
    }

    public static PlayerEntity getClosestEnemy(double distance) {
        PlayerEntity closest = null;

        for (PlayerEntity player : getEnemies(distance)) {
            if (closest == null) {
                closest = player;
            } else if (mc.player.squaredDistanceTo(EntityUtil.pos(player)) < mc.player.squaredDistanceTo(EntityUtil.pos(closest))) {
                closest = player;
            }
        }

        return closest;
    }
}
