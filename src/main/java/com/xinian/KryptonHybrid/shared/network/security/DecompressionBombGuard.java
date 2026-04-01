package com.xinian.KryptonHybrid.shared.network.security;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stateless guard that validates decompression ratios to prevent zip/zstd bombs.
 *
 * <p>A decompression bomb exploits the asymmetry between compressed and
 * uncompressed sizes: a tiny compressed payload can decompress into gigabytes,
 * causing OOM or extreme CPU usage.  This guard rejects packets whose
 * compression ratio exceeds a configurable threshold <strong>before</strong>
 * decompression occurs (the claimed uncompressed size is in the packet header).</p>
 *
 * <h3>Checks performed</h3>
 * <ol>
 *   <li><strong>Absolute size cap:</strong> claimed uncompressed size must not
 *       exceed {@link KryptonConfig#securityMaxDecompressedBytes}.</li>
 *   <li><strong>Ratio check:</strong>
 *       {@code claimedUncompressed / compressedSize ≤ maxCompressionRatio}.
 *       Default ratio limit: 100:1.</li>
 * </ol>
 *
 * <p>Both thresholds are configurable via the {@code [security]} config section.</p>
 */
public final class DecompressionBombGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("KryptonSecurity");

    private DecompressionBombGuard() {}

    /**
     * Validates the compression ratio before decompression.
     *
     * @param compressedSize          number of compressed bytes on the wire
     * @param claimedUncompressedSize uncompressed size from the packet header
     * @return {@code true} if the packet is safe to decompress
     * @throws DecompressionBombException if the ratio or size limit is exceeded
     */
    public static boolean validate(int compressedSize, int claimedUncompressedSize)
            throws DecompressionBombException {
        return validate(compressedSize, claimedUncompressedSize, null);
    }

    /**
     * Validates decompression limits; channel context is accepted for API compatibility.
     */
    public static boolean validate(int compressedSize, int claimedUncompressedSize, Channel channel)
            throws DecompressionBombException {

        if (!KryptonConfig.securityEnabled) return true;


        int maxBytes = KryptonConfig.securityMaxDecompressedBytes;
        if (claimedUncompressedSize > maxBytes) {
            SecurityMetrics.INSTANCE.recordDecompressionBomb();
            String msg = String.format(
                    "Decompression bomb: claimed size %d exceeds max %d bytes",
                    claimedUncompressedSize, maxBytes);
            LOGGER.warn("[Krypton Security] {}", msg);
            throw new DecompressionBombException(msg);
        }


        if (compressedSize > 0
                && compressedSize >= KryptonConfig.securityMinCompressedBytesForRatioCheck) {
            int maxRatio = KryptonConfig.securityMaxCompressionRatio;
            if ((long) claimedUncompressedSize > (long) compressedSize * (long) maxRatio) {
                SecurityMetrics.INSTANCE.recordDecompressionBomb();
                String msg = String.format(
                        "Decompression bomb: claimed=%d exceeds ratio limit %d:1 for compressed=%d",
                        claimedUncompressedSize, maxRatio, compressedSize);
                LOGGER.warn("[Krypton Security] {}", msg);
                throw new DecompressionBombException(msg);
            }
        }

        return true;
    }

    /**
     * Exception thrown when a decompression bomb is detected.
     * Extends RuntimeException so it can propagate through Netty pipeline
     * exception handlers and disconnect the client.
     */
    public static class DecompressionBombException extends RuntimeException {
        public DecompressionBombException(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}

