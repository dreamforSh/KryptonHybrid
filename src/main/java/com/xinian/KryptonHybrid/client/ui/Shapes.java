package com.xinian.KryptonHybrid.client.ui;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import com.xinian.KryptonHybrid.client.compat.KryptonGuiGraphics;
import com.xinian.KryptonHybrid.client.compat.UiBatch;
import net.minecraft.client.Minecraft;

/**
 * Anti-aliased vector shapes for the dashboard and HUD.
 *
 * <p>Vanilla GUI fills are axis-aligned rectangles snapped to GUI pixels, which at GUI
 * scale 2-4 makes rounded corners and chart lines visibly stepped. These shapes are
 * built from float-positioned triangles, so they resolve at screen-pixel precision,
 * and every edge gets a one-screen-pixel fringe fading to transparent for
 * anti-aliasing.</p>
 *
 * <p>Vertices go wherever {@link KryptonGuiGraphics#shapeTarget()} points: the frame
 * batch, a geometry recorder, or (outside a batch) an immediate draw. Corner points
 * come from precomputed angle tables and are written straight into the buffer, so
 * drawing allocates nothing. Render thread only.</p>
 */
public final class Shapes {
    private static final int MAX_SEGMENTS = 24;
    /** Per segment count n: cos/sin for the 4*(n+1) corner points, clockwise from the top-left arc. */
    private static final float[][] COS = new float[MAX_SEGMENTS + 1][];
    private static final float[][] SIN = new float[MAX_SEGMENTS + 1][];

    // Scratch outlines (render thread only): two rings of up to 4*(MAX_SEGMENTS+1) points.
    private static final float[] AX = new float[4 * (MAX_SEGMENTS + 1)];
    private static final float[] AY = new float[4 * (MAX_SEGMENTS + 1)];
    private static final float[] BX = new float[4 * (MAX_SEGMENTS + 1)];
    private static final float[] BY = new float[4 * (MAX_SEGMENTS + 1)];

    private Shapes() {}

    // ------------------------------------------------------------------
    //  Public shapes
    // ------------------------------------------------------------------

    /** Pixel-aligned rectangle without anti-aliasing, equivalent to a vanilla fill. */
    public static void rect(KryptonGuiGraphics g, float x0, float y0, float x1, float y1, int color) {
        if (alpha(color) == 0 || x0 == x1 || y0 == y1) {
            return;
        }
        Builder b = Builder.begin(g);
        b.quad(Math.min(x0, x1), Math.max(y0, y1), Math.max(x0, x1), Math.max(y0, y1),
                Math.max(x0, x1), Math.min(y0, y1), Math.min(x0, x1), Math.min(y0, y1),
                color, color, color, color);
        b.end();
    }

    /** Filled rounded rectangle; {@code r} is clamped to half the shorter side. */
    public static void roundRect(KryptonGuiGraphics g, float x, float y, float w, float h, float r, int color) {
        if (w <= 0 || h <= 0 || alpha(color) == 0) {
            return;
        }
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        float aa = pixel();
        int n = segments(r);
        int count = outline(x, y, x + w, y + h, r, -aa / 2, n, AX, AY);
        outline(x, y, x + w, y + h, r, aa / 2, n, BX, BY);
        Builder b = Builder.begin(g);
        float cx = x + w / 2;
        float cy = y + h / 2;
        for (int i = 0; i < count; i++) {
            int j = i + 1 == count ? 0 : i + 1;
            b.tri(cx, cy, color, AX[i], AY[i], color, AX[j], AY[j], color);
        }
        ring(b, AX, AY, BX, BY, count, color, transparent(color));
        b.end();
    }

    /** Fully rounded ends (pill / circle when square). */
    public static void pill(KryptonGuiGraphics g, float x, float y, float w, float h, int color) {
        roundRect(g, x, y, w, h, Math.min(w, h) / 2, color);
    }

    public static void circle(KryptonGuiGraphics g, float cx, float cy, float radius, int color) {
        roundRect(g, cx - radius, cy - radius, radius * 2, radius * 2, radius, color);
    }

