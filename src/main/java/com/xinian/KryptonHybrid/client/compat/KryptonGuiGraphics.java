package com.xinian.KryptonHybrid.client.compat;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Minimal 1.20-style drawing facade for the 1.19.2 client API.
 *
 * <p>Between {@link #beginBatch()} and {@link #endBatch()} fills, shapes and text are
 * collected in {@link UiBatch} and drawn together; scissor changes flush
 * automatically. A {@linkplain #recorder recorder} instead captures shape geometry
 * in GUI coordinates for caching.</p>
 */
public final class KryptonGuiGraphics {
    private final PoseStack poseStack;
    private final BufferBuilder recordTarget;
    private boolean batching;

    public KryptonGuiGraphics(PoseStack poseStack) {
        this(poseStack, null);
    }

    private KryptonGuiGraphics(PoseStack poseStack, BufferBuilder recordTarget) {
        this.poseStack = poseStack;
        this.recordTarget = recordTarget;
    }

    /** Graphics that writes shapes into {@code target} with an identity pose; it cannot draw text. */
    public static KryptonGuiGraphics recorder(BufferBuilder target) {
        return new KryptonGuiGraphics(new PoseStack(), target);
    }

    public PoseStack pose() {
        return poseStack;
    }

    // ------------------------------------------------------------------
    //  Batching
    // ------------------------------------------------------------------

    public void beginBatch() {
        batching = true;
    }

    public void endBatch() {
        flush();
        batching = false;
    }

    /** Draws everything queued so far; call before drawing outside this facade. */
    public void flush() {
        if (batching) {
            UiBatch.flush();
        }
    }

    /**
     * Destination for shape vertices: the recorder's buffer, the frame batch, or
     * {@code null} when shapes must draw immediately.
     */
    public BufferBuilder shapeTarget() {
        if (recordTarget != null) {
            return recordTarget;
        }
        return batching ? UiBatch.shapes() : null;
    }

    public void shapeVerticesAdded(int count) {
        if (recordTarget == null && batching) {
            UiBatch.shapeVerticesAdded(count);
        }
    }

    /** Draws cached GUI-space geometry with the current pose (deferred while batching). */
    public void drawCached(VertexBuffer buffer) {
        if (batching) {
            UiBatch.deferCached(buffer, poseStack.last().pose());
        } else {
            UiBatch.drawCachedNow(buffer, poseStack.last().pose());
        }
    }

    // ------------------------------------------------------------------
    //  Drawing
    // ------------------------------------------------------------------

    public void fill(int minX, int minY, int maxX, int maxY, int color) {
        if (batching || recordTarget != null) {
            com.xinian.KryptonHybrid.client.ui.Shapes.rect(this, minX, minY, maxX, maxY, color);
        } else {
            GuiComponent.fill(poseStack, minX, minY, maxX, maxY, color);
        }
    }

    public void drawString(Font font, String text, int x, int y, int color, boolean shadow) {
        if (batching) {
            font.drawInBatch(text, x, y, color, shadow, poseStack.last().pose(), UiBatch.text(), false, 0, LightTexture.FULL_BRIGHT);
        } else if (shadow) {
            font.drawShadow(poseStack, text, x, y, color);
        } else {
            font.draw(poseStack, text, x, y, color);
        }
    }

    public void drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
        if (batching) {
            font.drawInBatch(text, x, y, color, shadow, poseStack.last().pose(), UiBatch.text(), false, 0, LightTexture.FULL_BRIGHT);
        } else if (shadow) {
            font.drawShadow(poseStack, text, x, y, color);
        } else {
            font.draw(poseStack, text, x, y, color);
        }
    }

    public void drawString(Font font, FormattedCharSequence text, int x, int y, int color) {
        if (batching) {
            font.drawInBatch(text, x, y, color, false, poseStack.last().pose(), UiBatch.text(), false, 0, LightTexture.FULL_BRIGHT);
        } else {
            font.draw(poseStack, text, x, y, color);
        }
    }

    public void drawCenteredString(Font font, String text, int x, int y, int color) {
        drawString(font, text, x - font.width(text) / 2, y, color, true);
    }

    public void enableScissor(int minX, int minY, int maxX, int maxY) {
        flush();
        GuiComponent.enableScissor(minX, minY, maxX, maxY);
    }

    public void disableScissor() {
        flush();
        GuiComponent.disableScissor();
    }

    public int guiWidth() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    public int guiHeight() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }
}
