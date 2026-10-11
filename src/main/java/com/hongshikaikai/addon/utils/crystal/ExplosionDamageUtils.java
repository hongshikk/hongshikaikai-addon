package com.hongshikaikai.addon.utils.crystal;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.GameMode;
import net.minecraft.world.explosion.Explosion;

/**
 * Explosion damage calculation for the crystal modules.
 *
 * <p>Only the explosion path is here: it is the only one the crystal modules use.
 *
 * <p>The shape of the calculation is vanilla's: the sampled exposure of the target's hitbox to the
 * blast, the distance falloff, then armour toughness, Resistance and the Protection/Blast
 * Protection enchants. Two deliberate differences from vanilla:
 *
 * <ul>
 *     <li>the exposure raycast ignores every block below 600 blast resistance, so only obsidian,
 *         bedrock, anvils and the like cast a shadow over a crystal;</li>
 *     <li>a base-place calculation can override one block with obsidian, which is how "place the
 *         obsidian and the crystal in the same tick" is costed before the obsidian exists.</li>
 * </ul>
 */
public final class ExplosionDamageUtils implements Wrapper {
    private ExplosionDamageUtils() {
    }

    /** Counts a block as a blast shield only from this resistance up; the threshold is 600. */
    private static final float BLAST_RESISTANCE_THRESHOLD = 600.0f;

    /** The vanilla exposure raycast, restricted to blast-resistant blocks as described above. */
    public static final RaycastFactory HIT_FACTORY = (context, blockPos) -> {
        BlockState blockState = CombatUtil.getBlockState(blockPos);
        return blockState.getBlock().getBlastResistance() < BLAST_RESISTANCE_THRESHOLD
            ? null
            : blockState.getCollisionShape(mc.world, blockPos).raycast(context.start(), context.end(), blockPos);
    };

    /** Damage of a crystal detonated at {@code pos}, using the target's predicted state when given. */
    public static float calculateDamage(Vec3d pos, LivingEntity entity, PlayerEntityPredict predict, float power) {
        return explosionDamage(entity, predict, pos, power * 2.0f);
    }

    /**
     * Damage of a respawn anchor detonated at {@code pos}.
     *
     * <p>A charged anchor has blast power 10 (a crystal has 6), and the anchor block itself is read
     * as air, because the block is consumed by the detonation and must not shield the target from
     * its own explosion.
     */
    public static float anchorDamage(BlockPos pos, LivingEntity target, PlayerEntityPredict predict) {
        return overridingExplosionDamage(target, predict, pos.toCenterPos(), 10.0f, pos, Blocks.AIR.getDefaultState());
    }

    public static float explosionDamage(LivingEntity target, PlayerEntityPredict predict, Vec3d explosionPos, float power) {
        return explosionDamage(target, predict, explosionPos, power, HIT_FACTORY);
    }

    private static float explosionDamage(LivingEntity target, PlayerEntityPredict predict, Vec3d explosionPos, float power, RaycastFactory raycastFactory) {
        if (target == null) {
            return 0.0f;
        }

        if (target instanceof PlayerEntity player && getGameMode(player) == GameMode.CREATIVE) {
            return 0.0f;
        }

        return explosionDamage(
            target,
            predict != null ? predict.pos : EntityUtil.pos(target),
            predict != null ? predict.box : target.getBoundingBox(),
            explosionPos,
            power,
            raycastFactory
        );
    }

    public static float explosionDamage(
        LivingEntity target, Vec3d targetPos, Box targetBox, Vec3d explosionPos, float power, RaycastFactory raycastFactory
    ) {
        double modDistance = distance(targetPos.x, targetPos.y, targetPos.z, explosionPos.x, explosionPos.y, explosionPos.z);
        if (modDistance > power) {
            return 0.0f;
        }

        double exposure = getExposure(explosionPos, targetBox, raycastFactory);
        double impact = (1.0 - modDistance / power) * exposure;
        float damage = (int) ((impact * impact + impact) / 2.0 * 7.0 * 12.0 + 1.0);
        return calculateReductionsExplosion(damage, target, mc.world.getDamageSources().explosion((Explosion) null));
    }

