package com.hongshikaikai.addon.mixin;

import com.hongshikaikai.addon.utils.GlassUi;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the HSKK-HUD glass on the HUD elements.
 *
 * <p>Unlike the screen renderer, {@code HudRenderer} has two independent overloads - the
 * one-colour one writes straight to {@code Renderer2D.COLOR} instead of delegating to the
 * four-colour one - so both are hooked. Everything else is the same as {@code GuiRendererMixin}.
 */
@Mixin(value = HudRenderer.class, remap = false)
public class HudRendererMixin {
    private static final String QUAD = "quad(DDDDLmeteordevelopment/meteorclient/utils/render/color/Color;)V";
    private static final String QUAD_GRADIENT = "quad(DDDDLmeteordevelopment/meteorclient/utils/render/color/Color;Lmeteordevelopment/meteorclient/utils/render/color/Color;Lmeteordevelopment/meteorclient/utils/render/color/Color;Lmeteordevelopment/meteorclient/utils/render/color/Color;)V";

    @Inject(method = QUAD, at = @At("HEAD"), cancellable = true)
    private void hskk$glassQuad(double x, double y, double width, double height, Color color, CallbackInfo info) {
        if (!GlassUi.shouldPaint(width, height, true)) return;

        GlassUi.paint((HudRenderer) (Object) this, x, y, width, height, color, color, color, color);
        info.cancel();
    }

    @Inject(method = QUAD_GRADIENT, at = @At("HEAD"), cancellable = true)
    private void hskk$glassGradientQuad(double x, double y, double width, double height,
                                        Color cTopLeft, Color cTopRight, Color cBottomRight, Color cBottomLeft,
                                        CallbackInfo info) {
        if (!GlassUi.shouldPaint(width, height, true)) return;

        GlassUi.paint((HudRenderer) (Object) this, x, y, width, height, cTopLeft, cTopRight, cBottomRight, cBottomLeft);
        info.cancel();
    }
}
