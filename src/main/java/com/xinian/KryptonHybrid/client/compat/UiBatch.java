package com.xinian.KryptonHybrid.client.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Frame batch for the dashboard and HUD (render thread only).
 *
 * <p>Vanilla draws every fill and every string as its own draw call. While a
 * {@link KryptonGuiGraphics} is batching, shapes go into one shared triangle buffer,
 * cached chart geometry is queued, and text is collected per font texture. A
 * {@link #flush()} then issues shapes, charts, text, in that order: text always ends
 * on top, which matches how every widget is drawn (background, then label). Flushes
 * happen at scissor changes and before anything drawn outside the batch.</p>
 */
public final class UiBatch {
    private static final BufferBuilder SHAPES = new BufferBuilder(1 << 16);
    private static final TextBuffers TEXT = new TextBuffers();
    private static final List<CachedDraw> CACHED = new ArrayList<>();
    private static int shapeVertices;

    private record CachedDraw(VertexBuffer buffer, Matrix4f modelView) {}

    private UiBatch() {}

    static BufferBuilder shapes() {
        if (!SHAPES.building()) {
            SHAPES.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        }
        return SHAPES;
    }

    static void shapeVerticesAdded(int count) {
        shapeVertices += count;
    }

    static MultiBufferSource text() {
        return TEXT;
    }

    static void deferCached(VertexBuffer buffer, Matrix4f pose) {
        CACHED.add(new CachedDraw(buffer, modelView(pose)));
    }

    static void flush() {
        if (SHAPES.building()) {
            BufferBuilder.RenderedBuffer rendered = SHAPES.end();
            if (shapeVertices > 0) {
                beginShapeState();
                BufferUploader.drawWithShader(rendered);
                endShapeState();
            } else {
                rendered.release();
            }
        }
        shapeVertices = 0;
        if (!CACHED.isEmpty()) {
            beginShapeState();
            for (CachedDraw draw : CACHED) {
                drawBuffer(draw.buffer(), draw.modelView());
            }
            VertexBuffer.unbind();
            endShapeState();
            CACHED.clear();
        }
        TEXT.draw();
    }

    /** Draws cached geometry right away (used when not batching). */
    static void drawCachedNow(VertexBuffer buffer, Matrix4f pose) {
        beginShapeState();
        drawBuffer(buffer, modelView(pose));
        VertexBuffer.unbind();
        endShapeState();
    }

    /** GL state for position/color triangles (blend on, no texture, no culling). */
    public static void beginShapeState() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableTexture();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    public static void endShapeState() {
        RenderSystem.enableCull();
        RenderSystem.enableTexture();
        RenderSystem.disableBlend();
    }

    private static void drawBuffer(VertexBuffer buffer, Matrix4f modelView) {
        buffer.bind();
        buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), GameRenderer.getPositionColorShader());
    }

    /**
     * Cached geometry is stored in GUI coordinates; immediate vertices are
     * pre-multiplied by the GUI pose and then by the global model-view, so the
     * cached draw uses model-view x pose.
     */
    private static Matrix4f modelView(Matrix4f pose) {
        Matrix4f modelView = RenderSystem.getModelViewMatrix().copy();
        modelView.multiply(pose);
        return modelView;
    }

    /**
     * Text vertices per render type (one per glyph texture). Unlike vanilla's
     * immediate buffer source it never flushes on a render-type switch, so queued
     * text cannot be drawn before the shapes queued ahead of it.
     */
    private static final class TextBuffers implements MultiBufferSource {
        private final Map<RenderType, BufferBuilder> pool = new HashMap<>();
        private final Map<RenderType, BufferBuilder> active = new LinkedHashMap<>();

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            BufferBuilder buffer = active.get(type);
            if (buffer == null) {
                buffer = pool.computeIfAbsent(type, t -> new BufferBuilder(Math.max(256, t.bufferSize())));
                buffer.begin(type.mode(), type.format());
                active.put(type, buffer);
            }
            return buffer;
        }

        void draw() {
            for (Map.Entry<RenderType, BufferBuilder> entry : active.entrySet()) {
                entry.getKey().end(entry.getValue(), 0, 0, 0);
            }
            active.clear();
        }
    }
}
