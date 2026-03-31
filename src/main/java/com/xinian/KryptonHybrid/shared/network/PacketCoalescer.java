package com.xinian.KryptonHybrid.shared.network;

import com.xinian.KryptonHybrid.mixin.network.microopt.MoveEntityPacketAccessor;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;

import java.util.Iterator;
import java.util.List;

/**
 * Deduplicates and coalesces redundant packets within a per-player packet list
 * collected by {@link EntityBundleCollector} during a single entity-tracking tick.
 *
 * <h3>Coalescing rules</h3>
 * <ol>
 *   <li><strong>{@link ClientboundSetEntityMotionPacket}:</strong> when multiple
 *       velocity updates for the <em>same entity</em> are present, only the
 *       <strong>last</strong> one is meaningful — the client will overwrite the
 *       velocity on each receipt.  Earlier duplicates are removed.</li>
 *   <li><strong>{@link ClientboundTeleportEntityPacket}:</strong> a teleport sets
 *       the entity's absolute position, making any preceding relative
 *       {@link ClientboundMoveEntityPacket} (Pos / PosRot / Rot) for the same
 *       entity redundant.  When a teleport is present:
 *       <ul>
 *         <li>Earlier MoveEntity packets for the same entity are removed.</li>
 *         <li>If multiple teleports exist for the same entity, only the last is kept.</li>
 *       </ul>
 *   </li>
 *   <li><strong>{@link ClientboundSetEntityDataPacket}:</strong> when multiple
 *       metadata updates for the same entity are present, only the last one is
 *       kept (it contains the most up-to-date dirty values).</li>
 * </ol>
 *
 * <h3>Algorithm</h3>
 * <p>The list is scanned <strong>backwards</strong> (from last to first).  For each
 * packet, the entity ID is extracted and checked against a per-type "seen" set.
 * If the entity was already seen (meaning a <em>later</em> packet supersedes this
 * one), the current (earlier) packet is removed.  Backward scanning ensures we
 * always keep the <strong>last</strong> occurrence.</p>
 *
 * <h3>Performance</h3>
 * <p>Uses fastutil {@link IntOpenHashSet} for zero-boxing entity ID lookups.
 * Typical list sizes are 5–50 packets, so the overhead is negligible.</p>
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

        // Phase 1: collect entity IDs that have a teleport (absolute position)
        // so we can remove earlier relative moves for those entities.
        IntOpenHashSet teleportedEntities = null;

        // Sets tracking "last seen" entity IDs per supersedable packet type.
        // We iterate backwards so the FIRST hit for an entity is the LAST packet.
        IntOpenHashSet seenMotion   = new IntOpenHashSet();
        IntOpenHashSet seenTeleport = new IntOpenHashSet();
        IntOpenHashSet seenData     = new IntOpenHashSet();

        // Backward scan: mark removals
        for (int i = packets.size() - 1; i >= 0; i--) {
            Packet<?> pkt = packets.get(i);

            if (pkt instanceof ClientboundSetEntityMotionPacket motion) {
                if (!seenMotion.add(motion.getId())) {
                    // Already seen a later motion for this entity → remove this one
                    packets.remove(i);
                }
            } else if (pkt instanceof ClientboundTeleportEntityPacket teleport) {
                if (!seenTeleport.add(teleport.getId())) {
                    // Already seen a later teleport for this entity → remove this one
                    packets.remove(i);
                } else {
                    // First time seeing teleport for this entity (it's the last one).
                    // Mark entity for MoveEntity removal.
                    if (teleportedEntities == null) {
                        teleportedEntities = new IntOpenHashSet();
                    }
                    teleportedEntities.add(teleport.getId());
                }
            } else if (pkt instanceof ClientboundSetEntityDataPacket data) {
                if (!seenData.add(data.id())) {
                    // Already seen a later data packet for this entity → remove this one
                    packets.remove(i);
                }
            }
        }

        // Phase 2: remove MoveEntity packets that are superseded by a teleport
        if (teleportedEntities != null && !teleportedEntities.isEmpty()) {
            IntOpenHashSet finalTeleported = teleportedEntities;
            Iterator<Packet<?>> it = packets.iterator();
            while (it.hasNext()) {
                Packet<?> pkt = it.next();
                if (pkt instanceof ClientboundMoveEntityPacket move) {
                    int entityId = ((MoveEntityPacketAccessor) move).krypton$getEntityId();
                    if (finalTeleported.contains(entityId)) {
                        it.remove();
                    }
                }
            }
        }
    }
}

