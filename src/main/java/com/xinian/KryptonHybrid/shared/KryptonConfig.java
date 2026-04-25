package com.xinian.KryptonHybrid.shared;

import com.xinian.KryptonHybrid.shared.network.compression.CompressionAlgorithm;

/**
 * Loader-agnostic configuration value holder for KryptonFNP.
 *
 * <p>This class contains only plain {@code volatile} fields that any loader-specific
 * config implementation (Forge {@code ForgeConfigSpec}, Fabric Cloth Config, 鈥? can
 * write to after the config file has been read. All runtime code in the {@code common}
 * module reads values from here, keeping it free of loader-specific APIs.</p>
 *
 * <h3>Compression algorithm notes</h3>
 * <ul>
 *   <li>{@link #compressionAlgorithm} 鈥?selects the packet compression algorithm.
 *       <strong>Both</strong> the server and the connected client must use the same
 *       algorithm; mixing algorithms across a connection will corrupt the session.</li>
 *   <li>{@link #zstdLevel} 鈥?Zstd compression level (1 = fastest / largest output,
 *       22 = slowest / smallest output). Backed by zstd-jni (native) which fully
 *       supports the Zstandard level range. Only applies when
 *       {@link #compressionAlgorithm} is {@link com.xinian.KryptonHybrid.shared.network.compression.CompressionAlgorithm#ZSTD}.</li>
 * </ul>
 *
 * <p><strong>Note on LZ4 level:</strong> The aircompressor {@code Lz4Compressor} used
 * for LZ4 does not expose a configurable acceleration level. LZ4 always runs at its
 * library-internal default (鈮埪?). Per-level tuning for LZ4 is reserved for a future
 * native implementation.</p>
 */
public final class KryptonConfig {

    /**
     * The compression algorithm selected by the user.
     * Defaults to {@link CompressionAlgorithm#ZSTD}.
     */
    public static volatile CompressionAlgorithm compressionAlgorithm = CompressionAlgorithm.ZSTD;

    /**
     * Zstd compression level in the range [1, 22].
     * Backed by zstd-jni (native Zstandard). Lower values compress faster with less
     * size reduction; higher values produce smaller output at the cost of more CPU.
     * Default: 3 (matches the zstd reference implementation default).
     * Only used when {@link #compressionAlgorithm} is {@link CompressionAlgorithm#ZSTD}.
     */
    public static volatile int zstdLevel = 3;

    /**
     * Adaptive Zstd compression: use a higher level for large packets (e.g. chunk data)
     * to get better ratios where CPU budget allows.
     * Set to 0 to disable adaptive compression (always use {@link #zstdLevel}).
     * When positive, packets at or above {@link #zstdAdaptiveLargeThreshold} bytes will
     * be compressed at this level instead of {@link #zstdLevel}.
     * Default: 0 (disabled).
     */
    public static volatile int zstdAdaptiveLargeLevel = 0;

    /**
     * Byte-size threshold above which the adaptive large-packet compression level
     * ({@link #zstdAdaptiveLargeLevel}) is used instead of {@link #zstdLevel}.
     * Only effective when {@link #zstdAdaptiveLargeLevel} &gt; 0.
     * Default: 8192 (8 KiB — typical chunk packet size).
     */
    public static volatile int zstdAdaptiveLargeThreshold = 8192;


    public static volatile int zstdWorkers = 0;


    public static volatile int zstdOverlapLog = 0;


    public static volatile int zstdJobSize = 0;


    public static volatile boolean zstdEnableLDM = false;


    public static volatile int zstdLongDistanceWindowLog = 27;


    public static volatile int zstdStrategy = 0;

    /**
     * Enables pre-trained Zstd dictionary compression for all connections.
     *
     * <p>When enabled, both compressor and decompressor contexts load the same dictionary
     * bytes before processing packets. This improves ratio for repetitive packet families
     * (chunk/light/entity deltas and frequent custom payload templates), especially at
     * low compression levels.</p>
     */
    public static volatile boolean zstdDictEnabled = false;

    /**
     * Path to the pre-trained Zstd dictionary file (.zdict).
     * Relative paths are resolved from the game working directory.
     */
    public static volatile String zstdDictPath = "config/krypton_hybrid.zdict";

    /**
     * If true, dictionary load failure is fatal for Zstd context creation.
     * If false, Krypton logs a warning and falls back to plain Zstd.
     */
    public static volatile boolean zstdDictRequired = false;

    // --- Network security / independent packet control ---

    /** Global security kill-switch. */
    public static volatile boolean securityEnabled = true;

    /** Sustained connection attempts per second per remote IP. */
    public static volatile int securityConnectionRatePerSecond = 8;

    /** Burst capacity for new connections per remote IP. */
    public static volatile int securityConnectionBurstLimit = 20;

