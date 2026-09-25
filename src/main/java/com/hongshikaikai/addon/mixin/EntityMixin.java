package com.hongshikaikai.addon.mixin;

import com.hongshikaikai.addon.utils.FreeView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps raw mouse look out of the player entity rotation while the module owns it.
 *
 * Mouse.updateMouse calls changeLookDirection on the client player on every mouse move. While
 * look-control is flying, that call would fight the locked rotation - and the entity rotation is
 * the single value the physics, the vanilla movement packet and the server prediction all share,
 * so letting the mouse write it would break that agreement mid-tick.
 *
 * The delta is forwarded to the module's own view instead, which is what the camera mixin
 * renders. The entity rotation is then written by the module once per tick, before the player
 * tick runs, so the packet for that tick carries exactly the rotation the client flew with.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void hskCaptureView(double cursorDeltaX, double cursorDeltaY, CallbackInfo info) {
        if ((Object) this != MinecraftClient.getInstance().player) return;

        FreeView view = FreeView.active();
        if (view == null) return;

        // Same 0.15 factor vanilla applies inside changeLookDirection.
        view.applyLookDelta(cursorDeltaX * 0.15, cursorDeltaY * 0.15);
        info.cancel();
    }
}
