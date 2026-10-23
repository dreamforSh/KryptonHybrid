package com.xinian.KryptonHybrid.shared.network.security;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-IP throttle for server-list STATUS pings and old legacy pings.
 *
 * <p>Port scanners commonly probe Minecraft servers with repeated STATUS
 * handshakes because they are cheap for the scanner and relatively expensive for
 * the server if every probe serializes MOTD JSON. This guard is intentionally
 * separate from login rate limiting so real players are not penalized as heavily
 * by server-list refreshes or scanner noise.</p>
 */
public final class StatusRequestGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger("KryptonSecurity");
    private static final Map<String, StatusBudget> WINDOWS = new ConcurrentHashMap<>();
    /** Idle buckets are dropped after this long, unless still quarantined. */
    private static final long IDLE_EVICT_MS = 60_000L;
    private static final long EVICT_INTERVAL_MS = 30_000L;
    private static final AtomicLong NEXT_EVICT_MS = new AtomicLong();

    private StatusRequestGuard() {}

    public static boolean allowStatusPing(Channel channel, String hostName) {
        if (!KryptonConfig.securityEnabled || !KryptonConfig.securityStatusPingGuardEnabled) {
            return true;
        }
        return allow(channel, "status", 1.0D, hostName);
    }

    public static boolean allowLegacyQuery(Channel channel, int readableBytes) {
        if (!KryptonConfig.securityEnabled
                || !KryptonConfig.securityStatusPingGuardEnabled
                || !KryptonConfig.securityLegacyQueryGuardEnabled) {
            return true;
        }
        return allow(channel, "legacy", 1.5D, "legacy_bytes=" + readableBytes);
    }

    /**
     * Drops buckets of addresses that have been idle for a minute and are not
     * quarantined. Runs from the connection path at most every 30 s, so the map stays
     * bounded by the addresses seen recently.
     */
    public static void evictStaleWindows() {
        long now = System.currentTimeMillis();
        WINDOWS.values().removeIf(budget -> budget.evictable(now));
    }

    private static void evictStaleWindowsIfDue() {
        long now = System.currentTimeMillis();
        long due = NEXT_EVICT_MS.get();
        if (now >= due && NEXT_EVICT_MS.compareAndSet(due, now + EVICT_INTERVAL_MS)) {
            evictStaleWindows();
        }
    }

    private static boolean allow(Channel channel, String kind, double cost, String detail) {
        String ip = RateLimitKeys.of(channel);
        if (ip == null) {
            return true;
        }

        evictStaleWindowsIfDue();
        StatusBudget budget = WINDOWS.get(ip);
        if (budget == null) {
            if (WINDOWS.size() >= RateLimitKeys.MAX_TRACKED_ADDRESSES) {
                evictStaleWindows();
                if (WINDOWS.size() >= RateLimitKeys.MAX_TRACKED_ADDRESSES) {
                    return true;
                }
            }
            budget = WINDOWS.computeIfAbsent(ip, key -> new StatusBudget());
        }
        if (budget.tryAcquire(cost)) {
            return true;
        }

        if ("legacy".equals(kind)) {
            SecurityMetrics.INSTANCE.recordLegacyQueryDropped();
        } else {
            SecurityMetrics.INSTANCE.recordStatusRequestDropped();
        }

        if (budget.shouldLogViolation()) {
            LOGGER.warn(
                    "[Krypton Security] {} ping scan throttled for {} ({}, rate={}, burst={}, quarantine={}s)",
                    kind,
                    ip,
                    detail,
                    KryptonConfig.securityStatusPingRatePerSecond,
                    KryptonConfig.securityStatusPingBurstLimit,
                    KryptonConfig.securityStatusPingQuarantineSeconds);
        }
        return false;
    }

    static final class StatusBudget {
        private double tokens;
        private long lastRefillNanos;
        private volatile long lastAccess = System.currentTimeMillis();
        private volatile long quarantineUntilMs;
        private volatile long lastLogMs;
        private int consecutiveViolations;

        synchronized boolean tryAcquire(double cost) {
            long nowMs = System.currentTimeMillis();
            lastAccess = nowMs;

            if (quarantineUntilMs > nowMs) {
                return false;
            }

            refill(System.nanoTime());
            if (tokens >= cost) {
                tokens -= cost;
                consecutiveViolations = 0;
                return true;
            }

            consecutiveViolations++;
            int quarantineSeconds = KryptonConfig.securityStatusPingQuarantineSeconds;
            if (quarantineSeconds > 0 && consecutiveViolations >= 2) {
                quarantineUntilMs = nowMs + quarantineSeconds * 1000L;
            }
            return false;
        }

        private void refill(long nowNanos) {
            double rate = KryptonConfig.securityStatusPingRatePerSecond;
            double burst = KryptonConfig.securityStatusPingBurstLimit;

            if (lastRefillNanos == 0L) {
                tokens = burst;
                lastRefillNanos = nowNanos;
                return;
            }

            long elapsed = nowNanos - lastRefillNanos;
            lastRefillNanos = nowNanos;
            if (elapsed <= 0L || rate <= 0.0D || burst <= 0.0D) {
                if (burst > 0.0D && tokens > burst) {
                    tokens = burst;
                }
                return;
            }

            tokens = Math.min(burst, tokens + (elapsed / 1_000_000_000.0D) * rate);
        }

        boolean evictable(long nowMs) {
            return nowMs - lastAccess > IDLE_EVICT_MS && quarantineUntilMs <= nowMs;
        }

        long lastAccessTime() {
            return lastAccess;
        }

        synchronized boolean shouldLogViolation() {
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastLogMs < 5000L) {
                return false;
            }
            lastLogMs = nowMs;
            return true;
        }
    }
}
