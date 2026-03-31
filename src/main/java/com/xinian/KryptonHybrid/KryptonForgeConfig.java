package com.xinian.KryptonHybrid;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.ProxyMode;
import com.xinian.KryptonHybrid.shared.network.compression.CompressionAlgorithm;
import org.apache.commons.lang3.tuple.Pair;

/**
 * NeoForge-specific configuration definition for Krypton Hybrid (1.21.1).
 *
 * <p>The config file is registered as type {@link net.neoforged.fml.config.ModConfig.Type#COMMON COMMON},
 * which means NeoForge generates it immediately at mod load time under
 * {@code config/krypton_hybrid-common.toml} on both clients and dedicated servers.
 * The file is created on the first launch and can be edited while the game is not running.</p>
 *
 * <p>Values are pushed into {@link KryptonConfig} via {@link #bake()} whenever the config
 * file is loaded or reloaded.</p>
 */
public final class KryptonForgeConfig {

    /** The built {@link ModConfigSpec}. Pass this to {@code registerConfig()}. */
    public static final ModConfigSpec SPEC;

    /** The singleton config instance. */
    public static final KryptonForgeConfig INSTANCE;

    static {
        Pair<KryptonForgeConfig, ModConfigSpec> pair =
                new ModConfigSpec.Builder().configure(KryptonForgeConfig::new);
        INSTANCE = pair.getLeft();
        SPEC     = pair.getRight();
    }

    private final ModConfigSpec.EnumValue<CompressionAlgorithm> algorithm;
    private final ModConfigSpec.IntValue                        zstdLevel;
    private final ModConfigSpec.IntValue                        zstdWorkers;
    private final ModConfigSpec.IntValue                        zstdOverlapLog;
    private final ModConfigSpec.IntValue                        zstdJobSize;
    private final ModConfigSpec.BooleanValue                    zstdEnableLDM;
    private final ModConfigSpec.IntValue                        zstdLongDistanceWindowLog;
    private final ModConfigSpec.IntValue                        zstdStrategy;
    private final ModConfigSpec.BooleanValue                    zstdDictEnabled;
    private final ModConfigSpec.ConfigValue<String>             zstdDictPath;
    private final ModConfigSpec.BooleanValue                    zstdDictRequired;
    private final ModConfigSpec.BooleanValue                    lightOptEnabled;
    private final ModConfigSpec.BooleanValue                    chunkOptEnabled;
    private final ModConfigSpec.BooleanValue                    dccEnabled;
    private final ModConfigSpec.IntValue                        dccSizeLimit;
    private final ModConfigSpec.IntValue                        dccDistance;
    private final ModConfigSpec.IntValue                        dccTimeoutSeconds;
    private final ModConfigSpec.BooleanValue                    broadcastCacheEnabled;
    private final ModConfigSpec.BooleanValue                    packetCoalescingEnabled;
    private final ModConfigSpec.BooleanValue                    blockEntityDeltaEnabled;
    private final ModConfigSpec.EnumValue<ProxyMode>            proxyMode;
    private final ModConfigSpec.ConfigValue<String>             velocityForwardingSecret;

    // --- Security ---
    private final ModConfigSpec.BooleanValue                    securityEnabled;
    private final ModConfigSpec.IntValue                        securityConnectionRateLimit;
    private final ModConfigSpec.IntValue                        securityConnectionBurstLimit;
    private final ModConfigSpec.BooleanValue                    securityPacketRateLimitEnabled;
    private final ModConfigSpec.IntValue                        securityPacketsPerSecond;
    private final ModConfigSpec.IntValue                        securityPacketBurstLimit;
    private final ModConfigSpec.IntValue                        securityMaxDecompressedBytes;
    private final ModConfigSpec.IntValue                        securityMaxCompressionRatio;
    private final ModConfigSpec.IntValue                        securityHandshakeTimeoutSec;
    private final ModConfigSpec.IntValue                        securityLoginTimeoutSec;
    private final ModConfigSpec.IntValue                        securityPlayTimeoutSec;
    private final ModConfigSpec.IntValue                        securityMaxPacketBytes;
    private final ModConfigSpec.IntValue                        securityMinProtocolVersion;
    private final ModConfigSpec.IntValue                        securityMaxProtocolVersion;
    private final ModConfigSpec.IntValue                        securityMaxHandshakeAddressLength;
    private final ModConfigSpec.IntValue                        securityAnomalyStrikeThreshold;
    private final ModConfigSpec.IntValue                        securityWriteWatermarkLow;
    private final ModConfigSpec.IntValue                        securityWriteWatermarkHigh;
    private final ModConfigSpec.IntValue                        securityMaxPendingWrites;
    private final ModConfigSpec.IntValue                        securityMaxUnwritableSeconds;
    private final ModConfigSpec.IntValue                        securityMetricsIntervalSec;

