package com.xinian.KryptonHybrid.shared.network.security;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

/**
 * Applies Krypton's status-ping throttle before vanilla's legacy query handler.
 *
 * <p>Like vanilla's {@code legacy_query} handler, this guard only inspects the first
 * inbound read of a connection and then removes itself, so later reads (handshake,
 * login, play) are never charged against the status-ping budget. Only reads that
 * start with the legacy ping id {@code 0xFE} are charged.</p>
 */
public final class LegacyQueryGuardHandler extends ChannelInboundHandlerAdapter {
    public static final String HANDLER_NAME = "krypton_legacy_query_guard";

    private static final int LEGACY_PING_ID = 0xFE;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ctx.pipeline().remove(this);

        if (msg instanceof ByteBuf buf
                && buf.isReadable()
                && buf.getUnsignedByte(buf.readerIndex()) == LEGACY_PING_ID
                && !StatusRequestGuard.allowLegacyQuery(ctx.channel(), buf.readableBytes())) {
            ReferenceCountUtil.release(msg);
            ctx.close();
            return;
        }

        ctx.fireChannelRead(msg);
    }
}
