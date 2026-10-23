package com.xinian.KryptonHybrid.shared.network.broadcast;

import com.xinian.KryptonHybrid.shared.network.flow.PacketCoalescer;
import com.xinian.KryptonHybrid.shared.network.motion.MotionDeltaCache;
import com.xinian.KryptonHybrid.shared.network.stats.NetworkTrafficStats;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the per-player packets of the broadcast phase of
 * {@code ServerChunkCache.tickChunks()}: block, section, light and block-entity
 * updates that {@code ChunkHolder.broadcastChanges()} sends through
 * {@code ChunkHolder.broadcast} (redirected by {@code ChunkHolderBroadcastMixin}).
 *
 * <p>Works like {@link EntityBundleCollector}: {@link #endBatchAndFlush()}
 * de-duplicates each player's packets and writes them, several of them as one
 * {@link ClientboundBundlePacket}; the tick's flush window flushes them together with
 * the entity packets once per player. {@code ChunkMapFlushMixin} closes this window before entity
 * tracking starts, so block updates keep their vanilla position ahead of entity
 * updates.</p>
 *
 * @see com.xinian.KryptonHybrid.mixin.network.flushconsolidation.ServerChunkCacheBroadcastMixin
 * @see com.xinian.KryptonHybrid.mixin.network.flushconsolidation.ChunkHolderBroadcastMixin
 */
public final class BroadcastBundleCollector {

    /** Non-null only while a collection window is open. Identity-keyed: one per player. */
    private static final ThreadLocal<Map<ServerPlayer, List<Packet<?>>>> BATCH =
            new ThreadLocal<>();

    private BroadcastBundleCollector() {}

    /** Opens a new collection window. */
    public static void beginBatch() {
        BATCH.set(new IdentityHashMap<>());
    }

    /** Whether a collection window is open on this thread. */
    public static boolean isCollecting() {
        return BATCH.get() != null;
    }

    /**
     * Collects {@code packet} for {@code player} if a window is open.
     *
     * @return {@code true} if collected (the caller must not send it), {@code false}
     *         if no window is open (the caller sends it normally)
     */
    public static boolean collect(ServerPlayer player, Packet<?> packet) {
        Map<ServerPlayer, List<Packet<?>>> batch = BATCH.get();
        if (batch == null) {
            return false;
        }

        List<Packet<?>> list = batch.computeIfAbsent(player, k -> new ArrayList<>());

        // Unwrap existing bundles to prevent nesting
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> sub : bundle.subPackets()) {
                list.add(sub);
            }
        } else {
            list.add(packet);
        }

        return true;
    }

    /**
     * Closes the window and writes each player's collected packets after coalescing.
     * Safe to call when no window is open.
     */
    public static void endBatchAndFlush() {
        Map<ServerPlayer, List<Packet<?>>> batch = BATCH.get();
        BATCH.remove();

        if (batch == null || batch.isEmpty()) {
            return;
        }

        int written = 0;
        for (Map.Entry<ServerPlayer, List<Packet<?>>> entry : batch.entrySet()) {
            ServerPlayer player = entry.getKey();
            List<Packet<?>> packets = entry.getValue();

            PacketCoalescer.coalesce(packets);
            MotionDeltaCache.filter(player.connection, packets);

            if (packets.isEmpty()) {
                continue;
            }
            if (packets.size() == 1) {
                player.connection.send(packets.get(0));
            } else {
                // One bundle, so the client applies the tick's updates together.
                player.connection.send(new ClientboundBundlePacket(
                        (Iterable<Packet<? super ClientGamePacketListener>>) (Iterable<?>) packets
                ));
            }
            written += packets.size();
        }

        NetworkTrafficStats.INSTANCE.recordBatchedPackets(written);
    }
}
