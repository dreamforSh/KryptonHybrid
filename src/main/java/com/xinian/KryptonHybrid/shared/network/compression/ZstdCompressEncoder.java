package com.xinian.KryptonHybrid.shared.network.compression;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDictCompress;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import net.minecraft.network.FriendlyByteBuf;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.broadcast.BroadcastCompressedCache;
import com.xinian.KryptonHybrid.shared.network.broadcast.BroadcastSerializationCache;
import com.xinian.KryptonHybrid.shared.network.stats.NetworkTrafficStats;
import com.xinian.KryptonHybrid.shared.network.util.ReservedFrameHeader;
import net.minecraft.network.protocol.Packet;

import java.nio.ByteBuffer;

/**
 * Low-latency Netty {@link MessageToByteEncoder} that compresses outgoing Minecraft
 * packets using Zstandard (Zstd) via the zstd-jni native library.
 *
 * <h3>Wire format</h3>
 * <p>Intentionally mirrors vanilla {@link net.minecraft.network.CompressionEncoder}:</p>
 * <pre>
 *   VarInt(0)                             ??packet NOT compressed (size &lt; threshold)
 *   raw bytes
 *
 *   VarInt(original_uncompressed_size)    ??packet IS compressed
 *   Zstd-compressed bytes
 * </pre>
 *
 * <h3>Latency optimizations over the na?ve implementation</h3>
 *
 * <h4>1. Zero-copy DirectByteBuffer fast path</h4>
 * <p>When both the input {@code msg} and the output {@code out} are backed by direct
 * memory (the common case in Netty's pooled allocator), the encoder calls
 * {@link ZstdCompressCtx#compressDirectByteBuffer} which passes native pointers
 * directly to libzstd.  This eliminates:</p>
 * <ul>
 *   <li>Two {@code new byte[]} heap allocations per packet (input copy + output buffer)</li>
 *   <li>Two full-payload {@code memcpy} operations (ByteBuf ??byte[] ??ByteBuf)</li>
 *   <li>GC pressure from short-lived byte arrays at 20+ Hz ? N players</li>
 * </ul>
 *
 * <h4>2. Per-handler reusable scratch buffers (heap fallback)</h4>
 * <p>When either buffer is heap-backed (rare: integrated-server local channel, some
 * test harnesses), the encoder falls back to {@link ZstdCompressCtx#compressByteArray}
 * but reuses <em>grow-only</em> byte arrays stored as instance fields instead of
 * allocating fresh arrays every call.  This bounds GC pressure to at most one
 * allocation per power-of-two size class over the handler's lifetime.</p>
 *
 * <h4>3. Content-size header suppression</h4>
 * <p>{@link ZstdUtil#createCompressor()} sets {@code setContentSize(false)}, telling
 * libzstd to omit the 8-byte uncompressed-size field from the Zstd frame header.
 * The Minecraft protocol already transmits the uncompressed size as a VarInt prefix,
 * so the frame-header field is redundant.  Savings: 8 bytes per compressed packet +
 * the internal bookkeeping to write it.</p>
 *
 * <h4>4. Direct output allocation</h4>
 * <p>{@link #allocateBuffer} always allocates a <strong>direct</strong> output buffer
 * (via {@code ctx.alloc().directBuffer()}) to maximize the chance of hitting the
 * zero-copy path and to align with the downstream Netty pipeline (encryption,
 * framing, socket write) which also operates on direct memory.</p>
 *
 * <h4>Latency impact summary</h4>
 * <table>
 *   <tr><th>Operation</th><th>Before</th><th>After (direct)</th></tr>
 *   <tr><td>Heap alloc</td><td>2 ? new byte[N]</td><td>0</td></tr>
 *   <tr><td>Data copies</td><td>2 ? memcpy(N)</td><td>0</td></tr>
 *   <tr><td>Frame header</td><td>+8 B content-size</td><td>omitted</td></tr>
 *   <tr><td>GC pressure</td><td>~2N bytes/packet</td><td>0</td></tr>
 * </table>
 *
 * @see ZstdCompressDecoder
 * @see ZstdUtil#createCompressor()
 */
