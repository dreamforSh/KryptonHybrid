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
 *   VarInt(0)                           ??packet NOT compressed
 *   raw bytes (passed through unchanged)
 *
 *   VarInt(original_uncompressed_size)  ??packet IS Zstd-compressed
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
 *   <tr><td>Heap alloc</td><td>3 ? new byte[N]</td><td>0</td></tr>
 *   <tr><td>Data copies</td><td>2 ? memcpy(N)</td><td>0</td></tr>
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

    /** Standard frames (no dictionary). */
    private final ZstdDecompressCtx decompressor;
    /** Magicless dictionary frames, or {@code null} without a dictionary. */
    private final ZstdDecompressCtx dictDecompressor;
    private final boolean validate;
    private int threshold;

    /** Grow-only scratch buffer for compressed input without a backing array (allocated on first use). */
    private byte[] scratchIn;

    /**
     * Creates a new decoder.
     *
     * @param threshold    the compression threshold in bytes (used for validation)
     * @param validate     when {@code true}, incoming packets are checked against the
     *                     threshold and the hard size cap
     * @param decompressor a per-channel {@link ZstdDecompressCtx} for standard frames (see
     *                     {@link ZstdUtil#createDecompressor()})
     * @param dictDecompressor a per-channel context for magicless dictionary frames (see
     *                     {@link ZstdUtil#createDictionaryDecompressor}), or {@code null}
     *                     when the connection has no dictionary
     */
    public ZstdCompressDecoder(int threshold, boolean validate, ZstdDecompressCtx decompressor,
                               ZstdDecompressCtx dictDecompressor) {
        this.threshold = threshold;
        this.validate = validate;
        this.decompressor = decompressor;
        this.dictDecompressor = dictDecompressor;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        FriendlyByteBuf bb = new FriendlyByteBuf(in);
        int claimedUncompressedSize = bb.readVarInt();

        if (claimedUncompressedSize == 0) {
            // --- Uncompressed packet: pass through ---
            //
            // NOTE: Vanilla Minecraft (1.21.1 net.minecraft.network.CompressionDecoder)
            // intentionally performs NO threshold check on the uncompressed branch ??only
            // compressed packets are validated against the threshold.  We deliberately
            // mirror that behaviour to remain interoperable with peers whose effective
            // encoding threshold differs from ours, e.g.:
            //   ??the threshold was lowered mid-session (setupCompression) and an
            //     in-flight packet was already encoded under the previous threshold;
            //   ??BroadcastCompressedCache replays wire bytes captured at an earlier
            //     threshold;
            //   ??a peer using a slightly different compression threshold negotiation.
            //
            // We still enforce the absolute hard cap to prevent abuse via giant
            // uncompressed payloads (which the upstream frame decoder also limits).
            int actualUncompressedSize = in.readableBytes();
            checkState(actualUncompressedSize <= UNCOMPRESSED_CAP,
                    "Uncompressed pass-through size %s exceeds hard cap of %s",
                    actualUncompressedSize, UNCOMPRESSED_CAP);
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

        // --- Decompression bomb guard (server side only: validate is false on the client,
        // whose server legitimately sends packets far above any ratio limit, e.g. uniform
        // light or map data) ---
        if (validate) {
            DecompressionBombGuard.validate(compressedSize, claimedUncompressedSize, ctx.channel());
        }

        // With a dictionary, frames compressed with it are magicless and the rest (large
        // packets) are standard frames; the magic number tells them apart.
        ZstdDecompressCtx zstd = dictDecompressor != null && !ZstdUtil.isStandardFrame(in)
                ? dictDecompressor
                : decompressor;

        // --- Fast path: direct input ??direct output, zero-copy ---
        if (in.isDirect() && in.nioBufferCount() == 1) {
            ByteBuf result = ctx.alloc().directBuffer(claimedUncompressedSize);
            try {
                ByteBuffer nioIn  = in.nioBuffer(in.readerIndex(), compressedSize);
                ByteBuffer nioOut = result.nioBuffer(0, claimedUncompressedSize);

                int actualDecompressed;
                try {
                    actualDecompressed = zstd.decompressDirectByteBuffer(
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

        // --- Heap input (e.g. the client's vanilla cipher output): decompress straight
        // from its backing array, or one scratch copy, into the direct output buffer ---
        byte[] src;
        int srcOffset;
        if (in.hasArray()) {
            src = in.array();
            srcOffset = in.arrayOffset() + in.readerIndex();
        } else {
            if (scratchIn == null || scratchIn.length < compressedSize) {
                scratchIn = new byte[Math.max(8192, compressedSize)];
            }
            in.getBytes(in.readerIndex(), scratchIn, 0, compressedSize);
            src = scratchIn;
            srcOffset = 0;
        }

        ByteBuf result = ctx.alloc().directBuffer(claimedUncompressedSize);
        try {
            ByteBuffer nioOut = result.nioBuffer(0, claimedUncompressedSize);
            int actualDecompressed;
            try {
                actualDecompressed = zstd.decompressByteArrayToDirectByteBuffer(
                        nioOut, 0, claimedUncompressedSize,
                        src, srcOffset, compressedSize);
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
    }

    /**
     * Releases the native Zstd decompression context when the handler is removed from the pipeline.
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        decompressor.close();
        if (dictDecompressor != null) {
            dictDecompressor.close();
        }
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

