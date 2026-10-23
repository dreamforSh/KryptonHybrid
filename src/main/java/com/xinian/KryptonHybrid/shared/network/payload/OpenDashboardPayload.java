package com.xinian.KryptonHybrid.shared.network.payload;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-to-client request to open the stats dashboard, sent by
 * {@code /krypton stats gui}. Snapshots alone never open the dashboard, so routine
 * refreshes cannot pop it over whatever screen the player has open.
 */
public record OpenDashboardPayload() implements CustomPacketPayload {
    public static final OpenDashboardPayload INSTANCE = new OpenDashboardPayload();

    public static final Type<OpenDashboardPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("krypton_hybrid", "open_dashboard"));

    public static final StreamCodec<ByteBuf, OpenDashboardPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<OpenDashboardPayload> type() {
        return TYPE;
    }
}
