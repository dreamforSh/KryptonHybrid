package com.xinian.KryptonHybrid.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Modern web-style card container, ported from MemoryCatcher.
 * Features rounded corners, multi-layer shadow, optional collapsible title bar,
 * and smooth expand animation.
 */
public class MCPanel {

    private static final int CORNER_RADIUS = 8;
    private static final int TITLE_BAR_HEIGHT = 24;
    private static final int INNER_PADDING = 12;

    private int x, y, width, height;
    private String title = "";
    private boolean collapsible = false;
    private boolean collapsed = false;
    private int titleColor;
    private final List<RenderEntry> entries = new ArrayList<>();
    private float expandProgress = 1.0f;
    private float titleHoverAnim = 0f;

    public MCPanel(int x, int y, int width, int height) {
        this.x = x; this.y = y; this.width = width; this.height = height;
        this.titleColor = UITheme.colors().textPrimary();
    }

    public MCPanel setTitle(String title) { this.title = title; return this; }
    public MCPanel setCollapsible(boolean v) { this.collapsible = v; return this; }
    public MCPanel setTitleColor(int c) { this.titleColor = c; return this; }
    public MCPanel setPosition(int x, int y) { this.x = x; this.y = y; return this; }
    public MCPanel setSize(int w, int h) { this.width = w; this.height = h; return this; }
    public MCPanel clearEntries() { entries.clear(); return this; }
    public MCPanel addEntry(RenderEntry e) { entries.add(e); return this; }

    public boolean isCollapsed() { return collapsed; }
    public void toggleCollapse() { if (collapsible) collapsed = !collapsed; }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return width; }
    public int getContentWidth() { return width - INNER_PADDING * 2; }
    public int getContentStartY() {
        boolean hasTitle = title != null && !title.isEmpty();
        return y + (hasTitle ? TITLE_BAR_HEIGHT : 0) + INNER_PADDING;
    }

    public int getEffectiveHeight() {
        boolean hasTitle = title != null && !title.isEmpty();
        int titleH = hasTitle ? TITLE_BAR_HEIGHT : 0;
        if (collapsed && expandProgress <= 0.01f) return titleH;
        float eased = UITheme.easeInOutQuad(expandProgress);
        return titleH + (int) ((height - titleH) * eased);
    }

    public boolean handleClick(double mx, double my) {
        if (!collapsible || title == null || title.isEmpty()) return false;
        if (mx >= x && mx <= x + width && my >= y && my <= y + TITLE_BAR_HEIGHT) {
            toggleCollapse();
            return true;
        }
        return false;
    }

    public void render(GuiGraphics g, int mouseX, int mouseY) {
        updateAnimations(mouseX, mouseY);
        var c = UITheme.colors();
        boolean hasTitle = title != null && !title.isEmpty();
        int effH = getEffectiveHeight();

        for (int i = 3; i >= 1; i--) {
            int alpha = 6 * (4 - i);
            UITheme.fillRoundedRect(g, x + i, y + i, width, effH, CORNER_RADIUS,
                    UITheme.withAlpha(c.shadow(), alpha));
        }
        UITheme.fillRoundedRect(g, x, y, width, effH, CORNER_RADIUS, c.panelBg());
        UITheme.fillRoundedRect(g, x, y, width, 4, CORNER_RADIUS, UITheme.withAlpha(0xFFFFFFFF, 0x08));

        if (hasTitle) renderTitleBar(g, c);

        int borderColor = UITheme.lerpColor(c.widgetBorder(),
                UITheme.withAlpha(c.accent(), 0x40), titleHoverAnim * 0.3f);
        UITheme.drawRoundedBorder(g, x, y, width, effH, CORNER_RADIUS, borderColor);

        if (expandProgress > 0.01f) {
            int contentY = y + (hasTitle ? TITLE_BAR_HEIGHT : 0) + INNER_PADDING;
            int contentX = x + INNER_PADDING;
            int contentW = width - INNER_PADDING * 2;
            int fullH = height - (hasTitle ? TITLE_BAR_HEIGHT : 0) - INNER_PADDING * 2;
            int visibleH = (int) (fullH * UITheme.easeInOutQuad(expandProgress));
            if (visibleH > 0) {
                g.enableScissor(contentX, contentY, contentX + contentW, contentY + visibleH);
                for (RenderEntry e : entries) e.render(g, contentX, contentY, contentW, fullH);
                g.disableScissor();
            }
        }
    }

    private void updateAnimations(int mouseX, int mouseY) {
        float target = collapsed ? 0f : 1f;
        expandProgress = UITheme.smoothDamp(expandProgress, target, 0.18f);
        if (Math.abs(expandProgress - target) < 0.005f) expandProgress = target;
        if (collapsible) {
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + TITLE_BAR_HEIGHT;
            float ht = hovered ? 1f : 0f;
            titleHoverAnim = UITheme.smoothDamp(titleHoverAnim, ht, 0.15f);
            if (Math.abs(titleHoverAnim - ht) < 0.005f) titleHoverAnim = ht;
        }
    }

    private void renderTitleBar(GuiGraphics g, UITheme.ColorPalette c) {
        UITheme.fillRoundedRect(g, x, y, width, TITLE_BAR_HEIGHT / 2, CORNER_RADIUS,
                UITheme.brighten(c.headerBg(), 0.03f));
        UITheme.fillRoundedRect(g, x, y + TITLE_BAR_HEIGHT / 2 - 2, width, TITLE_BAR_HEIGHT / 2 + 2,
                CORNER_RADIUS, c.headerBg());
        UITheme.fillRoundedRect(g, x + 2, y + 1, width - 4, 2, 2, UITheme.withAlpha(0xFFFFFFFF, 0x15));

        if (collapsible && titleHoverAnim > 0.01f) {
            float eased = UITheme.easeOutCubic(titleHoverAnim);
            int overlayAlpha = (int) (25 * eased);
            UITheme.fillRoundedRect(g, x, y, width, TITLE_BAR_HEIGHT, CORNER_RADIUS,
                    UITheme.withAlpha(c.accent(), overlayAlpha));
        }

        int borderColor = collapsible
                ? UITheme.lerpColor(c.divider(), c.accent(), titleHoverAnim * 0.6f)
                : c.divider();
        g.fill(x + INNER_PADDING, y + TITLE_BAR_HEIGHT - 1,
                x + width - INNER_PADDING, y + TITLE_BAR_HEIGHT, borderColor);

        Minecraft mc = Minecraft.getInstance();
        int textY = y + (TITLE_BAR_HEIGHT - mc.font.lineHeight) / 2 + 1;

        if (collapsible) {
            String ind = expandProgress > 0.5f ? "\u25BC" : "\u25B6";
            int indColor = UITheme.lerpColor(c.textMuted(), c.accentLight(), titleHoverAnim);
            g.drawString(mc.font, ind, x + INNER_PADDING + 1, textY + 1, UITheme.withAlpha(0xFF000000, 0x40), false);
            g.drawString(mc.font, ind, x + INNER_PADDING, textY, indColor, true);
        }

        int titleX = x + INNER_PADDING + (collapsible ? 18 : 0);
        int displayColor = collapsible
                ? UITheme.lerpColor(titleColor, c.accentLight(), titleHoverAnim * 0.4f)
                : titleColor;
        g.drawString(mc.font, title, titleX + 1, textY + 1, UITheme.withAlpha(0xFF000000, 0x50), false);
        g.drawString(mc.font, title, titleX, textY, displayColor, true);
    }

    @FunctionalInterface
    public interface RenderEntry {
        void render(GuiGraphics graphics, int x, int y, int width, int height);
    }
}

