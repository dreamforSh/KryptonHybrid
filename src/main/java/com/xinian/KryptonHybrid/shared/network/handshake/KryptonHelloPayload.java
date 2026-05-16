package com.xinian.KryptonHybrid.shared.network.handshake;

import com.xinian.KryptonHybrid.shared.KryptonConfig;

/** Lightweight feature flag container used by Forge 1.20.1 wire-format gates. */
public record KryptonHelloPayload(int featureFlags) {
    public static final int FEATURE_CHUNK_DATA = 1;
    public static final int FEATURE_LIGHT_DATA = 1 << 1;
    public static final int FEATURE_BLOCK_ENTITY_DELTA = 1 << 2;

    public static KryptonHelloPayload current() {
        int flags = 0;
        if (KryptonConfig.chunkOptEnabled) {
            flags |= FEATURE_CHUNK_DATA;
        }
        if (KryptonConfig.lightOptEnabled) {
            flags |= FEATURE_LIGHT_DATA;
        }
        if (KryptonConfig.blockEntityDeltaEnabled) {
            flags |= FEATURE_BLOCK_ENTITY_DELTA;
        }
        return new KryptonHelloPayload(flags);
    }

    public boolean supportsChunkData() {
        return (this.featureFlags & FEATURE_CHUNK_DATA) != 0;
    }

    public boolean supportsLightData() {
        return (this.featureFlags & FEATURE_LIGHT_DATA) != 0;
    }

    public boolean supportsBlockEntityDelta() {
        return (this.featureFlags & FEATURE_BLOCK_ENTITY_DELTA) != 0;
    }
}
