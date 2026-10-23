package com.xinian.KryptonHybrid.shared.network.payload;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Server-to-client request to open the stats dashboard, sent by
 * {@code /krypton stats gui}. Snapshots alone never open the dashboard, so routine
 * refreshes cannot pop it over whatever screen the player has open.
 */
public record OpenDashboardPayload() {
    public static final OpenDashboardPayload INSTANCE = new OpenDashboardPayload();

    public static void encode(OpenDashboardPayload payload, FriendlyByteBuf buf) {
    }

    public static OpenDashboardPayload decode(FriendlyByteBuf buf) {
        return INSTANCE;
    }
}