    private KryptonForgeConfig(ModConfigSpec.Builder builder) {
        builder.comment(
                "Krypton Hybrid - Packet Compression",
                "Select the compression algorithm used for all player connections.",
                "IMPORTANT: BOTH the server and every connecting client must use the same",
                "algorithm. Mismatched algorithms will immediately corrupt the session."
        ).push("compression");

        algorithm = builder
                .comment(
                        "Compression algorithm to use for network packets.",
                        "  ZLIB  - vanilla zlib (DEFLATE). Always available. Minecraft default.",
                        "  ZSTD  - Zstandard compression (zstd-jni, native). Best ratio; moderate CPU.",
                        "Default: ZSTD"
                )
                .defineEnum("algorithm", CompressionAlgorithm.ZSTD);

        zstdLevel = builder
                .comment(
                        "Zstd compression level (1 = fastest/largest, 22 = slowest/smallest).",
                        "Only used when algorithm = ZSTD. Backed by zstd-jni (native).",
                        "Level 3 is the zstd reference implementation default.",
                        "Range: 1 \u2013 22  |  Default: 3"
                )
                .defineInRange("zstd_level", 3, 1, 22);

        builder.pop();

        builder.comment(
                "Krypton Hybrid - Zstd Advanced / Parallel Compression",
                "Fine-grained control over Zstd's native multi-threaded compression",
                "and match-finding parameters.  These settings only take effect when",
                "compression.algorithm = ZSTD.  Changes require a server restart",
                "(new connections will use the updated values).",
                "",
                "WARNING: Misconfigured values can increase CPU usage or memory",
                "consumption significantly.  The defaults are safe for all scenarios."
        ).push("zstd_advanced");

        zstdWorkers = builder
                .comment(
                        "Number of native worker threads for parallel Zstd compression.",
                        "0 = single-threaded (compression runs in the Netty I/O thread).",
                        "Values >= 1 activate libzstd's multi-threaded mode: input data is",
                        "split into jobs compressed in parallel by a per-connection native",
                        "thread pool.  Useful when compressing large payloads (chunk data,",
                        "recipe sync) on multi-core CPUs.",
                        "",
                        "Total native threads = workers × active connections, so keep this",
                        "low on high-player-count servers.",
                        "Range: 0 \u2013 128  |  Default: 0 (single-threaded)"
                )
                .defineInRange("workers", 0, 0, 128);

        zstdOverlapLog = builder
                .comment(
                        "Overlap log for multi-threaded compression.",
                        "Controls how much context (dictionary data) each worker thread",
                        "shares with the previous thread's output.  Higher values improve",
                        "compression ratio but use more memory per job.",
                        "Only meaningful when workers >= 1.",
                        "overlap_size = 2^(overlapLog) KB.",
                        "0 = auto (Zstd picks a value based on compression level).",
                        "Range: 0 \u2013 9  |  Default: 0"
                )
                .defineInRange("overlap_log", 0, 0, 9);

        zstdJobSize = builder
                .comment(
                        "Job size (bytes) for multi-threaded compression.",
                        "Minimum input partition per worker thread.  Smaller values increase",
                        "parallelism for small payloads but add scheduling overhead.",
                        "Only meaningful when workers >= 1.",
                        "0 = auto (Zstd selects based on compression level and overlap).",
                        "Range: 0 (auto) or 512 \u2013 1073741824  |  Default: 0"
                )
                .defineInRange("job_size", 0, 0, 1073741824);

        zstdEnableLDM = builder
                .comment(
                        "Enable Zstd long-distance matching (LDM).",
                        "When true, Zstd searches for repeated byte sequences across a",
                        "much larger window than the standard match finder.  Improves ratio",
                        "for highly repetitive data (flat-world chunks, bulk NBT) at the",
                        "cost of higher memory usage.",
                        "Default: false"
                )
                .define("enable_long_distance_matching", false);

        zstdLongDistanceWindowLog = builder
                .comment(
                        "Window log for long-distance matching.",
                        "Sets the LDM window size exponent: window = 2^windowLog bytes.",
                        "Only used when enable_long_distance_matching = true.",
                        "20 = 1 MB, 24 = 16 MB, 27 = 128 MB (Zstd default).",
                        "For Minecraft traffic, 20\u201324 is usually sufficient.",
                        "Range: 10 \u2013 30  |  Default: 27"
                )
                .defineInRange("long_distance_window_log", 27, 10, 30);

        zstdStrategy = builder
                .comment(
                        "Zstd compression strategy (match-finding algorithm).",
                        "Higher strategies find better matches but use more CPU.",
                        "0 = auto (determined by compression level).",
                        "1 = fast,  2 = dfast,  3 = greedy,  4 = lazy,  5 = lazy2,",
                        "6 = btlazy2,  7 = btopt,  8 = btultra,  9 = btultra2.",
                        "Values above 5 are NOT recommended for real-time game servers.",
                        "Range: 0 \u2013 9  |  Default: 0 (auto)"
                )
                .defineInRange("strategy", 0, 0, 9);

        zstdDictEnabled = builder
                .comment(
                        "Enable pre-trained Zstd dictionary compression.",
                        "Both server and client must use the same dictionary file.",
                        "Default: false"
                )
                .define("dict_enabled", false);

        zstdDictPath = builder
                .comment(
                        "Path to the pre-trained dictionary file (.zdict).",
                        "Relative paths are resolved from the game working directory.",
                        "Default: config/krypton_hybrid.zdict"
                )
                .define("dict_path", "config/krypton_hybrid.zdict");

        zstdDictRequired = builder
                .comment(
                        "If true, dictionary load failure is fatal for Zstd context creation.",
                        "If false, Krypton falls back to plain Zstd and logs a warning.",
                        "Default: false"
                )
                .define("dict_required", false);

        builder.pop();

        builder.comment(
                "Krypton Hybrid - Light Data Optimization",
                "Reduces ClientboundLevelChunkWithLightPacket size by replacing uniform",
                "DataLayer arrays (e.g. all-max sky-light sections) with 2-byte tokens.",
                "Requires Krypton Hybrid on BOTH the server and every connecting client."
        ).push("light_opt");

        lightOptEnabled = builder
                .comment(
                        "Enable uniform-RLE encoding for light DataLayer arrays.",
                        "Saves up to ~40 KB per chunk load in open-sky environments.",
                        "Default: true"
                )
                .define("enabled", true);

        builder.pop();

        builder.comment(
                "Krypton Hybrid - Chunk Data Optimization",
                "Reduces ClientboundLevelChunkPacketData size by replacing NBT-based",
                "heightmap serialization with compact binary + XOR-delta encoding, and",
                "extracting biome data from the section buffer for single-value detection.",
                "Requires Krypton Hybrid on BOTH the server and every connecting client."
        ).push("chunk_data_opt");

        chunkOptEnabled = builder
                .comment(
                        "Enable biome delta encoding and heightmap compression.",
                        "Heightmaps: compact binary format with XOR-delta (~40 bytes NBT overhead",
                        "saved per chunk, plus significantly better compressibility for correlated",
                        "heightmap data under Zstd/ZLIB).",
                        "Biomes: single-value sections encoded as 2 bytes instead of 3+; biome",
                        "data grouped for better cross-section compressor exploitation.",
                        "Default: true"
                )
                .define("enabled", true);

        builder.pop();

        builder.comment(
                "Delayed Chunk Cache (DCC)",
                "Reduces redundant chunk resends when a player moves near the edge of",
                "their view distance. Departing chunks are buffered; if the player",
                "re-enters range within the timeout, the full resend is skipped."
        ).push("dcc");

        dccEnabled = builder
                .comment(
                        "Enable or disable the Delayed Chunk Cache entirely.",
                        "Default: true"
                )
                .define("enabled", true);

        dccSizeLimit = builder
                .comment(
                        "Maximum number of chunks buffered per player.",
                        "When full, new departing chunks are untracked immediately.",
                        "Range: 1 \u2013 200  |  Default: 60"
                )
                .defineInRange("size_limit", 60, 1, 200);

        dccDistance = builder
                .comment(
                        "Cache radius (chunks) around the player's current position.",
                        "Cached chunks farther than this are evicted and untracked.",
                        "Range: 1 \u2013 32  |  Default: 5"
                )
                .defineInRange("distance", 5, 1, 32);

        dccTimeoutSeconds = builder
                .comment(
                        "Seconds before a cached chunk is forcibly evicted.",
                        "Higher values improve hit rate; lower values reduce client memory.",
                        "Range: 5 \u2013 300  |  Default: 30"
                )
                .defineInRange("timeout_seconds", 30, 5, 300);

        builder.pop();

        builder.comment(
                "Broadcast Serialization Cache",
                "Caches serialized packet bytes when the same Packet object is",
                "broadcast to multiple players, avoiding redundant serialization."
        ).push("broadcast_cache");

        broadcastCacheEnabled = builder
                .comment(
                        "Enable the broadcast serialization cache.",
                        "Saves CPU by avoiding re-serialization of the same packet",
                        "when broadcast to multiple players on the same Netty I/O thread.",
                        "Default: true"
                )
                .define("enabled", true);

        builder.pop();

        builder.comment(
                "Packet Coalescing",
                "Deduplicates redundant entity update packets within each tick's",
                "bundle before sending.  Removes superseded velocity, teleport,",
                "and entity data packets for the same entity."
        ).push("packet_coalescing");

        packetCoalescingEnabled = builder
                .comment(
                        "Enable packet coalescing within entity tracking bundles.",
                        "Default: true"
                )
                .define("enabled", true);

        builder.pop();

        builder.comment(
                "Block Entity NBT Delta Sync",
                "Reduces block entity data packet size by sending only changed NBT",
                "keys instead of the full tag.  Requires Krypton Hybrid on BOTH",
                "the server and every connecting client."
        ).push("block_entity_delta");

        blockEntityDeltaEnabled = builder
                .comment(
                        "Enable per-player NBT delta encoding for block entity updates.",
                        "Significantly reduces bandwidth for frequently-updating block",
                        "entities (furnaces, hoppers, redstone components).",
                        "Default: true"
                )
                .define("enabled", true);

        builder.pop();

        builder.comment(
                "Proxy Compatibility",
                "Controls how Krypton Hybrid interacts with reverse proxies",
                "(e.g. Velocity). When behind a proxy, certain optimizations must",
                "be disabled or gated to preserve proxy compatibility."
        ).push("proxy");

        proxyMode = builder
                .comment(
                        "Proxy detection mode.",
                        "  NONE     - No proxy; all optimizations active (direct connection).",
                        "  AUTO     - Auto-detect Velocity via login plugin channel.",
                        "             When detected, forces ZLIB on backend and gates custom",
                        "             wire formats behind capability negotiation.",
                        "  VELOCITY - Assume Velocity proxy; always use ZLIB backend",
                        "             compression and gate custom wire formats.",
                        "Default: NONE"
                )
                .defineEnum("mode", ProxyMode.NONE);

        velocityForwardingSecret = builder
                .comment(
                        "Shared secret for Velocity Modern Forwarding.",
                        "Must match the forwarding-secret in Velocity's velocity.toml.",
                        "When non-empty and mode is AUTO or VELOCITY, the server will",
                        "verify connecting players via HMAC-SHA256 signed forwarding data.",
                        "Leave empty to disable modern forwarding (heuristic detection only).",
                        "Default: (empty)"
                )
                .define("forwarding_secret", "");

        builder.pop();

        // ═══════════════════════════════════════════════════════════════
        // §  Network Security & DDoS Protection
        // ═══════════════════════════════════════════════════════════════

        builder.comment(
                "Krypton Hybrid - Network Security & DDoS Protection",
                "Comprehensive protection against connection floods, packet floods,",
                "decompression bombs, protocol abuse, and resource exhaustion.",
                "All features are gated behind the global 'enabled' toggle."
        ).push("security");

        securityEnabled = builder
                .comment(
                        "Global kill-switch for all security features.",
                        "When false, no security handlers are injected into the pipeline.",
                        "Default: true"
                )
                .define("enabled", true);

        builder.comment(
                "Connection Rate Limiting",
                "Limits the number of new TCP connections per IP per second.",
                "Prevents SYN flood and login flood attacks."
        ).push("connection_rate_limit");

        securityConnectionRateLimit = builder
                .comment(
                        "Maximum new connections per IP per second (sustained rate).",
                        "Range: 1 - 1000  |  Default: 10"
                )
                .defineInRange("rate", 10, 1, 1000);

        securityConnectionBurstLimit = builder
                .comment(
                        "Burst limit — peak connections allowed within a 1-second window.",
                        "Range: 1 - 2000  |  Default: 20"
                )
                .defineInRange("burst", 20, 1, 2000);

        builder.pop(); // connection_rate_limit

        builder.comment(
                "Packet Rate Limiting",
                "Per-connection token-bucket PPS (packets per second) limiter.",
                "Prevents high-frequency small-packet floods."
        ).push("packet_rate_limit");

        securityPacketRateLimitEnabled = builder
                .comment(
                        "Enable per-connection packet rate limiting.",
                        "Default: true"
                )
                .define("enabled", true);

        securityPacketsPerSecond = builder
                .comment(
                        "Sustained packets-per-second limit per connection.",
                        "Range: 50 - 10000  |  Default: 500"
                )
                .defineInRange("pps", 500, 50, 10000);

        securityPacketBurstLimit = builder
                .comment(
                        "Burst capacity — maximum token accumulation.",
                        "Allows short traffic spikes (e.g. chunk loading).",
                        "Range: 50 - 20000  |  Default: 800"
                )
                .defineInRange("burst", 800, 50, 20000);

        builder.pop(); // packet_rate_limit

        builder.comment(
                "Decompression Bomb Protection",
                "Validates compression ratios before decompression to prevent",
                "OOM attacks via crafted compressed payloads."
        ).push("decompression_guard");

        securityMaxDecompressedBytes = builder
                .comment(
                        "Maximum allowed decompressed packet size in bytes.",
                        "Packets claiming a larger size are rejected before decompression.",
                        "Range: 1048576 (1 MiB) - 134217728 (128 MiB)  |  Default: 16777216 (16 MiB)"
                )
                .defineInRange("max_decompressed_bytes", 16 * 1024 * 1024,
                        1024 * 1024, 128 * 1024 * 1024);

        securityMaxCompressionRatio = builder
                .comment(
                        "Maximum allowed compression ratio (uncompressed:compressed).",
                        "Packets exceeding this ratio are flagged as bombs.",
                        "Range: 10 - 10000  |  Default: 100"
                )
                .defineInRange("max_ratio", 100, 10, 10000);

        builder.pop(); // decompression_guard

        builder.comment(
                "Stage-Aware Read Timeouts",
                "Protects against half-open connections and slow-login attacks",
                "by applying different read timeouts for each connection stage."
        ).push("timeouts");

        securityHandshakeTimeoutSec = builder
                .comment(
                        "Read timeout in seconds during the HANDSHAKE stage.",
                        "Range: 1 - 30  |  Default: 5"
                )
                .defineInRange("handshake_sec", 5, 1, 30);

        securityLoginTimeoutSec = builder
                .comment(
                        "Read timeout in seconds during the LOGIN stage.",
                        "Range: 5 - 60  |  Default: 10"
                )
                .defineInRange("login_sec", 10, 5, 60);

        securityPlayTimeoutSec = builder
                .comment(
                        "Read timeout in seconds during the PLAY stage.",
                        "Range: 10 - 120  |  Default: 30"
                )
                .defineInRange("play_sec", 30, 10, 120);

        builder.pop(); // timeouts

        builder.comment(
                "Packet Size Validation",
                "Rejects oversized decoded packet frames to prevent resource exhaustion."
        ).push("packet_size");

        securityMaxPacketBytes = builder
                .comment(
                        "Maximum decoded packet frame size in bytes.",
                        "Range: 65536 (64 KiB) - 16777216 (16 MiB)  |  Default: 2097152 (2 MiB)"
                )
                .defineInRange("max_bytes", 2 * 1024 * 1024, 65536, 16 * 1024 * 1024);

        builder.pop(); // packet_size

        builder.comment(
                "Handshake Validation",
                "Validates protocol version and server address in the initial handshake",
                "to reject scanning bots and malformed clients early."
        ).push("handshake_validation");

        securityMinProtocolVersion = builder
                .comment(
                        "Minimum accepted protocol version.",
                        "Range: 1 - 2000  |  Default: 3 (Minecraft 1.7)"
                )
                .defineInRange("min_protocol", 3, 1, 2000);

        securityMaxProtocolVersion = builder
                .comment(
                        "Maximum accepted protocol version.",
                        "Range: 100 - 5000  |  Default: 1100"
                )
                .defineInRange("max_protocol", 1100, 100, 5000);

        securityMaxHandshakeAddressLength = builder
                .comment(
                        "Maximum server address string length in the handshake.",
                        "Range: 32 - 512  |  Default: 255"
                )
                .defineInRange("max_address_length", 255, 32, 512);

        builder.pop(); // handshake_validation


        builder.comment(
                "Anomaly Detection",
                "Tracks suspicious behaviour patterns per connection and disconnects",
                "connections that exceed a configurable weighted strike threshold.",
                "Strike weights: HMAC failure=3, compression error=2,",
                "invalid packet=2, missing hello=1, rapid reconnect=1, protocol violation=3."
        ).push("anomaly");

        securityAnomalyStrikeThreshold = builder
                .comment(
                        "Total weighted strikes before the connection is forcibly closed.",
                        "Range: 3 - 100  |  Default: 10"
                )
                .defineInRange("strike_threshold", 10, 3, 100);

        builder.pop(); // anomaly

        builder.comment(
                "Netty Resource Protection",
                "Prevents memory exhaustion from slow clients that don't read",
                "outbound data fast enough (slow-read / slow-loris attacks)."
        ).push("resource_guard");

        securityWriteWatermarkLow = builder
                .comment(
                        "Write buffer low watermark in bytes.",
                        "Range: 32768 - 4194304  |  Default: 524288 (512 KiB)"
                )
                .defineInRange("watermark_low", 512 * 1024, 32768, 4 * 1024 * 1024);

        securityWriteWatermarkHigh = builder
                .comment(
                        "Write buffer high watermark in bytes.",
                        "Range: 65536 - 16777216  |  Default: 2097152 (2 MiB)"
                )
                .defineInRange("watermark_high", 2 * 1024 * 1024, 65536, 16 * 1024 * 1024);

        securityMaxPendingWrites = builder
                .comment(
                        "Maximum pending writes per connection before new writes are dropped.",
                        "Range: 128 - 65536  |  Default: 4096"
                )
                .defineInRange("max_pending_writes", 4096, 128, 65536);

        securityMaxUnwritableSeconds = builder
                .comment(
                        "Maximum seconds a channel can stay unwritable before force-close.",
                        "Range: 5 - 120  |  Default: 15"
                )
                .defineInRange("max_unwritable_seconds", 15, 5, 120);

        builder.pop(); // resource_guard

        builder.comment(
                "Security Metrics",
                "Periodic logging of security event counters."
        ).push("metrics");

        securityMetricsIntervalSec = builder
                .comment(
                        "Interval in seconds for logging security metric summaries.",
                        "Set to 0 to disable periodic logging.",
                        "Range: 0 - 3600  |  Default: 300 (5 min)"
                )
                .defineInRange("interval_sec", 300, 0, 3600);

        builder.pop(); // metrics

        builder.pop(); // security
    }

