package com.xinian.KryptonHybrid.shared.network.compression;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.KryptonSharedBootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility class for Zstd compression using the zstd-jni library.
 *
 * <p>zstd-jni provides a JNI binding to the native Zstandard library, supporting the
 * full compression level range (1\u201322) and Java 8+ runtimes (including Java 17 used
 * by Minecraft 1.19.2). The native binaries for all major platforms are bundled inside
 * the JAR and extracted automatically on first use.</p>
 *
 * <h3>Two-stage enablement model</h3>
 * <ol>
 *   <li>{@link #AVAILABLE} \u2013 set once at class-load time by attempting to initialise a
 *       {@link ZstdCompressCtx}. If the native library fails to load this flag is
 *       {@code false} and Zstd is permanently disabled for the JVM session.</li>
 *   <li>{@link KryptonConfig#compressionAlgorithm} \u2013 user-controlled algorithm selection
 *       in {@code krypton_fnp-common.toml}.</li>
 * </ol>
 * <p>Use {@link #isEnabled()} to test both conditions together.</p>
 */
public final class ZstdUtil {

    /**
     * {@code true} if the zstd-jni native library loaded and a self-test context was
     * created successfully at startup.
     */
    public static final boolean AVAILABLE;

    private static volatile byte[] dictionaryBytes;
    private static volatile long dictionaryId;
    private static volatile String dictionaryError;
    private static volatile boolean dictionaryLoadAttempted;
    private static volatile ZstdDictionaryMetadata dictionaryMetadata;
    private static volatile String dictionaryPath;
    private static volatile String dictionarySha256;

    static {
        boolean available = false;
        try {
            ZstdCompressCtx probe = new ZstdCompressCtx();
            probe.close();
            available = true;
        } catch (Throwable t) {
            KryptonSharedBootstrap.LOGGER.warn(
                    "Zstd (zstd-jni) native library init failed \u2013 Zstd compression will be unavailable. Cause: {}",
                    t.toString());
        }
        AVAILABLE = available;
    }

    private ZstdUtil() {}

    /**
     * Returns {@code true} when Zstd should be used for the current connection.
     */
    public static boolean isEnabled() {
        return AVAILABLE && KryptonConfig.compressionAlgorithm == CompressionAlgorithm.ZSTD;
    }

    /**
     * Returns a human-readable description of the current Zstd status.
     */
    public static String statusDescription() {
        if (!AVAILABLE) {
            return "unavailable (native library load failed)";
        }
        if (KryptonConfig.compressionAlgorithm != CompressionAlgorithm.ZSTD) {
            return "available but not selected";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("enabled (zstd-jni, native, level ").append(KryptonConfig.zstdLevel);
        if (KryptonConfig.zstdWorkers > 0) {
            sb.append(", workers=").append(KryptonConfig.zstdWorkers);
            if (KryptonConfig.zstdOverlapLog > 0) {
                sb.append(", overlap=").append(KryptonConfig.zstdOverlapLog);
            }
            if (KryptonConfig.zstdJobSize > 0) {
                sb.append(", jobSize=").append(KryptonConfig.zstdJobSize);
            }
        }
        if (KryptonConfig.zstdEnableLDM) {
            sb.append(", LDM=on(wlog=").append(KryptonConfig.zstdLongDistanceWindowLog).append(')');
        }
        if (KryptonConfig.zstdStrategy > 0) {
            sb.append(", strategy=").append(STRATEGY_NAMES[KryptonConfig.zstdStrategy]);
        }
        if (KryptonConfig.zstdDictEnabled) {
            sb.append(", dict=").append(dictionaryStatusDescription());
        }
        sb.append(')');
        return sb.toString();
    }

    public static synchronized void reloadDictionary() {
        dictionaryLoadAttempted = true;
        dictionaryBytes = null;
        dictionaryId = 0L;
        dictionaryError = null;
        dictionaryMetadata = null;
        dictionaryPath = null;
        dictionarySha256 = null;

        if (!AVAILABLE || !KryptonConfig.zstdDictEnabled) {
            return;
        }

        String configuredPath = KryptonConfig.zstdDictPath == null ? "" : KryptonConfig.zstdDictPath.trim();
        if (configuredPath.isEmpty()) {
            dictionaryError = "dictionary path is empty";
            return;
        }

        Path path = resolveDictionaryPath(configuredPath);
        try {
            if (!Files.exists(path)) {
                dictionaryError = "dictionary file not found: " + path;
                return;
            }

            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length < 64) {
                dictionaryError = "dictionary file is too small: " + bytes.length + " bytes";
                return;
            }

            ZstdDictionaryMetadata metadata = ZstdDictionaryMetadata.tryParse(bytes);
            byte[] plainDictionary = metadata != null ? metadata.getPlainDictionary() : bytes;
            long dictId = metadata != null ? metadata.getDictID() : Zstd.getDictIdFromDict(plainDictionary);
            validateDictionary(plainDictionary);
            dictionaryBytes = plainDictionary;
            dictionaryId = dictId;
            dictionaryMetadata = metadata;
            dictionaryPath = path.toString();
            dictionarySha256 = metadata != null ? toHex(metadata.getHash(), 12) : sha256Hex(plainDictionary, 12);

            KryptonSharedBootstrap.LOGGER.info(
                    "Loaded Zstd dictionary: {} ({} bytes, id={}, sha256={}, metadata={})",
                    path,
                    plainDictionary.length,
                    dictId,
                    dictionarySha256,
                    metadata != null ? "wrapped" : "plain");
        } catch (IOException ioe) {
            dictionaryError = "failed reading dictionary: " + ioe.getMessage();
        } catch (Throwable t) {
            dictionaryError = "failed validating dictionary: " + t.getMessage();
        }
    }

    public static String dictionaryStatusDescription() {
        if (!KryptonConfig.zstdDictEnabled) {
            return "off";
        }
        if (dictionaryBytes != null) {
            StringBuilder sb = new StringBuilder("on(id=").append(dictionaryId).append(", ").append(dictionaryBytes.length).append("B");
            if (dictionarySha256 != null) {
                sb.append(", sha256=").append(dictionarySha256);
            }
            if (dictionaryMetadata != null) {
                sb.append(", samples=").append(dictionaryMetadata.getSampleCount());
            }
            if (dictionaryPath != null) {
                sb.append(", path=").append(dictionaryPath);
            }
            sb.append(")");
            return sb.toString();
        }
        String err = dictionaryError;
        if (err == null) {
            return "pending";
        }
        return "error(" + err + ")";
    }

    private static Path resolveDictionaryPath(String configuredPath) {
        Path path = Paths.get(configuredPath);
        return path.isAbsolute() ? path.normalize() : Paths.get("").resolve(path).normalize();
    }

    private static byte[] getDictionaryOrFailIfRequired() {
        if (!KryptonConfig.zstdDictEnabled) {
            return null;
        }
        if (!dictionaryLoadAttempted) {
            reloadDictionary();
        }

        byte[] dict = dictionaryBytes;
        if (dict != null) {
            return dict;
        }

        String err = dictionaryError != null ? dictionaryError : "unknown dictionary load failure";
        if (KryptonConfig.zstdDictRequired) {
            throw new IllegalStateException("Zstd dictionary is required but unavailable: " + err);
        }

        KryptonSharedBootstrap.LOGGER.warn(
                "Zstd dictionary unavailable ({}); falling back to plain Zstd.",
                err);
        return null;
    }

    /** Human-readable names for Zstd strategies (indices 0–9). */
    private static final String[] STRATEGY_NAMES = {
            "auto", "fast", "dfast", "greedy", "lazy", "lazy2",
            "btlazy2", "btopt", "btultra", "btultra2"
    };

    /**
     * Creates a new per-channel {@link ZstdCompressCtx} configured with all current
     * compression parameters from {@link KryptonConfig}.
     *
     * <h4>Applied parameters</h4>
     * <ul>
     *   <li><strong>Level</strong> ({@link KryptonConfig#zstdLevel}): base compression
     *       level [1–22].  Must be set <em>before</em> strategy so that strategy can
     *       override the level's default match-finding algorithm.</li>
     *   <li><strong>Workers</strong> ({@link KryptonConfig#zstdWorkers}): number of
     *       native threads for parallel compression.  0 = single-threaded (Netty I/O
     *       thread only).  When &ge; 1, the native Zstd library internally manages a
     *       worker pool per {@code ZstdCompressCtx} and partitions input into jobs.</li>
     *   <li><strong>Overlap log</strong> ({@link KryptonConfig#zstdOverlapLog}): how
     *       much context each worker shares with the previous one.  Only effective
     *       when workers &ge; 1.  0 = auto (Zstd picks based on level).</li>
     *   <li><strong>Job size</strong> ({@link KryptonConfig#zstdJobSize}): minimum
     *       bytes per worker thread.  Only effective when workers &ge; 1.  0 = auto.</li>
     *   <li><strong>Long-distance matching</strong> ({@link KryptonConfig#zstdEnableLDM}
     *       + {@link KryptonConfig#zstdLongDistanceWindowLog}): searches for repeated
     *       sequences across a very large window.  Improves ratio for repetitive data
     *       at the cost of higher memory.</li>
     *   <li><strong>Strategy</strong> ({@link KryptonConfig#zstdStrategy}): selects the
     *       match-finding algorithm (fast / dfast / greedy / lazy / lazy2 / btlazy2 /
     *       btopt / btultra / btultra2).  0 = auto (determined by level).</li>
     *   <li><strong>Checksum</strong>: always disabled — Minecraft's protocol already
     *       frames packets with VarInt length prefixes; an additional checksum would
     *       add 4 bytes per packet for no benefit.</li>
     * </ul>
     *
     * @return a configured, ready-to-use compress context
     * @throws IllegalStateException if Zstd is not enabled
     */
    public static ZstdCompressCtx createCompressor() {
        if (!isEnabled()) {
            throw new IllegalStateException("Zstd is not enabled. Check krypton_fnp-common.toml.");
        }
        ZstdCompressCtx ctx = new ZstdCompressCtx();
        ctx.setLevel(KryptonConfig.zstdLevel);
        ctx.setChecksum(false);
        // Suppress the 8-byte content-size field in the Zstd frame header.
        // The Minecraft protocol already transmits uncompressed size as a VarInt prefix,
        // so the frame-level field is redundant.  Removing it saves 8 bytes per packet
        // and avoids a small amount of internal bookkeeping in libzstd.
        ctx.setContentSize(false);

        // --- Multi-threaded compression ---
        int workers = KryptonConfig.zstdWorkers;
        if (workers > 0) {
            ctx.setWorkers(workers);

            int overlapLog = KryptonConfig.zstdOverlapLog;
            if (overlapLog > 0) {
                ctx.setOverlapLog(overlapLog);
            }

            int jobSize = KryptonConfig.zstdJobSize;
            if (jobSize > 0) {
                ctx.setJobSize(jobSize);
            }
        }

        // --- Long-distance matching ---
        if (KryptonConfig.zstdEnableLDM) {
            ctx.setEnableLongDistanceMatching(Zstd.ParamSwitch.ENABLE);
            int windowLog = KryptonConfig.zstdLongDistanceWindowLog;
            if (windowLog > 0) {
                ctx.setLong(windowLog);
            }
        }

        // --- Strategy override ---
        int strategy = KryptonConfig.zstdStrategy;
        if (strategy > 0) {
            ctx.setStrategy(strategy);
        }

        byte[] dict = getDictionaryOrFailIfRequired();
        if (dict != null) {
            ctx.loadDict(dict);
            ctx.setDictID(true);
        }

        return ctx;
    }

    /**
     * Creates a new per-channel {@link ZstdDecompressCtx}.
     *
     * @return a ready-to-use decompress context
     * @throws IllegalStateException if Zstd is not enabled
     */
    public static ZstdDecompressCtx createDecompressor() {
        if (!isEnabled()) {
            throw new IllegalStateException("Zstd is not enabled. Check krypton_fnp-common.toml.");
        }
        ZstdDecompressCtx ctx = new ZstdDecompressCtx();
        byte[] dict = getDictionaryOrFailIfRequired();
        if (dict != null) {
            ctx.loadDict(dict);
        }
        return ctx;
    }

    /**
     * Returns the loaded dictionary metadata if available (only for wrapped dictionaries).
     * @return metadata or null if dictionary is not loaded or is a plain dictionary
     */
    public static ZstdDictionaryMetadata getDictionaryMetadata() {
        return dictionaryMetadata;
    }

    /**
     * Returns the currently loaded dictionary ID.
     * @return dictionary ID, or 0 if no dictionary is loaded
     */
    public static long getCurrentDictionaryId() {
        return dictionaryId;
    }

    public static String getCurrentDictionaryPath() {
        return dictionaryPath;
    }

    public static String getCurrentDictionarySha256() {
        return dictionarySha256;
    }

    private static void validateDictionary(byte[] plainDictionary) {
        ZstdCompressCtx compressCtx = null;
        ZstdDecompressCtx decompressCtx = null;
        try {
            compressCtx = new ZstdCompressCtx();
            compressCtx.loadDict(plainDictionary);
            decompressCtx = new ZstdDecompressCtx();
            decompressCtx.loadDict(plainDictionary);
        } finally {
            if (compressCtx != null) {
                compressCtx.close();
            }
            if (decompressCtx != null) {
                decompressCtx.close();
            }
        }
    }

    private static String sha256Hex(byte[] data, int maxBytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(data), maxBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }
    }

    private static String toHex(byte[] bytes, int maxBytes) {
        StringBuilder sb = new StringBuilder(Math.min(bytes.length, maxBytes) * 2);
        int count = Math.min(bytes.length, maxBytes);
        for (int i = 0; i < count; i++) {
            sb.append(Character.forDigit((bytes[i] >>> 4) & 0x0F, 16));
            sb.append(Character.forDigit(bytes[i] & 0x0F, 16));
        }
        return sb.toString();
    }

    /**
     * Returns the maximum compressed output size for an input of {@code uncompressedSize} bytes.

     * @param uncompressedSize the number of uncompressed input bytes
     * @return an upper bound on the compressed output size
     */
    public static int maxCompressedLength(int uncompressedSize) {
        int result = uncompressedSize + (uncompressedSize >>> 8);
        if (uncompressedSize < (1 << 17)) {
            result += ((1 << 17) - uncompressedSize) >>> 11;
        }
        return result;
    }
}
