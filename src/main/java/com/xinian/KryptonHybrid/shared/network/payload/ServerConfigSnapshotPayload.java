package com.xinian.KryptonHybrid.shared.network.payload;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Server's network config as text, sent to an operator after a request or an update.
 * {@code error} is empty on success, otherwise an error code
 * ({@code no_permission}, {@code unknown_key}, {@code invalid_value}). Secret values are
 * never included: their entry carries an empty value and {@code hasValue} instead.
 */
public record ServerConfigSnapshotPayload(boolean canEdit, String error, List<Entry> entries) {
    public static final int MAX_ENTRIES = 1024;
    public static final int MAX_PATH = 256;
    public static final int MAX_VALUE = 4096;

    public record Entry(String path, String value, boolean hasValue) {}

    public static void encode(ServerConfigSnapshotPayload p, FriendlyByteBuf buf) {
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

    public static ServerConfigSnapshotPayload decode(FriendlyByteBuf buf) {
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
}
