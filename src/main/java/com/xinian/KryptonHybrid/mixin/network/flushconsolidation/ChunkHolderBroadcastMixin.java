package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import com.xinian.KryptonHybrid.shared.network.broadcast.BroadcastBundleCollector;
import com.xinian.KryptonHybrid.shared.network.chunk.ChunkChangeStamp;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
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
public abstract class ChunkHolderBroadcastMixin {

    @Accessor("playerProvider")
    abstract ChunkHolder.PlayerProvider krypton$getPlayerProvider();

    @Accessor("pos")
    abstract ChunkPos krypton$getPos();

    /**
     * Intercepts {@code broadcast(Packet, boolean)} to collect packets into the
     * broadcast bundle collector when a collection window is active.
     */
    @Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
    private void krypton$collectBroadcast(Packet<?> packet, boolean boundaryOnly, CallbackInfo ci) {
        // Players that left this chunk but still hold it (delayed chunk cache) miss
        // this update; the stamp makes their next return resend the chunk.
        LevelChunk chunk = ((ChunkHolder) (Object) this).getFullChunk();
        if (chunk != null) {
            ((ChunkChangeStamp) chunk).krypton$markChanged();
        }

        if (!BroadcastBundleCollector.isCollecting()) {
            return;
        }

        List<ServerPlayer> players = this.krypton$getPlayerProvider().getPlayers(this.krypton$getPos(), boundaryOnly);

        // Collect for each player instead of sending immediately
        for (ServerPlayer player : players) {
            BroadcastBundleCollector.collect(player, packet);
        }

        ci.cancel();
    }
}
