package com.xinian.KryptonHybrid.shared.network.compression;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDictCompress;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import net.minecraft.network.FriendlyByteBuf;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.broadcast.BroadcastCompressedCache;
import com.xinian.KryptonHybrid.shared.network.broadcast.BroadcastSerializationCache;
import com.xinian.KryptonHybrid.shared.network.stats.NetworkTrafficStats;
import com.xinian.KryptonHybrid.shared.network.util.ReservedFrameHeader;
import com.xinian.KryptonHybrid.shared.network.util.VarIntUtil;
import net.minecraft.network.protocol.Packet;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;

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
 * <h3>Batch frames</h3>
 * <p>On a server connection whose client agreed to {@link BatchFrames}, and while
 * {@code batch_frames.enabled} is on, packets written without a flush are held and the
 * next flush sends them as one compressed frame. A packet that is flushed on its own,
 * a large one, and a chunk or light packet the compressed broadcast cache can serve
 * still go out as single frames, after the packets held before them.</p>
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

    /** Whether this encoder may send {@link BatchFrames}: server side, and the client agreed. */
    private final boolean batchFrames;
    /** Packets held for the next batch frame, or {@code null} (event loop only). */
    private Batch pending;

    /**
     * Creates a new encoder.
     *
     * @param threshold  packets whose uncompressed size is below this value will be sent
     *                   raw (VarInt 0 prefix); packets at or above it will be Zstd-compressed
     * @param compressor a per-channel {@link ZstdCompressCtx} instance (see
     *                   {@link ZstdUtil#createCompressor()})
     * @param dictionary the dictionary to compress with ({@link ZstdUtil#compressionDictionary()}),
     *                   or {@code null}; packets above {@code dict_max_packet_bytes} never use it
     * @param batchFrames whether {@link BatchFrames} may be sent on this connection; they
     *                   are only sent while {@code batch_frames.enabled} is on
     */
    public ZstdCompressEncoder(int threshold, ZstdCompressCtx compressor, byte[] dictionary,
                               boolean batchFrames) {
        this.threshold = threshold;
        this.compressor = compressor;
        this.dictionary = dictionary;
        this.dictCompressor = dictionary == null ? null : ZstdUtil.createDictionaryCompressor();
        this.dictionaryByLevel = dictionary == null ? null : new ZstdDictCompress[23];
        this.batchFrames = batchFrames;
    }

    /**
     * Holds the packet for the next batch frame while batch frames are on; otherwise, or
     * when the packet must leave alone, sends whatever is held first and then the packet
     * as a single frame.
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!batchFrames || !KryptonConfig.batchFramesEnabled || !(msg instanceof ByteBuf buf)
                || !ctx.channel().isOpen()) {
            kryptonEmitPending(ctx);
            super.write(ctx, msg, promise);
            return;
        }

        Object current = BroadcastSerializationCache.pollCurrentPacket();
        Packet<?> packet = (current instanceof Packet<?> p) ? p : null;
        int size = buf.readableBytes();
        int maxBatchBytes = Math.min(KryptonConfig.batchFramesMaxBatchBytes, BatchFrames.MAX_BATCH_BYTES);
        int entryBytes = VarIntUtil.getVarIntLength(size) + size;

        if (size == 0 || size > KryptonConfig.batchFramesMaxPacketBytes || entryBytes > maxBatchBytes
                || (packet != null && size >= threshold && BroadcastCompressedCache.isCacheable(packet))) {
            // Its own frame, after everything held so far: keeps the order, and chunk and
            // light packets keep using the compressed broadcast cache.
            kryptonEmitPending(ctx);
            BroadcastSerializationCache.setCurrentPacket(packet);
            super.write(ctx, msg, promise);
            return;
        }

        if (pending != null && (pending.count == BatchFrames.MAX_PACKETS
                || pending.content.readableBytes() + entryBytes > maxBatchBytes)) {
            kryptonEmitPending(ctx);
        }
        if (pending == null) {
            pending = new Batch(ctx.alloc().directBuffer(Math.min(maxBatchBytes, 8192)));
        }
        try {
            pending.add(buf, packet, promise);
        } finally {
            buf.release();
        }
    }

    /** Sends the held packets, then flushes. */
    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        kryptonEmitPending(ctx);
        ctx.flush();
    }

    /**
     * Held packets are dropped when the channel closes, like the writes Netty has not
     * flushed yet: once it is closed, but before the pipeline is torn down, so their
     * failures still reach vanilla's {@code Connection}, which ignores them on a closed
     * channel. A close by the peer or an I/O error never passes through the pipeline's
     * {@code close()}, hence the close future.
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        if (batchFrames) {
            ctx.channel().closeFuture().addListener(future -> kryptonDiscardPending(new ClosedChannelException()));
        }
    }

    /**
     * Sends whatever is held: as one batch frame when that is smaller, otherwise (and for
     * a single packet) as single frames, byte for byte as without batching.
     */
    private void kryptonEmitPending(ChannelHandlerContext ctx) {
        Batch batch = pending;
        if (batch == null) {
            return;
        }
        pending = null;
        try {
            boolean sent;
            try {
                sent = batch.count > 1 && kryptonWriteBatch(ctx, batch);
            } catch (Throwable t) {
                batch.fail(t);
                return;
            }
            if (!sent) {
                for (int i = 0; i < batch.count; i++) {
                    try {
                        BroadcastSerializationCache.setCurrentPacket(batch.packets[i]);
                        super.write(ctx, batch.content.retainedSlice(batch.offsets[i], batch.sizes[i]),
                                batch.promises[i]);
                    } catch (Throwable t) {
                        batch.promises[i].tryFailure(t);
                    }
                }
            }
        } finally {
            batch.content.release();
        }
    }

    /**
     * Compresses {@code batch} into one {@link BatchFrames batch frame} and writes it.
     * Returns {@code false}, writing nothing, when the frame would not be smaller than its
     * packets sent raw as single frames (each also needs its own frame length).
     */
    private boolean kryptonWriteBatch(ChannelHandlerContext ctx, Batch batch) {
        ByteBuf content = batch.content;
        int size = content.readableBytes();
        if (!content.isDirect() || content.nioBufferCount() != 1) {
            return false;
        }

        // Batches hold small packets: the base level, and the dictionary when the size allows it.
        ZstdCompressCtx zstd = kryptonUseLevel(size <= KryptonConfig.zstdDictMaxPacketBytes,
                clampLevel(KryptonConfig.zstdLevel));
        int maxOut = ZstdUtil.maxCompressedLength(size);
        ByteBuf out = ReservedFrameHeader.reserve(ctx.alloc().directBuffer(ReservedFrameHeader.SIZE + 5 + maxOut));
        try {
            int startWireIndex = out.writerIndex();
            VarIntUtil.writeVarInt(out, -size);
            out.ensureWritable(maxOut);
            int compressedSize = zstd.compressDirectByteBuffer(
                    out.nioBuffer(out.writerIndex(), maxOut), 0, maxOut,
                    content.nioBuffer(content.readerIndex(), size), 0, size);
            int wire = out.writerIndex() - startWireIndex + compressedSize;
            if (wire + VarIntUtil.getVarIntLength(wire) >= size + batch.count) {
                return false;
            }
            out.writerIndex(out.writerIndex() + compressedSize);

            // Training samples: the packets a single frame would have compressed (single
            // frames record their own in encode()).
            if (KryptonConfig.zstdDictTrainingCaptureEnabled) {
                for (int i = 0; i < batch.count; i++) {
                    if (batch.sizes[i] >= threshold) {
                        ZstdSampleRecorder.maybeRecord(batch.packets[i],
                                content.slice(batch.offsets[i], batch.sizes[i]), batch.sizes[i]);
                    }
                }
            }
            kryptonRecordBatch(batch, wire);
            ChannelPromise written = ctx.newPromise();
            written.addListener(batch);
            ByteBuf frame = out;
            out = null;
            ctx.write(frame, written);
            return true;
        } finally {
            if (out != null) {
                out.release();
            }
        }
    }

    /** Splits the frame's wire bytes over its packets by their size, for the per-packet stats. */
    private static void kryptonRecordBatch(Batch batch, int wire) {
        long total = Math.max(1L, batch.packetBytes);
        long cumulativeSize = 0L;
        long assigned = 0L;
        for (int i = 0; i < batch.count; i++) {
            cumulativeSize += batch.sizes[i];
            long upTo = wire * cumulativeSize / total;
            kryptonRecord(batch.packets[i], batch.sizes[i], (int) (upTo - assigned));
            assigned = upTo;
        }
        NetworkTrafficStats.INSTANCE.recordBatchFrame(batch.count);
    }

    private void kryptonDiscardPending(Throwable cause) {
        Batch batch = pending;
        if (batch == null) {
            return;
        }
        pending = null;
        batch.fail(cause);
        batch.content.release();
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
        // Held packets were written while compression was on: send them as such.
        if (ctx.channel().isActive()) {
            kryptonEmitPending(ctx);
        } else {
            kryptonDiscardPending(new ClosedChannelException());
        }
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

    /**
     * Packets held for one batch frame: their bytes, each prefixed with its length as in
     * the frame content, and their promises, completed once the frame is written.
     */
    private static final class Batch implements ChannelFutureListener {
        final ByteBuf content;
        int count;
        long packetBytes;
        int[] offsets = new int[32];
        int[] sizes = new int[32];
        Packet<?>[] packets = new Packet<?>[32];
        ChannelPromise[] promises = new ChannelPromise[32];

        Batch(ByteBuf content) {
            this.content = content;
        }

        void add(ByteBuf packetBytes, Packet<?> packet, ChannelPromise promise) {
            if (count == offsets.length) {
                int capacity = count * 2;
                offsets = Arrays.copyOf(offsets, capacity);
                sizes = Arrays.copyOf(sizes, capacity);
                packets = Arrays.copyOf(packets, capacity);
                promises = Arrays.copyOf(promises, capacity);
            }
            int size = packetBytes.readableBytes();
            VarIntUtil.writeVarInt(content, size);
            offsets[count] = content.writerIndex();
            sizes[count] = size;
            packets[count] = packet;
            promises[count] = promise;
            content.writeBytes(packetBytes, packetBytes.readerIndex(), size);
            count++;
            this.packetBytes += size;
        }

        void fail(Throwable cause) {
            for (int i = 0; i < count; i++) {
                promises[i].tryFailure(cause);
            }
        }

        @Override
        public void operationComplete(ChannelFuture future) {
            Throwable cause = future.cause();
            for (int i = 0; i < count; i++) {
                if (cause == null) {
                    promises[i].trySuccess();
                } else {
                    promises[i].tryFailure(cause);
                }
            }
        }
    }
}
