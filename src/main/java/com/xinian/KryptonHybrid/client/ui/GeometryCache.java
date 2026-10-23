package com.xinian.KryptonHybrid.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.xinian.KryptonHybrid.client.compat.KryptonGuiGraphics;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;

/**
 * GPU-resident shape geometry keyed by a draw-site slot.
 *
 * <p>Chart curves are thousands of anti-aliased triangles but only change when a
 * snapshot arrives (about once a second) or the layout changes. Each slot keeps its
 * triangles in a {@link VertexBuffer} in GUI coordinates and rebuilds them only when
 * its key differs from the one it was built with; every other frame costs a single
 * draw call. Render thread only.</p>
 */
public final class GeometryCache {
    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    private static final BufferBuilder RECORD = new BufferBuilder(1 << 15);

    private static final class Entry {
        private Object key;
        private VertexBuffer buffer;
        private boolean empty;
    }

    private GeometryCache() {}

    /**
     * Draws the geometry cached for {@code slot}, first rebuilding it with
     * {@code build} if {@code key} changed. {@code key} must capture everything the
     * geometry depends on (data version, bounds, colors, GUI scale).
     */
    public static void draw(KryptonGuiGraphics g, String slot, Object key, Consumer<KryptonGuiGraphics> build) {
        Entry entry = ENTRIES.computeIfAbsent(slot, s -> new Entry());
        if (!key.equals(entry.key)) {
            RECORD.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            build.accept(KryptonGuiGraphics.recorder(RECORD));
            BufferBuilder.RenderedBuffer rendered = RECORD.end();
            entry.key = key;
            entry.empty = rendered.isEmpty();
            if (entry.empty) {
                rendered.release();
            } else {
                if (entry.buffer == null) {
                    entry.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                }
                entry.buffer.bind();
                entry.buffer.upload(rendered);
                VertexBuffer.unbind();
            }
        }
        if (!entry.empty) {
            g.drawCached(entry.buffer);
        }
    }

    /** Frees the GPU buffers of every slot whose name starts with {@code prefix}. */
    public static void release(String prefix) {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(() -> release(prefix));
            return;
        }
        Iterator<Map.Entry<String, Entry>> it = ENTRIES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> e = it.next();
            if (e.getKey().startsWith(prefix)) {
                if (e.getValue().buffer != null) {
                    e.getValue().buffer.close();
                }
                it.remove();
            }
        }
    }
}
