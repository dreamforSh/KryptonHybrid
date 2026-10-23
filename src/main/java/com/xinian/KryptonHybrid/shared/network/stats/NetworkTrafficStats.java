package com.xinian.KryptonHybrid.shared.network.stats;

import net.minecraft.network.protocol.Packet;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

public final class NetworkTrafficStats {

    public static final NetworkTrafficStats INSTANCE = new NetworkTrafficStats();

    private static final class TypeStats {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalBytes = new LongAdder();

        public void record(int bytes) {
            count.increment();
            totalBytes.add(bytes);
        }

        public long getCount() { return count.sum(); }
        public long getTotalBytes() { return totalBytes.sum(); }

        public double getAvgBytes() {
            long c = count.sum();
            return c == 0 ? 0.0 : (double) totalBytes.sum() / c;
        }
    }

    public record TrafficEntry(String key, long count, long totalBytes) {
        public double avgBytes() {
            return count == 0 ? 0.0 : (double) totalBytes / count;
        }
    }

    private final LongAdder packetsSent = new LongAdder();
    private final LongAdder packetsReceived = new LongAdder();
    private final LongAdder bytesSentOriginal = new LongAdder();
    private final LongAdder bytesSentWire = new LongAdder();
    private final LongAdder bytesReceived = new LongAdder();
    // Flush consolidation: flushes that released held writes, and how many packets they carried.
    private final LongAdder consolidatedFlushes = new LongAdder();
    private final LongAdder deferredPackets = new LongAdder();
    // Tick batching: packets written by the collectors, and packets coalescing dropped first.
    private final LongAdder batchedPackets = new LongAdder();
    private final LongAdder coalesceDroppedPackets = new LongAdder();
    // Batch frames: frames sent, and the packets they carried.
    private final LongAdder batchFrames = new LongAdder();
    private final LongAdder batchFramePackets = new LongAdder();
    private final ConcurrentHashMap<String, TypeStats> perTypeStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TypeStats> perModStats  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TypeStats> perTypeWireStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TypeStats> perModWireStats  = new ConcurrentHashMap<>();
    private volatile long startTimeMs = System.currentTimeMillis();

    private NetworkTrafficStats() {}

    public void recordEncode(int originalSize, int wireSize) {
        packetsSent.increment();
        bytesSentOriginal.add(originalSize);
        bytesSentWire.add(wireSize);
    }

    public void recordDecode(int payloadBytes) {
        packetsReceived.increment();
        bytesReceived.add(payloadBytes);
    }

    /** Serialized size of one outgoing packet, under its type and mod. */
    public void recordPacket(Packet<?> packet, int bytes) {
        PacketStatKeys.Keys keys = PacketStatKeys.of(packet);
        recordInto(perTypeStats, keys.type(), bytes);
        recordInto(perModStats, keys.mod(), bytes);
    }

    /** Wire size (after compression) of one outgoing packet, under its type and mod. */
    public void recordPacketWire(Packet<?> packet, int wireBytes) {
        PacketStatKeys.Keys keys = PacketStatKeys.of(packet);
        recordInto(perTypeWireStats, keys.type(), wireBytes);
        recordInto(perModWireStats, keys.mod(), wireBytes);
    }

    private static void recordInto(ConcurrentHashMap<String, TypeStats> map, String key, int bytes) {
        TypeStats stats = map.get(key);
        if (stats == null) {
            stats = map.computeIfAbsent(key, k -> new TypeStats());
        }
        stats.record(bytes);
    }

    public List<TrafficEntry> getTopModsByCount(int limit)     { return topN(perModStats,      TypeStats::getCount,      limit); }
    public List<TrafficEntry> getTopModsByBytes(int limit)     { return topN(perModStats,      TypeStats::getTotalBytes, limit); }
    public List<TrafficEntry> getTopModsByWireBytes(int limit) { return topN(perModWireStats,  TypeStats::getTotalBytes, limit); }
    public List<TrafficEntry> getTopByCount(int limit)         { return topN(perTypeStats,     TypeStats::getCount,      limit); }
    public List<TrafficEntry> getTopByBytes(int limit)         { return topN(perTypeStats,     TypeStats::getTotalBytes, limit); }
    public List<TrafficEntry> getTopByWireBytes(int limit)     { return topN(perTypeWireStats, TypeStats::getTotalBytes, limit); }

    public TrafficEntry getTopModByBytes()  { return topOne(perModStats,  TypeStats::getTotalBytes); }
    public TrafficEntry getTopTypeByBytes() { return topOne(perTypeStats, TypeStats::getTotalBytes); }

    public int getTrackedTypeCount() { return perTypeStats.size(); }
    public int getTrackedModCount()  { return perModStats.size(); }

    public long getTotalTrackedTypePackets()     { return sum(perTypeStats,     TypeStats::getCount); }
    public long getTotalTrackedTypeBytes()       { return sum(perTypeStats,     TypeStats::getTotalBytes); }
    public long getTotalTrackedModPackets()      { return sum(perModStats,      TypeStats::getCount); }
    public long getTotalTrackedModBytes()        { return sum(perModStats,      TypeStats::getTotalBytes); }
    public long getTotalTrackedTypeWirePackets() { return sum(perTypeWireStats, TypeStats::getCount); }
    public long getTotalTrackedTypeWireBytes()   { return sum(perTypeWireStats, TypeStats::getTotalBytes); }
    public long getTotalTrackedModWirePackets()  { return sum(perModWireStats,  TypeStats::getCount); }
    public long getTotalTrackedModWireBytes()    { return sum(perModWireStats,  TypeStats::getTotalBytes); }

