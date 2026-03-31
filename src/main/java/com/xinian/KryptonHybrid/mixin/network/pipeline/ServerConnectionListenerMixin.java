package com.xinian.KryptonHybrid.mixin.network.pipeline;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.security.*;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects Krypton security handlers into every new inbound server connection's
 * Netty pipeline.
 *
 * <p>Targets {@link ServerConnectionListener}'s inner {@code ChannelInitializer}
 * that runs inside {@code startTcpServerListener()}.  The injection point is the
 * {@code initChannel(Channel)} method of the anonymous {@code ChannelInitializer}
 * subclass created in that method.</p>
 *
 * <p>We inject at {@link Connection#configureSerialization}, which is one of the
 * last things called in vanilla's channel initializer.  By injecting after it
 * returns, the vanilla pipeline is fully built and we can insert our handlers at
 * the correct positions relative to the existing ones.</p>
 *
 * <h3>Pipeline order after injection</h3>
 * <pre>
 *   krypton_rate_limiter   (ConnectionRateLimiter — first, sharable)
 *   krypton_timeout        (HandshakeTimeoutHandler — stage-aware)
 *   timeout                (vanilla ReadTimeoutHandler)
 *   legacy_query           (vanilla LegacyQueryHandler)
 *   splitter               (Varint21FrameDecoder)
 *   krypton_size_validator (PacketSizeValidator — after frame decode)
 *   krypton_pps_limiter    (PacketRateLimiter — per-connection)
 *   decoder                (PacketDecoder)
 *   prepender              (Varint21LengthFieldPrepender)
 *   encoder                (PacketEncoder)
 *   krypton_resource_guard (NettyResourceGuard — write side)
 *   packet_handler         (Connection)
 * </pre>
 */
@Mixin(targets = "net.minecraft.server.network.ServerConnectionListener$1")
public class ServerConnectionListenerMixin {

    /**
     * Injects after {@code Connection.configureSerialization()} completes, which is the
     * point at which the vanilla pipeline is fully assembled.
     */
    @Inject(
            method = "initChannel",
            at = @At("TAIL")
    )
    private void krypton$injectSecurityHandlers(Channel channel, CallbackInfo ci) {
        if (!KryptonConfig.securityEnabled) return;

        ChannelPipeline pipeline = channel.pipeline();

        // ── 1. Connection rate limiter (first handler, sharable singleton) ──
        pipeline.addFirst("krypton_rate_limiter", ConnectionRateLimiter.INSTANCE);

        // ── 2. Handshake timeout handler (after rate limiter) ──────────────
        pipeline.addAfter("krypton_rate_limiter",
                HandshakeTimeoutHandler.HANDLER_NAME,
                new HandshakeTimeoutHandler());

        // ── 3. Packet size validator (after frame decoder "splitter") ──────
        if (pipeline.get("splitter") != null) {
            pipeline.addAfter("splitter",
                    PacketSizeValidator.HANDLER_NAME,
                    new PacketSizeValidator());
        }

        // ── 4. Packet rate limiter (after size validator / before decoder) ─
        String afterHandler = pipeline.get(PacketSizeValidator.HANDLER_NAME) != null
                ? PacketSizeValidator.HANDLER_NAME
                : "splitter";
        if (pipeline.get(afterHandler) != null) {
            pipeline.addAfter(afterHandler,
                    "krypton_pps_limiter",
                    new PacketRateLimiter());
        }

        // ── 5. Netty resource guard (before packet_handler, write side) ────
        if (pipeline.get("packet_handler") != null) {
            pipeline.addBefore("packet_handler",
                    NettyResourceGuard.HANDLER_NAME,
                    new NettyResourceGuard());
        }

        // ── 6. Initialize anomaly detector attribute ──────────────────────
        AnomalyDetector.get(channel);
    }
}

