package com.hongshikaikai.addon.mixin;

import net.minecraft.client.network.PendingUpdateManager;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Opens up {@code ClientWorld.getPendingUpdateManager()}, which is package-private.
 *
 * <p>The crystal modules send block interactions as packets when {@code packet-place} is on, and a
 * sequenced interact packet needs the world's pending-update sequence: it is how the server tells
 * the interaction apart from the block change the client predicted for it. Vanilla's own
 * {@code ClientPlayerInteractionManager.sendSequencedPacket} pulls the sequence from this method,
 * which is why it exists - there is no public path to it.
 *
 * <p>The member name is stable from 1.21.4 to 1.21.11, the same reasoning as
 * {@link ChatHudAccessor}: the invoker's own name is ours, so a rename on the Minecraft side cannot
 * break the call site.
 */
@Mixin(ClientWorld.class)
public interface ClientWorldAccessor {
    @Invoker("getPendingUpdateManager")
    PendingUpdateManager hskk$getPendingUpdateManager();
}
