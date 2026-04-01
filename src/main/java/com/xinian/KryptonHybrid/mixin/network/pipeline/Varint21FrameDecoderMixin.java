package com.xinian.KryptonHybrid.mixin.network.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import com.xinian.KryptonHybrid.shared.network.NetworkTrafficStats;
import com.xinian.KryptonHybrid.shared.network.security.SecurityMetrics;
import com.xinian.KryptonHybrid.shared.network.util.QuietDecoderException;
import net.minecraft.network.Varint21FrameDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

import static io.netty.util.ByteProcessor.FIND_NON_NUL;
import static com.xinian.KryptonHybrid.shared.network.util.WellKnownExceptions.BAD_LENGTH_CACHED;
import static com.xinian.KryptonHybrid.shared.network.util.WellKnownExceptions.VARINT_BIG_CACHED;

/**
 * Replaces the Varint21FrameDecoder with optimized packet splitting from Velocity 1.1.0. In addition this applies a
 * security fix to stop "nullping" attacks.
 * <p>
 * Uses {@code @Inject} with cancellation instead of {@code @Overwrite} to preserve the original method bytecode,
 * allowing other mods (e.g. PacketFixer) to apply their own injections without conflict.
 */
@Mixin(value = Varint21FrameDecoder.class)
public class Varint21FrameDecoderMixin {

    @Inject(method = "decode", at = @At("HEAD"), cancellable = true)
    private void decode$kryptonfnp(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) throws Exception {
        if (!ctx.channel().isActive()) {
            in.clear();
            ci.cancel();
            return;
        }


        int packetStart = in.forEachByte(FIND_NON_NUL);
        if (packetStart == -1) {
            SecurityMetrics.INSTANCE.recordNullFrameDropped();
            in.clear();
            ci.cancel();
            return;
        }
        in.readerIndex(packetStart);


        in.markReaderIndex();
        int preIndex = in.readerIndex();
        int length = kryptonfnp$readRawVarInt21(in);
        if (preIndex == in.readerIndex()) {

            ci.cancel();
            return;
        }
        if (length < 0) {
            ci.cancel();
            throw BAD_LENGTH_CACHED;
        }


        if (length > 0) {
            if (in.readableBytes() < length) {
                in.resetReaderIndex();
            } else {
                out.add(in.readRetainedSlice(length));
                NetworkTrafficStats.INSTANCE.recordDecode(length);
            }
        }
        ci.cancel();
    }

    /**
     * Reads a VarInt from the buffer of up to 21 bits in size.
     *
     * @param buffer the buffer to read from
     * @return the VarInt decoded, {@code 0} if no varint could be read
     * @throws QuietDecoderException if the VarInt is too big to be decoded
     */
    @Unique
    private static int kryptonfnp$readRawVarInt21(ByteBuf buffer) {
        if (buffer.readableBytes() < 4) {

            return kryptonfnp$readRawVarintSmallBuf(buffer);
        }
        int wholeOrMore = buffer.getIntLE(buffer.readerIndex());


        int atStop = ~wholeOrMore & 0x808080;
        if (atStop == 0) {

            throw VARINT_BIG_CACHED;
        }

        int bitsToKeep = Integer.numberOfTrailingZeros(atStop) + 1;
        buffer.skipBytes(bitsToKeep >> 3);

        // remove all bits we don't need to keep, a trick from
        // https://github.com/netty/netty/pull/14050#issuecomment-2107750734:
        //
        // > The idea is that thisVarintMask has 0s above the first one of firstOneOnStop, and 1s at
        // > and below it. For example if firstOneOnStop is 0x800080 (where the last 0x80 is the only
        // > one that matters), then thisVarintMask is 0xFF.
        //
        // this is also documented in Hacker's Delight, section 2-1 "Manipulating Rightmost Bits"
        int preservedBytes = wholeOrMore & (atStop ^ (atStop - 1));

        // merge together using this trick: https://github.com/netty/netty/pull/14050#discussion_r1597896639
        preservedBytes = (preservedBytes & 0x007F007F) | ((preservedBytes & 0x00007F00) >> 1);
        preservedBytes = (preservedBytes & 0x00003FFF) | ((preservedBytes & 0x3FFF0000) >> 2);
        return preservedBytes;
    }

    @Unique
    private static int kryptonfnp$readRawVarintSmallBuf(ByteBuf buffer) {
        if (!buffer.isReadable()) {
            return 0;
        }
        buffer.markReaderIndex();

        byte tmp = buffer.readByte();
        if (tmp >= 0) {
            return tmp;
        }
        int result = tmp & 0x7F;
        if (!buffer.isReadable()) {
            buffer.resetReaderIndex();
            return 0;
        }
        if ((tmp = buffer.readByte()) >= 0) {
            return result | tmp << 7;
        }
        result |= (tmp & 0x7F) << 7;
        if (!buffer.isReadable()) {
            buffer.resetReaderIndex();
            return 0;
        }
        if ((tmp = buffer.readByte()) >= 0) {
            return result | tmp << 14;
        }
        return result | (tmp & 0x7F) << 14;
    }
}

