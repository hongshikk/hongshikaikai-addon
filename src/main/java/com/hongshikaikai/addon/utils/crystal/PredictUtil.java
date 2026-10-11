package com.hongshikaikai.addon.utils.crystal;

import com.hongshikaikai.addon.utils.VersionCompat;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Where a player will be in a few ticks.
 *
 * <p>The simulation steps the recorded motion forward one tick at a time and, for each step, asks
 * whether the resulting hitbox fits in the world. Because a whole 0.05-block step is often blocked
 * while some fraction of it is not, the inner loops sweep a grid of {@code simulation + 1} fractions
 * along each axis and take the fractional step that fits, which is what lets the prediction walk
 * through doorways and slide along walls instead of stopping dead at the first obstruction.
 *
 * <p>The motion mode is position deltas, clamped at {@code maxMotionY}.
 */
public final class PredictUtil implements Wrapper {
    private PredictUtil() {
    }

    public static Vec3d getPos(
        PlayerEntity e, double maxMotionY, int ticks, int simulation, boolean step, boolean doubleStep, boolean jump, boolean inBlockPause
    ) {
        if (inBlockPause && BlockUtil.canCollide(e, e.getBoundingBox())) {
            return EntityUtil.pos(e);
        }

        // Position deltas rather than the velocity vector: it is what actually moved, and it is what
        // the server saw move.
        double velocityX = e.getX() - VersionCompat.getLastX(e);
        double velocityY = e.getY() - VersionCompat.getLastY(e);
        double velocityZ = e.getZ() - VersionCompat.getLastZ(e);
        if (velocityY > maxMotionY) {
            velocityY = maxMotionY;
        }

        double motionX = velocityX;
        double motionY = velocityY;
        double motionZ = velocityZ;
        double x = e.getX();
        double y = e.getY();
        double z = e.getZ();
        Vec3d lastPos = new Vec3d(x, y, z);

        if (velocityX == 0.0 && velocityY == 0.0 && velocityZ == 0.0) {
            return lastPos;
        }

        for (int i = 0; i < ticks; i++) {
            lastPos = new Vec3d(x, y, z);
            boolean move = false;
            boolean fall = false;
            int yTime = simulation;

            while (yTime >= 0 && !move) {
                int xTime = simulation;
                while (xTime >= 0) {
                    double xFactor = (double) xTime / simulation;
                    double yFactor = (double) yTime / simulation;

                    if (canMove(lastPos.add(motionX * xFactor, motionY * yFactor, motionZ * xFactor), e)) {
                        if (Math.abs(motionX * xFactor) + Math.abs(motionZ * xFactor) + Math.abs(motionY * yFactor) <= 0.05) {
                            // The step barely moves: try to walk up a block, then two, before giving up.
                            if (step && !canMove(lastPos.add(velocityX, 0.0, velocityZ), e) && canMove(lastPos.add(velocityX, 1.1, velocityZ), e)) {
                                y++;
                                motionY = 0.03;

                                double[] swept = sweep(lastPos, e, motionX, motionY, motionZ, simulation);
                                if (swept == null) return lastPos;

                                move = true;
                                x += swept[0];
                                z += swept[1];
                                y += swept[2];
                                fall = swept[3] > 0.0;
                            } else if (doubleStep && canMove(lastPos.add(velocityX, 0.0, velocityZ), e) && canMove(lastPos.add(velocityX, 2.1, velocityZ), e)) {
                                y += 2.05;
                                motionY = 0.03;

                                double[] swept = sweep(lastPos, e, motionX, motionY, motionZ, simulation);
                                if (swept == null) return lastPos;

                                move = true;
                                x += swept[0];
                                z += swept[1];
                                y += swept[2];
                                fall = swept[3] > 0.0;
                            } else {
                                return lastPos;
                            }
                        } else {
                            move = true;
                            x += motionX * xFactor;
                            z += motionZ * xFactor;
                            if (yTime > 0) {
                                y += motionY * yFactor;
                                fall = true;
                            }
                        }

                        break;
                    }

                    xTime--;
                }

                if (!move) yTime--;
            }

            if (!move) {
                return lastPos;
            }

            if (!e.isGliding()) {
                motionX *= 0.99;
                motionZ *= 0.99;
                motionY *= 0.99;
                motionY -= 0.05f;
            }

            if (!fall) {
                if (e.isOnGround()) {
                    motionX = velocityX;
                    motionZ = velocityZ;
                    motionY = 0.0;
                } else if (jump) {
                    motionX = velocityX;
                    motionZ = velocityZ;
                    motionY = 0.333;
                } else {
                    motionY = 0.0;
                }
            }
        }

        return lastPos;
    }

    /**
     * The fractional sweep the step-up branches use: the largest grid step that fits, or null when
     * none does.
     *
     * @return {@code {dx, dz, dy, fallFlag}} of the accepted step
     */
    private static double[] sweep(Vec3d lastPos, PlayerEntity e, double motionX, double motionY, double motionZ, int simulation) {
        for (int yTime = simulation; yTime >= 0; yTime--) {
            for (int xTime = simulation; xTime >= 0; xTime--) {
                double xFactor = (double) xTime / simulation;
                double yFactor = (double) yTime / simulation;
                if (canMove(lastPos.add(motionX * xFactor, motionY * yFactor, motionZ * xFactor), e)) {
                    return new double[]{
                        motionX * xFactor,
                        motionZ * xFactor,
                        yTime > 0 ? motionY * yFactor : 0.0,
                        yTime > 0 ? 1.0 : 0.0
                    };
                }
            }
        }

        return null;
    }

    /**
     * Whether the player's hitbox at {@code pos} fits in the world.
     *
     * <p>The second half of the test is an escape hatch: standing inside the block cell that already
     * contains the player is never treated as a collision, so a prediction that starts in a block
     * does not immediately freeze.
     */
    public static boolean canMove(Vec3d pos, PlayerEntity player) {
        return !mc.world.canCollide(player, player.getDimensions(player.getPose()).getBoxAt(pos))
            || new Box(new BlockPosX(pos)).intersects(player.getBoundingBox());
    }
}
