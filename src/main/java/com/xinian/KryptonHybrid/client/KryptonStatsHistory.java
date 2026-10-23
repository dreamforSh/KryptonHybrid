package com.xinian.KryptonHybrid.client;

import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotPayload;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Client-side ring buffer of live traffic rates.
 *
 * <p>Snapshot counters are cumulative since the server started, so their built-in
 * rates are lifetime averages. Live rates come from the difference between two
 * consecutive snapshots divided by the time between them. Client thread only.</p>
 */
public final class KryptonStatsHistory {

    /** Two minutes at the dashboard's 1 s refresh, four at the HUD's default 2 s. */
    public static final int CAPACITY = 120;

    /** Shared server snapshots are reused for 250 ms; closer samples carry no new data. */
    private static final long MIN_SAMPLE_INTERVAL_MS = 400L;

    /**
     * One interval's rates. {@code savingPercent} is {@link Double#NaN} when nothing
     * was sent in the interval; {@code latencyMs} is this client's ping as last
     * reported by the server, or {@link Double#NaN} when unknown or local.
     */
    public record Sample(long atMs,
                         double outWireBps,
                         double outOriginalBps,
                         double inBps,
                         double outPps,
                         double inPps,
                         double savingPercent,
                         double latencyMs) {}

    /** A plottable series of {@link Sample}. */
    public enum Field {
        OUT_WIRE(Sample::outWireBps),
        OUT_ORIGINAL(Sample::outOriginalBps),
        IN(Sample::inBps),
        OUT_PPS(Sample::outPps),
        IN_PPS(Sample::inPps),
        SAVING(Sample::savingPercent),
        LATENCY(Sample::latencyMs);

        private final ToDoubleFunction<Sample> extractor;

        Field(ToDoubleFunction<Sample> extractor) {
            this.extractor = extractor;
        }
    }

    private static final ArrayDeque<Sample> SAMPLES = new ArrayDeque<>(CAPACITY);
    private static final Map<Field, double[]> SERIES = new EnumMap<>(Field.class);
    private static StatsSnapshotPayload previous;
    private static long previousAtMs;
    private static long version;

    private KryptonStatsHistory() {}

    static void record(StatsSnapshotPayload snap, long nowMs) {
        if (previous != null) {
            long dtMs = nowMs - previousAtMs;
            if (dtMs < MIN_SAMPLE_INTERVAL_MS) {
                return;
            }
            long dWire = snap.bytesSentWire() - previous.bytesSentWire();
            long dOrig = snap.bytesSentOriginal() - previous.bytesSentOriginal();
            long dIn = snap.bytesReceived() - previous.bytesReceived();
            long dOutPackets = snap.packetsSent() - previous.packetsSent();
            long dInPackets = snap.packetsReceived() - previous.packetsReceived();
            if (dWire < 0 || dOrig < 0 || dIn < 0 || dOutPackets < 0 || dInPackets < 0) {
                // Counters went backwards: server restarted or stats were reset.
                SAMPLES.clear();
            } else {
                double seconds = dtMs / 1000.0;
                double saving = dOrig > 0 ? (1.0 - dWire / (double) dOrig) * 100.0 : Double.NaN;
                if (SAMPLES.size() == CAPACITY) {
                    SAMPLES.removeFirst();
                }
                SAMPLES.addLast(new Sample(nowMs,
                        dWire / seconds,
                        dOrig / seconds,
                        dIn / seconds,
                        dOutPackets / seconds,
                        dInPackets / seconds,
                        saving,
                        KryptonStatsClientController.latencyMs()));
            }
            changed();
        }
        previous = snap;
        previousAtMs = nowMs;
    }

    static void clear() {
        SAMPLES.clear();
        previous = null;
        previousAtMs = 0L;
        changed();
    }

    private static void changed() {
        version++;
        SERIES.clear();
    }

    /** Increments whenever the samples change; cached geometry keys on it. */
    public static long version() {
        return version;
    }

    public static Sample latest() {
        return SAMPLES.peekLast();
    }

    public static int size() {
        return SAMPLES.size();
    }

    /** Seconds covered by the buffered samples. */
    public static long spanSeconds() {
        if (SAMPLES.size() < 2) {
            return 0L;
        }
        return (SAMPLES.peekLast().atMs() - SAMPLES.peekFirst().atMs()) / 1000L;
    }

    /**
     * Oldest-first values of one series. The array is shared until the next sample
     * arrives; callers must not modify it.
     */
    public static double[] series(Field field) {
        double[] cached = SERIES.get(field);
        if (cached == null) {
            cached = new double[SAMPLES.size()];
            int i = 0;
            for (Sample s : SAMPLES) {
                cached[i++] = field.extractor.applyAsDouble(s);
            }
            SERIES.put(field, cached);
        }
        return cached;
    }
}