    /**
     * Damage of an explosion that is costed as if {@code overridePos} held {@code overrideState}.
     *
     * <p>This is what makes a base place possible: the obsidian's blast shadow is applied to the
     * target before the obsidian has been placed, so a position is only chosen when the crystal on
     * top of it is still lethal after the block that will be underneath it is accounted for.
     */
    public static float overridingExplosionDamage(
        LivingEntity target, PlayerEntityPredict predict, Vec3d explosionPos, float power, BlockPos overridePos, BlockState overrideState
    ) {
        return explosionDamage(target, predict, explosionPos, power, getOverridingHitFactory(overridePos, overrideState));
    }

    public static RaycastFactory getOverridingHitFactory(BlockPos overridePos, BlockState overrideState) {
        return (context, blockPos) -> {
            BlockState blockState;
            if (blockPos.equals(overridePos)) {
                blockState = overrideState;
            } else {
                blockState = CombatUtil.getBlockState(blockPos);
                if (blockState.getBlock().getBlastResistance() < BLAST_RESISTANCE_THRESHOLD) {
                    return null;
                }
            }

            return blockState.getCollisionShape(mc.world, blockPos).raycast(context.start(), context.end(), blockPos);
        };
    }

    /** Armour, Resistance and protection enchants, in the order vanilla applies them. */
    public static float calculateReductionsExplosion(float damage, LivingEntity entity, DamageSource damageSource) {
        if (damageSource.isScaledWithDifficulty()) {
            switch (mc.world.getDifficulty()) {
                case EASY -> damage = Math.min(damage / 2.0f + 1.0f, damage);
                case HARD -> damage *= 1.5f;
                default -> {
                }
            }
        }

        damage = DamageUtil.getDamageLeft(entity, damage, damageSource, getArmor(entity), getArmorToughness(entity));
        damage = resistanceReduction(entity, damage);
        damage = DamageUtil.getInflictedDamage(damage, getProtectionAmount(entity));
        return Math.max(damage, 0.0f);
    }

    /** Armour points as the server sees them, i.e. the attribute the equipment has already fed. */
    private static float getArmor(LivingEntity entity) {
        if (entity instanceof PlayerEntity player) {
            return player.getArmor();
        }

        return (float) Math.floor(entity.getAttributeValue(EntityAttributes.ARMOR));
    }

    private static float getArmorToughness(LivingEntity entity) {
        return (float) entity.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
    }

    private static float resistanceReduction(LivingEntity player, float damage) {
        StatusEffectInstance resistance = player.getStatusEffect(StatusEffects.RESISTANCE);
        if (resistance != null) {
            int lvl = resistance.getAmplifier() + 1;
            damage *= 1.0f - lvl * 0.2f;
        }

        return Math.max(damage, 0.0f);
    }

    /**
     * The protection levels worn by the target, weighted the way vanilla's explosion path weights
     * them: Blast Protection counts double, plain Protection once.
     */
    private static int getProtectionAmount(LivingEntity entity) {
        int amount = 0;

        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = entity.getEquippedStack(slot);
            amount += getEnchantmentLevel(stack, Enchantments.BLAST_PROTECTION) * 2;
            amount += getEnchantmentLevel(stack, Enchantments.PROTECTION);
        }

