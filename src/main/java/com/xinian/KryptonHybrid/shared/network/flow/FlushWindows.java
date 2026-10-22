package com.xinian.KryptonHybrid.shared.network.flow;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.util.AutoFlushUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;

/**
 * Tick-scoped flush-consolidation windows (server thread only).
 *
 * <p>While a window is open, each player's connection only writes packets; closing
 * the outermost window flushes them once (or hands the flush to
 * {@link MicroBatchFlusher}). Windows nest: {@code ServerChunkCache.tickChunks} opens
 * one around its broadcast phase and runs {@code ChunkMap.tick()} inside it, and
 * {@code ServerEntity.addPairing} runs inside {@code ChunkMap.tick()}. Inner windows
 * leave auto-flush alone when an outer one already holds it; otherwise the inner
 * close would turn auto-flush back on and every packet written afterwards would be
 * flushed on its own.</p>
 *
 * <p>If an exception skips a window's close, the next outermost open performs a
 * flush and starts clean.</p>
 */
public final class FlushWindows {
    /** For each open pairing window: whether it turned auto-flush off and must turn it back on. */
    private static final ThreadLocal<ArrayDeque<Boolean>> PAIRING = ThreadLocal.withInitial(ArrayDeque::new);

    private FlushWindows() {}

    /** Opens an outermost window: flushes anything left over, then holds flushes. */
    public static void openOutermost(ServerLevel level) {
        PAIRING.get().clear();
        for (ServerPlayer player : level.players()) {
            AutoFlushUtil.setAutoFlush(player, true);
            AutoFlushUtil.setAutoFlush(player, false);
        }
    }

    /** Closes an outermost window: one flush per player, now or after the micro-batch delay. */
    public static void closeOutermost(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            if (KryptonConfig.microBatchFlushEnabled) {
                MicroBatchFlusher.scheduleFlush(player);
            } else {
                AutoFlushUtil.setAutoFlush(player, true);
            }
        }
    }

    /** Start of {@code addPairing}: holds flushes only if no outer window already does. */
    public static void pairingStart(ServerPlayer player) {
        boolean took = AutoFlushUtil.isAutoFlushing(player);
        if (took) {
            AutoFlushUtil.setAutoFlush(player, false);
        }
        PAIRING.get().push(took);
    }

    /** End of {@code addPairing}: flushes only if {@link #pairingStart} took the hold. */
    public static void pairingEnd(ServerPlayer player) {
        ArrayDeque<Boolean> stack = PAIRING.get();
        if (!stack.isEmpty() && stack.pop()) {
            AutoFlushUtil.setAutoFlush(player, true);
        }
    }
}
