package com.xinian.KryptonHybrid.shared.network.chunk;

import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import com.xinian.KryptonHybrid.shared.KryptonConfig;

import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/**
 * Per-player delayed chunk cache (DCC) for Krypton Hybrid.
 *
 * <p>Buffers "forget chunk" events instead of sending them immediately. If the
 * player re-enters a cached chunk's range before the entry expires, the chunk
 * resend is skipped entirely??he client still has the data. Entries are evicted
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

    /**
     * Per-player cache state. Entries are scoped to a single dimension
     * ({@code ChunkPos.toLong()} collides across dimensions, so an
     * Overworld (5,5) and a Nether (5,5) would otherwise share a cache slot
     * and cause "no chunks display after portal travel"). When a DCC call
     * arrives for a player whose recorded dimension differs from the call's
     * dimension, the entries are silently cleared and the player is rebound
     * to the new dimension: the client unloaded the old dim's chunks during
     * dim change, so a cache hit there would be a false positive, and sending
     * forget-packets for old-dim chunk pos against the new dim would target
     * unrelated chunks.
     */
    private static final class PlayerEntry {
        ResourceKey<Level> dimension;
        final Long2LongLinkedOpenHashMap entries = new Long2LongLinkedOpenHashMap();

        PlayerEntry(ResourceKey<Level> dimension) {
            this.dimension = dimension;
            this.entries.defaultReturnValue(ABSENT);
        }
    }

    /** Per-player state; WeakHashMap for automatic GC on disconnect. */
    private final WeakHashMap<ServerPlayer, PlayerEntry> perPlayerCache = new WeakHashMap<>();

    private DelayedChunkCache() {}

    /**
     * Called when a chunk leaves the player's view. Caches the chunk if eligible.
     * Returns {@code true} if cached (caller must skip {@code untrackChunk}).
     */
    public boolean onChunkLeave(ServerPlayer player, ResourceKey<Level> dimension, ChunkPos pos) {
        if (!KryptonConfig.dccEnabled) return false;

        // Only cache chunks within the configured extra-distance radius.
        int maxDist = KryptonConfig.dccDistance;
        ChunkPos playerChunk = player.chunkPosition();
        int dx = pos.x - playerChunk.x;
        int dz = pos.z - playerChunk.z;
        if (dx * dx + dz * dz > maxDist * maxDist) return false;

        PlayerEntry e = perPlayerCache.computeIfAbsent(player, k -> new PlayerEntry(dimension));
        if (!e.dimension.equals(dimension)) {
            e.entries.clear();
            e.dimension = dimension;
        }

        // If the cache is already at the size limit, skip caching this chunk.
        if (e.entries.size() >= KryptonConfig.dccSizeLimit) return false;

        e.entries.put(pos.toLong(), System.currentTimeMillis());
        return true;
    }

    /**
     * Called when a chunk enters the player's view. Returns {@code true} on cache-hit
     * (client already has the data; caller must skip the chunk resend).
     */
    public boolean onChunkEnter(ServerPlayer player, ResourceKey<Level> dimension, ChunkPos pos) {
        if (!KryptonConfig.dccEnabled) return false;

        PlayerEntry e = perPlayerCache.get(player);
        if (e == null) return false;

        if (!e.dimension.equals(dimension)) {
            // Stale cache from a previous dimension ??client has already unloaded
            // those chunks. Treat as miss and reset the slot for the new dim.
            e.entries.clear();
            e.dimension = dimension;
            return false;
        }

        // remove() returns ABSENT (-1) when the key is not present.
        return e.entries.remove(pos.toLong()) != ABSENT;
    }

    /**
     * Evicts timed-out or out-of-range cache entries and calls {@code evictCallback}
     * for each, allowing the caller to perform deferred {@code untrackChunk} calls.
     *
     * @param dimension the dimension whose ChunkMap is ticking; entries cached under
     *                  a different dimension are silently dropped (no callback) since
     *                  emitting forget-packets for them would address the wrong chunks.
     */
    public void tick(ResourceKey<Level> dimension,
                     Iterable<ServerPlayer> players,
                     BiConsumer<ServerPlayer, ChunkPos> evictCallback) {
        if (!KryptonConfig.dccEnabled) return;

        long now       = System.currentTimeMillis();
        long timeoutMs = (long) KryptonConfig.dccTimeoutSeconds * 1000L;

        for (ServerPlayer player : players) {
            PlayerEntry e = perPlayerCache.get(player);
            if (e == null || e.entries.isEmpty()) continue;

            if (!e.dimension.equals(dimension)) {
                // Player returned to this dim after leaving for another;
                // any entries here are stale (cached in the old dim before the
                // player moved away). Silent clear ??see PlayerEntry javadoc.
                e.entries.clear();
                e.dimension = dimension;
                continue;
            }

            int maxDist    = KryptonConfig.dccDistance;
            int maxDistSq  = maxDist * maxDist;
            ChunkPos pChunk = player.chunkPosition();
            int px = pChunk.x;
            int pz = pChunk.z;

            ObjectIterator<Long2LongMap.Entry> iter = Long2LongMaps.fastIterator(e.entries);
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
}

