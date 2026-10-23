package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import com.xinian.KryptonHybrid.shared.network.broadcast.BroadcastBundleCollector;
import com.xinian.KryptonHybrid.shared.network.chunk.ChunkChangeStamp;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
 * <p>{@code ChunkHolder.broadcast(List<ServerPlayer>, Packet<?>)} is the private
 * helper that iterates over a player list and calls
 * {@code player.connection.send(packet)} for each.  It is used by
 * {@code broadcastChanges()} to distribute block updates, light updates, and
 * block-entity updates to nearby players.</p>
 *
 * <h3>Redirect behavior</h3>
 * <p>When the {@link BroadcastBundleCollector} has an active collection window
 * (opened by {@link ServerChunkCacheBroadcastMixin}), this mixin intercepts the
 * {@code broadcast()} call and routes each player's packet to the collector.
 * When no window is active, the original method runs unchanged.</p>
 *
 * <h3>Change stamps</h3>
 * <p>Players whose client still holds this chunk after it left their view (delayed
 * chunk cache) are not among its trackers and miss its updates, so every change marks
 * the chunk's {@link ChunkChangeStamp} and their next return resends it. Changes are
 * marked where they are recorded ({@code blockChanged}, {@code sectionLightChanged}),
 * because vanilla only broadcasts them when the chunk has other trackers, and also on
 * every broadcast.</p>
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderBroadcastMixin {

    /**
     * Intercepts {@code broadcast(List, Packet)} to collect packets into the
     * broadcast bundle collector when a collection window is active.
     */
    @Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
    private void krypton$collectBroadcast(List<ServerPlayer> players, Packet<?> packet, CallbackInfo ci) {
        krypton$markChanged();

        if (!BroadcastBundleCollector.isCollecting()) {
            return;
        }

        // Collect for each player instead of sending immediately
        for (ServerPlayer player : players) {
            BroadcastBundleCollector.collect(player, packet);
        }

        ci.cancel();
    }

    /** Block and block-entity changes ({@code sendBlockUpdated} ends here too). */
    @Inject(method = "blockChanged", at = @At("HEAD"))
    private void krypton$stampBlockChange(BlockPos pos, CallbackInfo ci) {
        krypton$markChanged();
    }

    @Inject(method = "sectionLightChanged", at = @At("HEAD"))
    private void krypton$stampLightChange(LightLayer layer, int sectionY, CallbackInfo ci) {
        krypton$markChanged();
    }

    @Unique
    private void krypton$markChanged() {
        LevelChunk chunk = ((ChunkHolder) (Object) this).getFullChunk();
        if (chunk != null) {
            ((ChunkChangeStamp) chunk).krypton$markChanged();
        }
    }
}
