package com.hongshikaikai.addon.hud;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.modules.ScaffoldPlus;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

/**
 * How many blocks Scaffold+ has left.
 *
 * <p>Counted with the module's own block list and filter rather than a plain "is a block" test, so
 * the number is what the module could actually place. Only the hotbar is counted, because that is
 * all the module can reach: if this reaches zero it stops placing, whatever else is in the
 * inventory.
 *
 * <p>Nothing is rendered in game while Scaffold+ is off, and the element collapses to no size so it
 * leaves no empty gap in the HUD either. The one exception is the HUD editor: the element is drawn
 * there whatever the module is doing, because a collapsed element is one you cannot find while
 * placing it.
 */
public class ScaffoldBlocksHud extends HudElement {
    public static final HudElementInfo<ScaffoldBlocksHud> INFO = new HudElementInfo<>(
        HongShiKaiKai.HUD_GROUP,
        "scaffold-blocks",
        "Blocks left for Scaffold+, counted with the module's own block filter.",
        ScaffoldBlocksHud::new
    );

    private static final double PADDING = 2.0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> showTotal = sgGeneral.add(new BoolSetting.Builder()
        .name("show-total")
        .description("Show the rest of the inventory too, as hotbar / total.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> lowBlocks = sgGeneral.add(new IntSetting.Builder()
        .name("low-blocks")
        .description("Colour the count as a warning at or below this many hotbar blocks. 0 disables the warning.")
        .defaultValue(16)
        .min(0)
        .sliderRange(0, 64)
        .build()
    );

    private final Setting<Boolean> hideWhenInactive = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-when-inactive")
        .description("Hide the element while Scaffold+ is off.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> textColor = sgGeneral.add(new ColorSetting.Builder()
        .name("text-color")
        .description("Colour of the count.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> lowColor = sgGeneral.add(new ColorSetting.Builder()
        .name("low-color")
        .description("Colour of the count once it is at or below low-blocks.")
        .defaultValue(new SettingColor(255, 80, 80))
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Colour behind the text.")
        .defaultValue(new SettingColor(0, 0, 0, 100))
        .build()
    );

    public ScaffoldBlocksHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        ScaffoldPlus module = Modules.get().get(ScaffoldPlus.class);

        // The editor draws the element whatever Scaffold+ is doing. Nothing is being placed there,
        // and a hidden element collapses to a 0x0 box that cannot be found, let alone positioned -
        // which is exactly what adding the element and then looking for it used to do.
        boolean editor = isInEditor();

        if (module == null || (hideWhenInactive.get() && !module.isActive() && !editor)) {
            setSize(0, 0);
            return;
        }

        int hotbar = module.countBlocks(false);
        int total = module.countBlocks(true);

        String text = showTotal.get()
            ? hotbar + " / " + total
            : Integer.toString(hotbar);

        Color color = lowBlocks.get() > 0 && hotbar <= lowBlocks.get() ? lowColor.get() : textColor.get();

        double width = renderer.textWidth(text, true) + PADDING * 2;
        double height = renderer.textHeight(true) + PADDING * 2;
        setSize(width, height);

        renderer.quad(x, y, width, height, backgroundColor.get());
        renderer.text(text, x + PADDING, y + PADDING, color, true);
    }
}
