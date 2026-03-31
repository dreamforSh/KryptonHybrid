package com.xinian.KryptonHybrid.shared.network.security;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import io.netty.channel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Netty channel handler that protects against memory exhaustion from slow or
 * malicious clients that don't read data fast enough.
 *
 * <h3>Protections</h3>
 * <ul>
 *   <li><strong>Write watermarks:</strong> configures the channel's
 *       {@link WriteBufferWaterMark} so Netty signals when the pending outbound
 *       buffer exceeds the high watermark and resumes when it drops below the low
 *       watermark.</li>
 *   <li><strong>Pending queue limit:</strong> tracks the number of pending writes
 *       and drops new writes when the limit is exceeded, preventing unbounded memory
 *       growth.</li>
 *   <li><strong>Unwritable timeout:</strong> if a channel stays unwritable for too
 *       long (slow client), the connection is forcibly closed.</li>
 * </ul>
 */
public class NettyResourceGuard extends ChannelDuplexHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("KryptonSecurity");

    public static final String HANDLER_NAME = "krypton_resource_guard";

    /** Tracks pending write count for this connection. */
    private final AtomicLong pendingWrites = new AtomicLong(0);

    /** Timestamp when the channel became unwritable (0 if writable). */
    private volatile long unwritableSince = 0;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (KryptonConfig.securityEnabled) {
            // Apply write watermarks
            int low = KryptonConfig.securityWriteWatermarkLow;
            int high = KryptonConfig.securityWriteWatermarkHigh;
            ctx.channel().config().setWriteBufferWaterMark(
                    new WriteBufferWaterMark(low, high));
        }
        super.channelActive(ctx);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
            throws Exception {
        if (!KryptonConfig.securityEnabled) {
            super.write(ctx, msg, promise);
            return;
        }

        long pending = pendingWrites.incrementAndGet();
        int maxPending = KryptonConfig.securityMaxPendingWrites;

        // ── Pending queue limit ───────────────────────────────────────
        if (pending > maxPending) {
            pendingWrites.decrementAndGet();
            SecurityMetrics.INSTANCE.recordWriteDropped();
            promise.setFailure(new IOException("Pending write queue exceeded limit: " + pending));
            // Release the message to prevent memory leak
            io.netty.util.ReferenceCountUtil.release(msg);
            return;
        }

        // Decrement on completion
        promise.addListener((ChannelFutureListener) future -> pendingWrites.decrementAndGet());

        super.write(ctx, msg, promise);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (KryptonConfig.securityEnabled) {
            if (!ctx.channel().isWritable()) {
                // Channel became unwritable — start tracking
                if (unwritableSince == 0) {
                    unwritableSince = System.currentTimeMillis();
                    SecurityMetrics.INSTANCE.recordWatermarkBreach();
                    LOGGER.debug("[Krypton Security] Channel unwritable: {}",
                            ctx.channel().remoteAddress());
                }
            } else {
                // Channel is writable again
                if (unwritableSince != 0) {
                    long duration = System.currentTimeMillis() - unwritableSince;
                    LOGGER.debug("[Krypton Security] Channel writable again after {} ms: {}",
                            duration, ctx.channel().remoteAddress());
                    unwritableSince = 0;
                }
            }
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // Check if the channel has been unwritable too long (slow client attack)
        if (KryptonConfig.securityEnabled && unwritableSince != 0) {
            long elapsed = System.currentTimeMillis() - unwritableSince;
            int maxUnwritableSec = KryptonConfig.securityMaxUnwritableSeconds;
            if (elapsed > maxUnwritableSec * 1000L) {
                LOGGER.warn("[Krypton Security] Channel unwritable for {} ms, closing: {}",
                        elapsed, ctx.channel().remoteAddress());
                ctx.close();
                return;
            }
        }
        super.channelRead(ctx, msg);
    }

    private static class IOException extends Exception {
        IOException(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}

