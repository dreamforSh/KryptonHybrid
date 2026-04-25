package com.xinian.KryptonHybrid.shared.network;

/**
 * Thread-local "currently inside a bundle" flag used by
 * {@link com.xinian.KryptonHybrid.shared.network.compression.ZstdCompressEncoder}
 * to apply forced compression to bundle sub-packets even when their individual
 * size is below the regular compression threshold.
 *
 * <h3>How it tracks bundle scope</h3>
 * <p>Vanilla's {@code PacketBundlePacker} writes a singleton
 * {@code ClientboundBundleDelimiterPacket} instance both immediately before and
 * immediately after the sub-packets of a {@code ClientboundBundlePacket}.  We
 * count delimiter occurrences modulo 2 — odd count means we are currently
 * inside a bundle.  The counter is incremented from
 * {@link com.xinian.KryptonHybrid.mixin.network.pipeline.PacketEncoderMixin}
 * when a {@code BundleDelimiterPacket} is encoded.</p>
 *
 * <p>Because the entire encoding pipeline for one packet runs synchronously on
 * a single Netty I/O thread (every {@code MessageToByteEncoder.write()} is a
 * blocking call), the thread-local flag is reliably observed by the downstream
 * {@code ZstdCompressEncoder} that runs in the same call stack.</p>
 */
public final class BundleEncodeContext {

    private static final ThreadLocal<int[]> COUNTER = ThreadLocal.withInitial(() -> new int[1]);

    private BundleEncodeContext() {}

    /** Toggle scope on every delimiter encounter. */
    public static void onDelimiter() {
        COUNTER.get()[0]++;
    }

    /** {@code true} iff we are between a start delimiter and the matching end delimiter. */
    public static boolean isInBundle() {
        return (COUNTER.get()[0] & 1) == 1;
    }

    /** Resets the counter (called when a channel disconnects to avoid drift). */
    public static void reset() {
        COUNTER.get()[0] = 0;
    }
}