    /** Border drawn just inside the rounded rectangle's edge. */
    public static void roundRectStroke(KryptonGuiGraphics g, float x, float y, float w, float h, float r,
                                       float strokeWidth, int color) {
        if (w <= 0 || h <= 0 || alpha(color) == 0) {
            return;
        }
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        float aa = pixel();
        float sw = Math.max(strokeWidth, aa);
        int n = segments(r);
        float x1 = x + w;
        float y1 = y + h;
        int clear = transparent(color);
        Builder b = Builder.begin(g);
        // Inner fringe, solid band, outer fringe; each ring reuses the previous outer edge.
        int count = outline(x, y, x1, y1, r, -sw - aa / 2, n, AX, AY);
        outline(x, y, x1, y1, r, -sw + aa / 2, n, BX, BY);
        ring(b, AX, AY, BX, BY, count, clear, color);
        if (sw > aa) {
            outline(x, y, x1, y1, r, -aa / 2, n, AX, AY);
            ring(b, BX, BY, AX, AY, count, color, color);
        } else {
            System.arraycopy(BX, 0, AX, 0, count);
            System.arraycopy(BY, 0, AY, 0, count);
        }
        outline(x, y, x1, y1, r, aa / 2, n, BX, BY);
        ring(b, AX, AY, BX, BY, count, color, clear);
        b.end();
    }

    /** A crisp line one screen pixel thick. */
    public static void hairline(KryptonGuiGraphics g, float x0, float x1, float y, int color) {
        if (alpha(color) == 0) {
            return;
        }
        float t = pixel();
        Builder b = Builder.begin(g);
        b.quad(x0, y, x1, y, x1, y + t, x0, y + t, color, color, color, color);
        b.end();
    }

    /**
     * Anti-aliased polyline through {@code (xs[i], ys[i])} for {@code i} in
     * {@code [from, to)}; joins use averaged normals, which is seamless for the
     * densely sampled curves the charts produce.
     */
    public static void polyline(KryptonGuiGraphics g, float[] xs, float[] ys, int from, int to, float width, int color) {
        int count = to - from;
        if (count < 2 || alpha(color) == 0) {
            return;
        }
        float aa = pixel();
        float core = Math.max(0, width / 2 - aa / 2);
        float edge = width / 2 + aa / 2;
        int clear = transparent(color);
        Builder b = Builder.begin(g);
        float pnx = 0;
        float pny = 0;
        for (int i = 0; i < count; i++) {
            int a = from + Math.max(0, i - 1);
            int c = from + Math.min(count - 1, i + 1);
            float tx = xs[c] - xs[a];
            float ty = ys[c] - ys[a];
            float len = (float) Math.sqrt(tx * tx + ty * ty);
            float nx = len < 1e-6f ? 0 : -ty / len;
            float ny = len < 1e-6f ? -1 : tx / len;
            if (i > 0) {
                int p = from + i - 1;
                float ax = xs[p], ay = ys[p], bx = xs[p + 1], by = ys[p + 1];
                if (core > 0) {
                    b.quad(ax + pnx * core, ay + pny * core, bx + nx * core, by + ny * core,
                            bx - nx * core, by - ny * core, ax - pnx * core, ay - pny * core,
                            color, color, color, color);
                }
                b.quad(ax + pnx * core, ay + pny * core, bx + nx * core, by + ny * core,
                        bx + nx * edge, by + ny * edge, ax + pnx * edge, ay + pny * edge,
                        color, color, clear, clear);
                b.quad(ax - pnx * core, ay - pny * core, bx - nx * core, by - ny * core,
                        bx - nx * edge, by - ny * edge, ax - pnx * edge, ay - pny * edge,
                        color, color, clear, clear);
            }
            pnx = nx;
            pny = ny;
        }
        b.end();
    }

    /** Vertical gradient between a curve and {@code baseY}. */
    public static void areaUnder(KryptonGuiGraphics g, float[] xs, float[] ys, int from, int to, float baseY,
                                 int topColor, int bottomColor) {
        if (to - from < 2) {
            return;
        }
        Builder b = Builder.begin(g);
        for (int i = from; i < to - 1; i++) {
            b.quad(xs[i], ys[i], xs[i + 1], ys[i + 1], xs[i + 1], baseY, xs[i], baseY,
                    topColor, topColor, bottomColor, bottomColor);
        }
        b.end();
    }

    /** One screen pixel in GUI units. */
    public static float pixel() {
        return 1.0F / (float) Minecraft.getInstance().getWindow().getGuiScale();
    }

    // ------------------------------------------------------------------
    //  Geometry
    // ------------------------------------------------------------------