        return amount;
    }

    /**
     * Level of one enchantment on a stack, read straight off the item's enchantment component.
     *
     * <p>Reading the component instead of going through the registry keeps this additive-only and
     * identical on every version the addon builds for, and it already covers enchanted books
     * through the stored-enchantments component.
     */
    public static int getEnchantmentLevel(ItemStack stack, RegistryKey<Enchantment> enchantment) {
        if (stack.isEmpty()) {
            return 0;
        }

        ItemEnchantmentsComponent component = stack.getItem() == Items.ENCHANTED_BOOK
            ? stack.get(DataComponentTypes.STORED_ENCHANTMENTS)
            : stack.getEnchantments();

        if (component == null) {
            return 0;
        }

        for (var entry : component.getEnchantmentEntries()) {
            RegistryEntry<Enchantment> key = entry.getKey();
            if (key.matchesKey(enchantment)) {
                return entry.getIntValue();
            }
        }

        return 0;
    }

    /**
     * Fraction of the target's hitbox a ray from the explosion can reach.
     *
     * <p>The exact sweep samples one point per {@code 1 / (size * 2 + 1)} of a block along each axis;
     * the "optimised" branch samples the eight corners only, which is much cheaper and slightly
     * optimistic but can miss a shield that only covers part of a face.
     */
    private static float getExposure(Vec3d source, Box box, RaycastFactory raycastFactory) {
        if (CrystalConfig.optimizedCalc) {
            int miss = 0;
            int hit = 0;

            for (int k = 0; k <= 1; k++) {
                for (int l = 0; l <= 1; l++) {
                    for (int m = 0; m <= 1; m++) {
                        double n = MathHelper.lerp(k, box.minX, box.maxX);
                        double o = MathHelper.lerp(l, box.minY, box.maxY);
                        double p = MathHelper.lerp(m, box.minZ, box.maxZ);
                        Vec3d vec3d = new Vec3d(n, o, p);
                        if (raycast(vec3d, source, CombatUtil.terrainIgnore) == net.minecraft.util.hit.HitResult.Type.MISS) {
                            miss++;
                        }

                        hit++;
                    }
                }
            }

            return (float) miss / hit;
        }

        double xDiff = box.maxX - box.minX;
        double yDiff = box.maxY - box.minY;
        double zDiff = box.maxZ - box.minZ;
        double xStep = 1.0 / (xDiff * 2.0 + 1.0);
        double yStep = 1.0 / (yDiff * 2.0 + 1.0);
        double zStep = 1.0 / (zDiff * 2.0 + 1.0);

        if (xStep <= 0.0 || yStep <= 0.0 || zStep <= 0.0) {
            return 0.0f;
        }

        int misses = 0;
        int hits = 0;
        double xOffset = (1.0 - Math.floor(1.0 / xStep) * xStep) * 0.5;
        double zOffset = (1.0 - Math.floor(1.0 / zStep) * zStep) * 0.5;
        xStep *= xDiff;
        yStep *= yDiff;
        zStep *= zDiff;
        double startX = box.minX + xOffset;
        double startY = box.minY;
        double startZ = box.minZ + zOffset;
        double endX = box.maxX + xOffset;
        double endY = box.maxY;
        double endZ = box.maxZ + zOffset;

        for (double x = startX; x <= endX; x += xStep) {
            for (double y = startY; y <= endY; y += yStep) {
                for (double z = startZ; z <= endZ; z += zStep) {
                    Vec3d position = new Vec3d(x, y, z);
                    if (raycast(new ExposureRaycastContext(position, source), raycastFactory) == null) {
                        misses++;
                    }

                    hits++;
                }
            }
        }

        return (float) misses / hits;
    }

    /**
     * Block raycast used by the optimised exposure branch.
     *
     * <p>When {@code ignoreTerrain} is set, every block below the blast-resistance threshold is
     * treated as absent - the "terrain ignore" switch.
     */
    public static net.minecraft.util.hit.HitResult.Type raycast(Vec3d start, Vec3d end, boolean ignoreTerrain) {
        return BlockView.raycast(start, end, null, (innerContext, blockPos) -> {
            BlockState blockState = CombatUtil.getBlockState(blockPos);
            if (blockState.getBlock().getBlastResistance() < BLAST_RESISTANCE_THRESHOLD && ignoreTerrain) {
                return null;
            }

            BlockHitResult hitResult = blockState.getCollisionShape(mc.world, blockPos).raycast(start, end, blockPos);
            return hitResult == null ? null : hitResult.getType();
        }, innerContext -> net.minecraft.util.hit.HitResult.Type.MISS);
    }

    /** Exposure raycast against the supplied factory, for the exact sweep. */
    public static BlockHitResult raycast(ExposureRaycastContext context, RaycastFactory raycastFactory) {
        return BlockView.raycast(context.start(), context.end(), context, raycastFactory, ctx -> null);
    }

    public static GameMode getGameMode(PlayerEntity player) {
        if (player == null || mc.getNetworkHandler() == null) {
            return null;
        }

        PlayerListEntry playerListEntry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
        return playerListEntry == null ? null : playerListEntry.getGameMode();
    }

    public static double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dX = x2 - x1;
        double dY = y2 - y1;
        double dZ = z2 - z1;
        return Math.sqrt(dX * dX + dY * dY + dZ * dZ);
    }

    /** Start and end of one exposure sample ray. */
    public record ExposureRaycastContext(Vec3d start, Vec3d end) {
    }

    @FunctionalInterface
    public interface RaycastFactory extends java.util.function.BiFunction<ExposureRaycastContext, BlockPos, BlockHitResult> {
    }
}
