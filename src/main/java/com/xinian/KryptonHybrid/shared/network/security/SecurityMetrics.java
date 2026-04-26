package com.xinian.KryptonHybrid.shared.network.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized security event counters for Krypton Hybrid.
 *
 * <p>All counters are lock-free {@link AtomicLong}s, safe to be incremented from
 * any Netty I/O thread or the server main thread without synchronization.</p>
 *
 * <p>A periodic summary can be logged via {@link #logSummaryAndReset()}.</p>
 */
public final class SecurityMetrics {

    public static final SecurityMetrics INSTANCE = new SecurityMetrics();

    private static final Logger LOGGER = LoggerFactory.getLogger("KryptonSecurity");

    // ── Connection-level ──────────────────────────────────────────────

    /** Connections rejected by the per-IP rate limiter. */
    private final AtomicLong connectionsRateLimited = new AtomicLong();

    // ── Packet-level ──────────────────────────────────────────────────

    /** Packets rejected by the size / NBT depth validator. */
    private final AtomicLong packetsSizeRejected = new AtomicLong();

    /** Payload decode fields rejected by FriendlyByteBuf read-side guardrails. */
    private final AtomicLong readLimitRejected = new AtomicLong();

    /** All-zero/null frame data dropped by the frame decoder nullping guard. */
    private final AtomicLong nullFramesDropped = new AtomicLong();

    // ── Decompression ─────────────────────────────────────────────────

    /** Packets flagged as decompression bombs (ratio exceeded). */
    private final AtomicLong decompressionBombs = new AtomicLong();

    // ── Handshake / protocol ──────────────────────────────────────────

    /** Handshakes rejected by the protocol/address validator. */
    private final AtomicLong handshakesRejected = new AtomicLong();

    /** Modern STATUS ping requests dropped by the scan guard. */
    private final AtomicLong statusRequestsDropped = new AtomicLong();

    /** Legacy pre-1.7 ping requests dropped by the scan guard. */
    private final AtomicLong legacyQueriesDropped = new AtomicLong();

    /** Connections closed by the stage-aware read timeout handler. */
    private final AtomicLong timeouts = new AtomicLong();

    // ── Anomaly detection ─────────────────────────────────────────────

    /** Individual anomaly events recorded (before disconnect threshold). */
    private final AtomicLong anomalyEvents = new AtomicLong();

    /** Connections forcibly closed due to anomaly strike threshold. */
    private final AtomicLong anomalyDisconnects = new AtomicLong();

    // ── Netty resource guard ──────────────────────────────────────────

    /** Writes dropped because the pending queue exceeded the limit. */
    private final AtomicLong writesDropped = new AtomicLong();

    /** Times the channel became unwritable (high watermark hit). */
    private final AtomicLong watermarkBreaches = new AtomicLong();

    private SecurityMetrics() {}

    // ── Increment methods ─────────────────────────────────────────────

    public void recordConnectionRateLimited()   { connectionsRateLimited.incrementAndGet(); }
    public void recordPacketSizeRejected()       { packetsSizeRejected.incrementAndGet(); }
    public void recordReadLimitRejected()        { readLimitRejected.incrementAndGet(); }
    public void recordNullFrameDropped()         { nullFramesDropped.incrementAndGet(); }
    public void recordDecompressionBomb()         { decompressionBombs.incrementAndGet(); }
    public void recordHandshakeRejected()        { handshakesRejected.incrementAndGet(); }
    public void recordStatusRequestDropped()      { statusRequestsDropped.incrementAndGet(); }
    public void recordLegacyQueryDropped()        { legacyQueriesDropped.incrementAndGet(); }
    public void recordTimeout()                  { timeouts.incrementAndGet(); }
    public void recordAnomalyEvent()             { anomalyEvents.incrementAndGet(); }
    public void recordAnomalyDisconnect()        { anomalyDisconnects.incrementAndGet(); }
    public void recordWriteDropped()             { writesDropped.incrementAndGet(); }
    public void recordWatermarkBreach()          { watermarkBreaches.incrementAndGet(); }

    // ── Read methods (for /krypton stats) ─────────────────────────────

    public long getConnectionsRateLimited()  { return connectionsRateLimited.get(); }
    public long getPacketsSizeRejected()     { return packetsSizeRejected.get(); }
    public long getReadLimitRejected()       { return readLimitRejected.get(); }
    public long getNullFramesDropped()       { return nullFramesDropped.get(); }
    public long getDecompressionBombs()      { return decompressionBombs.get(); }
    public long getHandshakesRejected()      { return handshakesRejected.get(); }
    public long getStatusRequestsDropped()   { return statusRequestsDropped.get(); }
    public long getLegacyQueriesDropped()    { return legacyQueriesDropped.get(); }
    public long getTimeouts()                { return timeouts.get(); }
    public long getAnomalyEvents()           { return anomalyEvents.get(); }
    public long getAnomalyDisconnects()      { return anomalyDisconnects.get(); }
    public long getWritesDropped()           { return writesDropped.get(); }
    public long getWatermarkBreaches()       { return watermarkBreaches.get(); }

    /**
     * Logs a summary of all counters and resets them to zero.
     * Intended to be called periodically (e.g. every 5 minutes).
     */
    public void logSummaryAndReset() {
        long connRL  = connectionsRateLimited.getAndSet(0);
        long pktSR   = packetsSizeRejected.getAndSet(0);
        long readRej = readLimitRejected.getAndSet(0);
        long nullFrm = nullFramesDropped.getAndSet(0);
        long dcBomb  = decompressionBombs.getAndSet(0);
        long hsRej   = handshakesRejected.getAndSet(0);
        long statusDrop = statusRequestsDropped.getAndSet(0);
        long legacyDrop = legacyQueriesDropped.getAndSet(0);
        long to      = timeouts.getAndSet(0);
        long anEvt   = anomalyEvents.getAndSet(0);
        long anDisc  = anomalyDisconnects.getAndSet(0);
        long wrDrop  = writesDropped.getAndSet(0);
        long wmBr    = watermarkBreaches.getAndSet(0);

        long total = connRL + pktSR + readRej + nullFrm + dcBomb + hsRej + statusDrop + legacyDrop + to + anEvt + anDisc + wrDrop + wmBr;
        if (total == 0) return; // nothing to report

        LOGGER.info("[Krypton Security] Period summary — "
                        + "connRateLimited={}, "
                        + "pktSizeRejected={}, readLimitRejected={}, nullFramesDropped={}, "
                        + "decompBombs={}, handshakeRejected={}, statusDropped={}, legacyDropped={}, timeouts={}, "
                        + "anomalyEvents={}, anomalyDisconnects={}, "
                        + "writesDropped={}, watermarkBreaches={}",
                connRL, pktSR, readRej, nullFrm, dcBomb, hsRej, statusDrop, legacyDrop, to, anEvt, anDisc, wrDrop, wmBr);
    }

    /**
     * Returns a multi-line human-readable snapshot (for command output).
     */
    public String snapshot() {
        return String.format(
                """
                §6[Krypton Security Metrics]
                §7Connections rate-limited: §f%d
                §7Packets size-rejected:    §f%d
                §7Read-limit rejected:      §f%d
                §7Null frames dropped:      §f%d
                §7Decompression bombs:      §f%d
                §7Handshakes rejected:      §f%d
                §7Status pings dropped:     §f%d
                §7Legacy pings dropped:     §f%d
                §7Timeouts:                 §f%d
                §7Anomaly events:           §f%d
                §7Anomaly disconnects:      §f%d
                §7Writes dropped:           §f%d
                §7Watermark breaches:       §f%d""",
                connectionsRateLimited.get(),
                packetsSizeRejected.get(),
                readLimitRejected.get(),
                nullFramesDropped.get(),
                decompressionBombs.get(),
                handshakesRejected.get(),
                statusRequestsDropped.get(),
                legacyQueriesDropped.get(),
                timeouts.get(),
                anomalyEvents.get(),
                anomalyDisconnects.get(),
                writesDropped.get(),
                watermarkBreaches.get());
    }
}

