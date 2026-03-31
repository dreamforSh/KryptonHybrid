package com.xinian.KryptonHybrid;

import com.xinian.KryptonHybrid.command.KryptonStatsCommand;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.KryptonSharedBootstrap;
import com.xinian.KryptonHybrid.shared.network.KryptonHelloPayload;
import com.xinian.KryptonHybrid.shared.network.KryptonNetworkHandler;
import com.xinian.KryptonHybrid.shared.network.compression.ZstdUtil;
import com.xinian.KryptonHybrid.shared.network.security.ConnectionRateLimiter;
import com.xinian.KryptonHybrid.shared.network.security.SecurityMetrics;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
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

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);

        KryptonSharedBootstrap.run(FMLEnvironment.dist.isClient());
    }

    /** Tick counter for periodic security maintenance. */
    private int securityTickCounter = 0;

    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == KryptonForgeConfig.SPEC) {
            KryptonForgeConfig.INSTANCE.bake();
            ZstdUtil.reloadDictionary();
        }
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == KryptonForgeConfig.SPEC) {
            KryptonForgeConfig.INSTANCE.bake();
            ZstdUtil.reloadDictionary();
            KryptonSharedBootstrap.LOGGER.info(
                    "Krypton config reloaded - compression algorithm: {}, zstd status: {}, security: {}",
                    KryptonConfig.compressionAlgorithm,
                    ZstdUtil.statusDescription(),
                    KryptonConfig.securityEnabled ? "ENABLED" : "DISABLED");
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        KryptonStatsCommand.register(event.getDispatcher());
    }

    /**
     * Periodic security maintenance on server tick.
     * Runs cleanup tasks every ~5 seconds (100 ticks) and metrics logging
     * at the configured interval.
     */
    private void onServerTick(ServerTickEvent.Post event) {
        if (!KryptonConfig.securityEnabled) return;

        securityTickCounter++;

        // Every 100 ticks (~5 seconds): cleanup stale rate limiter windows
        if (securityTickCounter % 100 == 0) {
            ConnectionRateLimiter.evictStaleWindows();
        }

        // Periodic security metrics summary
        int intervalSec = KryptonConfig.securityMetricsIntervalSec;
        if (intervalSec > 0) {
            int intervalTicks = intervalSec * 20;
            if (securityTickCounter % intervalTicks == 0) {
                SecurityMetrics.INSTANCE.logSummaryAndReset();
            }
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
    }
}

