package com.xinian.KryptonHybrid.client.screen;

import com.xinian.KryptonHybrid.client.compat.KryptonGuiGraphics;
import com.xinian.KryptonHybrid.client.ui.Anim;
import com.xinian.KryptonHybrid.client.ui.Shapes;
import com.xinian.KryptonHybrid.client.ui.UITheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Shared look and widgets of the Krypton screens: translucent floating panel,
 * anti-aliased controls with eased hover/selection animation, and a tooltip.
 *
 * <p>Widgets are drawn immediate-mode: each frame starts with {@link #beginFrame},
 * controls register clickable {@link Hit} regions while drawing, and
 * {@link #mouseClicked} dispatches to the regions of the last frame.</p>
 */
public abstract class KryptonScreen extends Screen {

    protected record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    protected static final float CARD_RADIUS = 5F;
    protected static final float PANEL_RADIUS = 8F;

    protected final List<Hit> hits = new ArrayList<>();
    protected final Anim anim = new Anim();
    private final Map<String, String> translations = new HashMap<>();
    protected List<Component> tooltip;
    protected int mouseX;
    protected int mouseY;

    protected KryptonScreen(Component title) {
        super(title);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * {@code Screen.render} draws the background before the widgets, which here is after
     * the panels; {@link #beginFrame} draws it instead.
     */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    }

    /** Subclasses call this first; it also runs again after a language change. */
    @Override
    protected void init() {
        translations.clear();
    }

    /**
     * Call at the start of {@code render}. In a world the panels sit directly over it
     * (their opacity is a setting); without one (opened from the mod list) the menu
     * background is drawn first.
     */
    protected void beginFrame(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (this.minecraft == null || this.minecraft.level == null) {
            super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        }
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.hits.clear();
        this.tooltip = null;
        anim.frame();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // Later regions were drawn on top, so they win where regions overlap.
            List<Hit> snapshot = List.copyOf(hits);
            for (int i = snapshot.size() - 1; i >= 0; i--) {
                Hit hit = snapshot.get(i);
                if (hit.contains(mouseX, mouseY)) {
                    hit.action().run();
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    //  Surfaces
    // ------------------------------------------------------------------

    /** World dim plus the translucent floating panel. */
    protected void drawPanel(KryptonGuiGraphics g, int x, int y, int w, int h) {
        var c = UITheme.colors();
        g.fill(0, 0, this.width, this.height, 0x48000000);
        Shapes.roundRect(g, x, y, w, h, PANEL_RADIUS, UITheme.panelBackground());
        Shapes.roundRectStroke(g, x, y, w, h, PANEL_RADIUS, stroke(), UITheme.withAlpha(c.widgetBorder(), 0xD0));
    }

    protected static int cardBackground() {
        return UITheme.withAlpha(UITheme.colors().widgetBg(), 0xB0);
    }

    /** Border width that stays about one screen pixel at GUI scale 2 and never vanishes. */
    protected static float stroke() {
        return Math.max(Shapes.pixel(), 0.5F);
    }

    // ------------------------------------------------------------------
    //  Small widgets
    // ------------------------------------------------------------------

    /** Draws a tinted pill and returns the x just after it; skipped if it would pass {@code limit}. */
    protected int pill(KryptonGuiGraphics g, int x, int textY, int limit, String text, int color, List<Component> tip) {
        int w = this.font.width(text) + 8;
        if (x + w > limit) {
            return x;
        }
        Shapes.pill(g, x, textY - 2, w, 12, UITheme.withAlpha(color, 0x30));
        g.drawString(this.font, text, x + 4, textY, color, false);
        if (inside(x, textY - 2, w, 12)) {
            tooltip = tip;
        }
        return x + w + 4;
    }

    /** Round icon button; the close glyph tints red on hover, the others use the accent. */
    protected void iconButton(KryptonGuiGraphics g, int x, int y, int size, String glyph, boolean active,
                              List<Component> tip, Runnable action) {
        var c = UITheme.colors();
        boolean hover = inside(x, y, size, size);
        float hoverAnim = anim.hover("icon." + glyph, hover);
        float activeAnim = anim.approach("icon.active." + glyph, active ? 1F : 0F, 14F);
        int tint = "✕".equals(glyph) ? c.dangerColor() : c.accent();
        int alpha = (int) Math.max(0x50 * hoverAnim, 0x34 * activeAnim);
        if (alpha > 0) {
            Shapes.circle(g, x + size / 2F, y + size / 2F, size / 2F, UITheme.withAlpha(tint, alpha));
        }
        int gw = this.font.width(glyph);
        int glyphColor = UITheme.lerpColor(c.textSecondary(), c.textPrimary(), Math.max(hoverAnim, activeAnim));
        g.drawString(this.font, glyph, x + (size - gw + 1) / 2, y + (size - 8) / 2, glyphColor, false);
        hits.add(new Hit(x, y, size, size, action));
        if (hover) {
            tooltip = tip;
        }
    }

    /** Text button drawn as a pill; {@code primary} fills it with the accent. */
    protected int textButton(KryptonGuiGraphics g, int rightX, int y, String id, String label, boolean primary,
                             Runnable action) {
        var c = UITheme.colors();
        int w = this.font.width(label) + 16;
        int h = 13;
        int x = rightX - w;
        float hover = anim.hover("button." + id, inside(x, y, w, h));
        int bg = primary
                ? UITheme.lerpColor(c.accent(), c.accentHover(), hover)
                : UITheme.withAlpha(c.accent(), 0x30 + (int) (0x28 * hover));
        Shapes.pill(g, x, y, w, h, bg);
        g.drawString(this.font, label, x + 8, y + 3, primary ? 0xFFFFFFFF : c.accentLight(), false);
        hits.add(new Hit(x, y, w, h, action));
        return x;
    }

    /** 18x10 switch with a sliding knob. */
    protected void toggleSwitch(KryptonGuiGraphics g, float x, float y, String id, boolean on) {
        var c = UITheme.colors();
        float t = anim.approach("toggle." + id, on ? 1F : 0F, 14F);
        float tw = 18F;
        float th = 10F;
        int offTrack = UITheme.withAlpha(c.widgetBorderHover(), 0xA0);
        Shapes.pill(g, x, y, tw, th, UITheme.lerpColor(offTrack, c.successColor(), t));
        float knob = th - 3F;
        Shapes.circle(g, x + 1.5F + knob / 2 + t * (tw - th), y + th / 2, knob / 2, 0xFFFFFFFF);
    }

    /** Width of {@link #segmented} for these options. */
    protected int segmentedWidth(String[] options) {
        int total = 0;
        for (String option : options) {
            total += this.font.width(option) + 12;
        }
        return total;
    }

    /**
     * Segmented control ending at {@code right}; the selection pill slides to the
     * chosen option. Each option registers a hit spanning {@code hitY..hitY+hitH}.
     */
    protected void segmented(KryptonGuiGraphics g, float right, float y, String id, String[] options, int selected,
                             int hitY, int hitH, IntConsumer select) {
        var c = UITheme.colors();
        int pad = 6;
        int total = segmentedWidth(options);
        float th = 11F;
        float tx = right - total;
        Shapes.pill(g, tx - 1, y - 1, total + 2, th + 2, UITheme.withAlpha(c.widgetBorder(), 0xB0));

        float selX = tx;
        for (int i = 0; i < selected; i++) {
            selX += this.font.width(options[i]) + pad * 2;
        }
        float sx = anim.approach("segment.x." + id, selX, 18F);
        float sw = anim.approach("segment.w." + id, this.font.width(options[Math.max(0, selected)]) + pad * 2, 18F);
        if (selected >= 0) {
            Shapes.pill(g, sx, y, sw, th, c.accent());
        }

        float ox = tx;
        for (int i = 0; i < options.length; i++) {
            int w = this.font.width(options[i]) + pad * 2;
            boolean hover = inside((int) ox, (int) y, w, (int) th);
            int color = i == selected ? 0xFFFFFFFF
                    : UITheme.lerpColor(c.textSecondary(), c.textPrimary(), anim.hover("segment." + id + "." + i, hover));
            g.drawString(this.font, options[i], (int) ox + pad, (int) y + 2, color, false);
            final int index = i;
            hits.add(new Hit((int) ox, hitY, w, hitH, () -> select.accept(index)));
            ox += w;
        }
    }

    /** Value pill with a "›" that advances on click (the caller registers the hit); returns its left x. */
    protected float cyclePill(KryptonGuiGraphics g, float right, float y, String id, String value, boolean hover) {
        var c = UITheme.colors();
        float hoverAnim = anim.hover("cycle." + id, hover);
        String label = value + "  ›";
        float bw = this.font.width(label) + 12;
        float bx = right - bw;
        Shapes.pill(g, bx, y, bw, 11F, UITheme.withAlpha(c.accent(), 0x38 + (int) (0x30 * hoverAnim)));
        g.drawString(this.font, label, (int) (bx + 6), (int) (y + 2), c.accentLight(), false);
        return bx;
    }

    // ------------------------------------------------------------------
    //  Setting rows
    // ------------------------------------------------------------------

    /** Row with a fading hover highlight and a label; returns whether it is hovered. */
    protected boolean rowBase(KryptonGuiGraphics g, int x, int y, int w, int h, String id, String label,
                              Supplier<List<Component>> tip) {
        var c = UITheme.colors();
        boolean hover = inside(x, y, w, h);
        float hoverAnim = anim.hover("row." + id, hover);
        if (hoverAnim > 0.01F) {
            Shapes.roundRect(g, x, y, w, h - 1, 4, UITheme.withAlpha(c.accent(), (int) (0x24 * hoverAnim)));
        }
        if (hover && tip != null) {
            tooltip = tip.get();
        }
        g.drawString(this.font, label, x + 6, y + (h - 8) / 2, c.textPrimary(), false);
        return hover;
    }

    /** Row with a switch on the right; the whole row toggles. */
    protected int toggleRow(KryptonGuiGraphics g, int x, int y, int w, int h, String id, String label,
                            Supplier<List<Component>> tip, boolean on, Runnable toggle) {
        rowBase(g, x, y, w, h, id, label, tip);
        toggleSwitch(g, x + w - 23, y + (h - 11) / 2F, id, on);
        hits.add(new Hit(x, y, w, h, toggle));
        return y + h;
    }

    /** Row with a value pill that advances on click. */
    protected int cycleRow(KryptonGuiGraphics g, int x, int y, int w, int h, String id, String label,
                           Supplier<List<Component>> tip, String value, Runnable next) {
        boolean hover = rowBase(g, x, y, w, h, id, label, tip);
        cyclePill(g, x + w - 5, y + (h - 12) / 2F, id, value, hover);
        hits.add(new Hit(x, y, w, h, next));
        return y + h;
    }

    /** Row with a segmented control on the right. */
    protected int segmentedRow(KryptonGuiGraphics g, int x, int y, int w, int h, String id, String label,
                               Supplier<List<Component>> tip, String[] options, int selected, IntConsumer select) {
        rowBase(g, x, y, w, h, id, label, tip);
        segmented(g, x + w - 7, y + (h - 12) / 2F, id, options, selected, y, h, select);
        return y + h;
    }

    // ------------------------------------------------------------------
    //  Text and tooltip
    // ------------------------------------------------------------------

    protected void drawCentered(KryptonGuiGraphics g, String text, int centerX, int y, int color) {
        g.drawString(this.font, text, centerX - this.font.width(text) / 2, y, color, false);
    }

    protected void renderTooltip(KryptonGuiGraphics g, List<Component> lines, int x, int y) {
        int wrapW = 200;
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        int titleLines = 0;
        for (int i = 0; i < lines.size(); i++) {
            List<FormattedCharSequence> split = this.font.split(lines.get(i), wrapW);
            wrapped.addAll(split);
            if (i == 0) {
                titleLines = split.size();
            }
        }
        int maxW = 0;
        for (FormattedCharSequence line : wrapped) {
            maxW = Math.max(maxW, this.font.width(line));
        }
        int pad = 5;
        int w = maxW + pad * 2;
        int h = wrapped.size() * 10 + pad * 2 - 1;
        int tx = Math.max(2, Math.min(x, this.width - w - 4));
        int ty = Math.max(2, Math.min(y, this.height - h - 4));
        var c = UITheme.colors();
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        Shapes.roundRect(g, tx, ty, w, h, 5, UITheme.withAlpha(c.headerBg(), 0xF2));
        Shapes.roundRectStroke(g, tx, ty, w, h, 5, stroke(), UITheme.withAlpha(c.widgetBorderHover(), 0xA0));
        for (int i = 0; i < wrapped.size(); i++) {
            int color = i < titleLines ? c.accentLight() : c.textSecondary();
            g.drawString(this.font, wrapped.get(i), tx + pad, ty + pad + i * 10, color);
        }
        g.pose().popPose();
    }

    protected boolean inside(int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** Static labels are looked up once per {@link #init}. */
    protected String translate(String key) {
        String cached = translations.get(key);
        if (cached == null) {
            cached = Component.translatable(key).getString();
            translations.put(key, cached);
        }
        return cached;
    }

    protected static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
