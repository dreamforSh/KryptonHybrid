package com.xinian.KryptonHybrid.mixin.network.chunk;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import com.xinian.KryptonHybrid.mixin.network.flushconsolidation.ChunkMapLevelAccessor;
import com.xinian.KryptonHybrid.shared.network.chunk.DelayedChunkCache;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Implements the Delayed Chunk Cache (DCC) optimization for Krypton Hybrid.
 *
 * <p>Forge 1.19.2 still exposes the multi-argument
 * {@code ChunkMap.updateChunkTracking} path. DCC intercepts that transition point
 * directly: leave transitions can delay {@code untrackChunk}, and matching enter
 * transitions can skip a resend while the client still has the chunk and it has not
 * changed since (see {@link DelayedChunkCache}).</p>
 *
 * <p>Before a player's view center moves ({@code move}, or a player being added at a
 * new position), kept chunks the client could no longer reach are forgotten, so the
 * "forget" packets arrive while the client can still apply them.</p>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapDccMixin {

    @Shadow
    protected abstract ChunkHolder getVisibleChunkIfPresent(long pos);

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
        if (player.getLevel() != level) {
            return;
        }

        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim = level.dimension();
        if (newWithinViewDistance && !oldWithinViewDistance) {
            ChunkHolder holder = this.getVisibleChunkIfPresent(pos.toLong());
            DelayedChunkCache.Result result = DelayedChunkCache.INSTANCE.onChunkEnter(
                    player, dim, pos, holder == null ? null : holder.getFullChunk());
            if (result == DelayedChunkCache.Result.HIT) {
                ci.cancel();
            } else if (result == DelayedChunkCache.Result.STALE) {
                // The unwatch was held back when the chunk was cached; the resend that
                // follows replaces the client's copy and fires the watch event again.
                net.minecraftforge.event.ForgeEventFactory.fireChunkUnWatch(player, pos, level);
            }
            return;
        }

        if (!newWithinViewDistance && oldWithinViewDistance
                && DelayedChunkCache.INSTANCE.onChunkLeave(player, dim, pos)) {
            ci.cancel();
        }
    }

    @Inject(method = "move", at = @At("HEAD"))
    private void krypton$evictBeforeMove(ServerPlayer player, CallbackInfo ci) {
        krypton$evictForCenter(player);
    }

    @Inject(method = "updatePlayerStatus", at = @At("HEAD"))
    private void krypton$evictBeforeAdd(ServerPlayer player, boolean track, CallbackInfo ci) {
        if (track) {
            krypton$evictForCenter(player);
        }
    }

    /** Forgets kept chunks outside the client's range around the player's coming view center. */
    private void krypton$evictForCenter(ServerPlayer player) {
        ServerLevel level = this.krypton$level();
        if (player.getLevel() != level) {
            return;
        }
        SectionPos next = SectionPos.of(player);
        DelayedChunkCache.INSTANCE.evictOutsideClientRange(player, level.dimension(), next.x(), next.z(),
                ChunkMapDccMixin::dropChunkDeferred);
    }

    /** Evicts stale DCC cache entries and performs deferred {@code untrackChunk} calls. */
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void tick$evictDccCache(CallbackInfo ci) {
        ServerLevel level = this.krypton$level();
        DelayedChunkCache.INSTANCE.tick(level.dimension(), level.players(), ChunkMapDccMixin::dropChunkDeferred);
    }

    private static void dropChunkDeferred(ServerPlayer player, ChunkPos chunkPos) {
        player.untrackChunk(chunkPos);
        net.minecraftforge.event.ForgeEventFactory.fireChunkUnWatch(player, chunkPos, player.getLevel());
    }
}
