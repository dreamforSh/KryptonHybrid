package com.xinian.KryptonHybrid.shared.network.broadcast;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;

/**
 * Thread-local cache of <strong>post-Zstd compressed</strong> wire bytes keyed
 * by {@link Packet} identity.  Companion to {@link BroadcastSerializationCache}
 * which caches raw serialized bytes (pre-compression).
 *
 * <h3>Why a separate cache?</h3>
 * <p>{@link BroadcastSerializationCache} eliminates redundant
 * <em>serialization</em> work, but Zstd compression is still re-run for every
 * recipient sharing a Netty I/O thread because the compressor sees only the
 * raw bytes and cannot know they came from the same Packet.  This cache closes
 * that gap by storing the final compressed payload (length-prefix already
 * written) so that the compressor can short-circuit to {@code out.writeBytes}
 * for repeat encounters.</p>
 *
 * <h3>Scope restriction</h3>
 * <p>Only large broadcast packets benefit (chunk + light updates), and only when
 * they were actually compressed. See {@link #isCacheable(Packet)}.</p>
 *
 * <h3>Dictionaries</h3>
 * <p>Each entry remembers the Zstd dictionary it was compressed with; a connection
 * using another one (after a dictionary reload) gets a miss, never foreign bytes.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>Entries live for one server tick (see {@link ThreadLocalIdentityCache}), capped
 * at {@link #MAX_ENTRIES} per thread because compressed chunk packets are large
 * (~8 KB each).</p>
 */
public final class BroadcastCompressedCache {

    /** Cap per-thread entries.  64 x ~8 KB = 0.5 MB worst case. */
    private static final int MAX_ENTRIES = 64;

    private record Wire(byte[] bytes, Object dictionary) {}

    private static final ThreadLocalIdentityCache<Wire> CACHE =
            new ThreadLocalIdentityCache<>(MAX_ENTRIES, () -> KryptonConfig.broadcastCompressedCacheEnabled);

    private BroadcastCompressedCache() {}

    /** Whether this packet type benefits from compressed-output caching. */
    public static boolean isCacheable(Packet<?> packet) {
        return packet instanceof ClientboundLevelChunkWithLightPacket
            || packet instanceof ClientboundLightUpdatePacket;
    }

    /**
     * Wire bytes for {@code packet} compressed with {@code dictionary} (identity; may be
     * {@code null} for no dictionary), or {@code null}.
     */
    public static byte[] get(Object packet, Object dictionary) {
        Wire wire = CACHE.get(packet);
        return wire != null && wire.dictionary() == dictionary ? wire.bytes() : null;
    }

    public static void put(Object packet, Object dictionary, byte[] bytes) {
        CACHE.put(packet, new Wire(bytes, dictionary));
    }

    public static long hits() {
        return CACHE.hits();
    }

    public static long misses() {
        return CACHE.misses();
    }

    public static void resetCounters() {
        CACHE.resetCounters();
    }
}
