package com.hongshikaikai.addon;

import com.hongshikaikai.addon.hud.ScaffoldBlocksHud;
import com.hongshikaikai.addon.modules.ChatCopy;
import com.hongshikaikai.addon.modules.ElytraFlyPlus;
import com.hongshikaikai.addon.modules.KillAuraPlus;
import com.hongshikaikai.addon.modules.NoFallPlus;
import com.hongshikaikai.addon.modules.ScaffoldPlus;
import com.hongshikaikai.addon.modules.SprintPlus;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class HongShiKaiKai extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    /** The combat/movement modules: ElytraFly+, Scaffold+, Sprint+, KillAura+ and NoFall+. */
    public static final Category CATEGORY = new Category("hongshikaikai");
    /** Utility modules that are neither combat nor movement; ChatCopy lives here. */
    public static final Category CATEGORY_TOOLS = new Category("hskk-tools");
    public static final HudGroup HUD_GROUP = new HudGroup("hongshikaikai");

    @Override
    public void onInitialize() {
        LOG.info("Initializing HongShiKaiKai (ElytraFly+, Scaffold+, Sprint+, KillAura+, NoFall+, ChatCopy)");

        // Modules
        Modules.get().add(new ElytraFlyPlus());
        Modules.get().add(new ScaffoldPlus());
        Modules.get().add(new SprintPlus());
        Modules.get().add(new KillAuraPlus());
        Modules.get().add(new NoFallPlus());
        Modules.get().add(new ChatCopy());

        // HUD
        Hud.get().register(ScaffoldBlocksHud.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
        Modules.registerCategory(CATEGORY_TOOLS);
    }

    @Override
    public String getPackage() {
        return "com.hongshikaikai.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("hongshikaikai", "HongShiKaiKai");
    }
}
