package com.xinian.KryptonHybrid.shared.network.control;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Feature-flag bitmask exchanged at PLAY-phase login so the server can decide
 * which Krypton wire-format optimizations are safe to emit on each connection.
 *
 * <p>The client sends one of these to the server immediately after entering the
 * world via {@code KryptonHybrid.NETWORK}; the server stores the received flags
 * in {@link PacketControlState#markHelloNegotiated(int)}. Connections that have
 * not negotiated are treated as vanilla — see {@link KryptonWireFormat}.</p>
 */
public record KryptonHelloPayload(int featureFlags) {
    public static final int FEATURE_CHUNK_DATA = 1;
    public static final int FEATURE_LIGHT_DATA = 1 << 1;
    public static final int FEATURE_BLOCK_ENTITY_DELTA = 1 << 2;
    /** Chunk data with byte-aligned block data ({@code ChunkDataCodec#ALIGNED_BLOCKS_MARKER}). */
    public static final int FEATURE_CHUNK_ALIGNED_BLOCKS = 1 << 3;
    /** Chunk data without heightmaps; the client computes them ({@code ChunkDataCodec#computeHeightmaps}). */
    public static final int FEATURE_CHUNK_CLIENT_HEIGHTMAPS = 1 << 4;

    public static KryptonHelloPayload current() {
        int flags = 0;
        if (KryptonConfig.chunkOptEnabled) {
            flags |= FEATURE_CHUNK_DATA | FEATURE_CHUNK_ALIGNED_BLOCKS | FEATURE_CHUNK_CLIENT_HEIGHTMAPS;
        }
        if (KryptonConfig.lightOptEnabled) {
            flags |= FEATURE_LIGHT_DATA;
        }
        if (KryptonConfig.blockEntityDeltaEnabled) {
            flags |= FEATURE_BLOCK_ENTITY_DELTA;
        }
        return new KryptonHelloPayload(flags);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.featureFlags);
    }

    public static KryptonHelloPayload decode(FriendlyByteBuf buf) {
        return new KryptonHelloPayload(buf.readVarInt());
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

    public boolean supportsChunkAlignedBlocks() {
        return (this.featureFlags & FEATURE_CHUNK_ALIGNED_BLOCKS) != 0;
    }

    public boolean supportsChunkClientHeightmaps() {
        return (this.featureFlags & FEATURE_CHUNK_CLIENT_HEIGHTMAPS) != 0;
    }
}
