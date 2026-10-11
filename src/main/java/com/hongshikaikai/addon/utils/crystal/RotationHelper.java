package com.hongshikaikai.addon.utils.crystal;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The silent-aim helper for the crystal modules.
 *
 * <h2>Why a look packet and not Meteor's {@code Rotations}</h2>
 * An attack or an interaction packet carries no rotation: the server resolves it against the last
 * rotation <em>it</em> received. This helper therefore writes its aim into a look packet and sends
 * that immediately before the interaction. Meteor's {@code Rotations} queues the rotation for the
 * next movement packet instead, which puts the interaction of the same tick ahead of the rotation
 * that is supposed to justify it and can also hand the view back mid-sequence.
 *
 * <p>So {@link #lookAt} sends a {@code LookAndOnGround} with the new aim straight away, and the
 * interaction that follows is resolved against it. The player's own camera is left alone - the
 * packet is silent - and the server keeps the aim because the client only sends a rotation when the
 * player's own yaw changes.
 *
 * <h2>Yaw stepping</h2>
 * {@link #injectStep} is the gradual turn: at most {@code 180 * steps} degrees of yaw and
 * {@code 90 * steps} of pitch per tick towards the target, which is what stops the aim from snapping
 * across the screen. The stepped value is compared against the aim that was last sent, because that
 * - not the player's camera - is the rotation the server currently believes.
 */
public final class RotationHelper implements Wrapper {
    /** Yaw of the last look packet sent here. */
    private static float serverYaw;
    /** Pitch of the last look packet sent here. */
    private static float serverPitch;
    private static boolean tracked;

    private RotationHelper() {
    }

    /** Drops the tracked aim back onto the player's own view; called when a module activates. */
    public static void reset() {
        tracked = mc.player != null;
        if (tracked) {
            serverYaw = mc.player.getYaw();
            serverPitch = mc.player.getPitch();
        }
    }

    private static float currentYaw() {
        if (!tracked) reset();
        return tracked ? serverYaw : 0.0f;
    }

    private static float currentPitch() {
        if (!tracked) reset();
        return tracked ? serverPitch : 0.0f;
    }

    /**
     * The yaw the server currently believes - the one on the last look packet sent from here.
     *
     * <p>The pearl module writes this value into the interaction packet instead of the player's own
     * camera angle, because with yaw stepping on the camera is deliberately <em>not</em> where the
     * server is being aimed.
     */
    public static float getLastYaw() {
        return currentYaw();
    }

    /** The pitch the server currently believes; see {@link #getLastYaw()}. */
    public static float getLastPitch() {
        return currentPitch();
    }

    /** Absolute yaw/pitch that would look at {@code vec} from the eye. */
    public static float[] getRotation(Vec3d vec) {
        Vec3d eye = mc.player.getEyePos();
        double dx = vec.x - eye.x;
        double dy = vec.y - eye.y;
        double dz = vec.z - eye.z;
        double diffXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, diffXZ));
        return new float[]{MathHelper.wrapDegrees(yaw), MathHelper.clamp(pitch, -90.0f, 90.0f)};
    }

    /** Aim straight at a point, telling the server about it at once. */
    public static void lookAt(Vec3d vec) {
        float[] angle = getRotation(vec);
        snapAt(angle[0], angle[1]);
    }

    /** Aim at the given face of a block. */
    public static void lookAt(BlockPos pos, Direction side) {
        Vec3d hitVec = pos.toCenterPos().add(side.getVector().getX() * 0.5, side.getVector().getY() * 0.5, side.getVector().getZ() * 0.5);
        lookAt(hitVec);
    }

    /**
     * Sends a look packet with an explicit rotation.
     *
     * <p>Used directly by the piston module, which has to make the server compute a specific block
     * facing from the player's rotation at the moment the piston is placed.
     */
    public static void snapAt(float yaw, float pitch) {
        serverYaw = MathHelper.wrapDegrees(yaw);
        serverPitch = MathHelper.clamp(pitch, -90.0f, 90.0f);
        tracked = true;

        if (mc.getNetworkHandler() != null && mc.player != null) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(serverYaw, serverPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
        }
    }

    /**
     * The stepped aim towards {@code vec}.
     *
     * @param steps fraction of a half-turn of yaw (and a quarter-turn of pitch) allowed this tick
     */
    public static float[] injectStep(Vec3d vec, float steps) {
        float currentYaw = currentYaw();
        float currentPitch = currentPitch();
        float[] target = getRotation(vec);
        float yawDelta = MathHelper.wrapDegrees(target[0] - currentYaw);
        float pitchDelta = target[1] - currentPitch;

        float yawStepVal = 180.0f * steps;
        float pitchStepVal = 90.0f * steps;
        float clampedYawDelta = MathHelper.clamp(Math.abs(yawDelta), -yawStepVal, yawStepVal);
        float clampedPitchDelta = MathHelper.clamp(pitchDelta, -pitchStepVal, pitchStepVal);
        float newYaw = currentYaw + (yawDelta > 0.0f ? clampedYawDelta : -clampedYawDelta);
        float newPitch = MathHelper.clamp(currentPitch + clampedPitchDelta, -90.0f, 90.0f);
        return new float[]{newYaw, newPitch};
    }

    /** Whether the aim the server currently holds is already inside {@code fov} degrees of {@code vec}. */
    public static boolean inFov(Vec3d vec, float fov) {
        float[] angle = getRotation(vec);
        float yawDifferent = Math.abs(MathHelper.wrapDegrees(angle[0] - currentYaw()));
        float pitchDifferent = Math.abs(angle[1] - currentPitch());
        return yawDifferent * yawDifferent + pitchDifferent * pitchDifferent <= fov * fov;
    }

    /**
     * Turns towards {@code vec}, either snapping or stepping, and reports whether the aim is close
     * enough to act on.
     *
     * <p>With yaw stepping on, the action is held back until the aim has come within {@code fov}
     * (unless the FOV check is switched off), which is what keeps a stepped turn from attacking
     * before it has arrived.
     */
    public static boolean faceVector(Vec3d vec, boolean yawStep, boolean checkFov, float fov, float steps) {
        if (vec == null) {
            return false;
        }

        if (!yawStep) {
            lookAt(vec);
            return true;
        }

        float[] stepped = injectStep(vec, steps);
        snapAt(stepped[0], stepped[1]);
        return inFov(vec, fov) || !checkFov;
    }
}
