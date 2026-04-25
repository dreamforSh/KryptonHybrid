package com.xinian.KryptonHybrid.shared.network;

import com.xinian.KryptonHybrid.mixin.network.microopt.MoveEntityPacketAccessor;
import com.xinian.KryptonHybrid.mixin.network.microopt.RotateHeadPacketAccessor;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * Deduplicates and coalesces redundant packets within a per-player packet list
 * collected by {@link EntityBundleCollector} or {@link BroadcastBundleCollector}
 * during a single server tick.
 *
 * <h3>Coalescing rules</h3>
 * <ol>
 *   <li><strong>{@link ClientboundSetEntityMotionPacket}:</strong> multiple velocity
 *       updates for the same entity — only the last is kept.</li>
 *   <li><strong>{@link ClientboundTeleportEntityPacket}:</strong> a teleport supersedes
 *       any preceding relative {@link ClientboundMoveEntityPacket} for the same entity.
 *       Multiple teleports — only the last is kept.</li>
 *   <li><strong>{@link ClientboundSetEntityDataPacket}:</strong> multiple metadata
 *       updates for the same entity — only the last is kept.</li>
 *   <li><strong>{@link ClientboundRotateHeadPacket}:</strong> multiple head-yaw
 *       updates for the same entity — only the last is kept.</li>
 *   <li><strong>{@link ClientboundMoveEntityPacket} (Rot-only):</strong> multiple
 *       pure-rotation move packets for the same entity without a teleport — only the
 *       last is kept (rotation is absolute, not cumulative).</li>
 *   <li><strong>{@link ClientboundBlockEntityDataPacket}:</strong> multiple block-entity
 *       NBT updates for the same block position — only the last is kept.</li>
 * </ol>
 *
 * <h3>Algorithm</h3>
 * <p>The list is scanned <strong>backwards</strong> (from last to first). For each
 * packet, the entity ID / block position is extracted and checked against a per-type
 * "seen" set. If already seen (a later packet supersedes this one), the earlier packet
 * is removed. Backward scanning ensures we always keep the last occurrence.</p>
 *
 * <h3>Performance</h3>
 * <p>Uses fastutil {@link IntOpenHashSet} for zero-boxing entity ID lookups.
 * Block-entity dedup uses a compact {@link HashMap} keyed on long-encoded BlockPos.
 * Typical list sizes are 5–30 packets, so the overhead is negligible.</p>
 */
public final class PacketCoalescer {

    private PacketCoalescer() {}

