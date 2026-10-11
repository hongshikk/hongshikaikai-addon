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
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
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
import net.minecraft.util.Hand;

/**
 * Keeps splash potions of the selected effects up.
 *
 * <h2>What it does</h2>
 * Keeps splash potions of the selected effects up: when you are missing resistance (below level
 * three), speed, strength or slow falling it throws the matching potion straight down, either from
 * the hotbar or - with {@code inventory-swap} - silently out of the inventory. The three keybinds
 * throw one specific potion on demand even when the matching automatic option is off, and each one
 * fires once per press rather than every tick it is held.
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
        .description("Throw a potion of swiftness when you do not have one.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> resistance = sgGeneral.add(new BoolSetting.Builder()
        .name("resistance")
        .description("Throw a potion of resistance while your resistance is below level three.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> strength = sgGeneral.add(new BoolSetting.Builder()
        .name("strength")
        .description("Throw a potion of strength when you do not have one.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> slowFalling = sgGeneral.add(new BoolSetting.Builder()
        .name("slow-falling")
        .description("Throw a potion of slow falling when you do not have one.")
        .defaultValue(false)
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
        .description("Throw a swiftness potion while this key is held.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> strengthKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("strength-key")
        .description("Throw a strength potion while this key is held.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> resistanceKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("resistance-key")
        .description("Throw a resistance potion while this key is held.")
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
            "Keeps splash potions of the selected effects up, with one key per potion."
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

    /** The container slot holding a splash potion of {@code targetEffect}, or -1. */
    public static int findPotionInventorySlot(StatusEffect targetEffect) {
        MinecraftClient client = MinecraftClient.getInstance();

        for (int i = 35; i >= 0; i--) {
            ItemStack itemStack = client.player.getInventory().getStack(i);
            if (Item.getRawId(itemStack.getItem()) == Item.getRawId(Items.SPLASH_POTION)) {
                PotionContentsComponent potionContentsComponent = itemStack.getOrDefault(
                    DataComponentTypes.POTION_CONTENTS, PotionContentsComponent.DEFAULT
                );

                for (StatusEffectInstance effect : potionContentsComponent.getEffects()) {
                    if (effect.getEffectType().value() == targetEffect) {
                        return i < 9 ? i + 36 : i;
                    }
                }
            }
        }

        return -1;
    }

    /** The hotbar slot holding a splash potion of {@code targetEffect}, or -1. */
    public static int findPotion(StatusEffect targetEffect) {
        MinecraftClient client = MinecraftClient.getInstance();

        for (int i = 0; i < 9; i++) {
            ItemStack itemStack = client.player.getInventory().getStack(i);
            if (Item.getRawId(itemStack.getItem()) == Item.getRawId(Items.SPLASH_POTION)) {
                PotionContentsComponent potionContentsComponent = itemStack.getOrDefault(
                    DataComponentTypes.POTION_CONTENTS, PotionContentsComponent.DEFAULT
                );

                for (StatusEffectInstance effect : potionContentsComponent.getEffects()) {
                    if (effect.getEffectType().value() == targetEffect) {
                        return i;
                    }
                }
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
            if (!turtlePress && checkThrow(StatusEffects.RESISTANCE.value())) {
                throwPotion(StatusEffects.RESISTANCE.value());
                turtlePress = true;
                return;
            }
        } else {
            turtlePress = false;
        }

        if (strengthKey.get().isPressed()) {
            if (!strengthPress && checkThrow(StatusEffects.STRENGTH.value())) {
                throwPotion(StatusEffects.STRENGTH.value());
                strengthPress = true;
                return;
            }
        } else {
            strengthPress = false;
        }

        if (speedKey.get().isPressed()) {
            if (!speedPress && checkThrow(StatusEffects.SPEED.value())) {
                throwPotion(StatusEffects.SPEED.value());
                speedPress = true;
            }
        } else {
            speedPress = false;
        }
    }

    /** The automatic half: resistance stacks up to level three, the rest merely refresh. */
    private void autoThrow() {
        if (!delayTimer.passedMs(delay.get() * 1000.0)) return;
        if (onlyGround.get() && !(mc.player.isOnGround() || EntityUtil.isInWeb(mc.player))) return;
        if (onlyGround.get() && mc.world.isAir(new BlockPosX(EntityUtil.pos(mc.player).add(0.0, -1.0, 0.0)))) return;

        if (resistance.get()
            && (!mc.player.hasStatusEffect(StatusEffects.RESISTANCE) || mc.player.getStatusEffect(StatusEffects.RESISTANCE).getAmplifier() < 2)) {
            throwing = checkThrow(StatusEffects.RESISTANCE.value());
            if (isThrow()) {
                throwPotion(StatusEffects.RESISTANCE.value());
                return;
            }
        }

        if (speed.get() && !mc.player.hasStatusEffect(StatusEffects.SPEED)) {
            throwing = checkThrow(StatusEffects.SPEED.value());
            if (isThrow()) {
                throwPotion(StatusEffects.SPEED.value());
                return;
            }
        }

        if (strength.get() && !mc.player.hasStatusEffect(StatusEffects.STRENGTH)) {
            throwing = checkThrow(StatusEffects.STRENGTH.value());
            if (isThrow()) {
                throwPotion(StatusEffects.STRENGTH.value());
                return;
            }
        }

        if (slowFalling.get() && !mc.player.hasStatusEffect(StatusEffects.SLOW_FALLING)) {
            throwing = checkThrow(StatusEffects.SLOW_FALLING.value());
            if (isThrow()) throwPotion(StatusEffects.SLOW_FALLING.value());
        }
    }

    public void throwPotion(StatusEffect targetEffect) {
        int oldSlot = EntityUtil.selectedSlot();
        int newSlot;

        if (inventory.get() && (newSlot = findPotionInventorySlot(targetEffect)) != -1) {
            lookDown();
            InventoryUtil.inventorySwap(newSlot, EntityUtil.selectedSlot());
            EntityUtil.sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                Hand.MAIN_HAND, id, RotationHelper.getLastYaw(), RotationHelper.getLastPitch()
            ));
            InventoryUtil.inventorySwap(newSlot, EntityUtil.selectedSlot());
            EntityUtil.syncInventory();
            lookBack();
            delayTimer.reset();
        } else if ((newSlot = findPotion(targetEffect)) != -1) {
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

    public boolean checkThrow(StatusEffect targetEffect) {
        if (!EntityUtil.inInventory()) return false;
        if (usingPause.get() && mc.player.isUsingItem()) return false;

        return findPotion(targetEffect) != -1 || inventory.get() && findPotionInventorySlot(targetEffect) != -1;
    }
}
