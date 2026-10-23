package com.xinian.KryptonHybrid.mixin.network.pipeline;

import com.xinian.KryptonHybrid.shared.network.util.ReservedFrameHeader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.MessageToByteEncoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Writes the frame length into the room the compression encoders reserve in front of
 * their output and passes that same buffer on, instead of allocating a new buffer and
 * copying the whole frame behind the length. Any other buffer (no compression, another
 * handler's output, a frame too large) takes vanilla's path.
 */
@Mixin(Varint21LengthFieldPrepender.class)
public abstract class Varint21LengthFieldPrependerMixin extends MessageToByteEncoder<ByteBuf> {

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf buf && ReservedFrameHeader.prependLength(buf)) {
            ctx.write(buf, promise);
            return;
        }
        super.write(ctx, msg, promise);
    }
}
