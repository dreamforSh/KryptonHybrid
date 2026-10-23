package com.xinian.KryptonHybrid.shared.network.motion;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.stats.NetworkTrafficStats;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.network.ServerPlayerConnection;

import java.util.Iterator;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Per-player cache of the last sent velocity for each entity, used to drop redundant
 * {@link ClientboundSetEntityMotionPacket} updates whose per-axis change is below
 * {@link KryptonConfig#motionDeltaThreshold}.
 *
 * <h3>Why this works</h3>
 * <p>Projectiles, dropped items and falling blocks tick continuously and emit
 * a velocity packet every server tick even when the velocity barely changed.
 * The encoded short representation cannot resolve sub-{@code 1/8000} block-per-
 * tick changes, so dropping packets whose all-axis encoded delta is within
 * a few units is visually identical to sending them. A velocity that drops to zero
 * is always sent, so nothing keeps drifting on the client.</p>
 *
 * <p>Teleports are not filtered across ticks: the client also moves the entity by
 * relative moves in between, so a teleport to the same place as the previous one can
 * still be a needed correction. Duplicates within one tick are removed by
 * {@code PacketCoalescer}.</p>
 *
 * <h3>Storage / lifecycle</h3>
 * <p>Outer map is a {@link WeakHashMap} keyed by {@link ServerPlayerConnection}
 * so disconnected players' state is GC'd automatically.  Inner map is a fastutil
 * {@code Int2ObjectOpenHashMap} keyed by entityId; an entity's entry is dropped
 * whenever the player starts or stops tracking it ({@link #forget}), so it only holds
 * entities the player currently sees. All access is on the server thread (entity
 * tracking + bundle flush both run there), so no synchronization.</p>
 */
public final class MotionDeltaCache {

    /** Compact mutable triple used as the motion cache value. */
    private static final class MotionState {
        double xa, ya, za;
        MotionState(double xa, double ya, double za) { this.xa = xa; this.ya = ya; this.za = za; }
    }

    private static final WeakHashMap<ServerPlayerConnection, Int2ObjectOpenHashMap<MotionState>> MOTION =
            new WeakHashMap<>();

    private MotionDeltaCache() {}

    /**
     * Drops superseded motion packets in-place.  Returns the number of
     * packets removed for stats accounting.  Safe to call after the regular
     * {@code PacketCoalescer#coalesce}.
     */
    public static int filter(ServerPlayerConnection conn, List<Packet<?>> packets) {
        if (!KryptonConfig.motionDeltaEnabled || conn == null || packets.isEmpty()) {
            return 0;
        }
        int dropped = 0;
        // Vanilla encodes velocity as (int)(v * 8000); convert encoded-unit threshold to double tolerance.
        double motionThresh = Math.max(0, KryptonConfig.motionDeltaThreshold) / 8000.0;

        Int2ObjectOpenHashMap<MotionState> motionMap = null;

        Iterator<Packet<?>> it = packets.iterator();
        while (it.hasNext()) {
            Packet<?> pkt = it.next();
            if (!(pkt instanceof ClientboundSetEntityMotionPacket m)) {
                continue;
            }
            if (motionMap == null) motionMap = MOTION.computeIfAbsent(conn, k -> new Int2ObjectOpenHashMap<>());
            MotionState prev = motionMap.get(m.getId());
            double xa = m.getXa(), ya = m.getYa(), za = m.getZa();
            boolean stopped = xa == 0 && ya == 0 && za == 0;
            if (prev != null
                    && !stopped
                    && Math.abs(xa - prev.xa) <= motionThresh
                    && Math.abs(ya - prev.ya) <= motionThresh
                    && Math.abs(za - prev.za) <= motionThresh) {
                it.remove();
                dropped++;
            } else if (prev == null) {
                motionMap.put(m.getId(), new MotionState(xa, ya, za));
            } else {
                prev.xa = xa; prev.ya = ya; prev.za = za;
            }
        }
        if (dropped > 0) {
            NetworkTrafficStats.INSTANCE.recordCoalesceDropped(dropped);
        }
        return dropped;
    }

    /**
     * Drops the cached state for one entity seen by one player. Called when the player
     * starts or stops tracking the entity: pairing sends the velocity directly, and an
     * untracked entity's state would otherwise stay in the map for the whole session.
     */
    public static void forget(ServerPlayerConnection conn, int entityId) {
        Int2ObjectOpenHashMap<MotionState> m = MOTION.get(conn);
        if (m != null) {
            m.remove(entityId);
        }
    }
}
