package com.xinian.KryptonHybrid.client.ui;

import com.xinian.KryptonHybrid.client.compat.KryptonGuiGraphics;

/**
 * Charts for the stats dashboard and HUD, drawn with {@link Shapes}: series are
 * smoothed with monotone cubic interpolation (no overshoot past the data, so rates
 * never dip below zero) and rendered as anti-aliased lines over a soft gradient.
 * No text is drawn here.
 */
public final class Charts {
    private Charts() {}

    /** Largest finite value across the series, or 0 when there is none. */
    public static double max(double[]... series) {
        double max = 0.0;
        for (double[] values : series) {
            for (double v : values) {
                if (!Double.isNaN(v) && v > max) {
                    max = v;
                }
            }
        }
        return max;
    }

    /** A rounded-up axis maximum with 10% headroom, never zero. */
    public static double niceMax(double max) {
        if (max <= 0.0) {
            return 1.0;
        }
        double padded = max * 1.1;
        double magnitude = Math.pow(10, Math.floor(Math.log10(padded)));
        double step = padded / magnitude;
        double nice = step <= 1 ? 1 : step <= 2 ? 2 : step <= 2.5 ? 2.5 : step <= 5 ? 5 : 10;
        return nice * magnitude;
    }

    /** Everything a cached curve's triangles depend on. */
    private record CurveKey(long dataVersion, int x, int y, int w, int h, double max, int color0, int color1,
                            float pixel) {}

    /**
     * Single series scaled to its own range, for KPI cards and the HUD. The curve is
     * cached under {@code slot} until {@code dataVersion}, bounds, color or GUI scale change.
     */
    public static void sparkline(KryptonGuiGraphics g, String slot, long dataVersion,
                                 int x, int y, int w, int h, double[] values, int color) {
        if (values.length < 2 || w < 2 || h < 2) {
            return;
        }
        GeometryCache.draw(g, slot, new CurveKey(dataVersion, x, y, w, h, 0, color, 0, Shapes.pixel()), rec -> {
            double min = 0.0;
            double max = 0.0;
            for (double v : values) {
                if (!Double.isNaN(v)) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
            }
            if (max - min < 1e-9) {
                max = min + 1.0;
            }
            drawSeries(rec, x, y + 1, w, h - 2, values, min, max, color, 1.1f, true, true);
        });
    }

    /**
     * Two series on a shared 0..{@code max} scale with hairline grid lines; the first
     * gets a gradient area fill. Curves are cached under {@code slot} like
     * {@link #sparkline}.
     */
    public static void lineChart(KryptonGuiGraphics g, String slot, long dataVersion, int x, int y, int w, int h,
                                 double max, double[][] series, int[] colors) {
        var c = UITheme.colors();
        for (int i = 1; i <= 3; i++) {
            float gy = y + h - 1 - (h - 1) * i / 4f;
            Shapes.hairline(g, x, x + w, gy, UITheme.withAlpha(c.gridLine(), 0xA0));
        }
        Shapes.hairline(g, x, x + w, y + h - 1, c.gridLine());
        CurveKey key = new CurveKey(dataVersion, x, y, w, h, max, colors[0], colors[1], Shapes.pixel());
        GeometryCache.draw(g, slot, key, rec -> {
            // Area first so it never tints the other line; the first series' line ends on top.
            drawSeries(rec, x, y + 1, w, h - 3, series[0], 0.0, max, colors[0], 1.4f, true, false);
            for (int s = series.length - 1; s >= 0; s--) {
                drawSeries(rec, x, y + 1, w, h - 3, series[s], 0.0, max, colors[s], 1.4f, false, true);
            }
        });
    }