public class ZstdCompressEncoder extends MessageToByteEncoder<ByteBuf> {

    /** Pending tasks above which the I/O thread is treated as busy (see level selection). */
    private static final int BUSY_IO_TASKS = 128;

    /** How a packet class picks its compression level; computed once per class. */
    private enum LevelClass { CUSTOM_PAYLOAD, CHUNK_OR_LIGHT, BULK_SYNC, OTHER }

    private static final ClassValue<LevelClass> LEVEL_CLASS = new ClassValue<>() {
        @Override
        protected LevelClass computeValue(Class<?> type) {
            String name = type.getSimpleName();
            if (name.endsWith("CustomPayloadPacket")) {
                return LevelClass.CUSTOM_PAYLOAD;
            }
            if (name.contains("LevelChunk") || name.contains("LightUpdate")) {
                return LevelClass.CHUNK_OR_LIGHT;
            }
            if (name.contains("UpdateRecipes") || name.contains("Recipe") || name.contains("RegistryData")
                    || name.contains("UpdateTags") || name.contains("Login") || name.contains("Configuration")) {
                return LevelClass.BULK_SYNC;
            }
            return LevelClass.OTHER;
        }
    };

    private int threshold;
    /** Standard frames, no dictionary: every packet without a dictionary, and large ones with. */
    private final ZstdCompressCtx compressor;
    /** Magicless frames with the dictionary, or {@code null} without one (see {@link ZstdUtil#FRAME_MAGIC}). */
    private final ZstdCompressCtx dictCompressor;
    /** Dictionary this connection compresses with, or {@code null}. */
    private final byte[] dictionary;
    /** Prepared dictionaries by level (only with a dictionary). */
    private final ZstdDictCompress[] dictionaryByLevel;
    /** Levels the contexts are set to, or -1 before their first compression. */
    private int currentLevel = -1;
    private int currentDictLevel = -1;

    /** Grow-only scratch buffer for the input (heap-fallback path only). */
    private byte[] scratchIn;
    /** Grow-only scratch buffer for the output (heap-fallback path only). */
    private byte[] scratchOut;

