package com.xinian.KryptonHybrid.client.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.xinian.KryptonHybrid.client.KryptonClientConfig;
import com.xinian.KryptonHybrid.client.KryptonStatsClientController;
import com.xinian.KryptonHybrid.client.KryptonStatsHistory;
import com.xinian.KryptonHybrid.client.compat.KryptonGuiGraphics;
import com.xinian.KryptonHybrid.client.overlay.KryptonHudOverlay;
import com.xinian.KryptonHybrid.client.ui.Anim;
import com.xinian.KryptonHybrid.client.ui.Charts;
import com.xinian.KryptonHybrid.client.ui.Fmt;
import com.xinian.KryptonHybrid.client.ui.GeometryCache;
import com.xinian.KryptonHybrid.client.ui.Shapes;
import com.xinian.KryptonHybrid.client.ui.UITheme;
import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Krypton stats dashboard: a translucent floating panel over the game world.
 *
 * <p>Everything is drawn from {@link KryptonStatsClientController#latestSnapshot()}
 * and {@link KryptonStatsHistory} on every frame, so refreshed data shows up
 * immediately. Clickable regions are registered while drawing and hit-tested on
 * the next click.</p>
 *
 * <p>Tabs: overview (live KPIs, outbound chart, top mods), traffic (packet-rate
 * chart, outbound/inbound breakdown, batching meters), mods (filterable, sortable
 * table), security (all guard counters plus client diagnostics). The gear button
 * swaps the content for the settings view.</p>
 */
public final class KryptonStatsScreen extends Screen {

    private enum Tab { OVERVIEW, TRAFFIC, MODS, SECURITY }
    private enum ModSort { NAME, BYTES, PACKETS, AVG }

    private static final long AUTO_REFRESH_MS = 1000L;
    private static final int HEADER_H = 20;
    private static final int TABS_H = 16;
    private static final int FOOTER_H = 14;
    private static final int PAD = 10;
    private static final float CARD_RADIUS = 5F;
    private static final int[] OPACITY_STEPS = {55, 70, 85, 100};
    private static final long[] HUD_REFRESH_STEPS = {1000L, 2000L, 5000L};

    /** Remembered for the session so reopening lands on the same tab. */
    private static Tab lastTab = Tab.OVERVIEW;

    private Tab tab = lastTab;
    private boolean settingsOpen;
    private ModSort modSort = ModSort.BYTES;
    private boolean modSortAscending;
    private int modScroll;
    private String modFilterText = "";
    private EditBox modFilter;
    private long lastAutoRefreshMs;

    private int px, py, pw, ph;
    private int cx, cy, cw, ch;
    private int modListX, modListY, modListW, modListH;

    private final List<Hit> hits = new ArrayList<>();
    private final Anim anim = new Anim();
    private final Map<String, String> translations = new HashMap<>();
    private List<Component> tooltip;

    // Sorted/filtered mod lists, rebuilt only when the snapshot (by identity) or the view changes.
    private StatsSnapshotPayload topModsSnapshot;
    private List<StatsSnapshotPayload.ModEntry> topModsCache = List.of();
    private StatsSnapshotPayload modsSnapshot;
    private String modsFilter;
    private ModSort modsSort;
    private boolean modsAscending;
    private List<StatsSnapshotPayload.ModEntry> modsCache = List.of();
    private int mouseX, mouseY;

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    public KryptonStatsScreen() {
        super(Component.translatable("gui.krypton_hybrid.dash.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        translations.clear();
        pw = clamp(this.width - 32, 300, 540);
        ph = clamp(this.height - 28, 200, 340);
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;
        cx = px + PAD;
        cy = py + HEADER_H + TABS_H + 6;
        cw = pw - PAD * 2;
        ch = ph - HEADER_H - TABS_H - 6 - FOOTER_H - 4;

        modFilter = new EditBox(this.font, cx + 4, cy + 3, 110, 10,
                Component.translatable("gui.krypton_hybrid.dash.mods.filter"));
        modFilter.setBordered(false);
        modFilter.setMaxLength(64);
        modFilter.setValue(modFilterText);
        modFilter.setTextColor(UITheme.colors().textPrimary());
        modFilter.setResponder(value -> {
            modFilterText = value;
            modScroll = 0;
        });
        addRenderableWidget(modFilter);
    }

    @Override
    public void tick() {
        modFilter.tick();
        long now = System.currentTimeMillis();
        if (now - lastAutoRefreshMs >= AUTO_REFRESH_MS) {
            lastAutoRefreshMs = now;
            KryptonStatsClientController.requestFreshSnapshot();
        }
    }

    @Override
    public void removed() {
        lastTab = tab;
        GeometryCache.release("dash.");
    }

    // ------------------------------------------------------------------
    //  Render
    // ------------------------------------------------------------------

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.hits.clear();
        this.tooltip = null;
        anim.frame();

        KryptonGuiGraphics g = new KryptonGuiGraphics(poseStack);
        g.beginBatch();
        var c = UITheme.colors();
        StatsSnapshotPayload snap = KryptonStatsClientController.latestSnapshot();

        g.fill(0, 0, this.width, this.height, 0x48000000);
        Shapes.roundRect(g, px, py, pw, ph, 8, UITheme.panelBackground());
        Shapes.roundRectStroke(g, px, py, pw, ph, 8, stroke(), UITheme.withAlpha(c.widgetBorder(), 0xD0));

        renderHeader(g);
        renderTabs(g);

        boolean showFilter = !settingsOpen && tab == Tab.MODS && snap != null;
        modFilter.visible = showFilter;
        modFilter.setTextColor(c.textPrimary());
        if (!showFilter && modFilter.isFocused()) {
            modFilter.setFocus(false);
        }

        if (!settingsOpen && snap != null && tab == Tab.MODS) {
            // Clips its own list; GuiComponent scissors do not nest.
            renderMods(g, snap);
        } else {
            g.enableScissor(px + 2, cy - 2, px + pw - 2, cy + ch + 2);
            if (settingsOpen) {
                renderSettings(g);
            } else if (snap == null) {
                drawCentered(g, translate("gui.krypton_hybrid.dash.waiting"), cx + cw / 2, cy + ch / 2 - 4, c.textMuted());
            } else {
                switch (tab) {
                    case OVERVIEW -> renderOverview(g, snap);
                    case TRAFFIC -> renderTraffic(g, snap);
                    case SECURITY -> renderSecurity(g, snap);
                    default -> { }
                }
            }
            g.disableScissor();
        }

        renderFooter(g, snap);
        // Widgets (the mod filter box) draw through vanilla, on top of the panel.
        g.flush();
        super.render(poseStack, mouseX, mouseY, partialTick);

        if (tooltip != null && !tooltip.isEmpty()) {
            renderTooltip(g, tooltip, mouseX + 10, mouseY + 10);
        }
        g.endBatch();
    }

    private void renderHeader(KryptonGuiGraphics g) {
        var c = UITheme.colors();
        int ty = py + 7;
        Component title = this.title.copy().withStyle(ChatFormatting.BOLD);
        g.drawString(this.font, title, px + PAD, ty, c.textPrimary(), false);

        int right = px + pw - 6;
        int btn = 14;
        int by = py + 4;
        iconButton(g, right - btn, by, btn, "✕", false,
                List.of(Component.translatable("gui.krypton_hybrid.dash.close")), this::onClose);
        iconButton(g, right - btn * 2 - 2, by, btn, "⚙", settingsOpen,
                List.of(Component.translatable("gui.krypton_hybrid.dash.settings")),
                () -> settingsOpen = !settingsOpen);
        iconButton(g, right - btn * 3 - 4, by, btn, "↻", false,
                List.of(Component.translatable("gui.krypton_hybrid.dash.refresh"),
                        Component.translatable("gui.krypton_hybrid.dash.refresh.desc")),
                KryptonStatsClientController::requestFreshSnapshot);
        int pillLimit = right - btn * 3 - 10;

        int x = px + PAD + this.font.width(title) + 8;
        KryptonStatsClientController.Codec codec = KryptonStatsClientController.currentCodec();
        x = pill(g, x, ty, pillLimit, translate("gui.krypton_hybrid.dash.codec." + codec.name().toLowerCase(Locale.ROOT)),
                c.accentLight(), List.of(
                        Component.translatable("gui.krypton_hybrid.dash.codec.title"),
                        Component.translatable("gui.krypton_hybrid.dash.codec.desc")));

        boolean receiving = KryptonStatsClientController.latestSnapshotAgeMs() <= 10_000L;
        String live = translate(receiving ? "gui.krypton_hybrid.dash.live" : "gui.krypton_hybrid.dash.live.waiting");
        x = pill(g, x, ty, pillLimit, "● " + live, receiving ? c.successColor() : c.textMuted(), List.of(
                Component.translatable("gui.krypton_hybrid.dash.live.title"),
                Component.translatable("gui.krypton_hybrid.dash.live.desc")));

        long rtt = KryptonStatsClientController.lastRoundTripMs();
        if (rtt > 0) {
            String text = "RTT " + rtt + " ms";
            if (x + this.font.width(text) <= pillLimit) {
                g.drawString(this.font, text, x + 2, ty, c.textMuted(), false);
            }
        }
    }

    private void renderTabs(KryptonGuiGraphics g) {
        var c = UITheme.colors();
        int y = py + HEADER_H;
        Shapes.hairline(g, px + PAD, px + pw - PAD, y + TABS_H - 0.5F, UITheme.withAlpha(c.divider(), 0xA0));
        int x = px + PAD;
        float activeX = -1;
        float activeW = 0;
        for (Tab t : Tab.values()) {
            String label = translate("gui.krypton_hybrid.dash.tab." + t.name().toLowerCase(Locale.ROOT));
            int w = this.font.width(label) + 12;
            boolean active = t == tab && !settingsOpen;
            float hover = anim.hover("tab." + t.name(), inside(x, y, w, TABS_H - 1));
            if (!active && hover > 0.01F) {
                Shapes.roundRect(g, x + 1, y + 1, w - 2, TABS_H - 5, 4, UITheme.withAlpha(c.accent(), (int) (0x26 * hover)));
            }
            int color = active ? c.textPrimary() : UITheme.lerpColor(c.textSecondary(), c.accentLight(), hover);
            g.drawString(this.font, label, x + 6, y + 3, color, false);
            if (active) {
                activeX = x + 4;
                activeW = w - 8;
            }
            final Tab target = t;
            hits.add(new Hit(x, y, w, TABS_H, () -> {
                tab = target;
                settingsOpen = false;
                modScroll = 0;
            }));
            x += w + 2;
        }
        if (activeX >= 0) {
            // The underline slides between tabs instead of jumping.
            float ux = anim.approach("tab.underline.x", activeX, 18F);
            float uw = anim.approach("tab.underline.w", activeW, 18F);
            Shapes.pill(g, ux, y + TABS_H - 2.5F, uw, 2.5F, c.accent());
        }
    }

    private void renderFooter(KryptonGuiGraphics g, StatsSnapshotPayload snap) {
        var c = UITheme.colors();
        int y = py + ph - FOOTER_H;
        Shapes.hairline(g, px + PAD, px + pw - PAD, y - 3, UITheme.withAlpha(c.divider(), 0x80));
        String left = snap == null
                ? translate("gui.krypton_hybrid.dash.footer.scope")
                : Component.translatable("gui.krypton_hybrid.dash.footer.uptime", Fmt.duration(snap.elapsedSeconds())).getString();
        String right = Component.translatable("gui.krypton_hybrid.dash.footer.keys",
                KryptonStatsClientController.openStatsKey().getTranslatedKeyMessage()).getString();
        int rightW = this.font.width(right);
        g.drawString(this.font, Fmt.truncate(this.font, left, pw - PAD * 2 - rightW - 8), px + PAD, y + 1, c.textMuted(), false);
        g.drawString(this.font, right, px + pw - PAD - rightW, y + 1, c.textMuted(), false);
    }

    // ------------------------------------------------------------------
    //  Overview
    // ------------------------------------------------------------------

    private void renderOverview(KryptonGuiGraphics g, StatsSnapshotPayload snap) {
        var c = UITheme.colors();
        KryptonStatsHistory.Sample live = KryptonStatsHistory.latest();

        int cols = cw >= 380 ? 4 : 2;
        int gap = 6;
        int cardW = (cw - gap * (cols - 1)) / cols;
        int cardH = 36;
        String approx = "~";
        String[] labels = {
                "gui.krypton_hybrid.dash.kpi.saving",
                "gui.krypton_hybrid.dash.kpi.out",
                "gui.krypton_hybrid.dash.kpi.in",
                "gui.krypton_hybrid.dash.kpi.pps"
        };
        String[] values = {
                Fmt.percent(snap.savingPercent()),
                live != null ? Fmt.rate(live.outWireBps()) : approx + Fmt.rate(snap.wireBytesPerSecond()),
                live != null ? Fmt.rate(live.inBps()) : approx + Fmt.rate(snap.receivedBytesPerSecond()),
                live != null ? Fmt.perSecond(live.outPps()) : approx + Fmt.perSecond(snap.packetsSentPerSecond())
        };
        int[] colors = {
                qualityColor(snap.savingPercent(), 30.0, 10.0),
                c.accent(),
                c.accentTertiary(),
                c.accentLight()
        };
        double[][] series = {
                KryptonStatsHistory.series(KryptonStatsHistory.Field.SAVING),
                KryptonStatsHistory.series(KryptonStatsHistory.Field.OUT_WIRE),
                KryptonStatsHistory.series(KryptonStatsHistory.Field.IN),
                KryptonStatsHistory.series(KryptonStatsHistory.Field.OUT_PPS)
        };
        long dataVersion = KryptonStatsHistory.version();
        for (int i = 0; i < 4; i++) {
            int x = cx + (i % cols) * (cardW + gap);
            int y = cy + (i / cols) * (cardH + gap);
            float hover = anim.hover("kpi." + i, inside(x, y, cardW, cardH));
            Shapes.roundRect(g, x, y, cardW, cardH, CARD_RADIUS, cardBackground());
            if (hover > 0.01F) {
                Shapes.roundRectStroke(g, x, y, cardW, cardH, CARD_RADIUS, stroke(),
                        UITheme.withAlpha(colors[i], (int) (0xB0 * hover)));
            }
            g.drawString(this.font, Fmt.truncate(this.font, translate(labels[i]), cardW - 10), x + 6, y + 4, c.textMuted(), false);
            Component value = Component.literal(Fmt.truncate(this.font, values[i], cardW - 12)).withStyle(ChatFormatting.BOLD);
            g.drawString(this.font, value, x + 6, y + 14, i == 0 ? colors[0] : c.textPrimary(), false);
            Charts.sparkline(g, "dash.kpi." + i, dataVersion, x + 6, y + 26, cardW - 12, 7, series[i], colors[i]);
            if (inside(x, y, cardW, cardH)) {
                tooltip = List.of(
                        Component.translatable(labels[i]),
                        Component.translatable(labels[i] + ".desc"));
            }
        }

        int rows = (4 + cols - 1) / cols;
        int top = cy + rows * (cardH + gap);
        int stripY = cy + ch - 10;
        int boxH = stripY - 6 - top;
        boolean wide = cw >= 380;
        int chartW = wide ? (int) (cw * 0.6f) : cw;

        String chartTitle = Component.translatable("gui.krypton_hybrid.dash.chart.out",
                KryptonStatsHistory.spanSeconds()).getString();
        renderRateChart(g, "dash.chart.out", cx, top, chartW, boxH, chartTitle,
                new double[][]{
                        KryptonStatsHistory.series(KryptonStatsHistory.Field.OUT_WIRE),
                        KryptonStatsHistory.series(KryptonStatsHistory.Field.OUT_ORIGINAL)
                },
                new int[]{c.accent(), c.textMuted()},
                new String[]{"gui.krypton_hybrid.dash.legend.wire", "gui.krypton_hybrid.dash.legend.original"},
                true);

        if (wide) {
            int bx = cx + chartW + gap;
            int bw = cw - chartW - gap;
            renderTopMods(g, snap, bx, top, bw, boxH);
        }

        long totalEvents = snap.securityTotalEvents();
        String[] stripText = {
                Component.translatable("gui.krypton_hybrid.dash.strip.bundle", Fmt.percent(snap.bundleHitRatePercent())).getString(),
                Component.translatable("gui.krypton_hybrid.dash.strip.coalesce", Fmt.percent(snap.coalesceDropRatePercent())).getString(),
                Component.translatable("gui.krypton_hybrid.dash.strip.avg",
                        Fmt.bytes(snap.averageOriginalPacketBytes()), Fmt.bytes(snap.averageWirePacketBytes())).getString(),
                Component.translatable("gui.krypton_hybrid.dash.strip.security", Fmt.count(totalEvents)).getString()
        };
        int x = cx;
        for (int i = 0; i < stripText.length; i++) {
            int color = i == 3 ? (totalEvents == 0 ? c.successColor() : c.warningColor()) : c.textSecondary();
            if (x + this.font.width(stripText[i]) > cx + cw) {
                break;
            }
            g.drawString(this.font, stripText[i], x, stripY, color, false);
            x += this.font.width(stripText[i]) + 12;
        }
    }

    private void renderTopMods(KryptonGuiGraphics g, StatsSnapshotPayload snap, int x, int y, int w, int h) {
        var c = UITheme.colors();
        Shapes.roundRect(g, x, y, w, h, CARD_RADIUS, cardBackground());
        g.drawString(this.font, translate("gui.krypton_hybrid.dash.topmods"), x + 6, y + 5, c.textSecondary(), false);
        if (snap != topModsSnapshot) {
            List<StatsSnapshotPayload.ModEntry> sorted = new ArrayList<>(snap.topMods());
            sorted.sort(Comparator.comparingLong(StatsSnapshotPayload.ModEntry::bytes).reversed());
            topModsCache = sorted;
            topModsSnapshot = snap;
        }
        List<StatsSnapshotPayload.ModEntry> mods = topModsCache;
        long total = Math.max(1L, snap.totalTrackedModBytes());
        int rowH = 13;
        int maxRows = Math.max(0, (h - 18) / rowH);
        if (mods.isEmpty()) {
            g.drawString(this.font, translate("gui.krypton_hybrid.dash.mods.empty"), x + 6, y + 20, c.textMuted(), false);
            return;
        }
        int nameW = Math.max(40, (w - 12) * 2 / 5);
        for (int i = 0; i < Math.min(maxRows, mods.size()); i++) {
            StatsSnapshotPayload.ModEntry e = mods.get(i);
            int ry = y + 18 + i * rowH;
            double share = e.bytes() / (double) total;
            String pct = Fmt.percent(share * 100.0);
            int pctW = this.font.width(pct);
            g.drawString(this.font, Fmt.truncate(this.font, e.modId(), nameW - 4), x + 6, ry, c.textPrimary(), false);
            int barX = x + 6 + nameW;
            int barW = w - 12 - nameW - pctW - 6;
            if (barW > 8) {
                Charts.bar(g, barX, ry + 2, barW, 4, share, c.accent());
            }
            g.drawString(this.font, pct, x + w - 6 - pctW, ry, c.textSecondary(), false);
            if (inside(x, ry - 2, w, rowH)) {
                tooltip = modTooltip(i + 1, e, snap);
            }
        }
    }

    /** A titled chart box with legend and a y-axis maximum label. */
    private void renderRateChart(KryptonGuiGraphics g, String slot, int x, int y, int w, int h, String title,
                                 double[][] series, int[] colors, String[] legendKeys, boolean bytes) {
        var c = UITheme.colors();
        Shapes.roundRect(g, x, y, w, h, CARD_RADIUS, cardBackground());
        g.drawString(this.font, Fmt.truncate(this.font, title, w / 2), x + 6, y + 5, c.textSecondary(), false);

        int lx = x + w - 6;
        for (int i = legendKeys.length - 1; i >= 0; i--) {
            String text = translate(legendKeys[i]);
            int tw = this.font.width(text);
            lx -= tw;
            g.drawString(this.font, text, lx, y + 5, c.textMuted(), false);
            lx -= 10;
            Shapes.pill(g, lx, y + 7.5F, 7, 2.5F, colors[i]);
            lx -= 6;
        }

        int chartX = x + 6;
        int chartY = y + 17;
        int chartW = w - 12;
        int chartH = h - 22;
        if (chartH < 12) {
            return;
        }
        if (KryptonStatsHistory.size() < 2) {
            drawCentered(g, translate("gui.krypton_hybrid.dash.chart.collecting"), x + w / 2, chartY + chartH / 2 - 4, c.textMuted());
            return;
        }
        double max = Charts.niceMax(Charts.max(series));
        Charts.lineChart(g, slot, KryptonStatsHistory.version(), chartX, chartY, chartW, chartH, max, series, colors);
        String axis = bytes ? Fmt.rate(max) : Fmt.perSecond(max);
        g.drawString(this.font, axis, chartX + 2, chartY + 1, UITheme.withAlpha(c.textMuted(), 0xC0), false);
    }

    // ------------------------------------------------------------------
    //  Traffic
    // ------------------------------------------------------------------

    private void renderTraffic(KryptonGuiGraphics g, StatsSnapshotPayload snap) {
        var c = UITheme.colors();
        KryptonStatsHistory.Sample live = KryptonStatsHistory.latest();
        int gap = 6;

        int meterH = 26;
        // The outbound column needs 96 px for all seven rows; the chart takes what is left.
        int chartH = clamp(ch - 96 - gap - meterH - gap, 40, ch * 40 / 100);
        String chartTitle = Component.translatable("gui.krypton_hybrid.dash.chart.pps",
                KryptonStatsHistory.spanSeconds()).getString();
        renderRateChart(g, "dash.chart.pps", cx, cy, cw, chartH, chartTitle,
                new double[][]{
                        KryptonStatsHistory.series(KryptonStatsHistory.Field.OUT_PPS),
                        KryptonStatsHistory.series(KryptonStatsHistory.Field.IN_PPS)
                },
                new int[]{c.accent(), c.accentTertiary()},
                new String[]{"gui.krypton_hybrid.dash.legend.out", "gui.krypton_hybrid.dash.legend.in"},
                false);

        int colTop = cy + chartH + gap;
        int colH = ch - chartH - gap - meterH - gap;
        int colW = (cw - gap) / 2;

        sectionBox(g, cx, colTop, colW, colH, "gui.krypton_hybrid.dash.section.out");
        metricRows(g, cx + 6, colTop + 17, colW - 12, colTop + colH - 2, List.of(
                new MetricRow("gui.krypton_hybrid.dash.m.live_rate",
                        live != null ? Fmt.rate(live.outWireBps()) : "--", c.accentLight()),
                new MetricRow("gui.krypton_hybrid.dash.m.original_total", Fmt.bytes(snap.bytesSentOriginal()), c.textPrimary()),
                new MetricRow("gui.krypton_hybrid.dash.m.wire_total", Fmt.bytes(snap.bytesSentWire()), c.textPrimary()),
                new MetricRow("gui.krypton_hybrid.dash.m.saved",
                        Fmt.bytes(snap.savedBytes()) + " (" + Fmt.percent(snap.savingPercent()) + ")",
                        qualityColor(snap.savingPercent(), 30.0, 10.0)),
                new MetricRow("gui.krypton_hybrid.dash.m.ratio",
                        String.format(Locale.ROOT, "%.3f", snap.compressionRatio()), c.textPrimary()),
                new MetricRow("gui.krypton_hybrid.dash.m.avg_packet",
                        Fmt.bytes(snap.averageOriginalPacketBytes()) + " → " + Fmt.bytes(snap.averageWirePacketBytes()), c.textPrimary()),
                new MetricRow("gui.krypton_hybrid.dash.m.packets", Fmt.count(snap.packetsSent()), c.textPrimary())));

        int rx = cx + colW + gap;
        sectionBox(g, rx, colTop, colW, colH, "gui.krypton_hybrid.dash.section.in");
        metricRows(g, rx + 6, colTop + 17, colW - 12, colTop + colH - 2, List.of(
                new MetricRow("gui.krypton_hybrid.dash.m.live_rate",
                        live != null ? Fmt.rate(live.inBps()) : "--", c.accentLight()),
                new MetricRow("gui.krypton_hybrid.dash.m.received_total", Fmt.bytes(snap.bytesReceived()), c.textPrimary()),
                new MetricRow("gui.krypton_hybrid.dash.m.packets", Fmt.count(snap.packetsReceived()), c.textPrimary()),
                new MetricRow("gui.krypton_hybrid.dash.m.avg_packet_in", Fmt.bytes(snap.averageReceivedPacketBytes()), c.textPrimary()),
                new MetricRow("gui.krypton_hybrid.dash.m.lifetime_rate", Fmt.rate(snap.receivedBytesPerSecond()), c.textSecondary())));

        int my = cy + ch - meterH;
        int mw = (cw - gap * 2) / 3;
        meter(g, cx, my, mw, "gui.krypton_hybrid.dash.meter.bundle", snap.bundleHitRatePercent(),
                qualityColor(snap.bundleHitRatePercent(), 70.0, 35.0));
        meter(g, cx + mw + gap, my, mw, "gui.krypton_hybrid.dash.meter.coalesce", snap.coalesceDropRatePercent(),
                c.accentLight());
        meter(g, cx + (mw + gap) * 2, my, mw, "gui.krypton_hybrid.dash.meter.coverage", snap.trackedCoveragePercent(),
                qualityColor(snap.trackedCoveragePercent(), 85.0, 60.0));
    }

    private void meter(KryptonGuiGraphics g, int x, int y, int w, String key, double percent, int color) {
        var c = UITheme.colors();
        String value = Fmt.percent(percent);
        int vw = this.font.width(value);
        g.drawString(this.font, Fmt.truncate(this.font, translate(key), w - vw - 6), x, y, c.textSecondary(), false);
        g.drawString(this.font, value, x + w - vw, y, c.textPrimary(), false);
        Charts.bar(g, x, y + 12, w, 5, percent / 100.0, color);
        if (inside(x, y, w, 18)) {
            tooltip = List.of(Component.translatable(key), Component.translatable(key + ".desc"));
        }
    }

    // ------------------------------------------------------------------
    //  Mods
    // ------------------------------------------------------------------

    private void renderMods(KryptonGuiGraphics g, StatsSnapshotPayload snap) {
        var c = UITheme.colors();

        int boxW = 120;
        float focus = anim.approach("filter.focus", modFilter.isFocused() ? 1F : 0F, 14F);
        float hoverBox = anim.hover("filter.hover", inside(cx, cy, boxW, 15));
        Shapes.roundRect(g, cx, cy, boxW, 15, 7.5F, cardBackground());
        Shapes.roundRectStroke(g, cx, cy, boxW, 15, 7.5F, stroke(),
                UITheme.lerpColor(UITheme.withAlpha(c.widgetBorder(), 0xC0 + (int) (0x30 * hoverBox)), c.accent(), focus));
        if (modFilterText.isEmpty() && !modFilter.isFocused()) {
            g.drawString(this.font, translate("gui.krypton_hybrid.dash.mods.filter"), cx + 4, cy + 4, c.textMuted(), false);
        }

        List<StatsSnapshotPayload.ModEntry> mods = filteredMods(snap);
        String totals = Component.translatable("gui.krypton_hybrid.dash.mods.totals",
                snap.trackedModCount(), Fmt.bytes(snap.totalTrackedModBytes())).getString();
        int tw = this.font.width(totals);
        g.drawString(this.font, totals, cx + cw - tw, cy + 4, c.textMuted(), false);

        int headerY = cy + 20;
        int pctW = 74;
        int bytesW = 54;
        int packetsW = 54;
        int avgW = 44;
        int nameW = cw - pctW - bytesW - packetsW - avgW - 8;
        int colName = cx + 4;
        int colBytes = colName + nameW;
        int colPct = colBytes + bytesW;
        int colPackets = colPct + pctW;
        int colAvg = colPackets + packetsW;
        sortHeader(g, colName, headerY, nameW, "gui.krypton_hybrid.dash.mods.col.name", ModSort.NAME, false);
        sortHeader(g, colBytes, headerY, bytesW, "gui.krypton_hybrid.dash.mods.col.bytes", ModSort.BYTES, true);
        g.drawString(this.font, translate("gui.krypton_hybrid.dash.mods.col.share"), colPct + 4, headerY, c.textMuted(), false);
        sortHeader(g, colPackets, headerY, packetsW, "gui.krypton_hybrid.dash.mods.col.packets", ModSort.PACKETS, true);
        sortHeader(g, colAvg, headerY, avgW, "gui.krypton_hybrid.dash.mods.col.avg", ModSort.AVG, true);
        Shapes.hairline(g, cx, cx + cw, headerY + 11, UITheme.withAlpha(c.divider(), 0xA0));

        modListX = cx;
        modListY = headerY + 14;
        modListW = cw;
        modListH = cy + ch - modListY;
        int rowH = 13;
        int visible = Math.max(1, modListH / rowH);
        int maxScroll = Math.max(0, mods.size() - visible);
        modScroll = clamp(modScroll, 0, maxScroll);

        if (mods.isEmpty()) {
            String empty = translate(modFilterText.isEmpty()
                    ? "gui.krypton_hybrid.dash.mods.empty"
                    : "gui.krypton_hybrid.dash.mods.no_match");
            drawCentered(g, empty, cx + cw / 2, modListY + 12, c.textMuted());
            return;
        }

        long totalBytes = Math.max(1L, snap.totalTrackedModBytes());
        boolean scrollbar = mods.size() > visible;
        int rowW = cw - (scrollbar ? 6 : 0);
        g.enableScissor(modListX, modListY, modListX + rowW, modListY + modListH);
        for (int i = 0; i < visible && modScroll + i < mods.size(); i++) {
            int idx = modScroll + i;
            StatsSnapshotPayload.ModEntry e = mods.get(idx);
            int ry = modListY + i * rowH;
            boolean hover = inside(modListX, ry, rowW, rowH);
            float hoverAnim = anim.hover("mod." + e.modId(), hover);
            if ((idx & 1) == 0) {
                Shapes.roundRect(g, cx, ry, rowW, rowH - 1, 3, UITheme.withAlpha(c.widgetBg(), 0x50));
            }
            if (hoverAnim > 0.01F) {
                Shapes.roundRect(g, cx, ry, rowW, rowH - 1, 3, UITheme.withAlpha(c.accent(), (int) (0x34 * hoverAnim)));
            }
            double share = e.bytes() / (double) totalBytes;
            double avg = e.packets() == 0 ? 0.0 : e.bytes() / (double) e.packets();
            int ty = ry + 3;
            g.drawString(this.font, Fmt.truncate(this.font, e.modId(), nameW - 6), colName, ty, c.textPrimary(), false);
            g.drawString(this.font, Fmt.bytes(e.bytes()), colBytes, ty, c.textPrimary(), false);
            String pct = Fmt.percent(share * 100.0);
            int pw2 = this.font.width(pct);
            Charts.bar(g, colPct + 4, ry + 4, pctW - pw2 - 12, 4, share, c.accent());
            g.drawString(this.font, pct, colPct + pctW - pw2 - 4, ty, c.textSecondary(), false);
            g.drawString(this.font, Fmt.count(e.packets()), colPackets, ty, c.textSecondary(), false);
            g.drawString(this.font, Fmt.bytes(avg), colAvg, ty, c.textSecondary(), false);
            if (hover) {
                tooltip = modTooltip(idx + 1, e, snap);
            }
        }
        g.disableScissor();

        if (scrollbar) {
            int trackX = cx + cw - 3;
            Shapes.pill(g, trackX, modListY, 3, modListH, c.scrollbarTrack());
            float thumbH = Math.max(12, modListH * visible / (float) mods.size());
            float targetY = modListY + (modListH - thumbH) * modScroll / Math.max(1, maxScroll);
            float thumbY = anim.approach("mods.thumb", targetY, 20F);
            Shapes.pill(g, trackX, thumbY, 3, thumbH, c.scrollbarThumb());
        }
    }

    private void sortHeader(KryptonGuiGraphics g, int x, int y, int w, String key, ModSort sort, boolean descendingFirst) {
        var c = UITheme.colors();
        boolean active = modSort == sort;
        String label = translate(key);
        if (active) {
            label += modSortAscending ? " ▲" : " ▼";
        }
        float hover = anim.hover("sort." + sort.name(), inside(x, y - 2, w, 12));
        int color = active ? c.accentLight() : UITheme.lerpColor(c.textMuted(), c.textPrimary(), hover);
        g.drawString(this.font, Fmt.truncate(this.font, label, w - 4), x, y, color, false);
        hits.add(new Hit(x, y - 2, w, 12, () -> {
            if (modSort == sort) {
                modSortAscending = !modSortAscending;
            } else {
                modSort = sort;
                modSortAscending = !descendingFirst;
            }
            modScroll = 0;
        }));
    }

    private List<StatsSnapshotPayload.ModEntry> filteredMods(StatsSnapshotPayload snap) {
        if (snap == modsSnapshot && modFilterText.equals(modsFilter) && modSort == modsSort
                && modSortAscending == modsAscending) {
            return modsCache;
        }
        modsSnapshot = snap;
        modsFilter = modFilterText;
        modsSort = modSort;
        modsAscending = modSortAscending;
        String needle = modFilterText.trim().toLowerCase(Locale.ROOT);
        List<StatsSnapshotPayload.ModEntry> mods = new ArrayList<>();
        for (StatsSnapshotPayload.ModEntry e : snap.topMods()) {
            if (needle.isEmpty() || e.modId().toLowerCase(Locale.ROOT).contains(needle)) {
                mods.add(e);
            }
        }
        Comparator<StatsSnapshotPayload.ModEntry> cmp = switch (modSort) {
            case NAME -> Comparator.comparing(StatsSnapshotPayload.ModEntry::modId, String.CASE_INSENSITIVE_ORDER);
            case BYTES -> Comparator.comparingLong(StatsSnapshotPayload.ModEntry::bytes);
            case PACKETS -> Comparator.comparingLong(StatsSnapshotPayload.ModEntry::packets);
            case AVG -> Comparator.comparingDouble(e -> e.packets() == 0 ? 0.0 : e.bytes() / (double) e.packets());
        };
        mods.sort(modSortAscending ? cmp : cmp.reversed());
        modsCache = mods;
        return mods;
    }

    private List<Component> modTooltip(int rank, StatsSnapshotPayload.ModEntry e, StatsSnapshotPayload snap) {
        double byteShare = e.bytes() * 100.0 / Math.max(1L, snap.totalTrackedModBytes());
        double packetShare = e.packets() * 100.0 / Math.max(1L, snap.totalTrackedModPackets());
        double avg = e.packets() == 0 ? 0.0 : e.bytes() / (double) e.packets();
        return List.of(
                Component.translatable("gui.krypton_hybrid.dash.mods.tip.title", rank, e.modId()),
                Component.translatable("gui.krypton_hybrid.dash.mods.tip.bytes", Fmt.bytes(e.bytes()), Fmt.percent(byteShare)),
                Component.translatable("gui.krypton_hybrid.dash.mods.tip.packets", Fmt.count(e.packets()), Fmt.percent(packetShare)),
                Component.translatable("gui.krypton_hybrid.dash.mods.tip.avg", Fmt.bytes(avg)));
    }

    // ------------------------------------------------------------------
    //  Security
    // ------------------------------------------------------------------

    private record Counter(String key, long value, boolean severe) {}

    private void renderSecurity(KryptonGuiGraphics g, StatsSnapshotPayload snap) {
        var c = UITheme.colors();

        int bannerH = 26;
        Shapes.roundRect(g, cx, cy, cw, bannerH, CARD_RADIUS, cardBackground());
        boolean on = snap.securityEnabled();
        String status = translate(on ? "gui.krypton_hybrid.dash.sec.on" : "gui.krypton_hybrid.dash.sec.off");
        int statusColor = on ? c.successColor() : c.dangerColor();
        int sx = pill(g, cx + 6, cy + 9, cx + cw, status, statusColor, List.of(
                Component.translatable("gui.krypton_hybrid.dash.sec.status.title"),
                Component.translatable("gui.krypton_hybrid.dash.sec.status.desc")));
        long total = snap.securityTotalEvents();
        Component totalText = Component.translatable("gui.krypton_hybrid.dash.sec.total", Fmt.count(total))
                .withStyle(ChatFormatting.BOLD);
        g.drawString(this.font, totalText, sx + 6, cy + 9, total == 0 ? c.textPrimary() : c.warningColor(), false);
        String since = translate("gui.krypton_hybrid.dash.sec.since");
        g.drawString(this.font, since, cx + cw - 6 - this.font.width(since), cy + 9, c.textMuted(), false);

        Counter[] counters = {
                new Counter("rate_limited", snap.secConnectionsRateLimited(), false),
                new Counter("status_dropped", snap.secStatusRequestsDropped(), false),
                new Counter("legacy_dropped", snap.secLegacyQueriesDropped(), false),
                new Counter("handshake_rejected", snap.secHandshakesRejected(), false),
                new Counter("timeouts", snap.secTimeouts(), false),
                new Counter("size_rejected", snap.secPacketsSizeRejected(), true),
                new Counter("read_limit", snap.secReadLimitRejected(), true),
                new Counter("null_frames", snap.secNullFramesDropped(), false),
                new Counter("decomp_bombs", snap.secDecompressionBombs(), true),
                new Counter("anomaly_events", snap.secAnomalyEvents(), false),
                new Counter("anomaly_disconnects", snap.secAnomalyDisconnects(), true),
                new Counter("writes_dropped", snap.secWritesDropped(), true),
                new Counter("watermark", snap.secWatermarkBreaches(), false)
        };
        // The client line is pinned to the bottom so it is never clipped; the counter
        // grid shrinks its row height to fit the space left above it.
        int diagY = cy + ch - 9;
        int gridTop = cy + bannerH + 6;
        int cols = cw >= 330 ? 3 : 2;
        int rows = (counters.length + cols - 1) / cols;
        int gap = 6;
        int cellW = (cw - gap * (cols - 1)) / cols;
        int cellH = clamp((diagY - 6 - gridTop) / rows, 10, 14);
        int textOffset = cellH >= 12 ? 2 : 1;
        for (int i = 0; i < counters.length; i++) {
            Counter k = counters[i];
            int x = cx + (i % cols) * (cellW + gap);
            int y = gridTop + (i / cols) * cellH;
            String key = "gui.krypton_hybrid.dash.sec." + k.key();
            int valueColor = k.value() == 0 ? c.textMuted() : k.severe() ? c.dangerColor() : c.warningColor();
            String value = Fmt.count(k.value());
            int vw = this.font.width(value);
            boolean hover = inside(x, y, cellW, cellH);
            float hoverAnim = anim.hover("sec." + k.key(), hover);
            if (hoverAnim > 0.01F) {
                Shapes.roundRect(g, x - 3, y, cellW + 6, cellH - 1, 3, UITheme.withAlpha(c.accent(), (int) (0x2C * hoverAnim)));
            }
            if (hover) {
                tooltip = List.of(Component.translatable(key), Component.translatable(key + ".desc"));
            }
            g.drawString(this.font, Fmt.truncate(this.font, translate(key), cellW - vw - 8), x, y + textOffset, c.textSecondary(), false);
            g.drawString(this.font, value, x + cellW - vw, y + textOffset, valueColor, false);
        }

        Shapes.hairline(g, cx, cx + cw, diagY - 4, UITheme.withAlpha(c.divider(), 0x80));
        long req = KryptonStatsClientController.snapshotRequestCount();
        long recv = KryptonStatsClientController.snapshotReceiveCount();
        double success = req == 0 ? 100.0 : Math.min(100.0, recv * 100.0 / req);
        long rtt = KryptonStatsClientController.lastRoundTripMs();
        String diag = Component.translatable("gui.krypton_hybrid.dash.client.line",
                rtt > 0 ? rtt + " ms" : "--",
                Fmt.count(req), Fmt.count(recv), Fmt.percent(success)).getString();
        g.drawString(this.font, Fmt.truncate(this.font, diag, cw), cx, diagY, c.textMuted(), false);
    }

    // ------------------------------------------------------------------
    //  Settings
    // ------------------------------------------------------------------

    private void renderSettings(KryptonGuiGraphics g) {
        var c = UITheme.colors();
        int rowH = 14;
        int y = cy;
        g.drawString(this.font, translate("gui.krypton_hybrid.dash.settings.dashboard"), cx, y, c.textSecondary(), false);
        y += 12;
        y = segmentedRow(g, y, rowH, "gui.krypton_hybrid.dash.settings.theme",
                new String[]{
                        translate("gui.krypton_hybrid.dash.settings.theme.dark"),
                        translate("gui.krypton_hybrid.dash.settings.theme.light")
                },
                UITheme.getMode().ordinal(),
                i -> UITheme.setMode(UITheme.Mode.values()[i]));
        String[] opacityLabels = new String[OPACITY_STEPS.length];
        int opacityIndex = 0;
        for (int i = 0; i < OPACITY_STEPS.length; i++) {
            opacityLabels[i] = OPACITY_STEPS[i] + "%";
            if (OPACITY_STEPS[i] <= UITheme.panelOpacityPercent()) {
                opacityIndex = i;
            }
        }
        y = segmentedRow(g, y, rowH, "gui.krypton_hybrid.dash.settings.opacity", opacityLabels, opacityIndex,
                i -> UITheme.setPanelOpacityPercent(OPACITY_STEPS[i]));

        y += 6;
        g.drawString(this.font, translate("gui.krypton_hybrid.dash.settings.hud"), cx, y, c.textSecondary(), false);
        y += 12;
        y = toggleRow(g, y, rowH, "gui.krypton_hybrid.dash.settings.hud_visible",
                KryptonHudOverlay.isVisible(), KryptonHudOverlay::toggleVisible);
        y = cycleRow(g, y, rowH, "gui.krypton_hybrid.dash.settings.hud_anchor",
                translate("gui.krypton_hybrid.dash.settings.anchor." + KryptonHudOverlay.anchor().name().toLowerCase(Locale.ROOT)),
                KryptonHudOverlay::cycleAnchor);
        String[] refreshLabels = new String[HUD_REFRESH_STEPS.length];
        int refreshIndex = 0;
        for (int i = 0; i < HUD_REFRESH_STEPS.length; i++) {
            refreshLabels[i] = Component.translatable("gui.krypton_hybrid.dash.settings.seconds",
                    HUD_REFRESH_STEPS[i] / 1000).getString();
            if (HUD_REFRESH_STEPS[i] <= KryptonHudOverlay.autoRefreshIntervalMs()) {
                refreshIndex = i;
            }
        }
        y = segmentedRow(g, y, rowH, "gui.krypton_hybrid.dash.settings.hud_refresh", refreshLabels, refreshIndex,
                i -> KryptonHudOverlay.setAutoRefreshIntervalMs(HUD_REFRESH_STEPS[i]));
        y = toggleRow(g, y, rowH, "gui.krypton_hybrid.dash.settings.hud_graph",
                KryptonHudOverlay.showGraph(), KryptonHudOverlay::toggleShowGraph);
        y = toggleRow(g, y, rowH, "gui.krypton_hybrid.dash.settings.hud_top_mod",
                KryptonHudOverlay.showTopMod(), KryptonHudOverlay::toggleShowTopMod);

        String hint = translate("gui.krypton_hybrid.dash.settings.saved_hint");
        g.drawString(this.font, Fmt.truncate(this.font, hint, cw), cx, Math.max(y + 6, cy + ch - 10), c.textMuted(), false);
    }

    /** Row background with a fading hover highlight and the setting's label; returns the hover state. */
    private boolean settingRowBase(KryptonGuiGraphics g, int y, int h, String key) {
        var c = UITheme.colors();
        boolean hover = inside(cx, y, cw, h);
        float hoverAnim = anim.hover("setting." + key, hover);
        if (hoverAnim > 0.01F) {
            Shapes.roundRect(g, cx, y, cw, h - 1, 4, UITheme.withAlpha(c.accent(), (int) (0x24 * hoverAnim)));
        }
        if (hover) {
            tooltip = List.of(Component.translatable(key), Component.translatable(key + ".desc"));
        }
        g.drawString(this.font, translate(key), cx + 6, y + (h - 8) / 2, c.textPrimary(), false);
        return hover;
    }

    /** On/off switch with a sliding knob; the whole row toggles it. */
    private int toggleRow(KryptonGuiGraphics g, int y, int h, String key, boolean on, Runnable toggle) {
        var c = UITheme.colors();
        settingRowBase(g, y, h, key);
        float t = anim.approach("toggle." + key, on ? 1F : 0F, 14F);
        float tw = 18F;
        float th = 10F;
        float tx = cx + cw - tw - 5;
        float ty = y + (h - 1 - th) / 2F;
        int offTrack = UITheme.withAlpha(c.widgetBorderHover(), 0xA0);
        Shapes.pill(g, tx, ty, tw, th, UITheme.lerpColor(offTrack, c.successColor(), t));
        float knob = th - 3F;
        Shapes.circle(g, tx + 1.5F + knob / 2 + t * (tw - th), ty + th / 2, knob / 2, 0xFFFFFFFF);
        hits.add(new Hit(cx, y, cw, h, () -> {
            toggle.run();
            KryptonClientConfig.save();
        }));
        return y + h;
    }

    /** Value pill that advances to the next option on click. */
    private int cycleRow(KryptonGuiGraphics g, int y, int h, String key, String value, Runnable next) {
        var c = UITheme.colors();
        boolean hover = settingRowBase(g, y, h, key);
        float hoverAnim = anim.hover("cycle." + key, hover);
        String label = value + "  ›";
        int vw = this.font.width(label);
        float bw = vw + 12;
        float bh = 11F;
        float bx = cx + cw - bw - 5;
        float by = y + (h - 1 - bh) / 2F;
        Shapes.pill(g, bx, by, bw, bh, UITheme.withAlpha(c.accent(), 0x38 + (int) (0x30 * hoverAnim)));
        g.drawString(this.font, label, (int) (bx + 6), (int) (by + 2), c.accentLight(), false);
        hits.add(new Hit(cx, y, cw, h, () -> {
            next.run();
            KryptonClientConfig.save();
        }));
        return y + h;
    }

    /** Segmented control whose selection pill slides to the chosen option. */
    private int segmentedRow(KryptonGuiGraphics g, int y, int h, String key, String[] options, int selected,
                             java.util.function.IntConsumer select) {
        var c = UITheme.colors();
        settingRowBase(g, y, h, key);
        int pad = 6;
        int[] widths = new int[options.length];
        int total = 0;
        for (int i = 0; i < options.length; i++) {
            widths[i] = this.font.width(options[i]) + pad * 2;
            total += widths[i];
        }
        float th = 11F;
        float tx = cx + cw - total - 7;
        float ty = y + (h - 1 - th) / 2F;
        Shapes.pill(g, tx - 1, ty - 1, total + 2, th + 2, UITheme.withAlpha(c.widgetBorder(), 0xB0));

        float ox = tx;
        float selX = tx;
        for (int i = 0; i < selected; i++) {
            selX += widths[i];
        }
        float sx = anim.approach("segment.x." + key, selX, 18F);
        float sw = anim.approach("segment.w." + key, widths[selected], 18F);
        Shapes.pill(g, sx, ty, sw, th, c.accent());

        for (int i = 0; i < options.length; i++) {
            boolean hover = inside((int) ox, (int) ty, widths[i], (int) th);
            int color = i == selected ? 0xFFFFFFFF
                    : UITheme.lerpColor(c.textSecondary(), c.textPrimary(), anim.hover("segment." + key + "." + i, hover));
            g.drawString(this.font, options[i], (int) ox + pad, (int) ty + 2, color, false);
            final int index = i;
            hits.add(new Hit((int) ox, y, widths[i], h, () -> {
                select.accept(index);
                KryptonClientConfig.save();
            }));
            ox += widths[i];
        }
        return y + h;
    }

    // ------------------------------------------------------------------
    //  Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            for (Hit hit : List.copyOf(hits)) {
                if (hit.contains(mouseX, mouseY)) {
                    hit.action().run();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!settingsOpen && tab == Tab.MODS
                && mouseX >= modListX && mouseX < modListX + modListW
                && mouseY >= modListY && mouseY < modListY + modListH) {
            modScroll = Math.max(0, modScroll - (int) Math.signum(delta) * 3);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && settingsOpen) {
            settingsOpen = false;
            return true;
        }
        if (modFilter.isFocused()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (KryptonStatsClientController.openStatsKey().matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_4) {
            tab = Tab.values()[keyCode - GLFW.GLFW_KEY_1];
            settingsOpen = false;
            modScroll = 0;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------
    //  Drawing helpers
    // ------------------------------------------------------------------

    private int cardBackground() {
        return UITheme.withAlpha(UITheme.colors().widgetBg(), 0xB0);
    }

    /** Border width that stays about one screen pixel at GUI scale 2 and never vanishes. */
    private static float stroke() {
        return Math.max(Shapes.pixel(), 0.5F);
    }

    private void sectionBox(KryptonGuiGraphics g, int x, int y, int w, int h, String titleKey) {
        Shapes.roundRect(g, x, y, w, h, CARD_RADIUS, cardBackground());
        g.drawString(this.font, translate(titleKey), x + 6, y + 5, UITheme.colors().textSecondary(), false);
    }

    private record MetricRow(String key, String value, int color) {}

    /** Label/value rows from {@code y} down, stopping before a row would cross {@code maxY}. */
    private void metricRows(KryptonGuiGraphics g, int x, int y, int w, int maxY, List<MetricRow> rows) {
        var c = UITheme.colors();
        for (MetricRow row : rows) {
            if (y + 9 > maxY) {
                return;
            }
            int vw = this.font.width(row.value());
            g.drawString(this.font, Fmt.truncate(this.font, translate(row.key()), w - vw - 6), x, y, c.textMuted(), false);
            g.drawString(this.font, row.value(), x + w - vw, y, row.color(), false);
            y += 11;
        }
    }

    /** Draws a tinted pill and returns the x just after it; skipped if it would pass {@code limit}. */
    private int pill(KryptonGuiGraphics g, int x, int textY, int limit, String text, int color, List<Component> tip) {
        int w = this.font.width(text) + 8;
        if (x + w > limit) {
            return x;
        }
        Shapes.pill(g, x, textY - 2, w, 12, UITheme.withAlpha(color, 0x30));
        g.drawString(this.font, text, x + 4, textY, color, false);
        if (inside(x, textY - 2, w, 12)) {
            tooltip = tip;
        }
        return x + w + 4;
    }

    private void iconButton(KryptonGuiGraphics g, int x, int y, int size, String glyph, boolean active,
                            List<Component> tip, Runnable action) {
        var c = UITheme.colors();
        boolean hover = inside(x, y, size, size);
        float hoverAnim = anim.hover("icon." + glyph, hover);
        float activeAnim = anim.approach("icon.active." + glyph, active ? 1F : 0F, 14F);
        // Close tints red on hover; the others use the accent.
        int tint = "✕".equals(glyph) ? c.dangerColor() : c.accent();
        int alpha = (int) Math.max(0x50 * hoverAnim, 0x34 * activeAnim);
        if (alpha > 0) {
            Shapes.circle(g, x + size / 2F, y + size / 2F, size / 2F, UITheme.withAlpha(tint, alpha));
        }
        int gw = this.font.width(glyph);
        int glyphColor = UITheme.lerpColor(c.textSecondary(), c.textPrimary(), Math.max(hoverAnim, activeAnim));
        g.drawString(this.font, glyph, x + (size - gw + 1) / 2, y + (size - 8) / 2, glyphColor, false);
        hits.add(new Hit(x, y, size, size, action));
        if (hover) {
            tooltip = tip;
        }
    }

    private void drawCentered(KryptonGuiGraphics g, String text, int centerX, int y, int color) {
        g.drawString(this.font, text, centerX - this.font.width(text) / 2, y, color, false);
    }

    private void renderTooltip(KryptonGuiGraphics g, List<Component> lines, int x, int y) {
        int wrapW = 200;
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        int titleLines = 0;
        for (int i = 0; i < lines.size(); i++) {
            List<FormattedCharSequence> split = this.font.split(lines.get(i), wrapW);
            wrapped.addAll(split);
            if (i == 0) {
                titleLines = split.size();
            }
        }
        int maxW = 0;
        for (FormattedCharSequence line : wrapped) {
            maxW = Math.max(maxW, this.font.width(line));
        }
        int pad = 5;
        int w = maxW + pad * 2;
        int h = wrapped.size() * 10 + pad * 2 - 1;
        int tx = Math.max(2, Math.min(x, this.width - w - 4));
        int ty = Math.max(2, Math.min(y, this.height - h - 4));
        var c = UITheme.colors();
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        Shapes.roundRect(g, tx, ty, w, h, 5, UITheme.withAlpha(c.headerBg(), 0xF2));
        Shapes.roundRectStroke(g, tx, ty, w, h, 5, stroke(), UITheme.withAlpha(c.widgetBorderHover(), 0xA0));
        for (int i = 0; i < wrapped.size(); i++) {
            int color = i < titleLines ? c.accentLight() : c.textSecondary();
            g.drawString(this.font, wrapped.get(i), tx + pad, ty + pad + i * 10, color);
        }
        g.pose().popPose();
    }

    private boolean inside(int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static int qualityColor(double value, double good, double warn) {
        var c = UITheme.colors();
        if (value >= good) return c.successColor();
        if (value >= warn) return c.warningColor();
        return c.dangerColor();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Static labels are looked up once; init() clears the cache (it runs again on language change). */
    private String translate(String key) {
        String cached = translations.get(key);
        if (cached == null) {
            cached = Component.translatable(key).getString();
            translations.put(key, cached);
        }
        return cached;
    }
}
