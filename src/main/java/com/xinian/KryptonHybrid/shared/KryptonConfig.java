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

    private KryptonConfig() {}
}

