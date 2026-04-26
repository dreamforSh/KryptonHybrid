package com.xinian.KryptonHybrid.shared.network.security;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    public static void evictStaleWindows() {
        long now = System.currentTimeMillis();
        WINDOWS.entrySet().removeIf(e -> now - e.getValue().lastAccessTime() > 60_000L);
    }

    private static boolean allow(Channel channel, String kind, double cost, String detail) {
        InetAddress address = extractAddress(channel);
        if (address == null) {
            return true;
        }

        String ip = address.getHostAddress();
        StatusBudget budget = WINDOWS.computeIfAbsent(ip, key -> new StatusBudget());
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

    private static InetAddress extractAddress(Channel channel) {
        if (channel.remoteAddress() instanceof InetSocketAddress inet) {
            return inet.getAddress();
        }
        return null;
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
