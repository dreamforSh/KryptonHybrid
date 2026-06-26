package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import com.xinian.KryptonHybrid.shared.network.broadcast.BroadcastBundleCollector;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Intercepts {@link ChunkHolder#broadcastChanges}'s internal fan-out to collect
 * per-player block-change packets into the {@link BroadcastBundleCollector} when
 * a collection window is active.
 *
 * <h3>Target method</h3>
 * <p>{@code ChunkHolder.broadcast(Packet<?>, boolean)} is the method that iterates
 * over tracked players and calls {@code player.connection.send(packet)}
 * for each.  It is used by {@code broadcastChanges()} to distribute block
 * updates, light updates, and block-entity updates to nearby players.</p>
 *
 * <h3>Redirect behavior</h3>
 * <p>When the {@link BroadcastBundleCollector} has an active collection window
 * (opened by {@link ServerChunkCacheBroadcastMixin}), this mixin intercepts the
 * {@code broadcast()} call and routes each player's packet to the collector.
 * When no window is active, the original method runs unchanged.</p>
 */
@Mixin(ChunkHolder.class)
public class ChunkHolderBroadcastMixin {

    @Shadow
    @Final
    private ChunkHolder.PlayerProvider playerProvider;

    @Shadow
    @Final
    private ChunkPos pos;

    /**
     * Intercepts {@code broadcast(Packet, boolean)} to collect packets into the
     * broadcast bundle collector when a collection window is active.
     */
    @Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
    private void krypton$collectBroadcast(Packet<?> packet, boolean boundaryOnly, CallbackInfo ci) {
        if (!BroadcastBundleCollector.isCollecting()) {
            return;
        }

        List<ServerPlayer> players = this.playerProvider.getPlayers(this.pos, boundaryOnly);

        // Collect for each player instead of sending immediately
        for (ServerPlayer player : players) {
            BroadcastBundleCollector.collect(player, packet);
        }

        ci.cancel();
    }
}
