package com.xinian.KryptonHybrid.shared;

import com.xinian.KryptonHybrid.shared.network.compression.CompressionAlgorithm;

/**
 * Loader-agnostic configuration value holder for Krypton Hybrid.
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

    // --- Zstd parallel / advanced scheduling ---

    /**
     * Number of worker threads for Zstd multi-threaded compression.
     *
     * <p>When set to 0 (default), compression is single-threaded and runs entirely
     * inside the Netty I/O thread — this is the safest and lowest-latency mode for
     * the small packet payloads typical of Minecraft network traffic.</p>
     *
     * <p>When set to &ge; 1, Zstd's native multi-threaded compression mode is activated:
     * the input is partitioned into jobs that are compressed in parallel by a native
     * thread pool managed inside libzstd.  Each {@link com.github.luben.zstd.ZstdCompressCtx}
     * (one per {@link net.minecraft.network.Connection}) spawns its own pool.
     * Useful for large payloads (chunk data, recipe sync) but adds thread scheduling
     * overhead for small packets.</p>
     *
     * <p>Range: 0–128.  Sensible values: 0 (off), 2–4 for moderate parallelism.
     * Note: the total native thread count is {@code workers × active_connections},
     * so keep this low on servers with many players.</p>
     */
    public static volatile int zstdWorkers = 0;

    /**
     * Overlap log for multi-threaded Zstd compression.
     *
     * <p>Controls how much data each worker thread shares with the previous one for
     * dictionary context.  Higher values improve compression ratio at the cost of
     * memory.  Only meaningful when {@link #zstdWorkers} &ge; 1.</p>
     *
     * <p>Range: 0–9 (0 = use Zstd default based on compression level).
     * The overlap size is {@code 2^(overlapLog) KB}.</p>
     */
    public static volatile int zstdOverlapLog = 0;

    /**
     * Job size (in bytes) for multi-threaded Zstd compression.
     *
     * <p>Determines the minimum input size per worker thread.  Smaller values increase
     * parallelism for small inputs but add scheduling overhead.  Only meaningful when
     * {@link #zstdWorkers} &ge; 1.</p>
     *
     * <p>Range: 0 (auto, default) or any positive value.  Zstd enforces a minimum
     * of {@code overlap_size + 512 B}.  For Minecraft packets (typically 64 B – 64 KB),
     * the default (auto) is almost always optimal.  Set explicitly only for very large
     * bulk payloads (chunk batches, recipe sync &gt; 256 KB).</p>
     */
    public static volatile int zstdJobSize = 0;

    /**
     * Enable Zstd long-distance matching (LDM).
     *
     * <p>When enabled, Zstd searches for repeated sequences across a much larger
     * window than the standard match finder.  This can significantly improve
     * compression ratio for highly repetitive data (e.g. large flat-world chunks,
     * bulk NBT payloads) at the cost of higher memory usage and slightly slower
     * compression.</p>
     *
     * <p>The window size is controlled by {@link #zstdLongDistanceWindowLog}.
     * Default: {@code false} (disabled).</p>
     */
    public static volatile boolean zstdEnableLDM = false;

    /**
     * Window log for Zstd long-distance matching.
     *
     * <p>Sets the window size exponent for long-distance matching:
     * {@code window_size = 2^windowLog} bytes.  Only meaningful when
     * {@link #zstdEnableLDM} is {@code true}.</p>
     *
     * <p>Range: 10–30 (1 KB – 1 GB).  Default: 27 (128 MB, the Zstd reference default).
     * For Minecraft network compression, values of 20–24 (1 MB – 16 MB) are
     * typically sufficient and avoid excessive memory allocation.</p>
     */
    public static volatile int zstdLongDistanceWindowLog = 27;

    /**
     * Zstd compression strategy.
     *
     * <p>Controls the internal match-finding algorithm.  Higher strategies find
     * better matches (improving compression ratio) but consume more CPU cycles.
     * The strategy interacts with the compression level: higher levels automatically
     * select higher strategies, but this parameter can override that selection.</p>
     *
     * <p>Values (from zstd.h):</p>
     * <ul>
     *   <li>{@code 0} — use default (determined by compression level)</li>
     *   <li>{@code 1} — ZSTD_fast: fastest, worst ratio</li>
     *   <li>{@code 2} — ZSTD_dfast: slightly slower, better ratio</li>
     *   <li>{@code 3} — ZSTD_greedy: moderate speed and ratio</li>
     *   <li>{@code 4} — ZSTD_lazy: slower, good ratio</li>
     *   <li>{@code 5} — ZSTD_lazy2: slower, better ratio</li>
     *   <li>{@code 6} — ZSTD_btlazy2: uses binary tree, good ratio</li>
     *   <li>{@code 7} — ZSTD_btopt: optimal parsing, high CPU cost</li>
     *   <li>{@code 8} — ZSTD_btultra: ultra-optimal, very high CPU cost</li>
     *   <li>{@code 9} — ZSTD_btultra2: maximum compression, extreme CPU cost</li>
     * </ul>
     *
     * <p>Default: 0 (auto).  For Minecraft servers, values above 5 are unlikely to
     * provide meaningful improvement and may cause tick lag.</p>
     */
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

    // --- Proxy Compatibility ---

    /**
     * Controls how Krypton Hybrid interacts with reverse proxies (e.g. Velocity).
     * <ul>
     *   <li>{@link ProxyMode#NONE} — no proxy; all optimizations active (direct
     *       client↔server connection). This is the default.</li>
     *   <li>{@link ProxyMode#AUTO} — auto-detect Velocity via the
     *       {@code velocity:player_info} login plugin channel. When detected,
     *       Zstd compression is disabled on the backend leg and custom wire formats
     *       are gated behind the {@code krypton_hybrid:hello} handshake.</li>
     *   <li>{@link ProxyMode#VELOCITY} — assume every connection comes through
     *       Velocity. Forces ZLIB and gates all custom wire formats.</li>
     * </ul>
     * Default: {@link ProxyMode#NONE}.
     */
    public static volatile ProxyMode proxyMode = ProxyMode.NONE;

    /**
     * Shared secret for Velocity Modern Forwarding (HMAC-SHA256 verification).
     *
     * <p>When non-empty and {@code proxyMode} is {@link ProxyMode#AUTO} or
     * {@link ProxyMode#VELOCITY}, the server will:
     * <ol>
     *   <li>Send a {@code velocity:player_info} login plugin request to each
     *       connecting client (actually the Velocity proxy).</li>
     *   <li>Verify the HMAC-SHA256 signature in the response using this secret.</li>
     *   <li>Extract the real player IP, UUID, name, and skin properties.</li>
     * </ol>
     *
     * <p>This secret must match the {@code forwarding-secret} configured in
     * Velocity's {@code velocity.toml} (or the content of the file referenced
     * by {@code forwarding-secret-file}).</p>
     *
     * <p>Default: {@code ""} (empty — modern forwarding disabled).</p>
     */
    public static volatile String velocityForwardingSecret = "";

    private KryptonConfig() {}
}

