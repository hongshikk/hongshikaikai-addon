package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.crystal.BlockPosX;
import com.hongshikaikai.addon.utils.crystal.EntityUtil;
import com.hongshikaikai.addon.utils.crystal.InventoryUtil;
import com.hongshikaikai.addon.utils.crystal.RotationHelper;
import com.hongshikaikai.addon.utils.crystal.Timer;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.PotionSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.MyPotion;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps splash potions of the selected effects up.
 *
 * <h2>What it does</h2>
 * Keeps splash potions of the selected effects up: when you are missing resistance (below the level
 * the potion grants), speed, strength or slow falling it throws the matching potion straight down,
 * either from the hotbar or - with {@code inventory-swap} - silently out of the inventory. The
 * three keybinds throw one specific potion on demand even when the matching automatic option is off,
 * and each one fires once per press rather than every tick it is held.
 *
 * <p>Every slot carries its own potion type: the {@code *-potion} settings open Meteor's own
 * "Select Potion" screen, and the slot only throws that exact potion - swiftness, swiftness long,
 * turtle master strong and so on are distinct choices, not one hardcoded effect. A slot's effect is
 * still what decides when to throw, while the chosen potion decides the level it waits for.
 *
 * <h2>How it works here</h2>
 * <p>The automatic throw and the keybinds are both handled on Meteor's {@link TickEvent.Pre},
 * automatic first. The throw aims the server down with {@link RotationHelper} and writes that aim
 * into the interaction packet; the aim is handed back to your own view straight afterwards.
 * {@code EntityUtil.isInWeb} is what makes standing in a cobweb count as standing on something.
 */
