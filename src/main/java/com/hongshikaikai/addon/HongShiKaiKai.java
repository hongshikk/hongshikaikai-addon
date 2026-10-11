package com.hongshikaikai.addon;

import com.hongshikaikai.addon.hud.ScaffoldBlocksHud;
import com.hongshikaikai.addon.modules.AutoAnchor;
import com.hongshikaikai.addon.modules.AutoPot;
import com.hongshikaikai.addon.modules.BlockIn;
import com.hongshikaikai.addon.modules.Breaker;
import com.hongshikaikai.addon.modules.ChatCopy;
import com.hongshikaikai.addon.modules.CrystalAuraPlus;
import com.hongshikaikai.addon.modules.Derp;
import com.hongshikaikai.addon.modules.DirectionControl;
import com.hongshikaikai.addon.modules.ElytraFlyPlus;
import com.hongshikaikai.addon.modules.FakeLag;
import com.hongshikaikai.addon.modules.HandDerp;
import com.hongshikaikai.addon.modules.HskkHud;
import com.hongshikaikai.addon.modules.KillAuraPlus;
import com.hongshikaikai.addon.modules.NoFallPlus;
import com.hongshikaikai.addon.modules.PacketEat;
import com.hongshikaikai.addon.modules.PacketMinePlus;
import com.hongshikaikai.addon.modules.Phase;
import com.hongshikaikai.addon.modules.PistonCrystal;
import com.hongshikaikai.addon.modules.ScaffoldPlus;
import com.hongshikaikai.addon.modules.SmoothCamera;
import com.hongshikaikai.addon.modules.SprintPlus;
import com.hongshikaikai.addon.modules.TpAuraPlus;
import com.hongshikaikai.addon.modules.XCarry;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.PostInit;
import org.slf4j.Logger;

public class HongShiKaiKai extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    /** The combat/movement modules plus the fun ones: ElytraFly+, Scaffold+, BlockIn, Sprint+, KillAura+, CrystalAura+, PistonCrystal, AutoAnchor, Breaker, TpAura+, NoFall+, FakeLag, Derp, HandDerp, Phase, XCarry, PacketMine+ and PacketEat. */
    public static final Category CATEGORY = new Category("hongshikaikai");
    /** Utility modules that are neither combat nor movement; ChatCopy, DirectionControl, SmoothCamera, AutoPot and HSKK-HUD live here. */
    public static final Category CATEGORY_TOOLS = new Category("hskk-tools");
    public static final HudGroup HUD_GROUP = new HudGroup("hongshikaikai");

    @Override
    public void onInitialize() {
        LOG.info("Initializing HongShiKaiKai (ElytraFly+, Scaffold+, BlockIn, Sprint+, KillAura+, CrystalAura+, PistonCrystal, AutoAnchor, Breaker, TpAura+, NoFall+, FakeLag, Derp, HandDerp, Phase, XCarry, PacketMine+, PacketEat, ChatCopy, DirectionControl, SmoothCamera, AutoPot, HSKK-HUD)");

        // Modules
        Modules.get().add(new ElytraFlyPlus());
        Modules.get().add(new ScaffoldPlus());
        Modules.get().add(new BlockIn());
        Modules.get().add(new SprintPlus());
        Modules.get().add(new KillAuraPlus());
        Modules.get().add(new CrystalAuraPlus());
        Modules.get().add(new PistonCrystal());
        Modules.get().add(new AutoAnchor());
        Modules.get().add(new Breaker());
        Modules.get().add(new TpAuraPlus());
        Modules.get().add(new NoFallPlus());
        Modules.get().add(new FakeLag());
        Modules.get().add(new Derp());
        Modules.get().add(new HandDerp());
        Modules.get().add(new Phase());
        Modules.get().add(new XCarry());
        Modules.get().add(new PacketMinePlus());
        Modules.get().add(new PacketEat());
        Modules.get().add(new ChatCopy());
        Modules.get().add(new DirectionControl());
        Modules.get().add(new SmoothCamera());
        Modules.get().add(new AutoPot());
        Modules.get().add(new HskkHud());

        // HUD
        Hud.get().register(ScaffoldBlocksHud.INFO);
    }

    /**
     * HSKK-HUD is default-on, but Meteor loads the saved config after {@link #onInitialize()} and
     * that load starts by switching every module off, so the switch has to happen here instead -
     * once the config has had its say. See {@link HskkHud#applyDefaultOn()}.
     */
    @PostInit
    public static void afterConfigLoaded() {
        HskkHud.applyDefaultOn();
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
