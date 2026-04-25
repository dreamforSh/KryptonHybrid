package com.xinian.KryptonHybrid.shared.network;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;

import java.util.IdentityHashMap;

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
 * <p>Only large broadcast packets benefit (chunk + light updates).  Caching
 * every packet would waste memory and inflate the LRU.  See
 * {@link #isCacheable(Packet)}.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>Same as the parent cache — {@link ThreadLocal}, capped at
 * {@link #MAX_ENTRIES} per thread, full-clear on overflow.  Smaller cap (64)
 * because compressed chunk packets are large (~8 KB each) and we don't want
 * unbounded heap retention.</p>
 */
public final class BroadcastCompressedCache {

    /** Cap per-thread entries.  64 × ~8 KB ≈ 0.5 MB worst case. */
    private static final int MAX_ENTRIES = 64;

    private static final ThreadLocal<IdentityHashMap<Object, byte[]>> CACHE =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private BroadcastCompressedCache() {}

    /** Whether this packet type benefits from compressed-output caching. */
    public static boolean isCacheable(Packet<?> packet) {
        return packet instanceof ClientboundLevelChunkWithLightPacket
            || packet instanceof ClientboundLightUpdatePacket;
    }

    public static byte[] get(Object packet) {
        if (!KryptonConfig.broadcastCompressedCacheEnabled) return null;
        return CACHE.get().get(packet);
    }

    public static void put(Object packet, byte[] bytes) {
        if (!KryptonConfig.broadcastCompressedCacheEnabled) return;
        IdentityHashMap<Object, byte[]> map = CACHE.get();
        if (map.size() >= MAX_ENTRIES) map.clear();
        map.put(packet, bytes);
    }
}

