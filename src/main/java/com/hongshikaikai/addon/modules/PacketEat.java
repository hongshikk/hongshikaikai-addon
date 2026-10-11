package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.crystal.EntityUtil;
import com.hongshikaikai.addon.utils.crystal.RotationHelper;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;

/**
 * Keeps the server's item-use state open while you eat.
 *
 * <h2>What it does</h2>
 * Eating is a two-packet conversation: one {@code PlayerInteractItem} to start, and one
 * {@code RELEASE_USE_ITEM} to stop. Both options here work on that conversation.
 *
 * <ul>
 *     <li>{@code de-sync} re-sends the start packet every tick while you are eating. Each one carries
 *         the aim the last look packet left on the server, so an anti-cheat that keys "which way was
 *         the player facing while eating" on the release packet sees a fresh, consistent start
 *         instead of a stale one.</li>
 *     <li>{@code no-release} swallows the release packet, so a server that answers the release with
 *         its own item-use bookkeeping never sees the meal finish. The item effect is applied by the
 *         server on the start, so the food value still lands; only the "you stopped eating" half goes
 *         missing.</li>
 * </ul>
 *
 * <h2>How it works here</h2>
 * <p>{@code de-sync} runs on Meteor's {@link TickEvent.Pre}, once per client tick. The rotation written
 * into the start packet comes from {@link RotationHelper}, so it matches the aim the last look
 * packet left on the server.
 */
public class PacketEat extends Module {
    public static PacketEat INSTANCE;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> deSync = sgGeneral.add(new BoolSetting.Builder()
        .name("de-sync")
        .description("Re-send the start-eat packet every tick while you are eating.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> noRelease = sgGeneral.add(new BoolSetting.Builder()
        .name("no-release")
        .description("Cancel the packet that tells the server you stopped eating.")
        .defaultValue(true)
        .build()
    );

    public PacketEat() {
        super(HongShiKaiKai.CATEGORY, "PacketEat", "Keeps the server's item-use state open while you eat.");
        INSTANCE = this;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (!deSync.get() || !mc.player.isUsingItem() || !isFood(mc.player.getActiveItem())) return;

        EntityUtil.sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
            mc.player.getActiveHand(), id, RotationHelper.getLastYaw(), RotationHelper.getLastPitch()
        ));
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (mc.player == null) return;

        if (noRelease.get()
            && event.packet instanceof PlayerActionC2SPacket packet
            && packet.getAction() == PlayerActionC2SPacket.Action.RELEASE_USE_ITEM
            && isFood(mc.player.getActiveItem())) {
            event.cancel();
        }
    }

    /** Whether the stack is edible - i.e. whether this is actually an eat. */
    private static boolean isFood(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.contains(DataComponentTypes.FOOD);
    }
}
