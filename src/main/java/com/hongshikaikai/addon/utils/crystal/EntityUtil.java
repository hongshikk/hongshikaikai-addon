package com.hongshikaikai.addon.utils.crystal;

import com.hongshikaikai.addon.mixin.ClientWorldAccessor;
import com.hongshikaikai.addon.utils.VersionCompat;
import meteordevelopment.meteorclient.gui.screens.ModulesScreen;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.network.PendingUpdateManager;
import net.minecraft.client.network.SequencedPacketCreator;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/** Player/entity helpers for the crystal modules. */
public final class EntityUtil implements Wrapper {
    private EntityUtil() {
    }

    /**
     * Whether an inventory swap can be performed right now.
     *
     * <p>A swap needs either no screen at all or one that leaves the player's own inventory
     * reachable - the inventory, the chat, the game menu, the options screens and Meteor's ClickGUI.
     * Any other container means the swap would land in the wrong handler.
     */
    public static boolean inInventory() {
        return mc.currentScreen == null
            || mc.currentScreen instanceof GameOptionsScreen
            || mc.currentScreen instanceof OptionsScreen
            || mc.currentScreen instanceof ChatScreen
            || mc.currentScreen instanceof InventoryScreen
            || mc.currentScreen instanceof ModulesScreen
            || mc.currentScreen instanceof GameMenuScreen;
    }

    public static int getDamagePercent(ItemStack stack) {
        if (stack.isEmpty() || stack.getMaxDamage() <= 0) return 100;
        return stack.getDamage() == stack.getMaxDamage()
            ? 100
            : (int) ((stack.getMaxDamage() - stack.getDamage()) / Math.max(0.1, stack.getMaxDamage()) * 100.0);
    }

    public static float getHealth(LivingEntity entity) {
        return entity != null ? entity.getHealth() + entity.getAbsorptionAmount() : 0.0f;
    }

    /**
     * An entity's exact position.
     *
     * <p>Built from {@code getX/getY/getZ} rather than {@code Entity.getPos()}, whose name changed to
     * {@code getEntityPos()} in the 1.21.10 mappings. The three component accessors are the same in
     * every version this addon builds for, and this is exactly what {@code getPos()} returned.
     */
    public static Vec3d pos(net.minecraft.entity.Entity entity) {
        return new Vec3d(entity.getX(), entity.getY(), entity.getZ());
    }

    public static BlockPos getEntityPos(net.minecraft.entity.Entity entity) {
        return new BlockPosX(pos(entity));
    }

    public static BlockPos getEntityPos(net.minecraft.entity.Entity entity, boolean fix) {
        return new BlockPosX(pos(entity), fix);
    }

    public static BlockPos getPlayerPos(boolean fix) {
        return new BlockPosX(pos(mc.player), fix);
    }

    /** Whether a straight line from the eye reaches the given face of {@code pos} without a block in the way. */
    public static boolean canSee(BlockPos pos, Direction side) {
        Vec3d testVec = pos.toCenterPos().add(side.getVector().getX() * 0.5, side.getVector().getY() * 0.5, side.getVector().getZ() * 0.5);
        HitResult result = mc.world.raycast(new RaycastContext(mc.player.getEyePos(), testVec, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        return result == null || result.getType() == HitResult.Type.MISS;
    }

    public static void swingHand(Hand hand) {
        swingHand(hand, CrystalConfig.interactSwing);
    }

    public static void swingHand(Hand hand, SwingSide side) {
        switch (side) {
            case All -> mc.player.swingHand(hand);
            case Client -> mc.player.swingHand(hand, false);
            case Server -> mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));
        }
    }

    /** Moves the held slot, going through the selected-slot compatibility shim. */
    public static void selectSlot(int slot) {
        VersionCompat.setSelectedSlot(mc.player.getInventory(), slot);
    }

    public static int selectedSlot() {
        return VersionCompat.getSelectedSlot(mc.player.getInventory());
    }

    /**
     * Puts the client-side inventory back in sync with the server after a silent swap.
     *
     * <p>When the "close screen" workaround is on, the handled screen is closed; the default is off,
     * so this is a no-op unless that switch is flipped.
     */
    public static void syncInventory() {
        if (CrystalConfig.closeScreen && mc.player.currentScreenHandler != null) {
            mc.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
        }
    }

    /**
     * Sends a block interaction the way vanilla does, either through the interaction manager or as a
     * sequenced packet.
     *
     * <p>The packet path mirrors {@code ClientPlayerInteractionManager.sendSequencedPacket}: the
     * sequence number comes from the world's pending-update manager and is closed after the send, so
     * the server can tell the interaction apart from the block update it predicts.
     */
    public static void interactBlock(Hand hand, BlockHitResult result) {
        if (CrystalConfig.packetPlace) {
            if (mc.player == null) return;

            sendSequencedPacket(sequence -> new PlayerInteractBlockC2SPacket(hand, result, sequence));
        } else {
            mc.interactionManager.interactBlock(mc.player, hand, result);
        }
    }

    /**
     * Sends a packet whose sequence number comes from the world's pending-update manager, the way
     * {@code ClientPlayerInteractionManager.sendSequencedPacket} does.
     *
     * <p>The interaction packets this code sends by hand (item use, block actions) have to carry the
     * same sequence the client's own prediction is keyed on, or the server resolves them against a
     * stale block state.
     */
    public static void sendSequencedPacket(SequencedPacketCreator creator) {
        if (mc.getNetworkHandler() == null || mc.world == null) return;

        try (PendingUpdateManager pending = ((ClientWorldAccessor) mc.world).hskk$getPendingUpdateManager().incrementSequence()) {
            mc.getNetworkHandler().sendPacket(creator.predict(pending.getSequence()));
        }
    }

    public static boolean isPlayer(Object entity) {
        return entity instanceof PlayerEntity;
    }

    /**
     * Whether a player is standing in a cobweb.
     *
     * <p>The column of cells around the player's feet is sampled rather than just the cell the feet
     * round into, because the hitbox is wider than a block and a player can be caught on the edge of
     * one.
     */
    public static boolean isInWeb(PlayerEntity player) {
        for (float x : new float[]{0.0f, 0.3f, -0.3f}) {
            for (float z : new float[]{0.0f, 0.3f, -0.3f}) {
                for (int y : new int[]{-1, 0, 1, 2}) {
                    BlockPos pos = new BlockPosX(player.getX() + x, player.getY(), player.getZ() + z).up(y);
                    if (new Box(pos).intersects(player.getBoundingBox()) && mc.world.getBlockState(pos).getBlock() == Blocks.COBWEB) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
