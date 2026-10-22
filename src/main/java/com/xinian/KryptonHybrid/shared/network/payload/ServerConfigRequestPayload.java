package com.xinian.KryptonHybrid.shared.network.payload;

import net.minecraft.network.FriendlyByteBuf;

/** Client asks the server for its network config (answered only for operators). */
public record ServerConfigRequestPayload() {
    public static final ServerConfigRequestPayload INSTANCE = new ServerConfigRequestPayload();

    public static void encode(ServerConfigRequestPayload payload, FriendlyByteBuf buf) {
    }

    public static ServerConfigRequestPayload decode(FriendlyByteBuf buf) {
        return INSTANCE;
    }
}
