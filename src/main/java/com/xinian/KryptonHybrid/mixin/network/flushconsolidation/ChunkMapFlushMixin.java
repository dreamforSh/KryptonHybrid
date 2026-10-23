package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import com.xinian.KryptonHybrid.shared.network.broadcast.BroadcastBundleCollector;
import com.xinian.KryptonHybrid.shared.network.broadcast.EntityBundleCollector;
import com.xinian.KryptonHybrid.shared.network.flow.FlushWindows;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Batches the entity-tracking tick, {@code ChunkMap.tick()}.
 *
 * <p>Per-player entity packets are collected by {@link TrackedEntityBundleMixin} into
 * {@link EntityBundleCollector}, which de-duplicates them ({@code PacketCoalescer},
 * motion delta filter) and writes the survivors at the end of the tick, several of
 * them as one {@code ClientboundBundlePacket}; the gain is fewer packets, updates the
 * client applies together, and one flush per player instead of one per packet.</p>
 *
 * <h3>Nesting</h3>
 * <p>In a normal world this tick runs inside the broadcast window that
 * {@code ServerChunkCache.tickChunks} opens (see {@link ServerChunkCacheBroadcastMixin}),
 * which already holds every player's flush. In that case this mixin first writes the
 * block, light and block-entity updates collected so far (vanilla sends those before
 * entity tracking) and leaves the flush to the outer window. Only when it runs on its
 * own (debug worlds) does it open and close a flush window itself.</p>
 *
 * <pre>
 *   HEAD:   nested?  write collected broadcast packets
 *           else     open the outermost flush window
 *           open the entity collection window
 *   BODY:   entity tracking; packets collected by TrackedEntityBundleMixin
 *   RETURN: write collected entity packets (not flushed)
 *           not nested? close the flush window: one flush per player
 * </pre>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapFlushMixin {

    /** Whether the current tick runs inside the tickChunks broadcast window. */
    @Unique
    private boolean krypton$nested;

    @Shadow @Final
    ServerLevel level;

    /**
     * The descriptor {@code ()V} targets the entity-tracking {@code tick()} rather than
     * the chunk-loading {@code tick(BooleanSupplier)} overload.
     */
    @Inject(method = "tick()V", at = @At("HEAD"))
    private void tick$openWindow(CallbackInfo ci) {
        // Recovery: deliver anything a previous tick left behind after an exception.
        EntityBundleCollector.endBatchAndFlush();

        krypton$nested = BroadcastBundleCollector.isCollecting();
        if (krypton$nested) {
            BroadcastBundleCollector.endBatchAndFlush();
        } else {
            FlushWindows.openOutermost(this.level);
        }

        EntityBundleCollector.beginBatch();
    }

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void tick$closeWindow(CallbackInfo ci) {
        EntityBundleCollector.endBatchAndFlush();
        if (!krypton$nested) {
            FlushWindows.closeOutermost(this.level);
        }
    }
}
