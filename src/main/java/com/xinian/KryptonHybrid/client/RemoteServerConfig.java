package com.xinian.KryptonHybrid.client;

import com.xinian.KryptonHybrid.KryptonForgeConfig;
import com.xinian.KryptonHybrid.shared.network.config.ConfigValueCodec;
import com.xinian.KryptonHybrid.shared.network.payload.ServerConfigRequestPayload;
import com.xinian.KryptonHybrid.shared.network.payload.ServerConfigSnapshotPayload;
import com.xinian.KryptonHybrid.shared.network.payload.ServerConfigUpdatePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client copy of the connected server's network config, for operators editing it in
 * the config screen. Values arrive as text and are parsed with this client's copy of
 * the same spec. Edits are shown right away and replaced by the server's answer.
 * Client thread only.
 */
public final class RemoteServerConfig {
    /** Must match the server's {@code ServerConfigSync.PERMISSION_LEVEL}. */
    public static final int PERMISSION_LEVEL = 2;

    private static final Map<String, Object> VALUES = new HashMap<>();
    private static final Set<String> SECRETS_SET = new HashSet<>();
    private static boolean loaded;
    private static boolean waiting;
    private static String error = "";
    private static long version;

    private RemoteServerConfig() {}

    /** Whether the player is on a remote Krypton Hybrid server and may edit its config. */
    public static boolean available() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getConnection() != null
                && minecraft.player != null
                && !minecraft.getConnection().getConnection().isMemoryConnection()
                && minecraft.getConnection().hasChannel(ServerConfigRequestPayload.TYPE)
                && minecraft.player.hasPermissions(PERMISSION_LEVEL);
    }

    public static void request() {
        if (Minecraft.getInstance().getConnection() == null
                || !Minecraft.getInstance().getConnection().hasChannel(ServerConfigRequestPayload.TYPE)) {
            return;
        }
        waiting = true;
        PacketDistributor.sendToServer(ServerConfigRequestPayload.INSTANCE);
    }

    static void accept(ServerConfigSnapshotPayload payload) {
        waiting = false;
        error = payload.error();
        if (payload.canEdit()) {
            VALUES.clear();
            SECRETS_SET.clear();
            for (ServerConfigSnapshotPayload.Entry entry : payload.entries()) {
                if (!(KryptonForgeConfig.SPEC.getSpec().get(entry.path()) instanceof ModConfigSpec.ValueSpec spec)) {
                    continue;
                }
                Object parsed = ConfigValueCodec.parse(spec.getDefault(), entry.value());
                if (parsed != null) {
                    VALUES.put(entry.path(), parsed);
                }
                if (ConfigValueCodec.isSecret(entry.path()) && entry.hasValue()) {
                    SECRETS_SET.add(entry.path());
                }
            }
            loaded = true;
        } else {
            loaded = false;
        }
        version++;
    }

    static void clear() {
        VALUES.clear();
        SECRETS_SET.clear();
        loaded = false;
        waiting = false;
        error = "";
        version++;
    }

    public static boolean loaded() { return loaded; }
    public static boolean waiting() { return waiting; }
    /** Last error code from the server, or empty. */
    public static String error() { return error; }
    /** Increments whenever the server's answer replaces the values. */
    public static long version() { return version; }

    public static Object value(String path) {
        return VALUES.get(path);
    }

    public static boolean secretIsSet(String path) {
        return SECRETS_SET.contains(path);
    }

    /** Shows {@code value} locally without sending it (slider drags). */
    public static void preview(String path, Object value) {
        VALUES.put(path, value);
    }

    public static void submit(String path, Object value) {
        submit(Map.of(path, value));
    }

    /** Sends changes to the server; they show right away and are confirmed by its answer. */
    public static void submit(Map<String, Object> changes) {
        if (changes.isEmpty()) {
            return;
        }
        List<ServerConfigUpdatePayload.Change> list = new ArrayList<>();
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            VALUES.put(change.getKey(), change.getValue());
            if (ConfigValueCodec.isSecret(change.getKey())) {
                if (String.valueOf(change.getValue()).isEmpty()) {
                    SECRETS_SET.remove(change.getKey());
                } else {
                    SECRETS_SET.add(change.getKey());
                }
            }
            list.add(new ServerConfigUpdatePayload.Change(change.getKey(), ConfigValueCodec.format(change.getValue())));
        }
        error = "";
        waiting = true;
        PacketDistributor.sendToServer(new ServerConfigUpdatePayload(list));
    }
}
