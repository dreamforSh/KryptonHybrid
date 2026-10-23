package com.xinian.KryptonHybrid.shared.network.security;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Stage-aware read timeout handler that adjusts the timeout based on the
 * current connection lifecycle phase.
 *
 * <h3>Stages</h3>
 * <ul>
 *   <li><strong>HANDSHAKE</strong> — initial TCP connection, waiting for the
 *       handshake packet. Timeout: 5s (configurable). Prevents half-open connections
 *       from occupying resources.</li>
 *   <li><strong>LOGIN</strong> — handshake received, encryption/compression being
 *       negotiated. Timeout: 30s (configurable). Covers Velocity forwarding, Mojang
 *       session verification, etc.</li>
 *   <li><strong>PLAY</strong> — game session active. Timeout: 30s (configurable).
 *       Vanilla already has its own keepalive; this is a safety net for completely
 *       dead connections.</li>
 * </ul>
 *
 * <p>A read timeout only measures silence, so a peer sending a byte every few seconds
 * would never time out. The HANDSHAKE stage therefore also has an absolute deadline of
 * twice its timeout: a login has left it by then, and a status ping (which never leaves
 * it) is long done.</p>
 *
 * <p>The handler is inserted by {@code ServerConnectionListenerMixin} and starts
 * in HANDSHAKE stage.  Other mixins (or pipeline events) call
 * {@link #advanceStage(ChannelHandlerContext, Stage)} to update the timeout.</p>
 */
public class HandshakeTimeoutHandler extends ReadTimeoutHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("KryptonSecurity");

    public enum Stage {
        HANDSHAKE,
        LOGIN,
        PLAY
    }

    public static final String HANDLER_NAME = "krypton_timeout";

    private volatile Stage currentStage = Stage.HANDSHAKE;

    /** Absolute HANDSHAKE-stage deadline; cancelled when this handler is replaced or removed. */
    private ScheduledFuture<?> handshakeDeadline;

    /**
     * Creates a handler starting in HANDSHAKE stage.
     */
    public HandshakeTimeoutHandler() {
        super(KryptonConfig.securityHandshakeTimeoutSec, TimeUnit.SECONDS);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        if (this.currentStage == Stage.HANDSHAKE && KryptonConfig.securityEnabled) {
            long deadlineSec = 2L * Math.max(1, KryptonConfig.securityHandshakeTimeoutSec);
            this.handshakeDeadline = ctx.executor().schedule(() -> {
                if (KryptonConfig.securityEnabled && ctx.channel().isOpen()
                        && ctx.pipeline().get(HANDLER_NAME) == this) {
                    SecurityMetrics.INSTANCE.recordTimeout();
                    LOGGER.debug("[Krypton Security] Handshake deadline passed for {}", ctx.channel().remoteAddress());
                    ctx.close();
                }
            }, deadlineSec, TimeUnit.SECONDS);
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        if (this.handshakeDeadline != null) {
            this.handshakeDeadline.cancel(false);
            this.handshakeDeadline = null;
        }
        super.handlerRemoved(ctx);
    }

    @Override
    protected void readTimedOut(ChannelHandlerContext ctx) throws Exception {
        // Security switched off after this connection was set up: stay inert and leave
        // timeouts to vanilla's own read timeout.
        if (!KryptonConfig.securityEnabled) {
            return;
        }
        SecurityMetrics.INSTANCE.recordTimeout();
        LOGGER.debug("[Krypton Security] Read timeout in {} stage for {}",
                currentStage, ctx.channel().remoteAddress());
        super.readTimedOut(ctx);
    }

    /**
     * Advances to a new stage, adjusting the read timeout.
     *
     * <p>This replaces this handler in the pipeline with a new instance
     * configured for the target stage's timeout value.</p>
     *
     * <p>Not gated on {@code securityEnabled}: a handler installed while security was
     * on must keep following the connection, or a later re-enable would leave a
     * playing connection on the 5 s handshake timeout.</p>
     */
    public static void advanceStage(ChannelHandlerContext ctx, Stage newStage) {
        if (ctx.pipeline().get(HANDLER_NAME) == null) return;

        int timeoutSec = switch (newStage) {
            case HANDSHAKE -> KryptonConfig.securityHandshakeTimeoutSec;
            case LOGIN     -> KryptonConfig.securityLoginTimeoutSec;
            case PLAY      -> KryptonConfig.securityPlayTimeoutSec;
        };

        HandshakeTimeoutHandler newHandler = new HandshakeTimeoutHandler(timeoutSec);
        newHandler.currentStage = newStage;

        try {
            ctx.pipeline().replace(HANDLER_NAME, HANDLER_NAME, newHandler);
        } catch (Exception e) {

            LOGGER.debug("[Krypton Security] Could not advance timeout stage: {}", e.getMessage());
        }
    }

    /**
     * Convenience static method that works with a pipeline directly.
     */
    public static void advanceStage(io.netty.channel.Channel channel, Stage newStage) {
        var handler = channel.pipeline().get(HANDLER_NAME);
        if (handler != null) {
            ChannelHandlerContext ctx = channel.pipeline().context(HANDLER_NAME);
            if (ctx != null) {
                advanceStage(ctx, newStage);
            }
        }
    }

    /**
     * Internal constructor for stage advancement.
     */
    private HandshakeTimeoutHandler(int timeoutSec) {
        super(timeoutSec, TimeUnit.SECONDS);
    }
}

