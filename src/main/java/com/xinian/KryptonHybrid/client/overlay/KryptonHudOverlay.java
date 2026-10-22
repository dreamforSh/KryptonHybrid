package com.xinian.KryptonHybrid.client.overlay;

import com.xinian.KryptonHybrid.client.KryptonStatsClientController;
import com.xinian.KryptonHybrid.client.KryptonStatsHistory;
import com.xinian.KryptonHybrid.client.compat.KryptonGuiGraphics;
import com.xinian.KryptonHybrid.client.ui.Charts;
import com.xinian.KryptonHybrid.client.ui.Fmt;
import com.xinian.KryptonHybrid.client.ui.Shapes;
import com.xinian.KryptonHybrid.client.ui.UITheme;
import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.Locale;

/**
 * Compact traffic card drawn over the game.
 *
 * <p>Shows this connection's codec and the server-wide bandwidth saving, live
 * outbound/inbound rates (from {@link KryptonStatsHistory}, falling back to
 * lifetime averages marked with {@code ~} until two snapshots have arrived), the
 * connection's ping, optional sparklines, and optionally the top mod by traffic.
 * The card background can be faded down to fully transparent; text then gets a
 * shadow so it stays readable over the world.</p>
 */
public final class KryptonHudOverlay implements IGuiOverlay {
    public static final KryptonHudOverlay INSTANCE = new KryptonHudOverlay();

    public enum HudAnchor { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private static final int PAD = 5;
    private static final int LINE = 11;
    private static final int TEXT_W = 86;
    private static final int GRAPH_W = 52;
    /** At or below this background opacity text is drawn with a shadow. */
    private static final int SHADOW_BELOW_OPACITY = 35;

    private static boolean visible = false;
    private static long autoRefreshIntervalMs = 2000L;
    private static long lastAutoRefreshMs = 0L;
    private static HudAnchor anchor = HudAnchor.TOP_RIGHT;
    private static boolean showTopMod = true;
    private static boolean showGraph = true;
    private static boolean showLatency = true;
    private static int backgroundOpacityPercent = 70;

    private KryptonHudOverlay() {}

    public static boolean isVisible() { return visible; }
    public static void setVisible(boolean v) { visible = v; }
    public static void toggleVisible() { visible = !visible; }
    public static long autoRefreshIntervalMs() { return autoRefreshIntervalMs; }
    public static void setAutoRefreshIntervalMs(long value) { autoRefreshIntervalMs = Math.max(500L, value); }
    public static HudAnchor anchor() { return anchor; }
    public static void setAnchor(HudAnchor value) { anchor = value; }
    public static void cycleAnchor() {
        anchor = switch (anchor) {
            case TOP_LEFT -> HudAnchor.TOP_RIGHT;
            case TOP_RIGHT -> HudAnchor.BOTTOM_RIGHT;
            case BOTTOM_RIGHT -> HudAnchor.BOTTOM_LEFT;
            case BOTTOM_LEFT -> HudAnchor.TOP_LEFT;
        };
    }
    public static boolean showTopMod() { return showTopMod; }
    public static void setShowTopMod(boolean value) { showTopMod = value; }
    public static void toggleShowTopMod() { showTopMod = !showTopMod; }
    public static boolean showGraph() { return showGraph; }
    public static void setShowGraph(boolean value) { showGraph = value; }
    public static void toggleShowGraph() { showGraph = !showGraph; }
    public static boolean showLatency() { return showLatency; }
    public static void setShowLatency(boolean value) { showLatency = value; }
    public static void toggleShowLatency() { showLatency = !showLatency; }
    public static int backgroundOpacityPercent() { return backgroundOpacityPercent; }
    public static void setBackgroundOpacityPercent(int percent) { backgroundOpacityPercent = Math.max(0, Math.min(100, percent)); }

    @Override
    public void render(ForgeGui gui, PoseStack poseStack, float partialTick, int screenWidth, int screenHeight) {
        if (!visible) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || mc.options.hideGui) return;

        long now = System.currentTimeMillis();
        if (now - lastAutoRefreshMs > autoRefreshIntervalMs) {
            lastAutoRefreshMs = now;
            KryptonStatsClientController.requestFreshSnapshot();
        }
        StatsSnapshotPayload snap = KryptonStatsClientController.latestSnapshot();
        if (snap == null) return;

        Font font = mc.font;
        var c = UITheme.colors();
        int width = PAD * 2 + TEXT_W + (showGraph ? GRAPH_W + 4 : 0);
        int innerW = width - PAD * 2;
        if (now - connectionCheckedAtMs > 500L) {
            connectionCheckedAtMs = now;
            codec = KryptonStatsClientController.currentCodec();
            latencyMs = KryptonStatsClientController.latencyMs();
        }
        Lines text = lines(snap, font, innerW);

        int lines = 3 + (text.latency() != null ? 1 : 0) + (text.topName() != null ? 1 : 0);
        int height = PAD * 2 + lines * LINE - 2;
        int margin = 4;
        int x = switch (anchor) {
            case TOP_LEFT, BOTTOM_LEFT -> margin;
            case TOP_RIGHT, BOTTOM_RIGHT -> screenWidth - width - margin;
        };
        int y = switch (anchor) {
            case TOP_LEFT, TOP_RIGHT -> margin;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenHeight - height - margin;
        };

        KryptonGuiGraphics g = new KryptonGuiGraphics(poseStack);
        g.beginBatch();
        if (backgroundOpacityPercent > 0) {
            float opacity = backgroundOpacityPercent / 100F;
            Shapes.roundRect(g, x, y, width, height, 6, UITheme.withAlpha(c.panelBg(), (int) (0xFF * opacity)));
            Shapes.roundRectStroke(g, x, y, width, height, 6, Math.max(Shapes.pixel(), 0.5F),
                    UITheme.withAlpha(c.widgetBorder(), (int) (0xA0 * opacity)));
        }
        boolean shadow = backgroundOpacityPercent <= SHADOW_BELOW_OPACITY;

