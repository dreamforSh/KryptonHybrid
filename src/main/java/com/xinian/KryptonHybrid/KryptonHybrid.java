package com.xinian.KryptonHybrid;

import com.xinian.KryptonHybrid.command.KryptonStatsCommand;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.KryptonSharedBootstrap;
import com.xinian.KryptonHybrid.shared.network.handshake.KryptonHelloConfigurationTask;
import com.xinian.KryptonHybrid.shared.network.handshake.KryptonHelloPayload;
import com.xinian.KryptonHybrid.shared.network.handshake.KryptonNetworkHandler;
import com.xinian.KryptonHybrid.shared.network.control.PacketControlState;
import com.xinian.KryptonHybrid.shared.network.compression.ZstdUtil;
import com.xinian.KryptonHybrid.shared.network.config.ServerConfigSync;
import com.xinian.KryptonHybrid.shared.network.payload.OpenDashboardPayload;
import com.xinian.KryptonHybrid.shared.network.payload.ServerConfigRequestPayload;
import com.xinian.KryptonHybrid.shared.network.payload.ServerConfigSnapshotPayload;
import com.xinian.KryptonHybrid.shared.network.payload.ServerConfigUpdatePayload;
import com.xinian.KryptonHybrid.shared.network.payload.StatsRequestPayload;
import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotPayload;
import com.xinian.KryptonHybrid.shared.network.security.MotdCache;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(KryptonHybrid.MODID)
public final class KryptonHybrid {
    public static final String MODID = "krypton_hybrid";
    // Bump whenever a Krypton payload format changes, so peers running an older build
    // are refused during configuration instead of misparsing packets. 2: stats snapshot
    // reports flush consolidation and status/legacy ping drops; open-dashboard and
    // operator server-config payloads.
    private static final String NETWORK_VERSION = "2";

    /** Below the client's own 750 ms cooldown, so a well-behaved client is never dropped. */
    private static final long STATS_REQUEST_MIN_INTERVAL_MS = 500L;
    private static final long STATS_SNAPSHOT_REUSE_MS = 250L;
    private static final AttributeKey<Long> LAST_STATS_REQUEST =
            AttributeKey.valueOf("krypton_hybrid:last_stats_request");
    private static StatsSnapshotPayload sharedStatsSnapshot;
    private static long sharedStatsSnapshotAtMs;

    public KryptonHybrid(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, KryptonForgeConfig.SPEC);

        modEventBus.addListener(this::onConfigLoad);
        modEventBus.addListener(this::onConfigReload);
        modEventBus.addListener(this::onRegisterPayloads);
        modEventBus.addListener(this::onRegisterConfigurationTasks);

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        if (FMLEnvironment.dist.isClient()) {
            com.xinian.KryptonHybrid.client.KryptonStatsClientController.init(modEventBus);
        }

