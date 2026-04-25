package com.xinian.KryptonHybrid.shared.network;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Cross-tick micro-batch flush coordinator.
 *
 * <h3>Problem solved</h3>
 * <p>The vanilla flush-consolidation mixins disable {@code autoFlush} during the
 * entity-tick and re-enable it at the end (which fires an immediate flush).
 * Within the same server tick a second consolidation window opens for
 * block-update broadcasts and produces another flush.  Result: ≥ 2 syscalls per
 * tick per player.</p>
 *
 * <h3>What this does</h3>
 * <p>When {@link KryptonConfig#microBatchFlushEnabled} is true, end-of-tick
 * "re-enable + flush" is replaced by scheduling a deferred channel-level flush
 * on the connection's Netty event loop {@link KryptonConfig#microBatchFlushDelayMs}
 * milliseconds later.  Auto-flush stays disabled during the window, so any
 * additional writes (block updates, chat, plugin payloads) accumulate into one
 * kernel write.</p>
 *
 * <h3>Idempotency</h3>
 * <p>Multiple {@link #scheduleFlush(Connection)} calls within the same window
 * coalesce — a flush is at most pending once per connection at any time.</p>
 *
 * <h3>Thread safety</h3>
 * <p>The {@code pending} set uses a concurrent map because schedule calls come
 * from the server main thread while the flush runnable executes on the channel
 * event-loop thread.</p>
 */
public final class MicroBatchFlusher {

    /** Connections that already have a pending flush queued. */
    private static final Set<Channel> PENDING =
            ConcurrentHashMap.newKeySet();

    private MicroBatchFlusher() {}

    public static void scheduleFlush(ServerPlayer player) {
        if (player == null) return;
        if (player.getClass() != ServerPlayer.class) return;
        scheduleFlush(player.connection.getConnection());
    }

    public static void scheduleFlush(Connection connection) {
        if (connection == null) return;
        Channel ch = connection.channel();
        if (ch == null || !ch.isActive()) return;
        EventLoop loop = ch.eventLoop();
        if (loop == null) return;

        // Idempotent registration: if already pending, drop.
        if (!PENDING.add(ch)) return;

        long delay = Math.max(1, Math.min(20, KryptonConfig.microBatchFlushDelayMs));
        loop.schedule(() -> {
            try {
                if (ch.isActive()) {
                    // Re-enable autoflush so subsequent writes don't pile up forever
                    if (connection instanceof ConfigurableAutoFlush caf) {
                        caf.setShouldAutoFlush(true);
                    }
                    ch.flush();
                }
            } finally {
                PENDING.remove(ch);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** For testing / shutdown — drops any pending bookkeeping. */
    public static void clear() {
        PENDING.clear();
    }
}

