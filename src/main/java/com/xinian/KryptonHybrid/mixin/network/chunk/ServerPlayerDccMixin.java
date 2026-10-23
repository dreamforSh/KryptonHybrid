package com.xinian.KryptonHybrid.mixin.network.chunk;

import com.xinian.KryptonHybrid.shared.network.chunk.DelayedChunkCache;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the delayed chunk cache which chunks the client actually holds: every chunk
 * packet sent, and every "forget" actually sent (vanilla skips it for a dead player,
 * whose client then keeps the chunk).
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDccMixin {

    @Inject(method = "trackChunk", at = @At("HEAD"))
    private void krypton$recordSent(ChunkPos pos, Packet<?> packet, CallbackInfo ci) {
        DelayedChunkCache.INSTANCE.onSent((ServerPlayer) (Object) this, pos);
    }

    @Inject(method = "untrackChunk", at = @At("HEAD"))
    private void krypton$recordForgotten(ChunkPos pos, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (self.isAlive()) {
            DelayedChunkCache.INSTANCE.onForgotten(self, pos);
        }
    }
}
