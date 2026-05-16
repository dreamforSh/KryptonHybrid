package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import com.xinian.KryptonHybrid.shared.network.broadcast.EntityBundleCollector;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects per-player packet sends inside {@code ChunkMap.TrackedEntity.broadcast()}
 * to the {@link EntityBundleCollector} during the entity tracking tick, enabling
 * automatic packet bundling.
 *
 * <h3>Target method</h3>
 * <p>{@code ChunkMap.TrackedEntity.broadcast(Packet)} iterates over all
 * {@link ServerPlayerConnection}s in the entity's {@code seenBy} set and calls
 * {@link ServerPlayerConnection#send(Packet)} for each one.  This is the primary
 * fan-out point for entity update packets ??a single entity's movement/metadata
 * change results in N individual {@code send()} calls for N tracking players.</p>
 *
 * <h3>Redirect behavior</h3>
 * <p>When the {@link EntityBundleCollector} has an active collection window (opened
 * by {@link ChunkMapFlushMixin} at the start of {@code ChunkMap.tick()}), the
 * redirect intercepts the {@code send()} call and routes the packet to
 * {@link EntityBundleCollector#collect(ServerPlayerConnection, Packet)} instead.
 * The collector appends the packet to a per-player list; all collected packets for
 * each player are then flushed as a single {@link net.minecraft.network.protocol.game.ClientboundBundlePacket
 * ClientboundBundlePacket} at the end of the tick.</p>
 *
 * <p>When no collection window is active (broadcast called from outside the tick,
 * e.g. during {@code addEntity} or manual {@code broadcast()} calls), the redirect
 * falls through to the original {@code send()} call unchanged.</p>
 *
 * <h3>Why {@code TrackedEntity.broadcast()} and not {@code ServerEntity.sendChanges()}?</h3>
 * <p>{@code broadcast()} is the single fan-out point where one packet becomes N sends.
 * Intercepting here captures <em>all</em> entity update paths: position, rotation,
 * velocity, metadata, passengers, head rotation, and hurt animation ??without needing
 * to modify each individual packet creation site in {@code sendChanges()}.</p>
 *
 * <h3>Self-send for player entities</h3>
 * <p>{@code ServerEntity.broadcastAndSend()} and {@code TrackedEntity.broadcastAndSend()}
 * also send a copy to the entity's own player connection via a direct
 * {@code connection.send()} call <em>outside</em> of {@code broadcast()}.  This
 * self-send is intentionally not intercepted ??it is a single packet per player-entity
 * per change and does not benefit meaningfully from bundling.  The flush consolidation
 * system already ensures this packet is batched at the Netty channel level.</p>
 *
 * @see EntityBundleCollector
 * @see ChunkMapFlushMixin
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public class TrackedEntityBundleMixin {

    /**
     * Intercepts every {@link ServerPlayerConnection#send(Packet)} call inside
     * {@code broadcast(Packet)} and routes it through the bundle collector when
     * a collection window is active.
     *
     * <p>The redirect target matches the interface method on
     * {@link ServerPlayerConnection} ??regardless of the concrete implementation
     * class ({@code ServerGamePacketListenerImpl}, etc.).</p>
     *
     * @param conn   the player connection being sent to
     * @param packet the entity update packet to send or collect
     */
    @Redirect(
            method = "broadcast",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerPlayerConnection;send(Lnet/minecraft/network/protocol/Packet;)V"
            )
    )
    private void krypton$collectForBundle(ServerPlayerConnection conn, Packet<?> packet) {
        if (!EntityBundleCollector.collect(conn, packet)) {
            // No active batch ??send immediately (normal non-tick code path)
            conn.send(packet);
        }
    }
}

