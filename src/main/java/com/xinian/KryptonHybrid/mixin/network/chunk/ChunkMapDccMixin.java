package com.xinian.KryptonHybrid.mixin.network.chunk;

import net.minecraft.server.level.ChunkHolder;
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
 * <p>In NeoForge 1.21.1 every change of a player's chunk view goes through
 * {@code ChunkMap.applyChunkTrackingView}, which diffs the old and new
 * {@link ChunkTrackingView}. DCC wraps the diff's callbacks: a chunk leaving the view
 * can stay on the client instead of being dropped, and a chunk entering it again skips
 * the resend while the client still has it and it has not changed since (see
 * {@link DelayedChunkCache}).</p>
 *
 * <p>Before the view center moves, kept chunks the client could no longer reach are
 * forgotten, so the "forget" packets arrive before the new center and the client can
 * still apply them.</p>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapDccMixin {

    @Shadow @Final
    ServerLevel level;

    @Shadow
    private void markChunkPendingToSend(ServerPlayer player, ChunkPos chunkPos) {
        throw new AssertionError();
    }

    @Shadow
    protected abstract ChunkHolder getVisibleChunkIfPresent(long pos);

    /** Forgets kept chunks outside the client's range around the coming view center. */
    @Inject(method = "applyChunkTrackingView", at = @At("HEAD"))
    private void krypton$evictBeforeCenterMoves(ServerPlayer player, ChunkTrackingView view, CallbackInfo ci) {
        if (player.level() == this.level && view instanceof ChunkTrackingView.Positioned positioned) {
            ChunkPos center = positioned.center();
            DelayedChunkCache.INSTANCE.evictOutsideClientRange(player, this.level.dimension(), center.x, center.z,
                    ChunkMapDccMixin::dropChunkDeferred);
        }
    }

    /**
     * Wraps chunk enter/leave callbacks with DCC logic:
     * - enter callback: skip the resend on a cache hit
     * - leave callback: keep the chunk on the client when the cache accepts it
     */
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
                    ChunkHolder holder = this.getVisibleChunkIfPresent(pos.toLong());
                    DelayedChunkCache.Result result = DelayedChunkCache.INSTANCE.onChunkEnter(
                            player, dim, pos, holder == null ? null
                                    : holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null));
                    if (result == DelayedChunkCache.Result.HIT) {
                        // The client still has this chunk, unchanged: skip the resend.
                        return;
                    }
                    if (result == DelayedChunkCache.Result.STALE) {
                        // The unwatch was held back when the chunk was kept; the resend
                        // replaces the client's copy and fires the watch event again.
                        net.neoforged.neoforge.event.EventHooks.fireChunkUnWatch(player, pos, this.level);
                    }
                    this.markChunkPendingToSend(player, pos);
                },
                pos -> {
                    // A chunk that was never sent is just removed from the pending set.
                    if (player.connection.chunkSender.isPending(pos.toLong())
                            || !DelayedChunkCache.INSTANCE.onChunkLeave(player, dim, pos)) {
                        krypton$dropChunkNow(player, pos);
                    }
                }
        );
    }

    @Unique
    private static void krypton$dropChunkNow(ServerPlayer player, ChunkPos chunkPos) {
        net.neoforged.neoforge.event.EventHooks.fireChunkUnWatch(player, chunkPos, player.serverLevel());
        player.connection.chunkSender.dropChunk(player, chunkPos);
    }

    /** Evicts stale DCC cache entries and performs the deferred drops. */
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void tick$evictDccCache(CallbackInfo ci) {
        DelayedChunkCache.INSTANCE.tick(this.level.dimension(), this.level.players(), ChunkMapDccMixin::dropChunkDeferred);
    }

    @Unique
    private static void dropChunkDeferred(ServerPlayer player, ChunkPos chunkPos) {
        net.neoforged.neoforge.event.EventHooks.fireChunkUnWatch(player, chunkPos, player.serverLevel());
        player.connection.chunkSender.dropChunk(player, chunkPos);
    }
}
