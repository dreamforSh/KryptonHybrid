package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.xinian.KryptonHybrid.shared.network.util.AutoFlushUtil.setAutoFlush;

/**
 * Optimizes {@link ServerEntity#addPairing} by disabling auto-flush before sending all
 * entity tracking packets to a newly-tracking player, and flushing once upon completion.
 * This consolidates many small per-packet syscalls into a single network write.
 *
 * <p>If an exception is thrown inside {@code addPairing} the RETURN inject will not fire,
 * leaving the player's auto-flush stuck at {@code false}.  The stuck state is recovered
 * at the start of the next {@link net.minecraft.server.level.ChunkMap#tick()} call by
 * {@code ChunkMapFlushMixin.tick$disableAutoFlush}, which performs a re-enable flush
 * before opening a new consolidation window.
 */
@Mixin(ServerEntity.class)
public class ServerEntityFlushMixin {

    @Inject(at = @At("HEAD"), method = "addPairing")
    private void addPairing$disableAutoFlush(ServerPlayer pPlayer, CallbackInfo ci) {
        setAutoFlush(pPlayer, false);
    }

    @Inject(at = @At("RETURN"), method = "addPairing")
    private void addPairing$reenableAutoFlush(ServerPlayer pPlayer, CallbackInfo ci) {
        setAutoFlush(pPlayer, true);
    }
}

