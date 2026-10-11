package com.hongshikaikai.addon.mixin;

import com.hongshikaikai.addon.utils.GlassUi;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the HSKK-HUD glass on every rectangle a Meteor screen draws.
 *
 * <p>Every widget in the ClickGUI - windows, the module list, buttons, settings, the HUD editor -
 * ends up in the four-colour {@code quad} overload: the one-, two- and four-colour overloads and the
 * widget one all delegate to it. So this single injection is the whole screen side of the skin, and
 * a widget added by a later Meteor version is skinned without this addon knowing it exists.
 *
 * <p>{@code GlassUi.paint} answers {@code false} from {@link GlassUi#painting()} while it is drawing
 * its own panels, which is what keeps the injection from calling itself forever; the panels it draws
 * fall through to the original body.
 */
@Mixin(value = GuiRenderer.class, remap = false)
public class GuiRendererMixin {
    private static final String QUAD = "quad(DDDDLmeteordevelopment/meteorclient/utils/render/color/Color;Lmeteordevelopment/meteorclient/utils/render/color/Color;Lmeteordevelopment/meteorclient/utils/render/color/Color;Lmeteordevelopment/meteorclient/utils/render/color/Color;)V";

    @Inject(method = QUAD, at = @At("HEAD"), cancellable = true)
    private void hskk$glassQuad(double x, double y, double width, double height,
                                Color cTopLeft, Color cTopRight, Color cBottomRight, Color cBottomLeft,
                                CallbackInfo info) {
        if (!GlassUi.shouldPaint(width, height, false)) return;

        GlassUi.paint((GuiRenderer) (Object) this, x, y, width, height, cTopLeft, cTopRight, cBottomRight, cBottomLeft);
        info.cancel();
    }
}
