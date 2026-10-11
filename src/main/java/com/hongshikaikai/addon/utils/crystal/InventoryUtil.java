package com.hongshikaikai.addon.utils.crystal;

import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Hotbar/inventory search and swap helpers.
 *
 * <p>Two swap modes: {@link #switchToSlot} moves the held slot and tells the server, while
 * {@link #inventorySwap} moves the item into the held slot with a container click and does
 * <em>not</em> tell the server which slot is held - the "silent" swap the crystal modules use so the
 * server never sees the switch.
 */
public final class InventoryUtil implements Wrapper {
    private static int lastSlot = -1;
    private static int lastSelect = -1;

    private InventoryUtil() {
    }

    /**
     * Silent swap: puts {@code slot} into the held slot and remembers how to undo it.
     *
     * <p>Calling it twice with the same slot flips the two back, which is how the modules restore the
     * player's own item after a placement. Slots are container slots, so a hotbar slot {@code n} is
     * {@code n + 36}.
     *
     * <p>Uses the held-slot shim rather than {@code selectedSlot} directly, because the field became
     * private in 1.21.5.
     */
    public static void inventorySwap(int slot, int selectedSlot) {
        if (slot == lastSlot) {
            switchToSlot(lastSelect);
            lastSlot = -1;
            lastSelect = -1;
        } else if (slot - 36 != selectedSlot) {
            if (EntityUtil.inInventory()) {
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, selectedSlot, SlotActionType.SWAP, mc.player);
                mc.player.getInventory().updateItems();
            }
        }
    }

    /** Moves the held slot and tells the server about it. */
    public static void switchToSlot(int slot) {
        EntityUtil.selectSlot(slot);
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    public static int findItem(Item input) {
        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (Item.getRawId(item) == Item.getRawId(input)) {
                return i;
            }
        }

        return -1;
    }

    public static int findClass(Class<?> clazz) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                if (clazz.isInstance(stack.getItem())) {
                    return i;
                }

                if (stack.getItem() instanceof BlockItem blockItem && clazz.isInstance(blockItem.getBlock())) {
                    return i;
                }
            }
        }

        return -1;
    }

    public static int findClassInventorySlot(Class<?> clazz) {
        if (CrystalConfig.priorHotbar) {
            for (int i = 0; i < 36; i++) {
                int found = matchClass(mc.player.getInventory().getStack(i), clazz, i);
                if (found != -1) return found;
            }
        } else {
            for (int i = 35; i >= 0; i--) {
                int found = matchClass(mc.player.getInventory().getStack(i), clazz, i);
                if (found != -1) return found;
            }
        }

        return -1;
    }

    private static int matchClass(ItemStack stack, Class<?> clazz, int index) {
        if (stack.isEmpty()) return -1;

        if (clazz.isInstance(stack.getItem())) {
            return index < 9 ? index + 36 : index;
        }

        if (stack.getItem() instanceof BlockItem blockItem && clazz.isInstance(blockItem.getBlock())) {
            return index < 9 ? index + 36 : index;
        }

        return -1;
    }

    public static int findBlock(Block blockIn) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == blockIn) {
                return i;
            }
        }

        return -1;
    }

    /**
     * The first hotbar slot that does <em>not</em> hold a placeable block.
     *
     * <p>The anchor modules click a charged respawn anchor to detonate it, and a right-click while a
     * block is held would place that block instead; this picks something safe to hold for the
     * detonation click.
     */
    public static int findUnBlock() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!(stack.getItem() instanceof BlockItem)) {
                return i;
            }
        }

        return -1;
    }

    public static int findBlockInventorySlot(Block block) {
        return findItemInventorySlot(block.asItem());
    }

    public static int findItemInventorySlot(Item item) {
        if (CrystalConfig.priorHotbar) {
            return findItemInventorySlotFromZero(item);
        }

        for (int i = 35; i >= 0; i--) {
            if (mc.player.getInventory().getStack(i).getItem() == item) {
                return i < 9 ? i + 36 : i;
            }
        }

        return -1;
    }

    public static int findItemInventorySlotFromZero(Item item) {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item) {
                return i < 9 ? i + 36 : i;
            }
        }

        return -1;
    }
}
