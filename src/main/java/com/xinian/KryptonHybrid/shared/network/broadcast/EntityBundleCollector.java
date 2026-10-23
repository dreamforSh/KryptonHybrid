package com.xinian.KryptonHybrid.shared.network.broadcast;

import com.xinian.KryptonHybrid.shared.network.flow.PacketCoalescer;
import com.xinian.KryptonHybrid.shared.network.motion.MotionDeltaCache;
import com.xinian.KryptonHybrid.shared.network.stats.NetworkTrafficStats;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.network.ServerPlayerConnection;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the per-player packets of the entity-tracking tick and writes them at the
 * end of the tick.
 *
 * <p>During {@code ChunkMap.tick()} every tracked entity's {@code sendChanges()} fans
 * position, rotation, velocity, head and metadata updates out to nearby players
 * through {@code ChunkMap.TrackedEntity.broadcast}, which {@code TrackedEntityBundleMixin}
 * redirects into {@link #collect}. At {@link #endBatchAndFlush()} each player's list
 * is de-duplicated by {@link PacketCoalescer} and the motion delta filter, and the
 * remaining packets are written, several of them as one {@link ClientboundBundlePacket}
 * so the client applies them together; the tick's flush window then flushes them once
 * per player (see {@code FlushWindows}).</p>
 *
 * <p>If an exception skips the closing call, the next tick's opening calls
 * {@link #endBatchAndFlush()} first, so stale packets are delivered.</p>
 *
 * <p>All state is thread-local; collection happens on the server thread.</p>
 */
public final class EntityBundleCollector {

    /**
     * Non-null only while a collection window is open. Identity-keyed: connections are
     * one per player.
     */
    private static final ThreadLocal<Map<ServerPlayerConnection, List<Packet<?>>>> BATCH =
            new ThreadLocal<>();

    private EntityBundleCollector() {}

    /** Opens a collection window for the current tick. */
    public static void beginBatch() {
        BATCH.set(new IdentityHashMap<>());
    }

    /** Whether a collection window is open on this thread. */
    public static boolean isCollecting() {
        return BATCH.get() != null;
    }

    /**
     * Collects {@code packet} for {@code conn} if a window is open.
     *
     * @return {@code true} if collected (the caller must not send it), {@code false}
     *         if no window is open (the caller sends it normally)
     */
    public static boolean collect(ServerPlayerConnection conn, Packet<?> packet) {
        Map<ServerPlayerConnection, List<Packet<?>>> batch = BATCH.get();
        if (batch == null) {
            return false;
        }

        List<Packet<?>> list = batch.computeIfAbsent(conn, k -> new ArrayList<>());

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
        Map<ServerPlayerConnection, List<Packet<?>>> batch = BATCH.get();
        BATCH.remove();

        if (batch == null || batch.isEmpty()) {
            return;
        }

        int written = 0;
        for (Map.Entry<ServerPlayerConnection, List<Packet<?>>> entry : batch.entrySet()) {
            ServerPlayerConnection conn = entry.getKey();
            List<Packet<?>> packets = entry.getValue();

            PacketCoalescer.coalesce(packets);
            MotionDeltaCache.filter(conn, packets);

            if (packets.isEmpty()) {
                continue;
            }
            if (packets.size() == 1) {
                conn.send(packets.get(0));
            } else {
                // One bundle, so the client applies the tick's updates together.
                conn.send(new ClientboundBundlePacket(
                        (Iterable<Packet<ClientGamePacketListener>>) (Iterable<?>) packets
                ));
            }
            written += packets.size();
        }

        NetworkTrafficStats.INSTANCE.recordBatchedPackets(written);
    }
}
