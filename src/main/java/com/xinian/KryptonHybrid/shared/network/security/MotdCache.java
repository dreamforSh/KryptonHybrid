package com.xinian.KryptonHybrid.shared.network.security;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import net.minecraft.network.protocol.status.ServerStatus;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Short-lived cache for MOTD / Server List Ping responses.
 *
 * <p>Modern status responses spend time encoding {@link ServerStatus} into
 * JSON. During server-list scans this can happen many times per second. A small
 * TTL keeps the response fresh enough for the server list while avoiding repeated
 * JSON serialization and legacy MOTD string formatting.</p>
 */
public final class MotdCache {
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

    public static String cachedLegacyVersion0(Object server) {
        return null;
    }

    public static String cachedLegacyVersion1(Object server) {
        return null;
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
        return status.getJson();
    }

    private record JsonEntry(String json, long expiresAtMs) {}

    private record LegacyEntry(String response, long expiresAtMs) {}
}
