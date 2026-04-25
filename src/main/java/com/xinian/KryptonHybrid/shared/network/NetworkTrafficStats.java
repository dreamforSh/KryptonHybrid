package com.xinian.KryptonHybrid.shared.network;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public final class NetworkTrafficStats {

    public static final NetworkTrafficStats INSTANCE = new NetworkTrafficStats();

    public static final class TypeStats {
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

    private final AtomicLong packetsSent = new AtomicLong();
    private final AtomicLong packetsReceived = new AtomicLong();
    private final AtomicLong bytesSentOriginal = new AtomicLong();
    private final AtomicLong bytesSentWire = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    // P1-⑤ Bundle/coalesce hit-rate counters
    private final AtomicLong bundlesEmitted = new AtomicLong();
    private final AtomicLong bundlePacketsTotal = new AtomicLong();
    private final AtomicLong bundleBatchesObserved = new AtomicLong();
    private final AtomicLong coalesceDroppedPackets = new AtomicLong();
    private final ConcurrentHashMap<String, TypeStats> perTypeStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TypeStats> perModStats  = new ConcurrentHashMap<>();
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
        perTypeStats.computeIfAbsent(key, k -> new TypeStats()).record(bytes);
    }

    public void recordPacketMod(String modId, int bytes) {
        perModStats.computeIfAbsent(modId, k -> new TypeStats()).record(bytes);
    }

    public List<Map.Entry<String, TypeStats>> getTopModsByCount(int limit) {
        return perModStats.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().getCount(), a.getValue().getCount()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    public List<Map.Entry<String, TypeStats>> getTopModsByBytes(int limit) {
        return perModStats.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().getTotalBytes(), a.getValue().getTotalBytes()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    public Map.Entry<String, TypeStats> getTopModByBytes() {
        return perModStats.entrySet().stream()
                .max((a, b) -> Long.compare(a.getValue().getTotalBytes(), b.getValue().getTotalBytes()))
                .orElse(null);
    }

    public int getTrackedModCount() {
        return perModStats.size();
    }

    public List<Map.Entry<String, TypeStats>> getTopByCount(int limit) {
        return perTypeStats.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().getCount(), a.getValue().getCount()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    public List<Map.Entry<String, TypeStats>> getTopByBytes(int limit) {
        return perTypeStats.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().getTotalBytes(), a.getValue().getTotalBytes()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    public Map.Entry<String, TypeStats> getTopTypeByBytes() {
        return perTypeStats.entrySet().stream()
                .max((a, b) -> Long.compare(a.getValue().getTotalBytes(), b.getValue().getTotalBytes()))
                .orElse(null);
    }

    public int getTrackedTypeCount() {
        return perTypeStats.size();
    }

    public long getTotalTrackedTypePackets() {
        return perTypeStats.values().stream().mapToLong(TypeStats::getCount).sum();
    }

    public long getTotalTrackedTypeBytes() {
        return perTypeStats.values().stream().mapToLong(TypeStats::getTotalBytes).sum();
    }

    public long getTotalTrackedModPackets() {
        return perModStats.values().stream().mapToLong(TypeStats::getCount).sum();
    }

    public long getTotalTrackedModBytes() {
        return perModStats.values().stream().mapToLong(TypeStats::getTotalBytes).sum();
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
        startTimeMs = System.currentTimeMillis();
    }

    // P1-⑤ bundle/coalesce metric APIs

    /**
     * Records the result of one batch flush.
     * @param batchPlayers number of player connections that had ≥1 packet collected
     *                     (i.e. distinct keys in the batch map)
     * @param emittedBundles number of those players that ended up actually receiving
     *                       a {@link net.minecraft.network.protocol.game.ClientboundBundlePacket}
     *                       (i.e. ≥2 packets after coalescing)
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

