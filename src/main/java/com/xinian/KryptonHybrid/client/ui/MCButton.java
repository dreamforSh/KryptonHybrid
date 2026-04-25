package com.xinian.KryptonHybrid.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Modern web-style themed button (ported from MemoryCatcher). */
public class MCButton extends AbstractWidget {

    private static final int CORNER_RADIUS = 6;
    private final OnPress onPress;
    private float hoverProgress = 0f;
    private float pressAnimation = 0f;

    @FunctionalInterface
    public interface OnPress { void onPress(MCButton button); }

    public MCButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message);
        this.onPress = onPress;
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        updateAnimations(partialTick);
        var c = UITheme.colors();
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();

        float easedHover = UITheme.easeOutCubic(hoverProgress);
        float easedPress = UITheme.easeOutCubic(pressAnimation);

        if (easedPress > 0.01f) {
            int s = (int) (2 * easedPress);
            x += s; y += s; w -= s * 2; h -= s * 2;
        }

        if (this.active) {
            int layers = easedHover > 0.1f ? 2 : 1;
            for (int i = layers; i >= 1; i--) {
                float hf = 0.6f + 0.4f * easedHover;
                int alpha = (int) (12 * hf * (layers - i + 1) / layers);
                UITheme.fillRoundedRect(g, x + i, y + i, w, h, CORNER_RADIUS, UITheme.withAlpha(c.shadow(), alpha));
            }
        }

        int bgTop, bgBottom, borderColor, textColor;
        if (!this.active) {
            bgTop = bgBottom = UITheme.withAlpha(c.widgetBg(), 0x60);
            borderColor = UITheme.withAlpha(c.widgetBorder(), 0x50);
            textColor = c.textMuted();
        } else {
            int normTop = UITheme.brighten(c.widgetBg(), 0.05f);
            int normBot = c.widgetBg();
            int hovTop  = UITheme.brighten(c.accent(), 0.15f);
            int hovBot  = c.accent();
            bgTop    = UITheme.lerpColor(normTop, hovTop, easedHover);
            bgBottom = UITheme.lerpColor(normBot, hovBot, easedHover);
            int normBorder = c.widgetBorder();
            int hovBorder  = UITheme.lerpColor(c.accent(), 0xFFFFFFFF, 0.3f);
            borderColor = UITheme.lerpColor(normBorder, hovBorder, easedHover * 0.7f);
            textColor = UITheme.lerpColor(c.textPrimary(), 0xFFFFFFFF, easedHover * 0.2f);
        }

        UITheme.fillRoundedRect(g, x, y, w, h, CORNER_RADIUS, bgBottom);
        UITheme.fillRoundedRect(g, x, y, w, h / 2 + 2, CORNER_RADIUS, bgTop);
        UITheme.drawRoundedBorder(g, x, y, w, h, CORNER_RADIUS, borderColor);

        if (this.active) {
            int hi = (int) (20 + 15 * easedHover);
            UITheme.fillRoundedRect(g, x + 1, y + 1, w - 2, 3, 3, UITheme.withAlpha(0xFFFFFFFF, hi));
        }

        Minecraft mc = Minecraft.getInstance();
        int tw = mc.font.width(getMessage());
        int tx = x + (w - tw) / 2;
        int ty = y + (h - mc.font.lineHeight) / 2 + 1;
        if (this.active) {
            g.drawString(mc.font, getMessage(), tx + 1, ty + 1, UITheme.withAlpha(0xFF000000, 0x60), false);
        }
        g.drawString(mc.font, getMessage(), tx, ty, textColor, true);
    }

    private void updateAnimations(float partialTick) {
        boolean hovered = isHoveredOrFocused() && this.active;
        float target = hovered ? 1.0f : 0f;
        float speed = hovered ? 0.15f : 0.08f;
        hoverProgress = UITheme.smoothDamp(hoverProgress, target, speed * partialTick * 3f);
        if (Math.abs(hoverProgress - target) < 0.005f) hoverProgress = target;
        if (pressAnimation > 0.01f)
            pressAnimation = UITheme.smoothDamp(pressAnimation, 0f, 0.15f * partialTick * 3f);
        else
            pressAnimation = 0f;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (this.active) {
            pressAnimation = 1.0f;
            onPress.onPress(this);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}

