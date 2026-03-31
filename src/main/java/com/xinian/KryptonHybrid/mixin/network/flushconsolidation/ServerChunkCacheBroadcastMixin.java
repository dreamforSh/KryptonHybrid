package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import com.xinian.KryptonHybrid.shared.network.BroadcastBundleCollector;
import com.xinian.KryptonHybrid.shared.network.util.AutoFlushUtil;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wraps the {@code "broadcast"} phase of {@link ServerChunkCache#tickChunks()} with
 * flush consolidation and per-player packet bundling.
 *
 * <h3>Context</h3>
 * <p>In {@code tickChunks()}, after mob spawning and random ticking, Minecraft enters
 * the "broadcast" profiler section where
 * {@link net.minecraft.server.level.ChunkHolder#broadcastChanges} is called for every
 * loaded chunk.  This fans out {@link net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket},
 * {@link net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket},
 * {@link net.minecraft.network.protocol.game.ClientboundLightUpdatePacket}, and
 * {@link net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket}
 * to nearby players via {@code ChunkHolder.broadcast(List, Packet)}.  Without
 * consolidation, each packet causes an independent {@code flush()} syscall.</p>
 *
 * <h3>Optimization</h3>
 * <ol>
 *   <li><strong>Flush consolidation:</strong> auto-flush is disabled for all players
 *       before the broadcast loop and re-enabled after, coalescing all writes into
 *       a single kernel {@code flush()} per player.</li>
 *   <li><strong>Bundle collection:</strong> a {@link BroadcastBundleCollector}
 *       collection window is opened, causing {@code ChunkHolderBroadcastMixin} to
 *       collect packets per-player instead of sending them individually.  At the end,
 *       each player's collected block-change packets are wrapped in a
 *       {@link net.minecraft.network.protocol.game.ClientboundBundlePacket} for
 *       atomic client-side application and better compression.</li>
 * </ol>
 *
 * <h3>Injection strategy</h3>
 * <p>We inject at the {@code profiler.popPush("broadcast")} and
 * {@code profiler.pop()} boundaries that surround the broadcast loop in
 * {@code tickChunks()}.  The {@code CONSTANT} match on the string {@code "broadcast"}
 * gives us a reliable anchor.</p>
 */
@Mixin(ServerChunkCache.class)
public class ServerChunkCacheBroadcastMixin {

    @Shadow @Final
    ServerLevel level;

    /**
     * Inject just before the {@code "broadcast"} profiler section to disable
     * auto-flush and open a bundle collection window.
     */
    @Inject(
            method = "tickChunks",
            at = @At(
                    value = "CONSTANT",
                    args = "stringValue=broadcast",
                    shift = At.Shift.BEFORE
            )
    )
    private void krypton$beforeBroadcast(CallbackInfo ci) {
        // Recovery: flush any stale batch from a previous failed tick
        BroadcastBundleCollector.endBatchAndFlush();

        // Disable auto-flush for all players
        for (var player : this.level.players()) {
            AutoFlushUtil.setAutoFlush(player, true);   // recovery
            AutoFlushUtil.setAutoFlush(player, false);
        }

        // Open bundle collection window
        BroadcastBundleCollector.beginBatch();
    }

    /**
     * Inject at the end of {@code tickChunks()} (after the broadcast loop)
     * to flush all collected packets and re-enable auto-flush.
     */
    @Inject(
            method = "tickChunks",
            at = @At("RETURN")
    )
    private void krypton$afterBroadcast(CallbackInfo ci) {
        // Close the bundle window — sends BundlePackets (buffered, not flushed)
        BroadcastBundleCollector.endBatchAndFlush();

        // Re-enable auto-flush → single kernel flush per player
        for (var player : this.level.players()) {
            AutoFlushUtil.setAutoFlush(player, true);
        }
    }
}

