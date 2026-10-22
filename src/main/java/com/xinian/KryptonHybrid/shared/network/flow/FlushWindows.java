package com.xinian.KryptonHybrid.shared.network.flow;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.util.AutoFlushUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;

/**
 * Tick-scoped flush-consolidation windows (server thread only).
 *
 * <p>While a window is open, each player's connection only writes packets; closing
 * the window flushes them once. There are three kinds:</p>
 * <ul>
 *   <li><strong>Whole tick</strong> ({@code tick_flush.enabled}): opened when the
 *       server tick starts and released per connection by vanilla's own flush in the
 *       connection phase, so world, entity, block-entity and player ticks all share
 *       one flush. Packets sent between ticks (replies to client packets) are not
 *       held.</li>
 *   <li><strong>Per level</strong>: {@code ServerChunkCache.tickChunks} opens one around
 *       its broadcast phase and runs {@code ChunkMap.tick()} inside it. Used when the
 *       whole-tick window is off; inside it these leave auto-flush alone. Closing it
 *       flushes once, or hands the flush to {@link MicroBatchFlusher}.</li>
 *   <li><strong>Pairing</strong>: {@code ServerEntity.addPairing}, which also runs
 *       between ticks when a player moves.</li>
 * </ul>
 *
 * <p>Inner windows leave auto-flush alone when an outer one already holds it;
 * otherwise the inner close would turn auto-flush back on and every packet written
 * afterwards would be flushed on its own. If an exception skips a window's close,
 * the next outermost open performs a flush and starts clean.</p>
 */
public final class FlushWindows {
    /** For each open pairing window: whether it turned auto-flush off and must turn it back on. */
    private static final ThreadLocal<ArrayDeque<Boolean>> PAIRING = ThreadLocal.withInitial(ArrayDeque::new);

    private static volatile boolean tickWindowOpen;

    private FlushWindows() {}

    /** Whether the current server tick holds every player's flush until the connection phase. */
    public static boolean isTickWindowOpen() {
        return tickWindowOpen;
    }

    /** Start of a server tick: holds every player's flush (after delivering leftovers). */
    public static void openTick(MinecraftServer server) {
        PAIRING.get().clear();
        tickWindowOpen = KryptonConfig.tickFlushEnabled;
        if (!tickWindowOpen) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            AutoFlushUtil.setAutoFlush(player, true);
            AutoFlushUtil.setAutoFlush(player, false);
        }
    }

    /**
     * End of a server tick. Connections are normally released in the connection phase
     * already; this covers players whose connection was not ticked this time.
     */
    public static void closeTick(MinecraftServer server) {
        if (!tickWindowOpen) {
            return;
        }
        tickWindowOpen = false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            AutoFlushUtil.setAutoFlush(player, true);
        }
    }

    /** Opens a per-level window: flushes anything left over, then holds flushes. */
    public static void openOutermost(ServerLevel level) {
        PAIRING.get().clear();
        if (tickWindowOpen) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            AutoFlushUtil.setAutoFlush(player, true);
            AutoFlushUtil.setAutoFlush(player, false);
        }
    }

    /** Closes a per-level window: one flush per player, now or after the micro-batch delay. */
    public static void closeOutermost(ServerLevel level) {
        if (tickWindowOpen) {
            return;
        }
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
