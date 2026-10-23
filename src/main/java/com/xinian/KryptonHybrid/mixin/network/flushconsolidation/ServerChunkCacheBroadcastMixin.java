package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import com.xinian.KryptonHybrid.shared.network.broadcast.BroadcastBundleCollector;
import com.xinian.KryptonHybrid.shared.network.broadcast.EntityBundleCollector;
import com.xinian.KryptonHybrid.shared.network.flow.FlushWindows;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the outermost flush window of the tick around the {@code "broadcast"} phase
 * of {@link ServerChunkCache#tickChunks()} and closes it when {@code tickChunks}
 * returns.
 *
 * <p>The broadcast phase calls {@code ChunkHolder.broadcastChanges} for every loaded
 * chunk, fanning out block, section, light and block-entity updates; afterwards the
 * same method runs {@code ChunkMap.tick()} (entity tracking). While the window is open
 * every player's connection only writes, and a {@link BroadcastBundleCollector}
 * window lets {@code ChunkHolderBroadcastMixin} collect the broadcast packets per
 * player. {@code ChunkMapFlushMixin} writes those before entity tracking starts, so
 * the vanilla order is kept, and closing this window flushes each player once for
 * the whole tick (or schedules the micro-batch flush).</p>
 *
 * <p>Debug worlds skip the broadcast phase; there {@code ChunkMap.tick()} manages its
 * own window.</p>
 */
@Mixin(ServerChunkCache.class)
public class ServerChunkCacheBroadcastMixin {

    @Unique
    private boolean krypton$windowOpen;

    @Unique
    private ServerLevel krypton$level() {
        return ((ServerChunkCacheLevelAccessor) this).krypton$getLevel();
    }

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

        FlushWindows.openOutermost(this.krypton$level());
        BroadcastBundleCollector.beginBatch();
        krypton$windowOpen = true;
    }

    @Inject(method = "tickChunks", at = @At("RETURN"))
    private void krypton$afterBroadcast(CallbackInfo ci) {
        if (!krypton$windowOpen) {
            return;
        }
        krypton$windowOpen = false;
        // Normally already written by ChunkMapFlushMixin; covers a tick without entity tracking.
        BroadcastBundleCollector.endBatchAndFlush();
        FlushWindows.closeOutermost(this.krypton$level());
    }
}
