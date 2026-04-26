package com.xinian.KryptonHybrid.shared.network.compression;

import com.github.luben.zstd.ZstdCompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.BroadcastCompressedCache;
import com.xinian.KryptonHybrid.shared.network.BroadcastSerializationCache;
import com.xinian.KryptonHybrid.shared.network.BundleEncodeContext;
import com.xinian.KryptonHybrid.shared.network.NetworkTrafficStats;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;

import java.nio.ByteBuffer;

/**
 * Low-latency Netty {@link MessageToByteEncoder} that compresses outgoing Minecraft
 * packets using Zstandard (Zstd) via the zstd-jni native library.
 *
 * <h3>Wire format</h3>
 * <p>Intentionally mirrors vanilla {@link net.minecraft.network.CompressionEncoder}:</p>
 * <pre>
 *   VarInt(0)                             – packet NOT compressed (size &lt; threshold)
 *   raw bytes
 *
 *   VarInt(original_uncompressed_size)    – packet IS compressed
 *   Zstd-compressed bytes
 * </pre>
 *
 * <h3>Latency optimizations over the naïve implementation</h3>
 *
 * <h4>1. Zero-copy DirectByteBuffer fast path</h4>
 * <p>When both the input {@code msg} and the output {@code out} are backed by direct
 * memory (the common case in Netty's pooled allocator), the encoder calls
 * {@link ZstdCompressCtx#compressDirectByteBuffer} which passes native pointers
 * directly to libzstd.  This eliminates:</p>
 * <ul>
 *   <li>Two {@code new byte[]} heap allocations per packet (input copy + output buffer)</li>
 *   <li>Two full-payload {@code memcpy} operations (ByteBuf → byte[] → ByteBuf)</li>
 *   <li>GC pressure from short-lived byte arrays at 20+ Hz × N players</li>
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
 *   <tr><td>Heap alloc</td><td>2 × new byte[N]</td><td>0</td></tr>
 *   <tr><td>Data copies</td><td>2 × memcpy(N)</td><td>0</td></tr>
 *   <tr><td>Frame header</td><td>+8 B content-size</td><td>omitted</td></tr>
 *   <tr><td>GC pressure</td><td>~2N bytes/packet</td><td>0</td></tr>
 * </table>
 *
 * @see ZstdCompressDecoder
 * @see ZstdUtil#createCompressor()
 */
public class ZstdCompressEncoder extends MessageToByteEncoder<ByteBuf> {

    private int threshold;
    private final ZstdCompressCtx compressor;

    /** Grow-only scratch buffer for the input (heap-fallback path only). */
    private byte[] scratchIn = new byte[8192];
    /** Grow-only scratch buffer for the output (heap-fallback path only). */
    private byte[] scratchOut = new byte[8192];

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

        // ── Pull the (optional) Packet reference handed off by PacketEncoderMixin ──
        Object currentPacket = BroadcastSerializationCache.pollCurrentPacket();
        Packet<?> packet = (currentPacket instanceof Packet<?> p) ? p : null;
        ZstdSampleRecorder.maybeRecord(packet, msg, uncompressedSize);

        // ── P0 Compressed-bytes broadcast cache: short-circuit if the same Packet
        //    instance was already compressed earlier on this Netty I/O thread. ──
        if (packet != null && BroadcastCompressedCache.isCacheable(packet)) {
            byte[] cachedWire = BroadcastCompressedCache.get(packet);
            if (cachedWire != null) {
                out.writeBytes(cachedWire);
                msg.skipBytes(uncompressedSize);
                NetworkTrafficStats.INSTANCE.recordEncode(uncompressedSize, cachedWire.length);
                kryptonRecordWirePacket(packet, cachedWire.length);
                return;
            }
        }

        // ── P0 Bundle forced-compression: lower the threshold for sub-packets
        //    bracketed by BundleDelimiterPacket frames. ──
        int effectiveThreshold = threshold;
        if (KryptonConfig.bundleAlwaysCompress && BundleEncodeContext.isInBundle()) {
            effectiveThreshold = Math.max(1, KryptonConfig.bundleCompressMinBytes);
        }

