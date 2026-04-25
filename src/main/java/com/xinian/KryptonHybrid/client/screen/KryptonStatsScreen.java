package com.xinian.KryptonHybrid.client.screen;

import com.xinian.KryptonHybrid.shared.network.NetworkTrafficStats;
import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Client-side statistics GUI driven by a {@link StatsSnapshotPayload} pushed
 * from the server in response to {@code /krypton stats gui}.
 *
 * <p>Layout (top → bottom):
 * <ol>
 *   <li>Centered title</li>
 *   <li>Three column-grouped sections: Network / Bundle / Compression</li>
 *   <li>Bottom-right Close button</li>
 * </ol>
 */
public final class KryptonStatsScreen extends Screen {

    private final StatsSnapshotPayload snap;

    public KryptonStatsScreen(StatsSnapshotPayload snap) {
        super(Component.translatable("gui.krypton_hybrid.stats.title"));
        this.snap = snap;
    }

    @Override
    protected void init() {
        addRenderableWidget(
                Button.builder(Component.translatable("gui.krypton_hybrid.button.close"),
                                b -> onClose())
                      .bounds(this.width - 90, this.height - 28, 80, 20)
                      .build());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        // Title
        g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFAA00);

        long elapsed = Math.max(1L, snap.elapsedSeconds());
        long sendRateOrig = snap.bytesSentOriginal() / elapsed;
        long sendRateWire = snap.bytesSentWire() / elapsed;
        long recvRate = snap.bytesReceived() / elapsed;
        double bundleHit = snap.bundleBatchesObserved() == 0 ? 0.0
                : 100.0 * snap.bundlesEmitted() / (double) snap.bundleBatchesObserved();
        double avgBundleSize = snap.bundlesEmitted() == 0 ? 0.0
                : (double) snap.bundlePacketsTotal() / (double) snap.bundlesEmitted();

        int colW = (this.width - 40) / 3;
        int col1 = 20;
        int col2 = 20 + colW;
        int col3 = 20 + colW * 2;
        int yStart = 36;
        int line = 12;

        // ── Network section ────────────────────────────────────────────
        int y = yStart;
        g.drawString(this.font,
                Component.translatable("gui.krypton_hybrid.section.network")
                         .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                col1, y, 0xFFFFFFFF);
        y += line + 2;
        y = drawKv(g, col1, y, "gui.krypton_hybrid.label.uptime", elapsed + " s");
        y = drawKv(g, col1, y, "gui.krypton_hybrid.label.packets_sent",
                String.format("%,d", snap.packetsSent()));
        y = drawKv(g, col1, y, "gui.krypton_hybrid.label.packets_received",
                String.format("%,d", snap.packetsReceived()));
        y = drawKv(g, col1, y, "gui.krypton_hybrid.label.sent_orig",
                NetworkTrafficStats.formatBytes(snap.bytesSentOriginal())
                        + " (" + NetworkTrafficStats.formatBytes(sendRateOrig) + "/s)");
        y = drawKv(g, col1, y, "gui.krypton_hybrid.label.sent_wire",
                NetworkTrafficStats.formatBytes(snap.bytesSentWire())
                        + " (" + NetworkTrafficStats.formatBytes(sendRateWire) + "/s)");
        y = drawKv(g, col1, y, "gui.krypton_hybrid.label.received",
                NetworkTrafficStats.formatBytes(snap.bytesReceived())
                        + " (" + NetworkTrafficStats.formatBytes(recvRate) + "/s)");

        // ── Bundle section ─────────────────────────────────────────────
        y = yStart;
        g.drawString(this.font,
                Component.translatable("gui.krypton_hybrid.section.bundle")
                         .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                col2, y, 0xFFFFFFFF);
        y += line + 2;
        y = drawKv(g, col2, y, "gui.krypton_hybrid.label.bundles_emitted",
                String.format("%,d", snap.bundlesEmitted()));
        y = drawKv(g, col2, y, "gui.krypton_hybrid.label.bundle_packets_total",
                String.format("%,d", snap.bundlePacketsTotal()));
        y = drawKv(g, col2, y, "gui.krypton_hybrid.label.bundle_avg_size",
                String.format("%.2f", avgBundleSize));
        y = drawKv(g, col2, y, "gui.krypton_hybrid.label.bundle_hit_rate",
                String.format("%.1f%%", bundleHit));
        y = drawKv(g, col2, y, "gui.krypton_hybrid.label.coalesce_dropped",
                String.format("%,d", snap.coalesceDroppedPackets()));

        // ── Compression section ────────────────────────────────────────
        y = yStart;
        g.drawString(this.font,
                Component.translatable("gui.krypton_hybrid.section.compression")
                         .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                col3, y, 0xFFFFFFFF);
        y += line + 2;
        y = drawKv(g, col3, y, "gui.krypton_hybrid.label.algorithm",
                snap.compressionAlgorithm());
        y = drawKv(g, col3, y, "gui.krypton_hybrid.label.compression_ratio",
                String.format("%.4f", snap.compressionRatio()));
        y = drawKv(g, col3, y, "gui.krypton_hybrid.label.bandwidth_saving",
                String.format("%.2f%%", snap.savingPercent()));
    }

    private int drawKv(GuiGraphics g, int x, int y, String labelKey, String value) {
        g.drawString(this.font, Component.translatable(labelKey)
                .withStyle(ChatFormatting.GRAY), x, y, 0xFFAAAAAA);
        g.drawString(this.font, value, x + 4, y + 10, 0xFFFFFFFF);
        return y + 24;
    }
}

