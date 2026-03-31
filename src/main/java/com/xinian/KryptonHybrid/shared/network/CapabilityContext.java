package com.xinian.KryptonHybrid.shared.network;

/**
 * Thread-local holder for the current connection's {@link KryptonCapabilities}.
 *
 * <p>Set by the {@code PacketEncoder} mixin before encoding a packet and cleared
 * afterwards. Write-side mixins (chunk data, light data, block entity delta) read
 * this to decide whether to emit Krypton's custom wire format or fall back to
 * vanilla encoding.</p>
 *
 * <p>This approach avoids passing capabilities through every codec call site,
 * and is safe because packet encoding happens on the Netty I/O thread with no
 * concurrent encoding for the same connection.</p>
 */
public final class CapabilityContext {

    private static final ThreadLocal<KryptonCapabilities> CURRENT = new ThreadLocal<>();

    private CapabilityContext() {}

    /**
     * Sets the current capabilities for the encoding thread.
     */
    public static void set(KryptonCapabilities caps) {
        CURRENT.set(caps);
    }

    /**
     * Clears the current capabilities after encoding.
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Returns the current capabilities, or {@code null} if not set.
     */
    public static KryptonCapabilities get() {
        return CURRENT.get();
    }

    /**
     * Returns whether the current connection supports Krypton chunk data optimization.
     * Falls back to {@code true} if no capabilities are set (direct connection assumed).
     */
    public static boolean chunkOptAllowed() {
        KryptonCapabilities caps = CURRENT.get();
        return caps == null || caps.isChunkOptSupported();
    }

    /**
     * Returns whether the current connection supports Krypton light data optimization.
     * Falls back to {@code true} if no capabilities are set (direct connection assumed).
     */
    public static boolean lightOptAllowed() {
        KryptonCapabilities caps = CURRENT.get();
        return caps == null || caps.isLightOptSupported();
    }

    /**
     * Returns whether the current connection supports Krypton block entity delta encoding.
     * Falls back to {@code true} if no capabilities are set (direct connection assumed).
     */
    public static boolean blockEntityDeltaAllowed() {
        KryptonCapabilities caps = CURRENT.get();
        return caps == null || caps.isBlockEntityDeltaSupported();
    }
}

