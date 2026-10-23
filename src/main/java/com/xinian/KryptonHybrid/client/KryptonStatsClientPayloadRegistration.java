package com.xinian.KryptonHybrid.client;

import com.xinian.KryptonHybrid.shared.network.payload.OpenDashboardPayload;
import com.xinian.KryptonHybrid.shared.network.payload.ServerConfigSnapshotPayload;
import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client-only payload registration for the stats GUI.
 *
 * <p>Kept in a separate class so that the dedicated server JVM never references
 * client-only classes.  This
 * class is only loaded behind a {@code FMLEnvironment.dist.isClient()} guard
 * inside {@code KryptonHybrid.onRegisterPayloads}.</p>
 */
public final class KryptonStatsClientPayloadRegistration {

    private KryptonStatsClientPayloadRegistration() {}

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(
                StatsSnapshotPayload.TYPE,
                StatsSnapshotPayload.STREAM_CODEC,
                KryptonStatsClientPayloadRegistration::handleSnapshot
        );
        registrar.playToClient(
                OpenDashboardPayload.TYPE,
                OpenDashboardPayload.STREAM_CODEC,
                KryptonStatsClientPayloadRegistration::handleOpenDashboard
        );
        registrar.playToClient(
                ServerConfigSnapshotPayload.TYPE,
                ServerConfigSnapshotPayload.STREAM_CODEC,
                KryptonStatsClientPayloadRegistration::handleServerConfig
        );
    }

    private static void handleSnapshot(StatsSnapshotPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> KryptonStatsClientController.receiveSnapshot(payload));
    }

    private static void handleOpenDashboard(OpenDashboardPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(KryptonStatsClientController::openOrRequestLatest);
    }

    private static void handleServerConfig(ServerConfigSnapshotPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> RemoteServerConfig.accept(payload));
    }
}

