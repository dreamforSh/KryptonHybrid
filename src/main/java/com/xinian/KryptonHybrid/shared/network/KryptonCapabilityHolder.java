package com.xinian.KryptonHybrid.shared.network;

import java.net.SocketAddress;

/**
 * Duck interface implemented on {@link net.minecraft.network.Connection} via mixin,
 * providing access to the per-connection {@link KryptonCapabilities} and the ability
 * to update the remote address (used by Velocity Modern Forwarding).
 */
public interface KryptonCapabilityHolder {

    /** Returns the per-connection Krypton capabilities (never null). */
    KryptonCapabilities krypton$getCapabilities();

    /**
     * Updates the connection's remote address.
     * Used by Velocity Modern Forwarding to set the player's real IP address
     * after verifying the forwarding data from the proxy.
     */
    void krypton$setAddress(SocketAddress address);
}

