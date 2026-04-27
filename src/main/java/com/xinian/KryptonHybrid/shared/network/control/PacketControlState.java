package com.xinian.KryptonHybrid.shared.network.control;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * Per-connection state for lightweight inbound packet-control decisions.
 */
public final class PacketControlState {
    public static final AttributeKey<PacketControlState> ATTR_KEY =
            AttributeKey.valueOf("krypton_packet_control_state");

    private volatile PacketControlPhase phase = PacketControlPhase.CONNECTED;
    private volatile boolean helloAvailable;
    private volatile boolean helloNegotiated;
    private volatile int remoteFeatureFlags;

    private PacketControlState() {}

    public static PacketControlState get(Channel channel) {
        PacketControlState state = channel.attr(ATTR_KEY).get();
        if (state == null) {
            state = new PacketControlState();
            channel.attr(ATTR_KEY).set(state);
        }
        return state;
    }

    public PacketControlPhase getPhase() {
        return this.phase;
    }

    public void setPhase(PacketControlPhase phase) {
        this.phase = phase;
    }

    public boolean isHelloNegotiated() {
        return this.helloNegotiated;
    }

    public int getRemoteFeatureFlags() {
        return this.remoteFeatureFlags;
    }

    public boolean supportsRemoteFeature(int featureFlag) {
        return this.helloNegotiated && (this.remoteFeatureFlags & featureFlag) != 0;
    }

    public boolean isHelloAvailable() {
        return this.helloAvailable;
    }

    public void markHelloAvailable() {
        this.helloAvailable = true;
    }

    public void markHelloNegotiated(int remoteFeatureFlags) {
        this.helloAvailable = true;
        this.helloNegotiated = true;
        this.remoteFeatureFlags = remoteFeatureFlags;
    }


}

