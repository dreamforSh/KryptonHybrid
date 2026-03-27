package com.xinian.KryptonHybrid.shared.network.compression;

import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;

import static com.google.common.base.Preconditions.checkState;

/**
 * A Netty {@link MessageToMessageDecoder} that decompresses incoming Minecraft packets
 * that were compressed by a {@link ZstdCompressEncoder} on the other end of the connection.
 *
 * <p>Protocol format (same as vanilla / {@link MinecraftCompressDecoder}):</p>
 * <pre>
 *   VarInt(0)                           \u2013 packet is NOT compressed
 *   raw bytes (passed through unchanged)
 *
 *   VarInt(original_uncompressed_size)  \u2013 packet IS Zstd-compressed
 *   Zstd-compressed bytes
 * </pre>
 *
 * <p>Safety limits copied from {@link MinecraftCompressDecoder}:</p>
 * <ul>
 *   <li>Uncompressed size must be &ge; threshold (unless validation is disabled)</li>
 *   <li>Uncompressed size must not exceed 8&nbsp;MiB (or 128&nbsp;MiB with
 *       {@code -Dkrypton.permit-oversized-packets=true})</li>
 * </ul>
 *
 * <p>Each instance owns its own {@link ZstdDecompressCtx} which must be
 * {@link #handlerRemoved closed} when the handler is removed from the pipeline.</p>
 */
public class ZstdCompressDecoder extends MessageToMessageDecoder<ByteBuf> {

    private static final int VANILLA_MAXIMUM_UNCOMPRESSED_SIZE = 8 * 1024 * 1024;
    private static final int HARD_MAXIMUM_UNCOMPRESSED_SIZE    = 128 * 1024 * 1024;

    private static final int UNCOMPRESSED_CAP =
            Boolean.getBoolean("krypton.permit-oversized-packets")
                    ? HARD_MAXIMUM_UNCOMPRESSED_SIZE
                    : VANILLA_MAXIMUM_UNCOMPRESSED_SIZE;

    private final ZstdDecompressCtx decompressor;
    private final boolean validate;
    private int threshold;

    /**
     * Creates a new decoder.
     *
     * @param threshold    the compression threshold in bytes (used for validation)
     * @param validate     when {@code true}, incoming packets are checked against the
     *                     threshold and the hard size cap
     * @param decompressor a per-channel {@link ZstdDecompressCtx} (see
     *                     {@link ZstdUtil#createDecompressor()})
     */
    public ZstdCompressDecoder(int threshold, boolean validate, ZstdDecompressCtx decompressor) {
        this.threshold = threshold;
        this.validate = validate;
        this.decompressor = decompressor;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        FriendlyByteBuf bb = new FriendlyByteBuf(in);
        int claimedUncompressedSize = bb.readVarInt();

        if (claimedUncompressedSize == 0) {
            int actualUncompressedSize = in.readableBytes();
            checkState(actualUncompressedSize < threshold,
                    "Actual uncompressed size %s is greater than threshold %s",
                    actualUncompressedSize, threshold);
            out.add(in.retain());
            return;
        }

        if (validate) {
            checkState(claimedUncompressedSize >= threshold,
                    "Uncompressed size %s is less than threshold %s",
                    claimedUncompressedSize, threshold);
            checkState(claimedUncompressedSize <= UNCOMPRESSED_CAP,
                    "Uncompressed size %s exceeds hard cap of %s",
                    claimedUncompressedSize, UNCOMPRESSED_CAP);
        }

        int compressedSize = in.readableBytes();
        byte[] compressedBytes = new byte[compressedSize];
        in.readBytes(compressedBytes);

        byte[] uncompressedBytes = new byte[claimedUncompressedSize];

        int actualDecompressed;
        try {
            actualDecompressed = decompressor.decompressByteArray(
                    uncompressedBytes, 0, claimedUncompressedSize,
                    compressedBytes, 0, compressedSize);
        } catch (ZstdException e) {
            throw new Exception("Zstd decompression failed: " + e.getMessage(), e);
        }

        checkState(actualDecompressed == claimedUncompressedSize,
                "Zstd decompressed size %s does not match claimed size %s",
                actualDecompressed, claimedUncompressedSize);

        ByteBuf result = ctx.alloc().heapBuffer(claimedUncompressedSize);
        result.writeBytes(uncompressedBytes, 0, claimedUncompressedSize);
        out.add(result);
    }

    /**
     * Releases the native Zstd decompression context when the handler is removed from the pipeline.
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        decompressor.close();
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

