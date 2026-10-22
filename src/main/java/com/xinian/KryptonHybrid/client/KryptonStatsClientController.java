package com.xinian.KryptonHybrid.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.xinian.KryptonHybrid.client.overlay.KryptonHudOverlay;
import com.xinian.KryptonHybrid.client.screen.KryptonStatsScreen;
import com.xinian.KryptonHybrid.client.ui.GeometryCache;
import com.xinian.KryptonHybrid.KryptonHybrid;
import com.xinian.KryptonHybrid.shared.network.compression.ZstdCompressEncoder;
import com.xinian.KryptonHybrid.shared.network.control.KryptonHelloPayload;
import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotPayload;
import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotRequestPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side controller for the stats dashboard.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Registers Controls-menu key bindings (open dashboard, toggle HUD)</li>
 *   <li>Caches the latest server-pushed {@link StatsSnapshotPayload} and feeds
 *       {@link KryptonStatsHistory}</li>
 *   <li>Opens the dashboard immediately from cache, then requests a fresh snapshot</li>
 *   <li>Registers the in-game HUD overlay layer and the client config</li>
 * </ul>
 */
public final class KryptonStatsClientController {

    private static final long SNAPSHOT_REQUEST_COOLDOWN_MS = 750L;

    private static final KeyMapping OPEN_STATS_KEY = new KeyMapping(
            "key.krypton_hybrid.open_stats",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.krypton_hybrid"
    );

    private static final KeyMapping TOGGLE_HUD_KEY = new KeyMapping(
            "key.krypton_hybrid.toggle_hud",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            "key.categories.krypton_hybrid"
    );

    /** Which compression the local connection is using, for the dashboard header. */
    public enum Codec { ZSTD, ZLIB, NONE, LOCAL }

    private static StatsSnapshotPayload latestSnapshot;
    private static long latestSnapshotReceivedAtMs;
    private static long lastRequestAtMs;
    private static long lastRequestSentAtMs;
    private static long lastRoundTripMs;
    private static long snapshotRequestCount;
    private static long snapshotReceiveCount;
    /** Set when the player asked for the dashboard before any snapshot arrived. */
    private static boolean pendingOpen;

    private KryptonStatsClientController() {}

    public static void init(IEventBus modEventBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, KryptonClientConfig.SPEC);
        modEventBus.addListener(KryptonStatsClientController::onConfigLoad);
        modEventBus.addListener(KryptonStatsClientController::onConfigReload);
        modEventBus.addListener(KryptonStatsClientController::onRegisterKeyMappings);
        modEventBus.addListener(KryptonStatsClientController::onRegisterGuiLayers);
        MinecraftForge.EVENT_BUS.addListener(KryptonStatsClientController::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(KryptonStatsClientController::onClientLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(KryptonStatsClientController::onClientLoggedOut);
    }

    private static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == KryptonClientConfig.SPEC) {
            KryptonClientConfig.apply();
        }
    }

    private static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == KryptonClientConfig.SPEC) {
            KryptonClientConfig.apply();
        }
    }

    /**
     * Sends our feature-flag hello to the server right after entering the world.
     * Required for the server-side wire-format gates in {@code KryptonWireFormat}
     * — connections that don't receive a hello stay on vanilla format. See
     * {@code KryptonHybrid#handleClientHello}.
     */
    private static void onClientLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        KryptonHybrid.NETWORK.sendToServer(KryptonHelloPayload.current());
    }

    /** Stats belong to the server we left; never show them for the next one. */
    private static void onClientLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        latestSnapshot = null;
        latestSnapshotReceivedAtMs = 0L;
        lastRequestAtMs = 0L;
        lastRequestSentAtMs = 0L;
        lastRoundTripMs = 0L;
        snapshotRequestCount = 0L;
        snapshotReceiveCount = 0L;
        pendingOpen = false;
        KryptonStatsHistory.clear();
        GeometryCache.release("");
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_STATS_KEY);
        event.register(TOGGLE_HUD_KEY);
    }

    private static void onRegisterGuiLayers(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll(
                "stats_hud",
                KryptonHudOverlay.INSTANCE
        );
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;

        while (OPEN_STATS_KEY.consumeClick()) {
            openOrRequestLatest();
        }
        while (TOGGLE_HUD_KEY.consumeClick()) {
            KryptonHudOverlay.toggleVisible();
            KryptonClientConfig.save();
            minecraft.gui.setOverlayMessage(
                    Component.translatable(KryptonHudOverlay.isVisible()
                            ? "gui.krypton_hybrid.hud.on"
                            : "gui.krypton_hybrid.hud.off"),
                    false);
        }
    }

    public static void receiveSnapshot(StatsSnapshotPayload payload) {
        long now = System.currentTimeMillis();
        latestSnapshot = payload;
        latestSnapshotReceivedAtMs = now;
        snapshotReceiveCount++;
        if (lastRequestSentAtMs > 0L && now >= lastRequestSentAtMs) {
            lastRoundTripMs = now - lastRequestSentAtMs;
        }
        KryptonStatsHistory.record(payload, now);

        Minecraft minecraft = Minecraft.getInstance();
        if (pendingOpen) {
            pendingOpen = false;
            if (minecraft.screen == null) {
                open();
            }
        }
    }

    public static KeyMapping openStatsKey() { return OPEN_STATS_KEY; }
    public static KeyMapping toggleHudKey() { return TOGGLE_HUD_KEY; }
    public static StatsSnapshotPayload latestSnapshot() { return latestSnapshot; }
    public static long snapshotRequestCount() { return snapshotRequestCount; }
    public static long snapshotReceiveCount() { return snapshotReceiveCount; }
    public static long lastRoundTripMs() { return lastRoundTripMs; }

    public static long latestSnapshotAgeMs() {
        return latestSnapshotReceivedAtMs == 0L
                ? Long.MAX_VALUE
                : Math.max(0L, System.currentTimeMillis() - latestSnapshotReceivedAtMs);
    }

    /** Compression actually installed on this client's connection (after negotiation). */
    public static Codec currentCodec() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener == null) {
            return Codec.NONE;
        }
        Connection connection = listener.getConnection();
        if (connection.isMemoryConnection()) {
            return Codec.LOCAL;
        }
        Object compressor = connection.channel().pipeline().get("compress");
        if (compressor == null) {
            return Codec.NONE;
        }
        return compressor instanceof ZstdCompressEncoder ? Codec.ZSTD : Codec.ZLIB;
    }

    public static void openOrRequestLatest() {
        Minecraft minecraft = Minecraft.getInstance();
        if (latestSnapshot != null) {
            open();
        } else {
            pendingOpen = true;
            minecraft.gui.setOverlayMessage(Component.translatable("gui.krypton_hybrid.hint.requesting"), false);
        }
        requestFreshSnapshot();
    }

    public static void requestFreshSnapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;

        long now = System.currentTimeMillis();
        if (now - lastRequestAtMs < SNAPSHOT_REQUEST_COOLDOWN_MS) return;

        lastRequestAtMs = now;
        lastRequestSentAtMs = now;
        snapshotRequestCount++;
        KryptonHybrid.NETWORK.sendToServer(StatsSnapshotRequestPayload.INSTANCE);
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new KryptonStatsScreen());
    }
}
