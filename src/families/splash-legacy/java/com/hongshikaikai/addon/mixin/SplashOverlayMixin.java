package com.hongshikaikai.addon.mixin;

import java.util.function.Function;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.SplashOverlay;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Puts the addon's name on the loading screen where Mojang's wordmark normally goes.
 *
 * <p>SplashOverlay paints its logo as two texture halves: one drawTexture call for the left half
 * and one for the right, both cut from textures/gui/title/mojangstudios.png and both tinted white
 * over the brand-coloured fill behind them. Replacing the wordmark therefore means dropping both
 * calls and drawing one word in the box they share - the first hook draws it, the second swallows
 * the other half.
 *
 * <p>A resource pack cannot do this, which is worth knowing before trying: SplashOverlay.LogoTexture
 * reads that PNG straight out of the client's DefaultResourcePack, so a mod-supplied copy of
 * textures/gui/title/mojangstudios.png is never consulted. Nothing here touches the background
 * fill, the progress bar or the fade - the word simply inherits the alpha vanilla hands the draw
 * call, so it fades in and out with the rest of the splash.
 *
 * <h2>Why this is a wrap and not a redirect</h2>
 * Puzzle draws its own splash logo by wrapping these exact two calls and, when its
 * {@code resourcepackSplashScreen} option is on, never passes them on - so anything that only
 * redirects them is silently skipped and "hskk" never appears. A {@code @Redirect} cannot be
 * chained and loses that argument; {@code @WrapOperation} chains, with the mixin applied last
 * holding the outermost position and deciding whether the ones under it run at all. The priority
 * below is what puts this mixin there: Puzzle declares 2000 for its splash mixin, and mixins are
 * applied in ascending priority order, so anything above 2000 wraps it rather than the other way
 * round.
 *
 * <p>The same rule is why the wordmark is drawn here rather than handed to the wrapped call: the
 * point of the hook is to take Mojang's half off the screen, and letting the call through would put
 * it back.
 */
@Mixin(value = SplashOverlay.class, priority = 2500)
public abstract class SplashOverlayMixin {
    /** What the loading screen says instead of "MOJANG". */
    private static final String HSK_BRAND = "hskk";

    /**
     * Height of the wordmark's capital line in the vanilla logo, measured from the top of the "M"
     * down to its baseline. The vanilla texture is 1024x256 and that line fills rows 8..163 of it,
     * so the replacement is scaled to the same fraction of the box to keep the same weight.
     */
    private static final float HSK_CAP_HEIGHT_RATIO = 156.0f / 256.0f;

    /**
     * Rows a glyph with an ascender ("h", "k") fills in the default font's 8 px cell. The cell's
     * leading is what makes TextRenderer.fontHeight 9, so this - not fontHeight - is the ink the
     * cap height has to match.
     */
    private static final float HSK_ASCENDER_HEIGHT = 7.0f;

    @WrapOperation(
        method = "render(Lnet/minecraft/client/gui/DrawContext;IIF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Ljava/util/function/Function;Lnet/minecraft/util/Identifier;IIFFIIIIIII)V",
            ordinal = 0
        )
    )
    private void hskDrawBrand(DrawContext context, Function<Identifier, RenderLayer> renderLayers, Identifier texture,
                              int x, int y, float u, float v, int width, int height,
                              int regionWidth, int regionHeight, int textureWidth, int textureHeight, int color,
                              Operation<Void> original) {
        // Vanilla passes the fade as ColorHelper.getWhite(alpha); it is the only part of the
        // colour that varies, since the wordmark itself is always white.
        int alpha = color >>> 24;
        if (alpha <= 0) return;

        // The call being replaced is the left half, so the full box is twice as wide and its
        // centre sits on the edge shared with the right half - the middle of the screen.
        int centerX = x + width;
        int centerY = y + height / 2;

        float scale = height * HSK_CAP_HEIGHT_RATIO / HSK_ASCENDER_HEIGHT;
        if (scale <= 0.0f) return;

        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        MatrixStack matrices = context.getMatrices();

        matrices.push();
        // Place the text's top-left in whole screen pixels and scale about that corner, so the
        // glyphs cannot drift by a scaled-up rounding error.
        matrices.translate(
            centerX - font.getWidth(HSK_BRAND) * scale / 2.0f,
            centerY - font.fontHeight * scale / 2.0f,
            0.0f
        );
        matrices.scale(scale, scale, 1.0f);
        context.drawText(font, HSK_BRAND, 0, 0, alpha << 24 | 0xFFFFFF, false);
        matrices.pop();
    }

    /**
     * The right half of the wordmark. The brand is already drawn once by the left-half hook, so
     * this one only has to swallow the second texture call rather than paint Mojang's half over it.
     */
    @WrapOperation(
        method = "render(Lnet/minecraft/client/gui/DrawContext;IIF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Ljava/util/function/Function;Lnet/minecraft/util/Identifier;IIFFIIIIIII)V",
            ordinal = 1
        )
    )
    private void hskDropBrandRightHalf(DrawContext context, Function<Identifier, RenderLayer> renderLayers,
                                       Identifier texture, int x, int y, float u, float v, int width, int height,
                                       int regionWidth, int regionHeight, int textureWidth, int textureHeight, int color,
                                       Operation<Void> original) {
    }
}
