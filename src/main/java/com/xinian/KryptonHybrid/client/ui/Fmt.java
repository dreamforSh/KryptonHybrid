package com.xinian.KryptonHybrid.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Compact number formatting for the dashboard and HUD. */
public final class Fmt {
    private Fmt() {}

    public static String bytes(double bytes) {
        if (Double.isNaN(bytes) || bytes < 0) {
            return "--";
        }
        if (bytes < 1024) {
            return String.format(Locale.ROOT, "%.0f B", bytes);
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024);
        }
        if (bytes < 1024.0 * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024 * 1024));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static String rate(double bytesPerSecond) {
        return bytes(bytesPerSecond) + "/s";
    }

    public static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    public static String perSecond(double value) {
        if (Double.isNaN(value)) {
            return "--";
        }
        return String.format(Locale.ROOT, value < 10 ? "%.1f/s" : "%,.0f/s", value);
    }

    public static String percent(double value) {
        if (Double.isNaN(value)) {
            return "--";
        }
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    /** Server uptime as "3h 12m" style text, localized. */
    public static String duration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return Component.translatable("gui.krypton_hybrid.dash.duration.hm", h, m).getString();
        }
        if (m > 0) {
            return Component.translatable("gui.krypton_hybrid.dash.duration.ms", m, s).getString();
        }
        return Component.translatable("gui.krypton_hybrid.dash.duration.s", s).getString();
    }

    private static final String ELLIPSIS = "...";

    /** Cuts {@code text} to fit {@code maxWidth}, ending in "..." (keeps at least one character). */
    public static String truncate(Font font, String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return "-";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        // Glyph widths add up, so the longest prefix that fits next to the ellipsis is one scan.
        String prefix = font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(ELLIPSIS)));
        if (prefix.isEmpty()) {
            prefix = text.substring(0, 1);
        }
        return prefix + ELLIPSIS;
    }
}
