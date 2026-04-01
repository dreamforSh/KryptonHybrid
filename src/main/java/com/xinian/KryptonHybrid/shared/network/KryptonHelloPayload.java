package com.xinian.KryptonHybrid.shared.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Lightweight capability-negotiation marker payload used during configuration.
 */
public final class KryptonHelloPayload implements CustomPacketPayload {
    public static final Type<KryptonHelloPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("krypton_hybrid", "hello"));
    public static final KryptonHelloPayload INSTANCE = new KryptonHelloPayload();
    public static final StreamCodec<ByteBuf, KryptonHelloPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    private KryptonHelloPayload() {}

    @Override
    public Type<KryptonHelloPayload> type() {
        return TYPE;
    }
}

