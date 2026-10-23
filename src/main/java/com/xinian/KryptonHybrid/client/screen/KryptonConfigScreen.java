package com.xinian.KryptonHybrid.client.screen;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.minecraft.client.gui.GuiGraphics;
import com.xinian.KryptonHybrid.KryptonForgeConfig;
import com.xinian.KryptonHybrid.client.KryptonClientConfig;
import com.xinian.KryptonHybrid.client.RemoteServerConfig;
import com.xinian.KryptonHybrid.client.compat.KryptonGuiGraphics;
import com.xinian.KryptonHybrid.client.ui.Fmt;
import com.xinian.KryptonHybrid.client.ui.Shapes;
import com.xinian.KryptonHybrid.client.ui.UITheme;
import com.xinian.KryptonHybrid.shared.network.compression.ZstdUtil;
import com.xinian.KryptonHybrid.shared.network.config.ConfigValueCodec;
import com.xinian.KryptonHybrid.shared.network.security.MotdCache;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.common.ForgeConfigSpec;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Visual editor for Krypton's config files, in the dashboard's style.
 *
 * <p>The editor is generated from the {@link ForgeConfigSpec}s themselves, so every
 * option shows up with the right control: switches for booleans, segmented controls
 * or cycling pills for enums, sliders for small integer ranges, and validated input
 * fields for large numbers and text (secrets are masked). Labels and descriptions
 * come from {@code config.krypton_hybrid.<path>} lang keys when present, otherwise
 * from the key name and the spec comment.</p>
 *
 * <p>Edits apply to the live config immediately (and are re-baked into the runtime
 * mirrors); the files are written when the screen closes.</p>
 *
 * <p>On a remote server, operators also get a "Server" tab that edits that server's
 * network config through {@link RemoteServerConfig}; the server checks the
 * permission, applies, saves and logs every change.</p>
 */
public final class KryptonConfigScreen extends KryptonScreen {

    private enum Source {
        CLIENT(KryptonClientConfig.SPEC),
        COMMON(KryptonForgeConfig.SPEC),
        /** The connected server's network config, edited remotely by an operator. */
        SERVER(KryptonForgeConfig.SPEC);

        final ForgeConfigSpec spec;

        Source(ForgeConfigSpec spec) {
            this.spec = spec;
        }
    }

    private enum Kind { BOOL, ENUM, SLIDER, NUMBER, TEXT }

    /** One option of the current source. */
    private static final class Entry {
        final String path;
        final ForgeConfigSpec.ValueSpec spec;
        final ForgeConfigSpec.ConfigValue<Object> value;
        /** Values come from {@link RemoteServerConfig} instead of the local config. */
        final boolean remote;
        final Kind kind;
        final Object[] options;
        final long min;
        final long max;
        final String label;
        final List<Component> description;
        final boolean secret;
        EditBox box;
        boolean invalid;
        float sliderX;
        float sliderW;

        Entry(String path, ForgeConfigSpec.ValueSpec spec, ForgeConfigSpec.ConfigValue<Object> value, boolean remote,
              Kind kind, Object[] options, long min, long max, String label, List<Component> description) {
            this.path = path;
            this.spec = spec;
            this.value = value;
            this.remote = remote;
            this.kind = kind;
            this.options = options;
            this.min = min;
            this.max = max;
            this.label = label;
            this.description = description;
            this.secret = ConfigValueCodec.isSecret(path);
        }

        Object get() {
            if (!remote) {
                return value.get();
            }
            Object v = RemoteServerConfig.value(path);
            return v != null ? v : spec.getDefault();
        }

        boolean modified() {
            if (remote) {
                if (!RemoteServerConfig.loaded()) return false;
                if (secret) return RemoteServerConfig.secretIsSet(path);
            }
            return !Objects.equals(get(), spec.getDefault());
        }
    }

    /** A row of the right-hand list: either a sub-section header or an option. */
    private record Row(String header, int depth, Entry entry) {}

    /** A top-level section, shown in the sidebar. */
    private record Group(String label, List<Row> rows) {}

    private static final int HEADER_H = 22;
    private static final int FOOTER_H = 18;
    private static final int ROW_H = 18;
    private static final int SIDEBAR_W = 108;
    private static final int SLIDER_MAX_SPAN = 200;

    private static Source lastSource = Source.CLIENT;
    private static int lastGroup;

    private final Screen parent;
    private final Set<Source> dirty = EnumSet.noneOf(Source.class);
    private Source source = lastSource;
    private int groupIndex = lastGroup;
    private final List<Group> groups = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private String searchText = "";
    private EditBox search;
    private float targetScroll;
    private float sideTargetScroll;
    private float listContentH;
    private Entry dragging;
    private Object dragStart;
    /** Remote field being typed into; sent when it loses focus. */
    private Entry editing;
    private long seenRemoteVersion;

