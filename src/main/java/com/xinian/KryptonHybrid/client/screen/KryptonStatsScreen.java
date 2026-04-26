package com.xinian.KryptonHybrid.client.screen;

import com.xinian.KryptonHybrid.client.KryptonStatsClientController;
import com.xinian.KryptonHybrid.client.ui.MCButton;
import com.xinian.KryptonHybrid.client.ui.MCPanel;
import com.xinian.KryptonHybrid.client.ui.UITheme;
import com.xinian.KryptonHybrid.shared.network.NetworkTrafficStats;
import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Themed Krypton stats dashboard.
 *
 * <p>Layout adapts to GUI scale &amp; window width:</p>
 * <ul>
 *   <li>Tabs and chips reflow to fewer columns on narrow viewports.</li>
 *   <li>Line spacing is driven by {@code font.lineHeight} so larger GUI scales
 *       remain readable.</li>
 *   <li>The "Mods" tab streams the per-mod traffic table from
 *       {@link StatsSnapshotPayload#topMods()} with sortable bars and scrolling.</li>
 * </ul>
 */
public final class KryptonStatsScreen extends Screen {

    private enum Tab { OVERVIEW, NETWORK, COMPRESSION, MODS, INSIGHTS }
    private enum ModSort { BYTES, PACKETS, NAME }

    private StatsSnapshotPayload snap;
    private Tab currentTab = Tab.OVERVIEW;
    private final List<MCPanel> activePanels = new ArrayList<>();
    private final List<MCButton> tabButtons = new ArrayList<>();

    // Mods tab state
    private ModSort modSort = ModSort.BYTES;
    private int modScroll = 0;
    private int modListInnerX, modListInnerY, modListInnerW, modListInnerH;
    private int lastMouseX;
    private int lastMouseY;
    private List<Component> summaryTooltip;

    public KryptonStatsScreen(StatsSnapshotPayload snap) {
        super(Component.translatable("gui.krypton_hybrid.stats.title"));
        this.snap = snap;
    }

    public void updateSnapshot(StatsSnapshotPayload snap) { this.snap = snap; }

    @Override
    public boolean isPauseScreen() { return false; }

    private int lineH() { return this.font.lineHeight + 2; }

    @Override
    protected void init() {
        clearWidgets();
        tabButtons.clear();
        activePanels.clear();

        // ─── Theme toggle (top-right, sized to content) ───
        Component themeText = themeLabel();
        int themeW = Math.max(72, this.font.width(themeText) + 16);
        int topBtnH = 18;
        int topGap = 6;
        int settingsW = Math.max(90, this.font.width(Component.translatable("gui.krypton_hybrid.button.settings")) + 16);

        MCButton themeBtn = new MCButton(this.width - themeW - 8, 8, themeW, topBtnH,
                themeText, b -> {
                    UITheme.toggleMode();
                    b.setMessage(themeLabel());
                });
        addRenderableWidget(themeBtn);

        addRenderableWidget(new MCButton(this.width - themeW - settingsW - topGap - 8, 8, settingsW, topBtnH,
                Component.translatable("gui.krypton_hybrid.button.settings"),
                b -> this.minecraft.setScreen(new KryptonStatsSettingsScreen(this))));

        // ─── Tab strip — adaptive width based on longest label ───
        Tab[] tabs = Tab.values();
        int gap = 6;
        int sidePad = 12;
        int labelMax = 0;
        for (Tab t : tabs) {
            labelMax = Math.max(labelMax, this.font.width(Component.translatable(tabKey(t))));
        }
        int idealTabW = Math.max(64, labelMax + 14);
        int available = this.width - sidePad * 2;
        int totalIdeal = tabs.length * idealTabW + (tabs.length - 1) * gap;
        int tabW;
        if (totalIdeal <= available) {
            tabW = idealTabW;
        } else {
            tabW = Math.max(40, (available - gap * (tabs.length - 1)) / tabs.length);
        }
        int totalW = tabs.length * tabW + (tabs.length - 1) * gap;
        int startX = Math.max(sidePad, (this.width - totalW) / 2);
        int tabY = 36;
        int tabH = Math.max(18, this.font.lineHeight + 8);
        for (int i = 0; i < tabs.length; i++) {
            final Tab tab = tabs[i];
            MCButton b = new MCButton(startX + i * (tabW + gap), tabY, tabW, tabH,
                    Component.translatable(tabKey(tab)), btn -> switchTab(tab));
            tabButtons.add(b);
            addRenderableWidget(b);
        }

        rebuildPanels();

        // ─── Action bar (bottom-centered, sized by translated label width) ───
        int btnH = Math.max(20, this.font.lineHeight + 10);
        int btnY = this.height - btnH - 8;
        Component refreshText = Component.translatable("gui.krypton_hybrid.button.refresh");
        Component closeText   = Component.translatable("gui.krypton_hybrid.button.close");
        int refreshW = Math.max(96, this.font.width(refreshText) + 24);
        int closeW   = Math.max(76, this.font.width(closeText)   + 24);
        int btnGap = 8;
        int total = refreshW + closeW + btnGap;
        int bx = Math.max(10, (this.width - total) / 2);
        addRenderableWidget(new MCButton(bx, btnY, refreshW, btnH, refreshText,
                b -> KryptonStatsClientController.requestFreshSnapshot()));
        addRenderableWidget(new MCButton(bx + refreshW + btnGap, btnY, closeW, btnH, closeText,
                b -> onClose()));
    }

    private void switchTab(Tab tab) {
        this.currentTab = tab;
        this.modScroll = 0;
        rebuildPanels();
    }

    private void rebuildPanels() {
        activePanels.clear();
        // Header consumes y∈[0..32], tab strip starts at y=36 with height ≈ font+8.
        int tabBottom = 36 + Math.max(18, this.font.lineHeight + 8);
        int contentTop = tabBottom + 10;
        // Footer reserves the action button row (height ≈ font+10) + hint line + padding.
        int footerReserve = Math.max(20, this.font.lineHeight + 10) + lineH() + 14;
        int contentBottom = this.height - footerReserve;
        int left = 14;
        int right = this.width - 14;
        int width = right - left;
        int height = Math.max(160, contentBottom - contentTop);
        boolean singleColumn = this.width < 470;
        int gap = 10;

        switch (currentTab) {
            case OVERVIEW -> buildOverview(left, contentTop, width, height, gap, singleColumn);
            case NETWORK -> buildNetwork(left, contentTop, width, height, gap, singleColumn);
            case COMPRESSION -> buildCompression(left, contentTop, width, height);
            case MODS -> buildMods(left, contentTop, width, height);
            case INSIGHTS -> buildInsights(left, contentTop, width, height);
        }
    }

    // ─── Tab builders ───

    private void buildOverview(int left, int top, int width, int height, int gap, boolean singleColumn) {
        long elapsed = Math.max(1L, snap.elapsedSeconds());
        long snapshotAgeMs = KryptonStatsClientController.latestSnapshotAgeMs();
        double bundleHit = snap.bundleHitRatePercent();

        int cols = width < 380 ? 2 : 4;
        int rows = (4 + cols - 1) / cols;
        int chipH = this.font.lineHeight * 2 + 8;
        int chipPanelH = MCPanelHeader() + rows * chipH + (rows - 1) * 6 + 8;
        MCPanel summary = new MCPanel(left, top, width, chipPanelH)
                .setTitle(translate("gui.krypton_hybrid.section.summary"));
        summary.addEntry((g, x, y, w, h) -> drawChipStrip(g, x, y, w, snapshotAgeMs, bundleHit));
        activePanels.add(summary);

        int remTop = top + chipPanelH + gap;
        int remH = Math.max(140, height - chipPanelH - gap);
        int colW = singleColumn ? width : (width - gap) / 2;
        int colH = singleColumn ? (remH - gap) / 2 : remH;

        MCPanel saving = new MCPanel(left, remTop, colW, colH)
                .setTitle(translate("gui.krypton_hybrid.section.saving_summary"))
                .setCollapsible(true);
        saving.addEntry((g, x, y, w, h) -> {
            int ly = drawSavingBar(g, x, y, w, snap.savingPercent());
            ly += 4;
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.saved_bytes",
                    NetworkTrafficStats.formatBytes(snap.savedBytes()), qualityColor(snap.savingPercent(), 30.0, 10.0));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.compression_ratio",
                    String.format("%.4f", snap.compressionRatio()), ratioColor(snap.compressionRatio()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.tracked_coverage",
                    String.format("%.1f%%", snap.trackedCoveragePercent()), qualityColor(snap.trackedCoveragePercent(), 85.0, 60.0));
            drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.algorithm", snap.compressionAlgorithm(), UITheme.colors().accentLight());
        });
        activePanels.add(saving);

        int sx = singleColumn ? left : left + colW + gap;
        int sy = singleColumn ? remTop + colH + gap : remTop;
        MCPanel throughput = new MCPanel(sx, sy, colW, colH)
                .setTitle(translate("gui.krypton_hybrid.section.throughput"))
                .setCollapsible(true);
        throughput.addEntry((g, x, y, w, h) -> {
            int ly = drawMetric(g, x, y, w, "gui.krypton_hybrid.label.uptime", formatDuration(elapsed));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.packets_sent",
                    String.format("%,d (%.1f/s)", snap.packetsSent(), snap.packetsSentPerSecond()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.packets_received",
                    String.format("%,d (%.1f/s)", snap.packetsReceived(), snap.packetsReceivedPerSecond()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.sent_orig_rate",
                    formatBytesRate(snap.bytesSentOriginal(), snap.originalBytesPerSecond()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.sent_wire_rate",
                    formatBytesRate(snap.bytesSentWire(), snap.wireBytesPerSecond()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.received_rate",
                    formatBytesRate(snap.bytesReceived(), snap.receivedBytesPerSecond()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.bundle_hit_rate",
                    String.format("%.1f%%", snap.bundleHitRatePercent()), qualityColor(snap.bundleHitRatePercent(), 70.0, 35.0));
            drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.coalesce_drop_rate",
                    String.format("%.1f%%", snap.coalesceDropRatePercent()), inverseQualityColor(snap.coalesceDropRatePercent(), 5.0, 15.0));
        });
        activePanels.add(throughput);
    }

    private void buildNetwork(int left, int top, int width, int height, int gap, boolean singleColumn) {
        long elapsed = Math.max(1L, snap.elapsedSeconds());
        int colW = singleColumn ? width : (width - gap) / 2;
        int colH = singleColumn ? (height - gap) / 2 : height;

        MCPanel sent = new MCPanel(left, top, colW, colH)
                .setTitle(translate("gui.krypton_hybrid.section.network_sent"))
                .setCollapsible(true);
        sent.addEntry((g, x, y, w, h) -> {
            int ly = drawMetric(g, x, y, w, "gui.krypton_hybrid.label.uptime", formatDuration(elapsed));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.packets_sent",
                    String.format("%,d", snap.packetsSent()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.sent_orig_rate",
                    formatBytesRate(snap.bytesSentOriginal(), snap.originalBytesPerSecond()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.sent_wire_rate",
                    formatBytesRate(snap.bytesSentWire(), snap.wireBytesPerSecond()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.sent_orig",
                    NetworkTrafficStats.formatBytes(snap.bytesSentOriginal()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.sent_wire",
                    NetworkTrafficStats.formatBytes(snap.bytesSentWire()), ratioColor(snap.compressionRatio()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.saved_bytes",
                    NetworkTrafficStats.formatBytes(snap.savedBytes()), qualityColor(snap.savingPercent(), 30.0, 10.0));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.avg_orig_packet",
                    String.format("%.1f B", snap.averageOriginalPacketBytes()));
            drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.avg_wire_packet",
                    String.format("%.1f B", snap.averageWirePacketBytes()));
        });
        activePanels.add(sent);

        int sx = singleColumn ? left : left + colW + gap;
        int sy = singleColumn ? top + colH + gap : top;
        MCPanel recv = new MCPanel(sx, sy, colW, colH)
                .setTitle(translate("gui.krypton_hybrid.section.network_recv"))
                .setCollapsible(true);
        recv.addEntry((g, x, y, w, h) -> {
            int ly = drawMetric(g, x, y, w, "gui.krypton_hybrid.label.packets_received",
                    String.format("%,d", snap.packetsReceived()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.received_rate",
                    formatBytesRate(snap.bytesReceived(), snap.receivedBytesPerSecond()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.received",
                    NetworkTrafficStats.formatBytes(snap.bytesReceived()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.avg_recv_packet",
                    String.format("%.1f B", snap.averageReceivedPacketBytes()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.bundles_emitted",
                    String.format("%,d", snap.bundlesEmitted()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.bundle_batches_observed",
                    String.format("%,d", snap.bundleBatchesObserved()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.bundle_packets_total",
                    String.format("%,d", snap.bundlePacketsTotal()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.bundle_avg_size",
                    String.format("%.2f", snap.averageBundleSize()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.bundle_hit_rate",
                    String.format("%.1f%%", snap.bundleHitRatePercent()), qualityColor(snap.bundleHitRatePercent(), 70.0, 35.0));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.coalesce_dropped",
                    String.format("%,d", snap.coalesceDroppedPackets()));
            drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.coalesce_drop_rate",
                    String.format("%.1f%%", snap.coalesceDropRatePercent()), inverseQualityColor(snap.coalesceDropRatePercent(), 5.0, 15.0));
        });
        activePanels.add(recv);
    }

    private void buildCompression(int left, int top, int width, int height) {
        MCPanel p = new MCPanel(left, top, width, height)
                .setTitle(translate("gui.krypton_hybrid.section.compression"))
                .setCollapsible(true);
        p.addEntry((g, x, y, w, h) -> {
            int ly = drawSavingBar(g, x, y, w, snap.savingPercent());
            ly += 6;
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.algorithm", snap.compressionAlgorithm());
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.sent_orig",
                    NetworkTrafficStats.formatBytes(snap.bytesSentOriginal()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.sent_wire",
                    NetworkTrafficStats.formatBytes(snap.bytesSentWire()), ratioColor(snap.compressionRatio()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.received",
                    NetworkTrafficStats.formatBytes(snap.bytesReceived()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.saved_bytes",
                    NetworkTrafficStats.formatBytes(snap.savedBytes()), qualityColor(snap.savingPercent(), 30.0, 10.0));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.compression_ratio",
                    String.format("%.4f", snap.compressionRatio()), ratioColor(snap.compressionRatio()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.bandwidth_saving",
                    String.format("%.2f%%", snap.savingPercent()), qualityColor(snap.savingPercent(), 30.0, 10.0));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.tracked_coverage",
                    String.format("%.1f%%", snap.trackedCoveragePercent()), qualityColor(snap.trackedCoveragePercent(), 85.0, 60.0));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.avg_orig_packet",
                    String.format("%.1f B", snap.averageOriginalPacketBytes()));
            ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.avg_wire_packet",
                    String.format("%.1f B", snap.averageWirePacketBytes()));
            drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.avg_recv_packet",
                    String.format("%.1f B", snap.averageReceivedPacketBytes()));
        });
        activePanels.add(p);
    }

    private void buildMods(int left, int top, int width, int height) {
        MCPanel p = new MCPanel(left, top, width, height)
                .setTitle(translate("gui.krypton_hybrid.section.per_mod"));
        p.addEntry(this::drawModsContent);
        activePanels.add(p);
    }

    private void buildInsights(int left, int top, int width, int height) {
        boolean stacked = width < 560;
        int gap = 10;

        if (stacked) {
            int paneH = Math.max(92, (height - gap * 2) / 3);

            MCPanel hotspots = new MCPanel(left, top, width, paneH)
                    .setTitle(translate("gui.krypton_hybrid.section.insights"))
                    .setCollapsible(true);
            hotspots.addEntry(this::drawHotspotEntries);
            activePanels.add(hotspots);

            MCPanel security = new MCPanel(left, top + paneH + gap, width, paneH)
                    .setTitle(translate("gui.krypton_hybrid.section.security"))
                    .setCollapsible(true);
            security.addEntry(this::drawSecurityEntries);
            activePanels.add(security);

            MCPanel client = new MCPanel(left, top + (paneH + gap) * 2, width, paneH)
                    .setTitle(translate("gui.krypton_hybrid.section.client"))
                    .setCollapsible(true);
            client.addEntry(this::drawClientEntries);
            activePanels.add(client);
            return;
        }

        int leftW = (int) (width * 0.58f);
        int rightW = width - leftW - gap;
        int rightH = (height - gap) / 2;

        MCPanel hotspots = new MCPanel(left, top, leftW, height)
                .setTitle(translate("gui.krypton_hybrid.section.insights"))
                .setCollapsible(true);
        hotspots.addEntry(this::drawHotspotEntries);
        activePanels.add(hotspots);

        MCPanel security = new MCPanel(left + leftW + gap, top, rightW, rightH)
                .setTitle(translate("gui.krypton_hybrid.section.security"))
                .setCollapsible(true);
        security.addEntry(this::drawSecurityEntries);
        activePanels.add(security);

        MCPanel client = new MCPanel(left + leftW + gap, top + rightH + gap, rightW, rightH)
                .setTitle(translate("gui.krypton_hybrid.section.client"))
                .setCollapsible(true);
        client.addEntry(this::drawClientEntries);
        activePanels.add(client);
    }

    private void drawHotspotEntries(GuiGraphics g, int x, int y, int w, int h) {
        int ly = drawMetric(g, x, y, w, "gui.krypton_hybrid.label.tracked_types",
                String.format("%,d", snap.trackedTypeCount()));
        ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.tracked_mods",
                String.format("%,d", snap.trackedModCount()));
        ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.tracked_coverage",
                String.format("%.1f%%", snap.trackedCoveragePercent()), qualityColor(snap.trackedCoveragePercent(), 85.0, 60.0));
        ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.tracked_type_packets",
                String.format("%,d", snap.totalTrackedTypePackets()));
        ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.tracked_type_bytes",
                NetworkTrafficStats.formatBytes(snap.totalTrackedTypeBytes()));
        ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.tracked_mod_packets",
                String.format("%,d", snap.totalTrackedModPackets()));
        ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.tracked_mod_bytes",
                NetworkTrafficStats.formatBytes(snap.totalTrackedModBytes()));
        ly += 4;
        ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.hottest_packet",
                truncate(snap.hottestPacketType(), w - 140), UITheme.colors().accentLight());
        ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.hottest_packet_bytes",
                NetworkTrafficStats.formatBytes(snap.hottestPacketTypeBytes()) + " / " +
                        String.format("%,d pkt", snap.hottestPacketTypePackets()));
        ly = drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.hottest_mod",
                truncate(snap.hottestModId(), w - 140), UITheme.colors().accentLight());
        drawMetric(g, x, ly, w, "gui.krypton_hybrid.label.hottest_mod_bytes",
                NetworkTrafficStats.formatBytes(snap.hottestModBytes()) + " / " +
                        String.format("%,d pkt", snap.hottestModPackets()));
    }

    private void drawSecurityEntries(GuiGraphics g, int x, int y, int w, int h) {
        var c = UITheme.colors();
        int statusColor = snap.securityEnabled() ? c.successColor() : c.dangerColor();
        int ly = drawMetricWithTooltip(g, x, y, w,
                "gui.krypton_hybrid.label.security_enabled",
                snap.securityEnabled() ? translate("gui.krypton_hybrid.state.enabled") : translate("gui.krypton_hybrid.state.disabled"),
                statusColor,
                List.of(
                        Component.translatable("gui.krypton_hybrid.tooltip.security.enabled.title"),
                        Component.translatable("gui.krypton_hybrid.tooltip.security.enabled.desc")
                ));
        long total = snap.securityTotalEvents();
        ly = drawMetricWithTooltip(g, x, ly, w,
                "gui.krypton_hybrid.label.security_total_events",
                String.format("%,d", total),
                total == 0 ? c.successColor() : c.warningColor(),
                List.of(
                        Component.translatable("gui.krypton_hybrid.tooltip.security.total.title"),
                        Component.translatable("gui.krypton_hybrid.tooltip.security.total.desc")
                ));
        ly = drawMetricWithTooltip(g, x, ly, w,
                "gui.krypton_hybrid.label.security_decomp_bombs",
                String.format("%,d", snap.secDecompressionBombs()),
                inverseQualityColor(snap.secDecompressionBombs(), 0, 3),
                List.of(
                        Component.translatable("gui.krypton_hybrid.tooltip.security.decomp.title"),
                        Component.translatable("gui.krypton_hybrid.tooltip.security.decomp.desc")
                ));
        ly = drawMetricWithTooltip(g, x, ly, w,
                "gui.krypton_hybrid.label.security_handshake_rejected",
                String.format("%,d", snap.secHandshakesRejected()),
                inverseQualityColor(snap.secHandshakesRejected(), 0, 5),
                List.of(
                        Component.translatable("gui.krypton_hybrid.tooltip.security.handshake.title"),
                        Component.translatable("gui.krypton_hybrid.tooltip.security.handshake.desc")
                ));
        ly = drawMetricWithTooltip(g, x, ly, w,
                "gui.krypton_hybrid.label.security_anomaly_disconnects",
                String.format("%,d", snap.secAnomalyDisconnects()),
                inverseQualityColor(snap.secAnomalyDisconnects(), 0, 2),
                List.of(
                        Component.translatable("gui.krypton_hybrid.tooltip.security.anomaly.title"),
                        Component.translatable("gui.krypton_hybrid.tooltip.security.anomaly.desc")
                ));
        drawMetricWithTooltip(g, x, ly, w,
                "gui.krypton_hybrid.label.security_watermark_breaches",
                String.format("%,d", snap.secWatermarkBreaches()),
                inverseQualityColor(snap.secWatermarkBreaches(), 0, 5),
                List.of(
                        Component.translatable("gui.krypton_hybrid.tooltip.security.watermark.title"),
                        Component.translatable("gui.krypton_hybrid.tooltip.security.watermark.desc")
                ));
    }

    private void drawClientEntries(GuiGraphics g, int x, int y, int w, int h) {
        long age = KryptonStatsClientController.latestSnapshotAgeMs();
        long req = KryptonStatsClientController.snapshotRequestCount();
        long recv = KryptonStatsClientController.snapshotReceiveCount();
        long rtt = KryptonStatsClientController.lastRoundTripMs();
        double success = req == 0 ? 100.0 : (recv * 100.0 / (double) req);
        int ly = drawMetricWithTooltip(g, x, y, w,
                "gui.krypton_hybrid.label.client_snapshot_age",
                formatFreshness(age),
                freshnessColor(age),
                List.of(
                        Component.translatable("gui.krypton_hybrid.tooltip.client.age.title"),
                        Component.translatable("gui.krypton_hybrid.tooltip.client.age.desc")
                ));
        ly = drawMetricWithTooltip(g, x, ly, w,
                "gui.krypton_hybrid.label.client_rtt",
                rtt <= 0 ? "--" : rtt + " ms",
                rttColor(rtt),
                List.of(
                        Component.translatable("gui.krypton_hybrid.tooltip.client.rtt.title"),
                        Component.translatable("gui.krypton_hybrid.tooltip.client.rtt.desc")
                ));
        ly = drawMetricWithTooltip(g, x, ly, w,
                "gui.krypton_hybrid.label.client_requests",
                String.format("%,d", req),
                UITheme.colors().textPrimary(),
                List.of(
                        Component.translatable("gui.krypton_hybrid.tooltip.client.requests.title"),
                        Component.translatable("gui.krypton_hybrid.tooltip.client.requests.desc")
                ));
        ly = drawMetricWithTooltip(g, x, ly, w,
                "gui.krypton_hybrid.label.client_received",
                String.format("%,d", recv),
                UITheme.colors().textPrimary(),
                List.of(
                        Component.translatable("gui.krypton_hybrid.tooltip.client.received.title"),
                        Component.translatable("gui.krypton_hybrid.tooltip.client.received.desc")
                ));
        ly = drawMetricWithTooltip(g, x, ly, w,
                "gui.krypton_hybrid.label.client_success_rate",
                String.format("%.1f%%", success),
                qualityColor(success, 95.0, 80.0),
                List.of(
                        Component.translatable("gui.krypton_hybrid.tooltip.client.success.title"),
                        Component.translatable("gui.krypton_hybrid.tooltip.client.success.desc")
                ));
        drawMetricWithTooltip(g, x, ly, w, "gui.krypton_hybrid.label.client_hud",
                com.xinian.KryptonHybrid.client.overlay.KryptonHudOverlay.isVisible()
                        ? translate("gui.krypton_hybrid.state.enabled")
                        : translate("gui.krypton_hybrid.state.disabled"),
                com.xinian.KryptonHybrid.client.overlay.KryptonHudOverlay.isVisible() ? UITheme.colors().successColor() : UITheme.colors().textSecondary(),
                List.of(
                        Component.translatable("gui.krypton_hybrid.tooltip.client.hud.title"),
                        Component.translatable("gui.krypton_hybrid.tooltip.client.hud.desc")
                ));
    }

    private int drawMetricWithTooltip(GuiGraphics g,
                                      int x,
                                      int y,
                                      int width,
                                      String labelKey,
                                      String value,
                                      int valueColor,
                                      List<Component> tooltipLines) {
        int nextY = drawMetric(g, x, y, width, labelKey, value, valueColor);
        if (lastMouseX >= x && lastMouseX <= x + width && lastMouseY >= y && lastMouseY <= nextY) {
            summaryTooltip = tooltipLines;
        }
        return nextY;
    }

    // ─── Mods table renderer ───

    /** Returns [x0, x1, x2, x3, y, h] — three sort-chip ranges and shared y/h. */
    private int[] sortChipBounds(int x, int y, int w) {
        int h = Math.max(16, this.font.lineHeight + 6);
        int gap = 6;
        int third = (w - gap * 2) / 3;
        int x0 = x;
        int x1 = x + third + gap;
        int x2 = x + (third + gap) * 2;
        int x3 = x + w; // right edge of 3rd chip
        return new int[]{x0, x1, x2, x3, y, h};
    }

    private void drawModsContent(GuiGraphics g, int x, int y, int w, int h) {
        var c = UITheme.colors();
        long totalPacketsAll = Math.max(1L, snap.totalTrackedModPackets());

        // Sort chips
        int[] sb = sortChipBounds(x, y, w);
        int chipH = sb[5];
        drawSortChip(g, sb[0], sb[4], sb[1] - sb[0] - 6, chipH, modSort == ModSort.BYTES,   "gui.krypton_hybrid.sort.bytes");
        drawSortChip(g, sb[1], sb[4], sb[2] - sb[1] - 6, chipH, modSort == ModSort.PACKETS, "gui.krypton_hybrid.sort.packets");
        drawSortChip(g, sb[2], sb[4], sb[3] - sb[2],     chipH, modSort == ModSort.NAME,    "gui.krypton_hybrid.sort.name");

        // Totals line
        int headerY = y + chipH + 6;
        long totalBytes = Math.max(1L, snap.totalTrackedModBytes());
        long totalPackets = snap.totalTrackedModPackets();
        String totals = Component.translatable("gui.krypton_hybrid.mods.totals",
                snap.trackedModCount(),
                String.format("%,d", totalPackets),
                NetworkTrafficStats.formatBytes(snap.totalTrackedModBytes())).getString();
        g.drawString(this.font, totals, x, headerY, c.textSecondary(), false);

        // List area
        int listTop = headerY + lineH() + 2;
        int listBottom = y + h;
        int listH = listBottom - listTop;
        modListInnerX = x;
        modListInnerY = listTop;
        modListInnerW = w;
        modListInnerH = listH;

        List<StatsSnapshotPayload.ModEntry> data = sortedMods();
        int rowH = lineH() + 6;
        int totalRows = data.size();
        int visibleRows = Math.max(1, listH / rowH);
        int maxScroll = Math.max(0, totalRows - visibleRows);
        if (modScroll > maxScroll) modScroll = maxScroll;
        if (modScroll < 0) modScroll = 0;

        int rowsToDraw = Math.min(visibleRows, totalRows - modScroll);
        if (totalRows == 0) {
            g.drawString(this.font, Component.translatable("gui.krypton_hybrid.mods.empty").getString(),
                    x, listTop + 4, c.textMuted(), false);
            return;
        }

        // Reserve scrollbar gutter when needed
        boolean hasScrollbar = totalRows > visibleRows;
        int rowW = w - (hasScrollbar ? 8 : 0);

        g.enableScissor(x, listTop, x + rowW, listBottom);
        for (int i = 0; i < rowsToDraw; i++) {
            int idx = modScroll + i;
            StatsSnapshotPayload.ModEntry e = data.get(idx);
            int ry = listTop + i * rowH;

            // Alternating row background
            if ((idx & 1) == 0) {
                UITheme.fillRoundedRect(g, x, ry, rowW, rowH - 2, 3,
                        UITheme.withAlpha(c.widgetBg(), 0x60));
            }

            // Right-side metric (bytes + percent + packets)
            double frac = e.bytes() / (double) totalBytes;
            String right = String.format("%s · %.1f%% · %,d",
                    NetworkTrafficStats.formatBytes(e.bytes()),
                    frac * 100.0,
                    e.packets());
            int rightW = this.font.width(right);

            // Left-side mod id (truncated to fit)
            int idMaxW = rowW - rightW - 16;
            String id = truncate(e.modId(), Math.max(40, idMaxW));
            g.drawString(this.font, id, x + 4, ry + 2, c.textPrimary(), false);
            g.drawString(this.font, right, x + rowW - rightW - 4, ry + 2, c.textSecondary(), false);

            // Proportional traffic bar at the bottom of the row
            int barMax = rowW - 8;
            int barFill = Math.max(1, (int) (barMax * frac));
            int barColor = UITheme.lerpColor(c.accent(), c.accentSecondary(), Math.min(1f, (float) frac * 2f));
            UITheme.fillRoundedRect(g, x + 4, ry + rowH - 5, barMax, 2, 1,
                    UITheme.withAlpha(c.widgetBorder(), 0x60));
            UITheme.fillRoundedRect(g, x + 4, ry + rowH - 5, barFill, 2, 1, barColor);

            if (lastMouseX >= x && lastMouseX <= x + rowW && lastMouseY >= ry && lastMouseY <= ry + rowH - 2) {
                summaryTooltip = modsTooltipForEntry(idx + 1, e, frac, totalPacketsAll);
            }
        }
        g.disableScissor();

        // Scrollbar
        if (hasScrollbar) {
            int trackX = x + w - 4;
            UITheme.fillRoundedRect(g, trackX, listTop, 3, listH, 1, c.scrollbarTrack());
            int thumbH = Math.max(14, listH * visibleRows / totalRows);
            int thumbY = listTop + (listH - thumbH) * modScroll / Math.max(1, maxScroll);
            UITheme.fillRoundedRect(g, trackX, thumbY, 3, thumbH, 1, c.scrollbarThumb());
        }
    }

    private void drawSortChip(GuiGraphics g, int x, int y, int w, int h, boolean active, String key) {
        var c = UITheme.colors();
        int bg = active ? UITheme.withAlpha(c.accent(), 0x40) : UITheme.withAlpha(c.widgetBg(), 0xA0);
        int border = active ? c.accent() : UITheme.withAlpha(c.widgetBorder(), 0xA0);
        int textColor = active ? c.accentLight() : c.textSecondary();
        UITheme.fillRoundedRect(g, x, y, w, h, 4, bg);
        UITheme.drawRoundedBorder(g, x, y, w, h, 4, border);
        String label = Component.translatable(key).getString();
        int tw = Math.min(this.font.width(label), w - 8);
        String safe = truncate(label, w - 8);
        int sw = this.font.width(safe);
        g.drawString(this.font, safe, x + (w - sw) / 2, y + (h - this.font.lineHeight) / 2 + 1, textColor, false);
        // Suppress unused-warning compilation noise
        if (tw < 0) tw = 0;

        if (lastMouseX >= x && lastMouseX <= x + w && lastMouseY >= y && lastMouseY <= y + h) {
            summaryTooltip = sortTooltipForKey(key, active);
        }
    }

    private List<Component> sortTooltipForKey(String key, boolean active) {
        List<Component> lines = new ArrayList<>(3);
        String suffix = key.endsWith("bytes") ? "bytes" : key.endsWith("packets") ? "packets" : "name";
        lines.add(Component.translatable("gui.krypton_hybrid.tooltip.sort." + suffix + ".title"));
        lines.add(Component.translatable("gui.krypton_hybrid.tooltip.sort." + suffix + ".desc"));
        lines.add(Component.translatable("gui.krypton_hybrid.tooltip.sort.state", active
                ? translate("gui.krypton_hybrid.tooltip.sort.active")
                : translate("gui.krypton_hybrid.tooltip.sort.inactive")));
        return lines;
    }

    private List<Component> modsTooltipForEntry(int rank,
                                                StatsSnapshotPayload.ModEntry entry,
                                                double bytesShare,
                                                long totalPacketsAll) {
        List<Component> lines = new ArrayList<>(5);
        long packets = entry.packets();
        long bytes = entry.bytes();
        double packetShare = packets * 100.0 / totalPacketsAll;
        double avg = packets == 0 ? 0.0 : bytes / (double) packets;

        lines.add(Component.translatable("gui.krypton_hybrid.tooltip.mod.title", rank, entry.modId()));
        lines.add(Component.translatable("gui.krypton_hybrid.tooltip.mod.bytes",
                NetworkTrafficStats.formatBytes(bytes), String.format("%.2f%%", bytesShare * 100.0)));
        lines.add(Component.translatable("gui.krypton_hybrid.tooltip.mod.packets",
                String.format("%,d", packets), String.format("%.2f%%", packetShare)));
        lines.add(Component.translatable("gui.krypton_hybrid.tooltip.mod.avg",
                String.format("%.1f B", avg)));
        lines.add(Component.translatable("gui.krypton_hybrid.tooltip.mod.hint"));
        return lines;
    }

    private List<StatsSnapshotPayload.ModEntry> sortedMods() {
        List<StatsSnapshotPayload.ModEntry> mods = new ArrayList<>(snap.topMods());
        Comparator<StatsSnapshotPayload.ModEntry> cmp = switch (modSort) {
            case BYTES   -> Comparator.comparingLong(StatsSnapshotPayload.ModEntry::bytes).reversed();
            case PACKETS -> Comparator.comparingLong(StatsSnapshotPayload.ModEntry::packets).reversed();
            case NAME    -> Comparator.comparing(StatsSnapshotPayload.ModEntry::modId, String.CASE_INSENSITIVE_ORDER);
        };
        mods.sort(cmp);
        return mods;
    }

    // ─── Render ───

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.summaryTooltip = null;

        var c = UITheme.colors();
        renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, this.width, this.height, c.panelBg());

        // Header: title + subtitle stacked, aligned to font line height
        int lh = this.font.lineHeight;
        g.drawString(this.font, this.title, 14, 8, c.textPrimary(), true);
        g.drawString(this.font,
                Component.translatable("gui.krypton_hybrid.subtitle").getString(),
                14, 8 + lh + 2, c.textSecondary(), false);

        // Active tab underline
        for (int i = 0; i < tabButtons.size(); i++) {
            MCButton b = tabButtons.get(i);
            if (Tab.values()[i] == currentTab) {
                int underlineY = b.getY() + b.getHeight() + 1;
                UITheme.fillRoundedRect(g, b.getX() + 4, underlineY, b.getWidth() - 8, 2, 1, c.accent());
            }
        }

        for (MCPanel p : activePanels) p.render(g, mouseX, mouseY);

        if (summaryTooltip != null && !summaryTooltip.isEmpty()) {
            renderModernTooltip(g, summaryTooltip, mouseX + 12, mouseY + 10);
        }

        // Footer hint sits just above the action buttons.
        int btnH = Math.max(20, lh + 10);
        int hintY = this.height - btnH - 8 - lineH();
        Component hint = Component.translatable(
                "gui.krypton_hybrid.footer_hint",
                KryptonStatsClientController.openStatsKey().getTranslatedKeyMessage());
        String hintText = truncate(hint.getString(), this.width - 24);
        g.drawCenteredString(this.font, hintText, this.width / 2, hintY, c.textMuted());

        for (var renderable : this.renderables) renderable.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Sort chip click in Mods tab — uses the same geometry as the renderer.
        if (currentTab == Tab.MODS && !activePanels.isEmpty()) {
            MCPanel panel = activePanels.get(0);
            int cx = panel.getX() + 12;
            int cy = panel.getContentStartY();
            int cw = panel.getContentWidth();
            int[] sb = sortChipBounds(cx, cy, cw);
            if (mouseY >= sb[4] && mouseY <= sb[4] + sb[5]) {
                if (mouseX >= sb[0] && mouseX < sb[1] - 6) { modSort = ModSort.BYTES;   return true; }
                if (mouseX >= sb[1] && mouseX < sb[2] - 6) { modSort = ModSort.PACKETS; return true; }
                if (mouseX >= sb[2] && mouseX < sb[3])     { modSort = ModSort.NAME;    return true; }
            }
        }
        for (MCPanel p : activePanels) {
            if (p.handleClick(mouseX, mouseY)) return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (currentTab == Tab.MODS
                && mouseX >= modListInnerX && mouseX <= modListInnerX + modListInnerW
                && mouseY >= modListInnerY && mouseY <= modListInnerY + modListInnerH) {
            modScroll -= (int) Math.signum(scrollY);
            if (modScroll < 0) modScroll = 0;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ─── Drawing helpers ───

    private void drawChipStrip(GuiGraphics g, int x, int y, int w, long ageMs, double bundleHit) {
        var c = UITheme.colors();
        String[][] chips = {
                {translate("gui.krypton_hybrid.label.algorithm"), snap.compressionAlgorithm()},
                {translate("gui.krypton_hybrid.label.bandwidth_saving"),
                        String.format("%.2f%%", snap.savingPercent())},
                {translate("gui.krypton_hybrid.label.bundle_hit_rate"),
                        String.format("%.1f%%", bundleHit)},
                {translate("gui.krypton_hybrid.label.snapshot_age"),
                        formatFreshness(ageMs)},
        };
        int[] colors = {
                c.accentLight(),
                qualityColor(snap.savingPercent(), 30.0, 10.0),
                qualityColor(bundleHit, 70.0, 35.0),
                freshnessColor(ageMs)
        };

        int cols = w < 380 ? 2 : 4;
        int gap = 6;
        int chipW = (w - gap * (cols - 1)) / cols;
        int chipH = this.font.lineHeight * 2 + 8;
        for (int i = 0; i < chips.length; i++) {
            int cx = x + (i % cols) * (chipW + gap);
            int cy = y + (i / cols) * (chipH + gap);
            UITheme.fillRoundedRect(g, cx, cy, chipW, chipH, 4, c.widgetBg());
            UITheme.drawRoundedBorder(g, cx, cy, chipW, chipH, 4, UITheme.withAlpha(c.widgetBorder(), 0xC0));
            UITheme.fillRoundedRect(g, cx, cy, 3, chipH, 1, colors[i]);

            // Two-line centered layout: title + value for better visual alignment.
            String chipLabel = truncate(chips[i][0], chipW - 10);
            int labelW = this.font.width(chipLabel);
            int labelX = cx + Math.max(6, (chipW - labelW) / 2);
            g.drawString(this.font, chipLabel, labelX, cy + 3, c.textMuted(), false);
            String val = truncate(chips[i][1], chipW - 14);
            int vw = this.font.width(val);
            int valX = cx + Math.max(6, (chipW - vw) / 2);
            g.drawString(this.font, val, valX, cy + chipH - this.font.lineHeight - 3, colors[i], false);

            if (lastMouseX >= cx && lastMouseX <= cx + chipW && lastMouseY >= cy && lastMouseY <= cy + chipH) {
                summaryTooltip = summaryTooltipForIndex(i, ageMs, bundleHit);
            }
        }
    }

    private List<Component> summaryTooltipForIndex(int chipIndex, long ageMs, double bundleHit) {
        List<Component> lines = new ArrayList<>(3);
        switch (chipIndex) {
            case 0 -> {
                lines.add(Component.translatable("gui.krypton_hybrid.tooltip.algorithm.title"));
                lines.add(Component.translatable("gui.krypton_hybrid.tooltip.algorithm.desc"));
                lines.add(Component.translatable("gui.krypton_hybrid.tooltip.algorithm.value", snap.compressionAlgorithm()));
            }
            case 1 -> {
                lines.add(Component.translatable("gui.krypton_hybrid.tooltip.saving.title"));
                lines.add(Component.translatable("gui.krypton_hybrid.tooltip.saving.desc"));
                lines.add(Component.translatable("gui.krypton_hybrid.tooltip.saving.value",
                        String.format("%.2f%%", snap.savingPercent()),
                        NetworkTrafficStats.formatBytes(snap.savedBytes())));
            }
            case 2 -> {
                lines.add(Component.translatable("gui.krypton_hybrid.tooltip.bundle_hit.title"));
                lines.add(Component.translatable("gui.krypton_hybrid.tooltip.bundle_hit.desc"));
                lines.add(Component.translatable("gui.krypton_hybrid.tooltip.bundle_hit.value",
                        String.format("%.1f%%", bundleHit),
                        String.format("%,d", snap.bundlesEmitted()),
                        String.format("%,d", snap.bundleBatchesObserved())));
            }
            case 3 -> {
                lines.add(Component.translatable("gui.krypton_hybrid.tooltip.snapshot_age.title"));
                lines.add(Component.translatable("gui.krypton_hybrid.tooltip.snapshot_age.desc"));
                lines.add(Component.translatable("gui.krypton_hybrid.tooltip.snapshot_age.value",
                        formatFreshness(ageMs),
                        KryptonStatsClientController.lastRoundTripMs() <= 0
                                ? "--"
                                : KryptonStatsClientController.lastRoundTripMs() + " ms"));
            }
            default -> {
                lines.add(Component.literal("-"));
            }
        }
        return lines;
    }

    private void renderModernTooltip(GuiGraphics g, List<Component> lines, int x, int y) {
        int maxW = 0;
        for (Component line : lines) maxW = Math.max(maxW, this.font.width(line));
        int pad = 6;
        int width = maxW + pad * 2;
        int height = lines.size() * this.font.lineHeight + pad * 2 + (lines.size() - 1);

        int tx = Math.min(x, this.width - width - 6);
        int ty = Math.min(y, this.height - height - 6);
        UITheme.renderTooltipBackground(g, tx, ty, width, height);

        var c = UITheme.colors();
        int cy = ty + pad;
        for (int i = 0; i < lines.size(); i++) {
            int color = (i == 0) ? c.accentLight() : (i == lines.size() - 1 ? c.textPrimary() : c.textSecondary());
            g.drawString(this.font, lines.get(i), tx + pad, cy, color, false);
            cy += this.font.lineHeight + 1;
        }
    }

    private int drawSavingBar(GuiGraphics g, int x, int y, int w, double percent) {
        var c = UITheme.colors();
        int barH = 10;
        UITheme.fillRoundedRect(g, x, y, w, barH, 3, c.widgetBg());
        double clamped = Math.max(0.0, Math.min(100.0, percent));
        int fillW = (int) (w * (clamped / 100.0));
        int color = qualityColor(percent, 30.0, 10.0);
        if (fillW > 1) {
            UITheme.fillRoundedRect(g, x, y, fillW, barH, 3, color);
            UITheme.fillRoundedRect(g, x, y, fillW, barH / 2, 3, UITheme.brighten(color, 0.25f));
        }
        UITheme.drawRoundedBorder(g, x, y, w, barH, 3, UITheme.withAlpha(c.widgetBorder(), 0x80));
        String label = Component.translatable("gui.krypton_hybrid.label.bar_saved",
                String.format("%.2f%%", percent)).getString();
        int lw = this.font.width(label);
        g.drawString(this.font, label, x + w - lw, y + barH + 2, c.textSecondary(), false);
        return y + barH + lineH() + 2;
    }

    private int drawMetric(GuiGraphics g, int x, int y, int width, String labelKey, String value) {
        return drawMetric(g, x, y, width, labelKey, value, UITheme.colors().textPrimary());
    }

    private int drawMetric(GuiGraphics g, int x, int y, int width, String labelKey, String value, int valueColor) {
        var c = UITheme.colors();
        String label = Component.translatable(labelKey).getString();
        int labelW = this.font.width(label);
        int valueW = this.font.width(value);
        int gap = 8;
        // If label + value collide, wrap value to a second line right-aligned.
        if (labelW + gap + valueW > width) {
            g.drawString(this.font, truncate(label, width), x, y, c.textSecondary(), false);
            String safe = truncate(value, width - 2);
            int sw = this.font.width(safe);
            g.drawString(this.font, safe, x + width - sw, y + lineH(), valueColor, false);
            return y + lineH() * 2;
        }
        g.drawString(this.font, label, x, y, c.textSecondary(), false);
        String safe = truncate(value, Math.max(40, width - labelW - gap));
        int sw = this.font.width(safe);
        g.drawString(this.font, safe, x + width - sw, y, valueColor, false);
        return y + lineH();
    }

    // ─── Utility ───

    /** Approximate panel header height (matches MCPanel TITLE_BAR_HEIGHT + padding). */
    private static int MCPanelHeader() { return 24 + 12; }

    private static String translate(String key) { return Component.translatable(key).getString(); }

    private static String tabKey(Tab t) {
        return switch (t) {
            case OVERVIEW -> "gui.krypton_hybrid.tab.overview";
            case NETWORK -> "gui.krypton_hybrid.tab.network";
            case COMPRESSION -> "gui.krypton_hybrid.tab.compression";
            case MODS -> "gui.krypton_hybrid.tab.mods";
            case INSIGHTS -> "gui.krypton_hybrid.tab.insights";
        };
    }

    private static Component themeLabel() {
        return Component.translatable(UITheme.getMode() == UITheme.Mode.DARK
                ? "gui.krypton_hybrid.theme.dark"
                : "gui.krypton_hybrid.theme.light");
    }

    private static String formatDuration(long seconds) {
        return Component.translatable("gui.krypton_hybrid.value.seconds", String.format("%,d", seconds)).getString();
    }

    private static String formatBytesRate(long total, long perSecond) {
        return NetworkTrafficStats.formatBytes(total) + " (" + NetworkTrafficStats.formatBytes(perSecond) + "/s)";
    }

    private static int freshnessColor(long ageMs) {
        var c = UITheme.colors();
        if (ageMs <= 2000L) return c.successColor();
        if (ageMs <= 6000L) return c.warningColor();
        return c.dangerColor();
    }

    private static int qualityColor(double value, double goodThreshold, double warnThreshold) {
        var c = UITheme.colors();
        if (value >= goodThreshold) return c.successColor();
        if (value >= warnThreshold) return c.warningColor();
        return c.dangerColor();
    }

    private static int inverseQualityColor(double value, double goodThreshold, double warnThreshold) {
        var c = UITheme.colors();
        if (value <= goodThreshold) return c.successColor();
        if (value <= warnThreshold) return c.warningColor();
        return c.dangerColor();
    }

    private static int ratioColor(double ratio) {
        // Lower ratio is better (wire/orig).
        return inverseQualityColor(ratio, 0.75, 0.90);
    }

    private static int rttColor(long rttMs) {
        if (rttMs <= 0) return UITheme.colors().textSecondary();
        return inverseQualityColor(rttMs, 120.0, 250.0);
    }

    private static String formatFreshness(long ageMs) {
        if (ageMs == Long.MAX_VALUE) return "--";
        if (ageMs < 1000L) return ageMs + " ms";
        return String.format("%.1f s", ageMs / 1000.0);
    }

    private String truncate(String text, int maxWidth) {
        if (text == null || text.isEmpty()) return "-";
        if (this.font.width(text) <= maxWidth) return text;
        String ell = "...";
        int end = text.length();
        while (end > 1 && this.font.width(text.substring(0, end) + ell) > maxWidth) end--;
        return text.substring(0, Math.max(1, end)) + ell;
    }
}

