package com.xinian.KryptonHybrid.mixin.network.avoidwork;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker accessor for the package-private {@code ChunkMap.TrackedEntity.updatePlayer} method.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface ChunkMapTrackedEntityInvoker {
    @Invoker("updatePlayer")
    void invokeUpdatePlayer(ServerPlayer pPlayer);
}

