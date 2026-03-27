package com.xinian.KryptonHybrid.shared.network.compression;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.KryptonSharedBootstrap;

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
        return KryptonConfig.compressionAlgorithm == CompressionAlgorithm.ZSTD
                ? "enabled (zstd-jni, native, level " + KryptonConfig.zstdLevel + ")"
                : "available but not selected";
    }

    /**
     * Creates a new per-channel {@link ZstdCompressCtx} configured with the current
     * compression level from {@link KryptonConfig#zstdLevel}.
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
        return new ZstdDecompressCtx();
    }

    /**
     * Returns the maximum compressed output size for an input of {@code uncompressedSize} bytes.
     *
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

