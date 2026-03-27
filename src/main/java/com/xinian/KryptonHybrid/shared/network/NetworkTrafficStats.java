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

    public int getTrackedTypeCount() {
        return perTypeStats.size();
    }

    public long getTotalTrackedTypePackets() {
        return perTypeStats.values().stream().mapToLong(TypeStats::getCount).sum();
    }

    public long getTotalTrackedTypeBytes() {
        return perTypeStats.values().stream().mapToLong(TypeStats::getTotalBytes).sum();
    }

    public void reset() {
        packetsSent.set(0);
        packetsReceived.set(0);
        bytesSentOriginal.set(0);
        bytesSentWire.set(0);
        bytesReceived.set(0);
        perTypeStats.clear();
        perModStats.clear();
        startTimeMs = System.currentTimeMillis();
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