    /**
     * Creates a new encoder.
     *
     * @param threshold  packets whose uncompressed size is below this value will be sent
     *                   raw (VarInt 0 prefix); packets at or above it will be Zstd-compressed
     * @param compressor a per-channel {@link ZstdCompressCtx} instance (see
     *                   {@link ZstdUtil#createCompressor()})
     * @param dictionary the dictionary to compress with ({@link ZstdUtil#compressionDictionary()}),
     *                   or {@code null}; packets above {@code dict_max_packet_bytes} never use it
     */
    public ZstdCompressEncoder(int threshold, ZstdCompressCtx compressor, byte[] dictionary) {
        this.threshold = threshold;
        this.compressor = compressor;
        this.dictionary = dictionary;
        this.dictCompressor = dictionary == null ? null : ZstdUtil.createDictionaryCompressor();
        this.dictionaryByLevel = dictionary == null ? null : new ZstdDictCompress[23];
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        FriendlyByteBuf wrappedOut = new FriendlyByteBuf(out);
        int uncompressedSize = msg.readableBytes();
        int startWireIndex = out.writerIndex();

        // Pull the (optional) Packet reference handed off by PacketEncoderMixin.
        Object currentPacket = BroadcastSerializationCache.pollCurrentPacket();
        Packet<?> packet = (currentPacket instanceof Packet<?> p) ? p : null;

        // Compressed-bytes broadcast cache: short-circuit if the same Packet instance was
        // already compressed (with the same dictionary) earlier on this Netty I/O thread.
        boolean cacheable = packet != null && BroadcastCompressedCache.isCacheable(packet);
        if (cacheable) {
            byte[] cachedWire = BroadcastCompressedCache.get(packet, dictionary);
            if (cachedWire != null) {
                out.writeBytes(cachedWire);
                msg.skipBytes(uncompressedSize);
                kryptonRecord(packet, uncompressedSize, cachedWire.length);
                return;
            }
        }

        // --- Below threshold: pass through uncompressed ---
        if (uncompressedSize < threshold) {
            wrappedOut.writeVarInt(0);
            out.writeBytes(msg);
            kryptonRecord(packet, uncompressedSize, out.writerIndex() - startWireIndex);
            return;
        }

        // Training samples: only packets that are actually compressed.
        ZstdSampleRecorder.maybeRecord(packet, msg, uncompressedSize);

        // --- Dictionary for small and medium packets only; packet-aware level ---
        ZstdCompressCtx zstd = kryptonUseLevel(
                uncompressedSize <= KryptonConfig.zstdDictMaxPacketBytes,
                kryptonSelectCompressionLevel(ctx, packet, uncompressedSize));

        int maxOut = ZstdUtil.maxCompressedLength(uncompressedSize);

        // Reserve space for the uncompressed-size VarInt header.
        int headerStart = out.writerIndex();
        wrappedOut.writeVarInt(uncompressedSize);
        int headerLen = out.writerIndex() - headerStart;

        // --- Fast path: both buffers are direct, zero-copy native compression ---
        if (msg.isDirect() && msg.nioBufferCount() == 1
                && out.isDirect() && out.nioBufferCount() == 1) {
            // Ensure the output buffer has enough writable capacity
            out.ensureWritable(maxOut);

            // Obtain NIO direct byte buffers from Netty
            ByteBuffer nioIn  = msg.nioBuffer(msg.readerIndex(), uncompressedSize);
            ByteBuffer nioOut = out.nioBuffer(out.writerIndex(), maxOut);

            int compressedSize = zstd.compressDirectByteBuffer(
                    nioOut, 0, maxOut,
                    nioIn,  0, uncompressedSize);

            // Safety: rewind and write raw if compression inflated. The decoder accepts
            // VarInt(0) pass-through packets even when they exceed the negotiated threshold.
            if ((headerLen + compressedSize) >= (1 + uncompressedSize)) {
                out.writerIndex(startWireIndex);
                wrappedOut.writeVarInt(0);
                out.writeBytes(msg, msg.readerIndex(), uncompressedSize);
                msg.skipBytes(uncompressedSize);
                kryptonRecord(packet, uncompressedSize, out.writerIndex() - startWireIndex);
                return;
            }

            // Advance Netty indices
            msg.skipBytes(uncompressedSize);
            out.writerIndex(out.writerIndex() + compressedSize);

            int wire = out.writerIndex() - startWireIndex;
            kryptonRecord(packet, uncompressedSize, wire);
            if (cacheable) {
                kryptonCacheCompressed(packet, out, startWireIndex, wire);
            }
            return;
        }

        // --- Heap fallback: reusable scratch buffers ---
        if (scratchIn == null || scratchIn.length < uncompressedSize) {
            scratchIn = new byte[Math.max(8192, uncompressedSize)];
        }
        msg.readBytes(scratchIn, 0, uncompressedSize);

        if (scratchOut == null || scratchOut.length < maxOut) {
            scratchOut = new byte[Math.max(8192, maxOut)];
        }

        int compressedSize = zstd.compressByteArray(
                scratchOut, 0, maxOut,
                scratchIn, 0, uncompressedSize);

        boolean raw = (headerLen + compressedSize) >= (1 + uncompressedSize);
        if (raw) {
            out.writerIndex(startWireIndex);
            wrappedOut.writeVarInt(0);
            out.writeBytes(scratchIn, 0, uncompressedSize);
        } else {
            out.writeBytes(scratchOut, 0, compressedSize);
        }

        int wire = out.writerIndex() - startWireIndex;
        kryptonRecord(packet, uncompressedSize, wire);
        if (cacheable && !raw) {
            kryptonCacheCompressed(packet, out, startWireIndex, wire);
        }
    }

