package com.xinian.KryptonHybrid.shared.network.compression;

import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdException;
import com.xinian.KryptonHybrid.shared.network.security.DecompressionBombGuard;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import net.minecraft.network.FriendlyByteBuf;

import java.nio.ByteBuffer;
import java.util.List;

import static com.google.common.base.Preconditions.checkState;

/**
 * Low-latency Netty {@link MessageToMessageDecoder} that decompresses incoming Minecraft
 * packets that were compressed by a {@link ZstdCompressEncoder} on the other end of the
 * connection.
 *
 * <h3>Wire format</h3>
 * <pre>
 *   VarInt(0)                           – packet NOT compressed
 *   raw bytes (passed through unchanged)
 *
 *   VarInt(original_uncompressed_size)  – packet IS Zstd-compressed
 *   Zstd-compressed bytes
 * </pre>
 *
 * <h3>Latency optimizations</h3>
 *
 * <h4>1. Zero-copy DirectByteBuffer fast path</h4>
 * <p>When the incoming {@code in} buffer is backed by direct memory (the common case
 * for data read from the socket), and a direct output buffer can be allocated, the
 * decoder calls {@link ZstdDecompressCtx#decompressDirectByteBuffer} which passes
 * native pointers directly to libzstd.  This eliminates:</p>
 * <ul>
 *   <li>Three {@code new byte[]} heap allocations per packet (compressed copy,
 *       uncompressed output, final ByteBuf backing array)</li>
 *   <li>Two full-payload {@code memcpy} operations</li>
 *   <li>GC pressure from short-lived byte arrays</li>
 * </ul>
 *
 * <h4>2. Per-handler reusable scratch buffers (heap fallback)</h4>
 * <p>When the incoming buffer is heap-backed, the decoder falls back to
 * {@link ZstdDecompressCtx#decompressByteArray} using <em>grow-only</em> byte
 * arrays stored as instance fields, avoiding per-packet allocations.</p>
 *
 * <h4>3. Direct output allocation</h4>
 * <p>The decompressed output is allocated as a direct buffer when possible, keeping
 * the data in off-heap memory for the downstream pipeline stages (packet decoder,
 * encryption) which also operate on direct buffers.</p>
 *
 * <h4>Latency impact summary</h4>
 * <table>
 *   <tr><th>Operation</th><th>Before</th><th>After (direct)</th></tr>
 *   <tr><td>Heap alloc</td><td>3 × new byte[N]</td><td>0</td></tr>
 *   <tr><td>Data copies</td><td>2 × memcpy(N)</td><td>0</td></tr>
 *   <tr><td>GC pressure</td><td>~3N bytes/packet</td><td>0</td></tr>
 * </table>
 *
 * <h3>Safety limits</h3>
 * <ul>
 *   <li>Uncompressed size must be &ge; threshold (when validation enabled)</li>
 *   <li>Uncompressed size must not exceed 8 MiB (or 128 MiB with
 *       {@code -Dkrypton.permit-oversized-packets=true})</li>
 * </ul>
 *
 * @see ZstdCompressEncoder
 * @see ZstdUtil#createDecompressor()
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

    /** Grow-only scratch buffer for compressed input (heap-fallback path only). */
    private byte[] scratchIn = new byte[8192];
    /** Grow-only scratch buffer for decompressed output (heap-fallback path only). */
    private byte[] scratchOut = new byte[8192];

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
            // --- Uncompressed packet: pass through ---
            int actualUncompressedSize = in.readableBytes();
            checkState(actualUncompressedSize < threshold,
                    "Actual uncompressed size %s is greater than threshold %s",
                    actualUncompressedSize, threshold);
            out.add(in.retain());
            return;
        }

        // --- Validate claimed size ---
        if (validate) {
            checkState(claimedUncompressedSize >= threshold,
                    "Uncompressed size %s is less than threshold %s",
                    claimedUncompressedSize, threshold);
            checkState(claimedUncompressedSize <= UNCOMPRESSED_CAP,
                    "Uncompressed size %s exceeds hard cap of %s",
                    claimedUncompressedSize, UNCOMPRESSED_CAP);
        }

        int compressedSize = in.readableBytes();

        // --- Decompression bomb guard ---
        DecompressionBombGuard.validate(compressedSize, claimedUncompressedSize, ctx.channel());

        // --- Fast path: direct input → direct output, zero-copy ---
        if (in.isDirect() && in.nioBufferCount() == 1) {
            ByteBuf result = ctx.alloc().directBuffer(claimedUncompressedSize);
            try {
                ByteBuffer nioIn  = in.nioBuffer(in.readerIndex(), compressedSize);
                ByteBuffer nioOut = result.nioBuffer(0, claimedUncompressedSize);

                int actualDecompressed;
                try {
                    actualDecompressed = decompressor.decompressDirectByteBuffer(
                            nioOut, 0, claimedUncompressedSize,
                            nioIn,  0, compressedSize);
                } catch (ZstdException e) {
                    throw new Exception("Zstd decompression failed: " + e.getMessage(), e);
                }

                checkState(actualDecompressed == claimedUncompressedSize,
                        "Zstd decompressed size %s does not match claimed size %s",
                        actualDecompressed, claimedUncompressedSize);

                in.skipBytes(compressedSize);
                result.writerIndex(claimedUncompressedSize);
                out.add(result);
                result = null; // ownership transferred to out
            } finally {
                if (result != null) {
                    result.release(); // cleanup on exception
                }
            }
            return;
        }

        // --- Heap fallback: reusable scratch buffers ---
        if (scratchIn.length < compressedSize) {
            scratchIn = new byte[compressedSize];
        }
        in.readBytes(scratchIn, 0, compressedSize);

        if (scratchOut.length < claimedUncompressedSize) {
            scratchOut = new byte[claimedUncompressedSize];
        }

        int actualDecompressed;
        try {
            actualDecompressed = decompressor.decompressByteArray(
                    scratchOut, 0, claimedUncompressedSize,
                    scratchIn, 0, compressedSize);
        } catch (ZstdException e) {
            throw new Exception("Zstd decompression failed: " + e.getMessage(), e);
        }

        checkState(actualDecompressed == claimedUncompressedSize,
                "Zstd decompressed size %s does not match claimed size %s",
                actualDecompressed, claimedUncompressedSize);

        // Write from scratch to a direct buffer for downstream pipeline stages
        ByteBuf result = ctx.alloc().directBuffer(claimedUncompressedSize);
        result.writeBytes(scratchOut, 0, claimedUncompressedSize);
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