    private int px, py, pw, ph;
    private int sideX, sideY, sideW, sideH;
    private int listX, listY, listW, listH;

    public KryptonConfigScreen(Screen parent) {
        super(Component.translatable("gui.krypton_hybrid.config.title"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------
    //  Model
    // ------------------------------------------------------------------

    private void buildModel() {
        groups.clear();
        entries.clear();
        UnmodifiableConfig specRoot = source.spec.getSpec();
        UnmodifiableConfig valueRoot = source.spec.getValues();
        for (Map.Entry<String, Object> top : specRoot.valueMap().entrySet()) {
            List<Row> rows = new ArrayList<>();
            String path = top.getKey();
            if (top.getValue() instanceof UnmodifiableConfig section) {
                collect(section, (UnmodifiableConfig) valueRoot.valueMap().get(top.getKey()), path, 0, rows);
                groups.add(new Group(label(path), rows));
            } else if (top.getValue() instanceof ForgeConfigSpec.ValueSpec spec) {
                Entry entry = entry(path, spec, valueRoot.valueMap().get(top.getKey()));
                rows.add(new Row(null, 0, entry));
                groups.add(new Group(entry.label, rows));
            }
        }
        groupIndex = clamp(groupIndex, 0, Math.max(0, groups.size() - 1));
    }

    private void collect(UnmodifiableConfig specs, UnmodifiableConfig values, String prefix, int depth, List<Row> rows) {
        List<Map.Entry<String, Object>> sections = new ArrayList<>();
        for (Map.Entry<String, Object> e : specs.valueMap().entrySet()) {
            if (e.getValue() instanceof ForgeConfigSpec.ValueSpec spec) {
                rows.add(new Row(null, depth, entry(prefix + "." + e.getKey(), spec, values.valueMap().get(e.getKey()))));
            } else if (e.getValue() instanceof UnmodifiableConfig) {
                sections.add(e);
            }
        }
        // Direct options first, then each sub-section under its own header.
        for (Map.Entry<String, Object> e : sections) {
            String path = prefix + "." + e.getKey();
            rows.add(new Row(label(path), depth + 1, null));
            collect((UnmodifiableConfig) e.getValue(), (UnmodifiableConfig) values.valueMap().get(e.getKey()),
                    path, depth + 1, rows);
        }
    }

    @SuppressWarnings("unchecked")
    private Entry entry(String path, ForgeConfigSpec.ValueSpec spec, Object configValue) {
        ForgeConfigSpec.ConfigValue<Object> value = (ForgeConfigSpec.ConfigValue<Object>) configValue;
        Object def = spec.getDefault();
        Kind kind;
        Object[] options = null;
        long min = 0;
        long max = 0;
        Bounds bounds = bounds(spec);
        if (def instanceof Boolean) {
            kind = Kind.BOOL;
        } else if (def instanceof Enum<?> e) {
            kind = Kind.ENUM;
            options = e.getDeclaringClass().getEnumConstants();
        } else if ((def instanceof Integer || def instanceof Long) && bounds != null) {
            min = (long) bounds.min();
            max = (long) bounds.max();
            kind = max - min <= SLIDER_MAX_SPAN ? Kind.SLIDER : Kind.NUMBER;
        } else if (def instanceof Number) {
            kind = Kind.NUMBER;
        } else {
            kind = Kind.TEXT;
        }
        Entry entry = new Entry(path, spec, value, source == Source.SERVER, kind, options, min, max,
                label(path), description(path, spec));
        entries.add(entry);
        return entry;
    }

    private record Bounds(double min, double max) {}

    /** Forge's own range line, appended last to the comment: "Range: a ~ b", "Range: > a" or "Range: < b". */
    private static final Pattern RANGE_LINE = Pattern.compile("Range: (?:([<>]) (\\S+)|(\\S+) ~ (\\S+))");

    /**
     * Range of a numeric option. {@code ForgeConfigSpec.Range} is private in this
     * Forge version, so the bounds are read back from the comment line Forge
     * generates for every ranged value.
     */
    private static Bounds bounds(ForgeConfigSpec.ValueSpec spec) {
        String comment = spec.getComment();
        if (comment == null) {
            return null;
        }
        Bounds found = null;
        for (String line : comment.split("\n")) {
            Matcher m = RANGE_LINE.matcher(line.strip());
            if (!m.matches()) {
                continue;
            }
            try {
                if (m.group(1) != null) {
                    double v = Double.parseDouble(m.group(2));
                    found = m.group(1).equals(">")
                            ? new Bounds(v, Integer.MAX_VALUE)
                            : new Bounds(Integer.MIN_VALUE, v);
                } else {
                    found = new Bounds(Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4)));
                }
            } catch (NumberFormatException ignored) {
                // Not Forge's line (an author-written "Range:" note); keep looking.
            }
        }
        return found;
    }

    private static String boundText(double v) {
        if (v >= Integer.MAX_VALUE) return "∞";
        if (v <= Integer.MIN_VALUE) return "-∞";
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private static String label(String path) {
        String key = "config.krypton_hybrid." + path;
        if (I18n.exists(key)) {
            return I18n.get(key);
        }
        String last = path.substring(path.lastIndexOf('.') + 1).replace('_', ' ');
        return last.isEmpty() ? path : Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }

    /** Lang description if present, else the spec comment without its "Default:"/"Range:" lines. */
    private static List<Component> description(String path, ForgeConfigSpec.ValueSpec spec) {
        List<Component> lines = new ArrayList<>();
        String key = "config.krypton_hybrid." + path + ".desc";
        if (I18n.exists(key)) {
            lines.add(Component.translatable(key));
            return lines;
        }
        String comment = spec.getComment();
        if (comment != null) {
            for (String line : comment.split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("Default:") || trimmed.startsWith("Range:")
                        || trimmed.startsWith("Allowed Values:")) {
                    continue;
                }
                lines.add(Component.literal(line.stripTrailing()));
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        pw = clamp(this.width - 32, 320, 600);
        ph = clamp(this.height - 28, 200, 360);
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;
        sideX = px + 8;
        sideY = py + HEADER_H + 4;
        sideW = Math.min(SIDEBAR_W, pw / 4);
        sideH = ph - HEADER_H - FOOTER_H - 10;
        listX = sideX + sideW + 8;
        listW = px + pw - 8 - listX;
        listY = sideY + 20;
        listH = sideY + sideH - listY;

        if (source == Source.SERVER && !RemoteServerConfig.available()) {
            source = Source.CLIENT;
        }
        if (source == Source.SERVER && !RemoteServerConfig.waiting()) {
            RemoteServerConfig.request();
        }
        seenRemoteVersion = RemoteServerConfig.version();
        buildModel();

        search = new EditBox(this.font, listX + 6, sideY + 3, listW - 12, 10,
                Component.translatable("gui.krypton_hybrid.config.search"));
        search.setBordered(false);
        search.setMaxLength(64);
        search.setValue(searchText);
        search.setResponder(text -> {
            searchText = text;
            targetScroll = 0;
        });
        addRenderableWidget(search);

        for (Entry entry : entries) {
            if (entry.kind == Kind.NUMBER || entry.kind == Kind.TEXT) {
                EditBox box = new EditBox(this.font, 0, 0, 80, 10, Component.literal(entry.label));
                box.setBordered(false);
                box.setMaxLength(256);
                box.setValue(boxText(entry));
                box.moveCursorToStart();
                if (entry.secret) {
                    box.setFormatter((text, offset) -> FormattedCharSequence.forward(
                            box.isFocused() ? text : "•".repeat(text.length()), Style.EMPTY));
                }
                box.setResponder(text -> onEdited(entry, text));
                box.visible = false;
                entry.box = box;
                addRenderableWidget(box);
            }
        }
    }

    @Override
    public void tick() {
        if (source == Source.SERVER && !RemoteServerConfig.available()) {
            // Disconnected or de-opped while the tab was open.
            editing = null;
            switchSource(Source.CLIENT);
        }
        search.tick();
        Entry focused = null;
        for (Entry entry : entries) {
            if (entry.box != null && entry.box.isFocused()) {
                entry.box.tick();
                focused = entry;
            }
        }
        // Remote fields are sent once editing ends (Enter or focus loss), not per keystroke.
        if (editing != null && editing != focused) {
            commitRemoteText(editing);
        }
        editing = focused != null && focused.remote ? focused : null;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        if (editing != null) {
            commitRemoteText(editing);
            editing = null;
        }
        lastSource = source;
        lastGroup = groupIndex;
        for (Source s : dirty) {
            s.spec.save();
        }
        if (dirty.contains(Source.COMMON)) {
            ZstdUtil.reloadDictionary();
        }
        dirty.clear();
    }

    private void switchSource(Source next) {
        if (next == source) {
            return;
        }
        if (editing != null) {
            commitRemoteText(editing);
            editing = null;
        }
        source = next;
        groupIndex = 0;
        targetScroll = 0;
        searchText = "";
        if (next == Source.SERVER) {
            RemoteServerConfig.request();
        }
        rebuildWidgets();
    }

    // ------------------------------------------------------------------
    //  Editing
    // ------------------------------------------------------------------

    private void set(Entry entry, Object value) {
        if (!entry.spec.test(value) || Objects.equals(entry.get(), value)) {
            return;
        }
        if (entry.remote) {
            // Applied, saved and logged by the server; its answer replaces the shown value.
            RemoteServerConfig.submit(entry.path, value);
            return;
        }
        entry.value.set(value);
        dirty.add(source);
        // Re-bake right away so the change is live (the file is written on close).
        if (source == Source.CLIENT) {
            KryptonClientConfig.apply();
        } else {
            KryptonForgeConfig.INSTANCE.bake();
            MotdCache.invalidate();
        }
    }

    private void onEdited(Entry entry, String text) {
        Object parsed = parse(entry, text.strip());
        entry.invalid = parsed == null || !entry.spec.test(parsed);
        if (!entry.invalid && !entry.remote) {
            set(entry, parsed);
        }
    }

    /** Sends a remote field's text once editing ends; an untouched secret field stays as it is. */
    private void commitRemoteText(Entry entry) {
        String text = entry.box.getValue();
        if (entry.secret && text.isEmpty()) {
            return;
        }
        Object parsed = parse(entry, text.strip());
        if (parsed != null && entry.spec.test(parsed)) {
            set(entry, parsed);
            if (entry.secret) {
                entry.box.setValue("");
            }
        }
    }

    /** Text shown in an input field: the value, except remote secrets, which are never sent. */
    private static String boxText(Entry entry) {
        if (entry.remote && entry.secret) {
            return "";
        }
        return String.valueOf(entry.get());
    }

    private static Object parse(Entry entry, String text) {
        Object def = entry.spec.getDefault();
        try {
            if (def instanceof Integer) return Integer.parseInt(text);
            if (def instanceof Long) return Long.parseLong(text);
            if (def instanceof Double) return Double.parseDouble(text);
            if (def instanceof Float) return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return null;
        }
        return text;
    }

    private void reset(Entry entry) {
        Object def = entry.spec.getDefault();
        if (entry.remote && entry.secret) {
            // The current secret is unknown here, so always send the reset.
            RemoteServerConfig.submit(entry.path, def);
        } else {
            set(entry, def);
        }
        entry.invalid = false;
        if (entry.box != null) {
            entry.box.setValue(entry.remote && entry.secret ? "" : String.valueOf(def));
            entry.box.moveCursorToStart();
        }
    }

    private void resetPage() {
        Map<String, Object> remote = new LinkedHashMap<>();
        for (Row row : visibleRows()) {
            Entry e = row.entry();
            if (e == null) {
                continue;
            }
            if (e.remote) {
                if (e.modified()) {
                    remote.put(e.path, e.spec.getDefault());
                }
                if (e.box != null) {
                    e.box.setValue(e.secret ? "" : String.valueOf(e.spec.getDefault()));
                }
                e.invalid = false;
            } else {
                reset(e);
            }
        }
        // One message for the whole page instead of one per option.
        RemoteServerConfig.submit(remote);
    }

    private void setSlider(Entry entry, double mouseX) {
        double t = (mouseX - entry.sliderX) / Math.max(1, entry.sliderW);
        long v = entry.min + Math.round(Math.max(0, Math.min(1, t)) * (entry.max - entry.min));
        Object value = entry.spec.getDefault() instanceof Long ? (Object) v : (Object) (int) v;
        if (entry.remote) {
            // Shown while dragging; sent once on release.
            RemoteServerConfig.preview(entry.path, value);
        } else {
            set(entry, value);
        }
    }

    // ------------------------------------------------------------------
    //  Render
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        beginFrame(mouseX, mouseY);
        KryptonGuiGraphics g = new KryptonGuiGraphics(guiGraphics);
        g.beginBatch();
        var c = UITheme.colors();
        drawPanel(g, px, py, pw, ph);
        if (source == Source.SERVER && seenRemoteVersion != RemoteServerConfig.version()) {
            seenRemoteVersion = RemoteServerConfig.version();
            syncRemoteFields();
        }

        renderHeader(g);
        renderSidebar(g);
        renderSearch(g);
        for (Entry entry : entries) {
            if (entry.box != null) {
                entry.box.visible = false;
            }
        }
        renderList(g);
        renderFooter(g);

        g.flush();
        search.setTextColor(c.textPrimary());
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (tooltip != null && !tooltip.isEmpty()) {
            renderTooltip(g, tooltip, mouseX + 10, mouseY + 10);
        }
        g.endBatch();
    }

    private void renderHeader(KryptonGuiGraphics g) {
        var c = UITheme.colors();
        Component title = this.title.copy().withStyle(ChatFormatting.BOLD);
        g.drawString(this.font, title, px + 10, py + 8, c.textPrimary(), false);

        int right = px + pw - 6;
        iconButton(g, right - 14, py + 5, 14, "✕", false,
                List.of(Component.translatable("gui.krypton_hybrid.config.done")), this::onClose);

        // The server tab only exists for operators on a remote server.
        List<Source> shown = new ArrayList<>(List.of(Source.CLIENT, Source.COMMON));
        if (RemoteServerConfig.available()) {
            shown.add(Source.SERVER);
        }
        String[] sources = new String[shown.size()];
        for (int i = 0; i < sources.length; i++) {
            sources[i] = translate(sourceKey(shown.get(i)));
        }
        int segRight = right - 22;
        int segLeft = segRight - segmentedWidth(sources);
        segmented(g, segRight, py + 6, "source", sources, shown.indexOf(source), py + 4, 15,
                i -> switchSource(shown.get(i)));
        if (inside(segLeft, py + 4, segRight - segLeft, 15)) {
            int x = segLeft;
            for (int i = 0; i < sources.length; i++) {
                x += this.font.width(sources[i]) + 12;
                if (mouseX < x || i == sources.length - 1) {
                    String key = sourceKey(shown.get(i));
                    tooltip = List.of(Component.translatable(key), Component.translatable(key + ".desc"));
                    break;
                }
            }
        }
        Shapes.hairline(g, px + 8, px + pw - 8, py + HEADER_H, UITheme.withAlpha(c.divider(), 0xA0));
    }

    private static String sourceKey(Source s) {
        return "gui.krypton_hybrid.config.source." + s.name().toLowerCase(Locale.ROOT);
    }

    /** Shows the server's latest answer in every field that is not being typed into. */
    private void syncRemoteFields() {
        for (Entry entry : entries) {
            if (entry.remote && entry.box != null && !entry.box.isFocused()) {
                entry.box.setValue(boxText(entry));
                entry.box.moveCursorToStart();
                entry.invalid = false;
            }
        }
    }

    private void renderSidebar(KryptonGuiGraphics g) {
        var c = UITheme.colors();
        Shapes.roundRect(g, sideX, sideY, sideW, sideH, CARD_RADIUS, cardBackground());
        boolean searching = !searchText.isBlank();
        int rowH = 15;
        float maxScroll = Math.max(0, groups.size() * rowH + 8 - sideH);
        sideTargetScroll = Math.max(0, Math.min(maxScroll, sideTargetScroll));
        float scroll = anim.approach("side.scroll", sideTargetScroll, 20F);
        int firstHit = hits.size();
        g.enableScissor(sideX, sideY + 2, sideX + sideW, sideY + sideH - 2);
        int y = (int) (sideY + 4 - scroll);
        float selectedY = -1;
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            boolean selected = i == groupIndex && !searching;
            boolean hover = inside(sideX + 3, y, sideW - 6, rowH);
            float hoverAnim = anim.hover("side." + i, hover);
            if (!selected && hoverAnim > 0.01F) {
                Shapes.roundRect(g, sideX + 3, y, sideW - 6, rowH - 1, 4, UITheme.withAlpha(c.accent(), (int) (0x26 * hoverAnim)));
            }
            if (selected) {
                selectedY = y;
            }
            boolean modified = group.rows().stream().anyMatch(r -> r.entry() != null && r.entry().modified());
            int textColor = selected ? c.textPrimary() : UITheme.lerpColor(c.textSecondary(), c.textPrimary(), hoverAnim);
            int textW = sideW - 18 - (modified ? 6 : 0);
            g.drawString(this.font, Fmt.truncate(this.font, group.label(), textW), sideX + 10, y + 3, textColor, false);
            if (modified) {
                Shapes.circle(g, sideX + sideW - 9, y + rowH / 2F - 0.5F, 2F, c.accent());
            }
            final int index = i;
            hits.add(new Hit(sideX + 3, y, sideW - 6, rowH, () -> {
                groupIndex = index;
                targetScroll = 0;
                if (!searchText.isEmpty()) {
                    search.setValue("");
                }
            }));
            y += rowH;
        }
        if (selectedY >= 0) {
            float ay = anim.approach("side.selected", selectedY, 18F);
            Shapes.roundRect(g, sideX + 3, ay, sideW - 6, rowH - 1, 4, UITheme.withAlpha(c.accent(), 0x3C));
            Shapes.pill(g, sideX + 4, ay + 3, 2.5F, rowH - 7, c.accent());
        }
        g.disableScissor();
        hits.subList(firstHit, hits.size()).removeIf(h -> h.y() + h.h() <= sideY || h.y() >= sideY + sideH);
    }

    private void renderSearch(KryptonGuiGraphics g) {
        var c = UITheme.colors();
        int h = 15;
        float focus = anim.approach("search.focus", search.isFocused() ? 1F : 0F, 14F);
        Shapes.roundRect(g, listX, sideY, listW, h, h / 2F, cardBackground());
        Shapes.roundRectStroke(g, listX, sideY, listW, h, h / 2F, stroke(),
                UITheme.lerpColor(UITheme.withAlpha(c.widgetBorder(), 0xC0), c.accent(), focus));
        if (searchText.isEmpty() && !search.isFocused()) {
            g.drawString(this.font, translate("gui.krypton_hybrid.config.search"), listX + 6, sideY + 4, c.textMuted(), false);
        }
    }

    private List<Row> visibleRows() {
        if (searchText.isBlank()) {
            return groups.isEmpty() ? List.of() : groups.get(groupIndex).rows();
        }
        String needle = searchText.strip().toLowerCase(Locale.ROOT);
        List<Row> rows = new ArrayList<>();
        for (Group group : groups) {
            boolean headerAdded = false;
            for (Row row : group.rows()) {
                Entry e = row.entry();
                if (e == null) {
                    continue;
                }
                boolean match = e.label.toLowerCase(Locale.ROOT).contains(needle)
                        || e.path.toLowerCase(Locale.ROOT).contains(needle);
                if (match) {
                    if (!headerAdded) {
                        rows.add(new Row(group.label(), 1, null));
                        headerAdded = true;
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private void renderList(KryptonGuiGraphics g) {
        var c = UITheme.colors();
        List<Row> rows = visibleRows();
        listContentH = 0;
        for (Row row : rows) {
            listContentH += row.entry() == null ? 16 : ROW_H;
        }
        float maxScroll = Math.max(0, listContentH - listH);
        targetScroll = Math.max(0, Math.min(maxScroll, targetScroll));
        float scroll = anim.approach("list.scroll", targetScroll, 20F);

        if (source == Source.SERVER && !RemoteServerConfig.loaded()) {
            String error = RemoteServerConfig.error();
            String key = !RemoteServerConfig.waiting() && !error.isEmpty()
                    ? "gui.krypton_hybrid.config.server.error." + error
                    : "gui.krypton_hybrid.config.server.loading";
            drawCentered(g, translate(key), listX + listW / 2, listY + 20,
                    key.endsWith("loading") ? c.textMuted() : c.dangerColor());
            return;
        }
        if (rows.isEmpty()) {
            drawCentered(g, translate("gui.krypton_hybrid.config.no_match"), listX + listW / 2, listY + 20, c.textMuted());
            return;
        }

        int firstHit = hits.size();
        List<Component> tooltipBefore = tooltip;
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        float y = listY - scroll;
        int rowW = listW - (maxScroll > 0 ? 6 : 0);
        for (Row row : rows) {
            if (row.entry() == null) {
                if (y + 16 > listY && y < listY + listH) {
                    g.drawString(this.font, row.header(), listX + 2, (int) y + 5, c.accentLight(), false);
                    Shapes.hairline(g, listX + 4 + this.font.width(row.header()) + 6, listX + rowW - 2, y + 9,
                            UITheme.withAlpha(c.divider(), 0x80));
                }
                y += 16;
                continue;
            }
            if (y + ROW_H > listY && y < listY + listH) {
                renderEntry(g, row.entry(), listX, (int) y, rowW, y >= listY && y + ROW_H <= listY + listH);
            }
            y += ROW_H;
        }
        g.disableScissor();
        // Rows scrolled out of view are clipped visually; keep them from reacting too.
        hits.subList(firstHit, hits.size()).removeIf(h -> h.y() + h.h() <= listY || h.y() >= listY + listH);
        if (!inside(listX, listY, listW, listH)) {
            tooltip = tooltipBefore;
        }

        if (maxScroll > 0) {
            int trackX = listX + listW - 3;
            Shapes.pill(g, trackX, listY, 3, listH, c.scrollbarTrack());
            float thumbH = Math.max(14, listH * listH / listContentH);
            float thumbY = listY + (listH - thumbH) * (scroll / maxScroll);
            Shapes.pill(g, trackX, thumbY, 3, thumbH, c.scrollbarThumb());
        }
    }

    private void renderEntry(KryptonGuiGraphics g, Entry e, int x, int y, int w, boolean fullyVisible) {
        var c = UITheme.colors();
        int right = x + w - 4;
        int midY = y + ROW_H / 2;
        boolean hover = rowBase(g, x, y, w, ROW_H, e.path, "", () -> entryTooltip(e));

        // Control on the right; remember where it starts so the label can be truncated.
        float controlLeft;
        switch (e.kind) {
            case BOOL -> {
                boolean on = Boolean.TRUE.equals(e.get());
                toggleSwitch(g, right - 18, midY - 5, e.path, on);
                hits.add(new Hit(x, y, w, ROW_H, () -> set(e, !on)));
                controlLeft = right - 18;
            }
            case ENUM -> {
                String[] names = new String[e.options.length];
                int selected = 0;
                for (int i = 0; i < e.options.length; i++) {
                    names[i] = enumLabel(e, e.options[i]);
                    if (e.options[i] == e.get()) {
                        selected = i;
                    }
                }
                if (segmentedWidth(names) <= Math.min(180, w * 0.55F)) {
                    segmented(g, right - 1, midY - 6, e.path, names, selected, y, ROW_H,
                            i -> set(e, e.options[i]));
                    controlLeft = right - 1 - segmentedWidth(names);
                } else {
                    int next = (selected + 1) % e.options.length;
                    controlLeft = cyclePill(g, right, midY - 6, e.path, names[selected], hover);
                    hits.add(new Hit(x, y, w, ROW_H, () -> set(e, e.options[next])));
                }
            }
            case SLIDER -> controlLeft = renderSlider(g, e, right, midY, y);
            default -> controlLeft = renderField(g, e, right, y, fullyVisible);
        }

        if (e.modified()) {
            float rx = controlLeft - 14;
            boolean overReset = inside((int) rx - 1, y + 3, 12, 12);
            float resetHover = anim.hover("reset." + e.path, overReset);
            Shapes.circle(g, rx + 5, midY, 5.5F, UITheme.withAlpha(c.accent(), 0x30 + (int) (0x40 * resetHover)));
            g.drawString(this.font, "↺", (int) rx + 2, midY - 4, c.accentLight(), false);
            hits.add(new Hit((int) rx - 1, y + 3, 12, 12, () -> reset(e)));
            if (overReset) {
                tooltip = List.of(Component.translatable("gui.krypton_hybrid.config.reset"),
                        Component.translatable("gui.krypton_hybrid.config.default", displayValue(e, e.spec.getDefault())));
            }
            controlLeft = rx - 4;
        }

        int labelX = x + 8;
        if (e.modified()) {
            Shapes.circle(g, x + 4, midY, 1.6F, c.accent());
        }
        int labelW = (int) controlLeft - labelX - 6;
        int labelColor = e.invalid ? c.dangerColor() : c.textPrimary();
        g.drawString(this.font, Fmt.truncate(this.font, e.label, labelW), labelX, midY - 4, labelColor, false);
    }

    private float renderSlider(KryptonGuiGraphics g, Entry e, int right, int midY, int rowY) {
        var c = UITheme.colors();
        long v = ((Number) e.get()).longValue();
        String text = Long.toString(v);
        int textW = Math.max(this.font.width(Long.toString(e.max)), this.font.width(Long.toString(e.min)));
        g.drawString(this.font, text, right - this.font.width(text), midY - 4, c.textPrimary(), false);
        float trackW = 90F;
        float trackX = right - textW - 8 - trackW;
        e.sliderX = trackX;
        e.sliderW = trackW;
        float t = (float) (v - e.min) / Math.max(1, e.max - e.min);
        float shown = anim.approach("slider." + e.path, t, 22F);
        boolean hover = inside((int) trackX - 4, rowY, (int) trackW + 8, ROW_H) || dragging == e;
        float hoverAnim = anim.hover("slider.hover." + e.path, hover);
        Shapes.pill(g, trackX, midY - 1.5F, trackW, 3F, UITheme.withAlpha(c.widgetBorder(), 0xA0));
        Shapes.pill(g, trackX, midY - 1.5F, Math.max(3F, trackW * shown), 3F, c.accent());
        float knobR = 4F + hoverAnim;
        float kx = trackX + trackW * shown;
        Shapes.circle(g, kx, midY, knobR + 0.8F, c.accent());
        Shapes.circle(g, kx, midY, knobR - 0.6F, 0xFFFFFFFF);
        hits.add(new Hit((int) trackX - 4, rowY, (int) trackW + 8, ROW_H, () -> {
            dragging = e;
            dragStart = e.get();
            setSlider(e, mouseX);
        }));
        return trackX - 6;
    }

    private float renderField(KryptonGuiGraphics g, Entry e, int right, int rowY, boolean fullyVisible) {
        var c = UITheme.colors();
        int w = e.kind == Kind.NUMBER ? 84 : Math.min(150, listW / 2);
        int h = 13;
        int x = right - w;
        int y = rowY + (ROW_H - h) / 2;
        float focus = anim.approach("field." + e.path, e.box.isFocused() ? 1F : 0F, 14F);
        int border = e.invalid ? c.dangerColor()
                : UITheme.lerpColor(UITheme.withAlpha(c.widgetBorder(), 0xC0), c.accent(), focus);
        Shapes.roundRect(g, x, y, w, h, 4, UITheme.withAlpha(c.inputBg(), 0xC0));
        Shapes.roundRectStroke(g, x, y, w, h, 4, stroke(), border);
        // Edit boxes cannot be clipped, so they only show on fully visible rows.
        e.box.visible = fullyVisible;
        e.box.setX(x + 4);
        e.box.setY(y + 3);
        e.box.setWidth(w - 8);
        e.box.setTextColor(e.invalid ? c.dangerColor() : c.textPrimary());
        if (!fullyVisible && e.box.isFocused()) {
            e.box.setFocused(false);
        }
        // Remote secrets are never sent back, so only say whether one is set.
        if (fullyVisible && e.remote && e.secret && !e.box.isFocused() && e.box.getValue().isEmpty()
                && RemoteServerConfig.secretIsSet(e.path)) {
            g.drawString(this.font, Fmt.truncate(this.font, translate("gui.krypton_hybrid.config.secret_set"), w - 8),
                    x + 4, y + 3, c.textMuted(), false);
        }
        return x;
    }

    private void renderFooter(KryptonGuiGraphics g) {
        var c = UITheme.colors();
        int y = py + ph - FOOTER_H + 2;
        int right = textButton(g, px + pw - 8, y, "done", translate("gui.krypton_hybrid.config.done"), true, this::onClose);
        boolean serverReady = source != Source.SERVER || RemoteServerConfig.loaded();
        if (serverReady) {
            right = textButton(g, right - 6, y, "reset_page", translate("gui.krypton_hybrid.config.reset_page"), false,
                    this::resetPage);
        }
        String note = translate("gui.krypton_hybrid.config.note." + source.name().toLowerCase(Locale.ROOT));
        int noteColor = c.textMuted();
        if (source == Source.SERVER && serverReady && !RemoteServerConfig.error().isEmpty()) {
            // A rejected change; the list already shows the server's actual values again.
            note = translate("gui.krypton_hybrid.config.server.error." + RemoteServerConfig.error());
            noteColor = c.dangerColor();
        }
        g.drawString(this.font, Fmt.truncate(this.font, note, right - px - 20), px + 10, y + 3, noteColor, false);
    }

    private List<Component> entryTooltip(Entry e) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(e.label));
        lines.addAll(e.description);
        lines.add(Component.translatable("gui.krypton_hybrid.config.default", displayValue(e, e.spec.getDefault()))
                .withStyle(ChatFormatting.GRAY));
        Bounds bounds = bounds(e.spec);
        if (bounds != null) {
            lines.add(Component.translatable("gui.krypton_hybrid.config.range",
                    boundText(bounds.min()), boundText(bounds.max())).withStyle(ChatFormatting.GRAY));
        }
        if (e.spec.needsWorldRestart()) {
            lines.add(Component.translatable("gui.krypton_hybrid.config.restart").withStyle(ChatFormatting.GOLD));
        }
        if (e.invalid) {
            lines.add(Component.translatable("gui.krypton_hybrid.config.invalid").withStyle(ChatFormatting.RED));
        }
        lines.add(Component.translatable("gui.krypton_hybrid.config.path", e.path).withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private String displayValue(Entry e, Object value) {
        if (e.secret) {
            return value == null || String.valueOf(value).isEmpty() ? "—" : "••••";
        }
        if (value instanceof Enum<?>) {
            return enumLabel(e, value);
        }
        if (value instanceof Boolean b) {
            return translate(b ? "gui.krypton_hybrid.config.on" : "gui.krypton_hybrid.config.off");
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? "—" : text;
    }

    private static String enumLabel(Entry e, Object option) {
        String name = ((Enum<?>) option).name();
        String key = "config.krypton_hybrid." + e.path + "." + name.toLowerCase(Locale.ROOT);
        return I18n.exists(key) ? I18n.get(key) : name;
    }

    // ------------------------------------------------------------------
    //  Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging != null && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            setSlider(dragging, mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging != null && dragging.remote) {
            // The previewed value has to be sent explicitly; set() would see no change.
            Object value = RemoteServerConfig.value(dragging.path);
            if (value != null && dragging.spec.test(value) && !Objects.equals(value, dragStart)) {
                RemoteServerConfig.submit(dragging.path, value);
            }
        }
        dragging = null;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH) {
            targetScroll -= (float) delta * ROW_H * 2;
            return true;
        }
        if (mouseX >= sideX && mouseX < sideX + sideW && mouseY >= sideY && mouseY < sideY + sideH) {
            sideTargetScroll -= (float) delta * 30;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (getFocused() instanceof EditBox box && box != search) {
                box.setFocused(false);
                setFocused(null);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
