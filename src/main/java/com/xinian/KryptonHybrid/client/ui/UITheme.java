package com.xinian.KryptonHybrid.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Modern web-style theme system ported from MemoryCatcher.
 * <p>Provides a dark/light {@link ColorPalette} (GitHub-Primer inspired) and
 * helper drawing utilities (rounded rects, gradients, shadows, tooltips).</p>
 */
public final class UITheme {

    public enum Mode { DARK, LIGHT }

    private static Mode currentMode = Mode.DARK;

    private static final ColorPalette DARK = new ColorPalette(
            0xF00D1117, 0xFF161B22, 0xFF21262D, 0xFF30363D, 0xFF8B949E,
            0xFF58A6FF, 0xFF79C0FF, 0xFFA5D6FF,
            0xFFF0F6FC, 0xFFC9D1D9, 0xFF8B949E,
            0xFF3FB950, 0xFFD29922, 0xFFF85149,
            0xFF0D1117, 0xFF21262D, 0xFF484F58,
            0x40000000, 0xFF0D1117, 0xFF30363D, 0xFF58A6FF,
            0xFF1F6FEB, 0xFF8957E5, 0xFF238636, 0xFFDA3633, 0xE0FFFFFF, 0xFF484F58
    );

    private static final ColorPalette LIGHT = new ColorPalette(
            0xF0FFFFFF, 0xFFF6F8FA, 0xFFFFFFFF, 0xFFD0D7DE, 0xFF57606A,
            0xFF0969DA, 0xFF0550AE, 0xFF54AEFF,
            0xFF1F2328, 0xFF656D76, 0xFF8C959F,
            0xFF1A7F37, 0xFF9A6700, 0xFFCF222E,
            0xFFF6F8FA, 0xFFEAEEF2, 0xFFAFB8C1,
            0x20000000, 0xFFFFFFFF, 0xFFD8DEE4, 0xFF0969DA,
            0xFF8250DF, 0xFFBF3989, 0xFFDAFBE1, 0xFFFFE7E7, 0xE0FFFFFF, 0xFFD8DEE4
    );

    public static void setMode(Mode mode) { currentMode = mode; }
    public static Mode getMode() { return currentMode; }
    public static void toggleMode() { currentMode = currentMode == Mode.DARK ? Mode.LIGHT : Mode.DARK; }
    public static ColorPalette colors() { return currentMode == Mode.DARK ? DARK : LIGHT; }

