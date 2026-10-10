package com.hongshikaikai.addon;

import com.hongshikaikai.addon.hud.ScaffoldBlocksHud;
import com.hongshikaikai.addon.modules.BlockIn;
import com.hongshikaikai.addon.modules.ChatCopy;
import com.hongshikaikai.addon.modules.Derp;
import com.hongshikaikai.addon.modules.DirectionControl;
import com.hongshikaikai.addon.modules.ElytraFlyPlus;
import com.hongshikaikai.addon.modules.FakeLag;
import com.hongshikaikai.addon.modules.HandDerp;
import com.hongshikaikai.addon.modules.KillAuraPlus;
import com.hongshikaikai.addon.modules.NoFallPlus;
import com.hongshikaikai.addon.modules.ScaffoldPlus;
import com.hongshikaikai.addon.modules.SmoothCamera;
import com.hongshikaikai.addon.modules.SprintPlus;
import com.hongshikaikai.addon.modules.TpAuraPlus;
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

    /** The combat/movement modules plus the fun ones: ElytraFly+, Scaffold+, BlockIn, Sprint+, KillAura+, TpAura+, NoFall+, FakeLag, Derp and HandDerp. */
    public static final Category CATEGORY = new Category("hongshikaikai");
    /** Utility modules that are neither combat nor movement; ChatCopy, DirectionControl and SmoothCamera live here. */
    public static final Category CATEGORY_TOOLS = new Category("hskk-tools");
    public static final HudGroup HUD_GROUP = new HudGroup("hongshikaikai");

    @Override
    public void onInitialize() {
        LOG.info("Initializing HongShiKaiKai (ElytraFly+, Scaffold+, BlockIn, Sprint+, KillAura+, TpAura+, NoFall+, FakeLag, Derp, HandDerp, ChatCopy, DirectionControl, SmoothCamera)");

        // Modules
        Modules.get().add(new ElytraFlyPlus());
        Modules.get().add(new ScaffoldPlus());
        Modules.get().add(new BlockIn());
        Modules.get().add(new SprintPlus());
        Modules.get().add(new KillAuraPlus());
        Modules.get().add(new TpAuraPlus());
        Modules.get().add(new NoFallPlus());
        Modules.get().add(new FakeLag());
        Modules.get().add(new Derp());
        Modules.get().add(new HandDerp());
        Modules.get().add(new ChatCopy());
        Modules.get().add(new DirectionControl());
        Modules.get().add(new SmoothCamera());

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
