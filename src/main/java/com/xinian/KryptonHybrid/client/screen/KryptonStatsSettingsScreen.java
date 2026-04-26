package com.xinian.KryptonHybrid.client.screen;

import com.xinian.KryptonHybrid.client.overlay.KryptonHudOverlay;
import com.xinian.KryptonHybrid.client.ui.MCButton;
import com.xinian.KryptonHybrid.client.ui.MCPanel;
import com.xinian.KryptonHybrid.client.ui.UITheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.TimeUnit;

/**
 * Styled settings screen for Krypton stats UI/HUD runtime options.
 */
public final class KryptonStatsSettingsScreen extends Screen {

    private final Screen parent;

    private MCButton themeBtn;
    private MCButton hudVisibleBtn;
    private MCButton hudPosBtn;
    private MCButton hudRefreshBtn;
    private MCButton hudTopModBtn;

    public KryptonStatsSettingsScreen(Screen parent) {
        super(Component.translatable("gui.krypton_hybrid.settings.title"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        clearWidgets();

        int panelW = Math.min(420, this.width - 24);
        int panelH = Math.min(220, this.height - 48);
        int px = (this.width - panelW) / 2;
        int py = (this.height - panelH) / 2;

        int rowX = px + 18;
        int rowW = panelW - 36;
        int rowH = Math.max(20, this.font.lineHeight + 10);
        int y = py + 42;
        int gap = 8;

        themeBtn = new MCButton(rowX, y, rowW, rowH, themeLabel(), b -> {
            UITheme.toggleMode();
            refreshLabels();
        });
        addRenderableWidget(themeBtn);
        y += rowH + gap;

        hudVisibleBtn = new MCButton(rowX, y, rowW, rowH, hudVisibleLabel(), b -> {
            KryptonHudOverlay.toggleVisible();
            refreshLabels();
        });
        addRenderableWidget(hudVisibleBtn);
        y += rowH + gap;

        hudPosBtn = new MCButton(rowX, y, rowW, rowH, hudPosLabel(), b -> {
            KryptonHudOverlay.cycleAnchor();
            refreshLabels();
        });
        addRenderableWidget(hudPosBtn);
        y += rowH + gap;

        hudRefreshBtn = new MCButton(rowX, y, rowW, rowH, hudRefreshLabel(), b -> {
            cycleRefreshInterval();
            refreshLabels();
        });
        addRenderableWidget(hudRefreshBtn);
        y += rowH + gap;

        hudTopModBtn = new MCButton(rowX, y, rowW, rowH, hudTopModLabel(), b -> {
            KryptonHudOverlay.toggleShowTopMod();
            refreshLabels();
        });
        addRenderableWidget(hudTopModBtn);

        int closeW = Math.max(90, this.font.width(Component.translatable("gui.krypton_hybrid.button.done")) + 24);
        addRenderableWidget(new MCButton(px + panelW - closeW - 12, py + panelH - rowH - 10, closeW, rowH,
                Component.translatable("gui.krypton_hybrid.button.done"), b -> onClose()));
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        var c = UITheme.colors();
        renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, this.width, this.height, c.panelBg());

        int panelW = Math.min(420, this.width - 24);
        int panelH = Math.min(220, this.height - 48);
        int px = (this.width - panelW) / 2;
        int py = (this.height - panelH) / 2;

        MCPanel panel = new MCPanel(px, py, panelW, panelH)
                .setTitle(Component.translatable("gui.krypton_hybrid.settings.panel").getString());
        panel.render(g, mouseX, mouseY);

        g.drawString(this.font,
                Component.translatable("gui.krypton_hybrid.settings.subtitle").getString(),
                px + 18, py + 26, c.textSecondary(), false);

        for (var renderable : this.renderables) {
            renderable.render(g, mouseX, mouseY, partialTick);
        }
    }

    private void refreshLabels() {
        themeBtn.setMessage(themeLabel());
        hudVisibleBtn.setMessage(hudVisibleLabel());
        hudPosBtn.setMessage(hudPosLabel());
        hudRefreshBtn.setMessage(hudRefreshLabel());
        hudTopModBtn.setMessage(hudTopModLabel());
    }

    private Component themeLabel() {
        return Component.translatable("gui.krypton_hybrid.settings.theme",
                Component.translatable(UITheme.getMode() == UITheme.Mode.DARK
                        ? "gui.krypton_hybrid.theme.dark"
                        : "gui.krypton_hybrid.theme.light"));
    }

    private Component hudVisibleLabel() {
        return Component.translatable("gui.krypton_hybrid.settings.hud_visible",
                Component.translatable(KryptonHudOverlay.isVisible()
                        ? "gui.krypton_hybrid.state.enabled"
                        : "gui.krypton_hybrid.state.disabled"));
    }

    private Component hudPosLabel() {
        String key = switch (KryptonHudOverlay.anchor()) {
            case TOP_LEFT -> "gui.krypton_hybrid.settings.pos.top_left";
            case TOP_RIGHT -> "gui.krypton_hybrid.settings.pos.top_right";
            case BOTTOM_LEFT -> "gui.krypton_hybrid.settings.pos.bottom_left";
            case BOTTOM_RIGHT -> "gui.krypton_hybrid.settings.pos.bottom_right";
        };
        return Component.translatable("gui.krypton_hybrid.settings.hud_pos",
                Component.translatable(key));
    }

    private Component hudRefreshLabel() {
        long ms = KryptonHudOverlay.autoRefreshIntervalMs();
        return Component.translatable("gui.krypton_hybrid.settings.hud_refresh",
                String.format("%.1fs", ms / 1000.0));
    }

    private Component hudTopModLabel() {
        return Component.translatable("gui.krypton_hybrid.settings.hud_top_mod",
                Component.translatable(KryptonHudOverlay.showTopMod()
                        ? "gui.krypton_hybrid.state.enabled"
                        : "gui.krypton_hybrid.state.disabled"));
    }

    private static void cycleRefreshInterval() {
        long current = KryptonHudOverlay.autoRefreshIntervalMs();
        long[] choices = {
                TimeUnit.SECONDS.toMillis(1),
                TimeUnit.SECONDS.toMillis(2),
                TimeUnit.SECONDS.toMillis(5),
                TimeUnit.SECONDS.toMillis(10)
        };
        for (int i = 0; i < choices.length; i++) {
            if (current <= choices[i]) {
                KryptonHudOverlay.setAutoRefreshIntervalMs(choices[(i + 1) % choices.length]);
                return;
            }
        }
        KryptonHudOverlay.setAutoRefreshIntervalMs(choices[0]);
    }
}

