package com.xinian.KryptonHybrid.shared.network.security;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Netty {@link ChannelInboundHandlerAdapter} that limits the rate of new TCP
 * connections per source IP address using a token-bucket with rapid reconnect
 * penalties and short quarantine support.
 *
 * <p>This handler is added <strong>first</strong> in the child channel pipeline
 * (before any Minecraft decoder) by {@code ServerConnectionListenerMixin}.
 * On {@link #channelActive(ChannelHandlerContext)}, it:</p>
 * <ol>
 *   <li>Consumes tokens from the source IP's connection bucket.</li>
 *   <li>Applies an extra cost to suspicious rapid reconnects.</li>
 *   <li>Temporarily quarantines IPs that repeatedly exceed the budget.</li>
 * </ol>
 *
 * <p>The handler is marked {@link ChannelHandler.Sharable} ??a single instance is
 * shared across all child channels.  All state is stored in the static
 * {@link #WINDOWS} map, not in the handler instance.</p>
 */
@ChannelHandler.Sharable
public class ConnectionRateLimiter extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("KryptonSecurity");

    /** Per-IP connection budget state. */
    private static final Map<String, ConnectionBudget> WINDOWS = new ConcurrentHashMap<>();

    /**
     * Singleton instance shared across all child channels.
     */
    public static final ConnectionRateLimiter INSTANCE = new ConnectionRateLimiter();

    private ConnectionRateLimiter() {}

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (!KryptonConfig.securityEnabled) {
            super.channelActive(ctx);
            return;
        }

        String ip = RateLimitKeys.of(ctx.channel());
        if (ip == null) {
            // Memory connection, loopback, or behind a proxy: not IP-limited
            super.channelActive(ctx);
            return;
        }

        ConnectionBudget budget = WINDOWS.computeIfAbsent(ip, k -> new ConnectionBudget());
        if (!budget.tryAcquire()) {
            SecurityMetrics.INSTANCE.recordConnectionRateLimited();
            if (budget.shouldLogViolation()) {
                LOGGER.warn("[Krypton Security] Connection rate limit exceeded for {} (rate={}, burst={}, quarantine={}s)",
                        ip,
                        KryptonConfig.securityConnectionRatePerSecond,
                        KryptonConfig.securityConnectionBurstLimit,
                        KryptonConfig.securityConnectionQuarantineSeconds);
            }
            // Let vanilla's Connection see channelActive first: it only assigns its
            // channel there, and ServerConnectionListener already holds the Connection.
            // Closing before that leaves channel == null, so the Connection is never
            // ticked or removed and its disconnect path throws NPEs.
            super.channelActive(ctx);
            ctx.close();
            return;
        }

        super.channelActive(ctx);
    }

    /**
     * Periodically clean up stale window entries.
     * Call from a timer or tick event.
     */
    public static void evictStaleWindows() {
        long now = System.currentTimeMillis();
        WINDOWS.entrySet().removeIf(e -> now - e.getValue().lastAccessTime() > 60_000L);
    }

    // ?? Sliding window implementation ?????????????????????????????????

    static final class ConnectionBudget {
        private double tokens;
        private long lastRefillNanos;
        private volatile long lastAccess = System.currentTimeMillis();
        private volatile long lastConnectTime;
        private volatile long quarantineUntilMs;
        private volatile long lastLogMs;
        private int consecutiveViolations;

        synchronized boolean tryAcquire() {
            long nowMs = System.currentTimeMillis();
            lastAccess = nowMs;

            if (quarantineUntilMs > nowMs) {
                return false;
            }

            refill(System.nanoTime());

            double cost = 1.0D;
            int rapidWindowMs = KryptonConfig.securityRapidReconnectWindowMs;
            if (rapidWindowMs > 0 && lastConnectTime != 0L && nowMs - lastConnectTime <= rapidWindowMs) {
                cost += KryptonConfig.securityRapidReconnectPenalty;
            }

            if (tokens >= cost) {
                tokens -= cost;
                lastConnectTime = nowMs;
                consecutiveViolations = 0;
                return true;
            }

            consecutiveViolations++;
            int quarantineSeconds = KryptonConfig.securityConnectionQuarantineSeconds;
            if (quarantineSeconds > 0 && consecutiveViolations >= 2) {
                quarantineUntilMs = nowMs + quarantineSeconds * 1000L;
            }
            return false;
        }

        private void refill(long nowNanos) {
            double rate = KryptonConfig.securityConnectionRatePerSecond;
            double burst = KryptonConfig.securityConnectionBurstLimit;

            if (lastRefillNanos == 0L) {
                tokens = burst;
                lastRefillNanos = nowNanos;
                return;
            }

            long elapsed = nowNanos - lastRefillNanos;
            lastRefillNanos = nowNanos;
            if (elapsed <= 0L || rate <= 0.0D || burst <= 0.0D) {
                if (burst > 0.0D && tokens > burst) {
                    tokens = burst;
                }
                return;
            }

            tokens = Math.min(burst, tokens + (elapsed / 1_000_000_000.0D) * rate);
        }

        long lastAccessTime() {
            return lastAccess;
        }

        synchronized boolean shouldLogViolation() {
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastLogMs < 5000L) {
                return false;
            }
            lastLogMs = nowMs;
            return true;
        }
    }
}
