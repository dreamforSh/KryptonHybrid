package com.xinian.KryptonHybrid.client.ui;

/**
 * Dark/light {@link ColorPalette} (GitHub-Primer inspired) for the dashboard and
 * HUD, plus color math. Shapes are drawn with {@link Shapes}.
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

    private static int panelOpacityPercent = 85;

    private UITheme() {}

    public static void setMode(Mode mode) { currentMode = mode; }
    public static Mode getMode() { return currentMode; }
    public static void toggleMode() { currentMode = currentMode == Mode.DARK ? Mode.LIGHT : Mode.DARK; }
    public static ColorPalette colors() { return currentMode == Mode.DARK ? DARK : LIGHT; }

    public static int panelOpacityPercent() { return panelOpacityPercent; }
    public static void setPanelOpacityPercent(int percent) { panelOpacityPercent = Math.max(40, Math.min(100, percent)); }
    /** Background color of floating panels at the configured opacity. */
    public static int panelBackground() { return withAlpha(colors().panelBg(), panelOpacityPercent * 255 / 100); }

    public static int lerpColor(int c1, int c2, float t) {
        t = Math.max(0, Math.min(1, t));
        int a = (int) (((c1 >> 24) & 0xFF) + (((c2 >> 24) & 0xFF) - ((c1 >> 24) & 0xFF)) * t);
        int r = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
        int g = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
        int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }

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
