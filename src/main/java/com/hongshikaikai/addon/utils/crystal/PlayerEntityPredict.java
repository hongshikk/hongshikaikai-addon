package com.hongshikaikai.addon.utils.crystal;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * A player plus where the simulation thinks they will be.
 *
 * <p>The predicted state is carried as a position and a moved hitbox alongside the real player,
 * which stays the source of the equipment - no cloned entity that would have to be kept in step
 * with the player class across Minecraft versions.
 */
public class PlayerEntityPredict {
    public final PlayerEntity player;
    /** Where the player is predicted to be, or the real position when no prediction was asked for. */
    public final Vec3d pos;
    /** The player's hitbox moved to {@link #pos}. */
    public final Box box;

    public PlayerEntityPredict(
        PlayerEntity player, double maxMotionY, int ticks, int simulation, boolean step, boolean doubleStep, boolean jump, boolean inBlockPause
    ) {
        this.player = player;

        if (ticks > 0) {
            this.pos = PredictUtil.getPos(player, maxMotionY, ticks, simulation, step, doubleStep, jump, inBlockPause);
        } else {
            this.pos = EntityUtil.pos(player);
        }

        this.box = player.getBoundingBox().offset(this.pos.subtract(EntityUtil.pos(player)));
    }
}