    /**
     * Picks the context for this packet (the dictionary one when there is a dictionary and
     * {@code dictionarySize} allows it) and sets it to {@code level} if it is not already
     * there. The dictionary context also loads the dictionary prepared for that level:
     * libzstd ignores level changes once a raw dictionary's tables are built.
     */
    private ZstdCompressCtx kryptonUseLevel(boolean dictionarySize, int level) {
        if (dictCompressor == null || !dictionarySize) {
            if (level != currentLevel) {
                compressor.setLevel(level);
                currentLevel = level;
            }
            return compressor;
        }
        if (level != currentDictLevel) {
            ZstdDictCompress prepared = dictionaryByLevel[level];
            if (prepared == null) {
                prepared = ZstdUtil.compressDictionary(dictionary, level);
                dictionaryByLevel[level] = prepared;
            }
            dictCompressor.loadDict(prepared);
            dictCompressor.setLevel(level);
            currentDictLevel = level;
        }
        return dictCompressor;
    }

    /**
     * Base level for ordinary packets; the adaptive (higher) level for large chunk, light
     * and bulk-sync packets, unless this I/O thread is busy: other players' packets queue
     * behind a slow compression, so a backlog (e.g. a join burst) uses the base level.
     */
    private static int kryptonSelectCompressionLevel(ChannelHandlerContext ctx, Packet<?> packet,
                                                     int uncompressedSize) {
        int baseLevel = clampLevel(KryptonConfig.zstdLevel);
        int adaptiveLevel = KryptonConfig.zstdAdaptiveLargeLevel;
        int adaptiveThreshold = Math.max(0, KryptonConfig.zstdAdaptiveLargeThreshold);

        LevelClass levelClass = packet == null ? LevelClass.OTHER : LEVEL_CLASS.get(packet.getClass());
        if (levelClass == LevelClass.CUSTOM_PAYLOAD) {
            // Mod payloads are often already compact or self-compressed. Keep latency
            // predictable and rely on the post-compression raw fallback for bandwidth.
            return Math.min(baseLevel, 3);
        }
        int largeFrom = levelClass == LevelClass.BULK_SYNC
                ? Math.max(1024, adaptiveThreshold / 2)
                : adaptiveThreshold;
        if (adaptiveLevel <= 0 || uncompressedSize < largeFrom || kryptonIoBusy(ctx)) {
            return baseLevel;
        }
        return clampLevel(adaptiveLevel);
    }

    private static boolean kryptonIoBusy(ChannelHandlerContext ctx) {
        return ctx.executor() instanceof SingleThreadEventExecutor loop
                && loop.pendingTasks() > BUSY_IO_TASKS;
    }

    private static int clampLevel(int level) {
        return Math.max(1, Math.min(22, level));
    }

    private static void kryptonRecord(Packet<?> packet, int uncompressedSize, int wireBytes) {
        NetworkTrafficStats.INSTANCE.recordEncode(uncompressedSize, wireBytes);
        if (packet != null && wireBytes > 0) {
            NetworkTrafficStats.INSTANCE.recordPacketWire(packet, wireBytes);
        }
    }

    /**
     * Stores the just-emitted compressed wire bytes into {@link BroadcastCompressedCache}
     * when they are small enough to be worth caching (i.e. won't exceed our per-thread
     * memory budget). Raw pass-through output is not cached: replaying it saves nothing.
     */
    private void kryptonCacheCompressed(Packet<?> packet, ByteBuf out, int startWireIndex, int wireLen) {
        if (wireLen <= 0 || wireLen > 65536) return;
        byte[] copy = new byte[wireLen];
        out.getBytes(startWireIndex, copy);
        BroadcastCompressedCache.put(packet, dictionary, copy);
    }

    /**
     * Always allocates a <strong>direct</strong> output buffer to maximize the chance
     * of hitting the zero-copy compression fast path, with room in front for the frame
     * length ({@link ReservedFrameHeader}).
     *
     * <p>Size is {@code 5 (max VarInt) + maxCompressedLength(input)} which guarantees
     * the compressed output always fits without reallocation.</p>
     */
    @Override
    protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, ByteBuf msg, boolean preferDirect)
            throws Exception {
        int initialSize = ReservedFrameHeader.SIZE + 5 + ZstdUtil.maxCompressedLength(msg.readableBytes());
        return ReservedFrameHeader.reserve(ctx.alloc().directBuffer(initialSize));
    }

    /**
     * Releases the native Zstd compression context when the handler is removed from the pipeline.
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        compressor.close();
        if (dictCompressor != null) {
            dictCompressor.close();
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
