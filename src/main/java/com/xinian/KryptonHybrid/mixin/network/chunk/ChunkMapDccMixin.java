package com.xinian.KryptonHybrid.mixin.network.chunk;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import com.xinian.KryptonHybrid.mixin.network.flushconsolidation.ChunkMapLevelAccessor;
import com.xinian.KryptonHybrid.shared.network.chunk.DelayedChunkCache;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Implements the Delayed Chunk Cache (DCC) optimization for Krypton Hybrid.
 *
 * <p>Forge 1.20.1 still exposes the multi-argument
 * {@code ChunkMap.updateChunkTracking} path. DCC intercepts that transition point
 * directly: leave transitions can delay {@code untrackChunk}, and matching enter
 * transitions can skip a resend while the client still has the chunk.</p>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapDccMixin {

    private ServerLevel krypton$level() {
        return ((ChunkMapLevelAccessor) this).krypton$getLevel();
    }

    @Inject(
            method = "updateChunkTracking",
            at = @At("HEAD"),
            cancellable = true
    )
    private void krypton$applyDccToTracking(
            ServerPlayer player,
            ChunkPos pos,
            MutableObject<ClientboundLevelChunkWithLightPacket> packetCache,
            boolean oldWithinViewDistance,
            boolean newWithinViewDistance,
            CallbackInfo ci
    ) {
        ServerLevel level = this.krypton$level();
        if (player.level() != level) {
            return;
        }

        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim = level.dimension();
        if (newWithinViewDistance && !oldWithinViewDistance
                && DelayedChunkCache.INSTANCE.onChunkEnter(player, dim, pos)) {
            ci.cancel();
            return;
        }

        if (!newWithinViewDistance && oldWithinViewDistance
                && DelayedChunkCache.INSTANCE.onChunkLeave(player, dim, pos)) {
            ci.cancel();
        }
    }

    /** Evicts stale DCC cache entries and performs deferred {@code untrackChunk} calls. */
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void tick$evictDccCache(CallbackInfo ci) {
        ServerLevel level = this.krypton$level();
        DelayedChunkCache.INSTANCE.tick(level.dimension(), level.players(), ChunkMapDccMixin::dropChunkDeferred);
    }

    private static void dropChunkDeferred(ServerPlayer player, ChunkPos chunkPos) {
        player.untrackChunk(chunkPos);
        net.minecraftforge.event.ForgeEventFactory.fireChunkUnWatch(player, chunkPos, player.serverLevel());
    }
}
