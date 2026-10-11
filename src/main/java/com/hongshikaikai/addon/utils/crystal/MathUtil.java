package com.hongshikaikai.addon.utils.crystal;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Geometry helpers used by the crystal logic. */
public final class MathUtil {
    private MathUtil() {
    }

    public static float clamp(float num, float min, float max) {
        return num < min ? min : Math.min(num, max);
    }

    public static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    public static float random(float min, float max) {
        return (float) (Math.random() * (max - min) + min);
    }

    public static double interpolate(double previous, double current, double delta) {
        return previous + (current - previous) * delta;
    }

    public static float interpolate(float previous, float current, float delta) {
        return previous + (current - previous) * delta;
    }

    /**
     * Picks the point on a {@code boxSize} square column standing on {@code bottom} that is closest to
     * the eye while still inside {@code range}.
     *
     * <p>This is the "attack vector": instead of aiming at the crystal's centre, the
     * module aims at the nearest reachable point of its hitbox, which is what makes a crystal behind
     * a lip or under a ceiling still attackable. The search starts at the bottom and stops as soon as
     * it would have to look above the eye, so the answer is always a point at or below eye level.
     */
    public static Vec3d getPointToBoxFromBottom(Vec3d eye, Vec3d bottom, double range, double boxSize, double step) {
        Vec3d target = null;
        double halfWidth = boxSize / 2.0;
        double minOffset = Double.MAX_VALUE;
        boolean xPlus = bottom.x < eye.x;
        boolean zPlus = bottom.z < eye.z;
        double rangeSq = range * range;
        double yOffset = 0.0;

        while (yOffset <= boxSize) {
            for (double xOffset = 0.0; xOffset <= halfWidth; xOffset += step) {
                for (double zOffset = 0.0; zOffset <= halfWidth; zOffset += step) {
                    double y = bottom.y + yOffset;
                    if (yOffset != 0.0 && y > eye.y) {
                        return target;
                    }

                    double x = bottom.x + (xPlus ? xOffset : -xOffset);
                    double z = bottom.z + (zPlus ? zOffset : -zOffset);
                    double dxToEye = x - eye.x;
                    double dyToEye = y - eye.y;
                    double dzToEye = z - eye.z;
                    double distSq = dxToEye * dxToEye + dyToEye * dyToEye + dzToEye * dzToEye;
                    double offsets = xOffset + yOffset + zOffset;
                    if (distSq <= rangeSq && offsets < minOffset) {
                        minOffset = offsets;
                        target = new Vec3d(x, y, z);
                    }
                }
            }

            yOffset += step;
        }

        return target;
    }

    public static Vec3d getClosestPointToBox(Vec3d pos, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double closestX = Math.max(minX, Math.min(pos.x, maxX));
        double closestY = Math.max(minY, Math.min(pos.y, maxY));
        double closestZ = Math.max(minZ, Math.min(pos.z, maxZ));
        return new Vec3d(closestX, closestY, closestZ);
    }

    public static Vec3d getClosestPointToBox(Vec3d pos, Box boundingBox) {
        return getClosestPointToBox(pos, boundingBox.minX, boundingBox.minY, boundingBox.minZ, boundingBox.maxX, boundingBox.maxY, boundingBox.maxZ);
    }

    public static Vec3d getClosestPoint(Entity entity) {
        return getClosestPointToBox(Wrapper.mc.player.getEyePos(), entity.getBoundingBox());
    }

    public static double distance(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static double distance(BlockPos from, Vec3d to) {
        return distance(from.toCenterPos(), to);
    }

    public static float getHealth(LivingEntity entity) {
        return entity.getHealth() + entity.getAbsorptionAmount();
    }
}
