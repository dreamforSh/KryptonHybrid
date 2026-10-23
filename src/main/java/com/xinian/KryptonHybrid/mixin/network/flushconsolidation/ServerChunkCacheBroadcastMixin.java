package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import com.xinian.KryptonHybrid.shared.network.broadcast.BroadcastBundleCollector;
import com.xinian.KryptonHybrid.shared.network.broadcast.EntityBundleCollector;
import com.xinian.KryptonHybrid.shared.network.flow.FlushWindows;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Opens the outermost flush window of the level tick at the {@code "broadcast"} phase
 * of {@link ServerChunkCache#tickChunks()} and closes it once
 * {@code ServerChunkCache.tick} has run {@code ChunkMap.tick()} (entity tracking),
 * which on 1.21.1 follows {@code tickChunks}.
 *
 * <p>The broadcast phase calls {@code ChunkHolder.broadcastChanges} for every ticking
 * chunk, fanning out block, section, light and block-entity updates. While the window
 * is open every player's connection only writes, and a {@link BroadcastBundleCollector}
 * window lets {@code ChunkHolderBroadcastMixin} collect the broadcast packets per
 * player. {@code ChunkMapFlushMixin} writes those before entity tracking starts, so
 * the vanilla order is kept, and closing this window flushes each player once for
 * the whole level tick (or schedules the micro-batch flush).</p>
 *
 * <p>Debug worlds skip the broadcast phase; there {@code ChunkMap.tick()} manages its
 * own window.</p>
 */
@Mixin(ServerChunkCache.class)
public class ServerChunkCacheBroadcastMixin {

    @Shadow @Final
    ServerLevel level;

    @Unique
    private boolean krypton$windowOpen;

    @Inject(
            method = "tickChunks",
            at = @At(
                    value = "CONSTANT",
                    args = "stringValue=broadcast",
                    shift = At.Shift.BEFORE
            )
    )
    private void krypton$beforeBroadcast(CallbackInfo ci) {
        // Recovery: deliver anything a previous tick left behind after an exception.
        BroadcastBundleCollector.endBatchAndFlush();
        EntityBundleCollector.endBatchAndFlush();

        FlushWindows.openOutermost(this.level);
        BroadcastBundleCollector.beginBatch();
        krypton$windowOpen = true;
    }

    @Inject(
            method = "tick(Ljava/util/function/BooleanSupplier;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;tick()V",
                    shift = At.Shift.AFTER
            )
    )
    private void krypton$afterEntityTracking(BooleanSupplier hasTimeLeft, boolean tickChunks, CallbackInfo ci) {
        if (!krypton$windowOpen) {
            return;
        }
        krypton$windowOpen = false;
        // Normally already written by ChunkMapFlushMixin; covers a tick without entity tracking.
        BroadcastBundleCollector.endBatchAndFlush();
        FlushWindows.closeOutermost(this.level);
    }
}
