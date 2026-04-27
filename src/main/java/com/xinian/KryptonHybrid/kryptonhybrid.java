package com.xinian.KryptonHybrid;

import com.xinian.KryptonHybrid.command.KryptonStatsCommand;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.KryptonSharedBootstrap;
import com.xinian.KryptonHybrid.shared.network.KryptonHelloConfigurationTask;
import com.xinian.KryptonHybrid.shared.network.KryptonHelloPayload;
import com.xinian.KryptonHybrid.shared.network.KryptonNetworkHandler;
import com.xinian.KryptonHybrid.shared.network.control.PacketControlState;
import com.xinian.KryptonHybrid.shared.network.compression.ZstdUtil;
import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotPayload;
import com.xinian.KryptonHybrid.shared.network.security.MotdCache;
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
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(kryptonhybrid.MODID)
public class kryptonhybrid {
    public static final String MODID = "krypton_hybrid";

    public kryptonhybrid(IEventBus modEventBus, ModContainer modContainer) {
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
     * Registers the {@code krypton_hybrid:hello} payload for capability negotiation.
     * The channel is marked as optional so vanilla/non-Krypton clients can still connect.
     */
    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(MODID)
                .optional();

        registrar.configurationBidirectional(
                KryptonHelloPayload.TYPE,
                KryptonHelloPayload.STREAM_CODEC,
                new DirectionalPayloadHandler<>(
                        KryptonNetworkHandler::handleClientHello,
                        KryptonNetworkHandler::handleServerHello
                )
        );

        // Stats GUI snapshot — registered as play-phase, server → client.
        // Client-only handler is in a separate class to keep dedicated server free
        // of references to net.minecraft.client.* classes.
        if (FMLEnvironment.dist.isClient()) {
            com.xinian.KryptonHybrid.client.KryptonStatsClientPayloadRegistration.register(registrar);
        } else {
            // Dedicated server: register the type so it can be sent, but with a
            // no-op handler (handlers are never invoked server-side for playToClient).
            registrar.playToClient(
                    StatsSnapshotPayload.TYPE,
                    StatsSnapshotPayload.STREAM_CODEC,
                    (payload, ctx) -> { /* no-op on server */ }
            );
        }
    }
}

