package com.xinian.KryptonHybrid.mixin.network.blockentity;

import com.xinian.KryptonHybrid.shared.network.blockentity.BlockEntityDeltaHolder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Resets the player's block-entity delta cache for a chunk whenever that chunk is
 * (re)sent. The client rebuilds those block entities from the chunk packet, so the
 * server must not keep sending deltas against tags from a previous copy of the chunk
 * (chunk reload, dimension change, respawn).
 */
@Mixin(ServerPlayer.class)
public class ServerPlayerTrackChunkMixin {

    @Inject(method = "trackChunk", at = @At("HEAD"))
    private void krypton$resetBlockEntityDeltas(ChunkPos chunkPos, Packet<?> packet, CallbackInfo ci) {
        if (((ServerPlayer) (Object) this).connection instanceof BlockEntityDeltaHolder holder) {
            holder.krypton$getBlockEntityDeltaCache().evictChunk(chunkPos.x, chunkPos.z);
        }
    }
}
