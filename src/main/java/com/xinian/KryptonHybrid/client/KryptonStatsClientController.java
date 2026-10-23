package com.xinian.KryptonHybrid.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.xinian.KryptonHybrid.client.overlay.KryptonHudOverlay;
import com.xinian.KryptonHybrid.client.screen.KryptonStatsScreen;
import com.xinian.KryptonHybrid.KryptonHybrid;
import com.xinian.KryptonHybrid.shared.network.payload.StatsRequestPayload;
import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side controller for the stats dashboard.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Registers Controls-menu key bindings (open dashboard, toggle HUD)</li>
 *   <li>Caches the latest server-pushed {@link StatsSnapshotPayload}</li>
 *   <li>Opens the dashboard immediately from cache, then requests a fresh snapshot</li>
 *   <li>Registers the in-game HUD overlay layer</li>
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
        modEventBus.addListener(KryptonStatsClientController::onRegisterKeyMappings);
        modEventBus.addListener(KryptonStatsClientController::onRegisterGuiLayers);
        NeoForge.EVENT_BUS.addListener(KryptonStatsClientController::onClientTick);
        NeoForge.EVENT_BUS.addListener(KryptonStatsClientController::onClientLoggedOut);
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
        RemoteServerConfig.clear();
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_STATS_KEY);
        event.register(TOGGLE_HUD_KEY);
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(KryptonHybrid.MODID, "stats_hud"),
                new KryptonHudOverlay()
        );
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;

        while (OPEN_STATS_KEY.consumeClick()) {
            openOrRequestLatest();
        }
        while (TOGGLE_HUD_KEY.consumeClick()) {
            KryptonHudOverlay.toggleVisible();
            minecraft.gui.setOverlayMessage(
                    Component.translatable(KryptonHudOverlay.isVisible()
                            ? "gui.krypton_hybrid.hud.on"
                            : "gui.krypton_hybrid.hud.off"),
                    false);
        }
    }

    public static void receiveSnapshot(StatsSnapshotPayload payload) {
        latestSnapshot = payload;
        latestSnapshotReceivedAtMs = System.currentTimeMillis();
        snapshotReceiveCount++;
        if (lastRequestSentAtMs > 0L && latestSnapshotReceivedAtMs >= lastRequestSentAtMs) {
            lastRoundTripMs = latestSnapshotReceivedAtMs - lastRequestSentAtMs;
        }

        // Snapshots never open the dashboard by themselves (that is what
        // OpenDashboardPayload is for), except for a request made before any arrived.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof KryptonStatsScreen screen) {
            screen.updateSnapshot(payload);
        } else if (pendingOpen && minecraft.screen == null) {
            open(payload);
        }
        pendingOpen = false;
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

    public static void openOrRequestLatest() {
        Minecraft minecraft = Minecraft.getInstance();
        if (latestSnapshot != null) {
            open(latestSnapshot);
        } else {
            pendingOpen = true;
            minecraft.gui.setOverlayMessage(Component.translatable("gui.krypton_hybrid.hint.requesting"), false);
        }
        requestFreshSnapshot();
    }

    public static void requestFreshSnapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        // Servers without Krypton Hybrid (or an older build) do not take the request.
        if (minecraft.player == null || !minecraft.player.connection.hasChannel(StatsRequestPayload.TYPE)) return;

        long now = System.currentTimeMillis();
        if (now - lastRequestAtMs < SNAPSHOT_REQUEST_COOLDOWN_MS) return;

        lastRequestAtMs = now;
        lastRequestSentAtMs = now;
        snapshotRequestCount++;
        PacketDistributor.sendToServer(StatsRequestPayload.INSTANCE);
    }

    public static void open(StatsSnapshotPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new KryptonStatsScreen(payload));
    }
}

