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
 * Compact translucent traffic card drawn over the game.
 *
 * <p>Shows this connection's codec and the server-wide bandwidth saving, live
 * outbound/inbound rates (from {@link KryptonStatsHistory}, falling back to
 * lifetime averages marked with {@code ~} until two snapshots have arrived) with
 * optional sparklines, and optionally the top mod by traffic.</p>
 */
public final class KryptonHudOverlay implements IGuiOverlay {
    public static final KryptonHudOverlay INSTANCE = new KryptonHudOverlay();

    public enum HudAnchor { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private static final int PAD = 5;
    private static final int LINE = 11;
    private static final int TEXT_W = 86;
    private static final int GRAPH_W = 52;

    private static boolean visible = false;
    private static long autoRefreshIntervalMs = 2000L;
    private static long lastAutoRefreshMs = 0L;
    private static HudAnchor anchor = HudAnchor.TOP_RIGHT;
    private static boolean showTopMod = true;
    private static boolean showGraph = true;

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
        if (now - codecCheckedAtMs > 500L) {
            codecCheckedAtMs = now;
            codec = KryptonStatsClientController.currentCodec();
        }
        Lines text = lines(snap, font, innerW);

        int lines = 3 + (text.topName() != null ? 1 : 0);
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
        Shapes.roundRect(g, x, y, width, height, 6, UITheme.withAlpha(c.panelBg(), 0xB8));
        Shapes.roundRectStroke(g, x, y, width, height, 6, Math.max(Shapes.pixel(), 0.5F),
                UITheme.withAlpha(c.widgetBorder(), 0x70));

        int tx = x + PAD;
        int ty = y + PAD;
        long dataVersion = KryptonStatsHistory.version();

        g.drawString(font, text.codec(), tx, ty, c.accentLight(), false);
        g.drawString(font, text.saving(), tx + innerW - text.savingWidth(), ty, savingColor(snap.savingPercent(), c), false);

        ty += LINE;
        g.drawString(font, text.out(), tx, ty, c.textPrimary(), false);
        if (showGraph) {
            Charts.sparkline(g, "hud.out", dataVersion, x + width - PAD - GRAPH_W, ty, GRAPH_W, 8,
                    KryptonStatsHistory.series(KryptonStatsHistory.Field.OUT_WIRE), c.accent());
        }

        ty += LINE;
        g.drawString(font, text.in(), tx, ty, c.textPrimary(), false);
        if (showGraph) {
            Charts.sparkline(g, "hud.in", dataVersion, x + width - PAD - GRAPH_W, ty, GRAPH_W, 8,
                    KryptonStatsHistory.series(KryptonStatsHistory.Field.IN), c.accentTertiary());
        }

        if (text.topName() != null) {
            ty += LINE;
            g.drawString(font, text.topName(), tx, ty, c.textSecondary(), false);
            g.drawString(font, text.topShare(), tx + innerW - text.topShareWidth(), ty, c.textMuted(), false);
        }
        g.endBatch();
    }

    /** The HUD's strings, rebuilt only when the data or layout they show changes. */
    private record Lines(String codec, String saving, int savingWidth, String out, String in,
                         String topName, String topShare, int topShareWidth) {}

    private record LinesKey(long dataVersion, KryptonStatsClientController.Codec codec,
                            boolean showTopMod, int innerWidth) {}

    private static KryptonStatsClientController.Codec codec = KryptonStatsClientController.Codec.NONE;
    private static long codecCheckedAtMs;
    /** Compared by identity: a new snapshot object means new data. */
    private static StatsSnapshotPayload linesSnapshot;
    private static LinesKey linesKey;
    private static Lines lines;

    private static Lines lines(StatsSnapshotPayload snap, Font font, int innerW) {
        LinesKey key = new LinesKey(KryptonStatsHistory.version(), codec, showTopMod, innerW);
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
                topName, topShare, topShareWidth);
        linesKey = key;
        return lines;
    }

    private static int savingColor(double value, UITheme.ColorPalette c) {
        if (value >= 30.0) return c.successColor();
        if (value >= 10.0) return c.warningColor();
        return c.dangerColor();
    }
}