    /**
     * Coalesces redundant packets in the given list <strong>in-place</strong>.
     * Must be called before the list is sent or wrapped in a
     * {@link ClientboundBundlePacket}.
     *
     * @param packets mutable list of packets for a single player connection
     */
    public static void coalesce(List<Packet<?>> packets) {
        if (!KryptonConfig.packetCoalescingEnabled || packets.size() < 2) {
            return;
        }

        final int sizeBefore = packets.size();

        // Phase 1: collect entity IDs that have a teleport so we can remove
        // earlier relative moves for those entities.
        IntOpenHashSet teleportedEntities = null;

        // Sets tracking "last seen" entity IDs per supersedable packet type.
        // Backward iteration: FIRST hit = LAST packet chronologically.
        IntOpenHashSet seenMotion    = new IntOpenHashSet();
        IntOpenHashSet seenTeleport  = new IntOpenHashSet();
        // entityId -> index in `packets` of the (later) merged SetEntityData packet
        Int2IntOpenHashMap dataMergeIdx = null;
        IntOpenHashSet seenHeadRot   = new IntOpenHashSet();
        // Pure-rotation MoveEntity packets: keep only the last per entity.
        IntOpenHashSet seenMoveRot   = new IntOpenHashSet();
        // Block-entity data: long-encoded BlockPos → seen (any non-null value).
        HashMap<Long, Boolean> seenBlockEntity = null;

        for (int i = packets.size() - 1; i >= 0; i--) {
            Packet<?> pkt = packets.get(i);

            if (pkt instanceof ClientboundSetEntityMotionPacket motion) {
                if (!seenMotion.add(motion.getId())) {
                    packets.remove(i);
                }
            } else if (pkt instanceof ClientboundTeleportEntityPacket teleport) {
                if (!seenTeleport.add(teleport.getId())) {
                    packets.remove(i);
                } else {
                    if (teleportedEntities == null) teleportedEntities = new IntOpenHashSet();
                    teleportedEntities.add(teleport.getId());
                }
            } else if (pkt instanceof ClientboundSetEntityDataPacket data) {
                // Merge multiple metadata updates for the same entity into a single
                // packet.  Newer (later index) values supersede older for the same
                // DataValue.id().  Merging (vs. drop-older) preserves any unique
                // slots updated only in the earlier packet — important when
                // unrelated metadata fields tick at different rates.
                int entityId = data.id();
                if (dataMergeIdx == null) dataMergeIdx = new Int2IntOpenHashMap();
                int laterIdx = dataMergeIdx.getOrDefault(entityId, -1);
                if (laterIdx == -1) {
                    dataMergeIdx.put(entityId, i);
                } else {
                    ClientboundSetEntityDataPacket later =
                        (ClientboundSetEntityDataPacket) packets.get(laterIdx);
                    List<SynchedEntityData.DataValue<?>> laterItems = later.packedItems();
                    List<SynchedEntityData.DataValue<?>> earlierItems = data.packedItems();
                    IntOpenHashSet ids = new IntOpenHashSet(laterItems.size() + earlierItems.size());
                    List<SynchedEntityData.DataValue<?>> merged =
                        new ArrayList<>(laterItems.size() + earlierItems.size());
                    // Later items take priority — add first, mark id seen.
                    for (SynchedEntityData.DataValue<?> v : laterItems) {
                        ids.add(v.id());
                        merged.add(v);
                    }
                    // Add earlier items only if their slot wasn't already overridden.
                    for (SynchedEntityData.DataValue<?> v : earlierItems) {
                        if (ids.add(v.id())) merged.add(v);
                    }
                    packets.set(laterIdx, new ClientboundSetEntityDataPacket(entityId, merged));
                    packets.remove(i); // i > laterIdx, so laterIdx index unaffected
                }
            } else if (pkt instanceof ClientboundRotateHeadPacket headRot) {
                int entityId = ((RotateHeadPacketAccessor) headRot).krypton$getEntityId();
                if (!seenHeadRot.add(entityId)) {
                    packets.remove(i);
                }
            } else if (pkt instanceof ClientboundMoveEntityPacket move) {
                if (move.hasRotation() && !move.hasPosition()) {
                    int entityId = ((MoveEntityPacketAccessor) move).krypton$getEntityId();
                    if (!seenMoveRot.add(entityId)) {
                        packets.remove(i);
                    }
                }
            } else if (pkt instanceof ClientboundBlockEntityDataPacket blockEntity) {
                long posLong = blockEntity.getPos().asLong();
                if (seenBlockEntity == null) seenBlockEntity = new HashMap<>();
                if (seenBlockEntity.put(posLong, Boolean.TRUE) != null) {
                    packets.remove(i);
                }
            }
        }

        // Phase 2: remove MoveEntity packets superseded by a teleport
        if (teleportedEntities != null && !teleportedEntities.isEmpty()) {
            Iterator<Packet<?>> it = packets.iterator();
            while (it.hasNext()) {
                Packet<?> pkt = it.next();
                if (pkt instanceof ClientboundMoveEntityPacket move) {
                    int entityId = ((MoveEntityPacketAccessor) move).krypton$getEntityId();
                    if (teleportedEntities.contains(entityId)) {
                        it.remove();
                    }
                }
            }
        }

        // Update aggregated coalescing stats
        int dropped = sizeBefore - packets.size();
        if (dropped > 0) {
            NetworkTrafficStats.INSTANCE.recordCoalesceDropped(dropped);
        }
    }
}