    /**
     * Copies the current config values into {@link KryptonConfig}.
     * Called from {@link kryptonhybrid} in response to
     * {@link ModConfigEvent.Loading} and {@link ModConfigEvent.Reloading}.
     */
    public void bake() {
        KryptonConfig.compressionAlgorithm = algorithm.get();
        KryptonConfig.zstdLevel            = zstdLevel.get();
        KryptonConfig.zstdWorkers          = zstdWorkers.get();
        KryptonConfig.zstdOverlapLog       = zstdOverlapLog.get();
        KryptonConfig.zstdJobSize          = zstdJobSize.get();
        KryptonConfig.zstdEnableLDM        = zstdEnableLDM.get();
        KryptonConfig.zstdLongDistanceWindowLog = zstdLongDistanceWindowLog.get();
        KryptonConfig.zstdStrategy         = zstdStrategy.get();
        KryptonConfig.zstdDictEnabled      = zstdDictEnabled.get();
        KryptonConfig.zstdDictPath         = zstdDictPath.get();
        KryptonConfig.zstdDictRequired     = zstdDictRequired.get();
        KryptonConfig.lightOptEnabled      = lightOptEnabled.get();
        KryptonConfig.chunkOptEnabled      = chunkOptEnabled.get();
        KryptonConfig.dccEnabled           = dccEnabled.get();
        KryptonConfig.dccSizeLimit         = dccSizeLimit.get();
        KryptonConfig.dccDistance          = dccDistance.get();
        KryptonConfig.dccTimeoutSeconds    = dccTimeoutSeconds.get();
        KryptonConfig.broadcastCacheEnabled    = broadcastCacheEnabled.get();
        KryptonConfig.packetCoalescingEnabled  = packetCoalescingEnabled.get();
        KryptonConfig.blockEntityDeltaEnabled  = blockEntityDeltaEnabled.get();
        KryptonConfig.proxyMode                = proxyMode.get();
        KryptonConfig.velocityForwardingSecret = velocityForwardingSecret.get();

        // --- Security ---
        KryptonConfig.securityEnabled                  = securityEnabled.get();
        KryptonConfig.securityConnectionRateLimit       = securityConnectionRateLimit.get();
        KryptonConfig.securityConnectionBurstLimit      = securityConnectionBurstLimit.get();
        KryptonConfig.securityPacketRateLimitEnabled    = securityPacketRateLimitEnabled.get();
        KryptonConfig.securityPacketsPerSecond          = securityPacketsPerSecond.get();
        KryptonConfig.securityPacketBurstLimit          = securityPacketBurstLimit.get();
        KryptonConfig.securityMaxDecompressedBytes      = securityMaxDecompressedBytes.get();
        KryptonConfig.securityMaxCompressionRatio       = securityMaxCompressionRatio.get();
        KryptonConfig.securityHandshakeTimeoutSec       = securityHandshakeTimeoutSec.get();
        KryptonConfig.securityLoginTimeoutSec           = securityLoginTimeoutSec.get();
        KryptonConfig.securityPlayTimeoutSec            = securityPlayTimeoutSec.get();
        KryptonConfig.securityMaxPacketBytes            = securityMaxPacketBytes.get();
        KryptonConfig.securityMinProtocolVersion        = securityMinProtocolVersion.get();
        KryptonConfig.securityMaxProtocolVersion        = securityMaxProtocolVersion.get();
        KryptonConfig.securityMaxHandshakeAddressLength = securityMaxHandshakeAddressLength.get();
        KryptonConfig.securityAnomalyStrikeThreshold    = securityAnomalyStrikeThreshold.get();
        KryptonConfig.securityWriteWatermarkLow         = securityWriteWatermarkLow.get();
        KryptonConfig.securityWriteWatermarkHigh        = securityWriteWatermarkHigh.get();
        KryptonConfig.securityMaxPendingWrites          = securityMaxPendingWrites.get();
        KryptonConfig.securityMaxUnwritableSeconds      = securityMaxUnwritableSeconds.get();
        KryptonConfig.securityMetricsIntervalSec        = securityMetricsIntervalSec.get();
    }
}

