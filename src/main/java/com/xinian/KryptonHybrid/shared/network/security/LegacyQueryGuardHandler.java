package com.xinian.KryptonHybrid.shared.network.security;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

/**
 * Applies Krypton's status-ping throttle before vanilla's legacy query handler.
 */
public final class LegacyQueryGuardHandler extends ChannelInboundHandlerAdapter {
    public static final String HANDLER_NAME = "krypton_legacy_query_guard";

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf buf && !StatusRequestGuard.allowLegacyQuery(ctx.channel(), buf.readableBytes())) {
            ReferenceCountUtil.release(msg);
            ctx.close();
            return;
        }

        super.channelRead(ctx, msg);
    }
}
