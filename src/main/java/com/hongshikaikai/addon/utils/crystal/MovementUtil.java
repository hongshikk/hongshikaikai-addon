package com.hongshikaikai.addon.utils.crystal;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import net.minecraft.util.math.Vec2f;

/** Movement helpers used by the crystal modules. */
public final class MovementUtil implements Wrapper {
    private MovementUtil() {
    }

    /**
     * Whether the player is asking to move this tick.
     *
     * <p>Read from the input vector rather than the {@code movementForward}/{@code movementSideways}
     * fields directly: those two became private in 1.21.5, while {@code getMovementInput()} exists -
     * and returns the same pair - on every version this addon builds for.
     */
    public static boolean isMoving() {
        Vec2f input = mc.player.input.getMovementInput();
        return input.x != 0.0f || input.y != 0.0f;
    }

    public static boolean isStatic() {
        return mc.player.getVelocity().getX() == 0.0 && mc.player.isOnGround() && mc.player.getVelocity().getZ() == 0.0;
    }

    /**
     * Whether the player is riding a Meteor elytra fly, which the crystal rotation has to stand down
     * for because the flight owns the rotation.
     */
    public static boolean elytraFlying() {
        return Modules.get().isActive(ElytraFly.class) && mc.player.isGliding();
    }
}
