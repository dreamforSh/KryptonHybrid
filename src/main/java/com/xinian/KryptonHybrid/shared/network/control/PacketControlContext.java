package com.xinian.KryptonHybrid.shared.network.control;

import io.netty.channel.Channel;

/**
 * Thread-local encode context for packet serializers that do not receive a Netty context directly.
 */
public final class PacketControlContext {
    private static final ThreadLocal<Channel> CURRENT_CHANNEL = new ThreadLocal<>();

    private PacketControlContext() {}

    public static void setCurrentChannel(Channel channel) {
        CURRENT_CHANNEL.set(channel);
    }

    public static Channel getCurrentChannel() {
        return CURRENT_CHANNEL.get();
    }

    public static void clearCurrentChannel() {
        CURRENT_CHANNEL.remove();
    }
}
