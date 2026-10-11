package com.hongshikaikai.addon.mixin;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Write access to the {@code onGround} flag of a movement packet.
 *
 * <p>{@code PacketMine+} uses this to claim onGround on the movement packets sent in the last few
 * hundred milliseconds of a break. Vanilla divides the mining speed by five while the player is
 * airborne, and the server believes whatever the last movement packet said, so the claim is what
 * removes that penalty on the server side. The field is {@code protected final} in every version
 * this addon builds for, which is why the accessor has to be {@link Mutable}.
 */
@Mixin(PlayerMoveC2SPacket.class)
public interface PlayerMoveC2SPacketAccessor {
    @Mutable
    @Accessor("onGround")
    void setOnGround(boolean onGround);
}
