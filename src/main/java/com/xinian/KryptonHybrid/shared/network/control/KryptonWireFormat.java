package com.xinian.KryptonHybrid.shared.network.control;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import com.xinian.KryptonHybrid.shared.network.KryptonHelloPayload;

/**
 * Central checks for connection-scoped Krypton wire-format capability.
 */
public final class KryptonWireFormat {
    private KryptonWireFormat() {}

    public static boolean canWriteCurrentConnection() {
        Channel channel = PacketControlContext.getCurrentChannel();
        return channel != null && canWrite(channel);
    }

    public static boolean canWriteCurrentChunkData() {
        return canWriteCurrentFeature(KryptonHelloPayload.FEATURE_CHUNK_DATA);
    }

    public static boolean canWriteCurrentLightData() {
        return canWriteCurrentFeature(KryptonHelloPayload.FEATURE_LIGHT_DATA);
    }

    public static boolean canWriteBlockEntityDelta(Connection connection) {
        return canWriteFeature(connection, KryptonHelloPayload.FEATURE_BLOCK_ENTITY_DELTA);
    }

    public static boolean canWrite(Connection connection) {
        return connection != null && canWrite(connection.channel());
    }

    public static boolean canWrite(Channel channel) {
        return channel != null && PacketControlState.get(channel).isHelloNegotiated();
    }

    private static boolean canWriteCurrentFeature(int featureFlag) {
        Channel channel = PacketControlContext.getCurrentChannel();
        return channel != null && canWriteFeature(channel, featureFlag);
    }

    private static boolean canWriteFeature(Connection connection, int featureFlag) {
        return connection != null && canWriteFeature(connection.channel(), featureFlag);
    }

    private static boolean canWriteFeature(Channel channel, int featureFlag) {
        return channel != null && PacketControlState.get(channel).supportsRemoteFeature(featureFlag);
    }
}
