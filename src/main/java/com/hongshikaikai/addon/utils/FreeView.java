package com.hongshikaikai.addon.utils;

import com.hongshikaikai.addon.modules.Derp;
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
     * time; a save in progress takes precedence over a flight, a flight over combat, and all three
     * over a derp.
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

        Derp derp = modules.get(Derp.class);
        if (derp != null && derp.isViewLockActive()) return derp;

        return null;
    }

    /**
     * The held view that {@code contender} has to stand down for, or null when the body is free for
     * it to take.
     *
     * <p>This is {@link #active()} with the precedence applied, and it exists because the weakest
     * holder - Derp - is the one that holds the view the most: it takes the body rotation whenever
     * it is switched on and nothing stronger is using it. A module must therefore read the holder
     * as "who is above me" and not as "who is in the way": a combat aim has to be able to take the
     * body back from a derp mid-fight, and Derp, reading the same thing, has to let it.
     */
    static FreeView outranking(FreeView contender) {
        FreeView holder = active();
        if (holder == null || holder == contender) return null;

        return rank(holder) < rank(contender) ? holder : null;
    }

    /** Position in the precedence order, ascending: the smaller the number, the stronger the claim. */
    private static int rank(FreeView view) {
        if (view instanceof NoFallPlus) return 0;
        if (view instanceof ElytraFlyPlus) return 1;
        if (view instanceof KillAuraPlus) return 2;
        if (view instanceof Derp) return 3;

        return Integer.MAX_VALUE;
    }

    /**
     * The view another module is holding right now, or null when the entity rotation is the player's
     * own look.
     *
     * <p>This is what a module taking the body over starts its own view from. While another module
     * holds the view the mouse has been going to that view rather than to the entity, so the entity
     * rotation is the other module's output and starting from it would jump the camera to whatever
     * angle the other module happened to stop on. NoFall+ taking over a flight is the existing case;
     * combat or a flight taking over a derp is the same shape.
     */
    static FreeView heldByOther(FreeView contender) {
        FreeView holder = active();

        return holder == null || holder == contender ? null : holder;
    }
}
