package com.xinian.KryptonHybrid.shared.network.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Operator's changes to the server's network config, as text values. */
public record ServerConfigUpdatePayload(List<Change> changes) implements CustomPacketPayload {
    public static final int MAX_CHANGES = 128;

    public static final Type<ServerConfigUpdatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("krypton_hybrid", "server_config_update"));

    public static final StreamCodec<FriendlyByteBuf, ServerConfigUpdatePayload> STREAM_CODEC =
            StreamCodec.of(ServerConfigUpdatePayload::encode, ServerConfigUpdatePayload::decode);

    public record Change(String path, String value) {}

    private static void encode(FriendlyByteBuf buf, ServerConfigUpdatePayload p) {
        int n = Math.min(p.changes.size(), MAX_CHANGES);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Change c = p.changes.get(i);
            buf.writeUtf(c.path, ServerConfigSnapshotPayload.MAX_PATH);
            buf.writeUtf(c.value, ServerConfigSnapshotPayload.MAX_VALUE);
        }
    }

    private static ServerConfigUpdatePayload decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_CHANGES) {
            throw new IllegalArgumentException("Too many config changes: " + n);
        }
        List<Change> changes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            changes.add(new Change(buf.readUtf(ServerConfigSnapshotPayload.MAX_PATH),
                    buf.readUtf(ServerConfigSnapshotPayload.MAX_VALUE)));
        }
        return new ServerConfigUpdatePayload(changes);
    }

    @Override
    public Type<ServerConfigUpdatePayload> type() {
        return TYPE;
    }
}
