package com.xinian.KryptonHybrid.shared.network.payload;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client asks the server for its network config (answered only for operators). */
public record ServerConfigRequestPayload() implements CustomPacketPayload {
    public static final ServerConfigRequestPayload INSTANCE = new ServerConfigRequestPayload();

    public static final Type<ServerConfigRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("krypton_hybrid", "server_config_request"));

    public static final StreamCodec<ByteBuf, ServerConfigRequestPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<ServerConfigRequestPayload> type() {
        return TYPE;
    }
}
