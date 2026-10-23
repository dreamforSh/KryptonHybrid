package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import com.xinian.KryptonHybrid.shared.network.flow.FlushWindows;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flushes the packets {@link ServerEntity#addPairing} sends to a newly tracking player
 * at once instead of one by one. Inside an already open tick window (the usual case:
 * pairing happens during {@code ChunkMap.tick()}), it leaves auto-flush to that
 * window; see {@link FlushWindows}.
 */
@Mixin(ServerEntity.class)
public class ServerEntityFlushMixin {

    @Inject(at = @At("HEAD"), method = "addPairing")
    private void addPairing$holdFlush(ServerPlayer pPlayer, CallbackInfo ci) {
        FlushWindows.pairingStart(pPlayer);
    }

    @Inject(at = @At("RETURN"), method = "addPairing")
    private void addPairing$releaseFlush(ServerPlayer pPlayer, CallbackInfo ci) {
        FlushWindows.pairingEnd(pPlayer);
    }
}
