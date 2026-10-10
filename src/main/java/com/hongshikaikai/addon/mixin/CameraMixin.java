package com.hongshikaikai.addon.mixin;

import com.hongshikaikai.addon.utils.FreeView;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Renders the camera from the module's own view instead of the player entity rotation.
 *
 * While look-control is flying, the player entity carries the LOCKED flight rotation - that is
 * what the physics use, what the vanilla movement packet carries and what the server predicts
 * with, so all three agree and nothing looks like a spoof. The camera, however, must not follow
 * it: the keys choose a direction relative to where the player is looking, so if the camera moved
 * with the body the reference frame would feed back on itself after one tick.
 *
 * Vanilla builds the camera rotation from the focused entity, so the only way to render a
 * different view is to replace the two floats handed to Camera.setRotation. This is rendering
 * only - nothing here touches the entity or anything that is sent to the server.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Unique
    private float hskTickDelta;

    // 1.21.11 narrowed Camera.update's first parameter from BlockView to World (a BlockView, the
    // method just stopped widening it). Declaring the supertype and letting Mixin coerce the
    // argument keeps one source for 1.21.4-1.21.11: on the older versions the descriptor matches
    // exactly and the annotation does nothing, on 1.21.11 Mixin accepts the World argument for a
    // BlockView parameter. CanCoerce is a superclass check, so the direction only works this way
    // round. The parameter itself is unused - only tickDelta is captured.
    @Inject(method = "update", at = @At("HEAD"))
    private void hskCaptureTickDelta(@Coerce BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo info) {
        hskTickDelta = tickDelta;
    }

    @ModifyArgs(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V"))
    private void hskRenderFreeView(Args args) {
        FreeView view = FreeView.active();
        if (view == null) return;

        // Interpolate exactly like vanilla interpolates the entity rotation, so the free view
        // stays smooth even though the locked rotation is only recomputed once per tick.
        float delta = hskTickDelta;
        args.set(0, MathHelper.lerpAngleDegrees(delta, view.getPrevCameraYaw(), view.getCameraYaw()));
        args.set(1, MathHelper.lerp(delta, view.getPrevCameraPitch(), view.getCameraPitch()));
    }
}
