package com.hongshikaikai.addon;

import com.hongshikaikai.addon.hud.ScaffoldBlocksHud;
import com.hongshikaikai.addon.modules.ElytraFlyPlus;
import com.hongshikaikai.addon.modules.KillAuraPlus;
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

    /** One group for the whole addon: ElytraFly+ and Scaffold+ both live here. */
    public static final Category CATEGORY = new Category("hongshikaikai");
    public static final HudGroup HUD_GROUP = new HudGroup("hongshikaikai");

    @Override
    public void onInitialize() {
        LOG.info("Initializing HongShiKaiKai (ElytraFly+, Scaffold+, Sprint+, KillAura+)");

        // Modules
        Modules.get().add(new ElytraFlyPlus());
        Modules.get().add(new ScaffoldPlus());
        Modules.get().add(new SprintPlus());
        Modules.get().add(new KillAuraPlus());

        // HUD
        Hud.get().register(ScaffoldBlocksHud.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
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
