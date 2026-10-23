package com.xinian.KryptonHybrid.mixin.network.blockentity;

import com.xinian.KryptonHybrid.shared.network.blockentity.BlockEntityDeltaHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
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
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderBlockEntityMixin {

    @Inject(method = "sendChunk", at = @At("HEAD"))
    private static void krypton$resetBlockEntityDeltas(ServerGamePacketListenerImpl packetListener, ServerLevel level,
                                                       LevelChunk chunk, CallbackInfo ci) {
        if (packetListener instanceof BlockEntityDeltaHolder holder) {
            ChunkPos pos = chunk.getPos();
            holder.krypton$getBlockEntityDeltaCache().evictChunk(pos.x, pos.z);
        }
    }
}
