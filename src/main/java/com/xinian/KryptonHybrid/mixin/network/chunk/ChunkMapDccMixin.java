package com.xinian.KryptonHybrid.mixin.network.chunk;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import com.xinian.KryptonHybrid.shared.network.chunk.DelayedChunkCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Implements the Delayed Chunk Cache (DCC) optimization for Krypton Hybrid.
 *
 * <p>In NeoForge 1.21.1, {@code ChunkMap.updateChunkTracking} only takes a single
 * {@code ServerPlayer} argument; the multi-arg overload with {@code ChunkPos},
 * {@code MutableObject}, and two booleans was removed. DCC chunk-enter/leave
 * interception therefore runs via a {@code tick()V} RETURN hook that evicts stale
 * entries and performs deferred {@code untrackChunk} calls.</p>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapDccMixin {

    @Shadow @Final
    ServerLevel level;

    /** Evicts stale DCC cache entries and performs deferred {@code untrackChunk} calls. */
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void tick$evictDccCache(CallbackInfo ci) {
        DelayedChunkCache.INSTANCE.tick(this.level.players(), ChunkMapDccMixin::dropChunkDeferred);
    }

    private static void dropChunkDeferred(ServerPlayer player, ChunkPos chunkPos) {
        net.neoforged.neoforge.event.EventHooks.fireChunkUnWatch(player, chunkPos, player.serverLevel());
        player.connection.chunkSender.dropChunk(player, chunkPos);
    }
}
