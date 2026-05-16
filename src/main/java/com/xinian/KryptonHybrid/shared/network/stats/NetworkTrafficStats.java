package com.xinian.KryptonHybrid.shared.network.stats;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

public final class NetworkTrafficStats {

    public static final NetworkTrafficStats INSTANCE = new NetworkTrafficStats();

    private static final class TypeStats {
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong totalBytes = new AtomicLong();

        public void record(int bytes) {
            count.incrementAndGet();
            totalBytes.addAndGet(bytes);
        }

        public long getCount() { return count.get(); }
        public long getTotalBytes() { return totalBytes.get(); }

        public double getAvgBytes() {
            long c = count.get();
            return c == 0 ? 0.0 : (double) totalBytes.get() / c;
        }
    }

    public record TrafficEntry(String key, long count, long totalBytes) {
        public double avgBytes() {
            return count == 0 ? 0.0 : (double) totalBytes / count;
        }
    }

    private final AtomicLong packetsSent = new AtomicLong();
    private final AtomicLong packetsReceived = new AtomicLong();
    private final AtomicLong bytesSentOriginal = new AtomicLong();
    private final AtomicLong bytesSentWire = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    // Bundle/coalesce hit-rate counters.
    private final AtomicLong bundlesEmitted = new AtomicLong();
    private final AtomicLong bundlePacketsTotal = new AtomicLong();
    private final AtomicLong bundleBatchesObserved = new AtomicLong();
    private final AtomicLong coalesceDroppedPackets = new AtomicLong();
    private final ConcurrentHashMap<String, TypeStats> perTypeStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TypeStats> perModStats  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TypeStats> perTypeWireStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TypeStats> perModWireStats  = new ConcurrentHashMap<>();
    private volatile long startTimeMs = System.currentTimeMillis();

    private NetworkTrafficStats() {}

    public void recordEncode(int originalSize, int wireSize) {
        packetsSent.incrementAndGet();
        bytesSentOriginal.addAndGet(originalSize);
        bytesSentWire.addAndGet(wireSize);
    }

    public void recordDecode(int payloadBytes) {
        packetsReceived.incrementAndGet();
        bytesReceived.addAndGet(payloadBytes);
    }

    public void recordPacketType(String key, int bytes) {
        recordInto(perTypeStats, key, bytes);
    }

    public void recordPacketMod(String modId, int bytes) {
        recordInto(perModStats, modId, bytes);
    }

    public void recordPacketWire(String key, String modId, int wireBytes) {
        recordInto(perTypeWireStats, key, wireBytes);
        recordInto(perModWireStats, modId, wireBytes);
    }

    private static void recordInto(ConcurrentHashMap<String, TypeStats> map, String key, int bytes) {
        map.computeIfAbsent(key, k -> new TypeStats()).record(bytes);
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
        packetsSent.set(0);
        packetsReceived.set(0);
        bytesSentOriginal.set(0);
        bytesSentWire.set(0);
        bytesReceived.set(0);
        bundlesEmitted.set(0);
        bundlePacketsTotal.set(0);
        bundleBatchesObserved.set(0);
        coalesceDroppedPackets.set(0);
        perTypeStats.clear();
        perModStats.clear();
        perTypeWireStats.clear();
        perModWireStats.clear();
        startTimeMs = System.currentTimeMillis();
    }

    // P1-??bundle/coalesce metric APIs

    /**
     * Records the result of one batch flush.
     * @param batchPlayers number of player connections that had ?? packet collected
     *                     (i.e. distinct keys in the batch map)
     * @param emittedBundles number of those players that ended up actually receiving
     *                       a {@link net.minecraft.network.protocol.game.ClientboundBundlePacket}
     *                       (i.e. ?? packets after coalescing)
     * @param packetsInBundles total sub-packet count across all emitted bundles
     */
    public void recordBundleBatch(int batchPlayers, int emittedBundles, int packetsInBundles) {
        if (batchPlayers > 0) bundleBatchesObserved.addAndGet(batchPlayers);
        if (emittedBundles > 0) bundlesEmitted.addAndGet(emittedBundles);
        if (packetsInBundles > 0) bundlePacketsTotal.addAndGet(packetsInBundles);
    }

    public void recordCoalesceDropped(int dropped) {
        if (dropped > 0) coalesceDroppedPackets.addAndGet(dropped);
    }

    public long getBundlesEmitted() { return bundlesEmitted.get(); }
    public long getBundlePacketsTotal() { return bundlePacketsTotal.get(); }
    public long getBundleBatchesObserved() { return bundleBatchesObserved.get(); }
    public long getCoalesceDroppedPackets() { return coalesceDroppedPackets.get(); }

    public double getAvgBundleSize() {
        long b = bundlesEmitted.get();
        return b == 0 ? 0.0 : (double) bundlePacketsTotal.get() / (double) b;
    }

    public double getBundleHitRate() {
        long obs = bundleBatchesObserved.get();
        return obs == 0 ? 0.0 : (double) bundlesEmitted.get() / (double) obs;
    }

    public long getSavedBytes() {
        return Math.max(0L, bytesSentOriginal.get() - bytesSentWire.get());
    }

    public double getAverageOriginalPacketBytes() {
        long sent = packetsSent.get();
        return sent == 0 ? 0.0 : (double) bytesSentOriginal.get() / (double) sent;
    }

    public double getAverageWirePacketBytes() {
        long sent = packetsSent.get();
        return sent == 0 ? 0.0 : (double) bytesSentWire.get() / (double) sent;
    }

    public double getAverageReceivedPacketBytes() {
        long received = packetsReceived.get();
        return received == 0 ? 0.0 : (double) bytesReceived.get() / (double) received;
    }

    public long getPacketsSent() {
        return packetsSent.get();
    }

    public long getPacketsReceived() {
        return packetsReceived.get();
    }

    public long getBytesSentOriginal() {
        return bytesSentOriginal.get();
    }

    public long getBytesSentWire() {
        return bytesSentWire.get();
    }

    public long getBytesReceived() {
        return bytesReceived.get();
    }

    public long getElapsedSeconds() {
        return Math.max(1L, (System.currentTimeMillis() - startTimeMs) / 1000L);
    }

    public double getCompressionRatio() {
        long original = bytesSentOriginal.get();
        long wire = bytesSentWire.get();
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
