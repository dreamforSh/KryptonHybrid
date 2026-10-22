package com.xinian.KryptonHybrid;

import com.xinian.KryptonHybrid.command.KryptonStatsCommand;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.KryptonSharedBootstrap;
import com.xinian.KryptonHybrid.shared.network.compression.CompressionNegotiation;
import com.xinian.KryptonHybrid.shared.network.compression.ZstdUtil;
import com.xinian.KryptonHybrid.shared.network.control.KryptonHelloPayload;
import com.xinian.KryptonHybrid.shared.network.control.PacketControlState;
import com.xinian.KryptonHybrid.shared.network.payload.OpenDashboardPayload;
import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotPayload;
import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotRequestPayload;
import com.xinian.KryptonHybrid.shared.network.security.MotdCache;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import java.util.function.Supplier;

@Mod(KryptonHybrid.MODID)
public final class KryptonHybrid {
    public static final String MODID = "krypton_hybrid";
    // Bump whenever a Krypton wire format changes, so peers running an older build are
    // refused at login instead of misparsing packets. 2: light data carries trustEdges,
    // compression algorithm is negotiated. 3: stats snapshot carries status/legacy ping
    // drops, open-dashboard message.
    private static final String NETWORK_VERSION = "3";

    public static final SimpleChannel NETWORK = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "main"),
            () -> NETWORK_VERSION,
            NETWORK_VERSION::equals,
            NETWORK_VERSION::equals
    );

    /** Below the client's own 750 ms cooldown, so a well-behaved client is never dropped. */
    private static final long STATS_REQUEST_MIN_INTERVAL_MS = 500L;
    private static final long STATS_SNAPSHOT_REUSE_MS = 250L;
    private static final AttributeKey<Long> LAST_STATS_REQUEST =
            AttributeKey.valueOf("krypton_hybrid:last_stats_request");
    private static StatsSnapshotPayload sharedStatsSnapshot;
    private static long sharedStatsSnapshotAtMs;

    public KryptonHybrid() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, KryptonForgeConfig.SPEC);

        modEventBus.addListener(this::onConfigLoad);
        modEventBus.addListener(this::onConfigReload);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        registerPackets();
        CompressionNegotiation.register();

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

    private static void registerPackets() {
        NETWORK.messageBuilder(StatsSnapshotPayload.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StatsSnapshotPayload::encode)
                .decoder(StatsSnapshotPayload::decode)
                .consumerMainThread(KryptonHybrid::handleStatsSnapshot)
                .add();
        NETWORK.messageBuilder(KryptonHelloPayload.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(KryptonHelloPayload::encode)
                .decoder(KryptonHelloPayload::decode)
                .consumerMainThread(KryptonHybrid::handleClientHello)
                .add();
        NETWORK.messageBuilder(StatsSnapshotRequestPayload.class, 2, NetworkDirection.PLAY_TO_SERVER)
                .encoder(StatsSnapshotRequestPayload::encode)
                .decoder(StatsSnapshotRequestPayload::decode)
                .consumerMainThread(KryptonHybrid::handleStatsSnapshotRequest)
                .add();
        NETWORK.messageBuilder(OpenDashboardPayload.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenDashboardPayload::encode)
                .decoder(OpenDashboardPayload::decode)
                .consumerMainThread(KryptonHybrid::handleOpenDashboard)
                .add();
    }

    private static void handleOpenDashboard(OpenDashboardPayload payload, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        if (FMLEnvironment.dist.isClient()) {
            com.xinian.KryptonHybrid.client.KryptonStatsClientPayloadRegistration.handleOpenDashboard();
        }
        ctx.setPacketHandled(true);
    }

    public static void openDashboard(ServerPlayer player) {
        NETWORK.send(PacketDistributor.PLAYER.with(() -> player), OpenDashboardPayload.INSTANCE);
    }

    private static void handleStatsSnapshot(StatsSnapshotPayload payload, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist.isClient()) {
                com.xinian.KryptonHybrid.client.KryptonStatsClientPayloadRegistration.handleSnapshot(payload);
            }
        });
        ctx.setPacketHandled(true);
    }

    /**
     * Server-side handler for the client's hello. Stamps the connection's
     * {@link PacketControlState} with the client-reported feature flags so
     * downstream wire-format mixins can gate optimizations per-connection.
     * Vanilla clients never send this packet, so their {@code PacketControlState}
     * stays un-negotiated and the server emits vanilla wire format to them.
     */
    private static void handleClientHello(KryptonHelloPayload payload, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer sender = ctx.getSender();
        if (sender != null) {
            PacketControlState.get(sender.connection.connection.channel())
                    .markHelloNegotiated(payload.featureFlags());
        }
        ctx.setPacketHandled(true);
    }

    /**
     * Server-side handler for the client's snapshot request. Pushes a
     * {@link StatsSnapshotPayload} back to the sender. Open to any connected
     * player so the HUD auto-refresh works without op permission, so it is
     * throttled per connection on the server (the client's own cooldown can be
     * bypassed) and the snapshot is shared between requests.
     */
    private static void handleStatsSnapshotRequest(StatsSnapshotRequestPayload payload, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer sender = ctx.getSender();
        if (sender != null && allowStatsRequest(sender)) {
            sendStatsSnapshot(sender, sharedStatsSnapshot());
        }
        ctx.setPacketHandled(true);
    }

    private static boolean allowStatsRequest(ServerPlayer player) {
        Attribute<Long> last = player.connection.connection.channel().attr(LAST_STATS_REQUEST);
        long now = System.currentTimeMillis();
        Long previous = last.get();
        if (previous != null && now - previous < STATS_REQUEST_MIN_INTERVAL_MS) {
            return false;
        }
        last.set(now);
        return true;
    }

    /** Main thread only (requests are handled via consumerMainThread). */
    private static StatsSnapshotPayload sharedStatsSnapshot() {
        long now = System.currentTimeMillis();
        if (sharedStatsSnapshot == null || now - sharedStatsSnapshotAtMs >= STATS_SNAPSHOT_REUSE_MS) {
            sharedStatsSnapshot = StatsSnapshotPayload.current();
            sharedStatsSnapshotAtMs = now;
        }
        return sharedStatsSnapshot;
    }

    public static void sendStatsSnapshot(ServerPlayer player, StatsSnapshotPayload payload) {
        NETWORK.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }
}