    /** Seconds an address remains quarantined after repeated connection abuse. */
    public static volatile int securityConnectionQuarantineSeconds = 30;

    /** Window used to detect suspicious rapid reconnects. */
    public static volatile int securityRapidReconnectWindowMs = 1500;

    /** Extra tokens consumed when a connection rapidly reconnects. */
    public static volatile int securityRapidReconnectPenalty = 4;


    /** Absolute maximum allowed decompressed payload size. */
    public static volatile int securityMaxDecompressedBytes = 16 * 1024 * 1024;

    /** Maximum allowed compression ratio before decompression is refused. */
    public static volatile int securityMaxCompressionRatio = 100;

    /** Minimum compressed byte count before ratio checks become meaningful. */
    public static volatile int securityMinCompressedBytesForRatioCheck = 8;


    public static volatile int securityHandshakeTimeoutSec = 5;
    public static volatile int securityLoginTimeoutSec = 10;
    public static volatile int securityPlayTimeoutSec = 30;

    /** Maximum decoded frame size accepted after packet splitting. */
    public static volatile int securityMaxPacketBytes = 2 * 1024 * 1024;

    /** Maximum custom-payload size accepted by policy. */
    public static volatile int securityMaxCustomPayloadBytes = 128 * 1024;

    /** Enables FriendlyByteBuf read-side guardrails (disabled by default for compatibility). */
    public static volatile boolean securityReadLimitsEnabled = false;

    /** Max UTF-8 string characters accepted when decoding packet payload fields. */
    public static volatile int securityMaxStringChars = 32_767;

    /** Max element count for decoded collections. */
    public static volatile int securityMaxCollectionElements = 16_384;

    /** Max entry count for decoded maps. */
    public static volatile int securityMaxMapEntries = 8_192;

    /** Max loop count for readWithCount-style payload sections. */
    public static volatile int securityMaxCountedElements = 16_384;

    /** Max decoded byte[] length allowed through FriendlyByteBuf readByteArray(int). */
    public static volatile int securityMaxByteArrayBytes = 2 * 1024 * 1024;

    public static volatile int securityMinProtocolVersion = 3;
    public static volatile int securityMaxProtocolVersion = 1100;
    public static volatile int securityMaxHandshakeAddressLength = 255;

    /** Weighted anomaly strike threshold before force-disconnect. */
    public static volatile int securityAnomalyStrikeThreshold = 10;

    public static volatile int securityWriteWatermarkLow = 524_288;
    public static volatile int securityWriteWatermarkHigh = 2_097_152;
    public static volatile int securityMaxPendingWrites = 4096;
    public static volatile int securityMaxUnwritableSeconds = 15;

    // Chunk data optimization (biome delta encoding + heightmap compression)

    /**
     * Whether to apply Krypton's chunk-data optimizations to
     * {@code ClientboundLevelChunkPacketData}: biome delta encoding and heightmap
     * binary compression with XOR-delta.  When enabled, heightmap data is written in
     * a compact binary format (replacing NBT), and per-section biome data is extracted
     * from the section buffer and encoded with single-value detection, improving both
     * pre-compression size and compressibility.
     * Requires Krypton Hybrid on <strong>both</strong> server and client.
     * Default: {@code true}.
     */
    public static volatile boolean chunkOptEnabled = true;

    // Delayed Chunk Cache (DCC)

    // Light data optimization

    /**
     * Whether to apply Krypton's uniform-RLE encoding for {@code ClientboundLightUpdatePacketData}.
     * When enabled, sky-light DataLayers that are entirely one value (e.g. all-15 above terrain)
     * are encoded as 2 bytes instead of 2048, reducing chunk-load traffic by up to 40 KB per chunk
     * in open-sky environments. Requires Krypton Hybrid on <strong>both</strong> server and client.
     * Default: {@code true}.
     */
    public static volatile boolean lightOptEnabled = true;

    /** Whether the Delayed Chunk Cache is active. Default: {@code true}. */
    public static volatile boolean dccEnabled = true;

    /** Max cached chunks per player; excess departures are untracked immediately. Default: 60. */
    public static volatile int dccSizeLimit = 60;

    /** Cache radius (chunks) from the player; farther entries are evicted. Default: 5. */
    public static volatile int dccDistance = 5;

    /** Seconds before a cached chunk is forcibly evicted and untracked. Default: 30. */
    public static volatile int dccTimeoutSeconds = 30;

    // --- P0-⑧ Broadcast Serialization Cache ---

    /**
     * Whether to enable the broadcast serialization cache.  When enabled, the
     * {@link net.minecraft.network.PacketEncoder} caches the serialized bytes of
     * a Packet instance on each Netty I/O thread.  Subsequent encodes of the same
     * Packet object (common in broadcast scenarios where one packet is sent to N
     * players) reuse the cached bytes, skipping all serialization work.
     * Default: {@code true}.
     */
    public static volatile boolean broadcastCacheEnabled = true;

