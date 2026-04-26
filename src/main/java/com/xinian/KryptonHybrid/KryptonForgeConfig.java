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
    private final ModConfigSpec.IntValue                        zstdAdaptiveLargeLevel;
    private final ModConfigSpec.IntValue                        zstdAdaptiveLargeThreshold;
    private final ModConfigSpec.IntValue                        zstdWorkers;
    private final ModConfigSpec.IntValue                        zstdOverlapLog;
    private final ModConfigSpec.IntValue                        zstdJobSize;
    private final ModConfigSpec.BooleanValue                    zstdEnableLDM;
    private final ModConfigSpec.IntValue                        zstdLongDistanceWindowLog;
    private final ModConfigSpec.IntValue                        zstdStrategy;
    private final ModConfigSpec.BooleanValue                    zstdDictEnabled;
    private final ModConfigSpec.ConfigValue<String>             zstdDictPath;
    private final ModConfigSpec.BooleanValue                    zstdDictRequired;
    private final ModConfigSpec.BooleanValue                    zstdDictTrainingCaptureEnabled;
    private final ModConfigSpec.ConfigValue<String>             zstdDictTrainingSamplesDir;
    private final ModConfigSpec.IntValue                        zstdDictTrainingMaxSamples;
    private final ModConfigSpec.IntValue                        zstdDictTrainingSampleEvery;
    private final ModConfigSpec.IntValue                        zstdDictTrainingMinBytes;
    private final ModConfigSpec.IntValue                        zstdDictTrainingMaxBytes;
    private final ModConfigSpec.BooleanValue                    securityEnabled;
    private final ModConfigSpec.IntValue                        securityConnectionRatePerSecond;
    private final ModConfigSpec.IntValue                        securityConnectionBurstLimit;
    private final ModConfigSpec.IntValue                        securityConnectionQuarantineSeconds;
    private final ModConfigSpec.IntValue                        securityRapidReconnectWindowMs;
    private final ModConfigSpec.IntValue                        securityRapidReconnectPenalty;
    private final ModConfigSpec.IntValue                        securityMaxDecompressedBytes;
    private final ModConfigSpec.IntValue                        securityMaxCompressionRatio;
    private final ModConfigSpec.IntValue                        securityMinCompressedBytesForRatioCheck;
    private final ModConfigSpec.IntValue                        securityHandshakeTimeoutSec;
    private final ModConfigSpec.IntValue                        securityLoginTimeoutSec;
    private final ModConfigSpec.IntValue                        securityPlayTimeoutSec;
    private final ModConfigSpec.IntValue                        securityMaxPacketBytes;
    private final ModConfigSpec.IntValue                        securityMaxCustomPayloadBytes;
    private final ModConfigSpec.BooleanValue                    securityReadLimitsEnabled;
    private final ModConfigSpec.IntValue                        securityMaxStringChars;
    private final ModConfigSpec.IntValue                        securityMaxCollectionElements;
    private final ModConfigSpec.IntValue                        securityMaxMapEntries;
    private final ModConfigSpec.IntValue                        securityMaxCountedElements;
    private final ModConfigSpec.IntValue                        securityMaxByteArrayBytes;
    private final ModConfigSpec.BooleanValue                    motdCacheEnabled;
    private final ModConfigSpec.IntValue                        motdCacheTtlMs;
    private final ModConfigSpec.BooleanValue                    securityStatusPingGuardEnabled;
    private final ModConfigSpec.IntValue                        securityStatusPingRatePerSecond;
    private final ModConfigSpec.IntValue                        securityStatusPingBurstLimit;
    private final ModConfigSpec.IntValue                        securityStatusPingQuarantineSeconds;
    private final ModConfigSpec.BooleanValue                    securityStatusPingSilentDrop;
    private final ModConfigSpec.BooleanValue                    securityLegacyQueryGuardEnabled;
    private final ModConfigSpec.IntValue                        securityMinProtocolVersion;
    private final ModConfigSpec.IntValue                        securityMaxProtocolVersion;
    private final ModConfigSpec.IntValue                        securityMaxHandshakeAddressLength;
    private final ModConfigSpec.IntValue                        securityAnomalyStrikeThreshold;
    private final ModConfigSpec.IntValue                        securityWriteWatermarkLow;
    private final ModConfigSpec.IntValue                        securityWriteWatermarkHigh;
    private final ModConfigSpec.IntValue                        securityMaxPendingWrites;
    private final ModConfigSpec.IntValue                        securityMaxUnwritableSeconds;
    private final ModConfigSpec.BooleanValue                    lightOptEnabled;
    private final ModConfigSpec.BooleanValue                    chunkOptEnabled;
    private final ModConfigSpec.BooleanValue                    dccEnabled;
    private final ModConfigSpec.IntValue                        dccSizeLimit;
    private final ModConfigSpec.IntValue                        dccDistance;
    private final ModConfigSpec.IntValue                        dccTimeoutSeconds;
    private final ModConfigSpec.BooleanValue                    broadcastCacheEnabled;
    private final ModConfigSpec.BooleanValue                    bundleAlwaysCompress;
    private final ModConfigSpec.IntValue                        bundleCompressMinBytes;
    private final ModConfigSpec.BooleanValue                    broadcastCompressedCacheEnabled;
    private final ModConfigSpec.BooleanValue                    microBatchFlushEnabled;
    private final ModConfigSpec.IntValue                        microBatchFlushDelayMs;
    private final ModConfigSpec.BooleanValue                    motionDeltaEnabled;
    private final ModConfigSpec.IntValue                        motionDeltaThreshold;
    private final ModConfigSpec.DoubleValue                     teleportDeltaSquared;
    private final ModConfigSpec.BooleanValue                    packetCoalescingEnabled;
    private final ModConfigSpec.BooleanValue                    blockEntityDeltaEnabled;
    private final ModConfigSpec.EnumValue<ProxyMode>            proxyMode;
    private final ModConfigSpec.ConfigValue<String>             velocityForwardingSecret;

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

        zstdAdaptiveLargeLevel = builder
                .comment(
                        "Zstd adaptive compression level for large payloads (1 = fastest/largest, 22 = slowest/smallest).",
                        "Only used when algorithm = ZSTD and payload size exceeds the adaptive threshold.",
                        "Backed by zstd-jni (native).",
                        "Default: 11"
                )
                .defineInRange("zstd_adaptive_large_level", 11, 1, 22);

        zstdAdaptiveLargeThreshold = builder
                .comment(
                        "Payload size (bytes) threshold for activating adaptive compression level.",
                        "Only used when algorithm = ZSTD. Below this size, the regular compression",
                        "level (zstd_level) is used. Above this size, the adaptive level",
                        "(zstd_adaptive_large_level) is used.",
                        "Default: 8192 (8 KB)"
                )
                .defineInRange("zstd_adaptive_large_threshold", 8192, 0, Integer.MAX_VALUE);

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

        zstdDictTrainingCaptureEnabled = builder
                .comment(
                        "Capture serialized packet samples before Zstd compression for dictionary training.",
                        "Only enable this temporarily while collecting representative traffic.",
                        "Captured files may contain mod payload data. Do not publish them publicly.",
                        "Default: false"
                )
                .define("dict_training_capture_enabled", false);

        zstdDictTrainingSamplesDir = builder
                .comment(
                        "Directory where dictionary-training packet samples are written.",
                        "Relative paths are resolved from the game working directory.",
                        "Default: run/krypton_zstd_samples"
                )
                .define("dict_training_samples_dir", "run/krypton_zstd_samples");

        zstdDictTrainingMaxSamples = builder
                .comment(
                        "Maximum packet samples captured in one JVM session.",
                        "Capture stops automatically after this many samples.",
                        "Range: 1 - 100000  |  Default: 12000"
                )
                .defineInRange("dict_training_max_samples", 12000, 1, 100000);

        zstdDictTrainingSampleEvery = builder
                .comment(
                        "Capture one eligible packet every N packets.",
                        "Higher values reduce disk writes and sampling overhead.",
                        "Range: 1 - 10000  |  Default: 3"
                )
                .defineInRange("dict_training_sample_every", 3, 1, 10000);

        zstdDictTrainingMinBytes = builder
                .comment(
                        "Minimum serialized packet size captured for training.",
                        "Very tiny packets usually add noise to dictionary training.",
                        "Range: 1 - 1048576  |  Default: 24"
                )
                .defineInRange("dict_training_min_bytes", 24, 1, 1048576);

        zstdDictTrainingMaxBytes = builder
                .comment(
                        "Maximum serialized packet size captured for training.",
                        "Caps sample-file size and memory copied by the recorder.",
                        "Range: 1024 - 2097152  |  Default: 262144"
                )
                .defineInRange("dict_training_max_bytes", 256 * 1024, 1024, 2 * 1024 * 1024);

        builder.pop();

        builder.comment(
                "Krypton Hybrid - Network Security / Independent Packet Control",
                "Protects the inbound network path without touching Velocity Native fast paths.",
                "All checks run in the Minecraft / NeoForge layer and are designed to fail fast",
                "before expensive decode, decompression, NBT, or gameplay processing begins."
        ).push("security");

        securityEnabled = builder
                .comment("Global security kill-switch.", "Default: true")
                .define("enabled", true);

        builder.comment("Connection-rate limiting and rapid reconnect protection.").push("connection_rate_limit");

        securityConnectionRatePerSecond = builder
                .comment("Sustained connection attempts per second per IP.", "Range: 1 - 1000  |  Default: 8")
                .defineInRange("rate", 8, 1, 1000);

        securityConnectionBurstLimit = builder
                .comment("Burst capacity for new connections per IP.", "Range: 1 - 5000  |  Default: 20")
                .defineInRange("burst", 20, 1, 5000);

        securityConnectionQuarantineSeconds = builder
                .comment("Quarantine duration after repeated connection abuse.", "Range: 0 - 3600  |  Default: 30")
                .defineInRange("quarantine_seconds", 30, 0, 3600);

        securityRapidReconnectWindowMs = builder
                .comment("Rapid reconnect detection window in milliseconds.", "Range: 0 - 60000  |  Default: 1500")
                .defineInRange("rapid_reconnect_window_ms", 1500, 0, 60000);

        securityRapidReconnectPenalty = builder
                .comment("Extra connection tokens consumed on rapid reconnect.", "Range: 0 - 128  |  Default: 4")
                .defineInRange("rapid_reconnect_penalty", 4, 0, 128);

        builder.pop();

        builder.comment("Decompression-bomb prevention for both ZLIB and ZSTD.").push("decompression_guard");

        securityMaxDecompressedBytes = builder
                .defineInRange("max_decompressed_bytes", 16 * 1024 * 1024, 1024, Integer.MAX_VALUE);
        securityMaxCompressionRatio = builder
                .defineInRange("max_ratio", 100, 1, Integer.MAX_VALUE);
        securityMinCompressedBytesForRatioCheck = builder
                .defineInRange("min_compressed_bytes_for_ratio_check", 8, 0, Integer.MAX_VALUE);

        builder.pop();

        builder.comment("Handshake/login timeout budgets.").push("timeouts");

        securityHandshakeTimeoutSec = builder.defineInRange("handshake_sec", 5, 1, 300);
        securityLoginTimeoutSec = builder.defineInRange("login_sec", 10, 1, 300);
        securityPlayTimeoutSec = builder.defineInRange("play_sec", 30, 1, 3600);

        builder.pop();

        builder.comment("Frame and logical payload size checks.").push("packet_size");

        securityMaxPacketBytes = builder
                .defineInRange("frame_max_bytes", 2 * 1024 * 1024, 1024, Integer.MAX_VALUE);
        securityMaxCustomPayloadBytes = builder
                .defineInRange("custom_payload_max_bytes", 128 * 1024, 0, Integer.MAX_VALUE);
        securityReadLimitsEnabled = builder
                .comment("Enable FriendlyByteBuf read-side hard limits (can break large mod custom payloads).", "Default: false")
                .define("read_limits_enabled", false);
        securityMaxStringChars = builder
                .defineInRange("string_max_chars", 32_767, 1, Integer.MAX_VALUE);
        securityMaxCollectionElements = builder
                .defineInRange("collection_max_elements", 16_384, 1, Integer.MAX_VALUE);
        securityMaxMapEntries = builder
                .defineInRange("map_max_entries", 8_192, 1, Integer.MAX_VALUE);
        securityMaxCountedElements = builder
                .defineInRange("counted_loop_max", 16_384, 1, Integer.MAX_VALUE);
        securityMaxByteArrayBytes = builder
                .defineInRange("byte_array_max_bytes", 2 * 1024 * 1024, 1, Integer.MAX_VALUE);

        builder.pop();

        builder.comment(
                "MOTD / Server List Ping cache and scan protection.",
                "These settings target server-list scanners and repeated status pings.",
                "They do not affect normal LOGIN connections."
        ).push("status_ping_guard");

        motdCacheEnabled = builder
                .comment(
                        "Cache serialized MOTD/status responses for a short time.",
                        "This avoids rebuilding the JSON response for every server-list ping.",
                        "Default: true"
                )
                .define("motd_cache_enabled", true);

        motdCacheTtlMs = builder
                .comment(
                        "MOTD/status cache time-to-live in milliseconds.",
                        "Higher values reduce CPU during scans but make player count stale longer.",
                        "0 disables caching even when motd_cache_enabled is true.",
                        "Range: 0 - 60000  |  Default: 3000"
                )
                .defineInRange("motd_cache_ttl_ms", 3000, 0, 60000);

        securityStatusPingGuardEnabled = builder
                .comment(
                        "Enable per-IP rate limiting for modern STATUS ping handshakes.",
                        "This is separate from LOGIN rate limiting so server-list scans can be",
                        "throttled without blocking real players as aggressively.",
                        "Default: true"
                )
                .define("enabled", true);

        securityStatusPingRatePerSecond = builder
                .comment("Sustained STATUS pings per second per IP.", "Range: 1 - 1000  |  Default: 4")
                .defineInRange("rate", 4, 1, 1000);

        securityStatusPingBurstLimit = builder
                .comment("Burst capacity for STATUS pings per IP.", "Range: 1 - 5000  |  Default: 8")
                .defineInRange("burst", 8, 1, 5000);

        securityStatusPingQuarantineSeconds = builder
                .comment("Temporary quarantine after repeated STATUS ping abuse.", "Range: 0 - 3600  |  Default: 15")
                .defineInRange("quarantine_seconds", 15, 0, 3600);

        securityStatusPingSilentDrop = builder
                .comment(
                        "Silently close excessive STATUS ping connections.",
                        "When true, scanners receive no status JSON and no disconnect message.",
                        "Default: true"
                )
                .define("silent_drop", true);

        securityLegacyQueryGuardEnabled = builder
                .comment(
                        "Apply the same scan guard to legacy pre-1.7 ping packets.",
                        "Default: true"
                )
                .define("legacy_query_guard_enabled", true);

        builder.pop();

        builder.comment("Handshake protocol sanity checks.").push("handshake_validation");

        securityMinProtocolVersion = builder.defineInRange("min_protocol", 3, 0, Integer.MAX_VALUE);
        securityMaxProtocolVersion = builder.defineInRange("max_protocol", 1100, 0, Integer.MAX_VALUE);
        securityMaxHandshakeAddressLength = builder.defineInRange("max_address_length", 255, 1, 1024);

        builder.pop();

        builder.comment("Weighted anomaly response policy.").push("anomaly");

        securityAnomalyStrikeThreshold = builder
                .defineInRange("strike_threshold", 10, 1, 1000);

        builder.pop();

        builder.comment("Write-side backpressure and slow-reader protection.").push("resource_guard");

        securityWriteWatermarkLow = builder
                .defineInRange("watermark_low", 524_288, 1024, Integer.MAX_VALUE);
        securityWriteWatermarkHigh = builder
                .defineInRange("watermark_high", 2_097_152, 1024, Integer.MAX_VALUE);
        securityMaxPendingWrites = builder
                .defineInRange("max_pending_writes", 4096, 1, Integer.MAX_VALUE);
        securityMaxUnwritableSeconds = builder
                .defineInRange("max_unwritable_seconds", 15, 1, 3600);

        builder.pop();
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
                "Bundle Forced Compression",
                "Lowers the compression threshold for sub-packets inside vanilla bundle",
                "frames. The encoder still falls back to raw output when compression",
                "would increase wire size."
        ).push("bundle_compression");

        bundleAlwaysCompress = builder
                .comment(
                        "Force compression for bundle sub-packets above min_bytes.",
                        "Best when Zstd dictionary compression is enabled.",
                        "Default: true"
                )
                .define("always_compress", true);

        bundleCompressMinBytes = builder
                .comment(
                        "Minimum uncompressed bundle sub-packet size before forced compression.",
                        "Range: 1 - 4096  |  Default: 24"
                )
                .defineInRange("min_bytes", 24, 1, 4096);

        builder.pop();

        builder.comment(
                "Broadcast Compressed Cache",
                "Caches post-compression wire bytes for large broadcast packets such as",
                "chunk and light updates. This mainly saves CPU, and also keeps repeated",
                "broadcast output stable across recipients on the same Netty thread."
        ).push("broadcast_compressed_cache");

        broadcastCompressedCacheEnabled = builder
                .comment("Enable compressed-byte broadcast caching.", "Default: true")
                .define("enabled", true);

        builder.pop();

        builder.comment(
                "Cross-tick Micro-batch Flush",
                "Defers the end-of-tick flush by a few milliseconds to coalesce writes",
                "from adjacent broadcast phases. This reduces syscall pressure and may",
                "improve compression grouping at the cost of small added latency."
        ).push("micro_batch_flush");

        microBatchFlushEnabled = builder
                .comment("Enable deferred micro-batch flush.", "Default: false")
                .define("enabled", false);

        microBatchFlushDelayMs = builder
                .comment("Deferred flush delay in milliseconds.", "Range: 1 - 20  |  Default: 5")
                .defineInRange("delay_ms", 5, 1, 20);

        builder.pop();

        builder.comment(
                "Motion and Teleport Delta Filter",
                "Drops redundant motion and teleport packets when the encoded delta is",
                "below a visual threshold for the same entity/player pair."
        ).push("motion_delta");

        motionDeltaEnabled = builder
                .comment("Enable motion and teleport delta filtering.", "Default: true")
                .define("enabled", true);

        motionDeltaThreshold = builder
                .comment(
                        "Encoded motion-unit threshold per axis. Vanilla motion is velocity * 8000.",
                        "Range: 0 - 8000  |  Default: 40"
                )
                .defineInRange("motion_threshold", 40, 0, 8000);

        teleportDeltaSquared = builder
                .comment(
                        "Squared block-distance threshold for redundant teleport packets.",
                        "Range: 0.0 - 1.0  |  Default: 1.0E-4"
                )
                .defineInRange("teleport_delta_squared", 1.0E-4, 0.0, 1.0);

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
    }

    /**
     * Copies the current config values into {@link KryptonConfig}.
     * Called from {@link kryptonhybrid} in response to
     * {@link ModConfigEvent.Loading} and {@link ModConfigEvent.Reloading}.
     */
    public void bake() {
        KryptonConfig.compressionAlgorithm = algorithm.get();
        KryptonConfig.zstdLevel            = zstdLevel.get();
        KryptonConfig.zstdAdaptiveLargeLevel     = zstdAdaptiveLargeLevel.get();
        KryptonConfig.zstdAdaptiveLargeThreshold = zstdAdaptiveLargeThreshold.get();
        KryptonConfig.zstdWorkers          = zstdWorkers.get();
        KryptonConfig.zstdOverlapLog       = zstdOverlapLog.get();
        KryptonConfig.zstdJobSize          = zstdJobSize.get();
        KryptonConfig.zstdEnableLDM        = zstdEnableLDM.get();
        KryptonConfig.zstdLongDistanceWindowLog = zstdLongDistanceWindowLog.get();
        KryptonConfig.zstdStrategy         = zstdStrategy.get();
        KryptonConfig.zstdDictEnabled      = zstdDictEnabled.get();
        KryptonConfig.zstdDictPath         = zstdDictPath.get();
        KryptonConfig.zstdDictRequired     = zstdDictRequired.get();
        KryptonConfig.zstdDictTrainingCaptureEnabled = zstdDictTrainingCaptureEnabled.get();
        KryptonConfig.zstdDictTrainingSamplesDir     = zstdDictTrainingSamplesDir.get();
        KryptonConfig.zstdDictTrainingMaxSamples     = zstdDictTrainingMaxSamples.get();
        KryptonConfig.zstdDictTrainingSampleEvery    = zstdDictTrainingSampleEvery.get();
        KryptonConfig.zstdDictTrainingMinBytes       = zstdDictTrainingMinBytes.get();
        KryptonConfig.zstdDictTrainingMaxBytes       = zstdDictTrainingMaxBytes.get();
        KryptonConfig.securityEnabled      = securityEnabled.get();
        KryptonConfig.securityConnectionRatePerSecond = securityConnectionRatePerSecond.get();
        KryptonConfig.securityConnectionBurstLimit = securityConnectionBurstLimit.get();
        KryptonConfig.securityConnectionQuarantineSeconds = securityConnectionQuarantineSeconds.get();
        KryptonConfig.securityRapidReconnectWindowMs = securityRapidReconnectWindowMs.get();
        KryptonConfig.securityRapidReconnectPenalty = securityRapidReconnectPenalty.get();
        KryptonConfig.securityMaxDecompressedBytes = securityMaxDecompressedBytes.get();
        KryptonConfig.securityMaxCompressionRatio = securityMaxCompressionRatio.get();
        KryptonConfig.securityMinCompressedBytesForRatioCheck = securityMinCompressedBytesForRatioCheck.get();
        KryptonConfig.securityHandshakeTimeoutSec = securityHandshakeTimeoutSec.get();
        KryptonConfig.securityLoginTimeoutSec = securityLoginTimeoutSec.get();
        KryptonConfig.securityPlayTimeoutSec = securityPlayTimeoutSec.get();
        KryptonConfig.securityMaxPacketBytes = securityMaxPacketBytes.get();
        KryptonConfig.securityMaxCustomPayloadBytes = securityMaxCustomPayloadBytes.get();
        KryptonConfig.securityReadLimitsEnabled = securityReadLimitsEnabled.get();
        KryptonConfig.securityMaxStringChars = securityMaxStringChars.get();
        KryptonConfig.securityMaxCollectionElements = securityMaxCollectionElements.get();
        KryptonConfig.securityMaxMapEntries = securityMaxMapEntries.get();
        KryptonConfig.securityMaxCountedElements = securityMaxCountedElements.get();
        KryptonConfig.securityMaxByteArrayBytes = securityMaxByteArrayBytes.get();
        KryptonConfig.motdCacheEnabled = motdCacheEnabled.get();
        KryptonConfig.motdCacheTtlMs = motdCacheTtlMs.get();
        KryptonConfig.securityStatusPingGuardEnabled = securityStatusPingGuardEnabled.get();
        KryptonConfig.securityStatusPingRatePerSecond = securityStatusPingRatePerSecond.get();
        KryptonConfig.securityStatusPingBurstLimit = securityStatusPingBurstLimit.get();
        KryptonConfig.securityStatusPingQuarantineSeconds = securityStatusPingQuarantineSeconds.get();
        KryptonConfig.securityStatusPingSilentDrop = securityStatusPingSilentDrop.get();
        KryptonConfig.securityLegacyQueryGuardEnabled = securityLegacyQueryGuardEnabled.get();
        KryptonConfig.securityMinProtocolVersion = securityMinProtocolVersion.get();
        KryptonConfig.securityMaxProtocolVersion = securityMaxProtocolVersion.get();
        KryptonConfig.securityMaxHandshakeAddressLength = securityMaxHandshakeAddressLength.get();
        KryptonConfig.securityAnomalyStrikeThreshold = securityAnomalyStrikeThreshold.get();
        KryptonConfig.securityWriteWatermarkLow = securityWriteWatermarkLow.get();
        KryptonConfig.securityWriteWatermarkHigh = securityWriteWatermarkHigh.get();
        KryptonConfig.securityMaxPendingWrites = securityMaxPendingWrites.get();
        KryptonConfig.securityMaxUnwritableSeconds = securityMaxUnwritableSeconds.get();
        KryptonConfig.lightOptEnabled      = lightOptEnabled.get();
        KryptonConfig.chunkOptEnabled      = chunkOptEnabled.get();
        KryptonConfig.dccEnabled           = dccEnabled.get();
        KryptonConfig.dccSizeLimit         = dccSizeLimit.get();
        KryptonConfig.dccDistance          = dccDistance.get();
        KryptonConfig.dccTimeoutSeconds    = dccTimeoutSeconds.get();
        KryptonConfig.broadcastCacheEnabled    = broadcastCacheEnabled.get();
        KryptonConfig.bundleAlwaysCompress     = bundleAlwaysCompress.get();
        KryptonConfig.bundleCompressMinBytes   = bundleCompressMinBytes.get();
        KryptonConfig.broadcastCompressedCacheEnabled = broadcastCompressedCacheEnabled.get();
        KryptonConfig.microBatchFlushEnabled   = microBatchFlushEnabled.get();
        KryptonConfig.microBatchFlushDelayMs   = microBatchFlushDelayMs.get();
        KryptonConfig.motionDeltaEnabled       = motionDeltaEnabled.get();
        KryptonConfig.motionDeltaThreshold     = motionDeltaThreshold.get();
        KryptonConfig.teleportDeltaSquared     = teleportDeltaSquared.get();
        KryptonConfig.packetCoalescingEnabled  = packetCoalescingEnabled.get();
        KryptonConfig.blockEntityDeltaEnabled  = blockEntityDeltaEnabled.get();
        KryptonConfig.proxyMode                = proxyMode.get();
        KryptonConfig.velocityForwardingSecret = velocityForwardingSecret.get();
    }
}

