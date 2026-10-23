package com.xinian.KryptonHybrid.shared.network.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import com.xinian.KryptonHybrid.shared.KryptonConfig;

import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/**
 * Per-client delayed chunk cache (DCC) for Krypton Hybrid.
 *
 * <p>Buffers "forget chunk" events instead of sending them immediately. If the
 * player re-enters a cached chunk's range before the entry expires, and the chunk has
 * not changed since it left the player's view, the chunk resend is skipped: the client
 * still has the data. All access is server-thread-only.</p>
 *
 * <h3>What the client has</h3>
 * <p>State is kept per connection, not per {@code ServerPlayer}: a respawn creates a new
 * player object (with the same entity id, and entities compare by id) but keeps the
 * connection, and the client keeps its level and chunks unless the dimension changed.
 * Each client records the chunks it was actually sent ({@link #onSent}) and not told to
 * forget ({@link #onForgotten}); only those can be kept. A chunk that entered view but
 * left again before it was ever sent (still loading) is untracked normally, so a later
 * return sends it instead of assuming the client has it. When the player shows up in a
 * new dimension, the client has switched to a new, empty level and everything recorded
 * is dropped.</p>
 *
 * <h3>Distance</h3>
 * <p>The client stores chunks within {@code max(2, viewDistance) + 3} of its view center
 * (vanilla {@code ClientChunkCache}); it ignores "forget" packets for chunks beyond that
 * and cannot show them. Vanilla stops tracking a chunk about that far away on the axes,
 * so kept chunks are evicted as soon as the next view center would put them outside the
 * client's range: before the center moves ({@link #evictOutsideClientRange}), so the
 * "forget" still reaches the client.</p>
 *
 * <h3>Freshness</h3>
 * <p>While a chunk is cached the player is not among its trackers, so its block, light
 * and block-entity updates are not sent. Each entry records a {@link ChunkChangeStamp}
 * when the chunk leaves; a chunk stamped later is {@link Result#STALE} and is resent.</p>
 *
 * <p>Adapted from NotEnoughBandwidth's {@code CachedChunkTrackingView} for the
 * 1.21.1 {@code ChunkMap.applyChunkTrackingView} API.</p>
 */
public final class DelayedChunkCache {

    /** Shared singleton; chunk tracking is per-level but connections are global. */
    public static final DelayedChunkCache INSTANCE = new DelayedChunkCache();

    /** Outcome of a chunk re-entering a player's view. */
    public enum Result {
        /** Not cached: send it normally. */
        NOT_CACHED,
        /** Cached and unchanged: the client still has the current data. */
        HIT,
        /** Cached, but changed while away (or DCC was switched off): resend it. */
        STALE
    }

    private record Entry(long cachedAtMs, long stamp) {}

    /** What one client holds in its current level. */
    private static final class ClientState {
        ResourceKey<Level> dimension;
        /** Chunks sent to the client and not forgotten since. */
        final LongOpenHashSet sent = new LongOpenHashSet();
        /** Sent chunks that left the player's view but are kept on the client. */
        final Long2ObjectLinkedOpenHashMap<Entry> cached = new Long2ObjectLinkedOpenHashMap<>();

        ClientState(ResourceKey<Level> dimension) {
            this.dimension = dimension;
        }

        /** A different dimension means the client is in a new, empty level. */
        void bind(ResourceKey<Level> dimension) {
            if (!this.dimension.equals(dimension)) {
                sent.clear();
                cached.clear();
                this.dimension = dimension;
            }
        }
    }

    /** Per connection; WeakHashMap drops a client's state once it disconnects. */
    private final WeakHashMap<ServerGamePacketListenerImpl, ClientState> clients = new WeakHashMap<>();

    private DelayedChunkCache() {}

    private ClientState state(ServerPlayer player, ResourceKey<Level> dimension, boolean create) {
        ServerGamePacketListenerImpl connection = player.connection;
        if (connection == null) {
            return null;
        }
        ClientState state = clients.get(connection);
        if (state == null) {
            if (!create) {
                return null;
            }
            state = new ClientState(dimension);
            clients.put(connection, state);
        }
        state.bind(dimension);
        return state;
    }

    /** The client's chunk storage radius ({@code ClientChunkCache.calculateStorageRange}). */
    private static int clientRadius(ServerPlayer player) {
        return Math.max(2, player.server.getPlayerList().getViewDistance()) + 3;
    }

    private static boolean withinClientRange(long packedPos, int centerX, int centerZ, int radius) {
        return Math.abs(ChunkPos.getX(packedPos) - centerX) <= radius
                && Math.abs(ChunkPos.getZ(packedPos) - centerZ) <= radius;
    }

    /** A chunk packet went to the player ({@code PlayerChunkSender.sendChunk}). */
    public void onSent(ServerPlayer player, ChunkPos pos) {
        ClientState state = state(player, player.serverLevel().dimension(), true);
        if (state != null) {
            state.sent.add(pos.toLong());
        }
    }

