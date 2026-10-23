package com.xinian.KryptonHybrid.client.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

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
    private static final ByteBufferBuilder SHAPE_BYTES = new ByteBufferBuilder(1 << 16);
    private static final TextBuffers TEXT = new TextBuffers();
    private static final List<CachedDraw> CACHED = new ArrayList<>();
    private static BufferBuilder shapes;

    private record CachedDraw(VertexBuffer buffer, Matrix4f modelView) {}

    private UiBatch() {}

    static BufferBuilder shapes() {
        if (shapes == null) {
            shapes = new BufferBuilder(SHAPE_BYTES, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        }
        return shapes;
    }

    static MultiBufferSource text() {
        return TEXT;
    }

    static void deferCached(VertexBuffer buffer, Matrix4f pose) {
        CACHED.add(new CachedDraw(buffer, modelView(pose)));
    }

    static void flush() {
        if (shapes != null) {
            // null when nothing was added
            MeshData mesh = shapes.build();
            shapes = null;
            if (mesh != null) {
                beginShapeState();
                BufferUploader.drawWithShader(mesh);
                endShapeState();
            }
        }
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

    /** GL state for position/color triangles (blend on, no culling). */
    public static void beginShapeState() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    public static void endShapeState() {
        RenderSystem.enableCull();
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
        return new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose);
    }

    /**
     * Text vertices per render type (one per glyph texture). Unlike vanilla's
     * immediate buffer source it never flushes on a render-type switch, so queued
     * text cannot be drawn before the shapes queued ahead of it.
     */
    private static final class TextBuffers implements MultiBufferSource {
        private final Map<RenderType, ByteBufferBuilder> pool = new HashMap<>();
        private final Map<RenderType, BufferBuilder> active = new LinkedHashMap<>();

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            BufferBuilder buffer = active.get(type);
            if (buffer == null) {
                ByteBufferBuilder bytes = pool.computeIfAbsent(type, t -> new ByteBufferBuilder(Math.max(256, t.bufferSize())));
                buffer = new BufferBuilder(bytes, type.mode(), type.format());
                active.put(type, buffer);
            }
            return buffer;
        }

        void draw() {
            for (Map.Entry<RenderType, BufferBuilder> entry : active.entrySet()) {
                MeshData mesh = entry.getValue().build();
                if (mesh != null) {
                    if (entry.getKey().sortOnUpload()) {
                        mesh.sortQuads(pool.get(entry.getKey()), RenderSystem.getVertexSorting());
                    }
                    entry.getKey().draw(mesh);
                }
            }
            active.clear();
        }
    }
}
