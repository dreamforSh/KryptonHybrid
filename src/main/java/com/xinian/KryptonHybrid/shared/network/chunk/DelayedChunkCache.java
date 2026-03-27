package com.xinian.KryptonHybrid.shared.network.chunk;

import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import com.xinian.KryptonHybrid.shared.KryptonConfig;

import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/**
 * Per-player delayed chunk cache (DCC) for Krypton Hybrid.
 *
 * <p>Buffers "forget chunk" events instead of sending them immediately. If the
 * player re-enters a cached chunk's range before the entry expires, the chunk
 * resend is skipped entirely鈥攖he client still has the data. Entries are evicted
 * by timeout, distance, or size limit. All access is server-thread-only.</p>
 *
 * <p>Adapted from NotEnoughBandwidth's {@code CachedChunkTrackingView} for the
 * 1.19.2 {@code ChunkMap.updateChunkTracking} API.</p>
 */
public final class DelayedChunkCache {

    /** Shared singleton; chunk tracking is per-level but player objects are global. */
    public static final DelayedChunkCache INSTANCE = new DelayedChunkCache();

    /** Default-return sentinel; timestamps are always positive so {@code -1} is safe. */
    private static final long ABSENT = -1L;

    /** packed ChunkPos 鈫?departure-timestamp per player; WeakHashMap for automatic GC on disconnect. */
    private final WeakHashMap<ServerPlayer, Long2LongLinkedOpenHashMap> perPlayerCache =
            new WeakHashMap<>();

    private DelayedChunkCache() {}

    /**
     * Called when a chunk leaves the player's view. Caches the chunk if eligible.
     * Returns {@code true} if cached (caller must skip {@code untrackChunk}).
     */
    public boolean onChunkLeave(ServerPlayer player, ChunkPos pos) {
        if (!KryptonConfig.dccEnabled) return false;

        // Only cache chunks within the configured extra-distance radius.
        int maxDist = KryptonConfig.dccDistance;
        ChunkPos playerChunk = player.chunkPosition();
        int dx = pos.x - playerChunk.x;
        int dz = pos.z - playerChunk.z;
        if (dx * dx + dz * dz > maxDist * maxDist) return false;

        Long2LongLinkedOpenHashMap map = getOrCreateMap(player);

        // If the cache is already at the size limit, skip caching this chunk.
        if (map.size() >= KryptonConfig.dccSizeLimit) return false;

        map.put(pos.toLong(), System.currentTimeMillis());
        return true;
    }

    /**
     * Called when a chunk enters the player's view. Returns {@code true} on cache-hit
     * (client already has the data; caller must skip the chunk resend).
     */
    public boolean onChunkEnter(ServerPlayer player, ChunkPos pos) {
        if (!KryptonConfig.dccEnabled) return false;

        Long2LongLinkedOpenHashMap map = perPlayerCache.get(player);
        if (map == null) return false;

        // remove() returns ABSENT (-1) when the key is not present.
        return map.remove(pos.toLong()) != ABSENT;
    }

    /**
     * Evicts timed-out or out-of-range cache entries and calls {@code evictCallback}
     * for each, allowing the caller to perform deferred {@code untrackChunk} calls.
     */
    public void tick(Iterable<ServerPlayer> players,
                     BiConsumer<ServerPlayer, ChunkPos> evictCallback) {
        if (!KryptonConfig.dccEnabled) return;

        long now       = System.currentTimeMillis();
        long timeoutMs = (long) KryptonConfig.dccTimeoutSeconds * 1000L;

        for (ServerPlayer player : players) {
            Long2LongLinkedOpenHashMap map = perPlayerCache.get(player);
            if (map == null || map.isEmpty()) continue;

            int maxDist    = KryptonConfig.dccDistance;
            int maxDistSq  = maxDist * maxDist;
            ChunkPos pChunk = player.chunkPosition();
            int px = pChunk.x;
            int pz = pChunk.z;

            ObjectIterator<Long2LongMap.Entry> iter = Long2LongMaps.fastIterator(map);
            while (iter.hasNext()) {
                Long2LongMap.Entry entry = iter.next();
                long packedPos = entry.getLongKey();
                long timestamp = entry.getLongValue();

                ChunkPos pos = new ChunkPos(packedPos);
                int dx = pos.x - px;
                int dz = pos.z - pz;
                boolean tooFar  = (dx * dx + dz * dz) > maxDistSq;
                boolean expired = (now - timestamp) >= timeoutMs;

                if (tooFar || expired) {
                    iter.remove();
                    evictCallback.accept(player, pos);
                }
            }
        }
    }

    private Long2LongLinkedOpenHashMap getOrCreateMap(ServerPlayer player) {
        return perPlayerCache.computeIfAbsent(player, k -> {
            Long2LongLinkedOpenHashMap m = new Long2LongLinkedOpenHashMap();
            m.defaultReturnValue(ABSENT);
            return m;
        });
    }
}

