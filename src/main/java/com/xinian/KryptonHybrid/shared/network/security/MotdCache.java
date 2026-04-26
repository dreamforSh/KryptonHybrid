package com.xinian.KryptonHybrid.shared.network.security;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.ServerInfo;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Short-lived cache for MOTD / Server List Ping responses.
 *
 * <p>Modern status responses spend time encoding {@link ServerStatus#CODEC} into
 * JSON. During server-list scans this can happen many times per second. A small
 * TTL keeps the response fresh enough for the server list while avoiding repeated
 * JSON serialization and legacy MOTD string formatting.</p>
 */
public final class MotdCache {
    private static final Gson GSON = new Gson();

    private static volatile JsonEntry modernJson;
    private static volatile LegacyEntry legacyV0;
    private static volatile LegacyEntry legacyV1;

    private static final AtomicLong modernHits = new AtomicLong();
    private static final AtomicLong modernMisses = new AtomicLong();
    private static final AtomicLong legacyHits = new AtomicLong();
    private static final AtomicLong legacyMisses = new AtomicLong();

    private MotdCache() {}

    public static String cachedStatusJson(ServerStatus status) {
        if (!isEnabled()) {
            return null;
        }

        long now = System.currentTimeMillis();
        JsonEntry entry = modernJson;
        if (entry != null && entry.expiresAtMs() > now) {
            modernHits.incrementAndGet();
            return entry.json();
        }

        synchronized (MotdCache.class) {
            now = System.currentTimeMillis();
            entry = modernJson;
            if (entry != null && entry.expiresAtMs() > now) {
                modernHits.incrementAndGet();
                return entry.json();
            }

            String json = encodeStatusJson(status);
            modernJson = new JsonEntry(json, now + ttlMs());
            modernMisses.incrementAndGet();
            return json;
        }
    }

    public static String cachedLegacyVersion0(ServerInfo server) {
        if (!isEnabled()) {
            return null;
        }

        long now = System.currentTimeMillis();
        LegacyEntry entry = legacyV0;
        if (entry != null && entry.expiresAtMs() > now) {
            legacyHits.incrementAndGet();
            return entry.response();
        }

        synchronized (MotdCache.class) {
            now = System.currentTimeMillis();
            entry = legacyV0;
            if (entry != null && entry.expiresAtMs() > now) {
                legacyHits.incrementAndGet();
                return entry.response();
            }

            String response = String.format(Locale.ROOT, "%s\u00a7%d\u00a7%d",
                    server.getMotd(),
                    server.getPlayerCount(),
                    server.getMaxPlayers());
            legacyV0 = new LegacyEntry(response, now + ttlMs());
            legacyMisses.incrementAndGet();
            return response;
        }
    }

    public static String cachedLegacyVersion1(ServerInfo server) {
        if (!isEnabled()) {
            return null;
        }

        long now = System.currentTimeMillis();
        LegacyEntry entry = legacyV1;
        if (entry != null && entry.expiresAtMs() > now) {
            legacyHits.incrementAndGet();
            return entry.response();
        }

        synchronized (MotdCache.class) {
            now = System.currentTimeMillis();
            entry = legacyV1;
            if (entry != null && entry.expiresAtMs() > now) {
                legacyHits.incrementAndGet();
                return entry.response();
            }

            String response = String.format(
                    Locale.ROOT,
                    "\u00a71\u0000%d\u0000%s\u0000%s\u0000%d\u0000%d",
                    127,
                    server.getServerVersion(),
                    server.getMotd(),
                    server.getPlayerCount(),
                    server.getMaxPlayers());
            legacyV1 = new LegacyEntry(response, now + ttlMs());
            legacyMisses.incrementAndGet();
            return response;
        }
    }

    public static void invalidate() {
        modernJson = null;
        legacyV0 = null;
        legacyV1 = null;
    }

    public static String statusDescription() {
        if (!isEnabled()) {
            return "off";
        }
        return String.format(Locale.ROOT,
                "on(ttl=%dms, modern hits/misses=%d/%d, legacy hits/misses=%d/%d)",
                ttlMs(),
                modernHits.get(),
                modernMisses.get(),
                legacyHits.get(),
                legacyMisses.get());
    }

    private static boolean isEnabled() {
        return KryptonConfig.securityEnabled
                && KryptonConfig.motdCacheEnabled
                && KryptonConfig.motdCacheTtlMs > 0;
    }

    private static int ttlMs() {
        return Math.max(0, KryptonConfig.motdCacheTtlMs);
    }

    private static String encodeStatusJson(ServerStatus status) {
        DataResult<JsonElement> result = ServerStatus.CODEC.encodeStart(JsonOps.INSTANCE, status);
        return GSON.toJson(result.getOrThrow(error -> new EncoderException("Failed to encode server status: " + error)));
    }

    private record JsonEntry(String json, long expiresAtMs) {}

    private record LegacyEntry(String response, long expiresAtMs) {}
}
