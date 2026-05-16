package com.xinian.KryptonHybrid.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.xinian.KryptonHybrid.client.overlay.KryptonHudOverlay;
import com.xinian.KryptonHybrid.client.screen.KryptonStatsScreen;
import com.xinian.KryptonHybrid.KryptonHybrid;
import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
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

    private KryptonStatsClientController() {}

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(KryptonStatsClientController::onRegisterKeyMappings);
        modEventBus.addListener(KryptonStatsClientController::onRegisterGuiLayers);
        MinecraftForge.EVENT_BUS.addListener(KryptonStatsClientController::onClientTick);
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

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof KryptonStatsScreen screen) {
            screen.updateSnapshot(payload);
        } else if (!KryptonHudOverlay.isVisible()) {
            // Preserve legacy behaviour: server-pushed snapshot pops the dashboard
            // open. When the HUD is on it auto-refreshes every 2s; suppress
            // auto-open so the GUI does not steal focus.
            open(payload);
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

    public static void openOrRequestLatest() {
        Minecraft minecraft = Minecraft.getInstance();
        if (latestSnapshot != null) {
            open(latestSnapshot);
        }
        requestFreshSnapshot();
        if (latestSnapshot == null) {
            minecraft.gui.setOverlayMessage(Component.translatable("gui.krypton_hybrid.hint.requesting"), false);
        }
    }

    public static void requestFreshSnapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;

        long now = System.currentTimeMillis();
        if (now - lastRequestAtMs < SNAPSHOT_REQUEST_COOLDOWN_MS) return;

        lastRequestAtMs = now;
        lastRequestSentAtMs = now;
        snapshotRequestCount++;
        minecraft.player.connection.sendUnsignedCommand("krypton stats gui");
    }

    public static void open(StatsSnapshotPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new KryptonStatsScreen(payload));
    }
}

