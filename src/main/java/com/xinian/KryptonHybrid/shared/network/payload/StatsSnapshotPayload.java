package com.xinian.KryptonHybrid.shared.network.payload;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.NetworkTrafficStats;
import com.xinian.KryptonHybrid.shared.network.security.SecurityMetrics;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Server → client snapshot of {@link NetworkTrafficStats} used to drive the
 * {@code KryptonStatsScreen} GUI on the client.
 *
 * <p>Sent in response to {@code /krypton stats gui} so the player can see
 * real-time bandwidth, compression and bundle metrics in a graphical view.</p>
 */
public record StatsSnapshotPayload(
        long elapsedSeconds,
        long packetsSent,
        long packetsReceived,
        long bytesSentOriginal,
        long bytesSentWire,
        long bytesReceived,
        long savedBytes,
        double compressionRatio,
        double savingPercent,
        long bundlesEmitted,
        long bundlePacketsTotal,
        long bundleBatchesObserved,
        long coalesceDroppedPackets,
        int trackedTypeCount,
        int trackedModCount,
        long totalTrackedTypePackets,
        long totalTrackedTypeBytes,
        long totalTrackedModPackets,
        long totalTrackedModBytes,
        double trackedCoveragePercent,
        String hottestPacketType,
        long hottestPacketTypePackets,
        long hottestPacketTypeBytes,
        String hottestModId,
        long hottestModPackets,
        long hottestModBytes,
        String compressionAlgorithm,
        boolean securityEnabled,
        long secConnectionsRateLimited,
        long secPacketsSizeRejected,
        long secReadLimitRejected,
        long secNullFramesDropped,
        long secDecompressionBombs,
        long secHandshakesRejected,
        long secTimeouts,
        long secAnomalyEvents,
        long secAnomalyDisconnects,
        long secWritesDropped,
        long secWatermarkBreaches,
        List<ModEntry> topMods
) implements CustomPacketPayload {

    /** Maximum number of per-mod entries shipped to the client. */
    public static final int MAX_TOP_MODS = 64;

    /** Per-mod traffic record (used for the GUI "Mods" tab). */
    public record ModEntry(String modId, long packets, long bytes) {}

    public static final Type<StatsSnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("krypton_hybrid", "stats_snapshot"));

    public static final StreamCodec<FriendlyByteBuf, StatsSnapshotPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarLong(p.elapsedSeconds);
                        buf.writeVarLong(p.packetsSent);
                        buf.writeVarLong(p.packetsReceived);
                        buf.writeVarLong(p.bytesSentOriginal);
                        buf.writeVarLong(p.bytesSentWire);
                        buf.writeVarLong(p.bytesReceived);
                        buf.writeVarLong(p.savedBytes);
                        buf.writeDouble(p.compressionRatio);
                        buf.writeDouble(p.savingPercent);
                        buf.writeVarLong(p.bundlesEmitted);
                        buf.writeVarLong(p.bundlePacketsTotal);
                        buf.writeVarLong(p.bundleBatchesObserved);
                        buf.writeVarLong(p.coalesceDroppedPackets);
                        buf.writeVarInt(p.trackedTypeCount);
                        buf.writeVarInt(p.trackedModCount);
                        buf.writeVarLong(p.totalTrackedTypePackets);
                        buf.writeVarLong(p.totalTrackedTypeBytes);
                        buf.writeVarLong(p.totalTrackedModPackets);
                        buf.writeVarLong(p.totalTrackedModBytes);
                        buf.writeDouble(p.trackedCoveragePercent);
                        buf.writeUtf(p.hottestPacketType, 256);
                        buf.writeVarLong(p.hottestPacketTypePackets);
                        buf.writeVarLong(p.hottestPacketTypeBytes);
                        buf.writeUtf(p.hottestModId, 128);
                        buf.writeVarLong(p.hottestModPackets);
                        buf.writeVarLong(p.hottestModBytes);
                        buf.writeUtf(p.compressionAlgorithm, 32);
                        buf.writeBoolean(p.securityEnabled);
                        buf.writeVarLong(p.secConnectionsRateLimited);
                        buf.writeVarLong(p.secPacketsSizeRejected);
                        buf.writeVarLong(p.secReadLimitRejected);
                        buf.writeVarLong(p.secNullFramesDropped);
                        buf.writeVarLong(p.secDecompressionBombs);
                        buf.writeVarLong(p.secHandshakesRejected);
                        buf.writeVarLong(p.secTimeouts);
                        buf.writeVarLong(p.secAnomalyEvents);
                        buf.writeVarLong(p.secAnomalyDisconnects);
                        buf.writeVarLong(p.secWritesDropped);
                        buf.writeVarLong(p.secWatermarkBreaches);
                        int n = Math.min(p.topMods.size(), MAX_TOP_MODS);
                        buf.writeVarInt(n);
                        for (int i = 0; i < n; i++) {
                            ModEntry e = p.topMods.get(i);
                            buf.writeUtf(e.modId, 128);
                            buf.writeVarLong(e.packets);
                            buf.writeVarLong(e.bytes);
                        }
                    },
                    buf -> {
                        long elapsedSeconds = buf.readVarLong();
                        long packetsSent = buf.readVarLong();
                        long packetsReceived = buf.readVarLong();
                        long bytesSentOriginal = buf.readVarLong();
                        long bytesSentWire = buf.readVarLong();
                        long bytesReceived = buf.readVarLong();
                        long savedBytes = buf.readVarLong();
                        double compressionRatio = buf.readDouble();
                        double savingPercent = buf.readDouble();
                        long bundlesEmitted = buf.readVarLong();
                        long bundlePacketsTotal = buf.readVarLong();
                        long bundleBatchesObserved = buf.readVarLong();
                        long coalesceDroppedPackets = buf.readVarLong();
                        int trackedTypeCount = buf.readVarInt();
                        int trackedModCount = buf.readVarInt();
                        long totalTrackedTypePackets = buf.readVarLong();
                        long totalTrackedTypeBytes = buf.readVarLong();
                        long totalTrackedModPackets = buf.readVarLong();
                        long totalTrackedModBytes = buf.readVarLong();
                        double trackedCoveragePercent = buf.readDouble();
                        String hottestPacketType = buf.readUtf(256);
                        long hottestPacketTypePackets = buf.readVarLong();
                        long hottestPacketTypeBytes = buf.readVarLong();
                        String hottestModId = buf.readUtf(128);
                        long hottestModPackets = buf.readVarLong();
                        long hottestModBytes = buf.readVarLong();
                        String compressionAlgorithm = buf.readUtf(32);
                        boolean securityEnabled = buf.readBoolean();
                        long secConnectionsRateLimited = buf.readVarLong();
                        long secPacketsSizeRejected = buf.readVarLong();
                        long secReadLimitRejected = buf.readVarLong();
                        long secNullFramesDropped = buf.readVarLong();
                        long secDecompressionBombs = buf.readVarLong();
                        long secHandshakesRejected = buf.readVarLong();
                        long secTimeouts = buf.readVarLong();
                        long secAnomalyEvents = buf.readVarLong();
                        long secAnomalyDisconnects = buf.readVarLong();
                        long secWritesDropped = buf.readVarLong();
                        long secWatermarkBreaches = buf.readVarLong();
                        int n = Math.min(buf.readVarInt(), MAX_TOP_MODS);
                        List<ModEntry> mods = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            String id = buf.readUtf(128);
                            long pk = buf.readVarLong();
                            long by = buf.readVarLong();
                            mods.add(new ModEntry(id, pk, by));
                        }
                        return new StatsSnapshotPayload(
                                elapsedSeconds, packetsSent, packetsReceived,
                                bytesSentOriginal, bytesSentWire, bytesReceived,
                                savedBytes, compressionRatio, savingPercent,
                                bundlesEmitted, bundlePacketsTotal, bundleBatchesObserved,
                                coalesceDroppedPackets,
                                trackedTypeCount, trackedModCount,
                                totalTrackedTypePackets, totalTrackedTypeBytes,
                                totalTrackedModPackets, totalTrackedModBytes,
                                trackedCoveragePercent,
                                hottestPacketType, hottestPacketTypePackets, hottestPacketTypeBytes,
                                hottestModId, hottestModPackets, hottestModBytes,
                                compressionAlgorithm,
                                securityEnabled,
                                secConnectionsRateLimited,
                                secPacketsSizeRejected,
                                secReadLimitRejected,
                                secNullFramesDropped,
                                secDecompressionBombs,
                                secHandshakesRejected,
                                secTimeouts,
                                secAnomalyEvents,
                                secAnomalyDisconnects,
                                secWritesDropped,
                                secWatermarkBreaches,
                                mods);
                    }
            );

    @Override
    public Type<StatsSnapshotPayload> type() {
        return TYPE;
    }

    /** Builds a snapshot from current server-side counters. */
    public static StatsSnapshotPayload current() {
        NetworkTrafficStats s = NetworkTrafficStats.INSTANCE;
        Map.Entry<String, NetworkTrafficStats.TypeStats> hottestPacket = s.getTopTypeByBytes();
        Map.Entry<String, NetworkTrafficStats.TypeStats> hottestMod = s.getTopModByBytes();
        SecurityMetrics sec = SecurityMetrics.INSTANCE;
        long totalTrackedPackets = s.getTotalTrackedTypePackets();
        double trackedCoverage = s.getPacketsSent() == 0 ? 0.0 : 100.0 * totalTrackedPackets / (double) s.getPacketsSent();

        return new StatsSnapshotPayload(
                s.getElapsedSeconds(),
                s.getPacketsSent(),
                s.getPacketsReceived(),
                s.getBytesSentOriginal(),
                s.getBytesSentWire(),
                s.getBytesReceived(),
                s.getSavedBytes(),
                s.getCompressionRatio(),
                s.getCompressionSavingPercent(),
                s.getBundlesEmitted(),
                s.getBundlePacketsTotal(),
                s.getBundleBatchesObserved(),
                s.getCoalesceDroppedPackets(),
                s.getTrackedTypeCount(),
                s.getTrackedModCount(),
                s.getTotalTrackedTypePackets(),
                s.getTotalTrackedTypeBytes(),
                s.getTotalTrackedModPackets(),
                s.getTotalTrackedModBytes(),
                trackedCoverage,
                hottestPacket != null ? hottestPacket.getKey() : "-",
                hottestPacket != null ? hottestPacket.getValue().getCount() : 0L,
                hottestPacket != null ? hottestPacket.getValue().getTotalBytes() : 0L,
                hottestMod != null ? hottestMod.getKey() : "-",
                hottestMod != null ? hottestMod.getValue().getCount() : 0L,
                hottestMod != null ? hottestMod.getValue().getTotalBytes() : 0L,
                KryptonConfig.compressionAlgorithm.name(),
                KryptonConfig.securityEnabled,
                sec.getConnectionsRateLimited(),
                sec.getPacketsSizeRejected(),
                sec.getReadLimitRejected(),
                sec.getNullFramesDropped(),
                sec.getDecompressionBombs(),
                sec.getHandshakesRejected(),
                sec.getTimeouts(),
                sec.getAnomalyEvents(),
                sec.getAnomalyDisconnects(),
                sec.getWritesDropped(),
                sec.getWatermarkBreaches(),
                buildTopMods(s)
        );
    }

    public long securityTotalEvents() {
        return secConnectionsRateLimited
                + secPacketsSizeRejected
                + secReadLimitRejected
                + secNullFramesDropped
                + secDecompressionBombs
                + secHandshakesRejected
                + secTimeouts
                + secAnomalyEvents
                + secAnomalyDisconnects
                + secWritesDropped
                + secWatermarkBreaches;
    }

    private static List<ModEntry> buildTopMods(NetworkTrafficStats s) {
        List<Map.Entry<String, NetworkTrafficStats.TypeStats>> top = s.getTopModsByBytes(MAX_TOP_MODS);
        List<ModEntry> out = new ArrayList<>(top.size());
        for (Map.Entry<String, NetworkTrafficStats.TypeStats> e : top) {
            out.add(new ModEntry(e.getKey(), e.getValue().getCount(), e.getValue().getTotalBytes()));
        }
        return out;
    }

    public double packetsSentPerSecond() {
        return packetsSent / (double) Math.max(1L, elapsedSeconds);
    }

    public double packetsReceivedPerSecond() {
        return packetsReceived / (double) Math.max(1L, elapsedSeconds);
    }

    public long originalBytesPerSecond() {
        return bytesSentOriginal / Math.max(1L, elapsedSeconds);
    }

    public long wireBytesPerSecond() {
        return bytesSentWire / Math.max(1L, elapsedSeconds);
    }

    public long receivedBytesPerSecond() {
        return bytesReceived / Math.max(1L, elapsedSeconds);
    }

    public double averageOriginalPacketBytes() {
        return packetsSent == 0 ? 0.0 : (double) bytesSentOriginal / (double) packetsSent;
    }

    public double averageWirePacketBytes() {
        return packetsSent == 0 ? 0.0 : (double) bytesSentWire / (double) packetsSent;
    }

    public double averageReceivedPacketBytes() {
        return packetsReceived == 0 ? 0.0 : (double) bytesReceived / (double) packetsReceived;
    }

    public double bundleHitRatePercent() {
        return bundleBatchesObserved == 0 ? 0.0 : 100.0 * bundlesEmitted / (double) bundleBatchesObserved;
    }

    public double averageBundleSize() {
        return bundlesEmitted == 0 ? 0.0 : (double) bundlePacketsTotal / (double) bundlesEmitted;
    }

    public double coalesceDropRatePercent() {
        long base = coalesceDroppedPackets + bundlePacketsTotal;
        return base == 0 ? 0.0 : 100.0 * coalesceDroppedPackets / (double) base;
    }
}

