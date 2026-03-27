package com.xinian.KryptonHybrid.shared.network.compression;

import com.github.luben.zstd.ZstdCompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import net.minecraft.network.FriendlyByteBuf;
import com.xinian.KryptonHybrid.shared.network.NetworkTrafficStats;

/**
 * A Netty {@link MessageToByteEncoder} that compresses outgoing Minecraft packets using
 * the Zstandard (Zstd) algorithm via the zstd-jni library.
 *
 * <p>The wire format intentionally mirrors the vanilla
 * {@link net.minecraft.network.CompressionEncoder} / Krypton
 * {@link MinecraftCompressEncoder} protocol so that the
 * {@link ZstdCompressDecoder} on the receiving end can reverse it correctly:</p>
 * <pre>
 *   VarInt(0)                        \u2013 packet is NOT compressed (size &lt; threshold)
 *   raw bytes
 *
 *   VarInt(original_uncompressed_size)  \u2013 packet IS compressed
 *   Zstd-compressed bytes
 * </pre>
 *
 * <p><strong>Note:</strong> Both sides of a connection (client and server) must run
 * KryptonFNP with {@code compression.algorithm = ZSTD} for Zstd to be used.</p>
 */
public class ZstdCompressEncoder extends MessageToByteEncoder<ByteBuf> {

    private int threshold;
    private final ZstdCompressCtx compressor;

    /**
     * Creates a new encoder.
     *
     * @param threshold  packets whose uncompressed size is below this value will be sent
     *                   raw (VarInt 0 prefix); packets at or above it will be Zstd-compressed
     * @param compressor a per-channel {@link ZstdCompressCtx} instance (see
     *                   {@link ZstdUtil#createCompressor()})
     */
    public ZstdCompressEncoder(int threshold, ZstdCompressCtx compressor) {
        this.threshold = threshold;
        this.compressor = compressor;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        FriendlyByteBuf wrappedOut = new FriendlyByteBuf(out);
        int uncompressedSize = msg.readableBytes();
        int startWireIndex = out.writerIndex();

        if (uncompressedSize < threshold) {
            wrappedOut.writeVarInt(0);
            out.writeBytes(msg);
            NetworkTrafficStats.INSTANCE.recordEncode(uncompressedSize, out.writerIndex() - startWireIndex);
            return;
        }

        byte[] inputBytes = new byte[uncompressedSize];
        msg.readBytes(inputBytes);

        int maxOut = ZstdUtil.maxCompressedLength(uncompressedSize);
        byte[] compressedBytes = new byte[maxOut];

        int compressedSize = compressor.compressByteArray(
                compressedBytes, 0, maxOut,
                inputBytes, 0, uncompressedSize);

        wrappedOut.writeVarInt(uncompressedSize);
        out.writeBytes(compressedBytes, 0, compressedSize);
        NetworkTrafficStats.INSTANCE.recordEncode(uncompressedSize, out.writerIndex() - startWireIndex);
    }

    /**
     * Pre-allocates the output buffer.
     */
    @Override
    protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, ByteBuf msg, boolean preferDirect)
            throws Exception {
        int readable = msg.readableBytes();
        int initialSize = 5 + ZstdUtil.maxCompressedLength(readable);
        return preferDirect
                ? ctx.alloc().directBuffer(initialSize)
                : ctx.alloc().heapBuffer(initialSize);
    }

    /**
     * Releases the native Zstd compression context when the handler is removed from the pipeline.
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        compressor.close();
        super.handlerRemoved(ctx);
    }

    /**
     * Updates the compression threshold without replacing the pipeline handler.
     *
     * @param threshold the new threshold in bytes
     */
    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }
}