public class AutoPot extends Module {
    public static AutoPot INSTANCE;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> delay = sgGeneral.add(new DoubleSetting.Builder()
        .name("delay")
        .description("Seconds between two throws.")
        .defaultValue(5.0)
        .min(0.0)
        .sliderRange(0.0, 10.0)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Boolean> speed = sgGeneral.add(new BoolSetting.Builder()
        .name("speed")
        .description("Throw the selected swiftness potion whenever you are below the speed it grants.")
        .defaultValue(false)
        .build()
    );

    private final Setting<MyPotion> speedPotion = sgGeneral.add(new PotionSetting.Builder()
        .name("speed-potion")
        .description("The exact potion thrown for speed - picked in Meteor's Select Potion screen.")
        .defaultValue(MyPotion.Swiftness)
        .visible(speed::get)
        .build()
    );

    private final Setting<Boolean> resistance = sgGeneral.add(new BoolSetting.Builder()
        .name("resistance")
        .description("Throw the selected resistance potion whenever you are below the resistance it grants.")
        .defaultValue(false)
        .build()
    );

    private final Setting<MyPotion> resistancePotion = sgGeneral.add(new PotionSetting.Builder()
        .name("resistance-potion")
        .description("The exact potion thrown for resistance - picked in Meteor's Select Potion screen.")
        .defaultValue(MyPotion.TurtleMaster)
        .visible(resistance::get)
        .build()
    );

    private final Setting<Boolean> strength = sgGeneral.add(new BoolSetting.Builder()
        .name("strength")
        .description("Throw the selected strength potion whenever you are below the strength it grants.")
        .defaultValue(false)
        .build()
    );

    private final Setting<MyPotion> strengthPotion = sgGeneral.add(new PotionSetting.Builder()
        .name("strength-potion")
        .description("The exact potion thrown for strength - picked in Meteor's Select Potion screen.")
        .defaultValue(MyPotion.Strength)
        .visible(strength::get)
        .build()
    );

    private final Setting<Boolean> slowFalling = sgGeneral.add(new BoolSetting.Builder()
        .name("slow-falling")
        .description("Throw the selected slow falling potion whenever you do not have slow falling.")
        .defaultValue(false)
        .build()
    );

    private final Setting<MyPotion> slowFallingPotion = sgGeneral.add(new PotionSetting.Builder()
        .name("slow-falling-potion")
        .description("The exact potion thrown for slow falling - picked in Meteor's Select Potion screen.")
        .defaultValue(MyPotion.SlowFalling)
        .visible(slowFalling::get)
        .build()
    );

    private final Setting<Boolean> usingPause = sgGeneral.add(new BoolSetting.Builder()
        .name("using-pause")
        .description("Do not throw while you are already using an item.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyGround = sgGeneral.add(new BoolSetting.Builder()
        .name("only-ground")
        .description("Only throw while you are standing on something.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> inventory = sgGeneral.add(new BoolSetting.Builder()
        .name("inventory-swap")
        .description("Take the potion from your inventory rather than the hotbar, without telling the server which slot is held.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Keybind> speedKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("speed-key")
        .description("Throw the selected swiftness potion while this key is held.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> strengthKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("strength-key")
        .description("Throw the selected strength potion while this key is held.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> resistanceKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("resistance-key")
        .description("Throw the selected resistance potion while this key is held.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Timer delayTimer = new Timer();

    private boolean throwing = false;
    private boolean turtlePress;
    private boolean speedPress;
    private boolean strengthPress;

    public AutoPot() {
        super(
            HongShiKaiKai.CATEGORY_TOOLS,
            "AutoPot",
            "Keeps splash potions of the selected effects up, each slot's potion type picked in Meteor's potion screen."
        );
        INSTANCE = this;
    }

    @Override
    public void onDeactivate() {
        throwing = false;
        turtlePress = false;
        speedPress = false;
        strengthPress = false;
    }

    /** The potion contents of a Meteor potion type, or the empty default when it has none. */
    private static PotionContentsComponent contents(MyPotion potion) {
        return potion.potion.getOrDefault(DataComponentTypes.POTION_CONTENTS, PotionContentsComponent.DEFAULT);
    }

    /** The effects of a potion component as a list, whatever the version hands back. */
    private static List<StatusEffectInstance> effectsOf(PotionContentsComponent contents) {
        List<StatusEffectInstance> effects = new ArrayList<>();

        for (StatusEffectInstance effect : contents.getEffects()) effects.add(effect);

        return effects;
    }

    /** True when {@code stack} is a splash potion of exactly the selected type. */
    private static boolean isSelectedPotion(ItemStack stack, MyPotion potion) {
        if (Item.getRawId(stack.getItem()) != Item.getRawId(Items.SPLASH_POTION)) return false;

        List<StatusEffectInstance> stackEffects = effectsOf(
            stack.getOrDefault(DataComponentTypes.POTION_CONTENTS, PotionContentsComponent.DEFAULT)
        );
        List<StatusEffectInstance> potionEffects = effectsOf(contents(potion));

        if (stackEffects.size() != potionEffects.size()) return false;

        for (int i = 0; i < stackEffects.size(); i++) {
            StatusEffectInstance stackEffect = stackEffects.get(i);
            StatusEffectInstance potionEffect = potionEffects.get(i);

            if (stackEffect.getEffectType().value() != potionEffect.getEffectType().value()) return false;
            if (stackEffect.getAmplifier() != potionEffect.getAmplifier()) return false;
            if (stackEffect.getDuration() != potionEffect.getDuration()) return false;
        }

        return true;
    }

    /**
     * The amplifier the selected potion grants {@code effect} with, or -1 when it does not grant
     * that effect at all.
     */
    private static int amplifierOf(MyPotion potion, RegistryEntry<StatusEffect> effect) {
        for (StatusEffectInstance instance : contents(potion).getEffects()) {
            if (instance.getEffectType().value() == effect.value()) return instance.getAmplifier();
        }

        return -1;
    }

    /** The container slot holding a splash potion of {@code potion}, or -1. */
    public static int findPotionInventorySlot(MyPotion potion) {
        MinecraftClient client = MinecraftClient.getInstance();

        for (int i = 35; i >= 0; i--) {
            if (isSelectedPotion(client.player.getInventory().getStack(i), potion)) {
                return i < 9 ? i + 36 : i;
            }
        }

        return -1;
    }

    /** The hotbar slot holding a splash potion of {@code potion}, or -1. */
    public static int findPotion(MyPotion potion) {
        MinecraftClient client = MinecraftClient.getInstance();

        for (int i = 0; i < 9; i++) {
            if (isSelectedPotion(client.player.getInventory().getStack(i), potion)) {
                return i;
            }
        }

        return -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (!inventory.get() || EntityUtil.inInventory()) {
            keybinds();
            autoThrow();
        }
    }

    /** The keybind half: one throw per press, and nothing while a screen is open. */
    private void keybinds() {
        if (mc.currentScreen != null) {
            speedPress = false;
            turtlePress = false;
            strengthPress = false;
            return;
        }

        if (resistanceKey.get().isPressed()) {
            if (!turtlePress && checkThrow(resistancePotion.get())) {
                throwPotion(resistancePotion.get());
                turtlePress = true;
                return;
            }
        } else {
            turtlePress = false;
        }

        if (strengthKey.get().isPressed()) {
            if (!strengthPress && checkThrow(strengthPotion.get())) {
                throwPotion(strengthPotion.get());
                strengthPress = true;
                return;
            }
        } else {
            strengthPress = false;
        }

        if (speedKey.get().isPressed()) {
            if (!speedPress && checkThrow(speedPotion.get())) {
                throwPotion(speedPotion.get());
                speedPress = true;
            }
        } else {
            speedPress = false;
        }
    }

    /**
     * Whether the potion a slot would throw is worth throwing right now.
     *
     * <p>The slot's effect is what is looked at, the selected potion is what decides the level: the
     * potion is needed while you are missing its effect or are below the amplifier it grants. That
     * keeps the old "resistance below level three" behaviour for the default turtle master and
     * makes a strong swiftness wait for level one to run out.
     */
    private boolean needsPotion(MyPotion potion, RegistryEntry<StatusEffect> effect) {
        int required = Math.max(amplifierOf(potion, effect), 0);
        StatusEffectInstance current = mc.player.getStatusEffect(effect);

        return current == null || current.getAmplifier() < required;
    }

    /** The automatic half: the selected potions are thrown when their slot's effect runs low. */
    private void autoThrow() {
        if (!delayTimer.passedMs(delay.get() * 1000.0)) return;
        if (onlyGround.get() && !(mc.player.isOnGround() || EntityUtil.isInWeb(mc.player))) return;
        if (onlyGround.get() && mc.world.isAir(new BlockPosX(EntityUtil.pos(mc.player).add(0.0, -1.0, 0.0)))) return;

        if (resistance.get() && needsPotion(resistancePotion.get(), StatusEffects.RESISTANCE)) {
            throwing = checkThrow(resistancePotion.get());
            if (isThrow()) {
                throwPotion(resistancePotion.get());
                return;
            }
        }

        if (speed.get() && needsPotion(speedPotion.get(), StatusEffects.SPEED)) {
            throwing = checkThrow(speedPotion.get());
            if (isThrow()) {
                throwPotion(speedPotion.get());
                return;
            }
        }

        if (strength.get() && needsPotion(strengthPotion.get(), StatusEffects.STRENGTH)) {
            throwing = checkThrow(strengthPotion.get());
            if (isThrow()) {
                throwPotion(strengthPotion.get());
                return;
            }
        }

        if (slowFalling.get() && needsPotion(slowFallingPotion.get(), StatusEffects.SLOW_FALLING)) {
            throwing = checkThrow(slowFallingPotion.get());
            if (isThrow()) throwPotion(slowFallingPotion.get());
        }
    }

    public void throwPotion(MyPotion potion) {
        int oldSlot = EntityUtil.selectedSlot();
        int newSlot;

        if (inventory.get() && (newSlot = findPotionInventorySlot(potion)) != -1) {
            lookDown();
            InventoryUtil.inventorySwap(newSlot, EntityUtil.selectedSlot());
            EntityUtil.sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                Hand.MAIN_HAND, id, RotationHelper.getLastYaw(), RotationHelper.getLastPitch()
            ));
            InventoryUtil.inventorySwap(newSlot, EntityUtil.selectedSlot());
            EntityUtil.syncInventory();
            lookBack();
            delayTimer.reset();
        } else if ((newSlot = findPotion(potion)) != -1) {
            lookDown();
            InventoryUtil.switchToSlot(newSlot);
            EntityUtil.sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                Hand.MAIN_HAND, id, RotationHelper.getLastYaw(), RotationHelper.getLastPitch()
            ));
            InventoryUtil.switchToSlot(oldSlot);
            lookBack();
            delayTimer.reset();
        }
    }

    /**
     * Throws the potion at your own feet.
     *
     * <p>The yaw is irrelevant to a splash potion, so the aim the server already holds is kept and
     * only the pitch is snapped to 90 degrees.
     */
    private void lookDown() {
        RotationHelper.snapAt(RotationHelper.getLastYaw(), 90.0f);
    }

    /** Hands the server's aim back to your own view. */
    private void lookBack() {
        RotationHelper.snapAt(mc.player.getYaw(), mc.player.getPitch());
    }

    public boolean isThrow() {
        return throwing;
    }

    public boolean checkThrow(MyPotion potion) {
        if (!EntityUtil.inInventory()) return false;
        if (usingPause.get() && mc.player.isUsingItem()) return false;

        return findPotion(potion) != -1 || inventory.get() && findPotionInventorySlot(potion) != -1;
    }
}
