package com.xinian.KryptonHybrid.shared.network;

import io.netty.buffer.ByteBuf;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Lightweight capability-negotiation marker payload used during configuration.
 */
public record KryptonHelloPayload(int featureFlags) implements CustomPacketPayload {
    public static final Type<KryptonHelloPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("krypton_hybrid", "hello"));

    public static final int FEATURE_CHUNK_DATA = 1;
    public static final int FEATURE_LIGHT_DATA = 1 << 1;
    public static final int FEATURE_BLOCK_ENTITY_DELTA = 1 << 2;

    public static final StreamCodec<ByteBuf, KryptonHelloPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public KryptonHelloPayload decode(ByteBuf buf) {
            return new KryptonHelloPayload(buf.readUnsignedByte());
        }

        @Override
        public void encode(ByteBuf buf, KryptonHelloPayload payload) {
            buf.writeByte(payload.featureFlags() & 0xFF);
        }
    };

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

    @Override
    public Type<KryptonHelloPayload> type() {
        return TYPE;
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

