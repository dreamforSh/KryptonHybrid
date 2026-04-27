package com.xinian.KryptonHybrid.mixin.network.blockentity;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.BlockEntityDeltaCache;
import com.xinian.KryptonHybrid.shared.network.BlockEntityDeltaHolder;
import com.xinian.KryptonHybrid.shared.network.control.KryptonWireFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Intercepts {@link ChunkHolder}'s block-entity broadcast to apply per-player
 * NBT delta encoding.
 *
 * <h3>Problem</h3>
 * <p>{@code ChunkHolder.broadcastBlockEntity(List, Level, BlockPos)} retrieves the
 * block entity's update packet (containing the <em>full</em> NBT tag) and broadcasts
 * it to all nearby players.  For frequently-updating block entities (furnaces, hoppers,
 * redstone), most NBT keys are unchanged between ticks.</p>
 *
 * <h3>Solution</h3>
 * <p>For each player, the mixin computes a per-player delta against the last-sent tag
 * stored in {@link BlockEntityDeltaCache}.  If the tag is unchanged, the send is
 * skipped entirely.  If only a subset of keys changed, a delta tag (marked with
 * {@code __krypton_delta = true}) is sent instead of the full tag.</p>
 *
 * <h3>Compatibility</h3>
 * <p>Requires Krypton Hybrid on <strong>both</strong> server and client.  The client
 * mixin ({@code ClientBlockEntityDeltaMixin}) detects the delta marker and merges
 * rather than replacing the local NBT.</p>
 */
@Mixin(ChunkHolder.class)
public class ChunkHolderBlockEntityMixin {

    /**
     * Intercepts the private {@code broadcastBlockEntity(List, Level, BlockPos)}
     * method to apply per-player delta encoding.
     */
    @Inject(method = "broadcastBlockEntity", at = @At("HEAD"), cancellable = true)
    private void krypton$deltaBlockEntity(List<ServerPlayer> players, Level level,
                                          BlockPos pos, CallbackInfo ci) {
        if (!KryptonConfig.blockEntityDeltaEnabled) {
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            ci.cancel();
            return;
        }

        Packet<?> fullPacket = blockEntity.getUpdatePacket();
        if (fullPacket == null) {
            ci.cancel();
            return;
        }

        // The update packet is a ClientboundBlockEntityDataPacket
        if (!(fullPacket instanceof ClientboundBlockEntityDataPacket bePacket)) {
            // Unexpected packet type — fall through to vanilla
            return;
        }

        CompoundTag fullTag = bePacket.getTag();
        if (fullTag == null || fullTag.isEmpty()) {
            // Empty tag — send as-is to all players
            for (ServerPlayer player : players) {
                player.connection.send(fullPacket);
            }
            ci.cancel();
            return;
        }

        long packedPos = pos.asLong();

        for (ServerPlayer player : players) {
            if (!KryptonWireFormat.canWriteBlockEntityDelta(player.connection.getConnection())) {
                player.connection.send(fullPacket);
                continue;
            }

            if (!(player.connection instanceof BlockEntityDeltaHolder holder)) {
                // Fallback: send full packet
                player.connection.send(fullPacket);
                continue;
            }

            BlockEntityDeltaCache cache = holder.krypton$getBlockEntityDeltaCache();
            CompoundTag deltaOrFull = cache.computeDelta(packedPos, fullTag);

            if (deltaOrFull == null) {
                // Unchanged — skip sending entirely
                continue;
            }

            if (deltaOrFull == fullTag) {
                // Full tag (first send, or delta not beneficial)
                player.connection.send(fullPacket);
            } else {
                // Delta tag — create a new packet with the delta
                player.connection.send(new ClientboundBlockEntityDataPacket(
                        pos, bePacket.getType(), deltaOrFull
                ));
            }
        }

        ci.cancel();
    }
}

