package com.xinian.KryptonHybrid.shared.network.payload;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.stats.NetworkTrafficStats;
import com.xinian.KryptonHybrid.shared.network.security.SecurityMetrics;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client snapshot of {@link NetworkTrafficStats} used by the stats GUI
 * and the HUD. Sent in answer to a {@link StatsRequestPayload} and by
 * {@code /krypton stats gui}; a snapshot never opens the dashboard by itself
 * (see {@link OpenDashboardPayload}).
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
        long consolidatedFlushes,
        long deferredPackets,
        long batchedPackets,
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
        long secStatusRequestsDropped,
        long secLegacyQueriesDropped,
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
                        buf.writeVarLong(p.consolidatedFlushes);
                        buf.writeVarLong(p.deferredPackets);
                        buf.writeVarLong(p.batchedPackets);
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
                        buf.writeVarLong(p.secStatusRequestsDropped);
                        buf.writeVarLong(p.secLegacyQueriesDropped);
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
                        long consolidatedFlushes = buf.readVarLong();
                        long deferredPackets = buf.readVarLong();
                        long batchedPackets = buf.readVarLong();
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
                        long secStatusRequestsDropped = buf.readVarLong();
                        long secLegacyQueriesDropped = buf.readVarLong();
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
                                consolidatedFlushes, deferredPackets, batchedPackets,
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
                                secStatusRequestsDropped,
                                secLegacyQueriesDropped,
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
        NetworkTrafficStats.TrafficEntry hottestPacket = s.getTopTypeByBytes();
        NetworkTrafficStats.TrafficEntry hottestMod = s.getTopModByBytes();
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
                s.getConsolidatedFlushes(),
                s.getDeferredPackets(),
                s.getBatchedPackets(),
                s.getCoalesceDroppedPackets(),
                s.getTrackedTypeCount(),
                s.getTrackedModCount(),
                s.getTotalTrackedTypePackets(),
                s.getTotalTrackedTypeBytes(),
                s.getTotalTrackedModPackets(),
                s.getTotalTrackedModBytes(),
                trackedCoverage,
                hottestPacket != null ? hottestPacket.key() : "-",
                hottestPacket != null ? hottestPacket.count() : 0L,
                hottestPacket != null ? hottestPacket.totalBytes() : 0L,
                hottestMod != null ? hottestMod.key() : "-",
                hottestMod != null ? hottestMod.count() : 0L,
                hottestMod != null ? hottestMod.totalBytes() : 0L,
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
                sec.getStatusRequestsDropped(),
                sec.getLegacyQueriesDropped(),
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
                + secWatermarkBreaches
                + secStatusRequestsDropped
                + secLegacyQueriesDropped;
    }

    private static List<ModEntry> buildTopMods(NetworkTrafficStats s) {
        List<NetworkTrafficStats.TrafficEntry> top = s.getTopModsByBytes(MAX_TOP_MODS);
        List<ModEntry> out = new ArrayList<>(top.size());
        for (NetworkTrafficStats.TrafficEntry e : top) {
            out.add(new ModEntry(e.key(), e.count(), e.totalBytes()));
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

    /** Share of flushes avoided by flush consolidation: {@code 1 - flushes / held packets}. */
    public double flushSavingPercent() {
        return deferredPackets == 0 ? 0.0 : 100.0 * (1.0 - consolidatedFlushes / (double) deferredPackets);
    }

    /** Average packets sent per consolidated flush. */
    public double packetsPerFlush() {
        return consolidatedFlushes == 0 ? 0.0 : (double) deferredPackets / (double) consolidatedFlushes;
    }

    /** Share of collected tick packets that coalescing dropped before sending. */
    public double coalesceDropRatePercent() {
        long base = coalesceDroppedPackets + batchedPackets;
        return base == 0 ? 0.0 : 100.0 * coalesceDroppedPackets / (double) base;
    }
}