    // --- P0 Bundle Forced Compression ---

    /**
     * If {@code true}, sub-packets emitted between {@code BundleDelimiterPacket}
     * frames are forcibly compressed even when their individual size is below
     * {@link #zstdLevel}-paired compression threshold.  Combined with a loaded
     * Zstd dictionary, this typically reduces total bundle wire size by 10–25%
     * because small sub-packets gain access to the shared dictionary entropy.
     * The compressor falls back to raw output if compressed size would exceed
     * uncompressed size, so enabling this never causes wire growth.
     * Default: {@code true}.
     */
    public static volatile boolean bundleAlwaysCompress = true;

    /**
     * Minimum uncompressed size (bytes) of a bundle sub-packet before forced
     * compression engages.  Packets smaller than this are still skipped to avoid
     * the marginal CPU cost of tiny-payload Zstd calls.  Default: {@code 24}.
     */
    public static volatile int bundleCompressMinBytes = 24;

    // --- P0 Compressed-bytes Broadcast Cache ---

    /**
     * Per-Netty-thread cache of the <em>post-Zstd</em> compressed bytes for
     * broadcast packets (currently chunk and light update packets).  When the
     * same Packet instance is sent to N players sharing one I/O thread, the
     * compressor only runs once instead of N times.  Independent of and in
     * addition to {@link #broadcastCacheEnabled} (which caches the raw serialized
     * bytes, before compression).
     * Default: {@code true}.
     */
    public static volatile boolean broadcastCompressedCacheEnabled = true;

    // --- P1 Cross-tick micro-batch flush ---

    /**
     * If {@code true}, the entity-tick auto-flush re-enable is replaced by a
     * deferred channel flush scheduled on the connection's Netty event loop.
     * This coalesces flushes between the entity-tick and the immediately
     * following block-update broadcast tick into a single syscall, at the cost
     * of {@link #microBatchFlushDelayMs} ms of additional outgoing latency.
     * Default: {@code false} (opt-in for high-population servers).
     */
    public static volatile boolean microBatchFlushEnabled = false;

    /**
     * Deferred-flush delay in milliseconds used when {@link #microBatchFlushEnabled}
     * is true.  Range 1–20 ms; recommended 5 ms.  Default: {@code 5}.
     */
    public static volatile int microBatchFlushDelayMs = 5;

    // --- P1 Motion / Teleport delta filter ---

    /**
     * If {@code true}, redundant {@code ClientboundSetEntityMotionPacket} and
     * {@code ClientboundTeleportEntityPacket} updates whose components have not
     * changed by more than {@link #motionDeltaThreshold} encoded units (motion)
     * or {@link #teleportDeltaSquared} squared blocks (teleport) since the last
     * sent value for that entity → that player are dropped.  Significantly cuts
     * projectile/dropped-item packet count.
     * Default: {@code true}.
     */
    public static volatile boolean motionDeltaEnabled = true;

    /**
     * Encoded-unit per-axis tolerance for motion delta filtering.  The vanilla
     * encoding is {@code (int)(velocity * 8000)} so 80 units ≈ 0.01 blocks/tick.
     * Default: {@code 40} (≈ 0.005 b/t — visually imperceptible).
     */
    public static volatile int motionDeltaThreshold = 40;

    /**
     * Squared-distance tolerance (blocks²) for teleport delta filtering.  Default:
     * {@code 1.0E-4} (0.01-block radius).
     */
    public static volatile double teleportDeltaSquared = 1.0E-4;

    // --- P1-③ Packet Coalescing ---

    /**
     * Whether to deduplicate redundant packets within the entity-tracking bundle
     * before sending.  When enabled, the coalescer removes superseded packets:
     * <ul>
     *   <li>Multiple velocity updates for the same entity → keep last only</li>
     *   <li>Multiple teleports for the same entity → keep last only</li>
     *   <li>Teleport supersedes relative move packets for the same entity</li>
     *   <li>Multiple entity data updates for the same entity → keep last only</li>
     * </ul>
     * Default: {@code true}.
     */
    public static volatile boolean packetCoalescingEnabled = true;

    // --- P1-① Block Entity NBT Delta Sync ---

    /**
     * Whether to enable per-player NBT delta encoding for block entity data packets.
     * When enabled, only the changed NBT keys are sent instead of the full tag.
     * Requires Krypton Hybrid on <strong>both</strong> server and client.
     * Default: {@code true}.
     */
    public static volatile boolean blockEntityDeltaEnabled = true;

    // --- Proxy compatibility (kept for config compatibility; no proxy mixin active) ---

    public static volatile ProxyMode proxyMode = ProxyMode.NONE;
    public static volatile String velocityForwardingSecret = "";

    private KryptonConfig() {}
}

