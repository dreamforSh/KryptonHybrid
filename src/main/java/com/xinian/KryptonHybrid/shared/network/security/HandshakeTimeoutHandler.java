package com.xinian.KryptonHybrid.shared.network.security;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.ReadTimeoutHandler;
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
 *       negotiated. Timeout: 10s (configurable). Covers Velocity forwarding, Mojang
 *       session verification, etc.</li>
 *   <li><strong>PLAY</strong> — game session active. Timeout: 30s (configurable).
 *       Vanilla already has its own keepalive; this is a safety net for completely
 *       dead connections.</li>
 * </ul>
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

    /**
     * Creates a handler starting in HANDSHAKE stage.
     */
    public HandshakeTimeoutHandler() {
        super(KryptonConfig.securityHandshakeTimeoutSec, TimeUnit.SECONDS);
    }

    @Override
    protected void readTimedOut(ChannelHandlerContext ctx) throws Exception {
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
     */
    public static void advanceStage(ChannelHandlerContext ctx, Stage newStage) {
        if (!KryptonConfig.securityEnabled) return;
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
        if (!KryptonConfig.securityEnabled) return;
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