    private static int segments(float r) {
        float screenRadius = r / pixel();
        return Math.max(1, Math.min(MAX_SEGMENTS, (int) Math.ceil(screenRadius * 0.5f)));
    }

    /**
     * Writes the rounded rectangle's outline offset by {@code d} (positive grows
     * outward) into {@code xs/ys}, clockwise from the top-left corner, {@code n + 1}
     * points per corner, and returns the point count. Offset curves of a rounded
     * rectangle are concentric arcs; once an inward offset passes the radius the
     * corner becomes sharp.
     */
    private static int outline(float x0, float y0, float x1, float y1, float r, float d, int n, float[] xs, float[] ys) {
        float[] cos = COS[n];
        float[] sin = SIN[n];
        if (cos == null) {
            cos = new float[4 * (n + 1)];
            sin = new float[4 * (n + 1)];
            double[] start = {Math.PI, Math.PI * 1.5, 0, Math.PI * 0.5};
            int k = 0;
            for (int corner = 0; corner < 4; corner++) {
                for (int s = 0; s <= n; s++) {
                    double a = start[corner] + (Math.PI / 2) * s / n;
                    cos[k] = (float) Math.cos(a);
                    sin[k] = (float) Math.sin(a);
                    k++;
                }
            }
            COS[n] = cos;
            SIN[n] = sin;
        }
        float rr = Math.max(0, r + d);
        float inset = Math.max(r, -d);
        float left = x0 + inset;
        float right = x1 - inset;
        float top = y0 + inset;
        float bottom = y1 - inset;
        int perCorner = n + 1;
        for (int k = 0; k < 4 * perCorner; k++) {
            int corner = k / perCorner;
            float cx = corner == 0 || corner == 3 ? left : right;
            float cy = corner < 2 ? top : bottom;
            xs[k] = cx + rr * cos[k];
            ys[k] = cy + rr * sin[k];
        }
        return 4 * perCorner;
    }

    private static void ring(Builder b, float[] ix, float[] iy, float[] ox, float[] oy, int count,
                             int innerColor, int outerColor) {
        for (int i = 0; i < count; i++) {
            int j = i + 1 == count ? 0 : i + 1;
            b.quad(ix[i], iy[i], ix[j], iy[j], ox[j], oy[j], ox[i], oy[i],
                    innerColor, innerColor, outerColor, outerColor);
        }
    }

    private static int alpha(int color) {
        return color >>> 24;
    }

    private static int transparent(int color) {
        return color & 0x00FFFFFF;
    }

    /** Writes triangles to the graphics' shape target, or draws them on {@link #end()} if there is none. */
    private static final class Builder {
        private static final Builder INSTANCE = new Builder();

        private BufferBuilder buffer;
        private Matrix4f matrix;
        private KryptonGuiGraphics graphics;
        private boolean immediate;
        private int vertices;

        static Builder begin(KryptonGuiGraphics g) {
            Builder b = INSTANCE;
            BufferBuilder target = g.shapeTarget();
            b.immediate = target == null;
            if (b.immediate) {
                target = Tesselator.getInstance().getBuilder();
                target.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            }
            b.buffer = target;
            b.matrix = g.pose().last().pose();
            b.graphics = g;
            b.vertices = 0;
            return b;
        }

        void tri(float x0, float y0, int c0, float x1, float y1, int c1, float x2, float y2, int c2) {
            vertex(x0, y0, c0);
            vertex(x1, y1, c1);
            vertex(x2, y2, c2);
        }

        void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
                  int c0, int c1, int c2, int c3) {
            tri(x0, y0, c0, x1, y1, c1, x2, y2, c2);
            tri(x0, y0, c0, x2, y2, c2, x3, y3, c3);
        }

        private void vertex(float x, float y, int color) {
            buffer.vertex(matrix, x, y, 0.0F)
                    .color((color >> 16) & 255, (color >> 8) & 255, color & 255, color >>> 24)
                    .endVertex();
            vertices++;
        }

        void end() {
            if (!immediate) {
                graphics.shapeVerticesAdded(vertices);
            } else {
                BufferBuilder.RenderedBuffer rendered = buffer.end();
                if (vertices == 0) {
                    rendered.release();
                } else {
                    UiBatch.beginShapeState();
                    BufferUploader.drawWithShader(rendered);
                    UiBatch.endShapeState();
                }
            }
            buffer = null;
            matrix = null;
            graphics = null;
        }
    }
}
