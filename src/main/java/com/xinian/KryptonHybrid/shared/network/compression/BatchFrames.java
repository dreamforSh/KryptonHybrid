package com.xinian.KryptonHybrid.shared.network.compression;

/**
 * Wire format of batch frames: several clientbound packets sent as one compressed frame.
 *
 * <p>Only the server sends them, after the peer agreed during login
 * ({@link CompressionNegotiation#supportsBatchFrames}) and while
 * {@code batch_frames.enabled} is on: {@link ZstdCompressEncoder} holds the packets
 * written between two flushes and compresses them together. The client's
 * {@link ZstdCompressDecoder} splits the frame back into single packets, so everything
 * after the decompressor (vanilla's packet decoder, mods hooking it) still sees one
 * packet at a time, in the original order.</p>
 *
 * <pre>
 *   VarInt(-n)     n = uncompressed size of the content, n &gt; 0
 *   Zstd frame     the compressed content: a standard frame, or a magicless
 *                  dictionary frame, chosen as for a single packet
 *
 *   content = { VarInt(length) packet[length] }+     length &gt; 0
 * </pre>
 *
 * <p>Vanilla's format never has a negative size (0 = uncompressed, otherwise the size of
 * one compressed packet), so the decoder can tell the two apart.</p>
 */
public final class BatchFrames {
    /** Packets per batch frame; the decoder rejects more. */
    public static final int MAX_PACKETS = 4096;

    /** Upper bound of {@code batch_frames.max_batch_bytes}. */
    public static final int MAX_BATCH_BYTES = 1024 * 1024;

    private BatchFrames() {}
}
