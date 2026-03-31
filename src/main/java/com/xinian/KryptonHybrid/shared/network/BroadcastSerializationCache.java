package com.xinian.KryptonHybrid.shared.network;

import com.xinian.KryptonHybrid.shared.KryptonConfig;

import java.util.IdentityHashMap;

/**
 * Thread-local cache that avoids redundant packet serialization when the same
 * {@link net.minecraft.network.protocol.Packet} instance is encoded by
 * {@link net.minecraft.network.PacketEncoder} on the same Netty I/O thread for
 * multiple connections.
 *
 * <h3>How it works</h3>
 * <p>Minecraft's broadcast pattern sends the <strong>same Packet object reference</strong>
 * to N players via {@code ChunkHolder.broadcast(List, Packet)} and
 * {@code TrackedEntity.broadcast(Packet)}.  Each connection has its own
 * {@link net.minecraft.network.PacketEncoder}, but connections sharing the same
 * Netty I/O event-loop thread will encode the same Packet instance sequentially.
 * By caching the serialized bytes keyed on object identity, subsequent encodes
 * of the same Packet on the same thread become a simple byte copy — skipping all
 * VarInt/NBT/collection serialization.</p>
 *
 * <h3>Thread safety</h3>
 * <p>All state is {@link ThreadLocal}.  With {@code NioEventLoopGroup(0)}, Netty
 * creates {@code availableProcessors × 2} I/O threads.  Each thread handles a
 * subset of connections.  The cache is effective within each thread's connection
 * set — typically {@code totalPlayers / (2 × cores)} connections per thread.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>The cache is bounded by {@link #MAX_ENTRIES}.  When the limit is reached,
 * the entire map is cleared.  Because Packet objects are short-lived (created each
 * tick, unreferenced after the pipeline flush), the IdentityHashMap entries
 * naturally become stale and don't prevent GC once cleared.</p>
 *
 * @see PacketEncoderMixin
 */
public final class BroadcastSerializationCache {

    /** Maximum cached entries before a full clear.  256 covers a typical tick. */
    private static final int MAX_ENTRIES = 256;

    private static final ThreadLocal<IdentityHashMap<Object, byte[]>> CACHE =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private BroadcastSerializationCache() {}

    /**
     * Looks up cached serialized bytes for the given packet instance.
     *
     * @param packet the Packet object (identity-compared)
     * @return cached bytes, or {@code null} if not cached
     */
    public static byte[] get(Object packet) {
        if (!KryptonConfig.broadcastCacheEnabled) return null;
        return CACHE.get().get(packet);
    }

    /**
     * Stores the serialized bytes for a packet instance.
     *
     * @param packet the Packet object (identity key)
     * @param bytes  the serialized bytes (packet ID + payload)
     */
    public static void put(Object packet, byte[] bytes) {
        if (!KryptonConfig.broadcastCacheEnabled) return;
        IdentityHashMap<Object, byte[]> map = CACHE.get();
        if (map.size() >= MAX_ENTRIES) {
            map.clear();
        }
        map.put(packet, bytes);
    }

    /** Clears the entire cache for the current thread. */
    public static void clear() {
        CACHE.get().clear();
    }
}

