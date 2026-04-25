package com.xinian.KryptonHybrid.shared.network;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.network.ServerPlayerConnection;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-local collector that batches per-player packets during the entity tracking
 * tick and flushes them as {@link ClientboundBundlePacket}s at the end of the tick.
 *
 * <h3>Motivation</h3>
 * <p>During {@code ChunkMap.tick()}, each tracked
 * entity calls {@link net.minecraft.server.level.ServerEntity#sendChanges() sendChanges()},
 * which may emit multiple packets (position, rotation, metadata, velocity, head rotation)
 * to every nearby player via
 * {@code ChunkMap.TrackedEntity.broadcast(Packet)}.  Without bundling, each packet is
 * individually:</p>
 * <ol>
 *   <li>Serialized to bytes (VarInt packet ID + payload)</li>
 *   <li>Framed with a VarInt length prefix (1–3 bytes overhead per packet)</li>
 *   <li>Compressed independently (ZSTD/LZ4/zlib)</li>
 * </ol>
 * <p>Compressing many small payloads independently yields worse ratios than compressing
 * a single concatenated payload of the same total size.  Additionally, each frame carries
 * fixed per-packet overhead (VarInt length prefix, compression header, etc.).</p>
 *
 * <h3>Solution — Tick-scoped per-player packet batching</h3>
 * <p>The collector works in three phases:</p>
 * <ol>
 *   <li><strong>{@link #beginBatch()}:</strong> called at the start of
 *       {@code ChunkMap.tick()} — creates a thread-local
 *       {@code IdentityHashMap&lt;ServerPlayerConnection, List&lt;Packet&gt;&gt;}.</li>
 *   <li><strong>{@link #collect(ServerPlayerConnection, Packet)}:</strong> called by
 *       the {@code TrackedEntityBundleMixin} redirect in
 *       {@code TrackedEntity.broadcast()} — instead of sending each packet immediately,
 *       appends it to the player's collection list.  If the packet is itself a
 *       {@link ClientboundBundlePacket}, its sub-packets are unwrapped and appended
 *       individually to prevent illegal bundle nesting.</li>
 *   <li><strong>{@link #endBatchAndFlush()}:</strong> called at the end of
 *       {@code ChunkMap.tick()} — for each player that has collected packets:
 *       <ul>
 *         <li>Single packet → sent directly (no bundle overhead).</li>
 *         <li>Two or more packets → wrapped in a {@link ClientboundBundlePacket}
 *             and sent as a single protocol message.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h3>Bundle protocol details</h3>
 * <p>{@link ClientboundBundlePacket} is a vanilla Minecraft 1.19.4+ protocol feature.
 * On the wire it is serialized as:</p>
 * <pre>
 *   [BundleDelimiter packet]   — signals "start of bundle"
 *   [Packet₁]
 *   [Packet₂]
 *   ...
 *   [BundleDelimiter packet]   — signals "end of bundle"
 * </pre>
 * <p>The client's {@link net.minecraft.network.PacketBundlePacker PacketBundlePacker}
 * collects all packets between delimiters and delivers them atomically to the game
 * thread.  From the client renderer's perspective, all bundled state changes appear
 * in the same tick — eliminating visual "tearing" (e.g. entity teleporting before
 * its rotation updates).</p>
 *
 * <h3>Performance benefits</h3>
 * <ul>
 *   <li><strong>Reduced framing overhead:</strong> N packets × (1–3 byte length prefix)
 *       is reduced to 2 × (delimiter frame) + 1 × (bundle frame).  For 20 packets
 *       this saves ~40–60 bytes.</li>
 *   <li><strong>Better compression ratio:</strong> one large compressed payload
 *       compresses 10–30% better than N small payloads of the same total size,
 *       because ZSTD/LZ4 can exploit cross-packet redundancy (e.g. similar entity IDs,
 *       repeated VarInt patterns).</li>
 *   <li><strong>Client-side atomicity:</strong> bundled updates are applied in a single
 *       client tick, preventing intermediate visual states.</li>
 * </ul>
 *
 * <h3>Exception safety</h3>
 * <p>If an exception occurs during {@code ChunkMap.tick()} and the RETURN inject
 * does not fire, the thread-local batch remains populated.  The next tick's HEAD
 * inject calls {@link #endBatchAndFlush()} as a recovery step before
 * {@link #beginBatch()}, ensuring stale packets are delivered and the collector is
 * reset to a clean state.</p>
 *
 * <h3>Thread safety</h3>
 * <p>All state is stored in a {@link ThreadLocal}.  {@code ChunkMap.tick()} runs on
 * the server main thread; {@code TrackedEntity.broadcast()} is called synchronously
 * from the same thread.  No cross-thread access occurs.</p>
 *
 * @see net.minecraft.network.protocol.game.ClientboundBundlePacket
 * @see net.minecraft.network.PacketBundleUnpacker
 */
public final class EntityBundleCollector {

    /**
     * Thread-local batch storage.  Non-null only while a collection window is open
     * (between {@link #beginBatch()} and {@link #endBatchAndFlush()}).
     *
     * <p>Uses {@link IdentityHashMap} because {@link ServerPlayerConnection} instances
     * are singletons per player — identity comparison is both correct and faster than
     * {@code equals()} / {@code hashCode()} dispatch through the interface.</p>
     */
    private static final ThreadLocal<Map<ServerPlayerConnection, List<Packet<?>>>> BATCH =
            new ThreadLocal<>();

    private EntityBundleCollector() {}

    /**
     * Opens a new collection window for the current tick.
     *
     * <p>Must be called on the server main thread at the start of
     * {@code ChunkMap.tick()}, <em>after</em> any exception-recovery flush
     * via {@link #endBatchAndFlush()}.</p>
     */
    public static void beginBatch() {
        BATCH.set(new IdentityHashMap<>());
    }

    /**
     * Returns {@code true} if a collection window is currently open on this thread.
     */
    public static boolean isCollecting() {
        return BATCH.get() != null;
    }

    /**
     * Attempts to collect a packet for deferred bundled delivery.
     *
     * <p>If a collection window is open, the packet is appended to the per-player
     * list and the method returns {@code true} (the caller should <em>not</em> send
     * the packet).  If no window is open, returns {@code false} (the caller should
     * send the packet normally).</p>
     *
     * <h4>Bundle nesting prevention</h4>
     * <p>If the incoming packet is already a {@link ClientboundBundlePacket} (e.g.
     * from {@link net.minecraft.server.level.ServerEntity#sendChanges()} which wraps
     * {@code SetEntityMotion + ProjectilePower} in a bundle), its sub-packets are
     * extracted and appended individually.  This prevents illegal bundle nesting
     * which would corrupt the protocol stream.</p>
     *
     * @param conn   the player connection to batch for
     * @param packet the packet to collect
     * @return {@code true} if the packet was collected (caller must NOT send);
     *         {@code false} if no batch is active (caller must send normally)
     */
    @SuppressWarnings("unchecked")
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
     * Closes the current collection window and sends all collected packets.
     *
     * <p>For each player connection with collected packets:</p>
     * <ul>
     *   <li><strong>0 packets:</strong> nothing is sent (no-op).</li>
     *   <li><strong>1 packet:</strong> sent directly without bundle wrapping to
     *       avoid the 2-delimiter overhead.</li>
     *   <li><strong>2+ packets:</strong> wrapped in a {@link ClientboundBundlePacket}
     *       and sent as a single protocol message.</li>
     * </ul>
     *
     * <p>This method is safe to call even if no batch is active (e.g. during
     * exception recovery) — it simply returns immediately.</p>
     */
    @SuppressWarnings("unchecked")
    public static void endBatchAndFlush() {
        Map<ServerPlayerConnection, List<Packet<?>>> batch = BATCH.get();
        BATCH.remove();

        if (batch == null || batch.isEmpty()) {
            return;
        }

        int batchPlayers = 0;
        int emittedBundles = 0;
        int packetsInBundles = 0;

        for (Map.Entry<ServerPlayerConnection, List<Packet<?>>> entry : batch.entrySet()) {
            ServerPlayerConnection conn = entry.getKey();
            List<Packet<?>> packets = entry.getValue();

            // P1-③ Packet coalescing: deduplicate redundant packets before sending
            PacketCoalescer.coalesce(packets);

            if (packets.isEmpty()) {
                continue;
            }
            batchPlayers++;

            if (packets.size() == 1) {
                // Single packet: send directly, no bundle overhead
                conn.send(packets.get(0));
            } else {
                emittedBundles++;
                packetsInBundles += packets.size();
                // Multiple packets: wrap in a BundlePacket
                conn.send(new ClientboundBundlePacket(
                        (Iterable<Packet<? super ClientGamePacketListener>>) (Iterable<?>) packets
                ));
            }
        }

        NetworkTrafficStats.INSTANCE.recordBundleBatch(batchPlayers, emittedBundles, packetsInBundles);
    }
}

