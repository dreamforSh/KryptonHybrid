package com.xinian.KryptonHybrid.shared.network;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Set;

/**
 * Per-player cache of the last-sent {@link CompoundTag} for each block entity,
 * enabling delta-only transmission of block entity data.
 *
 * <h3>Problem</h3>
 * <p>{@link net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket}
 * always sends the <em>full</em> update tag.  For frequently-updated block entities
 * (furnaces, hoppers, signs with dynamic text, redstone components), the majority
 * of NBT keys remain unchanged between ticks.  Full-tag transmission wastes
 * bandwidth on redundant data that the client already has.</p>
 *
 * <h3>Solution</h3>
 * <p>This cache stores the last successfully-sent {@link CompoundTag} per
 * {@link BlockPos} (packed as a {@code long}).  Before sending, the caller
 * invokes {@link #computeDelta(long, CompoundTag)} which diffs the new tag
 * against the cached version:</p>
 * <ul>
 *   <li><strong>No change:</strong> returns {@code null} (skip sending entirely).</li>
 *   <li><strong>Partial change:</strong> returns a delta tag containing only the
 *       changed/added keys plus a {@code __krypton_removed} list of deleted keys.
 *       A marker key {@code __krypton_delta = true} is set to distinguish delta
 *       tags from full tags on the client.</li>
 *   <li><strong>Full send preferred:</strong> if the delta tag is larger than the
 *       full tag (rare, e.g. wholesale tag replacement), the full tag is returned
 *       without the delta marker.</li>
 * </ul>
 *
 * <h3>Cache eviction</h3>
 * <p>The cache uses a LRU-ordered {@link Long2ObjectLinkedOpenHashMap} with a
 * configurable maximum size ({@link #MAX_ENTRIES}).  Oldest entries are evicted
 * when the limit is reached, causing a full-tag send on next update (correct but
 * slightly less efficient).</p>
 *
 * <h3>Thread safety</h3>
 * <p>One instance per player, accessed only from the server main thread.</p>
 *
 * @see com.xinian.KryptonHybrid.mixin.network.blockentity.ChunkHolderBlockEntityMixin
 */
public final class BlockEntityDeltaCache {

    /** Marker key present in delta tags. Client uses this to merge vs replace. */
    public static final String DELTA_MARKER_KEY = "__krypton_delta";

    /** Key listing removed NBT keys (stored as a comma-separated string). */
    public static final String REMOVED_KEYS_KEY = "__krypton_removed";

    /** Maximum cached block entity positions per player. */
    private static final int MAX_ENTRIES = 512;

    /** Packed BlockPos → last-sent CompoundTag (deep copy). */
    private final Long2ObjectLinkedOpenHashMap<CompoundTag> cache =
            new Long2ObjectLinkedOpenHashMap<>();

    public BlockEntityDeltaCache() {}

    /**
     * Computes the delta between the new tag and the cached (last-sent) tag.
     *
     * @param packedPos {@link BlockPos#asLong()} of the block entity
     * @param newTag    the full update tag to send (must not be modified after this call)
     * @return delta tag with {@code __krypton_delta=true}, the full tag if delta is
     *         not beneficial, or {@code null} if the tag is unchanged
     */
    public CompoundTag computeDelta(long packedPos, CompoundTag newTag) {
        CompoundTag lastSent = cache.get(packedPos);

        if (lastSent == null) {
            // First send — cache and return full tag
            putCache(packedPos, newTag.copy());
            return newTag;
        }

        // Quick equality check
        if (lastSent.equals(newTag)) {
            return null; // unchanged — skip sending
        }

        // Compute delta
        CompoundTag delta = new CompoundTag();
        delta.putBoolean(DELTA_MARKER_KEY, true);

        Set<String> newKeys = newTag.getAllKeys();
        Set<String> oldKeys = lastSent.getAllKeys();

        // Find changed/added keys
        int changedCount = 0;
        for (String key : newKeys) {
            Tag newValue = newTag.get(key);
            Tag oldValue = lastSent.get(key);
            if (oldValue == null || !oldValue.equals(newValue)) {
                delta.put(key, newValue.copy());
                changedCount++;
            }
        }

        // Find removed keys
        StringBuilder removedSb = null;
        for (String key : oldKeys) {
            if (!newKeys.contains(key)) {
                if (removedSb == null) {
                    removedSb = new StringBuilder();
                } else {
                    removedSb.append(',');
                }
                removedSb.append(key);
                changedCount++;
            }
        }
        if (removedSb != null) {
            delta.putString(REMOVED_KEYS_KEY, removedSb.toString());
        }

        // Update cache with the new full tag
        putCache(packedPos, newTag.copy());

        if (changedCount == 0) {
            return null; // shouldn't happen (equals check above), but be safe
        }

        // If delta is not smaller on the actual NBT wire representation, prefer full tag.
        if (tagWireSize(delta) >= tagWireSize(newTag)) {
            return newTag;
        }

        return delta;
    }

    /**
     * Evicts a specific position from the cache (e.g. when the block entity is
     * destroyed or the chunk is unloaded).
     */
    public void evict(long packedPos) {
        cache.remove(packedPos);
    }

    /** Clears the entire cache (e.g. on dimension change or disconnect). */
    public void clear() {
        cache.clear();
    }

    private void putCache(long packedPos, CompoundTag tag) {
        if (cache.size() >= MAX_ENTRIES) {
            // Evict oldest entry (first in insertion order)
            cache.removeFirst();
        }
        cache.put(packedPos, tag);
    }

    private static int tagWireSize(CompoundTag tag) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeNbt(tag);
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }
}

