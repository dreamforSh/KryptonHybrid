package com.xinian.KryptonHybrid.shared.network.security;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Netty {@link ChannelInboundHandlerAdapter} that limits the rate of new TCP
 * connections per source IP address using a sliding-window counter.
 *
 * <p>This handler is added <strong>first</strong> in the child channel pipeline
 * (before any Minecraft decoder) by {@code ServerConnectionListenerMixin}.
 * On {@link #channelActive(ChannelHandlerContext)}, it:</p>
 * <ol>
 *   <li>Increments a per-IP counter within a sliding time window.</li>
 *   <li>If the counter exceeds the configured burst limit, closes the channel.</li>
 * </ol>
 *
 * <p>The handler is marked {@link ChannelHandler.Sharable} — a single instance is
 * shared across all child channels.  All state is stored in the static
 * {@link #WINDOWS} map, not in the handler instance.</p>
 */
@ChannelHandler.Sharable
public class ConnectionRateLimiter extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("KryptonSecurity");

    /**
     * Per-IP sliding window state.
     */
    private static final Map<String, SlidingWindow> WINDOWS = new ConcurrentHashMap<>();

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

        InetAddress address = extractAddress(ctx);
        if (address == null) {
            // Memory connection (singleplayer) — always allow
            super.channelActive(ctx);
            return;
        }

        String ip = address.getHostAddress();

        // ── Rate limiting ─────────────────────────────────────────────
        int burstLimit = KryptonConfig.securityConnectionBurstLimit;

        SlidingWindow window = WINDOWS.computeIfAbsent(ip, k -> new SlidingWindow());
        int count = window.incrementAndGet();

        if (count > burstLimit) {
            SecurityMetrics.INSTANCE.recordConnectionRateLimited();
            LOGGER.warn("[Krypton Security] Connection rate limit exceeded for {} ({}/s, burst {})",
                    ip, count, burstLimit);
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
        WINDOWS.entrySet().removeIf(e -> now - e.getValue().lastAccessTime() > 30_000);
    }

    private static InetAddress extractAddress(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress inet) {
            return inet.getAddress();
        }
        return null;
    }

    // ── Sliding window implementation ─────────────────────────────────

    /**
     * A simple sliding-window counter that resets every second.
     */
    static final class SlidingWindow {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();
        private volatile long lastAccess = System.currentTimeMillis();

        int incrementAndGet() {
            long now = System.currentTimeMillis();
            lastAccess = now;

            // Reset the window if more than 1 second has passed
            if (now - windowStart > 1000) {
                synchronized (this) {
                    if (now - windowStart > 1000) {
                        count.set(0);
                        windowStart = now;
                    }
                }
            }

            return count.incrementAndGet();
        }

        long lastAccessTime() {
            return lastAccess;
        }
    }
}
