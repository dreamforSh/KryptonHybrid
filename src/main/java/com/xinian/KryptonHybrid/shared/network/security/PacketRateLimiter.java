package com.xinian.KryptonHybrid.shared.network.security;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-connection packet rate limiter using a token-bucket algorithm.
 *
 * <p>Each connection gets its own instance of this handler (not sharable).
 * On every inbound {@link #channelRead}, one token is consumed.  If no tokens
 * remain, the packet is dropped and the connection is closed after a configurable
 * tolerance window.</p>
 *
 * <h3>Token bucket parameters</h3>
 * <ul>
 *   <li>{@code packetsPerSecond} — sustained rate limit (tokens added per second)</li>
 *   <li>{@code burstCapacity} — maximum token accumulation (handles short bursts)</li>
 * </ul>
 *
 * <p>The bucket is refilled lazily on each {@code channelRead} call using elapsed
 * time since the last refill, avoiding the need for a dedicated timer thread.</p>
 */
public class PacketRateLimiter extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("KryptonSecurity");

    // ── Token bucket state (per instance / per connection) ────────────

    private double tokens;
    private long lastRefillNanos;
    private int violations;

    /** Max consecutive violations before the connection is closed. */
    private static final int MAX_VIOLATIONS = 5;

    public PacketRateLimiter() {
        this.tokens = KryptonConfig.securityPacketBurstLimit;
        this.lastRefillNanos = System.nanoTime();
        this.violations = 0;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!KryptonConfig.securityEnabled || !KryptonConfig.securityPacketRateLimitEnabled) {
            super.channelRead(ctx, msg);
            return;
        }

        // ── Refill tokens ─────────────────────────────────────────────
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        lastRefillNanos = now;

        double pps = KryptonConfig.securityPacketsPerSecond;
        double burst = KryptonConfig.securityPacketBurstLimit;

        tokens = Math.min(burst, tokens + (elapsed / 1_000_000_000.0) * pps);

        // ── Consume token ─────────────────────────────────────────────
        if (tokens >= 1.0) {
            tokens -= 1.0;
            violations = 0; // reset on successful consumption
            super.channelRead(ctx, msg);
        } else {
            // Rate limit exceeded
            SecurityMetrics.INSTANCE.recordPacketRateLimited();
            violations++;

            if (violations >= MAX_VIOLATIONS) {
                LOGGER.warn("[Krypton Security] Packet rate limit exceeded for {} — "
                                + "closing connection after {} violations",
                        ctx.channel().remoteAddress(), violations);

                ctx.close();
            }
            // else: silently drop the packet (don't forward downstream)
            // ReferenceCountUtil would release the msg if needed, but decoded
            // Minecraft packets past the frame decoder are not reference-counted
        }
    }
}

