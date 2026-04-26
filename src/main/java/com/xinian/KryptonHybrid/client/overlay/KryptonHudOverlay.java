package com.xinian.KryptonHybrid.client.overlay;

import com.xinian.KryptonHybrid.client.KryptonStatsClientController;
import com.xinian.KryptonHybrid.client.ui.UITheme;
import com.xinian.KryptonHybrid.shared.network.NetworkTrafficStats;
import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * MemoryCatcher-style in-game HUD layer for Krypton Hybrid.
 * Renders a compact rounded panel with algorithm, savings bar, throughput, and
 * the top traffic-consuming mod. Spacing follows {@code font.lineHeight} so the
 * overlay scales correctly with the in-game GUI scale.
 */
public final class KryptonHudOverlay implements LayeredDraw.Layer {

    public enum HudAnchor { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private static final int BAR_WIDTH = 130;
    private static final int BAR_HEIGHT = 6;
    private static final int PADDING = 6;
    private static final int CORNER_RADIUS = 4;

    private static long autoRefreshIntervalMs = 2000L;
    private static long lastAutoRefreshMs = 0L;
    private static HudAnchor anchor = HudAnchor.TOP_RIGHT;
    private static boolean showTopMod = true;

    private static boolean visible = false;
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

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!visible) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null) return;

        StatsSnapshotPayload snap = KryptonStatsClientController.latestSnapshot();
        long now = System.currentTimeMillis();
        if (now - lastAutoRefreshMs > autoRefreshIntervalMs) {
            lastAutoRefreshMs = now;
            KryptonStatsClientController.requestFreshSnapshot();
        }
        if (snap == null) return;

        var c = UITheme.colors();
        int lineH = mc.font.lineHeight + 2;

        // Compute number of lines: head + bar + sent + recv + saved + (optional top mod)
        boolean hasTop = showTopMod && !snap.topMods().isEmpty();
        int lines = 5 + (hasTop ? 1 : 0);
        int panelHeight = PADDING * 2 + lineH + BAR_HEIGHT + 4 + (lines - 2) * lineH;
        int panelWidth = BAR_WIDTH + PADDING * 2 + 4;

        int sw = graphics.guiWidth();
        int sh = graphics.guiHeight();
        int margin = 4;
        int panelX;
        int panelY;
        switch (anchor) {
            case TOP_LEFT -> {
                panelX = margin;
                panelY = margin;
            }
            case TOP_RIGHT -> {
                panelX = sw - panelWidth - margin;
                panelY = margin;
            }
            case BOTTOM_LEFT -> {
                panelX = margin;
                panelY = sh - panelHeight - margin;
            }
            case BOTTOM_RIGHT -> {
                panelX = sw - panelWidth - margin;
                panelY = sh - panelHeight - margin;
            }
            default -> {
                panelX = sw - panelWidth - margin;
                panelY = margin;
            }
        }

        UITheme.fillRoundedRect(graphics, panelX, panelY, panelWidth, panelHeight,
                CORNER_RADIUS, UITheme.withAlpha(c.panelBg(), 0xCC));
        UITheme.drawRoundedBorder(graphics, panelX, panelY, panelWidth, panelHeight,
                CORNER_RADIUS, UITheme.withAlpha(c.widgetBorder(), 0x80));

        int x = panelX + PADDING;
        int y = panelY + PADDING;

        String head = Component.translatable("hud.krypton_hybrid.header",
                snap.compressionAlgorithm(),
                String.format("%.1f%%", snap.savingPercent())).getString();
        int headColor = qualityColor(snap.savingPercent(), c);
        graphics.drawString(mc.font, head, x, y, headColor, false);

        y += lineH;
        double clamped = Math.max(0.0, Math.min(100.0, snap.savingPercent()));
        int fillW = (int) (BAR_WIDTH * (clamped / 100.0));
        UITheme.fillRoundedRect(graphics, x, y, BAR_WIDTH, BAR_HEIGHT, 2, c.widgetBg());
        if (fillW > 1) {
            UITheme.fillRoundedRect(graphics, x, y, fillW, BAR_HEIGHT, 2, headColor);
            UITheme.fillRoundedRect(graphics, x, y, fillW, BAR_HEIGHT / 2, 2,
                    UITheme.brighten(headColor, 0.25f));
        }
        UITheme.drawRoundedBorder(graphics, x, y, BAR_WIDTH, BAR_HEIGHT, 2,
                UITheme.withAlpha(c.widgetBorder(), 0x60));

        y += BAR_HEIGHT + 3;
        graphics.drawString(mc.font,
                Component.translatable("hud.krypton_hybrid.tx", NetworkTrafficStats.formatBytes(snap.wireBytesPerSecond())).getString(),
                x, y, c.textSecondary(), false);
        y += lineH;
        graphics.drawString(mc.font,
                Component.translatable("hud.krypton_hybrid.rx", NetworkTrafficStats.formatBytes(snap.receivedBytesPerSecond())).getString(),
                x, y, c.textSecondary(), false);
        y += lineH;
        graphics.drawString(mc.font,
                Component.translatable("hud.krypton_hybrid.saved", NetworkTrafficStats.formatBytes(snap.savedBytes())).getString(),
                x, y, c.textMuted(), false);

        if (hasTop) {
            y += lineH;
            // Pick mod with largest bytes (topMods is already sorted server-side, but be safe)
            List<StatsSnapshotPayload.ModEntry> mods = snap.topMods();
            StatsSnapshotPayload.ModEntry top = mods.get(0);
            for (StatsSnapshotPayload.ModEntry e : mods)
                if (e.bytes() > top.bytes()) top = e;
            String right = NetworkTrafficStats.formatBytes(top.bytes());
            int rw = mc.font.width(right);
            // Reserve gutter for the right-aligned size and a small spacer.
            int leftMaxW = panelWidth - PADDING * 2 - rw - 6;
            String left = Component.translatable("hud.krypton_hybrid.top_mod",
                    truncate(mc.font, top.modId(), leftMaxW - mc.font.width("▶ "))).getString();
            graphics.drawString(mc.font, left, x, y, c.accentLight(), false);
            graphics.drawString(mc.font, right, panelX + panelWidth - PADDING - rw, y, c.textSecondary(), false);
        }
    }

    private static String truncate(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (text == null || text.isEmpty()) return "-";
        if (font.width(text) <= maxWidth) return text;
        String ell = "…";
        int end = text.length();
        while (end > 1 && font.width(text.substring(0, end) + ell) > maxWidth) end--;
        return text.substring(0, Math.max(1, end)) + ell;
    }

    private static int qualityColor(double value, UITheme.ColorPalette c) {
        if (value >= 30.0) return c.successColor();
        if (value >= 10.0) return c.warningColor();
        return c.dangerColor();
    }
}

