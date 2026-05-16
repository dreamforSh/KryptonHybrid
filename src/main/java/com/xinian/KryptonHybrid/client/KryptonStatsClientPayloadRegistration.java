package com.xinian.KryptonHybrid.client;

import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotPayload;

/** Client-only receiver for Forge 1.20.1 stats GUI packets. */
public final class KryptonStatsClientPayloadRegistration {
    private KryptonStatsClientPayloadRegistration() {}

    public static void handleSnapshot(StatsSnapshotPayload payload) {
        KryptonStatsClientController.receiveSnapshot(payload);
    }
}
