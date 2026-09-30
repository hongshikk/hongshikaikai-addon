package com.hongshikaikai.addon.utils;

import com.hongshikaikai.addon.modules.ElytraFlyPlus;
import com.hongshikaikai.addon.modules.KillAuraPlus;
import com.hongshikaikai.addon.modules.NoFallPlus;
import meteordevelopment.meteorclient.systems.modules.Modules;

/**
 * A module that owns the body rotation while the player keeps a different view.
 *
 * <p>The body rotation and the rendered camera are two different things, and the movement packet
 * only carries the first. A module that wants to aim, fly or steer by the body without dragging the
 * camera along therefore does not need a "silent" rotation - it writes the body rotation to the
 * player entity, which keeps the client physics, the movement packet and the server prediction in
 * agreement, and renders the camera from its own view instead.
 *
 * <p>That is what this interface exposes: the view the {@code CameraMixin} renders, plus the hook
 * the {@code EntityMixin} uses to keep raw mouse look out of the entity while the view is held.
 */
public interface FreeView {
    /** Whether this module currently owns the body rotation and is rendering its own view. */
    boolean isViewLockActive();

    /**
     * Mouse movement, in degrees, while the view is held. Called instead of the vanilla
     * {@code Entity.changeLookDirection} the mouse would otherwise perform.
     */
    void applyLookDelta(double deltaYaw, double deltaPitch);

    float getCameraYaw();

    float getCameraPitch();

    float getPrevCameraYaw();

    float getPrevCameraPitch();

    /**
     * The module currently holding the view, or null. Only one thing can own the body rotation at a
     * time; a save in progress takes precedence over a flight, and a flight over combat.
     */
    static FreeView active() {
        Modules modules = Modules.get();
        if (modules == null) return null;

        // NoFall+ outranks the flight module: while a save is being flown, the body rotation is
        // the module's - level for the cap, then the aim at the landing point - and the rotation
        // the player is looking with has to be the one rendered, or the camera would show a
        // direction the movement packet does not carry.
        NoFallPlus noFall = modules.get(NoFallPlus.class);
        if (noFall != null && noFall.isViewLockActive()) return noFall;

        ElytraFlyPlus elytra = modules.get(ElytraFlyPlus.class);
        if (elytra != null && elytra.isViewLockActive()) return elytra;

        KillAuraPlus aura = modules.get(KillAuraPlus.class);
        if (aura != null && aura.isViewLockActive()) return aura;

        return null;
    }
}