        // --- Below threshold: pass through uncompressed ---
        if (uncompressedSize < effectiveThreshold) {
            wrappedOut.writeVarInt(0);
            out.writeBytes(msg);
            int wire = out.writerIndex() - startWireIndex;
            NetworkTrafficStats.INSTANCE.recordEncode(uncompressedSize, wire);
            kryptonRecordWirePacket(packet, wire);
            kryptonMaybeCacheCompressed(packet, out, startWireIndex, wire);
            return;
        }

        // --- Packet-aware compression level selection ---
        compressor.setLevel(kryptonSelectCompressionLevel(packet, uncompressedSize));

        int maxOut = ZstdUtil.maxCompressedLength(uncompressedSize);

        // Reserve space for the uncompressed-size VarInt header.
        int headerStart = out.writerIndex();
        wrappedOut.writeVarInt(uncompressedSize);
        int headerLen = out.writerIndex() - headerStart;

        // --- Fast path: both buffers are direct → zero-copy native compression ---
        if (msg.isDirect() && msg.nioBufferCount() == 1
                && out.isDirect() && out.nioBufferCount() == 1) {
            // Ensure the output buffer has enough writable capacity
            out.ensureWritable(maxOut);

            // Obtain NIO direct byte buffers from Netty
            ByteBuffer nioIn  = msg.nioBuffer(msg.readerIndex(), uncompressedSize);
            ByteBuffer nioOut = out.nioBuffer(out.writerIndex(), maxOut);

            int compressedSize = compressor.compressDirectByteBuffer(
                    nioOut, 0, maxOut,
                    nioIn,  0, uncompressedSize);

            // Safety: rewind and write raw if compression inflated. The decoder accepts
            // VarInt(0) pass-through packets even when they exceed the negotiated threshold.
            if ((headerLen + compressedSize) >= (1 + uncompressedSize)) {
                out.writerIndex(startWireIndex);
                wrappedOut.writeVarInt(0);
                out.writeBytes(msg, msg.readerIndex(), uncompressedSize);
                msg.skipBytes(uncompressedSize);
                int wire = out.writerIndex() - startWireIndex;
                NetworkTrafficStats.INSTANCE.recordEncode(uncompressedSize, wire);
                kryptonRecordWirePacket(packet, wire);
                kryptonMaybeCacheCompressed(packet, out, startWireIndex, wire);
                return;
            }

            // Advance Netty indices
            msg.skipBytes(uncompressedSize);
            out.writerIndex(out.writerIndex() + compressedSize);

            int wire = out.writerIndex() - startWireIndex;
            NetworkTrafficStats.INSTANCE.recordEncode(uncompressedSize, wire);
            kryptonRecordWirePacket(packet, wire);
            kryptonMaybeCacheCompressed(packet, out, startWireIndex, wire);
            return;
        }

        // --- Heap fallback: reusable scratch buffers ---
        if (scratchIn.length < uncompressedSize) {
            scratchIn = new byte[uncompressedSize];
        }
        msg.readBytes(scratchIn, 0, uncompressedSize);

        if (scratchOut.length < maxOut) {
            scratchOut = new byte[maxOut];
        }

        int compressedSize = compressor.compressByteArray(
                scratchOut, 0, maxOut,
                scratchIn, 0, uncompressedSize);

        if ((headerLen + compressedSize) >= (1 + uncompressedSize)) {
            out.writerIndex(startWireIndex);
            wrappedOut.writeVarInt(0);
            out.writeBytes(scratchIn, 0, uncompressedSize);
        } else {
            out.writeBytes(scratchOut, 0, compressedSize);
        }

