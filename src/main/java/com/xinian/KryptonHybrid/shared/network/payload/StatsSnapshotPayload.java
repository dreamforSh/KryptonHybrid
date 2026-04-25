package com.xinian.KryptonHybrid.shared.network.payload;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.NetworkTrafficStats;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

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
        double compressionRatio,
        double savingPercent,
        long bundlesEmitted,
        long bundlePacketsTotal,
        long bundleBatchesObserved,
        long coalesceDroppedPackets,
        String compressionAlgorithm
) implements CustomPacketPayload {

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
                        buf.writeDouble(p.compressionRatio);
                        buf.writeDouble(p.savingPercent);
                        buf.writeVarLong(p.bundlesEmitted);
                        buf.writeVarLong(p.bundlePacketsTotal);
                        buf.writeVarLong(p.bundleBatchesObserved);
                        buf.writeVarLong(p.coalesceDroppedPackets);
                        buf.writeUtf(p.compressionAlgorithm, 32);
                    },
                    buf -> new StatsSnapshotPayload(
                            buf.readVarLong(),
                            buf.readVarLong(),
                            buf.readVarLong(),
                            buf.readVarLong(),
                            buf.readVarLong(),
                            buf.readVarLong(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readVarLong(),
                            buf.readVarLong(),
                            buf.readVarLong(),
                            buf.readVarLong(),
                            buf.readUtf(32)
                    )
            );

    @Override
    public Type<StatsSnapshotPayload> type() {
        return TYPE;
    }

    /** Builds a snapshot from current server-side counters. */
    public static StatsSnapshotPayload current() {
        NetworkTrafficStats s = NetworkTrafficStats.INSTANCE;
        return new StatsSnapshotPayload(
                s.getElapsedSeconds(),
                s.getPacketsSent(),
                s.getPacketsReceived(),
                s.getBytesSentOriginal(),
                s.getBytesSentWire(),
                s.getBytesReceived(),
                s.getCompressionRatio(),
                s.getCompressionSavingPercent(),
                s.getBundlesEmitted(),
                s.getBundlePacketsTotal(),
                s.getBundleBatchesObserved(),
                s.getCoalesceDroppedPackets(),
                KryptonConfig.compressionAlgorithm.name()
        );
    }
}

