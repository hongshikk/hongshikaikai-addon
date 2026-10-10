package com.hongshikaikai.addon.mixin;

import com.hongshikaikai.addon.modules.SmoothCamera;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders the camera at the {@link SmoothCamera} module's trailing position.
 *
 * <h2>Why the frame is filtered at the end of {@code Camera.update}</h2>
 * {@code Camera.update} works out where the camera should be and stores it, and everything drawn
 * after that - the world, the entities, the hand - is drawn from the stored position. The module
 * therefore wants the last word on that value, and the end of {@code update} is the one place that
 * has it for every frame.
 *
 * <p>Filtering the call that sets the position instead looks simpler, and Meteor's own camera mixin
 * does it: {@code setPos(double, double, double)} is the call the ordinary first- and third-person
 * path uses. It is not the only path, though. On every version this addon builds for, {@code update}
 * opens with a second branch - the camera of a player riding a vehicle - which sets its position
 * with the {@code Vec3d} overload instead and skips the three-double one entirely, and in third
 * person the position is then moved back off the eye by {@code moveBy}. Filtering one call would
 * leave the vehicle branch unfiltered and would smooth the eye rather than the position that is
 * actually rendered. Reading and writing the finished value covers all of it, once per frame.
 *
 * <p>Only the position is touched. The rotation is set by separate calls and is left alone, so the
 * camera keeps looking exactly where the player looks, and aim, attacks and every rotation module
 * are unaffected by this being on. Nothing here goes near the player entity either, so the client
 * physics, the movement packet and the server prediction all still see the untouched player: the
 * lag exists on screen and nowhere else.
 *
 * <h2>Why there is no view-ownership guard</h2>
 * LiquidBounce's original skips the filter while its free-look module is running. That guard is not
 * needed here: this addon's {@link com.hongshikaikai.addon.utils.FreeView} modules own the camera
 * <em>rotation</em> and never its position, so the two never write the same thing. A locked view
 * being rendered from a module's own rotation is not a reason to stop trailing the player
 * underneath it, and the modules do compose - the body can fly on a locked rotation while the
 * camera drifts along behind it.
 */
@Mixin(Camera.class)
public abstract class SmoothCameraMixin {
    @Shadow
    public abstract Vec3d getPos();

    @Shadow
    protected abstract void setPos(Vec3d pos);

    // 1.21.11 narrowed update's first parameter from BlockView to World (World is a BlockView, the
    // method just stopped widening it). Declaring the supertype and letting Mixin coerce the
    // argument keeps one source for 1.21.4-1.21.11: on the older versions the descriptor matches
    // exactly and the annotation does nothing, on 1.21.11 Mixin accepts the World argument for a
    // BlockView parameter. CanCoerce is a superclass check, so the direction only works this way
    // round. The parameters are unused - only the end of the method matters.
    @Inject(method = "update", at = @At("TAIL"))
    private void hskSmoothCameraPosition(@Coerce BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo info) {
        Modules modules = Modules.get();
        if (modules == null) return;

        SmoothCamera smoothCamera = modules.get(SmoothCamera.class);
        if (smoothCamera == null || !smoothCamera.isActive()) return;

        Vec3d pos = getPos();
        Vec3d smoothed = smoothCamera.cameraUpdate(pos);

        // The module hands the position straight back - first person with smoothing off, a
        // perspective change, the first frame - and in those cases the camera is already where it
        // belongs, so there is nothing to write.
        if (smoothed != pos) setPos(smoothed);
    }
}
