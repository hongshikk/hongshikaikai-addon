package com.hongshikaikai.addon.utils.crystal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * {@link BlockPos} built from doubles.
 *
 * <p>It turns a float player position into the block cell the player's feet occupy without an
 * intermediate {@code floor} call at every use site; the {@code fix} variant nudges the height up
 * by 0.3 so a player standing on a block edge is attributed to the block they are standing on
 * rather than the one they are clipping into.
 */
public class BlockPosX extends BlockPos {
    public BlockPosX(double x, double y, double z) {
        super(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z));
    }

    public BlockPosX(double x, double y, double z, boolean fix) {
        this(x, y + (fix ? 0.3 : 0.0), z);
    }

    public BlockPosX(Vec3d vec3d) {
        this(vec3d.x, vec3d.y, vec3d.z);
    }

    public BlockPosX(Vec3d vec3d, boolean fix) {
        this(vec3d.x, vec3d.y, vec3d.z, fix);
    }
}