        int tx = x + PAD;
        int ty = y + PAD;
        int graphX = x + width - PAD - GRAPH_W;
        long dataVersion = KryptonStatsHistory.version();

        g.drawString(font, text.codec(), tx, ty, c.accentLight(), shadow);
        g.drawString(font, text.saving(), tx + innerW - text.savingWidth(), ty, savingColor(snap.savingPercent(), c), shadow);

        ty += LINE;
        g.drawString(font, text.out(), tx, ty, c.textPrimary(), shadow);
        if (showGraph) {
            Charts.sparkline(g, "hud.out", dataVersion, graphX, ty, GRAPH_W, 8,
                    KryptonStatsHistory.series(KryptonStatsHistory.Field.OUT_WIRE), c.accent());
        }

        ty += LINE;
        g.drawString(font, text.in(), tx, ty, c.textPrimary(), shadow);
        if (showGraph) {
            Charts.sparkline(g, "hud.in", dataVersion, graphX, ty, GRAPH_W, 8,
                    KryptonStatsHistory.series(KryptonStatsHistory.Field.IN), c.accentTertiary());
        }

        if (text.latency() != null) {
            ty += LINE;
            int color = latencyColor(latencyMs, c);
            g.drawString(font, text.latency(), tx, ty, color, shadow);
            if (showGraph && !Double.isNaN(latencyMs)) {
                Charts.sparkline(g, "hud.latency", dataVersion, graphX, ty, GRAPH_W, 8,
                        KryptonStatsHistory.series(KryptonStatsHistory.Field.LATENCY), color);
            }
        }

        if (text.topName() != null) {
            ty += LINE;
            g.drawString(font, text.topName(), tx, ty, c.textSecondary(), shadow);
            g.drawString(font, text.topShare(), tx + innerW - text.topShareWidth(), ty, c.textMuted(), shadow);
        }
        g.endBatch();
    }

    /** The HUD's strings, rebuilt only when the data or layout they show changes. */
    private record Lines(String codec, String saving, int savingWidth, String out, String in, String latency,
                         String topName, String topShare, int topShareWidth) {}

    private record LinesKey(long dataVersion, KryptonStatsClientController.Codec codec, long latency,
                            boolean showLatency, boolean showTopMod, int innerWidth) {}

    private static KryptonStatsClientController.Codec codec = KryptonStatsClientController.Codec.NONE;
    private static double latencyMs = Double.NaN;
    private static long connectionCheckedAtMs;
    /** Compared by identity: a new snapshot object means new data. */
    private static StatsSnapshotPayload linesSnapshot;
    private static LinesKey linesKey;
    private static Lines lines;

    private static Lines lines(StatsSnapshotPayload snap, Font font, int innerW) {
        long latencyKey = Double.isNaN(latencyMs) ? -1L : Math.round(latencyMs);
        LinesKey key = new LinesKey(KryptonStatsHistory.version(), codec, latencyKey, showLatency, showTopMod, innerW);
        if (snap == linesSnapshot && key.equals(linesKey)) {
            return lines;
        }
        linesSnapshot = snap;
        KryptonStatsHistory.Sample live = KryptonStatsHistory.latest();
        String codecText = Component.translatable("gui.krypton_hybrid.dash.codec."
                + codec.name().toLowerCase(Locale.ROOT)).getString();
        String saving = Component.translatable("hud.krypton_hybrid.saving", Fmt.percent(snap.savingPercent())).getString();
        String out = live != null ? Fmt.rate(live.outWireBps()) : "~" + Fmt.rate(snap.wireBytesPerSecond());
        String in = live != null ? Fmt.rate(live.inBps()) : "~" + Fmt.rate(snap.receivedBytesPerSecond());

        String latency = null;
        if (showLatency) {
            String value = codec == KryptonStatsClientController.Codec.LOCAL
                    ? Component.translatable("hud.krypton_hybrid.latency.local").getString()
                    : Double.isNaN(latencyMs) ? "--" : Math.round(latencyMs) + " ms";
            latency = Component.translatable("hud.krypton_hybrid.latency", value).getString();
        }

        String topName = null;
        String topShare = null;
        int topShareWidth = 0;
        if (showTopMod) {
            StatsSnapshotPayload.ModEntry top = null;
            for (StatsSnapshotPayload.ModEntry e : snap.topMods()) {
                if (top == null || e.bytes() > top.bytes()) top = e;
            }
            if (top != null) {
                topShare = Fmt.percent(top.bytes() * 100.0 / Math.max(1L, snap.totalTrackedModBytes()));
                topShareWidth = font.width(topShare);
                String name = Fmt.truncate(font, top.modId(), innerW - topShareWidth - 14);
                topName = Component.translatable("hud.krypton_hybrid.top_mod", name).getString();
            }
        }
        lines = new Lines(codecText, saving, font.width(saving),
                Component.translatable("hud.krypton_hybrid.out", out).getString(),
                Component.translatable("hud.krypton_hybrid.in", in).getString(),
                latency, topName, topShare, topShareWidth);
        linesKey = key;
        return lines;
    }

    private static int savingColor(double value, UITheme.ColorPalette c) {
        if (value >= 30.0) return c.successColor();
        if (value >= 10.0) return c.warningColor();
        return c.dangerColor();
    }

    private static int latencyColor(double ms, UITheme.ColorPalette c) {
        if (Double.isNaN(ms)) return c.textSecondary();
        if (ms <= 80) return c.successColor();
        if (ms <= 150) return c.warningColor();
        return c.dangerColor();
    }
}
