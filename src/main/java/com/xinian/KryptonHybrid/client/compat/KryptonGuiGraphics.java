package com.xinian.KryptonHybrid.client.compat;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Drawing facade over {@link GuiGraphics} for the dashboard and HUD, with frame batching.
 *
 * <p>Between {@link #beginBatch()} and {@link #endBatch()} fills, shapes and text are
 * collected in {@link UiBatch} and drawn together; scissor changes flush
 * automatically. Outside a batch every call goes straight to the wrapped
 * {@link GuiGraphics}. A {@linkplain #recorder recorder} instead captures shape
 * geometry in GUI coordinates for caching.</p>
 */
public final class KryptonGuiGraphics {
    private final GuiGraphics graphics;
    private final PoseStack poseStack;
    private final BufferBuilder recordTarget;
    private boolean batching;

    public KryptonGuiGraphics(GuiGraphics graphics) {
        this(graphics, graphics.pose(), null);
    }

    private KryptonGuiGraphics(GuiGraphics graphics, PoseStack poseStack, BufferBuilder recordTarget) {
        this.graphics = graphics;
        this.poseStack = poseStack;
        this.recordTarget = recordTarget;
    }

    /** Graphics that writes shapes into {@code target} with an identity pose; it cannot draw text. */
    public static KryptonGuiGraphics recorder(BufferBuilder target) {
        return new KryptonGuiGraphics(null, new PoseStack(), target);
    }

    public PoseStack pose() {
        return poseStack;
    }

    /** The wrapped graphics, for vanilla widgets; {@code null} for a recorder. */
    public GuiGraphics graphics() {
        return graphics;
    }

    // ------------------------------------------------------------------
    //  Batching
    // ------------------------------------------------------------------

    public void beginBatch() {
        // Whatever vanilla queued before the batch is drawn first.
        if (graphics != null) {
            graphics.flush();
        }
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
            graphics.fill(minX, minY, maxX, maxY, color);
        }
    }

    public void drawString(Font font, String text, int x, int y, int color, boolean shadow) {
        if (batching) {
            font.drawInBatch(text, x, y, color, shadow, poseStack.last().pose(), UiBatch.text(),
                    Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        } else {
            graphics.drawString(font, text, x, y, color, shadow);
        }
    }

    public void drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
        if (batching) {
            font.drawInBatch(text, x, y, color, shadow, poseStack.last().pose(), UiBatch.text(),
                    Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        } else {
            graphics.drawString(font, text, x, y, color, shadow);
        }
    }

    public void drawString(Font font, FormattedCharSequence text, int x, int y, int color) {
        if (batching) {
            font.drawInBatch(text, x, y, color, false, poseStack.last().pose(), UiBatch.text(),
                    Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        } else {
            graphics.drawString(font, text, x, y, color, false);
        }
    }

    public void drawCenteredString(Font font, String text, int x, int y, int color) {
        drawString(font, text, x - font.width(text) / 2, y, color, true);
    }

    public void enableScissor(int minX, int minY, int maxX, int maxY) {
        flush();
        graphics.enableScissor(minX, minY, maxX, maxY);
    }

    public void disableScissor() {
        flush();
        graphics.disableScissor();
    }

    public int guiWidth() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    public int guiHeight() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }
}
