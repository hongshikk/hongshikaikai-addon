package com.hongshikaikai.addon.mixin;

import com.hongshikaikai.addon.modules.PacketMinePlus;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The collision half of {@code PacketMine+}'s {@code no-collide} option.
 *
 * <p>While the module is holding a ghost block open - the client still shows a block the server has
 * already been told to remove - that cell stops colliding, so the player can walk through it without
 * waiting for the server's confirmation. Both the context-carrying and the cached context-free query
 * are hooked, because a block with a single cached collision shape answers the latter straight out
 * of its cache without ever reaching the former.
 */
@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class AbstractBlockStateMixin {
    @Inject(
        method = "getCollisionShape(Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/ShapeContext;)Lnet/minecraft/util/shape/VoxelShape;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void hskk$noCollideWithContext(BlockView world, BlockPos pos, ShapeContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (PacketMinePlus.shouldDisableCollision(pos)) {
            cir.setReturnValue(VoxelShapes.empty());
        }
    }

    @Inject(
        method = "getCollisionShape(Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/util/shape/VoxelShape;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void hskk$noCollide(BlockView world, BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
        if (PacketMinePlus.shouldDisableCollision(pos)) {
            cir.setReturnValue(VoxelShapes.empty());
        }
    }
}
