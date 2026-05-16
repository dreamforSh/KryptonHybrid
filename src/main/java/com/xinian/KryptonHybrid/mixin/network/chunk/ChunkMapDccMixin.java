package com.xinian.KryptonHybrid.mixin.network.chunk;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;
import com.xinian.KryptonHybrid.shared.network.chunk.DelayedChunkCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Implements the Delayed Chunk Cache (DCC) optimization for Krypton Hybrid.
 *
 * <p>In NeoForge 1.21.1, {@code ChunkMap.updateChunkTracking} only takes a single
 * {@code ServerPlayer} argument; the multi-arg overload with {@code ChunkPos},
 * {@code MutableObject}, and two booleans was removed. DCC chunk-enter/leave
 * interception is performed inside {@code applyChunkTrackingView} by wrapping
 * {@link ChunkTrackingView#difference} callbacks.</p>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapDccMixin {

    @Shadow @Final
    ServerLevel level;

    @Shadow
    private void markChunkPendingToSend(ServerPlayer player, ChunkPos chunkPos) {
        throw new AssertionError();
    }

    /**
     * Wraps chunk enter/leave callbacks with DCC logic:
     * - enter callback: skip resend on cache hit
     * - leave callback: delay untrack/send-forget when cache accepts the chunk
     */
    @Unique
    @Redirect(
            method = "applyChunkTrackingView",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkTrackingView;difference(Lnet/minecraft/server/level/ChunkTrackingView;Lnet/minecraft/server/level/ChunkTrackingView;Ljava/util/function/Consumer;Ljava/util/function/Consumer;)V"
            )
    )
    private void krypton$applyDccToTrackingDiff(
            ChunkTrackingView oldView,
            ChunkTrackingView newView,
            Consumer<ChunkPos> markSendCallback,
            Consumer<ChunkPos> dropCallback,
            ServerPlayer player,
            ChunkTrackingView requestedView
    ) {
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim = this.level.dimension();
        ChunkTrackingView.difference(
                oldView,
                newView,
                pos -> {
                    // Cache hit: the client still has this chunk, so skip resend.
                    if (!DelayedChunkCache.INSTANCE.onChunkEnter(player, dim, pos)) {
                        this.markChunkPendingToSend(player, pos);
                    }
                },
                pos -> {
                    // If the chunk was never sent, remove pending state immediately.
                    if (player.connection.chunkSender.isPending(pos.toLong())) {
                        krypton$dropChunkNow(player, pos);
                        return;
                    }

                    // Otherwise, delay the untrack unless the cache rejects this chunk.
                    if (!DelayedChunkCache.INSTANCE.onChunkLeave(player, dim, pos)) {
                        krypton$dropChunkNow(player, pos);
                    }
                }
        );
    }

    @SuppressWarnings("unused")
    @Unique
    private static void krypton$dropChunkNow(ServerPlayer player, ChunkPos chunkPos) {
        net.minecraftforge.event.EventHooks.fireChunkUnWatch(player, chunkPos, player.serverLevel());
        player.connection.chunkSender.dropChunk(player, chunkPos);
    }

    /** Evicts stale DCC cache entries and performs deferred {@code untrackChunk} calls. */
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void tick$evictDccCache(CallbackInfo ci) {
        DelayedChunkCache.INSTANCE.tick(this.level.dimension(), this.level.players(), ChunkMapDccMixin::dropChunkDeferred);
    }

    private static void dropChunkDeferred(ServerPlayer player, ChunkPos chunkPos) {
        net.minecraftforge.event.EventHooks.fireChunkUnWatch(player, chunkPos, player.serverLevel());
        player.connection.chunkSender.dropChunk(player, chunkPos);
    }
}
