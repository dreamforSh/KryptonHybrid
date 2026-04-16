package com.xinian.KryptonHybrid.shared.network;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-local collector that batches per-player packets during the
 * {@code ChunkHolder.broadcastChanges()} phase and flushes them as
 * {@link ClientboundBundlePacket}s at the end of the broadcast loop.
 *
 * <h3>Motivation</h3>
 * <p>{@code ChunkHolder.broadcastChanges()} emits block update packets
 * ({@code ClientboundSectionBlocksUpdatePacket}, {@code ClientboundBlockUpdatePacket},
 * {@code ClientboundBlockEntityDataPacket}, {@code ClientboundLightUpdatePacket})
 * for each chunk that has pending changes.  These are sent individually via
 * {@code ChunkHolder.broadcast(List, Packet)}, resulting in:</p>
 * <ul>
 *   <li>Per-packet compression (poor ratio for small payloads)</li>
 *   <li>Per-packet framing overhead (VarInt length prefix per packet)</li>
 *   <li>Multiple independent sends to the same player from different chunks</li>
 * </ul>
 *
 * <h3>Solution</h3>
 * <p>This collector works identically to {@link EntityBundleCollector} but is scoped
 * to the broadcast phase of {@code ServerChunkCache.tickChunks()}:</p>
 * <ol>
 *   <li>{@link #beginBatch()} — opens a collection window</li>
 *   <li>{@link #collect(ServerPlayer, Packet)} — appends packets per player</li>
 *   <li>{@link #endBatchAndFlush()} — wraps &ge;2 packets in a BundlePacket and sends</li>
 * </ol>
 *
 * @see com.xinian.KryptonHybrid.mixin.network.flushconsolidation.ServerChunkCacheBroadcastMixin
 * @see com.xinian.KryptonHybrid.mixin.network.flushconsolidation.ChunkHolderBroadcastMixin
 */
public final class BroadcastBundleCollector {

    /**
     * Thread-local batch storage.  Non-null only while a collection window is open.
     * Uses {@link IdentityHashMap} because {@link ServerPlayer} instances are
     * singletons — identity comparison is correct and fast.
     */
    private static final ThreadLocal<Map<ServerPlayer, List<Packet<?>>>> BATCH =
            new ThreadLocal<>();

    private BroadcastBundleCollector() {}

    /** Opens a new collection window. */
    public static void beginBatch() {
        BATCH.set(new IdentityHashMap<>());
    }

    /** Returns {@code true} if a collection window is currently open. */
    public static boolean isCollecting() {
        return BATCH.get() != null;
    }

    /**
     * Collects a packet for deferred bundled delivery.
     *
     * @param player the target player
     * @param packet the packet to collect
     * @return {@code true} if collected (caller must NOT send);
     *         {@code false} if no batch is active (caller must send normally)
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
     * Closes the current collection window and sends all collected packets.
     * <ul>
     *   <li>0 packets: no-op</li>
     *   <li>1 packet: sent directly without bundle overhead</li>
     *   <li>2+ packets: wrapped in a {@link ClientboundBundlePacket}</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public static void endBatchAndFlush() {
        Map<ServerPlayer, List<Packet<?>>> batch = BATCH.get();
        BATCH.remove();

        if (batch == null || batch.isEmpty()) {
            return;
        }

        for (Map.Entry<ServerPlayer, List<Packet<?>>> entry : batch.entrySet()) {
            ServerPlayer player = entry.getKey();
            List<Packet<?>> packets = entry.getValue();

            if (packets.isEmpty()) {
                continue;
            }

            // Coalesce redundant packets (head-rot, motion, data, block-entity dedup)
            PacketCoalescer.coalesce(packets);

            if (packets.isEmpty()) {
                continue;
            }

            if (packets.size() == 1) {
                player.connection.send(packets.get(0));
            } else {
                player.connection.send(new ClientboundBundlePacket(
                        (Iterable<Packet<? super ClientGamePacketListener>>) (Iterable<?>) packets
                ));
            }
        }
    }
}

