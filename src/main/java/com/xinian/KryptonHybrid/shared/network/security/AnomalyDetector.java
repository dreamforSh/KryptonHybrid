package com.xinian.KryptonHybrid.shared.network.security;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-connection anomaly tracker that detects suspicious behaviour patterns
 * and disconnects connections that exceed a configurable strike threshold.
 *
 * <h3>Tracked anomalies</h3>
 * <ul>
 *   <li><strong>HMAC verification failure</strong> — Velocity forwarding HMAC mismatch</li>
 *   <li><strong>Compression error</strong> — repeated decompression failures</li>
 *   <li><strong>Missing hello</strong> — connection enters PLAY without completing
 *       the {@code krypton_hybrid:hello} capability negotiation (not always malicious,
 *       but tracked as a signal)</li>
 *   <li><strong>Invalid packet</strong> — repeated malformed / out-of-protocol packets</li>
 *   <li><strong>Rapid reconnect</strong> — same IP reconnects too quickly after being
 *       disconnected</li>
 * </ul>
 *
 * <h3>Strike system</h3>
 * <p>Each anomaly event increments the per-connection strike counter.  When the
 * counter exceeds {@link KryptonConfig#securityAnomalyStrikeThreshold}, the
 * connection is forcibly closed.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 *   AnomalyDetector detector = AnomalyDetector.get(channel);
 *   detector.recordStrike(AnomalyDetector.AnomalyType.HMAC_FAILURE, "details");
 * </pre>
 */
public final class AnomalyDetector {

    private static final Logger LOGGER = LoggerFactory.getLogger("KryptonSecurity");

    /**
     * Netty channel attribute key for storing the per-connection detector.
     */
    public static final AttributeKey<AnomalyDetector> ATTR_KEY =
            AttributeKey.valueOf("krypton_anomaly_detector");

    /**
     * Anomaly types, each carrying a different weight (strike cost).
     */
    public enum AnomalyType {
        HMAC_FAILURE(3),           // high severity — likely attack
        COMPRESSION_ERROR(2),      // medium — could be corruption or attack
        MISSING_HELLO(1),          // low — could be vanilla client
        INVALID_PACKET(2),         // medium
        RAPID_RECONNECT(1),        // low — could be network issue
        PROTOCOL_VIOLATION(3);     // high — definite misbehaviour

        private final int weight;

        AnomalyType(int weight) {
            this.weight = weight;
        }

        public int weight() { return weight; }
    }

    // ── Per-connection state ──────────────────────────────────────────

    private final Channel channel;
    private int totalStrikes = 0;

    private AnomalyDetector(Channel channel) {
        this.channel = channel;
    }

    /**
     * Gets or creates the anomaly detector for the given channel.
     */
    public static AnomalyDetector get(Channel channel) {
        AnomalyDetector detector = channel.attr(ATTR_KEY).get();
        if (detector == null) {
            detector = new AnomalyDetector(channel);
            channel.attr(ATTR_KEY).set(detector);
        }
        return detector;
    }

    /**
     * Gets the detector if already present, or null.
     */
    public static AnomalyDetector getIfPresent(Channel channel) {
        return channel.attr(ATTR_KEY).get();
    }

    /**
     * Records a suspicious event and checks the strike threshold.
     *
     * @param type    the anomaly type
     * @param details human-readable description for logging
     */
    public void recordStrike(AnomalyType type, String details) {
        if (!KryptonConfig.securityEnabled) return;

        totalStrikes += type.weight();
        SecurityMetrics.INSTANCE.recordAnomalyEvent();

        LOGGER.warn("[Krypton Security] Anomaly detected on {} — type={}, strikes={}/{}, detail: {}",
                channel.remoteAddress(), type, totalStrikes,
                KryptonConfig.securityAnomalyStrikeThreshold, details);

        if (totalStrikes >= KryptonConfig.securityAnomalyStrikeThreshold) {
            LOGGER.warn("[Krypton Security] Strike threshold reached for {} — closing connection",
                    channel.remoteAddress());
            channel.close();
        }
    }

    /**
     * Returns the current total strike count.
     */
    public int getTotalStrikes() {
        return totalStrikes;
    }
}