        int wire = out.writerIndex() - startWireIndex;
        NetworkTrafficStats.INSTANCE.recordEncode(uncompressedSize, wire);
        kryptonRecordWirePacket(packet, wire);
        kryptonMaybeCacheCompressed(packet, out, startWireIndex, wire);
    }

    private static int kryptonSelectCompressionLevel(Packet<?> packet, int uncompressedSize) {
        int baseLevel = clampLevel(KryptonConfig.zstdLevel);
        int adaptiveLevel = KryptonConfig.zstdAdaptiveLargeLevel;
        int adaptiveThreshold = Math.max(0, KryptonConfig.zstdAdaptiveLargeThreshold);

        if (packet != null) {
            String packetName = packet.getClass().getSimpleName();

            if (kryptonIsCustomPayload(packetName)) {
                // Mod payloads are often already compact or self-compressed. Keep latency
                // predictable and rely on the post-compression raw fallback for bandwidth.
                return Math.min(baseLevel, 3);
            }

            if (kryptonIsChunkOrLightPacket(packetName)) {
                if (adaptiveLevel > 0 && uncompressedSize >= adaptiveThreshold) {
                    return clampLevel(adaptiveLevel);
                }
                return baseLevel;
            }

            if (kryptonIsBulkSyncPacket(packetName)
                    && adaptiveLevel > 0
                    && uncompressedSize >= Math.max(1024, adaptiveThreshold / 2)) {
                return clampLevel(adaptiveLevel);
            }
        }

        if (adaptiveLevel > 0 && uncompressedSize >= adaptiveThreshold) {
            return clampLevel(adaptiveLevel);
        }
        return baseLevel;
    }

    private static boolean kryptonIsChunkOrLightPacket(String packetName) {
        return packetName.contains("LevelChunk")
                || packetName.contains("LightUpdate");
    }

    private static boolean kryptonIsBulkSyncPacket(String packetName) {
        return packetName.contains("UpdateRecipes")
                || packetName.contains("Recipe")
                || packetName.contains("RegistryData")
                || packetName.contains("UpdateTags")
                || packetName.contains("Login")
                || packetName.contains("Configuration");
    }

    private static boolean kryptonIsCustomPayload(String packetName) {
        return packetName.endsWith("CustomPayloadPacket");
    }

    private static int clampLevel(int level) {
        return Math.max(1, Math.min(22, level));
    }

    private static void kryptonRecordWirePacket(Packet<?> packet, int wireBytes) {
        if (packet == null || wireBytes <= 0) return;
        NetworkTrafficStats.INSTANCE.recordPacketWire(
                kryptonResolveKey(packet),
                kryptonResolveModId(packet),
                wireBytes);
    }

    private static String kryptonResolveKey(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket cp) {
            ResourceLocation id = cp.payload().type().id();
            return "custom:" + id.getNamespace() + "/" + id.getPath();
        }
        if (packet instanceof ServerboundCustomPayloadPacket sp) {
            ResourceLocation id = sp.payload().type().id();
            return "custom:" + id.getNamespace() + "/" + id.getPath();
        }
        return packet.getClass().getSimpleName();
    }

    private static String kryptonResolveModId(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket cp) {
            return cp.payload().type().id().getNamespace();
        }
        if (packet instanceof ServerboundCustomPayloadPacket sp) {
            return sp.payload().type().id().getNamespace();
        }
        String pkg = packet.getClass().getPackageName();
        if (pkg.startsWith("net.minecraft.")) return "minecraft";
        String[] parts = pkg.split("\\.", 4);
        return parts.length >= 3 ? parts[2] : "unknown";
    }

    /**
     * Stores the just-emitted compressed wire bytes into
     * {@link BroadcastCompressedCache} when the packet is one of the cacheable
     * broadcast types and the wire payload is small enough to be worth caching
     * (i.e. won't exceed our per-thread memory budget).
     */
    private static void kryptonMaybeCacheCompressed(Packet<?> packet, ByteBuf out, int startWireIndex, int wireLen) {
        if (packet == null) return;
        if (!BroadcastCompressedCache.isCacheable(packet)) return;
        if (wireLen <= 0 || wireLen > 65536) return;
        byte[] copy = new byte[wireLen];
        out.getBytes(startWireIndex, copy);
        BroadcastCompressedCache.put(packet, copy);
    }

    /**
     * Always allocates a <strong>direct</strong> output buffer to maximize the chance
     * of hitting the zero-copy compression fast path.
     *
     * <p>Size is {@code 5 (max VarInt) + maxCompressedLength(input)} which guarantees
     * the compressed output always fits without reallocation.</p>
     */
    @Override
    protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, ByteBuf msg, boolean preferDirect)
            throws Exception {
        int initialSize = 5 + ZstdUtil.maxCompressedLength(msg.readableBytes());
        return ctx.alloc().directBuffer(initialSize);
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
