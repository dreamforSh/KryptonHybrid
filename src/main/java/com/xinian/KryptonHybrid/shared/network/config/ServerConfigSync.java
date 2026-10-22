package com.xinian.KryptonHybrid.shared.network.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.xinian.KryptonHybrid.KryptonForgeConfig;
import com.xinian.KryptonHybrid.shared.KryptonSharedBootstrap;
import com.xinian.KryptonHybrid.shared.network.compression.ZstdUtil;
import com.xinian.KryptonHybrid.shared.network.payload.ServerConfigSnapshotPayload;
import com.xinian.KryptonHybrid.shared.network.payload.ServerConfigUpdatePayload;
import com.xinian.KryptonHybrid.shared.network.security.MotdCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Server side of remote config editing: lets operators read and change this server's
 * {@code krypton_hybrid-common.toml} from the in-game config screen (server thread).
 *
 * <p>Every request is checked against {@link #PERMISSION_LEVEL}. Accepted changes
 * are applied to the live config, re-baked, saved to disk and logged with the
 * operator's name. Secret values are write-only: they are never sent to clients or
 * written to the log.</p>
 */
public final class ServerConfigSync {
    /** Same level as the {@code /krypton} commands. */
    public static final int PERMISSION_LEVEL = 2;

    public static final String ERROR_NO_PERMISSION = "no_permission";
    public static final String ERROR_UNKNOWN_KEY = "unknown_key";
    public static final String ERROR_INVALID_VALUE = "invalid_value";

    private ServerConfigSync() {}

    public static boolean mayEdit(ServerPlayer player) {
        return player.hasPermissions(PERMISSION_LEVEL);
    }

    /** Snapshot for {@code player}: the full config for operators, only the error otherwise. */
    public static ServerConfigSnapshotPayload snapshot(ServerPlayer player, String error) {
        if (!mayEdit(player)) {
            return new ServerConfigSnapshotPayload(false, ERROR_NO_PERMISSION, List.of());
        }
        List<ServerConfigSnapshotPayload.Entry> entries = new ArrayList<>();
        collect(KryptonForgeConfig.SPEC.getValues(), "", entries);
        return new ServerConfigSnapshotPayload(true, error == null ? "" : error, entries);
    }

    private static void collect(UnmodifiableConfig values, String prefix, List<ServerConfigSnapshotPayload.Entry> out) {
        for (Map.Entry<String, Object> e : values.valueMap().entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue() instanceof UnmodifiableConfig section) {
                collect(section, path, out);
            } else if (e.getValue() instanceof ForgeConfigSpec.ConfigValue<?> value) {
                Object current = value.get();
                if (ConfigValueCodec.isSecret(path)) {
                    out.add(new ServerConfigSnapshotPayload.Entry(path, "", !String.valueOf(current).isEmpty()));
                } else {
                    out.add(new ServerConfigSnapshotPayload.Entry(path, ConfigValueCodec.format(current), true));
                }
            }
        }
    }

    /**
     * Applies {@code changes} made by {@code player}. Invalid changes are skipped; the
     * error code of the first one is returned ({@code null} when all were applied).
     */
    @SuppressWarnings("unchecked")
    public static String apply(ServerPlayer player, List<ServerConfigUpdatePayload.Change> changes) {
        if (!mayEdit(player)) {
            return ERROR_NO_PERMISSION;
        }
        ForgeConfigSpec spec = KryptonForgeConfig.SPEC;
        String error = null;
        boolean changed = false;
        for (ServerConfigUpdatePayload.Change change : changes) {
            Object specEntry = spec.getSpec().get(change.path());
            Object valueEntry = spec.getValues().get(change.path());
            if (!(specEntry instanceof ForgeConfigSpec.ValueSpec valueSpec)
                    || !(valueEntry instanceof ForgeConfigSpec.ConfigValue<?> configValue)) {
                error = error == null ? ERROR_UNKNOWN_KEY : error;
                continue;
            }
            Object parsed = ConfigValueCodec.parse(valueSpec.getDefault(), change.value());
            if (parsed == null || !valueSpec.test(parsed)) {
                error = error == null ? ERROR_INVALID_VALUE : error;
                continue;
            }
            Object old = configValue.get();
            if (Objects.equals(old, parsed)) {
                continue;
            }
            ((ForgeConfigSpec.ConfigValue<Object>) configValue).set(parsed);
            changed = true;
            boolean secret = ConfigValueCodec.isSecret(change.path());
            KryptonSharedBootstrap.LOGGER.info("[Krypton] {} changed server config {}: {} -> {}",
                    player.getGameProfile().getName(), change.path(),
                    secret ? "***" : ConfigValueCodec.format(old),
                    secret ? "***" : ConfigValueCodec.format(parsed));
        }
        if (changed) {
            // Same steps as a file reload, then persist (the file watcher's reload is harmless).
            KryptonForgeConfig.INSTANCE.bake();
            MotdCache.invalidate();
            ZstdUtil.reloadDictionary();
            spec.save();
        }
        return error;
    }
}