    /** The player was told to forget a chunk ({@code PlayerChunkSender.dropChunk} sent the packet). */
    public void onForgotten(ServerPlayer player, ChunkPos pos) {
        ClientState state = state(player, player.serverLevel().dimension(), false);
        if (state != null) {
            long packed = pos.toLong();
            state.sent.remove(packed);
            state.cached.remove(packed);
        }
    }

    /**
     * Called when a chunk leaves the player's view. Caches the chunk if the client has it
     * and can keep it. Returns {@code true} if cached (caller must skip dropping it).
     */
    public boolean onChunkLeave(ServerPlayer player, ResourceKey<Level> dimension, ChunkPos pos) {
        if (!KryptonConfig.dccEnabled) return false;

        ClientState state = state(player, dimension, false);
        if (state == null) return false;

        long packed = pos.toLong();
        // A chunk still loading when it left view never reached the client.
        if (!state.sent.contains(packed)) return false;

        SectionPos center = player.getLastSectionPos();
        if (!withinClientRange(packed, center.x(), center.z(), clientRadius(player))) return false;

        // If the cache is already at the size limit, skip caching this chunk.
        if (state.cached.size() >= KryptonConfig.dccSizeLimit) return false;

        state.cached.put(packed, new Entry(System.currentTimeMillis(), ChunkChangeStamp.next()));
        return true;
    }

    /**
     * Called when a chunk enters the player's view.
     *
     * @param chunk the chunk now, or {@code null} if it is not loaded as a full chunk
     */
    public Result onChunkEnter(ServerPlayer player, ResourceKey<Level> dimension, ChunkPos pos,
                               LevelChunk chunk) {
        ClientState state = state(player, dimension, false);
        if (state == null) return Result.NOT_CACHED;

        Entry entry = state.cached.remove(pos.toLong());
        if (entry == null) return Result.NOT_CACHED;
        if (!KryptonConfig.dccEnabled || chunk == null
                || ((ChunkChangeStamp) chunk).krypton$changeStamp() > entry.stamp()) {
            return Result.STALE;
        }
        return Result.HIT;
    }

    /**
     * Evicts kept chunks that would be outside the client's range around the view center
     * ({@code centerX}, {@code centerZ}). Call before the player's view center moves there,
     * so the "forget" packets reach the client while it can still apply them.
     */
    public void evictOutsideClientRange(ServerPlayer player, ResourceKey<Level> dimension,
                                        int centerX, int centerZ,
                                        BiConsumer<ServerPlayer, ChunkPos> evictCallback) {
        ClientState state = state(player, dimension, false);
        if (state == null || state.cached.isEmpty()) return;

        int radius = clientRadius(player);
        LongArrayList evicted = null;
        ObjectIterator<Long2ObjectMap.Entry<Entry>> iter = Long2ObjectMaps.fastIterator(state.cached);
        while (iter.hasNext()) {
            long packedPos = iter.next().getLongKey();
            if (!withinClientRange(packedPos, centerX, centerZ, radius)) {
                iter.remove();
                if (evicted == null) evicted = new LongArrayList();
                evicted.add(packedPos);
            }
        }
        if (evicted != null) {
            for (int i = 0; i < evicted.size(); i++) {
                evictCallback.accept(player, new ChunkPos(evicted.getLong(i)));
            }
        }
    }

    /**
     * Evicts timed-out or out-of-range cache entries (all of them while DCC is switched
     * off) and calls {@code evictCallback} for each, allowing the caller to perform
     * deferred chunk drops.
     *
     * @param dimension the dimension whose ChunkMap is ticking; a client recorded in another
     *                  dimension has switched levels and is reset silently (no callback),
     *                  since forget-packets for its old chunks would address the wrong chunks.
     */
    public void tick(ResourceKey<Level> dimension,
                     Iterable<ServerPlayer> players,
                     BiConsumer<ServerPlayer, ChunkPos> evictCallback) {
        long now       = System.currentTimeMillis();
        long timeoutMs = (long) KryptonConfig.dccTimeoutSeconds * 1000L;
        boolean enabled = KryptonConfig.dccEnabled;

        for (ServerPlayer player : players) {
            ClientState state = state(player, dimension, false);
            if (state == null || state.cached.isEmpty()) continue;

            SectionPos center = player.getLastSectionPos();
            int radius = clientRadius(player);
            LongArrayList evicted = null;
            ObjectIterator<Long2ObjectMap.Entry<Entry>> iter = Long2ObjectMaps.fastIterator(state.cached);
            while (iter.hasNext()) {
                Long2ObjectMap.Entry<Entry> entry = iter.next();
                long packedPos = entry.getLongKey();
                boolean tooFar  = !withinClientRange(packedPos, center.x(), center.z(), radius);
                boolean expired = (now - entry.getValue().cachedAtMs()) >= timeoutMs;

                if (!enabled || tooFar || expired) {
                    iter.remove();
                    if (evicted == null) evicted = new LongArrayList();
                    evicted.add(packedPos);
                }
            }
            if (evicted != null) {
                for (int i = 0; i < evicted.size(); i++) {
                    evictCallback.accept(player, new ChunkPos(evicted.getLong(i)));
                }
            }
        }
    }
}
