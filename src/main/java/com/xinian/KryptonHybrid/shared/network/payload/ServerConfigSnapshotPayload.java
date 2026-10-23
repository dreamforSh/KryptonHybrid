package com.xinian.KryptonHybrid.shared.network.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server's network config as text, sent to an operator after a request or an update.
 * {@code error} is empty on success, otherwise an error code
 * ({@code no_permission}, {@code unknown_key}, {@code invalid_value}). Secret values are
 * never included: their entry carries an empty value and {@code hasValue} instead.
 */
public record ServerConfigSnapshotPayload(boolean canEdit, String error, List<Entry> entries)
        implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 1024;
    public static final int MAX_PATH = 256;
    public static final int MAX_VALUE = 4096;

    public static final Type<ServerConfigSnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("krypton_hybrid", "server_config_snapshot"));

    public static final StreamCodec<FriendlyByteBuf, ServerConfigSnapshotPayload> STREAM_CODEC =
            StreamCodec.of(ServerConfigSnapshotPayload::encode, ServerConfigSnapshotPayload::decode);

    public record Entry(String path, String value, boolean hasValue) {}

    private static void encode(FriendlyByteBuf buf, ServerConfigSnapshotPayload p) {
        buf.writeBoolean(p.canEdit);
        buf.writeUtf(p.error, 64);
        int n = Math.min(p.entries.size(), MAX_ENTRIES);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Entry e = p.entries.get(i);
            buf.writeUtf(e.path, MAX_PATH);
            buf.writeUtf(e.value, MAX_VALUE);
            buf.writeBoolean(e.hasValue);
        }
    }

    private static ServerConfigSnapshotPayload decode(FriendlyByteBuf buf) {
        boolean canEdit = buf.readBoolean();
        String error = buf.readUtf(64);
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many config entries: " + n);
        }
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            entries.add(new Entry(buf.readUtf(MAX_PATH), buf.readUtf(MAX_VALUE), buf.readBoolean()));
        }
        return new ServerConfigSnapshotPayload(canEdit, error, entries);
    }

    @Override
    public Type<ServerConfigSnapshotPayload> type() {
        return TYPE;
    }
}
