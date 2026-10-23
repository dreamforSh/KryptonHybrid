package com.xinian.KryptonHybrid.mixin.network.chunk;

import com.xinian.KryptonHybrid.shared.network.chunk.DelayedChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the delayed chunk cache which chunks the client actually holds: every chunk
 * packet sent, and every "forget" actually sent (vanilla skips it for a chunk that was
 * still pending and for a dead player, whose client then keeps the chunk).
 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderDccMixin {

    @Inject(method = "sendChunk", at = @At("HEAD"))
    private static void krypton$recordSent(ServerGamePacketListenerImpl packetListener, ServerLevel level,
                                           LevelChunk chunk, CallbackInfo ci) {
        DelayedChunkCache.INSTANCE.onSent(packetListener.player, chunk.getPos());
    }

    @Inject(
            method = "dropChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"
            )
    )
    private void krypton$recordForgotten(ServerPlayer player, ChunkPos pos, CallbackInfo ci) {
        DelayedChunkCache.INSTANCE.onForgotten(player, pos);
    }
}
