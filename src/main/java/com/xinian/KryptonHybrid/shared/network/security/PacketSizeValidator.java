package com.xinian.KryptonHybrid.shared.network.security;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates decoded packet frame sizes before they reach the Minecraft packet decoder.
 *
 * <p>This handler sits <strong>after</strong> the {@code Varint21FrameDecoder} (splitter)
 * in the pipeline.  At this point, each inbound message is a {@link ByteBuf} containing
 * exactly one framed packet (VarInt length prefix already stripped).</p>
 *
 * <h3>Checks</h3>
 * <ul>
 *   <li><strong>Max frame size:</strong> rejects packets larger than the configured
 *       maximum (default 2 MiB).  This catches oversized packets that somehow pass
 *       the compression layer.</li>
 *   <li><strong>Empty frame:</strong> rejects zero-length frames that waste processing
 *       time in the decoder.</li>
 * </ul>
 *
 * <p>Future enhancements may include NBT depth validation and string length checks
 * (would require hooking into the FriendlyByteBuf layer).</p>
 */
public class PacketSizeValidator extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("KryptonSecurity");

    public static final String HANDLER_NAME = "krypton_size_validator";

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!KryptonConfig.securityEnabled) {
            super.channelRead(ctx, msg);
            return;
        }

        if (msg instanceof ByteBuf buf) {
            int size = buf.readableBytes();
            int maxSize = KryptonConfig.securityMaxPacketBytes;

            // ── Empty frame check ─────────────────────────────────────
            if (size == 0) {
                SecurityMetrics.INSTANCE.recordPacketSizeRejected();
                LOGGER.debug("[Krypton Security] Rejected empty packet frame from {}",
                        ctx.channel().remoteAddress());
                buf.release();
                return;
            }

            // ── Oversized frame check ─────────────────────────────────
            if (size > maxSize) {
                SecurityMetrics.INSTANCE.recordPacketSizeRejected();
                LOGGER.warn("[Krypton Security] Rejected oversized packet: {} bytes (max {}) from {}",
                        size, maxSize, ctx.channel().remoteAddress());
                buf.release();

                // Close connection on severely oversized packets (>4x limit)
                if (size > maxSize * 4L) {
                    LOGGER.warn("[Krypton Security] Severely oversized packet — closing connection to {}",
                            ctx.channel().remoteAddress());
                    ctx.close();
                }
                return;
            }
        }

        super.channelRead(ctx, msg);
    }
}

