package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.EntityBundleCollector;
import com.xinian.KryptonHybrid.shared.network.MicroBatchFlusher;
import com.xinian.KryptonHybrid.shared.network.util.AutoFlushUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds flush consolidation and packet bundle coalescing to {@code ChunkMap.tick()}
 * (the entity tracking tick).
 *
 * <h3>Flush consolidation</h3>
 * <p>During a tracking tick, each entity sends movement/metadata packets to every
 * nearby player.  Without consolidation this results in one {@code flush()} syscall
 * per packet per player.  By disabling auto-flush at the start of the tick and
 * re-enabling it (which triggers a single flush) at the end, all packets for a given
 * player are coalesced into one kernel write, dramatically reducing system-call
 * overhead on busy servers.</p>
 *
 * <h3>Bundle coalescing</h3>
 * <p>In addition to Netty-level flush consolidation, this mixin opens a protocol-level
 * collection window via {@link EntityBundleCollector}.  During the window, the
 * {@link TrackedEntityBundleMixin} redirect collects all per-player entity update
 * packets instead of sending them individually.  At the end of the tick, collected
 * packets are wrapped in {@link net.minecraft.network.protocol.game.ClientboundBundlePacket
 * ClientboundBundlePacket}s and sent as single protocol messages.</p>
 *
 * <h3>Interaction between the two systems</h3>
 * <p>The two optimizations operate at different layers and are complementary:</p>
 * <ol>
 *   <li><strong>Bundle coalescing (protocol layer):</strong> N individual packets
 *       become 1 BundlePacket — reducing per-packet framing overhead and improving
 *       compression ratio (one large payload compresses better than N small ones).</li>
 *   <li><strong>Flush consolidation (Netty layer):</strong> the single BundlePacket
 *       (plus any unbatched packets) is buffered at the channel level and flushed in
 *       one syscall at the end of the tick.</li>
 * </ol>
 *
 * <h3>Execution order within {@code tick()}</h3>
 * <pre>
 *   HEAD:   [1] Recovery: flush stale bundle batch (exception safety)
 *           [2] Recovery: re-enable auto-flush (exception safety)
 *           [3] Disable auto-flush for all players
 *           [4] Open bundle collection window
 *
 *   BODY:   Entity tracking updates → packets collected by TrackedEntityBundleMixin
 *
 *   RETURN: [5] Close bundle window → send BundlePackets (buffered, not flushed)
 *           [6] Re-enable auto-flush → single kernel flush per player
 * </pre>
 *
 * @see EntityBundleCollector
 * @see TrackedEntityBundleMixin
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapFlushMixin {

    @Shadow @Final
    ServerLevel level;

    /**
     * Disable auto-flush for every player and open the bundle collection window
     * before entity tracking updates are sent.
     *
     * <p>The disambiguating descriptor {@code ()V} ensures we target the no-argument
     * entity-tracking {@code tick()} rather than the chunk-loading
     * {@code tick(BooleanSupplier)} overload.</p>
     *
     * <p><strong>Exception recovery (steps 1–2):</strong> a recovery flush of both
     * the bundle collector and auto-flush is performed first, so that any state left
     * by an exception in a previous tick (preventing the RETURN inject from firing)
     * is cleaned up before the new consolidation window begins.</p>
     */
    @Inject(method = "tick()V", at = @At("HEAD"))
    private void tick$disableAutoFlush(CallbackInfo ci) {
        // Step 1: Recovery — flush any stale bundle batch from a previous failed tick
        EntityBundleCollector.endBatchAndFlush();

        // Step 2–3: Recovery re-enable then disable auto-flush
        for (ServerPlayer player : this.level.players()) {
            AutoFlushUtil.setAutoFlush(player, true);
            AutoFlushUtil.setAutoFlush(player, false);
        }

        // Step 4: Open bundle collection window
        EntityBundleCollector.beginBatch();
    }

    /**
     * Close the bundle collection window (sending all BundlePackets) and re-enable
     * auto-flush (triggering a single kernel flush per player) after entity tracking
     * updates have been written to the send buffer.
     *
     * <p><strong>Order matters:</strong> the bundle flush must happen <em>before</em>
     * auto-flush is re-enabled.  Bundle flush writes BundlePackets to the Netty
     * channel (buffered because auto-flush is still disabled).  Auto-flush re-enable
     * then flushes everything in one syscall.</p>
     */
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void tick$reenableAutoFlush(CallbackInfo ci) {
        // Step 5: Close bundle window — BundlePackets are written but not flushed
        EntityBundleCollector.endBatchAndFlush();

        // Step 6: Either schedule a deferred (cross-tick coalescing) flush or
        // re-enable auto-flush immediately.
        if (KryptonConfig.microBatchFlushEnabled) {
            // Keep autoFlush=false; the scheduled task will set it back to true
            // and call channel.flush() once after the configured delay window.
            for (ServerPlayer player : this.level.players()) {
                MicroBatchFlusher.scheduleFlush(player);
            }
        } else {
            // Re-enable auto-flush — triggers single kernel flush
            for (ServerPlayer player : this.level.players()) {
                AutoFlushUtil.setAutoFlush(player, true);
            }
        }
    }
}

