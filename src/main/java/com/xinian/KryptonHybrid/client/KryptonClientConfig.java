package com.xinian.KryptonHybrid.client;

import com.xinian.KryptonHybrid.client.overlay.KryptonHudOverlay;
import com.xinian.KryptonHybrid.client.ui.UITheme;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Persisted dashboard and HUD preferences ({@code krypton_hybrid-client.toml}).
 *
 * <p>The live values stay in {@link UITheme} and {@link KryptonHudOverlay};
 * {@link #apply()} copies the file into them on load, and {@link #save()} writes
 * them back whenever the player changes a setting.</p>
 */
public final class KryptonClientConfig {
    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.EnumValue<UITheme.Mode> THEME;
    private static final ForgeConfigSpec.IntValue PANEL_OPACITY;
    private static final ForgeConfigSpec.BooleanValue HUD_VISIBLE;
    private static final ForgeConfigSpec.EnumValue<KryptonHudOverlay.HudAnchor> HUD_ANCHOR;
    private static final ForgeConfigSpec.IntValue HUD_REFRESH_MS;
    private static final ForgeConfigSpec.BooleanValue HUD_SHOW_TOP_MOD;
    private static final ForgeConfigSpec.BooleanValue HUD_SHOW_GRAPH;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Stats dashboard appearance.").push("dashboard");
        THEME = builder.comment("Color theme of the dashboard and HUD.")
                .defineEnum("theme", UITheme.Mode.DARK);
        PANEL_OPACITY = builder.comment("Dashboard panel opacity in percent; lower shows more of the world behind it.")
                .defineInRange("panel_opacity", 85, 40, 100);
        builder.pop();

        builder.comment("In-game traffic HUD.").push("hud");
        HUD_VISIBLE = builder.define("visible", false);
        HUD_ANCHOR = builder.defineEnum("anchor", KryptonHudOverlay.HudAnchor.TOP_RIGHT);
        HUD_REFRESH_MS = builder.comment("How often the HUD asks the server for fresh stats.")
                .defineInRange("refresh_ms", 2000, 500, 10000);
        HUD_SHOW_TOP_MOD = builder.define("show_top_mod", true);
        HUD_SHOW_GRAPH = builder.comment("Draw live rate sparklines next to the HUD values.")
                .define("show_graph", true);
        builder.pop();

        SPEC = builder.build();
    }

    private KryptonClientConfig() {}

    static void apply() {
        UITheme.setMode(THEME.get());
        UITheme.setPanelOpacityPercent(PANEL_OPACITY.get());
        KryptonHudOverlay.setVisible(HUD_VISIBLE.get());
        KryptonHudOverlay.setAnchor(HUD_ANCHOR.get());
        KryptonHudOverlay.setAutoRefreshIntervalMs(HUD_REFRESH_MS.get());
        KryptonHudOverlay.setShowTopMod(HUD_SHOW_TOP_MOD.get());
        KryptonHudOverlay.setShowGraph(HUD_SHOW_GRAPH.get());
    }

    public static void save() {
        if (!SPEC.isLoaded()) {
            return;
        }
        THEME.set(UITheme.getMode());
        PANEL_OPACITY.set(UITheme.panelOpacityPercent());
        HUD_VISIBLE.set(KryptonHudOverlay.isVisible());
        HUD_ANCHOR.set(KryptonHudOverlay.anchor());
        HUD_REFRESH_MS.set((int) KryptonHudOverlay.autoRefreshIntervalMs());
        HUD_SHOW_TOP_MOD.set(KryptonHudOverlay.showTopMod());
        HUD_SHOW_GRAPH.set(KryptonHudOverlay.showGraph());
        SPEC.save();
    }
}
