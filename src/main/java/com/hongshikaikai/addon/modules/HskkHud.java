package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.GlassUi;
import meteordevelopment.meteorclient.events.render.RenderAfterWorldEvent;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

/**
 * Turns every Meteor screen and every HUD element into frosted glass.
 *
 * <p>The work itself is done in {@link GlassUi}, which the two mixins
 * ({@code GuiRendererMixin}, {@code HudRendererMixin}) call for each rectangle Meteor draws; this
 * module is the switch and the look. It is on by default, and it stays on across restarts without
 * ever fighting the user: {@link #applyDefaultOn()} runs once, after Meteor has loaded the config,
 * and the {@code internal-default-on} flag that it writes is what stops a later launch from turning
 * the module back on after it was switched off.
 *
 * <p>The one thing glass cannot fake is the blur behind it, and HSKK-HUD does its own rather than
 * leaning on anything else in the client: while a Meteor screen is open it asks Minecraft for the
 * blur post-effect the vanilla menus use ({@code GameRenderer.renderBlur}). Meteor's own {@code
 * Blur} module is never read, never switched on and never switched off by this module, so the two
 * can be enabled side by side - or the other one left off - without either noticing the other.
 *
 * <p>The blur runs from {@code RenderAfterWorldEvent}, the tail of the world render, which is
 * before the HUD and before any screen is drawn: only the world behind the glass is blurred, and
 * the HUD and the panels themselves stay sharp.
 */
public class HskkHud extends Module {
    /** The name the module is saved, listed and typed under. */
    public static final String NAME = "HSKK-HUD";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColors = settings.createGroup("Colors");
    private final SettingGroup sgBlur = settings.createGroup("Blur");

    // General

    public final Setting<Boolean> screens = sgGeneral.add(new BoolSetting.Builder()
        .name("screens")
        .description("Skin every Meteor screen: the ClickGUI, module settings and the HUD editor.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> hud = sgGeneral.add(new BoolSetting.Builder()
        .name("hud")
        .description("Skin the HUD elements as well.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> cornerRadius = sgGeneral.add(new DoubleSetting.Builder()
        .name("corner-radius")
        .description("How round the corners of a panel are, in GUI pixels.")
        .defaultValue(8)
        .min(0)
        .sliderRange(0, 16)
        .build()
    );

    public final Setting<Double> minPanelSize = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-panel-size")
        .description("Rectangles thinner than this stay exactly as Meteor drew them, so separators, scrollbars and the accent bar of an active module keep their shape.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 24)
        .build()
    );

    public final Setting<Double> glassOpacity = sgGeneral.add(new DoubleSetting.Builder()
        .name("glass-opacity")
        .description("How much of a panel's own opacity survives. Lower lets more of the blurred world through.")
        .defaultValue(0.75)
        .min(0.05)
        .sliderRange(0.05, 1)
        .build()
    );

    public final Setting<Double> frost = sgGeneral.add(new DoubleSetting.Builder()
        .name("frost")
        .description("How far every panel colour is pushed towards white.")
        .defaultValue(0.04)
        .min(0)
        .sliderRange(0, 1)
        .build()
    );

    public final Setting<Double> tintStrength = sgGeneral.add(new DoubleSetting.Builder()
        .name("tint-strength")
        .description("How far every panel colour is pulled towards the tint colour.")
        .defaultValue(0.14)
        .min(0)
        .sliderRange(0, 1)
        .build()
    );

    public final Setting<Boolean> border = sgGeneral.add(new BoolSetting.Builder()
        .name("border")
        .description("Draw a one-pixel light edge around the large panels - windows and big buttons.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> sheen = sgGeneral.add(new BoolSetting.Builder()
        .name("sheen")
        .description("Draw the highlight that fades out over the top of a panel.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Draw a soft drop shadow under the large panels.")
        .defaultValue(true)
        .build()
    );

    // Colors

    public final Setting<SettingColor> tintColor = sgColors.add(new ColorSetting.Builder()
        .name("tint")
        .description("The colour panel backgrounds are pulled towards.")
        .defaultValue(new SettingColor(235, 240, 250))
        .build()
    );

    public final Setting<SettingColor> borderColor = sgColors.add(new ColorSetting.Builder()
        .name("border-color")
        .description("Colour of the light edge around the large panels.")
        .defaultValue(new SettingColor(255, 255, 255, 90))
        .build()
    );

    public final Setting<SettingColor> sheenColor = sgColors.add(new ColorSetting.Builder()
        .name("sheen-color")
        .description("Colour of the highlight on top of a panel.")
        .defaultValue(new SettingColor(255, 255, 255, 55))
        .build()
    );

    public final Setting<SettingColor> shadowColor = sgColors.add(new ColorSetting.Builder()
        .name("shadow-color")
        .description("Colour of the drop shadow under the large panels.")
        .defaultValue(new SettingColor(0, 0, 0, 70))
        .build()
    );

    // Blur

    public final Setting<Boolean> blur = sgBlur.add(new BoolSetting.Builder()
        .name("blur")
        .description("Blur the world behind a Meteor screen, with Minecraft's own menu blur. Honours the vanilla 'Menu Background Blurriness' video setting, so a player who set that to 0 keeps a sharp world and still gets the glass.")
        .defaultValue(true)
        .build()
    );

    // Internal

    private final Setting<Boolean> defaultOnApplied = sgGeneral.add(new BoolSetting.Builder()
        .name("internal-default-on")
        .description("Set once HSKK-HUD has made itself default-on, so switching it off stays switched off.")
        .defaultValue(false)
        .visible(() -> false)
        .build()
    );

    public HskkHud() {
        super(HongShiKaiKai.CATEGORY_TOOLS, NAME, "Apple-style frosted glass skin for every Meteor screen and HUD element.");
    }

    /**
     * Frosts the world behind a Meteor screen.
     *
     * <p>This is the tail of the world render - before the HUD, before the screen itself - so the
     * blur only lands on the world, and the HUD and the panels stay sharp on top of it. Only Meteor
     * screens are handled: every other screen already goes through vanilla's own background, blur
     * included, and blurring the world while no screen is open would just be a blurry game.
     *
     * <p>Nothing here touches Meteor's {@code Blur} module. Both can be on at once; the result is
     * simply a stronger blur, and neither module switches the other one.
     */
    @EventHandler
    private void onRenderAfterWorld(RenderAfterWorldEvent event) {
        if (!blur.get()) return;
        if (!(mc.currentScreen instanceof WidgetScreen)) return;

        mc.gameRenderer.renderBlur();
    }

    /**
     * Makes HSKK-HUD default-on, once.
     *
     * <p>It cannot simply be switched on in {@code onInitialize}: Meteor loads the saved config
     * afterwards, and loading it starts with {@code Modules.disableAll()}, which would switch a
     * module the config does not mention straight back off. So the flag is remembered in the
     * module's own settings and checked here - after the config is loaded - which is what makes the
     * first launch with HSKK-HUD installed turn it on and every launch after that respect whatever
     * the user chose.
     */
    public static void applyDefaultOn() {
        Modules modules = Modules.get();
        if (modules == null) return;

        HskkHud module = modules.get(HskkHud.class);
        if (module == null || module.defaultOnApplied.get()) return;

        module.defaultOnApplied.set(true);
        if (!module.isActive()) module.toggle();
    }
}
