package com.xinian.KryptonHybrid.shared.network.compression;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.KryptonSharedBootstrap;
import io.netty.buffer.ByteBuf;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Utility class for Zstd compression using the zstd-jni library.
 *
 * <p>zstd-jni provides a JNI binding to the native Zstandard library, supporting the
 * full compression level range (1\u201322) and Java 8+ runtimes (including Java 17 used
 * by Minecraft 1.20.1). The native binaries for all major platforms are bundled inside
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
     * Returns {@code true} when this side is willing to use Zstd. Whether a given
     * connection actually uses it is decided by {@link CompressionNegotiation}.
     */
    public static boolean isEnabled() {
        return AVAILABLE && KryptonConfig.compressionAlgorithm == CompressionAlgorithm.ZSTD;
    }

    /**
     * Returns the dictionary id both peers must agree on before Zstd can be negotiated:
     * the loaded dictionary's id, {@code 0} for plain Zstd, or {@code -1} when this side
     * cannot use Zstd at all (disabled, native library missing, or a required dictionary
     * failed to load).
     */
    public static synchronized long negotiableDictionaryId() {
        if (!isEnabled()) {
            return -1L;
        }
        if (!KryptonConfig.zstdDictEnabled) {
            return 0L;
        }
        if (!dictionaryLoadAttempted) {
            loadDictionary();
        }
        if (dictionaryBytes != null) {
            return dictionaryId;
        }
        return KryptonConfig.zstdDictRequired ? -1L : 0L;
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

    /**
     * (Re)loads the configured dictionary. Logins from now on offer the resulting
     * compression token ({@link CompressionNegotiation#localToken()}). Call after every
     * config change.
     */
    public static void reloadDictionary() {
        loadDictionary();
    }

    private static synchronized void loadDictionary() {
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
            failDictionary("dictionary path is empty");
            return;
        }

        Path path = resolveDictionaryPath(configuredPath);
        try {
            if (!Files.exists(path)) {
                failDictionary("dictionary file not found: " + path);
                return;
            }

            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length < 64) {
                failDictionary("dictionary file is too small: " + bytes.length + " bytes");
                return;
            }

            ZstdDictionaryMetadata metadata = ZstdDictionaryMetadata.tryParse(bytes);
            byte[] plainDictionary = metadata != null ? metadata.getPlainDictionary() : bytes;
            long dictId = Zstd.getDictIdFromDict(plainDictionary);
            // Peers agree on a dictionary by its ID, and ID 0 is what "no dictionary"
            // advertises: a raw-content dictionary would be mistaken for none.
            if (dictId == 0L) {
                failDictionary("dictionary has no ID (raw content); train one with trainZstdDict: " + path);
                return;
            }
            if (metadata != null && metadata.getDictID() != dictId) {
                failDictionary("metadata says dictionary ID " + metadata.getDictID()
                        + " but the dictionary is " + dictId + ": " + path);
                return;
            }
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
            failDictionary("failed reading dictionary: " + ioe.getMessage());
        } catch (Throwable t) {
            failDictionary("failed validating dictionary: " + t.getMessage());
        }
    }

    /** Records why the dictionary is unavailable and says what connections will do instead. */
    private static void failDictionary(String error) {
        dictionaryError = error;
        KryptonSharedBootstrap.LOGGER.error("Zstd dictionary not loaded: {}. {}", error,
                KryptonConfig.zstdDictRequired
                        ? "dict_required is on, so Zstd is not offered and connections use zlib."
                        : "Connections use Zstd without a dictionary.");
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

    /** Relative paths start at the game directory. */
    private static Path resolveDictionaryPath(String configuredPath) {
        Path path = Paths.get(configuredPath);
        return path.isAbsolute() ? path.normalize() : FMLPaths.GAMEDIR.get().resolve(path).normalize();
    }

    private static byte[] getDictionaryOrFailIfRequired() {
        if (!KryptonConfig.zstdDictEnabled) {
            return null;
        }
        if (!dictionaryLoadAttempted) {
            loadDictionary();
        }

        byte[] dict = dictionaryBytes;
        if (dict != null) {
            return dict;
        }

        // The failure was logged when loading; a required dictionary also keeps Zstd from
        // being negotiated, so this only throws if the configuration changed mid-login.
        if (KryptonConfig.zstdDictRequired) {
            String err = dictionaryError != null ? dictionaryError : "unknown dictionary load failure";
            throw new IllegalStateException("Zstd dictionary is required but unavailable: " + err);
        }
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

        // The dictionary is agreed on during login, so its ID in a frame header would be
        // redundant (4 bytes per packet).
        ctx.setDictID(false);

        return ctx;
    }

    /**
     * Little-endian Zstd frame magic. On a dictionary connection, frames compressed with
     * the dictionary are written without it (magicless) and frames compressed without it
     * keep it, so the decoder can tell them apart: a magicless frame can never start with
     * these bytes, because its first byte would have the reserved header bit set.
     */
    public static final int FRAME_MAGIC = 0xFD2FB528;

    /** Whether the frame at {@code buf}'s reader index is a standard frame (no dictionary). */
    public static boolean isStandardFrame(ByteBuf buf) {
        return buf.readableBytes() >= 4 && buf.getIntLE(buf.readerIndex()) == FRAME_MAGIC;
    }

    /** A context for dictionary frames: magicless; the encoder loads the dictionary per level. */
    public static ZstdCompressCtx createDictionaryCompressor() {
        ZstdCompressCtx ctx = createCompressor();
        ctx.setMagicless(true);
        return ctx;
    }

    /** Dictionary new connections compress with ({@code null} for none). */
    public static byte[] compressionDictionary() {
        return getDictionaryOrFailIfRequired();
    }

    /** Prepared dictionaries per raw dictionary; dropped once nothing uses the raw bytes. */
    private static final Map<byte[], ZstdDictCompress[]> COMPRESS_DICTS = new WeakHashMap<>();
    private static final Map<byte[], ZstdDictDecompress> DECOMPRESS_DICTS = new WeakHashMap<>();

    /**
     * {@code dict} prepared for compression at {@code level}, shared by all connections
     * (a context keeps the one it loaded alive). libzstd builds a raw dictionary's tables
     * for whatever level is active when it is first used and ignores later level changes,
     * so switching levels means loading the dictionary prepared for the new level.
     */
    public static synchronized ZstdDictCompress compressDictionary(byte[] dict, int level) {
        ZstdDictCompress[] byLevel = COMPRESS_DICTS.computeIfAbsent(dict, k -> new ZstdDictCompress[23]);
        ZstdDictCompress prepared = byLevel[level];
        if (prepared == null) {
            prepared = new ZstdDictCompress(dict, level);
            byLevel[level] = prepared;
        }
        return prepared;
    }

    /** {@code dict} prepared for decompression, shared by all connections. */
    private static synchronized ZstdDictDecompress decompressDictionary(byte[] dict) {
        return DECOMPRESS_DICTS.computeIfAbsent(dict, ZstdDictDecompress::new);
    }

    /**
     * Creates a new per-channel {@link ZstdDecompressCtx} for standard frames (no dictionary).
     *
     * @return a ready-to-use decompress context
     * @throws IllegalStateException if Zstd is not enabled
     */
    public static ZstdDecompressCtx createDecompressor() {
        if (!isEnabled()) {
            throw new IllegalStateException("Zstd is not enabled. Check krypton_fnp-common.toml.");
        }
        return new ZstdDecompressCtx();
    }

    /** A per-channel context for magicless dictionary frames compressed with {@code dict}. */
    public static ZstdDecompressCtx createDictionaryDecompressor(byte[] dict) {
        ZstdDecompressCtx ctx = createDecompressor();
        ctx.setMagicless(true);
        ctx.loadDict(decompressDictionary(dict));
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
