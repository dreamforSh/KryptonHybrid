package com.xinian.KryptonHybrid.shared.network;

/**
 * Per-connection capability flags negotiated between a Krypton server and client.
 *
 * <p>An instance is attached to each {@link net.minecraft.network.Connection} via
 * the {@link KryptonCapabilityHolder} duck interface. When a connection is behind
 * a Velocity proxy (or the remote endpoint does not have Krypton installed), the
 * capability flags are adjusted to ensure only compatible optimizations are active
 * on that specific connection.</p>
 *
 * <h3>Defaults</h3>
 * <p>All custom wire-format capabilities start as {@code false}. They are set to
 * {@code true} only after the {@code krypton_hybrid:hello} handshake completes and
 * both sides confirm support.</p>
 */
public final class KryptonCapabilities {

    // --- Bit masks for the hello payload ---
    public static final int BIT_ZSTD              = 1;
    public static final int BIT_CHUNK_OPT          = 1 << 1;
    public static final int BIT_LIGHT_OPT          = 1 << 2;
    public static final int BIT_BLOCK_ENTITY_DELTA = 1 << 3;

    /** Whether the remote endpoint is behind a Velocity proxy. */
    private volatile boolean behindProxy = false;

    /** Whether the remote endpoint supports Krypton's Zstd compression. */
    private volatile boolean zstdSupported = false;

    /** Whether the remote endpoint supports Krypton chunk data optimization. */
    private volatile boolean chunkOptSupported = false;

    /** Whether the remote endpoint supports Krypton light data optimization. */
    private volatile boolean lightOptSupported = false;

    /** Whether the remote endpoint supports Krypton block entity delta encoding. */
    private volatile boolean blockEntityDeltaSupported = false;

    /** Whether the hello handshake has been completed. */
    private volatile boolean negotiated = false;

    public KryptonCapabilities() {}

    // --- Proxy detection ---

    public boolean isBehindProxy() { return behindProxy; }
    public void setBehindProxy(boolean value) { this.behindProxy = value; }

    // --- Feature capabilities ---

    public boolean isZstdSupported() { return zstdSupported; }
    public void setZstdSupported(boolean value) { this.zstdSupported = value; }

    public boolean isChunkOptSupported() { return chunkOptSupported; }
    public void setChunkOptSupported(boolean value) { this.chunkOptSupported = value; }

    public boolean isLightOptSupported() { return lightOptSupported; }
    public void setLightOptSupported(boolean value) { this.lightOptSupported = value; }

    public boolean isBlockEntityDeltaSupported() { return blockEntityDeltaSupported; }
    public void setBlockEntityDeltaSupported(boolean value) { this.blockEntityDeltaSupported = value; }

    public boolean isNegotiated() { return negotiated; }
    public void setNegotiated(boolean value) { this.negotiated = value; }

    /**
     * Encodes enabled capabilities as a bitfield for the hello payload.
     */
    public int toBitfield() {
        int bits = 0;
        if (zstdSupported)              bits |= BIT_ZSTD;
        if (chunkOptSupported)          bits |= BIT_CHUNK_OPT;
        if (lightOptSupported)          bits |= BIT_LIGHT_OPT;
        if (blockEntityDeltaSupported)  bits |= BIT_BLOCK_ENTITY_DELTA;
        return bits;
    }

    /**
     * Applies the intersected (negotiated) capabilities from a received bitfield.
     */
    public void applyNegotiated(int remoteBits) {
        this.zstdSupported              = (remoteBits & BIT_ZSTD) != 0              && this.zstdSupported;
        this.chunkOptSupported          = (remoteBits & BIT_CHUNK_OPT) != 0          && this.chunkOptSupported;
        this.lightOptSupported          = (remoteBits & BIT_LIGHT_OPT) != 0          && this.lightOptSupported;
        this.blockEntityDeltaSupported  = (remoteBits & BIT_BLOCK_ENTITY_DELTA) != 0 && this.blockEntityDeltaSupported;
        this.negotiated = true;
    }

    /**
     * Sets all capabilities from a raw bitfield (used when creating local caps).
     */
    public void fromBitfield(int bits) {
        this.zstdSupported              = (bits & BIT_ZSTD) != 0;
        this.chunkOptSupported          = (bits & BIT_CHUNK_OPT) != 0;
        this.lightOptSupported          = (bits & BIT_LIGHT_OPT) != 0;
        this.blockEntityDeltaSupported  = (bits & BIT_BLOCK_ENTITY_DELTA) != 0;
    }

    @Override
    public String toString() {
        return "KryptonCapabilities{" +
                "proxy=" + behindProxy +
                ", zstd=" + zstdSupported +
                ", chunkOpt=" + chunkOptSupported +
                ", lightOpt=" + lightOptSupported +
                ", beDelta=" + blockEntityDeltaSupported +
                ", negotiated=" + negotiated +
                '}';
    }
}