    // ─── Rounded rect ───
    public static void fillRoundedRect(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        if (radius <= 0 || w < radius * 2 || h < radius * 2) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x + radius, y, x + w - radius, y + h, color);
        g.fill(x, y + radius, x + radius, y + h - radius, color);
        g.fill(x + w - radius, y + radius, x + w, y + h - radius, color);
        fillRoundedCorner(g, x, y, radius, color, true, true);
        fillRoundedCorner(g, x + w - radius, y, radius, color, false, true);
        fillRoundedCorner(g, x, y + h - radius, radius, color, true, false);
        fillRoundedCorner(g, x + w - radius, y + h - radius, radius, color, false, false);
    }

    private static void fillRoundedCorner(GuiGraphics g, int cx, int cy, int r, int color, boolean left, boolean top) {
        if (r <= 2) { fillCorner(g, cx, cy, r, color, left, top); return; }
        for (int dy = 0; dy < r; dy++) {
            float distY = top ? (r - dy - 0.5f) : (dy + 0.5f);
            int width = (int) Math.sqrt((float) r * r - distY * distY);
            if (left) g.fill(cx + (r - width), cy + dy, cx + r, cy + dy + 1, color);
            else      g.fill(cx, cy + dy, cx + width, cy + dy + 1, color);
        }
    }

    private static void fillCorner(GuiGraphics g, int cx, int cy, int r, int color, boolean left, boolean top) {
        for (int dy = 0; dy < r; dy++) for (int dx = 0; dx < r; dx++) {
            float distX = left ? (r - dx - 0.5f) : (dx + 0.5f);
            float distY = top  ? (r - dy - 0.5f) : (dy + 0.5f);
            if (distX * distX + distY * distY <= (float) r * r)
                g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    public static void drawRoundedBorder(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        g.fill(x + radius, y, x + w - radius, y + 1, color);
        g.fill(x + radius, y + h - 1, x + w - radius, y + h, color);
        g.fill(x, y + radius, x + 1, y + h - radius, color);
        g.fill(x + w - 1, y + radius, x + w, y + h - radius, color);
        if (radius >= 2) {
            g.fill(x + 1, y + 1, x + 2, y + 2, color);
            g.fill(x + w - 2, y + 1, x + w - 1, y + 2, color);
            g.fill(x + 1, y + h - 2, x + 2, y + h - 1, color);
            g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, color);
        }
    }

    public static void fillGradient(GuiGraphics g, int x, int y, int w, int h, int colorTop, int colorBottom) {
        if (h <= 0 || w <= 0) return;
        int steps = Math.min(h, 16);
        int stepH = Math.max(h / steps, 1);
        for (int i = 0; i < steps; i++) {
            float t = (float) i / Math.max(1, steps - 1);
            int color = lerpColor(colorTop, colorBottom, t);
            int sy = y + i * stepH;
            int ey = (i == steps - 1) ? y + h : sy + stepH;
            g.fill(x, sy, x + w, ey, color);
        }
    }

    public static void drawHLine(GuiGraphics g, int x, int y, int width, int color) {
        g.fill(x, y, x + width, y + 1, color);
    }

    // ─── Animation easing ───
    public static float easeOutCubic(float t) { t = clamp01(t); float f = 1 - t; return 1 - f * f * f; }
    public static float easeInOutQuad(float t) { t = clamp01(t); return t < 0.5f ? 2 * t * t : 1 - (-2 * t + 2) * (-2 * t + 2) / 2; }
    public static float smoothDamp(float current, float target, float speed) { return current + (target - current) * Math.min(1.0f, speed); }
    private static float clamp01(float t) { return Math.max(0, Math.min(1, t)); }

    public static void drawCardShadow(GuiGraphics g, int x, int y, int w, int h, int radius) {
        var c = colors();
        for (int i = 4; i >= 1; i--) {
            int alpha = 8 - i * 2;
            fillRoundedRect(g, x + i, y + i, w, h, radius, withAlpha(c.shadow(), alpha));
        }
        fillRoundedRect(g, x + 2, y + 2, w, h, radius, withAlpha(c.shadow(), 0x20));
    }

    /** MemoryCatcher-style tooltip background with rounded border and soft shadow. */
    public static void renderTooltipBackground(GuiGraphics g, int x, int y, int w, int h) {
        var c = colors();
        drawCardShadow(g, x - 2, y - 2, w + 4, h + 4, 6);
        fillRoundedRect(g, x, y, w, h, 6, withAlpha(c.headerBg(), 0xF5));
        drawRoundedBorder(g, x, y, w, h, 6, withAlpha(c.widgetBorderHover(), 0xA0));
        fillRoundedRect(g, x, y, w, 3, 3, withAlpha(0xFFFFFF, 0x20));
    }

    public static int lerpColor(int c1, int c2, float t) {
        t = clamp01(t);
        int a = (int) (((c1 >> 24) & 0xFF) + (((c2 >> 24) & 0xFF) - ((c1 >> 24) & 0xFF)) * t);
        int r = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
        int g = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
        int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }

    public static int brighten(int color, float factor) {
        int a = (color >> 24) & 0xFF;
        int r = Math.min(255, (int) (((color >> 16) & 0xFF) * (1 + factor)));
        int g = Math.min(255, (int) (((color >> 8) & 0xFF) * (1 + factor)));
        int b = Math.min(255, (int) ((color & 0xFF) * (1 + factor)));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public record ColorPalette(
            int panelBg, int headerBg, int widgetBg, int widgetBorder, int widgetBorderHover,
            int accent, int accentHover, int accentLight,
            int textPrimary, int textSecondary, int textMuted,
            int successColor, int warningColor, int dangerColor,
            int inputBg, int scrollbarTrack, int scrollbarThumb,
            int shadow, int graphBg, int gridLine, int graphLine,
            int accentSecondary, int accentTertiary, int successBg, int dangerBg, int glassBg, int divider
    ) {}
}

