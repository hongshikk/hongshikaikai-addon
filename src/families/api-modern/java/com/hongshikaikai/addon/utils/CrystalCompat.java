package com.hongshikaikai.addon.utils;

import meteordevelopment.meteorclient.systems.modules.world.PacketMine;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Meteor/Minecraft members the crystal modules need that moved between 1.21.4 and 1.21.11.
 *
 * <p>This is the {@code api-modern} generation: 1.21.11 and later.
 *
 * <ul>
 *     <li>{@code PacketMine.MyBlock.progress()} is the method form introduced in 1.21.8.</li>
 *     <li>{@code resetLastAttackedTicks()} was renamed to {@code resetTicksSinceLastAttack()} when
 *         1.21.11 reworked attack timing ({@code lastAttackedTicks} became
 *         {@code ticksSinceLastAttack}).</li>
 * </ul>
 */
public final class CrystalCompat {
    private CrystalCompat() {
    }

    /** How far through a pending PacketMine break is, as a fraction of one break. */
    public static double progress(PacketMine.MyBlock block) {
        return block.progress();
    }

    /** Clears the attack cooldown, so a swing that follows is not slowed by the one before it. */
    public static void resetAttackCooldown(PlayerEntity player) {
        player.resetTicksSinceLastAttack();
    }
}