    /** Horizontal meter with rounded ends; {@code fraction} is clamped to 0..1. */
    public static void bar(KryptonGuiGraphics g, int x, int y, int w, int h, double fraction, int fillColor) {
        var c = UITheme.colors();
        double f = Double.isNaN(fraction) ? 0.0 : Math.max(0.0, Math.min(1.0, fraction));
        Shapes.pill(g, x, y, w, h, UITheme.withAlpha(c.widgetBorder(), 0x90));
        float fill = (float) (w * f);
        if (fill > 0.05f) {
            // Keep the fill at least as wide as it is tall so both ends stay round.
            Shapes.pill(g, x, y, Math.max(fill, h), h, fillColor);
        }
    }

    private static void drawSeries(KryptonGuiGraphics g, float x, float y, float w, float h, double[] values,
                                   double min, double max, int color, float lineWidth, boolean area, boolean line) {
        int n = values.length;
        if (n < 2 || w < 2) {
            return;
        }
        float step = w / (n - 1);
        int start = 0;
        while (start < n) {
            while (start < n && Double.isNaN(values[start])) {
                start++;
            }
            int end = start;
            while (end < n && !Double.isNaN(values[end])) {
                end++;
            }
            if (end - start >= 2) {
                drawRun(g, x + start * step, y, step, h, values, start, end, min, max, color, lineWidth, area, line);
            }
            start = end;
        }
    }

    /** Smooths values[from, to) and draws them; {@code x0} is the x of values[from]. */
    private static void drawRun(KryptonGuiGraphics g, float x0, float y, float step, float h, double[] values,
                                int from, int to, double min, double max, int color, float lineWidth,
                                boolean area, boolean line) {
        int n = to - from;
        double[] slopes = monotoneSlopes(values, from, to);
        int perInterval = Math.max(1, Math.min(12, (int) Math.ceil(step)));
        int samples = (n - 1) * perInterval + 1;
        float[] xs = new float[samples];
        float[] ys = new float[samples];
        float bottom = y + h;
        double range = max - min;
        int k = 0;
        for (int i = 0; i < n - 1; i++) {
            double y0 = values[from + i];
            double y1 = values[from + i + 1];
            double m0 = slopes[i];
            double m1 = slopes[i + 1];
            for (int s = 0; s < perInterval; s++) {
                double t = s / (double) perInterval;
                xs[k] = x0 + (i + (float) t) * step;
                ys[k] = toY(hermite(y0, y1, m0, m1, t), min, range, y, h);
                k++;
            }
        }
        xs[k] = x0 + (n - 1) * step;
        ys[k] = toY(values[to - 1], min, range, y, h);

        if (area) {
            Shapes.areaUnder(g, xs, ys, 0, samples, bottom,
                    UITheme.withAlpha(color, 0x40), UITheme.withAlpha(color, 0x04));
        }
        if (line) {
            Shapes.polyline(g, xs, ys, 0, samples, lineWidth, color);
        }
    }

    private static float toY(double v, double min, double range, float y, float h) {
        double t = (v - min) / range;
        t = Math.max(0.0, Math.min(1.0, t));
        return (float) (y + h - t * h);
    }

    private static double hermite(double y0, double y1, double m0, double m1, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * y0 + (t3 - 2 * t2 + t) * m0 + (-2 * t3 + 3 * t2) * y1 + (t3 - t2) * m1;
    }

    /** Fritsch-Carlson tangents for unit-spaced points: the curve never overshoots. */
    private static double[] monotoneSlopes(double[] v, int from, int to) {
        int n = to - from;
        double[] d = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            d[i] = v[from + i + 1] - v[from + i];
        }
        double[] m = new double[n];
        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) {
            m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
        }
        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0) {
                m[i] = 0;
                m[i + 1] = 0;
                continue;
            }
            double a = m[i] / d[i];
            double b = m[i + 1] / d[i];
            double s = a * a + b * b;
            if (s > 9) {
                double tau = 3 / Math.sqrt(s);
                m[i] = tau * a * d[i];
                m[i + 1] = tau * b * d[i];
            }
        }
        return m;
    }
}