    private static List<TrafficEntry> topN(ConcurrentHashMap<String, TypeStats> map,
                                           ToLongFunction<TypeStats> sortKey,
                                           int limit) {
        return map.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, TypeStats>>comparingLong(e -> sortKey.applyAsLong(e.getValue())).reversed())
                .limit(limit)
                .map(NetworkTrafficStats::toTrafficEntry)
                .collect(Collectors.toList());
    }

    private static TrafficEntry topOne(ConcurrentHashMap<String, TypeStats> map,
                                       ToLongFunction<TypeStats> sortKey) {
        return map.entrySet().stream()
                .max(Comparator.comparingLong(e -> sortKey.applyAsLong(e.getValue())))
                .map(NetworkTrafficStats::toTrafficEntry)
                .orElse(null);
    }

    private static long sum(ConcurrentHashMap<String, TypeStats> map, ToLongFunction<TypeStats> field) {
        return map.values().stream().mapToLong(field).sum();
    }

    private static TrafficEntry toTrafficEntry(Map.Entry<String, TypeStats> entry) {
        TypeStats stats = entry.getValue();
        return new TrafficEntry(entry.getKey(), stats.getCount(), stats.getTotalBytes());
    }

    public void reset() {
        packetsSent.reset();
        packetsReceived.reset();
        bytesSentOriginal.reset();
        bytesSentWire.reset();
        bytesReceived.reset();
        consolidatedFlushes.reset();
        deferredPackets.reset();
        batchedPackets.reset();
        coalesceDroppedPackets.reset();
        batchFrames.reset();
        batchFramePackets.reset();
        perTypeStats.clear();
        perModStats.clear();
        perTypeWireStats.clear();
        perModWireStats.clear();
        startTimeMs = System.currentTimeMillis();
    }

    // Flush consolidation and tick batching metrics

    /** One flush that released {@code packets} writes held back by flush consolidation. */
    public void recordConsolidatedFlush(int packets) {
        consolidatedFlushes.increment();
        deferredPackets.add(packets);
    }

    /** Packets the per-tick collectors wrote after coalescing. */
    public void recordBatchedPackets(int packets) {
        if (packets > 0) batchedPackets.add(packets);
    }

    public void recordCoalesceDropped(int dropped) {
        if (dropped > 0) coalesceDroppedPackets.add(dropped);
    }

    public long getConsolidatedFlushes() { return consolidatedFlushes.sum(); }
    public long getDeferredPackets() { return deferredPackets.sum(); }
    public long getBatchedPackets() { return batchedPackets.sum(); }
    public long getCoalesceDroppedPackets() { return coalesceDroppedPackets.sum(); }

    /** One batch frame carrying {@code packets} packets. */
    public void recordBatchFrame(int packets) {
        batchFrames.increment();
        batchFramePackets.add(packets);
    }

    public long getBatchFrames() { return batchFrames.sum(); }
    public long getBatchFramePackets() { return batchFramePackets.sum(); }

    /** Average packets per batch frame. */
    public double getPacketsPerBatchFrame() {
        long frames = batchFrames.sum();
        return frames == 0 ? 0.0 : (double) batchFramePackets.sum() / (double) frames;
    }

    /** Average packets sent per consolidated flush. */
    public double getPacketsPerFlush() {
        long flushes = consolidatedFlushes.sum();
        return flushes == 0 ? 0.0 : (double) deferredPackets.sum() / (double) flushes;
    }

    /**
     * Share of flushes avoided compared with flushing every held packet on its own:
     * {@code 1 - flushes / packets}.
     */
    public double getFlushSavingRate() {
        long packets = deferredPackets.sum();
        return packets == 0 ? 0.0 : 1.0 - (double) consolidatedFlushes.sum() / (double) packets;
    }

    public long getSavedBytes() {
        return Math.max(0L, bytesSentOriginal.sum() - bytesSentWire.sum());
    }

    public double getAverageOriginalPacketBytes() {
        long sent = packetsSent.sum();
        return sent == 0 ? 0.0 : (double) bytesSentOriginal.sum() / (double) sent;
    }

    public double getAverageWirePacketBytes() {
        long sent = packetsSent.sum();
        return sent == 0 ? 0.0 : (double) bytesSentWire.sum() / (double) sent;
    }

    public double getAverageReceivedPacketBytes() {
        long received = packetsReceived.sum();
        return received == 0 ? 0.0 : (double) bytesReceived.sum() / (double) received;
    }

    public long getPacketsSent() {
        return packetsSent.sum();
    }

    public long getPacketsReceived() {
        return packetsReceived.sum();
    }

    public long getBytesSentOriginal() {
        return bytesSentOriginal.sum();
    }

    public long getBytesSentWire() {
        return bytesSentWire.sum();
    }

    public long getBytesReceived() {
        return bytesReceived.sum();
    }

    public long getElapsedSeconds() {
        return Math.max(1L, (System.currentTimeMillis() - startTimeMs) / 1000L);
    }

    public double getCompressionRatio() {
        long original = bytesSentOriginal.sum();
        long wire = bytesSentWire.sum();
        if (original == 0 || wire == 0) return 1.0;
        return (double) wire / (double) original;
    }

    public double getCompressionSavingPercent() {
        double ratio = getCompressionRatio();
        return (1.0 - ratio) * 100.0;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