        KryptonSharedBootstrap.run(FMLEnvironment.dist.isClient());
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == KryptonForgeConfig.SPEC) {
            KryptonForgeConfig.INSTANCE.bake();
            MotdCache.invalidate();
            ZstdUtil.reloadDictionary();
        }
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == KryptonForgeConfig.SPEC) {
            KryptonForgeConfig.INSTANCE.bake();
            MotdCache.invalidate();
            ZstdUtil.reloadDictionary();
            KryptonSharedBootstrap.LOGGER.info(
                    "Krypton config reloaded - compression algorithm: {}, zstd status: {}",
                    KryptonConfig.compressionAlgorithm,
                    ZstdUtil.statusDescription());
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        KryptonStatsCommand.register(event.getDispatcher());
    }

    private void onRegisterConfigurationTasks(RegisterConfigurationTasksEvent event) {
        if (event.getListener().hasChannel(KryptonHelloPayload.TYPE)) {
            PacketControlState.get(event.getListener().getConnection().channel()).markHelloAvailable();
            event.register(KryptonHelloConfigurationTask.INSTANCE);
        }
    }

    /**
     * Registers the {@code krypton_hybrid:hello} payload for capability negotiation and
     * the stats/config payloads. The channels are optional so vanilla/non-Krypton
     * clients can still connect.
     */
    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(MODID)
                .versioned(NETWORK_VERSION)
                .optional();

        registrar.configurationBidirectional(
                KryptonHelloPayload.TYPE,
                KryptonHelloPayload.STREAM_CODEC,
                new DirectionalPayloadHandler<>(
                        KryptonNetworkHandler::handleClientHello,
                        KryptonNetworkHandler::handleServerHello
                )
        );

        // Server-to-client payloads. Client-only handlers are in a separate class to
        // keep the dedicated server free of references to net.minecraft.client.* classes.
        if (FMLEnvironment.dist.isClient()) {
            com.xinian.KryptonHybrid.client.KryptonStatsClientPayloadRegistration.register(registrar);
        } else {
            // Dedicated server: register the types so they can be sent, with no-op
            // handlers (handlers are never invoked server-side for playToClient).
            registrar.playToClient(StatsSnapshotPayload.TYPE, StatsSnapshotPayload.STREAM_CODEC, (payload, ctx) -> {});
            registrar.playToClient(OpenDashboardPayload.TYPE, OpenDashboardPayload.STREAM_CODEC, (payload, ctx) -> {});
            registrar.playToClient(ServerConfigSnapshotPayload.TYPE, ServerConfigSnapshotPayload.STREAM_CODEC, (payload, ctx) -> {});
        }

        // Stats GUI/HUD refresh, client to server. Decouples the dashboard's refresh
        // from /krypton stats gui so the GUI never has to round-trip through the
        // chat/command pipeline.
        registrar.playToServer(StatsRequestPayload.TYPE, StatsRequestPayload.STREAM_CODEC,
                KryptonHybrid::handleStatsRequest);
        registrar.playToServer(ServerConfigRequestPayload.TYPE, ServerConfigRequestPayload.STREAM_CODEC,
                KryptonHybrid::handleServerConfigRequest);
        registrar.playToServer(ServerConfigUpdatePayload.TYPE, ServerConfigUpdatePayload.STREAM_CODEC,
                KryptonHybrid::handleServerConfigUpdate);
    }

    /**
     * Pushes a {@link StatsSnapshotPayload} back to the sender. Open to any connected
     * player so the HUD auto-refresh works without op permission, so it is throttled
     * per connection on the server (the client's own cooldown can be bypassed) and the
     * snapshot is shared between requests.
     */
    private static void handleStatsRequest(StatsRequestPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player && allowStatsRequest(player)) {
                PacketDistributor.sendToPlayer(player, sharedStatsSnapshot());
            }
        });
    }

    /** Operators read the server's network config; everyone else gets a permission error. */
    private static void handleServerConfigRequest(ServerConfigRequestPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                PacketDistributor.sendToPlayer(player, ServerConfigSync.snapshot(player, null));
            }
        });
    }

    /** Applies an operator's changes (permission re-checked) and answers with the result. */
    private static void handleServerConfigUpdate(ServerConfigUpdatePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                String error = ServerConfigSync.apply(player, payload.changes());
                PacketDistributor.sendToPlayer(player, ServerConfigSync.snapshot(player, error));
            }
        });
    }

    private static boolean allowStatsRequest(ServerPlayer player) {
        Attribute<Long> last = player.connection.getConnection().channel().attr(LAST_STATS_REQUEST);
        long now = System.currentTimeMillis();
        Long previous = last.get();
        if (previous != null && now - previous < STATS_REQUEST_MIN_INTERVAL_MS) {
            return false;
        }
        last.set(now);
        return true;
    }

    /** Main thread only (requests are handled via enqueueWork). */
    private static StatsSnapshotPayload sharedStatsSnapshot() {
        long now = System.currentTimeMillis();
        if (sharedStatsSnapshot == null || now - sharedStatsSnapshotAtMs >= STATS_SNAPSHOT_REUSE_MS) {
            sharedStatsSnapshot = StatsSnapshotPayload.current();
            sharedStatsSnapshotAtMs = now;
        }
        return sharedStatsSnapshot;
    }

    public static void openDashboard(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, OpenDashboardPayload.INSTANCE);
    }
}
