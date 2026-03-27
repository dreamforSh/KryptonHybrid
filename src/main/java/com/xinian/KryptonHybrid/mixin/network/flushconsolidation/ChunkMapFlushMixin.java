package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.xinian.KryptonHybrid.shared.network.util.AutoFlushUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds flush consolidation to {@link ChunkMap#tick()} (the entity tracking tick).
 *
 * <p>During a tracking tick, each entity sends movement/metadata packets to every
 * nearby player.  Without consolidation this results in one {@code flush()} syscall
 * per packet per player.  By disabling auto-flush at the start of the tick and
 * re-enabling it (which triggers a single flush) at the end, all packets for a given
 * player are coalesced into one kernel write, dramatically reducing system-call
 * overhead on busy servers.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapFlushMixin {

    @Shadow @Final
    ServerLevel level;

    /**
     * Disable auto-flush for every player before entity tracking updates are sent.
     * The disambiguating descriptor {@code ()V} ensures we target the no-argument
     * entity-tracking {@code tick()} rather than the chunk-loading
     * {@code tick(BooleanSupplier)} overload.
     *
     * <p>A recovery re-enable is performed first so that any player whose auto-flush
     * was left disabled by an exception in a previous tick (preventing the RETURN
     * inject from firing) will have their buffered packets flushed before the new
     * consolidation window begins.
     */
    @Inject(method = "tick()V", at = @At("HEAD"))
    private void tick$disableAutoFlush(CallbackInfo ci) {
        for (ServerPlayer player : this.level.players()) {
            AutoFlushUtil.setAutoFlush(player, true);
            AutoFlushUtil.setAutoFlush(player, false);
        }
    }

    /**
     * Re-enable auto-flush (and flush pending data) for every player after entity
     * tracking updates have been written to the send buffer.
     */
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void tick$reenableAutoFlush(CallbackInfo ci) {
        for (ServerPlayer player : this.level.players()) {
            AutoFlushUtil.setAutoFlush(player, true);
        }
    }
}

